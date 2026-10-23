package engine.render;

import java.nio.FloatBuffer;
import org.joml.FrustumIntersection;

/** Immutable local bounds of the positions in one uploaded mesh, not of mutable blocks. */
public record MeshBounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
    public static final float CULLING_PADDING = 0.01f;

    public MeshBounds {
        if (!Float.isFinite(minX) || !Float.isFinite(minY) || !Float.isFinite(minZ)
                || !Float.isFinite(maxX) || !Float.isFinite(maxY) || !Float.isFinite(maxZ)
                || minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Invalid mesh bounds");
        }
    }

    /** Reads XYZ only, starting at position; never changes the buffer's position or limit. */
    public static MeshBounds fromVertices(FloatBuffer vertices, int vertexCount) {
        int stride = MeshBuilder.STRIDE / Float.BYTES;
        if (vertexCount < 0 || (long) vertexCount * stride > vertices.remaining()) {
            throw new IllegalArgumentException("Vertex count exceeds the readable buffer");
        }
        if (vertexCount == 0) return null;
        float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
        float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        int start = vertices.position();
        for (int v = 0; v < vertexCount; v++) {
            int i = start + v * stride;
            float x = vertices.get(i), y = vertices.get(i + 1), z = vertices.get(i + 2);
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                throw new IllegalArgumentException("Non-finite position at vertex " + v);
            }
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
        }
        return new MeshBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public boolean intersects(FrustumIntersection frustum, float originX, float originZ) {
        float p = CULLING_PADDING;
        return frustum.testAab(originX + minX - p, minY - p, originZ + minZ - p,
                originX + maxX + p, maxY + p, originZ + maxZ + p);
    }
}
