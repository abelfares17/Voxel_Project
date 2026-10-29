package engine.render.shadow;

import org.joml.Matrix4f;

/** World units, independent of cascade clipping depth and of the receiver bias. */
public final class ShadowDistance {
    public static final float SAMPLING_GUARD = 4f;
    public static final float PREFETCH_MARGIN = 16f;

    public static float depthSpan(Matrix4f matrix) {
        float length = (float)Math.sqrt(matrix.m02()*matrix.m02()
                + matrix.m12()*matrix.m12() + matrix.m22()*matrix.m22());
        if (!(length > 0) || !Float.isFinite(length) || matrix.m03()!=0 || matrix.m13()!=0
                || matrix.m23()!=0 || matrix.m33()!=1)
            throw new IllegalArgumentException("Shadow distance needs a finite orthographic matrix");
        return 2f / length;
    }

    /** Worst supported kernel (mode 2) plus the vertex shader's maximum normal offset. */
    public static boolean samplingGuardFits(float texelSize) {
        return Float.isFinite(texelSize) && texelSize >= 0
                && texelSize * (0.6f + 1.5f * (float)Math.sqrt(2)) + 0.05f <= SAMPLING_GUARD;
    }

    public static float visibility(float distance, float start, float end) {
        float t = Math.max(0, Math.min(1, (distance-start)/(end-start)));
        return t*t*(3-2*t);
    }
    private ShadowDistance() {}
}
