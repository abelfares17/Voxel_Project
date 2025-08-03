// src/main/java/engine/render/MeshBuilder.java
package engine.render;

import engine.world.Block;
import engine.world.Chunk;
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
    private static final int STRIDE     = (POS_SIZE + TEX_SIZE + NOR_SIZE) * FLOAT_SIZE;

    public static void buildChunkMesh(Chunk chunk) {
        chunk.cleanup();
        List<Quad> quads = generateVisibleQuads(chunk);
        int vertexCount  = quads.size() * 6; // 2 triangles par quad

        FloatBuffer fb = MemoryUtil.memAllocFloat(vertexCount * (POS_SIZE + TEX_SIZE + NOR_SIZE));
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
        /*
        // *** Écriture dans un fichier texte ***
        if (chunk.getChunkX() == -1 && chunk.getChunkZ() == -1) { // ou un autre chunk de référence
            try (FileWriter writer = new FileWriter("chunk-1_-1_vertices.txt")) {
                int stride = POS_SIZE + TEX_SIZE + NOR_SIZE;
                for (int i = 0; i < vertexCount; i++) {
                    float x = fb.get(i * stride);
                    float y = fb.get(i * stride + 1);
                    float z = fb.get(i * stride + 2);
                    float u = fb.get(i * stride + 3);
                    float v = fb.get(i * stride + 4);
                    float nx = fb.get(i * stride + 5);
                    float ny = fb.get(i * stride + 6);
                    float nz = fb.get(i * stride + 7);
                    writer.write(String.format(
                            "V%d: pos=(%.2f, %.2f, %.2f) uv=(%.2f, %.2f) norm=(%.2f, %.2f, %.2f)%n",
                            i, x, y, z, u, v, nx, ny, nz
                    ));
                }
                //System.out.println("[DEBUG] Vertices du chunk (0,0) écrits dans chunk0_0_vertices.txt");
            } catch (IOException e) {
                e.printStackTrace();
            }
            fb.rewind(); // Important de revenir au début après la lecture
        }
         */

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
            System.out.println("Chunk PAS prêt ! vao=" + chunk.getVao() + " vbo=" + chunk.getVbo() + " count=" + chunk.getVertexCount() + " dirty=" + chunk.isMeshDirty());
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
    }

    public record Vertex(
            float x, float y, float z,
            float u, float v,
            float nx, float ny, float nz
    ) {}

    public record Quad(
            Vertex v0, Vertex v1, Vertex v2, Vertex v3
    ) {}

    private static List<Quad> generateVisibleQuads(Chunk chunk) {
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

                        boolean visible = !chunk.inBounds(nx, ny, nz)
                                || chunk.getBlockId(nx, ny, nz) == 0;
                        if (!visible) continue;

                        float[] norm = NORMALS[f];
                        TextureCoords tex = switch (f) {
                            case 0 -> block.getTopTexture();
                            case 1 -> block.getBottomTexture();
                            default -> block.getSideTexture();
                        };
                        //System.out.printf("bx=%d by=%d bz=%d f=%d\n", bx, by, bz, f);
                        mesh.add(generateQuad(bx, by, bz, f, tex, norm));
                    }
                }
            }
        }
        return mesh;
    }

    private static Quad generateQuad(int bx, int by, int bz, int f,
                                     TextureCoords tex, float[] norm) {
        float u0 = tex.getUMin(), v0 = tex.getVMin();
        float u1 = tex.getUMax(), v1 = tex.getVMax();
        float nx = norm[0], ny = norm[1], nz = norm[2];

        // Note : l’ordre (x,y,z) <-> (u,v) doit être cohérent POUR CHAQUE FACE
        return switch (f) {
            case 0 -> new Quad( // TOP (+Y) : X/Z -> U/V
                    new Vertex(bx,   by+1, bz,   u0, v0, nx, ny, nz), // (0,0)
                    new Vertex(bx+1, by+1, bz,   u1, v0, nx, ny, nz), // (1,0)
                    new Vertex(bx+1, by+1, bz+1, u1, v1, nx, ny, nz), // (1,1)
                    new Vertex(bx,   by+1, bz+1, u0, v1, nx, ny, nz)  // (0,1)
            );
            case 1 -> new Quad( // BOTTOM (-Y) : X/Z -> U/V
                    new Vertex(bx,   by, bz,     u0, v0, nx, ny, nz), // (0,0)
                    new Vertex(bx+1, by, bz,     u1, v0, nx, ny, nz), // (1,0)
                    new Vertex(bx+1, by, bz+1,   u1, v1, nx, ny, nz), // (1,1)
                    new Vertex(bx,   by, bz+1,   u0, v1, nx, ny, nz)  // (0,1)
            );
            case 2 -> new Quad( // WEST (-X) : Y/Z -> U/V
                    new Vertex(bx, by,   bz,     u0, v0, nx, ny, nz), // (0,0)
                    new Vertex(bx, by,   bz+1,   u1, v0, nx, ny, nz), // (1,0)
                    new Vertex(bx, by+1, bz+1,   u1, v1, nx, ny, nz), // (1,1)
                    new Vertex(bx, by+1, bz,     u0, v1, nx, ny, nz)  // (0,1)
            );
            case 3 -> new Quad( // EAST (+X) : Y/Z -> U/V
                    new Vertex(bx+1, by,   bz,     u0, v0, nx, ny, nz), // (0,0)
                    new Vertex(bx+1, by,   bz+1,   u1, v0, nx, ny, nz), // (1,0)
                    new Vertex(bx+1, by+1, bz+1,   u1, v1, nx, ny, nz), // (1,1)
                    new Vertex(bx+1, by+1, bz,     u0, v1, nx, ny, nz)  // (0,1)
            );
            case 4 -> new Quad( // NORTH (-Z) : X/Y -> U/V
                    new Vertex(bx,   by,   bz,     u0, v0, nx, ny, nz), // (0,0)
                    new Vertex(bx+1, by,   bz,     u1, v0, nx, ny, nz), // (1,0)
                    new Vertex(bx+1, by+1, bz,     u1, v1, nx, ny, nz), // (1,1)
                    new Vertex(bx,   by+1, bz,     u0, v1, nx, ny, nz)  // (0,1)
            );
            case 5 -> new Quad( // SOUTH (+Z) : X/Y -> U/V
                    new Vertex(bx,   by,   bz+1,   u0, v0, nx, ny, nz), // (0,0)
                    new Vertex(bx+1, by,   bz+1,   u1, v0, nx, ny, nz), // (1,0)
                    new Vertex(bx+1, by+1, bz+1,   u1, v1, nx, ny, nz), // (1,1)
                    new Vertex(bx,   by+1, bz+1,   u0, v1, nx, ny, nz)  // (0,1)
            );
            default -> throw new IllegalArgumentException("Face inconnue: " + f);
        };
    }
}