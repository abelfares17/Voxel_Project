package engine.world;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Compresses and decompresses chunk data using GZIP.
 *
 * Format:
 * - Header (12 bytes):
 *   [0-1]   Magic number: 0x56 0x58 ("VX" for Voxel)
 *   [2]     Version: 1
 *   [3]     Compression type: 1 = GZIP
 *   [4-7]   Uncompressed size (int, big-endian)
 *   [8-11]  Compressed size (int, big-endian)
 * - Body: GZIP compressed chunk data
 *
 * Expected compression ratio: 70-90% (131 KB → ~13-40 KB)
 */
public class ChunkCompressor {
    private static final byte MAGIC_1 = 0x56; // 'V'
    private static final byte MAGIC_2 = 0x58; // 'X'
    private static final byte VERSION = 1;
    private static final byte COMPRESSION_GZIP = 1;

    /**
     * Compress a chunk to a byte array with header.
     * @param chunk Chunk to compress
     * @return Compressed data with header
     * @throws IOException if compression fails
     */
    public static byte[] compress(Chunk chunk) throws IOException {
        // 1. Extract raw block data
        ByteArrayOutputStream rawData = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(rawData);

        for (int x = 0; x < Chunk.WIDTH; x++) {
            for (int y = 0; y < Chunk.HEIGHT; y++) {
                for (int z = 0; z < Chunk.DEPTH; z++) {
                    short id = chunk.getBlockId(x, y, z);
                    out.writeShort(id);
                }
            }
        }
        out.close();
        byte[] uncompressed = rawData.toByteArray();

        // 2. Compress with GZIP
        ByteArrayOutputStream compressedData = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressedData)) {
            gzip.write(uncompressed);
        }
        byte[] compressed = compressedData.toByteArray();

        // 3. Build header
        ByteBuffer header = ByteBuffer.allocate(12);
        header.order(ByteOrder.BIG_ENDIAN);
        header.put(MAGIC_1);
        header.put(MAGIC_2);
        header.put(VERSION);
        header.put(COMPRESSION_GZIP);
        header.putInt(uncompressed.length);  // 131072
        header.putInt(compressed.length);

        // 4. Combine header + compressed data
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(header.array());
        result.write(compressed);

        return result.toByteArray();
    }

    /**
     * Decompress a byte array to a Chunk.
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param data Compressed data with header
     * @return Decompressed chunk
     * @throws IOException if decompression fails or format is invalid
     */
    public static Chunk decompress(int chunkX, int chunkZ, byte[] data) throws IOException {
        // 1. Parse header
        ByteBuffer header = ByteBuffer.wrap(data, 0, 12);
        header.order(ByteOrder.BIG_ENDIAN);

        byte magic1 = header.get();
        byte magic2 = header.get();
        if (magic1 != MAGIC_1 || magic2 != MAGIC_2) {
            throw new IOException("Invalid chunk file: bad magic number");
        }

        byte version = header.get();
        byte compressionType = header.get();
        int uncompressedSize = header.getInt();
        int compressedSize = header.getInt();

        if (version != VERSION) {
            throw new IOException("Unsupported chunk version: " + version);
        }

        // 2. Decompress data
        ByteArrayInputStream compressedInput = new ByteArrayInputStream(data, 12, compressedSize);
        byte[] uncompressed = new byte[uncompressedSize];

        try (GZIPInputStream gzip = new GZIPInputStream(compressedInput)) {
            int bytesRead = gzip.read(uncompressed);
            if (bytesRead != uncompressedSize) {
                throw new IOException("Decompression size mismatch");
            }
        }

        // 3. Reconstruct chunk
        Chunk chunk = new Chunk(chunkX, chunkZ);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(uncompressed));

        for (int x = 0; x < Chunk.WIDTH; x++) {
            for (int y = 0; y < Chunk.HEIGHT; y++) {
                for (int z = 0; z < Chunk.DEPTH; z++) {
                    short id = in.readShort();
                    chunk.setBlock(x, y, z, id);
                }
            }
        }

        // Mark as clean (just loaded)
        chunk.setDataDirty(false);

        return chunk;
    }
}
