# Indexation des quads et harnais discret — 23 octobre 2026

## Résultat

La [piste proposée](Piste_indexation_quads_2026-10-23.md) est implémentée. La comparaison mobile ABBA sans instrumentation donne **43,78 → 58,66 FPS**, soit **+34,0 % de FPS** et **−25,4 % de temps moyen de frame** (22,84 → 17,05 ms). Les deux essais indexés battent les deux témoins. **L'indexation est activée par défaut**, avec retour à l'ancien chemin par `-Dvoxel.indexedMeshes=false`.

Ce résultat concerne ce parcours et cette machine, pas tous les mondes. Le P95 indexé reste à 21–24 ms : ne pas promettre 60 FPS constants. Les AABB précises restent OFF. Aucun changement de shader, biais, résolution, cadence solaire ou géométrie des triangles.

## Changements

- `MeshBuilder::generateMeshData` : quatre vertices complets par quad et six indices uint32 `0,1,2,0,2,3`. L'overload explicite permet de produire les deux versions à partir du même état de blocs pour les tests. Le chemin non indexé demeure disponible.
- `MeshData` : propriétaire des buffers natifs vertex et index ; counts séparés ; libération des deux. Les bornes portent sur les vertices stockés. Les allocations de génération sont libérées en cas d'échec avant transfert.
- `MeshWorkerManager::uploadMeshesIfAny` et `MeshBuilder::buildChunkMesh` : EBO créé avec le VAO lié. Le binding EBO reste attaché au VAO.
- `Chunk::setMesh/cleanup` : propriétaire du nouvel EBO, remplacement/destruction et reset. Les overloads legacy détachent logiquement l'ancien chemin indexé et détruisent son EBO.
- `MeshBuilder::drawBoundChunk` : sélection `glDrawElements` / `glDrawArrays` selon les handles du mesh, pas selon un flag global. Utilisé par le draw voxel et `World::renderDepth`, y compris la référence sans culling.
- `getDrawCount()` : nombre de références effectivement soumises ; `getVertexCount()` : vertices stockés. Les compteurs historiques `renderedVertices`, `shadow_vertices`, `voxel_vertices` restent des **références soumises**, pas des invocations mesurées de vertex shader.

Chaque quad conserve deux triangles et six références : le nombre de draw calls, triangles et fragments attendus ne diminue pas. Le stockage passe de 216 octets à 168 octets par quad (144 vertex + 24 indices), **−22,22 %**. Le gain mémoire ne signifie pas mécaniquement le même gain FPS.

## Harnais moins gênant

`Application::init` applique `GLFW_FOCUSED=false` et `GLFW_FOCUS_ON_SHOW=false` uniquement en mode test. L'initialisation du curseur et `PlayingState::enter` choisissent `GLFW_CURSOR_NORMAL` dans ce mode. Le jeu normal conserve ses contrôles et sa capture. `Game::update` ignorait déjà les entrées humaines pendant le scénario automatique.

La fenêtre reste visible, à la même résolution, sans minimisation, rendu offscreen ou suppression du swap. Les hints évitent la demande de focus ; ils ne constituent pas une garantie de placement derrière toutes les fenêtres du système. Le harnais ne restaure pas le focus d'une autre application et ne force pas l'ordre des fenêtres.

**Vérifié dans toutes les lignes retenues des quatre benchmarks** : focus 0, visible 1, iconified 0, cursorMode 212993 (`GLFW_CURSOR_NORMAL`), framebuffer 2268 × 1382. Soleil non pausé et caméra mobile. Une fenêtre non focalisée reste un vrai rendu ; une occultation complète ou une politique de composition macOS peut toutefois influencer les mesures. On compare donc A/B dans ce même mode, pas aux chiffres focalisés de la veille.

## Métriques ajoutées

CSV CPU : `indexedMeshes`, `storedVertices`, `meshBytes`, `focused`, `visible`, `iconified`, `cursorMode`. `storedVertices` et `meshBytes` totalisent les meshes installés dans le monde, y compris ceux actuellement hors champ. `meshBytes` est une comptabilité VBO/EBO, **pas la VRAM physique totale** : ni textures, ni overhead driver, ni rétention interne ne sont comptés. Les anciennes colonnes restent compatibles.

CSV GPU : `indexed_meshes` en plus des compteurs et temps des quatre passes déjà présents. Le script `tools/analyse_aabb_logs.py` expose les nouveaux champs lorsqu'ils existent, sans casser l'analyse des anciens CSV. Aucun compteur n'est présenté comme une mesure du cache GPU.

## Tests

`MeshIndexTest`, monde temporaire et contexte caché :

1. Fixtures vide, terrain irrégulier avec AO calculée depuis les blocs, face haute Y=255 et limites du chunk, puis damier de **98 328 vertices stockés / 147 492 indices**, dépassant 65 536.
2. Reconstruction par les indices et comparaison **bit à bit des neuf floats** avec l'ancien flux. Vérification des indices et de l'identité des bornes.
3. Rendu du même snapshot avec trois projections orthographiques et une perspective ; comparaison couleur et profondeur. Shader de test utilisant position, UV, normale et AO. Ce test n'emploie pas les shaders CSM de production.
4. Alternance indexé/non-indexé, suppression de l'ancien EBO contrôlée avec `glIsBuffer`, cleanup, compteur natif revenu à zéro et aucune erreur GL.

`MeshBoundsTest` : tests CPU et 20 000 frusta toujours PASS. `MeshBoundsGlTest` avec indexation active : worker réel, upload, remeshing bas/haut/bas, nettoyage/re-upload, voie directe et mesh vide PASS.

Ces tests ne prétendent pas être une comparaison bit à bit de deux scènes complètes CSM de production. L'équivalence des flux d'attributs et des triangles est vérifiée ; les shaders actuels n'utilisent ni `gl_VertexID` ni `gl_PrimitiveID`. Un contrôle prolongé de tous les mondes et du streaming reste distinct.

Contrôle supplémentaire sur le build final, sans préciser `indexedMeshes` pour exercer son nouveau défaut : [console de validation](../logs/index_validation_2026-10-23.txt), [CSV exclu des benchmarks](../logs/perf_test_World_10_2026-10-23_215249.csv). Les validateurs existants passent : **12 shadow maps et 4 images caméra couleur/depth**, sans erreur GL signalée. Ils comparent le culling sur les mêmes meshes indexés, **pas les représentations indexée/non indexée**. Le scénario mobile termine normalement, soleil non pausé, temps final 0,03235.

Compilation finale `installDist` réussie ; test de snapshots relancé avec assertion de pixels réellement couverts (pour éviter une comparaison triviale de deux images vides), PASS. SHA-256 de `world.json` et `player.dat` identiques avant/après les essais. Aucun benchmark n'a été exécuté en parallèle d'un autre benchmark ou d'une compilation.

## Benchmarks sans instrumentation

Apple M2 Pro ; OpenGL Apple 4.1 ; Retina 2268 × 1382 ; fenêtre 75 % ; stage 6, filtre 1, trois cascades 2048² ; World 10 ; culling lumineux historique ON, AABB précises OFF. Tests séquentiels de 20 s, sans compilation concurrente, queries GPU, readbacks ni `glFinish` de diagnostic.

Fenêtre analysée : `3 < somme(dt) ≤ 19 s`. FPS = somme des frames / somme de leurs durées ; pas moyenne des FPS instantanés. Le parcours intégré par dt n'a pas exactement les mêmes poses à chaque frame. Les compteurs/timings CPU concernent toujours le rendu précédent.

| Run / CSV | Indexation | Frames retenues | FPS | Moyenne | P95 |
|---|---|---:|---:|---:|---:|
| [A1 — 21:48:36](../logs/perf_test_World_10_2026-10-23_214836.csv) | OFF | 689 | 43,05 | 23,23 ms | 28 ms |
| [B1 — 21:48:58](../logs/perf_test_World_10_2026-10-23_214858.csv) | ON | 963 | 60,23 | 16,60 ms | 21 ms |
| [B2 — 21:49:20](../logs/perf_test_World_10_2026-10-23_214920.csv) | ON | 913 | 57,09 | 17,52 ms | 24 ms |
| [A2 — 21:49:43](../logs/perf_test_World_10_2026-10-23_214943.csv) | OFF | 712 | 44,50 | 22,47 ms | 26 ms |

Moyennes des octets de meshes installés : **676,3–676,4 Mo** non indexés, **525,9–526,1 Mo** indexés (Mo décimaux). Les références voxel restent autour de **6,45 millions/frame** : la hausse de FPS ne vient pas d'une baisse volontaire du terrain dessiné. Le coût de génération/upload n'a pas été isolé par un timer dédié ; ces résultats ne prouvent pas l'absence de pics de streaming.

**Confiance moyenne sur la taille exacte du gain, élevée sur la réduction des buffers et les résultats de cette série.** La température et la charge du bureau ne sont pas verrouillées ; refaire des séries plus longues pour extrapoler. Le warning JNI préexistant de LWJGL reste visible.

## Profil GPU séparé

Un run par variante, moyennes dans `3 < elapsed_s ≤ 19 s`. Ces runs instrumentés ne sont pas utilisés dans le calcul des FPS ci-dessus.

| Log | GPU shadow | GPU voxel | Références shadow | Références voxel |
|---|---:|---:|---:|---:|
| [Non indexé](../logs/gpu_pass_2026-10-23_215027_986.csv) | 8,967 ms | 13,054 ms | 31,941 millions | 6,460 millions |
| [Indexé](../logs/gpu_pass_2026-10-23_215050_269.csv) | 7,400 ms | 8,813 ms | 31,959 millions | 6,452 millions |

Les temps des deux passes baissent ; leur somme diminue d'environ 5,81 ms. Les volumes soumis restent comparables, les légères différences venant notamment de l'échantillonnage temporel du parcours. Cela soutient le bénéfice du changement de représentation, sans établir quelle part précise vient des lectures mémoire ou de la réutilisation des résultats vertex. Aucun compteur matériel de cache n'a été mesuré.

## Reproduire

```sh
./gradlew --offline installDist
JAVA_OPTS='-Dvoxel.testHarness=true -Dvoxel.testStatic=false -Dvoxel.windowScale=0.75 -Dvoxel.indexedMeshes=true -Dvoxel.tightShadowBounds=false -Dvoxel.tightCameraBounds=false -Dvoxel.gpuTiming=false' \
  build/install/TOB_Voxel_Project/bin/TOB_Voxel_Project
```

Pour le témoin, changer seulement `indexedMeshes` en `false`. Pour le profil des passes, faire des runs séparés avec `gpuTiming=true`. Ne pas utiliser leurs FPS comme résultats non instrumentés.

```sh
javac -cp 'build/classes/java/main:build/install/TOB_Voxel_Project/lib/*' \
  -d build/diagnostic-tests tools/tests/MeshBoundsTest.java tools/tests/MeshBoundsGlTest.java tools/tests/MeshIndexTest.java
index_repo="$PWD"
index_test_dir=$(mktemp -d /tmp/voxel-index-test.XXXXXX)
(
  cd "$index_test_dir" || exit 1
  java -XstartOnFirstThread \
    -cp "$index_repo/build/diagnostic-tests:$index_repo/build/classes/java/main:$index_repo/build/install/TOB_Voxel_Project/lib/*" \
    engine.render.MeshIndexTest
)
python3 tools/analyse_aabb_logs.py logs/perf_test_World_10_2026-10-23_214836.csv logs/perf_test_World_10_2026-10-23_214858.csv
```

Les logs sont locaux et ignorés par Git. Les anciens changements et les shaders modifiés sous `bin/` ont été préservés. Aucun fichier shader source n'est modifié par cette intervention. Le shimmering et les biais de cascades ne sont pas corrigés ici.
