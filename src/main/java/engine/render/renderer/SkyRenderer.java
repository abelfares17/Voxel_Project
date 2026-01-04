package engine.render.renderer;

import engine.core.config.RenderConfig;
import engine.render.core.RenderContext;
import org.joml.Vector3f;

import static org.lwjgl.opengl.GL11.*;

/**
 * Renderer responsible for clearing the screen with sky color.
 * Calculates sky color based on time of day with smooth transitions.
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
        // Get time of day from world's time manager
        float timeOfDay = context.getWorld().getTimeManager().getTimeOfDay();

        // Calculate sky color using centralized config
        Vector3f skyColor = RenderConfig.getSkyColor(timeOfDay);

        // Clear screen with calculated sky color
        glClearColor(skyColor.x, skyColor.y, skyColor.z, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }

    @Override
    public void cleanup() {
        // No resources to clean up
    }
}
