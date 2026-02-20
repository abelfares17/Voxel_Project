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
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
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
import static org.lwjgl.opengl.GL11.glGetError;
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
import static org.lwjgl.opengl.GL11.glClearDepth;
import static org.lwjgl.opengl.GL11.glFinish;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;

import engine.core.config.RenderConfig;
import engine.render.Shader;
import engine.render.camera.Camera;
import engine.render.camera.PerspectiveCamera;
import engine.render.core.RenderContext;
import engine.render.shadow.CSMCalculator;
import engine.render.shadow.CascadeData;
import engine.util.CSMDebugLog;
import engine.util.DebugFrameSnapshot;
import engine.util.SkyFlashDiagnostic;
import engine.util.SkyFlashTrace;

/**
 * Renderer responsible for Cascaded Shadow Mapping (CSM).
 * Renders depth maps from light's perspective for each cascade.
 * Uses stabilized fit algorithm for shimmer-free shadows.
 */
public class ShadowRenderer implements Renderer {

    private final int fbo;
    private final int shadowMapArray;  // GL_TEXTURE_2D_ARRAY for all cascades (primary)
    private final Shader depthShader;
    private final CSMCalculator csmCalculator;

    // Per-frame cascade data (accessible by VoxelRenderer)
    private CascadeData cascadeData;

    // ═══════════════════════════════════════════════════════════════════════════
    // LEAPFROG SHADOW SYSTEM
    // ═══════════════════════════════════════════════════════════════════════════
    // Two shadow map arrays for crossfade: A (current) and B (next)
    // When quantized state changes, we render to B and crossfade from A to B
    // Once crossfade completes, B becomes A and we're ready for next transition

    private int shadowMapArrayB;  // Secondary shadow map array for leapfrogging
    private CascadeData cascadeDataB;  // Cascade data for secondary array

    // Leapfrog state
    // ENABLED for diagnostic logging - captures transition events for SkyFlashTrace
    private boolean leapfrogEnabled = true;
    private boolean isTransitioning = false;  // True during A->B crossfade
    private float crossfadeProgress = 1.0f;   // 0 = fully A, 1 = fully B (or no transition)
    // Crossfade speed: controls how quickly we blend from old to new shadow map
    // Lower = smoother transition, higher = faster transition
    // 0.05 = ~20 frames, 0.1 = ~10 frames, 1.0 = instant (causes flash!)
    private static final float CROSSFADE_SPEED = 0.1f;

    // Stabilization frame: when true, crossfade has reached 1.0 but swap hasn't happened yet
    // This ensures the shader renders one frame with mix(A, B, 1.0) = B before we switch to B-only
    private boolean awaitingStabilizationFrame = false;

    // Which array is currently "active" (the one being displayed when not transitioning)
    private boolean primaryIsA = true;

    // Frozen cascade data for A during transition (captured at transition start)
    private CascadeData frozenCascadeDataA;

    // Depth pass counter for internal tracking
    private int depthPassCountThisFrame = 0;

    // Debug: track when last LEAP_END occurred (for UI display)
    private long lastLeapEndTimeMs = 0;
    private static final long LEAP_END_DISPLAY_DURATION_MS = 2000;  // Show "LEAP END" for 2 seconds

    // ═══════════════════════════════════════════════════════════════════════════
    // FRAME STATE INDICATOR (for visual debugging)
    // ═══════════════════════════════════════════════════════════════════════════
    // Tracks which special state we're in for on-screen display
    public enum FrameState {
        NORMAL,
        TRANSITION_START_FRAME,  // First frame of transition (crossfade=0)
        TRANSITIONING,
        STABILIZATION_FRAME,
        SWAP_FRAME,
        POST_SWAP_FRAME
    }
    private FrameState currentFrameState = FrameState.NORMAL;
    private long currentFrameId = 0;
    private boolean slowMotionEnabled = false;

    // Configurable delay between frames (in milliseconds)
    // Can be modified at runtime for debugging
    // NOTE: Shorter delays allow for more responsive input detection
    public static long SLOW_MOTION_DELAY_MS = 200;  // Default 200ms delay per frame (5 FPS)
    public static long SPECIAL_FRAME_DELAY_MS = 800;  // Extra delay on special frames (0.8s)

    // ═══════════════════════════════════════════════════════════════════════════
    // ONE-FRAME FREEZE AT SWAP (diagnostic toggle)
    // ═══════════════════════════════════════════════════════════════════════════
    // When enabled, freezes all matrices and lighting for exactly 1 frame after swap.
    // PURPOSE: Isolate if flash is caused by:
    //   - Sampling bug (flash still occurs with freeze) → shader/texture issue
    //   - Uniform update timing bug (flash disappears) → Java-side timing issue
    private boolean freezeOneFrameAtSwap = false;
    private boolean isSwapFrameFrozen = false;
    private CascadeData frozenSwapCascadeData = null;
    private CascadeData preSwapCascadeDataA = null;  // For logging discontinuity
    private int preSwapPrimaryTexId = 0;

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

        // Restore default framebuffer state after FBO setup
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glDrawBuffer(GL_BACK);   // CRITICAL: Restore default draw buffer
        glReadBuffer(GL_BACK);   // CRITICAL: Restore default read buffer
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);

        // Create second shadow map array for leapfrogging
        shadowMapArrayB = glGenTextures();
        glBindTexture(GL_TEXTURE_2D_ARRAY, shadowMapArrayB);
        glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_DEPTH_COMPONENT32F,
                shadowMapSize, shadowMapSize, cascadeCount,
                0, GL_DEPTH_COMPONENT, GL_FLOAT, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);

        // ═══════════════════════════════════════════════════════════════════════
        // INITIALIZE BOTH SHADOW MAP ARRAYS TO DEPTH 1.0
        // ═══════════════════════════════════════════════════════════════════════
        // Prevents sampling garbage from uninitialized textures
        clearShadowMapArray(shadowMapArray, cascadeCount, shadowMapSize);
        clearShadowMapArray(shadowMapArrayB, cascadeCount, shadowMapSize);

        // Restore default framebuffer state after initialization
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glDrawBuffer(GL_BACK);
        glReadBuffer(GL_BACK);

        // Pre-bind shadow textures to fixed units to prevent driver warnings
        glActiveTexture(GL_TEXTURE0 + 5);  // SHADOW_UNIT_A
        glBindTexture(GL_TEXTURE_2D_ARRAY, shadowMapArray);
        glActiveTexture(GL_TEXTURE0 + 6);  // SHADOW_UNIT_B
        glBindTexture(GL_TEXTURE_2D_ARRAY, shadowMapArrayB);
        glActiveTexture(GL_TEXTURE0);  // Reset to unit 0
    }

    /**
     * Clear all layers of a shadow map array to depth 1.0 (maximum distance).
     * This ensures we never sample garbage from uninitialized textures.
     */
    private void clearShadowMapArray(int textureArray, int cascadeCount, int shadowMapSize) {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, shadowMapSize, shadowMapSize);
        glClearDepth(1.0);
        glDepthMask(true);

        for (int i = 0; i < cascadeCount; i++) {
            glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, textureArray, 0, i);
            glClear(GL_DEPTH_BUFFER_BIT);
        }
    }

    @Override
    public void render(RenderContext context) {
        // Save viewport dimensions for restoration
        savedViewportWidth = context.getWindowWidth();
        savedViewportHeight = context.getWindowHeight();

        // DIAGNOSTIC: Log state at shadow pass entry
        SkyFlashDiagnostic.get().nextFrame();
        SkyFlashDiagnostic.get().logShadowEnter("render_start", isTransitioning, crossfadeProgress, primaryIsA);

        try {
            renderInternal(context);
        } finally {
            // ═══════════════════════════════════════════════════════════════════
            // GUARANTEED GL STATE RESTORATION
            // ═══════════════════════════════════════════════════════════════════
            // This runs after EVERY render path (including early returns via exception)
            // to ensure default framebuffer state is correct for sky rendering.
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDrawBuffer(GL_BACK);
            glReadBuffer(GL_BACK);
            glColorMask(true, true, true, true);
            glDepthMask(true);
            glDepthFunc(GL_LESS);
            glViewport(0, 0, savedViewportWidth, savedViewportHeight);
            drainGLErrors("ShadowPassEnd");

            // DIAGNOSTIC: Log state at shadow pass exit
            SkyFlashDiagnostic.get().logShadowExit("render_end", depthPassCountThisFrame);

            // SKYFLASH TRACE: Update transition state for other renderers
            SkyFlashTrace.get().updateTransitionState(isTransitioning);
        }
    }

    /**
     * Internal render implementation. GL state restoration is handled by caller.
     */
    private void renderInternal(RenderContext context) {
        // ═══════════════════════════════════════════════════════════════════════
        // FULL FREEZE: Skip entire render pass (matrices frozen + map frozen)
        // ═══════════════════════════════════════════════════════════════════════
        if (freezeMode == FREEZE_MODE_FULL) {
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

        // CSM instrumentation
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
        // GET SUN DIRECTION FOR SHADOWS
        // ═══════════════════════════════════════════════════════════════════════
        // Use sunDirShadows (not sunDirLighting) to avoid affecting sky/lighting
        // when shadow basis updates occur
        Vector3f sunDir = context.getSunDirShadows();

        // ═══════════════════════════════════════════════════════════════════════
        // LEAPFROG SHADOW SYSTEM
        // ═══════════════════════════════════════════════════════════════════════
        if (leapfrogEnabled && freezeMode == FREEZE_MODE_OFF) {
            renderWithLeapfrog(context, camera, sunDir);
            return;
        }

        // ═══════════════════════════════════════════════════════════════════════
        // LEGACY PATH (freeze modes or leapfrog disabled)
        // ═══════════════════════════════════════════════════════════════════════

        // When leapfrog is disabled, reset all leapfrog state to ensure consistency
        // between what we render to and what VoxelRenderer samples from
        if (!leapfrogEnabled) {
            primaryIsA = true;
            isTransitioning = false;
            crossfadeProgress = 1.0f;
            cascadeDataB = null;
            frozenCascadeDataA = null;
        }

        boolean usesFrozenMatrices = (freezeMode == FREEZE_MODE_MATRIX);

        if (usesFrozenMatrices) {
            cascadeData = frozenCascadeData;
            logFrozenState("MATRIX");
        } else {
            csmCalculator.updateLightBasis(sunDir);
            cascadeData = csmCalculator.computeCascadeMatrices(camera, camForward, camRight, camUp);
            if (freezeMode == FREEZE_MODE_MAP) {
                logFrozenState("MAP");
            }
        }

        if (freezeMode == FREEZE_MODE_MAP) {
            return;
        }

        // Render to primary shadow map (always shadowMapArray when leapfrog disabled)
        renderShadowMap(context, shadowMapArray, cascadeData);
    }

    /**
     * Leapfrog rendering: manages two shadow maps with crossfade.
     *
     * ═══════════════════════════════════════════════════════════════════════════
     * INVARIANT A: MAP↔MATRIX LOCK
     * ═══════════════════════════════════════════════════════════════════════════
     * For each shadow map array (A or B), the lightSpaceMatrices used to SAMPLE it
     * must be exactly the same matrices used when it was last RENDERED.
     *
     * Therefore:
     * - If we freeze the map (don't re-render), we must also freeze its matrices.
     * - If we recompute matrices, we must also re-render the map.
     *
     * ═══════════════════════════════════════════════════════════════════════════
     * UPDATE POLICY: Render shadow map only when needed
     * ═══════════════════════════════════════════════════════════════════════════
     * - Sun moved enough (angular threshold) → start transition to new basis
     * - Camera moved enough (snap grid changed) → re-render with same basis
     * - Neither changed → reuse frozen map+matrices (0 depth passes)
     */
    private void renderWithLeapfrog(RenderContext context, Camera camera, Vector3f sunDir) {
        // Reset depth pass counter
        depthPassCountThisFrame = 0;

        // Track if we just completed a swap this frame (to skip re-render)
        boolean justSwapped = false;

        // ─────────────────────────────────────────────────────────────────────
        // STEP 0: Handle one-frame freeze (if active from previous swap)
        // ─────────────────────────────────────────────────────────────────────
        if (isSwapFrameFrozen) {
            // End the freeze - this was the frozen frame
            isSwapFrameFrozen = false;
            frozenSwapCascadeData = null;
            System.out.println("[SWAP FREEZE] Freeze ended, resuming normal operation");
        }

        // ─────────────────────────────────────────────────────────────────────
        // STEP 1: Progress any ongoing crossfade
        // ─────────────────────────────────────────────────────────────────────
        if (isTransitioning) {
            crossfadeProgress += CROSSFADE_SPEED;

            // Update frame state to TRANSITIONING if we're between start and stabilization
            if (currentFrameState == FrameState.TRANSITION_START_FRAME) {
                currentFrameState = FrameState.TRANSITIONING;
            }

            if (crossfadeProgress >= 1.0f && !awaitingStabilizationFrame) {
                // ═══════════════════════════════════════════════════════════════
                // STABILIZATION FRAME: Hold at crossfade=1.0 for ONE frame
                // ═══════════════════════════════════════════════════════════════
                // This ensures the shader renders mix(A, B, 1.0) = B before we
                // switch to using B alone. Without this, there's a 10% jump from
                // mix(A, B, 0.9) to 100% B.
                crossfadeProgress = 1.0f;
                awaitingStabilizationFrame = true;
                // isTransitioning stays TRUE - shader will use mix(A, B, 1.0)
                // DON'T do swap yet - just continue rendering with crossfade=1.0

                // LOG: Stabilization frame entry
                SkyFlashTrace.get().logStabilizationFrame(
                    "STABILIZATION_ENTER",
                    crossfadeProgress,
                    isTransitioning,
                    primaryIsA,
                    primaryIsA ? shadowMapArray : shadowMapArrayB,
                    primaryIsA ? shadowMapArrayB : shadowMapArray,
                    cascadeData != null ? SkyFlashTrace.hashMat(cascadeData.lightSpaceMatrices()[0]) : "null",
                    cascadeDataB != null ? SkyFlashTrace.hashMat(cascadeDataB.lightSpaceMatrices()[0]) : "null"
                );

                // VISUAL DEBUG: Mark this as stabilization frame
                currentFrameState = FrameState.STABILIZATION_FRAME;
                System.out.println(">>> FRAME " + currentFrameId + ": STABILIZATION (crossfade=1.0, leapfrogActive=1) <<<");
            } else if (awaitingStabilizationFrame) {
                // ═══════════════════════════════════════════════════════════════
                // POST-STABILIZATION: Now do the actual swap
                // ═══════════════════════════════════════════════════════════════

                // LOG: Stabilization frame exit (before swap)
                SkyFlashTrace.get().logStabilizationFrame(
                    "STABILIZATION_EXIT_PRE_SWAP",
                    crossfadeProgress,
                    isTransitioning,
                    primaryIsA,
                    primaryIsA ? shadowMapArray : shadowMapArrayB,
                    primaryIsA ? shadowMapArrayB : shadowMapArray,
                    cascadeData != null ? SkyFlashTrace.hashMat(cascadeData.lightSpaceMatrices()[0]) : "null",
                    cascadeDataB != null ? SkyFlashTrace.hashMat(cascadeDataB.lightSpaceMatrices()[0]) : "null"
                );

                awaitingStabilizationFrame = false;

                // ═══════════════════════════════════════════════════════════════
                // SWAP DISCONTINUITY LOGGING - Capture state BEFORE swap
                // ═══════════════════════════════════════════════════════════════
                preSwapCascadeDataA = cascadeData;  // This is frozenCascadeDataA
                preSwapPrimaryTexId = primaryIsA ? shadowMapArray : shadowMapArrayB;
                String matrixHashBefore = (preSwapCascadeDataA != null &&
                    preSwapCascadeDataA.lightSpaceMatrices().length > 0)
                    ? SkyFlashTrace.hashMat(preSwapCascadeDataA.lightSpaceMatrices()[0])
                    : "null";

                // Crossfade complete - swap roles
                crossfadeProgress = 1.0f;
                isTransitioning = false;
                justSwapped = true;  // Mark that we just swapped

                // Swap roles: what was "secondary" becomes "primary"
                primaryIsA = !primaryIsA;

                // The secondary cascade data is now primary (LOCKED to its map)
                cascadeData = cascadeDataB;

                // ═══════════════════════════════════════════════════════════════
                // SWAP DISCONTINUITY LOGGING - Capture state AFTER swap
                // ═══════════════════════════════════════════════════════════════
                int postSwapPrimaryTexId = primaryIsA ? shadowMapArray : shadowMapArrayB;
                String matrixHashAfter = (cascadeData != null &&
                    cascadeData.lightSpaceMatrices().length > 0)
                    ? SkyFlashTrace.hashMat(cascadeData.lightSpaceMatrices()[0])
                    : "null";

                // Verify blend invariant: mix(A, B, 1.0) should equal B exactly
                // If the matrices match, the blend is correct
                boolean blendEqualsB = matrixHashAfter.equals(
                    (cascadeDataB != null && cascadeDataB.lightSpaceMatrices().length > 0)
                        ? SkyFlashTrace.hashMat(cascadeDataB.lightSpaceMatrices()[0])
                        : "null"
                );

                // Log the swap detail
                SkyFlashTrace.get().logSwapDetail(
                    1.0f, 1.0f,  // crossfade before/after (both 1.0 at swap)
                    matrixHashBefore, matrixHashAfter,
                    preSwapPrimaryTexId, postSwapPrimaryTexId,
                    blendEqualsB
                );

                // CRITICAL: Update lastSnapX/Y to match the new primary cascade data
                // This prevents an immediate re-render after the transition ends
                lastSnapX = getSnapX(cascadeData);
                lastSnapY = getSnapY(cascadeData);

                // ═══════════════════════════════════════════════════════════════
                // CRITICAL FIX #3: Update lastRenderedMatrixHashA after swap
                // ═══════════════════════════════════════════════════════════════
                // The new "primary" shadow map is what was previously B.
                // It was rendered with cascadeDataB matrices during the transition.
                // Now cascadeData = cascadeDataB (from line 524), so update the hash.
                lastRenderedMatrixHashA = matrixHashAfter;

                // ═══════════════════════════════════════════════════════════════
                // CRITICAL FIX #2: Reset quantization baseline to CURRENT sun position
                // ═══════════════════════════════════════════════════════════════
                csmCalculator.resetQuantizationBaseline(sunDir);

                // DIAGNOSTIC: Log transition end (this is when swap happens)
                SkyFlashDiagnostic.get().logLeapfrogEvent("TRANSITION_END_SWAP",
                    isTransitioning, crossfadeProgress, primaryIsA, depthPassCountThisFrame);
                DebugFrameSnapshot.markTransitionEnd();
                DebugFrameSnapshot.logNow("LEAP_END", context, this);

                // SKYFLASH TRACE: Log TRANSITION_END_SWAP with full state
                SkyFlashTrace.get().markSwap();
                logSkyFlashTransition("TRANSITION_END_SWAP", true);

                // Record timestamp for UI indicator
                lastLeapEndTimeMs = System.currentTimeMillis();

                // ═══════════════════════════════════════════════════════════════
                // ONE-FRAME FREEZE AT SWAP (if enabled)
                // ═══════════════════════════════════════════════════════════════
                if (freezeOneFrameAtSwap) {
                    isSwapFrameFrozen = true;
                    frozenSwapCascadeData = deepCopyCascadeData(cascadeData);
                    System.out.println("[SWAP FREEZE] Freezing matrices for 1 frame to isolate discontinuity");
                }

                // Clear secondary data
                cascadeDataB = null;
                frozenCascadeDataA = null;

                // LOG: Post-swap state (shader will now use B-only with leapfrogActive=0)
                SkyFlashTrace.get().logStabilizationFrame(
                    "POST_SWAP_STATE",
                    crossfadeProgress,
                    isTransitioning,
                    primaryIsA,
                    primaryIsA ? shadowMapArray : shadowMapArrayB,
                    0,  // No secondary after swap
                    cascadeData != null ? SkyFlashTrace.hashMat(cascadeData.lightSpaceMatrices()[0]) : "null",
                    "null (cleared)"
                );

                // VISUAL DEBUG: Mark this as swap frame
                currentFrameState = FrameState.SWAP_FRAME;
                System.out.println(">>> FRAME " + currentFrameId + ": SWAP (leapfrogActive=0, using new primary) <<<");
            }
        } else {
            // Not transitioning - check if we just finished
            if (currentFrameState == FrameState.SWAP_FRAME) {
                currentFrameState = FrameState.POST_SWAP_FRAME;
                System.out.println(">>> FRAME " + currentFrameId + ": POST_SWAP (normal rendering) <<<");
            } else if (currentFrameState == FrameState.POST_SWAP_FRAME) {
                currentFrameState = FrameState.NORMAL;
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // STEP 2: Handle rendering based on transition state
        // ─────────────────────────────────────────────────────────────────────

        if (isTransitioning) {
            // ═══════════════════════════════════════════════════════════════
            // DURING TRANSITION: NO RENDERING, both shadow maps are frozen
            // ═══════════════════════════════════════════════════════════════
            // MAP↔MATRIX LOCK: A uses frozenCascadeDataA, B uses cascadeDataB
            // Both are FROZEN - we don't recompute or re-render either.

            // Keep cascadeData pointing to frozen A for shader uniforms
            cascadeData = frozenCascadeDataA;

        } else {
            // ═══════════════════════════════════════════════════════════════
            // NOT TRANSITIONING: Check if we need to start one or re-render
            // ═══════════════════════════════════════════════════════════════
            boolean sunMoved = csmCalculator.shouldUpdateBasis(sunDir);

            if (cascadeData == null) {
                // ─────────────────────────────────────────────────────────
                // FIRST FRAME: Initialize everything
                // ─────────────────────────────────────────────────────────
                csmCalculator.updateLightBasis(sunDir, true);
                cascadeData = csmCalculator.computeCascadeMatrices(camera, camForward, camRight, camUp);

                // Render to primary array (MAP↔MATRIX LOCK: cascadeData matches this render)
                int primaryArray = primaryIsA ? shadowMapArray : shadowMapArrayB;
                renderShadowMap(context, primaryArray, cascadeData);
                depthPassCountThisFrame++;

                // Record the matrix hash for MAP↔MATRIX LOCK verification
                lastRenderedMatrixHashA = SkyFlashTrace.hashMat(cascadeData.lightSpaceMatrices()[0]);

                // Initialize snap tracking
                lastSnapX = getSnapX(cascadeData);
                lastSnapY = getSnapY(cascadeData);

            } else if (sunMoved) {
                // ─────────────────────────────────────────────────────────
                // SUN MOVED: Start transition to new basis
                // ─────────────────────────────────────────────────────────

                // SNAPSHOT: Freeze current primary (MAP↔MATRIX LOCK preserved)
                // cascadeData was computed when we last rendered the primary map
                frozenCascadeDataA = deepCopyCascadeData(cascadeData);

                // Update light basis for the NEW state
                csmCalculator.updateLightBasis(sunDir, true);

                // Compute NEW cascade data for B
                cascadeDataB = csmCalculator.computeCascadeMatrices(camera, camForward, camRight, camUp);

                // Copy splitDistances from A to B for consistent cascade selection
                System.arraycopy(frozenCascadeDataA.splitDistances(), 0,
                                 cascadeDataB.splitDistances(), 0,
                                 frozenCascadeDataA.splitDistances().length);

                // Render SECONDARY shadow map (MAP↔MATRIX LOCK: cascadeDataB matches this render)
                int secondaryArray = primaryIsA ? shadowMapArrayB : shadowMapArray;
                renderShadowMap(context, secondaryArray, cascadeDataB);
                depthPassCountThisFrame++;

                // ═══════════════════════════════════════════════════════════════════
                // GPU SYNCHRONIZATION: Ensure shadow map B render completes
                // ═══════════════════════════════════════════════════════════════════
                // This prevents any potential race condition where the GPU is still
                // writing to shadow map B while VoxelRenderer tries to sample.
                // Even though we sample A at crossfade=0, driver behavior can be
                // unpredictable when FBO attachments change.
                glFinish();

                // Start crossfade
                isTransitioning = true;
                crossfadeProgress = 0.0f;

                // Set cascadeData to frozen A for shader uniforms this frame
                cascadeData = frozenCascadeDataA;

                // VISUAL DEBUG: Mark this as transition start frame
                currentFrameState = FrameState.TRANSITION_START_FRAME;
                System.out.println(">>> FRAME " + currentFrameId + ": TRANSITION START (crossfade=0, rendering to B) <<<");

                // DEBUG: Log matrix hashes to verify MAP↔MATRIX LOCK
                String hashFrozenA = SkyFlashTrace.hashMat(frozenCascadeDataA.lightSpaceMatrices()[0]);
                String hashCascadeData = SkyFlashTrace.hashMat(cascadeData.lightSpaceMatrices()[0]);
                String hashCascadeDataB = SkyFlashTrace.hashMat(cascadeDataB.lightSpaceMatrices()[0]);
                System.out.println("    Matrix hashes: frozenA=" + hashFrozenA + " cascadeData=" + hashCascadeData + " B=" + hashCascadeDataB);
                System.out.println("    primaryIsA=" + primaryIsA + " shadowMapArray=" + shadowMapArray + " shadowMapArrayB=" + shadowMapArrayB);

                // ═══════════════════════════════════════════════════════════════════
                // MAP↔MATRIX LOCK VERIFICATION
                // ═══════════════════════════════════════════════════════════════════
                // Verify that frozenCascadeDataA matches what was actually used to render shadow map A
                if (!hashFrozenA.equals(lastRenderedMatrixHashA)) {
                    System.err.println("    *** MAP↔MATRIX LOCK VIOLATION! ***");
                    System.err.println("    frozenA hash:      " + hashFrozenA);
                    System.err.println("    lastRendered hash: " + lastRenderedMatrixHashA);
                    System.err.println("    Shadow map A was rendered with DIFFERENT matrices than frozenCascadeDataA!");
                } else {
                    System.out.println("    MAP↔MATRIX LOCK: OK (frozenA matches lastRendered)");
                }

                // DIAGNOSTIC: Log transition start (this is when we render to secondary)
                SkyFlashDiagnostic.get().logLeapfrogEvent("TRANSITION_START",
                    isTransitioning, crossfadeProgress, primaryIsA, depthPassCountThisFrame);
                DebugFrameSnapshot.markTransitionStart();  // Enable high-frequency logging
                DebugFrameSnapshot.logNow("LEAP_START", context, this);

                // SKYFLASH TRACE: Log TRANSITION_START with full state
                SkyFlashTrace.get().markTransitionStart();
                logSkyFlashTransition("TRANSITION_START", false);

            } else if (!justSwapped) {
                // ─────────────────────────────────────────────────────────
                // STABLE STATE: Check if camera moved enough to warrant re-render
                // ─────────────────────────────────────────────────────────
                // CRITICAL: Skip this check on the swap frame (justSwapped=true)
                // to preserve the MAP↔MATRIX LOCK. The swapped cascadeData matches
                // the already-rendered shadow map; re-computing would break this.

                // Compute what the NEW matrices would be
                CascadeData newCascadeData = csmCalculator.computeCascadeMatrices(camera, camForward, camRight, camUp);

                // Check if snap grid changed (camera drifted enough)
                float newSnapX = getSnapX(newCascadeData);
                float newSnapY = getSnapY(newCascadeData);
                boolean snapChanged = (newSnapX != lastSnapX) || (newSnapY != lastSnapY);

                if (snapChanged) {
                    // Camera moved enough - re-render with new matrices
                    // (MAP↔MATRIX LOCK: we update BOTH together)
                    cascadeData = newCascadeData;
                    lastSnapX = newSnapX;
                    lastSnapY = newSnapY;

                    int primaryArray = primaryIsA ? shadowMapArray : shadowMapArrayB;
                    renderShadowMap(context, primaryArray, cascadeData);
                    depthPassCountThisFrame++;

                    // Record the matrix hash for MAP↔MATRIX LOCK verification
                    lastRenderedMatrixHashA = SkyFlashTrace.hashMat(cascadeData.lightSpaceMatrices()[0]);
                }
            }
            // If justSwapped is true, we skip the stable state check and use
            // cascadeData (which was set to cascadeDataB) as-is. This preserves
            // the MAP↔MATRIX LOCK since cascadeDataB matches the shadow map.
        }

    }

    // Track snap grid position to detect camera drift
    private float lastSnapX = 0;
    private float lastSnapY = 0;

    // ═══════════════════════════════════════════════════════════════════════════
    // MAP↔MATRIX LOCK VERIFICATION
    // ═══════════════════════════════════════════════════════════════════════════
    // Track the ACTUAL matrices used when last rendering shadow map A
    // This allows verification that frozenCascadeDataA matches what's in the shadow map
    private String lastRenderedMatrixHashA = "none";

    /**
     * Extract snap X from cascade data (cascade 0 matrix).
     */
    private float getSnapX(CascadeData data) {
        if (data == null || data.lightSpaceMatrices().length == 0) return 0;
        // m30 is the X translation in the light-view matrix
        return data.lightSpaceMatrices()[0].m30();
    }

    /**
     * Extract snap Y from cascade data (cascade 0 matrix).
     */
    private float getSnapY(CascadeData data) {
        if (data == null || data.lightSpaceMatrices().length == 0) return 0;
        // m31 is the Y translation in the light-view matrix
        return data.lightSpaceMatrices()[0].m31();
    }

    /**
     * Log shadow transition event to SkyFlashTrace.
     */
    private void logSkyFlashTransition(String event, boolean swapOccurred) {
        Matrix4f matA0 = null;
        Matrix4f matB0 = null;
        float[] splitsA = null;
        float[] splitsB = null;
        float texelSizeA0 = 0;
        float texelSizeB0 = 0;

        if (cascadeData != null && cascadeData.lightSpaceMatrices().length > 0) {
            matA0 = cascadeData.lightSpaceMatrices()[0];
            splitsA = cascadeData.splitDistances();
            texelSizeA0 = cascadeData.texelSizes()[0];
        }
        if (cascadeDataB != null && cascadeDataB.lightSpaceMatrices().length > 0) {
            matB0 = cascadeDataB.lightSpaceMatrices()[0];
            splitsB = cascadeDataB.splitDistances();
            texelSizeB0 = cascadeDataB.texelSizes()[0];
        }

        SkyFlashTrace.get().logShadowTransition(event,
            leapfrogEnabled, isTransitioning, crossfadeProgress, primaryIsA,
            shadowMapArray, shadowMapArrayB,
            cascadeData, cascadeDataB, frozenCascadeDataA,
            matA0, matB0, splitsA, splitsB, texelSizeA0, texelSizeB0,
            swapOccurred);
    }

    /**
     * Deep copy cascade data to freeze it during transition.
     */
    private CascadeData deepCopyCascadeData(CascadeData src) {
        if (src == null) return null;

        Matrix4f[] matrices = new Matrix4f[src.lightSpaceMatrices().length];
        for (int i = 0; i < matrices.length; i++) {
            matrices[i] = new Matrix4f(src.lightSpaceMatrices()[i]);
        }

        return new CascadeData(
                matrices,
                src.splitDistances().clone(),
                src.sphereRadii().clone(),
                src.texelSizes().clone()
        );
    }

    /**
     * Render shadow map to the specified texture array.
     * Uses try-finally to guarantee GL state restoration even if rendering fails.
     */
    private void renderShadowMap(RenderContext context, int targetArray, CascadeData data) {
        // Clear any pending GL errors from previous operations
        drainGLErrors("PreShadowPass");

        savedViewportWidth = context.getWindowWidth();
        savedViewportHeight = context.getWindowHeight();

        // ═══════════════════════════════════════════════════════════════════════
        // FEEDBACK GUARD - Unbind BOTH shadow arrays from texture units
        // ═══════════════════════════════════════════════════════════════════════
        // This prevents read-while-write undefined behavior (sampling a texture
        // while it's attached to the current FBO for writing).
        glActiveTexture(GL_TEXTURE0 + 5);  // SHADOW_UNIT_A
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        glActiveTexture(GL_TEXTURE0 + 6);  // SHADOW_UNIT_B
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        glActiveTexture(GL_TEXTURE0);  // Reset to unit 0

        // === BEGIN SHADOW PASS STATE SETUP ===
        glDisable(GL_BLEND);
        glDisable(GL_SCISSOR_TEST);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);
        glDepthMask(true);
        glColorMask(false, false, false, false);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);

        int shadowMapSize = RenderConfig.CSM_SHADOW_MAP_SIZE;
        glViewport(0, 0, shadowMapSize, shadowMapSize);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);

        try {
            depthShader.bind();

            int cascadeCount = data.cascadeCount();
            for (int i = 0; i < cascadeCount; i++) {
                glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, targetArray, 0, i);

                int fboStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
                if (fboStatus != GL_FRAMEBUFFER_COMPLETE) {
                    System.err.println("[Shadow] FBO incomplete for cascade " + i + ": " + fboStatus);
                }

                glClear(GL_DEPTH_BUFFER_BIT);
                depthShader.setMat4("lightSpaceMatrix", data.lightSpaceMatrices()[i]);
                context.getWorld().renderDepth(depthShader);
            }

            depthShader.unbind();
        } finally {
            // ═══════════════════════════════════════════════════════════════════
            // BULLETPROOF STATE RESTORATION
            // ═══════════════════════════════════════════════════════════════════
            // This finally block GUARANTEES state is restored even if:
            // - An exception is thrown during rendering
            // - FBO status check fails
            // - Shader binding fails
            // This prevents GL state leaks that could cause sky color flashes.
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDrawBuffer(GL_BACK);   // CRITICAL: Shadow FBO uses GL_NONE
            glReadBuffer(GL_BACK);   // CRITICAL: Restore default read buffer
            glViewport(0, 0, savedViewportWidth, savedViewportHeight);
            glColorMask(true, true, true, true);
            glDepthMask(true);
            glDepthFunc(GL_LESS);

            // === DRAIN GL ERRORS ===
            // Clear any accumulated GL errors from shadow pass to prevent them
            // from being reported during normal rendering (MeshBuilder.renderChunk)
            drainGLErrors("ShadowPass");
        }
    }

    /**
     * Drain all accumulated GL errors to prevent them from being reported elsewhere.
     */
    private void drainGLErrors(String context) {
        while (glGetError() != GL_NO_ERROR) {
            // Silently drain errors
        }
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
    // LEAPFROG ACCESSORS (for VoxelRenderer shader uniforms)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get the primary shadow map array ID (the "from" array during crossfade).
     */
    public int getPrimaryShadowMapArray() {
        return primaryIsA ? shadowMapArray : shadowMapArrayB;
    }

    /**
     * Get the secondary shadow map array ID (the "to" array during crossfade).
     * Returns 0 if not transitioning.
     */
    public int getSecondaryShadowMapArray() {
        if (!isTransitioning) {
            return 0;  // No secondary during stable state
        }
        return primaryIsA ? shadowMapArrayB : shadowMapArray;
    }

    /**
     * Get the secondary cascade data (for the "to" array during crossfade).
     * Returns null if not transitioning.
     */
    public CascadeData getSecondaryCascadeData() {
        return cascadeDataB;
    }

    /**
     * Get current crossfade progress.
     * 0.0 = fully primary, 1.0 = fully secondary (or stable).
     * Value is clamped to [0.0, 1.0] to prevent shader issues.
     */
    public float getCrossfadeProgress() {
        if (!isTransitioning) return 1.0f;
        return Math.min(crossfadeProgress, 1.0f);  // Clamp to max 1.0
    }

    /**
     * Check if leapfrog system is currently transitioning between shadow maps.
     */
    public boolean isLeapfrogTransitioning() {
        return isTransitioning;
    }

    /**
     * Check if leapfrog system is enabled.
     */
    public boolean isLeapfrogEnabled() {
        return leapfrogEnabled;
    }

    /**
     * Check if LEAP_END just occurred (for UI display).
     * Returns true for LEAP_END_DISPLAY_DURATION_MS after a transition ends.
     */
    public boolean shouldShowLeapEndIndicator() {
        return (System.currentTimeMillis() - lastLeapEndTimeMs) < LEAP_END_DISPLAY_DURATION_MS;
    }

    /**
     * Enable or disable the leapfrog system.
     */
    public void setLeapfrogEnabled(boolean enabled) {
        this.leapfrogEnabled = enabled;
        System.out.println("Leapfrog Shadow System: " + (enabled ? "ENABLED" : "DISABLED"));
    }

    /**
     * Toggle the leapfrog system.
     */
    public void toggleLeapfrog() {
        setLeapfrogEnabled(!leapfrogEnabled);
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

    // ═══════════════════════════════════════════════════════════════════════════
    // ONE-FRAME FREEZE AT SWAP CONTROLS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Toggle one-frame freeze at swap diagnostic mode.
     * When enabled, freezes all matrices and lighting for exactly 1 frame after swap.
     * This isolates whether flash is caused by:
     *   - Sampling bug (flash still occurs) → shader/texture issue
     *   - Uniform update timing bug (flash disappears) → Java-side timing issue
     */
    public void toggleFreezeOneFrameAtSwap() {
        freezeOneFrameAtSwap = !freezeOneFrameAtSwap;
        System.out.println("One-Frame Freeze at Swap: " + (freezeOneFrameAtSwap ? "ENABLED" : "DISABLED"));
        if (freezeOneFrameAtSwap) {
            System.out.println("  If flash STILL occurs → shader/texture sampling bug");
            System.out.println("  If flash DISAPPEARS → Java-side uniform timing bug");
        }
    }

    /**
     * Check if one-frame freeze at swap is enabled.
     */
    public boolean isFreezeOneFrameAtSwap() {
        return freezeOneFrameAtSwap;
    }

    /**
     * Check if we are currently in the frozen swap frame.
     */
    public boolean isSwapFrameFrozen() {
        return isSwapFrameFrozen;
    }

    /**
     * Get frozen swap cascade data (used during frozen frame).
     * Returns null if not in frozen state.
     */
    public CascadeData getFrozenSwapCascadeData() {
        return frozenSwapCascadeData;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FRAME STATE AND SLOW MOTION CONTROLS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get current frame state for visual debugging.
     */
    public FrameState getCurrentFrameState() {
        return currentFrameState;
    }

    /**
     * Get current frame state as a displayable string.
     */
    public String getFrameStateString() {
        return switch (currentFrameState) {
            case NORMAL -> "";
            case TRANSITION_START_FRAME -> ">>> TRANSITION START (crossfade=0) <<<";
            case TRANSITIONING -> "TRANSITIONING (crossfade " + String.format("%.1f", crossfadeProgress) + ")";
            case STABILIZATION_FRAME -> ">>> STABILIZATION FRAME (mix=1.0) <<<";
            case SWAP_FRAME -> ">>> SWAP FRAME <<<";
            case POST_SWAP_FRAME -> "POST-SWAP";
        };
    }

    /**
     * Increment frame ID. Called at start of each frame.
     */
    public void incrementFrameId() {
        currentFrameId++;
        // Reset state to transitioning if we're in transition but not at a special point
        if (isTransitioning && currentFrameState == FrameState.NORMAL) {
            currentFrameState = FrameState.TRANSITIONING;
        }
    }

    /**
     * Get current frame ID.
     */
    public long getCurrentFrameId() {
        return currentFrameId;
    }

    /**
     * Toggle slow motion mode for debugging swap frames.
     */
    public void toggleSlowMotion() {
        slowMotionEnabled = !slowMotionEnabled;
        System.out.println("Slow Motion: " + (slowMotionEnabled ? "ON (500ms/frame)" : "OFF"));
    }

    /**
     * Check if slow motion is enabled.
     */
    public boolean isSlowMotionEnabled() {
        return slowMotionEnabled;
    }

    /**
     * Get slow motion delay in milliseconds.
     */
    public long getSlowMotionDelayMs() {
        return SLOW_MOTION_DELAY_MS;
    }

    /**
     * Check if we're in a special frame (transition start, stabilization, or swap).
     */
    public boolean isSpecialFrame() {
        return currentFrameState == FrameState.TRANSITION_START_FRAME ||
               currentFrameState == FrameState.STABILIZATION_FRAME ||
               currentFrameState == FrameState.SWAP_FRAME;
    }

    /**
     * Set the slow motion delay (in milliseconds).
     * Call this to adjust how slow the slow motion is.
     */
    public static void setSlowMotionDelay(long normalDelayMs, long specialDelayMs) {
        SLOW_MOTION_DELAY_MS = normalDelayMs;
        SPECIAL_FRAME_DELAY_MS = specialDelayMs;
        System.out.println("Slow motion delay: " + normalDelayMs + "ms normal, " + specialDelayMs + "ms special");
    }

    /**
     * Get the delay for special frames (in milliseconds).
     */
    public long getSpecialFrameDelayMs() {
        return SPECIAL_FRAME_DELAY_MS;
    }
}
