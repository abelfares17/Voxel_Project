// src/main/java/engine/render/MeshBuilder.java
package engine.render;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;
import org.lwjgl.system.MemoryUtil;

import engine.world.Block;
import engine.world.Chunk;
import engine.world.World;

/**
 * Construit et rend, en Core Profile 3.2, les meshes interleavés de chaque chunk.
 * Chaque face visible est convertie en deux triangles.
 */
public class MeshBuilder {
    private static final int FLOAT_SIZE = Float.BYTES;
    private static final int POS_SIZE   = 3; // x,y,z
    private static final int TEX_SIZE   = 2; // u,v
    private static final int NOR_SIZE   = 3; // nx,ny,nz
    private static final int AO_SIZE    = 1;
    // CPU shadow (LIGHT_SIZE) removed - GPU shadow mapping only
    public static final int STRIDE = (POS_SIZE + TEX_SIZE + NOR_SIZE + AO_SIZE) * FLOAT_SIZE;
    public static MeshData generateMeshData(Chunk chunk, World world) {
        return generateMeshData(chunk, world, engine.core.config.RenderConfig.INDEXED_MESHES);
    }

    public static MeshData generateMeshData(Chunk chunk, World world, boolean indexed) {
        return generateMeshData(chunk, world, indexed, engine.core.config.RenderConfig.COMPACT_VERTICES);
    }

    public static MeshData generateMeshData(Chunk chunk, World world, boolean indexed, boolean compact) {
        List<Quad> unitQuads = generateVisibleQuads(chunk, world);
        if (Boolean.getBoolean("voxel.probeShadowMerge")) {
            long start = System.nanoTime();
            var result = ShadowMergeProbe.measure(unitQuads);
            System.out.println("SHADOW_MERGE_PROBE chunk=" + chunk.getChunkX() + "," + chunk.getChunkZ()
                    + " faces=" + result.faces() + " rectangles=" + result.rectangles()
                    + " ns=" + (System.nanoTime() - start));
        }
        List<Quad> quads = engine.core.config.RenderConfig.GREEDY_VISUAL
                ? mergeVisualQuads(unitQuads)
                : unitQuads;
        if (Boolean.getBoolean("voxel.probeVisualMerge")) {
            List<Quad> uniformOnly = mergeVisualQuads(unitQuads, false, true);
            List<Quad> ignoreAo = mergeVisualQuads(unitQuads, true, false);
            System.out.println(String.format(java.util.Locale.ROOT,
                    "VISUAL_MERGE_PROBE chunk=%d,%d unit=%d uniformAO=%d(-%.1f%%) directionalAO=%d(-%.1f%%) ignoreAO=%d(-%.1f%%)",
                    chunk.getChunkX(), chunk.getChunkZ(),
                    unitQuads.size(),
                    uniformOnly.size(), unitQuads.isEmpty() ? 0.0 : 100.0 * (1.0 - (double) uniformOnly.size() / unitQuads.size()),
                    quads.size(), unitQuads.isEmpty() ? 0.0 : 100.0 * (1.0 - (double) quads.size() / unitQuads.size()),
                    ignoreAo.size(), unitQuads.isEmpty() ? 0.0 : 100.0 * (1.0 - (double) ignoreAo.size() / unitQuads.size())));
        }
        int vertexCount = Math.multiplyExact(quads.size(), indexed ? 4 : 6);
        FloatBuffer fb = org.lwjgl.system.MemoryUtil.memAllocFloat(vertexCount * (POS_SIZE + TEX_SIZE + NOR_SIZE + AO_SIZE));
        java.nio.IntBuffer indices = null;
        java.nio.ByteBuffer packed = null;
        try {
            if (indexed) indices = MemoryUtil.memAllocInt(Math.multiplyExact(quads.size(), 6));
            int base = 0;
            for (Quad q : quads) {
                // Triangle 1: v0→v1→v2 (CCW when viewed from outside)
                putVertex(fb, q.v0);
                putVertex(fb, q.v1);
                putVertex(fb, q.v2);
                // Triangle 2: v0→v2→v3 (CCW when viewed from outside)
                if (!indexed) {
                    putVertex(fb, q.v0);
                    putVertex(fb, q.v2);
                } else {
                    indices.put(base).put(base + 1).put(base + 2).put(base).put(base + 2).put(base + 3);
                    base += 4;
                }
                putVertex(fb, q.v3);
            }
            fb.flip();
            if (indices != null) indices.flip();
            MeshBounds bounds = MeshBounds.fromVertices(fb, vertexCount);
            if (compact) packed = MeshVertexLayout.pack(fb, vertexCount);
            ShadowMesh.Data shadowData = null;
            if (engine.core.config.RenderConfig.GREEDY_SHADOWS || Boolean.getBoolean("voxel.validateGreedyShadows")
                    || Boolean.getBoolean("voxel.compareGreedyImages"))
                shadowData = ShadowMesh.generate(unitQuads);
            MeshData data = new MeshData(compact ? null : fb, vertexCount, chunk, bounds, indices, packed);
            data.shadowData = shadowData;
            if (compact) { MemoryUtil.memFree(fb); fb = null; }
            return data;
        } catch (RuntimeException | Error failure) {
            if (packed != null) MemoryUtil.memFree(packed);
            if (indices != null) MemoryUtil.memFree(indices);
            if (fb != null) MemoryUtil.memFree(fb);
            throw failure;
        }
    }

    public static void buildChunkMesh(Chunk chunk, World world) {
        chunk.cleanup();
        MeshData data = generateMeshData(chunk, world);
        try {

            int vao = glGenVertexArrays();
            glBindVertexArray(vao);

            int vbo = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            MeshVertexLayout.upload(data);

            int ebo = uploadIndices(data);
            glBindVertexArray(0);
            glBindBuffer(GL_ARRAY_BUFFER, 0);

            chunk.setMesh(vao, vbo, data.vertexCount, data.bounds, ebo, data.indexCount, data.vertexStride());
            if (data.shadowData != null) chunk.setShadowMesh(ShadowMesh.upload(data.shadowData));
        } finally {
            data.free();
        }
        //System.out.println("Chunk ("+chunk.getChunkX()+","+chunk.getChunkZ()+"): quads=" + quads.size());
    }

    public static void renderChunk(Chunk chunk) {
        if (!chunk.isReady()) {
            return;
        }
        glBindVertexArray(chunk.getVao());
        drawBoundChunk(chunk);
        glBindVertexArray(0);
    }

    /** Called with the destination VAO bound; its element binding must remain attached. */
    public static int uploadIndices(MeshData data) {
        if (data.indexBuffer == null) return 0;
        int ebo = glGenBuffers();
        org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER, data.indexBuffer, GL_STATIC_DRAW);
        return ebo;
    }

    public static void drawBoundChunk(Chunk chunk) {
        if (chunk.getEbo() != 0) {
            org.lwjgl.opengl.GL11.glDrawElements(GL_TRIANGLES, chunk.getIndexCount(), org.lwjgl.opengl.GL11.GL_UNSIGNED_INT, 0L);
        } else {
            glDrawArrays(GL_TRIANGLES, 0, chunk.getVertexCount());
        }
    }

    private static void putVertex(FloatBuffer fb, Vertex v) {
        fb.put(v.x).put(v.y).put(v.z);
        fb.put(v.u).put(v.v);
        fb.put(v.nx).put(v.ny).put(v.nz);
        fb.put(v.ao);
    }

    public record Vertex(
            float x, float y, float z,
            float u, float v,
            float nx, float ny, float nz,
            float ao
    ) {}

    public record Quad(
            Vertex v0, Vertex v1, Vertex v2, Vertex v3
    ) {}

    // CPU shadow computation removed - GPU shadow mapping only

    private static List<Quad> generateVisibleQuads(Chunk chunk, World world) {
        List<Quad> mesh = new ArrayList<>();
        final int[][] DIRS = {
                { 0,  1,  0}, // TOP
                { 0, -1,  0}, // BOTTOM
                {-1,  0,  0}, // WEST
                { 1,  0,  0}, // EAST
                { 0,  0, -1}, // NORTH
                { 0,  0,  1}  // SOUTH
        };
        final float[][] NORMALS = {
                { 0,  1,  0},
                { 0, -1,  0},
                {-1,  0,  0},
                { 1,  0,  0},
                { 0,  0, -1},
                { 0,  0,  1}
        };

        for (int bx = 0; bx < Chunk.WIDTH; bx++) {
            for (int by = 0; by < Chunk.HEIGHT; by++) {
                for (int bz = 0; bz < Chunk.DEPTH; bz++) {
                    Block block = chunk.getBlock(bx, by, bz);
                    if (block == null || block.getId() == 0) continue; //air

                    for (int f = 0; f < 6; f++) {
                        int nx = bx + DIRS[f][0];
                        int ny = by + DIRS[f][1];
                        int nz = bz + DIRS[f][2];

                        boolean visible;
                        if (chunk.inBounds(nx, ny, nz)) {
                            // Bloc dans ce chunk
                            visible = chunk.getBlockId(nx, ny, nz) == 0;
                        } else {
                            // Bloc hors du chunk courant, on doit aller chercher dans le monde
                            int wx = chunk.getChunkX() * Chunk.WIDTH + nx;
                            int wy = ny;
                            int wz = chunk.getChunkZ() * Chunk.DEPTH + nz;
                            visible = world.getBlockAt(wx, wy, wz).getId() == 0;
                        }

                        if (!visible) continue;

                        int wx = chunk.getChunkX() * Chunk.WIDTH + bx;
                        int wy = by;
                        int wz = chunk.getChunkZ() * Chunk.DEPTH + bz;

                        float[] norm = NORMALS[f];
                        TextureCoords tex = switch (f) {
                            case 0 -> block.getTopTexture();
                            case 1 -> block.getBottomTexture();
                            default -> block.getSideTexture();
                        };
                        mesh.add(generateQuad(bx, by, bz, f, tex, norm, world, wx, wy, wz));
                    }
                }
            }
        }
        return mesh;
    }

    /**
     * Compute per-vertex AO using face tangent axes.
     * Each face has two tangent axes (U, V) that span the face plane.
     * For each corner vertex, we sample 3 neighbors: sideU, sideV, and corner (diagonal).
     *
     * Face tangent axes:
     *   TOP (+Y) / BOTTOM (-Y): U = +X, V = +Z
     *   NORTH (-Z) / SOUTH (+Z): U = +X, V = +Y
     *   WEST (-X) / EAST (+X):   U = +Z, V = +Y
     *
     * @param world World for block queries
     * @param bx Block X (world coords)
     * @param by Block Y (world coords)
     * @param bz Block Z (world coords)
     * @param face Face index (0=TOP, 1=BOTTOM, 2=WEST, 3=EAST, 4=NORTH, 5=SOUTH)
     * @param cornerIdx Corner index within quad (0-3), maps to (su, sv) in {-1,+1}
     * @return AO value in [0, 1] where 0 = fully occluded, 1 = no occlusion
     */
    private static float computeAO(World world, int bx, int by, int bz, int face, int cornerIdx) {
        // Define tangent axes (U, V) for each face, plus face normal offset
        // Format: {Ux, Uy, Uz, Vx, Vy, Vz, Nx, Ny, Nz}
        final int[][] FACE_AXES = {
            {1, 0, 0,  0, 0, 1,  0, 1, 0},  // TOP (+Y): U=+X, V=+Z, N=+Y
            {1, 0, 0,  0, 0, 1,  0,-1, 0},  // BOTTOM (-Y): U=+X, V=+Z, N=-Y
            {0, 0, 1,  0, 1, 0, -1, 0, 0},  // WEST (-X): U=+Z, V=+Y, N=-X
            {0, 0, 1,  0, 1, 0,  1, 0, 0},  // EAST (+X): U=+Z, V=+Y, N=+X
            {1, 0, 0,  0, 1, 0,  0, 0,-1},  // NORTH (-Z): U=+X, V=+Y, N=-Z
            {1, 0, 0,  0, 1, 0,  0, 0, 1},  // SOUTH (+Z): U=+X, V=+Y, N=+Z
        };

        // Corner signs: maps cornerIdx to (su, sv) in the face's (U, V) plane
        // cornerIdx: 0=(-1,-1), 1=(+1,-1), 2=(+1,+1), 3=(-1,+1)
        final int[][] CORNER_SIGNS = {
            {-1, -1}, {1, -1}, {1, 1}, {-1, 1}
        };

        int[] axes = FACE_AXES[face];
        int ux = axes[0], uy = axes[1], uz = axes[2];
        int vx = axes[3], vy = axes[4], vz = axes[5];
        int nx = axes[6], ny = axes[7], nz = axes[8];

        int su = CORNER_SIGNS[cornerIdx][0];
        int sv = CORNER_SIGNS[cornerIdx][1];

        // Sample position: block center + normal direction (outside the block)
        int px = bx + (nx > 0 ? 1 : 0);
        int py = by + (ny > 0 ? 1 : 0);
        int pz = bz + (nz > 0 ? 1 : 0);

        // Sample the 3 neighbors relative to this corner
        boolean sideU = world.getBlockAt(px + su * ux, py + su * uy, pz + su * uz).getId() != 0;
        boolean sideV = world.getBlockAt(px + sv * vx, py + sv * vy, pz + sv * vz).getId() != 0;
        boolean corner = world.getBlockAt(px + su * ux + sv * vx, py + su * uy + sv * vy, pz + su * uz + sv * vz).getId() != 0;

        // Standard Minecraft-like AO formula
        if (sideU && sideV) {
            return 0.0f; // Fully occluded corner
        }
        int occluders = (sideU ? 1 : 0) + (sideV ? 1 : 0) + (corner ? 1 : 0);
        return 1.0f - occluders / 3.0f;
    }

    private static Quad generateQuad(int bx, int by, int bz, int f,
                                     TextureCoords tex, float[] norm, World world, int wx, int wy, int wz) {
        float u0 = tex.getUMin(), v0 = tex.getVMin();
        float u1 = tex.getUMax(), v1 = tex.getVMax();
        float nx = norm[0], ny = norm[1], nz = norm[2];

        // All faces ordered for CCW winding when viewed from outside the block
        // AO cornerIdx: 0=(-U,-V), 1=(+U,-V), 2=(+U,+V), 3=(-U,+V) in face's tangent space
        // Vertex order must produce CCW winding when viewed from outside (front-face)
        // Cross product of (v1-v0) × (v2-v0) must point in same direction as face normal
        return switch (f) {
            case 0 -> new Quad( // TOP (+Y): reversed for CCW winding
                    new Vertex(bx,   by+1, bz,   u0, v0, nx, ny, nz, computeAO(world, wx, wy, wz, 0, 0)),
                    new Vertex(bx,   by+1, bz+1, u0, v1, nx, ny, nz, computeAO(world, wx, wy, wz, 0, 3)),
                    new Vertex(bx+1, by+1, bz+1, u1, v1, nx, ny, nz, computeAO(world, wx, wy, wz, 0, 2)),
                    new Vertex(bx+1, by+1, bz,   u1, v0, nx, ny, nz, computeAO(world, wx, wy, wz, 0, 1))
            );

            case 1 -> new Quad( // BOTTOM (-Y): reversed for CCW winding
                    new Vertex(bx,   by, bz,   u0, v1, nx, ny, nz, computeAO(world, wx, wy, wz, 1, 0)),
                    new Vertex(bx+1, by, bz,   u1, v1, nx, ny, nz, computeAO(world, wx, wy, wz, 1, 1)),
                    new Vertex(bx+1, by, bz+1, u1, v0, nx, ny, nz, computeAO(world, wx, wy, wz, 1, 2)),
                    new Vertex(bx,   by, bz+1, u0, v0, nx, ny, nz, computeAO(world, wx, wy, wz, 1, 3))
            );

            case 2 -> new Quad( // WEST (-X): already CCW, keep as is
                    new Vertex(bx, by,   bz+1, u0, v0, nx, ny, nz, computeAO(world, wx, wy, wz, 2, 1)),
                    new Vertex(bx, by+1, bz+1, u0, v1, nx, ny, nz, computeAO(world, wx, wy, wz, 2, 2)),
                    new Vertex(bx, by+1, bz,   u1, v1, nx, ny, nz, computeAO(world, wx, wy, wz, 2, 3)),
                    new Vertex(bx, by,   bz,   u1, v0, nx, ny, nz, computeAO(world, wx, wy, wz, 2, 0))
            );

            case 3 -> new Quad( // EAST (+X): already CCW, keep as is
                    new Vertex(bx+1, by,   bz,   u0, v0, nx, ny, nz, computeAO(world, wx, wy, wz, 3, 0)),
                    new Vertex(bx+1, by+1, bz,   u0, v1, nx, ny, nz, computeAO(world, wx, wy, wz, 3, 3)),
                    new Vertex(bx+1, by+1, bz+1, u1, v1, nx, ny, nz, computeAO(world, wx, wy, wz, 3, 2)),
                    new Vertex(bx+1, by,   bz+1, u1, v0, nx, ny, nz, computeAO(world, wx, wy, wz, 3, 1))
            );

            case 4 -> new Quad( // NORTH (-Z): reversed for CCW winding
                    new Vertex(bx,   by,   bz, u1, v0, nx, ny, nz, computeAO(world, wx, wy, wz, 4, 0)),
                    new Vertex(bx,   by+1, bz, u1, v1, nx, ny, nz, computeAO(world, wx, wy, wz, 4, 3)),
                    new Vertex(bx+1, by+1, bz, u0, v1, nx, ny, nz, computeAO(world, wx, wy, wz, 4, 2)),
                    new Vertex(bx+1, by,   bz, u0, v0, nx, ny, nz, computeAO(world, wx, wy, wz, 4, 1))
            );

            case 5 -> new Quad( // SOUTH (+Z): reversed for CCW winding
                    new Vertex(bx+1, by,   bz+1, u1, v0, nx, ny, nz, computeAO(world, wx, wy, wz, 5, 1)),
                    new Vertex(bx+1, by+1, bz+1, u1, v1, nx, ny, nz, computeAO(world, wx, wy, wz, 5, 2)),
                    new Vertex(bx,   by+1, bz+1, u0, v1, nx, ny, nz, computeAO(world, wx, wy, wz, 5, 3)),
                    new Vertex(bx,   by,   bz+1, u0, v0, nx, ny, nz, computeAO(world, wx, wy, wz, 5, 0))
            );
            default -> throw new IllegalArgumentException("Face inconnue: " + f);
        };
    }

    /**
     * Greedy 2D merging of coplanar visual faces sharing identical atlas tile and compatible AO.
     * - Standard mode (default): merges along U whenever AO is invariant along U (e.g. mountain steps
     *   and cliff bases/tops), and along V whenever AO is invariant along V (vertical/horizontal strips
     *   and full 2D rectangles), guaranteeing 0% AO interpolation error while merging mountain contours.
     * - When -Dvoxel.greedyIgnoreAO=true: merges purely by block texture (like ShadowMesh).
     */
    public static List<Quad> mergeVisualQuads(List<Quad> unitQuads) {
        boolean ignoreAO = Boolean.getBoolean("voxel.greedyIgnoreAO");
        boolean uniformOnly = Boolean.getBoolean("voxel.greedyUniformAO");
        boolean boundaryAO = Boolean.parseBoolean(System.getProperty("voxel.greedyBoundaryAO", "true"));
        if (boundaryAO && !ignoreAO && !uniformOnly) {
            boolean fullBoundary = Boolean.getBoolean("voxel.greedyFullBoundaryAO");
            return mergeVisualQuadsBoundaryAO(unitQuads, fullBoundary);
        }
        return mergeVisualQuads(unitQuads, ignoreAO, uniformOnly);
    }

    public static List<Quad> mergeVisualQuads(List<Quad> unitQuads, boolean ignoreAO, boolean uniformAoOnly) {
        if (unitQuads.isEmpty()) return unitQuads;
        List<Quad> result = new ArrayList<>(unitQuads.size() / 3 + 16);
        java.util.Map<Integer, List<Quad>> bySlice = new java.util.HashMap<>();

        for (Quad q : unitQuads) {
            int f;
            int slice;
            if (q.v0.ny > 0.5f)       { f = 0; slice = Math.round(q.v0.y); }
            else if (q.v0.ny < -0.5f) { f = 1; slice = Math.round(q.v0.y); }
            else if (q.v0.nx < -0.5f) { f = 2; slice = Math.round(q.v0.x); }
            else if (q.v0.nx > 0.5f)  { f = 3; slice = Math.round(q.v0.x); }
            else if (q.v0.nz < -0.5f) { f = 4; slice = Math.round(q.v0.z); }
            else                      { f = 5; slice = Math.round(q.v0.z); }

            if (uniformAoOnly && !ignoreAO) {
                boolean uniformAO = (q.v0.ao == q.v1.ao) && (q.v1.ao == q.v2.ao) && (q.v2.ao == q.v3.ao);
                if (!uniformAO) {
                    int u0Idx, v0Idx;
                    float uMin, vMin;
                    switch (f) {
                        case 0 -> { u0Idx = Math.round(q.v0.x); v0Idx = Math.round(q.v0.z); uMin = q.v0.u; vMin = q.v0.v; }
                        case 1 -> { u0Idx = Math.round(q.v0.x); v0Idx = Math.round(q.v0.z); uMin = q.v3.u; vMin = q.v3.v; }
                        case 2 -> { u0Idx = Math.round(q.v3.z); v0Idx = Math.round(q.v3.y); uMin = q.v0.u; vMin = q.v0.v; }
                        case 3 -> { u0Idx = Math.round(q.v0.z); v0Idx = Math.round(q.v0.y); uMin = q.v0.u; vMin = q.v0.v; }
                        case 4 -> { u0Idx = Math.round(q.v0.x); v0Idx = Math.round(q.v0.y); uMin = q.v3.u; vMin = q.v3.v; }
                        default -> { u0Idx = Math.round(q.v3.x); v0Idx = Math.round(q.v3.y); uMin = q.v3.u; vMin = q.v3.v; }
                    }
                    int tileU = Math.round(uMin * 64.0f);
                    int tileV = Math.round(vMin * 64.0f);
                    result.add(buildGreedyQuad(f, slice, u0Idx, v0Idx, 1, 1, tileU, tileV,
                            q.v0.ao, q.v1.ao, q.v2.ao, q.v3.ao));
                    continue;
                }
            }

            int sliceKey = (f << 12) | (slice & 0xFFF);
            bySlice.computeIfAbsent(sliceKey, k -> new ArrayList<>()).add(q);
        }

        int[][] grid = new int[Chunk.WIDTH][Chunk.HEIGHT];
        for (var entry : bySlice.entrySet()) {
            int sliceKey = entry.getKey();
            int f = sliceKey >>> 12;
            int slice = sliceKey & 0xFFF;
            int uMax = Chunk.WIDTH;
            int vMax = (f <= 1) ? Chunk.DEPTH : Chunk.HEIGHT;

            for (int u = 0; u < uMax; u++) {
                java.util.Arrays.fill(grid[u], 0, vMax, 0);
            }

            for (Quad q : entry.getValue()) {
                int u0Idx, v0Idx;
                float uMin, vMin;
                switch (f) {
                    case 0 -> { u0Idx = Math.round(q.v0.x); v0Idx = Math.round(q.v0.z); uMin = q.v0.u; vMin = q.v0.v; }
                    case 1 -> { u0Idx = Math.round(q.v0.x); v0Idx = Math.round(q.v0.z); uMin = q.v3.u; vMin = q.v3.v; }
                    case 2 -> { u0Idx = Math.round(q.v3.z); v0Idx = Math.round(q.v3.y); uMin = q.v0.u; vMin = q.v0.v; }
                    case 3 -> { u0Idx = Math.round(q.v0.z); v0Idx = Math.round(q.v0.y); uMin = q.v0.u; vMin = q.v0.v; }
                    case 4 -> { u0Idx = Math.round(q.v0.x); v0Idx = Math.round(q.v0.y); uMin = q.v3.u; vMin = q.v3.v; }
                    default -> { u0Idx = Math.round(q.v3.x); v0Idx = Math.round(q.v3.y); uMin = q.v3.u; vMin = q.v3.v; }
                }
                int tileU = Math.round(uMin * 64.0f) & 0x3F;
                int tileV = Math.round(vMin * 64.0f) & 0x3F;
                int aoBits;
                if (ignoreAO) {
                    aoBits = 0xFF; // all 4 corners = 3 (AO = 1.0)
                } else {
                    int a0 = Math.round(q.v0.ao * 3.0f) & 0x3;
                    int a1 = Math.round(q.v1.ao * 3.0f) & 0x3;
                    int a2 = Math.round(q.v2.ao * 3.0f) & 0x3;
                    int a3 = Math.round(q.v3.ao * 3.0f) & 0x3;
                    if (!Boolean.getBoolean("voxel.greedyStrictCornerAO")) {
                        // Unify 1-occluder (step end) and 2-occluder (step middle) crease AO
                        // so 2-block and 3-block mountain steps merge into a single continuous strip
                        // while preserving full crease shadows and deep corner AO.
                        if (a0 == 1) a0 = 2;
                        if (a1 == 1) a1 = 2;
                        if (a2 == 1) a2 = 2;
                        if (a3 == 1) a3 = 2;
                    }
                    aoBits = a0 | (a1 << 2) | (a2 << 4) | (a3 << 6);
                }
                grid[u0Idx][v0Idx] = 1 + (tileU | (tileV << 6) | (aoBits << 12));
            }

            for (int v = 0; v < vMax; v++) {
                for (int u = 0; u < uMax; u++) {
                    int key = grid[u][v];
                    if (key == 0) continue;

                    int packed = key - 1;
                    int tileU = packed & 0x3F;
                    int tileV = (packed >>> 6) & 0x3F;
                    int aoBits = (packed >>> 12) & 0xFF;
                    int a0 = aoBits & 0x3;
                    int a1 = (aoBits >>> 2) & 0x3;
                    int a2 = (aoBits >>> 4) & 0x3;
                    int a3 = (aoBits >>> 6) & 0x3;

                    // Directional AO invariance check:
                    // - Expand along U iff AO at U-min equals AO at U-max for both V edges
                    // - Expand along V iff AO at V-min equals AO at V-max for both U edges
                    boolean canExpandU = (f == 1) ? (a0 == a1 && a3 == a2) : (a0 == a3 && a1 == a2);
                    boolean canExpandV = (f == 1) ? (a0 == a3 && a1 == a2) : (a0 == a1 && a3 == a2);

                    int w = 1;
                    if (canExpandU) {
                        while (u + w < uMax && grid[u + w][v] == key) {
                            w++;
                        }
                    }

                    int h = 1;
                    if (canExpandV) {
                        outer:
                        while (v + h < vMax) {
                            for (int du = 0; du < w; du++) {
                                if (grid[u + du][v + h] != key) break outer;
                            }
                            h++;
                        }
                    }

                    for (int dv = 0; dv < h; dv++) {
                        for (int du = 0; du < w; du++) {
                            grid[u + du][v + dv] = 0;
                        }
                    }

                    result.add(buildGreedyQuad(f, slice, u, v, w, h, tileU, tileV,
                            a0 / 3.0f, a1 / 3.0f, a2 / 3.0f, a3 / 3.0f));
                }
            }
        }
        return result;
    }

    /**
     * Option 1: 9-Point Boundary-Clamped AO Greedy Meshing.
     * Packs the 4 corner AOs (c00, c10, c11, c01), 4 edge AOs (ev0, ev1, eu0, eu1),
     * and interior AO (aInt) into a single 19-bit integer stored in Vertex.ao.
     * The fragment shader evaluates the 1-block-clamped 3x3 bilinear AO grid using pure ALU instructions.
     */
    public static List<Quad> mergeVisualQuadsBoundaryAO(List<Quad> unitQuads, boolean fullGreedyGeometry) {
        if (unitQuads.isEmpty()) return unitQuads;
        List<Quad> result = new ArrayList<>(unitQuads.size() / 3 + 16);
        java.util.Map<Integer, List<Quad>> bySlice = new java.util.HashMap<>();

        for (Quad q : unitQuads) {
            int f;
            int slice;
            if (q.v0.ny > 0.5f)       { f = 0; slice = Math.round(q.v0.y); }
            else if (q.v0.ny < -0.5f) { f = 1; slice = Math.round(q.v0.y); }
            else if (q.v0.nx < -0.5f) { f = 2; slice = Math.round(q.v0.x); }
            else if (q.v0.nx > 0.5f)  { f = 3; slice = Math.round(q.v0.x); }
            else if (q.v0.nz < -0.5f) { f = 4; slice = Math.round(q.v0.z); }
            else                      { f = 5; slice = Math.round(q.v0.z); }

            int sliceKey = (f << 12) | (slice & 0xFFF);
            bySlice.computeIfAbsent(sliceKey, k -> new ArrayList<>()).add(q);
        }

        int[][] grid = new int[Chunk.WIDTH][Chunk.HEIGHT];
        int[][] aoGrid = new int[Chunk.WIDTH][Chunk.HEIGHT];
        for (var entry : bySlice.entrySet()) {
            int sliceKey = entry.getKey();
            int f = sliceKey >>> 12;
            int slice = sliceKey & 0xFFF;
            int uMax = Chunk.WIDTH;
            int vMax = (f <= 1) ? Chunk.DEPTH : Chunk.HEIGHT;

            for (int u = 0; u < uMax; u++) {
                java.util.Arrays.fill(grid[u], 0, vMax, 0);
                java.util.Arrays.fill(aoGrid[u], 0, vMax, 0);
            }

            for (Quad q : entry.getValue()) {
                int u0Idx, v0Idx;
                float uMin, vMin;
                float raw00, raw10, raw11, raw01;
                switch (f) {
                    case 0 -> {
                        u0Idx = Math.round(q.v0.x); v0Idx = Math.round(q.v0.z); uMin = q.v0.u; vMin = q.v0.v;
                        raw00 = q.v0.ao; raw10 = q.v3.ao; raw11 = q.v2.ao; raw01 = q.v1.ao;
                    }
                    case 1 -> {
                        u0Idx = Math.round(q.v0.x); v0Idx = Math.round(q.v0.z); uMin = q.v3.u; vMin = q.v3.v;
                        raw00 = q.v0.ao; raw10 = q.v1.ao; raw11 = q.v2.ao; raw01 = q.v3.ao;
                    }
                    case 2 -> {
                        u0Idx = Math.round(q.v3.z); v0Idx = Math.round(q.v3.y); uMin = q.v0.u; vMin = q.v0.v;
                        raw00 = q.v3.ao; raw10 = q.v0.ao; raw11 = q.v1.ao; raw01 = q.v2.ao;
                    }
                    case 3 -> {
                        u0Idx = Math.round(q.v0.z); v0Idx = Math.round(q.v0.y); uMin = q.v0.u; vMin = q.v0.v;
                        raw00 = q.v0.ao; raw10 = q.v3.ao; raw11 = q.v2.ao; raw01 = q.v1.ao;
                    }
                    case 4 -> {
                        u0Idx = Math.round(q.v0.x); v0Idx = Math.round(q.v0.y); uMin = q.v3.u; vMin = q.v3.v;
                        raw00 = q.v0.ao; raw10 = q.v3.ao; raw11 = q.v2.ao; raw01 = q.v1.ao;
                    }
                    default -> {
                        u0Idx = Math.round(q.v3.x); v0Idx = Math.round(q.v3.y); uMin = q.v3.u; vMin = q.v3.v;
                        raw00 = q.v3.ao; raw10 = q.v0.ao; raw11 = q.v1.ao; raw01 = q.v2.ao;
                    }
                }
                int tileU = Math.round(uMin * 64.0f) & 0x3F;
                int tileV = Math.round(vMin * 64.0f) & 0x3F;
                int c00 = Math.round(raw00 * 3.0f) & 0x3;
                int c10 = Math.round(raw10 * 3.0f) & 0x3;
                int c11 = Math.round(raw11 * 3.0f) & 0x3;
                int c01 = Math.round(raw01 * 3.0f) & 0x3;
                int aoBits = c00 | (c10 << 2) | (c11 << 4) | (c01 << 6);
                grid[u0Idx][v0Idx] = 1 + (tileU | (tileV << 6));
                aoGrid[u0Idx][v0Idx] = aoBits;
            }

            for (int v = 0; v < vMax; v++) {
                for (int u = 0; u < uMax; u++) {
                    int texKey = grid[u][v];
                    if (texKey == 0) continue;

                    int packedTex = texKey - 1;
                    int tileU = packedTex & 0x3F;
                    int tileV = (packedTex >>> 6) & 0x3F;

                    int ao0 = aoGrid[u][v];
                    int c00 = ao0 & 3;
                    int c10 = (ao0 >>> 2) & 3;
                    int c11 = (ao0 >>> 4) & 3;
                    int c01 = (ao0 >>> 6) & 3;

                    int w = 1;
                    int h = 1;
                    int ev0 = c10;
                    int ev1 = c11;
                    int eu0 = c01;
                    int eu1 = c11;
                    int aInt = 3;

                    if (fullGreedyGeometry) {
                        while (u + w < uMax && grid[u + w][v] == texKey) {
                            w++;
                        }
                        outerFull:
                        while (v + h < vMax) {
                            for (int du = 0; du < w; du++) {
                                if (grid[u + du][v + h] != texKey) break outerFull;
                            }
                            h++;
                        }
                        // Sample 4 outer corners of the merged W x H rectangle
                        int aoBR = aoGrid[u + w - 1][v];
                        int aoTR = aoGrid[u + w - 1][v + h - 1];
                        int aoTL = aoGrid[u][v + h - 1];
                        c10 = (aoBR >>> 2) & 3;
                        c11 = (aoTR >>> 4) & 3;
                        c01 = (aoTL >>> 6) & 3;

                        // Sample 4 edge AOs along the perimeter (minimum/crease AO along each edge)
                        if (w > 1) {
                            ev0 = 3;
                            ev1 = 3;
                            for (int du = 0; du < w - 1; du++) {
                                int bEdge = (aoGrid[u + du][v] >>> 2) & 3;
                                int tEdge = (aoGrid[u + du][v + h - 1] >>> 4) & 3;
                                if (bEdge < ev0) ev0 = bEdge;
                                if (tEdge < ev1) ev1 = tEdge;
                            }
                        } else {
                            ev0 = c00;
                            ev1 = c01;
                        }
                        if (h > 1) {
                            eu0 = 3;
                            eu1 = 3;
                            for (int dv = 0; dv < h - 1; dv++) {
                                int lEdge = (aoGrid[u][v + dv] >>> 6) & 3;
                                int rEdge = (aoGrid[u + w - 1][v + dv] >>> 4) & 3;
                                if (lEdge < eu0) eu0 = lEdge;
                                if (rEdge < eu1) eu1 = rEdge;
                            }
                        } else {
                            eu0 = c00;
                            eu1 = c10;
                        }
                        aInt = (w > 1 && h > 1) ? 3 : ev1;
                    } else {
                        // Exact 9-Point Boundary-Clamped AO expansion
                        while (u + w < uMax && grid[u + w][v] == texKey) {
                            if (w >= 2 && (c10 != ev0 || c11 != ev1)) {
                                break; // previous block already closed the right corner
                            }
                            int nextAO = aoGrid[u + w][v];
                            int n00 = nextAO & 3;
                            int n10 = (nextAO >>> 2) & 3;
                            int n11 = (nextAO >>> 4) & 3;
                            int n01 = (nextAO >>> 6) & 3;
                            if (n00 != ev0 || n01 != ev1) {
                                break;
                            }
                            c10 = n10;
                            c11 = n11;
                            w++;
                        }

                        eu0 = c01;
                        aInt = ev1;
                        eu1 = c11;

                        outerExact:
                        while (v + h < vMax) {
                            if (c01 != eu0 || ev1 != aInt || c11 != eu1) {
                                break; // previous row already closed the top boundary
                            }
                            int newEv1 = -1;
                            int newC01 = 0;
                            int newC11 = 0;
                            for (int du = 0; du < w; du++) {
                                if (grid[u + du][v + h] != texKey) break outerExact;
                                int cellAO = aoGrid[u + du][v + h];
                                int n00 = cellAO & 3;
                                int n10 = (cellAO >>> 2) & 3;
                                int n11 = (cellAO >>> 4) & 3;
                                int n01 = (cellAO >>> 6) & 3;

                                int reqBottomLeft = (du == 0) ? eu0 : aInt;
                                int reqBottomRight = (du == w - 1) ? eu1 : aInt;
                                if (n00 != reqBottomLeft || n10 != reqBottomRight) {
                                    break outerExact;
                                }
                                if (w == 1) {
                                    newC01 = n01;
                                    newC11 = n11;
                                    newEv1 = n11;
                                } else {
                                    if (du == 0) {
                                        newC01 = n01;
                                        newEv1 = n11;
                                    } else if (du < w - 1) {
                                        if (n01 != newEv1 || n11 != newEv1) break outerExact;
                                    } else {
                                        if (n01 != newEv1) break outerExact;
                                        newC11 = n11;
                                    }
                                }
                            }
                            c01 = newC01;
                            c11 = newC11;
                            ev1 = newEv1;
                            h++;
                        }
                    }

                    for (int dv = 0; dv < h; dv++) {
                        for (int du = 0; du < w; du++) {
                            grid[u + du][v + dv] = 0;
                        }
                    }

                    int packedAO = (1 << 18)
                            | (c00 & 3)
                            | ((c10 & 3) << 2)
                            | ((c11 & 3) << 4)
                            | ((c01 & 3) << 6)
                            | ((ev0 & 3) << 8)
                            | ((ev1 & 3) << 10)
                            | ((eu0 & 3) << 12)
                            | ((eu1 & 3) << 14)
                            | ((aInt & 3) << 16);

                    result.add(buildGreedyQuadBoundaryAO(f, slice, u, v, w, h, tileU, tileV, (float) packedAO));
                }
            }
        }
        return result;
    }

    private static Quad buildGreedyQuadBoundaryAO(int f, int slice, int u, int v, int w, int h,
                                                  int tileU, int tileV, float packedAO) {
        float u0 = (float) ((tileU << 9) | (w << 15));
        float u1 = (float) (w | (tileU << 9) | (w << 15));
        float v0 = (float) ((tileV << 9) | (h << 15));
        float v1 = (float) (h | (tileV << 9) | (h << 15));

        return switch (f) {
            case 0 -> new Quad( // TOP (+Y)
                    new Vertex(u,     slice, v,     u0, v0, 0, 1, 0, packedAO),
                    new Vertex(u,     slice, v + h, u0, v1, 0, 1, 0, packedAO),
                    new Vertex(u + w, slice, v + h, u1, v1, 0, 1, 0, packedAO),
                    new Vertex(u + w, slice, v,     u1, v0, 0, 1, 0, packedAO)
            );
            case 1 -> new Quad( // BOTTOM (-Y)
                    new Vertex(u,     slice, v,     u0, v0, 0, -1, 0, packedAO),
                    new Vertex(u + w, slice, v,     u1, v0, 0, -1, 0, packedAO),
                    new Vertex(u + w, slice, v + h, u1, v1, 0, -1, 0, packedAO),
                    new Vertex(u,     slice, v + h, u0, v1, 0, -1, 0, packedAO)
            );
            case 2 -> new Quad( // WEST (-X)
                    new Vertex(slice, v,     u + w, u1, v0, -1, 0, 0, packedAO),
                    new Vertex(slice, v + h, u + w, u1, v1, -1, 0, 0, packedAO),
                    new Vertex(slice, v + h, u,     u0, v1, -1, 0, 0, packedAO),
                    new Vertex(slice, v,     u,     u0, v0, -1, 0, 0, packedAO)
            );
            case 3 -> new Quad( // EAST (+X)
                    new Vertex(slice, v,     u,     u0, v0, 1, 0, 0, packedAO),
                    new Vertex(slice, v + h, u,     u0, v1, 1, 0, 0, packedAO),
                    new Vertex(slice, v + h, u + w, u1, v1, 1, 0, 0, packedAO),
                    new Vertex(slice, v,     u + w, u1, v0, 1, 0, 0, packedAO)
            );
            case 4 -> new Quad( // NORTH (-Z)
                    new Vertex(u,     v,     slice, u0, v0, 0, 0, -1, packedAO),
                    new Vertex(u,     v + h, slice, u0, v1, 0, 0, -1, packedAO),
                    new Vertex(u + w, v + h, slice, u1, v1, 0, 0, -1, packedAO),
                    new Vertex(u + w, v,     slice, u1, v0, 0, 0, -1, packedAO)
            );
            default -> new Quad( // SOUTH (+Z)
                    new Vertex(u + w, v,     slice, u1, v0, 0, 0, 1, packedAO),
                    new Vertex(u + w, v + h, slice, u1, v1, 0, 0, 1, packedAO),
                    new Vertex(u,     v + h, slice, u0, v1, 0, 0, 1, packedAO),
                    new Vertex(u,     v,     slice, u0, v0, 0, 0, 1, packedAO)
            );
        };
    }

    private static Quad buildGreedyQuad(int f, int slice, int u, int v, int w, int h,
                                        int tileU, int tileV,
                                        float ao0, float ao1, float ao2, float ao3) {
        float baseU = tileU * 512.0f;
        float baseV = tileV * 512.0f;
        float eps = 0.0005f;
        float u0 = baseU + eps;
        float u1 = baseU + w - eps;
        float v0 = baseV + eps;
        float v1 = baseV + h - eps;

        return switch (f) {
            case 0 -> new Quad( // TOP (+Y)
                    new Vertex(u,     slice, v,     u0, v0, 0, 1, 0, ao0),
                    new Vertex(u,     slice, v + h, u0, v1, 0, 1, 0, ao1),
                    new Vertex(u + w, slice, v + h, u1, v1, 0, 1, 0, ao2),
                    new Vertex(u + w, slice, v,     u1, v0, 0, 1, 0, ao3)
            );
            case 1 -> new Quad( // BOTTOM (-Y)
                    new Vertex(u,     slice, v,     u0, v1, 0, -1, 0, ao0),
                    new Vertex(u + w, slice, v,     u1, v1, 0, -1, 0, ao1),
                    new Vertex(u + w, slice, v + h, u1, v0, 0, -1, 0, ao2),
                    new Vertex(u,     slice, v + h, u0, v0, 0, -1, 0, ao3)
            );
            case 2 -> new Quad( // WEST (-X)
                    new Vertex(slice, v,     u + w, u0, v0, -1, 0, 0, ao0),
                    new Vertex(slice, v + h, u + w, u0, v1, -1, 0, 0, ao1),
                    new Vertex(slice, v + h, u,     u1, v1, -1, 0, 0, ao2),
                    new Vertex(slice, v,     u,     u1, v0, -1, 0, 0, ao3)
            );
            case 3 -> new Quad( // EAST (+X)
                    new Vertex(slice, v,     u,     u0, v0, 1, 0, 0, ao0),
                    new Vertex(slice, v + h, u,     u0, v1, 1, 0, 0, ao1),
                    new Vertex(slice, v + h, u + w, u1, v1, 1, 0, 0, ao2),
                    new Vertex(slice, v,     u + w, u1, v0, 1, 0, 0, ao3)
            );
            case 4 -> new Quad( // NORTH (-Z)
                    new Vertex(u,     v,     slice, u1, v0, 0, 0, -1, ao0),
                    new Vertex(u,     v + h, slice, u1, v1, 0, 0, -1, ao1),
                    new Vertex(u + w, v + h, slice, u0, v1, 0, 0, -1, ao2),
                    new Vertex(u + w, v,     slice, u0, v0, 0, 0, -1, ao3)
            );
            default -> new Quad( // SOUTH (+Z)
                    new Vertex(u + w, v,     slice, u1, v0, 0, 0, 1, ao0),
                    new Vertex(u + w, v + h, slice, u1, v1, 0, 0, 1, ao1),
                    new Vertex(u,     v + h, slice, u0, v1, 0, 0, 1, ao2),
                    new Vertex(u,     v,     slice, u0, v0, 0, 0, 1, ao3)
            );
        };
    }
}
