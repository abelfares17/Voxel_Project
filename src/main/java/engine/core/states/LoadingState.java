package engine.core.states;

import engine.core.Application;
import engine.core.GameState;
import engine.core.config.GameConfig;
import engine.input.Input;
import engine.render.core.RenderContext;
import engine.time.TimeManager;
import engine.ui.UIManager;
import engine.ui.screens.LoadingScreen;
import engine.world.World;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/**
 * Loading state - Displays loading screen while world generates.
 * Non-blocking: processes chunks frame-by-frame with visual feedback.
 *
 * Supports two modes:
 * 1. Creating new worlds with custom parameters
 * 2. Loading existing worlds from save files
 */
public class LoadingState implements GameState {
    private final Application app;
    private final RenderContext renderContext;
    private final Input input;

    private UIManager uiManager;
    private LoadingScreen loadingScreen;
    private World world;
    private TimeManager timeManager;

    // Mode-specific fields
    private enum Mode {
        CREATE_NEW_WORLD,
        LOAD_EXISTING_WORLD,
        DEPRECATED_DEFAULT  // For backward compatibility
    }

    private final Mode mode;
    private final String worldName;
    private final Long seedOverride;  // Nullable, for new world creation only
    private final World.Mode generationMode;  // For new world creation only
    private long seed;  // Keep for deprecated constructor

    private enum LoadingPhase {
        INITIALIZING,       // Setting up world structures
        GENERATING_CHUNKS,  // Generating chunk data
        BUILDING_MESHES,    // Building meshes (async)
        COMPLETE            // Ready to transition
    }

    private LoadingPhase phase;

    /**
     * Constructor for creating a new world with custom parameters.
     *
     * @param app Application instance
     * @param renderContext Render context
     * @param input Input handler
     * @param worldName Name for the new world
     * @param seed World generation seed
     * @param generationMode Terrain generation mode (FLAT or PERLIN)
     */
    public LoadingState(Application app, RenderContext renderContext, Input input,
                       String worldName, long seed, World.Mode generationMode) {
        this.app = app;
        this.renderContext = renderContext;
        this.input = input;
        this.mode = Mode.CREATE_NEW_WORLD;
        this.worldName = worldName;
        this.seedOverride = seed;
        this.generationMode = generationMode;
        this.phase = LoadingPhase.INITIALIZING;
    }

    /**
     * Constructor for loading an existing world from save.
     *
     * @param app Application instance
     * @param renderContext Render context
     * @param input Input handler
     * @param worldName Name of the world to load
     */
    public LoadingState(Application app, RenderContext renderContext, Input input,
                       String worldName) {
        this.app = app;
        this.renderContext = renderContext;
        this.input = input;
        this.mode = Mode.LOAD_EXISTING_WORLD;
        this.worldName = worldName;
        this.seedOverride = null;
        this.generationMode = null;
        this.phase = LoadingPhase.INITIALIZING;
    }

    /**
     * @deprecated Use constructor with world parameters instead.
     * This constructor is kept for backward compatibility.
     */
    @Deprecated
    public LoadingState(Application app, RenderContext renderContext, Input input) {
        this.app = app;
        this.renderContext = renderContext;
        this.input = input;
        this.mode = Mode.DEPRECATED_DEFAULT;
        this.worldName = null;
        this.seedOverride = null;
        this.generationMode = null;
        this.seed = System.currentTimeMillis();
        this.phase = LoadingPhase.INITIALIZING;
    }

    @Override
    public void enter() {
        System.out.println("Entering Loading state...");

        // Show cursor for loading screen
        glfwSetInputMode(app.getWindow(), GLFW_CURSOR, GLFW_CURSOR_NORMAL);

        // Create UI manager and show loading screen
        uiManager = new UIManager(app, renderContext, input);
        uiManager.showScreen("loading");
        loadingScreen = (LoadingScreen) uiManager.getCurrentScreen();

        // Initialize time manager
        timeManager = new TimeManager();

        // Create or load world based on mode
        switch (mode) {
            case CREATE_NEW_WORLD:
                System.out.println("Creating new world: " + worldName);
                world = new World(worldName, seedOverride, generationMode,
                                 GameConfig.DEFAULT_RENDER_DISTANCE, timeManager);
                break;

            case LOAD_EXISTING_WORLD:
                System.out.println("Loading existing world: " + worldName);
                world = World.loadFromSave(worldName,
                                          GameConfig.DEFAULT_RENDER_DISTANCE,
                                          timeManager);
                if (world == null) {
                    System.err.println("Failed to load world: " + worldName);
                    // Transition back to menu
                    app.getStateManager().setState(new MenuState(app, renderContext, input));
                    return;
                }
                break;

            case DEPRECATED_DEFAULT:
                System.out.println("Creating default world (deprecated path)");
                String defaultWorldName = engine.world.WorldNaming.generateWorldName();
                world = new World(defaultWorldName, seed, World.Mode.PERLIN,
                                 GameConfig.DEFAULT_RENDER_DISTANCE, timeManager);
                break;
        }

        // Start async chunk generation
        world.startAsyncChunkGeneration(0, 0);
        phase = LoadingPhase.GENERATING_CHUNKS;
    }

    @Override
    public void update(float deltaTime) {
        uiManager.update(deltaTime);

        switch (phase) {
            case INITIALIZING:
                // Already done in enter()
                break;

            case GENERATING_CHUNKS:
            case BUILDING_MESHES:
                // Process chunk loading (non-blocking)
                world.processChunkLoading();

                // Update progress
                float progress = world.getLoadingProgress();
                loadingScreen.setProgress(progress);

                // Check if complete
                if (world.isInitialLoadComplete()) {
                    phase = LoadingPhase.COMPLETE;
                }
                break;

            case COMPLETE:
                // Transition to playing state with pre-loaded world
                System.out.println("Loading complete! Transitioning to game...");
                app.getStateManager().setState(
                    new PlayingState(app, renderContext, input, world, timeManager)
                );
                // World ownership transferred to PlayingState
                world = null;
                break;
        }
    }

    @Override
    public void render(RenderContext context) {
        // Clear screen
        glClearColor(0.05f, 0.05f, 0.1f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        // Render loading UI
        uiManager.render(app.getWindowWidth(), app.getWindowHeight());
    }

    @Override
    public void exit() {
        System.out.println("Exiting Loading state...");
        uiManager.cleanup();

        // Cleanup world if it exists (in case loading was interrupted)
        if (world != null) {
            world.cleanup();
            world = null;
        }
    }

    @Override
    public void onResize(int newWidth, int newHeight) {
        if (uiManager != null) {
            uiManager.onResize(newWidth, newHeight);
        }
    }

    @Override
    public boolean blocksGameUpdates() {
        return true;
    }
}
