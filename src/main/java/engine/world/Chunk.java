// src/main/java/engine/world/Chunk.java
package engine.world;

import engine.render.MeshBuilder;
import java.util.Arrays;

/**
 * Représente un chunk de taille fixe WIDTH×HEIGHT×DEPTH.
 * Gère le stockage des block IDs, ainsi que le VAO/VBO et le dirty flag
 * pour le mesh construit par MeshBuilder.
 */
public class Chunk {
    public static final int WIDTH  = 16;
    public static final int HEIGHT = 256;
    public static final int DEPTH  = 16;

    private final int chunkX, chunkZ;
    private final short[][][] blocks = new short[WIDTH][HEIGHT][DEPTH];

    // VAO/VBO + vertexCount pour le mesh interleavé
    private int vao;
    private int vbo;
    private int vertexCount;

    // true si le mesh doit être reconstruit avant d'être rendu
    private boolean meshDirty = true;

    /**
     * Construit un chunk aux coordonnées de chunk (chunkX, chunkZ).
     * Les données de blocs doivent être remplies par WorldGenerator ou chargées via WorldSaver.
     */
    public Chunk(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        // Par défaut, on considère que tous les blocks valent 0 = air.
        for (short[][] column : blocks) {
            for (short[] layer : column) {
                Arrays.fill(layer, (short) 0);
            }
        }
        this.vao = 0;
        this.vbo = 0;
        this.vertexCount = 0;
        this.meshDirty = true;
    }

    /** Retourne l’ID du chunk en X */
    public int getChunkX() {
        return chunkX;
    }

    /** Retourne l’ID du chunk en Z */
    public int getChunkZ() {
        return chunkZ;
    }

    // Retourne la première couche solide de haut en bas, -1 si rien trouvé
    public int findSurfaceY(int x, int z) {
        for (int y = Chunk.HEIGHT - 1; y >= 0; y--) {
            short id = getBlockId(x, y, z);
            if (id != 0) return y;
        }
        return 4 ;
    }

    /**
     * Indique si les coordonnées (x,y,z) sont dans les bornes de ce chunk.
     */
    public boolean inBounds(int x, int y, int z) {
        return x >= 0 && x < WIDTH
                && y >= 0 && y < HEIGHT
                && z >= 0 && z < DEPTH;
    }

    /**
     * Retourne l’ID (short) du bloc à la position locale (x,y,z) dans ce chunk.
     * Si hors-bounds, renvoie 0 (= air).
     */
    public short getBlockId(int x, int y, int z) {
        if (!inBounds(x, y, z)) return 0;
        return blocks[x][y][z];
    }

    /**
     * Retourne l’objet Block correspondant à l’ID stocké à (x,y,z).
     * Si hors-bounds, retourne BlockRegistry.getBlock((short)0) (= air).
     */
    public Block getBlock(int x, int y, int z) {
        short id = getBlockId(x, y, z);
        return BlockRegistry.getBlock(id);
    }

    /**
     * Modifie l’ID du bloc à la position locale (x,y,z).
     * Remarque : appelle setMeshDirty(true) pour forcer la reconstruction du mesh.
     */
    public void setBlock(int x, int y, int z, short id) {
        if (!inBounds(x, y, z)) return;
        blocks[x][y][z] = id;
        meshDirty = true;
    }

    /** Indique si le mesh doit être reconstruit avant rendu. */
    public boolean isMeshDirty() {
        return meshDirty;
    }

    /** Indique si un VAO/VBO a déjà été généré et peut être rendu. */
    public boolean isReady() {
        return (vao != 0) && (vbo != 0) && (vertexCount > 0) && (!meshDirty);
    }

    /**
     * Stocke le VAO, le VBO et le nombre de vertices après appel à MeshBuilder.buildChunkMesh(...)
     * Set meshDirty à false pour indiquer que le mesh est à jour.
     */
    public void setMesh(int vao, int vbo, int vertexCount) {
        this.vao = vao;
        this.vbo = vbo;
        this.vertexCount = vertexCount;
        this.meshDirty = false;
        //System.out.println("setMesh: vao=" + vao + " vbo=" + vbo + " count=" + vertexCount);
    }

    public int getVao() {
        return vao;
    }

    public int getVbo() {
        return vbo;
    }

    public int getVertexCount() {
        return vertexCount;
    }

    public void cleanup() {
        if (vao != 0) {
            org.lwjgl.opengl.GL30.glDeleteVertexArrays(vao);
            vao = 0;
        }
        if (vbo != 0) {
            org.lwjgl.opengl.GL15.glDeleteBuffers(vbo);
            vbo = 0;
        }
        vertexCount = 0;
        meshDirty = true;
    }
}