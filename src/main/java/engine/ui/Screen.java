package engine.ui;

import engine.input.Input;
import org.joml.Matrix4f;

/**
 * Represents a UI screen (menu, pause, settings, etc.).
 * Contains and manages multiple UI components.
 */
public interface Screen {
    /**
     * Called when this screen becomes active.
     */
    void onShow();

    /**
     * Called when this screen is hidden.
     */
    void onHide();

    /**
     * Update screen logic.
     * @param deltaTime Time since last frame
     */
    void update(float deltaTime);

    /**
     * Render this screen.
     * @param orthoProjection Orthographic projection matrix
     * @param windowWidth Window width
     * @param windowHeight Window height
     */
    void render(Matrix4f orthoProjection, int windowWidth, int windowHeight);

    /**
     * Handle input for this screen.
     * @param input Input manager
     */
    void handleInput(Input input);

    /**
     * Cleanup resources.
     */
    void cleanup();

    /**
     * Called when window is resized.
     * Allows screens to recalculate component positions.
     * @param newWidth New window width (framebuffer)
     * @param newHeight New window height (framebuffer)
     */
    default void onResize(int newWidth, int newHeight) {
        // Default: do nothing (optional for screens to implement)
    }

    /**
     * Check if this screen should pause game updates.
     * @return true if game should pause
     */
    default boolean pausesGame() {
        return true; // Most screens pause the game
    }
}
