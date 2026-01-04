package engine.render.camera;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Orthographic camera for 2D rendering (UI, text, HUD).
 * Creates an orthographic projection suitable for 2D elements.
 */
public class OrthographicCamera implements Camera {

    private final Matrix4f projectionMatrix = new Matrix4f();
    private final Matrix4f viewMatrix = new Matrix4f();

    private float left;
    private float right;
    private float bottom;
    private float top;
    private float near;
    private float far;

    /**
     * Create an orthographic camera with specified bounds.
     *
     * @param left   Left edge of view volume
     * @param right  Right edge of view volume
     * @param bottom Bottom edge of view volume
     * @param top    Top edge of view volume
     * @param near   Near clipping plane
     * @param far    Far clipping plane
     */
    public OrthographicCamera(float left, float right, float bottom, float top, float near, float far) {
        this.left = left;
        this.right = right;
        this.bottom = bottom;
        this.top = top;
        this.near = near;
        this.far = far;
        updateProjection();
    }

    /**
     * Create a 2D orthographic camera (typical for UI).
     * Near = -1, Far = 1 for 2D rendering.
     *
     * @param left   Left edge (typically 0)
     * @param right  Right edge (typically window width)
     * @param bottom Bottom edge (typically window height)
     * @param top    Top edge (typically 0)
     */
    public OrthographicCamera(float left, float right, float bottom, float top) {
        this(left, right, bottom, top, -1.0f, 1.0f);
    }

    /**
     * Create an orthographic camera matching window dimensions.
     * Origin at top-left (0,0), Y increases downward (UI convention).
     *
     * @param width  Window width
     * @param height Window height
     * @return Orthographic camera for UI rendering
     */
    public static OrthographicCamera createForUI(int width, int height) {
        return new OrthographicCamera(0, width, height, 0);
    }

    @Override
    public Vector3f getPosition() {
        // Orthographic camera typically doesn't move
        return new Vector3f(0, 0, 0);
    }

    @Override
    public void updateProjection() {
        projectionMatrix.identity();
        projectionMatrix.setOrtho(left, right, bottom, top, near, far);
    }

    @Override
    public void setAspect(float aspect) {
        // Aspect ratio handled differently for orthographic cameras
        // Adjust right edge based on aspect while maintaining height
        float height = top - bottom;
        float width = height * aspect;
        this.right = left + width;
        updateProjection();
    }

    @Override
    public float getAspect() {
        float width = right - left;
        float height = top - bottom;
        return width / height;
    }

    @Override
    public Matrix4f getProjectionMatrix() {
        return projectionMatrix;
    }

    @Override
    public Matrix4f getViewMatrix(Vector3f position, float yaw, float pitch) {
        // For 2D orthographic, view matrix is typically identity
        // Position, yaw, pitch are ignored for UI cameras
        viewMatrix.identity();
        return viewMatrix;
    }

    /**
     * Get a simple 2D orthographic projection matrix.
     * Convenience method for UI rendering.
     *
     * @return 2D ortho projection matrix
     */
    public Matrix4f getOrtho2D() {
        return projectionMatrix;
    }

    /**
     * Resize the orthographic view volume.
     *
     * @param left   New left edge
     * @param right  New right edge
     * @param bottom New bottom edge
     * @param top    New top edge
     */
    public void resize(float left, float right, float bottom, float top) {
        this.left = left;
        this.right = right;
        this.bottom = bottom;
        this.top = top;
        updateProjection();
    }

    /**
     * Resize to match window dimensions (UI mode).
     *
     * @param width  New window width
     * @param height New window height
     */
    public void resizeToWindow(int width, int height) {
        resize(0, width, height, 0);
    }

    // Getters

    public float getLeft() {
        return left;
    }

    public float getRight() {
        return right;
    }

    public float getBottom() {
        return bottom;
    }

    public float getTop() {
        return top;
    }

    public float getNear() {
        return near;
    }

    public float getFar() {
        return far;
    }

    // Setters

    public void setLeft(float left) {
        this.left = left;
        updateProjection();
    }

    public void setRight(float right) {
        this.right = right;
        updateProjection();
    }

    public void setBottom(float bottom) {
        this.bottom = bottom;
        updateProjection();
    }

    public void setTop(float top) {
        this.top = top;
        updateProjection();
    }

    public void setNear(float near) {
        this.near = near;
        updateProjection();
    }

    public void setFar(float far) {
        this.far = far;
        updateProjection();
    }
}
