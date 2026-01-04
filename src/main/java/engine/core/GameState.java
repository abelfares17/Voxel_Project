package engine.core;

import engine.render.core.RenderContext;

/**
 * Represents a game state (Menu, Playing, Loading, Paused).
 * Each state has its own lifecycle and rendering logic.
 */
public interface GameState {
    /**
     * Called when entering this state.
     * Initialize resources, setup renderers, etc.
     */
    void enter();

    /**
     * Update state logic.
     * @param deltaTime Time since last frame in seconds
     */
    void update(float deltaTime);

    /**
     * Render this state.
     * @param context Rendering context with camera, window info, etc.
     */
    void render(RenderContext context);

    /**
     * Called when leaving this state.
     * Cleanup resources, save data, etc.
     */
    void exit();

    /**
     * Called when window is resized.
     * Allows states to recalculate UI layout, camera projections, etc.
     * @param newWidth New framebuffer width
     * @param newHeight New framebuffer height
     */
    default void onResize(int newWidth, int newHeight) {
        // Default: do nothing (optional for states to implement)
    }

    /**
     * Check if this state blocks underlying state updates.
     * @return true if game world shouldn't update (e.g., in Menu or Paused)
     */
    default boolean blocksGameUpdates() {
        return true; // Most states block game updates
    }
}
