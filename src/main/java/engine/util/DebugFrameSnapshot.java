package engine.util;

import engine.render.core.RenderContext;
import engine.render.renderer.ShadowRenderer;
import org.lwjgl.BufferUtils;

import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_BINDING_2D_ARRAY;

/**
 * Debug utility for capturing per-frame rendering state snapshots.
 * Used to diagnose sky flashes and leapfrog CSM issues.
 *
 * Logging is rate-limited to 1 line per second, with immediate logging
 * for leapfrog transition events.
 */
public class DebugFrameSnapshot {

    // Master toggle - disabled by default (terminal output too verbose)
    public static boolean enabled = false;

    // Rate limiting
    private static long lastLogMs = 0;
    private static final long INTERVAL_MS = 1000;

    // High-frequency logging around transitions
    private static long transitionEndFrame = -100;
    private static long transitionStartFrame = -100;
    private static final int POST_TRANSITION_LOG_FRAMES = 10;  // Log 10 frames after transition

    // Frame counter
    private static long frameIndex = 0;

    // Reusable buffers for GL queries
    private static final IntBuffer intBuf = BufferUtils.createIntBuffer(16);
    private static final IntBuffer viewportBuf = BufferUtils.createIntBuffer(4);

    // Shadow texture unit constants (must match VoxelRenderer)
    public static final int SHADOW_UNIT_A = 1;
    public static final int SHADOW_UNIT_B = 2;

    // Cached shader sampler uniform values (set by VoxelRenderer when binding)
    private static int lastSamplerAUnit = -1;
    private static int lastSamplerBUnit = -1;

    /**
     * Increment frame counter. Call once per frame.
     */
    public static void nextFrame() {
        frameIndex++;
    }

    /**
     * Get current frame index.
     */
    public static long getFrameIndex() {
        return frameIndex;
    }

    /**
     * Record which sampler units are used for shadow arrays.
     * Called by VoxelRenderer when setting uniforms.
     */
    public static void recordSamplerUnits(int unitA, int unitB) {
        lastSamplerAUnit = unitA;
        lastSamplerBUnit = unitB;
    }

    /**
     * Log frame snapshot if rate limit allows.
     * Also logs every frame for POST_TRANSITION_LOG_FRAMES after a transition ends.
     *
     * @param tag       Short tag for log line (e.g., "FRAME", "SKY", "VOX_PRE")
     * @param ctx       RenderContext with sky color and time info
     * @param sr        ShadowRenderer for leapfrog state (can be null)
     */
    public static void logIfDue(String tag, RenderContext ctx, ShadowRenderer sr) {
        if (!enabled) return;

        // Always log for a few frames after transition start or end (high-frequency mode)
        boolean inPostTransitionWindow = (frameIndex - transitionEndFrame) <= POST_TRANSITION_LOG_FRAMES
                                      || (frameIndex - transitionStartFrame) <= POST_TRANSITION_LOG_FRAMES;

        if (!inPostTransitionWindow) {
            // Normal rate limiting
            long now = System.currentTimeMillis();
            if (now - lastLogMs < INTERVAL_MS) return;
            lastLogMs = now;
        }

        logNow(tag, ctx, sr);
    }

    /**
     * Mark that a transition just started (enables high-frequency logging).
     */
    public static void markTransitionStart() {
        transitionStartFrame = frameIndex;
    }

    /**
     * Mark that a transition just ended (enables high-frequency logging).
     */
    public static void markTransitionEnd() {
        transitionEndFrame = frameIndex;
    }

    /**
     * Log frame snapshot immediately (for leapfrog transitions).
     *
     * @param tag       Short tag for log line
     * @param ctx       RenderContext with sky color and time info
     * @param sr        ShadowRenderer for leapfrog state (can be null)
     */
    public static void logNow(String tag, RenderContext ctx, ShadowRenderer sr) {
        if (!enabled) return;

        StringBuilder sb = new StringBuilder(256);

        // ─────────────────────────────────────────────────────────────────────
        // HEADER: frame index, time of day, sky color
        // ─────────────────────────────────────────────────────────────────────
        float timeOfDay = ctx != null ? ctx.getTimeOfDay() : -1;
        float skyR = -1, skyG = -1, skyB = -1;
        if (ctx != null && ctx.getSkyColor() != null) {
            skyR = ctx.getSkyColor().x;
            skyG = ctx.getSkyColor().y;
            skyB = ctx.getSkyColor().z;
        }

        sb.append(String.format("[SNAP][%s f=%d] t=%.3f sky=(%.2f,%.2f,%.2f)",
                tag, frameIndex, timeOfDay, skyR, skyG, skyB));

        // ─────────────────────────────────────────────────────────────────────
        // SKY CLEAR STATE (from RenderContext)
        // ─────────────────────────────────────────────────────────────────────
        if (ctx != null) {
            sb.append(String.format(" clear=(%.2f,%.2f,%.2f,%.2f) clrC=%d clrD=%d",
                    ctx.getSkyClearColorR(), ctx.getSkyClearColorG(),
                    ctx.getSkyClearColorB(), ctx.getSkyClearColorA(),
                    ctx.isSkyClearedColor() ? 1 : 0,
                    ctx.isSkyClearedDepth() ? 1 : 0));
        }

        // ─────────────────────────────────────────────────────────────────────
        // LEAPFROG STATE
        // ─────────────────────────────────────────────────────────────────────
        if (sr != null) {
            boolean transitioning = sr.isLeapfrogTransitioning();
            float progress = sr.getCrossfadeProgress();
            int primaryTex = sr.getPrimaryShadowMapArray();
            int secondaryTex = sr.getSecondaryShadowMapArray();

            // Determine primaryIsA from which array is primary
            // Primary is shadowMapArray when primaryIsA=true
            int mainArray = sr.getShadowMapArray();
            boolean primaryIsA = (primaryTex == mainArray);

            sb.append(String.format(" leap{on=%d prog=%.2f primA=%d texA=%d texB=%d}",
                    transitioning ? 1 : 0, progress, primaryIsA ? 1 : 0,
                    primaryTex, secondaryTex));
        }

        // ─────────────────────────────────────────────────────────────────────
        // GL STATE
        // ─────────────────────────────────────────────────────────────────────
        int fbo = glGetInteger(GL_FRAMEBUFFER_BINDING);

        glGetIntegerv(GL_VIEWPORT, viewportBuf);
        int vpX = viewportBuf.get(0);
        int vpY = viewportBuf.get(1);
        int vpW = viewportBuf.get(2);
        int vpH = viewportBuf.get(3);

        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        boolean stencil = glIsEnabled(GL_STENCIL_TEST);
        boolean depth = glIsEnabled(GL_DEPTH_TEST);

        // Color mask
        intBuf.clear();
        glGetIntegerv(GL_COLOR_WRITEMASK, intBuf);
        int maskR = intBuf.get(0);
        int maskG = intBuf.get(1);
        int maskB = intBuf.get(2);
        int maskA = intBuf.get(3);

        sb.append(String.format(" gl{fbo=%d vp=%d,%d,%d,%d scissor=%d stencil=%d depth=%d mask=%d%d%d%d}",
                fbo, vpX, vpY, vpW, vpH,
                scissor ? 1 : 0, stencil ? 1 : 0, depth ? 1 : 0,
                maskR, maskG, maskB, maskA));

        // ─────────────────────────────────────────────────────────────────────
        // TEXTURE BINDINGS (shadow units 1 and 2)
        // ─────────────────────────────────────────────────────────────────────
        // Save current active texture
        int savedActiveTexture = glGetInteger(GL_ACTIVE_TEXTURE);

        // Query unit 1 (SHADOW_UNIT_A)
        glActiveTexture(GL_TEXTURE0 + SHADOW_UNIT_A);
        int texOnUnitA = glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY);

        // Query unit 2 (SHADOW_UNIT_B)
        glActiveTexture(GL_TEXTURE0 + SHADOW_UNIT_B);
        int texOnUnitB = glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY);

        // Restore active texture
        glActiveTexture(savedActiveTexture);

        sb.append(String.format(" tex{u1=%d u2=%d sampA=%d sampB=%d}",
                texOnUnitA, texOnUnitB, lastSamplerAUnit, lastSamplerBUnit));

        // ─────────────────────────────────────────────────────────────────────
        // ANOMALY DETECTION
        // ─────────────────────────────────────────────────────────────────────
        // Check for sky/clear color mismatch (could cause visible seams)
        if (ctx != null && ctx.isSkyClearedColor()) {
            float dr = Math.abs(skyR - ctx.getSkyClearColorR());
            float dg = Math.abs(skyG - ctx.getSkyClearColorG());
            float db = Math.abs(skyB - ctx.getSkyClearColorB());
            if (dr > 0.001f || dg > 0.001f || db > 0.001f) {
                sb.append(" *** SKY/CLEAR MISMATCH ***");
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // OUTPUT
        // ─────────────────────────────────────────────────────────────────────
        System.out.println(sb);
    }

    /**
     * Convenience: get GL integer value.
     */
    private static int glGetInteger(int pname) {
        intBuf.clear();
        glGetIntegerv(pname, intBuf);
        return intBuf.get(0);
    }
}
