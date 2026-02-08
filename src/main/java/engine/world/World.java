package engine.world;

import java.util.*;

import engine.render.MeshWorkerManager;
import engine.render.Shader;
import engine.time.TimeManager;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static org.lwjgl.opengl.GL30.*;

/**
 * Représente un monde entier, contenant des chunks.
 * Player management has been moved to Game.java.
 */
public class World {
    private final Map<ChunkPos, Chunk> chunks = new HashMap<>();
    private final long seed;
    private final WorldStorage storage;  // Replaced WorldSaver with WorldStorage
    private final int renderDistance;
    private final WorldGenerator worldGenerator;
    private final Mode mode;
    private final MeshWorkerManager meshManager;
    private TimeManager timeManager;
    private final String worldName;

    public enum Mode {
        FLAT, PERLIN
    }

    public World(String worldName, long seed, Mode mode, int renderDistance, TimeManager timeManager) {
        this.worldName = worldName;
        this.timeManager = timeManager;
        this.mode = mode;
        this.renderDistance = renderDistance;
        this.seed = seed;
        this.storage = new WorldStorage(worldName, seed, mode.toString());
        this.worldGenerator = new WorldGenerator(seed, this);
        this.meshManager = new MeshWorkerManager(this, Runtime.getRuntime().availableProcessors());

        // Note: No preload here - caller must call startAsyncChunkGeneration()
    }



    public TimeManager getTimeManager() {
        return timeManager;
    }

    public Vector3f getSunDir() {
        return timeManager.getSunData().sunDir();
    }

    /**
     * Preload initial chunks around a position.
     *
     * @param centerChunkX Center chunk X coordinate
     * @param centerChunkZ Center chunk Z coordinate
     */
    public void preloadInitialChunks(int centerChunkX, int centerChunkZ) {
        // Generate all chunks in the grid
        for (int dx = -renderDistance; dx <= renderDistance; dx++) {
            for (int dz = -renderDistance; dz <= renderDistance; dz++) {
                ChunkPos pos = new ChunkPos(centerChunkX + dx, centerChunkZ + dz);
                if (!chunks.containsKey(pos)) {
                    Chunk chunk = generateChunk(pos);
                    chunks.put(pos, chunk);
                    meshManager.queueChunk(chunk);
                    markNeighborsDirty(pos.x(), pos.z());
                }
            }
        }
        System.out.println("All chunks generated");

        // Wait for all meshes to be uploaded to GPU
        while (!allVisibleChunksReady(centerChunkX, centerChunkZ, renderDistance)) {
            meshManager.processMeshes();
            meshManager.uploadMeshesIfAny();
            try {
                Thread.sleep(2);
            } catch (InterruptedException ignored) {}
        }
    }

    /**
     * Check if all visible chunks around a position are ready.
     *
     * @param centerChunkX Center chunk X
     * @param centerChunkZ Center chunk Z
     * @param radius       Chunk radius
     * @return True if all chunks are ready
     */
    public boolean allVisibleChunksReady(int centerChunkX, int centerChunkZ, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Chunk chunk = chunks.get(new ChunkPos(centerChunkX + dx, centerChunkZ + dz));
                if (chunk == null || !chunk.isReady()) return false;
            }
        }
        return true;
    }

    /**
     * Start async chunk generation for initial world load.
     * Non-blocking - call processChunkLoading() each frame to continue.
     *
     * @param centerChunkX Center X coordinate
     * @param centerChunkZ Center Z coordinate
     */
    public void startAsyncChunkGeneration(int centerChunkX, int centerChunkZ) {
        for (int dx = -renderDistance; dx <= renderDistance; dx++) {
            for (int dz = -renderDistance; dz <= renderDistance; dz++) {
                ChunkPos pos = new ChunkPos(centerChunkX + dx, centerChunkZ + dz);
                if (!chunks.containsKey(pos)) {
                    Chunk chunk = generateChunk(pos);
                    chunks.put(pos, chunk);
                    meshManager.queueChunk(chunk);
                    markNeighborsDirty(pos.x(), pos.z());
                }
            }
        }
        System.out.println("All chunks queued for generation");
    }

    /**
     * Process chunk loading for one frame.
     * Call this repeatedly until isInitialLoadComplete() returns true.
     */
    public void processChunkLoading() {
        meshManager.processMeshes();
        meshManager.uploadMeshesIfAny();
    }

    /**
     * Check if initial chunks are ready for rendering.
     */
    public boolean isInitialLoadComplete() {
        return allVisibleChunksReady(0, 0, renderDistance);
    }

    /**
     * Get loading progress (0.0 to 1.0).
     */
    public float getLoadingProgress() {
        int totalChunks = (2 * renderDistance + 1) * (2 * renderDistance + 1);
        int readyChunks = 0;

        for (int dx = -renderDistance; dx <= renderDistance; dx++) {
            for (int dz = -renderDistance; dz <= renderDistance; dz++) {
                Chunk chunk = chunks.get(new ChunkPos(dx, dz));
                if (chunk != null && chunk.isReady()) {
                    readyChunks++;
                }
            }
        }

        return (float) readyChunks / totalChunks;
    }

    public int getSpawnY(){
        Chunk chunk = chunks.get(new ChunkPos(0, 0));
        for (int y = Chunk.HEIGHT; y >= 0; y--) {
            if (chunk.getBlockId(0, y, 0) != 0){
                return y;
            }
        }
        return 256;
    }

    public MeshWorkerManager getMeshManager() {
        return meshManager;
    }

    public Chunk getChunk(int chunkX, int chunkZ) {
        return chunks.get(new ChunkPos(chunkX, chunkZ));
    }

    public int getChunkCount() {
        return chunks.size();
    }

    public Block getBlockAt(int x, int y, int z) {
        int chunkX = Math.floorDiv(x, Chunk.WIDTH);
        int chunkZ = Math.floorDiv(z, Chunk.DEPTH);
        int localX = Math.floorMod(x, Chunk.WIDTH);
        int localZ = Math.floorMod(z, Chunk.DEPTH);

        Chunk chunk = getChunk(chunkX, chunkZ);
        if (chunk == null) return BlockRegistry.getBlock((short) 0);
        return chunk.getBlock(localX, y, localZ);
    }

    public void setBlockAt(int x, int y, int z, short id) {
        int chunkX = Math.floorDiv(x, Chunk.WIDTH);
        int chunkZ = Math.floorDiv(z, Chunk.DEPTH);
        int localX = Math.floorMod(x, Chunk.WIDTH);
        int localZ = Math.floorMod(z, Chunk.DEPTH);

        Chunk chunk = getChunk(chunkX, chunkZ);
        if (chunk != null) {
            chunk.setBlock(localX, y, localZ, id);
            meshManager.queueChunk(chunk);
        }
    }

    public Chunk generateChunk(ChunkPos pos) {
        // Try to load from disk first
        Chunk loaded = storage.loadChunk(pos.x(), pos.z());
        if (loaded != null) {
            // Chunk was loaded from disk, it's clean
            return loaded;
        }

        // If not on disk, generate new chunk
        Chunk generated = new Chunk(pos.x(), pos.z());
        if (mode == Mode.FLAT) {
            this.worldGenerator.generateFlat(generated);
        } else {
            this.worldGenerator.generatePerlin(generated);
        }

        // Mark as needing save (newly generated)
        generated.setDataDirty(true);

        // Ajout : Marquer les voisins pour remeshing
        markNeighborsDirty(pos.x(), pos.z());
        return generated;
    }

    private void markNeighborsDirty(int chunkX, int chunkZ) {
        int[][] dirs = {{-1,0},{1,0},{0,-1},{0,1}};
        for (int[] dir : dirs) {
            int nx = chunkX + dir[0];
            int nz = chunkZ + dir[1];
            Chunk neighbor = getChunk(nx, nz);
            if (neighbor != null) {
                meshManager.queueChunk(neighbor);
            }
        }
    }

    public void save() {
        // Save all dirty chunks (modified data)
        for (Chunk chunk : chunks.values()) {
            if (chunk.isDataDirty()) {
                storage.saveChunkAsync(chunk);
            }
        }

        // Save metadata
        storage.saveMetadata();

        // Player saving is handled separately by Game.java
    }

    public WorldStorage getStorage() {
        return storage;
    }

    public Map<ChunkPos, Chunk> getAllChunks() {
        return chunks;
    }

    /**
     * Update world (process meshes, update chunks).
     * Player update is handled separately in Game.java.
     *
     * @param playerChunkX Player chunk X coordinate
     * @param playerChunkZ Player chunk Z coordinate
     */
    public void update(int playerChunkX, int playerChunkZ) {
        updateVisibleChunks(playerChunkX, playerChunkZ, renderDistance);
        unloadFarChunks(playerChunkX, playerChunkZ, renderDistance + 2);
        meshManager.processMeshes();
    }

    /**
     * Get all visible chunks around a position.
     *
     * @param centerChunkX Center chunk X
     * @param centerChunkZ Center chunk Z
     * @param radius       Chunk radius
     * @return List of visible chunks
     */
    public List<Chunk> getVisibleChunks(int centerChunkX, int centerChunkZ, int radius) {
        List<Chunk> list = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos pos = new ChunkPos(centerChunkX + dx, centerChunkZ + dz);
                Chunk chunk = chunks.get(pos);
                if (chunk != null) {
                    list.add(chunk);
                }
            }
        }
        return list;
    }

    /**
     * Update visible chunks around player position.
     *
     * @param centerChunkX Center chunk X
     * @param centerChunkZ Center chunk Z
     * @param radius       Chunk radius
     */
    private void updateVisibleChunks(int centerChunkX, int centerChunkZ, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos pos = new ChunkPos(centerChunkX + dx, centerChunkZ + dz);
                if (!chunks.containsKey(pos)) {
                    Chunk chunk = generateChunk(pos);
                    chunks.put(pos, chunk);
                    meshManager.queueChunk(chunk);
                }
            }
        }
    }

    /**
     * Unload chunks that are too far from player.
     *
     * @param centerChunkX Center chunk X
     * @param centerChunkZ Center chunk Z
     * @param maxDistance  Maximum distance in chunks
     */
    private void unloadFarChunks(int centerChunkX, int centerChunkZ, int maxDistance) {
        chunks.entrySet().removeIf(entry -> {
            ChunkPos pos = entry.getKey();
            int dx = Math.abs(pos.x() - centerChunkX);
            int dz = Math.abs(pos.z() - centerChunkZ);
            boolean toRemove = dx > maxDistance || dz > maxDistance;
            if (toRemove) {
                entry.getValue().cleanup();
            }
            return toRemove;
        });
    }

    public void renderDepth(Shader depthShader) {
        for (Map.Entry<ChunkPos, Chunk> entry : chunks.entrySet()) {
            ChunkPos pos = entry.getKey();
            Chunk chunk = entry.getValue();

            if (!chunk.isReady()) continue; // le mesh n’est pas encore prêt
            int vao = chunk.getVao();
            int vertexCount = chunk.getVertexCount();
            if (vao == -1 || vertexCount == 0) continue;

            int worldX = pos.getWorldX();
            int worldZ = pos.getWorldZ();
            Matrix4f modelMatrix = new Matrix4f().translate(worldX, 0, worldZ);
            depthShader.setMat4("modelMatrix", modelMatrix);

            glBindVertexArray(vao);
            glDrawArrays(GL_TRIANGLES, 0, vertexCount);
        }

        glBindVertexArray(0);
    }

    private Chunk getOrGenerateChunk(int x, int z) {
        ChunkPos pos = new ChunkPos(x, z);
        return chunks.computeIfAbsent(pos, this::generateChunk);
    }

    public long getSeed(){
        return seed;
    }

    public String getWorldName() {
        return worldName;
    }

    public int getRenderDistance(){
        return renderDistance;
    }

    /**
     * Load an existing world from disk.
     * @param worldName World name
     * @param renderDistance Render distance
     * @param timeManager Time manager
     * @return Loaded world, or null if world doesn't exist
     */
    public static World loadFromSave(String worldName, int renderDistance, TimeManager timeManager) {
        WorldMetadata metadata = WorldStorage.loadMetadata(worldName);
        if (metadata == null) {
            System.err.println("World not found: " + worldName);
            return null;
        }

        // Parse generation mode
        Mode mode;
        try {
            mode = Mode.valueOf(metadata.mode);
        } catch (IllegalArgumentException e) {
            System.err.println("Unknown generation mode: " + metadata.mode);
            mode = Mode.PERLIN;
        }

        // Create world with loaded metadata
        World world = new World(worldName, metadata.seed, mode, renderDistance, timeManager);

        System.out.println("Loaded world: " + worldName +
                          " (seed=" + metadata.seed + ", mode=" + mode + ")");

        return world;
    }

    /**
     * Cleanup world resources.
     * CRITICAL: Must be called when world is no longer needed to stop background threads.
     */
    public void cleanup() {
        // Shutdown mesh generation threads
        meshManager.shutdown();

        // Shutdown storage executor
        storage.shutdown();
    }

}
