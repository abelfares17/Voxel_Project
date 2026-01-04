// src/main/java/engine/Game.java
package engine;

import engine.core.config.GameConfig;
import engine.core.config.RenderConfig;
import engine.entity.Player;
import engine.input.Input;
import engine.physics.PhysicsEngine;
import engine.render.TextRenderer;
import engine.render.camera.PerspectiveCamera;
import engine.render.core.RenderContext;
import engine.render.renderer.*;
import engine.world.Chunk;
import engine.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static org.lwjgl.glfw.GLFW.*;

public class Game {
    private final long window;
    private final Input input;
    private final World world;
    private final Player player;
    private final PerspectiveCamera camera;
    private final PhysicsEngine physicsEngine;

    // Renderers
    private final SkyRenderer skyRenderer;
    private final SunRenderer sunRenderer;
    private final VoxelRenderer voxelRenderer;
    private final ShadowRenderer shadowRenderer;

    private final TextRenderer textRenderer;

    private long lastTime;
    private int winWidth;
    private int winHeight;
    private long frameNumber = 0;

    /**
     * Create a new Game instance with renderer architecture.
     *
     * @param window         GLFW window handle
     * @param input          Input handler
     * @param world          Game world
     * @param voxelRenderer  Voxel chunk renderer
     * @param shadowRenderer Shadow mapping renderer
     * @param skyRenderer    Sky background renderer
     * @param sunRenderer    Sun disc renderer
     * @param textRenderer   Text overlay renderer
     */
    public Game(long window, Input input, World world,
                VoxelRenderer voxelRenderer, ShadowRenderer shadowRenderer,
                SkyRenderer skyRenderer, SunRenderer sunRenderer,
                TextRenderer textRenderer) {
        this.window = window;
        this.input = input;
        this.world = world;

        // Initialize physics engine
        this.physicsEngine = new PhysicsEngine();

        // Create player at spawn position
        float spawnY = world.getSpawnY() + 2f;
        this.player = new Player(0.5f, spawnY, 0.5f, physicsEngine);

        // Try to load saved player position
        world.getStorage().loadPlayer(player);

        // Initialize camera with default values from config, attached to player
        this.camera = new PerspectiveCamera(
                RenderConfig.CAMERA_FOV,
                16f / 9f,  // Initial aspect ratio, updated via setAspect()
                RenderConfig.CAMERA_NEAR,
                RenderConfig.CAMERA_FAR,
                player
        );

        // Assign renderers
        this.voxelRenderer = voxelRenderer;
        this.shadowRenderer = shadowRenderer;
        this.skyRenderer = skyRenderer;
        this.sunRenderer = sunRenderer;
        this.textRenderer = textRenderer;

        this.lastTime = System.currentTimeMillis();
        this.player.setYaw(0f);
        this.player.setPitch(0f);
    }

    /**
     * Update camera aspect ratio.
     *
     * @param aspect New aspect ratio (width/height)
     */
    public void setAspect(float aspect) {
        camera.setAspect(aspect);
    }

    /**
     * Update window dimensions for UI rendering.
     *
     * @param winWidth  Window width in pixels
     * @param winHeight Window height in pixels
     */
    public void setWinDim(int winWidth, int winHeight) {
        this.winWidth = winWidth;
        this.winHeight = winHeight;
    }

    /**
     * Update game state (physics, input, time).
     */
    public void update() {
        long now = System.currentTimeMillis();
        long dt = (now - lastTime);
        lastTime = now;

        // Update time and lighting
        world.getTimeManager().update(dt);

        // Get player chunk coordinates
        int[] chunkCoords = player.getChunkCoords(GameConfig.CHUNK_WIDTH, GameConfig.CHUNK_DEPTH);
        int playerChunkX = chunkCoords[0];
        int playerChunkZ = chunkCoords[1];

        // Get current chunk for collision detection
        Chunk currentChunk = world.getChunk(playerChunkX, playerChunkZ);
        if (currentChunk != null) {
            player.update(currentChunk, input);
        }

        // Update world (chunk loading/unloading, mesh processing)
        world.update(playerChunkX, playerChunkZ);

        // Toggle spectator mode
        if (input.isKeyPressed(GLFW_KEY_P)) {
            player.setSpectatorMode(!player.isSpectatorMode());
        }
        // Used to escape the cursor
        if (input.isKeyPressed(GLFW_KEY_ESCAPE)) {
            glfwSetInputMode(window, GLFW_CURSOR, GameConfig.INITIAL_CURSOR_MODE);
        }

        if (input.isMousePressed(GLFW_MOUSE_BUTTON_LEFT)) {
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        }
    }

    /**
     * Render the game scene using the renderer architecture.
     */
    public void render() {
        // Upload mesh data from worker threads
        world.getMeshManager().uploadMeshesIfAny();

        // Create render context with all necessary data
        RenderContext context = new RenderContext();
        context.setCamera(camera);
        context.setWorld(world);
        context.setWindowSize(winWidth, winHeight);
        context.beginFrame((System.currentTimeMillis() - lastTime) / 1000.0f);
        frameNumber++;

        // Render pipeline
        skyRenderer.render(context);     // Clear screen with sky color
        shadowRenderer.render(context);   // Render shadow depth map
        sunRenderer.render(context);      // Render sun disc
        voxelRenderer.render(context);    // Render voxel world

        // Render UI overlay (time, FPS)
        renderUI();
    }

    /**
     * Render UI overlay with time and FPS information.
     */
    private void renderUI() {
        Matrix4f ortho = new Matrix4f().ortho2D(0, winWidth, winHeight, 0);

        // Calculate time display (0.0 = 6am, 1.0 = next 6am)
        float t = world.getTimeManager().getTimeOfDay();
        int totalMinutes = (int) (t * 24 * 60);
        totalMinutes = totalMinutes % (24 * 60);
        int hour = totalMinutes / 60;
        int minute = totalMinutes % 60;
        String heureStr = String.format("%02d:%02d", hour, minute);

        // Display time (top-left corner)
        float textScale = 2.0f;
        textRenderer.drawText(
                heureStr, 20, winHeight - 100,
                new Vector3f(1, 1, 0.85f),  // Pale yellow
                ortho, winHeight, textScale
        );

        // Display FPS
        String fpsStr = String.format("FPS: %d", world.getTimeManager().getFps());
        textRenderer.drawText(
                fpsStr, 20, winHeight - 140,
                new Vector3f(0.9f, 1f, 0.9f),  // Pale green
                ortho, winHeight, textScale
        );
    }

    public World getWorld() {
        return world;
    }

    public Player getPlayer() {
        return player;
    }
}