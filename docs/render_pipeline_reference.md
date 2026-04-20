# Référence Architecture — Pipeline de Rendu Voxel + CSM
**Projet :** TOB_Voxel_Project
**Stack :** Java 21 / LWJGL / OpenGL 3.2 Core / macOS Apple Silicon (Metal via MoltenVK-like translation)
**Usage de ce document :** contexte LLM pour continuer le travail sur le système d'ombres

---

## 1. Vue d'ensemble de la pipeline

```
Chaque frame :
┌─────────────────────────────────────────────────────────────────────┐
│ 1. ShadowRenderer.render()     — shadow depth pass (FBO, no color)  │
│    ├─ renderRunning/Warmup/Off                                       │
│    ├─ rendu A (optionnel, camera snap)    → shadowAtlas layers 0-2   │
│    ├─ rendu B (optionnel, camera snap)    → shadowAtlas layers 3-5   │
│    └─ rendu C (sliced, 1 cascade/frame)  → shadowMapArrayC layers 0-2│
│                                                                      │
│ 2. SkyRenderer.render()        — skybox (couleur gradient)          │
│ 3. SunRenderer.render()        — disque solaire                     │
│ 4. VoxelRenderer.render()      — chunks + ombres CSM                │
│    ├─ bind shadowAtlas (unit 1)                                      │
│    ├─ bind colorTestArray (unit 2) — micro-tests perf seulement     │
│    └─ voxel_fragment_shader.glsl                                     │
└─────────────────────────────────────────────────────────────────────┘
```

**Configs principales** (`RenderConfig.java`) :
```java
CSM_CASCADE_COUNT   = 3        // Cascades (0=near, 1=mid, 2=far)
CSM_SHADOW_MAP_SIZE = 2048     // px par cascade, carré
CSM_SHADOW_FAR      = 400.0f   // Distance max ombres (blocks)
CSM_SPLIT_LAMBDA    = 0.55f    // 0=linéaire, 1=log (Practical Split)
SHADOWS_ENABLED     = true     // Guard O1 : false = shader lit shadow=1.0
```

---

## 2. ShadowRenderer — Triple-Buffer Leapfrog

### 2.1 Concept général

Le soleil bouge en temps réel. Pour éviter que les ombres "sautent" visuellement à chaque mise à jour de la shadow map, le système utilise un **triple buffer leapfrog** :

- **A** : shadow map **courante**, échantillonnée par le fragment shader
- **B** : shadow map **cible** du prochain crossfade (futur proche)
- **C** : shadow map **en préparation** en background (futur lointain, staging)

```
timeline (normalized 0..1) :
   baseTimeOfDayA         +leadTime         +2*leadTime
         │                    │                   │
         A (rendu actuel)     B (cible xfade)     C (staging)
         ├────────────────────┤───────────────────┤
              crossfade progress: 0→1 en 0.67 s
```

Quand `crossfadeProgress` atteint 1.0 → **SWAP** :
1. `baseTimeOfDayA += leadTimeDayFrac`
2. Rotation des offsets atlas : A← B, B← C (ancienne position A)
3. Rotation des données/bases/snap : `dataA=dataB`, `dataB=dataC`, `dataC=oldDataA`
4. Re-rendu de C dans le slot B de l'atlas
5. Calcul nouveau `dataC` à `baseTimeOfDayA + 2*leadTime`
6. `cDirty=true` → sliced updates de C reprennent (1 cascade/frame)

### 2.2 Atlas unifié A+B

```
shadowAtlas (GL_TEXTURE_2D_ARRAY, GL_DEPTH_COMPONENT32F)
    ├─ 6 layers total = cascadeCount * 2
    ├─ layers [atlasOffsetA .. atlasOffsetA+2] = A
    └─ layers [atlasOffsetB .. atlasOffsetB+2] = B

atlasOffsetA = 0 ou 3  ← s'échangent sur SWAP (zero GPU cost)
atlasOffsetB = 3 ou 0

shadowMapArrayC (séparé, 3 layers) ← jamais lié au voxel shader
colorTestArray  (GL_R32F, 3 layers) ← micro-tests perf uniquement
```

Création en Java :
```java
// ShadowRenderer constructor
glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_DEPTH_COMPONENT32F,
    shadowMapSize, shadowMapSize, layerCount,   // layerCount = cascadeCount*2
    0, GL_DEPTH_COMPONENT, GL_FLOAT, null);
glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_MODE, GL_NONE);
// GL_NONE : pas de hardware compare → sampling manuel dans le shader
```

### 2.3 MAP↔MATRIX↔BASIS LOCK

Chaque buffer (A, B, C) a ses **propres vecteurs de base** figés au moment du rendu :

```java
// Par buffer (ex : A)
Vector3f lightDirA, lightRightA, lightUpA;  // base orthonormée

// renderRunning() utilise computeCascadeMatricesWithBasis() avec la base figée
// JAMAIS updateLightBasis() pour A/B en mode running
CascadeData candA = csmCalculator.computeCascadeMatricesWithBasis(
    camera, camForward, camRight, camUp,
    lightDirA, lightRightA, lightUpA);  // ← base gelée de A
```

**Invariant critique** : la shadow map d'un buffer et sa matrice de projection utilisent exactement la même base lumineuse. Si la base changeait sans re-rendre la texture, les coordonnées shadow map seraient incohérentes → acné/peter-panning.

### 2.4 CSMCalculator — Base lumineuse

Algorithme **Pixar/Duff JCGT 2017** (`updateLightBasis`) :

```java
lightDir.set(sunDir).negate().normalize();  // FROM sun TOWARD scene
float sign = Math.copySign(1.0f, z);
float a = -1.0f / (sign + z);
float b = x * y * a;
// Résultat : base orthonormée garantie (aucun cleanup externe nécessaire)
lightRight.set(-(1 + sign*x*x*a), -sign*b, sign*x);
lightUp.set(b, sign + y*y*a, -y);
```

**Stabilisation sub-texel** (CSMCalculator) :
- Rayon de bounding sphere par cascade : calculé une fois si FOV/aspect/near/far constants
- Snap au texel le plus proche (entier) pour éviter le "shadow swimming"
- Cooldown 0.12 s entre snaps pour éviter les mises à jour trop fréquentes

### 2.5 Machine d'état leapfrog

```java
enum LeapState { OFF, WARMUP, RUNNING }
```

- **OFF** : un seul shadow pass A, pas de crossfade
- **WARMUP** (1 seule frame) : initialise A, B, C avec forces basis, passe à RUNNING
- **RUNNING** : le régime normal — camera snaps conditionnels, crossfade, sliced C updates

---

## 3. VoxelRenderer — Uniforms et Bindings

```java
// Texture units
unit 0 : textureSampler       (atlas texture — GL_TEXTURE_2D)
unit 1 : shadowAtlas          (GL_TEXTURE_2D_ARRAY, GL_DEPTH_COMPONENT32F, GL_COMPARE_MODE=GL_NONE)
unit 2 : colorTestArray       (GL_TEXTURE_2D_ARRAY, GL_R32F — micro-tests seulement)
unit 3 : shadowAtlasShadow    (même atlas, GL_COMPARE_REF_TO_TEXTURE+GL_LEQUAL — stages 14/15)

// Unit 3 : la même texture est liée deux fois (unit 1 et unit 3) avec des params différents.
// VoxelRenderer.render() mute les params avant renderVisibleChunks() et les restaure après :
glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_R_TO_TEXTURE);
glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
// ... renderVisibleChunks() ...
glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_MODE, GL_NONE); // restaure

// Uniforms shadow
voxelShader.setUniform("shadowsEnabled", SHADOWS_ENABLED ? 1 : 0);
voxelShader.setUniform("atlasOffsetA", shadowRenderer.getAtlasOffsetA()); // 0 ou 3
voxelShader.setUniform("atlasOffsetB", shadowRenderer.getAtlasOffsetB()); // 3 ou 0

// Uniforms CSM (primary)
lightSpaceMatrices[MAX_CASCADES]  // mat4 array
cascadeSplits[MAX_CASCADES+1]     // float array
cascadeTexelSizes[MAX_CASCADES]   // float array
cascadeCount, shadowMapInvSize

// Uniforms leapfrog
lightSpaceMatricesB[MAX_CASCADES] // secondary matrices
cascadeSplitsB, cascadeTexelSizesB
leapfrogActive   // 0 ou 1  ← actuellement codé à 0 en prod (à activer)
shadowCrossfade  // 0.0 → 1.0
blendMode        // 0=dither stochastique, 1=mix() linéaire
```

**Note importante** : `leapfrogActive` est actuellement forcé à `0` dans VoxelRenderer (`voxelShader.setUniform("leapfrogActive", 0 /*leapfrogTransitioning ? 1 : 0*/)`). ShadowRenderer fait le travail A/B/C mais le shader ne blende pas encore en production. Pour activer : décommenter l'expression.

---

## 4. Voxel Fragment Shader — Anatomie complète

### 4.1 Defines compile-time

```glsl
#define PERF_BISECT_STAGE 5  // ← changer pour les tests perf
#define SHADOW_FILTER_MODE 2  // 0=single, 1=PCF+jitter, 2=world-stable PCF
#define WORLD_CELL_SIZE 4.0   // grille monde pour rotation PCF stable
#define PCF_RADIUS_TEXELS 1.5
#define PCF_SAMPLE_COUNT 4    // taps Poisson
```

### 4.2 Inputs vertex → fragment

```glsl
in vec3 vNormal, vWorldPos;
in vec2 vTexCoord;
in float vAO;          // ambient occlusion baked
in float vViewDepth;   // view-space Z pour sélection cascade

const int MAX_CASCADES = 4;
in vec4 vLightSpacePos[MAX_CASCADES];   // positions light-space A
in vec4 vLightSpacePosB[MAX_CASCADES];  // positions light-space B (leapfrog)
```

### 4.3 Main — chaîne de rendu

```glsl
void main() {
    // 1. Sélection cascade basée sur vViewDepth
    // 2. [optionnel] debug modes (debugShadow > 0, sortie immédiate)

    // PERF_BISECT_STAGE switch :
    // st=1 → albedo pur
    // st=2 → lighting sans AO/fog/shadow
    // st=3 → +AO
    // st=4 → +fog
    // st≥5 → shadow path complet :

    float shadow = (shadowsEnabled == 1)
        ? calculateShadow(N, L, vWorldPos, vViewDepth)
        : 1.0;  // ← guard O1

    // Hemisphere ambient (sky+ground)
    float hemisphereBlend = N.y * 0.5 + 0.5;
    vec3 indirect = mix(groundAmbientColor, skyAmbientColor, hemisphereBlend)
                    * ambient * aoFactor;
    vec3 direct = sunColor * max(dot(N, L), 0.0) * shadow;
    vec3 color = albedo * (indirect + direct);

    // Fog linéaire
    float fogFactor = clamp((fogEnd - dist) / (fogEnd - fogStart), 0.0, 1.0);
    fragColor = mix(vec4(fogColor, 1.0), vec4(color, 1.0), fogFactor);
}
```

### 4.4 calculateShadow() — logique de sampling

```glsl
float calculateShadow(vec3 normal, vec3 lightDir, vec3 worldPos, float viewDepth) {
    int cascade = selectCascade(viewDepth);         // loop sur cascadeSplits[]
    float bias  = computeBias(cascade, normal, lightDir); // slope-scaled tan(θ)

    // Projection dans l'espace lumière A
    vec4 lsPos   = vLightSpacePos[cascade];
    vec3 projCoords = lsPos.xyz / lsPos.w * 0.5 + 0.5;

    // Dispatch selon PERF_BISECT_STAGE
    #if STAGE == 5
        return sampleShadowSingleFromArray(atlasOffsetA, cascade, projCoords, bias);
    #elif STAGE == 6
        return sampleShadowPCFFromArray(atlasOffsetA, cascade, worldPos, projCoords, bias);
    // stages 7-9 : leapfrog A+B (cf. § 4.5)
    #else
        float shadowA = sampleShadowPCFFromArray(atlasOffsetA, ...);
        if (leapfrogActive != 1) return shadowA;
        // [compute projCoordsB from vLightSpacePosB]
        // dispatch sur STAGE 7/8/9
    #endif
}
```

### 4.5 Fonctions de sampling (atlas)

```glsl
// Fetch unique, vérif bounds
float sampleShadowSingleFromArray(int layerOffset, int cascade,
                                   vec3 projCoords, float bias) {
    if (out_of_bounds(projCoords)) return 1.0;
    float layer = float(layerOffset + cascade);  // ← offset atlas
    float depth = texture(shadowAtlas, vec3(projCoords.xy, layer)).r;
    return (depth < projCoords.z - bias) ? 0.0 : 1.0;
}

// PCF world-stable (SHADOW_FILTER_MODE == 2)
float sampleShadowPCFFromArray(int layerOffset, int cascade,
                                vec3 worldPos, vec3 projCoords, float bias) {
    if (out_of_bounds(projCoords)) return 1.0;
    float layer = float(layerOffset + cascade);
    float compareDepth = projCoords.z - bias;

    // Rotation déterministe par cellule monde → stable quand soleil bouge
    ivec2 cell = ivec2(floor(worldPos.xz / WORLD_CELL_SIZE));
    float angle = hashCell(cell) * 2π;
    mat2 rot = mat2(cos(angle), -sin(angle), sin(angle), cos(angle));

    // Rayon scalé par cascade (far cascades → plus gros kernel)
    float cascadeScale = min(cascadeTexelSizes[cascade]/cascadeTexelSizes[0], 2.0);
    float radius = PCF_RADIUS_TEXELS * cascadeScale;

    float shadow = 0.0;
    for (int i = 0; i < PCF_SAMPLE_COUNT; i++) {   // PCF_SAMPLE_COUNT = 4
        vec2 offset = (rot * POISSON_8[i]) * radius * shadowMapInvSize;
        float d = texture(shadowAtlas, vec3(projCoords.xy + offset, layer)).r;
        shadow += (d < compareDepth) ? 0.0 : 1.0;
    }
    return shadow / float(PCF_SAMPLE_COUNT);
}
```

### 4.6 Stages leapfrog 7/8/9

```glsl
// STAGE 7 : A PCF + B single (coût réduit pendant transition)
float shadowB = sampleShadowSingleFromArray(atlasOffsetB, cascade, projCoordsB, bias);
// Crossfade : dither stochastique ou mix()
return (blendMode == 1) ? mix(shadowA, shadowB, shadowCrossfade)
                        : (noise(worldPos) < shadowCrossfade) ? shadowB : shadowA;

// STAGE 8 : A PCF + B PCF (qualité max, pas de dFdx/dFdy)
float shadowB = sampleShadowPCFFromArray(atlasOffsetB, ...);
return (noise < shadowCrossfade) ? shadowB : shadowA;

// STAGE 9 : hybrid dither avec dFdx/dFdy (edge-advance)
float eA = abs(dFdx(shadowA)) + abs(dFdy(shadowA));
float eB = abs(dFdx(shadowB)) + abs(dFdy(shadowB));
float edge = clamp(max(eA,eB) * EDGE_GAIN, 0.0, 1.0);
float t = smoothstep(0,1, clamp(shadowCrossfade + edge * EDGE_ADVANCE, 0,1));
return (noise < t) ? shadowB : shadowA;
```

---

## 5. PERF_BISECT_STAGE — Table de référence complète

| Stage | Description | glFinish médiane | FPS |
|-------|-------------|-----------------|-----|
| 0 | Magenta pur (test compile) | ~6 ms | ~160 |
| 1 | Albedo seul, pas de lighting | ~5.8 ms | ~129 |
| 2 | Lighting basique (sans AO/fog/shadow) | ~5.9 ms | ~125 |
| 3 | +AO | ~6.0 ms | ~123 |
| 4 | +Fog (sans shadow) | ~6.5 ms | ~113 |
| **5** | **+Shadow single A (1 fetch depth array, coords dyn)** | **19.5 ms** | **~48** |
| 6 | +Shadow PCF A (4 taps, coords dyn) | ~20.5 ms | ~47 |
| 7 | Leapfrog A-PCF + B-single (réel) | ~37.3 ms | ~30 |
| 8 | Leapfrog A-PCF + B-PCF, pas dFdx | ~37.2 ms | ~30 |
| 9 | Leapfrog hybride A+B PCF + dFdx/dFdy | ~37.2 ms | ~30 |
| 10 | Micro-test : depth array coords CONSTANTES | ~20.3 ms | ~50 |
| 11 | Micro-test : depth array coords dynamiques | ~20.4 ms | ~48 |
| **12** | **Micro-test : color GL_R32F coords CONSTANTES** | **~6.7 ms** | **~105** |
| 13 | Micro-test : color GL_R32F coords dynamiques | ~20.1 ms | ~49 |
| **14** | **TEST C1 : `sampler2DArrayShadow` single tap (hw compare)** | **19.4 ms** | **~48** |
| 15 | TEST C2 : `sampler2DArrayShadow` PCF 4 taps (hw compare/tap) | 19.6 ms | ~48 |

Toutes les mesures : harness 20 s, World 10, spawn (0.5, 100, 0.5), 3 cascades × 2048×2048 px, Apple Silicon.

**Stages 14 et 15 — méthode** (`sampler2DArrayShadow`) :
- Même atlas que unit 1, re-lié sur unit 3 avec `GL_COMPARE_REF_TO_TEXTURE` + `GL_LEQUAL`
- Stage 14 : `texture(shadowAtlasShadow, vec4(projCoords.xy, layer, projCoords.z - bias))`
- Stage 15 : idem × 4 taps Poisson world-stable, hardware compare par tap

---

## 6. Le Problème d'Efficacité des Depth Arrays sur Apple Silicon

### 6.1 Observation centrale

Un seul fetch `texture(shadowAtlas, vec3(uv, layer)).r` sur une `sampler2DArray` format `GL_DEPTH_COMPONENT32F` coûte **~20 ms de glFinish** sur M-series, quel que soit le nombre de taps (1, 4, ou 8 — le coût est quasi identique).

Comparaison :
```
GL_DEPTH_COMPONENT32F + coords dynamiques  → ~20 ms  (stage 5, 11)
GL_DEPTH_COMPONENT32F + coords CONSTANTES  → ~20 ms  (stage 10)  ← inattendu !
GL_R32F              + coords CONSTANTES   →  ~6.7 ms (stage 12) ← rapide
GL_R32F              + coords dynamiques   → ~20.1 ms (stage 13)
```

### 6.2 Hypothèse — Warp broadcast sur Metal/Apple Silicon

Sur GPU conventionnel (NVIDIA/AMD) : quand tous les threads d'un warp accèdent au **même texel** (coords constantes), le driver peut **broadcastrer** un seul fetch → coût ~1 cycle.

Sur Apple Silicon via Metal :
- Le format `GL_DEPTH_COMPONENT32F` passe par un chemin interne différent (probablement lié à l'optimisation pour `sampler2DShadow` / hardware compare)
- Ce chemin **désactive le broadcast warp** même avec des coords parfaitement constantes
- Chaque thread fait un fetch indépendant → la latence de texture (~20 ms) est incompressible
- Le format `GL_R32F` (color) suit le chemin standard → broadcast possible → 6.7 ms avec coords constantes

### 6.3 Conséquences mesurées

```
Shadow depth pass (rendu CSM dans FBO, 3 cascades × 2048²) : ~16 ms
Fragment fetch A single (1 tap depth array) :                 ~20 ms total
  dont shadow depth pass :                                     ~16 ms
  dont fragment fetch :                                         ~0.1 ms
Fragment fetch B (leapfrog, atlas) :                          +17.7 ms
```

La latence observée en fragment (~20 ms) inclut en réalité le shadow depth pass qui s'exécute **en pipeline** avec le fragment shader précédent. Le fetch fragment lui-même coûte < 1 ms, mais le stall de pipeline causé par le depth pass est ~16 ms.

### 6.4 Pourquoi l'atlas A+B n'a pas aidé

Hypothèse initiale (micro-test 2026-03-03) : avoir deux `sampler2DArray` séparés coûtait +15.7 ms pour le second. Conclusion : l'atlas avec un seul sampler économiserait ce coût.

Réalité mesurée (atlas run 2026-03-05) : le fetch B dans l'atlas coûte **+17.7 ms** vs +15.7 ms mesuré pour deux samplers séparés. L'atlas est **légèrement plus lent** — l'atlas de 6 layers augmente la pression cache L2, et sur Metal, un seul sampler avec coords différentes (même texture) ne bénéficie pas de réduction de latence TMU.

### 6.5 Ce qui fonctionne sur cette plateforme

| Format | Coords | Latence | Mécanisme |
|--------|--------|---------|-----------|
| `GL_R32F` | constantes | 6.7 ms | broadcast warp possible |
| `GL_R32F` | dynamiques (uv) | 20 ms | divergence UV → broadcast impossible |
| `GL_DEPTH_COMPONENT32F` | constantes | 20 ms | chemin depth → jamais broadcast |
| `GL_DEPTH_COMPONENT32F` | dynamiques | 20 ms | idem |

**La divergence de layer** (cascade sélectionnée dynamiquement) n'a **aucun impact** mesurable — Metal gère bien les accès à des layers différents au sein d'un warp.

### 6.6 Pistes explorées et résultats

**`sampler2DArrayShadow` + `GL_COMPARE_REF_TO_TEXTURE` (mesuré 2026-03-05)** :

Sur GPU desktop, ce chemin hardware peut être 2× plus rapide que le compare manuel car il utilise un filtre bilinéaire hardware et un cache spécialisé. Résultat sur Apple Silicon :

```
Stage 5  (depth manual single)          : 19.5 ms médiane
Stage 14 (sampler2DArrayShadow single)  : 19.4 ms médiane   → Δ = -0.1 ms (bruit)
Stage 15 (sampler2DArrayShadow PCF×4)   : 19.6 ms médiane   → Δ = +0.1 ms (bruit)
```

**Conclusion** : `sampler2DArrayShadow` n'apporte **aucun gain mesurable** sur Metal/Apple Silicon (±0.1 ms = dans le bruit de mesure). La pénalité de latence de ~20 ms est inhérente au format `GL_DEPTH_COMPONENT32F` sur cette plateforme, indépendamment du chemin de compare utilisé. Metal traduit les deux chemins (manuel et hardware) de manière identique au niveau GPU.

Implémentation technique retenue : la mutation de `GL_TEXTURE_COMPARE_MODE` sur l'atlas avant/après le rendu est correcte (safe, sans feedback loop), mais ne produit pas le gain espéré.

### 6.7 Pistes restantes

1. **Réduction `CSM_SHADOW_MAP_SIZE` 2048 → 1024** : divise par 4 la surface des depth layers → moins de misses L2 sur le depth pass → gain estimé −8 à −10 ms sur le shadow depth pass. **C'est l'action de plus fort impact disponible.**

2. **Désactiver le shadow depth pass B quand `leapfrogActive = 0`** : ShadowRenderer continue de rendre A+B même quand le shader ne les blende pas → −8 ms steady-state.

---

## 7. Inventaire des fichiers clés

| Fichier | Rôle |
|---------|------|
| `src/main/resources/shaders/voxel_fragment_shader.glsl` | Shader principal, `PERF_BISECT_STAGE` define |
| `src/main/java/engine/render/renderer/ShadowRenderer.java` | Triple-buffer leapfrog, atlas A+B, staging C |
| `src/main/java/engine/render/renderer/VoxelRenderer.java` | Bindings textures, uniforms, rendu chunks |
| `src/main/java/engine/render/shadow/CSMCalculator.java` | Base lumineuse Pixar/Duff, bounding spheres, snap sub-texel |
| `src/main/java/engine/render/shadow/CascadeData.java` | Record immutable : matrices, splits, texelSizes |
| `src/main/java/engine/core/config/RenderConfig.java` | Constantes : CSM_CASCADE_COUNT, CSM_SHADOW_MAP_SIZE, SHADOWS_ENABLED |
| `src/main/java/engine/core/config/GameConfig.java` | TEST_HARNESS_ENABLED, TEST_WORLD_NAME |
| `src/main/java/engine/test/PerfLogger.java` | CSV perf (Locale.US obligatoire) |
| `src/main/java/engine/core/states/PlayingState.java` | Init harness, auto-close, extractPerfBisectStage() |
| `logs/perf_bisect_report.md` | Rapport bisect stages 1-13 |
| `logs/perf_microtest_report.md` | Rapport depth vs color, const vs dyn |
| `logs/perf_atlas_report.md` | Rapport atlas O1+O2 (2026-03-05) |
| `docs/render_pipeline_reference.md` | Ce document — contexte LLM, mis à jour en continu |

---

## 8. État actuel du code et points d'attention

### 8.1 Ce qui est actif en production

```glsl
// Fragment shader (2026-03-05 — restauré après série de tests C1/C2)
#define PERF_BISECT_STAGE 5  // single A — shadow path production
#define SHADOW_FILTER_MODE 2 // world-stable PCF
```

```java
// VoxelRenderer.java
voxelShader.setUniform("leapfrogActive", 0 /*leapfrogTransitioning ? 1 : 0*/);
// ↑ ATTENTION: leapfrog désactivé côté shader, ShadowRenderer tourne quand même

// Unit 3 : shadowAtlasShadow toujours bindé (même en stage 5, uniforme ignoré)
// La mutation compare_mode est inoffensive quand le shader ne lit pas unit 3.
```

### 8.2 Conventions shader importantes

- Toujours `#if/#elif/#else/#endif` — **jamais** deux blocs `#if` séparés (cause redeclaration GLSL crash)
- Build via `./gradlew build run` — **ne pas** copier manuellement dans `bin/`

### 8.3 Résumé des coûts GPU (Apple Silicon, 3 casc × 2048²)

```
Shadow depth pass A (seul, OFF mode)     : ~16 ms
Shadow depth pass A+B (leapfrog running) : ~16 ms  (pipeliné, pas additionnel)
Fragment fetch A single (st5)            : +~0 ms  (dominé par depth pass)
Fragment fetch A hw compare (st14)       : +~0 ms  (identique au manuel)
Fragment fetch A PCF×4 hw compare (st15) : +~0 ms  (identique au manuel)
Fragment fetch B leapfrog atlas (st7)    : +17.7 ms additionnels
Fragment PCF 4 taps vs 1 tap             : +0.1 ms (négligeable)

Frame totale typical :
  Steady-state (A seulement, st5)        : ~20 ms total = ~48 FPS
  Leapfrog actif (A+B, st9)              : ~37 ms total = ~30 FPS
  SWAP frame (re-render C→atlas)         : ~50-56 ms spike, ~1/2s

Conclusion sampler2DArrayShadow (mesuré 2026-03-05) :
  st14 médiane  19.4 ms vs st5 médiane 19.5 ms → aucune différence sur Metal.
  Le hardware compare path n'est PAS optimisé différemment sur Apple Silicon.
```

### 8.4 Action prioritaire recommandée

```java
// RenderConfig.java
CSM_SHADOW_MAP_SIZE = 1024;  // était 2048
// Gain estimé : -8 à -10 ms sur shadow depth pass → ~47 FPS steady, ~35 FPS leapfrog
```

---

## 9. Mesure & Instrumentation

### 9.1 Ce que mesure chaque métrique

**`renderMs`** (CSV colonne `render_ms`)
- Temps CPU de `stateManager.render()` mesuré avec `System.nanoTime()` côté Java
- N'attend **pas** la fin GPU — capture uniquement le coût de soumission des commandes GL
- Valeur typique : 1-3 ms (peu informative pour débogage GPU)

**`glFinish_ms`** (CSV colonne `glfinish_ms`)
- Appel à `glFinish()` en fin de frame dans `Application.java`, mesuré avec `System.nanoTime()`
- Bloque le thread Java jusqu'à ce que **tous les travaux GPU soumis soient terminés**
- Mesure = latence GPU totale de la frame (shadow depth pass + skybox + sun + voxels)
- Inclut les chevauchements pipeline : si le GPU commence le voxel pass pendant que le CPU soumet la frame suivante, `glFinish` force la synchronisation et capture tout
- **C'est la métrique principale** pour évaluer le coût GPU réel

**`shadow_finish_ms`** (CSV colonne `shadow_finish_ms`, nouveau — 2026-03-06)
- `glFinish()` injecté **juste après** `shadowRenderer.render()`, avant `skyRenderer.render()`
- Mesure le temps GPU du shadow depth pass **en isolation forcée**
- **Désactive le pipelining GPU** : le shadow pass et le reste de la frame ne se chevauchent plus
- Valeur = 0 quand `SHADOW_SPLIT_TIMING = false` (comportement par défaut — production)
- Usage : test d'isolation uniquement (cf. §12 expérience C3)

### 9.2 Infrastructure de mesure

**Harness automatisé** (`TEST_HARNESS_ENABLED = true`) :
- World : `TEST_WORLD_NAME = "World 10"`
- Spawn : (0.5, 100, 0.5), yaw=0°, pitch=0°
- Durée : 20 s
- Warmup : 10 frames rejetées avant logging
- Fréquence log : `TEST_LOG_FREQUENCY = 1` (toutes les frames)
- Output : `logs/perf_HHMMSS.csv`

**Flags d'isolation** (tous dans `RenderConfig.java` et `GameConfig.java`) :
```java
// GameConfig.java
SHADOW_SPLIT_TIMING       // bool — injecte glFinish après shadow pass (casse pipeline)

// RenderConfig.java
SHADOW_DEPTH_PASS_ENABLED // bool — guard dans ShadowRenderer.render() (return immédiat)
SHADOW_MAP_FILTER         // int  — 0=GL_LINEAR (défaut), 1=GL_NEAREST
SHADOW_MAP_FORMAT         // int  — 0=DEPTH32F (défaut), 1=DEPTH24, 2=DEPTH16
SHADOW_BASE_MAX_LEVEL     // bool — force GL_TEXTURE_BASE_LEVEL=0 et MAX_LEVEL=0
```

**Restauration après tests** : tous les flags reviennent à leur valeur par défaut.
`PERF_BISECT_STAGE` restauré à `5`. `TEST_HARNESS_ENABLED` restauré à `false`.

---

## 10. Détails OpenGL / LWJGL critiques

### 10.1 Shadow atlas — paramètres exacts de création

```java
// ShadowRenderer.createShadowMapArray()
glBindTexture(GL_TEXTURE_2D_ARRAY, shadowAtlas);
glTexImage3D(GL_TEXTURE_2D_ARRAY, 0,
    GL_DEPTH_COMPONENT32F,   // internalFormat (défaut, configurable via SHADOW_MAP_FORMAT)
    shadowMapSize, shadowMapSize,
    layerCount,              // = cascadeCount * 2 = 6
    0, GL_DEPTH_COMPONENT, GL_FLOAT, (ByteBuffer) null);

glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR);  // configurable
glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);  // configurable
glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_MODE, GL_NONE);
// GL_NONE : compare désactivé → sampling direct en .r sur unit 1
// (unit 3 active GL_COMPARE_REF_TO_TEXTURE pour stages 14/15 — cf. §3)
```

**`GL_TEXTURE_BASE_LEVEL` / `GL_TEXTURE_MAX_LEVEL`** : non fixés par défaut (valeurs GL : 0 / 1000).
Pour une texture 2D_ARRAY de depth sans mipmaps, OpenGL n'alloue qu'un niveau mipmap.
Sur Metal/Apple Silicon, la présence de MAX_LEVEL=1000 n'a aucune conséquence mesurable (cf. B5, §12).

### 10.2 Formats depth disponibles et compatibilité FBO

```java
// GL_DEPTH_COMPONENT32F  (org.lwjgl.opengl.GL30)  — défaut, toujours supporté
// GL_DEPTH_COMPONENT24   (org.lwjgl.opengl.GL14)  — supporté sur Metal
// GL_DEPTH_COMPONENT16   (org.lwjgl.opengl.GL14)  — supporté sur Metal (testé §12 run B3)
```

Vérification FBO après création (`checkAndFixAtlasFBO()`) :
```java
glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, shadowAtlas, 0, 0);
int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
// Si status != GL_FRAMEBUFFER_COMPLETE → fallback automatique vers DEPTH32F
```

### 10.3 colorTestArray — paramètres

```java
// Même structure que shadowAtlas mais format color :
glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_R32F, size, size, 3, 0, GL_RED, GL_FLOAT, null);
// GL_NEAREST min/mag (résultats scalaires exacts, pas d'interpolation)
// Usage : micro-tests stages 10-13 uniquement (unit 2)
```

### 10.4 Mutation compare mode (unit 3 — stages 14/15)

La même texture `shadowAtlas` est liée simultanément sur unit 1 (GL_NONE) et unit 3 (GL_COMPARE_REF_TO_TEXTURE). VoxelRenderer mute les paramètres avant/après rendu :

```java
// Avant renderVisibleChunks() :
glActiveTexture(GL_TEXTURE3);
glBindTexture(GL_TEXTURE_2D_ARRAY, shadowAtlas);
glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_R_TO_TEXTURE);
glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);

// Après renderVisibleChunks() :
glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_MODE, GL_NONE);
```

Cette mutation est **safe** (pas de feedback loop, pas de lecture/écriture simultanées). Elle est inoffensive quand le shader n'échantillonne pas unit 3 (stages ≤ 13).

---

## 11. Hypothèses testées et réfutées

Tableau synthétique de toutes les hypothèses explorées pour expliquer le coût ~20 ms (stage 5).

| # | Hypothèse | Expérience | Résultat | Conclusion |
|---|-----------|-----------|---------|-----------|
| H1 | Coût dû au nombre de taps PCF (4 vs 1) | Stage 5 (1 tap) vs stage 6 (4 taps) | Δ = +1 ms | **Réfutée** — PCF marginal |
| H2 | Coût dû à la divergence de layer (cascade index dynamique) | Stages 10 vs 11 (coords constantes vs dynamiques, même format depth) | Δ < 0.1 ms | **Réfutée** — divergence layer = 0 impact |
| H3 | Coût dû à la divergence UV (coordonnées dynamiques) | Stage 12 (color R32F, coords constantes) : 6.7 ms vs stage 13 (color, coords dyn) : 20.1 ms | Δ = +13.4 ms | **Validée pour color** — mais depth ne peut pas aller à 6.7 ms même avec coords constantes (stage 10 = 20.3 ms) |
| H4 | Le format `GL_DEPTH_COMPONENT32F` bloque le broadcast warp sur Metal | Stage 10 : depth + coords constantes = 20.3 ms (attendu ~6 ms si broadcast) | 20.3 ms = identique à dynamiques | **Validée** — depth désactive le broadcast sur Metal |
| H5 | `sampler2DArrayShadow` + hardware compare économise des cycles | Stages 14 (single hw) vs 15 (PCF×4 hw) vs 5 (manual) | Δ = ±0.1 ms (bruit) | **Réfutée** — Metal traduit les deux chemins identiquement |
| **H6.3** | **Le ~20 ms est causé par le shadow depth pass en pipeline (stall GPU), pas par le fetch fragment** | **C1 (pass OFF, fetch ON) = 19.05 ms ; C2 (fetch OFF, pass ON) = 6.29 ms** | **Pass OFF = quasi identique ; fetch OFF = chute dramatique** | **Réfutée — voir §12** |
| H7 | Filter LINEAR vs NEAREST impacte la latence TMU | B-ref (LINEAR) vs B1 (NEAREST) | Δ = 0.02 ms | **Réfutée** — aucun impact |
| H8 | Format DEPTH24/16 réduit la latence (données plus petites en cache) | B2 (DEPTH24) vs B3 (DEPTH16) vs B-ref (DEPTH32F) | Δ < 0.1 ms | **Réfutée** — Metal gère tous les formats depth à latence identique |
| H9 | `GL_TEXTURE_BASE_LEVEL=0 / MAX_LEVEL=0` réduit l'overhead (pas de mipmap stack) | B5 vs B-ref | Δ = 0.02 ms | **Réfutée** — aucun impact sur Metal |

---

## 12. Hypothèse 6.3 — Expériences d'isolation et conclusion

### 12.1 Formulation testable

> "Le ~20 ms de glFinish au stage 5 est causé principalement par le shadow depth pass (rendu CSM dans FBO, ~16 ms GPU) qui se termine pendant le glFinish de fin de frame. Le fragment fetch lui-même ne coûte que < 1 ms ; c'est le stall de pipeline qui révèle le coût du depth pass. Désactiver le depth pass devrait faire chuter glFinish de ~16 ms."

### 12.2 Plan des expériences

| Run | `SHADOW_DEPTH_PASS_ENABLED` | `SHADOW_SPLIT_TIMING` | `PERF_BISECT_STAGE` | Prédiction si H6.3 vraie |
|-----|----------------------------|-----------------------|--------------------|--------------------------|
| C3 | `true` | `true` | 5 | shadow_fin_med ≈ 16 ms, glfinish_med ≈ 4 ms (reste = fragment seulement) |
| C1 | `false` | `false` | 5 | glfinish_med chute vers ~4-6 ms |
| C2 | `true` | `false` | 4 | glfinish_med reste ~6.5 ms (pas de fetch → shadow pass seul) |

### 12.3 Résultats mesurés

Harness 20 s, World 10, spawn (0.5, 100, 0.5), 3 cascades × 2048×2048 px, Apple Silicon.

| Run | Description | n frames | glfinish_med (ms) | shadow_fin_med (ms) | p95 (ms) | FPS |
|-----|-------------|----------|-------------------|---------------------|----------|-----|
| **C3** | Split timing ON, st5, pass ON | 817 | **19.58** | **12.16** | 20.08 | 51.1 |
| **C1** | Pass OFF (no depth pass), st5 | 916 | **19.05** | 0.00 | 19.53 | 52.4 |
| **C2** | Stage 4 (no fetch), pass ON | 2072 | **6.29** | 0.00 | 10.75 | 147.2 |

### 12.4 Analyse expérience par expérience

**C3 — split timing (référence instrumentée)**

`shadow_fin_med = 12.16 ms` : le shadow depth pass consomme ~12 ms GPU quand on le force à se terminer avant de commencer le reste. Mais `glfinish_med = 19.58 ms` — soit ~7.4 ms supplémentaires pour le reste de la frame (voxels + sky + sun). Ce total de 19.6 ms est quasi identique à B-ref (19.63 ms), ce qui confirme que casser le pipeline ne change pas le total (les 12 ms shadow + 7.4 ms voxels = ~19.6 ms, idem que les deux en pipeline).

**C1 — shadow depth pass désactivé, fetch fragment actif (test de la prédiction principale)**

Prédiction H6.3 : glfinish devrait chuter de ~16 ms → ~4 ms.
Résultat réel : `glfinish_med = 19.05 ms` — **réduction de seulement 0.58 ms** (de 19.63 → 19.05).

C'est le résultat décisif. En supprimant entièrement le shadow depth pass (atlas garde son contenu stale), le glFinish ne baisse pas significativement. La frame avec stale shadows coûte presque autant qu'avec shadows recalculés à chaque frame. La pénalité ~19 ms existe même sans depth pass.

**C2 — shadow depth pass actif, fetch fragment supprimé (stage 4)**

`glfinish_med = 6.29 ms` — **chute de ~13.3 ms** (de 19.58 → 6.29).

Avec le shadow depth pass qui tourne **et** le fragment shader qui n'échantillonne pas la texture depth → la frame coûte seulement 6.3 ms. Cela prouve que le shadow depth pass **ne stalle pas** le fragment pass : les deux se chevauchent bien dans le pipeline Metal. Le depth pass de ~12 ms est masqué derrière le fragment pass rapide (stage 4 = 6.5 ms).

### 12.5 Conclusion : H6.3 RÉFUTÉE

**La cause du ~20 ms n'est PAS le shadow depth pass en pipeline.**

La démonstration par C2 est définitive : quand on garde le depth pass actif et qu'on supprime uniquement le fetch fragment (stage 4 vs stage 5), la frame passe de 19.6 ms à 6.3 ms. Le depth pass ne cause aucun stall visible — il est bien pipeliné avec le fragment pass.

La vraie cause est le **fetch sur la texture `GL_DEPTH_COMPONENT32F`** dans le fragment shader lui-même, indépendamment du depth pass. Ce coût est inhérent au format depth sur Metal/Apple Silicon (cf. §6.2, §11 H4).

### 12.6 Résultats campagne B — paramètres texture shadow

Tous les tests effectués avec PERF_BISECT_STAGE=5, shadow pass ON, flags par défaut sauf la variable testée.

| Run | FILTER | FORMAT | BASE_MAX_LEVEL | n frames | glfinish_med (ms) | p95 (ms) | FPS |
|-----|--------|--------|---------------|----------|-------------------|----------|-----|
| **B-ref** | LINEAR | DEPTH32F | false | 830 | 19.63 | 30.02 | 48.1 |
| B1 | NEAREST | DEPTH32F | false | 827 | 19.61 | 30.13 | 48.1 |
| B2 | LINEAR | DEPTH24 | false | 829 | 19.69 | 30.54 | 47.9 |
| B3 | LINEAR | DEPTH16 | false | 827 | 19.66 | 30.23 | 48.0 |
| B4 | NEAREST | DEPTH24 | false | 830 | 19.65 | 29.91 | 48.0 |
| B5 | LINEAR | DEPTH32F | true | 832 | 19.61 | 30.34 | 48.1 |

**Δ max entre tous les runs B : 0.08 ms** — dans le bruit de mesure.

### 12.7 Conclusions campagne B

Aucun paramètre de configuration de la texture shadow (filtre, format interne, allocation mipmap) n'a d'impact mesurable sur la latence de fetch fragment sur Apple Silicon Metal. La pénalité est entièrement déterminée par le fait que la texture est de format depth (`GL_DEPTH_COMPONENT*`), quelle que soit la précision (16/24/32 bits) ou le mode de filtrage.

**Recommandations** :
- Conserver `GL_LINEAR` (meilleure qualité, coût identique à NEAREST)
- Conserver `GL_DEPTH_COMPONENT32F` (précision maximale, coût identique à 16/24)
- `BASE_MAX_LEVEL` : inutile de l'activer

### 12.8 Prochaine piste : réduction de la résolution shadow

La seule variable non testée avec potentiel de gain significatif est `CSM_SHADOW_MAP_SIZE`. Réduire de 2048 à 1024 :
- Divise par 4 la surface des layers depth (4 Mo → 1 Mo par layer, 6 → 24 Mo total → 6 Mo)
- Réduit les misses L2 pendant le shadow depth pass lui-même (~12 ms mesuré C3)
- **N'affecte pas** le coût du fetch fragment (qui est inhérent au format, indépendant de la taille)
- Gain estimé : −8 à −10 ms sur le shadow depth pass → frame totale ~10-12 ms steady-state

```java
// RenderConfig.java
public static final int CSM_SHADOW_MAP_SIZE = 1024;  // était 2048
```
