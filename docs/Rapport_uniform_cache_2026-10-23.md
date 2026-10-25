# Cache des locations d'uniforms — 23 octobre 2026

**Résultat : coût de recherche supprimé, mais aucun gain FPS établi.** Le cache reste désactivé par défaut ; l'indexation précédente reste active. Cette expérience ne justifie pas d'annoncer une nouvelle accélération du moteur.

## Hypothèse et périmètre

Après l'indexation, la prochaine expérience vise les recherches CPU répétées de locations, pas une nouvelle modification de géométrie. `World::renderDepth` envoie `modelMatrix` pour chaque chunk/cascade ; `VoxelRenderer::renderVisibleChunks` le fait pour chaque chunk visible. Tous les setters de `Shader` appelaient `glGetUniformLocation` à chaque utilisation, même pour les uniforms absents.

Les locations restent stables après le link jusqu'au prochain link du programme : [référence Khronos](https://wikis.khronos.org/opengl/GLAPI/glGetUniformLocation). Les instances actuelles de `Shader` ne sont linkées qu'au constructeur. Le programme de `CubeTest` est distinct et n'est pas concerné.

## Implémentation

- Une map nom → location **par instance de Shader**, jamais partagée entre programmes.
- Tous les setters passent par le même helper ; les noms exacts des éléments de tableaux sont conservés.
- `-1` est également mémorisé : un uniform optimisé/inactif n'exige pas de nouvelle recherche chaque frame.
- Aucune valeur d'uniform n'est mise en cache ; les uploads, bindings, shaders et draws restent inchangés.
- Map vidée au cleanup. Un futur relink devra l'invalider ; ce contrat est commenté dans le code.
- `voxel.cacheUniforms` sélectionne le chemin ; `voxel.verifyUniformCache=true` compare chaque hit à un nouvel appel GL et échoue immédiatement si une location diffère.
- CSV CPU enrichi de `cacheUniforms` et `uniformLocationQueries`. Le compteur cumulatif couvre les recherches de résolution dans `Shader`, pas les appels supplémentaires du mode de vérification ni les programmes extérieurs au wrapper. Toutes ces opérations suivent le thread GL existant.

Ce n'est pas une correction de validité OpenGL : les recherches répétées étaient légales. Le but est de réduire le coût CPU/driver sans modifier le travail graphique demandé. La map ne possède aucune ressource native ou GPU.

## Protocole

Build `installDist`, parcours de validation mobile avec comparaison des locations à chaque hit, puis série **A–B–B–A** sans vérification ni queries GPU. A = cache OFF, B = cache ON. Indexation conservée ON, AABB précises OFF, scène/résolution/soleil inchangés. Le harnais reste visible sans focus ni capture du curseur.

Fenêtre d'analyse : `3 < somme(dt) ≤ 19 s` ; FPS agrégés = somme des frames / somme des durées. Les résultats ne sont pas comparés directement à ceux d'une autre session. Un gain CPU local ne sera pas présenté comme un gain de FPS si la frame entière ne suit pas.

## Résultats et décision

| Run / CSV | Cache | Frames analysées | FPS | Temps moyen | P95 | Render CPU médian |
|---|---|---:|---:|---:|---:|---:|
| [A1](../logs/perf_test_World_10_2026-10-23_220351.csv) | OFF | 980 | 61,281 | 16,318 ms | 20 ms | 3,46 ms |
| [B1](../logs/perf_test_World_10_2026-10-23_220414.csv) | ON | 981 | 61,328 | 16,306 ms | 20 ms | 3,31 ms |
| [B2](../logs/perf_test_World_10_2026-10-23_220436.csv) | ON | 978 | 61,159 | 16,351 ms | 21 ms | 3,45 ms |
| [A2](../logs/perf_test_World_10_2026-10-23_220458.csv) | OFF | 980 | 61,288 | 16,316 ms | 20 ms | 3,48 ms |

Agrégation pondérée : **61,284 FPS sans cache, 61,244 avec cache**, soit −0,067 %, non significatif dans ce protocole. Les témoins de cette session sont déjà autour de 61 FPS : ne pas attribuer au cache l'écart avec les 58,7 FPS d'une session précédente.

Les runs A terminent avec **2 607 867 et 2 605 621 recherches** cumulées ; les deux B avec **59**. Le mécanisme fonctionne, mais ces recherches ne limitaient pas le débit global sur ce parcours. Pas de profil GPU supplémentaire : les commandes de dessin et les uploads n'ont pas changé, et les FPS non instrumentés suffisent ici à rejeter l'hypothèse d'un gain visible. Cela ne constitue pas une preuve que le coût GPU est rigoureusement identique.

Tous les runs sont mobiles, indexés, stage 6, framebuffer 2268 × 1382, sans focus, visibles et non minimisés. Le soleil reste non pausé ; les processus terminent normalement. Le [run de validation](../logs/uniform_validation_2026-10-23.txt), CSV [22:03:29](../logs/perf_test_World_10_2026-10-23_220329.csv), a vérifié chaque hit contre OpenGL sans divergence. Il est exclu des mesures FPS. Build et `git diff --check` réussis ; aucune erreur GL signalée dans les consoles des essais.

**Confiance haute** dans la validité du cache pour le contrat actuel sans relink et dans la réduction des recherches ; **confiance moyenne** sur la généralisation des performances à d'autres scènes. Pas de comparaison pixel à pixel nouvelle : la validation porte sur l'identité des locations et les valeurs/uploads restent ceux du code précédent.

**Décision :** conserver l'expérience activable par `-Dvoxel.cacheUniforms=true`, défaut OFF. Ne pas multiplier les refactors CPU en supposant qu'ils augmenteront les FPS. La prochaine investigation devrait revenir au travail GPU et au volume de données des vertices — par exemple un layout compact sans perte sur les positions/normales — avec une validation dédiée, plutôt qu'une baisse de qualité des ombres. Cette piste suivante n'est pas implémentée ici.

## Commandes

```sh
./gradlew --offline installDist
JAVA_OPTS='-Dvoxel.testHarness=true -Dvoxel.testStatic=false -Dvoxel.cacheUniforms=true -Dvoxel.gpuTiming=false' \
  build/install/TOB_Voxel_Project/bin/TOB_Voxel_Project
```

Remplacer `cacheUniforms=true` par `false` pour A. Ajouter `-Dvoxel.verifyUniformCache=true` uniquement pour vérifier le cache, pas pour benchmarker. Cette validation porte directement sur les locations utilisées, pas sur une capture d'image comparée. Aucun shader, paramètre d'ombres ou layout n'est modifié.
