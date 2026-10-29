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
import static org.lwjgl.glfw.GLFW.GLFW_KEY_M;
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
    private final VoxelRenderer voxelRenderer;
    private final ShadowRenderer shadowRenderer;
    private final CameraCullingValidator cameraCullingValidator = new CameraCullingValidator();

    private final TextRenderer textRenderer;
    private boolean debugHudVisible;
    private boolean screenshotRequested;

    private long lastTime;
    private int winWidth;
    private int winHeight;

    // Debug notification display
    private String debugNotificationText = null;
    private long debugNotificationEndTime = 0;
    private static final long DEBUG_NOTIFICATION_DURATION_MS = 3000;

    public Game(long window, Input input, World world,
                VoxelRenderer voxelRenderer, ShadowRenderer shadowRenderer,
                SkyRenderer skyRenderer,
                TextRenderer textRenderer) {
        this.window = window;
        this.input = input;
        this.world = world;

        this.physicsEngine = new PhysicsEngine();

        // LoadingState preloads this location; do not query the unloaded origin for a saved player.
        Vector3f savedPosition = GameConfig.TEST_HARNESS_ENABLED ? GameConfig.TEST_FORCE_SPAWN_POS
                : world.getStorage().getSavedPlayerPosition();
        Vector3f spawn = savedPosition != null ? savedPosition : new Vector3f(0.5f, world.getSpawnY() + 2f, 0.5f);
        this.player = new Player(spawn.x, spawn.y, spawn.z, physicsEngine);

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
        this.textRenderer = textRenderer;

        this.lastTime = System.currentTimeMillis();

        // Keep the yaw/pitch restored by WorldStorage (new players already default to zero).
    }

    public void setAspect(float aspect) {
        camera.setAspect(aspect);
    }

    public void setWinDim(int winWidth, int winHeight) {
        this.winWidth = winWidth;
        this.winHeight = winHeight;
    }

    public void update(float deltaTimeSec) {
        world.getTimeManager().updateSeconds(deltaTimeSec);

        // Handle cursor transitions before player look consumes this frame's mouse delta.
        if (!GameConfig.TEST_HARNESS_ENABLED) {
            if (input.isKeyJustPressed(GLFW_KEY_ESCAPE)) input.setCursorCaptured(false);
            else if (input.isMousePressed(GLFW_MOUSE_BUTTON_LEFT)) input.setCursorCaptured(true);
        }

        int[] chunkCoords = player.getChunkCoords(GameConfig.CHUNK_WIDTH, GameConfig.CHUNK_DEPTH);
        int playerChunkX = chunkCoords[0];
        int playerChunkZ = chunkCoords[1];

        Chunk currentChunk = world.getChunk(playerChunkX, playerChunkZ);
        if (currentChunk != null && !GameConfig.TEST_HARNESS_ENABLED) {
            player.update(currentChunk, input);
        }

        chunkCoords = player.getChunkCoords(GameConfig.CHUNK_WIDTH, GameConfig.CHUNK_DEPTH);
        world.update(chunkCoords[0], chunkCoords[1]);

        // The harness owns the pose and controls; real mouse/key input must not alter a run.
        if (GameConfig.TEST_HARNESS_ENABLED) return;

        // Toggle spectator mode
        if (input.isKeyPressed(GLFW_KEY_P)) {
            player.setSpectatorMode(!player.isSpectatorMode());
        }

        // Screenshot (F2)
        if (input.isKeyJustPressed(GLFW_KEY_F2)) {
            screenshotRequested = true;
        }

        // F3 is a pose HUD, without changing the image being diagnosed. Shift+F3 retains AO.
        if (input.isKeyJustPressed(GLFW_KEY_F3)) {
            if (input.crouch) {
                voxelRenderer.toggleDebugAO();
                showDebugNotification(voxelRenderer.isDebugAO() ? "AO Debug: ON" : "AO Debug: OFF");
            } else {
                debugHudVisible = !debugHudVisible;
            }
        }

        // Cycle shadow debug mode (F4)
        if (input.isKeyJustPressed(GLFW_KEY_F4)) {
            if(input.crouch) {
                voxelRenderer.toggleShadowFade();
                showDebugNotification("Shadow distance fade: "+(voxelRenderer.isShadowFade()?"ON":"OFF"));
            } else {
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
        }

        if (input.isKeyJustPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_F5)) {
            shadowRenderer.toggleCasterCoverage();
            showDebugNotification(shadowRenderer.isCasterCoverageEnabled()
                    ? "CSM casters: corrected coverage" : "CSM casters: legacy coverage");
        }

        // Toggle sun/time pause (F6)
        if (input.isKeyJustPressed(GLFW_KEY_F6)) {
            world.getTimeManager().togglePause();
            showDebugNotification(world.getTimeManager().isPaused() ? "Time Pause: ON" : "Time Pause: OFF");
        }

        // Toggle wireframe mode (F7), Shift+F7 for greedy shadow mesh wireframe
        if (input.isKeyJustPressed(GLFW_KEY_F7)) {
            if (input.crouch) {
                voxelRenderer.toggleShadowMeshWireframe();
                showDebugNotification(voxelRenderer.isShadowMeshWireframe()
                        ? "Wireframe: Greedy Shadow Mesh" : "Wireframe: OFF");
            } else {
                voxelRenderer.toggleWireframe();
                showDebugNotification(voxelRenderer.isWireframe() ? "Wireframe: ON" : "Wireframe: OFF");
            }
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

        // Toggle wireframe mode (M), Shift+M for greedy shadow mesh wireframe
        if (input.isKeyJustPressed(GLFW_KEY_M)) {
            if (input.crouch) {
                voxelRenderer.toggleShadowMeshWireframe();
                showDebugNotification(voxelRenderer.isShadowMeshWireframe()
                        ? "Wireframe: Greedy Shadow Mesh" : "Wireframe: OFF");
            } else {
                voxelRenderer.toggleWireframe();
                showDebugNotification(voxelRenderer.isWireframe() ? "Wireframe: ON" : "Wireframe: OFF");
            }
        }
    }

    public void render(RenderContext context, GpuPassTimer gpuTimer) {
        world.getMeshManager().uploadMeshesIfAny();

        context.setCamera(camera);
        context.setWorld(world);
        context.setWindowSize(winWidth, winHeight);

        // Cache time-dependent values for frame consistency
        float timeOfDay = world.getTimeManager().getTimeOfDay();
        context.setTimeOfDay(timeOfDay);
        context.setCelestialState(world.getFrameCelestial());
        context.setShadowCoverage(world.snapshotShadowCoverage(voxelRenderer.isShadowFade()));
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
            cameraCullingValidator.validate(context, skyRenderer, voxelRenderer);
        } else {
            if (gpuTimer != null) gpuTimer.beginPass(GpuPassTimer.COMBINED_SCENE
                    ? GpuPassTimer.Pass.SCENE : GpuPassTimer.Pass.SKY_SUN);
            skyRenderer.render(context);
            if (gpuTimer != null && !GpuPassTimer.COMBINED_SCENE) {
                gpuTimer.endPass();
                gpuTimer.beginPass(GpuPassTimer.Pass.VOXEL);
            }
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
        // Read the completed back buffer (including F3 HUD), BEFORE Application swaps it.
        if (screenshotRequested) {
            screenshotRequested = false;
            captureScreenshot();
        }
    }

    private void captureScreenshot() {
        var path = ScreenCapture.capture(winWidth, winHeight);
        showDebugNotification(path != null ? "Screenshot saved" : "Screenshot failed (see console)");
    }

    private void showDebugNotification(String message) {
        debugNotificationText = message;
        debugNotificationEndTime = System.currentTimeMillis() + DEBUG_NOTIFICATION_DURATION_MS;
    }

    private void renderUI(RenderContext context) {
        Matrix4f ortho = new Matrix4f().ortho2D(0, winWidth, winHeight, 0);

        float textScale = 2.0f;
        if (debugHudVisible) {
            float t = context.getTimeOfDay();
            int totalMinutes = (int) (t * 24 * 60);
            totalMinutes = totalMinutes % (24 * 60);
            int hour = totalMinutes / 60;
            int minute = totalMinutes % 60;
            String heureStr = String.format(java.util.Locale.ROOT, "%02d:%02d  %s %.1f deg", hour, minute,
                    context.getCelestialState().activeLight(), context.getCelestialState().activeElevation());

            Vector3f pos = player.getPosition();
            String[] lines = {
                "World: " + world.getWorldName(),
                String.format(java.util.Locale.ROOT, "XYZ: %.3f / %.3f / %.3f", pos.x, pos.y, pos.z),
                String.format(java.util.Locale.ROOT, "Yaw: %.3f deg  Pitch: %.3f deg", player.getYaw(), player.getPitch()),
                String.format(java.util.Locale.ROOT, "Casters: %d/%d extra  Reach: %.0f  %s%s",
                        world.getCasterReady(),world.getCasterRequested(),world.getCasterPlan().reach(),
                        world.getCasterPlan().reachLimited()?"RANGE LIMIT ":"",
                        world.getCasterPlan().budgetLimited()?"BUDGET LIMIT":""),
                String.format(java.util.Locale.ROOT,"Fade: %s %.0f..%.0f  Coverage: %s",
                        context.getShadowCoverage().fadeEnabled()?"ON":"OFF",
                        context.getShadowCoverage().fadeStart(),context.getShadowCoverage().fadeEnd(),
                        context.getShadowCoverage().complete()?"COMPLETE":"DEGRADED"),
                String.format(java.util.Locale.ROOT,"Required: %d/%d  Prefetch: %d/%d",
                        context.getShadowCoverage().requiredReady(),context.getShadowCoverage().required(),
                        context.getShadowCoverage().prefetchReady(),context.getShadowCoverage().prefetch())
            };
            for (int i = 0; i < lines.length; i++) {
                float y = 48 + i * 48;
                textRenderer.drawText(lines[i], 22, y + 2, new Vector3f(0), ortho, winHeight, textScale);
                textRenderer.drawText(lines[i], 20, y, new Vector3f(1), ortho, winHeight, textScale);
            }

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
        }

        // Display debug notification if active
        if (debugNotificationText != null && System.currentTimeMillis() < debugNotificationEndTime) {
            float textWidth = debugNotificationText.length() * 10 * textScale;
            float centerX = (winWidth - textWidth) / 2;
            float bottomY = winHeight - 40;

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
