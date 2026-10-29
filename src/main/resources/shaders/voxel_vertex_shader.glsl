#version 150

in vec3 aPos;
in vec2 aTex;
in vec3 aNormal;
in float aAO;

uniform mat4 projectionMatrix;
uniform mat4 viewMatrix;
uniform mat4 modelMatrix;

// CSM: Use constant for max cascades, actual count from uniform
const int MAX_CASCADES = 4;
uniform mat4 lightSpaceMatrices[MAX_CASCADES];    // Primary light-space matrices
uniform mat4 lightSpaceMatricesB[MAX_CASCADES];   // Secondary matrices (for leapfrog crossfade)
uniform int cascadeCount;
uniform int leapfrogActive;  // 1 if transitioning between shadow maps

// Normal-offset shadow parameters
uniform vec3 lightDirection;  // Direction TO the active sun/moon (normal offset)
uniform float cascadeTexelSizes[MAX_CASCADES];  // For scaling normal offset per cascade
uniform float cascadeTexelSizesB[MAX_CASCADES]; // Secondary texel sizes (for leapfrog)
uniform int normalOffsetMode;  // 0=off, 1=on (for A/B testing)
uniform int greedyVisual;      // 1=decode repeating atlas tile UVs, 0=legacy direct UVs

out vec2 vTexCoord;
flat out vec2 vTileOrigin;
flat out vec2 vQuadSize;
flat out int vPackedAO;
out vec3 vNormal;
out vec3 vWorldPos;
out float vAO;

// CSM outputs
out float vViewDepth;
out vec4 vLightSpacePos[MAX_CASCADES];   // Primary light-space positions
out vec4 vLightSpacePosB[MAX_CASCADES];  // Secondary light-space positions (for leapfrog)

void main() {
    vec4 world = modelMatrix * vec4(aPos, 1.0);
    vWorldPos = world.xyz;

    // Transform normal to world space
    vec3 worldNormal = normalize(mat3(modelMatrix) * aNormal);
    vNormal = worldNormal;

    // Compute view-space position for cascade selection
    vec4 viewPos = viewMatrix * world;
    vViewDepth = -viewPos.z;  // Positive depth (camera looks down -Z)

    // Compute light-space positions for active cascades
    // With optional normal-offset to reduce shadow edge shimmer
    for (int i = 0; i < MAX_CASCADES; i++) {
        if (i < cascadeCount) {
            vec4 shadowWorld = world;
            vec4 shadowWorldB = world;

            if (normalOffsetMode == 1) {
                // Normal-offset shadow technique:
                // Offset the world position along the surface normal before light-space transform.
                // This moves the shadow sampling point away from geometric edges,
                // reducing depth comparison instability (shimmer/ripple).
                //
                // The offset scales with:
                // 1. Cascade texel size (larger offset for coarser cascades)
                // 2. Angle between normal and light (more offset at grazing angles)
                float NdotL = clamp(dot(worldNormal, lightDirection), 0.0, 1.0);
                float sinTheta = sqrt(max(1.0 - NdotL * NdotL, 0.0));  // sin of angle to light

                // Offset magnitude: larger at grazing angles, scaled by texel size
                float offsetScale = 0.6;  // Tunable multiplier (moderate offset to prevent shadow detachment)
                float offset = cascadeTexelSizes[i] * offsetScale * (0.25 + 0.75 * sinTheta);

                // Apply offset along normal (primary)
                shadowWorld = vec4(world.xyz + worldNormal * offset, 1.0);

                // Apply offset for secondary (use secondary texel sizes if leapfrog active)
                if (leapfrogActive == 1) {
                    float offsetB = cascadeTexelSizesB[i] * offsetScale * (0.25 + 0.75 * sinTheta);
                    shadowWorldB = vec4(world.xyz + worldNormal * offsetB, 1.0);
                }
            }

            vLightSpacePos[i] = lightSpaceMatrices[i] * shadowWorld;

            // Compute secondary light-space position for leapfrog
            // CRITICAL: Always compute VALID B values to ensure consistent GPU state
            // When NOT transitioning, use A's matrices for B to ensure valid w component
            // This prevents undefined behavior from w=0 division in fragment shader
            // and maintains consistent varying interpolation across all frames
            if (leapfrogActive == 1) {
                vLightSpacePosB[i] = lightSpaceMatricesB[i] * shadowWorldB;
            } else {
                // Use A's computation for B - ensures w is valid and GPU state is invariant
                vLightSpacePosB[i] = lightSpaceMatrices[i] * shadowWorld;
            }
        } else {
            vLightSpacePos[i] = vec4(0.0);
            vLightSpacePosB[i] = vec4(0.0);
        }
    }

    gl_Position = projectionMatrix * viewPos;
    if (aAO >= 2.0) {
        int rawU = int(aTex.x + 0.5);
        int rawV = int(aTex.y + 0.5);
        int uCorner = rawU & 511;
        int vCorner = rawV & 511;
        int tileU = (rawU >> 9) & 63;
        int tileV = (rawV >> 9) & 63;
        int quadW = max(rawU >> 15, 1);
        int quadH = max(rawV >> 15, 1);
        vTileOrigin = vec2(float(tileU), float(tileV)) / 64.0;
        vQuadSize = vec2(float(quadW), float(quadH));
        vTexCoord = vec2(float(uCorner), float(vCorner));
        vPackedAO = int(aAO + 0.5);
        vAO = 1.0;
    } else {
        vPackedAO = 0;
        vQuadSize = vec2(512.0);
        vAO = aAO;
        if (greedyVisual == 1) {
            vec2 tileIdx = floor(aTex / 512.0 + 0.0001);
            vTileOrigin = tileIdx / 64.0;
            vTexCoord = aTex - tileIdx * 512.0;
        } else {
            vTileOrigin = vec2(0.0);
            vTexCoord = aTex;
        }
    }
}
