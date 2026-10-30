package engine.render.camera;

import engine.core.config.GameConfig;
import engine.entity.Player;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Perspective camera for 3D rendering.
 * Creates a perspective projection suitable for viewing 3D scenes.
 */
public class PerspectiveCamera implements Camera {

    private final Matrix4f projectionMatrix = new Matrix4f();
    private final Matrix4f viewMatrix = new Matrix4f();

    private float fov;
    private float aspect;
    private float near;
    private float far;
    private Player player; // Optional player attachment

    /**
     * Create a perspective camera.
     *
     * @param fov    Field of view in degrees
     * @param aspect Aspect ratio (width / height)
     * @param near   Near clipping plane distance
     * @param far    Far clipping plane distance
     */
    public PerspectiveCamera(float fov, float aspect, float near, float far) {
        this(fov, aspect, near, far, null);
    }

    /**
     * Create a perspective camera attached to a player.
     *
     * @param fov    Field of view in degrees
     * @param aspect Aspect ratio (width / height)
     * @param near   Near clipping plane distance
     * @param far    Far clipping plane distance
     * @param player Player to attach camera to (can be null)
     */
    public PerspectiveCamera(float fov, float aspect, float near, float far, Player player) {
        this.fov = fov;
        this.aspect = aspect;
        this.near = near;
        this.far = far;
        this.player = player;
        updateProjection();
    }

    @Override
    public Vector3f getPosition() {
        if (player != null) {
            return player.getTransform().getPosition().add(0f, player.getEyeHeight(), 0f, new Vector3f());
        }
        return new Vector3f(0, 0, 0);
    }

    @Override
    public void updateProjection() {
        projectionMatrix.identity();
        projectionMatrix.setPerspective(
                (float) Math.toRadians(fov),
                aspect,
                near,
                far
        );
    }

    @Override
    public void setAspect(float newAspect) {
        this.aspect = newAspect;
        updateProjection();
    }

    @Override
    public float getAspect() {
        return aspect;
    }

    @Override
    public Matrix4f getProjectionMatrix() {
        return projectionMatrix;
    }

    @Override
    public Matrix4f getViewMatrix(Vector3f position, float yaw, float pitch) {
        viewMatrix.identity()
                .rotate((float) Math.toRadians(pitch), new Vector3f(1, 0, 0))
                .rotate((float) Math.toRadians(yaw), new Vector3f(0, 1, 0))
                .translate(-position.x, -position.y, -position.z);
        return viewMatrix;
    }

    /**
     * Get view matrix using the attached player's position and rotation.
     * Convenience method that automatically uses player's yaw and pitch.
     *
     * @return View matrix based on player's transform
     */
    public Matrix4f getViewMatrix() {
        if (player != null) {
            Vector3f position = getPosition();
            float yaw = player.getYaw();
            float pitch = player.getPitch();
            return getViewMatrix(position, yaw, pitch);
        }
        return getViewMatrix(new Vector3f(0, 0, 0), 0, 0);
    }

    /**
     * Get field of view in degrees.
     *
     * @return FOV in degrees
     */
    public float getFov() {
        return fov;
    }

    /**
     * Set field of view.
     *
     * @param fov FOV in degrees
     */
    public void setFov(float fov) {
        this.fov = fov;
        updateProjection();
    }

    /**
     * Get near clipping plane distance.
     *
     * @return Near plane distance
     */
    public float getNear() {
        return near;
    }

    /**
     * Set near clipping plane.
     *
     * @param near Near plane distance
     */
    public void setNear(float near) {
        this.near = near;
        updateProjection();
    }

    /**
     * Get far clipping plane distance.
     *
     * @return Far plane distance
     */
    public float getFar() {
        return far;
    }

    /**
     * Set far clipping plane.
     *
     * @param far Far plane distance
     */
    public void setFar(float far) {
        this.far = far;
        updateProjection();
    }

    /**
     * Get attached player (if any).
     *
     * @return Player or null
     */
    public Player getPlayer() {
        return player;
    }

    /**
     * Attach camera to a player.
     *
     * @param player Player to attach to
     */
    public void setPlayer(Player player) {
        this.player = player;
    }
}
