package engine.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * World metadata stored in JSON format (world.json).
 * Contains world information like name, seed, creation date, player data, etc.
 */
public class WorldMetadata {
    // Version for backward compatibility
    public int version = 2;
    // Optional in v1 saves; simulation days, no wall-clock/offline progression.
    public Double worldDays;

    // Core world info
    public String name;              // "World 1"
    public long seed;                // Perlin noise seed
    public String mode;              // "FLAT" or "PERLIN"
    public int renderDistance;       // From GameConfig
    public String gameVersion;       // e.g., "0.1.0"

    // Timestamps
    public String creationDate;      // ISO-8601 format
    public String lastPlayed;        // ISO-8601 format

    // Player data (basic for now)
    public static class PlayerData {
        public float x, y, z;
        public float yaw, pitch;
        public boolean spectatorMode;
    }
    public PlayerData playerData;

    // Statistics (optional, for UI)
    public static class Statistics {
        public int totalPlayTime;    // seconds
        public int chunksGenerated;
        public int blocksPlaced;
        public int blocksDestroyed;
    }
    public Statistics statistics;

    /**
     * Save metadata to world.json in the given world directory.
     * @param worldDir Path to world directory
     * @throws IOException if save fails
     */
    public void save(Path worldDir) throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(this);
        Path file = worldDir.resolve("world.json");
        Files.writeString(file, json);
    }

    /**
     * Load metadata from world.json in the given world directory.
     * @param worldDir Path to world directory
     * @return WorldMetadata object, or null if file doesn't exist
     * @throws IOException if load fails
     */
    public static WorldMetadata load(Path worldDir) throws IOException {
        Path file = worldDir.resolve("world.json");
        if (!Files.exists(file)) return null;
        String json = Files.readString(file);
        Gson gson = new Gson();
        return gson.fromJson(json, WorldMetadata.class);
    }
}
