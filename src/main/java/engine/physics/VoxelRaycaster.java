package engine.physics;

import engine.world.Block;
import engine.world.Chunk;
import engine.world.World;
import org.joml.Vector3f;

/**
 * Fast and exact 3D voxel raycaster using Amanatides & Woo's DDA grid traversal algorithm.
 */
public final class VoxelRaycaster {

    private VoxelRaycaster() {}

    /**
     * Cast a ray from camera eye position along look direction and return the first solid block hit.
     *
     * @param world       Voxel world to query
     * @param origin      Ray origin in world space (camera eye position)
     * @param direction   Ray direction vector (need not be normalized)
     * @param maxDistance Maximum reach distance in blocks (e.g. 5.5f)
     * @return RaycastHit if a solid block is hit within maxDistance, or null otherwise
     */
    public static RaycastHit raycast(World world, Vector3f origin, Vector3f direction, float maxDistance) {
        if (world == null || origin == null || direction == null) return null;

        float len = direction.length();
        if (len < 1e-6f) return null;

        float dx = direction.x / len;
        float dy = direction.y / len;
        float dz = direction.z / len;

        float ox = origin.x;
        float oy = origin.y;
        float oz = origin.z;

        int x = (int) Math.floor(ox);
        int y = (int) Math.floor(oy);
        int z = (int) Math.floor(oz);

        int stepX = dx > 0f ? 1 : (dx < 0f ? -1 : 0);
        int stepY = dy > 0f ? 1 : (dy < 0f ? -1 : 0);
        int stepZ = dz > 0f ? 1 : (dz < 0f ? -1 : 0);

        float tDeltaX = (stepX != 0) ? Math.abs(1.0f / dx) : Float.POSITIVE_INFINITY;
        float tDeltaY = (stepY != 0) ? Math.abs(1.0f / dy) : Float.POSITIVE_INFINITY;
        float tDeltaZ = (stepZ != 0) ? Math.abs(1.0f / dz) : Float.POSITIVE_INFINITY;

        float tMaxX = (stepX > 0) ? ((x + 1.0f - ox) * tDeltaX)
                    : (stepX < 0 ? ((ox - x) * tDeltaX) : Float.POSITIVE_INFINITY);
        float tMaxY = (stepY > 0) ? ((y + 1.0f - oy) * tDeltaY)
                    : (stepY < 0 ? ((oy - y) * tDeltaY) : Float.POSITIVE_INFINITY);
        float tMaxZ = (stepZ > 0) ? ((z + 1.0f - oz) * tDeltaZ)
                    : (stepZ < 0 ? ((oz - z) * tDeltaZ) : Float.POSITIVE_INFINITY);

        int nx = 0, ny = 0, nz = 0;
        float dist = 0.0f;

        for (int step = 0; step < 128 && dist <= maxDistance; step++) {
            if (step > 0 && y >= 0 && y < Chunk.HEIGHT) {
                Block block = world.getBlockAt(x, y, z);
                if (block != null && block.getId() != 0 && block.isSolid()) {
                    return new RaycastHit(x, y, z, nx, ny, nz, block, dist);
                }
            }

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    dist = tMaxX;
                    tMaxX += tDeltaX;
                    x += stepX;
                    nx = -stepX;
                    ny = 0;
                    nz = 0;
                } else {
                    dist = tMaxZ;
                    tMaxZ += tDeltaZ;
                    z += stepZ;
                    nx = 0;
                    ny = 0;
                    nz = -stepZ;
                }
            } else {
                if (tMaxY < tMaxZ) {
                    dist = tMaxY;
                    tMaxY += tDeltaY;
                    y += stepY;
                    nx = 0;
                    ny = -stepY;
                    nz = 0;
                } else {
                    dist = tMaxZ;
                    tMaxZ += tDeltaZ;
                    z += stepZ;
                    nx = 0;
                    ny = 0;
                    nz = -stepZ;
                }
            }
        }

        return null;
    }

    /**
     * Compute unit look direction vector from player yaw and pitch angles (in degrees).
     */
    public static Vector3f getLookDirection(float yawDeg, float pitchDeg) {
        float yawRad = (float) Math.toRadians(yawDeg);
        float pitchRad = (float) Math.toRadians(pitchDeg);
        float cosPitch = (float) Math.cos(pitchRad);
        float dx = (float) Math.sin(yawRad) * cosPitch;
        float dy = (float) -Math.sin(pitchRad);
        float dz = (float) -Math.cos(yawRad) * cosPitch;
        return new Vector3f(dx, dy, dz).normalize();
    }
}
