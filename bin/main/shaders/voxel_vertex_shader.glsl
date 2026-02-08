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
uniform mat4 lightSpaceMatrices[MAX_CASCADES];
uniform int cascadeCount;

// Normal-offset shadow parameters
uniform vec3 sunDir;  // Direction TO the sun (for normal offset calculation)
uniform float cascadeTexelSizes[MAX_CASCADES];  // For scaling normal offset per cascade
uniform int normalOffsetMode;  // 0=off, 1=on (for A/B testing)

out vec2 vTexCoord;
out vec3 vNormal;
out vec3 vWorldPos;
out float vAO;

// CSM outputs
out float vViewDepth;
out vec4 vLightSpacePos[MAX_CASCADES];

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

            if (normalOffsetMode == 1) {
                // Normal-offset shadow technique:
                // Offset the world position along the surface normal before light-space transform.
                // This moves the shadow sampling point away from geometric edges,
                // reducing depth comparison instability (shimmer/ripple).
                //
                // The offset scales with:
                // 1. Cascade texel size (larger offset for coarser cascades)
                // 2. Angle between normal and light (more offset at grazing angles)
                float NdotL = dot(worldNormal, sunDir);
                float sinTheta = sqrt(1.0 - NdotL * NdotL);  // sin of angle to light

                // Offset magnitude: larger at grazing angles, scaled by texel size
                float offsetScale = 1.0;  // Tunable multiplier
                float offset = cascadeTexelSizes[i] * offsetScale * sinTheta;

                // Apply offset along normal
                shadowWorld = vec4(world.xyz + worldNormal * offset, 1.0);
            }

            vLightSpacePos[i] = lightSpaceMatrices[i] * shadowWorld;
        } else {
            vLightSpacePos[i] = vec4(0.0);
        }
    }

    gl_Position = projectionMatrix * viewPos;
    vTexCoord = aTex;
    vAO = aAO;
}
