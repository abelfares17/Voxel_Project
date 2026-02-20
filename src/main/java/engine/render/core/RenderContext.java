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

    // ═══════════════════════════════════════════════════════════════════════════
    // SUN DIRECTION SPLIT
    // ═══════════════════════════════════════════════════════════════════════════
    // sunDirLighting: Raw, continuous sun direction for lighting/sky/sun disc
    // sunDirShadows:  May be quantized for shadow basis (leapfrog system)
    //
    // This separation ensures sky color doesn't jump when shadow basis updates
    private Vector3f sunDirLighting = new Vector3f();
    private Vector3f sunDirShadows = new Vector3f();

    // ═══════════════════════════════════════════════════════════════════════════
    // FRAME-CACHED SKY COLOR
    // ═══════════════════════════════════════════════════════════════════════════
    // Computed once per frame and used by all renderers (sky clear, fog, etc.)
    // This ensures consistent sky color throughout the frame
    private Vector3f skyColor = new Vector3f();
    private float timeOfDay = 0;

    // ═══════════════════════════════════════════════════════════════════════════
    // SKY CLEAR DIAGNOSTICS
    // ═══════════════════════════════════════════════════════════════════════════
    // Tracks what SkyRenderer actually did this frame (for debug logging)
    private float skyClearColorR, skyClearColorG, skyClearColorB, skyClearColorA;
    private boolean skyClearedColor;
    private boolean skyClearedDepth;

    // Legacy accessor - DO NOT USE in new code
    @Deprecated
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

    // ═══════════════════════════════════════════════════════════════════════════
    // SUN DIRECTION ACCESSORS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get raw, continuous sun direction for lighting/sky/sun disc.
     * This is NOT quantized and changes smoothly every frame.
     */
    public Vector3f getSunDirLighting() { return sunDirLighting; }

    /**
     * Set raw, continuous sun direction for lighting/sky/sun disc.
     */
    public void setSunDirLighting(Vector3f sunDir) { this.sunDirLighting.set(sunDir); }

    /**
     * Get sun direction for shadow basis calculation.
     * May be quantized (only updates when threshold is crossed).
     */
    public Vector3f getSunDirShadows() { return sunDirShadows; }

    /**
     * Set sun direction for shadow basis calculation.
     */
    public void setSunDirShadows(Vector3f sunDir) { this.sunDirShadows.set(sunDir); }

    /**
     * Legacy accessor - returns lighting sun direction.
     * @deprecated Use getSunDirLighting() or getSunDirShadows() explicitly
     */
    @Deprecated
    public Vector3f getSunDirection() { return sunDirLighting; }

    /**
     * Legacy setter - sets BOTH directions for backward compatibility.
     * @deprecated Use setSunDirLighting() and setSunDirShadows() explicitly
     */
    @Deprecated
    public void setSunDirection(Vector3f sunDirection) {
        this.sunDirLighting.set(sunDirection);
        this.sunDirShadows.set(sunDirection);
        this.sunDirection = sunDirection;
    }

    public Matrix4f getProjectionMatrix() { return projectionMatrix; }
    public void setProjectionMatrix(Matrix4f projectionMatrix) { this.projectionMatrix = projectionMatrix; }

    public Matrix4f getViewMatrix() { return viewMatrix; }
    public void setViewMatrix(Matrix4f viewMatrix) { this.viewMatrix = viewMatrix; }

    public int getWindowWidth() { return windowWidth; }
    public int getWindowHeight() { return windowHeight; }
    public float getDeltaTime() { return deltaTime; }
    public float getAspectRatio() { return aspectRatio; }

    // ═══════════════════════════════════════════════════════════════════════════
    // FRAME-CACHED SKY COLOR ACCESSORS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get the cached sky color for this frame.
     * This is computed once per frame and used consistently by all renderers.
     */
    public Vector3f getSkyColor() { return skyColor; }

    /**
     * Set the cached sky color for this frame.
     * Called once per frame before rendering starts.
     */
    public void setSkyColor(Vector3f color) { this.skyColor.set(color); }

    /**
     * Get the cached time of day for this frame.
     */
    public float getTimeOfDay() { return timeOfDay; }

    /**
     * Set the cached time of day for this frame.
     * Called once per frame before rendering starts.
     */
    public void setTimeOfDay(float timeOfDay) { this.timeOfDay = timeOfDay; }

    // ═══════════════════════════════════════════════════════════════════════════
    // SKY CLEAR DIAGNOSTIC ACCESSORS
    // ═══════════════════════════════════════════════════════════════════════════

    /** Record the actual clear color used by SkyRenderer. */
    public void recordSkyClear(float r, float g, float b, float a, boolean clearedColor, boolean clearedDepth) {
        this.skyClearColorR = r;
        this.skyClearColorG = g;
        this.skyClearColorB = b;
        this.skyClearColorA = a;
        this.skyClearedColor = clearedColor;
        this.skyClearedDepth = clearedDepth;
    }

    /** Reset sky clear state at frame start. */
    public void resetSkyClearState() {
        this.skyClearColorR = 0;
        this.skyClearColorG = 0;
        this.skyClearColorB = 0;
        this.skyClearColorA = 0;
        this.skyClearedColor = false;
        this.skyClearedDepth = false;
    }

    public float getSkyClearColorR() { return skyClearColorR; }
    public float getSkyClearColorG() { return skyClearColorG; }
    public float getSkyClearColorB() { return skyClearColorB; }
    public float getSkyClearColorA() { return skyClearColorA; }
    public boolean isSkyClearedColor() { return skyClearedColor; }
    public boolean isSkyClearedDepth() { return skyClearedDepth; }
}
