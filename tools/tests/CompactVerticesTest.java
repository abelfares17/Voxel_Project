package engine.render;

import java.nio.FloatBuffer;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/** CPU format boundary/error tests; native allocation is freed on every successful pack. */
public final class CompactVerticesTest {
    public static void main(String[] args) {
        FloatBuffer input = FloatBuffer.allocate(20);
        input.position(2);
        input.put(new float[] {16, 256, 0, .1234567f, .9876543f, -1, 0, 1, 1f / 3f});
        input.flip().position(2);
        ByteBuffer packed = MeshVertexLayout.pack(input, 1);
        try {
            check(input.position() == 2 && input.limit() == 11, "cursor changed");
            check(packed.remaining() == 24, "stride");
            check(Short.toUnsignedInt(packed.getShort(2)) == 256, "top position truncated");
            check(packed.get(6) == -1 && packed.get(8) == 1, "signed normal");
            check(Float.floatToRawIntBits(packed.getFloat(12)) == Float.floatToRawIntBits(input.get(5)), "UV rounded");
            check(Float.floatToRawIntBits(packed.getFloat(20)) == Float.floatToRawIntBits(input.get(10)), "AO rounded");
        } finally { MemoryUtil.memFree(packed); }
        invalid(() -> MeshVertexLayout.pack(input, -1));
        invalid(() -> MeshVertexLayout.pack(input, Integer.MAX_VALUE));
        input.put(2, .5f); invalid(() -> MeshVertexLayout.pack(input, 1));
        input.put(2, 65536); invalid(() -> MeshVertexLayout.pack(input, 1));
        input.put(2, Float.NaN); invalid(() -> MeshVertexLayout.pack(input, 1));
        input.put(2, -0f); invalid(() -> MeshVertexLayout.pack(input, 1));
        input.put(2, 16); input.put(7, .5f); invalid(() -> MeshVertexLayout.pack(input, 1));
        ByteBuffer empty = MeshVertexLayout.pack(input, 0);
        check(empty.remaining() == 0, "empty buffer"); MemoryUtil.memFree(empty);
        System.out.println("PASS compact vertices: cursor, stride, Y=256, signed normal, exact UV/AO, invalid values, empty");
    }
    static void check(boolean b, String message) { if (!b) throw new AssertionError(message); }
    static void invalid(Runnable test) {
        try { test.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid input accepted");
    }
}
