# Sky Flash Investigation Report

## Problem Description

A visual "flash" occurs during CSM (Cascaded Shadow Mapping) leapfrog transitions. The flash appears at **TRANSITION_START** frame when `leapfrogActive` switches from 0 to 1.

### Observed Behavior
- Flash occurs at TRANSITION_START (crossfade=0)
- Flash does NOT happen on every transition
- Moving the camera angle **maintains** the flash color (suggests projection-related issue)
- Flash persists despite all matrix/texture fixes

---

## Confirmed Working Correctly

### 1. MAP↔MATRIX LOCK ✅
The shadow map content matches the matrices used to sample it.

```
MAP↔MATRIX LOCK: OK (frozenA matches lastRendered)
```

- `lastRenderedMatrixHashA` is correctly updated after each shadow map render
- `lastRenderedMatrixHashA` is correctly updated after swap (CRITICAL FIX #3)
- `frozenCascadeDataA` matches the matrices used when shadow map A was rendered

### 2. Matrix Continuity ✅
No matrix changes detected at TRANSITION_START:

```
CHANGE DETECTED: leapfrog=true matrix=false shadowMap=false
```

- `cascadeData` hash is identical before and after transition starts
- The same `lightSpaceMatrices` are sent to the shader

### 3. Shadow Map Texture Binding ✅
Correct texture IDs are bound:

- `shadowMapArray` (ID 3) and `shadowMapArrayB` (ID 4) are properly alternated
- `primaryShadowMap` correctly returns the active texture based on `primaryIsA`
- When not transitioning, both samplers bind to the same primary texture

### 4. GPU Synchronization ✅
`glFinish()` is called after rendering shadow map B to ensure GPU completion before VoxelRenderer samples.

### 5. Crossfade Math ✅
At crossfade=0: `mix(shadowA, shadowB, 0.0) = shadowA` - mathematically returns 100% A.

### 6. Shader Optimization ✅
Added early-return when `shadowCrossfade < 0.001`:
```glsl
if (shadowCrossfade < 0.001) {
    return shadowA;  // Fully A, no need to sample B
}
```
This did NOT fix the flash.

---

## Potential Problem Areas

### 1. ⚠️ `leapfrogActive` Flag Itself
The ONLY change detected at TRANSITION_START is:
```
leapfrog=true (was false)
```

Even with identical matrices, textures, and crossfade=0, the mere act of entering the `if (leapfrogActive == 1)` branch causes a visual difference.

**Hypothesis**: There may be something in the shader compilation/execution that differs between the two branches, even when they should produce identical results.

### 2. ⚠️ Vertex Shader `vLightSpacePosB` Initialization
When `leapfrogActive == 0`:
```glsl
vLightSpacePosB[i] = vec4(0.0);
```

When `leapfrogActive == 1`:
```glsl
vLightSpacePosB[i] = lightSpaceMatricesB[i] * shadowWorldB;
```

Even though we don't sample B at crossfade=0 (after the optimization), the vertex shader is still computing different values for `vLightSpacePosB`. This could affect:
- Interpolation between vertices
- GPU execution patterns
- Cache behavior

### 3. ⚠️ Texture Unit State Change
When NOT transitioning:
```java
shadowMapArrayB → primaryShadowMap (same as A)
```

When transitioning:
```java
shadowMapArrayB → secondaryShadowMap (different texture)
```

Even if we don't sample `shadowMapArrayB` in the shader, binding a different texture to unit 2 might have side effects on some GPU drivers.

### 4. ⚠️ Uniform Array Initialization
When transitioning, we set additional uniforms:
```java
lightSpaceMatricesB[4]
cascadeSplitsB[5]
cascadeTexelSizesB[4]
```

These arrays might have uninitialized or stale values when NOT transitioning, causing undefined behavior in the vertex shader (even if not used in fragment shader calculations).

### 5. ⚠️ Camera Angle Dependency
The observation that "moving the camera angle maintains the flash color" strongly suggests the issue is related to:
- Light-space projection calculations
- View-dependent cascade selection
- Something computed per-vertex that persists across the frame

---

## Shader Code Path Analysis

### Normal Path (leapfrogActive=0)
```glsl
// Fragment shader
return sampleShadowPCFFromArray(shadowMapArray, cascade, worldPos, projCoords, bias);
```

### Leapfrog Path at crossfade=0 (leapfrogActive=1)
```glsl
// Fragment shader
float shadowA = sampleShadowPCFFromArray(shadowMapArray, cascade, worldPos, projCoords, bias);
if (shadowCrossfade < 0.001) {
    return shadowA;
}
```

These should be **identical** since:
- Same sampler (`shadowMapArray`)
- Same cascade index
- Same `projCoords` (computed from `vLightSpacePos[cascade]`)
- Same bias

Yet the flash still occurs.

---

## Recommended Next Steps

### 1. Force Identical Code Paths
Modify the shader to use the EXACT same code path regardless of `leapfrogActive`:

```glsl
// Always use the normal path structure
float shadow = sampleShadowPCFFromArray(shadowMapArray, cascade, worldPos, projCoords, bias);

// Only do crossfade logic if actually needed
if (leapfrogActive == 1 && shadowCrossfade > 0.01) {
    vec4 lightSpacePosB = vLightSpacePosB[cascade];
    vec3 projCoordsB = lightSpacePosB.xyz / lightSpacePosB.w;
    projCoordsB = projCoordsB * 0.5 + 0.5;
    float shadowB = sampleShadowPCFFromArray(shadowMapArrayB, cascade, worldPos, projCoordsB, bias);
    shadow = mix(shadow, shadowB, shadowCrossfade);
}

return shadow;
```

### 2. Initialize Uniform Arrays When Not Transitioning
Ensure `lightSpaceMatricesB`, `cascadeSplitsB`, and `cascadeTexelSizesB` have valid values even when not transitioning (copy from primary arrays).

### 3. Start Crossfade at 0.1 Instead of 0.0
Skip the first frame of transition where `crossfadeProgress = 0.0`:
```java
crossfadeProgress = 0.1f;  // Start at 10% instead of 0%
```

### 4. Delay `leapfrogActive` Flag by One Frame
Set `leapfrogActive = 1` one frame AFTER rendering shadow map B, so the shader has a chance to see the new uniforms in a stable state.

### 5. Investigate GPU-Specific Behavior
Test on different GPUs/drivers to determine if this is a driver-specific issue with:
- Shader recompilation when uniforms change
- Texture binding state changes
- Branch prediction/execution differences

---

## Technical Details

### Files Modified During Investigation
- `ShadowRenderer.java` - MAP↔MATRIX LOCK verification, GPU sync
- `VoxelRenderer.java` - Frame-to-frame state tracking
- `voxel_fragment_shader.glsl` - Early-return optimization
- `Input.java` - Keyboard tracking for debug keys

### Debug Tools Available
- **F11**: Freeze light basis
- **0**: Toggle slow motion
- **9/J**: Toggle swap freeze
- **F3**: Cycle shadow debug modes (mode 6 shows A/B difference)

### Log Output Format
```
>>> FRAME N: TRANSITION START (crossfade=0, rendering to B) <<<
    Matrix hashes: frozenA=... cascadeData=... B=...
    primaryIsA=true/false shadowMapArray=3 shadowMapArrayB=4
    MAP↔MATRIX LOCK: OK
[VOXEL] TRANSITION_START shader state:
    leapfrogActive=1 (was false)
    shadowCrossfade=0.0
    CHANGE DETECTED: leapfrog=true matrix=false shadowMap=false
```

---

## Conclusion

The flash is **NOT** caused by:
- Matrix mismatch between shadow map and sampling
- Texture binding errors
- GPU synchronization issues
- Crossfade math errors

The flash **IS** caused by:
- The transition from `leapfrogActive=0` to `leapfrogActive=1`
- Something in the shader execution that differs between the two code paths
- Possibly related to vertex shader `vLightSpacePosB` computation or uniform state changes

The root cause appears to be a subtle difference in how the GPU executes the shader when `leapfrogActive` changes, even when the mathematical result should be identical.
