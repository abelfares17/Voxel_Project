package engine.render.shadow;

import org.joml.Matrix4f;

/**
 * Immutable data for CSM cascades computed per frame.
 * Contains all information needed for shadow rendering and sampling.
 */
public record CascadeData(
    /** Light-space matrices for each cascade [CASCADE_COUNT] */
    Matrix4f[] lightSpaceMatrices,

    /** Split distances including near and far [CASCADE_COUNT + 1] */
    float[] splitDistances,

    /** Sphere radii per cascade for debug visualization [CASCADE_COUNT] */
    float[] sphereRadii,

    /** Texel sizes per cascade for cascade-aware bias [CASCADE_COUNT] */
    float[] texelSizes
) {
    /**
     * Get the number of cascades.
     */
    public int cascadeCount() {
        return lightSpaceMatrices.length;
    }
}
