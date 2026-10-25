package engine.core;

import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.system.Configuration;

import static org.lwjgl.glfw.GLFW.GLFW_COCOA_RETINA_FRAMEBUFFER;
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
import static org.lwjgl.glfw.GLFW.glfwGetWindowSize;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.glfw.GLFW.glfwSetFramebufferSizeCallback;
import static org.lwjgl.glfw.GLFW.glfwSetInputMode;
import static org.lwjgl.glfw.GLFW.glfwSetKeyCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowPos;
import static org.lwjgl.glfw.GLFW.glfwSetWindowSize;
import static org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose;
import static org.lwjgl.glfw.GLFW.glfwShowWindow;
import static org.lwjgl.glfw.GLFW.glfwSwapBuffers;
import static org.lwjgl.glfw.GLFW.glfwSwapInterval;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.glfw.GLFW.glfwWindowShouldClose;
import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import engine.core.config.GameConfig;
import engine.core.states.LoadingState;
import engine.core.states.MenuState;
import engine.core.states.PlayingState;
import engine.input.Input;
import engine.render.UIScreenScaler;
import engine.render.core.RenderContext;
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
    private GLFWErrorCallback errorCallback;

    private long lastFrameTime;
    private int windowWidth;
    private int windowHeight;

    private double fpsT0 = org.lwjgl.glfw.GLFW.glfwGetTime();
    private int fpsFrames = 0;
    private int fpsReal = 0;

    /**
     * Run the application (init -> loop -> cleanup).
     */
    public void run() {
        if (GameConfig.DEBUG_MEMORY_ALLOCATOR) {
            // Active le traqueur d'allocations mémoire natives LWJGL
            Configuration.DEBUG.set(true);
            Configuration.DEBUG_MEMORY_ALLOCATOR.set(true);
            Configuration.DEBUG_STACK.set(true);
        }

        init();
        loop();
        cleanup();
    }

    /**
     * Initialize GLFW, OpenGL, and initial game state.
     */
    private void init() {
        // GLFW error callback
        errorCallback = GLFWErrorCallback.createPrint(System.err).set();

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
        if (GameConfig.TEST_HARNESS_ENABLED) {
            glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_FOCUSED, GLFW_FALSE);
            glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_FOCUS_ON_SHOW, GLFW_FALSE);
        }
        long monitor = glfwGetPrimaryMonitor();
        org.lwjgl.glfw.GLFWVidMode vidMode = glfwGetVideoMode(monitor);
        if (vidMode == null) {
            throw new RuntimeException("Unable to get video mode");
        }

        windowWidth = vidMode.width();
        windowHeight = vidMode.height() - GameConfig.WINDOW_HEIGHT_REDUCTION;
        System.out.println("Initial logical window scale: " + GameConfig.WINDOW_SCALE
                + "; Retina: " + GameConfig.RETINA_FRAMEBUFFER_ENABLED);
        if (!GameConfig.RETINA_FRAMEBUFFER_ENABLED) {
            glfwWindowHint(GLFW_COCOA_RETINA_FRAMEBUFFER, GLFW_FALSE);
        }
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

        // Let Cocoa apply desktop/title-bar constraints before scaling the actual content area.
        // Scaling the requested monitor size instead changes aspect when only the full window is clamped.
        glfwPollEvents();
        int[] logicalWidth = new int[1], logicalHeight = new int[1];
        glfwGetWindowSize(window, logicalWidth, logicalHeight);
        if (GameConfig.WINDOW_SCALE != 1.0f) {
            glfwSetWindowSize(window,
                    Math.max(1, Math.round(logicalWidth[0] * GameConfig.WINDOW_SCALE)),
                    Math.max(1, Math.round(logicalHeight[0] * GameConfig.WINDOW_SCALE)));
            glfwPollEvents();
        }

        // Input setup
        scaler = new UIScreenScaler(window);
        input = new Input(window, scaler);

        // Cursor mode
        glfwSetInputMode(window, GLFW_CURSOR, GameConfig.TEST_HARNESS_ENABLED
                ? org.lwjgl.glfw.GLFW.GLFW_CURSOR_NORMAL : GameConfig.INITIAL_CURSOR_MODE);

        // Input callbacks
        setupInputCallbacks();

        // Create OpenGL capabilities
        GL.createCapabilities();

        //Log GL Starting State
        System.out.println("=== OPENGL RUNTIME IDENTITY ===");
        System.out.println("GL_VENDOR:   " + glGetString(GL_VENDOR));
        System.out.println("GL_RENDERER: " + glGetString(GL_RENDERER));
        System.out.println("GL_VERSION:  " + glGetString(GL_VERSION));
        System.out.println("GLSL:        " + glGetString(GL_SHADING_LANGUAGE_VERSION));
        System.out.println("Max Texture Units:   " + glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS));
        System.out.println("Max 2D Array Layers: " + glGetInteger(GL_MAX_ARRAY_TEXTURE_LAYERS));
        if (GameConfig.DEBUG_MEMORY_ALLOCATOR) {
            System.out.println("LWJGL Debug Allocator: " + Configuration.DEBUG_MEMORY_ALLOCATOR.get());
        }
        System.out.println("===============================");

        // Get framebuffer size
        IntBuffer fbW = BufferUtils.createIntBuffer(1);
        IntBuffer fbH = BufferUtils.createIntBuffer(1);
        glfwGetFramebufferSize(window, fbW, fbH);
        windowWidth = fbW.get(0);
        windowHeight = fbH.get(0);
        float aspect = (float) windowWidth / windowHeight;
        System.out.println("Viewport = " + windowWidth + "×" + windowHeight + "; aspect = " + aspect);
        var vid = glfwGetVideoMode(glfwGetPrimaryMonitor());
        System.out.println("refresh=" + (vid != null ? vid.refreshRate() : -1));
        int[] ww = new int[1], wh = new int[1];
        int[] fw = new int[1], fh = new int[1];
        glfwGetWindowSize(window, ww, wh);
        glfwGetFramebufferSize(window, fw, fh);
        System.out.println("window=" + ww[0] + "x" + wh[0] + " framebuffer=" + fw[0] + "x" + fh[0]
            + " scale=" + (fw[0] / (float)ww[0]));

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
            if (width == 0 || height == 0) return;
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

        // Initialize state manager with test harness or menu
        if (GameConfig.TEST_HARNESS_ENABLED) {
            // TEST MODE: Skip menu, load test world directly
            System.out.println("═══════════════════════════════════════════════════════════");
            System.out.println("TEST HARNESS MODE ENABLED");
            System.out.println("═══════════════════════════════════════════════════════════");
            System.out.println("Loading test world: " + GameConfig.TEST_WORLD_NAME);
            System.out.println("═══════════════════════════════════════════════════════════");

            // Create LoadingState for test world
            stateManager = new StateManager(
                new LoadingState(this, renderContext, input, GameConfig.TEST_WORLD_NAME)
            );
        } else {
            // NORMAL MODE: Start with menu
            stateManager = new StateManager(new MenuState(this, renderContext, input));
        }

        lastFrameTime = System.nanoTime();
    }

    /**
     * Main game loop (update -> render -> swap buffers).
     */
    private void loop() {
        while (!glfwWindowShouldClose(window)) {
            try {
                glfwPollEvents();
                input.update();

                // Calculate delta time
                long currentTime = System.nanoTime();
                float deltaTime = (float)((currentTime - lastFrameTime) / 1_000_000_000.0);
                lastFrameTime = currentTime;

                long t0 = System.nanoTime();

                // Update current state (with exception handling)
                long tUpdate0 = System.nanoTime();
                try {
                    if (stateManager.update(deltaTime)) {
                        // Exclude blocking enter()/loading work from the next simulation delta.
                        lastFrameTime = System.nanoTime();
                        deltaTime = 0f;
                    }
                } catch (Throwable t) {
                    CrashHandler.fatal("State Update", t);
                    glfwSetWindowShouldClose(window, true);
                    break;
                }
                long tUpdate1 = System.nanoTime();

                // Render current state (with exception handling)
                long tRender0 = System.nanoTime();
                try {
                    renderContext.beginFrame(deltaTime);
                    renderContext.setFpsReal(fpsReal);
                    stateManager.render(renderContext);
                } catch (Throwable t) {
                    CrashHandler.fatal("State Render", t);
                    glfwSetWindowShouldClose(window, true);
                    break;
                }
                long tRender1 = System.nanoTime();

                // Synchronize GPU and CPU to get accurate timing for swap buffers
                // DISABLED: glFinish() forces CPU to stall until GPU is completely idle, destroying pipelining.
                // long tF0 = System.nanoTime();
                // glFinish();
                // long tF1 = System.nanoTime();
                long tF0 = 0, tF1 = 0;

                // Swap buffers and time it
                long tSwap0 = System.nanoTime();
                glfwSwapBuffers(window);
                long tSwap1 = System.nanoTime();

                long t1 = System.nanoTime();

                // Calculate timing metrics
                double msUpdate = (tUpdate1 - tUpdate0) / 1e6;
                double msRender = (tRender1 - tRender0) / 1e6;
                double msSwap   = (tSwap1   - tSwap0) / 1e6;
                double msFrame  = (t1 - t0) / 1e6;
                double msFinish = (tF1 - tF0) / 1e6;

                // Store timing data in RenderContext for PerfLogger
                renderContext.setUpdateMs(msUpdate);
                renderContext.setRenderMs(msRender);
                renderContext.setSwapMs(msSwap);
                renderContext.setGlFinishMs(msFinish);

                // Console logging disabled to avoid terminal spam
                // if (!GameConfig.TEST_HARNESS_ENABLED && stateManager.getCurrentState() instanceof PlayingState) {
                //     System.out.printf("Frame time: %.2f ms (update=%.2f ms, render=%.2f ms, glfinish=%.2fms, swap=%.2f ms)%n",
                //         msFrame, msUpdate, msRender, msFinish, msSwap);
                // }

                fpsFrames++;
                double t = org.lwjgl.glfw.GLFW.glfwGetTime();
                if (t - fpsT0 >= 1.0) {
                    fpsReal = fpsFrames;
                    fpsFrames = 0;
                    fpsT0 = t;
                }

            } catch (Throwable t) {
                // Catch-all for any unexpected errors in the main loop
                CrashHandler.fatal("Main Loop", t);
                glfwSetWindowShouldClose(window, true);
                break;
            }
        }
    }

    /**
     * Cleanup resources on application exit.
     */
    private void cleanup() {
        System.out.println("Cleaning up application...");
        stateManager.cleanup();
        renderContext.cleanup();
        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        if (errorCallback != null) {
            errorCallback.free();
            errorCallback = null;
        }
        glfwTerminate();
        if (GameConfig.DEBUG_MEMORY_ALLOCATOR) {
            System.out.println("Application cleanup complete. Monitoring native memory via LWJGL DebugAllocator on shutdown...");
        } else {
            System.out.println("Application cleanup complete.");
        }
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
