package engine.entity;

import org.joml.Vector3f;

import engine.core.config.GameConfig;
import engine.input.Input;
import engine.physics.PhysicsEngine;
import engine.world.Chunk;

/**
 * Player entity with input handling and camera control.
 * Refactored from engine.player.Player to use Entity/Component architecture.
 */
public class Player extends Entity {

    private boolean spectatorMode = true;
    private boolean jumpPressedLastFrame = false;
    private final PhysicsEngine physicsEngine;

    /**
     * Create a new player.
     *
     * @param x              Initial X position
     * @param y              Initial Y position
     * @param z              Initial Z position
     * @param physicsEngine  Physics engine for movement
     */
    public Player(float x, float y, float z, PhysicsEngine physicsEngine) {
        super(x, y, z, GameConfig.PLAYER_WIDTH, GameConfig.PLAYER_HEIGHT, GameConfig.PLAYER_WIDTH);
        this.physicsEngine = physicsEngine;
        enablePhysics(true);
    }

    /**
     * Update player with input handling.
     *
     * @param chunk Current chunk
     * @param input Input handler
     */
    public void update(Chunk chunk, Input input) {
        handleInput(input);

        // Mouse look (yaw/pitch)
        transform.setYaw(transform.getYaw() + input.getMouseDeltaX() * 0.1f);
        float newPitch = transform.getPitch() + input.getMouseDeltaY() * 0.1f;
        transform.setPitch(Math.max(-89f, Math.min(89f, newPitch)));

        if (spectatorMode) {
            updateSpectatorMode(input);
            return;
        }

        // Jump handling (single press)
        boolean jumpPressed = input.jump;
        if (jumpPressed && !jumpPressedLastFrame) {
            physicsEngine.jump(physics);
        }
        jumpPressedLastFrame = jumpPressed;

        // Apply physics and update position
        float[] newPos = physicsEngine.update(
                physics,
                transform.getX(), transform.getY(), transform.getZ(),
                width, height, depth,
                chunk
        );
        transform.setPosition(newPos[0], newPos[1], newPos[2]);
    }

    @Override
    public void update(Chunk chunk) {
        // Not used - Player requires input, use update(chunk, input) instead
    }

    /**
     * Handle player input for movement.
     *
     * @param input Input handler
     */
    private void handleInput(Input input) {
        float speed = GameConfig.PLAYER_SPEED;

        float rad = (float) Math.toRadians(transform.getYaw());
        float dirX = (float) Math.sin(rad);
        float dirZ = (float) -Math.cos(rad);

        float strafeX = (float) Math.sin(rad - Math.PI / 2);
        float strafeZ = (float) -Math.cos(rad - Math.PI / 2);

        if (input.forward) {
            physics.addVelocity(dirX * speed, 0, dirZ * speed);
        }
        if (input.backward) {
            physics.addVelocity(-dirX * speed, 0, -dirZ * speed);
        }
        if (input.left) {
            physics.addVelocity(strafeX * speed, 0, strafeZ * speed);
        }
        if (input.right) {
            physics.addVelocity(-strafeX * speed, 0, -strafeZ * speed);
        }

        // Debug coords
        if (input.coords) {
            System.out.println("Coords : (x,y,z) = " + transform.getX() + ", " +
                    transform.getY() + ", " + transform.getZ());
            System.out.println("Coords_int = " + (int) Math.floor(transform.getX()) + ", " +
                    (int) Math.floor(transform.getY()) + ", " + (int) Math.floor(transform.getZ()));
        }
    }

    /**
     * Update player in spectator mode (no collision, free fly).
     *
     * @param input Input handler
     */
    private void updateSpectatorMode(Input input) {
        float speed = GameConfig.PLAYER_SPEED;
        float vx = 0, vy = 0, vz = 0;

        float rad = (float) Math.toRadians(transform.getYaw());
        float dirX = (float) Math.sin(rad);
        float dirZ = (float) -Math.cos(rad);
        float strafeX = (float) Math.sin(rad - Math.PI / 2);
        float strafeZ = (float) -Math.cos(rad - Math.PI / 2);

        if (input.forward) {
            vx += dirX;
            vz += dirZ;
        }
        if (input.backward) {
            vx -= dirX;
            vz -= dirZ;
        }
        if (input.left) {
            vx += strafeX;
            vz += strafeZ;
        }
        if (input.right) {
            vx -= strafeX;
            vz -= strafeZ;
        }
        if (input.jump) vy += 1.0f;
        if (input.crouch) vy -= 1.0f;

        // Normalize and apply speed
        float len = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (len > 0.001f) {
            vx = vx / len * speed;
            vy = vy / len * speed;
            vz = vz / len * speed;
        }

        // Apply movement directly (no physics in spectator mode)
        transform.setPosition(
                transform.getX() + vx,
                transform.getY() + vy,
                transform.getZ() + vz
        );
    }

    public Vector3f getPosition() {
        return new Vector3f(transform.getX(), transform.getY(), transform.getZ());
    }

    // Spectator mode
    public void setSpectatorMode(boolean enabled) { this.spectatorMode = enabled; }
    public boolean isSpectatorMode() { return spectatorMode; }
}
