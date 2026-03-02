package engine.render.renderer;

import org.joml.Vector3f;
import static org.lwjgl.opengl.GL11.GL_BACK;
import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_LESS;
import static org.lwjgl.opengl.GL11.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glColorMask;
import static org.lwjgl.opengl.GL11.glDepthFunc;
import static org.lwjgl.opengl.GL11.glDepthMask;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glDrawBuffer;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;

import engine.render.core.RenderContext;

/**
 * Renderer responsible for clearing the screen with sky color.
 * Uses the frame-cached sky color from RenderContext for consistency.
 */
public class SkyRenderer implements Renderer {

    public SkyRenderer() {
        // No initialization needed
    }

    @Override
    public void render(RenderContext context) {
        // Use the frame-cached sky color for consistency across all renderers
        Vector3f skyColor = context.getSkyColor();

        // Clamp to [0,1] to avoid artifacts
        float r = clamp01(skyColor.x);
        float g = clamp01(skyColor.y);
        float b = clamp01(skyColor.z);

        // Ensure we're rendering to default framebuffer with correct state
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glColorMask(true, true, true, true);
        glDepthMask(true);
        glDisable(GL_BLEND);
        glDisable(GL_SCISSOR_TEST);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);
        glDrawBuffer(GL_BACK);
        glReadBuffer(GL_BACK);
        glViewport(0, 0, context.getWindowWidth(), context.getWindowHeight());

        // Clear screen with sky color
        glClearColor(r, g, b, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    @Override
    public void cleanup() {
        // No resources to clean up
    }
}
