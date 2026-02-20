package engine.render.shadow;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import engine.core.config.RenderConfig;
import engine.render.camera.Camera;
import engine.render.camera.PerspectiveCamera;
import engine.util.CSMDebugLog;

/**
 * Cascaded Shadow Map (CSM) Calculator using Stabilized Fit algorithm.
 */
public class CSMCalculator {

    // ═══════════════════════════════════════════════════════════════════════════
    // CACHED DATA (recomputed only when camera parameters change)
    // ═══════════════════════════════════════════════════════════════════════════

    private float[] sphereRadii;
    private float[] sphereCenterDistances;
    private float[] splitDistances;
    private float[] texelSizes;

    private float cachedFov;
    private float cachedAspect;
    private float cachedNear;
    private float cachedFar;

    // ═══════════════════════════════════════════════════════════════════════════
    // PER-FRAME LIGHT BASIS
    // ═══════════════════════════════════════════════════════════════════════════

    private final Vector3f lightDir = new Vector3f();
    private final Vector3f lightRight = new Vector3f();
    private final Vector3f lightUp = new Vector3f();

    // ═══════════════════════════════════════════════════════════════════════════
    // DIAGNOSTIC FREEZE STATE
    // ═══════════════════════════════════════════════════════════════════════════

    // Freeze toggles (controlled externally via setters)
    private boolean freezeLightBasis = false;
    private boolean freezeSnappedXY = false;
    private boolean freezeZRange = false;

    // Frozen values for light basis
    private final Vector3f frozenLightDir = new Vector3f();
    private final Vector3f frozenLightRight = new Vector3f();
    private final Vector3f frozenLightUp = new Vector3f();
    private boolean lightBasisCaptured = false;

    // Frozen values for snapped XY (per cascade)
    private float[] frozenSnappedX;
    private float[] frozenSnappedY;
    private boolean snappedXYCaptured = false;

    // Frozen values for Z range (per cascade)
    private float[] frozenMinZ;
    private float[] frozenMaxZ;
    private boolean zRangeCaptured = false;

    // ═══════════════════════════════════════════════════════════════════════════
    // TEMPORARY STORAGE
    // ═══════════════════════════════════════════════════════════════════════════

    private final Vector3f tempVec = new Vector3f();
    private final Vector3f[] frustumCorners = new Vector3f[8];
    private final Vector3f frustumCentroid = new Vector3f();

    private final int cascadeCount;
    private final int shadowMapSize;

    public CSMCalculator() {
        this.cascadeCount = RenderConfig.CSM_CASCADE_COUNT;
        this.shadowMapSize = RenderConfig.CSM_SHADOW_MAP_SIZE;

        for (int i = 0; i < 8; i++) {
            frustumCorners[i] = new Vector3f();
        }

        sphereRadii = new float[cascadeCount];
        sphereCenterDistances = new float[cascadeCount];
        texelSizes = new float[cascadeCount];

        // Initialize frozen arrays
        frozenSnappedX = new float[cascadeCount];
        frozenSnappedY = new float[cascadeCount];
        frozenMinZ = new float[cascadeCount];
        frozenMaxZ = new float[cascadeCount];
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DIAGNOSTIC FREEZE CONTROLS
    // ═══════════════════════════════════════════════════════════════════════════

    public void setFreezeLightBasis(boolean freeze) {
        if (freeze && !this.freezeLightBasis) {
            // Capture current values
            frozenLightDir.set(lightDir);
            frozenLightRight.set(lightRight);
            frozenLightUp.set(lightUp);
            lightBasisCaptured = true;
            System.out.println("[CSM Diag] Light Basis FROZEN");
        } else if (!freeze && this.freezeLightBasis) {
            System.out.println("[CSM Diag] Light Basis UNFROZEN");
        }
        this.freezeLightBasis = freeze;
    }

    public void setFreezeSnappedXY(boolean freeze) {
        if (freeze && !this.freezeSnappedXY) {
            snappedXYCaptured = false; // Will capture on next compute
            System.out.println("[CSM Diag] Snapped XY FROZEN (will capture on next frame)");
        } else if (!freeze && this.freezeSnappedXY) {
            System.out.println("[CSM Diag] Snapped XY UNFROZEN");
        }
        this.freezeSnappedXY = freeze;
    }

    public void setFreezeZRange(boolean freeze) {
        if (freeze && !this.freezeZRange) {
            zRangeCaptured = false; // Will capture on next compute
            System.out.println("[CSM Diag] Z Range FROZEN (will capture on next frame)");
        } else if (!freeze && this.freezeZRange) {
            System.out.println("[CSM Diag] Z Range UNFROZEN");
        }
        this.freezeZRange = freeze;
    }

    public boolean isFreezeLightBasis() { return freezeLightBasis; }
    public boolean isFreezeSnappedXY() { return freezeSnappedXY; }
    public boolean isFreezeZRange() { return freezeZRange; }

    // ═══════════════════════════════════════════════════════════════════════════
    // SPHERE RADII COMPUTATION
    // ═══════════════════════════════════════════════════════════════════════════

    public void updateSphereRadii(float fov, float aspect, float near, float far) {
        if (fov == cachedFov && aspect == cachedAspect && near == cachedNear && far == cachedFar) {
            return;
        }

        cachedFov = fov;
        cachedAspect = aspect;
        cachedNear = near;
        cachedFar = far;

        splitDistances = FrustumSplitter.computeSplitDistances(
            near,
            RenderConfig.CSM_SHADOW_FAR,
            cascadeCount,
            RenderConfig.CSM_SPLIT_LAMBDA
        );

        float fovRad = (float) Math.toRadians(fov);
        float tanHalfFov = (float) Math.tan(fovRad / 2.0f);

        for (int i = 0; i < cascadeCount; i++) {
            float zNear = splitDistances[i];
            float zFar = splitDistances[i + 1];

            float farHalfH = zFar * tanHalfFov;
            float farHalfW = farHalfH * aspect;

            float zCenter = (zNear + zFar) / 2.0f;
            float zExtent = zFar - zCenter;

            float radius = (float) Math.sqrt(
                farHalfW * farHalfW +
                farHalfH * farHalfH +
                zExtent * zExtent
            );

            radius *= 1.05f;
            radius = (float) Math.ceil(radius * 16.0f) / 16.0f;

            sphereRadii[i] = radius;
            sphereCenterDistances[i] = zCenter;
            texelSizes[i] = (radius * 2.0f) / shadowMapSize;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LIGHT BASIS UPDATE - PIXAR/DUFF WITH QUANTIZATION
    // ═══════════════════════════════════════════════════════════════════════════

    // Quantization state for leapfrogging
    private final Vector3f lastQuantizedSunDir = new Vector3f(0, 1, 0);
    private boolean quantizedBasisInitialized = false;

    // Quantization threshold: only update basis when sun moves more than this angle (radians)
    // 0.5 degrees ≈ 0.00873 radians
    private static final float SUN_QUANTIZATION_THRESHOLD = 0.00873f;

    /**
     * Check if the sun direction has changed enough to warrant a basis update.
     * Used by leapfrogging system to decide when to render to the secondary shadow map.
     *
     * @param sunDir Current sun direction
     * @return true if basis should be updated
     */
    public boolean shouldUpdateBasis(Vector3f sunDir) {
        if (!quantizedBasisInitialized) {
            return true;
        }
        float angleDelta = sunDir.angle(lastQuantizedSunDir);
        return angleDelta >= SUN_QUANTIZATION_THRESHOLD;
    }

    /**
     * Update light basis vectors from sun direction using Pixar/Duff's revised method.
     *
     * This uses the numerically stable orthonormal basis construction from:
     * "Building an Orthonormal Basis, Revisited" (Pixar, JCGT 2017)
     *
     * @param sunDir Direction TO the sun
     * @param forceUpdate If true, update even if below quantization threshold
     * @return true if basis was actually updated
     */
    public boolean updateLightBasis(Vector3f sunDir, boolean forceUpdate) {
        // If frozen, use captured values
        if (freezeLightBasis && lightBasisCaptured) {
            lightDir.set(frozenLightDir);
            lightRight.set(frozenLightRight);
            lightUp.set(frozenLightUp);
            return false;
        }

        // Check quantization threshold
        if (!forceUpdate && quantizedBasisInitialized && !shouldUpdateBasis(sunDir)) {
            return false;  // No update needed
        }

        // Record this sun direction as the quantized state
        lastQuantizedSunDir.set(sunDir);
        quantizedBasisInitialized = true;

        // lightDir points FROM sun TOWARD scene (shadow casting direction)
        lightDir.set(sunDir).negate().normalize();

        // ═══════════════════════════════════════════════════════════════════════
        // PIXAR/DUFF ORTHONORMAL BASIS CONSTRUCTION (JCGT 2017)
        // ═══════════════════════════════════════════════════════════════════════
        float x = lightDir.x;
        float y = lightDir.y;
        float z = lightDir.z;

        float sign = Math.copySign(1.0f, z);
        float a = -1.0f / (sign + z);
        float b = x * y * a;

        // For left-handed (orthoLH compatible), negate xaxis (lightRight):
        lightRight.set(
            -(1.0f + sign * x * x * a),
            -sign * b,
            sign * x
        );
        lightUp.set(b, sign + y * y * a, -y);

        return true;
    }

    /**
     * Update light basis (backward compatible - always updates).
     */
    public void updateLightBasis(Vector3f sunDir) {
        updateLightBasis(sunDir, true);
    }

    /**
     * Reset the quantization baseline to the given sun direction.
     * This does NOT update the light basis vectors - it only resets
     * the reference point for shouldUpdateBasis() checks.
     *
     * Use case: After a crossfade completes, we want to measure future
     * sun movement from the CURRENT position, not from 20 frames ago.
     */
    public void resetQuantizationBaseline(Vector3f sunDir) {
        lastQuantizedSunDir.set(sunDir);
        quantizedBasisInitialized = true;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CASCADE MATRIX COMPUTATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Compute cascade matrices using an externally-provided light basis.
     * This is used during leapfrog transitions where we need to update
     * camera-dependent parts while keeping the light basis frozen.
     *
     * @param camera Camera for frustum computation
     * @param camForward Camera forward vector
     * @param camRight Camera right vector
     * @param camUp Camera up vector
     * @param extLightDir External light direction (frozen)
     * @param extLightRight External light right vector (frozen)
     * @param extLightUp External light up vector (frozen)
     * @return CascadeData with matrices computed using the external basis
     */
    public CascadeData computeCascadeMatricesWithBasis(Camera camera,
            Vector3f camForward, Vector3f camRight, Vector3f camUp,
            Vector3f extLightDir, Vector3f extLightRight, Vector3f extLightUp) {

        Vector3f camPos = camera.getPosition();

        if (camera instanceof PerspectiveCamera pc) {
            updateSphereRadii(pc.getFov(), pc.getAspect(), pc.getNear(), pc.getFar());
        }

        Matrix4f[] matrices = new Matrix4f[cascadeCount];
        float fovRad = (float) Math.toRadians(cachedFov);
        float tanHalfFov = (float) Math.tan(fovRad / 2.0f);

        for (int i = 0; i < cascadeCount; i++) {
            matrices[i] = computeCascadeMatrixWithBasis(i, camPos, camForward, camRight, camUp,
                tanHalfFov, cachedAspect, extLightDir, extLightRight, extLightUp);
        }

        return new CascadeData(matrices, splitDistances.clone(), sphereRadii.clone(), texelSizes.clone());
    }

    public CascadeData computeCascadeMatrices(Camera camera, Vector3f camForward, Vector3f camRight, Vector3f camUp) {
        Vector3f camPos = camera.getPosition();

        if (camera instanceof PerspectiveCamera pc) {
            updateSphereRadii(pc.getFov(), pc.getAspect(), pc.getNear(), pc.getFar());
        }

        Matrix4f[] matrices = new Matrix4f[cascadeCount];
        float fovRad = (float) Math.toRadians(cachedFov);
        float tanHalfFov = (float) Math.tan(fovRad / 2.0f);

        for (int i = 0; i < cascadeCount; i++) {
            matrices[i] = computeCascadeMatrix(i, camPos, camForward, camRight, camUp, tanHalfFov, cachedAspect);
        }

        return new CascadeData(matrices, splitDistances.clone(), sphereRadii.clone(), texelSizes.clone());
    }

    private Matrix4f computeCascadeMatrix(
            int cascadeIndex,
            Vector3f camPos,
            Vector3f camForward,
            Vector3f camRight,
            Vector3f camUp,
            float tanHalfFov,
            float aspect
    ) {
        float zNear = splitDistances[cascadeIndex];
        float zFar = splitDistances[cascadeIndex + 1];

        // Compute frustum corners (for reference)
        float nearHalfH = zNear * tanHalfFov;
        float nearHalfW = nearHalfH * aspect;
        float farHalfH = zFar * tanHalfFov;
        float farHalfW = farHalfH * aspect;

        Vector3f nearCenter = new Vector3f(camForward).mul(zNear).add(camPos);
        Vector3f farCenter = new Vector3f(camForward).mul(zFar).add(camPos);

        computeCorner(frustumCorners[0], nearCenter, camRight, camUp, -nearHalfW, -nearHalfH);
        computeCorner(frustumCorners[1], nearCenter, camRight, camUp,  nearHalfW, -nearHalfH);
        computeCorner(frustumCorners[2], nearCenter, camRight, camUp,  nearHalfW,  nearHalfH);
        computeCorner(frustumCorners[3], nearCenter, camRight, camUp, -nearHalfW,  nearHalfH);
        computeCorner(frustumCorners[4], farCenter, camRight, camUp, -farHalfW, -farHalfH);
        computeCorner(frustumCorners[5], farCenter, camRight, camUp,  farHalfW, -farHalfH);
        computeCorner(frustumCorners[6], farCenter, camRight, camUp,  farHalfW,  farHalfH);
        computeCorner(frustumCorners[7], farCenter, camRight, camUp, -farHalfW,  farHalfH);

        frustumCentroid.zero();
        for (Vector3f c : frustumCorners) {
            frustumCentroid.add(c);
        }
        frustumCentroid.div(8.0f);

        // Get constant bounds
        float halfSize = sphereRadii[cascadeIndex];
        float texelSize = texelSizes[cascadeIndex];

        // Compute sphere center in world space
        Vector3f sphereCenterWS = new Vector3f(camForward).mul(sphereCenterDistances[cascadeIndex]).add(camPos);

        // Project to light-space XY
        float centerLS_X = sphereCenterWS.dot(lightRight);
        float centerLS_Y = sphereCenterWS.dot(lightUp);

        // Snap to texel grid
        float snappedX = Math.round(centerLS_X / texelSize) * texelSize;
        float snappedY = Math.round(centerLS_Y / texelSize) * texelSize;

        // Handle frozen snapped XY
        if (freezeSnappedXY) {
            if (!snappedXYCaptured) {
                frozenSnappedX[cascadeIndex] = snappedX;
                frozenSnappedY[cascadeIndex] = snappedY;
                if (cascadeIndex == cascadeCount - 1) {
                    snappedXYCaptured = true;
                }
            } else {
                snappedX = frozenSnappedX[cascadeIndex];
                snappedY = frozenSnappedY[cascadeIndex];
            }
        }

        // Compute Z range
        float sphereCenterZ = sphereCenterWS.dot(lightDir);
        float sphereRadius = sphereRadii[cascadeIndex];

        float minZ = sphereCenterZ - sphereRadius;
        float maxZ = sphereCenterZ + sphereRadius;

        float casterMargin = (maxZ - minZ) * RenderConfig.CSM_DEPTH_MARGIN_RATIO;
        minZ -= casterMargin;
        maxZ += casterMargin;

        // Handle frozen Z range
        if (freezeZRange) {
            if (!zRangeCaptured) {
                frozenMinZ[cascadeIndex] = minZ;
                frozenMaxZ[cascadeIndex] = maxZ;
                if (cascadeIndex == cascadeCount - 1) {
                    zRangeCaptured = true;
                }
            } else {
                minZ = frozenMinZ[cascadeIndex];
                maxZ = frozenMaxZ[cascadeIndex];
            }
        }

        float orthoDepth = maxZ - minZ;

        // Build light-view matrix
        Matrix4f lightView = new Matrix4f();

        lightView.m00(lightRight.x);
        lightView.m10(lightRight.y);
        lightView.m20(lightRight.z);
        lightView.m30(-snappedX);

        lightView.m01(lightUp.x);
        lightView.m11(lightUp.y);
        lightView.m21(lightUp.z);
        lightView.m31(-snappedY);

        lightView.m02(lightDir.x);
        lightView.m12(lightDir.y);
        lightView.m22(lightDir.z);
        lightView.m32(-minZ);

        lightView.m03(0);
        lightView.m13(0);
        lightView.m23(0);
        lightView.m33(1);

        // Build orthographic projection
        Matrix4f lightProj = new Matrix4f().orthoLH(
            -halfSize, halfSize,
            -halfSize, halfSize,
            0.0f, orthoDepth
        );

        return lightProj.mul(lightView);
    }

    /**
     * Compute cascade matrix using an externally-provided light basis.
     * This variant allows recomputing matrices with camera changes while
     * keeping the light basis frozen (for leapfrog transitions).
     */
    private Matrix4f computeCascadeMatrixWithBasis(
            int cascadeIndex,
            Vector3f camPos,
            Vector3f camForward,
            Vector3f camRight,
            Vector3f camUp,
            float tanHalfFov,
            float aspect,
            Vector3f extLightDir,
            Vector3f extLightRight,
            Vector3f extLightUp
    ) {
        float zNear = splitDistances[cascadeIndex];
        float zFar = splitDistances[cascadeIndex + 1];

        // Compute frustum corners
        float nearHalfH = zNear * tanHalfFov;
        float nearHalfW = nearHalfH * aspect;
        float farHalfH = zFar * tanHalfFov;
        float farHalfW = farHalfH * aspect;

        Vector3f nearCenter = new Vector3f(camForward).mul(zNear).add(camPos);
        Vector3f farCenter = new Vector3f(camForward).mul(zFar).add(camPos);

        computeCorner(frustumCorners[0], nearCenter, camRight, camUp, -nearHalfW, -nearHalfH);
        computeCorner(frustumCorners[1], nearCenter, camRight, camUp,  nearHalfW, -nearHalfH);
        computeCorner(frustumCorners[2], nearCenter, camRight, camUp,  nearHalfW,  nearHalfH);
        computeCorner(frustumCorners[3], nearCenter, camRight, camUp, -nearHalfW,  nearHalfH);
        computeCorner(frustumCorners[4], farCenter, camRight, camUp, -farHalfW, -farHalfH);
        computeCorner(frustumCorners[5], farCenter, camRight, camUp,  farHalfW, -farHalfH);
        computeCorner(frustumCorners[6], farCenter, camRight, camUp,  farHalfW,  farHalfH);
        computeCorner(frustumCorners[7], farCenter, camRight, camUp, -farHalfW,  farHalfH);

        // Get constant bounds
        float halfSize = sphereRadii[cascadeIndex];
        float texelSize = texelSizes[cascadeIndex];

        // Compute sphere center in world space
        Vector3f sphereCenterWS = new Vector3f(camForward).mul(sphereCenterDistances[cascadeIndex]).add(camPos);

        // Project to light-space XY using EXTERNAL light basis
        float centerLS_X = sphereCenterWS.dot(extLightRight);
        float centerLS_Y = sphereCenterWS.dot(extLightUp);

        // Snap to texel grid
        float snappedX = Math.round(centerLS_X / texelSize) * texelSize;
        float snappedY = Math.round(centerLS_Y / texelSize) * texelSize;

        // Compute Z range using EXTERNAL light basis
        float sphereCenterZ = sphereCenterWS.dot(extLightDir);
        float sphereRadius = sphereRadii[cascadeIndex];

        float minZ = sphereCenterZ - sphereRadius;
        float maxZ = sphereCenterZ + sphereRadius;

        float casterMargin = (maxZ - minZ) * RenderConfig.CSM_DEPTH_MARGIN_RATIO;
        minZ -= casterMargin;
        maxZ += casterMargin;

        float orthoDepth = maxZ - minZ;

        // Build light-view matrix using EXTERNAL light basis
        Matrix4f lightView = new Matrix4f();

        lightView.m00(extLightRight.x);
        lightView.m10(extLightRight.y);
        lightView.m20(extLightRight.z);
        lightView.m30(-snappedX);

        lightView.m01(extLightUp.x);
        lightView.m11(extLightUp.y);
        lightView.m21(extLightUp.z);
        lightView.m31(-snappedY);

        lightView.m02(extLightDir.x);
        lightView.m12(extLightDir.y);
        lightView.m22(extLightDir.z);
        lightView.m32(-minZ);

        lightView.m03(0);
        lightView.m13(0);
        lightView.m23(0);
        lightView.m33(1);

        // Build orthographic projection
        Matrix4f lightProj = new Matrix4f().orthoLH(
            -halfSize, halfSize,
            -halfSize, halfSize,
            0.0f, orthoDepth
        );

        return lightProj.mul(lightView);
    }

    private void computeCorner(Vector3f result, Vector3f center, Vector3f right, Vector3f up, float x, float y) {
        result.set(center);
        result.add(tempVec.set(right).mul(x));
        result.add(tempVec.set(up).mul(y));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ACCESSORS
    // ═══════════════════════════════════════════════════════════════════════════

    public float[] getSplitDistances() { return splitDistances; }
    public float[] getSphereRadii() { return sphereRadii; }
    public float[] getTexelSizes() { return texelSizes; }
    public int getCascadeCount() { return cascadeCount; }

    // For external access to light basis (debugging)
    public Vector3f getLightDir() { return new Vector3f(lightDir); }
    public Vector3f getLightRight() { return new Vector3f(lightRight); }
    public Vector3f getLightUp() { return new Vector3f(lightUp); }
}
