# Leapfrog Flash Diagnostic Checklist

## Engineering Invariant

> **In transition vs non-transition frames, the pipeline must be as invariant as possible: same texture units bound, same samplers active, same uniform arrays populated, same vertex varyings computed, and the fragment shader must run the same primary path whenever crossfade=0. Any difference in state is a potential cause of flash and must be eliminated BEFORE declaring the flash "inherent".**

---

## TASK 1: Comprehensive Checklist

### Category 1: CPU-side Leapfrog State Machine Correctness

| Item | Status | Evidence/Method |
|------|--------|-----------------|
| 1.1 Transition start correctly sets `isTransitioning=true`, `crossfadeProgress=0` | [VERIFIED] | Log at TRANSITION_START shows `crossfade=0, leapfrogActive=1` |
| 1.2 Crossfade progresses monotonically from 0 to 1 | [VERIFIED] | Log shows incremental progress via `CROSSFADE_SPEED=0.1f` |
| 1.3 Stabilization frame holds at crossfade=1.0 before swap | [VERIFIED] | Log shows STABILIZATION_FRAME state, `awaitingStabilizationFrame` logic |
| 1.4 Swap correctly toggles `primaryIsA` and clears transition state | [VERIFIED] | Log shows SWAP_FRAME state, `primaryIsA = !primaryIsA` |
| 1.5 Primary/secondary array IDs are consistent with `primaryIsA` | [VERIFIED] | `getPrimaryShadowMapArray()` and `getSecondaryShadowMapArray()` use `primaryIsA` correctly |

### Category 2: MAP↔MATRIX LOCK Per-Array

| Item | Status | Evidence/Method |
|------|--------|-----------------|
| 2.1 Primary array (A) sampled with matrices used when rendered | [VERIFIED] | `lastRenderedMatrixHashA` verification shows "MAP↔MATRIX LOCK: OK" |
| 2.2 Secondary array (B) sampled with matrices used when rendered | [VERIFIED] | `cascadeDataB` is computed immediately before `renderShadowMap(secondaryArray, cascadeDataB)` |
| 2.3 Hash verification correct after swap | [VERIFIED] | CRITICAL FIX #3 updates `lastRenderedMatrixHashA = matrixHashAfter` after swap |
| 2.4 Frozen matrices not recomputed during transition | [VERIFIED] | `frozenCascadeDataA = deepCopyCascadeData(cascadeData)` captures at transition start |

### Category 3: Texture Binding Invariance

| Item | Status | Evidence/Method |
|------|--------|-----------------|
| 3.1 Same texture units used (1 for A, 2 for B) | [VERIFIED] | VoxelRenderer lines 282, 288: `setTextureArray("shadowMapArray", primaryShadowMap, 1)` and unit 2 |
| 3.2 When NOT transitioning, both units bound to same texture | [UNVERIFIED] | Code binds primary to unit 1, but unit 2 gets `primaryShadowMap` when not transitioning - **POTENTIAL ISSUE**: binding same texture to two units vs binding different texture |
| 3.3 Sampler parameters identical (compare mode, func, filtering) | [VERIFIED] | Both arrays created with identical `GL_LINEAR`, `GL_CLAMP_TO_EDGE`, `GL_LEQUAL` in ShadowRenderer constructor |
| 3.4 Pre-bound to fixed units to prevent driver warnings | [VERIFIED] | Lines 267-271: Pre-bind to units 5 and 6 (different from sampling units 1 and 2 - **POTENTIAL ISSUE**) |

### Category 4: Uniform Upload Invariance

| Item | Status | Evidence/Method |
|------|--------|-----------------|
| 4.1 `leapfrogActive` set every frame | [VERIFIED] | Line 206: `setUniform("leapfrogActive", leapfrogTransitioning ? 1 : 0)` |
| 4.2 `shadowCrossfade` set every frame | [VERIFIED] | Line 207: `setUniform("shadowCrossfade", crossfadeProgress)` |
| 4.3 B uniforms set when NOT transitioning | [VERIFIED] | Lines 244-250: `else if (cascadeData != null)` block copies A→B uniforms |
| 4.4 B uniform arrays fully populated (no garbage) | [UNVERIFIED] | Need to verify all 4 cascade slots are set, not just `cascadeCount` slots |
| 4.5 `cascadeTexelSizesB` set when NOT transitioning | [VERIFIED] | Line 249: `setUniformFloatArray("cascadeTexelSizesB", cascadeData.texelSizes())` |
| 4.6 No partial uploads (all arrays same length) | [NEEDS TOOLING] | Add logging to verify array lengths match |

### Category 5: Shader Control-Flow Invariance

| Item | Status | Evidence/Method |
|------|--------|-----------------|
| 5.1 crossfade=0 returns `shadowA` without sampling B | [VERIFIED] | Shader lines 393-395: `if (shadowCrossfade < 0.001) return shadowA;` |
| 5.2 Normal mode (leapfrogActive=0) uses identical `sampleShadowPCFFromArray` | [VERIFIED] | Shader line 419: same function call as line 389 |
| 5.3 Cascade selection uses same function in both paths | [VERIFIED] | `selectCascade(viewDepth)` called once at line 374, used by both paths |
| 5.4 Bias computation identical in both paths | [VERIFIED] | `computeBias(cascade, normal, lightDir)` called once at line 377 |
| 5.5 OOB logic identical in both paths | [UNVERIFIED] | Both paths call `sampleShadowPCFFromArray` which has OOB check - BUT at crossfade=0, we never sample B so OOB is not tested on B's projCoords |

### Category 6: Varying Generation Invariance

| Item | Status | Evidence/Method |
|------|--------|-----------------|
| 6.1 `vLightSpacePos[i]` always valid for active cascades | [VERIFIED] | Vertex shader lines 80-92: computed for `i < cascadeCount` |
| 6.2 `vLightSpacePosB[i]` valid when leapfrogActive=1 | [VERIFIED] | Line 84: `vLightSpacePosB[i] = lightSpaceMatricesB[i] * shadowWorldB` |
| 6.3 `vLightSpacePosB[i]` set to `vec4(0)` when leapfrogActive=0 | **[PROBLEM]** | Line 86: `vLightSpacePosB[i] = vec4(0.0);` - **w=0 causes undefined behavior if divided** |
| 6.4 No division by w=0 on B path when not needed | [UNVERIFIED] | At crossfade=0 we return early, BUT vertex shader still interpolates `vLightSpacePosB` with w=0 which could affect GPU state |
| 6.5 Normal offset computed identically for A and B | [VERIFIED] | Lines 68-77: offset computed per-cascade with correct texel sizes |

### Category 7: Numerical Validity Checks

| Item | Status | Evidence/Method |
|------|--------|-----------------|
| 7.1 No NaN/Inf in `lightSpacePos.xyz` | [NEEDS TOOLING] | Add debug mode to visualize invalid values |
| 7.2 No NaN/Inf in `projCoords` after perspective divide | [NEEDS TOOLING] | Add debug mode |
| 7.3 `w` not near zero for valid shadow coords | [NEEDS TOOLING] | Add check: `if (abs(w) < 0.0001)` |
| 7.4 `projCoords.z` in valid range [0,1] | [PARTIALLY VERIFIED] | OOB check exists but returns 1.0 (fully lit) - correct behavior |
| 7.5 B-side values valid even when not sampled | **[PROBLEM]** | `vLightSpacePosB = vec4(0)` when not transitioning → w=0, divide would produce NaN/Inf |

### Category 8: Out-of-Bounds Behavior

| Item | Status | Evidence/Method |
|------|--------|-----------------|
| 8.1 OOB returns deterministic value (1.0 = fully lit) | [VERIFIED] | Shader lines 294-298, 309-313 |
| 8.2 OOB rate consistent across frames | [NEEDS TOOLING] | Add counter/histogram debug mode |
| 8.3 OOB spikes detected at TRANSITION_START | [NEEDS TOOLING] | Add logging of OOB percentage per frame |
| 8.4 B-side OOB handled identically to A-side | [VERIFIED] | Same `sampleShadowPCFFromArray` function used for both |

### Category 9: Cascade Selection Stability

| Item | Status | Evidence/Method |
|------|--------|-----------------|
| 9.1 Cascade boundaries stable during leapfrog | [VERIFIED] | **Debug mode 5 (Cascade SOLID) shows cascade regions are perfectly immobile during leapfrogging** |
| 9.2 Split distances copied from A to B | [VERIFIED] | ShadowRenderer lines 676-678: `System.arraycopy(frozenCascadeDataA.splitDistances(), ...)` |
| 9.3 Same cascade index used for A and B sampling | [VERIFIED] | Fragment shader: single `cascade` variable used for both |

### Category 10: Shadow Factor Distribution

| Item | Status | Evidence/Method |
|------|--------|-----------------|
| 10.1 Per-frame shadow factor mean/min/max logged | [NEEDS TOOLING] | Add GPU-side or CPU-side histogram |
| 10.2 Spike detection at TRANSITION_START | [NEEDS TOOLING] | Compare distribution between frames N-1 and N (transition) |
| 10.3 Shadow A vs B difference visualized | [VERIFIED] | Debug mode 6 exists - shows `abs(shadowA - shadowB)` |

### Category 11: Fog/Illumination Coupling

| Item | Status | Evidence/Method |
|------|--------|-----------------|
| 11.1 Flash correlates with shadow factor (not fog alone) | [UNVERIFIED] | Need to test debug mode 4 (fog only) and mode 7 (lit before fog) |
| 11.2 Shadow factor contribution isolated | [VERIFIED] | Debug mode 1 shows raw shadow factor |
| 11.3 Fog factor contribution isolated | [VERIFIED] | Debug mode 4 shows fog factor |
| 11.4 Pre-fog color isolated | [VERIFIED] | Debug mode 7 shows lit color before fog |

### Category 12: GPU Ordering Assumptions

| Item | Status | Evidence/Method |
|------|--------|-----------------|
| 12.1 Uniforms/bindings updated before draw | [VERIFIED] | VoxelRenderer sets all uniforms before `renderVisibleChunks()` |
| 12.2 `glFinish()` after B render (diagnostic, not fix) | [VERIFIED] | ShadowRenderer line 692: `glFinish()` after rendering secondary |
| 12.3 No race between FBO write and texture sample | [VERIFIED] | glFinish ensures completion |

### Category 13: GLSL Undefined Behavior Triggers

| Item | Status | Evidence/Method |
|------|--------|-----------------|
| 13.1 Uninitialized uniforms | [VERIFIED] | B uniforms set to A values when not transitioning (lines 244-250) |
| 13.2 Uninitialized varyings | **[PROBLEM]** | `vLightSpacePosB = vec4(0)` - valid but w=0 is problematic |
| 13.3 Dynamic indexing with out-of-range cascade | [VERIFIED] | `selectCascade` clamps to `cascadeCount - 1` |
| 13.4 Sampler shadow behavior with invalid coords | [NEEDS TOOLING] | OOB check handles [0,1] but NaN/Inf could bypass |
| 13.5 Division by zero (w component) | **[PROBLEM]** | `projCoordsB = lightSpacePosB.xyz / lightSpacePosB.w` when w=0 |

---

## TASK 2: Hypothesis A (Prime Suspect)

### Hypothesis A Statement

**When `leapfrogActive` toggles from 0 to 1, we activate a pipeline state that depends on B-side data (uniform arrays and/or `vLightSpacePosB`) that is not always valid outside transitions. Even though we don't SAMPLE B at crossfade=0, the GPU may still:**

1. **Interpolate `vLightSpacePosB` varyings** with invalid values (w=0), affecting fragment shader register allocation or GPU execution patterns
2. **Pre-fetch `shadowMapArrayB` texture** even though we return early, causing cache/state effects
3. **Execute branch paths differently** due to uniform-dependent shader compilation

### What to Measure (Proof/Falsification)

| Measurement | Method | Proves Hypothesis A if... |
|-------------|--------|---------------------------|
| `projCoordsB.z` value distribution | Add debug visualization | Values are NaN/Inf/garbage when transitioning starts |
| `vLightSpacePosB.w` value at fragment | Add debug visualization | w is 0 or near-zero causing divide issues |
| OOB rate per frame | Log percentage of OOB early-returns | Spike at TRANSITION_START frame |
| Shadow factor mean per frame | Compute and log | Abrupt change exactly at TRANSITION_START |
| NaN/Inf heatmap | Shader debug mode: magenta if `!isfinite(projCoords)` | Magenta pixels appear at TRANSITION_START |

### New Debug Modes to Add

1. **Mode 8 - NaN/OOB Heatmap**:
   - Magenta if `!isfinite(projCoords.z) || !isfinite(projCoordsB.z)`
   - Yellow if OOB early-return path taken
   - Green if valid sampling

2. **Mode 9 - Shadow Delta View**:
   - Visualize `abs(shadow_currentFrame - shadow_previousFrame)`
   - Requires ping-pong texture or CPU readback

3. **Mode 10 - w-Component Validity**:
   - Red channel = `vLightSpacePos[cascade].w`
   - Green channel = `vLightSpacePosB[cascade].w`
   - Blue = 1.0 if both valid (w > 0.001)

---

## TASK 3: Next Steps (Priority Order)

Based on the checklist, the following items are **[PROBLEM]** or **[UNVERIFIED]**:

1. **[PROBLEM] 6.3, 7.5, 13.2, 13.5**: `vLightSpacePosB = vec4(0)` when NOT transitioning causes w=0
2. **[UNVERIFIED] 3.2**: Texture binding may differ between transition/non-transition
3. **[UNVERIFIED] 4.4**: B uniform arrays may have garbage in unused slots
4. **[UNVERIFIED] 5.5**: OOB logic not tested on B path at crossfade=0

### Selected Next Steps

#### Priority 1: Option (C) - Varyings Validity Fix

**Rationale**: The most likely cause. When `leapfrogActive` changes from 0 to 1:
- Vertex shader switches from `vLightSpacePosB[i] = vec4(0.0)` to computing actual values
- Even though fragment shader returns early at crossfade=0, the varying interpolation CHANGES
- GPU execution may differ due to different register usage or interpolation patterns

**Implementation**:
```glsl
// ALWAYS compute valid B values (same as A when not transitioning)
if (leapfrogActive == 1) {
    vLightSpacePosB[i] = lightSpaceMatricesB[i] * shadowWorldB;
} else {
    // Use A matrices for B to ensure valid w and consistent interpolation
    vLightSpacePosB[i] = lightSpaceMatrices[i] * shadowWorld;
}
```

#### Priority 2: Option (A) - Pipeline Invariance Enforcement (Textures)

**Rationale**: Secondary priority. Current code binds:
- When NOT transitioning: unit 1 = primary, unit 2 = primary (same texture)
- When transitioning: unit 1 = primary, unit 2 = secondary (different texture)

This texture binding change itself may trigger driver state changes.

**Implementation**:
```java
// ALWAYS bind both textures to their respective units
// When not transitioning, bind primary to both (but use same unit numbers)
voxelShader.setTextureArray("shadowMapArray", primaryShadowMap, 1);
voxelShader.setTextureArray("shadowMapArrayB",
    leapfrogTransitioning ? shadowRenderer.getSecondaryShadowMapArray() : primaryShadowMap, 2);
```

This is ALREADY implemented (line 286-288 in VoxelRenderer), so this is [VERIFIED] correct.

#### Priority 3: Option (D) - Fragment Path Unification

**Rationale**: Tertiary priority. The current shader has two distinct code paths:
- `if (leapfrogActive == 1) { ... }` - leapfrog path
- `return sampleShadowPCFFromArray(...)` - normal path

Even with identical inputs, branch divergence can cause execution differences.

**Implementation**:
```glsl
// ALWAYS use the same structure
float shadowA = sampleShadowPCFFromArray(shadowMapArray, cascade, worldPos, projCoords, bias);

// Only do B sampling if actually needed
if (leapfrogActive == 1 && shadowCrossfade > 0.01) {
    vec4 lightSpacePosB = vLightSpacePosB[cascade];
    vec3 projCoordsB = lightSpacePosB.xyz / lightSpacePosB.w;
    projCoordsB = projCoordsB * 0.5 + 0.5;
    float shadowB = sampleShadowPCFFromArray(shadowMapArrayB, cascade, worldPos, projCoordsB, bias);
    return mix(shadowA, shadowB, shadowCrossfade);
}

return shadowA;
```

---

## TASK 4: Minimal Implementation Plan

### Step 1: Fix Varyings Validity (Option C)

**File**: `src/main/resources/shaders/voxel_vertex_shader.glsl`

**Change at lines 82-87**:
```glsl
// BEFORE (problematic):
if (leapfrogActive == 1) {
    vLightSpacePosB[i] = lightSpaceMatricesB[i] * shadowWorldB;
} else {
    vLightSpacePosB[i] = vec4(0.0);  // w=0 causes issues
}

// AFTER (invariant):
if (leapfrogActive == 1) {
    vLightSpacePosB[i] = lightSpaceMatricesB[i] * shadowWorldB;
} else {
    // Use A's matrices for B to ensure valid w and consistent GPU state
    vLightSpacePosB[i] = lightSpaceMatrices[i] * shadowWorld;
}
```

### Step 2: Unify Fragment Shader Path (Option D)

**File**: `src/main/resources/shaders/voxel_fragment_shader.glsl`

**Change at lines 384-420**:
```glsl
// BEFORE: Two distinct code paths
if (leapfrogActive == 1) {
    float shadowA = sampleShadowPCFFromArray(shadowMapArray, cascade, worldPos, projCoords, bias);
    if (shadowCrossfade < 0.001) {
        return shadowA;
    }
    // ... B sampling and mix ...
}
return sampleShadowPCFFromArray(shadowMapArray, cascade, worldPos, projCoords, bias);

// AFTER: Single unified path
float shadowA = sampleShadowPCFFromArray(shadowMapArray, cascade, worldPos, projCoords, bias);

// Only compute B if actually blending
if (leapfrogActive == 1 && shadowCrossfade > 0.001) {
    vec4 lightSpacePosB = vLightSpacePosB[cascade];
    // Guard against invalid w (shouldn't happen with fix, but defensive)
    if (abs(lightSpacePosB.w) > 0.0001) {
        vec3 projCoordsB = lightSpacePosB.xyz / lightSpacePosB.w;
        projCoordsB = projCoordsB * 0.5 + 0.5;
        float shadowB = sampleShadowPCFFromArray(shadowMapArrayB, cascade, worldPos, projCoordsB, bias);
        return mix(shadowA, shadowB, shadowCrossfade);
    }
}

return shadowA;
```

### Step 3: Add NaN/Validity Debug Mode (Diagnostic)

**File**: `src/main/resources/shaders/voxel_fragment_shader.glsl`

Add debug mode 8:
```glsl
} else if (debugShadow == 8) {
    // Mode 8: NaN/w-validity heatmap
    float wA = vLightSpacePos[cascade].w;
    float wB = vLightSpacePosB[cascade].w;

    bool validA = abs(wA) > 0.0001 && !isinf(wA) && !isnan(wA);
    bool validB = abs(wB) > 0.0001 && !isinf(wB) && !isnan(wB);

    vec3 color = vec3(0.0);
    if (!validA) color.r = 1.0;  // Red = invalid A
    if (!validB) color.g = 1.0;  // Green = invalid B (expected when not transitioning BEFORE fix)
    if (validA && validB) color.b = 1.0;  // Blue = both valid

    fragColor = vec4(color, 1.0);
    return;
}
```

---

## Verification Plan

After implementing the fixes:

1. **Run with debug mode 8**: Should show ALL BLUE (both varyings valid) even when NOT transitioning
2. **Run normally**: Flash should be eliminated at TRANSITION_START
3. **Test with slow motion (0 key)**: Step through transition frames, verify no visual discontinuity
4. **Compare debug mode 6**: Shadow A vs B difference should be smooth during entire transition

If flash persists after these fixes, escalate to:
- GPU-specific behavior investigation
- Driver version testing
- GLSL assembly inspection
