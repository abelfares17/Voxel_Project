# Fusion des ombres : correction des fissures et qualification

## Correction retenue

La fusion reste activée par défaut (`voxel.greedyShadows=true`). Elle conserve les rectangles du partitionnement existant, mais **ne les rend plus comme deux grands triangles**.

`ShadowMesh.positions` conserve des segments d'un bloc sur tout le périmètre de chaque rectangle. Le rectangle est triangulé par découpage d'oreilles non dégénérées, sans sommet central. Le contrôle de la diagonale empêche de sauter un sommet collinéaire restant. Cette convention vaut pour toutes les orientations et pour les limites de chunks : aucun accès aux meshes voisins ni nouvelle responsabilité de chargement n'est nécessaire.

Pour un rectangle de `w × h` faces :

- source non fusionnée : `2*w*h` triangles ;
- éventail expérimental du diagnostic : `2*(w+h)` triangles ;
- correction : **`2*(w+h)-2` triangles**, toujours ≤ `2*w*h` pour w,h ≥ 1.

Une bande de largeur 1 ne gagne pas de triangles ; un rectangle plus large dans les deux dimensions en économise. Le gain n'est plus celui de l'ancienne fusion à deux triangles, qui produisait des fissures.

### Indexation nécessaire

La première correction géométrique sans indexation était correcte dans le diagnostic, mais **plus lente que OFF** dans le harnais : OFF 65,37 FPS, ON 63,83 FPS, soit −2,34 %. Résultats conservés dans [`greedy_suite_qf7vajee`](../logs/greedy_suite_qf7vajee/results.json). Elle n'a donc pas été retenue comme livraison finale.

Le chemin final partage les positions identiques dans un chunk et utilise un EBO 32 bits avec `glDrawElements`. Les positions restent des floats exacts de la grille locale ; la déduplication ne change ni ordre des triangles, ni orientation, ni coordonnées. La demi-grille est également acceptée pour les éventails des tests, sans quantification approximative.

La construction des données indexées se fait dans les workers CPU via `ShadowMesh.generate`, avant l'upload GL. `ShadowMesh.Data` possède uniquement des tableaux Java, libérés par abandon de référence dans `MeshData.free`. Le thread GL crée/détruit VAO, VBO et EBO ensemble. Le chemin direct `MeshBuilder.buildChunkMesh` utilise le même résultat CPU que `MeshWorkerManager`.

`Chunk.getMeshBytes` compte désormais réellement VBO + EBO shadow. Le compteur `ShadowMesh.count` est le nombre d'indices soumis, pas le nombre de positions uniques ; les compteurs de draw restent comparables aux meshes voxels déjà indexés.

## Pistes écartées

- Ne découper qu'aux coins des rectangles voisins et aux frontières de chunks réduit le défaut mais laisse des écarts importants : non retenu (`shadow_raster_12985894671472443268`). Préserver seulement ces jonctions ne suffit pas sur cette scène/M2.
- Désactiver le culling ne supprime pas les trous.
- Ajouter un centre sans découper les longues arêtes conserve les gros écarts.
- L'éventail à toutes arêtes unitaires corrige le défaut mais dépasse le budget de triangles du non-fusionné.

La conclusion pratique est de préserver la couverture raster des arêtes unitaires d'origine. Ces essais précisent le diagnostic « jonctions en T » : ils ne démontrent pas à eux seuls le détail des arrondis internes du rasteriseur.

## Tests de profondeur

Diagnostic autonome en fenêtre cachée, patch fixe de 299 chunks du monde 10 lus sans écriture, shaders depth/CSM de production, trois poses et trois cascades de 2048². Les limitations du reproducer sont décrites dans [le diagnostic initial](Diagnostic_fusion_ombres_2026-10-26.md) : ce n'est pas un replay pixel-identique de la session utilisateur, et le fit utilise les AABB complètes du patch.

| Éclairage | Résultats | Cartes | Gros écarts corrigé/référence (> 0,001) | Écart maximal |
|---|---|---:|---:|---:|
| Soleil à 17 h 19 | [CSV](../logs/shadow_raster_14370463573191421928/results.csv) | 9 | **0** | 0,000011563 |
| Soleil à 08 h | [CSV](../logs/shadow_raster_2726936124812298665/results.csv) | 9 | **0** | 0,000004441 |
| Lune à 01 h | [CSV](../logs/shadow_raster_13394553370618802342/results.csv) | 9 | **0** | 0,000003397 |

Ce sont des profondeurs normalisées, pas des mètres. L'ancien maillage présentait 648 gros écarts, dont 174 texels devenus clear, sur les neuf cartes à 17 h 19. Les faibles écarts d'interpolation restants ne sont pas une identité bit-à-bit. Le diagnostic corrigé possède désormais une assertion sur les gros écarts, et non seulement un export informatif.

Sur ce patch : **5 423 442 indices/vertices soumis sans fusion → 4 329 204 indices soumis corrigés**, soit −20,18 %. Ce chiffre compare la géométrie ; le gain de FPS doit être mesuré séparément.

## Tests structurels et de durée de vie

- `ShadowMeshTest` : six orientations, aire conservée, absence de triangles dégénérés, arêtes exposées unitaires, trous, surfaces aléatoires, grand mur 256×16, budget de triangles et entrée vide.
- Vérification que développer les indices reconstruit exactement les positions et le winding de la triangulation.
- Création/suppression de VAO, VBO **et EBO**, remplacement, nettoyage idempotent et remplacement du mesh visuel invalidant l'ancien mesh shadow.
- `MeshBoundsGlTest` dans un répertoire temporaire : worker upload, low/high/low, remplacement, destruction, re-upload, chemin direct et mesh vide. Les tests tolèrent la réutilisation légale d'un nom de buffer seulement après avoir vérifié sa destruction.
- Build `installDist` effectué ; aucune modification de shaders, biais, normal-offset, casters ou cycle jour/nuit.

## Benchmark final : fusion corrigée indexée contre OFF

Suite complète : [`greedy_suite_yr3joidi/results.json`](../logs/greedy_suite_yr3joidi/results.json), [agrégat ABBA](../logs/greedy_suite_yr3joidi/aggregate.json).

Apple M2 Pro, framebuffer **2268×1382**, fenêtres visibles mais non focalisées/non minimisées, curseur normal. Quatre runs de 20 secondes, ordre OFF/ON/ON/OFF. Analyse sur 3 < t ≤ 19 s ; caméra **et soleil en mouvement**, départ forcé du harnais près de l'origine, même monde/configuration. Heure solaire dans la fenêtre mesurée ≈ 08 h 04 à 08 h 23. Les runs FPS n'activent ni comparaison d'images/readback ni timer queries. Les deux profils GPU sont séparés.

| Run | Fusion | FPS | Frame moyenne | P95 frame |
|---|---|---:|---:|---:|
| A1 | OFF | 65,11 | 15,36 ms | 17,4 ms |
| B1 | Corrigée indexée | 79,97 | 12,50 ms | 14,4 ms |
| B2 | Corrigée indexée | 79,40 | 12,59 ms | 14,8 ms |
| A2 | OFF | 65,37 | 15,30 ms | 17,2 ms |

**Agrégation par nombre de frames / durée mesurée : 65,24 FPS OFF → 79,69 FPS ON, soit +22,15 %.** Les temps de frame agrégés passent d'environ 15,33 à 12,55 ms (−18,1 %). Le gain est présent dans les deux répétitions ON et les deux contrôles OFF sont proches.

Profil GPU séparé : passe shadow **7,95 ms OFF → 4,82 ms ON** (≈ −39,3 %). Nombre de draws shadow quasi identique (~2146 par frame), indices soumis ~39,60 M → 30,85 M. Le coût voxel reste autour de 8,8–9,1 ms ; il ne faut pas additionner les profils comme s'ils étaient les timings des runs FPS non instrumentés.

Coût mémoire : buffers de meshes estimés par le moteur ≈ 376,03 MB OFF → 466,66 MB ON, soit **+90,63 MB décimaux** pour garder le mesh shadow dédié en plus du mesh visuel. Ce compteur additionne les tailles allouées VBO/EBO, ce n'est pas une mesure de VRAM physique du driver. L'essai corrigé non indexé montait à environ 551 MB. La correction ne prétend pas rendre le mesh shadow gratuit en mémoire.

Comparaison des images finales : contrôle identique = zéro différence sur les quatre captures ; OFF/ON corrigé = respectivement **2, 2, 1 et 1 pixels différents sur 3 134 376**, maximum 9, 11, 16 et 1 niveaux sur 255. La profondeur **caméra** est identique ; ce compteur ne remplace pas les tests de profondeur shadow ci-dessus. Ces quelques différences de filtrage ne sont pas une preuve d'identité pixel-par-pixel, ni une raison de tolérer à nouveau les trous importants du diagnostic.

Conclusion : **la version finale corrige le défaut reproduit et reste une amélioration de performance mesurée face au mode sans fusion.** Les FPS concernent le parcours du harnais, pas la pose de la montagne ; la montagne a servi à la qualification géométrique/GPU des trous. Un ABBA court sur une machine ne garantit pas +22 % sur chaque monde, résolution ou GPU. Le coût CPU de reconstruction des meshes n'a pas été isolé dans un benchmark dédié.

## Reproduire

```sh
./gradlew --offline installDist
python3 tools/benchmark_greedy_shadows.py --mode all

shadow_test_classes=$(mktemp -d)
javac -cp 'build/classes/java/main:build/install/TOB_Voxel_Project/lib/*' \
  -d "$shadow_test_classes" tools/tests/ShadowMeshTest.java tools/tests/ShadowMergeRasterDiagnostic.java
java -XstartOnFirstThread \
  -cp "$shadow_test_classes:build/classes/java/main:build/install/TOB_Voxel_Project/lib/*" ShadowMeshTest
java -XstartOnFirstThread -Xmx2G -Ddiagnostic.hour=17.3166666667 \
  -cp "$shadow_test_classes:build/classes/java/main:build/install/TOB_Voxel_Project/lib/*" ShadowMergeRasterDiagnostic
```

Utiliser `-Ddiagnostic.hour=8` ou `1` pour les deux autres éclairages. Le harnais laisse le soleil avancer, utilise un curseur normal, et interdit les sauvegardes utilisateur. Le diagnostic lit seulement les chunks sauvegardés ; il échoue si un fichier requis manque.

La grande ombre tronquée par les casters déchargés reste indépendante de cette correction. Une validation humaine de la montagne avec la version finale reste utile ; les tests ne promettent pas l'absence universelle d'aliasing shadow sur tout matériel.
