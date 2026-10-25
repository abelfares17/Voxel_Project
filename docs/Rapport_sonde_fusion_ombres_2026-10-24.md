# Expérience de fusion des faces d’ombres

## Périmètre

Première étape de la piste proposée : mesurer le potentiel avant de créer un mesh GPU supplémentaire. Aucun changement de rendu ou de ressources GPU. La génération du mesh appelle `ShadowMergeProbe` uniquement avec `-Dvoxel.probeShadowMerge=true` (OFF par défaut).

La sonde prend les quads réellement produits, les regroupe par axe, orientation et plan, puis couvre leurs cellules par rectangles gloutons. Elle ignore matériaux et AO, qui ne sont pas consommés par le shader de profondeur actuel. Elle vérifie que chaque cellule est couverte exactement une fois. Elle suppose les faces unitaires axis-aligned à coordonnées entières du mesher actuel ; ce n’est pas un analyseur de meshes génériques.

## Résultat réel

Harnais mobile World 10 terminé normalement en 20,006 s ; soleil non pausé, heure finale 0,03316833. Sur **1 088 générations de meshes** : **3 469 899 faces → 1 119 569 rectangles**, soit **67,73 % de réduction**, facteur **3,10**. Les générations peuvent inclure des remeshings : ce total n’est ni un inventaire unique des chunks installés, ni une mesure pondérée par cascade.

Console de l’expérience : `/tmp/shadow_merge_probe.txt` (temporaire). CSV associé : `logs/perf_test_World_10_2026-10-24_201631.csv`. **Ne pas utiliser ses FPS comme benchmark** : allocations, calcul de fusion et logs supplémentaires perturbent le CPU.

## Vérifications

- `./gradlew --offline installDist` : PASS.
- `java -cp 'build/install/TOB_Voxel_Project/lib/*' tools/tests/ShadowMergeProbeTest.java` : PASS, cas vide, plan 16×16 fusionné en un rectangle, trou conservé.
- Invariants de couverture vérifiés sur toutes les générations de l’expérience réelle.
- Aucun test de rasterisation du mesh fusionné : il n’est pas encore généré pour le GPU.

## Décision

Le potentiel géométrique justifie maintenant l’étape suivante : produire un mesh shadow position-only fusionné, l’installer avec le mesh visuel de la même génération, comparer les depth maps puis mesurer en ABBA. Ne pas annoncer 3× plus de FPS : ni le nombre de draw calls ni le travail de rasterisation ne diminuent automatiquement dans les mêmes proportions. La pondération par cascade, les coûts mémoire/streaming et la correction des jonctions après triangulation restent à vérifier.

## Reproduire

```sh
JAVA_OPTS='-Dvoxel.testHarness=true -Dvoxel.testStatic=false -Dvoxel.probeShadowMerge=true' build/install/TOB_Voxel_Project/bin/TOB_Voxel_Project
```

Cette intervention implémente la sonde de décision, pas encore l’optimisation GPU. Aucun refactor de CSM, de cadence solaire ou du mesh visuel.
