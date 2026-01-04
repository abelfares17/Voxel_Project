package engine.ui.component;

import org.joml.Matrix4f;

import engine.input.Input;

/**
 * Base interface for all UI components.
 * Provides lifecycle methods and rendering contract.
 */
public interface UIComponent {
    /**
     * Update component logic.
     * @param deltaTime Time since last frame in seconds
     */
    void update(float deltaTime);

    /**
     * Render this component.
     * @param orthoProjection Orthographic projection matrix
     * @param windowHeight Window height for text rendering
     */
    void render(Matrix4f orthoProjection, int windowWidth, int windowHeight, float scale);

    /**
     * Handle input events for this component.
     * @param input Input manager
     */
    void handleInput(Input input);

    /**
     * Check if point is inside component bounds.
     * @param x Screen X coordinate
     * @param y Screen Y coordinate
     * @return true if point is inside
     */
    boolean contains(float x, float y);

    /**
     * Get component X position.
     */
    float getX();

    /**
     * Get component Y position.
     */
    float getY();

    /**
     * Get component width.
     */
    float getWidth();

    /**
     * Get component height.
     */
    float getHeight();

    /**
     * Set component position.
     */
    void setPosition(float x, float y);

    /**
     * Set component size.
     */
    void setSize(float width, float height);

    /**
     * Check if component is visible.
     */
    boolean isVisible();

    /**
     * Set component visibility.
     */
    void setVisible(boolean visible);
}
