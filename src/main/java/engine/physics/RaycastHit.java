package engine.physics;

import engine.world.Block;

/**
 * Immutable result of a 3D DDA voxel raycast against the world.
 *
 * @param blockX   World X coordinate of the targeted solid block
 * @param blockY   World Y coordinate of the targeted solid block
 * @param blockZ   World Z coordinate of the targeted solid block
 * @param normalX  Hit face normal X (-1, 0, or +1)
 * @param normalY  Hit face normal Y (-1, 0, or +1)
 * @param normalZ  Hit face normal Z (-1, 0, or +1)
 * @param block    Targeted block definition
 * @param distance Ray distance in blocks from camera eye to hit face
 */
public record RaycastHit(
        int blockX,
        int blockY,
        int blockZ,
        int normalX,
        int normalY,
        int normalZ,
        Block block,
        float distance
) {
    /** World X coordinate of the adjacent block cell on the hit face (for block placement). */
    public int adjacentX() {
        return blockX + normalX;
    }

    /** World Y coordinate of the adjacent block cell on the hit face (for block placement). */
    public int adjacentY() {
        return blockY + normalY;
    }

    /** World Z coordinate of the adjacent block cell on the hit face (for block placement). */
    public int adjacentZ() {
        return blockZ + normalZ;
    }
}
