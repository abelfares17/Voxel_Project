// src/main/java/engine/render/MeshBuilder.java
package engine.render;

import engine.world.Block;
import engine.world.Chunk;
import engine.world.World;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Construit et rend, en Core Profile 3.2, les meshes interleavés de chaque chunk.
 * Chaque face visible est convertie en deux triangles.
 */
public class MeshBuilder {
    private static final int FLOAT_SIZE = Float.BYTES;
    private static final int POS_SIZE   = 3; // x,y,z
    private static final int TEX_SIZE   = 2; // u,v
    private static final int NOR_SIZE   = 3; // nx,ny,nz
    private static final int LIGHT_SIZE = 1;
    private static final int AO_SIZE = 1;
    public static final int STRIDE = (POS_SIZE + TEX_SIZE + NOR_SIZE + LIGHT_SIZE + AO_SIZE) * FLOAT_SIZE;

    private static final float RAY_STEP = 0.5f;   // plus sûr que 1.0
    private static final float RAY_BIAS = 0.001f; // évite de ré-entrer dans la face



    // (Pas d'import OpenGL ici !)
    public static MeshData generateMeshData(Chunk chunk, World world) {
        //System.out.println("[MeshBuilder] Start mesh for chunk: " + chunk.getChunkX() + "," + chunk.getChunkZ());
        boolean[][][] shadowMask = ShadowCalculator.computeShadowMask(chunk, world, world.getSunDir());
        chunk.setShadowMask(shadowMask);
        List<Quad> quads = generateVisibleQuads(chunk, world);
        int vertexCount = quads.size() * 6;
        FloatBuffer fb = org.lwjgl.system.MemoryUtil.memAllocFloat(vertexCount *  (POS_SIZE + TEX_SIZE + NOR_SIZE + LIGHT_SIZE + AO_SIZE));
        for (Quad q : quads) {
            putVertex(fb, q.v0);
            putVertex(fb, q.v1);
            putVertex(fb, q.v2);
            putVertex(fb, q.v2);
            putVertex(fb, q.v3);
            putVertex(fb, q.v0);
        }
        fb.flip();
        //System.out.println("[MeshBuilder] End mesh for chunk: " + chunk.getChunkX() + "," + chunk.getChunkZ());
        return new MeshData(fb, vertexCount, chunk);
    }

    public static void buildChunkMesh(Chunk chunk, World world) {
        chunk.cleanup();
        List<Quad> quads = generateVisibleQuads(chunk, world);
        int vertexCount  = quads.size() * 6; // 2 triangles par quad

        FloatBuffer fb = MemoryUtil.memAllocFloat(vertexCount * (POS_SIZE + TEX_SIZE + NOR_SIZE + LIGHT_SIZE + AO_SIZE));
        for (Quad q : quads) {
            // Triangle 1 (v0,v1,v2)
            putVertex(fb, q.v0);
            putVertex(fb, q.v1);
            putVertex(fb, q.v2);
            // Triangle 2 (v2,v3,v0)
            putVertex(fb, q.v2);
            putVertex(fb, q.v3);
            putVertex(fb, q.v0);
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

        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        MemoryUtil.memFree(fb);

        chunk.setMesh(vao, vbo, vertexCount);
        //System.out.println("Chunk ("+chunk.getChunkX()+","+chunk.getChunkZ()+"): quads=" + quads.size());
    }

    public static void renderChunk(Chunk chunk) {
        if (!chunk.isReady()) {
            System.out.println("[MeshBuilder/RenderChunk] Chunk " + chunk.getChunkX() + " , " + chunk.getChunkZ() + ", PAS prêt ! vao=" + chunk.getVao() + " vbo=" + chunk.getVbo() + " count=" + chunk.getVertexCount() + " dirty=" + chunk.isMeshDirty());
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
        fb.put(v.light);
        fb.put(v.ao);
    }

    public record Vertex(
            float x, float y, float z,
            float u, float v,
            float nx, float ny, float nz,
            float light,
            float ao
    ) {}

    public record Quad(
            Vertex v0, Vertex v1, Vertex v2, Vertex v3
    ) {}

    private static int getFacingFaceFromSunDir(Vector3f sunDir) {
        // Exemple : choisir la composante dominante du vecteur
        if (Math.abs(sunDir.x) > Math.abs(sunDir.z)) {
            return sunDir.x > 0 ? 3 : 2; // EAST ou WEST
        } else {
            return sunDir.z > 0 ? 5 : 4; // SOUTH ou NORTH
        }
    }

    private static final int[][] FACE_NORMALS = {
            { 0,  1,  0}, // TOP
            { 0, -1,  0}, // BOTTOM
            {-1,  0,  0}, // WEST
            { 1,  0,  0}, // EAST
            { 0,  0, -1}, // NORTH
            { 0,  0,  1}  // SOUTH
    };

    private static float computeShadowFactorFace(int wx, int wy, int wz, int face, World world) {
        Vector3f s = world.getSunDir(); // doit être normalisé
        int[] n = FACE_NORMALS[face];

        // Départ : au centre **de la face**, légèrement en dehors et un micro décalage à l’opposé du rayon
        float x = wx + 0.5f + 0.5f * n[0] - s.x * RAY_BIAS;
        float y = wy + 0.5f + 0.5f * n[1] - s.y * RAY_BIAS;
        float z = wz + 0.5f + 0.5f * n[2] - s.z * RAY_BIAS;

        // Si le ciel est totalement bouché au-dessus de la colonne, on peut court-circuiter (optionnel)
        for (int yy = wy + 1; yy < Chunk.HEIGHT; yy++) {
            if (world.getBlockAt(wx, yy, wz).getId() != 0) return 0.0f;
        }

        // Raymarch discret sans round() : on avance de petits pas dans la direction du soleil
        for (int i = 0; i < 512; i++) {
            x += s.x * RAY_STEP;
            y += s.y * RAY_STEP;
            z += s.z * RAY_STEP;

            int cx = (int)Math.floor(x);
            int cy = (int)Math.floor(y);
            int cz = (int)Math.floor(z);

            if (cy < 0 || cy >= Chunk.HEIGHT) break; // hors monde → pas de bloc sur le chemin

            if (world.getBlockAt(cx, cy, cz).getId() != 0) {
                return 0.0f; // un bloc masque la face
            }
        }
        return 1.0f; // plein soleil vu depuis **cette face**
    }

    private static List<Quad> generateVisibleQuads(Chunk chunk, World world) {
        List<Quad> mesh = new ArrayList<>();
        boolean[][][] shadowMask = chunk.getShadowMask();
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

                        float light = computeShadowFactorFace(wx, wy, wz, f, world);

                        float[] norm = NORMALS[f];
                        TextureCoords tex = switch (f) {
                            case 0 -> block.getTopTexture();
                            case 1 -> block.getBottomTexture();
                            default -> block.getSideTexture();
                        };
                        int chunkWorldX = chunk.getChunkX() * Chunk.WIDTH;
                        int chunkWorldZ = chunk.getChunkZ() * Chunk.DEPTH;
                        mesh.add(generateQuad(bx, by, bz, f, tex, norm, light, world, wx, wy, wz));
                    }
                }
            }
        }
        return mesh;
    }

    private static float computeAO(World world, int wx, int wy, int wz, int ox, int oy, int oz, int face) {
        // ox, oy, oz sont le décalage local du sommet dans la face
        // face = index 0..5 pour TOP, BOTTOM, WEST, EAST, NORTH, SOUTH
        int side1x = ox != 0 ? ox : 0;
        int side1y = oy != 0 ? oy : 0;
        int side1z = oz != 0 ? oz : 0;

        // Les deux blocs latéraux
        boolean side1 = world.getBlockAt(wx + (face == 2 || face == 3 ? 0 : ox), wy, wz + (face == 4 || face == 5 ? 0 : oz)).getId() != 0;
        boolean side2 = world.getBlockAt(wx, wy + (face == 0 || face == 1 ? 0 : oy), wz + (face == 4 || face == 5 ? 0 : oz)).getId() != 0;

        // Bloc diagonal
        boolean corner = world.getBlockAt(wx + ox, wy + oy, wz + oz).getId() != 0;

        if (side1 && side2) return 0f;
        int blocks = (side1 ? 1 : 0) + (side2 ? 1 : 0) + (corner ? 1 : 0);
        return 1f - blocks * 0.33f; // 0 = noir, 1 = plein
    }

    private static Quad generateQuad(int bx, int by, int bz, int f,
                                     TextureCoords tex, float[] norm, float light, World world, int wx, int wy, int wz) {
        float u0 = tex.getUMin(), v0 = tex.getVMin();
        float u1 = tex.getUMax(), v1 = tex.getVMax();
        float nx = norm[0], ny = norm[1], nz = norm[2];

        // Note : l’ordre (x,y,z) <-> (u,v) doit être cohérent POUR CHAQUE FACE
        return switch (f) {
            case 0 -> new Quad( // TOP (+Y) : X/Z -> U/V
                    new Vertex(bx,   by+1, bz,     u0, v0, nx, ny, nz, light, computeAO(world, wx,   wy+1, wz,   0, 0, 0, 0)),
                    new Vertex(bx+1, by+1, bz,  u1, v0, nx, ny, nz, light, computeAO(world, wx+1, wy+1, wz,   1, 0, 0, 0)),
                    new Vertex(bx+1, by+1, bz+1, u1, v1, nx, ny, nz, light, computeAO(world, wx+1, wy+1, wz+1, 1, 0, 1, 0)),
                    new Vertex(bx,   by+1, bz+1, u0, v1, nx, ny, nz, light, computeAO(world, wx,   wy+1, wz+1, 0, 0, 1, 0))
            );

            case 1 -> new Quad( // BOTTOM (-Y) : X/Z -> U/V
                    new Vertex(bx,   by,   bz, u0, v0, nx, ny, nz, light, computeAO(world, wx,   wy,   wz,   0, 0, 0, 1)),
                    new Vertex(bx+1, by,   bz, u1, v0, nx, ny, nz, light, computeAO(world, wx+1, wy,   wz,   1, 0, 0, 1)),
                    new Vertex(bx+1, by,   bz+1, u1, v1, nx, ny, nz, light, computeAO(world, wx+1, wy,   wz+1, 1, 0, 1, 1)),
                    new Vertex(bx,   by,   bz+1, u0, v1, nx, ny, nz, light, computeAO(world, wx,   wy,   wz+1, 0, 0, 1, 1))
            );

            case 2 -> new Quad( // WEST (-X) : Y/Z -> U/V
                    new Vertex(bx,   by,   bz, u0, v0, nx, ny, nz, light, computeAO(world, wx,   wy,   wz,   0, 0, 0, 2)),
                    new Vertex(bx,   by,   bz+1, u1, v0, nx, ny, nz, light, computeAO(world, wx,   wy,   wz+1, 0, 0, 1, 2)),
                    new Vertex(bx,   by+1, bz+1, u1, v1, nx, ny, nz, light, computeAO(world, wx,   wy+1, wz+1, 0, 1, 1, 2)),
                    new Vertex(bx,   by+1, bz, u0, v1, nx, ny, nz, light, computeAO(world, wx,   wy+1, wz,   0, 1, 0, 2))
            );

            case 3 -> new Quad( // EAST (+X) : Y/Z -> U/V
                    new Vertex(bx+1, by,   bz, u0, v0, nx, ny, nz, light, computeAO(world, wx+1, wy,   wz,   1, 0, 0, 3)),
                    new Vertex(bx+1, by,   bz+1, u1, v0, nx, ny, nz, light, computeAO(world, wx+1, wy,   wz+1, 1, 0, 1, 3)),
                    new Vertex(bx+1, by+1, bz+1, u1, v1, nx, ny, nz, light, computeAO(world, wx+1, wy+1, wz+1, 1, 1, 1, 3)),
                    new Vertex(bx+1, by+1, bz, u0, v1, nx, ny, nz, light, computeAO(world, wx+1, wy+1, wz,   1, 1, 0, 3))
            );

            case 4 -> new Quad( // NORTH (-Z) : X/Y -> U/V
                    new Vertex(bx,   by,   bz, u0, v0, nx, ny, nz, light, computeAO(world, wx,   wy,   wz,   0, 0, 0, 4)),
                    new Vertex(bx+1, by,   bz, u1, v0, nx, ny, nz, light, computeAO(world, wx+1, wy,   wz,   1, 0, 0, 4)),
                    new Vertex(bx+1, by+1, bz, u1, v1, nx, ny, nz, light, computeAO(world, wx+1, wy+1, wz,   1, 1, 0, 4)),
                    new Vertex(bx,   by+1, bz, u0, v1, nx, ny, nz, light, computeAO(world, wx,   wy+1, wz,   0, 1, 0, 4))
            );

            case 5 -> new Quad( // SOUTH (+Z) : X/Y -> U/V
                    new Vertex(bx,   by,   bz+1, u0, v0, nx, ny, nz, light, computeAO(world, wx,   wy,   wz+1, 0, 0, 1, 5)),
                    new Vertex(bx+1, by,   bz+1, u1, v0, nx, ny, nz, light, computeAO(world, wx+1, wy,   wz+1, 1, 0, 1, 5)),
                    new Vertex(bx+1, by+1, bz+1, u1, v1, nx, ny, nz, light, computeAO(world, wx+1, wy+1, wz+1, 1, 1, 1, 5)),
                    new Vertex(bx,   by+1, bz+1, u0, v1, nx, ny, nz, light, computeAO(world, wx,   wy+1, wz+1, 0, 1, 1, 5))
            );
            default -> throw new IllegalArgumentException("Face inconnue: " + f);
        };
    }
}