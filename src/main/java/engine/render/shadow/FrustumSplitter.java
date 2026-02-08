package engine.render.shadow;

import engine.core.config.RenderConfig;

/**
 * Computes cascade split distances for Cascaded Shadow Mapping.
 * Uses a practical split scheme that blends logarithmic and linear distributions.
 */
public class FrustumSplitter {

    /**
     * Compute split distances for CSM cascades using practical split formula.
     *
     * The practical split blends logarithmic and linear distributions:
     * C_log[i] = near * (far / near)^(i / n)
     * C_lin[i] = near + (far - near) * (i / n)
     * split[i] = lambda * C_log[i] + (1 - lambda) * C_lin[i]
     *
     * @param near   Camera near plane
     * @param far    Shadow far plane (typically shorter than camera far)
     * @param count  Number of cascades
     * @param lambda Blend factor: 0 = linear, 1 = logarithmic, 0.75 = typical
     * @return Array of split distances [count + 1] including near and far
     */
    public static float[] computeSplitDistances(float near, float far, int count, float lambda) {
        float[] splits = new float[count + 1];
        splits[0] = near;
        splits[count] = far;

        for (int i = 1; i < count; i++) {
            float p = (float) i / count;

            // Logarithmic split (favors near field)
            float logSplit = near * (float) Math.pow(far / near, p);

            // Linear split (uniform distribution)
            float linSplit = near + (far - near) * p;

            // Blend using lambda
            splits[i] = lambda * logSplit + (1.0f - lambda) * linSplit;
        }

        return splits;
    }

    /**
     * Compute split distances using default configuration from RenderConfig.
     *
     * @return Array of split distances [CASCADE_COUNT + 1]
     */
    public static float[] computeSplitDistances() {
        return computeSplitDistances(
            RenderConfig.CAMERA_NEAR,
            RenderConfig.CSM_SHADOW_FAR,
            RenderConfig.CSM_CASCADE_COUNT,
            RenderConfig.CSM_SPLIT_LAMBDA
        );
    }
}
