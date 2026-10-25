# Mesh d’ombres fusionné : implémentation expérimentale

> Mise à jour finale : fusion désormais activée par défaut après qualification et correction d’un faux positif du test de lifetime (réutilisation des identifiants GL). Voir [la décision et les tests finaux](Qualification_fusion_ombres_2026-10-24.md). Le reste de ce document conserve l’historique expérimental.

## Statut

Implémenté, compilé et exécuté sur le M2. **OFF par défaut : équivalence des shadow maps non validée.** Les tests ont révélé des différences qui interdisent de présenter cette version comme une optimisation transparente prête à activer.

## Frontière et ownership

`ShadowMergeProbe.merge` partitionne les faces exposées du mesh visuel en rectangles par plan et orientation. `ShadowMesh.positions` produit six positions float par rectangle, winding CCW extérieur. Ce premier chemin utilise `glDrawArrays`, sans EBO : 72 octets par rectangle. Il ne touche ni aux textures ni à l’AO du mesh visuel.

`MeshBuilder.generateMeshData` produit les deux géométries depuis la même liste de quads. `MeshData` transporte le tableau shadow sur le heap (pas de nouvelle allocation native persistante). Les chemins direct et worker uploadent un VAO/VBO position-only sur le thread GL. Le chunk possède `ShadowMesh` : remplacement du mesh visuel efface l’ancien shadow, puis le nouveau shadow est installé dans la même séquence synchrone sans draw intermédiaire. Si aucun shadow n’est disponible, `World.renderDepth` utilise le mesh visuel. Cleanup idempotent du shadow, destruction au remplacement et au cleanup du chunk. Les métriques de mémoire des meshes incluent le VBO shadow ; le compteur de références soumises depth suit le chemin réellement utilisé.

Pas de changement de cadence solaire, cascades, biais ou shaders. L’option OFF ne génère ni ne stocke le mesh supplémentaire, sauf demande explicite de validation.

## Tests

- `./gradlew --offline installDist` : PASS.
- `ShadowMeshTest` : six orientations et winding, plan fusionné, trou, vide ; upload GL, suppression réelle VAO/VBO, cleanup répété ; remplacement dans le chunk, invalidation au remplacement visuel et cleanup chunk : PASS.
- Le contrôle de couverture du partitionneur impose la consommation unique de toutes les cellules sources. Ces tests ne prouvent pas l’identité de rasterisation.
- Harnais réel mobile terminé en 20,009 s, soleil non pausé (heure finale 0,032473296). CSV `logs/perf_test_World_10_2026-10-24_202202.csv`, console temporaire `/tmp/greedy_shadow_validation.txt`.

Le mode `voxel.validateGreedyShadows` rend successivement chaque cascade avec les deux meshes, mêmes matrices/culling, sans update ni upload intermédiaire. Il rapporte les différences exactes, les changements de couverture (depth égale à 1 contre depth différente de 1) et l’erreur absolue maximale. Il laisse la depth de référence en place. C’est un diagnostic, **pas une assertion de réussite ni un benchmark FPS**. Ne pas le combiner avec la validation de culling, qui teste une autre variable.

Sur 12 comparaisons : 0 à 29 différences de couverture par carte 2048². Des centaines de milliers de valeurs diffèrent bit à bit ; l’erreur maximale atteint presque 1 sur certaines cartes. La première cascade du premier contrôle n’a aucune différence de couverture et une erreur maximale de 3,52e-6, mais ce cas favorable ne généralise pas aux autres cartes.

**Conclusion :** des différences ne se limitent pas à de petits arrondis. La cause n’est pas prouvée : frontières de rasterisation, jonctions en T et différences de triangulation sont des hypothèses à isoler, pas un diagnostic établi. Aucune tolérance arbitraire n’a été utilisée pour déclarer PASS. Pas de benchmark de gain annoncé tant que la correction visuelle reste incertaine.

## Reproduction

```sh
./gradlew --offline installDist
java -XstartOnFirstThread -cp 'build/install/TOB_Voxel_Project/lib/*' tools/tests/ShadowMeshTest.java
JAVA_OPTS='-Dvoxel.testHarness=true -Dvoxel.testStatic=false -Dvoxel.greedyShadows=true -Dvoxel.validateGreedyShadows=true' build/install/TOB_Voxel_Project/bin/TOB_Voxel_Project
```

Pour observer le nouveau rendu seul : retirer `voxel.validateGreedyShadows`. Pour revenir au rendu habituel : retirer les deux propriétés. Aucun défaut de production n’a été changé.

## Travail restant avant activation

Localiser les texels en désaccord et comparer des cas isolés (plan, escalier, trou, frontière de chunk) sous les mêmes matrices lumineuses. Mesurer séparément les erreurs de faible amplitude et les discontinuités ; contrôler les images finales et plusieurs heures solaires. Corriger ou expliquer les écarts avant ABBA. La pondération du taux de fusion par cascade, les pics de meshing/streaming et l’éventuelle indexation du mesh shadow restent des expériences ultérieures.

## Investigation complémentaire : écarts et lever du soleil

Le test humain avec l’option activée ne révèle pas de régression visible. Les ombres décollées et l’éclairage des caves au lever du soleil existaient avant. Cette observation est distincte d’une preuve d’identité des depth maps.

Un diagnostic supplémentaire, sans modification du rendu, compte les erreurs absolues > 1e-4 et recherche une correspondance réciproque dans le voisinage 3×3 des deux cartes. Le seuil sert à classer les écarts, **pas à déclarer le rendu correct**. Run mobile de 20,009 s terminé normalement, console temporaire `/tmp/greedy_shadow_edges.txt`.

| Temps approximatif | Erreurs > 1e-4, cascades 0 / 1 / 2 | Correspondances voisines réciproques |
|---|---|---|
| 0 s | 0 / 15 / 40 | 0 / 1 / 21 |
| 5 s | 50 / 125 / 56 | 4 / 39 / 10 |
| 10 s | 37 / 143 / 80 | 2 / 16 / 12 |
| 15 s | 44 / 108 / 99 | 8 / 19 / 7 |

Au maximum 143 / 4 194 304 texels, soit 0,00341 %, dépassent ce seuil dans une carte. Le nombre de différences bit à bit surestime donc nettement la fréquence des grandes différences numériques. Le changement de triangulation est compatible avec des arrondis de profondeur différents, mais le test de voisinage **ne démontre pas** que toutes les grandes erreurs sont des déplacements de frontières. Des trous de rasterisation ou jonctions en T restent à isoler. Le biais du shader de lighting ne peut pas expliquer les différences entre ces depth maps : il n’intervient pas dans leur génération.

### Biais et artefacts préexistants

`voxel_fragment_shader.glsl::computeBias` produit au maximum 0,0035 × cascadeScale à incidence rasante, avec cascadeScale plafonné à 2. Cette valeur est soustraite à la profondeur normalisée. `CSMCalculator::computeCascadeMatrix` utilise un intervalle de profondeur de 4 × sphereRadius avec la marge actuelle de 0,5.

Calcul à partir des paramètres du harnais (FOV 90°, aspect 2268/1382, near 0,1, shadow far 200, lambda 0,62) : rayons 54,5 / 123,5 / 410,1875 ; intervalles Z 218 / 494 / 1640,75. Le biais maximal correspond donc à **0,763 / 3,458 / 11,485 unités monde le long de l’axe lumineux**. Ce ne sont pas des distances mesurées de séparation sur le sol ; ce sont les équivalents métriques du biais. Le facteur cascade accroît encore un biais déjà amplifié par l’intervalle Z.

Haute confiance sur le calcul, confiance moyenne sur l’attribution des symptômes sans A/B à pose figée. Le biais excessif est une explication forte du peter panning à faible élévation. Il ne constitue pas une singularité de normalisation de sunDir prouvée. L’ambient hémisphérique du shader est par ailleurs indépendant des shadow maps : une cave peut rester claire même lorsque la visibilité directe est correcte. Enfin, le culling de faces dos au soleil est attendu pour un volume fermé ; l’absence de la face inférieure dans la depth map ne prouve pas à elle seule une fuite, la face supérieure devant pouvoir occulter.

Prochain contrôle ciblé : même pose/heure, ancien mesh, biais normal contre biais zéro (F9 deux fois depuis le mode normal), puis même comparaison avec mesh fusionné. L’acné éventuelle à biais zéro est attendue et ne justifie pas de garder zéro en production. Examiner aussi les casters exclus par le volume lumineux/rayon lorsque le soleil est bas. Ne pas changer simultanément bias, culling et géométrie.

Références : [Microsoft — biais et peter panning](https://learn.microsoft.com/en-us/windows/desktop/DxTechArts/cascaded-shadow-maps), [OpenGL FAQ — profondeurs de primitives coplanaires différentes](https://opengl.org/archives/resources/faq/technical/polygonoffset.htm). Aucun correctif visuel ni changement du défaut greedy n’a été appliqué pendant cette investigation.
