package engine.core;

import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_DECORATED;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZABLE;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.glfw.GLFW.glfwGetPrimaryMonitor;
import static org.lwjgl.glfw.GLFW.glfwGetVideoMode;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.glfw.GLFW.glfwSetFramebufferSizeCallback;
import static org.lwjgl.glfw.GLFW.glfwSetInputMode;
import static org.lwjgl.glfw.GLFW.glfwSetKeyCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowPos;
import static org.lwjgl.glfw.GLFW.glfwShowWindow;
import static org.lwjgl.glfw.GLFW.glfwSwapBuffers;
import static org.lwjgl.glfw.GLFW.glfwSwapInterval;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.glfw.GLFW.glfwWindowShouldClose;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_FILL;
import static org.lwjgl.opengl.GL11.GL_FRONT_AND_BACK;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glPolygonMode;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_SRGB;

import engine.core.config.GameConfig;
import engine.core.states.MenuState;
import engine.input.Input;
import engine.render.UIScreenScaler;
import engine.render.core.RenderContext;
import engine.util.CSMDebugLog;
import engine.world.BlockRegistry;

/**
 * Main application class managing the game lifecycle.
 * Entry point for the entire game engine.
 */
public class Application {
    private long window;
    private StateManager stateManager;
    private Input input;
    private RenderContext renderContext;
    private UIScreenScaler scaler;

    private long lastFrameTime;
    private int windowWidth;
    private int windowHeight;

    /**
     * Run the application (init -> loop -> cleanup).
     */
    public void run() {
        init();
        loop();
        cleanup();
    }

    /**
     * Initialize GLFW, OpenGL, and initial game state.
     */
    private void init() {
        // GLFW error callback
        GLFWErrorCallback.createPrint(System.err).set();

        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        // OpenGL 3.2 Core Profile
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, GameConfig.OPENGL_VERSION_MAJOR);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, GameConfig.OPENGL_VERSION_MINOR);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_RESIZABLE, GameConfig.WINDOW_RESIZABLE ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_DECORATED, GameConfig.WINDOW_DECORATED ? GLFW_TRUE : GLFW_FALSE);

        // Create window
        long monitor = glfwGetPrimaryMonitor();
        org.lwjgl.glfw.GLFWVidMode vidMode = glfwGetVideoMode(monitor);
        if (vidMode == null) {
            throw new RuntimeException("Unable to get video mode");
        }

        windowWidth = vidMode.width();
        windowHeight = vidMode.height() - GameConfig.WINDOW_HEIGHT_REDUCTION;
        window = glfwCreateWindow(windowWidth, windowHeight, GameConfig.WINDOW_TITLE, 0, 0);

        if (window == 0) {
            throw new RuntimeException("Failed to create GLFW window");
        }

        // Position window
        glfwSetWindowPos(window, 0, GameConfig.WINDOW_TOP_OFFSET);

        // OpenGL context
        glfwMakeContextCurrent(window);
        glfwSwapInterval(GameConfig.VSYNC_ENABLED);
        glfwShowWindow(window);

        // Input setup
        scaler = new UIScreenScaler(window);
        input = new Input(window, scaler);

        // Cursor mode
        glfwSetInputMode(window, GLFW_CURSOR, GameConfig.INITIAL_CURSOR_MODE);

        // Input callbacks
        setupInputCallbacks();

        // Create OpenGL capabilities
        GL.createCapabilities();

        // Get framebuffer size
        IntBuffer fbW = BufferUtils.createIntBuffer(1);
        IntBuffer fbH = BufferUtils.createIntBuffer(1);
        glfwGetFramebufferSize(window, fbW, fbH);
        windowWidth = fbW.get(0);
        windowHeight = fbH.get(0);
        float aspect = (float) windowWidth / windowHeight;
        System.out.println("Viewport = " + windowWidth + "×" + windowHeight + "; aspect = " + aspect);

        // OpenGL settings
        glViewport(0, 0, windowWidth, windowHeight);
        glEnable(GL_FRAMEBUFFER_SRGB);
        glEnable(GL_DEPTH_TEST);
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);

        // Register blocks
        BlockRegistry.registerAll();

        // Render context
        renderContext = new RenderContext();
        renderContext.setWindowSize(windowWidth, windowHeight);

        // Framebuffer resize callback
        glfwSetFramebufferSizeCallback(window, (win, width, height) -> {
            if (height == 0) return;
            windowWidth = width;
            windowHeight = height;
            renderContext.setWindowSize(width, height);
            scaler.update(); // Update DPI scaling factors
            glViewport(0, 0, width, height);

            // Notify current state of resize
            if (stateManager != null) {
                stateManager.onResize(width, height);
            }
        });

        // Initialize state manager with MenuState
        stateManager = new StateManager(new MenuState(this, renderContext, input));

        lastFrameTime = System.currentTimeMillis();
    }

    /**
     * Main game loop (update -> render -> swap buffers).
     */
    private void loop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            input.update();

            // Calculate delta time
            long currentTime = System.currentTimeMillis();
            float deltaTime = (currentTime - lastFrameTime) / 1000.0f;
            lastFrameTime = currentTime;

            // Update current state
            stateManager.update(deltaTime);

            // Render current state
            renderContext.beginFrame(deltaTime);
            stateManager.render(renderContext);

            glfwSwapBuffers(window);
        }
    }

    /**
     * Cleanup resources on application exit.
     */
    private void cleanup() {
        System.out.println("Cleaning up application...");
        CSMDebugLog.get().close();
        stateManager.cleanup();
        renderContext.cleanup();
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    /**
     * Setup input callbacks.
     * Note: Cursor mode is now managed by individual GameStates (MenuState, PlayingState, etc.)
     */
    private void setupInputCallbacks() {
        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            // ESC handling is now done in PlayingState for pausing/unpausing
            // No global ESC handler needed
        });

        // No automatic cursor capture - states manage their own cursor mode
    }

    // Getters for states
    public long getWindow() { return window; }
    public int getWindowWidth() { return windowWidth; }
    public int getWindowHeight() { return windowHeight; }
    public StateManager getStateManager() { return stateManager; }
    public Input getInput() { return input; }
    public UIScreenScaler getScaler() { return scaler; }
}
