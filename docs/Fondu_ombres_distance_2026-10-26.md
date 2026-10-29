# Fondu des ombres longues et résidence cohérente

## Comportement livré

Une occultation conserve son contraste jusqu'à **80 blocs** de séparation caster/récepteur suivant le rayon lumineux, puis s'atténue avec `smoothstep` jusqu'à disparition à **128 blocs**. À 104 blocs, une occultation pleine donne une visibilité de 0,5. La distance à la caméra n'entre pas dans ce calcul.

Ce compromis raccourcit volontairement les ombres très longues. Il ne change ni soleil/lune, ni ambiance/fog, ni biais/normal-offset, ni fusion. Un seul atlas de trois cascades reste utilisé. Ce n'est pas une simulation physique de pénombre.

### Contrôle

- **Maj+F4** : activer/désactiver seulement le fondu, sans changer la résidence.
- **F4** seul : modes de diagnostic existants ; mode 1 montre la visibilité finale avec le fondu courant, mode 5 les cascades unies.
- **F3** : portée, indispensable prêt/demandé, préchargement prêt/demandé, couverture `COMPLETE`/`DEGRADED` et pression sur le budget.
- **F6** : figer/reprendre l'heure. **F2** : capture avec confirmation UI ; hors benchmark.

| Option JVM | Défaut | Rôle |
|---|---:|---|
| `voxel.shadowFade` | `true` | Fondu shader, indépendant du chargement |
| `voxel.shadowFadeStart` | `80` | Début du fondu en blocs |
| `voxel.shadowFadeEnd` | `128` | Extinction en blocs et portée du contrat de résidence |
| `voxel.shadowCasterBudget` | `640` | Chunks supplémentaires hors carré visible, union indispensable/préchargement |
| `voxel.shadowCasterLoadsPerUpdate` | `4` | Nombre maximum de nouveaux casters chargés par update |
| `voxel.legacyCasterResidency` | `false` | Ancienne sélection comme référence de mesure, sans garantie de couverture |
| `voxel.shadowCasterResidency` | `true` | Désactivation complète de l'extension pour diagnostic, sans garantie |
| `voxel.validateShadowCoverage` | `false` | Échec explicite si l'empreinte PCF/normal-offset dépasse la garde |

Les paramètres de fondu doivent être finis et respecter `0 ≤ start < end`. Un budget incapable de couvrir l'orbite est refusé ; la portée n'est pas raccourcie dynamiquement. L'ancien `shadowCasterReach` reste un plafond configuré et la portée détaillée indispensable doit y tenir avec sa garde ; sa valeur par défaut 256 convient. Ne pas confondre l'ancienne extension horizontale avec la nouvelle distance suivant le rayon lumineux.

Les toggles ne sont pas sauvegardés. Aucun format de monde n'est modifié.

## Chemin CPU → GPU

`ShadowDistance.depthSpan` calcule `2 / length(m02,m12,m22)` sur les matrices orthographiques **après** le fit des casters. `VoxelRenderer` transmet `shadowDepthWorldSpans[]`, les seuils et l'activation du snapshot de frame.

`shadowTapVisibility` conserve la comparaison avec biais, mais utilise la différence de profondeur **sans biais** pour la séparation mondiale. La fonction est appliquée à chaque tap avant l'interpolation PCF et le mélange des cascades. Les filtres manuels 0/1/2, le stage de production 6 et la référence single-tap stage 5 sont couverts. Aucun fetch supplémentaire de l'atlas, aucun nouvel atlas/FBO de production. Les chemins hardware-compare et leapfrog dormants ne sont pas réactivés.

Les profondeurs clear et les taps non occultés restent éclairés. Le normal-offset peut légèrement déplacer le point utilisé pour estimer la séparation ; ce système ne change pas son réglage.

## Contrat de résidence

`ShadowCasterResidency.coherentPlan` distingue deux listes disjointes :

1. **Required** : carré visible balayé par `lightDirection * fadeEnd`, projeté en XZ, avec une garde de **4 blocs**.
2. **Prefetch** : extension supplémentaire de **16 blocs** autour de ce balayage.

Les indispensables ne sont jamais tronqués pour respecter le budget. Le préchargement peut l'être et passe après eux dans la file de chargement. L'ordre est déterministe. Le toggle shader ne change aucune de ces listes.

La validation préalable de l'orbite utilise 720 intervalles et élargit chaque balayage central par une borne du déplacement possible dans l'intervalle : ce n'est pas seulement une vérification de quelques heures isolées. Le test à deux minutes d'intervalle sur la journée trouve au maximum **573 chunks sélectionnés** avec les défauts, sous le plafond 640.

Le monde retire les anciennes extras inutiles avant d'en charger de nouvelles. Les chunks indispensables courants sont conservés. Les meshes de frontière sont reconstruits et les résultats de workers périmés rejetés comme précédemment. Une erreur de sauvegarde conserve le chunk sale ; le chargement d'extras attend alors une place au lieu d'aggraver le dépassement. Une téléportation peut néanmoins laisser transitoirement trop de chunks précédemment visibles en cas d'erreur de sauvegarde : la protection des modifications utilisateur prime.

La pose de chargement est recalculée après le déplacement du joueur. Un état céleste commun sert au plan puis au rendu. Le plan cohérent est recalculé lorsque la direction change ; il ne dépense pas la garde dans une erreur de cache angulaire. Pendant `NONE`, le plan prépare l'astre montant sans modifier l'éclairage effectif.

Le chargement initial attend les indispensables et le préchargement sélectionné. Pendant le jeu, un retard ne bloque pas le joueur : le HUD signale la couverture dégradée. Le snapshot de couverture est constitué après upload des meshes ; le renderer y ajoute la validité de la garde d'échantillonnage. F5 en mode de fit legacy invalide également l'annonce de couverture complète.

## Tests et images

### Tests automatisés

- `ShadowFadeTest` : 2 001 valeurs du fondu, séparation mondiale avant/après fit, plusieurs élévations et profondeurs, garde d'échantillonnage, certificat de budget, 21 600 segments aléatoires et 77 760 cas aux frontières/avec garde, troncature du seul préchargement sous pression, préparation pendant NONE : **PASS**.
- `ShadowFadeGlTest` : **360 comparaisons GPU** avec les shaders vertex/fragment et depth de production, distances 40/80/104/128/160, filtres 0/1/2, stages 5/6, trois projections ajustées, rotation de vue et retrait d'un caster isolé éteint : **PASS**.
- Le même test reproduit la limite des superpositions : le caster à 160 blocs masque celui à 40 dans la depth map ; le fondu ne reconstruit pas cette seconde occultation.
- `ShadowResidencyGlTest` : monde temporaire, préchargement, éviction/relecture des modifications, frontières disque/déchargement, rejet des résultats périmés, SUN/NONE/MOON et budget : **PASS**.
- `DebugHudCaptureTest` : Maj+F4 ne change pas le mode F4 ; touche maintenue sans double toggle ; F4 seul garde son cycle ; F3, F2 et notification : **PASS**.
- Régressions `PlayerOrientationTest`, `MeshBoundsGlTest` fusion ON, `ShadowMeshTest`, `ShadowCasterVolumeTest` et `ShadowCasterDepthGlTest` : **PASS**.
- `CelestialTimeTest`, dans un répertoire temporaire : horloge/orbite, migration, conservation des métadonnées, persistance et overrides : **PASS**.

Les tests écrivant des données utilisent des répertoires temporaires. Les tests de montagne et le harnais n'écrivent pas dans les sauvegardes utilisateur.

### Replay de World 10

Résultats : [CSV des cinq poses](../logs/shadow_fade_mountain_18200932274542778810/results.csv), soleil figé à **17 h 19**, fenêtre cachée, framebuffer 1280×800. Déplacement X≈828→1028, chargement stabilisé à chaque pose. Chaque paire réutilise la même heure, pose, géométrie et projection.

Exemple à X≈928 : [sans fondu](../logs/shadow_fade_mountain_18200932274542778810/pose2_fade_off.png), [avec fondu](../logs/shadow_fade_mountain_18200932274542778810/pose2_fade_on.png), [cascades](../logs/shadow_fade_mountain_18200932274542778810/pose2_cascades.png).

Le fondu modifie 253 920 à 349 683 pixels de visibilité selon la pose, uniquement en les éclaircissant. Les ombres courtes visibles dans ces images restent contrastées. Les 387 indispensables et 152 préchargés sont prêts avant chaque paire.

La comparaison fusion/non-fusion comporte **11 à 49 pixels différents** selon la pose. Certains écarts en facteur shadow atteignent 179/255, mais ils sont déjà présents avec le fondu OFF : **zéro nouveau gros écart (>16/255) sur les cinq comparaisons**. Ce n'est pas une identité raster et cette série ne clôt pas tous les défauts de fusion possibles. Le premier seuil absolu de 16 a justement échoué ; la comparaison OFF/ON a permis d'identifier les écarts préexistants au lieu de les attribuer au fondu.

Les premiers exports `shadow_fade_mountain_7616959551512047349` sont invalides (registre des blocs non initialisé, terrain vide) et ne constituent aucune preuve. Le test comporte désormais des assertions sur les draws et sur l'exercice effectif des ombres longues. La série `10332734926333362733` s'arrête au premier écart fusion préexistant. Seule la série complète liée ci-dessus sert à cette qualification.

## Performances

Résultats bruts : [suite ABBA](../logs/fade_suite_ow785695/results.json), [agrégats](../logs/fade_suite_ow785695/aggregate.json). Douze exécutions, caméra **et soleil mobiles**, World 10, framebuffer Retina **2268×1382**, stage 6, meshes compacts/indexés et fusion ON. Fenêtre visible non focalisée, curseur libre, aucune capture/readback d'image pendant ces runs. Chaque run dure environ 20 secondes ; analyse entre 3 et 19 secondes. Les FPS agrégés sont calculés par nombre de frames / durée totale, pas par moyenne des FPS instantanés.

| Comparaison FPS sans instrumentation GPU | A1 | B1 | B2 | A2 | Agrégat A → B |
|---|---:|---:|---:|---:|---:|
| Même nouvelle résidence, fondu OFF → ON | 74,04 | 74,08 | 74,10 | 73,83 | **73,94 → 74,09 (+0,21 %)** |
| Ancienne résidence sans fondu → nouvelle résidence avec fondu | 73,94 | 74,10 | 74,49 | 73,54 | **73,74 → 74,30 (+0,75 %)** |

Ces écarts ne démontrent **pas un gain FPS robuste**. Ils ne montrent pas non plus de régression proche du seuil de 5 % dans cette scène. Les buffers de meshes passent d'environ **739,6 MB à 688,9 MB** pour la comparaison globale (−6,9 % ; octets décimaux, pas toute la VRAM). Ce résultat local ne prédit pas le coût à toutes les heures/poses.

Profil GPU séparé, même nouvelle résidence, valeurs moyennes en millisecondes :

| Passe | A1 OFF | B1 ON | B2 ON | A2 OFF | Moyenne OFF → ON |
|---|---:|---:|---:|---:|---:|
| Voxels, où s'exécute le fondu | 9,496 | 9,539 | 9,597 | 9,776 | **9,636 → 9,568 (−0,068 ms)** |
| Shadow | 5,587 | 5,590 | 5,568 | 5,644 | 5,616 → 5,579 |

Le dernier profil A2 est plus lent, y compris en UI : la différence négative n'est donc **pas une accélération prouvée du shader**. Même la comparaison B1/A1 donne seulement +0,043 ms voxel. Aucun dépassement du budget initial de +0,5 ms n'est observé. Les FPS des runs instrumentés (46,6–50,6) ne sont pas comparés aux FPS normaux : l'instrumentation perturbe fortement l'exécution sur cette machine.

**Décision : fondu activé par défaut**, les deux seuils d'alerte n'étant pas dépassés dans cette qualification. Il reste désactivable indépendamment avec Maj+F4 ou `voxel.shadowFade=false`. Répéter ces mesures si la scène ou le matériel change ; une seule série courte ne constitue pas une borne universelle de coût.

En mouvement, la couverture stricte complète est observée sur **96,9–97,5 %** des frames des runs shader, contre 100 % aux poses statiques stabilisées du replay. Il reste donc des périodes de reconstruction/streaming signalées `DEGRADED` : cette livraison ne prouve pas une disponibilité parfaite à chaque frame de déplacement. En moyenne, environ 382 indispensables et 152 préchargés sont demandés, sous le budget. Les runs legacy affichent volontairement une couverture cohérente fausse : ce diagnostic ne transforme pas leur politique tronquée en garantie.

Les nouvelles colonnes CSV suffixées `PreviousFrame` décrivent explicitement le dernier snapshot rendu, car le logger CPU est alimenté avant le rendu courant. Le HUD décrit le snapshot de la frame rendue. Ne pas interpréter ces colonnes comme une mesure anticipée des uploads à venir.

## Reproduction

Depuis la racine du projet :

```sh
./gradlew --offline installDist
java -cp 'build/install/TOB_Voxel_Project/lib/*' tools/tests/ShadowFadeTest.java
java -XstartOnFirstThread -cp 'build/install/TOB_Voxel_Project/lib/*' tools/tests/ShadowFadeGlTest.java
java -XstartOnFirstThread -Dvoxel.testHarness=true -Dvoxel.validateShadowCoverage=true \
  -cp 'build/install/TOB_Voxel_Project/lib/*' tools/tests/ShadowFadeMountainTest.java
python3 tools/benchmark_shadow_fade.py
```

Test de résidence avec écritures isolées (le répertoire temporaire est conservé pour inspection) :

```sh
project_dir="$PWD"
residency_test_dir=$(mktemp -d /tmp/shadow-fade-residency.XXXXXX)
(cd "$residency_test_dir" && java -XstartOnFirstThread \
  -cp "$project_dir/build/install/TOB_Voxel_Project/lib/*" \
  "$project_dir/tools/tests/ShadowResidencyGlTest.java")
```

Ne pas reprendre les anciens paramètres `shadowCasterReach=64`/`shadowCasterBudget=40` pour ce test cohérent : ils ne couvrent pas la portée demandée. Ils restent uniquement utilisables avec la référence legacy explicite.

Pour le contrôle interactif :

```sh
JAVA_OPTS='-Dvoxel.startHour=17.3166666667 -Dvoxel.pauseTime=true' \
  build/install/TOB_Voxel_Project/bin/TOB_Voxel_Project
```

Charger World 10, activer F3 et F4 mode 1, attendre `COMPLETE`, puis comparer Maj+F4 à pose fixe et reculer lentement. Garder F5 corrigé et F8 automatique. Répéter en rendu normal, puis avec le temps mobile. Le replay automatisé ne remplace pas cette validation artistique et la vérification du déplacement continu sur la machine en usage normal.

## Limites assumées

- Approximation artistique, pas une pénombre physique ; conservation d'un seul premier caster par texel.
- Une occultation plus proche cachée derrière un caster lointain peut être atténuée, puis réapparaître quand celui-ci disparaît de la carte. La continuité de tous les cas superposés n'est pas garantie.
- Téléportations, chargements lents, erreurs disque et fortes accélérations du temps peuvent produire une couverture temporairement dégradée.
- La résidence couvre la portée bornée et le budget configuré, pas tous les reliefs lointains.
- Aucun élargissement du rayon visible, aucune correction implicite de l'éclairage des caves, du shimmering ou des autres défauts de filtrage.
