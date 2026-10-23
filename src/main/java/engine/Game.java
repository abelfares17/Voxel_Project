// src/main/java/engine/Game.java
package engine;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR_DISABLED;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_B;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F10;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F11;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F12;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F2;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F3;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F4;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F6;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F7;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F8;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F9;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_L;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_P;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.glfwSetInputMode;

import static org.lwjgl.opengl.GL11.glFinish;

import engine.core.config.GameConfig;
import engine.core.config.RenderConfig;
import engine.entity.Player;
import engine.input.Input;
import engine.physics.PhysicsEngine;
import engine.render.TextRenderer;
import engine.render.GpuPassTimer;
import engine.render.camera.PerspectiveCamera;
import engine.render.core.RenderContext;
import engine.render.renderer.ShadowRenderer;
import engine.render.renderer.SkyRenderer;
import engine.render.renderer.SunRenderer;
import engine.render.renderer.VoxelRenderer;
import engine.util.ScreenCapture;
import engine.world.Chunk;
import engine.world.World;
import engine.test.CameraCullingValidator;

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
    private final CameraCullingValidator cameraCullingValidator = new CameraCullingValidator();

    private final TextRenderer textRenderer;

    private long lastTime;
    private int winWidth;
    private int winHeight;

    // Debug notification display
    private String debugNotificationText = null;
    private long debugNotificationEndTime = 0;
    private static final long DEBUG_NOTIFICATION_DURATION_MS = 3000;

    public Game(long window, Input input, World world,
                VoxelRenderer voxelRenderer, ShadowRenderer shadowRenderer,
                SkyRenderer skyRenderer, SunRenderer sunRenderer,
                TextRenderer textRenderer) {
        this.window = window;
        this.input = input;
        this.world = world;

        this.physicsEngine = new PhysicsEngine();

        // Spawn position (test harness overrides this later)
        float spawnY = world.getSpawnY() + 2f;
        this.player = new Player(0.5f, spawnY, 0.5f, physicsEngine);

        // Load player position from save (unless in test harness mode)
        if (!GameConfig.TEST_HARNESS_ENABLED) {
            world.getStorage().loadPlayer(player);
        } else {
            // Test harness: Use deterministic spawn position
            Vector3f spawnPos = GameConfig.TEST_FORCE_SPAWN_POS;
            player.setPosition(spawnPos.x, spawnPos.y, spawnPos.z);
            player.setYaw(GameConfig.TEST_FORCE_SPAWN_YAW);
            player.setPitch(GameConfig.TEST_FORCE_SPAWN_PITCH);
            System.out.println("Test harness: Player spawned at " + spawnPos +
                             " (yaw=" + GameConfig.TEST_FORCE_SPAWN_YAW +
                             ", pitch=" + GameConfig.TEST_FORCE_SPAWN_PITCH + ")");
        }

        this.camera = new PerspectiveCamera(
                RenderConfig.CAMERA_FOV,
                16f / 9f,
                RenderConfig.CAMERA_NEAR,
                RenderConfig.CAMERA_FAR,
                player
        );

        this.voxelRenderer = voxelRenderer;
        this.shadowRenderer = shadowRenderer;
        this.skyRenderer = skyRenderer;
        this.sunRenderer = sunRenderer;
        this.textRenderer = textRenderer;

        this.lastTime = System.currentTimeMillis();

        // Set initial camera angles (if not already set by test harness)
        if (!GameConfig.TEST_HARNESS_ENABLED) {
            this.player.setYaw(0f);
            this.player.setPitch(0f);
        }
    }

    public void setAspect(float aspect) {
        camera.setAspect(aspect);
    }

    public void setWinDim(int winWidth, int winHeight) {
        this.winWidth = winWidth;
        this.winHeight = winHeight;
    }

    public void update(float deltaTimeSec) {
        long dtMillis = (long)(deltaTimeSec * 1000.0f);

        world.getTimeManager().update(dtMillis);

        int[] chunkCoords = player.getChunkCoords(GameConfig.CHUNK_WIDTH, GameConfig.CHUNK_DEPTH);
        int playerChunkX = chunkCoords[0];
        int playerChunkZ = chunkCoords[1];

        Chunk currentChunk = world.getChunk(playerChunkX, playerChunkZ);
        if (currentChunk != null && !GameConfig.TEST_HARNESS_ENABLED) {
            player.update(currentChunk, input);
        }

        world.update(playerChunkX, playerChunkZ);

        // The harness owns the pose and controls; real mouse/key input must not alter a run.
        if (GameConfig.TEST_HARNESS_ENABLED) return;

        // Toggle spectator mode
        if (input.isKeyPressed(GLFW_KEY_P)) {
            player.setSpectatorMode(!player.isSpectatorMode());
        }

        // Escape cursor
        if (input.isKeyPressed(GLFW_KEY_ESCAPE)) {
            glfwSetInputMode(window, GLFW_CURSOR, GameConfig.INITIAL_CURSOR_MODE);
        }

        if (input.isMousePressed(GLFW_MOUSE_BUTTON_LEFT)) {
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        }

        // Screenshot (F2)
        if (input.isKeyJustPressed(GLFW_KEY_F2)) {
            ScreenCapture.capture(winWidth, winHeight);
            showDebugNotification("Screenshot Saved");
        }

        // Toggle AO debug (F3)
        if (input.isKeyJustPressed(GLFW_KEY_F3)) {
            voxelRenderer.toggleDebugAO();
            showDebugNotification(voxelRenderer.isDebugAO() ? "AO Debug: ON" : "AO Debug: OFF");
        }

        // Cycle shadow debug mode (F4)
        if (input.isKeyJustPressed(GLFW_KEY_F4)) {
            voxelRenderer.cycleShadowDebug();
            String[] modes = {
                "Shadow Debug: OFF",
                "Shadow Debug: Factor",
                "Shadow Debug: Cascade Colors",
                "Shadow Debug: World-Cell Hash",
                "Shadow Debug: Fog Factor",
                "Shadow Debug: Cascade SOLID",
                "Shadow Debug: A/B Diff",
                "Shadow Debug: Lit Before Fog"
            };
            showDebugNotification(modes[voxelRenderer.getDebugShadow()]);
        }

        // Toggle sun/time pause (F6)
        if (input.isKeyJustPressed(GLFW_KEY_F6)) {
            world.getTimeManager().togglePause();
            showDebugNotification(world.getTimeManager().isPaused() ? "Sun Pause: ON" : "Sun Pause: OFF");
        }

        // Cycle PCF mode info (F7)
        if (input.isKeyJustPressed(GLFW_KEY_F7)) {
            voxelRenderer.cycleForceCascade();
            String[] modes = {"Cascade: Normal", "Cascade: Force 0", "Cascade: Force 1", "Cascade: Force 2"};
            showDebugNotification(modes[voxelRenderer.getForceCascade() + 1]);
        }

        // Cycle forced cascade (F8)
        if (input.isKeyJustPressed(GLFW_KEY_F8)) {
            voxelRenderer.cycleForceCascade();
            String[] modes = {"Cascade: Normal", "Cascade: Force 0", "Cascade: Force 1", "Cascade: Force 2"};
            showDebugNotification(modes[voxelRenderer.getForceCascade() + 1]);
        }

        // Cycle bias mode (F9)
        if (input.isKeyJustPressed(GLFW_KEY_F9)) {
            voxelRenderer.cycleBiasMode();
            String[] modes = {"Bias: Normal", "Bias: Constant", "Bias: Zero", "Bias: Doubled"};
            showDebugNotification(modes[voxelRenderer.getBiasMode()]);
        }

        // Toggle normal-offset shadow mode (F10)
        if (input.isKeyJustPressed(GLFW_KEY_F10)) {
            voxelRenderer.toggleNormalOffset();
            showDebugNotification(voxelRenderer.getNormalOffsetMode() == 1 ? "Normal Offset: ON" : "Normal Offset: OFF");
        }

        // Toggle light basis freeze (F11)
        if (input.isKeyJustPressed(GLFW_KEY_F11)) {
            shadowRenderer.toggleFreezeLightBasis();
            showDebugNotification(shadowRenderer.isFreezeLightBasis() ? "Light Basis: FROZEN" : "Light Basis: LIVE");
        }

        // Toggle snapped XY freeze (F12), Shift+F12 for Z range
        if (input.isKeyJustPressed(GLFW_KEY_F12)) {
            if (input.crouch) {
                shadowRenderer.toggleFreezeZRange();
                showDebugNotification(shadowRenderer.isFreezeZRange() ? "Z Range: FROZEN" : "Z Range: LIVE");
            } else {
                shadowRenderer.toggleFreezeSnappedXY();
                showDebugNotification(shadowRenderer.isFreezeSnappedXY() ? "Snapped XY: FROZEN" : "Snapped XY: LIVE");
            }
        }

        // Toggle leapfrog shadow system (L)
        if (input.isKeyJustPressed(GLFW_KEY_L)) {
            shadowRenderer.toggleLeapfrog();
            showDebugNotification(shadowRenderer.isLeapfrogEnabled() ? "Leapfrog Shadow: ON" : "Leapfrog Shadow: OFF");
        }

        // Toggle blend mode: dither vs linear (B)
        if (input.isKeyJustPressed(GLFW_KEY_B)) {
            shadowRenderer.toggleBlendMode();
            showDebugNotification(shadowRenderer.isDitherBlend() ? "Blend: DITHER" : "Blend: LINEAR");
        }
    }

    public void render(RenderContext context, GpuPassTimer gpuTimer) {
        //world.getMeshManager().uploadMeshesIfAny();

        context.setCamera(camera);
        context.setWorld(world);
        context.setWindowSize(winWidth, winHeight);

        // Cache time-dependent values for frame consistency
        float timeOfDay = world.getTimeManager().getTimeOfDay();
        context.setTimeOfDay(timeOfDay);
        context.setSkyColor(RenderConfig.getSkyColor(timeOfDay));
        context.setSunDirLighting(world.getTimeManager().getSunData().sunDir());
        context.setSunDirShadows(world.getTimeManager().getSunData().sunDir());
        context.setDayNightPeriodSec(world.getTimeManager().getDayNightPeriodSec());

        if (gpuTimer != null) gpuTimer.beginFrame(context);
        world.resetDepthStats();

        // Render pipeline; non-overlapping queries, no synchronous result readback.
        if (gpuTimer != null) gpuTimer.beginPass(GpuPassTimer.Pass.SHADOW);
        shadowRenderer.render(context);
        if (gpuTimer != null) gpuTimer.endPass();

        // Per-pass timing: glFinish after shadow pass to isolate shadow GPU cost.
        // WARNING: breaks GPU pipelining → artificially inflates total frame time.
        // Only active when SHADOW_SPLIT_TIMING=true (hypothesis 6.3 test C3).
        if (GameConfig.SHADOW_SPLIT_TIMING) {
            long tShadow0 = System.nanoTime();
            glFinish();
            long tShadow1 = System.nanoTime();
            context.setShadowFinishMs((tShadow1 - tShadow0) / 1e6);
        }

        if (cameraCullingValidator.due(context.getDeltaTime())) {
            if (gpuTimer != null) throw new IllegalStateException("Disable GPU timing during camera validation");
            cameraCullingValidator.validate(context, skyRenderer, sunRenderer, voxelRenderer);
        } else {
            if (gpuTimer != null) gpuTimer.beginPass(GpuPassTimer.Pass.SKY_SUN);
            skyRenderer.render(context);
            sunRenderer.render(context);
            if (gpuTimer != null) gpuTimer.endPass();
            if (gpuTimer != null) gpuTimer.beginPass(GpuPassTimer.Pass.VOXEL);
            voxelRenderer.render(context);
            if (gpuTimer != null) gpuTimer.endPass();
        }

        if (gpuTimer != null) gpuTimer.beginPass(GpuPassTimer.Pass.UI);
        renderUI(context);
        if (gpuTimer != null) {
            gpuTimer.endPass();
            gpuTimer.endFrame(world.getDepthDrawCalls(), world.getDepthVertices(),
                    voxelRenderer.getDrawCalls(), voxelRenderer.getDrawnVertices());
        }
    }

    private void showDebugNotification(String message) {
        debugNotificationText = message;
        debugNotificationEndTime = System.currentTimeMillis() + DEBUG_NOTIFICATION_DURATION_MS;
    }

    private void renderUI(RenderContext context) {
        Matrix4f ortho = new Matrix4f().ortho2D(0, winWidth, winHeight, 0);

        float t = world.getTimeManager().getTimeOfDay();
        int totalMinutes = (int) (t * 24 * 60);
        totalMinutes = totalMinutes % (24 * 60);
        int hour = totalMinutes / 60;
        int minute = totalMinutes % 60;
        String heureStr = String.format("%02d:%02d", hour, minute);

        float textScale = 2.0f;
        textRenderer.drawText(
                heureStr, 20, winHeight - 100,
                new Vector3f(1, 1, 0.85f),
                ortho, winHeight, textScale
        );

        String fpsStr = String.format("FPS: %d", context.getFpsReal());
        textRenderer.drawText(
                fpsStr, 20, winHeight - 140,
                new Vector3f(0.9f, 1f, 0.9f),
                ortho, winHeight, textScale
        );

        // Display debug notification if active
        if (debugNotificationText != null && System.currentTimeMillis() < debugNotificationEndTime) {
            float textWidth = debugNotificationText.length() * 10 * textScale;
            float centerX = (winWidth - textWidth) / 2;
            float bottomY = 80;

            textRenderer.drawText(
                    debugNotificationText, centerX, bottomY,
                    new Vector3f(1.0f, 1.0f, 0.0f),
                    ortho, winHeight, textScale
            );
        } else if (debugNotificationText != null && System.currentTimeMillis() >= debugNotificationEndTime) {
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
