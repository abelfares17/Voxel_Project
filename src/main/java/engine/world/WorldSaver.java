package engine.world;

import engine.player.Player;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

public class WorldSaver {

    private final Path saveDir;

    public WorldSaver(String worldName) {
        this.saveDir = Path.of("saves", worldName);
        try {
            Files.createDirectories(saveDir);
        } catch (IOException e) {
            throw new RuntimeException("Impossible de créer le dossier de sauvegarde", e);
        }
    }

    public void saveChunk(Chunk chunk) {
        ChunkPos pos = new ChunkPos(chunk.getChunkX(), chunk.getChunkZ());
        Path file = saveDir.resolve("chunk_" + pos.x() + "_" + pos.z() + ".bin");

        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            for (int x = 0; x < Chunk.WIDTH; x++) {
                for (int y = 0; y < Chunk.HEIGHT; y++) {
                    for (int z = 0; z < Chunk.DEPTH; z++) {
                        short id = chunk.getBlockId(x, y, z);
                        out.writeShort(id);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Erreur lors de la sauvegarde du chunk " + pos + " : " + e.getMessage());
        }
    }

    public Chunk loadChunk(int chunkX, int chunkZ) {
        Chunk chunk = new Chunk(chunkX, chunkZ);
        Path file = saveDir.resolve("chunk_" + chunkX + "_" + chunkZ + ".bin");

        if (!Files.exists(file)) return null;

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            for (int x = 0; x < Chunk.WIDTH; x++) {
                for (int y = 0; y < Chunk.HEIGHT; y++) {
                    for (int z = 0; z < Chunk.DEPTH; z++) {
                        short id = in.readShort();
                        chunk.setBlock(x, y, z, id);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Erreur lors du chargement du chunk (" + chunkX + ", " + chunkZ + ") : " + e.getMessage());
        }

        return chunk;
    }


    public void savePlayer(Player player) {
        Path file = saveDir.resolve("player.dat");
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file.toFile()))) {
            out.writeFloat(player.getX());
            out.writeFloat(player.getY());
            out.writeFloat(player.getZ());
            out.writeFloat(player.getYaw());
            out.writeFloat(player.getPitch());
        } catch (IOException e) {
            System.err.println("Erreur lors de la sauvegarde du joueur : " + e.getMessage());
        }
    }

    public void loadPlayer(Player player) {
        Path file = saveDir.resolve("player.dat");
        if (!Files.exists(file)) return;

        try (DataInputStream in = new DataInputStream(new FileInputStream(file.toFile()))) {
            float x = in.readFloat();
            float y = in.readFloat();
            float z = in.readFloat();
            float yaw = in.readFloat();
            float pitch = in.readFloat();

            player.setPosition(x, y, z);
            player.setYaw(yaw);
            player.setPitch(pitch);
        } catch (IOException e) {
            System.err.println("Erreur lors du chargement du joueur : " + e.getMessage());
        }
    }
}