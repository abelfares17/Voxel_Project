package engine.render;
import engine.world.*;
import org.joml.Vector3f;

public class ShadowCalculator {

    public static boolean[][][] computeShadowMask(Chunk chunk, World world, Vector3f sunDir) {
        int W = Chunk.WIDTH;
        int H = Chunk.HEIGHT;
        int D = Chunk.DEPTH;
        boolean[][][] shadowMask = new boolean[W][H][D];

        // Direction du rayon depuis le soleil
        Vector3f rayDir = new Vector3f(sunDir).negate().normalize();

        // On décide quelle face du chunk "voit" le soleil
        int startX = (rayDir.x > 0) ? 0 : W - 1;
        int startZ = (rayDir.z > 0) ? 0 : D - 1;
        int stepX = (rayDir.x > 0) ? 1 : -1;
        int stepZ = (rayDir.z > 0) ? 1 : -1;

        // On lance des rayons depuis le haut du chunk + au-delà
        for (int x0 = 0; x0 < W; x0++) {
            for (int z0 = 0; z0 < D; z0++) {

                // Point de départ en coordonnées monde
                int wx = chunk.getChunkX() * W + x0;
                int wz = chunk.getChunkZ() * D + z0;
                int wy = H - 1;

                boolean blocked = false;

                // Avance le long du rayon
                float rx = wx;
                float ry = wy;
                float rz = wz;

                for (int step = 0; step < H * 2; step++) {
                    int bx = Math.floorMod((int) rx, W);
                    int by = (int) ry;
                    int bz = Math.floorMod((int) rz, D);
                    Chunk currentChunk = world.getChunk(
                            Math.floorDiv((int) rx, W),
                            Math.floorDiv((int) rz, D)
                    );

                    if (currentChunk != null && by >= 0 && by < H) {
                        short id = currentChunk.getBlockId(bx, by, bz);
                        if (id != 0) {
                            blocked = true;
                        } else if (!blocked) {
                            shadowMask[bx][by][bz] = true;
                        }
                    }

                    rx += rayDir.x;
                    ry += rayDir.y;
                    rz += rayDir.z;

                    if (ry < 0) break;
                }
            }
        }

        return shadowMask;
    }
}