#version 150

#define PERF_BISECT_STAGE 5
// 0 = normal
// 1 = shading trivial (albedo only)
// 2 = lighting sans AO/fog/shadow
// 3 = +AO only
// 4 = +fog only
// 5 = +shadow SINGLE A only (1 fetch, depth array, dynamic coords/layer)
// 6 = +shadow PCF A only (PCF_SAMPLE_COUNT taps)
// 7 = +leapfrog transition: A PCF + B SINGLE (PCF+1)
// 8 = full leapfrog: A PCF + B PCF (2*PCF) but NO dFdx/dFdy
// 9 = full original (including dFdx/dFdy hybrid)
// ── GPU MICRO-TESTS (depth vs color, constant vs dynamic) ──────────────────
// 10 = TEST B1: depth array, CONSTANT coords+layer (vec3(0.5,0.5,0.0)) — 1 fetch
// 11 = TEST B2: depth array, DYNAMIC coords+layer  (same as stage 5)   — 1 fetch
// 12 = TEST A1: COLOR array (GL_R32F), constant coords+layer            — 1 fetch
// 13 = TEST A2: COLOR array (GL_R32F), dynamic coords+layer             — 1 fetch
// ── HARDWARE COMPARE PATH (sampler2DArrayShadow) ───────────────────────────
// 14 = TEST C1: hardware compare SINGLE tap  (sampler2DArrayShadow, dynamic coords)
// 15 = TEST C2: hardware compare PCF 4 taps  (sampler2DArrayShadow per tap)

// ═══════════════════════════════════════════════════════════════════════════════
// SHADOW FILTER MODE (compile-time constant)
// ═══════════════════════════════════════════════════════════════════════════════
// 0 = Single sample (reference - no filtering)
// 1 = Current PCF (Poisson disk, world-jittered rotation)
// 2 = World-stable PCF (NEW - pattern locked to world grid)
#define SHADOW_FILTER_MODE 2

// World-stable PCF configuration
#define WORLD_CELL_SIZE 4.0        // World units per cell (meters) for PCF rotation
#define PCF_RADIUS_TEXELS 1.5      // PCF radius in shadow map texels
#define PCF_SAMPLE_COUNT 4         // Number of Poisson samples

// Stochastic crossfade (dither A/B) configuration
// Smaller = finer dither grain, larger = coarser pattern
#define WORLD_DITHER_CELL_SIZE 0.125  // World units per dither cell

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
in vec4 vLightSpacePos[MAX_CASCADES];   // Primary light-space positions
in vec4 vLightSpacePosB[MAX_CASCADES];  // Secondary light-space positions (for leapfrog)

// ═══════════════════════════════════════════════════════════════════════════════
// UNIFORMS
// ═══════════════════════════════════════════════════════════════════════════════

uniform sampler2D textureSampler;
uniform sampler2DArray shadowAtlas;         // Unified atlas: layers [offsetA..offsetA+C-1]=A, [offsetB..offsetB+C-1]=B
uniform sampler2DArray colorTestArray;      // GL_R32F color array (micro-test A)
uniform sampler2DArrayShadow shadowAtlasShadow; // Same atlas on unit 3, GL_COMPARE_REF_TO_TEXTURE+GL_LEQUAL

// Atlas layer offsets (0 or cascadeCount, swap on leapfrog SWAP)
uniform int atlasOffsetA;
uniform int atlasOffsetB;

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

// CSM parameters (primary)
uniform float cascadeSplits[MAX_CASCADES + 1];
uniform float cascadeTexelSizes[MAX_CASCADES];
uniform int cascadeCount;
uniform float shadowMapInvSize;  // 1.0 / shadowMapResolution

// Leapfrog crossfade parameters
uniform mat4 lightSpaceMatricesB[MAX_CASCADES];   // Secondary light-space matrices
uniform float cascadeSplitsB[MAX_CASCADES + 1];   // Secondary cascade splits
uniform float cascadeTexelSizesB[MAX_CASCADES];   // Secondary texel sizes
uniform float shadowCrossfade;                     // 0 = fully primary, 1 = fully secondary
uniform int leapfrogActive;                        // 1 if transitioning, 0 otherwise
uniform int blendMode;                             // 0 = dither (stochastic), 1 = linear (smooth mix)

// Debug mode (expanded for discontinuity diagnosis):
// 0 = off (normal rendering)
// 1 = shadow factor grayscale (raw shadow visibility)
// 2 = cascade colors (blended with albedo)
// 3 = world-cell hash visualization (for world-stable PCF debugging)
// 4 = fog factor grayscale (isolate fog contribution)
// 5 = cascade index as SOLID bands (detect cascade discontinuity)
// 6 = shadow A vs B difference (during leapfrog - shows blend discontinuity)
// 7 = lit color BEFORE fog (isolate fog amplification of flash)
uniform int debugShadow;

// Master shadow switch: 0 = no shadow fetches (shadow = 1.0), 1 = normal
uniform int shadowsEnabled;

// Legacy uniforms (kept for compatibility, may be unused)
uniform int pcfMode;
uniform int forceCascade;
uniform int biasMode;

// Temporal jitter for shadow anti-aliasing
uniform int frameIndex;  // Increments each frame (0-7 cycle)

out vec4 fragColor;

// ═══════════════════════════════════════════════════════════════════════════════
// CONSTANTS
// ═══════════════════════════════════════════════════════════════════════════════

// Halton sequence for temporal jittering (8 samples)
// Provides low-discrepancy sub-texel offsets for temporal accumulation
const vec2 HALTON_8[8] = vec2[](
    vec2(0.5, 0.333333) - 0.5,
    vec2(0.25, 0.666667) - 0.5,
    vec2(0.75, 0.111111) - 0.5,
    vec2(0.125, 0.444444) - 0.5,
    vec2(0.625, 0.777778) - 0.5,
    vec2(0.375, 0.222222) - 0.5,
    vec2(0.875, 0.555556) - 0.5,
    vec2(0.0625, 0.888889) - 0.5
);

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
 * Uses tan(angle) formula for proper handling of grazing angles.
 */
float computeBias(int cascade, vec3 normal, vec3 lightDir) {
    float NdotL = dot(normal, lightDir);
    float cosTheta = max(NdotL, 0.001);  // Clamp to avoid division by zero

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

    // Default: slope-scaled bias using tan(angle) for proper grazing angle handling
    // tan(angle) = sqrt(1 - cos²) / cos = sin/cos
    float sinTheta = sqrt(1.0 - cosTheta * cosTheta);
    float tanTheta = sinTheta / cosTheta;

    // Clamp tan to reasonable range (tan(85°) ≈ 11.4)
    tanTheta = min(tanTheta, 10.0);

    float baseBias = 0.0005;
    float slopeBias = 0.0003;  // Lower coefficient since tan grows faster
    return (baseBias + slopeBias * tanTheta) * cascadeScale;
}

// ═══════════════════════════════════════════════════════════════════════════════
// SHADOW SAMPLING FUNCTIONS
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Single sample shadow (Mode 0 - reference).
 * No filtering, just direct depth comparison.
 */
float sampleShadowSingle(int cascade, vec2 shadowUV, float refDepth, float bias) {
    float compareDepth = refDepth - bias;
    float depth = texture(shadowAtlas, vec3(shadowUV, float(atlasOffsetA + cascade))).r;
    return (depth < compareDepth) ? 0.0 : 1.0;
}

/**
 * Current PCF implementation (Mode 1 - reference).
 * Uses world-position-based rotation which causes shimmer when sun moves.
 */
float sampleShadowdCurrentPCF(int cascade, vec3 worldPos, vec2 shadowUV, float refDepth, float bias) {
    float compareDepth = refDepth - bias;

    ivec2 cell = ivec2(floor(worldPos.xz / WORLD_CELL_SIZE));
    float cellHash = hashCell(cell);
    float angle = cellHash * 6.2831853;

    float c = cos(angle);
    float s = sin(angle);
    mat2 rot = mat2(c, -s, s, c);

    vec2 texelSize = vec2(shadowMapInvSize);

    float cascadeScale = cascadeTexelSizes[cascade] / max(cascadeTexelSizes[0], 0.0001);
    cascadeScale = min(cascadeScale, 2.0);
    float radius = PCF_RADIUS_TEXELS * cascadeScale;

    float shadow = 0.0;
    for (int i = 0; i < PCF_SAMPLE_COUNT; ++i) {
        vec2 rotatedSample = rot * POISSON_8[i];
        vec2 offset = rotatedSample * radius * texelSize;

        float depth = texture(shadowAtlas, vec3(shadowUV + offset, float(atlasOffsetA + cascade))).r;
        shadow += (depth < compareDepth) ? 0.0 : 1.0;
    }

    return shadow / float(PCF_SAMPLE_COUNT);
}

/**
 * World-stable PCF implementation (Mode 2).
 *
 * Key properties:
 * - Sampling pattern is anchored to world-space grid (stable per pixel)
 * - Pattern does NOT depend on sun angle or light-space UVs
 * - Rotation angle is constant per world cell
 */
float sampleShadowWorldStable(int cascade, vec3 worldPos, vec2 shadowUV, float refDepth, float bias) {
    float compareDepth = refDepth - bias;
    // ─────────────────────────────────────────────────────────────────────
    // STEP 1: Quantize world position to stable grid cell
    // ─────────────────────────────────────────────────────────────────────
    ivec2 cell = ivec2(floor(worldPos.xz / WORLD_CELL_SIZE));

    // ─────────────────────────────────────────────────────────────────────
    // STEP 2: Generate deterministic rotation angle from cell
    // ─────────────────────────────────────────────────────────────────────
    float cellHash = hashCell(cell);
    float angle = cellHash * 6.2831853;  // [0, 2π)

    float c = cos(angle);
    float s = sin(angle);
    mat2 rot = mat2(c, -s, s, c);

    // ─────────────────────────────────────────────────────────────────────
    // STEP 3: Compute texel-based PCF radius
    // ─────────────────────────────────────────────────────────────────────
    vec2 texelSize = vec2(shadowMapInvSize);

    float cascadeScale = cascadeTexelSizes[cascade] / max(cascadeTexelSizes[0], 0.0001);
    cascadeScale = min(cascadeScale, 2.0);
    float radius = PCF_RADIUS_TEXELS * cascadeScale;

    // ─────────────────────────────────────────────────────────────────────
    // STEP 4: Sample shadow map with rotated Poisson kernel
    // ─────────────────────────────────────────────────────────────────────
    float shadow = 0.0;

    for (int i = 0; i < PCF_SAMPLE_COUNT; ++i) {
        vec2 rotatedSample = rot * POISSON_8[i];
        vec2 offset = rotatedSample * radius * texelSize;

        float depth = texture(shadowAtlas, vec3(shadowUV + offset, float(atlasOffsetA + cascade))).r;
        shadow += (depth < compareDepth) ? 0.0 : 1.0;
    }

    return shadow / float(PCF_SAMPLE_COUNT);
}

/**
 * Single sample shadow from the atlas at a given layer offset.
 * Used during leapfrog transitions to reduce GPU cost of 2x shadow sampling.
 */
float sampleShadowSingleFromArray(int layerOffset, int cascade,
                                   vec3 projCoords, float bias) {
    // Out-of-bounds check - return fully lit
    if (projCoords.z > 1.0 || projCoords.z < 0.0 ||
        projCoords.x < 0.0 || projCoords.x > 1.0 ||
        projCoords.y < 0.0 || projCoords.y > 1.0) {
        return 1.0;
    }

    float compareDepth = projCoords.z - bias;
    float depth = texture(shadowAtlas, vec3(projCoords.xy, float(layerOffset + cascade))).r;
    return (depth < compareDepth) ? 0.0 : 1.0;
}

/**
 * Manual depth-compare shadow sampling (NO hardware compare).
 * layerOffset: atlasOffsetA or atlasOffsetB — selects which half of the atlas.
 */
float sampleShadowPCFFromArray(
    int layerOffset,
    int cascade,
    vec3 worldPos,
    vec3 projCoords,
    float bias)
{
    // Out-of-bounds → fully lit
    if (projCoords.z > 1.0 || projCoords.z < 0.0 ||
        projCoords.x < 0.0 || projCoords.x > 1.0 ||
        projCoords.y < 0.0 || projCoords.y > 1.0) {
        return 1.0;
    }

    float compareDepth = projCoords.z - bias;
    float layer = float(layerOffset + cascade);

    #if SHADOW_FILTER_MODE == 0
        // Single sample
        float depth = texture(shadowAtlas, vec3(projCoords.xy, layer)).r;
        return depth < compareDepth ? 0.0 : 1.0;

    #elif SHADOW_FILTER_MODE == 1

        float rnd = fract(sin(dot(floor(worldPos.xz), vec2(127.1, 311.7))) * 43758.5453123);
        float angle = rnd * 6.2831853;
        float c = cos(angle);
        float s = sin(angle);
        mat2 rot = mat2(c, -s, s, c);

        float cascadeScale = cascadeTexelSizes[cascade] /
                             max(cascadeTexelSizes[0], 0.0001);
        float pcfRadius = 1.5 * min(cascadeScale, 3.0);

        vec2 texelSize = vec2(shadowMapInvSize);

        float shadow = 0.0;

        for (int i = 0; i < 8; ++i) {
            vec2 o = rot * POISSON_8[i];
            vec2 offset = o * pcfRadius * texelSize;

            vec3 uvw = vec3(projCoords.xy + offset, layer);
            float depth = texture(shadowAtlas, uvw).r;

            shadow += (depth < compareDepth) ? 0.0 : 1.0;
        }

        return shadow / 8.0;

    #else

        ivec2 cell = ivec2(floor(worldPos.xz / WORLD_CELL_SIZE));
        float cellHash = hashCell(cell);
        float angle = cellHash * 6.2831853;

        float c = cos(angle);
        float s = sin(angle);
        mat2 rot = mat2(c, -s, s, c);

        vec2 texelSize = vec2(shadowMapInvSize);

        float cascadeScale = cascadeTexelSizes[cascade] /
                             max(cascadeTexelSizes[0], 0.0001);
        cascadeScale = min(cascadeScale, 2.0);

        float radius = PCF_RADIUS_TEXELS * cascadeScale;

        float shadow = 0.0;

        for (int i = 0; i < PCF_SAMPLE_COUNT; ++i) {
            vec2 rotatedSample = rot * POISSON_8[i];
            vec2 offset = rotatedSample * radius * texelSize;

            vec3 uvw = vec3(projCoords.xy + offset, layer);
            float depth = texture(shadowAtlas, uvw).r;

            shadow += (depth < compareDepth) ? 0.0 : 1.0;
        }

        return shadow / float(PCF_SAMPLE_COUNT);

    #endif
}

/**
 * Main shadow calculation function.
 * Routes to appropriate implementation based on SHADOW_FILTER_MODE.
 * Supports leapfrog crossfade between two shadow maps.
 *
 * QUALITY: During transition, uses FULL PCF for both A and B to maintain
 * consistent shadow quality. This ensures the transition looks smooth
 * without any quality drop.
 */
float calculateShadow(vec3 normal, vec3 lightDir, vec3 worldPos, float viewDepth) {
    
    #if PERF_BISECT_STAGE <= 4
        return 1.0;
    #endif
    // Use same cascade selection for A and B (splits are identical during transition)
    int cascade = selectCascade(viewDepth);

    // Compute bias
    float bias = computeBias(cascade, normal, lightDir);

    // Primary light-space projection
    vec4 lightSpacePos = vLightSpacePos[cascade];
    vec3 projCoords = lightSpacePos.xyz / lightSpacePos.w;
    projCoords = projCoords * 0.5 + 0.5;

    #if PERF_BISECT_STAGE == 5
    // SINGLE SAMPLE A only (1 fetch, depth array, dynamic coords+layer)
        return sampleShadowSingleFromArray(atlasOffsetA, cascade, projCoords, bias);

    #elif PERF_BISECT_STAGE == 6
    // PCF A only (PCF_SAMPLE_COUNT fetch)
        return sampleShadowPCFFromArray(atlasOffsetA, cascade, worldPos, projCoords, bias);

    #elif PERF_BISECT_STAGE == 10
    // TEST B1: depth array, CONSTANT coords+layer (1 fetch) — baseline latency
        float refB1 = projCoords.z - bias;
        float depB1 = texture(shadowAtlas, vec3(0.5, 0.5, float(atlasOffsetA))).r;
        return (depB1 < refB1) ? 0.0 : 1.0;

    #elif PERF_BISECT_STAGE == 11
    // TEST B2: depth array, DYNAMIC coords+layer (1 fetch) — same as stage 5
        return sampleShadowSingleFromArray(atlasOffsetA, cascade, projCoords, bias);

    #elif PERF_BISECT_STAGE == 12
    // TEST A1: COLOR array (GL_R32F), constant coords+layer (1 fetch)
        float depA1 = texture(colorTestArray, vec3(0.5, 0.5, 0.0)).r;
        return depA1;  // Return raw value (no depth compare needed for color)

    #elif PERF_BISECT_STAGE == 13
    // TEST A2: COLOR array (GL_R32F), dynamic coords+layer (1 fetch)
        float depA2 = texture(colorTestArray, vec3(projCoords.xy, float(cascade))).r;
        return depA2;  // Return raw value (no depth compare needed for color)

    #elif PERF_BISECT_STAGE == 14
    // TEST C1: hardware compare SINGLE tap (sampler2DArrayShadow, dynamic coords)
    // texture(sampler2DArrayShadow, vec4(s, t, layer, refDepth)) → 0.0 or 1.0
        float layer14 = float(atlasOffsetA + cascade);
        return texture(shadowAtlasShadow, vec4(projCoords.xy, layer14, projCoords.z - bias));

    #elif PERF_BISECT_STAGE == 15
    // TEST C2: hardware compare PCF 4 taps (sampler2DArrayShadow per tap)
        float layer15 = float(atlasOffsetA + cascade);
        float compareDepth15 = projCoords.z - bias;
        ivec2 cell15 = ivec2(floor(worldPos.xz / WORLD_CELL_SIZE));
        float angle15 = hashCell(cell15) * 6.2831853;
        float c15 = cos(angle15);
        float s15 = sin(angle15);
        mat2 rot15 = mat2(c15, -s15, s15, c15);
        float cascadeScale15 = min(cascadeTexelSizes[cascade] / max(cascadeTexelSizes[0], 0.0001), 2.0);
        float radius15 = PCF_RADIUS_TEXELS * cascadeScale15;
        float shadow15 = 0.0;
        for (int i = 0; i < PCF_SAMPLE_COUNT; i++) {
            vec2 offset = (rot15 * POISSON_8[i]) * radius15 * shadowMapInvSize;
            shadow15 += texture(shadowAtlasShadow, vec4(projCoords.xy + offset, layer15, compareDepth15));
        }
        return shadow15 / float(PCF_SAMPLE_COUNT);

    #else
    // Not transitioning → A only (stages 7-9 and fallback)
    float shadowA = sampleShadowPCFFromArray(atlasOffsetA, cascade, worldPos, projCoords, bias);
    if (leapfrogActive != 1) {
        return shadowA;
    }

    // Fast paths
    if (shadowCrossfade <= 0.001) {
        return shadowA;
    }

    // Compute B once (needed for hybrid "front-like" dither)
    vec4 lightSpacePosB = vLightSpacePosB[cascade];
    float wB = lightSpacePosB.w;

    if (abs(wB) <= 0.0001) {
        return shadowA; // defensive fallback
    }

    vec3 projCoordsB = lightSpacePosB.xyz / wB;
    projCoordsB = projCoordsB * 0.5 + 0.5;

    #if PERF_BISECT_STAGE == 7
        // Transition: A PCF + B SINGLE (PCF + 1 fetch)
        float shadowB = sampleShadowSingleFromArray(atlasOffsetB, cascade, projCoordsB, bias);
        if (blendMode == 1) return mix(shadowA, shadowB, shadowCrossfade);
        float n = fract(sin(dot(worldPos.xz * 53.0, vec2(127.1, 311.7))) * 43758.5453123);
        return (n < shadowCrossfade) ? shadowB : shadowA;

    #elif PERF_BISECT_STAGE == 8
        // Transition: A PCF + B PCF (2*PCF fetch) sans dFdx/dFdy
        float shadowB = sampleShadowPCFFromArray(atlasOffsetB, cascade, worldPos, projCoordsB, bias);
        if (blendMode == 1) return mix(shadowA, shadowB, shadowCrossfade);
        float n = fract(sin(dot(worldPos.xz * 53.0, vec2(127.1, 311.7))) * 43758.5453123);
        return (n < shadowCrossfade) ? shadowB : shadowA;

    #elif PERF_BISECT_STAGE == 9
        // HYBRID DITHER CROSSFADE avec dFdx/dFdy
        float shadowB = sampleShadowPCFFromArray(atlasOffsetB, cascade, worldPos, projCoordsB, bias);
        float eA = abs(dFdx(shadowA)) + abs(dFdy(shadowA));
        float eB = abs(dFdx(shadowB)) + abs(dFdy(shadowB));
        float edge = max(eA, eB);
        const float EDGE_GAIN = 40.0;
        const float EDGE_ADVANCE = 0.35;
        edge = clamp(edge * EDGE_GAIN, 0.0, 1.0);
        float t = clamp(shadowCrossfade + edge * EDGE_ADVANCE, 0.0, 1.0);
        t = smoothstep(0.0, 1.0, t);
        float n = fract(sin(dot(worldPos.xz * 53.0, vec2(127.1, 311.7))) * 43758.5453123);
        float chooseB = step(n, t);
        return (chooseB > 0.5) ? shadowB : shadowA;

    #else
        // Fallback: linear blend
        float shadowB = sampleShadowPCFFromArray(atlasOffsetB, cascade, worldPos, projCoordsB, bias);
        return mix(shadowA, shadowB, shadowCrossfade);
    #endif  // inner: STAGE 7/8/9

    #endif  // outer: STAGE 5/6/10/11/12/13/else
}

// ═══════════════════════════════════════════════════════════════════════════════
// MAIN
// ═══════════════════════════════════════════════════════════════════════════════

void main() {
    vec3 N = normalize(vNormal);
    vec3 L = normalize(sunDir);  // sunDir points TO the sun

    int cascade = selectCascade(vViewDepth);

    if (debugShadow > 0) { fragColor = vec4(1,0,0,1); return; }

    // ─────────────────────────────────────────────────────────────────────
    // DEBUG MODES (expanded for discontinuity diagnosis)
    // ─────────────────────────────────────────────────────────────────────
    if (debugShadow > 0) {
        if (debugShadow == 1) {
            // Mode 1: Shadow factor grayscale (raw shadow visibility)
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
        } else if (debugShadow == 4) {
            // Mode 4: Fog factor grayscale (isolate fog contribution)
            // White = no fog, Black = full fog
            float dist = length(cameraPos - vWorldPos);
            float fogFac = clamp((fogEnd - dist) / (fogEnd - fogStart), 0.0, 1.0);
            fragColor = vec4(vec3(fogFac), 1.0);
            return;
        } else if (debugShadow == 5) {
            // Mode 5: Cascade index as SOLID bands (detect cascade boundary jumps)
            // Pure colors with NO shadow - detects if cascade selection changes abruptly
            fragColor = vec4(CASCADE_COLORS[cascade], 1.0);
            return;
        } else if (debugShadow == 6) {
            // Mode 6: Shadow A vs B difference (leapfrog blend discontinuity)
            // Shows |shadowA - shadowB| as red intensity
            // If flash happens, this will show bright red where discontinuity occurs
            float bias = computeBias(cascade, N, L);

            vec4 lightSpacePos = vLightSpacePos[cascade];
            vec3 projCoords = lightSpacePos.xyz / lightSpacePos.w;
            projCoords = projCoords * 0.5 + 0.5;
            float shadowA = sampleShadowPCFFromArray(atlasOffsetA, cascade, vWorldPos, projCoords, bias);

            float shadowB = shadowA;  // Default if not transitioning
            if (leapfrogActive == 1) {
                vec4 lightSpacePosB = vLightSpacePosB[cascade];
                vec3 projCoordsB = lightSpacePosB.xyz / lightSpacePosB.w;
                projCoordsB = projCoordsB * 0.5 + 0.5;
                shadowB = sampleShadowPCFFromArray(atlasOffsetB, cascade, vWorldPos, projCoordsB, bias);
            }

            float diff = abs(shadowA - shadowB);
            // Amplify difference for visibility (scale by 5x)
            diff = min(diff * 5.0, 1.0);

            // Red = difference, Green = shadowA, Blue = shadowB
            fragColor = vec4(diff, shadowA, shadowB, 1.0);
            return;
        } else if (debugShadow == 7) {
            // Mode 7: Lit color BEFORE fog application
            // Isolates whether flash comes from lighting or fog amplification
            vec3 albedo = texture(textureSampler, vTexCoord).rgb * 0.8;
            float diff_lighting = max(dot(N, L), 0.0);
            float shadow = calculateShadow(N, L, vWorldPos, vViewDepth);

            float aoMinVal = aoMin > 0.0 ? aoMin : 0.5;
            float aoPowerVal = aoPower > 0.0 ? aoPower : 2.0;
            float aoRemapped = pow(vAO, aoPowerVal);
            float aoFactor = mix(aoMinVal, 1.0, aoRemapped);

            float hemisphereBlend = N.y * 0.5 + 0.5;
            vec3 skyCol = skyAmbientColor.r > 0.0 ? skyAmbientColor : vec3(0.6, 0.7, 0.9);
            vec3 groundCol = groundAmbientColor.r > 0.0 ? groundAmbientColor : vec3(0.4, 0.35, 0.3);
            vec3 hemisphereAmbient = mix(groundCol, skyCol, hemisphereBlend);

            vec3 indirect = hemisphereAmbient * ambient * aoFactor;
            vec3 direct = sunColor * diff_lighting * shadow;
            vec3 colorNoFog = albedo * (indirect + direct);

            // Output lit color WITHOUT fog
            fragColor = vec4(colorNoFog, 1.0);
            return;
        } else if (debugShadow == 8) {
            // Mode 8: w-Component Validity Heatmap
            // Diagnoses whether vLightSpacePosB has valid w for safe division
            //
            // Color coding:
            // - Blue = BOTH A and B have valid w (expected in all frames now)
            // - Red = A has invalid w (should never happen - indicates shader bug)
            // - Green = B has invalid w (used to happen before fix when not transitioning)
            // - Yellow = Both invalid (critical error)
            // - Cyan intensity = leapfrogActive state

            float wA = vLightSpacePos[cascade].w;
            float wB = vLightSpacePosB[cascade].w;

            // Valid means: not NaN, not Inf, and magnitude > epsilon
            bool validA = abs(wA) > 0.0001 && wA == wA && abs(wA) < 1e10;
            bool validB = abs(wB) > 0.0001 && wB == wB && abs(wB) < 1e10;

            vec3 color = vec3(0.0);

            if (!validA && !validB) {
                color = vec3(1.0, 1.0, 0.0);  // Yellow = both invalid
            } else if (!validA) {
                color = vec3(1.0, 0.0, 0.0);  // Red = A invalid
            } else if (!validB) {
                color = vec3(0.0, 1.0, 0.0);  // Green = B invalid
            } else {
                color = vec3(0.0, 0.0, 1.0);  // Blue = both valid
            }

            // Add cyan tint when leapfrogActive is on
            if (leapfrogActive == 1) {
                color += vec3(0.0, 0.3, 0.3);
            }

            fragColor = vec4(color, 1.0);
            return;
        } else if (debugShadow == 9) {
            // Mode 9: Leapfrog State Visualization
            // Shows current leapfrog state with crossfade progress
            //
            // Background: shadow factor
            // Red channel: leapfrogActive (0 or 1)
            // Green channel: crossfade progress (0 to 1)
            // Blue channel: shadow factor

            float shadow = calculateShadow(N, L, vWorldPos, vViewDepth);

            float r = float(leapfrogActive);
            float g = shadowCrossfade;
            float b = shadow;

            fragColor = vec4(r, g, b, 1.0);
            return;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // PERF BISECT SWITCH
    // ─────────────────────────────────────────────────────────────────────
    
    #if PERF_BISECT_STAGE == 0
        fragColor = vec4(1.0, 0.0, 1.0, 1.0);
        return;
    #endif

    vec3 albedo = texture(textureSampler, vTexCoord).rgb * 0.8;
    float diff = max(dot(N, L), 0.0);

    #if PERF_BISECT_STAGE == 1
        fragColor = vec4(albedo, 1.0);
        return;

    #elif PERF_BISECT_STAGE == 2
        // Lighting minimal (pas AO, pas fog, pas shadow)
        vec3 color = albedo * (ambient + sunColor * diff);
        fragColor = vec4(color, 1.0);
        return;

    #elif PERF_BISECT_STAGE == 3
        // + AO only
        float aoMinVal = aoMin > 0.0 ? aoMin : 0.5;
        float aoPowerVal = aoPower > 0.0 ? aoPower : 2.0;
        float aoRemapped = pow(vAO, aoPowerVal);
        float aoFactor = mix(aoMinVal, 1.0, aoRemapped);

        vec3 color = albedo * (ambient * aoFactor + sunColor * diff);
        fragColor = vec4(color, 1.0);
        return;

    #elif PERF_BISECT_STAGE == 4
        // + fog only (no AO, no shadow)
        vec3 color = albedo * (ambient + sunColor * diff);
        float dist = length(cameraPos - vWorldPos);
        float fogFactor = clamp((fogEnd - dist) / (fogEnd - fogStart), 0.0, 1.0);
        fragColor = mix(vec4(fogColor, 1.0), vec4(color, 1.0), fogFactor);
        return;

    #else
        // Shadow path (stages 5..9 go through calculateShadow)
        float shadow = (shadowsEnabled == 1) ? calculateShadow(N, L, vWorldPos, vViewDepth) : 1.0;

        // AO
        float aoMinVal = aoMin > 0.0 ? aoMin : 0.5;
        float aoPowerVal = aoPower > 0.0 ? aoPower : 2.0;
        float aoRemapped = pow(vAO, aoPowerVal);
        float aoFactor = mix(aoMinVal, 1.0, aoRemapped);

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
        return;
    #endif
}
