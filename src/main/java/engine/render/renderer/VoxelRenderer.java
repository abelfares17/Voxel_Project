package engine.render.renderer;

import engine.core.config.GameConfig;
import engine.core.config.RenderConfig;
import engine.render.MeshBuilder;
import engine.render.Shader;
import engine.render.camera.PerspectiveCamera;
import engine.render.core.RenderContext;
import engine.render.shadow.CascadeData;
import engine.time.SunData;
import engine.util.DebugFrameSnapshot;
import engine.util.SkyFlashTrace;
import engine.world.Chunk;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_NO_ERROR;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_BINDING_2D_ARRAY;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;

/**
 * Renderer responsible for rendering voxel chunks with lighting and CSM shadows.
 */
public class VoxelRenderer implements Renderer {

    private final Shader voxelShader;
    private final ShadowRenderer shadowRenderer;
    private final int atlasTextureId;

    // AO tuning
    private float aoMin = 0.5f;
    private float aoPower = 2.0f;
    private boolean debugAO = false;

    // Shadow debug mode (expanded for discontinuity diagnosis):
    // 0=off, 1=shadow factor, 2=cascade colors, 3=world-cell hash,
    // 4=fog factor, 5=cascade solid, 6=shadow A/B diff, 7=lit before fog,
    // 8=w-validity heatmap, 9=leapfrog state
    private int debugShadow = 0;
    private static final int DEBUG_MODE_COUNT = 10;  // 0-9

    // PCF mode (legacy, kept for compatibility - actual mode is compile-time in shader)
    private int pcfMode = 0;
    private static final int PCF_MODE_COUNT = 3;  // 0=single, 1=current PCF, 2=world-stable

    // Cascade isolation (-1=normal, 0/1/2=force specific cascade)
    private int forceCascade = -1;

    // Bias isolation mode (0=normal, 1=constant, 2=zero, 3=doubled)
    private int biasMode = 0;
    private static final int BIAS_MODE_COUNT = 4;

    // Normal-offset shadow mode (0=off, 1=on) - fixes shadow edge shimmer
    private int normalOffsetMode = 0;

    // Temporal jitter frame counter for shadow anti-aliasing
    private int frameIndex = 0;

    // Hemisphere ambient colors
    private final Vector3f skyAmbientColor = new Vector3f(0.6f, 0.7f, 0.9f);
    private final Vector3f groundAmbientColor = new Vector3f(0.4f, 0.35f, 0.3f);

    // DEBUG: Track previous frame state for comparison at TRANSITION_START
    private boolean lastLeapfrogActive = false;
    private String lastMatrixHash = "none";
    private int lastPrimaryShadowMap = 0;

    /**
     * Create a new voxel renderer.
     *
     * @param voxelShader     Shader for voxel rendering
     * @param shadowRenderer  Shadow renderer for shadow mapping
     * @param atlasTextureId  Texture atlas ID
     */
    public VoxelRenderer(Shader voxelShader, ShadowRenderer shadowRenderer, int atlasTextureId) {
        this.voxelShader = voxelShader;
        this.shadowRenderer = shadowRenderer;
        this.atlasTextureId = atlasTextureId;
    }

    /**
     * Drain all accumulated GL errors to prevent them from being reported elsewhere.
     */
    private void drainGLErrors(String context) {
        while (glGetError() != GL_NO_ERROR) {
            // Silently drain errors
        }
    }

    @Override
    public void render(RenderContext context) {
        // Drain any accumulated GL errors from previous passes (shadow, sun, etc.)
        // This prevents error spam from bleeding into chunk rendering
        drainGLErrors("PreVoxelPass");

        // ═══════════════════════════════════════════════════════════════════════════
        // GET SUN DIRECTION FOR LIGHTING (not shadows)
        // ═══════════════════════════════════════════════════════════════════════════
        // Use sunDirLighting (continuous) for diffuse lighting - NOT sunDirShadows
        // This ensures lighting doesn't jump when shadow basis updates
        SunData sunData = context.getWorld().getTimeManager().getSunData();
        Vector3f sunDir = context.getSunDirLighting();  // Continuous, for lighting
        float sunY = sunData.sunY();

        // Calculate sun color (based on continuous sun position)
        Vector3f sunColor = RenderConfig.getSunColor(sunY);

        // Get camera position and matrices
        Vector3f cameraPos = context.getCamera().getPosition();
        Matrix4f proj = context.getCamera().getProjectionMatrix();

        // Get view matrix with player's rotation
        Matrix4f view;
        if (context.getCamera() instanceof PerspectiveCamera perspectiveCamera) {
            view = perspectiveCamera.getViewMatrix(); // Uses player's yaw and pitch
        } else {
            view = context.getCamera().getViewMatrix(cameraPos, 0, 0);
        }

        // NOTE: Shadow map is rendered once per frame in Game.render(), not here
        // This avoids double-rendering which caused the "progressive wipe" artifact

        // === BEGIN MAIN PASS STATE SETUP ===
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, context.getWindowWidth(), context.getWindowHeight());
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);
        glDepthMask(true);
        glDisable(GL_BLEND);
        glDisable(GL_SCISSOR_TEST);
        glFrontFace(GL_CCW);
        glCullFace(GL_BACK);
        glEnable(GL_CULL_FACE);
        glColorMask(true, true, true, true);
        // === END MAIN PASS STATE SETUP ===

        // Use frame-cached sky color for fog (ensures consistency with sky clear)
        Vector3f skyColor = context.getSkyColor();

        // Bind voxel shader and set uniforms
        voxelShader.bind();
        voxelShader.setUniform("sunDir", sunDir);
        voxelShader.setUniform("sunColor", sunColor);
        voxelShader.setUniform("ambient", context.getWorld().getTimeManager().getAmbient());
        voxelShader.setUniform("projectionMatrix", proj);
        voxelShader.setUniform("viewMatrix", view);
        voxelShader.setUniform("cameraPos", cameraPos);
        voxelShader.setUniform("fogColor", skyColor);

        // Set fog distances based on render distance
        int renderDistance = context.getWorld().getRenderDistance();
        voxelShader.setUniform("fogStart", RenderConfig.getFogStart(renderDistance));
        voxelShader.setUniform("fogEnd", RenderConfig.getFogEnd(renderDistance));

        // AO tuning uniforms
        voxelShader.setUniform("aoMin", aoMin);
        voxelShader.setUniform("aoPower", aoPower);
        voxelShader.setUniform("debugAO", debugAO ? 1 : 0);

        // Hemisphere ambient lighting uniforms
        voxelShader.setUniform("skyAmbientColor", skyAmbientColor);
        voxelShader.setUniform("groundAmbientColor", groundAmbientColor);

        // Shadow debug mode
        voxelShader.setUniform("debugShadow", debugShadow);

        // PCF mode for shimmer isolation testing
        voxelShader.setUniform("pcfMode", pcfMode);

        // Cascade and bias isolation for shimmer testing
        voxelShader.setUniform("forceCascade", forceCascade);
        voxelShader.setUniform("biasMode", biasMode);

        // Normal-offset shadow mode (vertex shader uniform)
        voxelShader.setUniform("normalOffsetMode", normalOffsetMode);

        // Temporal jitter frame index for shadow anti-aliasing
        voxelShader.setUniform("frameIndex", frameIndex);
        frameIndex = (frameIndex + 1) % 8;  // Cycle through 8 Halton samples

        // === CSM UNIFORMS ===
        CascadeData cascadeData = shadowRenderer.getCascadeData();

        if (cascadeData != null) {
            // Set light space matrices for all cascades
            voxelShader.setUniformMat4Array("lightSpaceMatrices", cascadeData.lightSpaceMatrices());

            // Set cascade split distances (includes near and far: [near, split1, split2, far])
            voxelShader.setUniformFloatArray("cascadeSplits", cascadeData.splitDistances());

            // Set cascade texel sizes for cascade-aware bias
            voxelShader.setUniformFloatArray("cascadeTexelSizes", cascadeData.texelSizes());

            // Set cascade count
            voxelShader.setUniform("cascadeCount", cascadeData.cascadeCount());

            // Shadow map inverse size for PCF sampling
            voxelShader.setUniform("shadowMapInvSize", 1.0f / RenderConfig.CSM_SHADOW_MAP_SIZE);
        }

        // === LEAPFROG CROSSFADE UNIFORMS ===
        boolean leapfrogTransitioning = shadowRenderer.isLeapfrogTransitioning();
        float crossfadeProgress = shadowRenderer.getCrossfadeProgress();
        voxelShader.setUniform("leapfrogActive", leapfrogTransitioning ? 1 : 0);
        voxelShader.setUniform("shadowCrossfade", crossfadeProgress);

        // DEBUG: Log shader uniforms at TRANSITION_START and the frame before
        // Track state to detect changes between frames
        boolean isTransitionStart = (shadowRenderer.getCurrentFrameState() == ShadowRenderer.FrameState.TRANSITION_START_FRAME);
        String currentMatrixHash = (cascadeData != null ?
            engine.util.SkyFlashTrace.hashMat(cascadeData.lightSpaceMatrices()[0]) : "null");
        int currentPrimaryShadowMap = shadowRenderer.getPrimaryShadowMapArray();

        if (isTransitionStart) {
            System.out.println("[VOXEL] TRANSITION_START shader state:");
            System.out.println("    leapfrogActive=" + (leapfrogTransitioning ? 1 : 0) + " (was " + lastLeapfrogActive + ")");
            System.out.println("    shadowCrossfade=" + crossfadeProgress);
            System.out.println("    cascadeData hash=" + currentMatrixHash + " (was " + lastMatrixHash + ")");
            System.out.println("    primaryShadowMap=" + currentPrimaryShadowMap + " (was " + lastPrimaryShadowMap + ")");
            System.out.println("    CHANGE DETECTED: leapfrog=" + (leapfrogTransitioning != lastLeapfrogActive) +
                " matrix=" + !currentMatrixHash.equals(lastMatrixHash) +
                " shadowMap=" + (currentPrimaryShadowMap != lastPrimaryShadowMap));
        }

        // Store for next frame comparison
        lastLeapfrogActive = leapfrogTransitioning;
        lastMatrixHash = currentMatrixHash;
        lastPrimaryShadowMap = currentPrimaryShadowMap;

        if (leapfrogTransitioning) {
            CascadeData cascadeDataB = shadowRenderer.getSecondaryCascadeData();
            if (cascadeDataB != null) {
                // Set secondary light space matrices
                voxelShader.setUniformMat4Array("lightSpaceMatricesB", cascadeDataB.lightSpaceMatrices());

                // Set secondary cascade split distances
                voxelShader.setUniformFloatArray("cascadeSplitsB", cascadeDataB.splitDistances());

                // Set secondary cascade texel sizes
                voxelShader.setUniformFloatArray("cascadeTexelSizesB", cascadeDataB.texelSizes());
            }
        } else if (cascadeData != null) {
            // Even when not transitioning, set secondary matrices to primary values
            // This prevents undefined behavior from uninitialized uniform arrays
            voxelShader.setUniformMat4Array("lightSpaceMatricesB", cascadeData.lightSpaceMatrices());
            voxelShader.setUniformFloatArray("cascadeSplitsB", cascadeData.splitDistances());
            voxelShader.setUniformFloatArray("cascadeTexelSizesB", cascadeData.texelSizes());
        }

        // Bind texture atlas
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, atlasTextureId);
        voxelShader.setUniform("textureSampler", 0);

        // Bind CSM shadow map arrays (primary and secondary for leapfrog)
        int primaryShadowMap = shadowRenderer.getPrimaryShadowMapArray();
        int secondaryShadowMapForLog = leapfrogTransitioning ?
            shadowRenderer.getSecondaryShadowMapArray() : primaryShadowMap;

        // SKYFLASH TRACE: VOXEL_PRE - before texture binding
        {
            Matrix4f matA0 = (cascadeData != null && cascadeData.lightSpaceMatrices().length > 0)
                ? cascadeData.lightSpaceMatrices()[0] : null;
            CascadeData cascadeDataBForLog = shadowRenderer.getSecondaryCascadeData();
            Matrix4f matB0 = (cascadeDataBForLog != null && cascadeDataBForLog.lightSpaceMatrices().length > 0)
                ? cascadeDataBForLog.lightSpaceMatrices()[0] : null;

            SkyFlashTrace.get().logVoxelPre(
                leapfrogTransitioning ? 1 : 0,
                shadowRenderer.getCrossfadeProgress(),
                cascadeData != null ? cascadeData.cascadeCount() : 0,
                forceCascade, biasMode, pcfMode, normalOffsetMode,
                primaryShadowMap, secondaryShadowMapForLog,
                1, 2,  // shadow texture units
                matA0, matB0,
                skyColor, skyColor, context.getWorld().getTimeManager().getAmbient(), sunDir,
                leapfrogTransitioning
            );
        }
        voxelShader.setTextureArray("shadowMapArray", primaryShadowMap, 1);

        // ALWAYS bind secondary shadow map array to prevent undefined sampler behavior
        // When not transitioning, just bind the same primary array (won't be sampled anyway)
        int secondaryShadowMap = leapfrogTransitioning ?
            shadowRenderer.getSecondaryShadowMapArray() : primaryShadowMap;
        voxelShader.setTextureArray("shadowMapArrayB", secondaryShadowMap, 2);

        // DEBUG PROBE #2: Verify texture binding is correct
        if (debugShadow > 0) {
            glActiveTexture(GL_TEXTURE0 + 1);  // Unit 1 where shadow map should be
            int boundTex = glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY);
            int expectedTex = shadowRenderer.getPrimaryShadowMapArray();
            if (boundTex != expectedTex) {
                System.err.println("[SHADOW DEBUG] BIND ERROR: bound=" + boundTex + " expected=" + expectedTex);
            }
        }

        // Upload any pending meshes from worker threads
        context.getWorld().getMeshManager().uploadMeshesIfAny();

        // DIAGNOSTIC: Log frame snapshot before chunk drawing
        DebugFrameSnapshot.recordSamplerUnits(1, 2);  // Units used above for shadowMapArray and shadowMapArrayB
        DebugFrameSnapshot.logIfDue("VOX_PRE", context, shadowRenderer);

        // Render all visible chunks
        renderVisibleChunks(context);

        // SKYFLASH TRACE: VOXEL_POST - after draw
        SkyFlashTrace.get().logVoxelPost(1, 2, leapfrogTransitioning);

        voxelShader.unbind();
    }

    /**
     * Render all chunks within render distance.
     *
     * @param context Render context
     */
    private void renderVisibleChunks(RenderContext context) {
        // Get player chunk position (assuming player is at camera position)
        Vector3f camPos = context.getCamera().getPosition();
        int cx = (int) Math.floor(camPos.x / GameConfig.CHUNK_WIDTH);
        int cz = (int) Math.floor(camPos.z / GameConfig.CHUNK_DEPTH);

        int radius = context.getWorld().getRenderDistance();

        // Iterate through chunks in render distance
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Chunk chunk = context.getWorld().getChunk(cx + dx, cz + dz);
                if (chunk == null) continue;

                // Calculate model matrix (translation to chunk position)
                Matrix4f model = new Matrix4f()
                        .translation(
                                chunk.getChunkX() * Chunk.WIDTH,
                                0,
                                chunk.getChunkZ() * Chunk.DEPTH
                        );

                // Set model matrix uniform
                voxelShader.setUniform("modelMatrix", model);

                // Render chunk mesh
                MeshBuilder.renderChunk(chunk);
            }
        }
    }

    @Override
    public void cleanup() {
        voxelShader.cleanup();
        shadowRenderer.cleanup();
    }

    /**
     * Get the shadow renderer used by this voxel renderer.
     *
     * @return Shadow renderer instance
     */
    public ShadowRenderer getShadowRenderer() {
        return shadowRenderer;
    }

    /** Toggle AO debug mode (renders AO as grayscale). */
    public void toggleDebugAO() {
        this.debugAO = !this.debugAO;
        System.out.println("AO Debug: " + (debugAO ? "ON" : "OFF"));
    }

    public boolean isDebugAO() {
        return debugAO;
    }

    public void setAoMin(float aoMin) {
        this.aoMin = Math.max(0.0f, Math.min(1.0f, aoMin));
    }

    public float getAoMin() {
        return aoMin;
    }

    /**
     * Cycle shadow debug mode (expanded for discontinuity diagnosis).
     * 0 = OFF
     * 1 = Shadow factor grayscale
     * 2 = Cascade colors (blended)
     * 3 = World-cell hash (PCF pattern debug)
     * 4 = Fog factor grayscale
     * 5 = Cascade SOLID (detect cascade boundary jumps)
     * 6 = Shadow A/B difference (leapfrog blend discontinuity)
     * 7 = Lit color BEFORE fog (isolate fog amplification)
     */
    public void cycleShadowDebug() {
        this.debugShadow = (this.debugShadow + 1) % DEBUG_MODE_COUNT;
        String[] modes = {
            "OFF",
            "Shadow Factor (grayscale)",
            "Cascade Colors (blended)",
            "World-Cell Hash (PCF pattern)",
            "Fog Factor (white=no fog)",
            "Cascade SOLID (boundary jumps)",
            "Shadow A/B Diff (R=diff, G=A, B=B)",
            "Lit Color BEFORE Fog",
            "w-Validity Heatmap (Blue=valid, Green=B invalid)",
            "Leapfrog State (R=active, G=crossfade, B=shadow)"
        };
        System.out.println("Shadow Debug: " + modes[debugShadow]);
    }

    public int getDebugShadow() {
        return debugShadow;
    }

    /**
     * Cycle PCF mode info display.
     * NOTE: Actual PCF mode is now a compile-time constant (SHADOW_FILTER_MODE) in the shader.
     * This method just displays info about the modes for reference.
     */
    public void cyclePcfMode() {
        this.pcfMode = (this.pcfMode + 1) % PCF_MODE_COUNT;
        String[] modes = {
            "Single Sample (Mode 0 - reference)",
            "Current PCF (Mode 1 - world-jittered)",
            "World-Stable PCF (Mode 2 - NEW)"
        };
        System.out.println("PCF Info: " + modes[pcfMode]);
        System.out.println("  NOTE: Change SHADOW_FILTER_MODE in shader to switch modes.");
    }

    public int getPcfMode() {
        return pcfMode;
    }

    /**
     * Cycle forced cascade mode for isolation testing.
     * -1 = normal selection, 0/1/2 = force specific cascade
     */
    public void cycleForceCascade() {
        this.forceCascade = (this.forceCascade + 2) % 4 - 1;  // -1, 0, 1, 2, -1, ...
        String[] modes = {"Normal Selection", "Force Cascade 0 (near)", "Force Cascade 1 (mid)", "Force Cascade 2 (far)"};
        System.out.println("Cascade: " + modes[forceCascade + 1]);
    }

    public int getForceCascade() {
        return forceCascade;
    }

    /**
     * Cycle bias mode for isolation testing.
     * 0 = normal, 1 = constant only, 2 = zero, 3 = doubled
     */
    public void cycleBiasMode() {
        this.biasMode = (this.biasMode + 1) % BIAS_MODE_COUNT;
        String[] modes = {"Normal Bias", "Constant Bias (no slope)", "Zero Bias", "Doubled Bias"};
        System.out.println("Bias Mode: " + modes[biasMode]);
    }

    public int getBiasMode() {
        return biasMode;
    }

    /**
     * Toggle normal-offset shadow mode.
     * 0 = off (baseline), 1 = on (normal offset applied in vertex shader)
     */
    public void toggleNormalOffset() {
        this.normalOffsetMode = (this.normalOffsetMode + 1) % 2;
        String[] modes = {"Normal Offset: OFF", "Normal Offset: ON"};
        System.out.println(modes[normalOffsetMode]);
    }

    public int getNormalOffsetMode() {
        return normalOffsetMode;
    }
}
