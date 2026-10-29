# Diagnostic des taches blanches — fusion des ombres

## Conclusion

**Défaut reproduit sur Apple M2 Pro dans les cartes de profondeur, indépendamment du shader de réception.** La fusion conserve exactement les cellules de faces orientées du terrain testé, mais sa rasterisation introduit des trous. Une triangulation expérimentale qui subdivise toutes les arêtes des rectangles en segments d'un bloc supprime tous les gros écarts mesurés. Changer seulement les diagonales en un éventail à quatre triangles ne les supprime pas.

**Haute confiance : défaut de continuité de couverture raster des longues arêtes fusionnées ; jonctions en T très fortement incriminées.** La cause numérique précise dans le rasteriseur/driver n'a pas été instrumentée. Ce résultat ne démontre pas un bug du driver Apple et ne signifie pas que les rectangles omettent des faces dans leur surface mathématique.

Aucun changement de production, de valeur par défaut ou de sauvegarde utilisateur. Ajout du diagnostic autonome `tools/tests/ShadowMergeRasterDiagnostic.java` et de ce rapport. Les modifications préexistantes dans le worktree sont conservées.

## Protocole exécuté

- Compilation des sources courantes : `./gradlew --offline classes`, PASS.
- Lecture seule des fichiers de chunks du monde 10, sans constructeur `World`, streaming, sauvegarde ni génération de terrain.
- Patch fixe de 299 chunks : X = 40..62, Z = 34..46, avec lecture des voisins nécessaires à la visibilité des faces.
- Reconstruction des faces exposées, selon le critère air/non-air du mesher. Les deux chemins GPU reçoivent exactement cette même géométrie source.
- Production : `ShadowMergeProbe.merge`, `ShadowMesh.positions/upload`, `CSMCalculator`, `ShadowCasterVolume`, `CelestialState`, shaders `depth_vertex_shader.glsl` et `depth_fragment_shader.glsl`.
- Heure fixe : 17,3166666667 h. Trois poses issues des dernières captures, avec hauteur caméra = hauteur joueur + 2.
- FOV 90°, aspect 3024/1898, trois cascades de 2048², profondeur `DEPTH_COMPONENT32F`, `GL_LESS`, winding CCW.
- Même matrice et même géométrie chargée pour toutes les variantes d'une comparaison ; aucune animation ni chargement concurrent.
- Contexte demandé 3.2 Core ; runtime : `Apple M2 Pro / 4.1 Metal - 90.5`.
- Fenêtre GLFW cachée : aucun curseur capturé, pas d'ouverture de fenêtre de jeu.

Poses caméra utilisées (XYZ inclut ici les 2 blocs de hauteur caméra) :

| Pose | XYZ | Yaw / pitch |
|---|---|---|
| 0 | 983,214 / 100,295 / 634,537 | 1347,163° / 8,922° |
| 1 | 911,354 / 100,295 / 639,017 | 1346,064° / 1,322° |
| 2 | 827,546 / 100,295 / 644,727 | 1346,863° / 1,122° |

## Résultats CPU

**903 907 faces unitaires → 317 057 rectangles.**

Le diagnostic reconstruit l'ensemble des cellules orientées `(axe, signe, plan, u, v)` couvertes par les rectangles et le compare à celui des faces d'entrée : aucune omission, aucun ajout, aucun recouvrement. PASS.

Le test existant `ShadowMeshTest` passe également : six orientations, winding, fusion, trou volontaire, entrée vide et durée de vie GPU. Il ne suffit donc pas, à lui seul, à détecter les fissures raster.

## Résultats GPU

Run principal : [`logs/shadow_raster_4764462583502958846`](../logs/shadow_raster_4764462583502958846/).

Résultats détaillés : [`results.csv`](../logs/shadow_raster_4764462583502958846/results.csv), [`metadata.txt`](../logs/shadow_raster_4764462583502958846/metadata.txt).

Sommes sur neuf cartes (3 poses × 3 cascades), pas des pixels de l'écran final ni des trous uniques dans le monde :

| Variante | Texels plus profonds de > 0,001 | Dont profondeur égale à 1 (clear) | Dont intérieur de la couverture de référence | Écart absolu maximal |
|---|---:|---:|---:|---:|
| Référence répétée, non fusionnée | 0 | 0 | 0 | 0 |
| Fusion actuelle, deux triangles/rectangle | **648** | **174** | **633** | **0,910710216** |
| Éventail à quatre triangles, longues arêtes conservées | **648** | **174** | **633** | **0,910710216** |
| Éventail, arêtes subdivisées à chaque bloc | **0** | **0** | **0** | **0,000012189** |
| Fusion actuelle sans backface culling | 476 | 89 | 467 | 0,926808417 |

Les deux colonnes « dont » ne sont pas disjointes. « Intérieur » signifie que les 25 texels du voisinage 5×5 de référence contiennent une profondeur < 1. Cela élimine la majorité des ambiguïtés de contour externe : les trous apparaissent au sein de surfaces couvertes, pas seulement sur leur silhouette.

Le seuil 0,001 est exprimé dans la profondeur normalisée de chaque carte, pas en mètres. Les petits écarts résiduels de l'éventail subdivisé sont compatibles avec des différences d'interpolation de profondeur. **Zéro gros écart ne signifie pas une identité bit-à-bit** : 71 texels dépassent encore 1e-5 au total, avec un maximum de 1,2189e-5.

Pour la fusion actuelle, les écarts > 0,001 par cascade sont :

| Pose | Near | Mid | Far |
|---|---:|---:|---:|
| 0 | 60 | 92 | 49 |
| 1 | 70 | 94 | 49 |
| 2 | 83 | 102 | 49 |

Le premier run exploratoire (`logs/shadow_raster_5998388156431440292`) retrouve les mêmes nombres pour les variantes communes. Il ne contenait pas encore le contrôle « éventail à quatre triangles ».

Les PNG de différence sont en coordonnées shadow map, pas caméra : rouge = profondeur devenue clear ; vert = profondeur augmentée de plus de 0,001 ; bleu = profondeur diminuée de plus de 0,001. Les texels sont isolés : examiner les PNG à 100 % ou davantage, un aperçu réduit peut sembler noir. Les petites différences sous le seuil ne sont pas coloriées.

## Pourquoi cela incrimine les jonctions en T

`ShadowMergeProbe.merge` partitionne indépendamment chaque plan orienté en rectangles. `ShadowMesh.positions` transforme chaque rectangle en deux triangles, sans assurer que les découpages d'arêtes correspondent entre rectangles voisins, entre plans ou entre chunks.

Une longue arête d'un rectangle peut ainsi faire face à plusieurs arêtes courtes. Géométriquement, elles couvrent la même ligne ; après projection et discrétisation du rasteriseur, cette jonction non conforme n'offre pas la même continuité qu'une arête ayant exactement les mêmes extrémités des deux côtés.

L'expérience distingue plusieurs hypothèses :

1. **Faces perdues par le partitionnement :** pas observées dans l'audit CPU exact.
2. **Seule diagonale interne du rectangle :** peu compatible avec le maintien des 648 gros écarts lorsque l'on remplace les deux triangles par quatre triangles autour d'un centre, sans subdiviser le périmètre.
3. **Discontinuité des arêtes longues/courtes :** fortement soutenue par la disparition des gros écarts lorsque toutes les arêtes deviennent unitaires.
4. **Backface culling seul :** ne suffit pas à expliquer/corriger le défaut ; des trous persistent sans lui. Le double-face produit en outre de nombreuses autres différences et n'est pas équivalent à la référence.
5. **Biais, PCF ou normal-offset du récepteur :** aucun ne participe à ces comparaisons de depth maps. Les trous existent avant leur intervention.

La lecture PCF de ces texels peut ensuite transformer un défaut de quelques texels en petite tache éclairée sur les récepteurs. Cela concorde avec les observations humaines : stabilité lorsque tout est figé, déplacement avec normal-offset, disparition sans fusion. La correspondance pixel par pixel avec chaque tache des captures n'a pas été réalisée.

## Limites explicites

- Ce n'est pas un replay complet du jeu : le patch chargé est fixe et volontairement indépendant du streaming problématique.
- Les faces unitaires sont reconstruites par le diagnostic, avec winding canonique ; le VAO compact/indexé de production et son ordre exact de triangulation ne sont pas utilisés comme référence. La fusion et le shader depth, eux, sont ceux de production.
- Le fit Z utilise les AABB complètes des chunks du patch, pas les bounds exactes des meshes de la session utilisateur. Les matrices sont strictement identiques entre variantes, mais pas promises identiques à celles des captures.
- L'attachement du diagnostic est une texture depth 2D, au même format/résolution que la production ; pas une couche de l'atlas array. Le défaut apparaît donc sans dépendre de l'échantillonnage d'array textures.
- Pas de test sur Intel/AMD/NVIDIA. Ne pas attribuer ce résultat à une microarchitecture ou à Metal sans autre preuve.
- Aucune mesure de performances : readbacks et écritures PNG sont volontaires et rendent ce test impropre à un benchmark FPS.
- LWJGL émet son avertissement de version JNI avec le JDK installé ; les exécutions se terminent normalement, sans erreur GL détectée.

## Suite recommandée — pas encore implémentée

1. Utiliser temporairement `-Dvoxel.greedyShadows=false` pour le rendu utilisateur ; la valeur par défaut n'a pas été changée pendant ce diagnostic.
2. Concevoir une triangulation conforme du maillage shadow : ajouter les sommets nécessaires aux jonctions, y compris aux frontières de chunks. Ne pas modifier biais/soleil/CSM pour cacher les trous.
3. Ne pas adopter directement l'éventail unitaire expérimental. Il soumet **6 231 546 sommets**, contre **5 423 442** pour la référence non fusionnée (+14,9 %) et **1 902 342** pour la fusion actuelle. C'est une preuve expérimentale, pas une optimisation prête à livrer.
4. Comparer une correction ciblée à la référence sur ces neuf cartes, puis sur plusieurs élévations solaires et la lune, avec des scènes synthétiques couvrant les jonctions entre chunks.
5. Faire ensuite une comparaison d'images finales dans le moteur réel, puis un benchmark sans readback. Une tolérance globale en pourcentage de pixels ne suffit pas : un petit nombre de gros trous est visuellement pertinent.

La grande ombre tronquée par le déchargement de la montagne reste un chantier distinct. Corriger la triangulation ne chargera pas les casters manquants.

## Reproduction

Depuis la racine du dépôt, sur macOS :

```sh
./gradlew --offline classes
# installDist est nécessaire une fois si son dossier lib n'existe pas.
diagnostic_classes=$(mktemp -d)
javac -cp 'build/classes/java/main:build/install/TOB_Voxel_Project/lib/*' \
  -d "$diagnostic_classes" tools/tests/ShadowMergeRasterDiagnostic.java
java -XstartOnFirstThread -Xmx2G \
  -cp "$diagnostic_classes:build/classes/java/main:build/install/TOB_Voxel_Project/lib/*" \
  ShadowMergeRasterDiagnostic
```

Le diagnostic imprime son répertoire unique `logs/shadow_raster_*`. Il dépend des chunks sauvegardés du monde 10 ; un fichier absent provoque un échec au lieu de générer ou modifier le monde. `DONE` signifie que les mesures sont terminées, pas que la fusion actuelle est validée. Les assertions vérifient notamment couverture CPU, compilation/link des shaders, complétude FBO et erreurs GL.
