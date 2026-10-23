// src/main/java/engine/render/MeshData.java
package engine.render;

import engine.world.Chunk;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicInteger;

public class MeshData {
    public final FloatBuffer vertexBuffer;
    public final int vertexCount;
    public final Chunk chunk;
    public final MeshBounds bounds;
    public final IntBuffer indexBuffer;
    public final int indexCount;
    public static AtomicInteger COUNT = new AtomicInteger(0);

    public MeshData(FloatBuffer buffer, int count, Chunk chunk) {
        this(buffer, count, chunk, null);
    }

    public MeshData(FloatBuffer buffer, int count, Chunk chunk, MeshBounds bounds) {
        this(buffer, count, chunk, bounds, null);
    }

    public MeshData(FloatBuffer buffer, int count, Chunk chunk, MeshBounds bounds, IntBuffer indices) {
        this.vertexBuffer = buffer;
        this.vertexCount = count;
        this.chunk = chunk;
        this.bounds = bounds;
        this.indexBuffer = indices;
        this.indexCount = indices == null ? 0 : indices.remaining();
        COUNT.incrementAndGet();
    }

    public void free() {
        if (indexBuffer != null) org.lwjgl.system.MemoryUtil.memFree(indexBuffer);
        if (vertexBuffer != null && vertexBuffer.isDirect()) {
            org.lwjgl.system.MemoryUtil.memFree(vertexBuffer);
            COUNT.decrementAndGet();
        }
    }
}
