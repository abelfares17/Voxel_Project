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
import static org.lwjgl.opengl.GL11.GL_NEAREST;
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
import static org.lwjgl.opengl.GL12.GL_TEXTURE_BASE_LEVEL;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_MAX_LEVEL;
import static org.lwjgl.opengl.GL12.glTexImage3D;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT16;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL14.GL_TEXTURE_COMPARE_MODE;
import static org.lwjgl.opengl.GL30.GL_DEPTH_ATTACHMENT;
import static org.lwjgl.opengl.GL30.GL_DEPTH_COMPONENT32F;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL30.GL_R32F;
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
 * Atlas design: A and B share one texture (shadowAtlas, cascadeCount*2 layers).
 * atlasOffsetA / atlasOffsetB track which half of the atlas each buffer occupies.
 * On SWAP, only the offsets are exchanged — zero-cost, no GPU copy.
 * C uses a separate staging texture (never sampled by the voxel shader).
 */
public class ShadowRenderer implements Renderer {

    private final int fbo;
    private final Shader depthShader;
    private final CSMCalculator csmCalculator;

    // ═══════════════════════════════════════════════════════════════════════════
    // SHADOW MAP TEXTURES
    // ═══════════════════════════════════════════════════════════════════════════

    // Unified atlas for A+B: cascadeCount*2 layers.
    // layers [atlasOffsetA .. atlasOffsetA+C-1] = A
    // layers [atlasOffsetB .. atlasOffsetB+C-1] = B
    private int shadowAtlas;
    private int atlasOffsetA = 0;           // 0 or cascadeCount, swaps on SWAP
    private int atlasOffsetB;               // cascadeCount initially, then swaps with A

    // Staging texture for C — never bound to voxel shader
    private int shadowMapArrayC;

    // GL_R32F color test array (same size/cascadeCount layers — micro-test A)
    private int colorTestArray;

    // ═══════════════════════════════════════════════════════════════════════════
    // CASCADE DATA (MAP↔MATRIX↔BASIS LOCK)
    // ═══════════════════════════════════════════════════════════════════════════

    private CascadeData dataA;
    private CascadeData dataB;
    private CascadeData dataC;

    // ─────────────────────────────────────────────────────────────
    // C STAGING: sliced update (1 cascade per frame)
    // ─────────────────────────────────────────────────────────────
    private boolean cDirty = false;
    private int cCascadeCursor = 0;
    private int cascadeCountCached = -1;

    private boolean renderedAThisFrame = false;
    private boolean renderedBThisFrame = false;
    private boolean renderedCThisFrame = false;

    // ═══════════════════════════════════════════════════════════════════════════
    // FROZEN LIGHT BASIS PER BUFFER (MAP↔MATRIX↔BASIS LOCK)
    // ═══════════════════════════════════════════════════════════════════════════

    private final Vector3f lightDirA = new Vector3f();
    private final Vector3f lightRightA = new Vector3f();
    private final Vector3f lightUpA = new Vector3f();

    private final Vector3f lightDirB = new Vector3f();
    private final Vector3f lightRightB = new Vector3f();
    private final Vector3f lightUpB = new Vector3f();

    private final Vector3f lightDirC = new Vector3f();
    private final Vector3f lightRightC = new Vector3f();
    private final Vector3f lightUpC = new Vector3f();

    // ═══════════════════════════════════════════════════════════════════════════
    // INTEGER SNAP INDICES
    // ═══════════════════════════════════════════════════════════════════════════

    private int lastSnapIxA = Integer.MIN_VALUE, lastSnapIyA = Integer.MIN_VALUE;
    private int lastSnapIxB = Integer.MIN_VALUE, lastSnapIyB = Integer.MIN_VALUE;

    private float snapCooldownA = 0f;
    private float snapCooldownB = 0f;
    private static final float SNAP_MIN_INTERVAL_SEC = 0.12f;

    // ═══════════════════════════════════════════════════════════════════════════
    // QUANTIZED LEAPFROG TIMELINE
    // ═══════════════════════════════════════════════════════════════════════════

    private float baseTimeOfDayA = Float.NaN;
    private float leadTimeDayFrac = 0.0f;

    // ═══════════════════════════════════════════════════════════════════════════
    // LEAPFROG STATE MACHINE
    // ═══════════════════════════════════════════════════════════════════════════

    private enum LeapState { OFF, WARMUP, RUNNING }

    private LeapState leapState = LeapState.WARMUP;
    private boolean leapfrogEnabled = true;

    // ═══════════════════════════════════════════════════════════════════════════
    // TIMING CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════

    private float leadTimeSec = 0.67f;
    private float cUpdateIntervalSec = 0.9f;
    private float crossfadeDurationSec = 0.67f;
    private float crossfadeSpeedPerSec;

    // ═══════════════════════════════════════════════════════════════════════════
    // RUNTIME STATE
    // ═══════════════════════════════════════════════════════════════════════════

    private float crossfadeProgress = 0.0f;
    private float timeSinceCUpdateSec = 0.0f;
    private int blendMode = 0;

    // ═══════════════════════════════════════════════════════════════════════════
    // DEBUG / LOGGING
    // ═══════════════════════════════════════════════════════════════════════════

    private float logAccumulatorSec = 0.0f;
    private static final float LOG_INTERVAL_SEC = 1.0f;
    private boolean cUpdatedThisSecond = false;

    // ═══════════════════════════════════════════════════════════════════════════
    // GL STATE
    // ═══════════════════════════════════════════════════════════════════════════

    private int savedViewportWidth;
    private int savedViewportHeight;

    private final Vector3f camForward = new Vector3f();
    private final Vector3f camRight = new Vector3f();
    private final Vector3f camUp = new Vector3f();

    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTRUCTOR
    // ═══════════════════════════════════════════════════════════════════════════

    public ShadowRenderer(Shader depthShader) {
        this.cascadeCountCached = RenderConfig.CSM_CASCADE_COUNT;
        this.depthShader = depthShader;
        this.csmCalculator = new CSMCalculator();
        this.crossfadeSpeedPerSec = 1.0f / crossfadeDurationSec;

        int cascadeCount = RenderConfig.CSM_CASCADE_COUNT;
        int shadowMapSize = RenderConfig.CSM_SHADOW_MAP_SIZE;

        // Atlas offsets: A starts at layer 0, B starts at layer cascadeCount
        atlasOffsetA = 0;
        atlasOffsetB = cascadeCount;

        // Create FBO
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);

        // Unified atlas for A+B (cascadeCount*2 layers)
        shadowAtlas = createShadowMapArray(cascadeCount * 2, shadowMapSize);

        // Separate staging texture for C
        shadowMapArrayC = createShadowMapArray(cascadeCount, shadowMapSize);

        // Color test array (micro-test A — cascadeCount layers, GL_R32F)
        colorTestArray = createColorTestArray(cascadeCount, shadowMapSize);

        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);

        // Verify FBO completeness; falls back to GL_DEPTH_COMPONENT32F if format unsupported
        checkAndFixAtlasFBO();

        // Initialize all layers to depth 1.0
        clearShadowMapArrayLayers(shadowAtlas, cascadeCount * 2, shadowMapSize);
        clearShadowMapArrayLayers(shadowMapArrayC, cascadeCount, shadowMapSize);

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glDrawBuffer(GL_BACK);
        glReadBuffer(GL_BACK);
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEXTURE CREATION
    // ═══════════════════════════════════════════════════════════════════════════

    private int createShadowMapArray(int layerCount, int shadowMapSize) {
        int textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D_ARRAY, textureId);

        // Select internal format from RenderConfig flag (falls back to 32F on FBO error)
        int internalFormat;
        switch (RenderConfig.SHADOW_MAP_FORMAT) {
            case 1  -> internalFormat = GL_DEPTH_COMPONENT24;
            case 2  -> internalFormat = GL_DEPTH_COMPONENT16;
            default -> internalFormat = GL_DEPTH_COMPONENT32F;
        }

        glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, internalFormat,
                shadowMapSize, shadowMapSize, layerCount,
                0, GL_DEPTH_COMPONENT, GL_FLOAT, (java.nio.ByteBuffer) null);

        // Select filter from RenderConfig flag
        int filter = (RenderConfig.SHADOW_MAP_FILTER == 1) ? GL_NEAREST : GL_LINEAR;
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_MODE, GL_NONE);

        // Optionally pin mipmap levels (depth arrays have 1 level, but explicit is safer)
        if (RenderConfig.SHADOW_BASE_MAX_LEVEL) {
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_BASE_LEVEL, 0);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAX_LEVEL, 0);
        }

        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        return textureId;
    }

    /**
     * Verify that the FBO is complete after attaching the shadow atlas.
     * If incomplete (e.g. unsupported depth format on Metal), logs a warning and
     * recreates the atlas with the fallback GL_DEPTH_COMPONENT32F format.
     */
    private void checkAndFixAtlasFBO() {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        // Attach layer 0 of the atlas temporarily to check completeness
        glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, shadowAtlas, 0, 0);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        if (status != GL_FRAMEBUFFER_COMPLETE) {
            System.err.printf("[ShadowRenderer] FBO incomplete (status=0x%X) for SHADOW_MAP_FORMAT=%d — falling back to GL_DEPTH_COMPONENT32F%n",
                    status, RenderConfig.SHADOW_MAP_FORMAT);
            // Delete bad texture and recreate with 32F
            glDeleteTextures(shadowAtlas);
            int savedFormat = RenderConfig.SHADOW_MAP_FORMAT;
            RenderConfig.SHADOW_MAP_FORMAT = 0;
            shadowAtlas = createShadowMapArray(cascadeCountCached * 2, RenderConfig.CSM_SHADOW_MAP_SIZE);
            RenderConfig.SHADOW_MAP_FORMAT = savedFormat; // restore so CSV reflects intent
        }
    }

    private int createColorTestArray(int layerCount, int shadowMapSize) {
        int textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D_ARRAY, textureId);

        glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_R32F,
                shadowMapSize, shadowMapSize, layerCount,
                0, org.lwjgl.opengl.GL11.GL_RED, GL_FLOAT, (java.nio.ByteBuffer) null);

        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        return textureId;
    }

    private void clearShadowMapArrayLayers(int textureArray, int layerCount, int shadowMapSize) {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, shadowMapSize, shadowMapSize);
        glClearDepth(1.0);
        glDepthMask(true);

        for (int i = 0; i < layerCount; i++) {
            glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, textureArray, 0, i);
            glClear(GL_DEPTH_BUFFER_BIT);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RENDER ENTRY POINT
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void render(RenderContext context) {
        // Perf isolation test C1: skip GPU shadow depth pass entirely.
        // Atlas retains stale content (fully-lit result) — acceptable for timing only.
        if (!RenderConfig.SHADOW_DEPTH_PASS_ENABLED) {
            return;
        }

        savedViewportWidth = context.getWindowWidth();
        savedViewportHeight = context.getWindowHeight();

        try {
            renderInternal(context);
        } finally {
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

        switch (leapState) {
            case WARMUP  -> renderWarmup(context, camera, sunDirNow);
            case RUNNING -> renderRunning(context, camera, sunDirNow);
            case OFF     -> renderOffMode(context, camera, sunDirNow);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LEAPFROG MODES
    // ═══════════════════════════════════════════════════════════════════════════

    private void renderOffMode(RenderContext context, Camera camera, Vector3f sunDir) {
        leapState = LeapState.OFF;
        crossfadeProgress = 1.0f;

        csmCalculator.updateLightBasis(sunDir);
        dataA = csmCalculator.computeCascadeMatrices(camera, camForward, camRight, camUp);
        // Render A into its atlas slot
        renderShadowMapIntoAtlas(context, shadowAtlas, atlasOffsetA, dataA);
        dataB = dataA;
    }

    private void renderWarmup(RenderContext context, Camera camera, Vector3f sunDirNow) {
        var timeManager = context.getWorld().getTimeManager();
        float timeOfDayNow = timeManager.getTimeOfDay();
        float dayPeriodSec = timeManager.getDayNightPeriodSec();

        leadTimeDayFrac = leadTimeSec / dayPeriodSec;
        baseTimeOfDayA = timeOfDayNow;

        float tA = baseTimeOfDayA;
        float tB = (baseTimeOfDayA + leadTimeDayFrac) % 1.0f;
        float tC = (baseTimeOfDayA + 2.0f * leadTimeDayFrac) % 1.0f;

        // A
        SunData sunDataA = timeManager.getSunDataAtTimeOfDay(tA);
        csmCalculator.updateLightBasis(sunDataA.sunDir(), true);
        dataA = csmCalculator.computeCascadeMatrices(camera, camForward, camRight, camUp);
        captureBasis(lightDirA, lightRightA, lightUpA);
        renderShadowMapIntoAtlas(context, shadowAtlas, atlasOffsetA, dataA);
        lastSnapIxA = snapIndexX(dataA);
        lastSnapIyA = snapIndexY(dataA);

        // B
        SunData sunDataB = timeManager.getSunDataAtTimeOfDay(tB);
        csmCalculator.updateLightBasis(sunDataB.sunDir(), true);
        dataB = csmCalculator.computeCascadeMatrices(camera, camForward, camRight, camUp);
        copySplitDistances(dataA, dataB);
        captureBasis(lightDirB, lightRightB, lightUpB);
        renderShadowMapIntoAtlas(context, shadowAtlas, atlasOffsetB, dataB);
        lastSnapIxB = snapIndexX(dataB);
        lastSnapIyB = snapIndexY(dataB);

        // C (staging, separate texture)
        SunData sunDataC = timeManager.getSunDataAtTimeOfDay(tC);
        csmCalculator.updateLightBasis(sunDataC.sunDir(), true);
        dataC = csmCalculator.computeCascadeMatrices(camera, camForward, camRight, camUp);
        copySplitDistances(dataA, dataC);
        captureBasis(lightDirC, lightRightC, lightUpC);
        renderShadowMap(context, shadowMapArrayC, dataC);

        csmCalculator.updateLightBasis(sunDataA.sunDir(), true);

        crossfadeProgress = 0.0f;
        timeSinceCUpdateSec = 0.0f;
        logAccumulatorSec = 0.0f;
        cUpdatedThisSecond = false;

        leapState = LeapState.RUNNING;
    }

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

        leadTimeDayFrac = leadTimeSec / dayPeriodSec;

        if (Float.isNaN(baseTimeOfDayA)) {
            baseTimeOfDayA = timeOfDayNow;
        }

        // Camera snap for A
        CascadeData candA = csmCalculator.computeCascadeMatricesWithBasis(
                camera, camForward, camRight, camUp,
                lightDirA, lightRightA, lightUpA);
        int ixA = snapIndexX(candA), iyA = snapIndexY(candA);
        if (snapCooldownA <= 0f && (ixA != lastSnapIxA || iyA != lastSnapIyA)) {
            dataA = candA;
            renderShadowMapIntoAtlas(context, shadowAtlas, atlasOffsetA, dataA);
            renderedAThisFrame = true;
            lastSnapIxA = ixA;
            lastSnapIyA = iyA;
            snapCooldownA = SNAP_MIN_INTERVAL_SEC;
        }

        // Camera snap for B
        CascadeData candB = csmCalculator.computeCascadeMatricesWithBasis(
                camera, camForward, camRight, camUp,
                lightDirB, lightRightB, lightUpB);
        copySplitDistances(dataA, candB);
        int ixB = snapIndexX(candB), iyB = snapIndexY(candB);
        if (!renderedAThisFrame && snapCooldownB <= 0f && (ixB != lastSnapIxB || iyB != lastSnapIyB)) {
            dataB = candB;
            renderShadowMapIntoAtlas(context, shadowAtlas, atlasOffsetB, dataB);
            renderedBThisFrame = true;
            lastSnapIxB = ixB;
            lastSnapIyB = iyB;
            snapCooldownB = SNAP_MIN_INTERVAL_SEC;
        }

        crossfadeSpeedPerSec = 1.0f / crossfadeDurationSec;
        crossfadeProgress += deltaTime * crossfadeSpeedPerSec;
        timeSinceCUpdateSec += deltaTime;

        if (crossfadeProgress >= 1.0f) {
            crossfadeProgress = 0.0f;
            baseTimeOfDayA = (baseTimeOfDayA + leadTimeDayFrac) % 1.0f;

            // ─────────────────────────────────────────────────────────────────
            // SWAP: exchange atlas offsets (A takes B's slot, B takes A's old slot)
            // and rotate cascade data / bases / snap indices.
            // C's data (from staging) is now assigned to B's slot in the atlas.
            // ─────────────────────────────────────────────────────────────────
            int oldOffsetA = atlasOffsetA;
            atlasOffsetA = atlasOffsetB;   // A now occupies what was B's half
            atlasOffsetB = oldOffsetA;     // B now occupies what was A's half (will be overwritten by new C)

            // Rotate cascade data: A <- B, B <- C, C <- oldA
            CascadeData oldDataA = dataA;
            dataA = dataB;
            dataB = dataC;
            dataC = oldDataA;

            // Rotate bases: A <- B <- C <- oldA
            float dx = lightDirA.x, dy = lightDirA.y, dz = lightDirA.z;
            float rx = lightRightA.x, ry = lightRightA.y, rz = lightRightA.z;
            float ux = lightUpA.x,   uy = lightUpA.y,   uz = lightUpA.z;

            lightDirA.set(lightDirB);   lightRightA.set(lightRightB);   lightUpA.set(lightUpB);
            lightDirB.set(lightDirC);   lightRightB.set(lightRightC);   lightUpB.set(lightUpC);
            lightDirC.set(dx, dy, dz);  lightRightC.set(rx, ry, rz);    lightUpC.set(ux, uy, uz);

            // Rotate snap indices
            lastSnapIxA = lastSnapIxB;
            lastSnapIyA = lastSnapIyB;
            lastSnapIxB = snapIndexX(dataB);
            lastSnapIyB = snapIndexY(dataB);

            // Copy C staging data into B's new atlas slot
            // (C staging texture already has the correct content from sliced updates)
            renderShadowMapIntoAtlas(context, shadowAtlas, atlasOffsetB, dataB);

            // Schedule new C computation at anchored time
            float tC = (baseTimeOfDayA + 2.0f * leadTimeDayFrac) % 1.0f;
            SunData sunDataC = timeManager.getSunDataAtTimeOfDay(tC);
            csmCalculator.updateLightBasis(sunDataC.sunDir(), true);
            dataC = csmCalculator.computeCascadeMatrices(camera, camForward, camRight, camUp);
            copySplitDistances(dataA, dataC);
            captureBasis(lightDirC, lightRightC, lightUpC);
            cDirty = true;
            cCascadeCursor = 0;

            timeSinceCUpdateSec = 0.0f;
            cUpdatedThisSecond = true;

        } else {
            // Periodically recompute C in staging
            if (timeSinceCUpdateSec >= cUpdateIntervalSec) {
                float tC = (baseTimeOfDayA + 2.0f * leadTimeDayFrac) % 1.0f;
                SunData sunDataC = timeManager.getSunDataAtTimeOfDay(tC);

                csmCalculator.updateLightBasis(sunDataC.sunDir(), true);
                dataC = csmCalculator.computeCascadeMatrices(camera, camForward, camRight, camUp);
                copySplitDistances(dataA, dataC);
                captureBasis(lightDirC, lightRightC, lightUpC);

                cDirty = true;
                cCascadeCursor = 0;

                timeSinceCUpdateSec = 0.0f;
                cUpdatedThisSecond = true;
            }
        }

        // Flush C staging progressively: 1 cascade per frame
        if (cDirty && !renderedCThisFrame && !renderedAThisFrame && !renderedBThisFrame) {
            int layer = Math.max(0, Math.min(cCascadeCursor, cascadeCountCached - 1));
            renderShadowMapLayer(context, shadowMapArrayC, dataC, layer);
            renderedCThisFrame = true;

            cCascadeCursor++;
            if (cCascadeCursor >= cascadeCountCached) {
                cDirty = false;
                cCascadeCursor = 0;
            }
        }

        // Debug logging (once per second)
        logAccumulatorSec += deltaTime;
        if (logAccumulatorSec >= LOG_INTERVAL_SEC) {
            System.out.printf("[LEAPFROG] state=%s cf=%.3f atlas=%d offA=%d offB=%d C=%d baseA=%.4f toD=%.4f cUpdated=%b%n",
                    leapState, crossfadeProgress,
                    shadowAtlas, atlasOffsetA, atlasOffsetB, shadowMapArrayC,
                    baseTimeOfDayA, timeOfDayNow, cUpdatedThisSecond);
            logAccumulatorSec = 0.0f;
            cUpdatedThisSecond = false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SHADOW MAP RENDERING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Render all cascades of data into consecutive layers starting at atlasLayerOffset
     * within the given texture array (atlas or standalone).
     */
    private void renderShadowMapIntoAtlas(RenderContext context, int targetArray,
                                          int atlasLayerOffset, CascadeData data) {
        drainGLErrors();

        savedViewportWidth = context.getWindowWidth();
        savedViewportHeight = context.getWindowHeight();

        // Unbind atlas from sampler unit 1 to prevent feedback loop
        glActiveTexture(GL_TEXTURE0 + 1);
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
                int layer = atlasLayerOffset + i;
                glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, targetArray, 0, layer);
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

    /** Render all cascades into a standalone texture (used for C staging). */
    private void renderShadowMap(RenderContext context, int targetArray, CascadeData data) {
        drainGLErrors();

        savedViewportWidth = context.getWindowWidth();
        savedViewportHeight = context.getWindowHeight();

        glActiveTexture(GL_TEXTURE0 + 1);
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

    /** Render a single cascade layer into a standalone texture (sliced C updates). */
    private void renderShadowMapLayer(RenderContext context, int targetArray,
                                      CascadeData data, int cascadeLayer) {
        drainGLErrors();

        savedViewportWidth = context.getWindowWidth();
        savedViewportHeight = context.getWindowHeight();

        glActiveTexture(GL_TEXTURE0 + 1);
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

    // ═══════════════════════════════════════════════════════════════════════════
    // CLEANUP
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void cleanup() {
        depthShader.cleanup();
        if (shadowAtlas != 0)      glDeleteTextures(shadowAtlas);
        if (shadowMapArrayC != 0)  glDeleteTextures(shadowMapArrayC);
        if (colorTestArray != 0)   glDeleteTextures(colorTestArray);
        if (fbo != 0)              glDeleteFramebuffers(fbo);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC ACCESSORS (for VoxelRenderer)
    // ═══════════════════════════════════════════════════════════════════════════

    public CascadeData getCascadeData() { return dataA; }

    /**
     * The unified shadow atlas (A+B in one texture).
     * VoxelRenderer binds this to "shadowAtlas" sampler on unit 1.
     */
    public int getShadowAtlas() { return shadowAtlas; }

    /** Layer offset of A within the atlas (0 or cascadeCount). */
    public int getAtlasOffsetA() { return atlasOffsetA; }

    /** Layer offset of B within the atlas (cascadeCount or 0). */
    public int getAtlasOffsetB() { return atlasOffsetB; }

    /** GL_R32F color test array (micro-test A). */
    public int getColorTestArray() { return colorTestArray; }

    public CascadeData getSecondaryCascadeData() { return dataB; }

    public float getCrossfadeProgress() {
        if (leapState != LeapState.RUNNING) return 1.0f;
        return Math.min(Math.max(crossfadeProgress, 0.0f), 1.0f);
    }

    public boolean isLeapfrogTransitioning() { return leapState == LeapState.RUNNING; }
    public boolean isLeapfrogEnabled()       { return leapfrogEnabled; }

    public void setLeapfrogEnabled(boolean enabled) {
        this.leapfrogEnabled = enabled;
        if (!enabled) {
            leapState = LeapState.OFF;
        } else if (leapState == LeapState.OFF) {
            leapState = LeapState.WARMUP;
        }
    }

    public void toggleLeapfrog() { setLeapfrogEnabled(!leapfrogEnabled); }

    // Legacy accessors
    @Deprecated public int getShadowMapArray()  { return shadowAtlas; }
    @Deprecated public int getDepthTexture()    { return shadowAtlas; }

    @Deprecated
    public Matrix4f getLightSpaceMatrix() {
        if (dataA != null && dataA.lightSpaceMatrices().length > 0) {
            return dataA.lightSpaceMatrices()[0];
        }
        return new Matrix4f();
    }

    public int getCascadeCount() { return RenderConfig.CSM_CASCADE_COUNT; }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private void copySplitDistances(CascadeData src, CascadeData dst) {
        if (src != null && dst != null) {
            System.arraycopy(src.splitDistances(), 0, dst.splitDistances(), 0,
                    Math.min(src.splitDistances().length, dst.splitDistances().length));
        }
    }

    private void captureBasis(Vector3f outDir, Vector3f outRight, Vector3f outUp) {
        outDir.set(csmCalculator.getLightDir());
        outRight.set(csmCalculator.getLightRight());
        outUp.set(csmCalculator.getLightUp());
    }

    private int snapIndexX(CascadeData d) {
        if (d == null) return 0;
        float snappedX = -d.lightSpaceMatrices()[0].m30();
        float texel = d.texelSizes()[0];
        return Math.round(snappedX / texel);
    }

    private int snapIndexY(CascadeData d) {
        if (d == null) return 0;
        float snappedY = -d.lightSpaceMatrices()[0].m31();
        float texel = d.texelSizes()[0];
        return Math.round(snappedY / texel);
    }

    private void computeCameraBasisFromViewMatrix(Matrix4f view) {
        Matrix4f invView = new Matrix4f(view).invert();
        camRight.set(invView.m00(), invView.m01(), invView.m02()).normalize();
        camUp.set(invView.m10(), invView.m11(), invView.m12()).normalize();
        camForward.set(-invView.m20(), -invView.m21(), -invView.m22()).normalize();
        camRight.cross(camForward, camUp).normalize();
    }

    private void drainGLErrors() {
        while (glGetError() != GL_NO_ERROR) { }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CSM DIAGNOSTIC CONTROLS
    // ═══════════════════════════════════════════════════════════════════════════

    public void toggleFreezeLightBasis() { csmCalculator.setFreezeLightBasis(!csmCalculator.isFreezeLightBasis()); }
    public void toggleFreezeSnappedXY()  { csmCalculator.setFreezeSnappedXY(!csmCalculator.isFreezeSnappedXY()); }
    public void toggleFreezeZRange()     { csmCalculator.setFreezeZRange(!csmCalculator.isFreezeZRange()); }
    public boolean isFreezeLightBasis()  { return csmCalculator.isFreezeLightBasis(); }
    public boolean isFreezeSnappedXY()   { return csmCalculator.isFreezeSnappedXY(); }
    public boolean isFreezeZRange()      { return csmCalculator.isFreezeZRange(); }

    // ═══════════════════════════════════════════════════════════════════════════
    // BLEND MODE
    // ═══════════════════════════════════════════════════════════════════════════

    public void toggleBlendMode()    { blendMode = (blendMode + 1) % 2; }
    public int  getBlendMode()       { return blendMode; }
    public boolean isDitherBlend()   { return blendMode == 0; }
}
