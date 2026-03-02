package engine.render.renderer;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import static org.lwjgl.opengl.GL11.GL_BACK;
import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_LESS;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_NONE;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearDepth;
import static org.lwjgl.opengl.GL11.glColorMask;
import static org.lwjgl.opengl.GL11.glCullFace;
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
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL14.GL_TEXTURE_COMPARE_MODE;
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
import engine.time.SunData;

/**
 * Renderer responsible for Cascaded Shadow Mapping (CSM).
 * Uses triple-buffered leapfrogging (A/B/C) for continuous shadow updates.
 *
 * Triple-buffer design:
 * - A: Current shadow map (primary sampler in shader)
 * - B: Next shadow map being crossfaded to (secondary sampler in shader)
 * - C: Staging shadow map updated in background (NOT sampled by shader)
 *
 * This eliminates pauses between crossfades by always having C ready
 * to become the new B when the A->B crossfade completes.
 */
public class ShadowRenderer implements Renderer {

    private final int fbo;
    private final Shader depthShader;
    private final CSMCalculator csmCalculator;

    // ═══════════════════════════════════════════════════════════════════════════
    // TRIPLE-BUFFERED SHADOW MAPS (A/B/C)
    // ═══════════════════════════════════════════════════════════════════════════

    // Texture array IDs
    private int shadowMapArrayA;
    private int shadowMapArrayB;
    private int shadowMapArrayC;

    // Corresponding cascade data (MAP↔MATRIX LOCK: each texture uses its own matrices)
    private CascadeData dataA;
    private CascadeData dataB;
    private CascadeData dataC;

    // ─────────────────────────────────────────────────────────────
    // C STAGING: sliced update (1 cascade per frame)
    // ─────────────────────────────────────────────────────────────
    private boolean cDirty = false;          // true => dataC needs to be rendered into shadowMapArrayC
    private int cCascadeCursor = 0;          // which cascade layer to render next
    private int cascadeCountCached = -1;     // cache cascadeCount for cursor logic

    // Optional: throttle A/B snap updates (prevents double-render in the same frame)
    private boolean renderedAThisFrame = false;
    private boolean renderedBThisFrame = false;
    private boolean renderedCThisFrame = false;

    // ═══════════════════════════════════════════════════════════════════════════
    // FROZEN LIGHT BASIS PER BUFFER (MAP↔MATRIX↔BASIS LOCK)
    // Each buffer captures its light basis at render time and keeps it immutable
    // ═══════════════════════════════════════════════════════════════════════════

    // Buffer A light basis (frozen at render time)
    private final Vector3f lightDirA = new Vector3f();
    private final Vector3f lightRightA = new Vector3f();
    private final Vector3f lightUpA = new Vector3f();

    // Buffer B light basis (frozen at render time)
    private final Vector3f lightDirB = new Vector3f();
    private final Vector3f lightRightB = new Vector3f();
    private final Vector3f lightUpB = new Vector3f();

    // Buffer C light basis (frozen at render time)
    private final Vector3f lightDirC = new Vector3f();
    private final Vector3f lightRightC = new Vector3f();
    private final Vector3f lightUpC = new Vector3f();

    // ═══════════════════════════════════════════════════════════════════════════
    // INTEGER SNAP INDICES (avoids float comparison issues)
    // ═══════════════════════════════════════════════════════════════════════════

    private int lastSnapIxA = Integer.MIN_VALUE, lastSnapIyA = Integer.MIN_VALUE;
    private int lastSnapIxB = Integer.MIN_VALUE, lastSnapIyB = Integer.MIN_VALUE;

    private float snapCooldownA = 0f;
    private float snapCooldownB = 0f;
    private static final float SNAP_MIN_INTERVAL_SEC = 0.12f; // ~8 Hz (à ajuster)

    // ═══════════════════════════════════════════════════════════════════════════
    // QUANTIZED LEAPFROG TIMELINE (anchored on A)
    // ═══════════════════════════════════════════════════════════════════════════

    // Timeline anchor: timeOfDay value that A corresponds to
    // B = baseTimeOfDayA + leadTimeDayFrac, C = baseTimeOfDayA + 2*leadTimeDayFrac
    private float baseTimeOfDayA = Float.NaN;

    // Lead time expressed as fraction of day cycle (leadTimeSec / dayPeriodSec)
    private float leadTimeDayFrac = 0.0f;

    // ═══════════════════════════════════════════════════════════════════════════
    // LEAPFROG STATE MACHINE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Leapfrog state machine:
     * - OFF: Leapfrog disabled, single shadow map mode
     * - WARMUP: Initializing A, B, C for the first time
     * - RUNNING: Continuous crossfade A->B with background updates of C
     */
    private enum LeapState {
        OFF,
        WARMUP,
        RUNNING
    }

    private LeapState leapState = LeapState.WARMUP;
    private boolean leapfrogEnabled = true;

    // ═══════════════════════════════════════════════════════════════════════════
    // TIMING CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════

    // How far ahead B represents (in seconds of real time)
    private float leadTimeSec = 0.67f;

    // How often to refresh C (10 Hz default)
    private float cUpdateIntervalSec = 0.9f;

    // Duration of one crossfade cycle A->B (seconds)
    private float crossfadeDurationSec = 0.67f;

    // Derived: crossfade speed per second
    private float crossfadeSpeedPerSec;

    // ═══════════════════════════════════════════════════════════════════════════
    // RUNTIME STATE
    // ═══════════════════════════════════════════════════════════════════════════

    // Current crossfade progress [0, 1]
    private float crossfadeProgress = 0.0f;

    // Time accumulator for C updates
    private float timeSinceCUpdateSec = 0.0f;

    // Blend mode: 0 = dither (stochastic), 1 = linear (smooth mix)
    private int blendMode = 0;

    // ═══════════════════════════════════════════════════════════════════════════
    // DEBUG / LOGGING
    // ═══════════════════════════════════════════════════════════════════════════

    private float logAccumulatorSec = 0.0f;
    private static final float LOG_INTERVAL_SEC = 1.0f;
    private boolean cUpdatedThisSecond = false;

    // ═══════════════════════════════════════════════════════════════════════════
    // GL STATE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    private int savedViewportWidth;
    private int savedViewportHeight;

    // Temporary vectors for camera basis computation
    private final Vector3f camForward = new Vector3f();
    private final Vector3f camRight = new Vector3f();
    private final Vector3f camUp = new Vector3f();

    public ShadowRenderer(Shader depthShader) {
        this.cascadeCountCached = RenderConfig.CSM_CASCADE_COUNT;
        this.depthShader = depthShader;
        this.csmCalculator = new CSMCalculator();
        this.crossfadeSpeedPerSec = 1.0f / crossfadeDurationSec;

        int cascadeCount = RenderConfig.CSM_CASCADE_COUNT;
        int shadowMapSize = RenderConfig.CSM_SHADOW_MAP_SIZE;

        // Create FBO (shared for all shadow map renders)
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);

        // Create all three shadow map arrays (A, B, C)
        shadowMapArrayA = createShadowMapArray(cascadeCount, shadowMapSize);
        shadowMapArrayB = createShadowMapArray(cascadeCount, shadowMapSize);
        shadowMapArrayC = createShadowMapArray(cascadeCount, shadowMapSize);

        // Verify FBO completeness with first texture
        glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, shadowMapArrayA, 0, 0);
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);

        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Shadow framebuffer incomplete: " + status);
        }

        // Initialize all shadow map arrays to depth 1.0
        clearShadowMapArray(shadowMapArrayA, cascadeCount, shadowMapSize);
        clearShadowMapArray(shadowMapArrayB, cascadeCount, shadowMapSize);
        clearShadowMapArray(shadowMapArrayC, cascadeCount, shadowMapSize);

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glDrawBuffer(GL_BACK);
        glReadBuffer(GL_BACK);
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
    }

    private int createShadowMapArray(int cascadeCount, int shadowMapSize) {
        int textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D_ARRAY, textureId);

        glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_DEPTH_COMPONENT32F,
                shadowMapSize, shadowMapSize, cascadeCount,
                0, GL_DEPTH_COMPONENT, GL_FLOAT, (java.nio.ByteBuffer) null);

        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_MODE, GL_NONE);

        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        return textureId;
    }

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
        savedViewportWidth = context.getWindowWidth();
        savedViewportHeight = context.getWindowHeight();

        try {
            renderInternal(context);
        } finally {
            // Guaranteed GL state restoration
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDrawBuffer(GL_BACK);
            glReadBuffer(GL_BACK);
            glColorMask(true, true, true, true);
            glDepthMask(true);
            glDepthFunc(GL_LESS);
            glViewport(0, 0, savedViewportWidth, savedViewportHeight);
            drainGLErrors();
        }
    }

    private void renderInternal(RenderContext context) {
        Camera camera = context.getCamera();
        Matrix4f view;
        if (camera instanceof PerspectiveCamera pc) {
            view = pc.getViewMatrix();
        } else {
            view = camera.getViewMatrix(camera.getPosition(), 0, 0);
        }
        computeCameraBasisFromViewMatrix(view);

        Vector3f sunDirNow = context.getSunDirShadows();

        if (!leapfrogEnabled) {
            renderOffMode(context, camera, sunDirNow);
            return;
        }

        // Leapfrog enabled - use triple-buffered pipeline
        switch (leapState) {
            case WARMUP -> renderWarmup(context, camera, sunDirNow);
            case RUNNING -> renderRunning(context, camera, sunDirNow);
            case OFF -> renderOffMode(context, camera, sunDirNow);
        }
    }

    /**
     * OFF mode: Leapfrog disabled, single shadow map render.
     */
    private void renderOffMode(RenderContext context, Camera camera, Vector3f sunDir) {
        leapState = LeapState.OFF;
        crossfadeProgress = 1.0f;

        csmCalculator.updateLightBasis(sunDir);
        dataA = csmCalculator.computeCascadeMatrices(camera, camForward, camRight, camUp);
        renderShadowMap(context, shadowMapArrayA, dataA);

        // dataB points to same as A when not transitioning
        dataB = dataA;
    }

    /**
     * WARMUP: Initialize all three shadow maps (A, B, C) for the first time.
     * Captures light basis for each buffer at render time.
     * Initializes the quantized leapfrog timeline anchored on A.
     */
    private void renderWarmup(RenderContext context, Camera camera, Vector3f sunDirNow) {
        var timeManager = context.getWorld().getTimeManager();
        float timeOfDayNow = timeManager.getTimeOfDay();
        float dayPeriodSec = timeManager.getDayNightPeriodSec();

        // ─────────────────────────────────────────────────────────────────────
        // Initialize quantized leapfrog timeline
        // A = tA, B = tA + lead, C = tA + 2*lead
        // ─────────────────────────────────────────────────────────────────────
        leadTimeDayFrac = leadTimeSec / dayPeriodSec;
        baseTimeOfDayA = timeOfDayNow;  // Anchor A timeline at warmup moment

        float tA = baseTimeOfDayA;
        float tB = (baseTimeOfDayA + leadTimeDayFrac) % 1.0f;
        float tC = (baseTimeOfDayA + 2.0f * leadTimeDayFrac) % 1.0f;

        // ─────────────────────────────────────────────────────────────────────
        // A = current sun position (tA)
        // ─────────────────────────────────────────────────────────────────────
        SunData sunDataA = timeManager.getSunDataAtTimeOfDay(tA);
        csmCalculator.updateLightBasis(sunDataA.sunDir(), true);
        dataA = csmCalculator.computeCascadeMatrices(camera, camForward, camRight, camUp);
        captureBasis(lightDirA, lightRightA, lightUpA);  // FREEZE basis for A
        renderShadowMap(context, shadowMapArrayA, dataA);
        lastSnapIxA = snapIndexX(dataA);
        lastSnapIyA = snapIndexY(dataA);

        // ─────────────────────────────────────────────────────────────────────
        // B = sun position at tB (tA + lead)
        // ─────────────────────────────────────────────────────────────────────
        SunData sunDataB = timeManager.getSunDataAtTimeOfDay(tB);
        csmCalculator.updateLightBasis(sunDataB.sunDir(), true);
        dataB = csmCalculator.computeCascadeMatrices(camera, camForward, camRight, camUp);
        copySplitDistances(dataA, dataB);
        captureBasis(lightDirB, lightRightB, lightUpB);  // FREEZE basis for B
        renderShadowMap(context, shadowMapArrayB, dataB);
        lastSnapIxB = snapIndexX(dataB);
        lastSnapIyB = snapIndexY(dataB);

        // ─────────────────────────────────────────────────────────────────────
        // C = sun position at tC (tA + 2*lead)
        // ─────────────────────────────────────────────────────────────────────
        SunData sunDataC = timeManager.getSunDataAtTimeOfDay(tC);
        csmCalculator.updateLightBasis(sunDataC.sunDir(), true);
        dataC = csmCalculator.computeCascadeMatrices(camera, camForward, camRight, camUp);
        copySplitDistances(dataA, dataC);
        captureBasis(lightDirC, lightRightC, lightUpC);  // FREEZE basis for C
        renderShadowMap(context, shadowMapArrayC, dataC);

        // Reset light basis to current for consistent state (does NOT affect stored bases)
        csmCalculator.updateLightBasis(sunDataA.sunDir(), true);

        // Initialize timing
        crossfadeProgress = 0.0f;
        timeSinceCUpdateSec = 0.0f;
        logAccumulatorSec = 0.0f;
        cUpdatedThisSecond = false;

        leapState = LeapState.RUNNING;
    }

    /**
     * RUNNING: Continuous crossfade A->B with background updates of C.
     *
     * CRITICAL FIX: A and B use FROZEN light bases (captured at render time).
     * This prevents shimmer by ensuring matrices don't change continuously.
     *
     * Camera snap updates for A/B use computeCascadeMatricesWithBasis() to
     * recompute matrices with camera changes while keeping the light basis frozen.
     *
     * C is computed using ANCHORED timeline: tC = baseTimeOfDayA + 2*leadTimeDayFrac
     * NOT using timeOfDayNow (which would cause temporal drift).
     *
     * When crossfade reaches 1.0: advance anchor, rotate A<-B<-C, render new C.
     */
    private void renderRunning(RenderContext context, Camera camera, Vector3f sunDirNow) {
        renderedAThisFrame = false;
        renderedBThisFrame = false;
        renderedCThisFrame = false;
        snapCooldownA = Math.max(0f, snapCooldownA - context.getDeltaTime());
        snapCooldownB = Math.max(0f, snapCooldownB - context.getDeltaTime());
        float deltaTime = context.getDeltaTime();

        var timeManager = context.getWorld().getTimeManager();
        float timeOfDayNow = context.getTimeOfDay();
        float dayPeriodSec = context.getDayNightPeriodSec();

        // Refresh leadTimeDayFrac in case dayPeriodSec changed
        leadTimeDayFrac = leadTimeSec / dayPeriodSec;

        // Safety: if baseTimeOfDayA is NaN, reinitialize
        if (Float.isNaN(baseTimeOfDayA)) {
            baseTimeOfDayA = timeOfDayNow;
        }

        // ───────────────────────────────────────────────────────────────────────
        // CAMERA SNAP UPDATE FOR A (uses FROZEN basisA - NO updateLightBasis!)
        // ───────────────────────────────────────────────────────────────────────
        CascadeData candA = csmCalculator.computeCascadeMatricesWithBasis(
                camera, camForward, camRight, camUp,
                lightDirA, lightRightA, lightUpA);
        int ixA = snapIndexX(candA), iyA = snapIndexY(candA);
        if (snapCooldownA <= 0f && (ixA != lastSnapIxA || iyA != lastSnapIyA)) {
            dataA = candA;
            // basisA stays FROZEN - do NOT recapture
            renderShadowMap(context, shadowMapArrayA, dataA);
            renderedAThisFrame = true;
            lastSnapIxA = ixA;
            lastSnapIyA = iyA;
            snapCooldownA = SNAP_MIN_INTERVAL_SEC;
        }

        // ───────────────────────────────────────────────────────────────────────
        // CAMERA SNAP UPDATE FOR B (uses FROZEN basisB - NO updateLightBasis!)
        // ───────────────────────────────────────────────────────────────────────
        CascadeData candB = csmCalculator.computeCascadeMatricesWithBasis(
                camera, camForward, camRight, camUp,
                lightDirB, lightRightB, lightUpB);
        copySplitDistances(dataA, candB);
        int ixB = snapIndexX(candB), iyB = snapIndexY(candB);
        if (!renderedAThisFrame && snapCooldownB <= 0f && (ixB != lastSnapIxB || iyB != lastSnapIyB)) {
            dataB = candB;
            renderShadowMap(context, shadowMapArrayB, dataB);
            renderedBThisFrame = true;
            lastSnapIxB = ixB;
            lastSnapIyB = iyB;
            snapCooldownB = SNAP_MIN_INTERVAL_SEC;
        }

        // Compute crossfade speed (may change if crossfadeDurationSec is modified)
        crossfadeSpeedPerSec = 1.0f / crossfadeDurationSec;

        // Progress crossfade
        crossfadeProgress += deltaTime * crossfadeSpeedPerSec;

        // Accumulate time for C updates
        timeSinceCUpdateSec += deltaTime;

        // ───────────────────────────────────────────────────────────────────────
        // Check if crossfade complete -> SWAP
        // ───────────────────────────────────────────────────────────────────────
        if (crossfadeProgress >= 1.0f) {
            crossfadeProgress = 0.0f;

            // ─────────────────────────────────────────────────────────────────
            // ADVANCE TIMELINE ANCHOR: new A corresponds to old B's time
            // ─────────────────────────────────────────────────────────────────
            baseTimeOfDayA = (baseTimeOfDayA + leadTimeDayFrac) % 1.0f;

            // ─────────────────────────────────────────────────────────────────
            // Ring rotation: A <- B <- C <- oldA
            // Must also rotate: bases, cascade data, and snap indices
            // ─────────────────────────────────────────────────────────────────
            int oldTexA = shadowMapArrayA;
            CascadeData oldDataA = dataA;

            // Rotate textures and data
            shadowMapArrayA = shadowMapArrayB;
            dataA = dataB;

            shadowMapArrayB = shadowMapArrayC;
            dataB = dataC;

            shadowMapArrayC = oldTexA;
            dataC = oldDataA;

            // Rotate bases: A <- B <- C <- (will be overwritten)
            // Save old A basis temporarily
            float oldDirAx = lightDirA.x, oldDirAy = lightDirA.y, oldDirAz = lightDirA.z;
            float oldRightAx = lightRightA.x, oldRightAy = lightRightA.y, oldRightAz = lightRightA.z;
            float oldUpAx = lightUpA.x, oldUpAy = lightUpA.y, oldUpAz = lightUpA.z;

            // A <- B
            lightDirA.set(lightDirB);
            lightRightA.set(lightRightB);
            lightUpA.set(lightUpB);

            // B <- C
            lightDirB.set(lightDirC);
            lightRightB.set(lightRightC);
            lightUpB.set(lightUpC);

            // C <- oldA (temporary, will be overwritten when we render new C)
            lightDirC.set(oldDirAx, oldDirAy, oldDirAz);
            lightRightC.set(oldRightAx, oldRightAy, oldRightAz);
            lightUpC.set(oldUpAx, oldUpAy, oldUpAz);

            // Rotate snap indices: A gets B's snap, B gets new dataB's snap (from old C)
            lastSnapIxA = lastSnapIxB;
            lastSnapIyA = lastSnapIyB;
            // Preserve B's snap indices from rotated dataB (avoids unnecessary re-render)
            lastSnapIxB = snapIndexX(dataB);
            lastSnapIyB = snapIndexY(dataB);

            // ─────────────────────────────────────────────────────────────────
            // Immediately render new C at ANCHORED time: baseTimeOfDayA + 2*lead
            // C is allowed to call updateLightBasis (fresh basis)
            // ─────────────────────────────────────────────────────────────────
            float tC = (baseTimeOfDayA + 2.0f * leadTimeDayFrac) % 1.0f;
            SunData sunDataC = timeManager.getSunDataAtTimeOfDay(tC);
            csmCalculator.updateLightBasis(sunDataC.sunDir(), true);
            dataC = csmCalculator.computeCascadeMatrices(camera, camForward, camRight, camUp);
            copySplitDistances(dataA, dataC);
            captureBasis(lightDirC, lightRightC, lightUpC);  // FREEZE new basis for C
            cDirty = true;
            cCascadeCursor = 0;

            timeSinceCUpdateSec = 0.0f;
            cUpdatedThisSecond = true;

        } else {
            // ───────────────────────────────────────────────────────────────────
            // Periodically update C (NOT A or B!)
            // C is computed at ANCHORED time: baseTimeOfDayA + 2*lead
            // ───────────────────────────────────────────────────────────────────
            if (timeSinceCUpdateSec >= cUpdateIntervalSec) {
                float tC = (baseTimeOfDayA + 2.0f * leadTimeDayFrac) % 1.0f;
                SunData sunDataC = timeManager.getSunDataAtTimeOfDay(tC);

                csmCalculator.updateLightBasis(sunDataC.sunDir(), true);
                dataC = csmCalculator.computeCascadeMatrices(camera, camForward, camRight, camUp);
                copySplitDistances(dataA, dataC);
                captureBasis(lightDirC, lightRightC, lightUpC);

                // Instead of rendering ALL cascades now:
                cDirty = true;
                cCascadeCursor = 0;

                timeSinceCUpdateSec = 0.0f;
                cUpdatedThisSecond = true;
            }
        }
        // ─────────────────────────────────────────────────────────────
        // Flush C progressively: 1 cascade per frame
        // ─────────────────────────────────────────────────────────────
        if (cDirty && !renderedCThisFrame && !renderedAThisFrame && !renderedBThisFrame) {
            int layer = cCascadeCursor;

            // Safety
            if (layer < 0) layer = 0;
            if (layer >= cascadeCountCached) layer = cascadeCountCached - 1;

            renderShadowMapLayer(context, shadowMapArrayC, dataC, layer);
            renderedCThisFrame = true;

            cCascadeCursor++;
            if (cCascadeCursor >= cascadeCountCached) {
                cDirty = false;        // done
                cCascadeCursor = 0;
            }
        }

        // ───────────────────────────────────────────────────────────────────────
        // Debug logging (once per second)
        // ───────────────────────────────────────────────────────────────────────
        logAccumulatorSec += deltaTime;
        if (logAccumulatorSec >= LOG_INTERVAL_SEC) {
            System.out.printf("[LEAPFROG] state=%s cf=%.3f A=%d B=%d C=%d baseA=%.4f toD=%.4f cUpdated=%b%n",
                    leapState, crossfadeProgress,
                    shadowMapArrayA, shadowMapArrayB, shadowMapArrayC,
                    baseTimeOfDayA, timeOfDayNow, cUpdatedThisSecond);
            logAccumulatorSec = 0.0f;
            cUpdatedThisSecond = false;
        }
    }

    private void renderShadowMapLayer(RenderContext context, int targetArray, CascadeData data, int cascadeLayer) {
        drainGLErrors();

        savedViewportWidth = context.getWindowWidth();
        savedViewportHeight = context.getWindowHeight();

        // Feedback guard
        glActiveTexture(GL_TEXTURE0 + 1);
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        glActiveTexture(GL_TEXTURE0 + 2);
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        glActiveTexture(GL_TEXTURE0);

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

            glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, targetArray, 0, cascadeLayer);
            glClear(GL_DEPTH_BUFFER_BIT);

            depthShader.setMat4("lightSpaceMatrix", data.lightSpaceMatrices()[cascadeLayer]);
            context.getWorld().renderDepth(depthShader);

            depthShader.unbind();
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDrawBuffer(GL_BACK);
            glReadBuffer(GL_BACK);
            glViewport(0, 0, savedViewportWidth, savedViewportHeight);
            glColorMask(true, true, true, true);
            glDepthMask(true);
            glDepthFunc(GL_LESS);
            drainGLErrors();
        }
    }

    /**
     * Copy split distances from source to destination.
     * Ensures consistent cascade selection between A, B, C.
     */
    private void copySplitDistances(CascadeData src, CascadeData dst) {
        if (src != null && dst != null) {
            System.arraycopy(src.splitDistances(), 0, dst.splitDistances(), 0,
                    Math.min(src.splitDistances().length, dst.splitDistances().length));
        }
    }

    /**
     * Render shadow map into target texture array.
     *
     * CRITICAL: This method unbinds texture units 1 and 2 to prevent
     * read/write hazards (GPU feedback loop).
     */
    private void renderShadowMap(RenderContext context, int targetArray, CascadeData data) {
        drainGLErrors();

        savedViewportWidth = context.getWindowWidth();
        savedViewportHeight = context.getWindowHeight();

        // ─────────────────────────────────────────────────────────────────────
        // FEEDBACK GUARD: Unbind shadow arrays from sampler units
        // Prevents rendering into a texture that might be sampled
        // ─────────────────────────────────────────────────────────────────────
        glActiveTexture(GL_TEXTURE0 + 1);
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        glActiveTexture(GL_TEXTURE0 + 2);
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        glActiveTexture(GL_TEXTURE0);

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
                glClear(GL_DEPTH_BUFFER_BIT);
                depthShader.setMat4("lightSpaceMatrix", data.lightSpaceMatrices()[i]);
                context.getWorld().renderDepth(depthShader);
            }

            depthShader.unbind();
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDrawBuffer(GL_BACK);
            glReadBuffer(GL_BACK);
            glViewport(0, 0, savedViewportWidth, savedViewportHeight);
            glColorMask(true, true, true, true);
            glDepthMask(true);
            glDepthFunc(GL_LESS);
            drainGLErrors();
        }
    }

    private void drainGLErrors() {
        while (glGetError() != GL_NO_ERROR) {
            // Silently drain errors
        }
    }

    private void computeCameraBasisFromViewMatrix(Matrix4f view) {
        Matrix4f invView = new Matrix4f(view).invert();

        camRight.set(invView.m00(), invView.m01(), invView.m02()).normalize();
        camUp.set(invView.m10(), invView.m11(), invView.m12()).normalize();
        camForward.set(-invView.m20(), -invView.m21(), -invView.m22()).normalize();

        camRight.cross(camForward, camUp).normalize();
    }

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

    @Override
    public void cleanup() {
        depthShader.cleanup();
        if (shadowMapArrayA != 0) {
            glDeleteTextures(shadowMapArrayA);
        }
        if (shadowMapArrayB != 0) {
            glDeleteTextures(shadowMapArrayB);
        }
        if (shadowMapArrayC != 0) {
            glDeleteTextures(shadowMapArrayC);
        }
        if (fbo != 0) {
            glDeleteFramebuffers(fbo);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC ACCESSORS (for VoxelRenderer)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get primary cascade data (A).
     * VoxelRenderer uses this for lightSpaceMatrices uniform.
     */
    public CascadeData getCascadeData() {
        return dataA;
    }

    /**
     * Get primary shadow map array (A).
     * VoxelRenderer binds this to shadowMapArray sampler.
     */
    public int getPrimaryShadowMapArray() {
        return shadowMapArrayA;
    }

    /**
     * Get secondary shadow map array (B) for crossfade.
     * VoxelRenderer binds this to shadowMapArrayB sampler.
     *
     * In RUNNING state, always returns B.
     * In OFF state, returns A as fallback (same as primary).
     */
    public int getSecondaryShadowMapArray() {
        if (leapState == LeapState.RUNNING) {
            return shadowMapArrayB;
        }
        // Fallback: return A to avoid null/zero texture
        return shadowMapArrayA;
    }

    /**
     * Get secondary cascade data (B) for crossfade.
     * VoxelRenderer uses this for lightSpaceMatricesB uniform.
     */
    public CascadeData getSecondaryCascadeData() {
        return dataB;
    }

    /**
     * Get current crossfade progress [0, 1].
     * 0 = fully A, 1 = fully B.
     */
    public float getCrossfadeProgress() {
        if (leapState != LeapState.RUNNING) {
            return 1.0f; // Not transitioning -> shader uses A only
        }
        return Math.min(Math.max(crossfadeProgress, 0.0f), 1.0f);
    }

    /**
     * Returns true when in RUNNING state (continuous crossfade active).
     * VoxelRenderer uses this to set leapfrogActive uniform.
     */
    public boolean isLeapfrogTransitioning() {
        return leapState == LeapState.RUNNING;
    }

    public boolean isLeapfrogEnabled() {
        return leapfrogEnabled;
    }

    public void setLeapfrogEnabled(boolean enabled) {
        this.leapfrogEnabled = enabled;
        if (!enabled) {
            leapState = LeapState.OFF;
        } else if (leapState == LeapState.OFF) {
            leapState = LeapState.WARMUP;
        }
    }

    public void toggleLeapfrog() {
        setLeapfrogEnabled(!leapfrogEnabled);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LEGACY ACCESSORS (for compatibility)
    // ═══════════════════════════════════════════════════════════════════════════

    @Deprecated
    public int getShadowMapArray() {
        return shadowMapArrayA;
    }

    @Deprecated
    public Matrix4f getLightSpaceMatrix() {
        if (dataA != null && dataA.lightSpaceMatrices().length > 0) {
            return dataA.lightSpaceMatrices()[0];
        }
        return new Matrix4f();
    }

    @Deprecated
    public int getDepthTexture() {
        return shadowMapArrayA;
    }

    public int getCascadeCount() {
        return RenderConfig.CSM_CASCADE_COUNT;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Capture the current light basis from CSMCalculator into the specified vectors.
     */
    private void captureBasis(Vector3f outDir, Vector3f outRight, Vector3f outUp) {
        outDir.set(csmCalculator.getLightDir());
        outRight.set(csmCalculator.getLightRight());
        outUp.set(csmCalculator.getLightUp());
    }

    /**
     * Compute integer snap index from CascadeData (X axis).
     * Uses cascade 0's texel size for quantization.
     */
    private int snapIndexX(CascadeData d) {
        if (d == null) return 0;
        float snappedX = -d.lightSpaceMatrices()[0].m30(); // stored as -snappedX
        float texel = d.texelSizes()[0];
        return Math.round(snappedX / texel);
    }

    /**
     * Compute integer snap index from CascadeData (Y axis).
     * Uses cascade 0's texel size for quantization.
     */
    private int snapIndexY(CascadeData d) {
        if (d == null) return 0;
        float snappedY = -d.lightSpaceMatrices()[0].m31(); // stored as -snappedY
        float texel = d.texelSizes()[0];
        return Math.round(snappedY / texel);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CSM DIAGNOSTIC CONTROLS
    // ═══════════════════════════════════════════════════════════════════════════

    public void toggleFreezeLightBasis() {
        boolean newState = !csmCalculator.isFreezeLightBasis();
        csmCalculator.setFreezeLightBasis(newState);
    }

    public void toggleFreezeSnappedXY() {
        boolean newState = !csmCalculator.isFreezeSnappedXY();
        csmCalculator.setFreezeSnappedXY(newState);
    }

    public void toggleFreezeZRange() {
        boolean newState = !csmCalculator.isFreezeZRange();
        csmCalculator.setFreezeZRange(newState);
    }

    public boolean isFreezeLightBasis() {
        return csmCalculator.isFreezeLightBasis();
    }

    public boolean isFreezeSnappedXY() {
        return csmCalculator.isFreezeSnappedXY();
    }

    public boolean isFreezeZRange() {
        return csmCalculator.isFreezeZRange();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BLEND MODE CONTROLS
    // ═══════════════════════════════════════════════════════════════════════════

    public void toggleBlendMode() {
        blendMode = (blendMode + 1) % 2;
    }

    public int getBlendMode() {
        return blendMode;
    }

    public boolean isDitherBlend() {
        return blendMode == 0;
    }
}
