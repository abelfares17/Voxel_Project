# Guide : performances, Retina et ombres CSM avec soleil mobile

Analyse du 26 septembre 2026, sur les **modifications locales actuelles**, et non sur le seul commit `0706298`. Référence mesurée : [dernier CSV, 26 septembre à 21:50:30](../logs/perf_test_World_10_2026-09-26_215030.csv).

Les sections 1 à 14 conservent l'analyse initiale : aucun code ni benchmark n'avait alors été modifié ou lancé. Leurs constats et numéros de lignes correspondent au snapshot du 26 septembre, pas nécessairement au code actuel.

**Mise à jour du 27 septembre 2026 : les deux premières solutions ont maintenant été implémentées et testées à ta demande. Voir la [section 15](#15-implémentation-et-tests-du-27-septembre-2026). Les conclusions principales reposent sur sept essais avec caméra ET soleil mobiles, pas sur les premiers essais figés. Sur ce parcours, le passage d'environ 41,2 à 41,7–41,8 FPS ne démontre pas d'amélioration importante ; Retina et le filtre PCF ne sont pas établis comme goulot dominant.**

**Mise à jour du 28 septembre : la [section 16](#16-culling-des-cascades-et-mesures-gpu--28-septembre-2026) décrit la nouvelle optimisation mesurée : rejet des chunks hors du volume lumineux, environ 26 % de vertices d'ombres soumis en moins et 41,3 → 45,1 FPS sur les essais mobiles sans instrumentation. Le problème de performances n'est pas entièrement résolu.**

## 1. Diagnostic en bref

**La voie recommandée reste un CSM direct simple, correctement filtré et calibré. Il n’est pas nécessaire de revenir au leapfrog ni de changer d’API pour poursuivre.**

Trois problèmes se superposent : coût du rendu par pixel, stabilité des shadow maps et cohérence de leurs paramètres. Retina peut amplifier le premier ; il n’explique pas les deux autres.

| Constat actuel | Confiance | Conséquence |
|---|---|---|
| L’ancien conflit compare mode / sampler manuel est retiré du chemin actif | Haute | Ne plus attribuer automatiquement les lenteurs à cet ancien défaut. |
| Le stage actif est 6, filtre 2 : jusqu’à 16 lectures depth par cascade, 32 dans une transition | Haute sur le GLSL, pas sur les transactions physiques GPU | Première piste simple pour réduire le coût fragment. |
| Le CSM direct force la mise à jour de base lumineuse à chaque frame | Haute | Même soleil immobile, les trois cascades sont normalement redessinées. |
| Le biais est exprimé en profondeur normalisée, sans conversion explicite depuis une unité monde | Haute | Son effet augmente fortement avec l’étendue Z des cascades ; candidat sérieux pour le popping. |
| Le texel snapping ne stabilise que la translation dans une base lumineuse donnée | Haute | Une rotation du soleil change cette base ; du shimmering reste possible malgré le snap. |
| Les commandes de debug cascade et shadow restent neutralisées à l’upload Java | Haute | Plusieurs tests affichent une notification sans modifier le shader. |
| Le resize de `PlayingState` n’est pas propagé à `Game` | Haute | Anciennes dimensions et ancien aspect peuvent revenir après un resize. |
| Le défaut Retina au démarrage ressemble à un problème GLFW déjà signalé | Moyenne | À confirmer par une trace des dimensions avant/après les premiers événements, pas par un facteur 2 codé en dur. |
| Le dernier CSV correspond principalement à environ 35–38 FPS, pas à 50 FPS stables | Haute pour les échantillons | Il faut mesurer des temps de frame et non moyenner des compteurs FPS transitoires. |

### Ce qui a effectivement progressé

- `ShadowRenderer::createShadowMapArray()` conserve `GL_TEXTURE_COMPARE_MODE = GL_NONE` ; `VoxelRenderer::render()` n’active plus le compare mode sur cet atlas. Le sampler utilisé est manuel.
- Les renderers voxel/depth ne détruisent plus leurs programmes empruntés ; `PlayingState::exit()` détruit les programmes et l’atlas voxel. `Chunk::setMesh()` libère l’ancien VAO/VBO ; `World::cleanup()` parcourt les chunks.
- Le rendu principal dispose désormais d’un frustum culling. La passe depth possède un culling de distance conservateur par cascade.
- Le `glFinish()` final systématique a été retiré.

**Réserve limitée sur la mémoire :** ces corrections sont visibles, mais « toutes les fuites résolues » dépasse ce que la lecture permet de certifier. `MeshWorkerManager::shutdown()` vide la file après `shutdownNow()` sans attendre les tâches encore en cours ; une tâche peut encore publier un résultat après ce drainage. L’upload ne vérifie toujours pas que son chunk appartient au monde. À tester séparément ; aucune preuve n’en fait la cause du régime actuel de FPS. Ne pas rouvrir tout le chantier mémoire pour cette analyse de shading.

## 2. Ce que dit réellement le dernier log

Le CSV contient **24 échantillons**, un toutes les 60 frames, entre les indices 60 et 1440. Il ne contient donc ni toutes les frames, ni des moyennes de blocs de 60 frames : `PerfLogger::logFrame()`, lignes 86–105, conserve seulement une frame sur 60.

| Mesure | Médiane des 24 échantillons | Interprétation |
|---|---:|---|
| `dt` | 27 ms | Équivalent à environ 37 FPS à cet instant. |
| `update_ms` | 0,05 ms | Update généralement court sur ces échantillons. |
| `render_ms` | 3,295 ms | Intervalle CPU de soumission/rendu, pas temps GPU. |
| `swap_ms` | 23,915 ms | Attente CPU observée dans la présentation. |
| `glfinish_ms` | 0 ms | Appel désactivé ; pas GPU gratuit. |
| `shadow_finish_ms` | 0 ms | Pas de mesure isolée disponible pour les ombres. |
| `fps_smooth` | 37 | Le début du fichier contient aussi 336 puis 95, valeurs transitoires. |

La moyenne arithmétique de la colonne `fps_smooth` vaut environ **51,3**, mais elle est gonflée par ces premières valeurs. Elle ne caractérise pas le régime stable. Un échantillon, frame 960, contient aussi un update de 29,93 ms : le CPU peut ponctuellement bloquer, sans être le coût médian dominant visible.

**Ce que cela suggère :** l’application attend surtout après la soumission. Un coût GPU important est plausible. Le filtre d’ombre et la résolution sont de bons candidats à tester.

**Ce que cela ne prouve pas :** que `swapBuffers` effectue lui-même 24 ms de calcul, que VSync est activé, que Retina est la cause unique ou que les shadow passes sont peu coûteuses. Le driver peut y exercer une contre-pression après des travaux soumis auparavant ; la présentation/composition peut aussi intervenir. Le code demande `VSYNC_ENABLED = 0`, mais ce seul réglage ne transforme pas `swap_ms` en timer GPU.

Limites encore présentes :

- `PlayingState::update()` journalise les timings de la frame précédente avec la pose déjà mise à jour ; `Application::loop()` stocke les timings en fin de frame. Les associations fines pose/pic sont décalées.
- `visibleChunks` et `renderedVertices` restent à zéro ; `shadowPassEnabled` reste codé en dur à `true`.
- Le CSV n’enregistre pas dimensions framebuffer, paramètres du filtre, temps GPU par passe, direction solaire ni nombre de cascades redessinées.
- Le stage 6 correspond au code actuel, mais le fichier seul n’archive pas le GLSL exact exécuté.

**Ne compare pas directement ce `swap_ms` aux anciens `glfinish_ms` de mars : le point d’attente a changé.**

## 3. Pourquoi le filtre actuel peut coûter cher

Preuves : `src/main/resources/shaders/voxel_fragment_shader.glsl`, lignes 3, 29, 355–419 et 431–455.

Le chemin réellement actif est :

```text
main → calculateShadow, stage 6
     → sampleCascadeBlendedA
       → sampleShadowPCFFromArray, filtre 2
         → 4 × sampleBilinearShadow
           → 4 lectures depth + 4 comparaisons par appel
       → éventuellement même calcul dans la cascade suivante
```

Cela donne jusqu’à **16 appels texture par cascade**, et **32** dans la bande de blend. S’ajoutent la texture de matériau et le reste du shading. Les sorties hors domaine réduisent ce nombre ; le compilateur et le cache peuvent fusionner ou réutiliser du travail. Ce n’est donc pas un comptage de transactions mémoire physiques.

Les quatre centres bilinéaires se recouvrent : le filtre couvre seulement **neuf texels distincts** dans le cas intérieur. Il est plus coûteux à exprimer que ne le laisse penser le commentaire « 4 taps ». `PCF_SAMPLE_COUNT = 4` ne pilote plus cette branche : modifier cette constante ne réduit pas ces seize lectures.

Si le framebuffer faisait 3024×1832, ce serait 5,54 millions de pixels. À titre d’ordre de grandeur seulement, 16 lectures sur une surface couvrant tous ces pixels représenteraient environ 88,6 millions d’appels de lookup par frame avant optimisations. La couverture réelle, le depth test et l’overdraw restent à mesurer. **Cette résolution n’est pas enregistrée dans le dernier CSV.**

Deux commentaires du shader sont trop forts : interpolation bilinéaire et blend entre cascades ne garantissent ni « zéro shimmer », ni disparition totale du popping, ni continuité globale C1 de l’image. Les profondeurs rasterisées, le choix de cascade et les occluders peuvent changer discrètement.

## 4. Solutions, des plus faciles aux plus difficiles

L’ordre de difficulté n’est pas exactement l’ordre d’exécution : rendre le debug et les dimensions fiables précède les comparaisons de qualité.

| Niveau | Proposition | Ce qu’elle peut améliorer | Limite |
|---|---|---|---|
| Très facile | Garder stage 6, comparer `SHADOW_FILTER_MODE` 0, 1, 2 | Isoler puis réduire le coût du filtre : 1, 4 ou 16 lectures par cascade | Qualité différente ; mode 0 surtout diagnostic. |
| Très facile | Tester une fenêtre plus petite, Retina conservé, après réparation du resize | Vérifier la sensibilité au nombre de pixels | Ne réduit pas les shadow maps 2048². |
| Très facile | Ralentir le cycle solaire de 1,0 à 0,1 | Réduire la vitesse visible des variations | Ne réduit pas le nombre de shadow passes dans le code actuel. |
| Facile | Réactiver les vrais uniforms de debug ; réparer dimensions/resize | Rendre les tests interprétables, corriger viewport et aspect | Pas une promesse de gain GPU à elle seule. |
| Facile à moyenne | Réexprimer biais et normal offset avec des unités explicites | Contacts et cohérence entre cascades | Demande des scènes de calibration ; risque d’acné si biais trop faible. |
| Moyenne | Passer à un chemin shadow-compare matériel exclusif | Réduire le nombre d’instructions explicites de filtrage | Gain et qualité à vérifier sur le M2 ; ne pas réintroduire le mélange de samplers. |
| Moyenne | Conserver Retina mais rendre la 3D dans un FBO à 75 % ou 50 % | Réduire le coût par pixel en gardant l’UI nette | Ajoute une cible et une composition ; exige un contrat de viewport clair. |
| Moyenne | Raffiner culling des casters et budgets de distance/résolution | Réduire coût des passes depth | Ne pas supprimer les casters hors caméra qui projettent une ombre visible. |
| Moyenne à difficile | Cache des cascades correctement invalidé, puis fréquence différenciée | Réduire certains redraws | Invalidation caméra/soleil/géométrie complexe ; peut réintroduire du popping. |
| Difficile | Filtrage temporel avec reprojection et rejet d’historique | Réduire le scintillement résiduel | Ghosting, disocclusions, mouvements : pas un premier remède. |
| Très difficile, non prioritaire | Leapfrog remanié, shadow maps de moments, autre API | Options spécialisées si un besoin mesuré subsiste | Ne corrigent pas automatiquement les biais ou les cascades incohérentes. |

## 5. Étape 0 — Rendre le diagnostic fiable

### Fichiers à toucher ultérieurement

`VoxelRenderer.java`, `PlayingState.java`, `Application.java`, `PerfLogger.java`, éventuellement les seules entrées de debug du fragment shader.

### Changements à implémenter

1. Dans `VoxelRenderer::render()`, lignes 148–149, envoyer les valeurs réelles de `debugShadow` et `forceCascade`, actuellement remplacées par 0 et -1. Le GLSL a déjà les modes utiles ; le bloc rouge qui les court-circuitait a été retiré.
2. Vérifier les commandes existantes : F4 sélectionne le debug, F6 pause le soleil, F8 force une cascade, F9 change le biais, F10 le normal offset. F7 duplique actuellement F8 ; ne pas le prendre pour un réglage de PCF. Sur macOS, vérifier aussi la capture effective des touches de fonction.
3. Ajouter un relevé par run : versions GL/GLSL et GLFW native, GPU, dimensions fenêtre/framebuffer, viewport, stage et filtre, résolution/nombre de cascades, `SHADOWS_ENABLED`, mode direct, vitesse solaire, pose et heure. Le code affiche déjà une partie de l’identité GL ; conserver cette sortie avec le CSV.
4. Aligner pose et timings sur le même identifiant de frame. Pour des essais de 10–20 secondes, journaliser toutes les frames ou produire des statistiques de fenêtres réelles ; ne pas appeler « moyenne » un prélèvement toutes les 60 frames.
5. Compter effectivement les draws/sommets du monde et de chaque cascade, et les cascades réellement redessinées.
6. Si le contexte supporte OpenGL 3.3 ou `ARB_timer_query`, utiliser des queries par passe et lire leurs résultats avec plusieurs frames de retard. Ne pas attendre immédiatement les résultats ni imbriquer des queries elapsed de même cible. **OpenGL 3.2 seul ne les garantit pas.** [Spécification ARB_timer_query](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_timer_query.txt).

Sans timer GPU disponible, commencer par les comparaisons de temps de frame à scène fixe. Le `glFinish` optionnel après les ombres (`Game::render`, 267–274) reste une mesure d’attente, pas un profil GPU autonome. Ne pas rétablir un `glFinish` permanent pour « accélérer » le swap.

### Scène de référence

Choisir une pose fixe après stabilisation du meshing, soleil à une hauteur connue, devant un sol, un bloc isolé et une colline. Capturer quatre cas : caméra/soleil immobiles ; caméra mobile seulement ; soleil mobile seulement ; les deux mobiles. Chaque essai de performance conserve les mêmes meshes, pose, heure et résolution.

**Validation :** F4/F8 changent réellement l’image et les valeurs envoyées ; les dimensions et draws ne sont plus devinés ; une répétition du même test retrouve un ordre de grandeur comparable.

## 6. Étape 1 — Régler Retina et les dimensions sans facteur magique

### 6.1 Défaut établi : resize en jeu

`Application::init()`, lignes 193–204, reçoit les dimensions framebuffer et met à jour `RenderContext`, le scaler, le viewport et l’état courant. Mais `PlayingState` n’implémente pas `onResize()` ; la méthode par défaut ne fait rien.

`PlayingState::enter()`, lignes 137–138, initialise une seule fois `Game.winWidth/winHeight` et l’aspect caméra. Puis `Game::render()`, ligne 254, recopie **ces anciennes dimensions** dans `RenderContext` à chaque frame. Les renderers les utilisent pour leurs viewports.

**Changement minimal :** faire parvenir les nouvelles dimensions à `Game` et recalculer son aspect, ou retirer cette seconde source de vérité au profit des dimensions détenues par l’application. Choisis une solution, pas deux mises à jour concurrentes. L’UI en jeu et les captures doivent utiliser la même taille actuelle.

### 6.2 Hypothèse précise pour le menu tronqué Retina OFF

Le menu utilise les dimensions de l’application pour le rectangle de fond et sa projection : `MenuState::render()` → `UIManager::render()` → `MainMenuScreen::render()`. Il n’applique pas systématiquement son viewport à chaque rendu. L’initialisation et le callback portent ce contrat.

Le dépôt officiel GLFW contient un signalement où Retina désactivé produit initialement une taille framebuffer doublée, corrigée après `glfwPollEvents()`, **sans callback de resize**. Si l’application conserve alors les dimensions initiales, un viewport trop grand sur un drawable plus petit explique un cadrage sur le quadrant inférieur gauche. C’est une hypothèse particulièrement compatible avec ton symptôme, **pas une reproduction sur ton Mac**. [GLFW, issue #1968](https://github.com/glfw/glfw/issues/1968).

Le commentaire actuel de `RETINA_FRAMEBUFFER_ENABLED` disant que Retina est requis pour un viewport correct est trop catégorique. GLFW distingue taille de fenêtre en coordonnées écran et taille framebuffer en pixels ; cette dernière doit servir aux opérations OpenGL. [Guide GLFW 3.3 : dimensions et framebuffer](https://www.glfw.org/docs/3.3/window_guide.html#window_fbsize).

### 6.3 Protocole et changements

1. Dans `Application`, relever les tailles juste après création/show, après le premier `glfwPollEvents()`, puis au début des premières frames. Ajouter le viewport réel, les dimensions conservées dans `Application`/`Game`/`RenderContext` et celles utilisées par l’orthographique UI.
2. Si les tailles changent sans callback, réinterroger après traitement des événements et faire converger les copies vers une mise à jour unique. Pendant le diagnostic, contrôler à chaque frame est acceptable ; n’appliquer le resize que si la taille change réellement.
3. À l’entrée de la passe menu/loading, établir explicitement FBO 0 et viewport framebuffer courant. Le rectangle de fond, la projection et les positions de composants doivent utiliser le même système de coordonnées.
4. Rafraîchir `UIScreenScaler` avec les tailles effectives. Les coordonnées souris de GLFW sont converties par le ratio framebuffer/fenêtre une seule fois dans `Input::getMouseX/Y()`.
5. Conserver d’abord le contrat actuel d’UI en pixels framebuffer pour limiter le changement. Les `/2` de `MainMenuScreen`, ligne 125, concernent notamment l’échelle du texte : ils ne sont pas une réparation générale du viewport. Une UI entièrement en points logiques est un choix ultérieur possible, pas nécessaire pour diagnostiquer ce bug.
6. Gérer largeur ou hauteur nulle lors d’une minimisation : pas de division par zéro, pas de rendu vers une cible 0×0.
7. Tester Retina ON/OFF, resize menu/loading/jeu et déplacement entre écrans si disponible. Contrôler les clics aux quatre coins, le centre et le ratio des objets 3D.

**Ne pas multiplier/diviser aveuglément viewport ou taille par 2.** Si les dimensions GLFW restent incohérentes après ce relevé, isoler un petit test de fenêtre et comparer une version plus récente des natives GLFW/LWJGL dans une expérience séparée. Le projet utilise LWJGL 3.3.1 ; la version de GLFW native doit être relevée, pas déduite de ce numéro Java. Aucune mise à jour de dépendance n’est nécessaire avant d’avoir la trace.

### 6.4 Test Retina sans dépendre de Retina OFF

Après réparation du resize, conserver Retina ON et réduire les dimensions logiques de la fenêtre en gardant le même aspect. Cela diminue aussi les dimensions framebuffer réelles. Comparer deux puis trois tailles en les enregistrant. La caméra, ses cascades et la scène doivent rester identiques hors résolution.

Un coût qui suit nettement le nombre de pixels conforte une limite de rendu écran/shading. Un coût presque fixe oriente davantage vers passes depth, géométrie, CPU ou présentation. Ce test n’identifie pas à lui seul lequel de ces derniers domine.

## 7. Étape 2 — Réduire le coût du filtre, sans changer toute la pipeline

### Première expérience : configuration existante

Dans `voxel_fragment_shader.glsl`, garder **stage 6** et ne changer que `SHADOW_FILTER_MODE` :

| Filtre | Lectures manuelles par cascade, hors sorties anticipées | Usage |
|---|---:|---|
| 0 | 1 | Référence dure, diagnostic du coût minimal et des contacts. |
| 1 | 4 | Un PCF bilinéaire 2×2 ; premier candidat simple pour jouer. |
| 2 | 16, neuf texels distincts intérieurs | Référence actuelle plus large. |

La bande de blend peut doubler chaque nombre. Garder `SHADOW_MAP_FILTER = 1` côté Java, c’est-à-dire **NEAREST**, et `COMPARE_MODE = NONE` pour ces chemins manuels. Changer le nommage de ces deux modes n’est pas requis, mais attention : le filtre Java contrôle l’échantillonnage texture, celui du GLSL contrôle l’algorithme de shadow filtering.

Mesurer en scène fixe, puis filmer le soleil en mouvement. Si le mode 1 apporte un gain important avec une qualité suffisante, **s’arrêter provisoirement là**. La qualité du contact et des cascades doit être corrigée séparément : un filtre large ne remplace pas un biais correct.

Deux autres expériences utiles :

- `SHADOWS_ENABLED = false` en laissant la génération depth inchangée isole mieux le coût de consommation d’ombres que passer du stage 4 au 6, qui change aussi le shading.
- Pour isoler les dessins depth, initialiser d’abord texture et matrices valides, puis geler leurs mises à jour. Ne pas désactiver `SHADOW_DEPTH_PASS_ENABLED` dès le lancement : le return de `ShadowRenderer::render()` coupe aussi le calcul initial des cascades.

### Option suivante : filtrage matériel exclusif

Fichiers : `ShadowRenderer::createShadowMapArray`, liaison texture dans `VoxelRenderer`, samplers et fonctions de lookup du fragment shader.

Préparer une variante cohérente : depth texture en compare mode avec fonction de comparaison choisie, sampler `sampler2DArrayShadow`, filtrage matériel testé. Remplacer **tous les accès actifs** à cet atlas, y compris les modes de debug utilisés. Ne pas conserver des lectures manuelles actives avec ce compare mode et ne pas recopier l’ancien double-binding fautif.

`sampler2DArrayShadow` et sa fonction `texture` existent en GLSL 1.50 ; cela ne nécessite pas en soi OpenGL 4.x. Les sampler objects, eux, ne sont pas nécessaires si une seule stratégie est active. [Spécification GLSL 1.50, §8.7](https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.1.50.pdf).

Commencer par vérifier la comparaison sur un motif simple, puis le filtrage LINEAR et sa qualité sur ce GPU. Le filtrage de comparaisons est l’objectif, pas une comparaison après moyenne des profondeurs. Des implémentations matérielles fournissent un PCF filtré en une instruction, mais **ne promettre ni un noyau exactement identique sur tout driver ni un facteur quatre de vitesse sur le M2**. [NVIDIA, GPU Gems : Shadow Map Antialiasing](https://developer.nvidia.com/gpugems/gpugems/part-ii-lighting-and-shadows/chapter-11-shadow-map-antialiasing).

Attention : remettre seulement `PERF_BISECT_STAGE = 14/15` ne suffit pas : `shadowAtlasShadow` est actuellement commenté et ses anciennes branches n’ont pas été adaptées au chemin courant.

Alternative si tu veux rester entièrement manuel : calculer une fois les neuf comparaisons distinctes du noyau actuel et combiner leurs poids, au lieu d’exprimer seize lectures. C’est un exercice local possible, à comparer avant/après à image égale ; pas une promesse de neuf transactions physiques au lieu de seize.

## 8. Étape 3 — Calibrer les biais et identifier le popping

### 8.1 Ce que « peter panning » signifierait ici

Le terme décrit une ombre détachée de son objet, notamment quand le biais est trop grand. Une ombre qui apparaît pendant l’approche peut aussi venir d’un changement de cascade, d’un caster absent/clippé, d’une portée d’ombre ou d’une carte obsolète. Le symptôme verbal seul ne permet pas de choisir. [Microsoft : Common Techniques to Improve Shadow Depth Maps, Peter Panning](https://learn.microsoft.com/en-us/windows/win32/dxtecharts/common-techniques-to-improve-shadow-depth-maps#peter-panning).

### 8.2 Problème concret du biais actuel

`voxel_fragment_shader.glsl::computeBias()`, lignes 201–231, retourne :

```text
biasDepth = (0,0005 + 0,0003 × tanThetaClamped) × cascadeScale
cascadeScale = min(texelSize[i] / texelSize[0], 2)
```

Cette quantité est soustraite à `projCoords.z`, déjà dans [0,1]. Or `CSMCalculator::computeCascadeMatrix()`, lignes 423–478, construit une plage Z de longueur `4R` avec la marge actuelle de 0,5.

**Donc le déplacement équivalent le long de la direction lumineuse vaut `biasDepth × depthSpan`.** Il dépend de la cascade deux fois : via sa plage Z et via `cascadeScale`.

Exemple calculé à partir des paramètres actuels, **en supposant un aspect 3024/1832** — illustration, pas mesure de ton framebuffer courant. FOV 90°, near 0,1, far ombre 200, lambda 0,62, résolution 2048 :

| Cascade | Intervalle de sélection, unités monde | Texel monde | Plage Z | Biais monde de base, pente nulle | Biais monde à `tanTheta = 10` |
|---|---|---:|---:|---:|---:|
| 0 | 0,1 → 26,14 | 0,0535 | 219 | 0,110 | 0,767 |
| 1 | 26,14 → 60,52 | 0,1211 | 496 | 0,496 | 3,472 |
| 2 | 60,52 → 200 | 0,4022 | 1647,5 | 1,648 | 11,533 |

Ces valeurs ne sont pas la largeur exacte d’un trou dans l’ombre au sol ; ce sont des distances de comparaison le long de l’axe lumière. Elles montrent néanmoins pourquoi un détail peut perdre son ombre en cascade lointaine et la retrouver en approchant. **Haute confiance sur la différence d’échelle ; confiance moyenne sur sa responsabilité dans ton artefact précis tant que F9 et les captures ne l’ont pas isolée.**

Le normal offset du vertex shader, lignes 63–71, ajoute une seconde modification : jusqu’à `0,6 × texelSize[i]`, soit environ 0,032 / 0,073 / 0,241 unité dans cet exemple. Il n’est pas nul même face au soleil : la formule contient un plancher de 0,25.

### 8.3 Changements à implémenter

1. Avec soleil figé et mode shadow-factor, comparer biais normal / zéro, normal offset activé / désactivé, indépendamment. Le mode zéro est un diagnostic, pas forcément le réglage final. Regarder les contacts et la colline, pas seulement l’absence d’acné.
2. Ajouter à `CascadeData` la plage de profondeur effectivement utilisée par chaque matrice ; l’origine doit être `maxZ - minZ` dans `CSMCalculator`, pas une formule reconstruite approximativement dans le renderer.
3. Transmettre cette plage au fragment shader par un tableau d’uniforms. Donner au biais une unité explicite : une petite distance monde, éventuellement liée à la taille du texel et à la pente, avec une borne raisonnable.
4. Convertir une seule fois vers la profondeur de comparaison : `biasDepth = biasWorld / depthSpan`. Le signe doit continuer à rendre la comparaison légèrement moins auto-ombrante ; vérifier avec un bloc sur un plan.
5. Calibrer d’abord sans normal offset ; réintroduire ensuite un faible offset si nécessaire. Ne pas maximiser simultanément offset normal, bias de comparaison et polygon offset pour masquer l’acné.
6. Faire varier la résolution et les splits : le sens physique des réglages doit rester explicable. Vérifier aussi `N·L` borné correctement avant racine/tangente, pour ne pas créer de NaN par arrondi.

**Validation :** bloc d’une unité posé sur un plan ; même contact observé successivement à travers les trois cascades ; soleil haut puis rasant ; rapprochement de la colline avec et sans blend. Une légère acné transitoire pendant le réglage vaut mieux qu’un biais énorme qui efface les petites ombres.

### 8.4 Si le défaut reste présent

Comparer les cartes de cascades elles-mêmes, leur couverture UV/Z et les casters dessinés. Pour un essai, désactiver uniquement le culling de distance depth, sans changer les matrices. S’il n’y a aucune différence, ne pas incriminer ce culling.

Le rayon actuel `2,5R + 32` est large et cohérent avec l’ordre de grandeur du volume projeté actuel ; il n’est pas démontré qu’il coupe des casters internes à ce volume. En revanche, une colline hors de la plage Z ou hors du monde chargé peut projeter une ombre vers une zone visible. Augmenter les casters dessinés ne réparera pas leur clipping par la projection elle-même.

## 9. Étape 4 — Rendre les cascades cohérentes

### Ce qui est déjà correct à conserver

`CSMCalculator` calcule des rayons constants pour un FOV/aspect donnés, arrondit leur taille et snap la translation XY en texels. Les indices de snap sont maintenant stockés explicitement dans `CascadeData` : l’ancienne extraction incorrecte depuis `m30/m31` est corrigée.

**Ne pas remplacer ce fit stable par un AABB serré qui change d’échelle à chaque rotation de caméra sans évaluer le retour du shimmering.** La stabilité et l’utilisation optimale des texels sont un compromis. [Microsoft : Cascaded Shadow Maps, fitting et blending](https://learn.microsoft.com/en-us/windows/win32/dxtecharts/cascaded-shadow-maps).

### Changements ciblés

1. Vérifier les deux cartes dans chaque bande de blend de 4 unités, `sampleCascadeBlendedA()`, lignes 431–455 : le receveur **et le support du filtre** doivent être valides dans les deux projections.
2. Distinguer splits de sélection et volumes de rendu : si nécessaire, étendre le début de la cascade suivante pour couvrir la bande précédente, puis recalculer ses bounds, texel size et biais. Ne pas simplement agrandir la bande GLSL. Les sphères conservatrices actuelles créent déjà un recouvrement important ; je n’ai pas démontré un manque de recouvrement pour les paramètres actuels.
3. Afficher A, suivante, blend factor et masque hors domaine pendant le test. Un `return 1.0` hors texture ne doit pas être confondu avec une zone réellement éclairée.
4. Harmoniser le rayon de filtre entre cascades si la largeur apparente de pénombre change trop. Un rayon constant en texels donne une pénombre plus large en unités monde dans une cascade lointaine. Décider explicitement d’un compromis qualité/coût ; pas de promesse de pénombre physique avec ce seul PCF.
5. Traiter la fin de portée. `selectCascade()` retourne toujours la dernière cascade au-delà de 200 ; le helper passe ensuite brutalement à éclairé quand la projection sort du domaine. Ajouter une extinction progressive de l’ombre vers une portée choisie, avec une politique claire au-delà.
6. Choisir cette portée par rapport au fog et aux détails que tu veux ombrer. À distance de rendu 15, le fog actuel couvre environ 230–250 unités, alors que la portée nominale CSM est 200. Le fog est radial ; la sélection de cascade repose sur la profondeur vue : ne pas les comparer comme deux distances identiques en tout pixel.

Une bande de blend ne peut pas recréer les ombres qu’un biais excessif ou un caster manquant a supprimées. C’est pourquoi cette étape vient **après** le diagnostic de biais.

## 10. Étape 5 — Soleil mobile : référence correcte avant cache temporel

### 10.1 Pourquoi toutes les cascades sont redessinées

`ShadowRenderer::renderOffMode()`, lignes 354–360, appelle `updateLightBasis(sunDir, true)`. `CSMCalculator::updateLightBasis()`, lignes 220–260, retourne vrai après reconstruction dès lors que la base n’est pas gelée. `basisUpdated` rend donc vraie la condition de redraw, même si le vecteur solaire est identique à la frame précédente.

**Il ne suffit pas de remplacer `true` par `false`.** Tu réactiverais la quantification angulaire à 0,4°, et le cache ne regarde que le snap XY de la première cascade. Ni changements de géométrie, ni plage Z, ni tous les snaps ni tous les paramètres de projection ne constituent alors une invalidation complète.

À la vitesse actuelle, la phase solaire parcourt 360° en 600 secondes, environ 0,6°/s. Un seuil de 0,4° correspond à environ 0,67 s entre changements dans cet ordre de grandeur ; à vitesse 0,1, environ 6,7 s. C’est précisément le type de mise à jour en paliers que tu veux éviter. Le vecteur normalisé avec composante Z constante a une vitesse angulaire légèrement différente de celle de la phase ; ces nombres sont des estimations.

### 10.2 Première référence recommandée

Conserver, au départ, le calcul cohérent des trois cascades chaque frame avec la direction continue du soleil. C’est une référence de correction parfaitement raisonnable. Réduire d’abord le coût du filtre et, si nécessaire, de la résolution écran avant de compliquer le scheduling.

Le snap XY stabilise une grille pour une **orientation de lumière fixe**. Quand la lumière tourne, la grille tourne aussi et les casters sont rerasterisés. Le PCF adoucit les changements ; il ne les annule pas. Recalculer une carte avec entrées strictement identiques ne crée pas à lui seul un scintillement : si caméra, monde et soleil sont immobiles et que l’ombre bouge encore, chercher une autre entrée variable ou incohérence.

F6 suffit à figer le soleil pour le test de stabilité ; les passes continuent à être redessinées actuellement. F11 gèle la base mais change aussi la condition de cache : ce n’est pas un interrupteur de coût isolé équivalent.

### 10.3 Rendre le soleil visuellement crédible sans grande réécriture

- `SUN_SPEED_MULTIPLIER = 0.1` donne environ 100 minutes par cycle. C’est un choix de rythme, pas un correctif graphique ni un gain de passes automatique.
- Définir une convention unique du temps. Actuellement le soleil est à l’horizon pour `timeOfDay = 0` et haut pour 0,25 ; ciel et ambient appliquent un décalage de six heures, mais le HUD affiche directement `timeOfDay × 24`. Corriger la convention affichée ou la conversion, sans décaler seulement la lumière.
- Calculer un état solaire cohérent par frame et le réutiliser pour disque, éclairage et ombres facilite la vérification. Cependant les appels actuels à `getSunData()` sont des fonctions pures du temps stocké : `docs/research.txt` surestime le risque de désynchronisation entre eux. Aucune mise à jour du temps pendant ces appels de rendu n’a été identifiée.
- La trajectoire actuelle est `normalize(cos(angle), sin(angle), 0.2)` : suffisante pour un cycle stylisé, pas un modèle astronomique. Une trajectoire dans un plan incliné peut être un enrichissement local ultérieur ; latitude/saison et diffusion atmosphérique ne sont pas nécessaires pour corriger les ombres.
- Le soleil éclaire encore sous l’horizon jusqu’aux seuils `SUN_FADE_START = -0.2` et `SUN_FADE_END = -0.3`. Décider de la lumière directe voulue près de l’horizon ; ne pas confondre lumière diffuse du crépuscule et soleil direct traversant le terrain. Une contribution directe nulle permet aussi de sauter les ombres solaires, avec réinitialisation correcte au retour du jour.
- Le disque du soleil utilise `SIZE = 50`, `DIST = 200`, soit un diamètre angulaire d’environ 14°. C’est un choix très stylisé. Si tu réduis le disque, ne présume pas que le PCF actuel devient une simulation physique de sa pénombre : ces deux réglages sont aujourd’hui indépendants.

### 10.4 Cache facultatif, seulement si les passes depth dominent encore

Pour chaque cascade, conserver ensemble **texture rendue, matrice utilisée, direction de lumière, plage Z et version des casters**. Une texture ancienne ne doit jamais être consommée avec une nouvelle matrice simplement parce que le CPU l’a recalculée.

Invalider sur première utilisation, changement pertinent de soleil, projection/FOV/aspect, déplacement/rotation de caméra affectant la couverture, plage Z et changements de géométrie/meshes. Déplacer l’upload des meshes avant les ombres peut aussi rendre la frame cohérente : aujourd’hui il se produit dans `VoxelRenderer` après la passe depth, et le monde visible peut contenir un mesh nouveau absent de l’ombre de cette frame.

Commencer par le cache exact d’une scène complètement immobile. Ensuite seulement tester une fréquence plus basse pour la cascade lointaine, tout en gardant texture et métadonnées solidaires. Le seuil doit être lié au déplacement projeté en texels et au résultat visible, pas seulement à un angle arbitraire global. Une rotation donne grossièrement un déplacement proportionnel à distance × angle ; aux faibles hauteurs solaires, vérifier aussi les longues ombres des casters.

Ne pas réactiver la touche L comme solution rapide : le mode courant n’alloue plus C, alors que le chemin leapfrog reste présent et peut être activé à runtime. Il faudrait remettre son contrat de ressources et ses consommateurs à plat avant de le considérer fonctionnel.

## 11. Étape 6 — Si Retina reste trop coûteux : résolution 3D indépendante

**Solution de produit recommandée si le test de taille confirme un coût par pixel important : garder la fenêtre Retina et son UI native, mais rendre la scène 3D dans une cible plus petite.** Cela évite de dépendre de Retina OFF.

```text
Shadow maps : résolution indépendante, par exemple 2048²
        ↓
Sky + Sun + Voxel : FBO couleur/depth à résolution 3D choisie
        ↓
Composition plein écran vers framebuffer Retina
        ↓
UI / texte : résolution Retina native
```

### Changements précis à prévoir

1. Un seul propriétaire pour le FBO de scène, sa texture couleur et son attachment depth. Création/recréation au resize, suppression à la sortie, statut FBO vérifié. Pas besoin d’un backend générique ni d’adopter les wrappers dormants pour cet exercice.
2. Distinguer `framebufferWidth/Height` de `sceneWidth/Height`. Une échelle `s` signifie `sceneWidth ≈ s × framebufferWidth` et idem pour la hauteur ; garder l’aspect cohérent malgré l’arrondi.
3. Commencer à `s = 1`, puis 0,75 et 0,5. La surface de shading devient respectivement 100 %, 56,25 % et 25 % ; le coût des shadow maps, uploads, géométrie et présentation n’est pas divisé par ces facteurs.
4. Dans `Game::render()`, après les ombres, bind de la cible scène et viewport scène. Adapter `SkyRenderer` et `VoxelRenderer`, qui imposent actuellement FBO 0 et viewport fenêtre. Changer seulement un bind dans `Game` ne suffirait pas : leurs binds l’écraseraient.
5. Faire dessiner Sky/Sun/Voxel dans cette cible ; garder les restores des ombres explicites. Les dimensions CSM dépendent du FOV/aspect, pas du nombre de pixels du FBO scène à aspect constant.
6. Composer une seule fois l’image sur FBO 0 avec viewport framebuffer complet, sans depth test/write ni blend indésirable, puis rendre UI/HUD en pleine résolution. Un quad plein écran avec shader GLSL 1.50 convient.
7. Pour une première version non HDR, une cible couleur `RGBA8` stockant des valeurs linéaires est simple. Contrôler l’encodage final : éviter gamma appliqué deux fois ou jamais. `GL_FRAMEBUFFER_SRGB` ne convertit que pour une destination appropriée ; identifier l’encodage du framebuffer par défaut. Comparer à échelle 1 avant de juger la qualité à 0,75.
8. Tester contours de blocs et lisibilité ; upscale bilinéaire pour la scène comme première option, sans flouter l’UI. Pas besoin de TAA ou d’upscaler temporel pour mesurer le gain.

**Validation :** échelle 1 proche de la référence en couleur et cadrage ; resize et minimisation sûrs ; UI et clics inchangés ; gain mesuré de la passe scène lorsque l’échelle baisse. Si le temps total change peu, la résolution écran n’est pas le levier dominant.

## 12. Étape 7 — Réduire les passes depth si nécessaire

Les trois cascades actuelles sont 2048² : 12,58 millions de pixels de cible au total, mais le coût dépend aussi des casters et de leur recouvrement. L’atlas réserve encore six couches A/B alors que le mode direct n’en consomme que trois : 96 MiB logiques en D32F contre 48 MiB pour trois couches. Cette réserve n’est pas une fuite ; la réduire n’est pas une garantie de FPS.

Ordre conseillé après mesure :

1. **Distance de monde.** Comparer 15 puis 10 chunks, à scène comparable. La grille maximale passe de 31² à 21² chunks, sans garantir le même rapport de draws grâce au culling. Attention : cela change aussi le fog et la disponibilité de casters ; c’est un compromis produit, pas une optimisation gratuite.
2. **Culling depth précis.** `World::renderDepth()` teste actuellement la distance XZ au centre. Passer à un test conservateur des AABB de chunks contre le volume de lumière peut réduire les envois. Garder les casters extérieurs au frustum caméra capables d’ombrer ses receveurs ; ne pas recycler aveuglément le culling caméra.
3. **Résolution des ombres.** Comparer 2048 et 1024 comme expérience indépendante. 1024 réduit la surface depth, mais double la taille monde des texels et peut aggraver shimmering/popping. Recalibrer les biais en unités cohérentes ; ne pas cumuler ce test avec un changement de filtre.
4. **Allocation directe.** Si le leapfrog reste hors périmètre, envisager ensuite seulement un atlas de trois couches, avec commandes legacy empêchées de sélectionner des couches absentes.
5. **Cache ou cadence par cascade.** Seulement avec les invariants de la section 10.4 et une preuve que le coût depth justifie la complexité.
6. **Meshing plus compact.** Si les compteurs montrent une charge géométrique dominante, le meshing émet aujourd’hui deux triangles par face visible. Un greedy meshing pourrait réduire la géométrie, mais il faut préserver matériaux, UV d’atlas, normales et AO lors des fusions. C’est un chantier plus exigeant, pas le prochain correctif du shimmering.

## 13. Séquence pratique et critères d’arrêt

### Première séance : comprendre et obtenir un gain simple

- [ ] Noter le working tree/build et garder un CSV de référence avec ses paramètres.
- [ ] Rendre F4/F8 effectifs ; capturer la colline en couleurs de cascade et facteur d’ombre.
- [ ] Réparer la propagation resize de `PlayingState` vers `Game`.
- [ ] Tracer Retina ON/OFF sur les premières frames pour confirmer ou écarter le scénario GLFW.
- [ ] Comparer filtres 2 → 1 → 0, stage 6 constant, pose/soleil figés.
- [ ] Comparer deux tailles de fenêtre, Retina ON, aspect identique.

### Deuxième séance : corriger les ombres

- [ ] Tester séparément bias et normal offset, soleil figé.
- [ ] Exposer les plages Z et rendre les biais dimensionnellement cohérents.
- [ ] Vérifier les deux cascades dans la bande de transition.
- [ ] Vérifier casters, clipping et extinction de portée si le popping subsiste.
- [ ] Tester caméra seule, soleil seul, puis les deux, à vitesse solaire normale choisie.

### Troisième séance, seulement selon les mesures

- [ ] Fragment dominant : PCF matériel exclusif, puis résolution 3D indépendante si nécessaire.
- [ ] Depth dominant : culling de lumière et réglages de portée/résolution ; cache correct ensuite seulement.
- [ ] Attente de présentation dominante même dans une scène triviale : cas GLFW minimal et relevé du contexte/presentation ; ne pas réécrire les ombres pour ce symptôme.

**Objectif de validation, pas promesse :** si tu vises 60 FPS, le budget total est 16,7 ms. Il faut expliquer où sont dépensées ces millisecondes, choisir une qualité qui rentre dans ce budget et conserver les contacts d’ombre à travers les cascades. Une médiane correcte ne suffit pas : vérifier aussi les pics, le streaming et les déplacements.

**Ce que je ne recommande pas maintenant :** revenir au leapfrog pour masquer le problème, augmenter toutes les shadow maps à 4096, ajouter TAA/SSAO/bloom, migrer vers Metal, ou faire un refactor global du renderer. Les leviers immédiats sont déjà dans le moteur : un filtre moins coûteux, des dimensions fiables et des paramètres CSM physiquement interprétables.

## 14. Références et limites

Principaux points d’entrée examinés :

- `src/main/java/engine/core/Application.java` — création GLFW, dimensions, callback et `loop()`.
- `src/main/java/engine/core/states/PlayingState.java` — initialisation dimensions, logging, cleanup, absence d’override resize.
- `src/main/java/engine/Game.java` — `update()`, `render()`, `renderUI()` et commandes debug.
- `src/main/java/engine/render/renderer/VoxelRenderer.java` — `render()`, uniforms, atlas, upload et culling caméra.
- `src/main/java/engine/render/renderer/ShadowRenderer.java` — allocation, `renderOffMode()`, rendu par cascade et toggles.
- `src/main/java/engine/render/shadow/CSMCalculator.java` et `CascadeData.java` — bounds, profondeur, snap et culling.
- `src/main/resources/shaders/voxel_vertex_shader.glsl` et `voxel_fragment_shader.glsl` — normal offset, biais, filtrage et blend.
- `src/main/java/engine/time/TimeManager.java`, `core/config/RenderConfig.java` et `GameConfig.java` — cycle solaire et réglages.
- `src/main/java/engine/render/UIScreenScaler.java`, `engine/ui/UIManager.java`, `engine/ui/screens/MainMenuScreen.java`, `engine/input/Input.java` — UI et conversion des coordonnées.
- `src/main/java/engine/test/PerfLogger.java` et CSV de septembre — contenu réel des mesures.

Les sources GLFW/Khronos servent aux contrats de plateforme et d’API ; les articles Microsoft/NVIDIA expliquent les techniques, mais leurs détails Direct3D ou leurs performances matérielles ne sont pas transposés automatiquement à OpenGL sur Apple.

Sans capture et mesure GPU de cette exécution, je ne peux pas trancher définitivement la cause du cadrage Retina, attribuer chaque artefact au biais, ni promettre un nombre de FPS après correction. En revanche, le comptage du filtre, le redraw systématique, la conversion du biais et le défaut de resize sont directement vérifiables dans le code. Ils suffisent à définir une marche à suivre précise sans réécrire le moteur.

## 15. Implémentation et tests du 27 septembre 2026

### 15.1. Changements effectivement réalisés

Les changements locaux déjà présents ont été conservés. Cette intervention n'a changé ni les biais CSM, ni les splits, ni la résolution des shadow maps, ni le cycle solaire normal, ni les règles d'ownership.

| Fichier / méthode | Changement de cette intervention |
|---|---|
| `src/main/resources/shaders/voxel_fragment_shader.glsl`, lignes 9 et 26–29 | Stage **6 conservé**, `SHADOW_FILTER_MODE` passé de **2 à 1** : un PCF bilinéaire 2×2, soit quatre lectures depth par cascade au lieu de seize ; huit au lieu de trente-deux dans le blend. Commentaires de sélection corrigés. |
| `src/main/java/engine/core/config/GameConfig.java` — `readWindowScale()` | Taille initiale réglable par `-Dvoxel.windowScale`, **0.75 par défaut**, bornée à `[0.25, 1.0]`. Retina reste activé. `1.0` retrouve la grande fenêtre. |
| `src/main/java/engine/core/Application.java` — `init()` | La réduction est appliquée à la taille logique réellement accordée par macOS, après affichage et traitement des événements ; le framebuffer réel est ensuite interrogé. Aucune division arbitraire par deux du viewport. Callback resize : dimensions nulles ignorées. |
| `src/main/java/engine/core/states/PlayingState.java` — `onResize()` | Propagation des dimensions **framebuffer** à `Game::setWinDim()` et de leur rapport à `Game::setAspect()`. Le jeu ne doit plus réinjecter ses anciennes dimensions au rendu suivant. |
| `src/main/java/engine/core/config/GameConfig.java` | Harnais activable par `-Dvoxel.testHarness=true`, désactivé par défaut sans cette propriété. |
| `src/main/java/engine/Game.java` — `update()` | En mode harnais seulement, les entrées utilisateur et la physique du joueur ne concurrencent plus les positions imposées par le scénario. L'update du monde et celui du temps restent exécutés. |
| `src/main/java/engine/test/TestHarness.java` — `beginRun()`, `update()` | Scénario mobile conservé par défaut, soleil explicitement non pausé. Option **distincte** `-Dvoxel.testStatic=true` pour un témoin figé. Journalisation du scénario, de l'état pause et de l'heure solaire au départ et à la fin ; fin automatique à la durée configurée. |

**Il s'agit bien d'une fenêtre plus petite**, pas d'un rendu 3D basse résolution dans une grande fenêtre. Aucun FBO de downsampling/composition n'a été ajouté. Le bureau occupé diminue également ; Retina reste à ×2 dans les essais. Le mode 1 produit un filtre plus étroit que le mode 2 : ce choix reste à valider visuellement, et n'est pas présenté comme une correction du shimmering.

Piège constaté pendant l'implémentation : réduire directement la taille demandée au moniteur donnait un aspect différent, car Cocoa limitait la hauteur de la grande fenêtre mais pas celle de la petite. Le code final réduit la taille effectivement accordée. Les écarts d'aspect restants sont ceux de l'arrondi aux points entiers : environ 0,04 % à 75 %, 0,11 % à 50 %.

### 15.2. Protocole retenu : caméra et soleil mobiles

**Correction méthodologique suite à ton observation :** les premiers essais avaient volontairement la caméra et le soleil figés ; l'horloge à zéro était donc attendue. Ils décrivent seulement ce cas statique et ne suffisaient pas pour ton besoin. Ils ne sont pas utilisés ci-dessous pour annoncer un gain en jeu. La comparaison principale a été entièrement reprise avec le parcours mobile et le soleil actif.

Conditions communes aux sept essais mobiles :

- Machine relevée par le programme : **Apple M2 Pro**, OpenGL **4.1 Metal - 90.5**, GLSL **4.10** ; écran annoncé à 120 Hz, `VSYNC_ENABLED = 0`.
- Même monde `World 10`, seed `28371262719219135`, PERLIN, distance de rendu 15 ; joueur initial `(0.5, 100, 0.5)`, yaw/pitch zéro. Ce n'est pas la position de colline utilisée dans le précédent log du 26 septembre.
- Parcours existant de 20 secondes : avance 0–5 s ; rotation/strafe 5–10 s ; cercle avec oscillation de pitch 10–15 s ; recul 15–20 s. Le joueur reste en spectateur, à altitude constante.
- Soleil mobile, vitesse configurée **1.0**, départ `timeOfDay = 0`, `paused=false`. Les valeurs de fin relevées sont comprises entre **0.033035 et 0.033247**, toujours `paused=false`. Il ne s'agit donc ni d'une pause ni d'un cycle complet : le test couvre une courte portion de début de journée.
- Stage 6, ombres actives, leapfrog désactivé, trois cascades 2048². Aucun `glFinish` final ou après la shadow pass ajouté pour ces mesures.
- Une ligne CSV par frame. Pour les statistiques : conserver **3 < somme(dt) ≤ 19 secondes**, afin d'écarter le démarrage et la fermeture. FPS calculés par **nombre de frames / somme(dt)**, jamais par moyenne de `fps_smooth`. Médiane et P95 : valeur triée à l'indice arrondi `(N−1) × p`.
- Runs exécutés séparément, sans autre benchmark ni compilation simultanés. La température, les processus du système et la composition macOS ne sont pas contrôlés ; un petit écart n'est pas une preuve de gain reproductible.

Le parcours intègre les déplacements avec `dt` : il est scénarisé mais pas identique au bit près entre deux fréquences. Le clamp temporel existant à 50 ms peut aussi produire de petits écarts solaires. Les positions finales diffèrent de quelques centimètres à une dizaine de centimètres ; il ne faut pas interpréter un écart de 1 % comme une mesure GPU de précision. Les CSV mobiles contiennent une pose différente à chaque échantillon retenu.

### 15.3. Résultats mobiles : pas d'amélioration majeure démontrée

Chaque lien ouvre le CSV correspondant ; le filtre et la taille sont consignés ici d'après la source reconstruite et la commande de lancement. **Le CSV seul ne contient toujours pas ces deux métadonnées.**

| Essai / CSV du 27 septembre | Filtre | Taille logique relative | Framebuffer réel | Frames retenues | `dt` moyen | FPS calculés | Médiane / P95 `dt` | Médiane render / swap CPU |
|---|---:|---:|---|---:|---:|---:|---:|---:|
| [22:32:36 — référence](../logs/perf_test_World_10_2026-09-27_223236.csv) | 2 | 100 % | 3024×1842 | 659 | 24,278 ms | 41,19 | 24 / 26 ms | 3,46 / 20,43 ms |
| [22:32:58 — fenêtre seule](../logs/perf_test_World_10_2026-09-27_223258.csv) | 2 | 75 % | 2268×1382 | 667 | 24,001 ms | 41,66 | 24 / 26 ms | 3,52 / 20,10 ms |
| [22:33:59 — témoin sans PCF](../logs/perf_test_World_10_2026-09-27_223359.csv) | 0 | 100 % | 3024×1842 | 672 | 23,799 ms | 42,02 | 24 / 26 ms | 3,43 / 19,89 ms |
| [22:34:50 — filtre seul](../logs/perf_test_World_10_2026-09-27_223450.csv) | 1 | 100 % | 3024×1842 | 664 | 24,072 ms | 41,54 | 24 / 26 ms | 3,43 / 20,32 ms |
| [22:35:12 — les deux changements](../logs/perf_test_World_10_2026-09-27_223512.csv) | 1 | 75 % | 2268×1382 | 668 | 23,966 ms | 41,73 | 24 / 26 ms | 3,50 / 20,17 ms |
| [22:35:35 — réduction plus forte](../logs/perf_test_World_10_2026-09-27_223535.csv) | 1 | 50 % | 1512×922 | 674 | 23,736 ms | 42,13 | 24 / 26 ms | 3,34 / 20,06 ms |
| [22:35:58 — répétition des deux changements](../logs/perf_test_World_10_2026-09-27_223558.csv) | 1 | 75 % | 2268×1382 | 669 | 23,919 ms | 41,81 | 24 / 26 ms | 3,47 / 20,24 ms |

Les fenêtres logiques mesurées sont respectivement **1512×921**, **1134×691**, **756×461**. Passer à 75 % retire environ **44 % des pixels** du framebuffer ; passer à 50 % en retire environ **75 %**. Les shadow maps restent strictement à 2048².

Vérification par phases, pour éviter qu'une moyenne globale masque une phase beaucoup plus lente :

| Temps moyen de frame | Avance, 3–5 s | Rotation, 5–10 s | Cercle, 10–15 s | Recul, 15–19 s |
|---|---:|---:|---:|---:|
| Filtre 2, 100 % | 24,86 ms | 23,35 ms | 25,11 ms | 24,21 ms |
| Filtre 1, 75 % | 24,51 ms | 23,07 ms | 24,77 ms | 23,88 ms |
| Filtre 1, 75 %, répétition | 24,54 ms | 22,95 ms | 24,73 ms | 23,90 ms |

**Conclusions :**

1. **Haute confiance pour ces runs :** les deux changements fonctionnent, mais ne débloquent pas les performances. La différence combinée observée est seulement de **0,31–0,36 ms**, soit environ **1,3–1,5 %** du temps de frame de référence. Sans répétitions appariées supplémentaires, ce n'est pas un gain significatif établi.
2. **Confiance moyenne, limitée à ce parcours :** ni le nombre de lectures du PCF ni le nombre de pixels du framebuffer ne se comporte comme le coût dominant attendu. Même passer au filtre 0 ou à une fenêtre à 50 % laisse le résultat autour de 42 FPS. Cela ne prouve pas que leur coût GPU est nul ou qu'une autre vue serait aussi peu sensible.
3. **Haute confiance sur la nature des mesures :** environ 20 ms sont toujours observées côté CPU dans `swapBuffers`. Cela ne donne pas 20 ms de calcul de présentation : GPU en retard, file de commandes et présentation restent à distinguer. `render_ms ≈ 3,4 ms` n'est pas le temps GPU du monde ou des ombres.
4. **Non démontré :** le coût dominant des shadow passes. C'est une piste logique à mesurer, car leur résolution ne change dans aucun de ces tests, mais les CSV ne permettent pas de l'affirmer.
5. **Aucune conclusion nouvelle sur la qualité temporelle :** shimmering, contacts, biais et popping entre cascades n'ont pas été corrigés par cette intervention. Le parcours de 20 s ne valide pas la scène de colline ni un cycle jour/nuit complet.

Le témoin filtre 0 sert seulement de diagnostic ; il n'est pas le réglage conservé. Un essai de démarrage Java bloqué par le sandbox Cocoa a brièvement coexisté avec ce témoin, puis a été arrêté : raison supplémentaire de ne pas traiter son petit écart comme une mesure précise. Les comparaisons principales filtre 2 / filtre 1 n'ont pas ce chevauchement.

### 15.4. Premiers essais statiques : traçabilité, pas validation du jeu mobile

Ils ne sont pas mélangés avec le tableau précédent. Ils conservaient `timeOfDay = 0`, caméra fixe. Contrairement à un cache d'ombres, cette pause ne supprimait pas la shadow pass : `ShadowRenderer::renderOffMode()` appelle toujours `updateLightBasis(sunDir, true)` ; `CSMCalculator::updateLightBasis()` retourne alors `true` hors freeze debug, ce qui déclenche le redraw. Le contenu et la visibilité de la scène restaient néanmoins différents d'un parcours mobile.

| Filtre | Échelle | CSV statique | `dt` moyen / FPS calculés |
|---|---:|---|---:|
| 2 | 100 % | [22:27:31](../logs/perf_test_World_10_2026-09-27_222731.csv) | 23,666 ms / 42,26 |
| 2 | 75 % | [22:27:53](../logs/perf_test_World_10_2026-09-27_222753.csv) | 23,444 ms / 42,66 |
| 2 | 50 % | [22:28:16](../logs/perf_test_World_10_2026-09-27_222816.csv) | 23,238 ms / 43,03 |
| 1 | 100 % | [22:30:37](../logs/perf_test_World_10_2026-09-27_223037.csv) | 23,599 ms / 42,38 |
| 1 | 75 % | [22:31:00](../logs/perf_test_World_10_2026-09-27_223100.csv) | 24,339 ms / 41,09 |
| 1 | 50 % | [22:31:22](../logs/perf_test_World_10_2026-09-27_223122.csv) | 23,370 ms / 42,79 |

Deux pilotes antérieurs, `22:25:37` et `22:26:28`, ne participent pas à la comparaison retenue. Le deuxième utilisait 2268×1458 : son aspect différent rendait la comparaison de taille ambiguë. Les fichiers sont conservés, pas supprimés.

### 15.5. Reproduire les essais et revenir à la grande fenêtre

Construire le lanceur (Gradle doit avoir accès à son cache ; `--offline` suppose les dépendances déjà présentes) :

```sh
./gradlew --offline installDist
```

Essai principal, **caméra et soleil mobiles**, fermeture automatique après 20 s :

```sh
JAVA_OPTS='-Dvoxel.testHarness=true -Dvoxel.testStatic=false -Dvoxel.windowScale=0.75' \
  build/install/TOB_Voxel_Project/bin/TOB_Voxel_Project
```

Remplacer seulement `0.75` par `1.0` ou `0.5` pour comparer la taille. Pour comparer le filtre, changer uniquement `SHADOW_FILTER_MODE` dans le GLSL entre `0`, `1`, `2`, puis **reconstruire avec `installDist` avant le prochain lancement**. Le réglage final livré est **1**. Ne pas modifier simultanément stage, biais, soleil, distance ou shadow maps.

Contrôle dans la console : `Scenario: movement timeline`, `Sun: paused=false; timeOfDay=0.0`, puis une heure supérieure à zéro et une position modifiée à `TEST HARNESS COMPLETED`. Le scénario statique n'est demandé que par `-Dvoxel.testStatic=true` ; il ne doit pas servir seul à valider les performances d'un soleil mobile.

Jeu normal, grande fenêtre Retina, sans harnais :

```sh
JAVA_OPTS='-Dvoxel.windowScale=1.0' \
  build/install/TOB_Voxel_Project/bin/TOB_Voxel_Project
```

Sans propriété, le lancement normal utilise la fenêtre à **75 %**, avec le soleil et les contrôles normaux. Cette valeur est un réglage d'essai conservé à ta demande, **pas une obligation pour atteindre un gain établi** : la grande fenêtre reste un choix raisonnable au vu des mesures.

Validation effectuée : compilation et `installDist` réussis ; les sept essais mobiles se terminent normalement, avec cleanup et sauvegarde du joueur/monde désactivée par le harnais. Les SHA-256 de `saves/World_10/world.json` et `player.dat` sont identiques avant/après. Cela ne certifie pas l'absence de toute fuite mémoire. `build.gradle` désactive les sources JUnit : il ne faut pas présenter cette validation comme une suite de tests unitaires.

Limites opérationnelles : avertissement LWJGL `Unsupported JNI version` sous le Java 23.0.1 installé, sans crash dans les runs graphiques terminés ; deux tentatives de lancement en sandbox ont dû être arrêtées à cause d'erreurs Cocoa/XPC. Les benchmarks retenus ont été lancés avec accès graphique autorisé. Ni le resize interactif dans tous les menus, ni le passage entre écrans, ni la qualité visuelle des ombres n'ont été validés automatiquement. Vérifier manuellement le HUD, l'aspect et les clics après resize avant de considérer ce point clos.

**Attention au versionnement :** la règle actuelle `.gitignore` `test/` ignore aussi `src/main/java/engine/test/TestHarness.java` et `PerfLogger.java`. La modification du harnais existe bien localement mais ne sera pas incluse par un ajout Git ordinaire. `.gitignore` n'a pas été modifié pendant cette intervention. Les CSV de `logs/` sont également ignorés ; garder leur correspondance avec ce tableau si tu archives les expériences.

### 15.6. Ordre de travail révisé à partir des résultats

1. **Ne pas engager maintenant le FBO à résolution réduite ni le PCF matériel comme remède présumé aux 40–50 FPS.** Les deux expériences simples n'établissent pas ce diagnostic. Garder les réglages réversibles et choisir le filtre selon sa qualité visible.
2. **Instrumenter le temps GPU par passe**, sur le contexte **4.1 effectivement obtenu** : shadow pass, monde, ciel/soleil, UI. Lire les queries plusieurs frames plus tard, sans attente immédiate. Conserver séparément les mesures CPU `update`, `render`, `swap`. Le contrat minimal 3.2 du moteur requiert toujours une vérification de capacité/extension si cet outil doit fonctionner ailleurs.
3. **Compter les draw calls et vertices réels**, notamment ceux des trois cascades. Les colonnes actuelles à zéro ne permettent pas d'évaluer la géométrie soumise.
4. **Faire ensuite une ablation contrôlée**, avec caméra/soleil mobiles identiques : désactiver réellement la génération ET la consommation des ombres, pas seulement les fetches du fragment shader. Si le GPU termine rapidement mais que l'attente de présentation persiste, investiguer GLFW/présentation avec un cas minimal avant de modifier le CSM.
5. **Reprendre le diagnostic visuel séparément** : uniforms de debug effectifs, pose de colline reproductible, biais en unités explicites, couverture des cascades et stabilité sous rotation solaire. Les sections 8–10 restent des pistes d'investigation, pas des corrections validées par ces benchmarks.

**Bilan : les deux solutions simples sont implémentées et testées, mais le problème de performances reste ouvert. Les résultats mobiles ne justifient ni une promesse de 60 FPS, ni un diagnostic définitif contre Retina.**

## 16. Culling des cascades et mesures GPU — 28 septembre 2026

### 16.1. Piste choisie et diagnostic

**Réduire la géométrie soumise à la génération des shadow maps, sans réduire leur qualité.** Le filtre PCF et la petite fenêtre restent aux réglages de la section 15 : mode 1, stage 6, Retina actif, fenêtre 75 %. Ni le soleil ni la caméra n'ont été figés pour les nouveaux benchmarks.

Dans `World::renderDepth()`, le chemin précédent filtrait les chunks avec un disque de distance XZ autour de chaque cascade. `CascadeData::cullRadius()` fournit `2.5 × rayon + 32`. Ce filtre est large et n'effectue pas de test contre les six plans du volume réellement rasterisé par la lumière. Des chunks soumis au GPU sont donc finalement rejetés par le clipping.

La première exécution instrumentée, [GPU 14:21:21](../logs/gpu_pass_2026-09-28_142121_016.csv), a relevé environ **43,3 millions de vertices soumis aux trois cascades par frame**, contre **6,46 millions pour le monde visible**. Ce sont des nombres de vertices passés aux draw calls, pas des triangles ni une mesure de trafic mémoire physique. La passe depth n'était donc pas un coût négligeable : intervalle GPU mesuré d'environ 11 ms.

**Ce qui change dans le diagnostic :** nous avons maintenant une réduction de travail géométrique vérifiable et une amélioration mesurée sans profiler. Cela soutient cette optimisation locale. Cela ne prouve pas que le renderer est exclusivement limité par les vertices, ni que le filtrage n'a aucun coût sur une autre scène.

### 16.2. Modification conservatrice implémentée

| Point de code | Responsabilité ajoutée |
|---|---|
| `core/config/RenderConfig.java` — `SHADOW_FRUSTUM_CULLING_ENABLED` | Culling activé par défaut, désactivable au démarrage par `-Dvoxel.shadowFrustumCulling=false` pour retrouver la référence. |
| `render/renderer/ShadowRenderer.java` — `renderCascadeDepth()` | Extrait les plans de la **même matrice light-space que celle envoyée au depth shader** ; réutilise un `FrustumIntersection`, sans nouvel objet par chunk. Chemins atlas et staging passent par ce helper. |
| `world/World.java` — `renderDepth(..., lightFrustum)`, `intersectsShadowFrustum()` | Après le filtre de distance existant, rejette uniquement les AABB entièrement hors du volume lumineux. |
| `world/World.java`, `render/renderer/VoxelRenderer.java` | Comptent les draw calls et vertices effectivement soumis ; les totaux shadow sont remis à zéro au début de la frame. |
| `render/GpuPassTimer.java`, `Game::render()`, `PlayingState::enter()/exit()` | Profilage optionnel des quatre groupes de passes ; ownership des queries dans le timer, créé et fermé par `PlayingState` avant destruction du contexte. |
| `engine/test/PerfLogger.java`, `PlayingState::update()` | Ajout de `shadowFrustumCulling`, `fbWidth`, `fbHeight`, `timeOfDay` ; compteurs voxel renseignés et compteur de vertices de type `long`. |
| `tools/tests/ShadowFrustumCullingTest.java` | Test CPU autonome des cas limites et de 20 000 volumes lumineux. |
| `.gitignore` | Exceptions ciblées pour les deux sources runtime `engine/test/TestHarness.java` et `PerfLogger.java`, précédemment masquées par `test/`. Aucun ajout à l'index Git n'a été effectué. Les sorties de benchmark restent ignorées. |

Chemins du tableau relatifs à `src/main/java/engine/`, sauf `.gitignore` et `tools/tests/`.

Invariants conservés :

- **Frustum de lumière, pas de caméra** : un caster hors écran reste admissible s'il peut être rasterisé dans la cascade.
- Bounds du chunk complets : `[x, x+16] × [0, 256] × [z, z+16]`, étendus de **0,01 unité** pour les frontières numériques. Le `MeshBuilder` actuel produit ses vertices dans ces bounds, y compris les faces supérieures à `y=256`.
- Le depth shader ne déplace pas les vertices : `lightSpaceMatrix × modelMatrix × position`. La projection utilise le clip OpenGL `−w ≤ x,y,z ≤ w`. Le code actuel n'active pas le depth clamp. Un futur changement de géométrie ou de clipping devra réexaminer cet invariant.
- Matrices, splits, biais, offset des receivers, format depth, résolution 2048², trois cascades et redraw avec soleil mobile **inchangés**. Le filtre de distance préalable est conservé ; ses éventuelles insuffisances antérieures ne sont pas corrigées ici.
- Aucun cache temporel, nouvelle cadence d'ombres, texture supplémentaire de production ou architecture de type RenderGraph.

Le test AABB peut garder trop de chunks, mais son objectif est de ne pas retirer une contribution existante. Les bounds verticaux complets sont volontairement conservateurs. [Contrat JOML 1.10.5 de `FrustumIntersection`](https://joml-ci.github.io/JOML/apidocs/org/joml/FrustumIntersection.html).

### 16.3. Instrumentation GPU et sa perturbation mesurée

`-Dvoxel.gpuTiming=true` active quatre queries `GL_TIME_ELAPSED` non imbriquées : **ombres**, **ciel + soleil**, **voxels**, **UI**. Un anneau de huit frames diffère les lectures. `GL_QUERY_RESULT` n'est demandé qu'après disponibilité de tous les résultats ; un slot encore occupé entraîne l'abandon de la nouvelle mesure, pas l'attente du GPU. Les résultats portent leur propre `frameIndex`, temps écoulé, dimensions, pose caméra, heure solaire et réglage du culling ; il ne faut pas les joindre au CSV CPU par simple numéro de ligne.

Activation conditionnée par OpenGL 3.3 ou `ARB_timer_query` et un nombre de bits non nul. Sur ce Mac, contexte obtenu **OpenGL 4.1 Metal - 90.5**, GLSL 4.10. Sur un contexte 3.2 sans extension exploitable, le profiler est désactivé avec un message, sans changer le rendu. Aucun `glFinish` ajouté. La disponibilité différée évite l'attente explicite des résultats, **pas toute perturbation du driver**. [Spécification `ARB_timer_query`, §§5.4 et 6.1.6](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_timer_query.txt).

Comparaison instrumentée, mêmes réglages, caméra et soleil mobiles ; moyennes dans la fenêtre `3 < elapsed ≤ 19 s` :

| Mesure | [Avant, GPU 14:24:19](../logs/gpu_pass_2026-09-28_142419_444.csv) | [Après, GPU 14:24:42](../logs/gpu_pass_2026-09-28_142442_264.csv) |
|---|---:|---:|
| Échantillons GPU retenus | 560 | 635 |
| Draw calls shadow / frame | 2 335,6 | 1 732,6 |
| Vertices shadow / frame | 43 291 947 | 31 893 225 |
| Intervalle GPU shadow | 10,993 ms | 8,896 ms |
| Draw calls voxel / frame | 348,3 | 348,3 |
| Vertices voxel / frame | 6 459 330 | 6 455 724 |
| Intervalle GPU voxel | 13,786 ms | 13,003 ms |
| Intervalle GPU UI | 0,121 ms | 0,103 ms |

Soit **26,3 % de vertices shadow soumis en moins**, et **19,1 % de réduction de l'intervalle shadow mesuré**. La légère différence de géométrie voxel vient du parcours intégré avec `dt`, pas d'un nouveau culling caméra. Les intervalles GPU couvrent les commandes et peuvent aussi refléter les effets d'ordonnancement ou d'alimentation du GPU ; ne pas les convertir en coût exact d'une instruction ou d'un vertex.

**Réserves importantes :**

- Le groupe ciel/soleil renvoie zéro dans ces relevés. Cela ne prouve pas son coût nul : attribution/résolution des timers sur cette implémentation restent une limite.
- Avec les queries, l'attente CPU se déplace largement dans `render_ms` et `swap_ms` tombe autour de 0,5 ms. Le temps de frame augmente aussi par rapport aux runs sans queries. La cause exacte de cette perturbation du driver n'est pas prouvée. Les valeurs profilées ne servent donc **pas** à annoncer le gain de FPS de production.
- Les trois runs instrumentés se sont terminés avec `skipped=0`, aucun résultat restant abandonné à la fermeture. Les 32 objets query sont détruits par `close()`. Profiler **désactivé par défaut**, donc aucune query allouée en jeu normal.

### 16.4. Gain réel : série avant/après/après/avant, sans queries

Quatre runs distincts de 20 secondes, **caméra et soleil mobiles**, dans l'ordre A–B–B–A. Même `World 10`, seed `28371262719219135`, spawn `(0.5,100,0.5)`, distance 15, framebuffer **2268×1382**, filtre 1, stage 6. Queries et validation depth désactivées. Pas de compilation ni d'autre benchmark lancés simultanément. Les autres processus Java identifiés étaient des services IDE/Gradle, pas un second jeu.

Analyse identique à la section 15 : conserver `3 < somme(dt) ≤ 19 s` ; FPS = nombre de lignes retenues / somme de leurs `dt`. P50/P95 à l'indice arrondi `(N−1) × p`. Aucun mélange avec les logs figés du 27 septembre.

| Run / CSV | Culling ajouté | Frames | `dt` moyen | FPS calculés | P50 / P95 | Render / swap CPU médians |
|---|---|---:|---:|---:|---:|---:|
| [A1 — 14:28:14](../logs/perf_test_World_10_2026-09-28_142814.csv) | OFF | 674 | 23,745 ms | 42,11 | 24 / 26 ms | 4,93 / 18,39 ms |
| [B1 — 14:28:36](../logs/perf_test_World_10_2026-09-28_142836.csv) | ON | 731 | 21,889 ms | 45,68 | 22 / 25 ms | 4,06 / 17,47 ms |
| [B2 — 14:28:59](../logs/perf_test_World_10_2026-09-28_142859.csv) | ON | 713 | 22,435 ms | 44,57 | 22 / 28 ms | 3,22 / 18,17 ms |
| [A2 — 14:29:21](../logs/perf_test_World_10_2026-09-28_142921.csv) | OFF | 649 | 24,664 ms | 40,54 | 25 / 28 ms | 4,60 / 19,70 ms |

En regroupant les frames retenues de chaque réglage :

- **Avant : 24,196 ms / 41,33 FPS**, P95 27 ms.
- **Après : 22,159 ms / 45,13 FPS**, P95 26 ms.
- Différence observée : **−2,04 ms par frame**, soit **−8,4 % de temps** ou **+9,2 % de FPS**.

**Confiance moyenne sur la taille exacte du gain, haute sur la réduction de géométrie.** Les deux runs ON sont plus rapides en moyenne que les deux runs OFF, mais la machine n'est pas dans un environnement thermique/processus strictement contrôlé. Le P95 du deuxième run ON atteint encore 28 ms : ne pas présenter cela comme une suppression des saccades. Le temps solaire progresse dans les CSV, et toutes les poses retenues sont mobiles.

Le budget de 60 FPS, 16,7 ms, n'est toujours pas atteint. Ce changement retire du travail inutile sans baisse volontaire de qualité ; ce n'est pas la résolution complète du problème du moteur.

### 16.5. Tests de conservation des ombres

Deux validations complémentaires ont été exécutées :

1. **Test CPU autonome** : frontières, coordonnées négatives, inclusion de la face à `y=256`, puis **20 000 volumes lumineux pseudo-aléatoires**. Pour chaque rejet, un oracle indépendant transforme les huit coins et vérifie qu'un même plan clip exclut tous les coins. **19 102 rejets vérifiés, test passé.** Cela exerce des orientations et positions variées, mais n'est pas une preuve formelle de tous les cas flottants possibles.
2. **Comparaison GPU dans les mêmes frames** : option `-Dvoxel.validateShadowCulling=true`, trois cascades comparées au début puis vers 5, 10 et 15 s. Pour chaque cascade : rendre avec culling, lire le depth, effacer, rerendre sans le nouveau culling avec les **mêmes matrices et meshes**, relire et comparer exactement les valeurs float. **12 comparaisons × 4 194 304 texels = 50 331 648 valeurs comparées ; zéro différence**, aucune erreur GL détectée par ce contrôle. Console du run [14:27:32](../logs/perf_test_World_10_2026-09-28_142732.csv) : douze `Shadow culling validation PASS` ; le CSV contient les timings, pas ces messages de validation.

Le second test introduit des readbacks synchrones et des draws supplémentaires : **son CSV est exclu de toutes les conclusions de performances**. Deux buffers natifs temporaires, 32 Mio au total, sont libérés dans des `finally` à chaque comparaison. L'option est désactivée par défaut.

Ces tests établissent la conservation du depth pour les cascades et frames échantillonnées, pas la correction de l'ancien peter-panning ou du shimmering. Ils ne remplacent pas un test visuel prolongé de la colline, d'un cycle solaire complet, du streaming et des modifications de terrain. Le mode leapfrog dormant bénéficie du même helper, mais n'a pas été validé à runtime ici.

Compilation `installDist` et test CPU réussis. Les runs graphiques terminent normalement avec cleanup ; SHA-256 de `world.json` et `player.dat` inchangés. L'avertissement préexistant LWJGL/JNI sous Java 23.0.1 reste visible ; il n'a pas été corrigé dans ce chantier. Le décalage antérieur des timings CPU d'une frame dans `PerfLogger` reste documenté : les champs de pose/heure sont ceux de l'update courant, les timings et compteurs voxel ceux du rendu précédent.

### 16.6. Reproduire, désactiver et poursuivre

Construire, puis exécuter le test CPU (indépendant des sources JUnit désactivées dans Gradle) :

```sh
./gradlew --offline installDist
javac -cp 'build/classes/java/main:build/install/TOB_Voxel_Project/lib/*' \
  -d build/diagnostic-tests tools/tests/ShadowFrustumCullingTest.java
java -cp 'build/diagnostic-tests:build/classes/java/main:build/install/TOB_Voxel_Project/lib/*' \
  engine.world.ShadowFrustumCullingTest
```

Benchmark mobile **sans instrumentation**, avec culling :

```sh
JAVA_OPTS='-Dvoxel.testHarness=true -Dvoxel.testStatic=false -Dvoxel.windowScale=0.75 -Dvoxel.shadowFrustumCulling=true' \
  build/install/TOB_Voxel_Project/bin/TOB_Voxel_Project
```

Pour le témoin, remplacer uniquement `voxel.shadowFrustumCulling=true` par `false`, sans reconstruire. Pour profiler, ajouter `-Dvoxel.gpuTiming=true` dans `JAVA_OPTS` : un second fichier `logs/gpu_pass_*.csv` apparaît. Pour vérifier les shadow maps, ajouter **séparément** `-Dvoxel.validateShadowCulling=true` et garder le culling actif. Ne pas utiliser ce dernier run comme benchmark.

En jeu normal, sans propriétés de diagnostic : **culling actif ; harnais, queries et validation désactivés ; soleil mobile**. Le retour arrière de cette optimisation se fait avec `-Dvoxel.shadowFrustumCulling=false`.

**Prochaine piste, non implémentée dans cette intervention :** expliquer les quelque **6,5 millions de vertices du monde visible**, ainsi que les 31,9 millions restant soumis aux ombres. D'abord examiner les bounds réellement occupés des meshes et l'efficacité du culling, puis le volume de géométrie produit par le meshing ; mesurer le vertex shader séparément avant de conclure à un coût fragment ou de lancer un refactor de meshing. La cohérence des biais et des cascades reste un chantier visuel distinct. Ne pas réintroduire du leapfrog pour masquer ces coûts.

## 17. Prochaine étape à implémenter manuellement : bounds réelles des meshes

Le [guide AABB extrêmement détaillé, pas à pas](Guide_AABB_meshes_pas_a_pas.md) décrit la prochaine expérience : remplacer les boîtes larges du culling caméra (`Y = −32..288`) et lumineux (`Y = 0..256`) par les bornes calculées depuis chaque mesh effectivement uploadé.

Il couvre le calcul sans altérer le `FloatBuffer`, le transport worker → upload → chunk, la cohérence au remeshing, les deux consommateurs, les tests CPU/GPU, les commandes et les critères d'arrêt. Les variantes caméra et ombres sont isolées pour éviter de mélanger leurs effets. Le principal invariant est l'association des bounds au **VBO courant**, pas à l'état le plus récent des blocs.

**Statut : guide uniquement. Aucun changement du moteur, aucune compilation ni nouveau benchmark dans cette phase. Le gain de cette nouvelle piste reste à mesurer ; les propriétés `voxel.tightShadowBounds` et `voxel.tightCameraBounds` sont des noms proposés, pas des options déjà implémentées.**

## 18. Application et résultats des bounds réelles — 22 octobre 2026

Le statut de la section 17 décrit la phase de rédaction initiale. Le guide a maintenant été **implémenté et testé**. Le [rapport complet](Rapport_AABB_meshes_2026-10-22.md) détaille les changements, commandes, logs, tests et limites.

Les AABB sont calculées depuis les positions du buffer interleavé après `flip`, transportées avec le `MeshData`, puis installées avec le VAO/VBO correspondant. Elles sont effacées au cleanup et remplacées au remeshing. Les deux consommateurs sont indépendants : culling lumineux et culling caméra. Les anciens volumes restent le fallback si les bornes manquent. Aucun shader, biais, filtre, mesh, format de vertex, nombre de cascades, cadence solaire ou paramètre Retina n'a été changé.

### Correction

- Tests CPU : **40 000 cas de frustum** au total, plus tests de buffer, boîtes planes/vides, coordonnées négatives, Y=256 et containment.
- Test GL : vrai upload worker, remeshing bas → haut → bas, destruction des anciens VAO/VBO, cleanup/ré-upload, chemin direct et mesh vide : **PASS**.
- Parcours mobile : **12 shadow maps comparées exactement** au chemin sans frustum lumineux, puis **4 images caméra couleur + depth comparées exactement** entre volumes anciens et précis : **zéro différence** sur les frames contrôlées.
- Les validations sont synchrones et exclues des chiffres de performance. Soleil mobile vérifié, pas de réutilisation des mesures à heure figée.

### Performance : résultat modeste, pas de gain FPS robuste

Sur le M2 Pro, Retina **2268 × 1382**, fenêtre 75 %, trois cascades 2048² et stage 6 :

| Variante | Géométrie soumise, profils séparés | FPS sans instrumentation, comparaison ABBA |
|---|---|---|
| Bornes shadow seules | Vertices shadow **−3,72 %** | 45,197 → **45,199** : inchangé |
| Bornes caméra seules | Vertices voxel **−1,08 %** | 45,097 → **45,425** : +0,73 %, à confirmer |
| Combinaison | Pas de profil GPU combiné ; les deux tests sont actifs | 45,105 → **44,990** : −0,25 %, non concluant |

Les dix runs ont été séquentiels, caméra et soleil mobiles, sans queries/readbacks/glFinish de diagnostic. Les séries partagent leurs témoins de frontière : A–S–S–A–C–C–A–B–B–A. Les petites différences de FPS ne sont pas une preuve de gain ou régression reproductible ; le P95 reste environ 26–28 ms.

**Décision : les deux nouvelles options restent désactivées par défaut.** Elles existent et peuvent être activées sans recompilation : `-Dvoxel.tightShadowBounds=true`, `-Dvoxel.tightCameraBounds=true`. Le calcul des bornes reste effectué au meshing, même options OFF. Le culling lumineux précédent reste actif.

Le scan CPU ajoute environ **0,037 ms** sur un mesh synthétique chaud de 30 984 vertices, contre 2,187 ms pour la génération complète incluant ce scan. Cette micro-mesure isolée ne vaut pas validation du chargement/streaming sous forte charge.

**Analyse :** les volumes trop larges expliquent une petite partie des soumissions, pas les performances globales autour de 45 FPS sur ce parcours. La correction du culling est étayée ; son activation comme optimisation par défaut ne l'est pas encore. Les nombreux vertices restants justifient une investigation séparée du meshing/indexage, sans promesse de FPS proportionnels au gain de stockage. Les artefacts de cascades restent un problème distinct : cette intervention ne les corrige pas.

## 19. Indexation des quads et harnais discret — 23 octobre 2026

L'indexation est implémentée et **activée par défaut**. Le [rapport détaillé](Rapport_indexation_2026-10-23.md) rassemble les changements, tests, limites et logs reproductibles.

- Quatre vertices complets et six indices uint32 par quad au lieu de six vertices dupliqués ; mêmes triangles, attributs et winding. Les passes monde et ombres utilisent toutes deux le mesh indexé.
- EBO possédé par le chunk, remplacé et détruit avec son mesh ; buffers natifs possédés par `MeshData`.
- Harnais sans demande de focus ni capture du curseur. Fenêtre réelle visible, non minimisée, résolution inchangée. Les états de fenêtre sont journalisés pour détecter un test non comparable.
- Métriques distinctes : références soumises, vertices stockés, octets VBO/EBO, flag d'indexation et état de fenêtre.

**ABBA mobile non instrumenté : 43,78 → 58,66 FPS (+34,0 %), 22,84 → 17,05 ms/frame.** Les deux runs indexés sont à 60,23 et 57,09 FPS ; P95 21 et 24 ms. Ce n'est pas une garantie de 60 FPS constants. Les données de meshes installés passent d'environ 676 à 526 Mo ; cela ne représente pas toute la VRAM.

Profils GPU séparés : shadow 8,97 → 7,40 ms, voxel 13,05 → 8,81 ms, avec environ 31,95 et 6,45 millions de références soumises. Le gain ne vient pas d'une suppression de triangles. Les résultats sont propres à ce parcours et à ces conditions de bureau ; ne pas les comparer directement aux anciens essais focalisés.

Tests : expansion exacte des neuf attributs via les indices, cas vide/terrain/AO/frontières et plus de 65 536 vertices ; rasterisation couleur/depth de snapshots sous quatre projections ; remplacement et destruction des EBO ; worker et remeshing. Les shaders de production n'ont pas été modifiés. Le rapport précise ce qui est couvert par ces tests et ce qui reste à contrôler.

Retour arrière sans recompilation : `-Dvoxel.indexedMeshes=false`. Les deux options de bounds précises restent OFF. Le shimmering et les biais d'ombres restent un chantier séparé.

## 20. Cache des locations d'uniforms — 23 octobre 2026

Expérience implémentée et mesurée : mémoriser les locations dans chaque `Shader`, y compris les uniforms inactifs, sans toucher aux uploads ou aux shaders. Validation mobile de chaque hit contre une recherche GL directe : aucune divergence. Voir le [rapport et les CSV](Rapport_uniform_cache_2026-10-23.md).

Les recherches passent d'environ **2,6 millions par run à 59 résolutions initiales**, mais la série ABBA non instrumentée donne **61,284 → 61,244 FPS** : aucun gain de débit démontré. Les 61 FPS des témoins étaient déjà présents dans cette session ; ils ne sont pas un gain par rapport aux mesures antérieures attribuable à ce changement.

Le cache reste **OFF par défaut**, activable par `-Dvoxel.cacheUniforms=true`. L'indexation reste ON. Cette piste CPU n'explique pas le temps restant de la frame ; l'expérience suivante devra cibler le travail GPU, sans en déduire prématurément un mécanisme matériel précis.

## 21. Éviter les ombres sans contribution directe — 24 octobre 2026

Piste GLSL implémentée derrière `-Dvoxel.skipUnlitShadows=true` : dans le chemin d'éclairage normal, ne pas appeler `calculateShadow` si `max(dot(N,L),0)` vaut zéro. Le facteur d'ombre n'intervient alors plus dans la couleur directe. Aucun changement de génération des shadow maps, de filtre, de biais, d'AO, de fog ou des modes debug.

Build et run GL réussis ; **quatre comparaisons exactes couleur RGBA8 + profondeur**, mêmes frames et mêmes ressources, sans différence. Caméra et soleil mobiles. Validation isolée des benchmarks. Voir le [rapport détaillé, commandes et logs](Rapport_ombres_sans_contribution_2026-10-24.md).

ABBA non instrumenté avec indexation et vertices compacts actifs : **64,652 → 64,862 FPS (+0,32 %)**, soit 15,467 → 15,417 ms/frame. L'écart est inférieur à la variation entre témoins : **aucun gain robuste démontré**. Profilage séparé : passe voxel 8,836 → 8,866 ms, shadow 6,289 → 6,339 ms, sans baisse mesurée.

**Option OFF par défaut.** L'expérience est disponible sans recompilation, mais ne justifie pas une activation générale. Ces mesures ne prouvent ni un gain à d'autres angles solaires, ni une limitation micro-architecturale particulière. La prochaine vérification de cette piste serait un test à d'autres heures/vues ; ne pas ajouter simultanément une modification du PCF qui empêcherait d'attribuer le résultat.

## 22. Fusion du mesh d’ombres : activée par défaut

Qualification finale : **65,37 → 79,34 FPS (+21,36 %)** sur le parcours ABBA ; temps GPU shadow **6,41 → 3,43 ms** dans les profils séparés. Environ **74 Mo supplémentaires** pour les meshes installés. Contrôle OFF/OFF exact, différences finales OFF/ON rares et contrôle manuel sans régression observée. Tests de remeshing et lifetime ON/OFF réussis après correction d’un faux positif lié à la réutilisation des noms GL.

Le réglage effectif est centralisé dans `RenderConfig.GREEDY_SHADOWS`. Fallback : `-Dvoxel.greedyShadows=false`. Aucun changement des biais, cascades ou cadence solaire. Voir [résultats, limites et décision finale](Qualification_fusion_ombres_2026-10-24.md).
