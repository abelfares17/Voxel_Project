package engine.entity;

import org.joml.Vector3f;

import engine.core.config.GameConfig;
import engine.input.Input;
import engine.physics.AABB;
import engine.physics.PhysicsEngine;
import engine.world.Chunk;
import engine.world.World;

/**
 * Player entity with cross-chunk AABB physics, spectator flight mode, hotbar state, and camera control.
 */
public class Player extends Entity {

    public static final float PHYSICAL_HEIGHT = 1.8f;
    public static final float STANDING_EYE_HEIGHT = 1.62f;
    public static final float SNEAKING_EYE_HEIGHT = 1.32f;

    /** Walk speed in blocks/sec (Physics mode) */
    private static final float WALK_SPEED = 4.6f;
    /** Sprint multiplier (Ctrl) */
    private static final float SPRINT_MULTIPLIER = 1.38f;
    /** Sneak multiplier (Shift) */
    private static final float SNEAK_MULTIPLIER = 0.36f;
    /** Spectator flight speed in blocks/sec */
    private static final float SPECTATOR_SPEED = 14.0f;

    /** 9-slot building block palette in the Hotbar */
    private static final short[] HOTBAR_BLOCKS = {
        1,  // Slot 1: Grass (Herbe)
        2,  // Slot 2: Dirt (Terre)
        3,  // Slot 3: Stone (Roche)
        9,  // Slot 4: Cobblestone (Pierre taillée)
        5,  // Slot 5: Oak Log (Tronc de chêne)
        6,  // Slot 6: Oak Planks (Planches de chêne)
        7,  // Slot 7: Oak Leaves (Feuillage)
        10, // Slot 8: Sand (Sable)
        11  // Slot 9: Bricks (Briques)
    };

    private boolean spectatorMode = GameConfig.TEST_HARNESS_ENABLED || Boolean.getBoolean("voxel.spectator");
    private boolean sneaking = false;
    private boolean sprinting = false;
    private float currentEyeHeight = STANDING_EYE_HEIGHT;
    private int selectedHotbarSlot = 0;
    private final PhysicsEngine physicsEngine;

    /**
     * Create a new player.
     */
    public Player(float x, float y, float z, PhysicsEngine physicsEngine) {
        super(x, y, z, GameConfig.PLAYER_WIDTH, PHYSICAL_HEIGHT, GameConfig.PLAYER_WIDTH);
        this.physicsEngine = physicsEngine;
        enablePhysics(true);
    }

    /**
     * Update player against the full multi-chunk World with frame-rate independent deltaTime.
     */
    public void update(World world, Input input, float deltaTime) {
        float dt = Math.min(Math.max(deltaTime, 0.0005f), 0.05f);

        // Mouse look (yaw/pitch)
        transform.setYaw(transform.getYaw() + input.getMouseDeltaX() * 0.1f);
        float newPitch = transform.getPitch() + input.getMouseDeltaY() * 0.1f;
        transform.setPitch(Math.max(-89f, Math.min(89f, newPitch)));

        if (spectatorMode) {
            sneaking = false;
            sprinting = false;
            currentEyeHeight = GameConfig.PLAYER_HEIGHT;
            updateSpectatorMode(input, dt);
            return;
        }

        // Smooth eye-height interpolation for sneaking (Shift)
        sneaking = input.crouch && physics.isOnGround();
        sprinting = input.sprint && input.forward && !sneaking;
        float targetEye = sneaking ? SNEAKING_EYE_HEIGHT : STANDING_EYE_HEIGHT;
        currentEyeHeight += (targetEye - currentEyeHeight) * Math.min(1.0f, dt * 16.0f);

        handlePhysicsInput(input);

        // Jump handling (auto-bunnyhop while holding Space on ground, like Minecraft)
        if (input.jump && physics.isOnGround()) {
            physicsEngine.jump(physics);
        }

        // Apply cross-chunk physics and update position
        float[] newPos = physicsEngine.update(
                physics,
                transform.getX(), transform.getY(), transform.getZ(),
                width, PHYSICAL_HEIGHT, depth,
                world, dt, sneaking
        );
        transform.setPosition(newPos[0], newPos[1], newPos[2]);
    }

    /**
     * Legacy single-chunk update.
     */
    public void update(Chunk chunk, Input input) {
        handlePhysicsInput(input);

        transform.setYaw(transform.getYaw() + input.getMouseDeltaX() * 0.1f);
        float newPitch = transform.getPitch() + input.getMouseDeltaY() * 0.1f;
        transform.setPitch(Math.max(-89f, Math.min(89f, newPitch)));

        if (spectatorMode) {
            updateSpectatorMode(input, 1f / 60f);
            return;
        }

        if (input.jump && physics.isOnGround()) {
            physicsEngine.jump(physics);
        }

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
        // Player requires input
    }

    private void handlePhysicsInput(Input input) {
        float moveX = 0f;
        float moveZ = 0f;

        float rad = (float) Math.toRadians(transform.getYaw());
        float dirX = (float) Math.sin(rad);
        float dirZ = (float) -Math.cos(rad);
        float strafeX = (float) Math.sin(rad - Math.PI / 2);
        float strafeZ = (float) -Math.cos(rad - Math.PI / 2);

        if (input.forward) {
            moveX += dirX;
            moveZ += dirZ;
        }
        if (input.backward) {
            moveX -= dirX;
            moveZ -= dirZ;
        }
        if (input.left) {
            moveX += strafeX;
            moveZ += strafeZ;
        }
        if (input.right) {
            moveX -= strafeX;
            moveZ -= strafeZ;
        }

        float len = (float) Math.sqrt(moveX * moveX + moveZ * moveZ);
        if (len > 0.001f) {
            float speed = WALK_SPEED;
            if (sneaking) speed *= SNEAK_MULTIPLIER;
            else if (sprinting) speed *= SPRINT_MULTIPLIER;
            physics.setVelocityX((moveX / len) * speed);
            physics.setVelocityZ((moveZ / len) * speed);
        } else {
            physics.setVelocityX(0f);
            physics.setVelocityZ(0f);
        }

        if (input.coords) {
            System.out.println("Coords : (x,y,z) = " + transform.getX() + ", " +
                    transform.getY() + ", " + transform.getZ());
        }
    }

    private void updateSpectatorMode(Input input, float dt) {
        float speed = SPECTATOR_SPEED * (input.sprint ? 2.2f : 1.0f);
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

        float len = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (len > 0.001f) {
            vx = vx / len * speed * dt;
            vy = vy / len * speed * dt;
            vz = vz / len * speed * dt;
        }

        transform.setPosition(
                transform.getX() + vx,
                transform.getY() + vy,
                transform.getZ() + vz
        );
    }

    /**
     * Ensure the player's physical AABB is not embedded inside solid blocks
     * (e.g. when switching from Spectator noclip to Physics mode, or loading an old save).
     */
    public void ensureNotStuckInBlocks(World world) {
        if (world == null) return;
        physics.resetVelocity();
        if (transform.getY() < 1.0f) {
            int surfY = physicsEngine.getCollisionDetector().getGroundY(transform.getX(), Chunk.HEIGHT - 2, transform.getZ(), world);
            transform.setPosition(transform.getX(), Math.max(1.0f, surfY + 0.01f), transform.getZ());
            return;
        }
        int safety = 0;
        while (safety < Chunk.HEIGHT && physicsEngine.getCollisionDetector().checkCollision(getAABB(), world)) {
            transform.setPosition(transform.getX(), (float) Math.floor(transform.getY()) + 1.001f, transform.getZ());
            safety++;
        }
    }

    /**
     * Get the player's physical bounding box in world coordinates.
     */
    public AABB getAABB() {
        return AABB.fromCenterAndSize(transform.getX(), transform.getY(), transform.getZ(),
                width, PHYSICAL_HEIGHT, depth);
    }

    /**
     * Get current camera eye height offset above feet Y.
     */
    public float getEyeHeight() {
        if (GameConfig.TEST_HARNESS_ENABLED || spectatorMode) {
            return GameConfig.PLAYER_HEIGHT;
        }
        return currentEyeHeight;
    }

    public Vector3f getPosition() {
        return new Vector3f(transform.getX(), transform.getY(), transform.getZ());
    }

    public void setSpectatorMode(boolean enabled) {
        this.spectatorMode = enabled;
        if (physics != null) {
            physics.resetVelocity();
        }
    }

    public boolean isSpectatorMode() { return spectatorMode; }
    public boolean isSneaking() { return sneaking; }
    public boolean isSprinting() { return sprinting; }

    // Hotbar accessors
    public int getSelectedHotbarSlot() { return selectedHotbarSlot; }
    public void setSelectedHotbarSlot(int slot) {
        this.selectedHotbarSlot = Math.floorMod(slot, HOTBAR_BLOCKS.length);
    }
    public void scrollHotbar(int delta) {
        this.selectedHotbarSlot = Math.floorMod(this.selectedHotbarSlot + delta, HOTBAR_BLOCKS.length);
    }
    public short getSelectedBlockId() {
        return HOTBAR_BLOCKS[selectedHotbarSlot];
    }
    public short[] getHotbarBlocks() {
        return HOTBAR_BLOCKS;
    }
}
