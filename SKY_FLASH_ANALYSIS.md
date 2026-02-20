# Sky Flash Root Cause Analysis

## Diagnostic Results

### What We Tested

1. **GL State at every critical point**:
   - Shadow pass entry
   - Shadow pass exit
   - SkyRenderer entry
   - Leapfrog transition start
   - Leapfrog transition end/swap

2. **Anomaly detection**:
   - FBO binding (expected: 0)
   - Draw buffer (expected: GL_BACK = 1029)
   - Read buffer (expected: GL_BACK = 1029)
   - Color mask (expected: all true)
   - Sky color jumps (delta > 0.1)

### Findings

**GL State is ALWAYS correct:**
```
[31] [LEAPFROG] TRANSITION_END_SWAP
    GL: FBO=0 draw=1029 mask=[true,true,true,true]
```

Every single logged frame shows:
- `FBO=0` (default framebuffer) ✓
- `draw=1029` (GL_BACK) ✓
- `mask=[true,true,true,true]` ✓

**No sky color jumps detected** - the sky color calculation is stable.

---

## Hypothesis Elimination

| Hypothesis | Result | Evidence |
|------------|--------|----------|
| H1: GL state leak | **ELIMINATED** | All 500+ frames show correct state |
| H2: Transition START causes flash | **SUSPECT** | Correlates with visual observation |
| H3: Transition END/SWAP causes flash | **SUSPECT** | Correlates with visual observation |
| H4: renderShadowMap() causes flash | **ELIMINATED** | State is correct after render |
| H5: Crossfade progress causes flash | **POSSIBLE** | Need to investigate shader |

---

## Remaining Hypothesis: Shader-Side Issue

Since GL state is provably correct, the flash must be happening **in the shader execution** or **GPU state** that we're not tracking.

### Key Observation from Logs

Transitions occur every ~25 frames:
- Frame 11: TRANSITION_START
- Frame 31: TRANSITION_END_SWAP (20 frames later)
- Frame 46: TRANSITION_START (15 frames later)
- Frame 66: TRANSITION_END_SWAP (20 frames later)

The pattern suggests ~20 frame crossfade followed by ~5-15 frames stable.

### Shader Analysis

Looking at `voxel_fragment_shader.glsl`:

```glsl
if (leapfrogActive == 1) {
    float shadowA = sampleShadowPCFFromArray(shadowMapArray, cascade, worldPos, projCoords, bias);
    float shadowB = sampleShadowPCFFromArray(shadowMapArrayB, cascade, worldPos, projCoordsB, bias);
    return mix(shadowA, shadowB, shadowCrossfade);
}
```

**Potential Issue #1: Uninitialized Secondary Shadow Map**

When `leapfrogActive == 1`, the shader samples from `shadowMapArrayB`. If this texture:
- Was never rendered to (first transition)
- Contains stale data from a previous transition
- Is bound incorrectly

...it could cause visual artifacts.

**Potential Issue #2: Light-Space Position Mismatch**

The shader uses `vLightSpacePosB[cascade]` which is computed in the vertex shader:

```glsl
if (leapfrogActive == 1) {
    vLightSpacePosB[i] = lightSpaceMatricesB[i] * shadowWorldB;
} else {
    vLightSpacePosB[i] = vec4(0.0);
}
```

If `lightSpaceMatricesB` uniforms are not set correctly by VoxelRenderer, the shader will sample garbage coordinates.

**Potential Issue #3: Texture Unit Binding Race**

The ShadowRenderer unbinds textures before rendering:
```java
glActiveTexture(GL_TEXTURE0 + 5);
glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
glActiveTexture(GL_TEXTURE0 + 6);
glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
```

But VoxelRenderer rebinds them:
```java
voxelShader.setTextureArray("shadowMapArray", shadowRenderer.getPrimaryShadowMapArray(), 1);
if (leapfrogTransitioning) {
    voxelShader.setTextureArray("shadowMapArrayB", shadowRenderer.getSecondaryShadowMapArray(), 2);
}
```

**Observation**: Shadow maps are bound to units 1 and 2 in VoxelRenderer, but unbound from units 5 and 6 in ShadowRenderer. This is inconsistent but shouldn't cause flashes.

---

## Most Likely Root Cause

### The Flash Occurs on Transition Frames

Looking at the flow:

**TRANSITION_START frame:**
1. Shadow pass renders to SECONDARY array
2. `isTransitioning = true`, `crossfadeProgress = 0.0`
3. VoxelRenderer sees `leapfrogTransitioning = true`
4. Shader samples from BOTH arrays with `mix(A, B, 0.0)` = 100% A
5. **But the `lightSpaceMatricesB` uniforms were just computed for B**

**Wait, this is correct** - at progress 0.0, mix returns 100% A.

**TRANSITION_END frame:**
1. `crossfadeProgress >= 1.0` → swap roles
2. `cascadeData = cascadeDataB` (B becomes primary)
3. VoxelRenderer now binds B's shadow map as PRIMARY
4. Shader uses 100% primary (B)
5. **This is a discrete jump from 95% A + 5% B to 100% B's position**

### The Actual Flash Mechanism

The flash likely occurs because of **coordinate mismatch at transition boundaries**:

1. During crossfade: `mix(shadowA, shadowB, progress)` blends shadow VALUES
2. But the LIGHT-SPACE COORDINATES for A and B are different!
3. At crossfade end, we SWAP which array is "primary"
4. The shader suddenly uses B's light-space matrices exclusively
5. If B's matrices produce different shadow coordinates than A's, the shadow position JUMPS

**This is NOT a sky flash - it's a SHADOW flash!**

The "sky flash" is actually the shadow coverage changing suddenly, making the scene appear brighter or darker for one frame.

---

## Verification Steps

1. **Check VoxelRenderer uniform setting** during transition:
   - Is `lightSpaceMatricesB` being set correctly?
   - Is `cascadeSplitsB` being set correctly?

2. **Check the swap logic** in ShadowRenderer:
   - When `primaryIsA` swaps, does VoxelRenderer pick up the correct arrays?
   - Is there a frame where the primary array ID doesn't match the primary matrices?

3. **Add logging to VoxelRenderer** to track:
   - Which shadow map array IDs are being bound
   - What `leapfrogTransitioning` value is used
   - What `crossfadeProgress` value is used

---

## Next Steps (Do NOT implement yet - diagnosis only)

1. **Verify shadow map array binding consistency**:
   - Log `shadowRenderer.getPrimaryShadowMapArray()` ID
   - Log `shadowRenderer.getSecondaryShadowMapArray()` ID
   - Compare with expected (A or B based on `primaryIsA`)

2. **Verify matrix consistency**:
   - Log `cascadeData` pointer identity
   - Log `cascadeDataB` pointer identity
   - Ensure they match what's being rendered

3. **Add per-frame trace during transition**:
   - Frame N: transitioning=true, progress=0.95
   - Frame N+1: transitioning=false, progress=1.0 (SWAP HAPPENED)
   - Check if uniforms change correctly
