package engine.physics;

import engine.world.Block;
import engine.world.Chunk;
import engine.world.World;

/**
 * Collision detection system for AABB vs voxel world (cross-chunk aware).
 */
public class CollisionDetector {

    public enum Axis { X, Y, Z }

    private static final float EPSILON = 1e-4f;

    /**
     * Check if a world-space voxel coordinate contains a solid block across any loaded chunk.
     */
    public boolean isSolidAt(World world, int wx, int wy, int wz) {
        if (wy < 0) return true; // Bedrock floor guard below y=0
        if (wy >= Chunk.HEIGHT) return false;
        if (world == null) return false;
        Block b = world.getBlockAt(wx, wy, wz);
        return b != null && b.getId() != 0 && b.isSolid();
    }

    /**
     * Check if an AABB collides with any solid block in the world (cross-chunk).
     */
    public boolean checkCollision(AABB aabb, World world) {
        int minX = (int) Math.floor(aabb.getMinX() + EPSILON);
        int maxX = (int) Math.floor(aabb.getMaxX() - EPSILON);
        int minY = (int) Math.floor(aabb.getMinY() + EPSILON);
        int maxY = (int) Math.floor(aabb.getMaxY() - EPSILON);
        int minZ = (int) Math.floor(aabb.getMinZ() + EPSILON);
        int maxZ = (int) Math.floor(aabb.getMaxZ() - EPSILON);

        for (int xi = minX; xi <= maxX; xi++) {
            for (int yi = minY; yi <= maxY; yi++) {
                for (int zi = minZ; zi <= maxZ; zi++) {
                    if (isSolidAt(world, xi, yi, zi)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Legacy single-chunk collision check.
     */
    public boolean checkCollision(AABB aabb, Chunk chunk) {
        if (chunk == null) return false;
        int minX = (int) Math.floor(aabb.getMinX() + EPSILON);
        int maxX = (int) Math.floor(aabb.getMaxX() - EPSILON);
        int minY = (int) Math.floor(aabb.getMinY() + EPSILON);
        int maxY = (int) Math.floor(aabb.getMaxY() - EPSILON);
        int minZ = (int) Math.floor(aabb.getMinZ() + EPSILON);
        int maxZ = (int) Math.floor(aabb.getMaxZ() - EPSILON);

        int baseX = chunk.getChunkX() * Chunk.WIDTH;
        int baseZ = chunk.getChunkZ() * Chunk.DEPTH;

        for (int xi = minX; xi <= maxX; xi++) {
            for (int yi = minY; yi <= maxY; yi++) {
                for (int zi = minZ; zi <= maxZ; zi++) {
                    int lx = xi - baseX;
                    int lz = zi - baseZ;
                    if (!chunk.inBounds(lx, yi, lz)) continue;
                    if (chunk.getBlockId(lx, yi, lz) != 0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Move an entity along a single axis with cross-chunk collision detection.
     * Splits movement into small steps to prevent tunneling.
     */
    public float moveAxis(float posX, float posY, float posZ, float delta,
                          float width, float height, float depth,
                          Axis axis, World world) {
        float pos = (axis == Axis.X) ? posX : (axis == Axis.Y) ? posY : posZ;
        if (Math.abs(delta) < 1e-6f) return pos;
        float step = (delta > 0) ? 1f : -1f;
        float remaining = delta;

        while (Math.abs(remaining) > 0.0001f) {
            float move = Math.min(Math.abs(remaining), 0.025f) * step;
            remaining -= move;
            float next = pos + move;

            float testX = (axis == Axis.X) ? next : posX;
            float testY = (axis == Axis.Y) ? next : posY;
            float testZ = (axis == Axis.Z) ? next : posZ;

            AABB testBox = AABB.fromCenterAndSize(testX, testY, testZ, width, height, depth);

            if (!checkCollision(testBox, world)) {
                pos = next;
            } else {
                if (axis == Axis.Y && step < 0f) {
                    // Snap cleanly to the top surface of the supporting block
                    pos = (float) Math.floor(pos + 0.03f);
                }
                break;
            }
        }
        return pos;
    }

    /**
     * Legacy single-chunk axis movement.
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

            float testX = (axis == Axis.X) ? next : posX;
            float testY = (axis == Axis.Y) ? next : posY;
            float testZ = (axis == Axis.Z) ? next : posZ;

            AABB testBox = AABB.fromCenterAndSize(testX, testY, testZ, width, height, depth);

            if (!checkCollision(testBox, chunk)) {
                pos = next;
            } else {
                break;
            }
        }
        return pos;
    }

    /**
     * Check if an entity is standing on solid ground across chunk boundaries.
     */
    public boolean isOnGround(float x, float y, float z, float width, float depth, World world) {
        AABB feetCheck = new AABB(
                x - width * 0.5f + EPSILON,
                y - 0.04f,
                z - depth * 0.5f + EPSILON,
                x + width * 0.5f - EPSILON,
                y - 0.001f,
                z + depth * 0.5f - EPSILON
        );
        return checkCollision(feetCheck, world);
    }

    public boolean isOnGround(float x, float y, float z, float width, float depth, Chunk chunk) {
        float feetY = y - 0.02f;
        int blockY = (int) Math.floor(feetY);
        int baseX = chunk.getChunkX() * Chunk.WIDTH;
        int baseZ = chunk.getChunkZ() * Chunk.DEPTH;

        int minX = (int) Math.floor(x - width / 2 + EPSILON);
        int maxX = (int) Math.floor(x + width / 2 - EPSILON);
        int minZ = (int) Math.floor(z - depth / 2 + EPSILON);
        int maxZ = (int) Math.floor(z + depth / 2 - EPSILON);

        for (int xi = minX; xi <= maxX; xi++) {
            for (int zi = minZ; zi <= maxZ; zi++) {
                int lx = xi - baseX;
                int lz = zi - baseZ;
                if (!chunk.inBounds(lx, blockY, lz)) continue;
                if (chunk.getBlockId(lx, blockY, lz) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    public int getGroundY(float x, float y, float z, World world) {
        int startY = Math.min(Chunk.HEIGHT - 1, (int) Math.floor(y));
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);

        for (int i = startY; i >= 0; i--) {
            if (isSolidAt(world, blockX, i, blockZ)) {
                return i + 1;
            }
        }
        return 0;
    }

    public int getGroundY(float x, float y, float z, Chunk chunk) {
        int startY = (int) Math.floor(y);
        int blockX = (int) Math.floor(x) - chunk.getChunkX() * Chunk.WIDTH;
        int blockZ = (int) Math.floor(z) - chunk.getChunkZ() * Chunk.DEPTH;

        for (int i = startY; i >= 0; i--) {
            if (chunk.inBounds(blockX, i, blockZ)) {
                if (chunk.getBlockId(blockX, i, blockZ) != 0) {
                    return i + 1;
                }
            }
        }
        return 0;
    }

    public float[] snapToGround(float y, float vy, float x, float z, float width, float depth, World world) {
        if (vy > 0) {
            return new float[]{y, vy};
        }
        if (isOnGround(x, y, z, width, depth, world)) {
            float snappedY = (float) Math.round(y * 1000f) / 1000f;
            if (Math.abs(snappedY - Math.round(snappedY)) < 0.04f) {
                snappedY = Math.round(snappedY);
            }
            return new float[]{snappedY, 0f};
        }
        return new float[]{y, vy};
    }

    public float[] snapToGround(float y, float vy, float x, float z, float width, float depth, Chunk chunk) {
        if (vy > 0) {
            return new float[]{y, vy};
        }
        if (isOnGround(x, y, z, width, depth, chunk)) {
            int feetY = (int) Math.floor(y - 0.02f);
            return new float[]{feetY + 1.0f, 0f};
        }
        return new float[]{y, vy};
    }
}
