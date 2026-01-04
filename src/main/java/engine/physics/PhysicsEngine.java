package engine.physics;

import engine.core.config.GameConfig;
import engine.entity.component.PhysicsComponent;
import engine.world.Chunk;

/**
 * Centralized physics engine for all entities.
 * Handles gravity, friction, velocity integration, and collision resolution.
 */
public class PhysicsEngine {

    private final CollisionDetector collisionDetector;

    public PhysicsEngine() {
        this.collisionDetector = new CollisionDetector();
    }

    /**
     * Update physics for an entity.
     *
     * @param physics Physics component to update
     * @param x       Current X position
     * @param y       Current Y position (feet)
     * @param z       Current Z position
     * @param width   Entity width
     * @param height  Entity height
     * @param depth   Entity depth
     * @param chunk   Chunk for collision detection
     * @return Updated position as float[3] {newX, newY, newZ}
     */
    public float[] update(PhysicsComponent physics, float x, float y, float z,
                          float width, float height, float depth, Chunk chunk) {

        // Apply gravity if not on ground
        if (!physics.isOnGround()) {
            physics.addVelocity(0, GameConfig.GRAVITY, 0);
        }

        // Get current velocities
        float vx = physics.getVelocityX();
        float vy = physics.getVelocityY();
        float vz = physics.getVelocityZ();

        // Move with collision detection (axis by axis)
        float newX = collisionDetector.moveAxis(x, y, z, vx, width, height, depth,
                CollisionDetector.Axis.X, chunk);
        float newY = collisionDetector.moveAxis(newX, y, z, vy, width, height, depth,
                CollisionDetector.Axis.Y, chunk);
        float newZ = collisionDetector.moveAxis(newX, newY, z, vz, width, height, depth,
                CollisionDetector.Axis.Z, chunk);

        // Check ground collision and snap if needed
        if (vy <= 0) {
            float[] groundSnap = collisionDetector.snapToGround(newY, vy, newX, newZ, width, depth, chunk);
            newY = groundSnap[0];
            physics.setVelocityY(groundSnap[1]);

            // Update ground state
            boolean onGround = collisionDetector.isOnGround(newX, newY, newZ, width, depth, chunk);
            physics.setOnGround(onGround);
        } else {
            physics.setOnGround(false);
        }

        // Apply friction to horizontal velocity
        physics.setVelocityX(vx * GameConfig.PLAYER_FRICTION);
        physics.setVelocityZ(vz * GameConfig.PLAYER_FRICTION);

        return new float[]{newX, newY, newZ};
    }

    /**
     * Apply a jump impulse to an entity.
     *
     * @param physics Physics component
     */
    public void jump(PhysicsComponent physics) {
        if (physics.isOnGround()) {
            physics.setVelocityY(GameConfig.JUMP_VELOCITY);
            physics.setOnGround(false);
        }
    }

    /**
     * Get the collision detector.
     *
     * @return Collision detector instance
     */
    public CollisionDetector getCollisionDetector() {
        return collisionDetector;
    }
}
