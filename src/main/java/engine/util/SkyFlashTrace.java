package engine.util;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Targeted logging utility for diagnosing sky flashes during leapfrog CSM transitions.
 * Logs to a file only during transition frames and nearby frames.
 */
public class SkyFlashTrace {

    private static SkyFlashTrace instance;

    private PrintWriter writer;
    private long frameId = 0;
    private boolean enabled = true;

    // Track transition state for conditional logging
    private boolean wasTransitioning = false;
    private long lastSwapFrame = -100;
    private long lastTransitionStartFrame = -100;

    // Buffer to track frames around swap
    private static final int FRAMES_BEFORE_SWAP = 1;
    private static final int FRAMES_AFTER_SWAP = 2;

    private SkyFlashTrace() {
        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String filename = "logs/skyflash_trace_" + timestamp + ".log";

            // Ensure logs directory exists
            java.io.File logsDir = new java.io.File("logs");
            if (!logsDir.exists()) {
                logsDir.mkdirs();
            }

            writer = new PrintWriter(new FileWriter(filename), true);
            writer.println("=== SKYFLASH TRACE LOG ===");
            writer.println("Started: " + new Date());
            writer.println("Purpose: Isolate cause of sky flashes during leapfrog CSM transitions");
            writer.println("");
            writer.println("LOGGING CONDITIONS:");
            writer.println("  - Every frame during transition (isTransitioning=true)");
            writer.println("  - TRANSITION_START frame");
            writer.println("  - TRANSITION_END_SWAP frame");
            writer.println("  - 1 frame before and 2 frames after swap");
            writer.println("");
            writer.println("QUESTIONS TO ANSWER:");
            writer.println("  1) At flash frame: matrix A vs texture A mismatch?");
            writer.println("  2) At flash frame: GL state (depth/fbo/viewport) same as neighbors?");
            writer.println("  3) Flash occurs when leapfrogActive 1->0 or when primaryTexId changes?");
            writer.println("");
            writer.println("════════════════════════════════════════════════════════════════════");
            writer.println("");
        } catch (IOException e) {
            System.err.println("[SkyFlashTrace] Failed to create log file: " + e.getMessage());
            enabled = false;
        }
    }

    public static SkyFlashTrace get() {
        if (instance == null) {
            instance = new SkyFlashTrace();
        }
        return instance;
    }

    public long getFrameId() {
        return frameId;
    }

    public void nextFrame() {
        frameId++;
    }

    /**
     * Check if we should log this frame based on transition state.
     */
    public boolean shouldLog(boolean isTransitioning) {
        if (!enabled) return false;

        // Log during transition
        if (isTransitioning) return true;

        // Log frames around swap
        long framesSinceSwap = frameId - lastSwapFrame;
        if (framesSinceSwap >= -FRAMES_BEFORE_SWAP && framesSinceSwap <= FRAMES_AFTER_SWAP) {
            return true;
        }

        // Log frame before transition start
        long framesSinceStart = frameId - lastTransitionStartFrame;
        if (framesSinceStart >= -FRAMES_BEFORE_SWAP && framesSinceStart <= 0) {
            return true;
        }

        return false;
    }

    /**
     * Mark that a swap occurred this frame.
     */
    public void markSwap() {
        lastSwapFrame = frameId;
    }

    /**
     * Mark that a transition started this frame.
     */
    public void markTransitionStart() {
        lastTransitionStartFrame = frameId;
    }

    /**
     * Update transition tracking state.
     */
    public void updateTransitionState(boolean isTransitioning) {
        wasTransitioning = isTransitioning;
    }

    public boolean wasTransitioning() {
        return wasTransitioning;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITY FUNCTIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Compact hash of matrix (6 key elements).
     */
    public static String hashMat(Matrix4f m) {
        if (m == null) return "null";
        return String.format("m00=%.6f m11=%.6f m22=%.6f m30=%.6f m31=%.6f m32=%.6f",
                m.m00(), m.m11(), m.m22(), m.m30(), m.m31(), m.m32());
    }

    /**
     * Format Vector3f compactly.
     */
    public static String vec3(Vector3f v) {
        if (v == null) return "null";
        return String.format("(%.4f, %.4f, %.4f)", v.x, v.y, v.z);
    }

    /**
     * Format float array compactly.
     */
    public static String floatArr(float[] arr) {
        if (arr == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format("%.4f", arr[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Get current timestamp.
     */
    private String timestamp() {
        return new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GL STATE CAPTURE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Capture and format essential GL state.
     */
    public String captureGLState() {
        StringBuilder sb = new StringBuilder();

        int fbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
        int program = glGetInteger(GL_CURRENT_PROGRAM);

        int[] viewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, viewport);

        boolean depthTest = glIsEnabled(GL_DEPTH_TEST);
        int depthMask = glGetInteger(GL_DEPTH_WRITEMASK);
        int depthFunc = glGetInteger(GL_DEPTH_FUNC);

        boolean blend = glIsEnabled(GL_BLEND);
        boolean cullFace = glIsEnabled(GL_CULL_FACE);

        int[] colorMask = new int[4];
        glGetIntegerv(GL_COLOR_WRITEMASK, colorMask);

        sb.append(String.format("FBO=%d program=%d viewport=[%d,%d,%d,%d] ",
                fbo, program, viewport[0], viewport[1], viewport[2], viewport[3]));
        sb.append(String.format("depthTest=%s depthMask=%d depthFunc=0x%X ",
                depthTest, depthMask, depthFunc));
        sb.append(String.format("blend=%s cullFace=%s colorMask=[%d,%d,%d,%d]",
                blend, cullFace, colorMask[0], colorMask[1], colorMask[2], colorMask[3]));

        return sb.toString();
    }

    /**
     * Capture texture bindings for shadow map units.
     */
    public String captureShadowTextureBindings(int unit1, int unit2) {
        int prevUnit = glGetInteger(GL_ACTIVE_TEXTURE);

        glActiveTexture(GL_TEXTURE0 + unit1);
        int tex1 = glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY);

        glActiveTexture(GL_TEXTURE0 + unit2);
        int tex2 = glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY);

        glActiveTexture(prevUnit);

        return String.format("unit%d->texId=%d unit%d->texId=%d", unit1, tex1, unit2, tex2);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOGGING METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Log FRAME_HEADER at start of render.
     */
    public void logFrameHeader(float timeOfDay, Vector3f sunDir, Vector3f skyColor,
                                float deltaTime, boolean isTransitioning) {
        if (!shouldLog(isTransitioning)) return;

        writer.println("---");
        writer.printf("[%s] FRAME_HEADER frameId=%d%n", timestamp(), frameId);
        writer.printf("    timeOfDay=%.6f deltaTime=%.6f transitioningNow=%s%n",
                timeOfDay, deltaTime, isTransitioning);
        writer.printf("    sunDir=%s skyColor=%s%n", vec3(sunDir), vec3(skyColor));
        writer.println("---");
    }

    /**
     * Log SHADOW_TRANSITION event (START or END_SWAP).
     */
    public void logShadowTransition(String event, boolean leapfrogEnabled, boolean isTransitioning,
                                     float crossfadeProgress, boolean primaryIsA,
                                     int shadowMapArrayA, int shadowMapArrayB,
                                     Object cascadeData, Object cascadeDataB, Object frozenCascadeDataA,
                                     Matrix4f matA0, Matrix4f matB0,
                                     float[] splitsA, float[] splitsB,
                                     float texelSizeA0, float texelSizeB0,
                                     boolean swapOccurred) {
        writer.println("---");
        writer.printf("[%s] SHADOW_%s frameId=%d%n", timestamp(), event, frameId);
        writer.printf("    leapfrogEnabled=%s isTransitioning=%s crossfadeProgress=%.4f primaryIsA=%s%n",
                leapfrogEnabled, isTransitioning, crossfadeProgress, primaryIsA);
        writer.printf("    textureIds: A=%d B=%d%n", shadowMapArrayA, shadowMapArrayB);

        int primaryId = primaryIsA ? shadowMapArrayA : shadowMapArrayB;
        int secondaryId = primaryIsA ? shadowMapArrayB : shadowMapArrayA;
        String primaryLetter = primaryIsA ? "A" : "B";
        String secondaryLetter = primaryIsA ? "B" : "A";
        writer.printf("    primary: id=%d (%s)  secondary: id=%d (%s)%n",
                primaryId, primaryLetter, secondaryId, secondaryLetter);

        writer.printf("    cascadeData identity: A=%d B=%d frozenA=%d%n",
                System.identityHashCode(cascadeData),
                System.identityHashCode(cascadeDataB),
                System.identityHashCode(frozenCascadeDataA));

        writer.printf("    matrixHash cascade0 A: %s%n", hashMat(matA0));
        writer.printf("    matrixHash cascade0 B: %s%n", hashMat(matB0));

        writer.printf("    splitsA=%s%n", floatArr(splitsA));
        writer.printf("    splitsB=%s%n", floatArr(splitsB));
        writer.printf("    texelSize[0]: A=%.6f B=%.6f%n", texelSizeA0, texelSizeB0);

        if (swapOccurred) {
            writer.println("    >>> SWAP OCCURRED THIS FRAME <<<");
        }
        writer.println("---");
    }

    /**
     * Log VOXEL_PRE before texture binding and uniform setup.
     */
    public void logVoxelPre(int leapfrogActive, float shadowCrossfade, int cascadeCount,
                            int forceCascade, int biasMode, int pcfMode, int normalOffsetMode,
                            int primaryShadowTexId, int secondaryShadowTexId,
                            int shadowUnit1, int shadowUnit2,
                            Matrix4f matA0, Matrix4f matB0,
                            Vector3f skyColor, Vector3f fogColor, float ambient, Vector3f sunDir,
                            boolean isTransitioning) {
        if (!shouldLog(isTransitioning)) return;

        writer.println("---");
        writer.printf("[%s] VOXEL_PRE frameId=%d%n", timestamp(), frameId);
        writer.printf("    leapfrogActive=%d shadowCrossfade=%.4f cascadeCount=%d%n",
                leapfrogActive, shadowCrossfade, cascadeCount);
        writer.printf("    forceCascade=%d biasMode=%d pcfMode=%d normalOffsetMode=%d%n",
                forceCascade, biasMode, pcfMode, normalOffsetMode);
        writer.printf("    binding: primaryTexId=%d secondaryTexId=%d units=[%d,%d]%n",
                primaryShadowTexId, secondaryShadowTexId, shadowUnit1, shadowUnit2);
        writer.printf("    matrixHash sent A: %s%n", hashMat(matA0));
        writer.printf("    matrixHash sent B: %s%n", hashMat(matB0));
        writer.printf("    skyColor=%s fogColor=%s ambient=%.4f sunDir=%s%n",
                vec3(skyColor), vec3(fogColor), ambient, vec3(sunDir));
        writer.println("---");
    }

    /**
     * Log VOXEL_POST after draw.
     */
    public void logVoxelPost(int shadowUnit1, int shadowUnit2, boolean isTransitioning) {
        if (!shouldLog(isTransitioning)) return;

        int glError = glGetError();
        String errorStr = (glError == GL_NO_ERROR) ? "OK" : String.format("0x%X", glError);

        writer.println("---");
        writer.printf("[%s] VOXEL_POST frameId=%d%n", timestamp(), frameId);
        writer.printf("    glGetError=%s%n", errorStr);
        writer.printf("    GL state: %s%n", captureGLState());
        writer.printf("    shadowTexBindings: %s%n", captureShadowTextureBindings(shadowUnit1, shadowUnit2));
        writer.println("---");
    }

    /**
     * Log SKY_PRE before sky clear/draw.
     */
    public void logSkyPre(Vector3f skyColor, float timeOfDay, float brightness,
                          float clearR, float clearG, float clearB, float clearA,
                          boolean isTransitioning) {
        if (!shouldLog(isTransitioning)) return;

        writer.println("---");
        writer.printf("[%s] SKY_PRE frameId=%d%n", timestamp(), frameId);
        writer.printf("    skyColor=%s timeOfDay=%.6f brightness=%.4f%n",
                vec3(skyColor), timeOfDay, brightness);
        writer.printf("    glClearColor=(%.4f, %.4f, %.4f, %.4f)%n", clearR, clearG, clearB, clearA);
        writer.printf("    GL state: %s%n", captureGLState());
        writer.println("---");
    }

    /**
     * Log SKY_POST after sky clear/draw.
     */
    public void logSkyPost(boolean isTransitioning) {
        if (!shouldLog(isTransitioning)) return;

        int glError = glGetError();
        String errorStr = (glError == GL_NO_ERROR) ? "OK" : String.format("0x%X", glError);

        writer.println("---");
        writer.printf("[%s] SKY_POST frameId=%d%n", timestamp(), frameId);
        writer.printf("    glGetError=%s%n", errorStr);
        writer.printf("    GL state: %s%n", captureGLState());
        writer.println("---");
    }

    /**
     * Log SWAP_FRAME_DETAIL with crossfade blend values.
     * Called at the exact moment of swap to capture discontinuity data.
     *
     * @param shadowCrossfadeBeforeSwap The crossfade value just before swap (should be 1.0)
     * @param shadowCrossfadeAfterSwap The crossfade value just after swap (should be 1.0)
     * @param cascadeDataABeforeSwap Matrix hash of A before swap
     * @param cascadeDataAAfterSwap Matrix hash of A after swap (should be different - was B)
     * @param primaryTexIdBefore Primary texture ID before swap
     * @param primaryTexIdAfter Primary texture ID after swap (should be different)
     */
    public void logSwapDetail(float shadowCrossfadeBeforeSwap, float shadowCrossfadeAfterSwap,
                               String cascadeDataABeforeSwap, String cascadeDataAAfterSwap,
                               int primaryTexIdBefore, int primaryTexIdAfter,
                               boolean blendEqualsB) {
        writer.println("---");
        writer.printf("[%s] SWAP_FRAME_DETAIL frameId=%d%n", timestamp(), frameId);
        writer.printf("    crossfade: before=%.4f after=%.4f%n",
                shadowCrossfadeBeforeSwap, shadowCrossfadeAfterSwap);
        writer.printf("    primaryTexId: before=%d after=%d (changed=%s)%n",
                primaryTexIdBefore, primaryTexIdAfter, primaryTexIdBefore != primaryTexIdAfter);
        writer.printf("    matrixHashA: before=%s%n", cascadeDataABeforeSwap);
        writer.printf("    matrixHashA: after=%s%n", cascadeDataAAfterSwap);
        writer.printf("    INVARIANT CHECK: mix(A,B,1.0)==B ? %s%n", blendEqualsB);
        if (!blendEqualsB) {
            writer.println("    >>> BLEND DISCONTINUITY DETECTED! mix(A,B,1.0) != B <<<");
        }
        writer.println("---");
    }

    /**
     * Log illumination discontinuity data at swap frame.
     * Captures shadow factor values to detect abrupt changes.
     */
    public void logIlluminationDiscontinuity(int cascade, float shadowA, float shadowB,
                                              float blendedShadow, float crossfade,
                                              float expectedBlend, boolean isDiscontinuity) {
        writer.println("---");
        writer.printf("[%s] ILLUMINATION_CHECK frameId=%d%n", timestamp(), frameId);
        writer.printf("    cascade=%d shadowA=%.6f shadowB=%.6f%n", cascade, shadowA, shadowB);
        writer.printf("    crossfade=%.4f blendedShadow=%.6f expectedBlend=%.6f%n",
                crossfade, blendedShadow, expectedBlend);
        writer.printf("    delta=%.6f DISCONTINUITY=%s%n",
                Math.abs(blendedShadow - expectedBlend), isDiscontinuity);
        if (isDiscontinuity) {
            writer.println("    >>> SHADOW DISCONTINUITY DETECTED! <<<");
        }
        writer.println("---");
    }

    /**
     * Log stabilization frame state.
     * Called when crossfade reaches 1.0 but swap hasn't happened yet.
     * This is the frame where shader should render mix(A, B, 1.0) = B exactly.
     *
     * @param event Event name (STABILIZATION_ENTER, STABILIZATION_EXIT)
     * @param crossfadeProgress Current crossfade value (should be 1.0)
     * @param isTransitioning Whether still in transition mode
     * @param primaryIsA Which buffer is primary
     * @param primaryTexId Primary texture ID
     * @param secondaryTexId Secondary texture ID
     * @param matrixHashA Hash of matrix A
     * @param matrixHashB Hash of matrix B
     */
    public void logStabilizationFrame(String event, float crossfadeProgress,
                                       boolean isTransitioning, boolean primaryIsA,
                                       int primaryTexId, int secondaryTexId,
                                       String matrixHashA, String matrixHashB) {
        writer.println("---");
        writer.printf("[%s] %s frameId=%d%n", timestamp(), event, frameId);
        writer.printf("    crossfadeProgress=%.4f isTransitioning=%s primaryIsA=%s%n",
                crossfadeProgress, isTransitioning, primaryIsA);
        writer.printf("    textures: primary=%d secondary=%d%n", primaryTexId, secondaryTexId);
        writer.printf("    matrixHashA=%s%n", matrixHashA);
        writer.printf("    matrixHashB=%s%n", matrixHashB);
        writer.printf("    SHADER WILL COMPUTE: mix(A, B, %.4f) = %.0f%% B%n",
                crossfadeProgress, crossfadeProgress * 100);
        if (crossfadeProgress >= 0.999f) {
            writer.println("    >>> STABILIZATION: shader should output 100% B (identical to post-swap) <<<");
        }
        writer.println("---");
    }

    /**
     * Flush and close the log file.
     */
    public void close() {
        if (writer != null) {
            writer.println("");
            writer.println("=== END OF LOG ===");
            writer.close();
        }
    }
}
