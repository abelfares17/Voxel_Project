package tools.tests;

import engine.render.MeshBuilder;
import engine.render.MeshBuilder.Quad;
import engine.render.ShadowMergeProbe;
import engine.time.TimeManager;
import engine.world.BlockRegistry;
import engine.world.Chunk;
import engine.world.World;

import java.util.List;
import java.util.Locale;

/**
 * Headless diagnostic & metrics analyzer for Visual and Shadow Greedy Meshing.
 * Evaluates all chunks in a radius around origin on World 10 and breaks down:
 * - Unmerged 1x1 faces vs Uniform-AO Greedy vs Directional-AO Greedy vs Ignore-AO Greedy vs Shadow Geometry Greedy
 * - Horizontal faces (TOP/BOTTOM) vs Vertical mountain/cliff faces (WEST/EAST/NORTH/SOUTH)
 * - Why remaining 1x1 faces on mountain slopes cannot be merged further (1-block grass crest vs dirt/stone below, or diagonal AO corners).
 */
public class GreedyMeshMetricsTool {

    public static void main(String[] args) throws Exception {
        BlockRegistry.registerAll();
        int radius = Integer.getInteger("voxel.metricsRadius", 8);
        String worldName = System.getProperty("voxel.metricsWorld", "World 10");

        TimeManager tm = new TimeManager();
        World world = new World(worldName, 28371262719219135L, World.Mode.PERLIN, 16, tm);

        var chunksField = World.class.getDeclaredField("chunks");
        chunksField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var chunksMap = (java.util.Map<engine.world.ChunkPos, Chunk>) chunksField.get(world);

        for (int cx = -radius - 1; cx <= radius + 1; cx++) {
            for (int cz = -radius - 1; cz <= radius + 1; cz++) {
                var pos = new engine.world.ChunkPos(cx, cz);
                chunksMap.put(pos, world.generateChunk(pos));
            }
        }

        var genVisible = MeshBuilder.class.getDeclaredMethod("generateVisibleQuads", Chunk.class, World.class);
        genVisible.setAccessible(true);

        long totalUnit = 0;
        long totalUniformAO = 0;
        long totalDirectionalAO = 0;
        long totalBoundaryExactAO = 0;
        long totalBoundaryFullAO = 0;
        long totalIgnoreAO = 0;
        long totalShadowRects = 0;

        long horizUnit = 0, horizUniform = 0, horizDir = 0, horizIgnore = 0;
        long vertUnit = 0, vertUniform = 0, vertDir = 0, vertIgnore = 0;

        long unit1x1InUniform = 0;
        long unit1x1InDirectional = 0;
        long unit1x1InIgnoreAO = 0;
        long strips1DInDirectional = 0;
        long rects2DInDirectional = 0;

        // Breakdown of unit faces on vertical faces by block type (Grass Side vs Dirt vs Stone)
        long vertGrassSide = 0, vertDirt = 0, vertStone = 0;
        long rem1x1UniformAO = 0, rem1x1OneCornerAO = 0, rem1x1EdgeAO = 0, rem1x1OtherAO = 0;

        // Surface-only metrics (Grass blocks = true mountain exterior surface: grass_top + grass_side)
        long grassTopUnit = 0, grassTopUniform = 0, grassTopDir = 0, grassTopIgnore = 0;
        long grassSideUnit = 0, grassSideUniform = 0, grassSideDir = 0, grassSideIgnore = 0;
        long grass1x1Uniform = 0, grass1x1Dir = 0, grass1x1Ignore = 0;
        long grassDir1x1UniformAO = 0, grassDir1x1EdgeAO = 0, grassDir1x1CornerAO = 0, grassDir1x1MultiAO = 0;

        int chunkCount = 0;
        for (int cx = -radius; cx <= radius; cx++) {
            for (int cz = -radius; cz <= radius; cz++) {
                Chunk chunk = world.getChunk(cx, cz);
                @SuppressWarnings("unchecked")
                List<Quad> unitQuads = (List<Quad>) genVisible.invoke(null, chunk, world);
                List<Quad> uniformQuads = MeshBuilder.mergeVisualQuads(unitQuads, false, true);
                List<Quad> dirQuads = MeshBuilder.mergeVisualQuads(unitQuads, false, false);
                List<Quad> boundaryExactQuads = MeshBuilder.mergeVisualQuadsBoundaryAO(unitQuads, false);
                List<Quad> boundaryFullQuads = MeshBuilder.mergeVisualQuadsBoundaryAO(unitQuads, true);
                List<Quad> ignoreAoQuads = MeshBuilder.mergeVisualQuads(unitQuads, true, false);
                var shadowStats = ShadowMergeProbe.measure(unitQuads);

                totalUnit += unitQuads.size();
                totalUniformAO += uniformQuads.size();
                totalDirectionalAO += dirQuads.size();
                totalBoundaryExactAO += boundaryExactQuads.size();
                totalBoundaryFullAO += boundaryFullQuads.size();
                totalIgnoreAO += ignoreAoQuads.size();
                totalShadowRects += shadowStats.rectangles();
                chunkCount++;

                for (Quad q : unitQuads) {
                    boolean isHoriz = Math.abs(q.v0().ny()) > 0.5f;
                    int tileU = getTileU(q);
                    if (tileU == 21) grassTopUnit++;       // col 22 -> index 21 (grass_top)
                    else if (tileU == 20) grassSideUnit++; // col 21 -> index 20 (grass_side)
                    if (isHoriz) horizUnit++;
                    else {
                        vertUnit++;
                        if (tileU == 20) vertGrassSide++;      // col 21 -> index 20
                        else if (tileU == 17) vertDirt++;      // col 18 -> index 17
                        else if (tileU == 30) vertStone++;     // col 31 -> index 30
                    }
                }
                for (Quad q : uniformQuads) {
                    if (Math.abs(q.v0().ny()) > 0.5f) horizUniform++; else vertUniform++;
                    int tileU = getTileU(q);
                    if (tileU == 21) grassTopUniform++;
                    else if (tileU == 20) grassSideUniform++;
                    if (quadArea(q) == 1) {
                        unit1x1InUniform++;
                        if (tileU == 21 || tileU == 20) grass1x1Uniform++;
                    }
                }
                for (Quad q : dirQuads) {
                    if (Math.abs(q.v0().ny()) > 0.5f) horizDir++; else vertDir++;
                    int tileU = getTileU(q);
                    if (tileU == 21) grassTopDir++;
                    else if (tileU == 20) grassSideDir++;
                    int[] wh = quadDims(q);
                    if (wh[0] == 1 && wh[1] == 1) {
                        unit1x1InDirectional++;
                        int a0 = Math.round(q.v0().ao() * 3.0f);
                        int a1 = Math.round(q.v1().ao() * 3.0f);
                        int a2 = Math.round(q.v2().ao() * 3.0f);
                        int a3 = Math.round(q.v3().ao() * 3.0f);
                        boolean isGrass = (tileU == 21 || tileU == 20);
                        if (isGrass) grass1x1Dir++;
                        if (a0 == a1 && a1 == a2 && a2 == a3) {
                            rem1x1UniformAO++;
                            if (isGrass) grassDir1x1UniformAO++;
                        } else if ((a0 == a3 && a1 == a2) || (a0 == a1 && a3 == a2)) {
                            rem1x1EdgeAO++;
                            if (isGrass) grassDir1x1EdgeAO++;
                        } else if ((a0 == a1 && a1 == a2) || (a0 == a1 && a1 == a3)
                                || (a0 == a2 && a2 == a3) || (a1 == a2 && a2 == a3)) {
                            rem1x1OneCornerAO++;
                            if (isGrass) grassDir1x1CornerAO++;
                        } else {
                            rem1x1OtherAO++;
                            if (isGrass) grassDir1x1MultiAO++;
                        }
                    } else if (wh[0] == 1 || wh[1] == 1) {
                        strips1DInDirectional++;
                    } else {
                        rects2DInDirectional++;
                    }
                }
                for (Quad q : ignoreAoQuads) {
                    if (Math.abs(q.v0().ny()) > 0.5f) horizIgnore++; else vertIgnore++;
                    int tileU = getTileU(q);
                    if (tileU == 21) grassTopIgnore++;
                    else if (tileU == 20) grassSideIgnore++;
                    if (quadArea(q) == 1) {
                        unit1x1InIgnoreAO++;
                        if (tileU == 21 || tileU == 20) grass1x1Ignore++;
                    }
                }
            }
        }

        System.out.println("================================================================================");
        System.out.printf(Locale.ROOT, "GREEDY MESHING METRICS REPORT (%s, %d chunks, radius=%d)%n",
                worldName, chunkCount, radius);
        System.out.println("================================================================================");
        System.out.printf(Locale.ROOT, "1. Unmerged (Legacy 1x1)                   : %,10d quads (%,10d tris)%n",
                totalUnit, totalUnit * 2);
        System.out.printf(Locale.ROOT, "2. Greedy v1 (Uniform-AO only)             : %,10d quads (%,10d tris)  [-%.2f%%]%n",
                totalUniformAO, totalUniformAO * 2, 100.0 * (1.0 - (double) totalUniformAO / totalUnit));
        System.out.printf(Locale.ROOT, "3. Greedy v2 (Directional-AO)              : %,10d quads (%,10d tris)  [-%.2f%%]%n",
                totalDirectionalAO, totalDirectionalAO * 2, 100.0 * (1.0 - (double) totalDirectionalAO / totalUnit));
        System.out.printf(Locale.ROOT, "4. Greedy v2.5 (Exact 9-Pt Boundary AO)    : %,10d quads (%,10d tris)  [-%.2f%%]%n",
                totalBoundaryExactAO, totalBoundaryExactAO * 2, 100.0 * (1.0 - (double) totalBoundaryExactAO / totalUnit));
        System.out.printf(Locale.ROOT, "5. Greedy v3 + Option 1 (Full + Clamped AO): %,10d quads (%,10d tris)  [-%.2f%%]%n",
                totalBoundaryFullAO, totalBoundaryFullAO * 2, 100.0 * (1.0 - (double) totalBoundaryFullAO / totalUnit));
        System.out.printf(Locale.ROOT, "6. Greedy v3 (Ignore-AO, same tex)         : %,10d quads (%,10d tris)  [-%.2f%%]%n",
                totalIgnoreAO, totalIgnoreAO * 2, 100.0 * (1.0 - (double) totalIgnoreAO / totalUnit));
        System.out.printf(Locale.ROOT, "7. Greedy v4 (ShadowMesh, all blocks)      : %,10d rects                  [-%.2f%%]%n",
                totalShadowRects, 100.0 * (1.0 - (double) totalShadowRects / totalUnit));
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("EXTERIOR MOUNTAIN & TERRAIN SURFACE (Grass Top + Grass Side):");
        System.out.printf(Locale.ROOT, "  Grass Top  (Horizontal steps/plains) : %,8d unit -> v1: %,7d (-%.1f%%) | v2: %,7d (-%.1f%%) | ignoreAO: %,7d (-%.1f%%)%n",
                grassTopUnit,
                grassTopUniform, 100.0 * (1.0 - (double) grassTopUniform / grassTopUnit),
                grassTopDir,     100.0 * (1.0 - (double) grassTopDir / grassTopUnit),
                grassTopIgnore,  100.0 * (1.0 - (double) grassTopIgnore / grassTopUnit));
        System.out.printf(Locale.ROOT, "  Grass Side (Vertical step risers)    : %,8d unit -> v1: %,7d (-%.1f%%) | v2: %,7d (-%.1f%%) | ignoreAO: %,7d (-%.1f%%)%n",
                grassSideUnit,
                grassSideUniform, 100.0 * (1.0 - (double) grassSideUniform / grassSideUnit),
                grassSideDir,     100.0 * (1.0 - (double) grassSideDir / grassSideUnit),
                grassSideIgnore,  100.0 * (1.0 - (double) grassSideIgnore / grassSideUnit));
        System.out.printf(Locale.ROOT, "  Grass Remaining 1x1 Quads            : v1 = %,7d -> v2 = %,7d (-%.1f%% fewer!) | ignoreAO = %,7d%n",
                grass1x1Uniform, grass1x1Dir,
                100.0 * (1.0 - (double) grass1x1Dir / grass1x1Uniform),
                grass1x1Ignore);
        System.out.printf(Locale.ROOT, "  Why %,d Grass 1x1 quads remain in v2 (Directional-AO):%n", grass1x1Dir);
        System.out.printf(Locale.ROOT, "    - Isolated 1x1 geometry (Uniform AO) : %,7d (%.1f%%)%n",
                grassDir1x1UniformAO, 100.0 * grassDir1x1UniformAO / Math.max(1, grass1x1Dir));
        System.out.printf(Locale.ROOT, "    - 1-block step segment  (Edge AO)    : %,7d (%.1f%%)%n",
                grassDir1x1EdgeAO, 100.0 * grassDir1x1EdgeAO / Math.max(1, grass1x1Dir));
        System.out.printf(Locale.ROOT, "    - Diagonal step corner  (1-Corner AO): %,7d (%.1f%%)%n",
                grassDir1x1CornerAO, 100.0 * grassDir1x1CornerAO / Math.max(1, grass1x1Dir));
        System.out.printf(Locale.ROOT, "    - Curved/Multi-corner   (Multi AO)   : %,7d (%.1f%%)%n",
                grassDir1x1MultiAO, 100.0 * grassDir1x1MultiAO / Math.max(1, grass1x1Dir));
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("BREAKDOWN BY ORIENTATION:");
        System.out.printf(Locale.ROOT, "  Horizontal (TOP/BOTTOM) : %,9d unit -> v1: %,8d (-%.1f%%) | v2: %,8d (-%.1f%%) | ignoreAO: %,8d (-%.1f%%)%n",
                horizUnit,
                horizUniform, 100.0 * (1.0 - (double) horizUniform / horizUnit),
                horizDir,     100.0 * (1.0 - (double) horizDir / horizUnit),
                horizIgnore,  100.0 * (1.0 - (double) horizIgnore / horizUnit));
        System.out.printf(Locale.ROOT, "  Vertical (Cliffs/Slopes): %,9d unit -> v1: %,8d (-%.1f%%) | v2: %,8d (-%.1f%%) | ignoreAO: %,8d (-%.1f%%)%n",
                vertUnit,
                vertUniform, 100.0 * (1.0 - (double) vertUniform / vertUnit),
                vertDir,     100.0 * (1.0 - (double) vertDir / vertUnit),
                vertIgnore,  100.0 * (1.0 - (double) vertIgnore / vertUnit));
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("QUAD SHAPE DISTRIBUTION (Greedy v2 Directional-AO vs v1 Uniform-AO):");
        System.out.printf(Locale.ROOT, "  Remaining 1x1 unmerged quads : v1 = %,8d  ->  v2 = %,8d (-%.1f%% fewer 1x1 quads!) | ignoreAO = %,8d%n",
                unit1x1InUniform, unit1x1InDirectional,
                100.0 * (1.0 - (double) unit1x1InDirectional / unit1x1InUniform),
                unit1x1InIgnoreAO);
        System.out.printf(Locale.ROOT, "  1D Merged Strips (Wx1 / 1xH) : %,8d quads (mountain steps & ledges)%n", strips1DInDirectional);
        System.out.printf(Locale.ROOT, "  2D Merged Rects  (WxH, W,H>1): %,8d quads (flat plains, cave walls & cliffs)%n", rects2DInDirectional);
        System.out.printf(Locale.ROOT, "  Breakdown of the %,d remaining 1x1 quads in v2:%n", unit1x1InDirectional);
        System.out.printf(Locale.ROOT, "    - Uniform AO (isolated 1x1 geometry) : %,8d (%.1f%%)%n",
                rem1x1UniformAO, 100.0 * rem1x1UniformAO / unit1x1InDirectional);
        System.out.printf(Locale.ROOT, "    - Edge AO (1-block long ledge/step)  : %,8d (%.1f%%)%n",
                rem1x1EdgeAO, 100.0 * rem1x1EdgeAO / unit1x1InDirectional);
        System.out.printf(Locale.ROOT, "    - 1-Corner AO (diagonal neighbor)    : %,8d (%.1f%%)%n",
                rem1x1OneCornerAO, 100.0 * rem1x1OneCornerAO / unit1x1InDirectional);
        System.out.printf(Locale.ROOT, "    - Multi-Corner AO (complex crease)   : %,8d (%.1f%%)%n",
                rem1x1OtherAO, 100.0 * rem1x1OtherAO / unit1x1InDirectional);
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("VERTICAL FACE COMPOSITION (Why mountain slopes have distinct layers):");
        System.out.printf(Locale.ROOT, "  Grass Side faces (top 1-block layer of every step) : %,8d (%.1f%% of all vertical faces)%n",
                vertGrassSide, 100.0 * vertGrassSide / Math.max(1, vertUnit));
        System.out.printf(Locale.ROOT, "  Dirt faces       (sub-surface layer)               : %,8d (%.1f%% of all vertical faces)%n",
                vertDirt, 100.0 * vertDirt / Math.max(1, vertUnit));
        System.out.printf(Locale.ROOT, "  Stone faces      (cliffs & caves)                  : %,8d (%.1f%% of all vertical faces)%n",
                vertStone, 100.0 * vertStone / Math.max(1, vertUnit));
        System.out.println("================================================================================");
        world.getMeshManager().shutdown();
    }

    private static int[] quadDims(Quad q) {
        int dx = Math.round(Math.max(Math.max(q.v0().x(), q.v1().x()), Math.max(q.v2().x(), q.v3().x()))
                - Math.min(Math.min(q.v0().x(), q.v1().x()), Math.min(q.v2().x(), q.v3().x())));
        int dy = Math.round(Math.max(Math.max(q.v0().y(), q.v1().y()), Math.max(q.v2().y(), q.v3().y()))
                - Math.min(Math.min(q.v0().y(), q.v1().y()), Math.min(q.v2().y(), q.v3().y())));
        int dz = Math.round(Math.max(Math.max(q.v0().z(), q.v1().z()), Math.max(q.v2().z(), q.v3().z()))
                - Math.min(Math.min(q.v0().z(), q.v1().z()), Math.min(q.v2().z(), q.v3().z())));
        if (dx == 0) return new int[]{dz, dy};
        if (dy == 0) return new int[]{dx, dz};
        return new int[]{dx, dy};
    }

    private static int getTileU(Quad q) {
        float minU = Math.min(Math.min(q.v0().u(), q.v1().u()), Math.min(q.v2().u(), q.v3().u()));
        if (minU > 2.0f) {
            return (Math.round(minU) >> 9) & 63;
        }
        return Math.round(minU * 64.0f);
    }

    private static int quadArea(Quad q) {
        int[] wh = quadDims(q);
        return wh[0] * wh[1];
    }
}
