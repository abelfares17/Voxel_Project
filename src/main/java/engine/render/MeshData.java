// src/main/java/engine/render/MeshData.java
package engine.render;

import engine.world.Chunk;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicInteger;

public class MeshData {
    public final FloatBuffer vertexBuffer;
    public final int vertexCount;
    public final Chunk chunk;
    public static AtomicInteger COUNT = new AtomicInteger(0);

    public MeshData(FloatBuffer buffer, int count, Chunk chunk) {
        this.vertexBuffer = buffer;
        this.vertexCount = count;
        this.chunk = chunk;
        COUNT.incrementAndGet();
    }

    public void free() {
        if (vertexBuffer != null && vertexBuffer.isDirect()) {
            org.lwjgl.system.MemoryUtil.memFree(vertexBuffer);
            COUNT.decrementAndGet();
        }
    }
}