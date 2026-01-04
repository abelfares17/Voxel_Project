package engine.physics;

import engine.world.Chunk;

/**
 * Collision detection system for AABB vs voxel world.
 * Extracted from Player class for reusability across all entities.
 */
public class CollisionDetector {

    public enum Axis { X, Y, Z }

    /**
     * Check if an AABB collides with solid blocks in the chunk.
     *
     * @param aabb  Bounding box to check
     * @param chunk Chunk to check against
     * @return True if collision detected
     */
    public boolean checkCollision(AABB aabb, Chunk chunk) {
        int minX = (int) Math.floor(aabb.getMinX());
        int maxX = (int) Math.floor(aabb.getMaxX() - 1e-6);
        int minY = (int) Math.floor(aabb.getMinY());
        int maxY = (int) Math.floor(aabb.getMaxY() - 1e-6);
        int minZ = (int) Math.floor(aabb.getMinZ());
        int maxZ = (int) Math.floor(aabb.getMaxZ() - 1e-6);

        for (int xi = minX; xi <= maxX; xi++) {
            for (int yi = minY; yi <= maxY; yi++) {
                for (int zi = minZ; zi <= maxZ; zi++) {
                    if (!chunk.inBounds(xi, yi, zi)) continue;
                    if (chunk.getBlockId(xi, yi, zi) != 0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Move an entity along a single axis with collision detection.
     * Splits movement into small steps to prevent tunneling.
     *
     * @param pos    Current position on this axis
     * @param delta  Desired movement delta
     * @param width  Entity width (X-axis)
     * @param height Entity height (Y-axis)
     * @param depth  Entity depth (Z-axis)
     * @param axis   Which axis to move on
     * @param chunk  Chunk to check collisions against
     * @return New position after collision-safe movement
     */
    public float moveAxis(float posX, float posY, float posZ, float delta,
                          float width, float height, float depth,
                          Axis axis, Chunk chunk) {
        float pos = (axis == Axis.X) ? posX : (axis == Axis.Y) ? posY : posZ;
        float step = (delta > 0) ? 1 : -1;

        while (Math.abs(delta) > 0.0001f) {
            float move = Math.min(Math.abs(delta), 0.05f) * step;
            delta -= move;
            float next = pos + move;

            // Create AABB at new position
            float testX = (axis == Axis.X) ? next : posX;
            float testY = (axis == Axis.Y) ? next : posY;
            float testZ = (axis == Axis.Z) ? next : posZ;

            AABB testBox = AABB.fromCenterAndSize(testX, testY, testZ, width, height, depth);

            if (!checkCollision(testBox, chunk)) {
                pos = next;
            } else {
                break; // Hit something, stop moving
            }
        }
        return pos;
    }

    /**
     * Check if an entity is standing on solid ground.
     *
     * @param x      Entity X position
     * @param y      Entity Y position (feet)
     * @param z      Entity Z position
     * @param width  Entity width
     * @param depth  Entity depth
     * @param chunk  Chunk to check
     * @return True if standing on solid block
     */
    public boolean isOnGround(float x, float y, float z, float width, float depth, Chunk chunk) {
        // Check slightly below feet position
        float feetY = y - 0.01f;
        int blockY = (int) Math.floor(feetY);

        int minX = (int) Math.floor(x - width / 2);
        int maxX = (int) Math.floor(x + width / 2);
        int minZ = (int) Math.floor(z - depth / 2);
        int maxZ = (int) Math.floor(z + depth / 2);

        for (int xi = minX; xi <= maxX; xi++) {
            for (int zi = minZ; zi <= maxZ; zi++) {
                if (!chunk.inBounds(xi, blockY, zi)) continue;
                if (chunk.getBlockId(xi, blockY, zi) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get the ground level (Y coordinate) at a position.
     *
     * @param x     X position
     * @param y     Starting Y position
     * @param z     Z position
     * @param chunk Chunk to check
     * @return Y coordinate of ground surface
     */
    public int getGroundY(float x, float y, float z, Chunk chunk) {
        int startY = (int) Math.floor(y);
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);

        for (int i = startY; i >= 0; i--) {
            if (chunk.inBounds(blockX, i, blockZ)) {
                if (chunk.getBlockId(blockX, i, blockZ) != 0) {
                    return i + 1; // Return top of the solid block
                }
            }
        }
        return 0;
    }

    /**
     * Snap entity to ground if falling and colliding.
     *
     * @param y       Current Y position
     * @param vy      Current Y velocity
     * @param chunk   Chunk to check
     * @param x       X position
     * @param z       Z position
     * @param width   Entity width
     * @param depth   Entity depth
     * @return Array [newY, newVY]
     */
    public float[] snapToGround(float y, float vy, float x, float z, float width, float depth, Chunk chunk) {
        if (vy > 0) {
            return new float[]{y, vy}; // Not falling
        }

        int feetY = (int) Math.floor(y - 0.01f);
        int cx = (int) Math.floor(x);
        int cz = (int) Math.floor(z);

        if (chunk.inBounds(cx, feetY, cz) && chunk.getBlockId(cx, feetY, cz) != 0) {
            // Snap to top of block
            float newY = feetY + 1.0f;
            return new float[]{newY, 0f};
        }

        return new float[]{y, vy};
    }
}
