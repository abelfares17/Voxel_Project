# Qualification automatisée de la fusion des ombres

## Décision finale : fusion activée par défaut

La validation ciblée est terminée. `RenderConfig.GREEDY_SHADOWS` est désormais ON par défaut, partagé par génération, rendu et métriques. Retour arrière : `-Dvoxel.greedyShadows=false`. Les sections suivantes conservent l’historique des étapes et de leurs réserves initiales.

Le contrôle utilisateur `logs/greedy_suite_5p1lxgsa` donne quatre comparaisons OFF/OFF parfaitement identiques. OFF/ON : 0 / 350 / 96 / 35 pixels différents sur 3 134 376, aucune différence de profondeur caméra. Le contrôle manuel utilisateur ne révèle pas de régression. Ce n’est pas une preuve d’identité pixel à pixel dans toutes les scènes, mais c’est suffisant pour accepter l’optimisation avec son fallback et ses limites documentées.

### Échec de test expliqué et corrigé

`MeshBoundsGlTest` vérifiait les anciens identifiants **après** création du nouveau mesh shadow. Or les noms OpenGL peuvent être réutilisés après suppression. L’investigation a observé explicitement : ancien VAO visuel 1 → nouveau VAO shadow 1 ; ancien VBO visuel 1 → nouveau VBO shadow 1.

Le test utilise désormais un `CheckedChunk` qui appelle le vrai `Chunk.setMesh`, puis vérifie immédiatement la suppression des anciens VAO/VBO/EBO visuels et VAO/VBO shadow, avant toute nouvelle allocation shadow. Après upload, il vérifie que les noms éventuellement redevenus actifs appartiennent au nouveau shadow. Ce n’est pas une suppression de l’assertion de lifetime : sa position a été rendue correcte. Aucun changement du mécanisme de destruction de production n’a été nécessaire.

Build final `installDist` : PASS. Tests réels OpenGL depuis un répertoire temporaire isolé : `MeshBoundsGlTest` avec défaut ON : PASS ; fallback OFF : PASS ; `ShadowMeshTest` : PASS. Ajout/suppression, worker, remplacement, unload/re-upload, chemin direct, vide et cleanup sont couverts par ces tests ciblés. Pas de nouveau benchmark nécessaire : aucun algorithme de rendu n’a changé depuis l’ABBA, seulement le défaut de configuration.

**Conclusion opérationnelle : conserver le gain mesuré (+21,36 % FPS sur le scénario), accepter les quelque 74 Mo de buffers supplémentaires observés et passer au prochain chantier.** Les artefacts CSM préexistants restent séparés ; aucune promesse de correction du détachement ou du shimmering n’est associée à cette activation.

## Reproduction

```sh
./gradlew --offline installDist
python3 tools/benchmark_greedy_shadows.py
```

La suite finale exécute séquentiellement un contrôle OFF/OFF, une comparaison OFF/ON, quatre runs FPS OFF–ON–ON–OFF, puis deux profils GPU séparés. Options : `--mode images` ou `--mode bench`. Les runs utilisent la fenêtre réelle discrète, caméra et soleil mobiles, sans sauvegarde du monde. Ne pas compiler ni lancer un autre jeu pendant la suite.

Chaque suite crée un répertoire unique `logs/greedy_suite_*` : consoles, PNG pour les images, `results.json` et `aggregate.json`. Les CSV CPU/GPU restent dans `logs/` et sont référencés dans le JSON. Le script vérifie fin normale, cleanup, erreurs, flags, état de fenêtre et comparabilité des résolutions. Le script ne déclare pas les écarts RGB acceptables automatiquement.

## Contrat de comparaison

Le comparateur upload les meshes une seule fois avant A. Il régénère les trois cascades avec les matrices A courantes, sans recalcul solaire ni mouvement caméra, puis rend ciel/soleil/voxels avant l’UI. Il recommence avec l’autre mesh, sans upload, update ou avancement temporel du shader. Culling, biais et filtrage ne changent pas. La profondeur caméra doit rester identique ; les différences RGB sont mesurées sur le framebuffer RGBA8 lu avant swap. Cela ne compare pas les valeurs float intermédiaires.

Le diagnostic est réservé au CSM direct initialisé. Il refuse les autres comparateurs simultanés. Les images de différence sont amplifiées ×8 et saturées à 255 pour inspection, pas pour calcul des métriques. `above8` compte les pixels dont au moins un canal RGB diffère de plus de 8 niveaux sur 255 : c’est un indicateur descriptif, pas une tolérance de réussite.

Les mesures FPS utilisent uniquement `3 < somme(dt) ≤ 19 s`. Les profils GPU utilisent `3 < elapsed_s ≤ 19 s`. FPS agrégés = somme des frames / somme des durées ; aucun run avec readback, PNG ou queries GPU n’entre dans les FPS ABBA.

## Images finales : premier parcours

Sources : [console](../logs/greedy_suite_twpfdzyp/images.txt), [résultats structurés](../logs/greedy_suite_twpfdzyp/results.json).

| Temps du harnais | Pixels différents / 3 134 376 | Pixels avec écart > 8 | Maximum canal / 255 | Profondeur caméra |
|---|---:|---:|---:|---|
| 0,002 s | 0 | 0 | 0 | identique |
| 5,005 s | 182 | 27 | 29 | identique |
| 10,013 s | 120 | 3 | 32 | identique |
| 15,013 s | 59 | 10 | 18 | identique |

Au maximum **0,00581 %** des pixels diffèrent sur ces captures. Les différences sont rares mais réelles, non nulles et parfois contrastées. Cela ne prouve pas l’absence de défauts temporels sur toute une animation. Les readbacks et écritures PNG ralentissent ce run ; son parcours entre contrôles diffère légèrement des benchmarks, mais chaque paire A/B partage strictement le même état.

Exemple à 5 s : [OFF](../logs/greedy_suite_twpfdzyp/images/frame_5.005_off.png), [ON](../logs/greedy_suite_twpfdzyp/images/frame_5.005_on.png), [différence ×8](../logs/greedy_suite_twpfdzyp/images/frame_5.005_diff_x8.png).

## Limites

Le parcours actuel observe le terrain extérieur près du lever du soleil. Ce n’est pas une suite exhaustive de scènes de cave, angles solaires et frontières. Les tests de remeshing complètent la gestion des ressources, mais ne remplacent pas une comparaison d’images pendant le streaming massif. Le benchmark court ne mesure pas complètement les pics de chargement initial. Aucune correction du détachement préexistant n’est incluse et aucun changement de valeur par défaut de `greedyShadows` n’est effectué par cette suite.

## Résultats finaux de cette série

| Run | FPS | Moyenne ms/frame | P95 ms |
|---|---:|---:|---:|
| A1 OFF | 65,470 | 15,274 | 19 |
| B1 ON | 79,615 | 12,560 | 16 |
| B2 ON | 79,067 | 12,647 | 17 |
| A2 OFF | 65,279 | 15,319 | 19 |

Agrégation pondérée : **65,374 → 79,341 FPS (+21,36 %)**, environ **15,297 → 12,604 ms/frame**. Les deux témoins sont proches, les deux runs optimisés également : le gain est cohérent dans cette session. Résolution 2268×1382 et état de fenêtre comparables, soleil et caméra mobiles.

Profils GPU séparés : passe shadow **6,411 → 3,430 ms (−46,5 %)** ; références géométriques shadow **31,94 → 10,59 millions (−66,9 %)** ; environ 1 735 draws dans les deux cas. La passe voxel est mesurée à 8,848 → 9,545 ms dans cette paire instrumentée : ne pas attribuer ce résultat à une cause matérielle non mesurée, ni additionner ces temps pour prédire les FPS sans instrumentation. Le bénéfice observé porte principalement sur la production des ombres, pas sur une réduction des draw calls.

Les octets des meshes installés passent d’environ **375,7 à 449,6 Mo**, soit **+73,9 Mo** de buffers GPU comptabilisés. Ce n’est ni toute la VRAM ni un pic RAM/native de remeshing.

**Conclusion : optimisation de performance convaincante sur le scénario testé ; forte indication de proximité visuelle, mais pas preuve d’équivalence générale.** Le mode est un bon candidat à l’usage normal avec le fallback conservé. Pas d’activation automatique dans cette intervention : les écarts rares restent documentés, les caves et autres heures solaires ne sont pas couverts par cette série.

Précision d’exécution : cette série a été lancée avant l’ajout du contrôle OFF/OFF au script. Ce contrôle est désormais prévu par la suite mais **n’a pas été exécuté dans les résultats ci-dessus**. Les nouvelles assertions shadow ajoutées à `MeshBoundsGlTest` n’ont pas été relancées dans cette intervention ; les tests GL de durée de vie déjà réussis appartiennent à l’intervention précédente. Le dernier build exécuté comprend la comparaison OFF/ON ; reconstruire avant de lancer la suite finale avec son contrôle OFF/OFF.
