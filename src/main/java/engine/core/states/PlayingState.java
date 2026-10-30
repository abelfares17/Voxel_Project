package engine.core.states;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import engine.Game;
import engine.core.Application;
import engine.core.GameState;
import engine.core.config.GameConfig;
import engine.core.config.RenderConfig;
import engine.input.Input;
import engine.render.Shader;
import engine.render.GpuPassTimer;
import engine.render.TextRenderer;
import engine.render.TextureLoader;
import engine.render.core.RenderContext;
import engine.render.renderer.ShadowRenderer;
import engine.render.renderer.SkyRenderer;
import engine.render.renderer.VoxelRenderer;
import engine.test.PerfLogger;
import engine.test.TestHarness;
import engine.time.TimeManager;
import engine.world.World;
import org.joml.Vector3f;

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
    private VoxelRenderer voxelRenderer;
    private Shader voxelShader;
    private Shader depthShader;
    private int atlasTextureId = 0;
    private GpuPassTimer gpuTimer;

    // Test harness (only active if GameConfig.TEST_HARNESS_ENABLED)
    private TestHarness testHarness;
    private PerfLogger perfLogger;
    private int frameIndex = 0;
    private String cachedPerfBisectStage = "";

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
        glfwSetInputMode(app.getWindow(), GLFW_CURSOR,
                GameConfig.TEST_HARNESS_ENABLED ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);

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
        this.atlasTextureId = TextureLoader.loadTextureFromClasspath(GameConfig.ATLAS_TEXTURE_PATH);
        System.out.println("Atlas texture ID: " + atlasTextureId);

        // Create renderers
        shadowRenderer = new ShadowRenderer(depthShader);
        skyRenderer = new SkyRenderer();
        voxelRenderer = new VoxelRenderer(voxelShader, shadowRenderer, atlasTextureId);

        // Create game instance
        game = new Game(
            app.getWindow(),
            input,
            world,
            voxelRenderer,
            shadowRenderer,
            skyRenderer,
            textRenderer
        );

        game.setWinDim(app.getWindowWidth(), app.getWindowHeight());
        game.setAspect((float) app.getWindowWidth() / app.getWindowHeight());
        gpuTimer = GpuPassTimer.createIfEnabled();
        System.out.println("Shadow light-frustum culling: " + RenderConfig.SHADOW_FRUSTUM_CULLING_ENABLED);
        System.out.println("Tight mesh bounds: shadow=" + RenderConfig.TIGHT_SHADOW_BOUNDS
                + "; camera=" + RenderConfig.TIGHT_CAMERA_BOUNDS);
        System.out.println("[CELESTIAL] startHour=" + System.getProperty("voxel.startHour", "saved/default")
                + "; timeScale=" + world.getTimeManager().getDebugSpeedMultiplier()
                + "; pauseTime=" + world.getTimeManager().isPaused()
                + "; worldDays=" + world.getTimeManager().getWorldDays()
                + "; light=" + world.getTimeManager().celestialState().activeLight()
                + "; skyMode=" + System.getProperty("voxel.skyMode", "procedural"));

        // Initialize test harness (if enabled)
        if (GameConfig.TEST_HARNESS_ENABLED) {
            testHarness = new TestHarness();
            testHarness.beginRun(game, world, game.getPlayer(), (engine.render.camera.PerspectiveCamera) renderContext.getCamera());
        }

        // Initialize performance logger (if enabled by config or test harness)
        if (GameConfig.PERF_LOG_ENABLED || GameConfig.TEST_HARNESS_ENABLED) {
            // Extract PERF_BISECT_STAGE from shader source for CSV labeling
            cachedPerfBisectStage = extractPerfBisectStage();

            try {
                int freq = GameConfig.TEST_HARNESS_ENABLED ? GameConfig.TEST_LOG_FREQUENCY : GameConfig.PERF_LOG_FREQUENCY;
                perfLogger = new PerfLogger(world.getWorldName(), freq);
            } catch (java.io.IOException e) {
                System.err.println("Failed to create PerfLogger: " + e.getMessage());
                e.printStackTrace();
            }

            frameIndex = 0;
        }
    }

    @Override
    public void update(float deltaTime) {
        // Run test harness automation (if enabled)
        if (GameConfig.TEST_HARNESS_ENABLED && testHarness != null) {
            testHarness.update(deltaTime);
            // Auto-close window when scenario is complete
            if (testHarness.isRunCompleted()) {
                System.out.println("[TEST HARNESS] Scenario complete - closing window.");
                glfwSetWindowShouldClose(app.getWindow(), true);
            }
        }

        game.update(deltaTime);

        // Log performance metrics (if enabled)
        if (perfLogger != null) {
            frameIndex++;

            // Get player position and camera orientation
            Vector3f playerPos = game.getPlayer().getPosition();
            float yaw = game.getPlayer().getYaw();
            float pitch = game.getPlayer().getPitch();

            // Calculate instantaneous FPS
            float fpsInstant = deltaTime > 0 ? (1.0f / deltaTime) : 0f;

            // Get renderer state (for diagnostic columns)
            boolean shadowPassEnabled = RenderConfig.SHADOW_DEPTH_PASS_ENABLED;
            boolean leapfrogEnabled = shadowRenderer != null && shadowRenderer.isLeapfrogEnabled();
            int debugShadowMode = voxelRenderer != null ? voxelRenderer.getDebugShadow() : 0;

            // Log frame metrics
            perfLogger.logFrame(
                frameIndex,
                deltaTime,
                renderContext.getUpdateMs(),
                renderContext.getRenderMs(),
                renderContext.getSwapMs(),
                renderContext.getGlFinishMs(),
                renderContext.getShadowFinishMs(),
                fpsInstant,
                renderContext.getFpsReal(),
                playerPos,
                yaw,
                pitch,
                voxelRenderer.getDrawCalls(), // Previous rendered frame, like CPU timing fields
                voxelRenderer.getDrawnVertices(),
                shadowPassEnabled,
                leapfrogEnabled,
                debugShadowMode,
                cachedPerfBisectStage,
                RenderConfig.SHADOW_FRUSTUM_CULLING_ENABLED,
                renderContext.getWindowWidth(), renderContext.getWindowHeight(),
                world.getTimeManager().getTimeOfDay(),
                RenderConfig.TIGHT_SHADOW_BOUNDS, RenderConfig.TIGHT_CAMERA_BOUNDS,
                RenderConfig.INDEXED_MESHES + "," + world.getStoredVertices() + "," + world.getMeshBytes()
                    + "," + glfwGetWindowAttrib(app.getWindow(), GLFW_FOCUSED)
                    + "," + glfwGetWindowAttrib(app.getWindow(), GLFW_VISIBLE)
                    + "," + glfwGetWindowAttrib(app.getWindow(), GLFW_ICONIFIED)
                    + "," + glfwGetInputMode(app.getWindow(), GLFW_CURSOR)
                    + "," + Shader.CACHE_UNIFORMS + "," + Shader.getLocationQueries()
                    + "," + RenderConfig.COMPACT_VERTICES
                    + "," + RenderConfig.SKIP_UNLIT_SHADOWS
                    + "," + RenderConfig.GREEDY_SHADOWS
                    + "," + System.getProperty("voxel.skyMode", "procedural")
                    + "," + renderContext.getCelestialState().activeLight()
                    + "," + renderContext.getCelestialState().activeElevation()
                    + "," + RenderConfig.SHADOW_CASTER_RESIDENCY
                    + "," + world.getChunkCount() + "," + world.getCasterRequested() + "," + world.getCasterReady()
                    + "," + world.getCasterPlan().candidates() + "," + world.getCasterPlan().reach()
                    + "," + world.getCasterPlan().reachLimited() + "," + world.getCasterPlan().budgetLimited()
                    + "," + RenderConfig.LEGACY_CASTER_RESIDENCY
                    + "," + renderContext.getShadowCoverage().fadeEnabled()
                    + "," + renderContext.getShadowCoverage().fadeStart() + "," + renderContext.getShadowCoverage().fadeEnd()
                    + "," + renderContext.getShadowCoverage().requiredReady() + "," + renderContext.getShadowCoverage().required()
                    + "," + renderContext.getShadowCoverage().prefetchReady() + "," + renderContext.getShadowCoverage().prefetch()
                    + "," + renderContext.getShadowCoverage().complete() + "," + renderContext.getShadowCoverage().guardValid()
            );
        }
    }

    @Override
    public void render(RenderContext context) {
        game.render(context, gpuTimer);
    }

    @Override
    public void exit() {
        System.out.println("Exiting Playing state...");
        if (gpuTimer != null) {
            gpuTimer.close();
            gpuTimer = null;
        }

        // Release cursor
        glfwSetInputMode(app.getWindow(), GLFW_CURSOR, GLFW_CURSOR_NORMAL);

        // Close performance logger (if active)
        if (perfLogger != null) {
            perfLogger.close();
            perfLogger = null;
        }

        // Save player and world (skip in test harness mode to prevent drift)
        if (game != null && world != null) {
            if (!GameConfig.TEST_HARNESS_ENABLED) {
                world.getStorage().savePlayer(game.getPlayer());
                world.save();
            } else {
                System.out.println("Test harness: Skipping player/world save to prevent drift");
            }
        }

        // Cleanup renderers
        if (game != null) {
            game.cleanup();
        }
        if (voxelRenderer != null) {
            voxelRenderer.cleanup();
            voxelRenderer = null;
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

        // 3. Cleanup voxel atlas
        if (atlasTextureId != 0) {
            glDeleteTextures(atlasTextureId);
            atlasTextureId = 0;
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

    @Override
    public void onResize(int width, int height) {
        if (game != null && width > 0 && height > 0) {
            game.setWinDim(width, height);
            game.setAspect((float) width / height);
        }
    }

    public void resume() {
        System.out.println("Resuming game...");
    }

    public Game getGame() {
        return game;
    }

    /** Read #define PERF_BISECT_STAGE N from the shader source at classpath. */
    private String extractPerfBisectStage() {
        try (java.io.InputStream is = getClass().getClassLoader()
                .getResourceAsStream(GameConfig.VOXEL_FRAGMENT_SHADER)) {
            if (is == null) return "?";
            String src = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("#define\\s+PERF_BISECT_STAGE\\s+(\\d+)")
                    .matcher(src);
            return m.find() ? m.group(1) : "?";
        } catch (Exception e) {
            return "?";
        }
    }
}
