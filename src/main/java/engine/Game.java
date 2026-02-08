// src/main/java/engine/Game.java
package engine;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR_DISABLED;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F2;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F3;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F4;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F5;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F6;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F7;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F8;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F9;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F10;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F11;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F12;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_P;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.glfwSetInputMode;

import engine.core.config.GameConfig;
import engine.core.config.RenderConfig;
import engine.entity.Player;
import engine.input.Input;
import engine.physics.PhysicsEngine;
import engine.render.TextRenderer;
import engine.render.camera.PerspectiveCamera;
import engine.render.core.RenderContext;
import engine.render.renderer.ShadowRenderer;
import engine.render.renderer.SkyRenderer;
import engine.render.renderer.SunRenderer;
import engine.render.renderer.VoxelRenderer;
import engine.util.ScreenCapture;
import engine.world.Chunk;
import engine.world.World;

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

    // Debug notification display
    private String debugNotificationText = null;
    private long debugNotificationEndTime = 0;
    private static final long DEBUG_NOTIFICATION_DURATION_MS = 3000;

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

        // Initialize CSM debug logging with world metadata
        //CSMDebugLog.get().logMetadata(world.getWorldName(), world.getSeed());
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

        // Screenshot capture (F2)
        if (input.isKeyJustPressed(GLFW_KEY_F2)) {
            ScreenCapture.capture(winWidth, winHeight);
            showDebugNotification("Screenshot Saved");
        }

        // Toggle AO debug mode (F3)
        if (input.isKeyJustPressed(GLFW_KEY_F3)) {
            voxelRenderer.toggleDebugAO();
            showDebugNotification(voxelRenderer.isDebugAO() ? "AO Debug: ON" : "AO Debug: OFF");
        }

        // Cycle shadow debug mode (F4)
        if (input.isKeyJustPressed(GLFW_KEY_F4)) {
            voxelRenderer.cycleShadowDebug();
            String[] modes = {"Shadow Debug: OFF", "Shadow Debug: Factor", "Shadow Debug: UV/Depth", "Shadow Debug: Bounds", "Shadow Debug: Cascades", "Shadow Debug: Cascade+Albedo", "Shadow Debug: Raw DEPTH Z", "Shadow Debug: Cascade OOB", "Shadow Debug: Bias (R=scale G=bias B=cascade)"};
            showDebugNotification(modes[voxelRenderer.getDebugShadow()]);
        }

        // Cycle shadow freeze mode for debugging (F5): OFF -> MATRIX -> MAP -> FULL -> OFF
        // MATRIX: matrices frozen, depth pass runs - if shimmer persists, issue is sampling/bias
        // MAP: matrices update, depth pass skipped - if shimmer disappears, issue is depth pass
        // FULL: both frozen (baseline reference)
        if (input.isKeyJustPressed(GLFW_KEY_F5)) {
            voxelRenderer.getShadowRenderer().cycleFreezeMode();
            showDebugNotification(voxelRenderer.getShadowRenderer().getFreezeModeString());
        }

        // Toggle sun/time pause for debugging (F6)
        if (input.isKeyJustPressed(GLFW_KEY_F6)) {
            world.getTimeManager().togglePause();
            String status = world.getTimeManager().isPaused() ? "Sun Pause: ON" : "Sun Pause: OFF";
            System.out.println("=== " + status + " ===");
            showDebugNotification(status);
        }

        // Cycle PCF mode for shimmer isolation testing (F7)
        if (input.isKeyJustPressed(GLFW_KEY_F7)) {
            voxelRenderer.cyclePcfMode();
            String[] modes = {"PCF: Baseline", "PCF: No Rotation", "PCF: Box 3x3", "PCF: Texel-Stable", "PCF: UV-Snapped", "PCF: Single Sample"};
            showDebugNotification(modes[voxelRenderer.getPcfMode()]);
        }

        // Cycle forced cascade for isolation testing (F8)
        if (input.isKeyJustPressed(GLFW_KEY_F8)) {
            voxelRenderer.cycleForceCascade();
            String[] modes = {"Cascade: Normal", "Cascade: Force 0 (near)", "Cascade: Force 1 (mid)", "Cascade: Force 2 (far)"};
            showDebugNotification(modes[voxelRenderer.getForceCascade() + 1]);
        }

        // Cycle bias mode for isolation testing (F9)
        if (input.isKeyJustPressed(GLFW_KEY_F9)) {
            voxelRenderer.cycleBiasMode();
            String[] modes = {"Bias: Normal", "Bias: Constant (no slope)", "Bias: Zero", "Bias: Doubled"};
            showDebugNotification(modes[voxelRenderer.getBiasMode()]);
        }

        // Toggle normal-offset shadow mode (F10) - main shimmer fix
        if (input.isKeyJustPressed(GLFW_KEY_F10)) {
            voxelRenderer.toggleNormalOffset();
            showDebugNotification(voxelRenderer.getNormalOffsetMode() == 1 ? "Normal Offset: ON" : "Normal Offset: OFF");
        }

        // CSM diagnostic freeze toggles for isolating shimmer source
        // F11 = Toggle light basis freeze (isolates basis discontinuities)
        if (input.isKeyJustPressed(GLFW_KEY_F11)) {
            shadowRenderer.toggleFreezeLightBasis();
            showDebugNotification(shadowRenderer.isFreezeLightBasis() ? "Light Basis: FROZEN" : "Light Basis: LIVE");
        }

        // F12 = Toggle snapped XY freeze (isolates snap grid changes)
        // Shift+F12 = Toggle Z range freeze (isolates depth range changes)
        if (input.isKeyJustPressed(GLFW_KEY_F12)) {
            if (input.crouch) {  // Shift is held
                shadowRenderer.toggleFreezeZRange();
                showDebugNotification(shadowRenderer.isFreezeZRange() ? "Z Range: FROZEN" : "Z Range: LIVE");
            } else {
                shadowRenderer.toggleFreezeSnappedXY();
                showDebugNotification(shadowRenderer.isFreezeSnappedXY() ? "Snapped XY: FROZEN" : "Snapped XY: LIVE");
            }
        }
    }

    /**
     * Render the game scene using the renderer architecture.
     */
    public void render() {
        // Upload mesh data from worker threads
        world.getMeshManager().uploadMeshesIfAny();

        // Calculate delta time
        float deltaTime = (System.currentTimeMillis() - lastTime) / 1000.0f;

        // Create render context with all necessary data
        RenderContext context = new RenderContext();
        context.setCamera(camera);
        context.setWorld(world);
        context.setWindowSize(winWidth, winHeight);
        context.beginFrame(deltaTime);

        // Set filtered sun direction for shadow mapping (reduces shimmer)
        context.setSunDirection(world.getTimeManager().getSunDirForShadows(deltaTime));

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
     * Show a debug notification message centered at bottom of screen for 3 seconds.
     */
    private void showDebugNotification(String message) {
        debugNotificationText = message;
        debugNotificationEndTime = System.currentTimeMillis() + DEBUG_NOTIFICATION_DURATION_MS;
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

        // Display debug notification if active
        if (debugNotificationText != null && System.currentTimeMillis() < debugNotificationEndTime) {
            // Calculate centered X position (approximate: 10 pixels per character at scale 2)
            float textWidth = debugNotificationText.length() * 10 * textScale;
            float centerX = (winWidth - textWidth) / 2;
            float bottomY = 80;  // 80 pixels from bottom

            textRenderer.drawText(
                    debugNotificationText, centerX, bottomY,
                    new Vector3f(1.0f, 1.0f, 0.0f),  // Bright yellow
                    ortho, winHeight, textScale
            );
        } else if (debugNotificationText != null && System.currentTimeMillis() >= debugNotificationEndTime) {
            // Clear expired notification
            debugNotificationText = null;
        }
    }

    public World getWorld() {
        return world;
    }

    public Player getPlayer() {
        return player;
    }
}