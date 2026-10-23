# Application du guide AABB — 22 octobre 2026

Ce rapport concerne exclusivement l'application du [guide des bornes réelles des meshes](Guide_AABB_meshes_pas_a_pas.md). Il ne réinterprète pas les anciens essais à soleil figé comme des références mobiles.

**Conclusion : implémentation et tests réussis, mais pas de gain FPS robuste sur ce parcours.** Les soumissions baissent légèrement ; les FPS restent autour de 45. Les deux options restent **désactivées par défaut**, activables séparément sans recompilation. Aucun résultat n'est présenté comme une résolution du problème de performances ou des artefacts d'ombres.

## 1. Périmètre et changements

Le calcul des meshes, leur format interleavé, les shaders, le nombre/résolution des cascades, les matrices, biais, filtres, textures, soleil et paramètres Retina sont conservés. L'optimisation consiste à ne plus soumettre certains meshes entièrement hors du frustum, en utilisant une boîte plus proche de leur géométrie.

| Fichier / méthode | Modification |
|---|---|
| `src/main/java/engine/render/MeshBounds.java` — `fromVertices`, `intersects` | Record immuable ; six coordonnées finies et ordonnées ; scan des seuls XYZ, stride 9 floats ; accès absolus respectant position/limit ; résultat `null` pour zéro vertex ; translation X/Z et marge conservative de 0,01 pour le culling. |
| `render/MeshBuilder.java` — `generateMeshData` | Calcul après le `flip`, depuis le buffer réellement envoyé au GPU ; libération du buffer en cas d'exception avant transfert à `MeshData`. |
| `render/MeshBuilder.java` — `buildChunkMesh` | Le chemin direct utilise le même producteur de `MeshData`, installe les mêmes bornes et libère le buffer en `finally`. Pas de suppression de cette voie dormante. |
| `render/MeshData.java`, `MeshWorkerManager::uploadMeshesIfAny` | Transport des bornes immuables avec les vertices, jusqu'à l'upload sur le thread GL. L'ancien constructeur reste conservateur : bornes absentes. |
| `world/Chunk.java` — `setMesh`, `cleanup` | Publication VAO/VBO/count/bornes avant `meshDirty=false` ; l'ancien overload et un mesh vide effacent les anciennes bornes ; le cleanup les remet à `null`. Une modification de bloc ne change pas les bornes du mesh encore installé. |
| `world/World.java` — `renderDepth` | Sélection bornes précises/ancienne boîte pour chaque frustum lumineux. Distance X/Z préalable et modèle de chunk inchangés. Un frustum nul reste une vraie référence sans ce rejet. |
| `render/renderer/VoxelRenderer.java` — `renderVisibleChunks` | Conservation du test large historique, puis test précis optionnel. L'origine du modèle reste celle du chunk, jamais le minimum de la boîte. |
| `core/config/RenderConfig.java` | Deux propriétés indépendantes : `voxel.tightShadowBounds` et `voxel.tightCameraBounds`. |
| `test/PerfLogger.java`, `render/GpuPassTimer.java`, `PlayingState` | Flags inscrits dans les CSV CPU/GPU et dans la console. Les anciennes colonnes restent nommées identiquement. |
| `test/CameraCullingValidator.java`, `Game::render`, overload de `VoxelRenderer::render` | Diagnostic facultatif `voxel.validateCameraCulling` : double rendu avant UI, mêmes meshes, heure, matrices, shadow maps et échantillon temporel. Aucun upload entre les deux rendus ; comparaison RGBA8 et depth. |
| `tools/tests/MeshBoundsTest.java`, `MeshBoundsGlTest.java`, `MeshBoundsCostTest.java` | Tests autonomes et micro-mesure informative du scan. |
| `tools/analyse_aabb_logs.py` | Analyse reproductible des CSV explicitement fournis, sans écrire de fichier ni sélectionner implicitement les runs de validation. |

Les chemins abrégés de ce tableau sont relatifs à `src/main/java/engine/`. Les modifications préexistantes des deux shaders sous `bin/main/shaders/` ont été laissées intactes. Les shaders sous `src/main/resources/shaders/` n'ont pas été modifiés.

### Invariant de lifetime

Les bornes décrivent le **mesh installé**, pas le monde mutable. Elles n'ajoutent aucun objet OpenGL ni allocation native persistante : un petit objet Java par résultat de meshing. Le thread worker calcule ; le thread GL installe. Cela ne prétend pas rendre atomique un ensemble de champs pour des lecteurs arbitraires : le contrat reste celui d'un rendu/upload sur le même thread GL.

## 2. Vérifications de correction

### CPU

`MeshBoundsTest` vérifie : position initiale non nulle, limit conservée, stride, attributs non-position remplis de NaN ignorés, boîte plane, cas vide, counts invalides/débordants, positions NaN/Inf refusées, containment de 1 000 vertices, coordonnées négatives, face supérieure à Y=256 et lifecycle sans handles GPU.

Il teste ensuite **20 000 frusta** alternant perspective et orthographique. Chaque rejet est contrôlé par un oracle indépendant transformant les huit coins en clip space : **16 226 rejets vérifiés, PASS**. Le test préexistant `ShadowFrustumCullingTest` passe également : **20 000 cas, 19 102 rejets vérifiés**. Les tests lancent une exception en cas d'échec ; ils ne dépendent pas de `-ea` ni de JUnit.

### GPU et remeshing

`MeshBoundsGlTest` a été exécuté dans un contexte GLFW/OpenGL caché et un répertoire temporaire, hors des sauvegardes du joueur :

- vrai worker → queue → upload ;
- bloc bas, ajout d'un bloc à Y=255 au bord du chunk, suppression : bornes bas → haut → bas ;
- ancienne boîte conservée pendant que le nouveau mesh est en attente ;
- anciens VAO/VBO effectivement détruits lors du remplacement, contrôlés par `glIsVertexArray`/`glIsBuffer` ;
- cleanup puis ré-upload ;
- chemin direct `buildChunkMesh` ;
- mesh entièrement vide : zéro vertex et bornes nulles ;
- compteur `MeshData.COUNT` revenu à zéro et aucune erreur GL à la fin.

**Résultat : PASS.** Le cleanup/ré-upload isolé ne remplace pas un stress test du streaming géographique ou des modifications concurrentes massives.

### Images et shadow maps, avec mouvement

Console conservée : [aabb_validation_2026-10-22.txt](../logs/aabb_validation_2026-10-22.txt). Le [CSV de ce run](../logs/perf_test_World_10_2026-10-22_211538.csv) est **exclu des benchmarks**.

Les deux flags sont actifs. Contrôles au début puis vers 5, 10 et 15 secondes :

- **12 comparaisons depth de cascades**, chacune sur 4 194 304 texels : **50 331 648 valeurs float identiques** au chemin sans frustum lumineux ;
- **4 comparaisons caméra**, chacune sur 3 134 376 pixels : RGBA8 et depth identiques entre anciennes boîtes et bornes précises ;
- aucune erreur GL signalée par ces contrôles ; caméra mobile, soleil non pausé, heure finale ≈ 0,03172.

Le validateur caméra rejoue ciel → soleil → voxels avant UI, pas `Game::render` entier. Le premier rendu traite les uploads et avance le compteur temporel ; le second réutilise l'échantillon précédent sans nouvel upload ni update. Les shadow maps ne sont pas recalculées entre ces deux rendus. Les buffers de lecture natifs sont libérés dans des `finally`.

Ces lectures synchrones perturbent volontairement les performances. Le clamp temporel existant explique aussi une progression solaire légèrement différente dans ce run lent : ce n'est pas une référence FPS. Le diagnostic est désactivé par défaut, et refuse d'être combiné au timer GPU actif.

**Confiance haute sur les cas exercés**, pas une preuve universelle. Aucun cycle solaire complet, contrôle visuel prolongé du shimmering, ni validation du leapfrog dormant n'a été réalisé.

## 3. Protocole de performance

Configuration commune : Apple M2 Pro ; `GL_VERSION=4.1 Metal - 90.5`, GLSL 4.10 ; contexte demandé inchangé ; fenêtre logique 1134 × 691 et framebuffer **2268 × 1382** ; Retina ON, échelle 0,75 ; trois cascades 2048² ; filtre/stage conservés (stage 6) ; culling lumineux historique ON ; `World 10` et même spawn.

Tous les essais utilisent `voxel.testHarness=true`, `voxel.testStatic=false`. Chaque scénario dure environ 20 secondes, déplace la caméra et fait avancer le soleil (`paused=false` vérifié en console). La trajectoire est intégrée avec `dt` : les poses ne sont pas rigoureusement identiques d'un processus à l'autre. Une comparaison dans la même frame sert donc à la correction, les runs séparés aux tendances de performance.

| Variante | `tightShadowBounds` | `tightCameraBounds` |
|---|---|---|
| A | false | false |
| S | true | false |
| C | false | true |
| B | true | true |

Analyse : **3 < temps écoulé ≤ 19 secondes**. Pour le CSV CPU : temps = somme des `dt`, FPS = nombre de lignes / somme des `dt`, pas moyenne des FPS instantanés. P50/P95 à l'indice arrondi `(N−1)×p`. Pour le CSV GPU : utiliser sa colonne `elapsed_s`, les résultats asynchrones pouvant arriver hors ordre en fin de fichier.

Les compteurs et timings CPU décrivent toujours le rendu précédent ; pose et heure sont celles de l'update courant. Le calcul des bornes est présent même dans A : A/S/C/B isolent l'usage des bornes, pas le coût ponctuel de leur génération.

Un run du code non modifié, [21:11:51](../logs/perf_test_World_10_2026-10-22_211151.csv), donne **45,50 FPS**, 21,98 ms/frame, P95 26 ms. C'est un contrôle initial, pas un témoin à mélanger automatiquement aux comparaisons rapprochées.

## 4. Profilage séparé des deux consommateurs

Moyennes sur la fenêtre retenue, un run par variante, avec queries activées. Ces valeurs ne servent pas à annoncer les FPS en jeu normal.

| Variante / log GPU | Draws shadow | Vertices shadow | Temps GPU shadow | Draws voxel | Vertices voxel | Temps GPU voxel |
|---|---:|---:|---:|---:|---:|---:|
| [A — 21:24:03](../logs/gpu_pass_2026-10-22_212403_039.csv) | 1 733,4 | 31 907 826 | 9,021 ms | 348,1 | 6 446 557 | 13,027 ms |
| [S — 21:24:40](../logs/gpu_pass_2026-10-22_212440_601.csv) | 1 669,9 | 30 722 333 | 8,848 ms | 348,2 | 6 451 417 | 13,042 ms |
| [C — 21:25:03](../logs/gpu_pass_2026-10-22_212503_180.csv) | 1 733,6 | 31 912 292 | 9,081 ms | 343,7 | 6 376 666 | 12,908 ms |

**Fait observé :** S réduit d'environ **3,72 %** les vertices shadow ; C réduit d'environ **1,08 %** les vertices voxel. La baisse des temps de leurs passes respectives est petite (≈0,17 et 0,12 ms). Les compteurs de la passe non ciblée restent proches, sans être identiques puisque les poses/échantillonnages temporels diffèrent légèrement.

**Interprétation prudente :** cette piste retire réellement des soumissions, mais les anciennes boîtes n'étaient pas responsables d'un excès massif de draws sur ce parcours. Les quelque 30 millions de vertices shadow et 6,4 millions de vertices voxel restants ne disparaissent pas grâce à cette optimisation. Un seul profil par variante n'établit pas la significativité des différences submilliseconde. Aucun mécanisme de cache ou détail micro-architectural n'est déduit de ces chiffres.

## 5. Mesures sans instrumentation et décision

Les dix runs suivants ont été exécutés **séquentiellement**, sans compilation concurrente, queries GPU, readbacks de validation ni `glFinish` de diagnostic. Ils forment trois séquences avec témoins de frontière partagés : **A1–S1–S2–A2**, **A2–C1–C2–A3**, **A3–B1–B2–A4**. Les consoles associées se trouvent dans `logs/aabb_fps_<variante>_2026-10-22.txt`.

| Run / CSV | Frames retenues | FPS | Temps moyen | P50 / P95 | Render / swap CPU médians |
|---|---:|---:|---:|---:|---:|
| [A1 — 21:26:06](../logs/perf_test_World_10_2026-10-22_212606.csv) | 723 | 45,216 | 22,116 ms | 22 / 26 ms | 3,310 / 18,090 ms |
| [S1 — 21:26:28](../logs/perf_test_World_10_2026-10-22_212628.csv) | 723 | 45,185 | 22,131 ms | 21 / 27 ms | 2,530 / 18,700 ms |
| [S2 — 21:26:51](../logs/perf_test_World_10_2026-10-22_212651.csv) | 723 | 45,213 | 22,118 ms | 21 / 27 ms | 2,540 / 18,620 ms |
| [A2 — 21:27:13](../logs/perf_test_World_10_2026-10-22_212713.csv) | 723 | 45,179 | 22,134 ms | 22 / 26 ms | 3,470 / 18,140 ms |
| [C1 — 21:27:36](../logs/perf_test_World_10_2026-10-22_212736.csv) | 726 | 45,403 | 22,025 ms | 22 / 26 ms | 3,440 / 17,965 ms |
| [C2 — 21:27:59](../logs/perf_test_World_10_2026-10-22_212759.csv) | 727 | 45,446 | 22,004 ms | 22 / 26 ms | 3,400 / 17,860 ms |
| [A3 — 21:28:21](../logs/perf_test_World_10_2026-10-22_212821.csv) | 720 | 45,014 | 22,215 ms | 22 / 27 ms | 3,720 / 18,020 ms |
| [B1 — 21:28:44](../logs/perf_test_World_10_2026-10-22_212844.csv) | 714 | 44,631 | 22,406 ms | 21 / 28 ms | 2,820 / 18,285 ms |
| [B2 — 21:29:06](../logs/perf_test_World_10_2026-10-22_212906.csv) | 726 | 45,349 | 22,051 ms | 21 / 27 ms | 2,545 / 18,425 ms |
| [A4 — 21:29:29](../logs/perf_test_World_10_2026-10-22_212929.csv) | 723 | 45,196 | 22,126 ms | 22 / 26 ms | 3,520 / 17,970 ms |

Agrégation par paire : somme des frames / somme des durées retenues, et non moyenne non pondérée des FPS.

| Comparaison | Témoins encadrants | Variante | Écart de FPS | Écart de temps moyen |
|---|---:|---:|---:|---:|
| Shadow seule | 45,197 FPS | 45,199 FPS | +0,003 % | −0,0007 ms |
| Caméra seule | 45,097 FPS | 45,425 FPS | +0,73 % | −0,160 ms |
| Combinaison | 45,105 FPS | 44,990 FPS | −0,25 % | +0,057 ms |

**Ce que ces chiffres établissent :** aucune amélioration sensible du débit global dans cette série. Le temps CPU médian de rendu baisse avec S/B, mais la frame complète ne bénéficie pas de la même baisse. Il ne faut pas transformer le delta du champ `render_ms` en gain utilisateur.

**Ce qu'ils n'établissent pas :** que C améliore systématiquement de 0,73 %, ou que B cause une régression reproductible de 0,25 %. Ces écarts sont petits, B1/B2 varient davantage, et la température/processus concurrents ne sont pas strictement contrôlés. Deux répétitions par variante, sur vingt secondes, ne constituent pas une étude statistique ni un test de tous les angles de vue. Le seuil 60 FPS/16,67 ms n'est pas atteint.

### Décision livrée

- `voxel.tightShadowBounds=false` par défaut.
- `voxel.tightCameraBounds=false` par défaut.
- Calcul et transport des bornes conservés et testés ; les flags contrôlent leur **consommation**, pas leur génération.
- Culling lumineux préexistant conservé actif ; validation et timer GPU désactivés par défaut.

Ce choix évite d'activer automatiquement une expérience dont le bénéfice de FPS n'est pas démontré. Il ne signifie pas que le culling précis soit incorrect. L'activer sur d'autres scènes, vues plongeantes ou terrains plus clairsemés reste une expérience possible, pas un gain garanti. Le calcul ponctuel des bornes reste un coût ajouté même avec les deux flags OFF ; ce n'est donc pas un retour bit-à-bit au code initial.

### Coût CPU du calcul ajouté

`MeshBoundsCostTest`, exécuté **après** les benchmarks graphiques, utilise un terrain synthétique de **30 984 vertices** dans un monde temporaire :

- scan du buffer après échauffement, moyenne de 2 000 appels : **0,0371 ms** ;
- génération complète avec calcul des bornes et libération, moyenne de 100 appels après échauffement : **2,1866 ms** ;
- rapport indicatif entre ces deux mesures : **1,70 %**.

Ce n'est ni une mesure JMH, ni un profilage des workers réels de `World 10`, ni une soustraction expérimentale avant/après. Le scan relit le même buffer chaud ; ses résultats ne prédisent pas les caches froids, la contention ou le chargement global. Cela indique seulement un coût local faible sur ce cas. Le pic de mémoire du calcul se limite à quelques scalaires et au record résultat, sans deuxième copie du buffer.

### Intégrité et fin des essais

Les builds `installDist`, tests CPU et tests GL terminent normalement. Les dix runs non instrumentés terminent tous le scénario et le cleanup, avec soleil non pausé ; le temps final est compris entre environ 0,03302 et 0,03321. Les sommes SHA-256 de `saves/World_10/world.json` et `player.dat` sont inchangées avant/après :

```text
world.json  2cbaa2c60eb5d90dc37f0861df903f5ebaafe208c5ed4021c543b158daa48dfd
player.dat  a5cf62c6a10434ee5994451b8895048da98a7bd4bae33910383f8453ee285e12
```

Les logs sont des artefacts locaux, ignorés par Git selon les règles existantes ; les liens de ce rapport nécessitent leur conservation avec le dossier `logs/` pour une consultation sur une autre machine.

## 6. Reproduction

Depuis la racine du dépôt :

```sh
./gradlew --offline installDist
javac -cp 'build/classes/java/main:build/install/TOB_Voxel_Project/lib/*' \
  -d build/diagnostic-tests tools/tests/ShadowFrustumCullingTest.java \
  tools/tests/MeshBoundsTest.java tools/tests/MeshBoundsGlTest.java tools/tests/MeshBoundsCostTest.java
java -cp 'build/diagnostic-tests:build/classes/java/main:build/install/TOB_Voxel_Project/lib/*' engine.render.MeshBoundsTest
java -cp 'build/diagnostic-tests:build/classes/java/main:build/install/TOB_Voxel_Project/lib/*' engine.world.ShadowFrustumCullingTest
```

Pour le test GL et la micro-mesure, isoler le `WorldStorage` créé par les tests :

```sh
bounds_repo="$PWD"
bounds_test_dir=$(mktemp -d /tmp/voxel-bounds-test.XXXXXX)
(
  cd "$bounds_test_dir" || exit 1
  java -XstartOnFirstThread \
    -cp "$bounds_repo/build/diagnostic-tests:$bounds_repo/build/classes/java/main:$bounds_repo/build/install/TOB_Voxel_Project/lib/*" \
    engine.render.MeshBoundsGlTest
  java -cp "$bounds_repo/build/diagnostic-tests:$bounds_repo/build/classes/java/main:$bounds_repo/build/install/TOB_Voxel_Project/lib/*" \
    engine.render.MeshBoundsCostTest
)
```

Benchmark B (pour A, mettre les deux flags à `false` ; S et C selon le tableau) :

```sh
JAVA_OPTS='-Dvoxel.testHarness=true -Dvoxel.testStatic=false -Dvoxel.windowScale=0.75 -Dvoxel.shadowFrustumCulling=true -Dvoxel.tightShadowBounds=true -Dvoxel.tightCameraBounds=true -Dvoxel.gpuTiming=false -Dvoxel.validateShadowCulling=false -Dvoxel.validateCameraCulling=false' \
  build/install/TOB_Voxel_Project/bin/TOB_Voxel_Project
```

Pour **valider**, conserver `gpuTiming=false` et passer les deux propriétés `validate...` à `true`. Pour **profiler**, conserver les deux validations à `false` et passer seulement `gpuTiming=true`. Ne jamais tirer un chiffre de FPS normal des deux modes instrumentés.

Analyse de fichiers sélectionnés explicitement :

```sh
python3 tools/analyse_aabb_logs.py logs/perf_test_World_10_2026-10-22_211151.csv
python3 tools/analyse_aabb_logs.py logs/gpu_pass_2026-10-22_212403_039.csv
```

`gradle test` n'est pas présenté comme une couverture de tests : les sources Java de test sont désactivées dans `build.gradle`. Les commandes autonomes ci-dessus ont cette responsabilité.

## 7. Limites et suite

- Le chantier ne corrige ni le shimmering, ni les biais/peter-panning, ni les discontinuités de cascades. Aucune cadence solaire ou stratégie de shadow-map caching n'a changé.
- Le validateur shadow conserve le préfiltre de distance préexistant dans les deux branches : il vérifie le rejet par frustum, pas la correction universelle de ce filtre de distance.
- Les courses éventuelles entre édition de blocs, tâches de meshing et unload n'ont pas été refactorées. Le test de lifetime couvre le chemin séquentiel contrôlé, pas un stress multithread.
- Un chunk vide reste non `isReady()` dans le contrat existant ; aucun monde vide n'a été utilisé pour le chargement du harnais.
- La durée globale de chargement, les pics de streaming et le coût sous une avalanche d'éditions ne sont pas isolés par un benchmark dédié. La micro-mesure du scan n'en tient pas lieu.
- Un lancement a échoué dans `Application::init`, à `glfwGetVideoMode`, après un retour nul du moniteur principal. La relance a réussi ; ce run n'a produit aucune frame mesurée. Cause non établie, pas attribuée aux AABB.
- L'avertissement LWJGL/JNI préexistant sous le JDK hôte reste présent ; aucune migration de dépendances dans ce chantier.

La suite raisonnable est de conserver les outils de validation, puis de quantifier une réduction du volume de géométrie réellement stocké/soumis (par exemple l'indexation des quads) dans un chantier distinct. Le profilage actuel n'autorise pas à promettre un gain de FPS proportionnel au gain de stockage. Le travail sur la qualité des cascades doit rester une expérience visuelle séparée.
