// src/main/java/engine/render/MeshBuilder.java
package engine.render;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glGetError;
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



    // (Pas d'import OpenGL ici !)
    public static MeshData generateMeshData(Chunk chunk, World world) {
        List<Quad> quads = generateVisibleQuads(chunk, world);
        int vertexCount = quads.size() * 6;
        FloatBuffer fb = org.lwjgl.system.MemoryUtil.memAllocFloat(vertexCount * (POS_SIZE + TEX_SIZE + NOR_SIZE + AO_SIZE));
        for (Quad q : quads) {
            // Triangle 1: v0→v1→v2 (CCW when viewed from outside)
            putVertex(fb, q.v0);
            putVertex(fb, q.v1);
            putVertex(fb, q.v2);
            // Triangle 2: v0→v2→v3 (CCW when viewed from outside)
            putVertex(fb, q.v0);
            putVertex(fb, q.v2);
            putVertex(fb, q.v3);
        }
        fb.flip();
        //System.out.println("[MeshBuilder] End mesh for chunk: " + chunk.getChunkX() + "," + chunk.getChunkZ());
        return new MeshData(fb, vertexCount, chunk);
    }

    public static void buildChunkMesh(Chunk chunk, World world) {
        chunk.cleanup();
        List<Quad> quads = generateVisibleQuads(chunk, world);
        int vertexCount  = quads.size() * 6; // 2 triangles par quad

        FloatBuffer fb = MemoryUtil.memAllocFloat(vertexCount * (POS_SIZE + TEX_SIZE + NOR_SIZE + AO_SIZE));
        for (Quad q : quads) {
            // Triangle 1: v0→v1→v2 (CCW when viewed from outside)
            putVertex(fb, q.v0);
            putVertex(fb, q.v1);
            putVertex(fb, q.v2);
            // Triangle 2: v0→v2→v3 (CCW when viewed from outside)
            putVertex(fb, q.v0);
            putVertex(fb, q.v2);
            putVertex(fb, q.v3);
        }
        fb.flip();

        int vao = glGenVertexArrays();
        glBindVertexArray(vao);

        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);

        // Attrib position (location = 0)
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, POS_SIZE, GL_FLOAT, false, STRIDE, 0);

        // Attrib texcoord (location = 1)
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, TEX_SIZE, GL_FLOAT, false, STRIDE, POS_SIZE * FLOAT_SIZE);

        // Attrib normal (location = 2)
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, NOR_SIZE, GL_FLOAT, false, STRIDE, (POS_SIZE + TEX_SIZE) * FLOAT_SIZE);

        // Attrib AO (location = 3)
        glEnableVertexAttribArray(3);
        glVertexAttribPointer(3, AO_SIZE, GL_FLOAT, false, STRIDE, (POS_SIZE + TEX_SIZE + NOR_SIZE) * FLOAT_SIZE);

        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        MemoryUtil.memFree(fb);

        chunk.setMesh(vao, vbo, vertexCount);
        //System.out.println("Chunk ("+chunk.getChunkX()+","+chunk.getChunkZ()+"): quads=" + quads.size());
    }

    public static void renderChunk(Chunk chunk) {
        if (!chunk.isReady()) {
            //System.out.println("[MeshBuilder/RenderChunk] Chunk " + chunk.getChunkX() + " , " + chunk.getChunkZ() + ", PAS prêt ! vao=" + chunk.getVao() + " vbo=" + chunk.getVbo() + " count=" + chunk.getVertexCount() + " dirty=" + chunk.isMeshDirty());
            return;
        }
        glBindVertexArray(chunk.getVao());
        //System.out.println("VAO: " + chunk.getVao() + " VBO: " + chunk.getVbo() + " count: " + chunk.getVertexCount());
        glDrawArrays(GL_TRIANGLES, 0, chunk.getVertexCount());
        //glDrawArrays(GL_POINTS, 0, chunk.getVertexCount());
        glBindVertexArray(0);
        int err = glGetError();
        if (err != GL_NO_ERROR) System.out.println("OpenGL ERROR: " + err);
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
}