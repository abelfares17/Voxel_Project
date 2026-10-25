package engine.render;

import engine.time.TimeManager;
import engine.world.BlockRegistry;
import engine.world.Chunk;
import engine.world.World;
import java.util.Locale;

/** Informational warmed micro-measurement, NOT a frame/loading benchmark. Run in a temp directory. */
public final class MeshBoundsCostTest {
    private static volatile MeshBounds sink;

    public static void main(String[] args) {
        BlockRegistry.registerAll();
        World world = new World("Bounds cost", 1, World.Mode.FLAT, 0, new TimeManager());
        try {
            Chunk chunk = new Chunk(0, 0);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int height = 48 + (x * x + z * z) / 12;
                for (int y = 0; y < height; y++) chunk.setBlock(x, y, z, (short) 1);
            }
            MeshData sample = MeshBuilder.generateMeshData(chunk, world, true, false);
            try {
                for (int i = 0; i < 2000; i++) sink = MeshBounds.fromVertices(sample.vertexBuffer, sample.vertexCount);
                long start = System.nanoTime();
                for (int i = 0; i < 2000; i++) sink = MeshBounds.fromVertices(sample.vertexBuffer, sample.vertexCount);
                double scanMs = (System.nanoTime() - start) / 2_000_000_000.0;
                for (int i = 0; i < 30; i++) MeshBuilder.generateMeshData(chunk, world, true, false).free();
                start = System.nanoTime();
                for (int i = 0; i < 100; i++) MeshBuilder.generateMeshData(chunk, world, true, false).free();
                double generationMs = (System.nanoTime() - start) / 100_000_000.0;
                System.out.printf(Locale.ROOT,
                        "Bounds micro-measurement: vertices=%d; scan_mean_ms=%.6f; generation_including_scan_mean_ms=%.6f; scan/generation=%.3f%%%n",
                        sample.vertexCount, scanMs, generationMs, 100 * scanMs / generationMs);
            } finally {
                sample.free();
            }
            MeshBoundsTest.require(MeshData.COUNT.get() == 0, "native MeshData leak");
        } finally {
            world.cleanup();
        }
    }
}
