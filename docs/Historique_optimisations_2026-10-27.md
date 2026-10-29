# Historique Complet des Optimisations & Architecture CSM / Voxel Meshing

**Date :** 2026-10-27  
**Plateforme de référence :** Apple M2 Pro (12 cœurs, GPU 19 cœurs), macOS, OpenGL 4.1 Metal  
**Résolution de test :** Fenêtre logique `1134×691` (Scale `0.75`), Framebuffer Retina `2268×1382`, `renderDistance = 16`  
**Protocole de mesure :** Harnais déterministe [`TestHarness.java`](../src/main/java/engine/test/TestHarness.java) (20 secondes, trajectoire mobile sur `World 10`, campagnes croisées ABBA).

---

## 1. Synthèse de l'évolution des performances (de 41,2 FPS à 102,0 FPS)

Ce tableau consolide l'ensemble des campagnes de benchmarks automatisés réalisées sur le moteur depuis la baseline initiale avec Cascaded Shadow Mapping ($3 \times 2048^2$) jusqu'à l'introduction du Greedy Meshing visuel à AO 9-points confinée aux bords (Option 1) et du filtrage anti-shimmering :

| # | Étape d'optimisation | Flag / Propriété JVM | FPS Moyen | Frame Time | GPU Shadow Pass | GPU Voxel Pass | Sommets / Indices Voxel par frame | Mémoire Mesh (VRAM) | Gain & Analyse technique |
| :-: | :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :--- |
| **1** | **Baseline CSM initiale** *(Sept. 2026)* | — | **41,2 FPS** | 24,3 ms | ~11,0 ms | ~13,0 ms | 6 460 000 | ~676 Mo | `glDrawArrays` non indexé : **43,3 M** de sommets shadow et **6,46 M** de sommets voxel soumis à chaque frame. |
| **2** | **Frustum Culling Shadow par cascade** | `-Dvoxel.shadowFrustumCulling=true` | **45,1 FPS** | 22,1 ms | 8,90 ms | ~13,0 ms | 6 460 000 | ~676 Mo | Rejet des chunks hors du volume orthographique de chaque cascade : **-26,3 %** de sommets shadow (`43,3 M → 31,9 M`). |
| **3** | **Tight AABB Mesh Bounds** | `-Dvoxel.tightShadowBounds=false` `-Dvoxel.tightCameraBounds=false` | **45,2 FPS** | 22,1 ms | 8,90 ms | ~13,0 ms | 6 460 000 | ~676 Mo | Gain négligeable (`+0,15 %`) en trajectoire mobile : laissés désactivés par défaut, mais conservés pour le calcul du volume Z des casters. |
| **4** | **Indexation des Quads par EBO (`uint32`)** *(23 oct. 2026)* | `-Dvoxel.indexedMeshes=true` | **58,7 FPS** *(+34 %)* | 17,0 ms | 7,40 ms | 8,81 ms | 6 460 000 *(indices)* | **526 Mo** *(-150 Mo)* | Passage de 6 à 4 sommets uniques par face (`glDrawElements`), réutilisation du cache post-transformation GPU. |
| **5** | **Cache des emplacements d'uniformes** *(23 oct. 2026)* | `-Dvoxel.cacheUniforms=false` | **61,2 FPS** | 16,3 ms | 7,40 ms | 8,80 ms | 6 460 000 | 526 Mo | Impact neutre (`-0,06 %`), le driver Metal cachant déjà les locations au niveau interne. |
| **6** | **Format de sommets compact (24 octets)** *(23 oct. 2026)* | `-Dvoxel.compactVertices=true` | **65,2 FPS** *(+6,5 %)* | 15,3 ms | 7,95 ms | 8,88 ms | 6 460 000 | **~410 Mo** | Réduction du stride VBO de 36 à 24 octets (`3×ushort pos + 3×byte normal + 2×float UV + 1×float AO`). |
| **7** | **Greedy Shadow Mesh sans T-junctions** *(24–26 oct. 2026)* | `-Dvoxel.greedyShadows=true` | **79,7 FPS** *(+22,2 %)* | 12,55 ms | **4,82 ms** | 8,88 ms | 6 460 000 | ~465 Mo | Fusion gloutonne 2D des faces shadow coplanaires + triangulation par ear-clipping à bords unitaires : **-48 %** de sommets shadow (`31,9 M → 16,5 M`). |
| **8** | **Résidence directionnelle des Casters** *(26 oct. 2026)* | `-Dvoxel.shadowCasterResidency=true` | **75,3 FPS** *(-6,1 %)* | 13,29 ms | 5,45 ms | 9,50 ms | 6 265 860 | ~779 Mo | Maintien en mémoire des chunks porteurs d'ombre en amont solaire (allégés via `ShadowCasterChunkFilter` : `ShadowMesh` seul, sans VBO visuel ni lightMap). Coût maîtrisé à **+0,8 ms/frame**. |
| **9** | **Fondu d'ombres par distance rayon** *(26 oct. 2026)* | `-Dvoxel.shadowFade=true` | **74,3 FPS** | 13,46 ms | 5,58 ms | 9,57 ms | 6 265 860 | ~779 Mo | Atténuation continue `smoothstep(160, 256)` de la portée rayon en blocs : élimine les coupures d'ombres rasantes à coût GPU nul. |
| **10** | **Greedy Meshing Visuel v1 (`Uniform-AO`) seul** *(27 oct. 2026)* | `-Dvoxel.greedyVisual=true` `-Dvoxel.shimmerFix=false` | **84,2 FPS** *(+21,5 %)* | **11,88 ms** | ~5,4 ms | **~6,9 ms** | **4 538 182** *(-27,6 %)* | **593,9 Mo** *(-185,3 Mo)* | Fusion gloutonne 2D des faces visuelles partageant la même tuile d'atlas et un AO uniforme + répétition UV intra-tuile dans le shader. |
| **11** | **Greedy Visuel v1 + Anti-Shimmering CSM** *(27 oct. 2026)* | `-Dvoxel.greedyVisual=true` `-Dvoxel.shimmerFix=true` | **78,2 FPS** *(+12,9 %)* | **12,79 ms** | ~5,4 ms | **~7,8 ms** | **4 530 796** *(-27,7 %)* | **593,9 Mo** *(-185,3 Mo)* | Le gain du Greedy Visuel v1 (`+2,56 ms`) finance intégralement le filtre PCF 4-tap bilinéaire $3\times 3$ (`0,91 ms`) et la stabilisation Z des cascades. |
| **12** | **Greedy v2.5 (`Exact 9-Pt Boundary AO`) + Anti-Shimmering** *(29 oct. 2026 — Nouveau Défaut)* | `-Dvoxel.greedyBoundaryAO=true` `-Dvoxel.greedyFullBoundaryAO=false` | **96,4 FPS** *(+39,2 % / moy. inst. 100,5 FPS, pic 166,2 FPS)* | **10,37 ms** | ~5,2 ms | **~5,1 ms** | **2 451 817** *(-60,9 %)* | **391,2 Mo** *(-388,0 Mo)* | Encodage 19-bit de la grille AO $3\times 3$ (4 coins, 4 bords, intérieur) dans `aAO` + évaluation ALU bornée à 1 bloc dans le fragment shader : fusionne les marches et coins rentrants avec **0 % d'erreur d'AO** sur chaque bloc. |
| **13** | **Greedy v3 + Option 1 (`Full Boundary-Clamped AO`) + Anti-Shimmering** *(29 oct. 2026 — Optionnel)* | `-Dvoxel.greedyBoundaryAO=true` `-Dvoxel.greedyFullBoundaryAO=true` | **102,0 FPS** *(+47,3 % / moy. inst. 106,1 FPS, pic 181,7 FPS)* | **9,80 ms** | ~5,1 ms | **~4,7 ms** | **2 147 739** *(-65,7 %)* | **361,7 Mo** *(-417,5 Mo)* | Fusion géométrique maximale (**-65,85 %** de quads) avec échantillonnage min des bords (peut étirer l'AO le long d'un ruban `W×1` si seule une extrémité touche une marche diagonale). |

---

## 2. Comparaison détaillée des passes Greedy Meshing & Anti-Shimmering (`World 10`, 20s)

Mesures directes issues du harnais [`TestHarness`](../src/main/java/engine/test/TestHarness.java) sur le même monde déterministe (`World 10`) :

| Configuration | Frames (20s) | FPS Moyen (`N/T` / `avg inst.`) | Frame Time | Indices Voxel / frame (`renderedVertices`) | Sommets en VRAM (`storedVertices`) | Mémoire Mesh (`meshMB`) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **A. Référence Legacy** (`-Dvoxel.greedyVisual=false -Dvoxel.shimmerFix=false`) | 1 367 | **69,25 FPS** | 14,44 ms | 6 265 860 | 21 919 022 | **779,2 Mo** |
| **B. Greedy Visual v1 seul (`Uniform-AO`)** (`-Dvoxel.greedyVisual=true -Dvoxel.shimmerFix=false`) | 1 665 | **84,16 FPS** *(+21,5 %)* | 11,88 ms *(-2,56 ms)* | 4 538 182 *(-27,6 %)* | 15 442 619 *(-29,5 %)* | **593,9 Mo** *(-185,3 Mo)* |
| **C. Greedy Visual v1 + Shimmer Fix** (`-Dvoxel.greedyBoundaryAO=false -Dvoxel.greedyUniformAO=true`) | 1 525 | **78,18 FPS** *(+12,9 %)* | 12,79 ms *(-1,65 ms)* | 4 530 796 *(-27,7 %)* | 15 442 828 *(-29,5 %)* | **593,9 Mo** *(-185,3 Mo)* |
| **D. Greedy v2.5 (`Exact 9-Pt Boundary AO`) + Shimmer Fix** (`-Dvoxel.greedyFullBoundaryAO=false`) | 1 929 | **96,39 FPS** / **100,54 FPS** *(+39,2 %)* | **10,37 ms** *(-4,07 ms)* | **2 451 817** *(-60,9 %)* | **8 359 274** *(-61,9 %)* | **391,2 Mo** *(-388,0 Mo)* |
| **E. Greedy v3 + Option 1 (`Full Boundary-Clamped AO`) + Shimmer Fix (Défaut)** | **2 042** | **102,03 FPS** / **106,10 FPS** *(+47,3 %)* | **9,80 ms** *(-4,64 ms)* | **2 147 739** *(-65,7 %)* | **7 326 747** *(-66,6 %)* | **361,7 Mo** *(-417,5 Mo)* |

---

## 3. Explication technique des deux dernières optimisations

### 3.1 Greedy Meshing Visuel sans altération de l'AO ni du VBO compact (`MeshBuilder.java`)
1. **Préservation exacte de l'Ambient Occlusion par sommet** :
   - Une fusion gloutonne naïve qui fusionnerait des faces ayant un gradient d'AO aux 4 coins étirerait l'ombre de coin sur toute la largeur du rectangle fusionné.
   - Dans [`MeshBuilder.mergeVisualQuads`](../src/main/java/engine/render/MeshBuilder.java), seules les faces coplanaires dont les 4 coins ont un AO uniforme (`ao0 == ao1 == ao2 == ao3`) et identique au voisin sont fusionnées en rectangles $W \times H$. Les faces situées contre une arête concave conservant un gradient d'AO restent des quads $1\times 1$ exacts.
2. **Répétition de texture intra-tuile dans l'atlas $64\times 64$ (0 octet de VBO supplémentaire)** :
   - Sans toucher au layout compact de 24 octets ([`MeshVertexLayout.java`](../src/main/java/engine/render/MeshVertexLayout.java)), l'attribut `aTex` encode à la fois l'indice de la tuile dans l'atlas et les coordonnées locales en blocs :
     $$\text{aTex} = \text{tileIdx} \times 512 + (u_{\text{local}}, v_{\text{local}}) \in [\varepsilon, W-\varepsilon] \times [\varepsilon, H-\varepsilon]$$
   - Le vertex shader extrait `flat out vec2 vTileOrigin = floor(aTex / 512.0) / 64.0` et transmet la coordonnée locale `vTexCoord` au fragment shader, qui effectue un bouclage périodique `vTileOrigin + clamp(fract(vTexCoord), 0.002, 0.998) / 64.0`.

### 3.2 Stabilisation CSM & Anti-Shimmering (`CSMCalculator.java`, `ShadowCasterVolume.java`, `voxel_fragment_shader.glsl`)
L'analyse des cascades éloignées (Cascade 1 : $1\text{ texel} \approx 12,1\text{ cm}$, Cascade 2 : $1\text{ texel} \approx 40,2\text{ cm}$) a mis en évidence trois sources distinctes de *shimmering* :
1. **Verrouillage XY (`texelSize` snapping)** :
   - Le centre de la sphère englobante de la Cascade 2 étant situé à $d_2 = 130,25\text{ m}$ devant la caméra ($\mathbf{C}_{\text{WS}} = \mathbf{P}_{\text{cam}} + d_2 \hat{F}_{\text{cam}}$), une simple rotation de la caméra sur place déplace $\mathbf{C}_{\text{WS}}$ de $2,27\text{ m}$ ($5,65\text{ texels}$) par degré de lacet. Le snap `Math.round(centerLS / texelSize) * texelSize` est donc indispensable pour maintenir la phase sub-texel des blocs constante lors des rotations caméra.
2. **Invariance en rotation de la plage de profondeur Z (`minZ` / `maxZ` et `ShadowCasterVolume`)** :
   - Auparavant, `sphereCenterZ = sphereCenterWS.dot(lightDir)` utilisait le centre déporté $\mathbf{C}_{\text{WS}}$, qui variait de $\pm 130\text{ m}$ lorsque la caméra pivotait sur place, tandis que [`ShadowCasterVolume.apply`](../src/main/java/engine/render/shadow/ShadowCasterVolume.java) recalculait un facteur d'échelle Z continu à chaque frame.
   - Avec `-Dvoxel.shimmerFix=true`, la plage Z est désormais ancrée sur `camPos.dot(lightDir)` (100 % invariant lorsque la caméra pivote sur place) et quantifiée par pas de 16 blocs, tandis que l'extension `near` de `ShadowCasterVolume` est quantifiée par pas de $1/8$, éliminant toute fluctuation de profondeur pendant la rotation caméra.
3. **Filtre PCF 4-tap bilinéaire $3\times 3$ (pondération tente continue) et fondu inter-cascades élargi** :
   - Dans [`voxel_fragment_shader.glsl`](../src/main/resources/shaders/voxel_fragment_shader.glsl), `shimmerFix=1` active l'évaluation de 4 échantillons bilinéaires $2\times 2$ décalés de $\pm 0,5\text{ texel}$ (couvrant une empreinte continue de $3\times 3 = 9\text{ texels}$ avec pondération tente $C^0$) et élargit la bande de transition inter-cascades à $15\,\%$ de la distance de split (~9 m entre Cascade 1 et Cascade 2).

### 3.3 Coexistence du Greedy v3 et de l'Ambient Occlusion : Grille AO $3\times 3$ Confinée aux Bords par ALU (`Option 1`)
1. **Théorème d'invariance intérieure de l'AO voxel** :
   - Sur tout rectangle fusionné $W \times H$ de faces visibles, les 4 cellules entourant un sommet strictement intérieur ($0 < u < W$, $0 < v < H$) ont nécessairement un bloc d'air devant elles (sans quoi la face serait occultée et absente du rectangle). Par conséquent, **tout sommet strictement intérieur d'un rectangle fusionné possède mathématiquement $\text{AO} = 1,0$**. L'Ambient Occlusion ne peut varier que sur le périmètre extérieur d'épaisseur 1 bloc (les 4 coins et les 4 bords).
2. **Encodage sans perte sur 19 bits dans `aAO` et dimensions $(W, H)$ dans `aTex` (0 octet de VBO ajouté)** :
   - Sans modifier le format compact de 24 octets par sommet ([`MeshVertexLayout.java`](../src/main/java/engine/render/MeshVertexLayout.java)), [`MeshBuilder.mergeVisualQuadsBoundaryAO`](../src/main/java/engine/render/MeshBuilder.java) packe les 9 valeurs d'AO de la grille de contrôle $3\times 3$ du rectangle ($c_{00}, c_{10}, c_{11}, c_{01}, e_{v0}, e_{v1}, e_{u0}, e_{u1}, a_{\text{int}}$, 2 bits chacune) dans la mantisse entière exacte 24 bits du `float aAO`, ainsi que $(W, H)$ dans les bits de poids fort (`>> 15`) de `aTex`.
3. **Évaluation ALU sans branchement dans [`voxel_fragment_shader.glsl`](../src/main/resources/shaders/voxel_fragment_shader.glsl)** :
   - La fonction `evaluateAO()` calcule les poids bornés à $1,0\text{ bloc}$ des 4 bords (`wu0 = clamp(1.0 - u, 0.0, 1.0)`, `wu1 = clamp(u - (W - 1.0), 0.0, 1.0)`, `wuM = 1.0 - wu0 - wu1`) et évalue le produit bilinéaire $3\times 3$ en ~12 instructions ALU et **0 accès mémoire supplémentaire**, réduisant le nombre de triangles de **-65,85 %** (`865 484 → 295 533` quads sur 289 chunks) et portant les performances à **102,0 FPS de moyenne** (`106,1 FPS` instantané moyen, pics à **181,7 FPS**) pour **361,7 Mo** de VRAM.
