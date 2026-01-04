package engine.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Utility class for generating unique world names.
 * Avoids collisions by scanning existing world folders.
 */
public class WorldNaming {
    private static final Path SAVES_DIR = Path.of("saves");

    /**
     * Generate a unique world name like "World 1", "World 2", etc.
     * Scans existing world folders to avoid collisions.
     * @return Unique world name
     */
    public static String generateWorldName() {
        Set<String> existingNames = getExistingWorldNames();

        int num = 1;
        while (existingNames.contains("World " + num)) {
            num++;
        }

        return "World " + num;
    }

    /**
     * Get all existing world names from saves/ directory.
     * @return Set of existing world names
     */
    private static Set<String> getExistingWorldNames() {
        Set<String> names = new HashSet<>();

        if (!Files.exists(SAVES_DIR)) {
            return names;
        }

        try (Stream<Path> paths = Files.list(SAVES_DIR)) {
            paths.filter(Files::isDirectory)
                 .forEach(path -> {
                     Path metadataFile = path.resolve("world.json");
                     if (Files.exists(metadataFile)) {
                         try {
                             WorldMetadata meta = WorldMetadata.load(path);
                             if (meta != null) {
                                 names.add(meta.name);
                             }
                         } catch (IOException e) {
                             // Ignore invalid metadata files
                         }
                     }
                     // Also add folder name as fallback
                     names.add(path.getFileName().toString());
                 });
        } catch (IOException e) {
            System.err.println("Error scanning world names: " + e.getMessage());
        }

        return names;
    }

    /**
     * Convert world name to folder name (safe for filesystem).
     * Example: "World 1" → "World_1"
     * @param worldName World name
     * @return Folder-safe name
     */
    public static String toFolderName(String worldName) {
        return worldName.replace(" ", "_");
    }
}
