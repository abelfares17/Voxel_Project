package engine.render.core;

import engine.core.config.GameConfig;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_SRGB;

/**
 * Abstraction of a GLFW window with OpenGL context.
 * Handles window creation, configuration, and lifecycle.
 */
public class Window {

    private long windowHandle;
    private int width;
    private int height;
    private int framebufferWidth;
    private int framebufferHeight;
    private String title;
    private boolean initialized = false;

    /**
     * Create a new window with the specified dimensions.
     *
     * @param width  Window width in screen coordinates
     * @param height Window height in screen coordinates
     * @param title  Window title
     */
    public Window(int width, int height, String title) {
        this.width = width;
        this.height = height;
        this.title = title;
        init();
    }

    /**
     * Create a fullscreen borderless window.
     *
     * @param title Window title
     * @return New Window instance in fullscreen mode
     */
    public static Window createFullscreenBorderless(String title) {
        // Initialize GLFW first to query monitor
        if (!glfwInit()) {
            throw new IllegalStateException("Failed to initialize GLFW");
        }

        long monitor = glfwGetPrimaryMonitor();
        GLFWVidMode vidMode = glfwGetVideoMode(monitor);
        if (vidMode == null) {
            throw new RuntimeException("Failed to get video mode");
        }

        int width = vidMode.width();
        int height = vidMode.height() - GameConfig.WINDOW_HEIGHT_REDUCTION;

        Window window = new Window(width, height, title);
        window.setPosition(0, GameConfig.WINDOW_TOP_OFFSET);

        return window;
    }

    /**
     * Initialize the window and OpenGL context.
     */
    private void init() {
        // Set up error callback
        GLFWErrorCallback.createPrint(System.err).set();

        // Initialize GLFW
        if (!initialized && !glfwInit()) {
            throw new IllegalStateException("Failed to initialize GLFW");
        }

        // Configure window hints
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, GameConfig.OPENGL_VERSION_MAJOR);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, GameConfig.OPENGL_VERSION_MINOR);

        if (GameConfig.OPENGL_CORE_PROFILE) {
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        }

        if (GameConfig.OPENGL_FORWARD_COMPAT) {
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        }

        glfwWindowHint(GLFW_RESIZABLE, GameConfig.WINDOW_RESIZABLE ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_DECORATED, GameConfig.WINDOW_DECORATED ? GLFW_TRUE : GLFW_FALSE);

        // Create window
        windowHandle = glfwCreateWindow(width, height, title, 0, 0);
        if (windowHandle == 0) {
            glfwTerminate();
            throw new RuntimeException("Failed to create GLFW window");
        }

        // Make OpenGL context current
        glfwMakeContextCurrent(windowHandle);

        // Enable V-Sync
        glfwSwapInterval(GameConfig.VSYNC_ENABLED);

        // Show window
        glfwShowWindow(windowHandle);

        // Create OpenGL capabilities
        GL.createCapabilities();

        // Query framebuffer size (actual pixels, accounts for HiDPI)
        IntBuffer fbW = BufferUtils.createIntBuffer(1);
        IntBuffer fbH = BufferUtils.createIntBuffer(1);
        glfwGetFramebufferSize(windowHandle, fbW, fbH);
        framebufferWidth = fbW.get(0);
        framebufferHeight = fbH.get(0);

        if (GameConfig.DEBUG_FRAMEBUFFER_SIZE) {
            float aspect = (float) framebufferWidth / (float) framebufferHeight;
            System.out.println("Viewport: " + framebufferWidth + "x" + framebufferHeight +
                             "; aspect ratio: " + aspect);
        }

        // Set up OpenGL viewport
        glViewport(0, 0, framebufferWidth, framebufferHeight);

        // Enable OpenGL features
        glEnable(GL_FRAMEBUFFER_SRGB);
        glEnable(GL_DEPTH_TEST);
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);

        initialized = true;
    }

    /**
     * Set window position on screen.
     *
     * @param x X coordinate in screen coordinates
     * @param y Y coordinate in screen coordinates
     */
    public void setPosition(int x, int y) {
        glfwSetWindowPos(windowHandle, x, y);
    }

    /**
     * Set cursor mode (visible/hidden/captured).
     *
     * @param mode GLFW cursor mode (GLFW_CURSOR_NORMAL, GLFW_CURSOR_HIDDEN, GLFW_CURSOR_DISABLED)
     */
    public void setCursorMode(int mode) {
        glfwSetInputMode(windowHandle, GLFW_CURSOR, mode);
    }

    /**
     * Get current cursor mode.
     *
     * @return Current cursor mode
     */
    public int getCursorMode() {
        return glfwGetInputMode(windowHandle, GLFW_CURSOR);
    }

    /**
     * Poll for window events.
     * Should be called once per frame.
     */
    public void pollEvents() {
        glfwPollEvents();
    }

    /**
     * Swap front and back buffers.
     * Should be called once per frame after rendering.
     */
    public void swapBuffers() {
        glfwSwapBuffers(windowHandle);
    }

    /**
     * Check if the window should close.
     *
     * @return true if window close was requested
     */
    public boolean shouldClose() {
        return glfwWindowShouldClose(windowHandle);
    }

    /**
     * Request window to close.
     */
    public void setShouldClose(boolean close) {
        glfwSetWindowShouldClose(windowHandle, close);
    }

    /**
     * Clean up and destroy the window.
     */
    public void destroy() {
        if (windowHandle != 0) {
            glfwDestroyWindow(windowHandle);
            windowHandle = 0;
        }
        glfwTerminate();
    }

    /**
     * Get the GLFW window handle.
     *
     * @return Window handle (pointer)
     */
    public long getHandle() {
        return windowHandle;
    }

    /**
     * Get window width in screen coordinates.
     *
     * @return Window width
     */
    public int getWidth() {
        return width;
    }

    /**
     * Get window height in screen coordinates.
     *
     * @return Window height
     */
    public int getHeight() {
        return height;
    }

    /**
     * Get framebuffer width in pixels.
     * May differ from window width on HiDPI displays.
     *
     * @return Framebuffer width in pixels
     */
    public int getFramebufferWidth() {
        return framebufferWidth;
    }

    /**
     * Get framebuffer height in pixels.
     * May differ from window height on HiDPI displays.
     *
     * @return Framebuffer height in pixels
     */
    public int getFramebufferHeight() {
        return framebufferHeight;
    }

    /**
     * Get aspect ratio of the framebuffer.
     *
     * @return Aspect ratio (width / height)
     */
    public float getAspectRatio() {
        return (float) framebufferWidth / (float) framebufferHeight;
    }

    /**
     * Update cached framebuffer dimensions.
     * Should be called when window is resized.
     */
    public void updateFramebufferSize() {
        IntBuffer fbW = BufferUtils.createIntBuffer(1);
        IntBuffer fbH = BufferUtils.createIntBuffer(1);
        glfwGetFramebufferSize(windowHandle, fbW, fbH);
        framebufferWidth = fbW.get(0);
        framebufferHeight = fbH.get(0);
    }

    /**
     * Set window title.
     *
     * @param title New window title
     */
    public void setTitle(String title) {
        this.title = title;
        glfwSetWindowTitle(windowHandle, title);
    }

    /**
     * Get window title.
     *
     * @return Window title
     */
    public String getTitle() {
        return title;
    }
}