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
    // LOGGING STATE (for delta tracking)
    // ═══════════════════════════════════════════════════════════════════════════

    private final Vector3f prevLightDir = new Vector3f();
    private final Vector3f prevLightRight = new Vector3f();
    private final Vector3f prevLightUp = new Vector3f();
    private float prevSnappedX = 0;
    private float prevSnappedY = 0;
    private long lastDetailedLogTime = 0;
    private static final long DETAILED_LOG_INTERVAL_MS = 500;

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
    // LIGHT BASIS UPDATE - STABLE VERSION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Update light basis vectors from sun direction using a STABLE method.
     *
     * This uses a singularity-free orthonormal basis construction inspired by
     * Frisvad's method, which avoids discontinuities when lightDir is near poles.
     *
     * The key insight: instead of a hard threshold switch between up vectors,
     * we use a mathematical construction that smoothly handles all directions.
     */
    public void updateLightBasis(Vector3f sunDir) {
        // If frozen, use captured values
        if (freezeLightBasis && lightBasisCaptured) {
            lightDir.set(frozenLightDir);
            lightRight.set(frozenLightRight);
            lightUp.set(frozenLightUp);
            return;
        }

        // lightDir points FROM sun TOWARD scene (shadow casting direction)
        lightDir.set(sunDir).negate().normalize();

        // ═══════════════════════════════════════════════════════════════════════
        // STABLE ORTHONORMAL BASIS CONSTRUCTION (Frisvad-inspired)
        // ═══════════════════════════════════════════════════════════════════════
        // This method creates an orthonormal basis from a single vector without
        // any discontinuous threshold switches.
        //
        // For a normalized vector n = (x, y, z):
        // - If z > -0.9999999: use the standard Frisvad construction
        // - Otherwise: handle the degenerate case (n ≈ (0, 0, -1))

        float x = lightDir.x;
        float y = lightDir.y;
        float z = lightDir.z;

        if (z > -0.9999999f) {
            // Standard case: Frisvad's method
            // This is continuous for all directions except exactly (0, 0, -1)
            float a = 1.0f / (1.0f + z);
            float b = -x * y * a;

            lightRight.set(1.0f - x * x * a, b, -x).normalize();
            lightUp.set(b, 1.0f - y * y * a, -y).normalize();
        } else {
            // Degenerate case: lightDir ≈ (0, 0, -1)
            // This only triggers for a single point, not a threshold band
            lightRight.set(0, -1, 0);
            lightUp.set(-1, 0, 0);
        }

        // Ensure perfect orthonormality (numerical cleanup)
        lightRight.cross(lightDir, lightUp).normalize();
        lightDir.cross(lightUp, lightRight).normalize();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CASCADE MATRIX COMPUTATION
    // ═══════════════════════════════════════════════════════════════════════════

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
                    System.out.println("[CSM Diag] Snapped XY captured");
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
                    System.out.println("[CSM Diag] Z Range captured");
                }
            } else {
                minZ = frozenMinZ[cascadeIndex];
                maxZ = frozenMaxZ[cascadeIndex];
            }
        }

        float orthoDepth = maxZ - minZ;

        // ═══════════════════════════════════════════════════════════════════════
        // DETAILED LOGGING (cascade 0 only, rate-limited)
        // ═══════════════════════════════════════════════════════════════════════
        if (cascadeIndex == 0) {
            long now = System.currentTimeMillis();
            if (now - lastDetailedLogTime >= DETAILED_LOG_INTERVAL_MS) {
                lastDetailedLogTime = now;

                // Compute deltas
                float deltaLightDir = lightDir.distance(prevLightDir);
                float deltaLightRight = lightRight.distance(prevLightRight);
                float deltaLightUp = lightUp.distance(prevLightUp);
                float deltaSnappedX = Math.abs(snappedX - prevSnappedX);
                float deltaSnappedY = Math.abs(snappedY - prevSnappedY);

                // Compute snap grid indices
                int snapIndexX = Math.round(snappedX / texelSize);
                int snapIndexY = Math.round(snappedY / texelSize);

                System.out.printf("[CSM C0] lightDir=(%.4f,%.4f,%.4f) Δ=%.6f%n",
                    lightDir.x, lightDir.y, lightDir.z, deltaLightDir);
                System.out.printf("[CSM C0] lightRight=(%.4f,%.4f,%.4f) Δ=%.6f%n",
                    lightRight.x, lightRight.y, lightRight.z, deltaLightRight);
                System.out.printf("[CSM C0] lightUp=(%.4f,%.4f,%.4f) Δ=%.6f%n",
                    lightUp.x, lightUp.y, lightUp.z, deltaLightUp);
                System.out.printf("[CSM C0] centerLS=(%.4f,%.4f) snapped=(%.4f,%.4f) grid=(%d,%d) Δsnap=(%.6f,%.6f)%n",
                    centerLS_X, centerLS_Y, snappedX, snappedY, snapIndexX, snapIndexY, deltaSnappedX, deltaSnappedY);
                System.out.printf("[CSM C0] sphereCenterZ=%.4f minZ=%.4f maxZ=%.4f orthoDepth=%.4f%n",
                    sphereCenterZ, minZ, maxZ, orthoDepth);

                // Flag large jumps
                if (deltaLightRight > 0.01f || deltaLightUp > 0.01f) {
                    System.out.println("[CSM C0] *** LARGE BASIS JUMP DETECTED ***");
                }
                if (deltaSnappedX > texelSize * 2 || deltaSnappedY > texelSize * 2) {
                    System.out.println("[CSM C0] *** LARGE SNAP JUMP DETECTED ***");
                }

                // Store for next frame
                prevLightDir.set(lightDir);
                prevLightRight.set(lightRight);
                prevLightUp.set(lightUp);
                prevSnappedX = snappedX;
                prevSnappedY = snappedY;
            }
        }

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
