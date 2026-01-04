package engine.entity;

import engine.entity.component.PhysicsComponent;
import engine.world.Chunk;

/**
 * Base class for all game entities.
 * Uses composition with Transform and optional PhysicsComponent.
 */
public abstract class Entity {
    protected final Transform transform;
    protected PhysicsComponent physics;
    protected float width, height, depth;

    /**
     * Create an entity at a position.
     *
     * @param x      X position
     * @param y      Y position
     * @param z      Z position
     * @param width  Bounding box width
     * @param height Bounding box height
     * @param depth  Bounding box depth
     */
    public Entity(float x, float y, float z, float width, float height, float depth) {
        this.transform = new Transform(x, y, z);
        this.width = width;
        this.height = height;
        this.depth = depth;
    }

    /**
     * Update entity logic.
     *
     * @param chunk Current chunk (for collision detection)
     */
    public abstract void update(Chunk chunk);

    // Transform accessors
    public Transform getTransform() { return transform; }

    public float getX() { return transform.getX(); }
    public float getY() { return transform.getY(); }
    public float getZ() { return transform.getZ(); }

    public void setPosition(float x, float y, float z) {
        transform.setPosition(x, y, z);
    }

    public float getYaw() { return transform.getYaw(); }
    public float getPitch() { return transform.getPitch(); }

    public void setYaw(float yaw) { transform.setYaw(yaw); }
    public void setPitch(float pitch) { transform.setPitch(pitch); }

    // Bounding box accessors
    public float getWidth() { return width; }
    public float getHeight() { return height; }
    public float getDepth() { return depth; }

    // Physics accessors
    public PhysicsComponent getPhysics() { return physics; }

    public boolean hasPhysics() { return physics != null; }

    /**
     * Enable physics for this entity.
     *
     * @param affectedByGravity Whether this entity is affected by gravity
     */
    protected void enablePhysics(boolean affectedByGravity) {
        if (physics == null) {
            physics = new PhysicsComponent(affectedByGravity);
        }
    }

    /**
     * Get chunk coordinates from entity position.
     *
     * @return Array [chunkX, chunkZ]
     */
    public int[] getChunkCoords(int chunkWidth, int chunkDepth) {
        int chunkX = (int) Math.floor(transform.getX() / chunkWidth);
        int chunkZ = (int) Math.floor(transform.getZ() / chunkDepth);
        return new int[]{chunkX, chunkZ};
    }
}
