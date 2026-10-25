package engine.render;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;

/** Two explicit layouts of the same nine shader inputs; no quantization of UV or AO. */
public final class MeshVertexLayout {
    public static final int COMPACT_STRIDE = 24;
    private MeshVertexLayout() {}

    public static ByteBuffer pack(FloatBuffer source, int count) {
        if (count < 0 || (long) count * 9 > source.remaining()) throw new IllegalArgumentException("Vertex count");
        ByteBuffer result = MemoryUtil.memAlloc(Math.multiplyExact(count, COMPACT_STRIDE));
        try {
            for (int v = 0; v < count; v++) {
                int i = source.position() + v * 9;
                for (int c = 0; c < 3; c++) {
                    float value = source.get(i + c);
                    if (!Float.isFinite(value) || value < 0 || value > 65535 || value != (int) value
                            || Float.floatToRawIntBits(value) == 0x80000000) throw new IllegalArgumentException("Non-integral position");
                    result.putShort((short) (int) value);
                }
                for (int c = 5; c < 8; c++) {
                    float value = source.get(i + c);
                    if (!Float.isFinite(value) || value < -1 || value > 1 || value != (int) value
                            || Float.floatToRawIntBits(value) == 0x80000000) throw new IllegalArgumentException("Non-axis normal");
                    result.put((byte) value);
                }
                result.put((byte) 0).put((byte) 0).put((byte) 0);
                result.putFloat(source.get(i + 3)).putFloat(source.get(i + 4)).putFloat(source.get(i + 8));
            }
            return result.flip();
        } catch (RuntimeException | Error failure) {
            MemoryUtil.memFree(result);
            throw failure;
        }
    }

    /** Destination VAO and ARRAY_BUFFER must already be bound. */
    public static void upload(MeshData data) {
        boolean compact = data.packedVertices != null;
        if (compact) glBufferData(GL_ARRAY_BUFFER, data.packedVertices, GL_STATIC_DRAW);
        else glBufferData(GL_ARRAY_BUFFER, data.vertexBuffer, GL_STATIC_DRAW);
        int stride = data.vertexStride();
        for (int a = 0; a < 4; a++) glEnableVertexAttribArray(a);
        glVertexAttribPointer(0, 3, compact ? GL_UNSIGNED_SHORT : GL_FLOAT, false, stride, 0L);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 12L);
        glVertexAttribPointer(2, 3, compact ? GL_BYTE : GL_FLOAT, false, stride, compact ? 6L : 20L);
        glVertexAttribPointer(3, 1, GL_FLOAT, false, stride, compact ? 20L : 32L);
    }
}
