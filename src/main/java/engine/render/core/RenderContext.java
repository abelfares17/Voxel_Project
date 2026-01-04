package engine.render.core;

import engine.render.camera.Camera;
import engine.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Rendering context shared across all states.
 * Contains camera, projection matrices, and window dimensions.
 */
public class RenderContext {
    private Camera camera;
    private World world;
    private Matrix4f projectionMatrix;
    private Matrix4f viewMatrix;
    private int windowWidth;
    private int windowHeight;
    private float deltaTime;
    private float aspectRatio;
    private Vector3f sunDirection;

    public RenderContext() {
        this.projectionMatrix = new Matrix4f();
        this.viewMatrix = new Matrix4f();
    }

    /**
     * Called at the beginning of each frame.
     * @param deltaTime Time since last frame in seconds
     */
    public void beginFrame(float deltaTime) {
        this.deltaTime = deltaTime;
    }

    /**
     * Set window size and update aspect ratio.
     * @param width Window width in pixels
     * @param height Window height in pixels
     */
    public void setWindowSize(int width, int height) {
        this.windowWidth = width;
        this.windowHeight = height;
        this.aspectRatio = (float) width / height;
    }

    /**
     * Cleanup resources.
     * Note: RenderContext doesn't own any GL resources.
     * Camera and World are managed by their respective owners (e.g., PlayingState).
     */
    public void cleanup() {
        // No GL resources to cleanup - this is a data container only
        camera = null;
        world = null;
    }

    // Getters and setters
    public Camera getCamera() { return camera; }
    public void setCamera(Camera camera) { this.camera = camera; }

    public World getWorld() { return world; }
    public void setWorld(World world) { this.world = world; }

    public Vector3f getSunDirection() { return sunDirection; }
    public void setSunDirection(Vector3f sunDirection) { this.sunDirection = sunDirection; }

    public Matrix4f getProjectionMatrix() { return projectionMatrix; }
    public void setProjectionMatrix(Matrix4f projectionMatrix) { this.projectionMatrix = projectionMatrix; }

    public Matrix4f getViewMatrix() { return viewMatrix; }
    public void setViewMatrix(Matrix4f viewMatrix) { this.viewMatrix = viewMatrix; }

    public int getWindowWidth() { return windowWidth; }
    public int getWindowHeight() { return windowHeight; }
    public float getDeltaTime() { return deltaTime; }
    public float getAspectRatio() { return aspectRatio; }
}
