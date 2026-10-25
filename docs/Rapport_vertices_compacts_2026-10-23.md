# Vertices compacts sans perte — 23 octobre 2026

**Livré et activé par défaut.** ABBA mobile sans instrumentation : **61,40 → 66,44 FPS**, environ **+8,21 %** ; temps moyen **16,29 → 15,05 ms** (−7,59 %). Les deux runs compacts sont meilleurs que les deux témoins. Retour arrière : `-Dvoxel.compactVertices=false`, sans désactiver l'indexation.

## Périmètre

Nouvelle option `voxel.compactVertices`. Indexation maintenue active ; cache d'uniforms et AABB précises désactivés pour la comparaison. Aucun changement de shader, triangles, indices, UV, AO, matrices, biais ou résolution.

Le format GPU passe de 36 à **24 octets par vertex** :

| Attribut | Offset | Stockage | Conversion GL |
|---|---:|---|---|
| Position XYZ | 0 | 3 × unsigned short | Float, sans normalisation |
| Normale XYZ | 6 | 3 × signed byte | Float, sans normalisation |
| Padding | 9 | 3 octets zéro | Non consommé |
| UV | 12 | 2 × float32 | Inchangé |
| AO | 20 | float32 | Inchangé |

Les coordonnées locales actuelles sont entières (incluant Y=256), les normales valent −1/0/+1. Ces valeurs sont représentables exactement dans les deux formats. Le packer refuse les positions fractionnaires, hors plage ou non finies et les normales incompatibles ; il refuse également −0 pour ne pas annoncer une équivalence binaire qui changerait son signe. Une évolution vers des meshes déformés devra conserver le chemin float ou revoir ce contrat, pas tronquer silencieusement les valeurs.

Pour un quad indexé : **168 → 120 octets VBO+EBO**, soit **−28,57 %**. Le VBO seul baisse de 33,33 %. Ni le nombre de vertices stockés, ni les indices, ni les draws ne diminuent dans cette expérience.

## Flux et ownership

`MeshBuilder::generateMeshData` produit d'abord le même buffer float et les mêmes bornes. `MeshVertexLayout::pack` le convertit sans changer position/limit. Le buffer float est libéré avant retour en mode compact ; `MeshData` possède alors le buffer compact et celui des indices. La file d'upload transporte ce résultat sans changer l'ownership.

`MeshVertexLayout::upload` définit explicitement les deux layouts et est partagé par les voies worker et directe. Les deux passes existantes utilisent le VAO installé sans avoir à connaître son layout. `Chunk::setMesh` reçoit le stride du même résultat ; `getMeshBytes` comptabilise ce stride, pas systématiquement 36. Les overloads legacy gardent 36.

**Compromis CPU :** un passage supplémentaire et une coexistence temporaire des buffers 36/24 octets pendant la conversion. La mémoire native en attente d'upload baisse, mais le pic de génération d'un mesh peut augmenter. Aucun gain de chargement ou de streaming n'est déduit des FPS. Une écriture compacte directe pourrait être une étape ultérieure, pas un changement cumulé dans cette expérience.

## Validation

- `MeshIndexTest` avec `voxel.compactVertices=true` : reconstruire les neuf attributs par les indices et comparer leurs bits au flux float non indexé ; cas vide, terrain avec AO, frontières et Y=256, puis 98 328 vertices stockés. PASS.
- Même test : comparaison couleur/depth avec trois projections orthographiques et une perspective, en utilisant le helper d'upload de production et un shader de test consommant les attributs. Couverture de pixels non vide imposée. PASS. Il ne s'agit pas d'un A/B des shaders CSM complets.
- `MeshBoundsGlTest` compact ON : vrai worker, remplacement bas/haut/bas, cleanup/ré-upload, voie directe et mesh vide. PASS.
- `CompactVerticesTest` : stride, curseur, signedness, précision UV/AO, positions invalides, normales non représentables et buffer vide.

La validation pixel compare les mêmes snapshots et matrices. Des captures de deux processus à des instants différents ne seraient pas équivalentes. La topologie et l'ordre des triangles sont conservés.

## Commandes

## Benchmark FPS

Apple M2 Pro, Retina **2268 × 1382**, World 10, stage 6 / filtre 1, trois cascades 2048². Caméra et soleil mobiles, fenêtre visible non minimisée sans focus, curseur normal. Ces états sont enregistrés dans les CSV. Pas de compilation simultanée, queries GPU, readbacks ou `glFinish` de diagnostic dans cette série.

Sélection `3 < somme(dt) ≤ 19 s` ; FPS calculés comme nombre de frames / somme de leurs durées. Les agrégations regroupent les durées, pas une moyenne des FPS instantanés. Les poses intégrées par dt diffèrent légèrement entre runs.

| Run / CSV | Compact | Frames retenues | FPS | Temps moyen | P95 |
|---|---|---:|---:|---:|---:|
| [A1](../logs/perf_test_World_10_2026-10-23_221336.csv) | OFF | 985 | 61,528 | 16,253 ms | 20 ms |
| [B1](../logs/perf_test_World_10_2026-10-23_221359.csv) | ON | 1 066 | 66,575 | 15,021 ms | 19 ms |
| [B2](../logs/perf_test_World_10_2026-10-23_221421.csv) | ON | 1 061 | 66,304 | 15,082 ms | 19 ms |
| [A2](../logs/perf_test_World_10_2026-10-23_221444.csv) | OFF | 981 | 61,267 | 16,322 ms | 20 ms |

Les données VBO/EBO installées dans le monde passent d'environ **526,0 Mo à 375,8 Mo**. C'est une comptabilité des buffers, pas la VRAM physique totale ; textures et overhead driver sont exclus. Le nombre de vertices stockés reste environ 12,53 millions et celui des références voxel soumises environ 6,45 millions/frame.

**Confiance moyenne sur le gain extrapolé, haute sur les tests effectués et l'économie de buffers.** Deux répétitions par variante ne couvrent pas les autres mondes, le cycle solaire complet, le streaming prolongé ni les conditions thermiques. Le P95 de 19 ms n'est pas une garantie de 60 FPS constants. Les artefacts de shadow mapping ne sont pas traités par ce changement.

## Reproduction

```sh
./gradlew --offline installDist
JAVA_OPTS='-Dvoxel.testHarness=true -Dvoxel.testStatic=false -Dvoxel.indexedMeshes=true -Dvoxel.compactVertices=true -Dvoxel.cacheUniforms=false -Dvoxel.gpuTiming=false' \
  build/install/TOB_Voxel_Project/bin/TOB_Voxel_Project
```

Pour le témoin, changer uniquement `compactVertices` en `false`. Harnais visible, non focalisé et sans capture du curseur. Les métadonnées CPU indiquent `compactVertices` et les octets installés ; les profils GPU indiquent `compact_vertices`. Les champs historiques de références soumises gardent leur sens.

Tests autonomes : compiler `tools/tests/MeshBoundsTest.java`, `MeshBoundsGlTest.java`, `MeshIndexTest.java` et `CompactVerticesTest.java` avec le classpath `build/classes/java/main:build/install/TOB_Voxel_Project/lib/*`, sortie `build/diagnostic-tests`. Exécuter les tests GL avec `-XstartOnFirstThread -Dvoxel.compactVertices=true` et le même classpath préfixé de `build/diagnostic-tests`, depuis un répertoire temporaire, comme dans le rapport d'indexation. Le test CPU `engine.render.CompactVerticesTest` ne requiert pas de contexte GL.
