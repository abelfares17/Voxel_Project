package engine.render.renderer;

import engine.render.GLStateValidator;
import engine.render.core.RenderContext;
import engine.util.SkyFlashDiagnostic;
import engine.util.SkyFlashTrace;
import org.joml.Vector3f;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;

/**
 * Renderer responsible for clearing the screen with sky color.
 * Uses the frame-cached sky color from RenderContext for consistency.
 */
public class SkyRenderer implements Renderer {

    /**
     * Create a new sky renderer.
     */
    public SkyRenderer() {
        // No initialization needed
    }

    @Override
    public void render(RenderContext context) {
        // ═══════════════════════════════════════════════════════════════════════
        // GL STATE VALIDATION (debug, rate-limited)
        // ═══════════════════════════════════════════════════════════════════════
        // Check for state leaks from previous passes (shadow, etc.)
        GLStateValidator.validateState("SkyRenderer.render");

        // Use the frame-cached sky color for consistency across all renderers
        Vector3f skyColor = context.getSkyColor();

        // DIAGNOSTIC: Log state BEFORE any defensive reset
        // This captures the actual state leak if any
        SkyFlashDiagnostic.get().logSkyRender(skyColor.x, skyColor.y, skyColor.z);

        // ═══════════════════════════════════════════════════════════════════════
        // DEFENSIVE GL STATE RESET
        // ═══════════════════════════════════════════════════════════════════════
        // Ensure we're rendering to default framebuffer with color writes enabled.
        // This guards against state leaks from shadow pass early-return paths.
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glColorMask(true, true, true, true);

        // SKYFLASH TRACE: SKY_PRE - before clear/draw
        float timeOfDay = context.getWorld().getTimeManager().getTimeOfDay();
        // Get transitioning state from trace (updated by ShadowRenderer)
        boolean isTransitioning = SkyFlashTrace.get().wasTransitioning();
        SkyFlashTrace.get().logSkyPre(skyColor, timeOfDay, 1.0f,
            skyColor.x, skyColor.y, skyColor.z, 1.0f, isTransitioning);

        // Clear screen with cached sky color
        glClearColor(skyColor.x, skyColor.y, skyColor.z, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        // SKYFLASH TRACE: SKY_POST - after clear/draw
        SkyFlashTrace.get().logSkyPost(isTransitioning);

        // ═══════════════════════════════════════════════════════════════════════
        // DIAGNOSTIC: Record what we actually cleared with
        // ═══════════════════════════════════════════════════════════════════════
        context.recordSkyClear(skyColor.x, skyColor.y, skyColor.z, 1.0f, true, true);
    }

    @Override
    public void cleanup() {
        // No resources to clean up
    }
}
