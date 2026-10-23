package engine.render;

import java.nio.FloatBuffer;
import java.util.Random;
import engine.world.Chunk;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Standalone CPU tests; throws on failure, independent of -ea/JUnit/GL. */
public final class MeshBoundsTest {
    public static void main(String[] args) {
        FloatBuffer buffer = FloatBuffer.allocate(32);
        buffer.position(3);
        vertex(buffer, -2, 256, 7);
        vertex(buffer, 16, 256, -9);
        buffer.limit(buffer.position()).position(3);
        MeshBounds bounds = MeshBounds.fromVertices(buffer, 2);
        require(bounds.equals(new MeshBounds(-2, 256, -9, 16, 256, 7)), "XYZ/planar bounds");
        require(buffer.position() == 3 && buffer.limit() == 21, "buffer cursor changed");
        require(MeshBounds.fromVertices(buffer, 0) == null, "empty mesh");
        invalid(() -> MeshBounds.fromVertices(buffer, -1));
        invalid(() -> MeshBounds.fromVertices(buffer, 3));
        invalid(() -> MeshBounds.fromVertices(buffer, Integer.MAX_VALUE));
        invalid(() -> new MeshBounds(1, 0, 0, 0, 1, 1));
        buffer.put(3, Float.NaN);
        invalid(() -> MeshBounds.fromVertices(buffer, 2));
        buffer.put(3, Float.POSITIVE_INFINITY);
        invalid(() -> MeshBounds.fromVertices(buffer, 2));

        Chunk chunk = new Chunk(-2, 3);
        chunk.setMesh(0, 0, 2, bounds); // zero handles keep this CPU-only
        require(chunk.getMeshBounds() == bounds, "bounds not installed");
        chunk.setBlock(1, 200, 1, (short) 1);
        require(chunk.getMeshBounds() == bounds && chunk.isMeshDirty(), "block edit changed uploaded bounds");
        chunk.setMesh(0, 0, 2);
        require(chunk.getMeshBounds() == null, "legacy upload retained stale bounds");
        chunk.setMesh(0, 0, 0, bounds);
        require(chunk.getMeshBounds() == null, "empty upload retained bounds");
        chunk.setMesh(0, 0, 2, bounds);
        chunk.cleanup();
        require(chunk.getMeshBounds() == null, "cleanup retained bounds");

        var top = new MeshBounds(0, 256, 0, 16, 256, 16);
        require(top.intersects(new FrustumIntersection(new Matrix4f().ortho(-20, 20, 255, 257, -20, 20)), 0, 0),
                "top face rejected");
        require(new MeshBounds(0, 0, 0, 16, 1, 16).intersects(new FrustumIntersection(new Matrix4f()), -17, 0),
                "negative origin boundary rejected");

        Random random = new Random(125676);
        int rejected = 0;
        for (int i = 0; i < 20000; i++) {
            Vector3f eye = new Vector3f(random.nextFloat() * 600 - 300, random.nextFloat() * 300,
                    random.nextFloat() * 600 - 300);
            Vector3f direction = new Vector3f(random.nextFloat() - .5f, random.nextFloat() - .5f,
                    random.nextFloat() - .5f).normalize();
            Matrix4f view = new Matrix4f().lookAt(eye, new Vector3f(eye).add(direction), new Vector3f(0, 1, 0));
            Matrix4f clip = ((i & 1) == 0 ? new Matrix4f().perspective((float) Math.toRadians(90), 1.64f, .1f, 1000)
                    : new Matrix4f().ortho(-150, 150, -150, 150, .1f, 1000)).mul(view);
            float x = (random.nextInt(50) - 25) * 16, z = (random.nextInt(50) - 25) * 16;
            float low = random.nextFloat() * 200;
            MeshBounds box = new MeshBounds(0, low, 0, 16, low + random.nextFloat() * 56, 16);
            if (!box.intersects(new FrustumIntersection(clip), x, z)) {
                rejected++;
                require(outsidePlane(clip, box, x, z), "false rejection " + i);
            }
        }
        require(rejected > 1000, "insufficient rejection coverage");

        FloatBuffer cloud = FloatBuffer.allocate(9000);
        for (int i = 0; i < 1000; i++) vertex(cloud, random.nextFloat() * 16, random.nextFloat() * 256, random.nextFloat() * 16);
        cloud.flip();
        MeshBounds cloudBounds = MeshBounds.fromVertices(cloud, 1000);
        for (int i = 0; i < 1000; i++) {
            float x = cloud.get(i * 9), y = cloud.get(i * 9 + 1), z = cloud.get(i * 9 + 2);
            require(x >= cloudBounds.minX() && x <= cloudBounds.maxX() && y >= cloudBounds.minY()
                    && y <= cloudBounds.maxY() && z >= cloudBounds.minZ() && z <= cloudBounds.maxZ(), "containment");
        }
        System.out.println("PASS MeshBounds: buffer/invalid/planar/containment/lifecycle + 20000 perspective/ortho cases; rejected=" + rejected);
    }

    private static void vertex(FloatBuffer b, float x, float y, float z) {
        b.put(x).put(y).put(z);
        for (int i = 0; i < 6; i++) b.put(Float.NaN); // non-position attributes must be ignored
    }

    private static boolean outsidePlane(Matrix4f clip, MeshBounds b, float x, float z) {
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int corner = 0; corner < 8; corner++) {
            Vector4f p = clip.transform(new Vector4f(x + ((corner & 1) == 0 ? b.minX() : b.maxX()),
                    (corner & 2) == 0 ? b.minY() : b.maxY(), z + ((corner & 4) == 0 ? b.minZ() : b.maxZ()), 1));
            float[] d = {p.w + p.x, p.w - p.x, p.w + p.y, p.w - p.y, p.w + p.z, p.w - p.z};
            for (int plane = 0; plane < 6; plane++) max[plane] = Math.max(max[plane], d[plane]);
        }
        for (float m : max) if (m < 0) return true;
        return false;
    }

    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void invalid(Runnable call) {
        try { call.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid input accepted");
    }
}
