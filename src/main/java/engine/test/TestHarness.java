package engine.test;

import engine.Game;
import engine.core.config.GameConfig;
import engine.entity.Player;
import engine.render.camera.PerspectiveCamera;
import engine.time.TimeManager;
import engine.world.World;
import org.joml.Vector3f;

/**
 * Automated test harness for scripted movement and performance testing.
 *
 * Executes a scripted 20-second movement timeline:
 * - 0-5s: Move forward
 * - 5-10s: Rotate yaw + strafe right
 * - 10-15s: Circle with pitch oscillation
 * - 15-20s: Move backward
 *
 * Direct player position mutation (spectator-style); integration still depends on frame dt.
 * The default scenario keeps the sun moving. -Dvoxel.testStatic=true explicitly
 * selects a fixed-camera, paused-sun control instead.
 */
public class TestHarness {
    private final boolean enabled;
    private final float scenarioDuration;
    private final boolean staticScene = Boolean.getBoolean("voxel.testStatic");

    private float elapsedTime = 0f;
    private boolean runCompleted = false;

    private Player player;
    private PerspectiveCamera camera;
    private TimeManager timeManager;
    private Vector3f initialPosition;

    public TestHarness() {
        this.enabled = GameConfig.TEST_HARNESS_ENABLED;
        this.scenarioDuration = GameConfig.TEST_SCENARIO_DURATION;
    }

    /**
     * Initialize the test run.
     * Called once when entering PlayingState.
     *
     * @param game Game instance
     * @param world World instance
     * @param player Player instance
     * @param camera Camera instance
     */
    public void beginRun(Game game, World world, Player player, PerspectiveCamera camera) {
        if (!enabled) return;

        this.player = player;
        this.camera = camera;
        this.timeManager = world.getTimeManager();

        // Force deterministic spawn position/orientation
        this.initialPosition = new Vector3f(GameConfig.TEST_FORCE_SPAWN_POS);
        player.setPosition(initialPosition.x, initialPosition.y, initialPosition.z);
        player.setYaw(GameConfig.TEST_FORCE_SPAWN_YAW);
        player.setPitch(GameConfig.TEST_FORCE_SPAWN_PITCH);

        // Enable spectator mode for direct position control
        player.setSpectatorMode(true);

        timeManager.setPaused(staticScene || Boolean.getBoolean("voxel.pauseTime"));

        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("TEST HARNESS STARTED");
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("World: " + GameConfig.TEST_WORLD_NAME);
        System.out.println("Spawn: " + initialPosition);
        System.out.println("Yaw: " + GameConfig.TEST_FORCE_SPAWN_YAW + "°");
        System.out.println("Pitch: " + GameConfig.TEST_FORCE_SPAWN_PITCH + "°");
        System.out.println("Scenario Duration: " + scenarioDuration + "s");
        System.out.println("Scenario: " + (staticScene ? "fixed camera and sun" : "movement timeline"));
        System.out.println("Sun: paused=" + timeManager.isPaused()
                + "; timeOfDay=" + timeManager.getTimeOfDay());
        System.out.println("═══════════════════════════════════════════════════════════");

        elapsedTime = 0f;
        runCompleted = false;
    }

    /**
     * Update the automated movement scenario.
     * Called every frame from PlayingState.update(), before the game update.
     *
     * @param deltaTime Delta time in seconds
     */
    public void update(float deltaTime) {
        if (!enabled || runCompleted) return;

        elapsedTime += deltaTime;

        if (elapsedTime >= scenarioDuration) {
            runCompleted = true;
            System.out.println("TEST HARNESS COMPLETED: " + elapsedTime + "s; position=" + player.getPosition());
            System.out.println("Sun at completion: paused=" + timeManager.isPaused()
                    + "; timeOfDay=" + timeManager.getTimeOfDay());
            return;
        }
        if (staticScene) return;

        // Apply timeline-based movement
        if (elapsedTime < 5f) {
            // Phase 1 (0-5s): Move forward
            moveForward(2.0f * deltaTime); // 2 blocks/sec
        } else if (elapsedTime < 10f) {
            // Phase 2 (5-10s): Rotate yaw + strafe right
            rotate(30f * deltaTime, 0f); // 30 deg/sec yaw
            strafeRight(1.5f * deltaTime); // 1.5 blocks/sec
        } else if (elapsedTime < 15f) {
            // Phase 3 (10-15s): Circle with pitch oscillation
            float t = (elapsedTime - 10f) / 5f; // 0->1 over 5 seconds
            rotate(45f * deltaTime, (float)Math.sin(t * Math.PI * 4) * 10f * deltaTime);
            moveForward(1.0f * deltaTime); // Circle motion
            strafeRight(1.0f * deltaTime);
        } else if (elapsedTime < 20f) {
            // Phase 4 (15-20s): Move backward
            moveBackward(2.0f * deltaTime); // 2 blocks/sec
        }
    }

    /**
     * Check if the test scenario is complete.
     */
    public boolean isRunCompleted() {
        return runCompleted;
    }

    /**
     * Get elapsed time in the scenario.
     */
    public float getElapsedTime() {
        return elapsedTime;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MOVEMENT HELPERS (direct position mutation for determinism)
    // ═══════════════════════════════════════════════════════════════════════════

    private void moveForward(float distance) {
        Vector3f pos = player.getPosition();
        float yaw = player.getYaw();
        float radians = (float)Math.toRadians(yaw);

        // Forward direction (yaw = 0 is -Z)
        float dx = (float)Math.sin(radians) * distance;
        float dz = -(float)Math.cos(radians) * distance;

        player.setPosition(pos.x + dx, pos.y, pos.z + dz);
    }

    private void moveBackward(float distance) {
        moveForward(-distance);
    }

    private void strafeRight(float distance) {
        Vector3f pos = player.getPosition();
        float yaw = player.getYaw();
        float radians = (float)Math.toRadians(yaw + 90f); // Right is +90 degrees

        float dx = (float)Math.sin(radians) * distance;
        float dz = -(float)Math.cos(radians) * distance;

        player.setPosition(pos.x + dx, pos.y, pos.z + dz);
    }

    private void rotate(float deltaYaw, float deltaPitch) {
        player.setYaw(player.getYaw() + deltaYaw);
        player.setPitch(player.getPitch() + deltaPitch);

        // Clamp pitch to prevent gimbal lock
        if (player.getPitch() > 89f) player.setPitch(89f);
        if (player.getPitch() < -89f) player.setPitch(-89f);
    }
}
