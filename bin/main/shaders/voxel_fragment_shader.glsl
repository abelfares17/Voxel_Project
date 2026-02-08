#version 150

// ═══════════════════════════════════════════════════════════════════════════════
// SHADOW FILTER MODE (compile-time constant)
// ═══════════════════════════════════════════════════════════════════════════════
// 0 = Single sample (reference - no filtering)
// 1 = Current PCF (Poisson disk, world-jittered rotation)
// 2 = World-stable PCF (NEW - pattern locked to world grid)
#define SHADOW_FILTER_MODE 2

// World-stable PCF configuration
#define WORLD_CELL_SIZE 4.0        // World units per cell (meters)
#define PCF_RADIUS_TEXELS 1.5      // PCF radius in shadow map texels
#define PCF_SAMPLE_COUNT 8         // Number of Poisson samples

// ═══════════════════════════════════════════════════════════════════════════════
// INPUTS
// ═══════════════════════════════════════════════════════════════════════════════

in vec3 vNormal;
in vec2 vTexCoord;
in vec3 vWorldPos;
in float vAO;

// CSM inputs
in float vViewDepth;
const int MAX_CASCADES = 4;
in vec4 vLightSpacePos[MAX_CASCADES];

// ═══════════════════════════════════════════════════════════════════════════════
// UNIFORMS
// ═══════════════════════════════════════════════════════════════════════════════

uniform sampler2D textureSampler;
uniform sampler2DArrayShadow shadowMapArray;

uniform vec3 sunDir;
uniform vec3 sunColor;
uniform float ambient;

uniform vec3 fogColor;
uniform float fogStart;
uniform float fogEnd;

uniform vec3 cameraPos;

// AO tuning
uniform float aoMin;
uniform float aoPower;
uniform int debugAO;

// Hemisphere ambient
uniform vec3 skyAmbientColor;
uniform vec3 groundAmbientColor;

// CSM parameters
uniform float cascadeSplits[MAX_CASCADES + 1];
uniform float cascadeTexelSizes[MAX_CASCADES];
uniform int cascadeCount;
uniform float shadowMapInvSize;  // 1.0 / shadowMapResolution

// Debug mode (simplified):
// 0 = off
// 1 = shadow factor grayscale
// 2 = cascade colors
// 3 = world-cell hash visualization (for world-stable PCF debugging)
uniform int debugShadow;

// Legacy uniforms (kept for compatibility, may be unused)
uniform int pcfMode;
uniform int forceCascade;
uniform int biasMode;

out vec4 fragColor;

// ═══════════════════════════════════════════════════════════════════════════════
// CONSTANTS
// ═══════════════════════════════════════════════════════════════════════════════

// 8-sample Poisson disk (compact, good coverage)
const vec2 POISSON_8[8] = vec2[](
    vec2(-0.613, -0.580),
    vec2( 0.170, -0.892),
    vec2( 0.766, -0.361),
    vec2( 0.943,  0.261),
    vec2( 0.374,  0.838),
    vec2(-0.395,  0.876),
    vec2(-0.880,  0.299),
    vec2(-0.299, -0.047)
);

// Cascade debug colors
const vec3 CASCADE_COLORS[4] = vec3[](
    vec3(1.0, 0.2, 0.2),   // Red for cascade 0 (near)
    vec3(0.2, 1.0, 0.2),   // Green for cascade 1 (mid)
    vec3(0.2, 0.2, 1.0),   // Blue for cascade 2 (far)
    vec3(1.0, 1.0, 0.2)    // Yellow for cascade 3 (if used)
);

// ═══════════════════════════════════════════════════════════════════════════════
// UTILITY FUNCTIONS
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Integer hash function for deterministic randomness.
 * Input: 2D integer cell coordinates
 * Output: float in [0, 1)
 */
float hashCell(ivec2 cell) {
    // Simple but effective integer hash
    int n = cell.x * 73856093 ^ cell.y * 19349663;
    n = (n << 13) ^ n;
    return float((n * (n * n * 15731 + 789221) + 1376312589) & 0x7fffffff) / float(0x7fffffff);
}

/**
 * Select the appropriate cascade based on view-space depth.
 * Returns cascade index (0 to cascadeCount-1).
 */
int selectCascade(float viewDepth) {
    // Support forced cascade mode for testing (if forceCascade >= 0)
    if (forceCascade >= 0 && forceCascade < cascadeCount) {
        return forceCascade;
    }

    for (int i = 0; i < cascadeCount; i++) {
        if (viewDepth < cascadeSplits[i + 1]) {
            return i;
        }
    }
    return cascadeCount - 1;
}

/**
 * Compute cascade-aware bias.
 * Scales with cascade texel size and surface angle to light.
 */
float computeBias(int cascade, vec3 normal, vec3 lightDir) {
    float NdotL = dot(normal, lightDir);
    float cosTheta = max(NdotL, 0.0);

    // Cascade scale factor (larger cascades need more bias)
    float cascadeScale = cascadeTexelSizes[cascade] / max(cascadeTexelSizes[0], 0.0001);
    cascadeScale = min(cascadeScale, 3.0);

    // Bias mode support (for A/B testing)
    if (biasMode == 1) {
        // Constant bias only
        return 0.001 * cascadeScale;
    } else if (biasMode == 2) {
        // Zero bias
        return 0.0;
    } else if (biasMode == 3) {
        // Doubled bias
        return (0.001 + 0.004 * (1.0 - cosTheta)) * cascadeScale;
    }

    // Default: normal bias with slope term
    float baseBias = 0.0005;
    float slopeBias = 0.002;
    return (baseBias + slopeBias * (1.0 - cosTheta)) * cascadeScale;
}

// ═══════════════════════════════════════════════════════════════════════════════
// SHADOW SAMPLING FUNCTIONS
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Single sample shadow (Mode 0 - reference).
 * No filtering, just direct depth comparison.
 */
float sampleShadowSingle(int cascade, vec2 shadowUV, float refDepth, float bias) {
    return texture(shadowMapArray, vec4(shadowUV, float(cascade), refDepth - bias));
}

/**
 * Current PCF implementation (Mode 1 - reference).
 * Uses world-position-based rotation which causes shimmer when sun moves.
 */
float sampleShadowCurrentPCF(int cascade, vec3 worldPos, vec2 shadowUV, float refDepth, float bias) {
    // World-jittered rotation (causes shimmer with sun movement)
    float rnd = fract(sin(dot(floor(worldPos.xz), vec2(127.1, 311.7))) * 43758.5453123);
    float angle = rnd * 6.2831853;
    float c = cos(angle);
    float s = sin(angle);
    mat2 rot = mat2(c, -s, s, c);

    // Cascade-scaled radius
    float cascadeScale = cascadeTexelSizes[cascade] / max(cascadeTexelSizes[0], 0.0001);
    float pcfRadius = 1.5 * min(cascadeScale, 3.0);
    vec2 texelSize = vec2(shadowMapInvSize);

    float shadow = 0.0;
    for (int i = 0; i < 8; ++i) {
        vec2 o = rot * POISSON_8[i];
        vec2 offset = o * pcfRadius * texelSize;
        shadow += texture(shadowMapArray, vec4(shadowUV + offset, float(cascade), refDepth - bias));
    }
    return shadow / 8.0;
}

/**
 * World-stable PCF implementation (Mode 2 - NEW).
 *
 * Key properties:
 * - Sampling pattern is anchored to world-space grid
 * - Pattern does NOT depend on sun angle or light-space UVs
 * - No temporal variation (deterministic from world position)
 * - Rotation angle is constant per world cell
 *
 * This isolates the filtering from light-space instabilities.
 */
float sampleShadowWorldStable(int cascade, vec3 worldPos, vec2 shadowUV, float refDepth, float bias) {
    // ─────────────────────────────────────────────────────────────────────
    // STEP 1: Quantize world position to stable grid cell
    // ─────────────────────────────────────────────────────────────────────
    // Using worldPos.xz only (terrain is voxel-based, Y doesn't matter for pattern)
    ivec2 cell = ivec2(floor(worldPos.xz / WORLD_CELL_SIZE));

    // ─────────────────────────────────────────────────────────────────────
    // STEP 2: Generate deterministic rotation angle from cell
    // ─────────────────────────────────────────────────────────────────────
    // This angle is CONSTANT for a given world cell, regardless of sun position
    float cellHash = hashCell(cell);
    float angle = cellHash * 6.2831853;  // [0, 2π)

    float c = cos(angle);
    float s = sin(angle);
    mat2 rot = mat2(c, -s, s, c);

    // ─────────────────────────────────────────────────────────────────────
    // STEP 3: Compute texel-based PCF radius
    // ─────────────────────────────────────────────────────────────────────
    // Radius is in shadow map texels, NOT dependent on sun angle
    vec2 texelSize = vec2(shadowMapInvSize);

    // Scale radius slightly with cascade for consistent visual softness
    float cascadeScale = cascadeTexelSizes[cascade] / max(cascadeTexelSizes[0], 0.0001);
    cascadeScale = min(cascadeScale, 2.0);  // Cap scaling
    float radius = PCF_RADIUS_TEXELS * cascadeScale;

    // ─────────────────────────────────────────────────────────────────────
    // STEP 4: Sample shadow map with rotated Poisson kernel
    // ─────────────────────────────────────────────────────────────────────
    float shadow = 0.0;

    for (int i = 0; i < PCF_SAMPLE_COUNT; ++i) {
        // Rotate the Poisson sample by cell-based angle
        vec2 rotatedSample = rot * POISSON_8[i];

        // Convert to UV offset (texel-based radius)
        vec2 offset = rotatedSample * radius * texelSize;

        // Sample shadow map
        shadow += texture(shadowMapArray, vec4(shadowUV + offset, float(cascade), refDepth - bias));
    }

    return shadow / float(PCF_SAMPLE_COUNT);
}

/**
 * Main shadow calculation function.
 * Routes to appropriate implementation based on SHADOW_FILTER_MODE.
 */
float calculateShadow(vec3 normal, vec3 lightDir, vec3 worldPos, float viewDepth) {
    int cascade = selectCascade(viewDepth);

    // Get light-space position for this cascade
    vec4 lightSpacePos = vLightSpacePos[cascade];
    vec3 projCoords = lightSpacePos.xyz / lightSpacePos.w;
    projCoords = projCoords * 0.5 + 0.5;

    // Out-of-bounds check - return fully lit
    if (projCoords.z > 1.0 || projCoords.z < 0.0 ||
        projCoords.x < 0.0 || projCoords.x > 1.0 ||
        projCoords.y < 0.0 || projCoords.y > 1.0) {
        return 1.0;
    }

    // Compute bias
    float bias = computeBias(cascade, normal, lightDir);

    // Route to appropriate shadow sampling function
    #if SHADOW_FILTER_MODE == 0
        // Mode 0: Single sample (reference)
        return sampleShadowSingle(cascade, projCoords.xy, projCoords.z, bias);
    #elif SHADOW_FILTER_MODE == 1
        // Mode 1: Current PCF (reference)
        return sampleShadowCurrentPCF(cascade, worldPos, projCoords.xy, projCoords.z, bias);
    #else
        // Mode 2: World-stable PCF (NEW)
        return sampleShadowWorldStable(cascade, worldPos, projCoords.xy, projCoords.z, bias);
    #endif
}

// ═══════════════════════════════════════════════════════════════════════════════
// MAIN
// ═══════════════════════════════════════════════════════════════════════════════

void main() {
    vec3 N = normalize(vNormal);
    vec3 L = normalize(sunDir);  // sunDir points TO the sun

    int cascade = selectCascade(vViewDepth);

    // ─────────────────────────────────────────────────────────────────────
    // DEBUG MODES (simplified to 3 essential modes)
    // ─────────────────────────────────────────────────────────────────────
    if (debugShadow > 0) {
        if (debugShadow == 1) {
            // Mode 1: Shadow factor grayscale
            float shadow = calculateShadow(N, L, vWorldPos, vViewDepth);
            fragColor = vec4(vec3(shadow), 1.0);
            return;
        } else if (debugShadow == 2) {
            // Mode 2: Cascade colors (blended with albedo)
            vec3 cascadeColor = CASCADE_COLORS[cascade];
            vec3 albedo = texture(textureSampler, vTexCoord).rgb;
            fragColor = vec4(mix(albedo, cascadeColor, 0.5), 1.0);
            return;
        } else if (debugShadow == 3) {
            // Mode 3: World-cell hash visualization
            // Shows the cell grid pattern - should remain STATIC when sun moves
            ivec2 cell = ivec2(floor(vWorldPos.xz / WORLD_CELL_SIZE));
            float cellHash = hashCell(cell);

            // Visualize as color based on hash
            vec3 cellColor = vec3(
                fract(cellHash * 13.37),
                fract(cellHash * 7.31),
                fract(cellHash * 3.71)
            );

            // Blend with shadow factor to show both
            float shadow = calculateShadow(N, L, vWorldPos, vViewDepth);
            fragColor = vec4(cellColor * shadow, 1.0);
            return;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // NORMAL RENDERING
    // ─────────────────────────────────────────────────────────────────────
    vec3 albedo = texture(textureSampler, vTexCoord).rgb * 0.8;
    float diff = max(dot(N, L), 0.0);
    float shadow = calculateShadow(N, L, vWorldPos, vViewDepth);

    // AO
    float aoMinVal = aoMin > 0.0 ? aoMin : 0.5;
    float aoPowerVal = aoPower > 0.0 ? aoPower : 2.0;
    float aoRemapped = pow(vAO, aoPowerVal);
    float aoFactor = mix(aoMinVal, 1.0, aoRemapped);

    if (debugAO == 1) {
        fragColor = vec4(vec3(aoRemapped), 1.0);
        return;
    }

    // Hemisphere ambient
    float hemisphereBlend = N.y * 0.5 + 0.5;
    vec3 skyColor = skyAmbientColor.r > 0.0 ? skyAmbientColor : vec3(0.6, 0.7, 0.9);
    vec3 groundColor = groundAmbientColor.r > 0.0 ? groundAmbientColor : vec3(0.4, 0.35, 0.3);
    vec3 hemisphereAmbient = mix(groundColor, skyColor, hemisphereBlend);

    vec3 indirect = hemisphereAmbient * ambient * aoFactor;
    vec3 direct = sunColor * diff * shadow;
    vec3 color = albedo * (indirect + direct);

    // Fog
    float dist = length(cameraPos - vWorldPos);
    float fogFactor = clamp((fogEnd - dist) / (fogEnd - fogStart), 0.0, 1.0);
    fragColor = mix(vec4(fogColor, 1.0), vec4(color, 1.0), fogFactor);
}
