package engine.core.states;

import engine.Game;
import engine.core.Application;
import engine.core.GameState;
import engine.core.config.GameConfig;
import engine.input.Input;
import engine.render.TextRenderer;
import engine.render.TextureLoader;
import engine.render.Shader;
import engine.render.core.RenderContext;
import engine.render.renderer.*;
import engine.time.TimeManager;
import engine.world.World;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Playing state - Main gameplay.
 * Delegates to Game.java for game logic.
 */
public class PlayingState implements GameState {
    private final Application app;
    private final RenderContext renderContext;
    private final Input input;

    private Game game;
    private World world;
    private TimeManager timeManager;
    private TextRenderer textRenderer;
    private boolean worldPreLoaded;

    // Renderers and resources that need cleanup
    private ShadowRenderer shadowRenderer;
    private SkyRenderer skyRenderer;
    private SunRenderer sunRenderer;
    private VoxelRenderer voxelRenderer;
    private Shader voxelShader;
    private Shader depthShader;

    public PlayingState(Application app, RenderContext renderContext, Input input) {
        this.app = app;
        this.renderContext = renderContext;
        this.input = input;
        this.worldPreLoaded = false;
    }

    /**
     * Create PlayingState with a pre-loaded world (from LoadingState).
     */
    public PlayingState(Application app, RenderContext renderContext, Input input,
                       World world, TimeManager timeManager) {
        this.app = app;
        this.renderContext = renderContext;
        this.input = input;
        this.world = world;
        this.timeManager = timeManager;
        this.worldPreLoaded = true;
    }

    @Override
    public void enter() {
        System.out.println("Entering Playing state...");

        // Capture cursor for FPS controls
        glfwSetInputMode(app.getWindow(), GLFW_CURSOR, GLFW_CURSOR_DISABLED);

        // Initialize text renderer
        textRenderer = new TextRenderer(GameConfig.FONT_PATH, GameConfig.FONT_SIZE);

        // Create world (if not pre-loaded)
        if (!worldPreLoaded) {
            long seed = System.currentTimeMillis();
            timeManager = new TimeManager();
            String worldName = engine.world.WorldNaming.generateWorldName();
            world = new World(worldName, seed, World.Mode.PERLIN, GameConfig.DEFAULT_RENDER_DISTANCE, timeManager);

            // Start async chunk generation and wait for completion (fallback for direct PlayingState creation)
            world.startAsyncChunkGeneration(0, 0);
            while (!world.isInitialLoadComplete()) {
                world.processChunkLoading();
                try {
                    Thread.sleep(2);
                } catch (InterruptedException ignored) {}
            }
        }

        // Create shaders
        voxelShader = new Shader(
                GameConfig.VOXEL_VERTEX_SHADER,
                GameConfig.VOXEL_FRAGMENT_SHADER
        );
        depthShader = new Shader(
                GameConfig.DEPTH_VERTEX_SHADER,
                GameConfig.DEPTH_FRAGMENT_SHADER
        );

        // Load texture atlas
        int atlasTextureId = TextureLoader.loadTextureFromClasspath(GameConfig.ATLAS_TEXTURE_PATH);
        System.out.println("Atlas texture ID: " + atlasTextureId);

        // Create renderers
        shadowRenderer = new ShadowRenderer(depthShader);
        skyRenderer = new SkyRenderer();
        sunRenderer = new SunRenderer();
        voxelRenderer = new VoxelRenderer(voxelShader, shadowRenderer, atlasTextureId);

        // Create game instance
        game = new Game(
            app.getWindow(),
            input,
            world,
            voxelRenderer,
            shadowRenderer,
            skyRenderer,
            sunRenderer,
            textRenderer
        );

        game.setWinDim(app.getWindowWidth(), app.getWindowHeight());
        game.setAspect((float) app.getWindowWidth() / app.getWindowHeight());
    }

    @Override
    public void update(float deltaTime) {
        game.update();
    }

    @Override
    public void render(RenderContext context) {
        game.render();
    }

    @Override
    public void exit() {
        System.out.println("Exiting Playing state...");

        // Release cursor
        glfwSetInputMode(app.getWindow(), GLFW_CURSOR, GLFW_CURSOR_NORMAL);

        // Save player and world
        if (game != null && world != null) {
            world.getStorage().savePlayer(game.getPlayer());
            world.save();
        }

        // Cleanup renderers
        if (voxelRenderer != null) {
            voxelRenderer.cleanup();
            voxelRenderer = null;
        }
        if (sunRenderer != null) {
            sunRenderer.cleanup();
            sunRenderer = null;
        }
        if (skyRenderer != null) {
            skyRenderer.cleanup();
            skyRenderer = null;
        }
        if (shadowRenderer != null) {
            shadowRenderer.cleanup();
            shadowRenderer = null;
        }

        // Cleanup shaders
        if (voxelShader != null) {
            voxelShader.cleanup();
            voxelShader = null;
        }
        if (depthShader != null) {
            depthShader.cleanup();
            depthShader = null;
        }

        // Cleanup text renderer
        if (textRenderer != null) {
            textRenderer.cleanup();
            textRenderer = null;
        }

        // Cleanup world (shuts down mesh manager and storage threads)
        if (world != null) {
            world.cleanup();
            world = null;
        }

        game = null;
    }

    @Override
    public boolean blocksGameUpdates() {
        return false; // Game world DOES update in Playing state
    }

    public void resume() {
        System.out.println("Resuming game...");
    }

    public Game getGame() {
        return game;
    }
}
