# Rapport Technique : Pipeline de Rendu et Système de Shaders

## Table des Matières

1. [Vue d'Ensemble de l'Architecture](#1-vue-densemble-de-larchitecture)
2. [Système de Shaders](#2-système-de-shaders)
3. [Pipeline de Rendu Principal](#3-pipeline-de-rendu-principal)
4. [Système de Construction de Mesh](#4-système-de-construction-de-mesh)
5. [Shadow Mapping](#5-shadow-mapping)
6. [Système de Caméra](#6-système-de-caméra)
7. [Gestion des Textures](#7-gestion-des-textures)
8. [Rendu UI](#8-rendu-ui)
9. [Évaluation Critique](#9-évaluation-critique)
10. [Recommandations pour un Rendu Industry-Standard](#10-recommandations-pour-un-rendu-industry-standard)

---

## 1. Vue d'Ensemble de l'Architecture

### 1.1 Diagramme de Flux

```
┌─────────────────────────────────────────────────────────────────────┐
│                          FRAME LOOP                                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  1. INPUT PHASE                                                      │
│     └─ glfwPollEvents()                                              │
│                                                                      │
│  2. UPDATE PHASE                                                     │
│     ├─ Camera position/rotation                                      │
│     ├─ Player movement                                               │
│     └─ Chunk loading/unloading                                       │
│                                                                      │
│  3. MESH BUILDING (Multi-threaded)                                   │
│     ├─ Main Thread: meshManager.processMeshes()                      │
│     └─ Worker Threads: MeshBuilder.generateMeshData()                │
│                                                                      │
│  4. RENDER PHASE                                                     │
│     ├─ A. SkyRenderer      → glClear avec couleur du ciel            │
│     ├─ B. ShadowRenderer   → Depth pass (FBO shadow map)             │
│     ├─ C. Mesh Upload      → MeshData → GPU VAO/VBO                  │
│     ├─ D. VoxelRenderer    → Rendu des chunks                        │
│     ├─ E. SunRenderer      → Billboard soleil                        │
│     └─ F. UIRenderer       → Interface utilisateur 2D                │
│                                                                      │
│  5. BUFFER SWAP                                                      │
│     └─ window.swapBuffers()                                          │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 1.2 Technologies Utilisées

| Composant | Technologie | Version |
|-----------|-------------|---------|
| API Graphique | OpenGL | 3.0 Core Profile |
| Bindings Java | LWJGL | 3.3.1 |
| Fenêtrage | GLFW | via LWJGL |
| Chargement Textures | STB Image | via LWJGL |
| Rendu Texte | STB TrueType | via LWJGL |
| Mathématiques | JOML | Dernière version |

---

## 2. Système de Shaders

### 2.1 Classe Shader

**Fichier:** [Shader.java](src/main/java/engine/render/Shader.java)

**Rôle:** Gestion centralisée de la compilation, linking et uniforms des shaders OpenGL.

#### Méthodes Clés

| Méthode | Description |
|---------|-------------|
| `Shader(vertexPath, fragmentPath)` | Charge et compile les shaders depuis le classpath |
| `bind()` / `unbind()` | Active/désactive le programme shader |
| `setUniform(name, value)` | Configure les uniforms (int, float, Vector3f, Vector4f) |
| `setMat4(name, matrix)` | Configure les matrices 4x4 |
| `setTexture(name, textureId, unit)` | Lie une texture à une unité |
| `cleanup()` | Libère les ressources GPU |

#### Binding des Attributs

```java
// Locations fixes pour tous les shaders
glBindAttribLocation(programId, 0, "aPos");
glBindAttribLocation(programId, 1, "aTex");
glBindAttribLocation(programId, 2, "aNormal");
```

### 2.2 Shaders de Voxels

**Fichiers:** `voxel_vertex_shader.glsl`, `voxel_fragment_shader.glsl`

#### Vertex Shader - Attributs d'Entrée

```glsl
layout (location = 0) in vec3 aPos;      // Position du vertex
layout (location = 1) in vec2 aTex;      // Coordonnées texture
layout (location = 2) in vec3 aNormal;   // Normale de la face
layout (location = 3) in float aLight;   // Facteur d'ombre (0.0-1.0)
layout (location = 4) in float aAO;      // Ambient Occlusion
```

#### Fragment Shader - Fonctionnalités

| Feature | Implémentation |
|---------|----------------|
| **Éclairage Directionnel** | `diffuse = max(dot(N, sunDir), 0.0)` |
| **Shadow Mapping PCF** | 1 sample, résolution 2048×3072, bias 0.005 |
| **Ambient Occlusion** | Interpolation `mix(0.7, 1.0, vAO)` |
| **Fog Linéaire** | Distance-based, fade vers `skyColor` |
| **Gamma Correction** | Via `GL_SRGB8_ALPHA8` texture format |

### 2.3 Shaders de Depth/Shadow

**Fichiers:** `depth_vertex_shader.glsl`, `depth_fragment_shader.glsl`

**Rôle:** Premier pass pour générer la shadow map depuis la perspective du soleil.

```glsl
// Vertex: Transformation vers l'espace lumière
gl_Position = lightSpaceMatrix * modelMatrix * vec4(aPos, 1.0);

// Fragment: Vide (seule la profondeur est écrite)
void main() { }
```

### 2.4 Shaders UI

**Fichiers:** `ui_vertex.glsl`, `ui_fragment.glsl`

```glsl
// Mode couleur solide ou texture
uniform int uUseTexture;  // 0 = couleur, 1 = texture

if (uUseTexture == 1) {
    FragColor = texture(uTexture, vTexCoord) * uColor;
} else {
    FragColor = uColor;
}
```

### 2.5 Shaders de Texte

**Fichiers:** `text_vertex.glsl`, `text_fragment.glsl`

```glsl
// Alpha depuis le canal RED du bitmap de police
float alpha = texture(uFontTex, vUV).r;
FragColor = vec4(uColor, alpha);
```

---

## 3. Pipeline de Rendu Principal

### 3.1 Interface Renderer

**Fichier:** [Renderer.java](src/main/java/engine/render/renderer/Renderer.java)

```java
public interface Renderer {
    void render(RenderContext context);
    void cleanup();
}
```

### 3.2 Implémentations

| Renderer | Rôle | Fichier |
|----------|------|---------|
| `SkyRenderer` | Clear avec couleur du ciel basée sur l'heure | [SkyRenderer.java](src/main/java/engine/render/renderer/SkyRenderer.java) |
| `ShadowRenderer` | Pass de profondeur pour shadow map | [ShadowRenderer.java](src/main/java/engine/render/renderer/ShadowRenderer.java) |
| `VoxelRenderer` | Rendu principal des chunks | [VoxelRenderer.java](src/main/java/engine/render/renderer/VoxelRenderer.java) |
| `SunRenderer` | Billboard du soleil | [SunRenderer.java](src/main/java/engine/render/renderer/SunRenderer.java) |

### 3.3 RenderContext

**Fichier:** [RenderContext.java](src/main/java/engine/render/core/RenderContext.java)

**Rôle:** État partagé entre tous les render passes.

```java
class RenderContext {
    Camera camera;
    World world;
    Matrix4f projectionMatrix, viewMatrix;
    int windowWidth, windowHeight;
    float deltaTime, aspectRatio;
    Vector3f sunDirection;

    void beginFrame(float deltaTime);
    void setWindowSize(int width, int height);
}
```

### 3.4 VoxelRenderer - Séquence de Rendu

**Fichier:** [VoxelRenderer.java](src/main/java/engine/render/renderer/VoxelRenderer.java)

```java
void render(RenderContext context) {
    // 1. Shadow pass
    shadowRenderer.render(context);

    // 2. Configuration shader
    voxelShader.bind();
    voxelShader.setUniform("sunDir", sunDirection);
    voxelShader.setUniform("sunColor", sunColor);
    voxelShader.setUniform("ambient", ambientIntensity);
    voxelShader.setMat4("projectionMatrix", projection);
    voxelShader.setMat4("viewMatrix", view);
    voxelShader.setMat4("lightSpaceMatrix", lightSpace);

    // 3. Bind textures
    voxelShader.setTexture("atlasTexture", atlas, 0);
    voxelShader.setTexture("shadowMap", shadowMap, 1);

    // 4. Upload meshes from worker threads
    meshManager.uploadMeshesIfAny();

    // 5. Render visible chunks
    for (Chunk chunk : getVisibleChunks()) {
        Matrix4f model = new Matrix4f().translate(chunk.worldX, 0, chunk.worldZ);
        voxelShader.setMat4("modelMatrix", model);
        meshBuilder.renderChunk(chunk);
    }
}
```

---

## 4. Système de Construction de Mesh

### 4.1 MeshBuilder

**Fichier:** [MeshBuilder.java](src/main/java/engine/render/MeshBuilder.java)

**Rôle:** Conversion des données voxel en triangles GPU-ready.

#### Format de Vertex (Interleaved)

| Attribut | Type | Taille | Offset |
|----------|------|--------|--------|
| Position | vec3 | 12 bytes | 0 |
| TexCoords | vec2 | 8 bytes | 12 |
| Normal | vec3 | 12 bytes | 20 |
| Light | float | 4 bytes | 32 |
| AO | float | 4 bytes | 36 |
| **Total** | | **40 bytes** | |

#### Méthodes Principales

| Méthode | Description |
|---------|-------------|
| `generateMeshData(Chunk, World)` | Génère FloatBuffer (thread-safe) |
| `buildChunkMesh(Chunk, World)` | Crée VAO/VBO sur GPU |
| `renderChunk(Chunk)` | `glDrawArrays(GL_TRIANGLES)` |

#### Face Culling

```java
// Seulement les faces adjacentes à l'air sont rendues
if (isAir(neighbor)) {
    addQuad(face, x, y, z, textureCoords, normal, light, ao);
}
```

#### Calcul d'Ambient Occlusion

```java
// 3 blocs adjacents par coin de vertex
float ao = 1.0f - (adjacentSolidBlocks * 0.33f);
// Range: 0.0 (totalement sombre) à 1.0 (pas d'occlusion)
```

### 4.2 Architecture Multi-threadée

**Fichiers:**
- [MeshWorkerManager.java](src/main/java/engine/render/MeshWorkerManager.java)
- [MeshUploadQueue.java](src/main/java/engine/render/MeshUploadQueue.java)
- [MeshData.java](src/main/java/engine/render/MeshData.java)

```
┌─────────────────────────────────────────────────────────────┐
│                    MESH THREADING MODEL                      │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  MAIN THREAD (OpenGL Context)                                │
│  ├─ queueChunk(chunk)                                        │
│  │   └─ ConcurrentLinkedQueue                                │
│  ├─ processMeshes()                                          │
│  │   └─ Submit to ThreadPool                                 │
│  └─ uploadMeshesIfAny()                                      │
│      └─ Poll MeshUploadQueue → glBufferData()                │
│                                                              │
│  WORKER THREADS                                              │
│  └─ MeshBuilder.generateMeshData()                           │
│      ├─ Generate quads                                       │
│      ├─ Calculate AO and lighting                            │
│      └─ Enqueue MeshData                                     │
│                                                              │
│  ⚠️ OpenGL calls ONLY on main thread!                        │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## 5. Shadow Mapping

### 5.1 ShadowRenderer

**Fichier:** [ShadowRenderer.java](src/main/java/engine/render/renderer/ShadowRenderer.java)

#### Configuration du Framebuffer

```java
// Texture de profondeur
glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT,
             3072, 2048, 0, GL_DEPTH_COMPONENT, GL_FLOAT, null);

// Mode de comparaison pour PCF
glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE,
                GL_COMPARE_REF_TO_TEXTURE);
glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
```

#### Calcul de la Light Space Matrix

```java
Vector3f sunPos = camera.sub(sunDir.mul(100));
Matrix4f lightView = new Matrix4f().lookAt(sunPos, camera, UP);
Matrix4f lightProj = new Matrix4f().ortho(-100, 100, -100, 100, 0.1f, 300f);
Matrix4f lightSpace = lightProj.mul(lightView);
```

### 5.2 ShadowCalculator

**Fichier:** [ShadowCalculator.java](src/main/java/engine/render/ShadowCalculator.java)

**Rôle:** Pré-calcul d'un masque d'ombre par chunk (raymarching CPU).

```java
// Pour chaque colonne de blocs
for (int x = 0; x < WIDTH; x++) {
    for (int z = 0; z < DEPTH; z++) {
        // Raymarche vers le soleil
        Vector3f ray = new Vector3f(x, topY, z);
        while (inBounds(ray)) {
            ray.add(sunDir.negate());
            if (isSolid(ray)) {
                markShadowed(x, y, z);
                break;
            }
        }
    }
}
```

---

## 6. Système de Caméra

### 6.1 Interface Camera

**Fichier:** [Camera.java](src/main/java/engine/render/camera/Camera.java)

```java
interface Camera {
    Matrix4f getProjectionMatrix();
    Matrix4f getViewMatrix(Vector3f position, float yaw, float pitch);
    Vector3f getPosition();
    void updateProjection();
    float getAspect();
}
```

### 6.2 PerspectiveCamera

**Fichier:** [PerspectiveCamera.java](src/main/java/engine/render/camera/PerspectiveCamera.java)

| Paramètre | Valeur |
|-----------|--------|
| FOV | 90.0° |
| Near Plane | 0.1 |
| Far Plane | 1000.0 |

```java
// View Matrix
Matrix4f view = new Matrix4f()
    .rotateX(pitch)
    .rotateY(yaw)
    .translate(-position.x, -position.y, -position.z);
```

### 6.3 OrthographicCamera

**Fichier:** [OrthographicCamera.java](src/main/java/engine/render/camera/OrthographicCamera.java)

**Usage:** Rendu UI 2D avec origine en haut-gauche.

```java
// Système de coordonnées UI
// (0,0) = coin supérieur gauche
// Y augmente vers le bas
Matrix4f ortho = new Matrix4f().ortho(0, width, height, 0, -1, 1);
```

---

## 7. Gestion des Textures

### 7.1 TextureLoader

**Fichier:** [TextureLoader.java](src/main/java/engine/render/TextureLoader.java)

```java
// Chargement avec STB Image
ByteBuffer image = stbi_load_from_memory(data, width, height, channels, 4);

// Upload GPU avec gamma correction
glTexImage2D(GL_TEXTURE_2D, 0, GL_SRGB8_ALPHA8,
             width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, image);

// Filtrage pixel-perfect pour voxels
glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
```

### 7.2 Atlas de Textures

**Fichier:** [TextureCoords.java](src/main/java/engine/render/TextureCoords.java)

| Paramètre | Valeur |
|-----------|--------|
| Grid Size | 64×64 tiles |
| Tile Size | 16×16 pixels |
| Atlas Total | 1024×1024 pixels |
| Capacité | 4096 textures uniques |

```java
// Calcul des coordonnées UV depuis la position dans la grille
float tileSize = 1.0f / atlasSize;
float uMin = col * tileSize;
float uMax = uMin + tileSize;
float vMax = 1.0f - (row - 1) * tileSize;  // Inversion V pour OpenGL
float vMin = vMax - tileSize;
```

---

## 8. Rendu UI

### 8.1 UIRenderer

**Fichier:** [UIRenderer.java](src/main/java/engine/render/UIRenderer.java)

| Méthode | Description |
|---------|-------------|
| `drawRect()` | Rectangle couleur solide |
| `drawTexture()` | Rectangle texturé avec tint |
| `drawRectWithBorder()` | Rectangle avec bordure |
| `drawBorder()` | Bordure seule (4 rectangles) |

```java
// Configuration blending
glEnable(GL_BLEND);
glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
```

### 8.2 TextRenderer

**Fichier:** [TextRenderer.java](src/main/java/engine/render/TextRenderer.java)

**Pipeline de Rendu Texte:**

1. Chargement TTF → `stbtt_BakeFontBitmap()`
2. Bitmap 512×512, ASCII 32-127
3. Texture GL_RED (single channel)
4. Quad par caractère avec UV du glyph

---

## 9. Évaluation Critique

### 9.1 Points Forts ✅

| Aspect | Évaluation |
|--------|------------|
| **Architecture Multi-threadée** | Bonne séparation mesh generation / GPU upload |
| **Face Culling** | Seules les faces visibles sont meshées |
| **Gamma Correction** | `GL_SRGB8_ALPHA8` + `GL_FRAMEBUFFER_SRGB` |
| **Ambient Occlusion** | AO per-vertex pour la profondeur |
| **Shadow Mapping** | Implémentation fonctionnelle avec PCF basique |
| **Texture Atlas** | Single bind, réduction des state changes |

### 9.2 Points Faibles ❌

#### Architecture

| Problème | Impact | Sévérité |
|----------|--------|----------|
| OpenGL 3.0 limité | Pas de geometry shaders, compute, instancing | 🔴 Haute |
| Pas de mipmaps | Aliasing à distance | 🟡 Moyenne |
| Re-mesh chunk entier | Inefficace pour modifications locales | 🟡 Moyenne |
| Pas de LOD | Performance distance non optimisée | 🔴 Haute |

#### Shadow Mapping

| Problème | Impact | Sévérité |
|----------|--------|----------|
| 1 sample PCF | Shadows dures avec artefacts | 🟡 Moyenne |
| Shadow acne possible | Bias fixe peut être insuffisant | 🟡 Moyenne |
| Résolution fixe | Pas adaptatif à la distance | 🟡 Moyenne |

#### Éclairage

| Problème | Impact | Sévérité |
|----------|--------|----------|
| Pas de Global Illumination | Éclairage plat dans les zones ombragées | 🔴 Haute |
| Pas de réflections | Surfaces mates uniquement | 🟡 Moyenne |
| Pas de SSAO | AO limité aux vertices | 🟡 Moyenne |
| Pas de bloom/HDR | Rendu LDR uniquement | 🟡 Moyenne |

#### Performance

| Problème | Impact | Sévérité |
|----------|--------|----------|
| Pas de frustum culling GPU | CPU-bound pour visibility | 🟡 Moyenne |
| Pas d'occlusion culling | Chunks cachés toujours rendus | 🔴 Haute |
| Pas de mesh compression | Mémoire GPU élevée | 🟡 Moyenne |

---

## 10. Recommandations pour un Rendu Industry-Standard

### 10.1 Améliorations Immédiates (Court Terme)

#### 1. Upgrade vers OpenGL 4.3+

```java
// Permet compute shaders, instancing, indirect draw
glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
```

**Bénéfices:**
- Compute shaders pour mesh generation GPU-side
- Instanced rendering pour chunks identiques
- Indirect draw pour réduire CPU overhead

#### 2. Cascaded Shadow Maps (CSM)

```glsl
// 3-4 cascades pour différentes distances
uniform mat4 lightSpaceMatrices[4];
uniform float cascadeSplits[4];

int cascade = getCascadeIndex(viewSpaceDepth);
vec3 shadowCoord = (lightSpaceMatrices[cascade] * worldPos).xyz;
```

**Bénéfices:**
- Meilleure résolution près de la caméra
- Shadows plus nettes sans augmenter la résolution totale

#### 3. PCF Amélioré (5×5 ou Poisson Disk)

```glsl
// Poisson disk sampling
const vec2 poissonDisk[16] = vec2[](
    vec2(-0.94201624, -0.39906216), ...
);

float shadow = 0.0;
for (int i = 0; i < 16; i++) {
    shadow += texture(shadowMap,
        projCoords.xy + poissonDisk[i] * texelSize);
}
shadow /= 16.0;
```

#### 4. Screen-Space Ambient Occlusion (SSAO)

```glsl
// Échantillonnage hémisphérique
for (int i = 0; i < KERNEL_SIZE; i++) {
    vec3 samplePos = TBN * kernel[i];
    samplePos = fragPos + samplePos * radius;

    float sampleDepth = texture(gDepth, projectToScreen(samplePos)).r;
    occlusion += (sampleDepth >= samplePos.z ? 0.0 : 1.0);
}
```

### 10.2 Améliorations Moyennes (Moyen Terme)

#### 5. Deferred Rendering Pipeline

```
┌──────────────────────────────────────────────────────────────┐
│                   DEFERRED PIPELINE                          │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  GEOMETRY PASS                                               │
│  ├─ G-Buffer: Position (RGB16F)                              │
│  ├─ G-Buffer: Normal (RGB16F)                                │
│  ├─ G-Buffer: Albedo + Specular (RGBA8)                      │
│  └─ G-Buffer: Depth (32F)                                    │
│                                                              │
│  LIGHTING PASS                                               │
│  ├─ Read G-Buffer                                            │
│  ├─ Apply directional light                                  │
│  ├─ Apply point lights (instanced)                           │
│  └─ Apply SSAO                                               │
│                                                              │
│  POST-PROCESS PASS                                           │
│  ├─ Bloom                                                    │
│  ├─ Tone mapping (HDR → LDR)                                 │
│  ├─ Color grading                                            │
│  └─ FXAA/TAA                                                 │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

**Bénéfices:**
- Éclairage découplé de la géométrie
- Support de nombreuses lumières ponctuelles
- Post-processing flexible

#### 6. Physically Based Rendering (PBR)

```glsl
// BRDF Cook-Torrance
vec3 F0 = mix(vec3(0.04), albedo, metallic);
vec3 F = fresnelSchlick(max(dot(H, V), 0.0), F0);

float D = distributionGGX(N, H, roughness);
float G = geometrySmith(N, V, L, roughness);

vec3 specular = (D * G * F) / (4.0 * NdotV * NdotL + 0.0001);
vec3 kD = (vec3(1.0) - F) * (1.0 - metallic);

Lo += (kD * albedo / PI + specular) * radiance * NdotL;
```

**Textures Requises:**
- Albedo (couleur de base)
- Normal map
- Metallic/Roughness
- Ambient Occlusion (baked)

#### 7. Level of Detail (LOD) pour Chunks

```java
class ChunkLOD {
    Mesh highDetail;   // Distance < 32 blocks
    Mesh mediumDetail; // Distance < 128 blocks
    Mesh lowDetail;    // Distance > 128 blocks

    Mesh selectLOD(float distance) {
        if (distance < 32) return highDetail;
        if (distance < 128) return mediumDetail;
        return lowDetail;
    }
}
```

**Algorithme Greedy Meshing pour LOD:**
- Fusionne les faces adjacentes de même texture
- Réduit considérablement le vertex count

### 10.3 Améliorations Avancées (Long Terme)

#### 8. Voxel Cone Tracing (Global Illumination)

```
┌──────────────────────────────────────────────────────────────┐
│              VOXEL CONE TRACING                              │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  1. VOXELIZATION PASS                                        │
│     └─ Rasterize scene into 3D texture (128³ or 256³)        │
│                                                              │
│  2. MIPMAP GENERATION                                        │
│     └─ Generate 3D mipmaps for cone filtering                │
│                                                              │
│  3. CONE TRACING                                             │
│     ├─ Diffuse: 5-6 wide cones in hemisphere                 │
│     ├─ Specular: 1 narrow cone in reflection direction       │
│     └─ Sample voxel grid, accumulate radiance                │
│                                                              │
│  RÉSULTAT: Indirect lighting + soft reflections              │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

#### 9. Volumetric Fog/Lighting

```glsl
// Raymarching dans le volume
vec3 rayDir = normalize(fragPos - cameraPos);
float stepSize = length(fragPos - cameraPos) / STEPS;

vec3 scattering = vec3(0.0);
float transmittance = 1.0;

for (int i = 0; i < STEPS; i++) {
    vec3 pos = cameraPos + rayDir * (stepSize * i);
    float density = sampleDensity(pos);

    // In-scattering from sun
    float shadow = sampleShadowMap(pos);
    vec3 inScatter = sunColor * shadow * density;

    scattering += inScatter * transmittance;
    transmittance *= exp(-density * stepSize);
}
```

#### 10. Temporal Anti-Aliasing (TAA)

```glsl
// Jitter projection matrix
mat4 jitteredProj = projection;
jitteredProj[2][0] += jitterX / screenWidth;
jitteredProj[2][1] += jitterY / screenHeight;

// Blend avec frame précédente
vec2 motionVector = currentPos - previousPos;
vec3 historyColor = texture(historyBuffer, uv - motionVector).rgb;
vec3 currentColor = texture(currentBuffer, uv).rgb;

// Neighborhood clamping
vec3 minColor, maxColor;
computeNeighborhoodAABB(minColor, maxColor);
historyColor = clamp(historyColor, minColor, maxColor);

FragColor = mix(historyColor, currentColor, 0.1);
```

### 10.4 Comparaison Industry Standard

| Feature | État Actuel | Minecraft | Hytale | UE5 |
|---------|-------------|-----------|--------|-----|
| Shadows | PCF 1-sample | CSM | CSM + VSM | Ray-traced |
| AO | Per-vertex | SSAO | HBAO+ | RTAO |
| GI | Aucun | Aucun | VCT | Lumen |
| Reflections | Aucun | Aucun | SSR | RT Reflections |
| Anti-aliasing | Aucun | FXAA | TAA | TSR |
| HDR | Non | Non | Oui | Oui |
| PBR | Non | Non | Oui | Oui |
| LOD | Non | Oui | Oui | Nanite |

### 10.5 Roadmap Recommandée

```
PHASE 1 (1-2 mois)
├─ Upgrade OpenGL 4.3+
├─ Cascaded Shadow Maps
├─ SSAO basique
└─ Mipmaps pour textures

PHASE 2 (2-4 mois)
├─ Deferred rendering
├─ HDR + Tone mapping
├─ Bloom
├─ FXAA ou TAA
└─ Greedy meshing + LOD

PHASE 3 (4-6 mois)
├─ PBR materials
├─ Screen-Space Reflections
├─ Volumetric fog
└─ Point lights support

PHASE 4 (6+ mois)
├─ Voxel Cone Tracing (GI)
├─ Occlusion culling GPU
├─ Compute shader meshing
└─ Vulkan backend (optionnel)
```

---

## Conclusion

Le système de rendu actuel fournit une base fonctionnelle avec shadow mapping, ambient occlusion et multithreading. Cependant, pour atteindre un niveau "industry standard", les priorités devraient être:

1. **Upgrade OpenGL** - Prérequis pour toute amélioration majeure
2. **Cascaded Shadow Maps** - Impact visuel immédiat
3. **SSAO** - Améliore significativement la perception de profondeur
4. **Deferred Rendering** - Architecture nécessaire pour effets avancés
5. **PBR + HDR** - Standard moderne pour le réalisme des matériaux

L'architecture actuelle supporte bien ces évolutions grâce à sa séparation claire entre mesh generation, rendering et UI.
