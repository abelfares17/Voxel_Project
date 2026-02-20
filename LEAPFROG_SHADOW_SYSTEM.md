# Leapfrog Shadow System - Detailed Documentation

This document describes the leapfrog shadow system implementation that eliminates shimmer while maintaining good performance.

---

## Overview

The leapfrog system uses **two shadow map arrays** (A and B) with **crossfade blending** to hide the visual discontinuity when the light basis changes. The key insight is that shimmer occurs when the light basis vectors change - by crossfading between two shadow maps computed with different bases, the shimmer is hidden.

---

## Core Invariant: MAP↔MATRIX LOCK

**Critical Rule**: For each shadow map array, the `lightSpaceMatrices` used to SAMPLE it must be exactly the same matrices used when it was last RENDERED.

This means:
- If we freeze the map (don't re-render), we must also freeze its matrices
- If we recompute matrices, we must also re-render the map
- Violating this invariant causes incorrect shadow sampling

---

## State Variables

```java
// Two shadow map arrays
private int shadowMapArray;      // Primary array (A)
private int shadowMapArrayB;     // Secondary array (B)

// Cascade data for each array
private CascadeData cascadeData;          // Current primary cascade data
private CascadeData cascadeDataB;         // Secondary cascade data (during transition)
private CascadeData frozenCascadeDataA;   // Frozen A data (captured at transition start)

// Leapfrog state
private boolean primaryIsA = true;        // Which array is currently primary
private boolean isTransitioning = false;  // True during A→B crossfade
private float crossfadeProgress = 1.0f;   // 0 = fully A, 1 = fully B

// Snap tracking (for camera movement detection)
private float lastSnapX = 0;
private float lastSnapY = 0;
```

---

## State Machine

```
┌─────────────────────────────────────────────────────────────────────┐
│                         STABLE STATE                                 │
│   - Primary shadow map is active (A or B depending on primaryIsA)   │
│   - cascadeData is LOCKED to the primary shadow map                 │
│   - 0-1 depth passes per frame (only if camera snap changed)        │
└─────────────────────────────────────────────────────────────────────┘
            │                                       ▲
            │ Sun moved > threshold                 │ Crossfade complete
            ▼                                       │
┌─────────────────────────────────────────────────────────────────────┐
│                      TRANSITION START                                │
│   1. Freeze current cascadeData → frozenCascadeDataA                │
│   2. Update light basis to new sun direction                        │
│   3. Compute cascadeDataB with new basis                            │
│   4. Render secondary shadow map (1 depth pass)                     │
│   5. Set isTransitioning = true, crossfadeProgress = 0              │
└─────────────────────────────────────────────────────────────────────┘
            │
            │ Each frame: crossfadeProgress += 0.05
            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      DURING TRANSITION                               │
│   - NO RENDERING (0 depth passes!)                                  │
│   - Both shadow maps are FROZEN                                     │
│   - Shader blends: mix(sampleA, sampleB, crossfadeProgress)         │
│   - A uses frozenCascadeDataA, B uses cascadeDataB                  │
└─────────────────────────────────────────────────────────────────────┘
            │
            │ crossfadeProgress >= 1.0
            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      TRANSITION END                                  │
│   1. primaryIsA = !primaryIsA (swap roles)                          │
│   2. cascadeData = cascadeDataB (new primary)                       │
│   3. Update lastSnapX/Y to prevent immediate re-render              │
│   4. Clear cascadeDataB, frozenCascadeDataA                         │
│   5. isTransitioning = false                                        │
│   → Return to STABLE STATE                                          │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Update Policy

The system renders shadow maps **only when needed**:

| Condition | Action | Depth Passes |
|-----------|--------|--------------|
| First frame | Initialize primary | 1 |
| Sun moved > threshold | Start transition, render secondary | 1 |
| During transition | Nothing (both maps frozen) | 0 |
| Camera snap changed | Re-render primary with same basis | 1 |
| Nothing changed | Reuse frozen map+matrices | 0 |

**Typical frame cost**: 0-1 depth passes (vs 1 depth pass every frame in naive approach)

---

## Key Methods

### `renderWithLeapfrog()`

Main entry point that orchestrates the leapfrog system:

```java
private void renderWithLeapfrog(RenderContext context, Camera camera, Vector3f sunDir) {
    // STEP 1: Progress any ongoing crossfade
    if (isTransitioning) {
        crossfadeProgress += CROSSFADE_SPEED;
        if (crossfadeProgress >= 1.0f) {
            // Crossfade complete - swap roles
            primaryIsA = !primaryIsA;
            cascadeData = cascadeDataB;
            lastSnapX = getSnapX(cascadeData);  // Prevent immediate re-render
            lastSnapY = getSnapY(cascadeData);
            // Clear transition state
            cascadeDataB = null;
            frozenCascadeDataA = null;
            isTransitioning = false;
        }
    }

    // STEP 2: Handle rendering based on state
    if (isTransitioning) {
        // DURING TRANSITION: 0 depth passes, use frozen data
        cascadeData = frozenCascadeDataA;
    } else {
        boolean sunMoved = csmCalculator.shouldUpdateBasis(sunDir);

        if (cascadeData == null) {
            // FIRST FRAME: Initialize
            // ... render primary
        } else if (sunMoved) {
            // SUN MOVED: Start transition
            // ... freeze A, compute B, render B, start crossfade
        } else {
            // STABLE: Check if camera snap changed
            // ... re-render if needed
        }
    }
}
```

### `shouldUpdateBasis()` (in CSMCalculator)

Determines if sun has moved enough to warrant a basis update:

```java
public boolean shouldUpdateBasis(Vector3f sunDir) {
    if (!quantizedBasisInitialized) {
        return true;  // First time
    }
    float cosAngle = sunDir.dot(lastQuantizedSunDir);
    float angle = (float) Math.acos(Math.min(1.0f, cosAngle));
    return angle > SUN_QUANTIZATION_THRESHOLD;  // ~2 degrees
}
```

### Snap Detection

Camera movement is detected by checking if the texel-snap grid changed:

```java
private float getSnapX(CascadeData data) {
    // m30 is the X translation in the light-view matrix
    return data.lightSpaceMatrices()[0].m30();
}

private float getSnapY(CascadeData data) {
    // m31 is the Y translation in the light-view matrix
    return data.lightSpaceMatrices()[0].m31();
}
```

---

## Shader Integration (VoxelRenderer)

The fragment shader receives:

```glsl
uniform int leapfrogActive;           // 1 during transition, 0 otherwise
uniform float shadowCrossfade;        // 0.0 = fully A, 1.0 = fully B
uniform sampler2DArrayShadow shadowMapArray;   // Primary (A)
uniform sampler2DArrayShadow shadowMapArrayB;  // Secondary (B)
uniform mat4 lightSpaceMatrices[4];   // Primary matrices
uniform mat4 lightSpaceMatricesB[4];  // Secondary matrices
```

Shadow sampling:

```glsl
float shadow;
if (leapfrogActive == 1) {
    float shadowA = sampleShadowMap(shadowMapArray, lightSpaceMatrices, ...);
    float shadowB = sampleShadowMap(shadowMapArrayB, lightSpaceMatricesB, ...);
    shadow = mix(shadowA, shadowB, shadowCrossfade);
} else {
    shadow = sampleShadowMap(shadowMapArray, lightSpaceMatrices, ...);
}
```

---

## Performance Characteristics

| Scenario | Depth Passes/Frame | Notes |
|----------|-------------------|-------|
| Standing still, sun moving slowly | 0 | Most frames |
| Sun crosses threshold | 1 | Render secondary |
| During 20-frame crossfade | 0 | Both maps frozen |
| Walking slowly | 0-1 | Snap may not change |
| Walking fast | 1 | Snap changes often |

**Average**: ~0.3 depth passes per frame (vs 1.0 in naive approach)

---

## Why This Eliminates Shimmer

1. **Shimmer Root Cause**: Light basis vectors change → shadow map content changes → visual discontinuity

2. **Leapfrog Solution**:
   - When light basis changes, we don't immediately switch to new shadow map
   - Instead, we render new shadow map to secondary array
   - Then crossfade over ~20 frames (blend A→B)
   - The gradual blend hides the discontinuity

3. **MAP↔MATRIX LOCK ensures correctness**:
   - Each shadow map is sampled with the exact matrices used to render it
   - No mismatch = no incorrect shadow positions

---

## GL State Management (Critical!)

The shadow pass must restore GL state to prevent sky color flashes:

```java
// In renderShadowMap() finally block:
glBindFramebuffer(GL_FRAMEBUFFER, 0);
glDrawBuffer(GL_BACK);     // CRITICAL: Shadow FBO uses GL_NONE
glReadBuffer(GL_BACK);     // CRITICAL: Restore default
glColorMask(true, true, true, true);  // CRITICAL: Shadow disables color writes
glDepthMask(true);
glDepthFunc(GL_LESS);
glViewport(0, 0, windowWidth, windowHeight);
```

Also in `render()` method - wrap in try-finally for guaranteed restoration.

---

## Debugging Tips

1. **Shimmer still present?**
   - Press F11 to freeze light basis → if shimmer stops, issue is basis update
   - Check if `shouldUpdateBasis()` threshold is too small

2. **Shadow jumps/pops?**
   - MAP↔MATRIX LOCK may be violated
   - Verify `cascadeData` matches the rendered shadow map

3. **Sky flashes?**
   - GL state leak from shadow pass
   - Check `glDrawBuffer`, `glColorMask` restoration

4. **Performance issues?**
   - Check depth pass count in debug display
   - Should be 0-1 per frame, not 2+
