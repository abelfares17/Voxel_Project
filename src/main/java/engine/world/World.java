package engine.world;

import java.util.HashMap;
import java.util.Map;

import engine.input.Input;
import engine.player.Player;
import engine.render.MeshWorkerManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Représente un monde entier, contenant des chunks et un joueur.
 */
public class World {
    private final Map<ChunkPos, Chunk> chunks = new HashMap<>();
    private final Player player;
    private final long seed;
    private final WorldSaver saver;
    private final int renderDistance;
    private final WorldGenerator worldGenerator;
    private final Mode mode;
    private final MeshWorkerManager meshManager;

    public enum Mode {
        FLAT, PERLIN
    }

    public World(long seed, int renderDistance) {
        this.mode = Mode.PERLIN;
        this.renderDistance = renderDistance;
        this.seed = seed;
        this.saver = new WorldSaver("world_" + seed);
        this.worldGenerator = new WorldGenerator(seed, this);
        this.meshManager = new MeshWorkerManager(this, Runtime.getRuntime().availableProcessors());
        // Initialisation "temporaire" d'un joueur fictif à 0,0 pour le preload
        Player fakePlayer = new Player(0.5f, 100, 0.5f); // y arbitraire ici

        // -> PRÉCHARGEMENT COMPLET :
        preloadInitialChunksAndWait(fakePlayer); // <-- à la place de preloadInitialChunks()

        // Maintenant le joueur peut être correctement positionné
        this.player = new Player(0.5f, getSpawnY() + 2f, 0.5f); // Position par défaut
        saver.loadPlayer(player); // Tente de charger la position
    }

    public void preloadInitialChunksAndWait(Player player) {
        // Génère tous les chunks dans la grille
        for (int dx = -renderDistance; dx <= renderDistance; dx++) {
            for (int dz = -renderDistance; dz <= renderDistance; dz++) {
                ChunkPos pos = new ChunkPos(dx, dz);
                if (!chunks.containsKey(pos)) {
                    Chunk chunk = generateChunk(pos);
                    chunks.put(pos, chunk);           // D’ABORD dans la map !
                    meshManager.queueChunk(chunk);    // Ensuite seulement, queue
                    markNeighborsDirty(pos.x(), pos.z());
                }
            }
        }
        System.out.println("Tous les chunks sont générés");

        // Attend que tous les meshes soient uploadés sur le GPU
        while (!allVisibleChunksReady(player, renderDistance)) {
            meshManager.processMeshes();
            meshManager.uploadMeshesIfAny(); // upload dans le thread principal !
            try {
                Thread.sleep(2); // évite 100% CPU (2 ms de pause)
            } catch (InterruptedException ignored) {}
            //System.out.println("Toujours bloqué en préchargement");
        }
    }

    public boolean allVisibleChunksReady(Player player, int radius) {
        int cx = player.getChunkX();
        int cz = player.getChunkZ();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Chunk chunk = chunks.get(new ChunkPos(cx + dx, cz + dz));
                if (chunk == null || !chunk.isReady()) return false;
            }
        }
        return true;
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
        Chunk loaded = saver.loadChunk(pos.x(), pos.z());
        if (loaded != null) return loaded;

        Chunk generated = new Chunk(pos.x(), pos.z());
        if (mode == Mode.FLAT) {
            this.worldGenerator.generateFlat(generated);
        } else {
            this.worldGenerator.generatePerlin(generated);
        }
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
        for (Chunk chunk : chunks.values()) {
            saver.saveChunk(chunk);
        }
        saver.savePlayer(player);
    }

    public Player getPlayer() {
        return player;
    }

    public Map<ChunkPos, Chunk> getAllChunks() {
        return chunks;
    }

    public void update(Input input) {
        Chunk currentChunk = getChunk(player.getChunkX(), player.getChunkZ());
        if (currentChunk != null) {
            player.update(currentChunk, input, this);
        }
        updateVisibleChunks(player, renderDistance); // charger les nouveaux chunks si besoin
        unloadFarChunks(player, renderDistance+2);     // Supprimer les chunk très lointains
        meshManager.processMeshes();
    }

    public List<Chunk> getVisibleChunks(Player player, int radius) {
        List<Chunk> list = new ArrayList<>();
        int cx = player.getChunkX();
        int cz = player.getChunkZ();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos pos = new ChunkPos(cx + dx, cz + dz);
                Chunk chunk = chunks.get(pos);
                if (chunk != null) {
                    list.add(chunk);
                }
            }
        }
        return list;
    }

    private void updateVisibleChunks(Player player, int radius) {
        int cx = player.getChunkX();
        int cz = player.getChunkZ();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos pos = new ChunkPos(cx + dx, cz + dz);
                if (!chunks.containsKey(pos)) {
                    Chunk chunk = generateChunk(pos);
                    chunks.put(pos, chunk);
                    meshManager.queueChunk(chunk);
                }
            }
        }
    }

    private void unloadFarChunks(Player player, int maxDistance) {
        int cx = player.getChunkX();
        int cz = player.getChunkZ();

        chunks.entrySet().removeIf(entry -> {
            ChunkPos pos = entry.getKey();
            int dx = Math.abs(pos.x() - cx);
            int dz = Math.abs(pos.z() - cz);
            boolean toRemove = dx > maxDistance || dz > maxDistance;
            if (toRemove) {
                entry.getValue().cleanup();
            }
            return toRemove;
        });
    }

    private Chunk getOrGenerateChunk(int x, int z) {
        ChunkPos pos = new ChunkPos(x, z);
        return chunks.computeIfAbsent(pos, this::generateChunk);
    }

    public long getSeed(){
        return seed;
    }

    public int getRenderDistance(){
        return renderDistance;
    }

}
