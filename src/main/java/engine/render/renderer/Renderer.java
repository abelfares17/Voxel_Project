package engine.render.renderer;

import engine.render.core.RenderContext;

/**
 * Base interface for all renderer implementations.
 * Defines the common contract for specialized renderers (voxel, sky, UI, etc.).
 *
 * Each renderer is responsible for rendering a specific aspect of the scene
 * and should manage its own OpenGL resources.
 */
public interface Renderer {

    /**
     * Render using the provided context.
     *
     * @param context Rendering context containing camera, world, and other shared state
     */
    void render(RenderContext context);

    /**
     * Clean up renderer resources (shaders, buffers, textures, etc.).
     * Must be called before application exit to prevent resource leaks.
     */
    void cleanup();
}
