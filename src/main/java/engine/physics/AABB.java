package engine.physics;

import org.joml.Vector3f;

/**
 * Axis-Aligned Bounding Box for collision detection.
 * Represents a rectangular box aligned with world axes (no rotation).
 */
public class AABB {
    private float minX, minY, minZ;
    private float maxX, maxY, maxZ;

    /**
     * Create an AABB with specified bounds.
     *
     * @param minX Minimum X coordinate
     * @param minY Minimum Y coordinate
     * @param minZ Minimum Z coordinate
     * @param maxX Maximum X coordinate
     * @param maxY Maximum Y coordinate
     * @param maxZ Maximum Z coordinate
     */
    public AABB(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    /**
     * Create an AABB from center position and size.
     *
     * @param centerX Center X position
     * @param centerY Center Y position (feet position)
     * @param centerZ Center Z position
     * @param width   Width (X-axis)
     * @param height  Height (Y-axis)
     * @param depth   Depth (Z-axis)
     * @return New AABB
     */
    public static AABB fromCenterAndSize(float centerX, float centerY, float centerZ,
                                         float width, float height, float depth) {
        return new AABB(
                centerX - width / 2,
                centerY,
                centerZ - depth / 2,
                centerX + width / 2,
                centerY + height,
                centerZ + depth / 2
        );
    }

    /**
     * Check if this AABB intersects with another AABB.
     *
     * @param other Other AABB to check
     * @return True if boxes intersect
     */
    public boolean intersects(AABB other) {
        return this.maxX > other.minX && this.minX < other.maxX &&
               this.maxY > other.minY && this.minY < other.maxY &&
               this.maxZ > other.minZ && this.minZ < other.maxZ;
    }

    /**
     * Create a new AABB offset by a vector.
     *
     * @param dx Offset in X direction
     * @param dy Offset in Y direction
     * @param dz Offset in Z direction
     * @return New offset AABB
     */
    public AABB offset(float dx, float dy, float dz) {
        return new AABB(
                minX + dx, minY + dy, minZ + dz,
                maxX + dx, maxY + dy, maxZ + dz
        );
    }

    /**
     * Expand this AABB by a margin in all directions.
     *
     * @param margin Expansion amount
     * @return New expanded AABB
     */
    public AABB expand(float margin) {
        return new AABB(
                minX - margin, minY - margin, minZ - margin,
                maxX + margin, maxY + margin, maxZ + margin
        );
    }

    // Getters
    public float getMinX() { return minX; }
    public float getMinY() { return minY; }
    public float getMinZ() { return minZ; }
    public float getMaxX() { return maxX; }
    public float getMaxY() { return maxY; }
    public float getMaxZ() { return maxZ; }

    @Override
    public String toString() {
        return String.format("AABB[%.2f,%.2f,%.2f -> %.2f,%.2f,%.2f]",
                minX, minY, minZ, maxX, maxY, maxZ);
    }
}
