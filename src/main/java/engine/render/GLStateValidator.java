package engine.render;

import org.lwjgl.BufferUtils;

import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING;

/**
 * Debug utility for detecting OpenGL state leaks between render passes.
 * Enable DEBUG_GL_LEAK_CHECK to log unexpected state at render pass boundaries.
 */
public class GLStateValidator {

    // Master switch - set to true to enable leak detection
    public static final boolean DEBUG_GL_LEAK_CHECK = true;

    // Rate limiting: only check every N frames
    private static final int CHECK_INTERVAL = 120;
    private static int frameCounter = 0;

    // Expected state at start of main render passes
    private static final int EXPECTED_FBO = 0;  // Default framebuffer
    private static final boolean[] EXPECTED_COLOR_MASK = {true, true, true, true};

    // Reusable buffers
    private static final IntBuffer intBuf = BufferUtils.createIntBuffer(16);

    /**
     * Increment frame counter. Call once per frame.
     */
    public static void nextFrame() {
        frameCounter++;
    }

    /**
     * Check if we should perform a validation this frame.
     */
    public static boolean shouldValidate() {
        return DEBUG_GL_LEAK_CHECK && (frameCounter % CHECK_INTERVAL == 0);
    }

    /**
     * Validate GL state at the start of a render pass.
     * Logs a warning if state is unexpected.
     *
     * @param passName Name of the render pass (for logging)
     * @return true if state is valid, false if leak detected
     */
    public static boolean validateState(String passName) {
        if (!shouldValidate()) {
            return true;
        }

        boolean valid = true;
        StringBuilder issues = new StringBuilder();

        // Check framebuffer binding
        int fbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
        if (fbo != EXPECTED_FBO) {
            issues.append(String.format("  FBO=%d (expected 0)%n", fbo));
            valid = false;
        }

        // Check color mask
        intBuf.clear();
        glGetIntegerv(GL_COLOR_WRITEMASK, intBuf);
        boolean[] colorMask = {
            intBuf.get(0) != 0,
            intBuf.get(1) != 0,
            intBuf.get(2) != 0,
            intBuf.get(3) != 0
        };

        if (colorMask[0] != EXPECTED_COLOR_MASK[0] ||
            colorMask[1] != EXPECTED_COLOR_MASK[1] ||
            colorMask[2] != EXPECTED_COLOR_MASK[2] ||
            colorMask[3] != EXPECTED_COLOR_MASK[3]) {
            issues.append(String.format("  ColorMask=[%b,%b,%b,%b] (expected all true)%n",
                colorMask[0], colorMask[1], colorMask[2], colorMask[3]));
            valid = false;
        }

        // Check viewport (just log it, don't validate since window size varies)
        intBuf.clear();
        glGetIntegerv(GL_VIEWPORT, intBuf);
        int vpX = intBuf.get(0);
        int vpY = intBuf.get(1);
        int vpW = intBuf.get(2);
        int vpH = intBuf.get(3);

        // Check depth test state
        boolean depthTest = glIsEnabled(GL_DEPTH_TEST);
        boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);

        if (!valid) {
            System.err.printf("[GL STATE LEAK] at %s (frame %d):%n%s",
                passName, frameCounter, issues);
            System.err.printf("  Viewport=[%d,%d,%d,%d] DepthTest=%b DepthMask=%b%n",
                vpX, vpY, vpW, vpH, depthTest, depthMask);
        }

        return valid;
    }

    /**
     * Force reset GL state to safe defaults.
     * Call this defensively at the start of critical render passes.
     */
    public static void resetToDefaults() {
        glBindFramebuffer(GL_FRAMEBUFFER_BINDING, 0);
        glColorMask(true, true, true, true);
        glDepthMask(true);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);
        glDisable(GL_BLEND);
        glDisable(GL_SCISSOR_TEST);
    }

    /**
     * Import for glBindFramebuffer (needed since we only imported GL_FRAMEBUFFER_BINDING).
     */
    private static void glBindFramebuffer(int target, int framebuffer) {
        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, framebuffer);
    }
}
