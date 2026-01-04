package engine.render.camera;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Abstract camera interface.
 * Defines common operations for all camera types (perspective, orthographic, etc.).
 */
public interface Camera {

    /**
     * Get the projection matrix.
     *
     * @return Projection matrix
     */
    Matrix4f getProjectionMatrix();

    /**
     * Get the view matrix.
     *
     * @param position Camera position in world space
     * @param yaw      Horizontal rotation in degrees
     * @param pitch    Vertical rotation in degrees
     * @return View matrix
     */
    Matrix4f getViewMatrix(Vector3f position, float yaw, float pitch);

    /**
     * Get camera position.
     *
     * @return Position vector
     */
    Vector3f getPosition();

    /**
     * Update projection matrix (e.g., when aspect ratio changes).
     */
    void updateProjection();

    /**
     * Get the camera's aspect ratio.
     *
     * @return Aspect ratio (width / height)
     */
    float getAspect();

    /**
     * Set the camera's aspect ratio.
     *
     * @param aspect New aspect ratio
     */
    void setAspect(float aspect);
}
