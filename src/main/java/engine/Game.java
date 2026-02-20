// src/main/java/engine/Game.java
package engine;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR_DISABLED;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_0;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_9;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F10;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F11;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F12;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F2;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F3;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F4;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F5;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F6;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F7;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F8;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F9;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_J;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_K;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_L;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_P;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.glfwSetInputMode;

import engine.core.config.GameConfig;
import engine.core.config.RenderConfig;
import engine.entity.Player;
import engine.input.Input;
import engine.physics.PhysicsEngine;
import engine.render.GLStateValidator;
import engine.render.TextRenderer;
import engine.render.camera.PerspectiveCamera;
import engine.render.core.RenderContext;
import engine.render.renderer.ShadowRenderer;
import engine.render.renderer.SkyRenderer;
import engine.render.renderer.SunRenderer;
import engine.render.renderer.VoxelRenderer;
import engine.util.DebugFrameSnapshot;
import engine.util.ScreenCapture;
import engine.util.SkyFlashTrace;
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

        // Cycle shadow debug mode (F4) - expanded for discontinuity diagnosis
        if (input.isKeyJustPressed(GLFW_KEY_F4)) {
            voxelRenderer.cycleShadowDebug();
            String[] modes = {
                "Shadow Debug: OFF",
                "Shadow Debug: Factor (grayscale)",
                "Shadow Debug: Cascade Colors",
                "Shadow Debug: World-Cell Hash",
                "Shadow Debug: Fog Factor",
                "Shadow Debug: Cascade SOLID",
                "Shadow Debug: A/B Diff (R=diff)",
                "Shadow Debug: Lit Before Fog",
                "w-Validity Heatmap (Blue=valid, Green=B invalid)",
                "Leapfrog State (R=active, G=crossfade, B=shadow)"
            };
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

        // L = Toggle leapfrog shadow system (quantized basis + crossfade)
        if (input.isKeyJustPressed(GLFW_KEY_L)) {
            shadowRenderer.toggleLeapfrog();
            showDebugNotification(shadowRenderer.isLeapfrogEnabled() ? "Leapfrog Shadow: ON" : "Leapfrog Shadow: OFF");
        }

        // K = Toggle sky color freeze (workaround for shadow transition flashes)
        if (input.isKeyJustPressed(GLFW_KEY_K)) {
            RenderConfig.toggleFreezeSkyColor();
            showDebugNotification(RenderConfig.isFreezeSkyColor() ? "Sky Color: FROZEN (day)" : "Sky Color: DYNAMIC");
        }

        // J = Toggle one-frame freeze at swap (diagnostic for flash isolation)
        // If flash STILL occurs with freeze → shader/texture sampling bug
        // If flash DISAPPEARS → Java-side uniform timing bug
        if (input.isKeyJustPressed(GLFW_KEY_J)) {
            shadowRenderer.toggleFreezeOneFrameAtSwap();
            showDebugNotification(shadowRenderer.isFreezeOneFrameAtSwap()
                ? "Swap Freeze: ON (1 frame)"
                : "Swap Freeze: OFF");
        }

        // 0 = Toggle slow motion mode for debugging swap frames
        // (Using number key because M key has issues on Mac M2)
        if (input.isKeyJustPressed(GLFW_KEY_0)) {
            System.out.println("[DEBUG] 0 key pressed - toggling slow motion");
            shadowRenderer.toggleSlowMotion();
            showDebugNotification(shadowRenderer.isSlowMotionEnabled()
                ? "SLOW MOTION: ON (500ms/frame)"
                : "SLOW MOTION: OFF");
        }

        // 9 = Toggle one-frame freeze at swap (backup for J key)
        if (input.isKeyJustPressed(GLFW_KEY_9)) {
            System.out.println("[DEBUG] 9 key pressed - toggling swap freeze");
            shadowRenderer.toggleFreezeOneFrameAtSwap();
            showDebugNotification(shadowRenderer.isFreezeOneFrameAtSwap()
                ? "Swap Freeze: ON (1 frame)"
                : "Swap Freeze: OFF");
        }
    }

    /**
     * Render the game scene using the renderer architecture.
     */
    public void render() {
        // Increment frame counter for GL state validation (rate-limited debug tool)
        GLStateValidator.nextFrame();

        // Increment shadow renderer frame ID for debugging
        shadowRenderer.incrementFrameId();

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
        context.resetSkyClearState();  // Reset diagnostic state for this frame

        // ═══════════════════════════════════════════════════════════════════════════
        // CACHE TIME-DEPENDENT VALUES FOR FRAME CONSISTENCY
        // ═══════════════════════════════════════════════════════════════════════════
        // All time-dependent values are computed ONCE here and cached in RenderContext.
        // This ensures all renderers use the same values throughout the frame.
        float timeOfDay = world.getTimeManager().getTimeOfDay();
        context.setTimeOfDay(timeOfDay);
        context.setSkyColor(RenderConfig.getSkyColor(timeOfDay));
        context.setSunDirLighting(world.getTimeManager().getSunData().sunDir());
        context.setSunDirShadows(world.getTimeManager().getSunData().sunDir());

        frameNumber++;

        // ═══════════════════════════════════════════════════════════════════════════
        // SKYFLASH TRACE: FRAME_HEADER
        // ═══════════════════════════════════════════════════════════════════════════
        SkyFlashTrace.get().nextFrame();
        boolean isTransitioning = shadowRenderer.isLeapfrogTransitioning();
        SkyFlashTrace.get().logFrameHeader(
            timeOfDay,
            context.getSunDirLighting(),
            context.getSkyColor(),
            deltaTime,
            isTransitioning
        );

        // ═══════════════════════════════════════════════════════════════════════════
        // RENDER PIPELINE
        // ═══════════════════════════════════════════════════════════════════════════
        // Each renderer uses appropriate sun direction:
        // - skyRenderer:    timeOfDay (continuous) for sky color
        // - shadowRenderer: sunDirShadows (may be quantized internally)
        // - sunRenderer:    sunDirLighting (continuous) for sun disc position
        // - voxelRenderer:  sunDirLighting (continuous) for diffuse lighting
        skyRenderer.render(context);      // Clear screen with sky color
        shadowRenderer.render(context);   // Render shadow depth map
        sunRenderer.render(context);      // Render sun disc
        voxelRenderer.render(context);    // Render voxel world

        // DIAGNOSTIC: Log end-of-frame snapshot (rate-limited)
        DebugFrameSnapshot.nextFrame();
        DebugFrameSnapshot.logIfDue("FRAME", context, shadowRenderer);

        // Render UI overlay (time, FPS)
        renderUI();

        // SLOW MOTION: Add delay if enabled (for debugging swap frames)
        if (shadowRenderer.isSlowMotionEnabled()) {
            // Extra delay on special frames (transition start/stabilization/swap)
            long delay = shadowRenderer.isSpecialFrame()
                ? shadowRenderer.getSpecialFrameDelayMs()  // 1.5 second on special frames
                : shadowRenderer.getSlowMotionDelayMs();   // 500ms on normal frames
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
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

        // Display frame state indicator (for swap debugging)
        String frameStateStr = shadowRenderer.getFrameStateString();
        if (!frameStateStr.isEmpty()) {
            // Calculate centered X position
            float stateTextWidth = frameStateStr.length() * 10 * textScale;
            float stateCenterX = (winWidth - stateTextWidth) / 2;
            float stateY = winHeight - 60;  // Near top of screen

            // Color based on frame state
            Vector3f stateColor;
            switch (shadowRenderer.getCurrentFrameState()) {
                case TRANSITION_START_FRAME -> stateColor = new Vector3f(1.0f, 1.0f, 0.0f);  // Yellow
                case STABILIZATION_FRAME -> stateColor = new Vector3f(0.0f, 1.0f, 0.0f);  // Bright green
                case SWAP_FRAME -> stateColor = new Vector3f(1.0f, 0.0f, 0.0f);  // Bright red
                case POST_SWAP_FRAME -> stateColor = new Vector3f(1.0f, 0.5f, 0.0f);  // Orange
                default -> stateColor = new Vector3f(0.5f, 0.5f, 1.0f);  // Light blue for transitioning
            }

            textRenderer.drawText(
                    frameStateStr, stateCenterX, stateY,
                    stateColor,
                    ortho, winHeight, textScale
            );

            // Also display frame ID
            String frameIdStr = "Frame #" + shadowRenderer.getCurrentFrameId();
            float frameIdWidth = frameIdStr.length() * 10 * textScale;
            float frameIdX = (winWidth - frameIdWidth) / 2;
            textRenderer.drawText(
                    frameIdStr, frameIdX, stateY - 40,
                    new Vector3f(1.0f, 1.0f, 1.0f),  // White
                    ortho, winHeight, textScale
            );
        }

        // Display slow motion indicator when enabled
        if (shadowRenderer.isSlowMotionEnabled()) {
            String slowMoStr = "[SLOW MOTION]";
            float slowMoWidth = slowMoStr.length() * 10 * textScale;
            float slowMoX = (winWidth - slowMoWidth) / 2;
            textRenderer.drawText(
                    slowMoStr, slowMoX, winHeight - 180,
                    new Vector3f(1.0f, 0.0f, 1.0f),  // Magenta
                    ortho, winHeight, textScale
            );
        }

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