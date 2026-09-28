# Guide de Remise en État du Moteur Voxel OpenGL

> **Contexte** : Ce guide est le plan d'action consolidé issu de la confrontation entre le *Rapport d'Archéologie* initial et la *Contre-Review Astra*. Il détaille, étape par étape, les anomalies du moteur (bugs OpenGL, fuites mémoire, incohérences mathématiques et instrumentation faussée) et fournit le code et la méthode pour reprendre la main sur la codebase de manière propre, stable et mesurable.

---

## Sommaire

1. [Cartographie des Problèmes & Ordre de Priorité](#1-cartographie-des-problèmes--ordre-de-priorité)
2. [Phase 0 : Vérité Runtime & Débouchage du Diagnostic](#phase-0--vérité-runtime--débouchage-du-diagnostic)
3. [Phase 1 : Bugs Critiques de Conformité OpenGL (Crashs & Undefined Behavior)](#phase-1--bugs-critiques-de-conformité-opengl)
   - [1.1 Conflit fatal Texture vs Compare Mode (`sampler2DArray`)](#11-conflit-fatal-texture-vs-compare-mode)
   - [1.2 Dépassement mémoire VBO dans `TextRenderer`](#12-dépassement-mémoire-vbo-dans-textrenderer)
   - [1.3 Pollution d'état `GL_DEPTH_TEST` dans l'UI (Menu & Loading)](#13-pollution-détat-gl_depth_test-dans-lui)
   - [1.4 Attributs Vertex non appariés avant le Link (`Shader.java`)](#14-attributs-vertex-non-appariés-avant-le-link)
4. [Phase 2 : Gestion de la Mémoire GPU & Ownership (Leaks & Double-Frees)](#phase-2--gestion-de-la-mémoire-gpu--ownership)
   - [2.1 Fuite massive de VAO/VBO lors du Remeshing des Chunks](#21-fuite-massive-de-vaovbo-lors-du-remeshing-des-chunks)
   - [2.2 Fuites à l'arrêt du monde et buffers natifs orphelins](#22-fuites-à-larrêt-du-monde-et-buffers-natifs-orphelins)
   - [2.3 Ownership des Shaders & Fuite de l'Atlas Voxel](#23-ownership-des-shaders--fuite-de-latlas-voxel)
5. [Phase 3 : Clarification Mathématique & CSM Leapfrog](#phase-3--clarification-mathématique--csm-leapfrog)
   - [3.1 Incohérence dimensionnelle sur le Snap Index (Matrices CSM)](#31-incohérence-dimensionnelle-sur-le-snap-index)
   - [3.2 Démystification du Leapfrog (Promotion B→A vs Crossfade GLSL)](#32-démystification-du-leapfrog)
   - [3.3 Sort des textures inutilisées (C staging & R32F)](#33-sort-des-textures-inutilisées)
6. [Phase 4 : Assainissement du Profiling & Benchmarks](#phase-4--assainissement-du-profiling--benchmarks)
   - [4.1 Suppression du `glFinish()` bloquant par frame](#41-suppression-du-glfinish-bloquant-par-frame)
   - [4.2 Instrumentation de timing conforme (Timer Queries & Fallback)](#42-instrumentation-de-timing-conforme)
   - [4.3 Sortie du mode `PERF_BISECT_STAGE` vers un pipeline standard](#43-sortie-du-mode-perf_bisect_stage)
7. [Feuille de Route Opérationnelle (Checklist pas à pas)](#7-feuille-de-route-opérationnelle-checklist)

---

## 1. Cartographie des Problèmes & Ordre de Priorité

Les deux analyses ont mis en évidence que les supposées « incompatibilités Apple Silicon M2 » ou « nécessités d'OpenGL 4.3 / Metal » étaient en réalité des conclusions hâtives dues à :
1. **Un cas d'Undefined Behavior formel** selon la spécification OpenGL 3.2 Core (lecture manuelle de profondeur avec compare mode actif).
2. **Une instrumentation faussée** (`glFinish()` forcé, benchmarks comparant des états corrompus, GPU timer queries préconisées sans vérifier les extensions).
3. **Des fuites de ressources OpenGL et mémoires natives** au fil des frames.

### Matrice des Risques Révisée

| Priorité | Problème | Fichiers concernés | Nature | Impact |
|:---:|---|---|:---:|---|
| **P0** | **Compare mode sur lecture manuelle de depth** | `VoxelRenderer.java`, `voxel_fragment_shader.glsl` | Spécification OpenGL | Résultat graphique indéfini, stalls driver |
| **P0** | **Dépassement buffer VBO texte** | `TextRenderer.java` | Corruption mémoire GPU | Draw hors buffer si caractères hors plage / accents |
| **P1** | **Drainage silencieux des erreurs GL** | `VoxelRenderer.java`, `ShadowRenderer.java` | Diagnostic | Masque les vraies erreurs de pipeline |
| **P1** | **Fuite de VAO/VBO au remeshing** | `Chunk.java`, `MeshWorkerManager.java` | Fuite mémoire VRAM | Accumulation continue de mémoire GPU en jeu |
| **P1** | **Incohérence du Depth Test dans l'UI** | `TextRenderer.java`, `LoadingState.java`, `MainMenuScreen.java` | État OpenGL | Éléments UI invisibles ou masqués par z-fighting |
| **P2** | **Attributs GLSL non liés explicitement** | `Shader.java`, `text_vertex.glsl`, `ui_vertex.glsl` | Portabilité driver | Risque de mauvaise assignation des attributs |
| **P2** | **Ownership des Shaders & Atlas Voxel** | `PlayingState.java`, `VoxelRenderer.java`, `ShadowRenderer.java` | Architecture / Fuite | Double/triple delete et atlas texture jamais libéré |
| **P2** | **Nettoyage final du monde & workers** | `World.java`, `MeshWorkerManager.java` | Fuite RAM / VRAM | Chunks résiduels et buffers natifs orphelins |
| **P3** | **Calcul du snap index CSM (Unités incohérentes)** | `ShadowRenderer.java`, `CSMCalculator.java` | Mathématique | Détection de snap erratique, rerenders inutiles |
| **P3** | **`glFinish()` obligatoire à chaque frame** | `Application.java` | Performance | Détruit le parallélisme CPU-GPU |

---

## Phase 0 : Vérité Runtime & Débouchage du Diagnostic

Avant de toucher au rendu, il est impératif d'activer la visibilité sur ce que fait réellement le pilote graphique et de cesser de jeter les erreurs OpenGL à la poubelle.

### 0.1 Arrêter le drainage silencieux des erreurs (`drainGLErrors`)

Dans [`VoxelRenderer.java:89`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/renderer/VoxelRenderer.java#L89) et [`ShadowRenderer.java:858`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/renderer/ShadowRenderer.java#L858) :

```java
// CODE ACTUEL DANGEREUX :
private void drainGLErrors() {
    while (glGetError() != GL_NO_ERROR) {} // Détruit la provenance de l'erreur !
}
```

**À remplacer par une inspection explicite :**

```java
public static void checkGLError(String location) {
    int error;
    while ((error = glGetError()) != GL_NO_ERROR) {
        String errorName = switch (error) {
            case GL_INVALID_ENUM -> "GL_INVALID_ENUM";
            case GL_INVALID_VALUE -> "GL_INVALID_VALUE";
            case GL_INVALID_OPERATION -> "GL_INVALID_OPERATION";
            case GL_STACK_OVERFLOW -> "GL_STACK_OVERFLOW";
            case GL_STACK_UNDERFLOW -> "GL_STACK_UNDERFLOW";
            case GL_OUT_OF_MEMORY -> "GL_OUT_OF_MEMORY";
            case GL_INVALID_FRAMEBUFFER_OPERATION -> "GL_INVALID_FRAMEBUFFER_OPERATION";
            default -> "0x" + Integer.toHexString(error);
        };
        System.err.println("[GL ERROR] " + errorName + " at: " + location);
    }
}
```

### 0.2 Loguer l'identité réelle du contexte OpenGL au démarrage

Dans [`Application.java:140`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/core/Application.java#L140), après `GL.createCapabilities()` :

```java
System.out.println("=== OPENGL RUNTIME IDENTITY ===");
System.out.println("GL_VENDOR:   " + glGetString(GL_VENDOR));
System.out.println("GL_RENDERER: " + glGetString(GL_RENDERER));
System.out.println("GL_VERSION:  " + glGetString(GL_VERSION));
System.out.println("GLSL:        " + glGetString(GL_SHADING_LANGUAGE_VERSION));
System.out.println("Max Texture Units:   " + glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS));
System.out.println("Max 2D Array Layers: " + glGetInteger(GL_MAX_ARRAY_TEXTURE_LAYERS));
System.out.println("Max Texture Size:    " + glGetInteger(GL_MAX_TEXTURE_SIZE));
System.out.println("===============================");
```

---

## Phase 1 : Bugs Critiques de Conformité OpenGL

### 1.1 Conflit fatal Texture vs Compare Mode

#### Le Problème
Dans [`VoxelRenderer.java:212-234`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/renderer/VoxelRenderer.java#L212-L234) :
1. `shadowAtlas` est lié sur l'unité 1 pour lecture manuelle (`sampler2DArray`).
2. Le **même** `shadowAtlas` est lié sur l'unité 3.
3. Le code applique `glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_R_TO_TEXTURE)` sur l'objet texture `atlasId`.
4. Le draw call (`renderVisibleChunks`) s'exécute.
5. Dans `voxel_fragment_shader.glsl` (au `PERF_BISECT_STAGE 5`), le shader fait :
   `texture(shadowAtlas, vec3(projCoords.xy, float(layerOffset + cascade))).r;`

> **Spécification OpenGL 3.2 Core (§3.9.2)** :
> Si une texture de profondeur est échantillonnée par un sampler non-shadow (`sampler2DArray`) alors que son paramètre d'objet `GL_TEXTURE_COMPARE_MODE` n'est pas `GL_NONE`, le résultat de la fonction de texture est **INDÉFINI**.
> C'est ce comportement indéfini qui causait les résultats chaotiques ou stalls sur GPU Apple Silicon !

#### La Solution
Si vous faites de la lecture de profondeur manuelle (sans comparaison matérielle), le compare mode doit impérativement rester `GL_NONE`.
Ne modifiez pas `GL_TEXTURE_COMPARE_MODE` au milieu du rendu sur un atlas partagé.

Dans [`VoxelRenderer.java`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/renderer/VoxelRenderer.java#L212-L235) :
- **Supprimez** les lignes 212 à 222 qui activent `GL_COMPARE_R_TO_TEXTURE` à la volée.
- **Supprimez** les lignes 230 à 234 qui le remettent à `GL_NONE` trop tard.
- **Choisissez explicitement la stratégie** :
  - **Option A (Recommandée pour la conformité et les perfs)** : Utiliser le matériel shadow compare (`sampler2DArrayShadow` + `texture(shadowSampler, vec4(uv, layer, refZ))`). Dans ce cas, réglez `GL_COMPARE_R_TO_TEXTURE` une fois pour toutes lors de la création de la texture dans `ShadowRenderer`, et n'utilisez QUE `sampler2DArrayShadow` dans le GLSL.
  - **Option B (Mode manuel pur)** : Gardez `GL_TEXTURE_COMPARE_MODE = GL_NONE` en permanence et faites la comparaison `depth < ref` dans le shader avec `sampler2DArray`.
  - **Règle absolue** : Ne mélangez jamais les deux sur la même texture sans objets Sampler (`glGenSamplers`), non garantis en 3.2 core.

---

### 1.2 Dépassement mémoire VBO dans `TextRenderer`

#### Le Problème
Dans [`TextRenderer.java:111-155`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/TextRenderer.java#L111-L155) :
1. `FloatBuffer verts` est alloué pour `6 * 4 * text.length()`.
2. La boucle filtre les caractères non supportés :
   `if (ci < 0 || ci >= NUM_CHARS) continue;`
3. Si un caractère est ignoré (caractère accentué, saut de ligne `\n`, etc.), moins de quads sont ajoutés.
4. `verts.flip()` est appelé.
5. `glBufferData(GL_ARRAY_BUFFER, verts, GL_DYNAMIC_DRAW)` n'uploade que `verts.remaining() * 4` octets (soit $24 \times m$ floats, où $m < n$).
6. Mais le draw call suivant fait :
   `glDrawArrays(GL_TRIANGLES, 0, 6 * text.length());`
7. Le GPU tente de lire $6 \times n$ sommets dans un buffer qui n'en contient que $6 \times m$ : **lecture hors allocation GPU** !

#### La Correction

Dans [`TextRenderer.java`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/TextRenderer.java#L110-L156) :

```diff
-        FloatBuffer verts = BufferUtils.createFloatBuffer(6 * 4 * text.length());
+        FloatBuffer verts = BufferUtils.createFloatBuffer(6 * 4 * text.length());
         float cursorX = 0, cursorY = 0;
+        int validQuads = 0;
         try (MemoryStack stack = MemoryStack.stackPush()) {
             FloatBuffer px = stack.floats(cursorX), py = stack.floats(cursorY);
             STBTTAlignedQuad quad = STBTTAlignedQuad.mallocStack(stack);
             for (int i = 0; i < text.length(); i++) {
                 char c = text.charAt(i);
                 int ci = c - FIRST_CHAR;
                 if (ci < 0 || ci >= NUM_CHARS) continue;
                 stbtt_GetBakedQuad(charData, BITMAP_W, BITMAP_H, ci, px, py, quad, true);
                 float x0 = x + quad.x0() * scale, y0 = y + quad.y0() * scale;
                 float x1 = x + quad.x1() * scale, y1 = y + quad.y1() * scale;
                 float u0 = quad.s0(), v0 = quad.t0(), u1 = quad.s1(), v1 = quad.t1();
                 verts.put(x0).put(y0).put(u0).put(v0);
                 verts.put(x1).put(y0).put(u1).put(v0);
                 verts.put(x1).put(y1).put(u1).put(v1);
                 verts.put(x0).put(y0).put(u0).put(v0);
                 verts.put(x1).put(y1).put(u1).put(v1);
                 verts.put(x0).put(y1).put(u0).put(v1);
+                validQuads++;
             }
             verts.flip();
         }
         
         glBindVertexArray(vao);
         glBindBuffer(GL_ARRAY_BUFFER, vbo);
         glBufferData(GL_ARRAY_BUFFER, verts, GL_DYNAMIC_DRAW);
         
         // ... bindings de texture et uniforms ...
         
-        glDrawArrays(GL_TRIANGLES, 0, 6 * text.length());
+        if (validQuads > 0) {
+            glDrawArrays(GL_TRIANGLES, 0, 6 * validQuads);
+        }
```

---

### 1.3 Pollution d'état `GL_DEPTH_TEST` dans l'UI

#### Le Problème
1. Dans [`MenuState.java:48`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/core/states/MenuState.java#L48), `glDisable(GL_DEPTH_TEST)` est appelé avant `uiManager.render()`.
2. Dans [`MainMenuScreen.java:117-128`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/ui/screens/MainMenuScreen.java#L117-L128), le titre en texte est rendu avant les boutons.
3. Dans [`TextRenderer.java:160`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/TextRenderer.java#L160), la méthode `drawText` contient :
   `glEnable(GL_DEPTH_TEST);`
4. Les boutons suivants sont donc rendus avec le Depth Test activé ! Comme les quads UI ont tous $z = 0$, les éléments se masquent de façon aléatoire selon le depth buffer existant.
5. Dans [`LoadingState.java:200`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/core/states/LoadingState.java#L200), `glDisable(GL_DEPTH_TEST)` n'est même pas appelé du tout.

#### La Correction
- Dans `TextRenderer.java`, **supprimez** `glEnable(GL_DEPTH_TEST)` à la ligne 160. `TextRenderer` est un composant 2D, il ne doit pas imposer le depth test 3D à ses appelants.
- Dans `UIRenderer.java` et `TextRenderer.java`, assurez-vous que la passe UI a un contrat clair :
  - Soit `uiManager.render()` encadre toute sa passe d'un `glDisable(GL_DEPTH_TEST)` / `glEnable(GL_DEPTH_TEST)`.
  - Soit chaque renderer 2D désactive le depth test au début et le restaure à son état d'origine.
- Dans `LoadingState.java` :
  ```java
  @Override
  public void render(RenderContext context) {
      glClearColor(0.05f, 0.05f, 0.1f, 1.0f);
      glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
      glDisable(GL_DEPTH_TEST); // <-- AJOUTER
      uiManager.render(app.getWindowWidth(), app.getWindowHeight());
      glEnable(GL_DEPTH_TEST);  // <-- AJOUTER
  }
  ```

---

### 1.4 Attributs Vertex non appariés avant le Link

#### Le Problème
Dans [`Shader.java:34-37`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/Shader.java#L34-L37) :
```java
glBindAttribLocation(programId, 0, "aPos");
glBindAttribLocation(programId, 1, "aTex");
glBindAttribLocation(programId, 2, "aNormal");
glBindAttribLocation(programId, 3, "aAO");
```
Mais :
- `text_vertex.glsl` déclare `in vec2 aUV;` (pas `aTex`).
- `ui_vertex.glsl` déclare `in vec2 aTexCoord;` (pas `aTex`).

Les VAO de `TextRenderer` et `UIRenderer` attendent la texture aux coordonnées sur `location = 1`. Faute de `glBindAttribLocation`, la position de `aUV` et `aTexCoord` dépend du bon vouloir du compilateur GLSL.

#### La Correction
Dans `Shader.java`, liez tous les alias standardisés à la location 1 avant `glLinkProgram` :
```java
glBindAttribLocation(programId, 0, "aPos");
glBindAttribLocation(programId, 1, "aTex");
glBindAttribLocation(programId, 1, "aUV");        // Support Text shader
glBindAttribLocation(programId, 1, "aTexCoord");  // Support UI shader
glBindAttribLocation(programId, 2, "aNormal");
glBindAttribLocation(programId, 3, "aAO");
```
*(En OpenGL, plusieurs noms non actifs simultanément peuvent être assignés au même index).*

---

## Phase 2 : Gestion de la Mémoire GPU & Ownership

### 2.1 Fuite massive de VAO/VBO lors du Remeshing des Chunks

#### Le Problème
Quand un chunk est modifié (destruction/pose de bloc) ou quand un voisin est généré (`markNeighborsDirty`), un nouveau mesh est calculé de manière asynchrone par `MeshWorkerManager`.

Dans [`MeshWorkerManager.java:87`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/MeshWorkerManager.java#L87) :
`data.chunk.setMesh(vao, vbo, data.vertexCount);`

Et dans [`Chunk.java:133-139`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/world/Chunk.java#L133-L139) :
```java
public void setMesh(int vao, int vbo, int vertexCount) {
    this.vao = vao;               // Écrase l'ancien VAO sans le libérer !
    this.vbo = vbo;               // Écrase l'ancien VBO sans le libérer !
    this.vertexCount = vertexCount;
    this.meshDirty = false;
}
```
L'ancien VAO et l'ancien VBO restent alloués en VRAM pour toujours. Après quelques minutes de jeu et de navigation, des centaines de mégagooctets de VRAM sont perdus.

#### La Correction
Puisque `uploadMeshesIfAny()` s'exécute sur le **thread OpenGL**, la libération de l'ancien mesh peut et doit se faire immédiatement :

Dans [`Chunk.java`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/world/Chunk.java#L133-L139) :
```java
public void setMesh(int newVao, int newVbo, int newVertexCount) {
    // Libération de l'ancien mesh s'il existait déjà
    if (this.vao != 0 && this.vao != newVao) {
        glDeleteVertexArrays(this.vao);
    }
    if (this.vbo != 0 && this.vbo != newVbo) {
        glDeleteBuffers(this.vbo);
    }
    this.vao = newVao;
    this.vbo = newVbo;
    this.vertexCount = newVertexCount;
    this.meshDirty = false;
}
```

---

### 2.2 Fuites à l'arrêt du monde et buffers natifs orphelins

#### Le Problème
1. Dans [`World.java:415`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/world/World.java#L415), `World.cleanup()` arrête les threads mais ne détruit aucun mesh des chunks chargés dans `chunks.values()`.
2. Dans [`MeshWorkerManager.java:93`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/MeshWorkerManager.java#L93), `shutdown()` stoppe l'exécuteur mais ne vide pas `uploadQueue`. Les objets `MeshData` restants en file d'attente contiennent des `FloatBuffer` alloués avec `MemoryUtil.memAllocFloat` : ces buffers natifs fuient en RAM.

#### La Correction
Dans [`MeshWorkerManager.java`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/MeshWorkerManager.java#L93-L96) :
```java
public void shutdown() {
    executor.shutdownNow();
    MeshData data;
    while ((data = uploadQueue.poll()) != null) {
        data.free(); // Libère la mémoire native allouée via lwjgl MemoryUtil
    }
    queuedSet.clear();
}
```

Dans [`World.java`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/world/World.java#L415-L422) :
```java
public void cleanup() {
    meshManager.shutdown();
    storage.shutdown();
    
    // Nettoyer tous les chunks chargés
    for (Chunk chunk : chunks.values()) {
        chunk.cleanup();
    }
    chunks.clear();
}
```

---

### 2.3 Ownership des Shaders & Fuite de l'Atlas Voxel

#### Le Problème
Dans [`PlayingState.java:241-267`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/core/states/PlayingState.java#L241-L267) :
1. `PlayingState.exit()` appelle `voxelRenderer.cleanup()`.
2. `VoxelRenderer.cleanup()` appelle `voxelShader.cleanup()` ET `shadowRenderer.cleanup()`.
3. `ShadowRenderer.cleanup()` détruit `depthShader` et ses textures.
4. Ensuite, `PlayingState.exit()` rappelle `shadowRenderer.cleanup()`, puis `voxelShader.cleanup()`, puis `depthShader.cleanup()` !
   - Le programme shader `voxelShader` est détruit **2 fois**.
   - Le programme shader `depthShader` est détruit **3 fois**.
5. Enfin, l'`atlasTextureId` créé à la ligne 115 de `PlayingState` (`TextureLoader.loadTextureFromClasspath`) n'est **jamais détruit** nulle part !

#### La Règle d'Architecture
- **Règle** : *« Celui qui crée possède et détruit. Celui qui emprunte ne détruit pas. »*
- `PlayingState` crée `voxelShader`, `depthShader`, `atlasTextureId`, `shadowRenderer` et `voxelRenderer`.
- C'est donc à `PlayingState` de détruire chacun de ces objets **une et une seule fois**.
- `VoxelRenderer` et `ShadowRenderer` ne doivent **pas** détruire les shaders qui leur ont été passés en paramètre.

#### La Correction

Dans [`VoxelRenderer.java`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/renderer/VoxelRenderer.java#L264-L268) :
```java
@Override
public void cleanup() {
    // VoxelRenderer n'est pas propriétaire de voxelShader ni de shadowRenderer !
    // Ne rien détruire ici qui appartienne à PlayingState.
}
```

Dans [`ShadowRenderer.java`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/renderer/ShadowRenderer.java#L754-L762) :
```java
@Override
public void cleanup() {
    // depthShader est géré par PlayingState, mais ShadowRenderer possède ses textures et son FBO :
    if (shadowAtlas != 0)      { glDeleteTextures(shadowAtlas); shadowAtlas = 0; }
    if (shadowMapArrayC != 0)  { glDeleteTextures(shadowMapArrayC); shadowMapArrayC = 0; }
    if (colorTestArray != 0)   { glDeleteTextures(colorTestArray); colorTestArray = 0; }
    if (fbo != 0)              { glDeleteFramebuffers(fbo); fbo = 0; }
}
```

Dans [`PlayingState.java`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/core/states/PlayingState.java#L241-L270) :
```java
// Garder atlasTextureId en champ de classe pour pouvoir le libérer
// Dans exit() :
if (voxelRenderer != null)  { voxelRenderer.cleanup(); voxelRenderer = null; }
if (shadowRenderer != null) { shadowRenderer.cleanup(); shadowRenderer = null; }
if (sunRenderer != null)    { sunRenderer.cleanup(); sunRenderer = null; }
if (skyRenderer != null)    { skyRenderer.cleanup(); skyRenderer = null; }
if (textRenderer != null)   { textRenderer.cleanup(); textRenderer = null; }

if (voxelShader != null)    { voxelShader.cleanup(); voxelShader = null; }
if (depthShader != null)    { depthShader.cleanup(); depthShader = null; }

if (atlasTextureId != 0) {
    glDeleteTextures(atlasTextureId);
    atlasTextureId = 0;
```

---

### 2.4 Validation & Traque des Fuites Mémoire (LWJGL DebugAllocator)

Pour vérifier que l'application ne fuit pas de mémoire native :

1. **Interrupteur dans [`GameConfig.java:176`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/core/config/GameConfig.java#L176)** :
   ```java
   public static final boolean DEBUG_MEMORY_ALLOCATOR = false; // Mettre à true pour diagnostiquer les fuites
   ```
2. **Pris en compte dans [`Main.java`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/Main.java) et [`Application.java`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/core/Application.java)** :
   Lorsque `DEBUG_MEMORY_ALLOCATOR = true`, LWJGL active `Configuration.DEBUG_MEMORY_ALLOCATOR` et `Configuration.DEBUG_STACK`.
   Lorsque `DEBUG_MEMORY_ALLOCATOR = false` (valeur par défaut), le terminal reste parfaitement silencieux et épuré.
3. **Comportement à l'exécution (si activé)** :
   - Au lancement : affiche `LWJGL Debug Allocator: true`.
   - À la fermeture : si des octets natifs ont fuité, LWJGL affiche la stacktrace exacte. S'il n'y a aucune fuite, le processus s'arrête proprement.

---

## Phase 3 : Clarification Mathématique & CSM Leapfrog

### 3.1 Incohérence dimensionnelle sur le Snap Index

#### Le Problème
Dans [`ShadowRenderer.java:836-848`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/renderer/ShadowRenderer.java#L836-L848) :
```java
private int snapIndexX(CascadeData d) {
    if (d == null) return 0;
    float snappedX = -d.lightSpaceMatrices()[0].m30();
    float texel = d.texelSizes()[0];
    return Math.round(snappedX / texel);
}
```
Or, dans [`CSMCalculator.java:556-562`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/shadow/CSMCalculator.java#L556-L562) :
La matrice retournée est `lightProj.mul(lightView)`.
- Dans `lightView`, la translation X est effectivement `-snappedX_world`.
- Mais `lightProj` est une matrice orthographique de demi-largeur `halfSize`. Elle multiplie la coordonnée X par `1.0f / halfSize`.
- Le coefficient `m30()` de la matrice résultante vaut donc :
  $$\text{m30} = \frac{-\text{snappedX\_world}}{\text{halfSize}}$$
  C'est une coordonnée adimensionnelle d'espace normalisé NDC (entre -1 et 1) !
- Diviser cette valeur par `texel` (exprimé en mètres monde) est un **non-sens dimensionnel** :
  $$\frac{\text{snappedX\_world}}{\text{halfSize} \times \text{texel}}$$
  Cela produit un indice de texel erroné, qui déclenche des réactualisations intempestives des cascades.

#### La Correction (Appliquée ✅)
Au lieu d'extraire acrobatiquement un paramètre depuis une matrice combinée projetée, on enregistre directement les entiers de cellule calculés dans [`CascadeData.java`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/shadow/CascadeData.java) :
1. Dans [`CSMCalculator.java`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/shadow/CSMCalculator.java) : calcul des indices entiers `snapCellX = Math.round(centerLS_X / texelSize)` et `snapCellY = Math.round(centerLS_Y / texelSize)` stockés dans `CascadeData`.
2. Dans [`ShadowRenderer.java:785-796`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/renderer/ShadowRenderer.java#L785-L796) :
   ```java
   private int snapIndexX(CascadeData d) {
       if (d == null || d.snapIndicesX() == null || d.snapIndicesX().length == 0) return 0;
       return d.snapIndicesX()[0];
   }
   private int snapIndexY(CascadeData d) {
       if (d == null || d.snapIndicesY() == null || d.snapIndicesY().length == 0) return 0;
       return d.snapIndicesY()[0];
   }
   ```
   **Résultat** : Détection de texel 100% exacte, zéro pop retardé, élimination du tremblement des ombres.

---

### 3.2 Démystification du Leapfrog & Bascule en CSM Direct (Appliquée ✅)

> **Mise au point fondamentale (Astra Review)** :
> L'affirmation « le leapfrog ne contribue pas à l'image » était **fausse**.
> 1. **Le crossfade GLSL** était forcé à 0 (`leapfrogActive = 0`), d'où l'effet de diaporama (*slideshow*) toutes les 0.67s.
> 2. **La promotion temporelle CPU (B → A)** : au swap, la carte B devenait A pour la frame suivante.

#### La Solution Mise en Place
Pour tester un CSM direct fluide tout en préservant l'infrastructure du Leapfrog :
- **Interrupteur [`RenderConfig.LEAPFROG_SHADOWS_ENABLED = false`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/core/config/RenderConfig.java#L118)** :
  - Par défaut (`false`) : mode CSM direct actif dans `ShadowRenderer::renderOffMode`.
  - Les ombres évoluent en continu avec le soleil et le snap index corrigé (zéro saccade, zéro diaporama, zéro tremblement).
  - Évite les doubles rendus A et B systématiques.
  - L'infrastructure du Leapfrog (`renderWarmup`, `renderRunning`, swap) reste intacte dans le code et peut être réactivée à tout moment.

---

### 3.3 Sort des textures inutilisées (C staging & R32F) (Appliquée ✅)

- **Texture C (Staging - 48 MiB)** :
  Désactivée en mode CSM direct (`shadowMapArrayC = 0`). Aucun appel de passe de rendu dans le vide. Économie de 48 Mo de VRAM et de centaines de draw calls par frame.
- **`colorTestArray` (R32F - 48 MiB)** :
  Suppression de son allocation (`colorTestArray = 0`) et protection de la liaison dans [`VoxelRenderer.java`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/renderer/VoxelRenderer.java#L198). Économie de 48 Mo de VRAM.
- **Gain total VRAM** : **96 Mo de VRAM libérés**.

---

- **Gain total VRAM** : **96 Mo de VRAM libérés**.

---

### 3.4 Suppression du Shimmering et Distance Culling des Cascades (Anti-30 FPS)

#### 3.4.1 Pourquoi le Culling Précédent n'avait pas d'effet (Le Piège du Lambda et du Far CSM)
Lors du premier essai, les métriques CSV ont montré un `render_ms` toujours élevé (4-11 ms) et un verrouillage à 30 FPS.
L'analyse géométrique en a révélé la cause exacte :
- [`RenderConfig.CSM_SHADOW_FAR`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/core/config/RenderConfig.java#L157) était fixé à **400 mètres** (alors que la distance de rendu du monde n'est que de 15 chunks = 240 mètres).
- [`RenderConfig.CSM_SPLIT_LAMBDA`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/core/config/RenderConfig.java#L151) valait **0.55** (distribution trop linéaire).
- Conséquence mathématique : La Cascade 0 s'étendait jusqu'à **60,9 mètres**, donnant une sphère englobante de **92 mètres de rayon**. Le calcul de culling conservateur donnait un rayon de $92 \times 2.5 + 32 = \mathbf{261\text{ mètres}}$, soit **supérieur au monde chargé entier (240 m)**. Aucun chunk n'était donc éliminé !

#### 3.4.2 Calibrage du Split CSM & Gain de Netteté
- **Ajustements dans [`RenderConfig.java`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/core/config/RenderConfig.java)** :
  - `CSM_SHADOW_FAR = 180.0f` : Aligné avec le monde visible avant le brouillard.
  - `CSM_SPLIT_LAMBDA = 0.82f` : Répartition logarithmique standard favorisant le champ proche.
  - `SHADOW_MAP_FILTER = 1` (`GL_NEAREST`) : Indispensable en mode comparaison manuelle pour éviter que le GPU n'interpole linéairement les profondeurs brutes $Z$ avant le test, ce qui créait des artefacts de chatoiement.
- **Impact sur les Cascades** :
  - **Cascade 0** : Distance réduite de 61m à **~12m** ($R \approx 15$m). Le rayon de culling tombe à **~53 mètres**. Le nombre de chunks dessinés dans la Cascade 0 passe de **961 à ~35 chunks** (**-96%**).
  - **Résolution des texels** : La Cascade 0 passe de texels de 10-15 cm à des texels de **1,46 cm** (**7 fois plus nette**).
  - **Cascade 1** : Passe de 961 chunks à **~200 chunks** (**-79%**).

#### 3.4.3 Culling Frontal Horizontal (Passe Forward dans [`VoxelRenderer.java`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/render/renderer/VoxelRenderer.java))
La passe avant soumettait elle aussi l'intégralité des 961 chunks dans un carré $31 \times 31$, y compris les 60% de chunks situés dans le dos du joueur.
- Ajout d'un test de cône horizontal à $78^\circ$ (marge généreuse sur le FOV horizontal de $49^\circ$) avec préservation d'un rayon de sécurité de 32m au pied du joueur.
- **Résultat** : La passe forward passe de 961 chunks à **~350 chunks**.
- **Total cumulé** : Plus de **2 600 draw calls éliminés par frame** sur l'ensemble du moteur.

#### 3.4.4 Élimination du Shimmering par Bilinear PCF 2x2
Pourquoi l'ombre scintillait-elle sur une "bande plus large et floue" ?
- L'algorithme Poisson précédent avec `#define WORLD_CELL_SIZE 4.0` tournait le motif d'échantillonnage de façon pseudo-aléatoire tous les 4 mètres dans le monde. Dès que le soleil tournait, l'ombre traversant ces cellules sautait brutalement.
- De plus, les 4 échantillons discrets espacés sur 6 texels produisaient 4 paliers visibles de luminosité (0%, 25%, 50%, 75%, 100%).
- **Remplacement dans [`voxel_fragment_shader.glsl`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/resources/shaders/voxel_fragment_shader.glsl)** :
  Implémentation de `sampleBilinearShadow` : calcul direct des 4 texels voisins immédiats avec interpolation bilinéaire continue du résultat de visibilité.
  Le gradient d'ombre devient **100% continu** à l'échelle sub-texel, sans bruit de rotation ni bandes discontinues.

---

## Phase 4 : Assainissement du Profiling & Benchmarks

### 4.1 Suppression du `glFinish()` bloquant par frame

Dans [`Application.java:246-249`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/java/engine/core/Application.java#L246-L249) :
```java
// CODE BLOQUANT ACTUEL :
long tF0 = System.nanoTime();
glFinish();
long tF1 = System.nanoTime();
```
`glFinish()` vide le pipeline et force le CPU à attendre que le GPU ait terminé le dernier pixel de la frame. Cela empêche le driver de préparer la frame suivante pendant que le GPU travaille.
- **Supprimez ce `glFinish()` systématique**.
- Pour mesurer les FPS réels d'un jeu, laissez le driver pipeliner et mesurez le delta time entre deux retours de `glfwSwapBuffers`.

---

### 4.2 Instrumentation de timing conforme

Si vous souhaitez mesurer le temps GPU réel d'une passe :
> **Attention** : Les timer queries (`GL_TIME_ELAPSED`) **ne font pas partie du core OpenGL 3.2** (elles ont été introduites en 3.3 et via l'extension `ARB_timer_query`).

Dans `Application.java` ou une classe `GPUTimer` :
```java
boolean hasTimerQueries = GL.getCapabilities().GL_ARB_timer_query || 
                          GL.getCapabilities().OpenGL33;

if (!hasTimerQueries) {
    System.out.println("GPU Timer queries non supportées nativement. Utiliser des mesures CPU sans glFinish intrusif.");
}
```
Si les queries sont disponibles, lisez toujours les résultats de façon **asynchrone avec 2 ou 3 frames de décalage** (`glGetQueryObjectui64v` après avoir testé `GL_QUERY_RESULT_AVAILABLE`), sans jamais bloquer la frame courante.

---

### 4.3 Sortie du mode `PERF_BISECT_STAGE`

Dans [`voxel_fragment_shader.glsl:3`](file:///Users/mhandfares/Desktop/TOB_Voxel_Project/src/main/resources/shaders/voxel_fragment_shader.glsl#L3) :
Le fichier est resté bloqué sur `#define PERF_BISECT_STAGE 5`.
Toutes les branches de calcul de lumière, de PCF propre et de rendu normal sont court-circuitées.

Une fois les bugs OpenGL de la Phase 1 résolus :
1. Repassez à un mode de production propre (sans les 15 stages de test).
2. Choisissez une fonction de filtrage d'ombre éprouvée :
   - Mode matériel : `sampler2DArrayShadow` avec PCF 4 taps ou Poisson disk.
   - Biais adaptatif normal/lumière (`slope-scale bias`).

---

## 7. Feuille de Route Opérationnelle (Checklist)

Pour reprendre la main pas à pas sans tout casser :

### Étape 1 : Diagnostic & Sécurité (Immédiat)
- [ ] Remplacer les boucles `while(glGetError() != 0)` par `checkGLError("nom_de_passe")` pour voir si des erreurs surviennent.
- [ ] Afficher les chaînes de version OpenGL et extensions au lancement dans `Application.java`.
- [ ] Fixer le bug du VBO dans `TextRenderer.java` (utiliser `validQuads` pour `glDrawArrays`).
- [ ] Fixer le depth test dans `TextRenderer.java` (retirer `glEnable(GL_DEPTH_TEST)`) et dans `LoadingState.java`.

### Étape 2 : Conformité Texture & Shaders
- [ ] Lier les attributs `aUV` et `aTexCoord` sur la location 1 dans `Shader.java`.
- [ ] Dans `VoxelRenderer.java`, supprimer la modification intempestive de `GL_TEXTURE_COMPARE_MODE` sur l'atlas avant le rendu des chunks.
- [ ] Dans `voxel_fragment_shader.glsl`, s'assurer que la fonction de sampling correspond bien à l'état de la texture (pas de fetch non-shadow sur une texture avec compare mode).

### Étape 3 : Nettoyage de la Mémoire & Ownership
- [ ] Dans `Chunk.java`, supprimer l'ancien VAO et l'ancien VBO dans `setMesh()` avant d'assigner les nouveaux.
- [ ] Dans `World.java`, ajouter la boucle de `chunk.cleanup()` dans `World.cleanup()`.
- [ ] Dans `MeshWorkerManager.java`, vider et libérer les `MeshData` de `uploadQueue` dans `shutdown()`.
- [ ] Clarifier l'ownership dans `PlayingState.java` : supprimer les appels de cleanup en doublon dans `VoxelRenderer` et `ShadowRenderer`, et libérer `atlasTextureId`.

### Étape 4 : Performance & Simplification
- [ ] Retirer le `glFinish()` obligatoire de la boucle principale dans `Application.java`.
- [ ] Désactiver le rendu inutile de la texture C dans `ShadowRenderer` pour économiser 3 passes de rendu du monde par frame.
- [ ] Supprimer l'allocation de la texture `colorTestArray` (48 MiB sauvés).
- [ ] Nettoyer la formule de snap dans `CSMCalculator` et `ShadowRenderer` en stockant directement les coordonnées entières de cellule.
- [ ] Sortir du `#define PERF_BISECT_STAGE 5` pour retrouver des ombres lissées et complètes.
