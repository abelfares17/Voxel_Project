package engine.world;

import java.util.Random;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Standalone CPU regression test; no GL context or disabled JUnit source set required. */
public final class ShadowFrustumCullingTest {
    public static void main(String[] args) {
        var identity = new FrustumIntersection(new Matrix4f());
        require(World.intersectsShadowFrustum(identity, 0, 0), "intersecting chunk rejected");
        require(!World.intersectsShadowFrustum(identity, 32, 32), "distant chunk accepted");
        require(World.intersectsShadowFrustum(identity, -17, 0), "touching negative-X face rejected");
        var highSlice = new FrustumIntersection(new Matrix4f().ortho(-20, 20, 255, 257, -20, 20));
        require(World.intersectsShadowFrustum(highSlice, 0, 0), "top face at HEIGHT rejected");

        Random random = new Random(28371262719219135L);
        int rejected = 0;
        for (int i = 0; i < 20000; i++) {
            Vector3f eye = new Vector3f(random.nextFloat() * 1000 - 500,
                    random.nextFloat() * 400, random.nextFloat() * 1000 - 500);
            Vector3f dir = new Vector3f(random.nextFloat() * 2 - 1,
                    random.nextFloat() * 1.8f - 0.9f, random.nextFloat() * 2 - 1).normalize();
            float radius = 10 + random.nextFloat() * 200;
            Matrix4f clip = new Matrix4f().orthoLH(-radius, radius, -radius, radius, 0, 4 * radius)
                    .mul(new Matrix4f().lookAtLH(eye, new Vector3f(eye).add(dir), new Vector3f(0, 1, 0)));
            float x = (random.nextInt(100) - 50) * Chunk.WIDTH;
            float z = (random.nextInt(100) - 50) * Chunk.DEPTH;
            if (!World.intersectsShadowFrustum(new FrustumIntersection(clip), x, z)) {
                rejected++;
                require(outsideClipPlane(clip, x, z), "false rejection at case " + i);
            }
        }
        require(rejected > 1000, "test did not exercise rejection");
        System.out.println("PASS: boundary/top/negative-coordinate cases + 20000 light volumes; rejected=" + rejected);
    }

    // Independent oracle: transform the eight unpadded corners, then test the six OpenGL clip inequalities.
    private static boolean outsideClipPlane(Matrix4f clip, float x, float z) {
        float[] maxima = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int corner = 0; corner < 8; corner++) {
            Vector4f p = clip.transform(new Vector4f(x + ((corner & 1) == 0 ? 0 : Chunk.WIDTH),
                    (corner & 2) == 0 ? 0 : Chunk.HEIGHT,
                    z + ((corner & 4) == 0 ? 0 : Chunk.DEPTH), 1));
            float[] distances = {p.w + p.x, p.w - p.x, p.w + p.y, p.w - p.y, p.w + p.z, p.w - p.z};
            for (int plane = 0; plane < 6; plane++) maxima[plane] = Math.max(maxima[plane], distances[plane]);
        }
        for (float maximum : maxima) if (maximum < 0) return true;
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
