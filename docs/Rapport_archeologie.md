# Rapport d’archéologie du renderer OpenGL

Analyse effectuée sur le commit `0706298`, sur le MacBook Pro `Mac14,9` actuel : Apple M2 Pro, GPU 16 cœurs, 16 Go, macOS 26.6.2.

Aucun fichier n’a été modifié ; le worktree reste propre.

## 1. Executive summary

1. Le renderer actif cible explicitement OpenGL 3.2 Core / GLSL 1.50. Les fonctionnalités utilisées — VAO, FBO, texture arrays, `glFramebufferTextureLayer`, `sampler2DArray` et `sampler2DArrayShadow` — sont compatibles avec ce contrat. Il n’existe pas de dépendance active démontrée à OpenGL 4.x.

2. Le problème principal n’est donc pas une absence de support OpenGL 3.2 sur le M2. Il s’agit d’une combinaison d’état texture/sampler dont le résultat est indéfini par la spécification.

3. Le même objet `shadowAtlas` est lié :

   - sur l’unité 1 comme `sampler2DArray`, pour lire manuellement la profondeur ;
   - sur l’unité 3 comme `sampler2DArrayShadow`.

   Ensuite, `GL_TEXTURE_COMPARE_MODE` est activé sur cet objet. Or ce paramètre appartient à l’objet texture, pas à l’unité de texture. La lecture non-shadow de l’unité 1 se fait donc elle aussi avec le mode de comparaison activé.

4. Avec `PERF_BISECT_STAGE == 5`, le fragment shader actif exécute précisément une lecture non-shadow de cet objet. La spécification déclare ce cas indéfini. C’est le meilleur candidat pour expliquer une différence de comportement entre drivers et l’apparente « incompatibilité » M2.

5. Aucun `GL_INVALID_OPERATION` n’est nécessairement produit : une valeur de texture indéfinie n’implique pas automatiquement une erreur récupérable par `glGetError`.

6. Le système décrit comme « leapfrogging » ne contribue actuellement pas à l’image finale : `VoxelRenderer` force `leapfrogActive = 0`, tandis que `ShadowRenderer` continue à calculer A, B et C.

7. Le shader livré n’exécute pas non plus son chemin PCF avancé : `PERF_BISECT_STAGE` vaut 5 et retourne après un seul échantillonnage de A. Les ressources B, le crossfade et la majorité du shader sont inactifs visuellement.

8. La texture de staging C est mise à jour mais n’est ni copiée dans l’atlas ni échantillonnée. Lors du swap, le commentaire annonce une copie de C, mais le code rerend le monde entier dans la moitié B de l’atlas.

9. Le `colorTestArray` R32F est alloué sans données, jamais écrit, puis échantillonné par les stages de micro-test 12/13. Ces résultats visuels sont indéterminés.

10. Les trois arrays 2048² à 32 bits représentent environ 192 Mio : atlas A+B 96 Mio, staging C 48 Mio, diagnostic R32F 48 Mio. Environ 96 Mio concernent actuellement des ressources sans effet utile sur l’image finale.

11. Le passage shadow effectue trois parcours complets du monde par shadow map. Le warm-up A+B+C en effectue neuf. Il n’existe ni frustum culling de la caméra pour la passe principale, ni culling par cascade pour les ombres.

12. Les métriques `glFinish_ms` sont des temps d’attente CPU cumulatifs, pas des mesures GPU isolées. L’appel `glFinish()` obligatoire à chaque frame sérialise CPU et GPU et perturbe fortement la performance mesurée.

13. Plusieurs conclusions des rapports perf — coût intrinsèque de `DEPTH_COMPONENT32F`, comportement de cache Metal, « warp broadcast » — ne sont pas prouvées par les données, car les tests de lecture manuelle ont été exécutés dans un cas de comportement indéfini.

14. L’ownership GPU est contradictoire : les shaders voxel et depth sont détruits plusieurs fois lors de `PlayingState::exit`, l’atlas voxel n’est jamais détruit, et les anciens VAO/VBO d’un chunk sont perdus lors d’un remeshing.

15. Le renderer est principalement une suite de composants manipulant directement le global state OpenGL. Les wrappers `render/core/Framebuffer`, `Texture` et `Window` existent, mais ne structurent pas le chemin de rendu actif.

16. Tester sur un Mac Intel peut être utile comme comparaison secondaire de driver/GPU. Ce n’est ni un correctif ni une plateforme de référence plus valide : le comportement indéfini demeure, et OpenGL est également déprécié sur les Mac Intel.

17. La première investigation doit rendre le test conforme à OpenGL 3.2, vérifier le contexte réellement obtenu et mesurer avec des GPU timer queries. Un port Metal ou un grand refactor n’est pas requis pour établir la cause.

**Conclusion principale — Haute confiance :** le problème observé sur M2 est d’abord un problème de validité et d’instrumentation du chemin shader, pas une incompatibilité fondamentale d’OpenGL 3.2 avec Apple Silicon.

---

## 2. Repository rendering map

### Chemin applicatif actif

```text
engine.Main
└── Application
    ├── GLFW : fenêtre et contexte OpenGL
    ├── LWJGL GL.createCapabilities()
    ├── StateManager
    │   ├── MenuState
    │   │   └── UIManager
    │   │       ├── UIRenderer
    │   │       └── TextRenderer ×2
    │   ├── LoadingState
    │   │   ├── UIManager
    │   │   └── World
    │   └── PlayingState
    │       ├── Game
    │       ├── World
    │       │   ├── Chunk
    │       │   ├── WorldGenerator / WorldStorage
    │       │   └── MeshWorkerManager
    │       │       ├── MeshBuilder
    │       │       ├── MeshData
    │       │       └── MeshUploadQueue
    │       ├── ShadowRenderer
    │       │   ├── CSMCalculator
    │       │   ├── CascadeData
    │       │   ├── FBO brut
    │       │   ├── shadowAtlas A+B
    │       │   └── shadowMapArrayC
    │       ├── SkyRenderer
    │       ├── SunRenderer
    │       ├── VoxelRenderer
    │       └── TextRenderer
    └── swap buffers
```

### Responsabilités observées

| Zone | Responsabilité réelle |
|---|---|
| [`Application.java:95`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/core/Application.java:95) — `Application::init` | Fenêtre GLFW, contexte GL, capabilities LWJGL, framebuffer size, état initial |
| [`Application.java:209`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/core/Application.java:209) — boucle principale | Poll, input, update du state, render, `glFinish`, swap |
| [`StateManager.java:38`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/core/StateManager.java:38) | Transitions différées et délégation update/render |
| [`PlayingState.java:77`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/core/states/PlayingState.java:77) | Composition du renderer de jeu et ownership ambigu de ses ressources |
| [`Game.java:128`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/Game.java:128) | Simulation joueur/temps/monde |
| [`Game.java:249`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/Game.java:249) | Orchestration réelle des passes |
| [`World.java:272`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/world/World.java:272) | Chargement/déchargement des chunks et lancement du meshing |
| [`MeshBuilder.java:38`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/MeshBuilder.java:38) | Génération CPU des faces visibles ; pas de greedy meshing |
| [`MeshWorkerManager.java:35`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/MeshWorkerManager.java:35) | Meshing asynchrone puis upload VAO/VBO sur le thread GL |
| [`ShadowRenderer.java:339`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/renderer/ShadowRenderer.java:339) | Machine d’état CSM/leapfrog et génération des depth arrays |
| [`VoxelRenderer.java:95`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/renderer/VoxelRenderer.java:95) | Passe forward opaque des voxels |
| [`SkyRenderer.java:37`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/renderer/SkyRenderer.java:37) | Clear couleur/profondeur ; aucun ciel géométrique |
| [`SunRenderer.java:57`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/renderer/SunRenderer.java:57) | Quad billboard du soleil |
| [`TextRenderer.java:109`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/TextRenderer.java:109) | Rasterisation texte via atlas STB et VBO dynamique |
| [`UIRenderer.java:81`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/UIRenderer.java:81) | Rectangles UI couleur/texturés |
| [`Shader.java:19`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/Shader.java:19) | Compilation/linking et uploads d’uniforms |

### Ce qui n’existe pas dans le chemin actif

Aucun EBO, UBO, SSBO, renderbuffer, stencil, scissor effectif, G-buffer, deferred lighting, SSAO, bloom, post-processing, eau, volumétrie, transparence voxel, skybox ou accumulation temporelle.

Les classes [`Framebuffer.java`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/core/Framebuffer.java), [`Texture.java`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/core/Texture.java) et [`Window.java`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/core/Window.java) ne sont pas utilisées par la pipeline de jeu active.

---

## 3. Frame execution trace

### Frame applicative

1. `Application` traite les événements GLFW et met à jour l’input.

2. `StateManager::update` applique une éventuelle transition, puis délègue au state actif.

3. En jeu, `PlayingState::update` appelle `Game::update`.

4. `Game::update` :

   - avance `TimeManager`;
   - met à jour le joueur ;
   - appelle `World::update`.

5. `World::update` :

   - crée les chunks dans un carré autour du joueur ;
   - nettoie les chunks suffisamment éloignés ;
   - soumet les chunks en attente au pool de meshing.

6. Les workers exécutent `MeshBuilder::generateMeshData`. Ils ne font aucun appel OpenGL.

7. `StateManager::render` → `PlayingState::render` → `Game::render`.

8. `Game::render` remplit `RenderContext` avec caméra, monde, dimensions, ciel et directions du soleil.

9. `ShadowRenderer::render` calcule ou réutilise les cascades, attache successivement les layers au FBO et appelle `World::renderDepth`.

10. `World::renderDepth`, pour chaque cascade :

    - parcourt tous les chunks chargés ;
    - envoie `modelMatrix` ;
    - lie le VAO du chunk ;
    - exécute `glDrawArrays(GL_TRIANGLES)`.

11. `SkyRenderer::render` rebinde le framebuffer 0, restaure plusieurs states et efface couleur et profondeur.

12. `SunRenderer::render` envoie les matrices du billboard, désactive depth/culling et dessine un `GL_TRIANGLE_FAN`.

13. `VoxelRenderer::render` :

    - établit l’état opaque ;
    - envoie caméra, lumière, fog, AO, CSM et état leapfrog ;
    - lie l’atlas voxels et les textures d’ombre ;
    - traite toutes les demandes d’upload de meshes ;
    - parcourt un carré de chunks ;
    - envoie une matrice modèle par chunk ;
    - dessine chaque VAO.

14. `Game::renderUI` génère une orthographique JOML et dessine les textes.

15. `Application` appelle systématiquement `glFinish`, puis `glfwSwapBuffers`.

Preuves principales : [`Game.java:249`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/Game.java:249), [`World.java:272`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/world/World.java:272), [`World.java:340`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/world/World.java:340), [`VoxelRenderer.java:224`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/renderer/VoxelRenderer.java:224).

### Chemin CPU → mesh → GPU

```text
World::update
→ MeshWorkerManager::queueChunk
→ worker: MeshBuilder::generateMeshData
→ MeshUploadQueue
→ VoxelRenderer::render
→ MeshWorkerManager::uploadMeshesIfAny
→ glGenVertexArrays / glGenBuffers
→ glBufferData
→ glVertexAttribPointer
→ Chunk::setMesh
→ MeshBuilder::renderChunk
→ glDrawArrays
```

Aucun mécanisme ne confirme qu’un résultat asynchrone correspond encore à la version actuelle ou à un chunk toujours chargé.

---

## 4. Render passes

| Ordre | Passe | FBO / sortie | État principal |
|---:|---|---|---|
| 1 | Shadow CSM/leapfrog | FBO custom, un layer depth array à la fois | Depth test/write ON, `LESS`, color mask OFF, back-face culling, blend/scissor OFF, viewport 2048² |
| 2 | Sky clear | FBO 0, back buffer couleur + profondeur | Color/depth mask ON, depth `LESS`, blend/scissor OFF, viewport framebuffer |
| 3 | Soleil | FBO 0 | Depth OFF, cull OFF, blend alpha ON |
| 4 | Voxels opaques | FBO 0 | Depth ON/write ON/`LESS`, back culling CCW, blend/scissor OFF |
| 5 | Texte HUD | FBO 0 | Depth/cull OFF, blend alpha ON |
| 6 | Swap | Front/back buffers GLFW | Après `glFinish()` |

### Shadow pass

**Factuel.** [`ShadowRenderer::renderShadowMapIntoAtlas`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/renderer/ShadowRenderer.java:606) attache chaque layer avec `glFramebufferTextureLayer`, clear la profondeur et rend tout le monde.

- Atlas A+B : six layers.
- C staging : trois layers.
- Pas de color attachment.
- `glDrawBuffer(GL_NONE)` et `glReadBuffer(GL_NONE)`.
- Aucun polygon offset.
- Le biais est appliqué lors de l’échantillonnage fragment.
- Aucun culling spécifique au frustum lumière/cascade.

### Machine leapfrog

**Factuel.**

- Warm-up : rendu complet de A, B et C, soit neuf traversées du monde.
- A/B peuvent être rerendus à la suite d’un changement de snap caméra.
- Le crossfade CPU avance continuellement.
- Au swap, les offsets et `CascadeData` sont pivotés.
- La prétendue copie C→B est en réalité un nouveau rendu complet dans l’atlas : [`ShadowRenderer.java:503-549`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/renderer/ShadowRenderer.java:503).
- C est ensuite progressivement rerendu, un layer par frame silencieuse.
- Aucun shader actif ne lit C.
- Le shader principal reçoit toujours `leapfrogActive = 0`.

**Intention probable.** Maintenir des shadow maps à plusieurs instants solaires et fondre A vers B pour éviter les sauts de direction et de snapping.

**Conclusion — Haute confiance.** L’implémentation actuelle est fonctionnellement un atlas A/B rerendu, accompagné d’une texture C redondante. Ce n’est pas le triple-buffer réellement consommé décrit par certains documents.

### Sky, soleil et monde

Le « sky pass » n’est qu’un clear. Le soleil est dessiné avant le monde sans depth test. Les voxels peuvent ensuite recouvrir le soleil selon leur profondeur normale.

La passe voxel est forward : albedo, Lambert solaire, ambient hémisphérique, AO vertex, ombre puis fog dans un seul fragment shader.

### UI/menu/loading

Le menu désactive explicitement le depth test avant `UIManager::render`. `LoadingState` efface profondeur et couleur, mais ne désactive pas le depth test avant la même UI. `UIRenderer` ne gère pas lui-même ce state. C’est une dépendance implicite et incohérente : [`MenuState.java:45`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/core/states/MenuState.java:45), [`LoadingState.java:200`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/core/states/LoadingState.java:200).

---

## 5. Shader inventory

| Programme | GLSL | Création / propriétaire | Usage réel |
|---|---|---|---|
| Voxel | `voxel_vertex_shader.glsl`, `voxel_fragment_shader.glsl` | Créé par `PlayingState`, injecté dans `VoxelRenderer` | Passe world opaque |
| Depth | `depth_vertex_shader.glsl`, `depth_fragment_shader.glsl` | Créé par `PlayingState`, injecté dans `ShadowRenderer` | Toutes les cascades shadow |
| Sun | `sun_vertex.glsl`, `sun_fragment.glsl` | Créé et détruit par `SunRenderer` | Quad soleil |
| Text | `text_vertex.glsl`, `text_fragment.glsl` | Un par `TextRenderer` | HUD et menus |
| UI | `ui_vertex.glsl`, `ui_fragment.glsl` | Créé par `UIRenderer` | Rectangles/menu |
| Cube | `cube_vertex.glsl`, `cube_fragment.glsl` | `CubeTest` uniquement | Harness/main séparé, pas le jeu |

Tous déclarent `#version 150` ou `#version 150 core`, cohérent avec OpenGL 3.2 / GLSL 1.50.

Le chargement commun est réalisé depuis le classpath par [`Shader.java:19-63`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/Shader.java:19).

---

## 6. Shader pipeline traces

### 6.1 Voxel

#### CPU → GPU

```text
PerspectiveCamera
→ projectionMatrix / viewMatrix / cameraPos
→ Game::render remplit RenderContext
→ VoxelRenderer::render
→ Shader::setUniform
→ voxel vertex/fragment shaders
```

```text
TimeManager::SunData
→ sunDir / sunColor / ambient
→ VoxelRenderer
→ uniforms de lighting
→ voxel_fragment_shader
```

```text
CSMCalculator
→ CascadeData A/B
→ ShadowRenderer
→ VoxelRenderer
→ lightSpaceMatrices, splits, texelSizes, offsets
→ voxel vertex shader
→ vLightSpacePos A/B
→ voxel fragment shader
```

#### Layout vertex

| Location CPU | Donnée | Type | Offset | GLSL |
|---:|---|---|---:|---|
| 0 | Position | 3 × float | 0 | `aPos vec3` |
| 1 | UV | 2 × float | 12 | `aTex vec2` |
| 2 | Normale | 3 × float | 20 | `aNormal vec3` |
| 3 | AO | 1 × float | 32 | `aAO float` |

Stride réel : 9 floats, 36 octets. Le layout est cohérent entre [`MeshBuilder.java:31-37`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/MeshBuilder.java:31), [`MeshWorkerManager.java:68-82`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/MeshWorkerManager.java:68) et [`voxel_vertex_shader.glsl:3`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/resources/shaders/voxel_vertex_shader.glsl:3).

Le stride 40 présent dans `RenderConfig` est une strate legacy non utilisée dans ce chemin.

#### Espaces

```text
aPos
→ modelMatrix : chunk local vers monde
→ viewMatrix : monde vers vue
→ projectionMatrix : vue vers clip

worldPosition
→ lightSpaceMatrices[i] : monde vers clip lumière A
→ lightSpaceMatricesB[i] : monde vers clip lumière B
→ division par w puis remap [-1,+1] vers [0,1]
→ coordonnées de shadow map
```

#### Fragment pipeline

Inputs : UV, normale monde, position monde, AO, profondeur vue et positions light-space.

Textures :

- unité 0 : atlas voxels 2D sRGB ;
- unité 1 : `shadowAtlas`, type GLSL `sampler2DArray` ;
- unité 2 : `colorTestArray`, R32F ;
- unité 3 : le même `shadowAtlas`, type `sampler2DArrayShadow`.

Output unique : `fragColor`, framebuffer par défaut.

Le chemin compilé actuel est `PERF_BISECT_STAGE 5` : [`voxel_fragment_shader.glsl:3`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/resources/shaders/voxel_fragment_shader.glsl:3). Il fait un seul fetch manuel A à [`voxel_fragment_shader.glsl:463`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/resources/shaders/voxel_fragment_shader.glsl:463).

Les branches PCF, B et blend leapfrog ne sont pas exécutées dans cette configuration.

#### Incohérences CPU/GLSL

- `leapfrogActive` forcé à zéro : [`VoxelRenderer.java:173-177`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/renderer/VoxelRenderer.java:173).
- `debugAO`, `debugShadow` et `forceCascade` sont eux aussi forcés à leurs valeurs neutres.
- Le premier `if (debugShadow > 0)` du shader retourne du rouge et rend le bloc de debug détaillé suivant inatteignable.
- Java expose huit modes `0..7`, le shader contient des branches jusqu’à 9.
- F7 et F8 appellent tous deux `cycleForceCascade`; F7 ne change aucun mode PCF.
- `frameIndex` est envoyé mais il n’existe aucun render target d’accumulation temporelle.

### 6.2 Depth

Vertex input : seule la position location 0 est consommée ; les VAO complets des chunks sont réutilisés.

```text
Chunk position locale
→ modelMatrix
→ lightSpaceMatrix
→ gl_Position
→ rasterisation profondeur
→ layer de GL_DEPTH_COMPONENT32F
```

Le fragment shader est vide. Il n’écrit aucune couleur. Output : depth attachment du layer courant.

### 6.3 Sun

- Vertex : position 3 floats, stride 12, location 0.
- CPU : position caméra + direction solaire × 200 ; inverse de la rotation de vue pour le billboard ; scale 50.
- Fragment : `vec4(uColor, 1)`.
- Output : back buffer.
- Le blending est activé, mais l’alpha produit vaut toujours 1.

### 6.4 Text

- Layout CPU : position2 + UV2, stride 16, locations 0/1.
- Uniforms : `uProjection`, `uColor`, `uFontTex`.
- Texture : atlas glyphes `GL_RED`.
- Fragment : alpha issu du canal rouge, sans discard.
- Output : back buffer avec blend alpha.

Un caractère non supporté est omis du buffer, mais le draw count reste `6 * text.length()`. Le buffer reste dimensionné au maximum, donc ce n’est pas une lecture hors allocation GPU, mais des sommets non renseignés peuvent être dessinés : [`TextRenderer.java:118-155`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/TextRenderer.java:118).

### 6.5 UI

- Layout CPU : position2 + UV2, stride 16.
- Uniforms : projection, couleur, booléen entier texture/couleur, sampler.
- Output : back buffer avec blending.
- Dépend du caller pour depth et culling.

### 6.6 Problème général des attributs

`Shader` ne bind explicitement que les noms `aPos`, `aTex`, `aNormal`, `aAO` : [`Shader.java:33-37`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/Shader.java:33).

Or :

- UI déclare `aTexCoord`;
- Text déclare `aUV`;
- Cube déclare `aTexCoord`.

Le CPU suppose néanmoins la location 1. Le linker choisit fréquemment 1 pour le deuxième attribut actif, mais ce choix n’est pas contractuellement fixé. C’est une dépendance non portable, pas une fonctionnalité OpenGL 3.2 manquante.

---

## 7. OpenGL state ownership

| State | Responsable observé | Dépendance/risque |
|---|---|---|
| `FRAMEBUFFER_SRGB` | `Application::init` | Activé globalement, jamais réévalué |
| FBO/viewport shadow | `ShadowRenderer` | Restaurés à framebuffer 0/dimensions fenêtre |
| Color mask | Shadow puis Sky/Voxel | Restauré explicitement |
| Depth test/write/func | Chaque passe 3D partiellement | UI loading dépend du state hérité |
| Blend | Sun/Text/UI/Shadow/Voxel | Chaque composant laisse un « défaut attendu », pas l’état précédent |
| Culling | Shadow, Sun, Voxel, Text | Sun/Text le laissent désactivé ; Voxel le rétablit |
| Front face | Voxel seulement | Shadow suppose le winding précédent |
| Draw/read buffer | Shadow/Sky | Retour forcé à `GL_BACK` |
| Texture units | Shader helper et renderers | Pas de restauration systématique ; unité 0 généralement laissée active |
| Texture compare mode | `VoxelRenderer` | Mutation globale de l’objet shadow atlas |
| VAO/VBO | Renderers individuels | Généralement unbind après draw |
| Program | `Shader::bind/unbind` | `glUseProgram(0)` après les passes |
| Polygon mode | `Application` | Fill permanent |
| Scissor | Shadow/Sky/Voxel le désactivent | Aucun usage effectif trouvé |
| Stencil | Personne | Aucun usage trouvé |

### Dépendances cachées importantes

1. `ShadowRenderer` ne restaure pas un snapshot : il impose quelques valeurs supposées convenables pour la suite.

2. `SunRenderer` et `TextRenderer` laissent le culling désactivé volontairement. La passe voxel fonctionne parce qu’elle le réactive elle-même.

3. `UIRenderer` suppose que le depth test a été désactivé par le caller. C’est vrai dans `MenuState`, pas dans `LoadingState`.

4. Le compare mode du shadow atlas est restauré seulement après le dessin normal. Il n’est pas protégé par `finally`; une exception entre les deux laisse l’objet en mode compare.

5. `ShadowRenderer` et `VoxelRenderer` commencent ou terminent par vider silencieusement `glGetError`. Cela détruit la provenance des erreurs au lieu de les diagnostiquer : [`ShadowRenderer.java:858`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/renderer/ShadowRenderer.java:858), [`VoxelRenderer.java:90`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/renderer/VoxelRenderer.java:90).

---

## 8. GPU resource ownership

| Ressource | Création / stockage | Destruction observée | Conclusion |
|---|---|---|---|
| Programme voxel | `PlayingState`, transmis à `VoxelRenderer` | `VoxelRenderer::cleanup`, puis encore `PlayingState::exit` | Ownership multiple |
| Programme depth | `PlayingState`, transmis à `ShadowRenderer` | via `VoxelRenderer`, puis `ShadowRenderer`, puis `PlayingState` | Jusqu’à trois tentatives |
| Atlas voxel | `TextureLoader`, handle local transmis à `VoxelRenderer` | Aucune | Leak lors des recréations de `PlayingState` |
| Shadow atlas/FBO/C/R32F | `ShadowRenderer` | `ShadowRenderer::cleanup` | Ownership local, mais cleanup non idempotent |
| Chunk VAO/VBO | `MeshWorkerManager`, handles dans `Chunk` | Au déchargement du chunk | Ancien mesh perdu lors d’un remesh |
| Sun VAO/VBO/shader | `SunRenderer` | `SunRenderer::cleanup` | Cohérent |
| Text texture/VAO/VBO/shader/STB data | `TextRenderer` | `TextRenderer::cleanup` | Cohérent si appelé une fois |
| UI VAO/VBO/shader | `UIRenderer` | `UIRenderer::cleanup` | Cohérent |
| Wrappers `Texture`/`Framebuffer` | Chemin dormant | Selon wrapper | Contrats d’ownership ambigus |

### Double cleanup

`VoxelRenderer::cleanup` détruit son shader et appelle `shadowRenderer.cleanup()` : [`VoxelRenderer.java:264`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/renderer/VoxelRenderer.java:264).

Puis `PlayingState::exit` appelle à nouveau `shadowRenderer.cleanup`, `voxelShader.cleanup` et `depthShader.cleanup` : [`PlayingState.java:241-267`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/core/states/PlayingState.java:241).

Les handles ne sont pas remis à zéro dans `Shader` et `ShadowRenderer`. Cela peut produire des erreurs GL sur des noms déjà supprimés et rend l’ownership impossible à déduire localement.

### Mesh leaks et concurrence

`MeshWorkerManager::uploadMeshesIfAny` crée toujours un nouveau VAO/VBO, puis `Chunk::setMesh` écrase les anciens handles sans les supprimer : [`MeshWorkerManager.java:56-89`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/MeshWorkerManager.java:56), [`Chunk.java:133`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/world/Chunk.java:133).

La méthode synchrone et apparemment legacy `MeshBuilder::buildChunkMesh` nettoie l’ancien mesh, mais elle n’est pas le chemin actif.

Un worker peut aussi terminer après le retrait du chunk de la map et uploader un mesh vers l’ancien objet `Chunk`. Aucune génération ou validation d’appartenance au monde n’est associée au résultat.

Enfin, `World::cleanup` arrête les executors mais ne parcourt pas les chunks restants pour appeler `Chunk::cleanup` : [`World.java:415`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/world/World.java:415).

### Wrappers dormants

`Texture.fromId` appelle d’abord `new Texture()`, générant un handle, puis l’écrase avec le handle externe : [`Texture.java:194`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/core/Texture.java:194). Le handle nouvellement généré fuit et le wrapper assume ensuite implicitement la propriété du handle externe.

`Framebuffer::attachColorTexture/attachDepthTexture` accepte des handles externes, mais `cleanup` les détruit. La frontière « attachment observé » versus « attachment possédé » n’est pas exprimée.

---

## 9. OpenGL / GLSL compatibility audit

## Contrat OpenGL réel vs contrat OpenGL supposé

| Ce que le contexte garantit | Ce que le code utilise | Ce que les shaders supposent |
|---|---|---|
| GLFW demande au minimum OpenGL 3.2 | Core VAO/VBO/FBO, texture arrays, sRGB, depth arrays | GLSL 1.50 |
| Core profile | Aucun fixed pipeline | Attributs génériques et fragment outputs |
| Forward compatible | Pas de fonctionnalité supprimée visible | Core-style shader pipeline |
| Version réelle non vérifiée | Le code suppose immédiatement les capabilities nécessaires | Pas de fallback de version |
| Extensions non vérifiées | Pas d’extension obligatoire identifiée | Aucune directive extension |
| Default framebuffer sRGB non explicitement demandé | `GL_FRAMEBUFFER_SRGB` activé | Sortie considérée linéaire vers framebuffer potentiellement sRGB |
| Limites GL non interrogées | 4 unités de texture, arrays de 6 layers, textures 2048² | `MAX_CASCADES=4`, usage réel 3 |

GLFW traite les hints de version comme une version minimale ; sur macOS, les contextes modernes sont core profile. Le programme ne journalise toutefois ni `GL_VERSION`, ni `GLSL_VERSION`, ni `GL_VENDOR`, ni `GL_RENDERER`. Voir la [documentation GLFW sur la création des contextes](https://www.glfw.org/docs/latest/window.html).

GLSL 1.50 correspond à OpenGL 3.2 et définit bien `sampler2DArrayShadow` ainsi que `texture(sampler2DArrayShadow, vec4)`. Voir la [spécification GLSL 1.50](https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.1.50.pdf).

### A — Violation probable de la spécification

#### Lecture non-shadow d’une texture depth en mode compare

**Factuel.**

```text
unit 1 → shadowAtlas → sampler2DArray
unit 3 → même shadowAtlas → sampler2DArrayShadow

glTexParameteri(shadowAtlas, GL_TEXTURE_COMPARE_MODE,
                GL_COMPARE_R_TO_TEXTURE)
```

Preuve : [`VoxelRenderer.java:202-233`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/renderer/VoxelRenderer.java:202).

Le mode compare est un paramètre de l’objet texture. Le binding de la même texture sur une autre unité ne crée pas une seconde copie de ses paramètres.

Le stage 5 effectue ensuite :

```glsl
texture(shadowAtlas, vec3(...)).r
```

La spécification OpenGL 3.2 indique que le résultat d’un lookup est indéfini lorsqu’un sampler non-shadow accède à une texture depth dont le compare mode n’est pas `NONE`. Voir la [spécification OpenGL 3.2 Core](https://registry.khronos.org/OpenGL/specs/gl/glspec32.core.pdf) et la [spécification GLSL 1.50](https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.1.50.pdf).

**Confiance : Haute.**

**Impact : Critique.**

**Intention probable.** Rendre disponibles à la fois des micro-tests manuels et hardware sans créer un second objet texture ou utiliser des sampler objects.

**Recommandation ultérieure.** Isoler les chemins manuels et shadow-compare de manière conforme avant toute nouvelle comparaison de performance. Ce rapport ne modifie pas l’implémentation.

### B — Fonctionnalité nécessitant une version supérieure

Aucun cas actif démontré.

Les fonctionnalités centrales du moteur sont disponibles en OpenGL 3.2. L’affirmation selon laquelle un upgrade 4.3 serait un prérequis au renderer actuel, présente dans l’ancien [`RENDERING_PIPELINE_REPORT.md:508`](/Users/mhandfares/Desktop/TOB_Voxel_Project/docs/RENDERING_PIPELINE_REPORT.md:508), n’est pas soutenue par le code actif.

### C — Dépendance à une extension

Aucune extension obligatoire identifiée dans le renderer actif.

### D — Driver / comportement non portable

1. Locations d’attributs UI/Text/Cube non fixées, alors que les VAO supposent la location 1. Confiance haute.

2. `GL_FRAMEBUFFER_SRGB` est activé sans hint `GLFW_SRGB_CAPABLE` ni interrogation de l’encodage réel du framebuffer. Confiance moyenne.

3. La manifestation concrète du lookup indéfini — couleur, lenteur, stabilité — dépend du driver. La racine reste néanmoins le cas A, pas une simple particularité Apple.

### E — Bug d’état OpenGL

1. `LoadingState` ne désactive pas le depth test avant d’utiliser un renderer UI qui suppose ce state fourni par l’appelant. Confiance haute, impact faible à moyen.

2. Les erreurs GL sont vidées silencieusement à plusieurs frontières, empêchant de relier une erreur à la passe qui l’a produite. Confiance haute.

### F — Incohérence CPU / GLSL

1. État CPU leapfrog actif versus uniform GLSL forcé à 0. Haute confiance.

2. Toggles debug modifiés côté Java mais uniforms forcés à des constantes. Haute confiance.

3. Stage annoncé/mesuré comme « shadows/leapfrog » alors que le préprocesseur compile un test mono-fetch A. Haute confiance.

4. `colorTestArray` alloué sans contenu puis échantillonné. Haute confiance.

5. Dans le harness Cube séparé, Java recherche `texAtlas` alors que le shader déclare `textureSampler`; le sampler fonctionne éventuellement grâce à la valeur par défaut 0. Haute confiance, chemin inactif.

### G — Problème architectural

- Ownership multiple des shaders et du shadow renderer.
- Wrappers GL parallèles au chemin raw GL.
- Configuration de benchmark intégrée au fragment shader de production.
- État de rendu dispersé sans contrat par passe.
- Machine C maintenue sans consommateur.
- Documentation décrivant plusieurs générations différentes de la pipeline.

### H — Sous-optimal mais correct

- `glGetUniformLocation` à chaque upload.
- `glFinish()` à chaque frame.
- VBO texte recréé/réuploadé par chaîne.
- Tous les chunks rendus dans toutes les cascades.
- Aucun frustum culling dans la passe voxel.
- Une matrice modèle et un draw call par chunk.
- Shadow maps 2048² × nombreux layers.
- Fenêtre mesurée à 3024×1832, soit environ 5,54 millions de fragments avant overdraw.

---

## 10. Analyse des logs `perf_test`

Les séries relues donnent approximativement, après exclusion du warm-up :

| Stage | Médiane `glFinish_ms` |
|---:|---:|
| 1 | 5,45 ms |
| 2 | 5,53 ms |
| 3 | 5,65 ms |
| 4 | 6,17 ms |
| 5 | 19,55 ms |
| 6 | 19,63 ms |
| 7–9 anciennes séries | environ 34,8–35 ms |
| 10 depth, coordonnées constantes | 19,48 ms |
| 11 depth, coordonnées dynamiques | 19,65 ms |
| 12 R32F, coordonnées constantes | 6,18 ms |
| 13 R32F, coordonnées dynamiques | 19,11 ms |
| 14 shadow sampler, un tap | 19,44 ms |
| 15 shadow sampler, quatre taps | 19,57 ms |

Sources : [`perf_bisect_report.md`](/Users/mhandfares/Desktop/TOB_Voxel_Project/logs/perf_bisect_report.md), [`perf_microtest_report.md`](/Users/mhandfares/Desktop/TOB_Voxel_Project/logs/perf_microtest_report.md), [`perf_atlas_report.md`](/Users/mhandfares/Desktop/TOB_Voxel_Project/logs/perf_atlas_report.md) et CSV correspondants.

### Ce que ces logs prouvent

**Factuel — Haute confiance :**

- Certaines configurations soumises produisent un temps de frame sensiblement supérieur.
- Le passage du stage 4 au stage 5 coïncide avec une hausse importante.
- Les coordonnées dynamiques provoquent également une hausse sur le test R32F.
- Sur ces runs, un tap et quatre taps ont des temps proches.
- Le framebuffer était très haute résolution.

### Ce qu’ils ne prouvent pas

1. `glFinish_ms` n’est pas le temps d’une instruction texture. C’est le temps pendant lequel le CPU attend l’achèvement de tout travail GPU antérieur non terminé.

2. `render_ms` mesure principalement la soumission CPU, pas le coût GPU.

3. L’appel obligatoire de [`Application.java:248`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/core/Application.java:248) empêche le pipelining normal des frames.

4. Les stages de lecture depth manuelle 5–11 sont affectés par le lookup indéfini.

5. Les stages 12/13 lisent une texture R32F jamais initialisée. La charge d’échantillonnage peut être chronométrée, mais pas la validité du résultat visuel.

6. La colonne `leapfrogEnabled` reflète `ShadowRenderer::isLeapfrogEnabled`, tandis que le shader reçoit toujours zéro.

7. `shadowPassEnabled` est journalisé avec la constante `true`, et `visibleChunks`/`renderedVertices` sont journalisés à zéro : [`PlayingState.java:184-208`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/core/states/PlayingState.java:184).

8. Aucun log ne contient le vendor, renderer, version GL/GLSL, limites ou compteurs GPU.

### Conclusions documentaires insuffisamment étayées

[`render_pipeline_reference.md`](/Users/mhandfares/Desktop/TOB_Voxel_Project/docs/render_pipeline_reference.md) attribue certains résultats à Metal, au cache L2, aux TMU et au « warp broadcast ». Le benchmark ne possède aucun compteur permettant de vérifier ces causes.

Le document dit aussi « Metal via MoltenVK-like translation ». Ce n’est pas une description correcte de la stack observée. MoltenVK est une implémentation Vulkan sur Metal, pas une couche OpenGL générique ; voir le [projet officiel MoltenVK](https://github.com/KhronosGroup/MoltenVK).

---

## 11. Architectural strata

### Strate 1 — Renderer direct historique

- `Application` crée directement la fenêtre GLFW.
- Textures et buffers sont des `int`.
- Les composants appellent directement `glEnable`, `glBind*`, `glDrawArrays`.
- `MeshBuilder` mélange génération de mesh et helpers de rendu.

Cette strate constitue toujours le chemin actif.

### Strate 2 — Abstractions `render/core`

`Window`, `Texture`, `Framebuffer` et `RenderContext` suggèrent une tentative de frontière plus structurée.

En pratique :

- seul `RenderContext` est véritablement utilisé ;
- `Window`, `Texture` et `Framebuffer` n’encapsulent pas les ressources actives ;
- `ShadowRenderer` réimplémente directement un FBO et ses textures ;
- les détails OpenGL restent visibles partout.

### Strate 3 — Ancien système menu

`engine/menu/**` coexiste avec `engine/ui/**`, mais Gradle exclut explicitement l’ancien package : [`build.gradle:26-37`](/Users/mhandfares/Desktop/TOB_Voxel_Project/build.gradle:26).

C’est une strate legacy conservée dans le dépôt, pas une architecture concurrente active.

### Strate 4 — CSM puis leapfrog

Les documents montrent plusieurs générations :

- ancien shadow map simple et `ShadowCalculator` désormais absent ;
- CSM array/hardware sampler ;
- deux arrays A/B ;
- atlas A+B plus staging C ;
- phase de bisect où leapfrog et debug sont volontairement neutralisés.

Exemples de contradictions :

- [`RENDERING_PIPELINE_REPORT.md`](/Users/mhandfares/Desktop/TOB_Voxel_Project/docs/RENDERING_PIPELINE_REPORT.md) décrit OpenGL 3.0, un composant supprimé et le CSM comme futur.
- [`LEAPFROG_SHADOW_SYSTEM.md`](/Users/mhandfares/Desktop/TOB_Voxel_Project/docs/LEAPFROG_SHADOW_SYSTEM.md) décrit deux textures A/B.
- [`LEAPFROG_FLASH_DIAGNOSTIC_CHECKLIST.md`](/Users/mhandfares/Desktop/TOB_Voxel_Project/docs/LEAPFROG_FLASH_DIAGNOSTIC_CHECKLIST.md) cite des unités et lignes d’une version antérieure.
- [`render_pipeline_reference.md`](/Users/mhandfares/Desktop/TOB_Voxel_Project/docs/render_pipeline_reference.md) correspond davantage au code actuel, tout en reproduisant l’erreur de raisonnement sur le compare mode.

### Autres indices de strates différentes

- Deux classes Sun : `engine/time/Sun` et le `SunRenderer` actif.
- `OrthographicCamera` existe, mais les UI construisent directement des matrices JOML.
- `RenderConfig` conserve un layout vertex à 40 octets alors que le chemin actif utilise 36.
- `PausedState` existe mais n’est jamais instancié.
- Tests présents dans le dépôt, mais désactivés par Gradle.
- `Shader` est très générique dans ses setters, mais ne définit pas complètement les interfaces d’attributs.

Je qualifierais cela de « strates architecturales probablement issues de périodes de développement différentes », sans pouvoir attribuer leur origine à un outil ou auteur particulier.

---

## 12. Risk register priorisé

| Priorité | Hotspot | Cat. | Gravité | Confiance |
|---:|---|:---:|---|---|
| 1 | Lecture `sampler2DArray` d’un depth atlas dont le compare mode est activé | A | Critique | Haute |
| 2 | Benchmarks utilisés pour conclure sur DEPTH32F/Metal alors que le chemin testé est indéfini | G | Critique pour le diagnostic | Haute |
| 3 | Leapfrog calculé côté CPU mais forcé OFF dans le shader | F | Élevée | Haute |
| 4 | C staging jamais transféré/consommé ; « copie » remplacée par un rerender complet | G | Élevée | Haute |
| 5 | Doubles/triples destructions de programmes et shadow resources | G | Élevée | Haute |
| 6 | VAO/VBO perdus à chaque remeshing | G | Élevée sur sessions longues | Haute |
| 7 | Upload possible d’un mesh produit pour un chunk déchargé ou périmé | G | Élevée | Moyenne |
| 8 | `World::cleanup` ne détruit pas les meshes des chunks restants | G | Élevée lors des transitions | Haute |
| 9 | Atlas voxel jamais détruit | G | Moyenne à élevée | Haute |
| 10 | Aucun culling par cascade ; jusqu’à neuf parcours complets au warm-up | H | Élevée en performance | Haute |
| 11 | `glFinish()` systématique à chaque frame | H | Élevée en performance/mesure | Haute |
| 12 | `colorTestArray` non initialisé mais utilisé pour des conclusions de micro-test | F | Moyenne | Haute |
| 13 | Snap index dérivé de `m30/m31` de la matrice projection×vue puis divisé par une taille de texel monde | F | Potentiellement élevée | Moyenne |
| 14 | Locations 1 de UI/Text/Cube dépendantes du linker | D | Moyenne | Haute |
| 15 | Debug shader inatteignable et controls Java neutralisés | F | Moyenne | Haute |
| 16 | UI de loading dépend d’un depth state incorrectement hérité | E | Faible à moyenne | Haute |
| 17 | sRGB framebuffer activé sans contrat explicite | D | Faible à moyenne | Moyenne |
| 18 | Erreurs GL silencieusement vidées | E | Moyenne pour le diagnostic | Haute |

### Snap index

[`ShadowRenderer.java:836-847`](/Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/renderer/ShadowRenderer.java:836) extrait `-m30/-m31` de la matrice light-space déjà combinée et divise ces valeurs par un texel exprimé en espace monde.

**Factuel :** ces grandeurs ne sont pas manifestement dans le même espace/unité.

**Intention probable :** détecter un déplacement d’au moins un texel dans la base lumière.

**Conclusion — Confiance moyenne :** le test de changement de snap peut déclencher des rerenders A/B à des moments incorrects. Une validation numérique séparée de `CSMCalculator` est nécessaire avant de conclure sur l’impact visuel.

---

## 13. Unknowns

Le code et les artefacts présents ne permettent pas d’établir :

- la version OpenGL effectivement retournée sur la machine durant les runs ;
- `GL_VENDOR`, `GL_RENDERER` et `GLSL_VERSION`;
- la limite réelle de texture size, layers et texture units ;
- l’encodage sRGB réel du default framebuffer ;
- la forme visuelle précise des flashes correspondant aux anciens logs ;
- si le comportement anormal est couleur incorrecte, cache/stall, recompilation interne ou autre conséquence du lookup indéfini ;
- la version exacte du code associée à chaque CSV au-delà des colonnes disponibles ;
- la présence de throttling thermique ou d’autres processus durant les mesures ;
- les temps GPU pass par pass ;
- le nombre réel de chunks/triangles dans les CSV, leurs colonnes étant à zéro ;
- si les remeshs arrivent fréquemment en usage normal ;
- l’intention produit concernant la fréquence solaire et le niveau de stabilité attendu ;
- si `PausedState` était destiné à préserver `PlayingState` ou à le détruire.

Les crash logs du 3 mars montrent une redéclaration GLSL de `shadowB` dans certaines combinaisons de préprocesseur. La structure conditionnelle actuelle semble avoir corrigé cette erreur ; elle doit être classée comme historique, pas comme bug de compilation actuel.

---

## 14. Questions that the code cannot answer

1. Le leapfrog doit-il réellement être une fonctionnalité de production, ou est-il encore un prototype de recherche ?

2. La cible produit prioritaire est-elle uniquement macOS, macOS Intel + Apple Silicon, ou également Windows/Linux ?

3. Le résultat graphique recherché privilégie-t-il la stabilité temporelle, la résolution des ombres ou le framerate ?

4. Le soleil doit-il évoluer suffisamment vite pour justifier deux cartes et un crossfade permanent ?

5. Les shadow maps doivent-elles suivre continuellement la caméra, ou tolérer un mouvement discret par texel/cellule ?

6. Les stages `PERF_BISECT_STAGE` doivent-ils rester dans le shader principal ou n’étaient-ils qu’un dispositif temporaire ?

7. Les anciennes captures/logs contiennent-ils une référence visuelle du flash permettant de distinguer :

   - un flash plein écran ;
   - un changement de cascade ;
   - une shadow map périmée ;
   - un défaut de coordonnées ;
   - un résultat indéfini de sampling ?

---

## 15. Recommended investigation order

Ces recommandations concernent une phase ultérieure ; aucune n’a été appliquée.

1. **Établir la vérité runtime.** Journaliser une fois le vendor, renderer, versions GL/GLSL, profil, flags, limites et encoding du framebuffer.

2. **Rendre un micro-test conforme à la spécification.** Séparer réellement le chemin manuel du chemin shadow-compare. Ne pas comparer Intel/M2 avant cette étape.

3. **Remplacer `glFinish` comme instrument principal.** Employer des timer queries GPU autour de chaque passe, tout en conservant les mêmes frame/scène/résolution.

4. **Rejouer une matrice minimale :**

   - aucune shadow texture lue ;
   - texture couleur R32F initialisée ;
   - depth texture lue manuellement avec compare mode `NONE`;
   - depth texture lue avec shadow sampler et compare mode actif ;
   - 1 puis 4 taps ;
   - profondeur pass désactivée/activée.

5. **Vérifier le résultat graphique**, pas seulement le temps, via captures identiques et valeurs de pixels contrôlées.

6. **Décider si leapfrog est actif ou expérimental.** Tant que l’uniform est forcé à zéro, ne pas attribuer les performances ou artefacts au blend A/B.

7. **Valider la mathématique CSM/snap** avec quelques positions de caméra connues et inspection des matrices.

8. **Auditer l’ownership**, en priorité les doubles destructions, remeshs et nettoyage final des chunks.

9. **Ajouter seulement ensuite les optimisations évidentes**, notamment culling par cascade et suppression des rendus/resources sans consommateur.

10. **Comparer les plateformes** après obtention d’un cas valide et reproductible.

---

## Faut-il tester sur un Mac Intel ?

Pas comme solution principale.

Un Mac Intel conserve la même contrainte macOS : OpenGL y est déprécié et son implémentation reste limitée à l’écosystème Apple. Apple indique qu’OpenGL reste disponible sur Apple Silicon mais recommande Metal pour les développements futurs : [documentation Apple Silicon](https://developer.apple.com/documentation/Apple-Silicon/porting-your-macos-apps-to-apple-silicon?changes=lates_7_6).

Un test Intel peut répondre à une question secondaire : « le même programme conforme se comporte-t-il différemment sur un autre GPU/driver Apple ? ». Il ne peut pas valider le programme actuel, car un comportement indéfini peut fonctionner sur un driver et échouer ou ralentir sur un autre.

La meilleure matrice serait :

1. M2 actuel, chemin rendu conforme et instrumenté ;
2. machine Windows ou Linux avec OpenGL natif moderne ;
3. Mac Intel comme troisième point de comparaison, si cette plateforme fait partie des cibles.

Un Mac Intel avec GPU AMD peut être plus rapide qu’un Intel iGPU, ou inversement selon la charge. Comparer « Intel contre M2 » mélange architecture CPU, GPU, driver, écran et résolution. Ce n’est donc pas un test isolant OpenGL 3.2.

**Verdict final — Haute confiance :** ne migre pas les investigations vers un Mac Intel pour contourner le problème. Corrige d’abord le protocole expérimental et le contrat texture/sampler ; utilise ensuite le Mac Intel uniquement comme comparaison de portabilité.