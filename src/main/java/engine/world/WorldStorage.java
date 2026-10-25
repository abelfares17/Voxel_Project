package engine.world;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import engine.entity.Player;

/**
 * Modern world storage system replacing WorldSaver.
 * Features:
 * - GZIP compression for chunks (70-90% size reduction)
 * - JSON metadata (world.json)
 * - Async compression (non-blocking saves)
 * - Dirty chunk tracking (only save modified chunks)
 */
public class WorldStorage {
    private final Path worldDir;
    private final Path chunksDir;
    private final WorldMetadata metadata;
    private final ExecutorService compressionExecutor;

    /**
     * Create or load world storage.
     * @param worldName Human-readable world name ("World 1")
     * @param seed World generation seed
     * @param mode Generation mode ("FLAT" or "PERLIN")
     */
    public WorldStorage(String worldName, long seed, String mode) {
        String folderName = WorldNaming.toFolderName(worldName);
        this.worldDir = Path.of("saves", folderName);
        this.chunksDir = worldDir.resolve("chunks");

        try {
            Files.createDirectories(chunksDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create world directory", e);
        }

        // Initialize or load metadata
        WorldMetadata loaded;
        try {
            loaded = WorldMetadata.load(worldDir);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot load metadata; refusing to overwrite " + worldDir, e);
        }
        this.metadata = loaded == null ? new WorldMetadata() : loaded;
        if (loaded == null) {
            metadata.name = worldName;
            metadata.seed = seed;
            metadata.mode = mode;
            metadata.creationDate = LocalDateTime.now().toString();
            metadata.gameVersion = "0.1.0";
            metadata.statistics = new WorldMetadata.Statistics();
            metadata.worldDays = engine.time.TimeManager.DEFAULT_DAYS;
        }
        metadata.version = 2;
        if (metadata.worldDays == null || !Double.isFinite(metadata.worldDays) || metadata.worldDays < 0) {
            System.err.println("[TIME] Missing/invalid saved worldDays in " + worldDir + "; using 08:00.");
            metadata.worldDays = engine.time.TimeManager.DEFAULT_DAYS;
        }

        // Thread pool for async compression (2 threads to avoid overload)
        this.compressionExecutor = Executors.newFixedThreadPool(2);
    }

    /**
     * Save a single chunk (compressed, async).
     * Only call this for dirty chunks.
     * @param chunk Chunk to save
     */
    public Double getWorldDays() { return metadata.worldDays; }

    public void setWorldDays(double value) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid worldDays");
        metadata.worldDays = value;
    }

    public void saveChunkAsync(Chunk chunk) {
        compressionExecutor.submit(() -> {
            try {
                saveChunk(chunk);
            } catch (IOException e) {
                System.err.println("Failed to save chunk (" +
                    chunk.getChunkX() + ", " + chunk.getChunkZ() + "): " +
                    e.getMessage());
            }
        });
    }

    /**
     * Save a single chunk (compressed, synchronous).
     * @param chunk Chunk to save
     * @throws IOException if save fails
     */
    public void saveChunk(Chunk chunk) throws IOException {
        int cx = chunk.getChunkX();
        int cz = chunk.getChunkZ();

        // Compress chunk data
        byte[] compressed = ChunkCompressor.compress(chunk);

        // Write to file
        Path file = chunksDir.resolve("chunk_" + cx + "_" + cz + ".dat");
        Files.write(file, compressed);

        // Mark as clean
        chunk.setDataDirty(false);
    }

    /**
     * Load a single chunk from disk.
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return Loaded chunk, or null if file doesn't exist
     */
    public Chunk loadChunk(int chunkX, int chunkZ) {
        Path file = chunksDir.resolve("chunk_" + chunkX + "_" + chunkZ + ".dat");

        if (!Files.exists(file)) {
            return null;
        }

        try {
            byte[] data = Files.readAllBytes(file);
            return ChunkCompressor.decompress(chunkX, chunkZ, data);
        } catch (IOException e) {
            //System.err.println("Failed to load chunk (" + chunkX + ", " + chunkZ + "): " + e.getMessage());
            return null;
        }
    }

    /**
     * Save player data.
     * @param player Player to save
     */
    public void savePlayer(Player player) {
        Path file = worldDir.resolve("player.dat");
        try (DataOutputStream out = new DataOutputStream(
                new FileOutputStream(file.toFile()))) {
            out.writeFloat(player.getX());
            out.writeFloat(player.getY());
            out.writeFloat(player.getZ());
            out.writeFloat(player.getYaw());
            out.writeFloat(player.getPitch());
        } catch (IOException e) {
            System.err.println("Failed to save player: " + e.getMessage());
        }

        // Also update metadata
        metadata.playerData = new WorldMetadata.PlayerData();
        metadata.playerData.x = player.getX();
        metadata.playerData.y = player.getY();
        metadata.playerData.z = player.getZ();
        metadata.playerData.yaw = player.getYaw();
        metadata.playerData.pitch = player.getPitch();
        metadata.playerData.spectatorMode = player.isSpectatorMode();
    }

    /**
     * Load player data.
     * @param player Player to load into
     */
    public void loadPlayer(Player player) {
        Path file = worldDir.resolve("player.dat");
        if (!Files.exists(file)) return;

        try (DataInputStream in = new DataInputStream(
                new FileInputStream(file.toFile()))) {
            float x = in.readFloat();
            float y = in.readFloat();
            float z = in.readFloat();
            float yaw = in.readFloat();
            float pitch = in.readFloat();

            player.setPosition(x, y, z);
            player.setYaw(yaw);
            player.setPitch(pitch);
        } catch (IOException e) {
            System.err.println("Failed to load player: " + e.getMessage());
        }
    }

    /**
     * Save metadata to world.json.
     */
    public void saveMetadata() {
        metadata.lastPlayed = LocalDateTime.now().toString();
        try {
            metadata.save(worldDir);
        } catch (IOException e) {
            System.err.println("Failed to save metadata: " + e.getMessage());
        }
    }

    /**
     * Load metadata from world.json.
     * @param worldName World name
     * @return WorldMetadata, or null if not found
     */
    public static WorldMetadata loadMetadata(String worldName) {
        String folderName = WorldNaming.toFolderName(worldName);
        Path worldDir = Path.of("saves", folderName);
        try {
            return WorldMetadata.load(worldDir);
        } catch (IOException e) {
            System.err.println("Failed to load metadata: " + e.getMessage());
            return null;
        }
    }

    /**
     * Cleanup - wait for async saves to complete.
     */
    public void shutdown() {
        compressionExecutor.shutdown();
        try {
            compressionExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            compressionExecutor.shutdownNow();
        }
    }

    // Getters
    public WorldMetadata getMetadata() { return metadata; }
    public Path getWorldDir() { return worldDir; }
}
