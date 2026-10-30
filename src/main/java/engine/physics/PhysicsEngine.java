package engine.physics;

import engine.core.config.GameConfig;
import engine.entity.component.PhysicsComponent;
import engine.world.Chunk;
import engine.world.World;

/**
 * Centralized physics engine for all entities.
 * Handles frame-rate independent gravity, friction, velocity integration,
 * sneak edge-guard, and cross-chunk collision resolution.
 */
public class PhysicsEngine {

    private final CollisionDetector collisionDetector;

    /** Physics gravity acceleration in blocks/sec^2 */
    private static final float GRAVITY_ACCEL = -28.0f;
    /** Terminal fall speed in blocks/sec */
    private static final float MAX_FALL_SPEED = -42.0f;
    /** Vertical jump impulse in blocks/sec (~1.38 blocks peak jump height) */
    private static final float JUMP_IMPULSE = 8.8f;

    public PhysicsEngine() {
        this.collisionDetector = new CollisionDetector();
    }

    /**
     * Update physics for an entity against the full multi-chunk World using deltaTime (seconds).
     */
    public float[] update(PhysicsComponent physics, float x, float y, float z,
                          float width, float height, float depth,
                          World world, float deltaTime, boolean sneakEdgeGuard) {
        float dt = Math.min(Math.max(deltaTime, 0.0005f), 0.05f);

        // 1. Integrate vertical velocity (gravity)
        if (physics.isAffectedByGravity()) {
            float vy = physics.getVelocityY() + GRAVITY_ACCEL * dt;
            if (vy < MAX_FALL_SPEED) vy = MAX_FALL_SPEED;
            physics.setVelocityY(vy);
        }

        float dx = physics.getVelocityX() * dt;
        float dy = physics.getVelocityY() * dt;
        float dz = physics.getVelocityZ() * dt;

        boolean startedOnGround = physics.isOnGround();

        // 2. Sneak Edge-Guard (Minecraft crouch behavior: prevent walking off block ledges)
        if (sneakEdgeGuard && startedOnGround && dy <= 0f) {
            while (Math.abs(dx) > 0.001f && !collisionDetector.isOnGround(x + dx, y, z, width, depth, world)) {
                dx = (Math.abs(dx) <= 0.02f) ? 0f : (dx - Math.signum(dx) * 0.02f);
            }
            while (Math.abs(dz) > 0.001f && !collisionDetector.isOnGround(x + dx, y, z + dz, width, depth, world)) {
                dz = (Math.abs(dz) <= 0.02f) ? 0f : (dz - Math.signum(dz) * 0.02f);
            }
        }

        // 3. Resolve X, Y, Z axes independently against World
        float newX = collisionDetector.moveAxis(x, y, z, dx, width, height, depth,
                CollisionDetector.Axis.X, world);
        if (Math.abs(newX - (x + dx)) > 1e-4f) {
            physics.setVelocityX(0f);
        }

        float newY = collisionDetector.moveAxis(newX, y, z, dy, width, height, depth,
                CollisionDetector.Axis.Y, world);
        if (dy > 0f && Math.abs(newY - (y + dy)) > 1e-4f) {
            // Hit ceiling: zero upward velocity immediately
            physics.setVelocityY(0f);
        }

        float newZ = collisionDetector.moveAxis(newX, newY, z, dz, width, height, depth,
                CollisionDetector.Axis.Z, world);
        if (Math.abs(newZ - (z + dz)) > 1e-4f) {
            physics.setVelocityZ(0f);
        }

        // 4. Ground state & snap
        if (physics.getVelocityY() <= 0f) {
            boolean onGround = collisionDetector.isOnGround(newX, newY, newZ, width, depth, world);
            physics.setOnGround(onGround);
            if (onGround) {
                float[] snapped = collisionDetector.snapToGround(newY, physics.getVelocityY(), newX, newZ, width, depth, world);
                newY = snapped[0];
                physics.setVelocityY(0f);
            }
        } else {
            physics.setOnGround(false);
        }

        // 5. Exponential horizontal damping (frame-rate independent friction)
        float damping = (float) Math.pow(0.0008f, dt);
        physics.setVelocityX(physics.getVelocityX() * damping);
        physics.setVelocityZ(physics.getVelocityZ() * damping);

        return new float[]{newX, newY, newZ};
    }

    /**
     * Legacy single-chunk physics update.
     */
    public float[] update(PhysicsComponent physics, float x, float y, float z,
                          float width, float height, float depth, Chunk chunk) {
        if (!physics.isOnGround()) {
            physics.addVelocity(0, GameConfig.GRAVITY, 0);
        }

        float vx = physics.getVelocityX();
        float vy = physics.getVelocityY();
        float vz = physics.getVelocityZ();

        float newX = collisionDetector.moveAxis(x, y, z, vx, width, height, depth,
                CollisionDetector.Axis.X, chunk);
        float newY = collisionDetector.moveAxis(newX, y, z, vy, width, height, depth,
                CollisionDetector.Axis.Y, chunk);
        float newZ = collisionDetector.moveAxis(newX, newY, z, vz, width, height, depth,
                CollisionDetector.Axis.Z, chunk);

        if (vy <= 0) {
            float[] groundSnap = collisionDetector.snapToGround(newY, vy, newX, newZ, width, depth, chunk);
            newY = groundSnap[0];
            physics.setVelocityY(groundSnap[1]);

            boolean onGround = collisionDetector.isOnGround(newX, newY, newZ, width, depth, chunk);
            physics.setOnGround(onGround);
        } else {
            physics.setOnGround(false);
        }

        physics.setVelocityX(vx * GameConfig.PLAYER_FRICTION);
        physics.setVelocityZ(vz * GameConfig.PLAYER_FRICTION);

        return new float[]{newX, newY, newZ};
    }

    /**
     * Apply a jump impulse to an entity.
     */
    public void jump(PhysicsComponent physics) {
        if (physics.isOnGround()) {
            physics.setVelocityY(JUMP_IMPULSE);
            physics.setOnGround(false);
        }
    }

    public CollisionDetector getCollisionDetector() {
        return collisionDetector;
    }
}
