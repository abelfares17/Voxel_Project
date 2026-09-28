package engine.render.shadow;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Immutable data for CSM cascades computed per frame.
 * Contains all information needed for shadow rendering, sampling, and culling.
 */
public record CascadeData(
    /** Light-space matrices for each cascade [CASCADE_COUNT] */
    Matrix4f[] lightSpaceMatrices,

    /** Split distances including near and far [CASCADE_COUNT + 1] */
    float[] splitDistances,

    /** Sphere radii per cascade for debug visualization and culling [CASCADE_COUNT] */
    float[] sphereRadii,

    /** Texel sizes per cascade for cascade-aware bias [CASCADE_COUNT] */
    float[] texelSizes,
    int[] snapIndicesX,
    int[] snapIndicesY,
    Vector3f[] sphereCenters
) {
    public CascadeData(Matrix4f[] lightSpaceMatrices, float[] splitDistances, float[] sphereRadii, float[] texelSizes) {
        this(lightSpaceMatrices, splitDistances, sphereRadii, texelSizes,
             new int[lightSpaceMatrices.length], new int[lightSpaceMatrices.length],
             new Vector3f[lightSpaceMatrices.length]);
    }

    public CascadeData(Matrix4f[] lightSpaceMatrices, float[] splitDistances, float[] sphereRadii, float[] texelSizes,
                       int[] snapIndicesX, int[] snapIndicesY) {
        this(lightSpaceMatrices, splitDistances, sphereRadii, texelSizes,
             snapIndicesX, snapIndicesY,
             new Vector3f[lightSpaceMatrices.length]);
    }

    /**
     * Get the number of cascades.
     */
    public int cascadeCount() {
        return lightSpaceMatrices.length;
    }

    public Vector3f sphereCenter(int cascadeIndex) {
        if (sphereCenters != null && cascadeIndex >= 0 && cascadeIndex < sphereCenters.length) {
            return sphereCenters[cascadeIndex];
        }
        return null;
    }

    public float sphereRadius(int cascadeIndex) {
        if (sphereRadii != null && cascadeIndex >= 0 && cascadeIndex < sphereRadii.length) {
            return sphereRadii[cascadeIndex];
        }
        return -1.0f;
    }

    /**
     * Compute conservative culling radius in world space for cascade shadow casting.
     * The shadow projection volume extends in depth by (1 + 2*depthMargin)*R along the sun vector,
     * and R in perpendicular dimensions. Adding chunk diagonal margin ensures zero popping.
     */
    public float cullRadius(int cascadeIndex) {
        if (sphereRadii != null && cascadeIndex >= 0 && cascadeIndex < sphereRadii.length) {
            float r = sphereRadii[cascadeIndex];
            return r * 2.5f + 32.0f;
        }
        return -1.0f;
    }
}
