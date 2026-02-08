package engine.render.renderer;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_BACK;
import static org.lwjgl.opengl.GL11.glCullFace;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_LEQUAL;
import static org.lwjgl.opengl.GL11.GL_LESS;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_NONE;
import static org.lwjgl.opengl.GL11.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glColorMask;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glDepthFunc;
import static org.lwjgl.opengl.GL11.glDepthMask;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glDrawBuffer;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12.glTexImage3D;
import static org.lwjgl.opengl.GL14.GL_TEXTURE_COMPARE_FUNC;
import static org.lwjgl.opengl.GL14.GL_TEXTURE_COMPARE_MODE;
import static org.lwjgl.opengl.GL30.GL_COMPARE_REF_TO_TEXTURE;
import static org.lwjgl.opengl.GL30.GL_DEPTH_ATTACHMENT;
import static org.lwjgl.opengl.GL30.GL_DEPTH_COMPONENT32F;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glCheckFramebufferStatus;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30.glFramebufferTextureLayer;
import static org.lwjgl.opengl.GL30.glGenFramebuffers;

import engine.core.config.RenderConfig;
import engine.render.Shader;
import engine.render.camera.Camera;
import engine.render.camera.PerspectiveCamera;
import engine.render.core.RenderContext;
import engine.render.shadow.CSMCalculator;
import engine.render.shadow.CascadeData;
import engine.util.CSMDebugLog;

/**
 * Renderer responsible for Cascaded Shadow Mapping (CSM).
 * Renders depth maps from light's perspective for each cascade.
 * Uses stabilized fit algorithm for shimmer-free shadows.
 */
public class ShadowRenderer implements Renderer {

    private final int fbo;
    private final int shadowMapArray;  // GL_TEXTURE_2D_ARRAY for all cascades
    private final Shader depthShader;
    private final CSMCalculator csmCalculator;

    // Per-frame cascade data (accessible by VoxelRenderer)
    private CascadeData cascadeData;

    // Viewport to restore after shadow pass
    private int savedViewportWidth;
    private int savedViewportHeight;

    // Temporary vectors for camera basis computation
    private final Vector3f camForward = new Vector3f();
    private final Vector3f camRight = new Vector3f();
    private final Vector3f camUp = new Vector3f();

    // ═══════════════════════════════════════════════════════════════════════════
    // FREEZE MODE SYSTEM (F5 key)
    // ═══════════════════════════════════════════════════════════════════════════
    // 0 = OFF:    matrices update + depth pass runs (normal operation)
    // 1 = MATRIX: matrices frozen, depth pass still runs with frozen matrices
    // 2 = MAP:    matrices update normally, depth pass skipped (shadow map frozen)
    // 3 = FULL:   matrices frozen AND depth pass skipped
    private int freezeMode = 0;
    private static final int FREEZE_MODE_OFF = 0;
    private static final int FREEZE_MODE_MATRIX = 1;
    private static final int FREEZE_MODE_MAP = 2;
    private static final int FREEZE_MODE_FULL = 3;
    private static final int FREEZE_MODE_COUNT = 4;

    // Frozen cascade data (captured when entering MATRIX or FULL mode)
    private CascadeData frozenCascadeData;

    // Logging for verification
    private long lastLogTime = 0;
    private static final long LOG_INTERVAL_MS = 1000;

    /**
     * Create a new shadow renderer with CSM support.
     *
     * @param depthShader Shader for depth-only rendering
     */
    public ShadowRenderer(Shader depthShader) {
        this.depthShader = depthShader;
        this.csmCalculator = new CSMCalculator();

        int cascadeCount = RenderConfig.CSM_CASCADE_COUNT;
        int shadowMapSize = RenderConfig.CSM_SHADOW_MAP_SIZE;

        // Create depth texture array for all cascades
        shadowMapArray = glGenTextures();
        glBindTexture(GL_TEXTURE_2D_ARRAY, shadowMapArray);

        // Allocate storage for all cascade layers
        glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_DEPTH_COMPONENT32F,
                shadowMapSize, shadowMapSize, cascadeCount,
                0, GL_DEPTH_COMPONENT, GL_FLOAT, (java.nio.ByteBuffer) null);

        // Linear filtering enables hardware-accelerated PCF when using sampler2DArrayShadow
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        // Use CLAMP_TO_EDGE to avoid artifacts at cascade boundaries
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        // Enable depth comparison for shadow sampling
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);

        // Create FBO (layer binding happens per-cascade in render loop)
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);

        // Attach first layer to validate FBO completeness
        glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, shadowMapArray, 0, 0);
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);

        // Validate framebuffer
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Shadow framebuffer incomplete: " + status);
        }

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
    }

    @Override
    public void render(RenderContext context) {
        // ═══════════════════════════════════════════════════════════════════════
        // FULL FREEZE: Skip entire render pass (matrices frozen + map frozen)
        // ═══════════════════════════════════════════════════════════════════════
        if (freezeMode == FREEZE_MODE_FULL) {
            // Use frozen cascade data, skip everything
            cascadeData = frozenCascadeData;
            logFrozenState("FULL");
            return;
        }

        // ═══════════════════════════════════════════════════════════════════════
        // COMPUTE CAMERA BASIS (needed for matrix computation)
        // ═══════════════════════════════════════════════════════════════════════
        Camera camera = context.getCamera();
        Matrix4f view;
        if (camera instanceof PerspectiveCamera pc) {
            view = pc.getViewMatrix();
        } else {
            view = camera.getViewMatrix(camera.getPosition(), 0, 0);
        }
        computeCameraBasisFromViewMatrix(view);

        // CSM instrumentation: frame counter and basis logging
        CSMDebugLog.get().nextFrame();
        if (CSMDebugLog.get().shouldLog()) {
            float yaw = 0, pitch = 0;
            if (camera instanceof PerspectiveCamera pc && pc.getPlayer() != null) {
                yaw = pc.getPlayer().getYaw();
                pitch = pc.getPlayer().getPitch();
            }
            CSMDebugLog.get().logBasis(yaw, pitch, camForward, camRight, camUp);
        }

        // ═══════════════════════════════════════════════════════════════════════
        // COMPUTE CASCADE MATRICES
        // ═══════════════════════════════════════════════════════════════════════
        // MATRIX/FULL modes use frozen data; OFF/MAP modes compute fresh
        boolean usesFrozenMatrices = (freezeMode == FREEZE_MODE_MATRIX);

        if (usesFrozenMatrices) {
            cascadeData = frozenCascadeData;
            logFrozenState("MATRIX");
        } else {
            // Get sun direction
            Vector3f sunDir = context.getSunDirection();
            if (sunDir == null) {
                sunDir = context.getWorld().getTimeManager().getSunData().sunDir();
            }

            // Update light basis from sun direction
            csmCalculator.updateLightBasis(sunDir);

            // Compute fresh cascade matrices
            cascadeData = csmCalculator.computeCascadeMatrices(camera, camForward, camRight, camUp);

            if (freezeMode == FREEZE_MODE_MAP) {
                logFrozenState("MAP");
            }
        }

        // ═══════════════════════════════════════════════════════════════════════
        // MAP FREEZE: Skip depth pass only (matrices update, shadow map frozen)
        // ═══════════════════════════════════════════════════════════════════════
        if (freezeMode == FREEZE_MODE_MAP) {
            // Matrices are updated (cascadeData is fresh), but don't render depth pass
            // Shadow map stays exactly as it was when MAP mode was entered
            // NOTE: No GL state changes needed since we're not touching the FBO
            return;
        }

        // ═══════════════════════════════════════════════════════════════════════
        // DEPTH PASS: Render shadow map (OFF and MATRIX modes)
        // ═══════════════════════════════════════════════════════════════════════
        // Save current viewport
        savedViewportWidth = context.getWindowWidth();
        savedViewportHeight = context.getWindowHeight();

        // === BEGIN SHADOW PASS STATE SETUP ===
        glDisable(GL_BLEND);
        glDisable(GL_SCISSOR_TEST);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);
        glDepthMask(true);
        glColorMask(false, false, false, false);

        // Standard back-face culling for shadow pass
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);

        // Set shadow map viewport
        int shadowMapSize = RenderConfig.CSM_SHADOW_MAP_SIZE;
        glViewport(0, 0, shadowMapSize, shadowMapSize);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);

        depthShader.bind();

        // Render each cascade
        int cascadeCount = cascadeData.cascadeCount();
        for (int i = 0; i < cascadeCount; i++) {
            // Bind this cascade's layer to the FBO
            glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, shadowMapArray, 0, i);

            // Verify FBO status for each layer
            int fboStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            if (fboStatus != GL_FRAMEBUFFER_COMPLETE) {
                System.err.println("[Shadow] FBO incomplete for cascade " + i + ": " + fboStatus);
            }

            // Clear depth buffer for this cascade
            glClear(GL_DEPTH_BUFFER_BIT);

            // Set light space matrix for this cascade
            depthShader.setMat4("lightSpaceMatrix", cascadeData.lightSpaceMatrices()[i]);

            // Render all shadow casters
            context.getWorld().renderDepth(depthShader);
        }

        depthShader.unbind();

        // === END SHADOW PASS - RESTORE STATE ===
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, savedViewportWidth, savedViewportHeight);
        glColorMask(true, true, true, true);
        glDepthFunc(GL_LESS);
    }

    /**
     * Compute camera forward, right, and up vectors from view matrix.
     */
    private void computeCameraBasisFromViewMatrix(Matrix4f view) {
        // Invert view matrix: world->view becomes view->world (camera-to-world)
        Matrix4f invView = new Matrix4f(view).invert();

        // Extract camera axes from COLUMNS of invView
        camRight.set(invView.m00(), invView.m01(), invView.m02()).normalize();
        camUp.set(invView.m10(), invView.m11(), invView.m12()).normalize();
        camForward.set(-invView.m20(), -invView.m21(), -invView.m22()).normalize();

        // Ensure orthonormality
        camRight.cross(camForward, camUp).normalize();
    }

    /**
     * Log frozen state once per second for verification.
     */
    private void logFrozenState(String mode) {
        long now = System.currentTimeMillis();
        if (now - lastLogTime >= LOG_INTERVAL_MS) {
            lastLogTime = now;
            if (cascadeData != null && cascadeData.lightSpaceMatrices().length > 0) {
                Matrix4f m = cascadeData.lightSpaceMatrices()[0];
                System.out.printf("[Shadow Freeze: %s] Cascade 0: m00=%.4f m11=%.4f m22=%.4f%n",
                        mode, m.m00(), m.m11(), m.m22());
            }
        }
    }

    @Override
    public void cleanup() {
        depthShader.cleanup();
        if (shadowMapArray != 0) {
            glDeleteTextures(shadowMapArray);
        }
        if (fbo != 0) {
            glDeleteFramebuffers(fbo);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC ACCESSORS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get cascade data for the current frame.
     */
    public CascadeData getCascadeData() {
        return cascadeData;
    }

    /**
     * Get shadow map texture array ID.
     */
    public int getShadowMapArray() {
        return shadowMapArray;
    }

    /**
     * Get the first cascade's light space matrix for backward compatibility.
     * @deprecated Use getCascadeData().lightSpaceMatrices() instead
     */
    @Deprecated
    public Matrix4f getLightSpaceMatrix() {
        if (cascadeData != null && cascadeData.lightSpaceMatrices().length > 0) {
            return cascadeData.lightSpaceMatrices()[0];
        }
        return new Matrix4f();
    }

    /**
     * Get depth texture ID for backward compatibility.
     * @deprecated Use getShadowMapArray() instead
     */
    @Deprecated
    public int getDepthTexture() {
        return shadowMapArray;
    }

    /**
     * Get cascade count for debug display.
     */
    public int getCascadeCount() {
        return RenderConfig.CSM_CASCADE_COUNT;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FREEZE MODE CONTROLS (F5 key)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Cycle through freeze modes: OFF -> MATRIX -> MAP -> FULL -> OFF
     *
     * Diagnostic purpose:
     * - MATRIX: If shimmer persists, issue is in sampling/bias/projection
     * - MAP: If shimmer disappears, issue is in depth pass (shadow map content changing)
     * - FULL: Baseline comparison (everything frozen)
     */
    public void cycleFreezeMode() {
        int previousMode = freezeMode;
        freezeMode = (freezeMode + 1) % FREEZE_MODE_COUNT;

        // Capture cascade data when entering a mode that needs frozen matrices
        if ((freezeMode == FREEZE_MODE_MATRIX || freezeMode == FREEZE_MODE_FULL)
                && previousMode != FREEZE_MODE_MATRIX && previousMode != FREEZE_MODE_FULL) {
            captureFrozenCascadeData();
        }

        // Clear frozen data when returning to OFF
        if (freezeMode == FREEZE_MODE_OFF) {
            frozenCascadeData = null;
        }

        // Print status
        String[] modeNames = {"OFF", "MATRIX", "MAP", "FULL"};
        System.out.println("Shadow Freeze Mode: " + modeNames[freezeMode]);

        // Print diagnostic hint
        switch (freezeMode) {
            case FREEZE_MODE_MATRIX:
                System.out.println("  Matrices frozen, depth pass runs.");
                System.out.println("  If shimmer persists: issue is sampling/bias/projection.");
                break;
            case FREEZE_MODE_MAP:
                System.out.println("  Matrices update, depth pass skipped.");
                System.out.println("  If shimmer disappears: issue is depth pass instability.");
                break;
            case FREEZE_MODE_FULL:
                System.out.println("  Both frozen (baseline reference).");
                break;
            case FREEZE_MODE_OFF:
                System.out.println("  Normal operation.");
                break;
        }
    }

    /**
     * Capture current cascade data for freeze modes.
     */
    private void captureFrozenCascadeData() {
        if (cascadeData == null) {
            System.err.println("[Shadow] Warning: No cascade data to freeze");
            return;
        }

        // Deep copy cascade data
        frozenCascadeData = new CascadeData(
                cascadeData.lightSpaceMatrices().clone(),
                cascadeData.splitDistances().clone(),
                cascadeData.sphereRadii().clone(),
                cascadeData.texelSizes().clone()
        );

        // Clone each matrix
        for (int i = 0; i < frozenCascadeData.lightSpaceMatrices().length; i++) {
            frozenCascadeData.lightSpaceMatrices()[i] = new Matrix4f(cascadeData.lightSpaceMatrices()[i]);
        }

        System.out.println("  [Captured cascade data at frame]");
    }

    /**
     * Get current freeze mode (0-3).
     */
    public int getFreezeMode() {
        return freezeMode;
    }

    /**
     * Get current freeze mode as a string for UI display.
     */
    public String getFreezeModeString() {
        String[] modeNames = {"OFF", "MATRIX", "MAP", "FULL"};
        return "Shadow Freeze: " + modeNames[freezeMode];
    }

    /**
     * Check if matrices are frozen (MATRIX or FULL mode).
     */
    public boolean isMatrixFrozen() {
        return freezeMode == FREEZE_MODE_MATRIX || freezeMode == FREEZE_MODE_FULL;
    }

    /**
     * Check if shadow map is frozen (MAP or FULL mode).
     */
    public boolean isShadowMapFrozen() {
        return freezeMode == FREEZE_MODE_MAP || freezeMode == FREEZE_MODE_FULL;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CSM DIAGNOSTIC FREEZE CONTROLS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Toggle light basis freeze for diagnostic testing.
     * When frozen, the light basis vectors (lightDir, lightRight, lightUp) are captured
     * and reused, isolating whether light basis changes cause shimmer.
     */
    public void toggleFreezeLightBasis() {
        boolean newState = !csmCalculator.isFreezeLightBasis();
        csmCalculator.setFreezeLightBasis(newState);
    }

    /**
     * Toggle snapped XY freeze for diagnostic testing.
     * When frozen, the texel-snapped XY coordinates are captured and reused,
     * isolating whether snap grid changes cause shimmer.
     */
    public void toggleFreezeSnappedXY() {
        boolean newState = !csmCalculator.isFreezeSnappedXY();
        csmCalculator.setFreezeSnappedXY(newState);
    }

    /**
     * Toggle Z range freeze for diagnostic testing.
     * When frozen, the minZ/maxZ depth range is captured and reused,
     * isolating whether Z range changes cause shimmer.
     */
    public void toggleFreezeZRange() {
        boolean newState = !csmCalculator.isFreezeZRange();
        csmCalculator.setFreezeZRange(newState);
    }

    /**
     * Get diagnostic freeze state string for UI display.
     */
    public String getDiagnosticFreezeString() {
        StringBuilder sb = new StringBuilder("CSM Diag: ");
        boolean any = false;

        if (csmCalculator.isFreezeLightBasis()) {
            sb.append("Basis");
            any = true;
        }
        if (csmCalculator.isFreezeSnappedXY()) {
            if (any) sb.append("+");
            sb.append("SnapXY");
            any = true;
        }
        if (csmCalculator.isFreezeZRange()) {
            if (any) sb.append("+");
            sb.append("ZRange");
            any = true;
        }

        if (!any) {
            sb.append("All OFF");
        }

        return sb.toString();
    }

    /**
     * Check if light basis is frozen.
     */
    public boolean isFreezeLightBasis() {
        return csmCalculator.isFreezeLightBasis();
    }

    /**
     * Check if snapped XY is frozen.
     */
    public boolean isFreezeSnappedXY() {
        return csmCalculator.isFreezeSnappedXY();
    }

    /**
     * Check if Z range is frozen.
     */
    public boolean isFreezeZRange() {
        return csmCalculator.isFreezeZRange();
    }
}
