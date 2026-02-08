# Cascaded Shadow Mapping (CSM) — Technical Report

## Table of Contents
1. [Theory & Mathematics](#1-theory--mathematics)
2. [Implementation Architecture](#2-implementation-architecture)
3. [Code Deep Dive](#3-code-deep-dive)
4. [Configuration Parameters](#4-configuration-parameters)
5. [Optimization & Improvements](#5-optimization--improvements)

---

## 1. Theory & Mathematics

### 1.1 The Shadow Mapping Problem

Standard shadow mapping projects the scene from the light's perspective into a single depth texture. The fundamental issue is **perspective aliasing**: objects near the camera require far more shadow map resolution than distant objects, but a single shadow map allocates resolution uniformly across the entire scene.

**The math of the problem:**

For a perspective camera, an object at distance `d` from the camera projects to screen space with a size proportional to `1/d`. However, in a uniform shadow map, that same object occupies a fixed number of texels regardless of its screen-space size.

This creates a resolution mismatch:
- Near objects: High screen resolution, low shadow resolution → blocky, aliased shadows
- Far objects: Low screen resolution, high shadow resolution → wasted texels

### 1.2 The CSM Solution

Cascaded Shadow Mapping divides the view frustum into multiple depth ranges (cascades), each with its own shadow map. Near cascades cover smaller volumes with higher effective resolution.

```
Camera Frustum Side View:

    ┌──────────────────────────────────────────────────────┐
    │                                                      │
    │  Cascade 0    │    Cascade 1     │    Cascade 2     │
    │  (Near)       │    (Mid)         │    (Far)         │
    │  High Res     │    Medium Res    │    Lower Res     │
    │               │                  │                  │
    └──────────────────────────────────────────────────────┘
   Near            Split₁            Split₂              Far
  (0.1)            (~13)             (~60)              (400)
```

### 1.3 Split Distance Calculation

The key mathematical challenge is determining where to place the cascade boundaries (splits). Two approaches exist:

**Linear Split:**
```
C_lin[i] = near + (far - near) × (i / n)
```
- Uniform depth distribution
- Wastes near-field resolution

**Logarithmic Split:**
```
C_log[i] = near × (far / near)^(i/n)
```
- Allocates more splits near the camera
- Better matches perspective projection
- Can leave far field under-sampled

**Practical Split (Blend):**
```
split[i] = λ × C_log[i] + (1 - λ) × C_lin[i]
```
Where `λ ∈ [0, 1]` controls the blend:
- `λ = 0`: Pure linear
- `λ = 1`: Pure logarithmic
- `λ = 0.55–0.75`: Practical values for games

**Example with current settings** (`near=0.1`, `far=400`, `n=3`, `λ=0.55`):
```
Split 0 (near):  0.1
Split 1:         λ × 0.1 × (4000)^0.33 + (1-λ) × (0.1 + 133.3) ≈ 68.6
Split 2:         λ × 0.1 × (4000)^0.67 + (1-λ) × (0.1 + 266.6) ≈ 189.2
Split 3 (far):   400
```

### 1.4 Light-Space Matrix Construction

Each cascade requires a light-space matrix that transforms world coordinates to normalized device coordinates (NDC) suitable for depth comparison.

**Step 1: Define Light Basis Vectors**

Given sun direction `S` (pointing toward sun), we define:
```
lightDir = -S                          // Points toward scene (shadow direction)
lightRight = normalize(lightDir × up)  // Horizontal perpendicular
lightUp = lightRight × lightDir        // Vertical perpendicular
```

The reference `up` vector is typically `(0, 1, 0)` unless `lightDir` is nearly vertical.

**Step 2: Compute Bounding Sphere**

For stable shadows, each cascade uses a bounding sphere centered at the frustum slice's centroid:

```
sphereCenter = camPos + camForward × ((zNear + zFar) / 2)
sphereRadius = distance(sphereCenter, farthestCorner)
```

The farthest corner is always on the far plane of the frustum slice:
```
radius = √(farHalfW² + farHalfH² + ((zFar - zNear)/2)²)
```

**Step 3: Build View Matrix**

Transform world position `P` to light-space coordinates:
```
X_light = P · lightRight - snappedX
Y_light = P · lightUp - snappedY
Z_light = P · lightDir - minZ
```

The `snapped` values are the sphere center projected to light XY, then snapped to the texel grid:
```
snappedX = round(sphereCenter · lightRight / texelSize) × texelSize
snappedY = round(sphereCenter · lightUp / texelSize) × texelSize
```

**Step 4: Build Orthographic Projection**

```
ortho(-halfSize, +halfSize,    // X range
      -halfSize, +halfSize,    // Y range
      0, orthoDepth)           // Z range: [minZ, maxZ]
```

Where:
- `halfSize = sphereRadius` (constant per cascade)
- `orthoDepth = maxZ - minZ` (depth range from sphere bounds + margin)

### 1.5 Texel Snapping for Stability

Without snapping, shadows "swim" as the camera rotates because the light-space center moves sub-texel amounts.

**The snapping formula:**
```
texelSize = (2 × sphereRadius) / shadowMapSize
snappedX = round(centerX / texelSize) × texelSize
```

This quantizes the projection center to discrete texel boundaries, ensuring the shadow map samples the same world positions across frames.

### 1.6 Shadow Sampling with Bias

When comparing depths in the fragment shader:
```glsl
shadowDepth = texture(shadowMap, lightSpacePos.xy).r
fragmentDepth = lightSpacePos.z
isShadowed = fragmentDepth > shadowDepth + bias
```

**Bias is necessary because:**
1. Depth buffer has finite precision
2. Surface may be at grazing angle to light (slope)
3. Far cascades have larger texels → need larger bias

**Cascade-aware bias formula:**
```glsl
baseBias = 0.0005
slopeBias = 0.002
cascadeScale = min(texelSize[cascade] / texelSize[0], 3.0)  // Capped!
bias = (baseBias + slopeBias × (1 - cosθ)) × cascadeScale
```

The `cosθ = dot(normal, lightDir)` term increases bias for surfaces at grazing angles.

### 1.7 Cascade Selection

In the fragment shader, select cascade based on view-space depth:
```glsl
viewDepth = -viewPos.z;  // Positive (camera looks down -Z)
for (i = 0; i < cascadeCount; i++) {
    if (viewDepth < cascadeSplits[i + 1]) return i;
}
return cascadeCount - 1;
```

---

## 2. Implementation Architecture

### 2.1 File Structure

```
src/main/java/engine/render/
├── shadow/
│   ├── CSMCalculator.java      # Core matrix computation
│   ├── FrustumSplitter.java    # Split distance calculation
│   └── CascadeData.java        # Per-frame cascade data record
├── renderer/
│   └── ShadowRenderer.java     # GPU rendering orchestration
└── core/config/
    └── RenderConfig.java       # CSM configuration constants

src/main/resources/shaders/
├── depth_vertex_shader.glsl    # Shadow pass vertex shader
├── voxel_vertex_shader.glsl    # Main pass (computes light-space positions)
└── voxel_fragment_shader.glsl  # Shadow sampling and cascade selection
```

### 2.2 Rendering Pipeline

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Frame Render Loop                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  1. UPDATE PHASE                                                    │
│     ├─ ShadowRenderer.render() called                              │
│     ├─ Extract camera basis from view matrix                       │
│     ├─ CSMCalculator.updateLightBasis(sunDir)                      │
│     └─ CSMCalculator.computeCascadeMatrices(camera, basis)         │
│           ├─ Check if sphere radii need recalculation              │
│           └─ For each cascade: computeCascadeMatrix()              │
│                 ├─ Compute sphere center in world space            │
│                 ├─ Project to light-space, snap to texel grid      │
│                 ├─ Compute Z range from sphere bounds              │
│                 └─ Build lightView × lightProj matrix              │
│                                                                     │
│  2. SHADOW PASS (per cascade)                                       │
│     ├─ Bind FBO with cascade layer                                 │
│     ├─ Clear depth buffer                                          │
│     ├─ Set lightSpaceMatrix uniform                                │
│     ├─ Render all shadow casters (depth only)                      │
│     └─ depth_vertex_shader: gl_Position = lightSpace × world       │
│                                                                     │
│  3. MAIN PASS                                                       │
│     ├─ voxel_vertex_shader:                                        │
│     │     ├─ Compute vViewDepth = -viewPos.z                       │
│     │     └─ For each cascade: vLightSpacePos[i] = matrix × world  │
│     └─ voxel_fragment_shader:                                      │
│           ├─ selectCascade(vViewDepth)                             │
│           ├─ Project to shadow map UV                              │
│           ├─ Calculate cascade-aware bias                          │
│           ├─ PCF sampling with Poisson disk                        │
│           └─ Apply shadow factor to lighting                       │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.3 Data Flow

```
RenderConfig (constants)
       │
       ▼
FrustumSplitter ──────────────────────────────────┐
       │                                          │
       │ splitDistances[]                         │
       ▼                                          │
CSMCalculator ────────────────────────────────────┤
       │                                          │
       │ sphereRadii[], texelSizes[]              │
       │ (cached, recalc on camera param change)  │
       │                                          │
       │ Per frame:                               │
       │ ├─ lightDir, lightRight, lightUp         │
       │ └─ lightSpaceMatrices[]                  │
       │                                          │
       ▼                                          │
CascadeData (record) ─────────────────────────────┤
       │                                          │
       │ Passed to:                               │
       ├─► ShadowRenderer (shadow pass uniforms)  │
       └─► VoxelRenderer (main pass uniforms)     │
                                                  │
Uniforms sent to GPU: ◄───────────────────────────┘
  • lightSpaceMatrices[4]
  • cascadeSplits[5]
  • cascadeTexelSizes[4]
  • cascadeCount
  • shadowMapArray (sampler2DArrayShadow)
```

---

## 3. Code Deep Dive

### 3.1 Sphere Radius Computation ([CSMCalculator.java:201-256](src/main/java/engine/render/shadow/CSMCalculator.java#L201-L256))

```java
public void updateSphereRadii(float fov, float aspect, float near, float far) {
    // Only recompute when camera params change (expensive operation)
    if (fov == cachedFov && aspect == cachedAspect && ...) return;

    // Compute cascade splits using practical split formula
    splitDistances = FrustumSplitter.computeSplitDistances(
        near, CSM_SHADOW_FAR, cascadeCount, CSM_SPLIT_LAMBDA);

    float tanHalfFov = tan(toRadians(fov) / 2.0f);

    for (int i = 0; i < cascadeCount; i++) {
        float zNear = splitDistances[i];
        float zFar = splitDistances[i + 1];

        // Frustum slice dimensions at far plane
        float farHalfH = zFar * tanHalfFov;
        float farHalfW = farHalfH * aspect;

        // Sphere center at midpoint of slice
        float zCenter = (zNear + zFar) / 2.0f;
        float zExtent = zFar - zCenter;

        // Radius to farthest corner (always far plane diagonal)
        float radius = sqrt(farHalfW² + farHalfH² + zExtent²);
        radius *= 1.05f;  // 5% margin for numerical stability

        sphereRadii[i] = radius;
        sphereCenterDistances[i] = zCenter;
        texelSizes[i] = (radius * 2.0f) / shadowMapSize;
    }
}
```

**Key insight**: The sphere radius is computed once and cached. This ensures `texelSize` remains constant, which is critical for stable texel snapping.

### 3.2 Light Basis Construction ([CSMCalculator.java:267-280](src/main/java/engine/render/shadow/CSMCalculator.java#L267-L280))

```java
public void updateLightBasis(Vector3f sunDir) {
    // lightDir points FROM sun TOWARD scene
    lightDir.set(sunDir).negate().normalize();

    // Avoid degeneracy when light is nearly vertical
    Vector3f upRef = new Vector3f(0, 1, 0);
    if (abs(lightDir.dot(upRef)) > 0.98f) {
        upRef.set(0, 0, 1);  // Switch to forward
    }

    // Build orthonormal basis
    lightDir.cross(upRef, lightRight).normalize();
    lightRight.cross(lightDir, lightUp).normalize();
}
```

### 3.3 Per-Frame Matrix Computation ([CSMCalculator.java:333-480](src/main/java/engine/render/shadow/CSMCalculator.java#L333-L480))

```java
private Matrix4f computeCascadeMatrix(int cascadeIndex, Vector3f camPos,
        Vector3f camForward, ...) {

    // STEP 1: Sphere center in world space
    Vector3f sphereCenterWS = camForward × sphereCenterDistances[i] + camPos;

    // STEP 2: Project to light-space XY
    float centerLS_X = sphereCenterWS · lightRight;
    float centerLS_Y = sphereCenterWS · lightUp;

    // STEP 3: Snap to texel grid (CRITICAL for stability)
    float snappedX = round(centerLS_X / texelSize) × texelSize;
    float snappedY = round(centerLS_Y / texelSize) × texelSize;

    // STEP 4: Z range from sphere center ± radius
    float sphereCenterZ = sphereCenterWS · lightDir;
    float minZ = sphereCenterZ - sphereRadius;
    float maxZ = sphereCenterZ + sphereRadius;

    // Add caster margin for shadows from behind camera
    float casterMargin = (maxZ - minZ) × 0.5f;
    minZ -= casterMargin;
    maxZ += casterMargin;

    // STEP 5: Build light-view matrix (manual, no lookAt)
    // JOML: mRC = column R, row C
    Matrix4f lightView = new Matrix4f();
    lightView.m00(lightRight.x); lightView.m10(lightRight.y);
    lightView.m20(lightRight.z); lightView.m30(-snappedX);
    lightView.m01(lightUp.x);    lightView.m11(lightUp.y);
    lightView.m21(lightUp.z);    lightView.m31(-snappedY);
    lightView.m02(lightDir.x);   lightView.m12(lightDir.y);
    lightView.m22(lightDir.z);   lightView.m32(-minZ);
    lightView.m03(0); lightView.m13(0); lightView.m23(0); lightView.m33(1);

    // STEP 6: Orthographic projection
    Matrix4f lightProj = new Matrix4f().orthoLH(
        -halfSize, halfSize,   // X: [-R, +R]
        -halfSize, halfSize,   // Y: [-R, +R]
        0.0f, orthoDepth);     // Z: [0, depth]

    return lightProj.mul(lightView);
}
```

### 3.4 JOML Matrix Convention (Critical!)

JOML uses a **confusing naming convention**:
```
m[column][row]  NOT  m[row][column]
```

For a matrix multiplication `result = M × v`:
```
result.x = m00×v.x + m10×v.y + m20×v.z + m30×v.w
result.y = m01×v.x + m11×v.y + m21×v.z + m31×v.w
result.z = m02×v.x + m12×v.y + m22×v.z + m32×v.w
```

So to compute `X_light = P · lightRight - snappedX`:
- `m00 = lightRight.x` (multiplies P.x)
- `m10 = lightRight.y` (multiplies P.y)
- `m20 = lightRight.z` (multiplies P.z)
- `m30 = -snappedX` (constant term)

### 3.5 Shadow Pass Rendering ([ShadowRenderer.java:144-258](src/main/java/engine/render/renderer/ShadowRenderer.java#L144-L258))

```java
public void render(RenderContext context) {
    // Extract camera basis from view matrix (not yaw/pitch directly)
    computeCameraBasisFromViewMatrix(view);

    // Update light basis from sun direction
    csmCalculator.updateLightBasis(sunDir);

    // Compute cascade matrices
    cascadeData = csmCalculator.computeCascadeMatrices(camera, camForward, ...);

    // Setup shadow pass state
    glDisable(GL_BLEND);
    glColorMask(false, false, false, false);  // Depth only
    glViewport(0, 0, shadowMapSize, shadowMapSize);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);

    for (int i = 0; i < cascadeCount; i++) {
        // Bind cascade layer to FBO
        glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                                   shadowMapArray, 0, i);
        glClear(GL_DEPTH_BUFFER_BIT);

        // Set light-space matrix and render
        depthShader.setMat4("lightSpaceMatrix", cascadeData.lightSpaceMatrices()[i]);
        context.getWorld().renderDepth(depthShader);
    }

    // Restore state
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, windowWidth, windowHeight);
    glColorMask(true, true, true, true);
}
```

### 3.6 Camera Basis Extraction ([ShadowRenderer.java:277-295](src/main/java/engine/render/renderer/ShadowRenderer.java#L277-L295))

```java
private void computeCameraBasisFromViewMatrix(Matrix4f view) {
    // Invert: world→view becomes view→world (camera-to-world)
    Matrix4f invView = new Matrix4f(view).invert();

    // JOML column extraction from invView:
    // Column 0 = camera right in world space
    camRight.set(invView.m00(), invView.m01(), invView.m02()).normalize();

    // Column 1 = camera up in world space
    camUp.set(invView.m10(), invView.m11(), invView.m12()).normalize();

    // Column 2 = camera -forward (OpenGL looks down -Z)
    camForward.set(-invView.m20(), -invView.m21(), -invView.m22()).normalize();

    // Ensure orthonormality
    camRight.cross(camForward, camUp).normalize();
}
```

### 3.7 Fragment Shader Shadow Sampling ([voxel_fragment_shader.glsl:81-133](src/main/resources/shaders/voxel_fragment_shader.glsl#L81-L133))

```glsl
float calculateShadow(vec3 normal, vec3 lightDir, vec3 worldPos, float viewDepth) {
    int cascade = selectCascade(viewDepth);

    // Transform to light-space [0,1] range
    vec4 lightSpacePos = vLightSpacePos[cascade];
    vec3 projCoords = lightSpacePos.xyz / lightSpacePos.w;
    projCoords = projCoords * 0.5 + 0.5;

    // Out-of-bounds check
    if (projCoords.z > 1.0 || projCoords.z < 0.0 || ...) return 1.0;

    // Cascade-aware bias
    float cosTheta = max(dot(normal, lightDir), 0.0);
    float cascadeScale = min(cascadeTexelSizes[cascade] / cascadeTexelSizes[0], 3.0);
    float bias = (0.0005 + 0.002 * (1.0 - cosTheta)) * cascadeScale;

    // PCF with world-stable rotation (prevents shadow swimming)
    float rnd = hash12(floor(worldPos.xz));
    mat2 rot = mat2(cos(angle), -sin(angle), sin(angle), cos(angle));

    float shadow = 0.0;
    for (int i = 0; i < 12; ++i) {
        vec2 offset = rot * POISSON[i] * pcfRadius * texelSize;
        shadow += texture(shadowMapArray,
            vec4(projCoords.xy + offset, float(cascade), projCoords.z - bias));
    }
    return shadow / 12.0;
}
```

**Key details:**
- `sampler2DArrayShadow` performs hardware depth comparison
- The 4th component of `vec4` is the reference depth to compare against
- Returns 0.0 (in shadow) or 1.0 (lit), with linear filtering giving soft edges
- Poisson disk rotation based on world position prevents PCF pattern shimmer

---

## 4. Configuration Parameters

### 4.1 Core CSM Parameters ([RenderConfig.java:94-119](src/main/java/engine/core/config/RenderConfig.java#L94-L119))

| Parameter | Current Value | Description |
|-----------|---------------|-------------|
| `CSM_CASCADE_COUNT` | 3 | Number of shadow cascades (2-4 typical) |
| `CSM_SHADOW_MAP_SIZE` | 2048 | Resolution per cascade (power of 2) |
| `CSM_SPLIT_LAMBDA` | 0.55 | Split blend: 0=linear, 1=logarithmic |
| `CSM_SHADOW_FAR` | 400.0 | Shadow distance (can be < camera far) |
| `CSM_DEPTH_MARGIN_RATIO` | 0.5 | Extra depth for casters behind camera |
| `CSM_BASE_SHADOW_BIAS` | 0.0005 | Base bias before cascade scaling |

### 4.2 Parameter Effects

**`CSM_CASCADE_COUNT`:**
- More cascades = better resolution distribution, but more draw calls
- 3 is the sweet spot for most games
- 4+ useful for very large view distances (flight sims)

**`CSM_SHADOW_MAP_SIZE`:**
- Higher = sharper shadows, more VRAM, slower rendering
- 2048: Good balance (requires ~48MB for 3 cascades with 32-bit depth)
- 4096: Very sharp but ~4x slower shadow pass
- 1024: Acceptable for lower-end hardware

**`CSM_SPLIT_LAMBDA`:**
- 0.0: Linear splits (more far cascade resolution, less near)
- 0.5: Balanced
- 0.75-0.85: Prioritize near field (typical for action games)
- 1.0: Pure logarithmic (most near, least far)

**`CSM_SHADOW_FAR`:**
- Should be ≤ `CAMERA_FAR`
- Lower value = better shadow quality within range
- 400 is good for render distance ~16 chunks (256 blocks)

**`CSM_DEPTH_MARGIN_RATIO`:**
- 0.0: No margin (may miss shadows from tall objects behind camera)
- 0.5: 50% extra depth range (safe default)
- 1.0+: For very tall shadow casters (skyscrapers)

### 4.3 Shader Bias Parameters ([voxel_fragment_shader.glsl:105-110](src/main/resources/shaders/voxel_fragment_shader.glsl#L105-L110))

```glsl
float baseBias = 0.0005;    // Minimum bias (prevents self-shadowing)
float slopeBias = 0.002;    // Additional bias at grazing angles
cascadeScale = min(..., 3.0);  // Cap prevents far cascade over-biasing
```

**Tuning guidelines:**
- If you see **shadow acne** (striped artifacts): Increase `baseBias`
- If you see **peter panning** (shadows detached from objects): Decrease bias
- If **far shadows disappear**: Reduce `cascadeScale` cap or reduce `slopeBias`

---

## 5. Optimization & Improvements

### 5.1 Performance Optimizations

**Already Implemented:**
- ✅ Cached sphere radii (only recompute on camera param change)
- ✅ Single texture array instead of separate textures
- ✅ Hardware PCF via `sampler2DArrayShadow`
- ✅ Depth-only rendering (color writes disabled)

**Potential Improvements:**

1. **Frustum Culling per Cascade**
   - Currently all chunks rendered to all cascades
   - Could cull chunks outside each cascade's frustum
   - Benefit: 30-50% fewer triangles in shadow pass

2. **Geometry Shader Cascade Rendering**
   - Render to all cascades in single pass using geometry shader instancing
   - Benefit: Reduces CPU overhead from multiple draw calls
   - Tradeoff: Geometry shader overhead may negate gains

3. **Variable Shadow Map Resolution**
   - Near cascade: 2048, Mid: 1024, Far: 512
   - Matches perspective distortion better
   - Benefit: ~50% VRAM reduction with minimal quality loss

4. **Shadow Map Caching**
   - Don't re-render if light direction and geometry unchanged
   - Useful for static scenes or slow sun movement
   - Benefit: Skip shadow pass entirely most frames

### 5.2 Quality Improvements

**Already Implemented:**
- ✅ Stabilized fit with texel snapping
- ✅ Cascade-aware bias with cap
- ✅ World-stable PCF rotation

**Potential Improvements:**

1. **Cascade Blending**
   - Blend between cascades at boundaries instead of hard cut
   - Smooths visible seams between cascade zones
   ```glsl
   float blendFactor = smoothstep(0.9, 1.0, viewDepth / splitEnd);
   shadow = mix(shadowCurrent, shadowNext, blendFactor);
   ```

2. **Normal Offset Bias**
   - In addition to depth bias, offset sample position along normal
   - Better handles surfaces at extreme angles
   ```glsl
   vec3 offsetPos = worldPos + normal * normalOffset * texelSize;
   ```

3. **Variance Shadow Maps (VSM)**
   - Store depth and depth² instead of just depth
   - Enables pre-filtered soft shadows
   - Tradeoff: Light bleeding artifacts in some cases

4. **Sample Distribution Shadow Maps (SDSM)**
   - Analyze scene depth buffer before computing splits
   - Places cascade boundaries where geometry actually exists
   - Benefit: Optimal resolution allocation per frame

### 5.3 Debug Tools

**Available Debug Modes (F4 to cycle):**

| Mode | Name | Shows |
|------|------|-------|
| 0 | Off | Normal rendering |
| 1 | Shadow Factor | Grayscale shadow intensity |
| 2 | Light-Space UV | XY as RG, depth as B |
| 3 | Out-of-Bounds | Red=XY OOB, Green=Z OOB |
| 4 | Cascade Index | Pure cascade colors |
| 5 | Cascade + Albedo | Cascade colors blended |
| 6 | Raw Depth | NDC Z visualization |
| 7 | OOB by Cascade | Cascade + OOB combined |
| 8 | Bias Diagnostic | CascadeScale, Bias, Index |

**Freeze Modes (F5 to cycle):**
- Matrix Freeze: Stops matrix updates, depth pass continues
- Full Freeze: Stops both matrix and depth pass

### 5.4 Common Issues & Solutions

| Symptom | Likely Cause | Solution |
|---------|--------------|----------|
| Shadow acne (stripes) | Bias too low | Increase `baseBias` |
| Peter panning (floating shadows) | Bias too high | Decrease bias values |
| Far shadows missing | Cascade scale too high | Reduce `cascadeScale` cap (currently 3.0) |
| Shadows swimming | Texel snapping broken | Ensure `texelSize` is constant per cascade |
| Cascade seams visible | Hard cascade transitions | Implement cascade blending |
| Performance drops | Shadow map too large | Reduce `CSM_SHADOW_MAP_SIZE` |

---

## Appendix: Coordinate System Reference

```
WORLD SPACE (Y-up, right-handed):
    Y
    │
    │
    │
    └───── X
   /
  Z

CAMERA/VIEW SPACE (looks down -Z):
    Y (up)
    │
    │    X (right)
    │   /
    └──/── -Z (forward)


LIGHT SPACE (custom orthonormal basis):
    lightUp
    │
    │    lightRight
    │   /
    └──/── lightDir (toward scene)


NDC (after projection):
    Y [-1, +1]
    │
    │    X [-1, +1]
    │   /
    └──/── Z [-1, +1] → depth [0, 1]
```

---

## Summary

This CSM implementation uses a **Stabilized Fit** algorithm that:

1. **Pre-computes** bounding sphere radii and texel sizes when camera parameters change
2. **Snaps** light-space centers to the texel grid every frame, preventing shadow swimming
3. **Computes Z range from sphere bounds**, ensuring consistent depth coverage
4. **Applies cascade-aware bias** that scales with texel size but is capped to prevent over-biasing

The result is stable, shimmer-free shadows across the entire view distance with configurable quality/performance tradeoffs.
