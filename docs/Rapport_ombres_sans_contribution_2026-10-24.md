# Éviter les ombres sans contribution directe — 24 octobre 2026

## Modification isolée

Dans le chemin normal de `voxel_fragment_shader.glsl`, `diff = max(dot(N,L), 0)` est calculé avant les ombres. Le facteur d'ombre ne contribue ensuite qu'à `sunColor * diff * shadow`. L'option `voxel.skipUnlitShadows` permet de ne pas appeler `calculateShadow` lorsque `diff == 0`, et de laisser `shadow = 1` dans ce cas.

L'éclairage indirect, l'AO, le fog et l'albedo restent calculés exactement comme avant. Les modes debug situés avant ce chemin ne sont pas modifiés. Pas de seuil approché (tel que `diff < 0.1`), pas de suppression de faces, pas de modification des shadow maps ou des biais. La passe de génération des ombres continue de rendre tous ses casters habituels. La propriété peut être changée entre processus sans reconstruire.

`RenderConfig` fournit le réglage, `VoxelRenderer` envoie un uniform entier. Son overload de diagnostic permet de changer seulement cette option entre deux rendus, sans muter un flag global. Les logs CPU/GPU indiquent respectivement `skipUnlitShadows` / `skip_unlit_shadows`.

La conclusion mathématique suppose des calculs finis : ce changement ne constitue pas une correction générale des NaN potentiels ailleurs. Le sampling existant est conservé : pas de `texelFetch` ni de PCF matériel ajouté dans cette expérience. Le depth array actuel utilise le filtrage nearest sans mipmaps ; le changement n'ajoute pas de lecture d'atlas de couleur dans une branche divergente.

## Validation sur les shaders de production

Le comparateur d'images existant a reçu un mode distinct `voxel.validateUnlitShadows=true`. Il refuse d'être combiné avec la comparaison de culling caméra : une seule variable change entre A et B.

À chaque contrôle : shadow maps générées une fois, puis ciel/soleil/voxels OFF et ON avant l'UI, mêmes matrices, meshes, heure et échantillon temporel. Un seul traitement des uploads, aucun entre les deux draws. Le culling est identique. Lecture RGBA8 et profondeur float, comparaison exacte ; buffers natifs libérés en `finally`.

Run [18:18:00](../logs/perf_test_World_10_2026-10-24_181800.csv), [console conservée](../logs/unlit_validation_2026-10-24.txt) : **quatre PASS**, vers 0, 5, 10 et 15 s, chacun sur **3 134 376 pixels**. Zéro différence couleur ou profondeur sur ces frames. Soleil non pausé, temps final 0,03252, scénario terminé normalement, aucune erreur GL signalée.

Ce run est exclu de toute conclusion de performance. Cela vérifie les images affichées RGBA8, pas les valeurs float intermédiaires avant conversion du framebuffer. Cela ne couvre ni tous les mondes, ni le cycle solaire complet, ni chaque mode debug dormant. Les shaders réels ont bien compilé/linké sur le M2 Pro ; le build Java seul n'aurait pas suffi à le prouver.

## Protocole FPS et GPU

Indexation et format compact ON ; cache d'uniforms et bornes précises OFF ; trois cascades 2048², filtre 1, stage 6. World 10, caméra et soleil mobiles. Retina 2268 × 1382, fenêtre 75 %, sans focus ni capture du curseur, visible non minimisée.

ABBA non instrumenté, puis deux profils GPU séparés. A = option OFF, B = ON. Aucun benchmark parallèle ou compilation concurrente. Analyse de `3 < somme(dt) ≤ 19 s` pour les CSV CPU, `3 < elapsed_s ≤ 19 s` pour les profils GPU ; FPS = nombre de frames / somme des durées. Les profils et validations ne servent pas à annoncer les FPS normaux. La trajectoire intégrée par dt diffère légèrement entre processus.

## Résultats et décision

Build `./gradlew --offline installDist` : PASS. Compilation/link des shaders et exécution réelle : PASS. `git diff --check` : PASS.

| Run non instrumenté | Option | FPS | Moyenne ms/frame | P95 ms | CSV |
|---|---|---:|---:|---:|---|
| A1 | OFF | 65,288 | 15,317 | 19 | [18:18:52](../logs/perf_test_World_10_2026-10-24_181852.csv) |
| B1 | ON | 64,962 | 15,394 | 19 | [18:19:15](../logs/perf_test_World_10_2026-10-24_181915.csv) |
| B2 | ON | 64,762 | 15,441 | 20 | [18:19:37](../logs/perf_test_World_10_2026-10-24_181937.csv) |
| A2 | OFF | 64,016 | 15,621 | 21 | [18:19:59](../logs/perf_test_World_10_2026-10-24_181959.csv) |

Agrégation pondérée par les durées : OFF = 2 069 frames / 32,002 s = **64,652 FPS** ; ON = 2 075 / 31,991 s = **64,862 FPS**, soit **+0,32 %** et 15,467 → 15,417 ms/frame. La variation entre témoins dépasse ce petit écart. **Aucun gain FPS robuste démontré**, ni régression établie. Les quatre logs confirment framebuffer 2268 × 1382, fenêtre visible, non minimisée, non focalisée, curseur normal ; soleil mobile sur une plage d'environ 0,0049 à 0,0315 dans la fenêtre analysée.

| Profils GPU séparés | OFF | ON |
|---|---:|---:|
| Temps moyen passe shadow | 6,289 ms | 6,339 ms |
| Temps moyen passe voxel | 8,836 ms | 8,866 ms |
| Draws shadow moyens | 1 735,98 | 1 735,22 |
| Draws voxel moyens | 348,31 | 348,47 |
| Références vertex shadow moyennes | 31,958 millions | 31,943 millions |
| Références vertex voxel moyennes | 6,457 millions | 6,461 millions |

Sources : [profil OFF](../logs/gpu_pass_2026-10-24_182022_375.csv), [profil ON](../logs/gpu_pass_2026-10-24_182044_739.csv). Pas de réduction observable du temps voxel dans cette paire. Les petites variations géométriques viennent de parcours intégrés par dt, pas d'un nouveau culling. Les valeurs GPU ne sont pas un benchmark FPS normal ; les queries peuvent perturber l'exécution. Aucune conclusion sur cache, divergence matérielle ou traduction du driver n'est justifiée par ces seules mesures.

**Décision : implémentation conservée comme expérience activable, mais `voxel.skipUnlitShadows=false` par défaut.** Éviter des appels GLSL ne garantit pas un gain du GPU. Le résultat concerne ce parcours court, cet angle solaire et ce matériel, pas toutes les scènes. Il ne démontre pas non plus que le fragment shader est gratuit : la passe voxel inclut d'autres coûts. Avant toute généralisation, refaire une série alternée à d'autres heures solaires et avec des vues présentant davantage de faces sans éclairage direct. Pas de changement supplémentaire de filtre, biais ou cadence CSM dans cette intervention.

## Reproduire

```sh
./gradlew --offline installDist
JAVA_OPTS='-Dvoxel.testHarness=true -Dvoxel.testStatic=false -Dvoxel.skipUnlitShadows=true -Dvoxel.gpuTiming=false' \
  build/install/TOB_Voxel_Project/bin/TOB_Voxel_Project
```

Mettre `skipUnlitShadows=false` pour A. Pour la validation, ajouter `-Dvoxel.validateUnlitShadows=true` sans queries GPU. Pour le profilage, utiliser `gpuTiming=true` sans validation. Le mode de vérification change explicitement l'option entre ses deux draws, quel que soit le défaut global.

Le comportement attendu est identique visuellement ; si la scène ou les matériaux évoluent pour utiliser les ombres dans l'illumination indirecte ou un effet supplémentaire, cette condition devra être réévaluée.
