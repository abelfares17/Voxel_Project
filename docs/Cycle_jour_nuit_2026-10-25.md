# Cycle jour/nuit, ciel et CSM solaire/lunaire — 25 octobre 2026

## Périmètre livré

Le pipeline actif utilise une seule horloge et un état céleste commun. Les ombres restent en CSM direct, avec fusion des meshes shadow et couverture conservative des casters chargés. Il n'y a ni second atlas lunaire, ni cache temporel, ni leapfrog actif.

### Temps et sauvegardes

- `TimeManager` stocke des jours simulés en `double`. 0 = minuit, 0,25 = 06 h, 0,5 = midi, 0,75 = 18 h. Une journée dure 1 200 secondes à vitesse 1.
- Seul `Game.update` fait avancer le temps. `Application` mesure le delta avec l'horloge monotone `nanoTime`, puis le transmet en secondes sans passage par des millisecondes entières. Chaque update consomme au maximum 100 ms ; un blocage fait donc perdre du temps simulé plutôt que provoquer un rattrapage.
- Les nouveaux mondes commencent à 08 h. `WorldStorage` charge maintenant les métadonnées existantes au lieu de les reconstruire. Le schéma passe à 2 ; `worldDays` absent, négatif ou non fini est remplacé par 08 h avec avertissement.
- Nom, seed, mode, création et statistiques existantes sont préservés. Les métadonnées illisibles provoquent une erreur plutôt qu'un écrasement silencieux.
- `World` restaure l'heure dès sa construction, avant les frames jouables. Aucun calcul à partir de `lastPlayed`, donc aucune progression hors ligne.
- À un changement d'état, `StateManager` transmet un premier delta nul ; `Application` réinitialise son horloge après `enter()`. Le temps passé dans le chargement ou la création des renderers ne se retrouve donc pas dans le premier update jouable ni dans le suivant.
- Une session avec `startHour`, `timeScale` ou `pauseTime` explicite ne remplace pas l'heure sauvegardée. La position du joueur et les modifications du monde restent sauvegardables hors harnais. Les pauses F6 et multiplicateurs ne sont jamais des champs persistants.
- Le harnais ne sauvegarde ni joueur ni monde ; la suite vérifie également l'absence de modification des fichiers sous `saves/`.

### Contrat céleste

`CelestialState.at(worldDays)` est une fonction pure ; `Game.render` en place une seule instance dans `RenderContext`. Les vecteurs exposés sont copiés pour empêcher une modification accidentelle de la frame.

L'orbite est un cercle incliné de 23,5° dans le repère du monde : altitude solaire maximale 66,5°, lever à 06 h, coucher à 18 h. La lune est opposée. Les directions vont **vers** les astres ; le calcul CSM applique sa convention interne de direction des rayons.

Les constantes artistiques sont centralisées dans `CelestialState` :

| Paramètre | Valeur initiale |
|---|---|
| Intensité solaire maximale | 1 |
| Intensité lunaire maximale | 0,08, bleutée |
| Activation directe | smoothstep entre 2° et 8° d'élévation réelle |
| Ambiance | 0,04 nuit → 0,45 jour, selon élévation solaire −6° → +10° |
| Disques | soleil 3°, lune 2° (ajustement après retour visuel ; initialement 1° chacun) |
| Étoiles | 512 directions déterministes par seed, fondu solaire −12° → −2° |

Les couleurs sont **linéaires**. Les shaders ne rajoutent pas de conversion gamma : ils utilisent le framebuffer sRGB déjà en place. Le fog utilise la couleur d'horizon de cet état, tandis que le ciel ajoute une coloration crépusculaire directionnelle.

### Ombres

`ShadowRenderer` produit les trois couches pour la direction de la lumière active. `VoxelRenderer` transmet les mêmes `lightDirection` et `lightColor` aux shaders. Aucun champ solaire n'est réutilisé implicitement pour représenter la lune.

- L'atlas alloué est ramené de six à **trois couches**. A et B des anciennes interfaces désignent les mêmes données ; C n'est pas alloué.
- À `NONE`, pas de production shadow et pas d'échantillonnage, y compris dans les diagnostics qui appellent le calcul shadow. Les index shader restent bornés lorsque le nombre de cascades est zéro.
- `hasCurrentShadowMap` vérifie l'identité du snapshot produit/consommé. Les trois cartes sont intégralement régénérées à chaque frame éclairée, donc avant toute réactivation ou changement d'astre.
- La compensation du biais avec le nouvel intervalle Z, le normal-offset, les emprises XY et le fit des casters ne sont pas retouchés.
- Les anciennes méthodes leapfrog restent dans le fichier comme strate dormante, mais L ne peut plus activer ce chemin ; le message indique son indisponibilité.

### Ciel et astres à l'infini

`SkyRenderer` possède deux programmes, deux VAO et un VBO d'étoiles. Le ciel utilise un triangle plein écran construit par `gl_VertexID`. Le rayon est reconstruit avec l'inverse de la projection et la rotation inverse de la vue, **sans translation**.

Les disques sont déterminés par l'angle au rayon et anticrénelés avec les dérivées GLSL. La lune pleine reçoit un détail procédural discret. Les étoiles utilisent un catalogue uniforme sur la sphère, créé une fois par seed ; aucune animation de scintillement n'est ajoutée.

Contrat de passe : framebuffer écran, viewport framebuffer, clear couleur/profondeur ; ciel sans depth test ni écriture depth ; puis depth LESS/write ON, back-face CCW, blending/scissor OFF, programme/VAO désengagés. Le terrain est dessiné ensuite et masque les astres.

`SunRenderer` n'est plus construit, appelé, ni détruit par `PlayingState`/`Game`. Les comparateurs ne l'appellent plus non plus. Ses anciens fichiers et shaders restent disponibles comme legacy, sans participation au rendu actif.

## Commandes

Depuis la racine :

```sh
./gradlew --offline installDist

# Jeu normal : heure persistante, cycle de 20 minutes.
build/install/TOB_Voxel_Project/bin/TOB_Voxel_Project

# Inspection manuelle de la nuit, sans remplacer l'heure sauvegardée.
JAVA_OPTS='-Dvoxel.startHour=0 -Dvoxel.pauseTime=true' \
  build/install/TOB_Voxel_Project/bin/TOB_Voxel_Project

# Cycle complet accéléré : une journée toutes les 10 secondes de simulation active.
JAVA_OPTS='-Dvoxel.startHour=12 -Dvoxel.timeScale=120' \
  build/install/TOB_Voxel_Project/bin/TOB_Voxel_Project

# Qualification réelle : cycle mobile, contrôle images nocturnes, ABBA à trois heures fixes.
python3 -B tools/benchmark_celestial.py --mode all

# Mesure complémentaire si les petites requêtes GPU retournent zéro.
python3 -B tools/benchmark_celestial.py --mode bench --combined-scene
```

`voxel.skyMode=flat|procedural` (défaut procedural) change uniquement le ciel/astres. Ni l'heure, ni la lumière, ni les casters, ni les filtres shadow ne changent. Le mode flat est une référence de coût, pas un autre modèle d'éclairage.

Les runs ABBA sont intentionnellement **figés** (pose et heure) pour isoler une variable. Le run `cycle` utilise au contraire le mouvement du harnais et une horloge active. Ne pas confondre ces deux protocoles.

Les logs GPU contiennent heure, astre actif, élévation, vitesse, pause, override initial, résolution, géométrie et temps par passe. Les colonnes CPU `activeLightPreviousFrame`/`lightElevationPreviousFrame` décrivent la frame rendue précédente, comme les temps CPU du logger existant. Le mode combiné écrit `gpu_scene_ms` pour ciel + voxels et `NaN` pour les deux sous-passes non mesurées : aucune requête elapsed imbriquée.

## Tests reproductibles

Les tests de stockage doivent impérativement travailler dans un dossier temporaire, pas dans les sauvegardes du dépôt :

```sh
repo="$PWD"
test_dir=$(mktemp -d /tmp/voxel-celestial.XXXXXX)
javac -cp "$repo/build/install/TOB_Voxel_Project/lib/*" -d "$test_dir" \
  tools/tests/CelestialTimeTest.java tools/tests/CelestialGlTest.java \
  tools/tests/PlayerOrientationTest.java tools/tests/ShadowCasterVolumeTest.java \
  tools/tests/ShadowCasterDepthGlTest.java
(
  cd "$test_dir"
  java -cp "$test_dir:$repo/build/install/TOB_Voxel_Project/lib/*" CelestialTimeTest
  java -XstartOnFirstThread -cp "$test_dir:$repo/build/install/TOB_Voxel_Project/lib/*" \
    CelestialGlTest "$repo/logs/celestial_discs"
  java -XstartOnFirstThread -cp "$test_dir:$repo/build/install/TOB_Voxel_Project/lib/*" \
    engine.input.PlayerOrientationTest
)
java -cp "$test_dir:$repo/build/install/TOB_Voxel_Project/lib/*" ShadowCasterVolumeTest
java -XstartOnFirstThread -cp "$test_dir:$repo/build/install/TOB_Voxel_Project/lib/*" ShadowCasterDepthGlTest
```

`CelestialTimeTest` couvre l'horloge double, le clamp, les sous-millisecondes, les journées multiples, la pause, les quatre heures cardinales, la continuité, les élévations 0,5°/2°/5°/8°/15°, l'opposition des astres, le catalogue déterministe, la migration v1, les métadonnées et l'exclusion des overrides des sauvegardes.

`CelestialGlTest` utilise une fenêtre cachée et les shaders de production. Il vérifie les transitions et la fraîcheur du snapshot, les trois couches, le state GL, les images de ciel identiques après translation, la rotation/FOV, les disques centrés et l'absence d'écriture depth, puis trois cycles création/destruction. Son monde est vide : il ne constitue pas à lui seul une validation visuelle des ombres montagneuses.

## Qualification visuelle restant à faire

1. Reprendre la scène de montagne de `Protocole_CSM_casters_2026-10-25.md`, sans F11/F12 ni mode legacy F5.
2. En jour et en nuit lunaire, vérifier les mêmes occultations en F4 mode 1, puis les frontières F4 mode 5 et F8. Les casters non chargés restent hors périmètre.
3. Tester les astres au centre puis au bord de l'écran, en translation, en rotation et à grand FOV. Leur distorsion perspective normale en bord de champ n'est pas un billboard défectueux.
4. Tourner vers le soleil à 06 h et 18 h : disque visible au voisinage de l'horizon, mais lumière directe faible/nulle ; les longues ombres contrastées ne doivent pas subsister jusqu'à une élévation arbitrairement petite.
5. Quitter/recharger un monde sans override : même heure et même pose au retour. Attendre hors jeu ne doit rien avancer.
6. Contrôler que les surfaces nocturnes restent assez lisibles pour le gameplay ; les constantes .04/.08 sont un réglage artistique à ajuster avec l'utilisateur, pas une mesure physique.

F6 garde son rôle de pause du temps. F4/F5/F8/F9/F10 restent disponibles ; le HUD affiche l'heure, SUN/MOON/NONE et l'élévation de la lumière active. NONE indique 0° dans ce champ (absence de lumière active), pas nécessairement l'élévation solaire exacte.

Heures fixes utiles pour `-Dvoxel.startHour=… -Dvoxel.pauseTime=true` :

| Élévation montante | Soleil (heure décimale) | Lune (heure décimale) |
|---|---|---|
| 0,5° | 6.036348 | 18.036348 |
| 2° | 6.145398 | 18.145398 |
| 5° | 6.363568 | 18.363568 |
| 8° | 6.581929 | 18.581929 |
| 15° | 7.092877 | 19.092877 |

Les valeurs arrondies très proches du seuil de 2° peuvent afficher une intensité positive infime plutôt que NONE ; ce n'est pas un saut visible.

## Limites assumées

Pas de nuages, météo, phases lunaires, HDR/bloom ou propagation de skylight dans les caves. Le ciel n'est pas un modèle atmosphérique physique. L'aliasing des ombres fines, les variations de résolution entre cascades et les biais existants restent des sujets distincts ; le cycle ne les corrige pas implicitement. Le chargement ne s'étend pas pour rechercher davantage de casters.

## Résultats de cette livraison

### Tests

- Build `./gradlew --offline installDist` : **PASS**.
- `CelestialTimeTest` : **PASS**, y compris delta nul lors des transitions, stockage réel dans un répertoire temporaire et exclusion des overrides.
- `CelestialGlTest` : **PASS** sur **Apple M2 Pro**, contexte effectif **OpenGL 4.1 Metal - 90.5**, shaders GLSL 150. Création/destruction répétées, programmes et buffers sky détruits, programme depth emprunté non détruit par ShadowRenderer.
- `ShadowCasterVolumeTest` : **PASS**, 10 000 cas, avec cas orientés additionnels du test existant.
- `ShadowCasterDepthGlTest` : **PASS**, les projections corrigées near/mid/far occultent le récepteur avec les shaders depth de production.
- `PlayerOrientationTest` : **PASS**, stockage et restauration de pose, libération/recapture du curseur et changements de focus.
- Captures ciblées du ciel : `logs/celestial_discs/sun.png` et `moon.png`. Fenêtre cachée, FOV 20° pour rendre le diamètre de 1° discernable dans une image de 128 pixels.

Le runtime LWJGL existant affiche encore son avertissement « Unsupported JNI version » avec le Java local ; aucun crash ni erreur GL n'a été constaté pendant ces tests. Cela n'est pas une correction de compatibilité Java/LWJGL.

### Mesures Retina : ne pas confondre zéro et absence de coût

Première suite : `logs/celestial_suite_f4w5ynlo/`. Le cycle mobile visite SUN, NONE et MOON. Les quatre relectures nocturnes de contrôle sont identiques en couleur et en profondeur. Mais le timer sky isolé renvoie presque toujours zéro à midi et la nuit. Ses moyennes de quelques microsecondes **ne sont pas retenues comme estimation du coût du ciel** ; les indicateurs de budget de son `aggregate.json` ont été invalidés explicitement, sans toucher aux CSV bruts.

Deuxième suite : `logs/celestial_suite_v03m_tkk/`. La requête couvre **ciel + voxels**, sans sous-requêtes imbriquées. Mesure ABBA à pose et heure fixes ; deux runs de chaque mode, fenêtre de mesure 5–19,5 s, sans readback ni glFinish. Résolution **2268 × 1382**, fenêtre visible, non minimisée, non focalisée, curseur normal. Le CSM et la géométrie sont identiques entre A et B.

| Heure / lumière | Ciel plat + voxels, GPU moyen | Ciel procédural + voxels, GPU moyen | Différence B−A |
|---|---:|---:|---:|
| Midi / SUN | 9,520 ms | 9,597 ms | **+0,076 ms** |
| Minuit / MOON | 9,497 ms | 9,593 ms | **+0,096 ms** |
| 06 h / NONE | 8,671 ms | 8,737 ms | **+0,065 ms** |

Les requêtes combinées ne retournent pas de valeurs nulles dans cette fenêtre. Les quatre moyennes individuelles restent proches dans chaque groupe ; le delta mesuré est petit devant le budget de **1 ms**, donc ce budget est tenu **pour ces poses et cette résolution**. Ce n'est ni une borne sur toutes les scènes ni une mesure indépendante du coût de chaque instruction GLSL.

La passe shadow coûte environ **2,83–2,85 ms** dans ces runs éclairés, pour **1 473 draws** et **9 035 118 références vertex** ; elle tombe à zéro draw à NONE. Les voxels conservent **345 draws** et **6 520 476 références vertex** entre les modes de ciel. Ne pas attribuer le gain du crépuscule au shader sky : l'éclairage et la production shadow y changent aussi.

Ces runs sont profilés : ils ne servent pas à annoncer un gain global de FPS non profilé sur l'ancien moteur. La réduction de six à trois couches DEPTH32F de 2048² retire nominalement **48 MiB** de stockage de texture ; ce nombre n'est pas une mesure de la mémoire physique réservée par le pilote.

La seule modification de production postérieure à cette ABBA concerne l'exclusion des deltas de transition/chargement et le libellé de pause/HUD ; les shaders de rendu sont inchangés hors commentaire. La fenêtre mesurée commence à 5 s avec l'heure explicitement figée. La qualification finale du build se trouve dans `logs/celestial_suite_wgfmo2lk/`.

### Qualification finale du build

La suite finale a terminé ses trois scénarios, sans erreur GL ni modification des sauvegardes :

- Cycle mobile ×120 : **SUN → NONE → MOON → NONE → SUN**, répété deux fois sur environ 20 secondes. Tous les échantillons NONE ont zéro draw shadow. La première frame est exactement à midi, sans consommation du delta de chargement.
- Contrôle nocturne OFF/OFF : **quatre images identiques**, couleur et profondeur.
- Fusion shadow nocturne OFF/ON : sur les quatre comparaisons de **3 134 376 pixels**, trois sont identiques ; une comporte **24 pixels différents**, écart maximal **7/255**, aucun pixel au-dessus de 8, et **zéro différence de profondeur caméra**. Ce n'est pas une identité bit-à-bit parfaite, mais aucun nouvel écart macroscopique n'est mis en évidence par ce scénario.

Conclusion : le système est implémenté, les invariants temporels/GL ciblés passent et le budget incrémental du ciel est respecté sur la scène benchmarkée. Il reste une **acceptation visuelle humaine** sur la montagne précise, les ombres fines et la lisibilité nocturne ; les tests automatiques ne permettent pas de la déclarer faite.


## Observations 

Le nouveau cycle jour/rempli globalement très bien sa fonction avec une skybox esthétique.
Point d'amélioration :
 - Les modèles de la lune et le soleil devraient avoir un diamètre plus important, (3x plsu pour le soleil et 2x plus pour la lune à calculer pour les tailles exactes)

Problèmes : 
 - Les modes de debug qui affichent les ombres comme des massses noires solides permettent de mettre en évidence un bug de shadow rendering, des petites taches blanches apparaissent et disparaissent de manière erratique un peu partout sur l'ombre. C'est beaucoup moins visible avec le blending avec les voxel mais c'est un problème.
 - Les montagnes hautes projettent toujours une ombre qui est beaucoup trop large, et à partir de 15/16 est suffisement large pour que le système de detection de caster dans la CSM échoue et qu'on retrouve les mêmes problèmes qu'avant. Il faudrait voir comment contourner ce problème (à partir d'une solution physique) tout en gardant un rendu réaliste lorsque le soleil s'approche de l'horizon.

## Suite du retour visuel : tailles corrigées, diagnostic shadow ouvert

### Changement effectué et vérifié

Soleil **3° de diamètre**, lune **2°**, contre 1° auparavant : rayons angulaires respectifs 1,5° et 1°. Les deux paramètres sont séparés dans CelestialState et transmis à SkyRenderer puis aux shaders sky/stars. Le masquage des étoiles suit les nouveaux disques ; l'échelle du détail lunaire suit son rayon au lieu d'une constante correspondant à 1° de diamètre.

À projection perspective, le diamètre au centre de l'image est proportionnel à tan(diamètre/2). Les facteurs projetés sont donc environ **3,0006** et **2,0002**, pratiquement les ×3 et ×2 demandés. Cela modifie l'apparence des astres, pas l'intensité ni la direction de l'éclairage et pas la pénombre des shadow maps.

Build installDist : PASS. CelestialGlTest : PASS sur M2 Pro, avec mesure du diamètre rendu contre la projection théorique, shaders de production, translation/rotation/FOV, transitions et cleanup. Les mesures de performance ci-dessus précèdent ce changement visuel ; aucun nouveau gain de FPS n'est revendiqué.

### Taches blanches : ce qui est établi, et ce qui ne l'est pas

**Haute confiance, factuel :** voxel_fragment_shader.glsl utilise PERF_BISECT_STAGE=6 et SHADOW_FILTER_MODE=1, donc le PCF bilinéaire manuel 2×2 de sampleBilinearShadow. Le frameIndex et les anciennes séquences de jitter ne participent pas à ce chemin. Il serait incorrect de diagnostiquer un jitter temporel actif sur cette seule observation.

Le shader renvoie pleinement éclairé lorsque la projection est hors du volume XYZ de la cascade. Les coordonnées de réception incluent le normal-offset du vertex shader. Plusieurs explications restent donc à départager : trous de couverture/rastérisation dans la carte, aliasing de petites faces projetées, biais/normal-offset déplaçant la comparaison, coordonnées hors carte, ou mélange avec une cascade qui ne représente pas correctement l'occultation. **La cause des taches n'est pas encore reproduite ni prouvée.**

Le mode facteur shadow montre également des surfaces dont N·L est nul/négatif, alors que leur contribution directe est nulle dans le rendu normal. Une variation du facteur sur ces surfaces n'a pas nécessairement d'effet sur leur éclairage final.

Les anciens commentaires GLSL promettant « zéro shimmer » ou l'élimination complète des coutures par le PCF/blend sont trop forts : filtrer une carte discrète ne reconstitue pas la géométrie absente. Les limites de l'échantillonnage à incidence rasante sont documentées dans [GPU Gems, Shadow Map Antialiasing](https://developer.nvidia.com/gpugems/gpugems/part-ii-lighting-and-shadows/chapter-11-shadow-map-antialiasing).

### Grandes ombres : ne pas confondre longueur physique et perte d'occultation

Sur un sol horizontal, un obstacle de hauteur H au-dessus du récepteur projette une longueur **L = H / tan(élévation)**. Pour l'orbite actuelle :

| Heure | Élévation solaire approximative | Longueur pour H = 100 blocs |
|---|---:|---:|
| 15 h | 40,4° | 117 blocs |
| 16 h | 27,3° | 194 blocs |
| 17 h | 13,7° | 409 blocs |

Ce calcul concerne la **longueur dans la direction du soleil**, pas une dilatation uniforme de la largeur de la silhouette. Un relief non horizontal modifie évidemment l'intersection. Si la largeur perpendiculaire au soleil paraît excessive, il faut contrôler les projections et la silhouette, pas l'expliquer automatiquement par cette formule.

À 15–16 h, nous ne sommes pas encore dans le voisinage immédiat de l'horizon. Remonter fortement le seuil de disparition des ombres pour cacher le défaut serait un compromis artistique, pas une correction physique.

**Haute confiance, code :**

- ShadowRenderer::renderOffMode étend le Z vers la lumière à partir des meshes chargés/prêts ; ShadowCasterVolume conserve l'emprise XY des récepteurs et ignore les AABB hors de celle-ci. C'est le bon principe pour une lumière directionnelle idéale, sous réserve d'emprise correcte, marges de filtrage et transformations correctes.
- World::renderDepth parcourt les chunks chargés, pas la liste visible par la caméra. Le rayon de culling est désactivé dans le chemin corrigé ; le frustum lumineux final peut encore rejeter des chunks.
- World::update appelle unloadFarChunks avec renderDistance+2. Un caster hors du monde résident ne peut donc pas réapparaître grâce au seul fit CSM.
- ShadowRenderer::validateCascadeDepth compare avec/sans culling CPU **dans la même matrice lumineuse**. Un PASS peut exclure une différence de culling sur les meshes disponibles, mais ne prouve ni une bonne projection, ni la présence des casters déchargés, ni la couverture des récepteurs après normal-offset.

**Confiance moyenne :** les longues ombres rendent plausibles les limites de résidence du terrain. Mais cela ne prouve pas que la montagne signalée soit déchargée. Une coupure qui suit une cascade alors que toute la montagne reste prête exige de contrôler le fit/projection/échantillonnage.

### Prochaine investigation, avant de modifier les CSM

1. Reproduire à heure fixe avec la pose réellement problématique, pas uniquement le spawn du harnais :

   ```sh
   JAVA_OPTS='-Dvoxel.startHour=16 -Dvoxel.pauseTime=true' \
     build/install/TOB_Voxel_Project/bin/TOB_Voxel_Project
   ```

2. Attendre la fin du streaming, ne plus déplacer la caméra, observer en F4 mode 1. Si les taches continuent de changer à pose/heure/géométrie constantes, ce n'est pas un aliasing causé par le mouvement solaire ; comparer alors les depth maps entre frames identiques.
3. Même pose, comparer F9 zéro **et** F10 OFF ensemble, puis séparément, et restaurer normal/ON. Les tests précédents de détachement ne suffisent pas à trancher ce nouvel artefact.
4. Comparer F4 mode 5 pour situer les coutures, puis F8 pour inspecter chaque cascade seulement dans son domaine valide. Une cascade forcée hors de sa couverture n'est pas une référence.
5. Capturer les projections XYZ, la cascade sélectionnée, les profondeurs des quatre taps et les bornes/états des casters de cette scène. Le prochain ajout de diagnostic utile est une couleur explicite « hors volume », plutôt que le blanc actuellement indiscernable de « réellement éclairé ».
6. Rejouer la même géométrie avec culling CPU désactivé, puis dans un volume lumineux de référence plus conservateur, et enfin comparer greedy ON/OFF. Ces expériences séparent sélection CPU, clipping GPU et rastérisation. Agrandir arbitrairement toutes les cascades en production ferait perdre de la résolution sans prouver la cause.

### Compromis physique recommandé, du plus simple au plus coûteux

1. **Garder direction réelle et fondu solaire actuel**, corriger localement le défaut démontré de couverture ou de comparaison. Ne pas incliner artificiellement les rayons vers le haut pour raccourcir les ombres.
2. Si les casters sont réellement déchargés : **étendre leur résidence vers la lumière**, indépendamment de la visibilité caméra, avec un budget explicite. C'est une extension du périmètre précédent qui limitait les ombres aux meshes déjà chargés. Elle augmente mémoire/travail et doit être mesurée avant généralisation.
3. Pour de très longues portées : conserver le CSM détaillé proche et ajouter une **représentation grossière des reliefs lointains pour les ombres**. Une heightfield peut servir aux grandes montagnes, mais ne représente pas correctement toutes les arches/caves/surplombs ; ce n'est pas un remplacement global des meshes.
4. Ensuite seulement, envisager une pénombre liée à une source de taille angulaire finie et une atténuation atmosphérique plus progressive. Ce sont des améliorations de réalisme, **pas des réparations de casters manquants**. Le diamètre visuel artistique de 3° ne doit pas automatiquement imposer la même taille à la source d'éclairage.

Les bugs shadow restent ouverts. Pour déterminer la correction suivante sans supposition, il manque surtout la pose/monde et des captures de la scène à heure fixe (facteur shadow et frontières des cascades).
