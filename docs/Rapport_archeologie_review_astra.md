# Review Astra du rapport d’archéologie

Contre-review du [rapport original](Rapport_archeologie.md), lu intégralement et conservé intact. Analyse du dépôt au commit `0706298d41e930bc228a4aea8bb60acd7ecec535`, effectuée le 6 septembre 2026.

Périmètre : vérification statique des chemins Java/GLSL, de l’historique Git pertinent, des documents de benchmarks et de plusieurs CSV, puis confrontation aux spécifications officielles. Aucun lancement du moteur, benchmark GPU nouveau, changement de source ou refactor n’a été effectué. Le seul fichier créé est ce document. Les références de lignes correspondent à cet état du dépôt ; les chemins `src/...` sont relatifs à sa racine.

Convention : **FACTUEL** décrit le code ou une observation disponible ; **INTENTION PROBABLE** est une interprétation ; **RECOMMANDATION** désigne une investigation ou une décision ultérieure, à réaliser par le développeur. Une preuve statique d’un défaut n’est pas une preuve de sa responsabilité dans un ralentissement historique.

## 1. Verdict global

**Fiable avec réserves importantes. Confiance globale : Haute confiance pour le modèle statique corrigé ci-dessous ; Confiance moyenne pour son raccordement aux anciens benchmarks.**

1. Le rapport constitue une bonne carte de navigation : chemin applicatif, passes principales, ressources et nombreux appels OpenGL sont correctement retrouvés.
2. Son diagnostic principal est confirmé dans le code actuel : un lookup depth non-shadow peut être exécuté avec le compare mode actif. C’est un défaut de validité du résultat, pas une incompatibilité intrinsèque entre le M2 et les texture arrays.
3. Il faut toutefois distinguer deux bindings du même objet de deux samplers effectivement actifs. Au stage 5, le sampler shadow peut être éliminé ; la mutation Java du compare mode reste exécutée et suffit au problème.
4. Ce défaut ne prouve ni la cause des 19–35 ms historiques, ni celle des anciens flashes. L’association entre chaque CSV et les sources réellement exécutées n’est pas suffisamment établie.
5. Les timer queries recommandées ne sont pas garanties par OpenGL 3.2 Core. Leur disponibilité doit précéder cette recommandation opérationnelle.
6. La phrase « le leapfrogging ne contribue pas à l’image » est trop large et doit être corrigée : le crossfade est neutralisé, mais B devient A lors du swap et devient alors visible.
7. Les pixels de C ne sont pas copiés dans B : B est redessiné. En revanche, les données CPU de C sont réutilisées. « C inutile » serait une conclusion dangereuse.
8. Les CSV conservent une valeur descriptive. Ils ne constituent pas une mesure isolée du coût d’un fetch, du format DEPTH32F ou d’un mécanisme interne du GPU.
9. Le rapport sous-estime le défaut du texte : le nombre de sommets demandé peut dépasser la taille effectivement uploadée au VBO, malgré la capacité initiale du buffer Java.
10. Le diagnostic UI est incomplet : `TextRenderer` réactive le depth test au milieu des menus, même quand `MenuState` l’a désactivé à l’entrée.
11. Les responsabilités de destruction des programmes sont réellement contradictoires. Mais toutes les répétitions de `glDelete*` n’ont pas la même conséquence ; elles ne constituent pas automatiquement un double-free provoquant un crash.
12. Les fuites de meshes lors du remeshing et l’absence de nettoyage final des chunks sont des problèmes concrets. Les wrappers dormants ne justifient pas, eux, une réorganisation immédiate du renderer actif.
13. Un Mac Intel serait une comparaison secondaire de portabilité, pas une meilleure référence de validité. Il faut d’abord obtenir un cas conforme et reproductible sur la machine actuelle.
14. Le meilleur premier refactor pédagogique est limité à l’ownership des programmes voxel/depth et à la chaîne de cleanup associée, après stabilisation du diagnostic. Le développeur doit en choisir et implémenter lui-même le contrat.

## 2. Tableau de validation des conclusions majeures

| Conclusion du rapport | Verdict | Confiance | Commentaire |
|---|---|---|---|
| Application → états → Game → Shadow/Sky/Sun/Voxel décrit le chemin actif | CONFIRMÉ | Haute confiance | Appels réels dans `Application::loop`, `PlayingState::render`, `Game::render`. |
| Le contexte demandé est OpenGL 3.2 Core et les shaders sont GLSL 1.50 | CONFIRMÉ AVEC NUANCE | Haute confiance | Contrat demandé, pas identité complète du contexte effectivement obtenu. |
| Aucune dépendance active à OpenGL 4.x n’est démontrée pour les ombres | CONFIRMÉ | Haute confiance | Le rapport a raison de rejeter l’exigence 4.3 de documents plus anciens. |
| Le lookup depth manuel avec compare mode actif est indéfini | CONFIRMÉ | Haute confiance | Défaut courant précisément localisé en section 4. |
| Le même atlas est lié au sampler manuel sur 1 et au sampler shadow sur 3 | CONFIRMÉ AVEC NUANCE | Haute confiance | Deux unités sont bindées ; le chemin shadow n’est pas exécuté au stage 5. Ne pas en déduire deux samplers actifs. |
| Ce problème est le meilleur candidat pour expliquer l’apparente incompatibilité M2 | INSUFFISAMMENT PROUVÉ | Haute confiance | Priorité de validité justifiée ; classement causal parmi les sources de lenteur encore non mesuré. |
| Tous les anciens stages depth manuels 5–11 étaient affectés | INSUFFISAMMENT PROUVÉ | Haute confiance | Vrai pour des variantes du Java actuel, pas démontré pour tous les exécutables historiques. |
| Le leapfrog ne contribue pas à l’image finale | À CORRIGER | Haute confiance | Pas de crossfade actuel, mais promotion temporelle B → A active. |
| A, B et C sont générés ; C → B n’est pas une copie GPU | CONFIRMÉ AVEC NUANCE | Haute confiance | Réemploi de données CPU et nouveau rendu dans B ; distinguer pixels et métadonnées. |
| Le stage 5 est un chemin A single-sample sans PCF ni blend A/B | CONFIRMÉ AVEC NUANCE | Haute confiance | Au plus un lookup d’ombre par invocation concernée, avec sorties anticipées et filtrage LINEAR. |
| `glFinish_ms` n’est pas un temps GPU par passe | CONFIRMÉ | Haute confiance | Temps CPU de l’attente résiduelle à cet emplacement. |
| `glFinish` perturbe fortement les performances mesurées | CONFIRMÉ AVEC NUANCE | Haute confiance | Sérialisation inter-frame établie ; amplitude du surcoût non mesurée. |
| Les timer queries peuvent remplacer directement cet instrument en 3.2 | À CORRIGER | Haute confiance | OpenGL 3.3 ou extension adéquate à vérifier. |
| Les temps proches entre un et quatre taps identifient une latence fixe | INSUFFISAMMENT PROUVÉ | Haute confiance | Observation descriptive, pas identification micro-architecturale. |
| Le VBO texte reste suffisamment grand pour le draw | RÉFUTÉ | Haute confiance | `flip()` puis upload de `remaining()`, pas de la capacité maximale. |
| Le menu garantit le depth test désactivé à l’UI | À CORRIGER | Haute confiance | Garantie rompue à l’intérieur de `TextRenderer::drawText`. |
| Programmes voxel/depth détruits plusieurs fois | CONFIRMÉ AVEC NUANCE | Haute confiance | Deux/trois tentatives ; distinguer erreurs de programme et suppressions de textures/FBO tolérées. |
| Remesh et cleanup final perdent des ressources de chunks | CONFIRMÉ | Haute confiance | Chemin asynchrone actif ; le cleanup correct du chemin legacy ne le protège pas. |
| Un upload peut arriver après l’unload | CONFIRMÉ AVEC NUANCE | Confiance moyenne | Ordonnancement possible sans validation d’appartenance ; occurrence réelle non enregistrée. |
| Les wrappers `Texture`/`Framebuffer` sont dormants avec des contrats ambigus | CONFIRMÉ AVEC NUANCE | Haute confiance | Lecture correcte ; aucune preuve de double ownership actif des attachments externes. |
| Le calcul des indices de snap mélange des espaces/unités | CONFIRMÉ AVEC NUANCE | Haute confiance | Incohérence algébrique vérifiable ; impact exact sur les flashes non démontré. |
| Tester Intel doit venir après la validité et la reproductibilité | CONFIRMÉ | Haute confiance | Comparaison pertinente ensuite, en identifiant le GPU réel et la résolution. |

Le rapport exprime déjà plusieurs réserves causales pertinentes ; elles doivent être appliquées également à son executive summary et à son ordre d’investigation. Cette review ne lui attribue pas une recommandation de grand refactor qu’il ne formule pas.

## 3. Findings critiques

### Finding 1 — Un défaut actuel certain n’identifie pas la cause des anciens ralentissements

**Verdict : CONFIRMÉ AVEC NUANCE.**

**Confiance : Haute confiance sur le défaut ; Confiance moyenne sur sa présence dans chaque ancien run.**

**Rapport original :** sections 9 et 10, diagnostic du compare mode et affirmation que les stages manuels 5–11 sont affectés.

**Vérification :** `src/main/java/engine/render/renderer/VoxelRenderer.java` — `render()`, lignes 203–233, modifie le même atlas avant les draws puis rétablit `NONE` après. Mais `logs/perf_atlas_report.md`, lignes 41–46 et 66, décrit plusieurs configurations historiques différentes de l’uniform leapfrog. L’historique montre l’ajout du chemin hardware compare dans le commit agrégé `0706298` du 20 avril ; son parent `7aaa531` ne contient pas cette mutation. Cela ne date pas avec certitude les modifications locales effectuées avant les runs de mars. `docs/render_pipeline_reference.md`, vers la ligne 453, mentionne aussi une restauration des sources après les essais.

**Pourquoi cela compte :** on ne peut pas rétroactivement appliquer l’état courant à chaque CSV ni annoncer qu’une seule correction supprimera tous les ralentissements. Les anciens flashes et les micro-tests ne sont pas nécessairement le même problème.

**Action recommandée — RECOMMANDATION :** associer toute nouvelle mesure à un hash des sources Java et GLSL effectivement chargées, aux paramètres réels et à une image de référence. Conserver les anciens CSV comme observations historiques, pas comme expérience causale reconstruite avec certitude.

### Finding 2 — Le leapfrog est partiellement actif, malgré l’uniform à zéro

**Verdict : À CORRIGER.**

**Confiance : Haute confiance.**

**Rapport original :** executive summary, point 6 : le système leapfrog ne contribue actuellement pas à l’image ; description séparée du swap et de C.

**Vérification :** `VoxelRenderer::render`, ligne 176, neutralise le blend shader. Cependant `src/main/java/engine/render/renderer/ShadowRenderer.java` — `renderInternal()`, lignes 500–545, échange les offsets, affecte `dataA = dataB`, puis `dataB = dataC` et redessine B. `getAtlasOffsetA()` et `getCascadeData()` alimentent ensuite le shader. La carte anciennement B devient réellement la carte A lue à la frame suivante.

**Pourquoi cela compte :** supprimer B ou désactiver arbitrairement son entretien modifierait le rendu courant. Le CPU leapfrog peut modifier les ombres sans aucun mélange simultané A/B. Le passage brusque entre cartes reste une hypothèse d’artefact à vérifier, non une preuve des flashes historiques.

**Action recommandée — RECOMMANDATION :** tracer les identités des slots physiques, leurs matrices et leurs dates de rendu autour d’un swap. Nommer séparément « ordonnancement/promotion des cartes » et « crossfade GLSL ».

### Finding 3 — Le protocole recommandé suppose une fonctionnalité non garantie en 3.2

**Verdict : À CORRIGER.**

**Confiance : Haute confiance.**

**Rapport original :** executive summary et étape 3 : mesurer les passes avec des timer queries, dans une démarche de conformité OpenGL 3.2.

**Vérification :** l’existence d’objets query en 3.2 ne garantit pas les cibles temporelles. Le chemin moderne relève d’OpenGL 3.3 ou de `ARB_timer_query` ; `EXT_timer_query` est une autre possibilité, avec un contrat distinct. Sources : [OpenGL 3.3 Core, §5.1 et annexe J](https://registry.khronos.org/OpenGL/specs/gl/glspec33.core.pdf), [ARB_timer_query](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_timer_query.txt), [EXT_timer_query](https://registry.khronos.org/OpenGL/extensions/EXT/EXT_timer_query.txt).

**Pourquoi cela compte :** une investigation sur un contrat minimal ne doit pas commencer par introduire silencieusement un contrat supérieur. Un loader LWJGL qui expose une méthode ne prouve pas que le contexte la supporte.

**Action recommandée — RECOMMANDATION :** inventorier les capabilities réelles, puis choisir un instrument supporté et lire les résultats de façon différée. À défaut, conserver une mesure CPU explicitement qualifiée, sans l’appeler temps GPU isolé.

### Finding 4 — Le texte peut demander des sommets hors du stockage GPU uploadé

**Verdict : RÉFUTÉ pour l’assurance d’absence de lecture hors allocation.**

**Confiance : Haute confiance.**

**Rapport original :** section 6.4 : caractères omis mais buffer dimensionné au maximum, donc pas de lecture hors allocation GPU.

**Vérification :** `src/main/java/engine/render/TextRenderer.java` — `drawText()`, lignes 109–155 : allocation CPU pour toute la chaîne, omission des caractères hors plage, `verts.flip()`, upload, puis draw de `6 * text.length()`. Dans LWJGL 3.3.1, la surcharge `GL15C.glBufferData(..., FloatBuffer, ...)` transmet `data.remaining() * 4` octets. Vérification dans le source JAR local de `org.lwjgl:lwjgl-opengl:3.3.1`, membre `org/lwjgl/opengl/GL15C.java`, lignes 394–395 ; [source de la même version](https://github.com/LWJGL/lwjgl3/blob/3.3.1/modules/lwjgl/opengl/src/generated/java/org/lwjgl/opengl/GL15C.java#L394).

Pour `n` caractères Java et `m` glyphes acceptés, le VBO reçoit `96m` octets, mais le draw demande des attributs correspondant à `96n` octets. Avec « Aé », `m = 1` et `n = 2`. La capacité CPU initiale ne protège donc pas le GPU. Le régime normatif pertinent est celui des accès hors buffer, pas seulement celui de sommets non renseignés : [OpenGL 3.2 Core, §2.9.4](https://registry.khronos.org/OpenGL/specs/gl/glspec32.core.pdf).

**Pourquoi cela compte :** c’est un défaut local conditionnel réel, à traiter avant de se fier à des observations UI contenant du texte non supporté. Aucun crash concret n’est démontré par cette review.

**Action recommandée — RECOMMANDATION :** vérifier la relation entre glyphes acceptés, octets uploadés et nombre de sommets pour ASCII, accents, retour à la ligne et chaîne entièrement non supportée. Ce problème n’exige pas de nouveau système de texte.

### Finding 5 — Le contrat de depth de l’UI est rompu à l’intérieur du menu

**Verdict : CONFIRMÉ MAIS INCOMPLET, soit CONFIRMÉ AVEC NUANCE.**

**Confiance : Haute confiance sur la chaîne d’état ; Confiance moyenne sur chaque artefact visuel sans capture.**

**Rapport original :** section 7 : `UIRenderer` dépend du caller pour désactiver le depth test ; cette condition serait vraie dans `MenuState`, fausse dans `LoadingState`.

**Vérification :** `src/main/java/engine/core/states/MenuState.java` — `render()`, lignes 45–57, désactive bien le test. Mais `TextRenderer::drawText`, lignes 150–161, le réactive inconditionnellement. `src/main/java/engine/ui/screens/MainMenuScreen.java` — `render()`, lignes 117–128, dessine le titre avant les boutons. Ceux-ci sont donc rendus après cette réactivation. `UIRenderer` ne reconfigure pas le depth ; `ui_vertex.glsl` utilise le même plan `z = 0`. Avec depth write et `LESS`, des primitives coplanaires successives peuvent se masquer. Dans `LoadingScreen::render`, lignes 111–134, le fond peut déjà remplir ce même plan avant la barre de progression.

**Pourquoi cela compte :** désactiver uniquement le depth à l’entrée de `LoadingState` ne suffit pas à définir un contrat UI stable. Le composant texte modifie la précondition de ses frères.

**Action recommandée — RECOMMANDATION :** établir sur papier l’état avant/après fond, titre, boutons et barre ; décider ensuite du contrat local de la passe UI. Pas besoin d’un gestionnaire générique de pipeline.

### Finding 6 — Les suppressions répétées n’ont pas toutes la même gravité

**Verdict : CONFIRMÉ AVEC NUANCE.**

**Confiance : Haute confiance.**

**Rapport original :** section 8 : ownership multiple, jusqu’à trois tentatives de destruction du programme depth, cleanup non idempotent des ombres.

**Vérification :** `PlayingState::exit`, lignes 241–265 → `VoxelRenderer::cleanup`, lignes 265–267 → `ShadowRenderer::cleanup`, lignes 755–760 → `Shader::cleanup`, lignes 86–88. Le programme voxel reçoit deux tentatives ; depth, trois. `Shader::cleanup` délie d’abord le programme : dans ce contexte unique, sa première suppression normale n’est pas simplement différée parce qu’il serait encore courant. Les tentatives suivantes sur son ancien nom sont erronées. À l’inverse, la suppression de noms de textures/FBO devenus inutilisés est ignorée ; sans allocation intercalée dans cette chaîne, ce n’est pas une double libération de mémoire comparable au C. Référence normative : [OpenGL 3.2 Core, §2.11.2, §3.8.14 et §4.4.1](https://registry.khronos.org/OpenGL/specs/gl/glspec32.core.pdf).

**Pourquoi cela compte :** la frontière d’ownership doit être corrigée, mais le risque ne doit pas être vendu comme un crash certain. La fuite répétée de meshes pendant le jeu est plus directement cumulative.

**Action recommandée — RECOMMANDATION :** écrire une table des propriétaires et emprunteurs, puis compter les destructions par ressource. Ne pas se contenter de rendre les méthodes silencieusement idempotentes : cela masquerait la responsabilité contradictoire.

### Finding 7 — Désactiver la shadow pass ne conserve pas automatiquement un test valide

**Verdict : CONFIRMÉ AVEC NUANCE pour la matrice de tests proposée.**

**Confiance : Haute confiance.**

**Rapport original :** étape 4 : comparer profondeur pass désactivée/activée.

**Vérification :** `ShadowRenderer::render`, vers la ligne 343, retourne immédiatement lorsque `SHADOW_DEPTH_PASS_ENABLED` est faux, avant le calcul des cascades. Au démarrage, `dataA` est nul ; `VoxelRenderer::render`, vers la ligne 165, n’envoie alors pas ces données. Le constructeur initialise certaines textures, mais pas les matrices de cascades normalement produites par `renderInternal`. Ce toggle ne retire donc pas uniquement des draw calls.

**Pourquoi cela compte :** une variante peut ne plus exécuter les mêmes branches ni lire les mêmes coordonnées. Son gain ne mesurerait pas le seul coût des rendus depth.

**Action recommandée — RECOMMANDATION :** produire d’abord une texture et des données CSM valides, puis conserver ces mêmes inputs pendant la comparaison avec/sans nouveaux dessins depth. Contrôler l’image et les inputs avant les temps.

## 4. Texture / sampler compare mode review

### Chaîne vérifiée dans le draw courant

**FACTUEL.** `Shader` charge les ressources du classpath sans injection de préprocesseur : `src/main/java/engine/render/Shader.java` — constructeur et `readFile()`, lignes 19–63. Le fragment source du dépôt fixe `PERF_BISECT_STAGE = 5`, ligne 3. Aucune activation runtime du stage 14/15 n’est identifiée dans ce chargement. Cela décrit les ressources sources actuelles, pas une garantie sur un ancien JAR déjà construit.

Dans `VoxelRenderer::render()` :

1. L’atlas voxel est associé à l’unité 0, lignes 194–197.
2. `setTextureArray("shadowAtlas", ..., 1)` configure l’atlas depth pour le sampler manuel, ligne 203. Le helper ne bind effectivement que si la location existe : `Shader::setTextureArray`, lignes 193–199.
3. Le même identifiant d’atlas est bindé explicitement sur l’unité 3, lignes 217–218.
4. Les lignes 219–220 activent le compare mode et `LEQUAL` sur cet objet, inconditionnellement, indépendamment de l’activité du sampler GLSL correspondant.
5. L’upload des meshes qui suit ne remplace pas cette configuration texture. Les chunks sont dessinés ligne 228.
6. Le compare mode repasse à `NONE` ligne 233, donc trop tard pour les dessins précédents.

`voxel_fragment_shader.glsl` — `main()`, vers la ligne 782, appelle le calcul d’ombre quand `shadowsEnabled == 1`. Le réglage courant l’autorise. `calculateShadow()`, lignes 453–465, choisit A puis appelle `sampleShadowSingleFromArray()`. Cette fonction retourne sans lecture hors du domaine, sinon exécute le lookup non-shadow ligne 345.

### Réponses aux huit questions

**1. Le même objet est-il réellement utilisé simultanément par les deux samplers ?**

Deux unités reçoivent bien le même objet pendant les draws. En revanche, deux lectures effectives par deux samplers différents ne sont pas établies au stage 5 : le chemin hardware est exclu et son uniform peut être inactif après optimisation. L’énoncé correct est donc « même objet exposé sur deux unités, dont les paramètres sont modifiés avant une lecture manuelle ». Une réflexion du programme lié permettrait de confirmer les uniforms actifs ; le bug ne dépend pas de son résultat.

**2. Le compare mode est-il une propriété de l’objet texture ici ?**

Oui. Aucun sampler object distinct n’est créé/bindé dans le chemin vérifié. Changer d’unité ne crée pas un second jeu de paramètres pour l’objet. Le mécanisme d’override par sampler objects n’est pas employé et ne doit pas être supposé présent.

**3. Le lookup manuel survient-il effectivement pendant ce mode ?**

Le chemin de code le permet directement et normalement au stage 5, ombres activées, pour les fragments passant les tests de domaine. Le restore arrive après les draws. La quantité de fragments effectivement concernés dans une frame donnée n’a pas été mesurée.

**4. Quelle qualification normative ?**

Le texte normatif qualifie le **résultat du lookup d’undefined** dans cette combinaison, et non d’implementation-defined ou d’unspecified. Références : [OpenGL 3.2 Core, §3.9.2, pages imprimées 179–180](https://registry.khronos.org/OpenGL/specs/gl/glspec32.core.pdf) ; [GLSL 1.50, §8.7, Texture Lookup Functions](https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.1.50.pdf). Interprétation pour ce moteur : le résultat d’ombre concerné n’a pas la garantie attendue ; ce n’est ni une preuve d’échec de compilation ni une autorisation d’inférer n’importe quel comportement du programme Java entier.

**5. Une erreur OpenGL doit-elle nécessairement être générée ?**

Non pour ce seul cas. Ne pas le confondre avec la règle distincte interdisant à des samplers actifs de types différents de référencer la **même unité**, qui concerne ici une autre configuration : unités 1 et 3 sont différentes. Voir [OpenGL 3.2 Core, §2.11.7](https://registry.khronos.org/OpenGL/specs/gl/glspec32.core.pdf). L’absence d’erreur GL ne valide donc pas cette expérience.

**6. Cela invalide-t-il toutes les conclusions de performance ?**

Non. Cela empêche d’interpréter une mesure affectée comme le coût d’un chemin manuel conforme et de la comparer sans réserve à un chemin shadow conforme. Cela ne fait pas disparaître un temps effectivement enregistré, et ne prouve pas que tous les anciens runs avaient cette configuration.

**7. Quelles conclusions restent raisonnables ?**

Les exécutables testés ont présenté les distributions temporelles enregistrées ; certaines variantes sont nettement plus lentes que d’autres. Ces écarts justifient une expérience contrôlée. Ils n’identifient pas, seuls, le format depth, le compare mode, Metal, un cache ou le nombre d’unités comme cause.

**8. Plus petit protocole conforme ? — RECOMMANDATION.**

Utiliser une seule texture array depth avec une couche et des valeurs connues, un quad couvrant une aire fixe et deux variantes de programme exécutées séparément : lecture manuelle avec `NONE`, puis lecture shadow avec compare actif et la même relation de comparaison. Une seule unité utile par variante suffit. Aucun rendu CSM ni swap A/B/C n’est nécessaire à ce test.

Invariants : mêmes dimensions, format, données initialisées, coordonnées dans le domaine, profondeur de référence, nombre de lookups et framebuffer ; résultat effectivement consommé dans la couleur ; pas de lecture de la texture pendant son écriture ; configuration effective vérifiée juste avant le draw. Commencer en `NEAREST` et avec des profondeurs dont le résultat attendu est évident.

Ce dernier point évite une autre confusion : lire une profondeur filtrée en LINEAR puis faire une comparaison n’est pas, en général, identique à filtrer des résultats de comparaisons shadow. Les paramètres LINEAR actuels ne constituent pas une erreur à eux seuls, mais ils empêchent d’exiger automatiquement une équivalence pixel à pixel entre les deux algorithmes. Voir [GLSL 1.50, §8.7](https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.1.50.pdf).

Valider d’abord les pixels, ensuite chronométrer avec un instrument supporté. Revenir enfin au stage 5 du moteur en ne changeant que la configuration de comparaison. Un résultat conforme et rapide conforterait l’hypothèse causale sur ce cas actuel ; un résultat conforme mais encore lent la limiterait. Le cas non conforme peut rester un témoin descriptif, jamais une référence de coût normal.

**Verdict final sur le diagnostic principal :** le défaut texture/sampler est confirmé dans le chemin actuel, mais sa responsabilité dans les ralentissements et artefacts historiques n’est pas démontrée.

## 5. OpenGL 3.2 / GLSL 1.50 compatibility review

### Fonctionnalités confirmées

`src/main/java/engine/core/Application.java` — `init()`, lignes 95–99 et 122–138, demande 3.2 Core forward-compatible via GLFW puis crée les capabilities LWJGL. `GameConfig` fournit les versions 3 et 2. Les sources GLSL utilisent `#version 150`.

| Fonctionnalité | Contrat pertinent | Conclusion pour le dépôt |
|---|---|---|
| Texture arrays 2D, allocation par `glTexImage3D` | OpenGL 3.2 Core | Garantie ; pas de besoin d’OpenGL 4.3. |
| FBO et `glFramebufferTextureLayer` | OpenGL 3.2 Core | Garantis ; attacher une couche ne nécessite pas de geometry shader. |
| Depth textures, `DEPTH_COMPONENT32F`, profondeur attachée au FBO | OpenGL 3.2 Core | Fonctionnalités disponibles ; contrôler aussi allocation et complétude réelles. |
| `sampler2DArray` et `sampler2DArrayShadow` | GLSL 1.50 | Types et fonctions de lookup présents. |
| VAO, VBO, attributs génériques | OpenGL 3.2 Core | Le layout voxel de neuf floats est cohérent entre upload et shader. |
| Binding d’attributs avant link | OpenGL 3.2 Core | Méthode valide employée pour voxel/depth ; noms UI/Text/Cube à contrôler. |
| `GL_FRAMEBUFFER_SRGB` | OpenGL 3.2 Core | Fonctionnalité disponible ; encodage réel du framebuffer distinct de son activation. |
| UBO | OpenGL 3.2 Core | Disponible, mais aucun besoin établi d’en introduire dans ce renderer. |
| SSBO / compute shaders | OpenGL 4.3 ou extensions appropriées | Pas de consommateur actif identifié ; ne sont pas un prérequis du moteur. |
| Timer queries | OpenGL 3.3 ou extension | Pas garanties par le contrat minimal actuel. |
| Sampler objects | OpenGL 3.3 ou `ARB_sampler_objects` | Pas employés ; ne pas les prescrire comme solution native 3.2 sans capability. |
| `layout(location=...)` pour attributs | GLSL 3.30 ou extension correspondante | Ne pas remplacer mécaniquement les bindings par cette syntaxe dans des shaders 1.50. |

Sources de version : [OpenGL 3.2 Core](https://registry.khronos.org/OpenGL/specs/gl/glspec32.core.pdf), [GLSL 1.50](https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.1.50.pdf), [OpenGL 3.3 Core, changements de version en annexe J](https://registry.khronos.org/OpenGL/specs/gl/glspec33.core.pdf), [ARB_sampler_objects](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_sampler_objects.txt). Le tableau classe des fonctionnalités ; il ne certifie pas à lui seul chaque configuration d’objets du moteur.

### Hypothèses à vérifier runtime

- Version GL et GLSL réelles, vendor, renderer, profil et flags. La requête GLFW définit un minimum et non la preuve que le contexte obtenu est exactement 3.2 : [documentation GLFW](https://www.glfw.org/docs/latest/window.html).
- Extensions/capabilities de timing et précision utile des compteurs ; notamment ne pas confondre présence de query objects et support de mesures temporelles.
- Encodage du framebuffer par défaut et taille en pixels. `Application::init`, lignes 140–159, journalise bien dimensions et échelle, mais pas l’identité GL complète. Aucun hint sRGB ni interrogation de cet encodage n’est visible.
- Uniforms et attributs actifs après link, formats réels après éventuel fallback, statut FBO et paramètres texture au draw. La configuration Java déclarée n’est pas un relevé de l’état GPU.

### Erreurs ou nuances du rapport

La réserve sur les timer queries manque et doit être ajoutée. En revanche, aucune exigence active d’OpenGL supérieur à 3.2 n’a été établie pour le CSM lui-même. Le défaut du compare mode relève d’une combinaison d’état et d’interface shader, pas de l’absence de `sampler2DArrayShadow` en GLSL 1.50.

Activer sRGB sans framebuffer sRGB ne constitue pas automatiquement un appel invalide ; c’est d’abord un contrat colorimétrique non vérifié. Les attributs non fixés ne constituent pas non plus une erreur de compilation obligatoire : le problème est de supposer ensuite leur location numérique.

## 6. Performance methodology review

### Ce que les benchmarks prouvent

**FACTUEL — Haute confiance.** Les ordres de grandeur du tableau original sont reproductibles dans les CSV correspondants. Un recalcul en lecture seule, avec exclusion explicite de `frameIndex <= 10`, retrouve notamment :

| Série / stage | Médiane `glFinish_ms` approximative |
|---|---:|
| [3 mars, stage 4, `012256`](../logs/perf_test_World_10_2026-03-03_012256.csv) | 6,17 ms |
| [3 mars, stage 5, `012320`](../logs/perf_test_World_10_2026-03-03_012320.csv) | 19,55 ms |
| [3 mars, stage 7, `012507`](../logs/perf_test_World_10_2026-03-03_012507.csv) | 34,86 ms |
| [3 mars, stage 10, `015310`](../logs/perf_test_World_10_2026-03-03_015310.csv) | 19,48 ms |
| [3 mars, stage 12, `015408`](../logs/perf_test_World_10_2026-03-03_015408.csv) | 6,18 ms |
| [3 mars, stage 13, `015436`](../logs/perf_test_World_10_2026-03-03_015436.csv) | 19,11 ms |
| [5 mars, stage 14, `230622`](../logs/perf_test_World_10_2026-03-05_230622.csv) | 19,44 ms |
| [5 mars, stage 15, `230724`](../logs/perf_test_World_10_2026-03-05_230724.csv) | 19,57 ms |

Références : CSV `logs/perf_test_*` de ces horodatages, [perf_bisect_report](../logs/perf_bisect_report.md), [perf_microtest_report](../logs/perf_microtest_report.md), [perf_atlas_report](../logs/perf_atlas_report.md). Les valeurs sont descriptives et dépendent de la fenêtre exclue ; elles ne représentent pas des mesures GPU isolées. Certains autres CSV anciens présentent des décimales avec virgules perturbant la structure des colonnes : ils ne doivent pas être agrégés sans validation du parsing.

`Application::loop`, lignes 209–291, mesure séparément update, render, attente dans `glFinish` et swap, avec stockage dans `RenderContext` en fin de frame. Un `glFinish()` final attend les commandes antérieures ; le timer qui l’entoure observe uniquement l’attente restante à cet instant. Il n’inclut pas le travail GPU déjà terminé pendant l’update/soumission CPU. Source normative pour la synchronisation : [OpenGL 3.2 Core, §5.1](https://registry.khronos.org/OpenGL/specs/gl/glspec32.core.pdf).

Il existe aussi un `glFinish()` optionnel immédiatement après les ombres : `Game::render`, lignes 267–274, sous `GameConfig.SHADOW_SPLIT_TIMING`, actuellement faux. Lorsqu’il est activé, `shadowFinish_ms` mesure cette attente intermédiaire, pas toute la durée GPU de la passe. Il déplace une partie de l’attente hors du `glFinish` final : ces deux configurations ne se comparent donc pas avec la seule colonne finale.

### Ce qu’ils suggèrent seulement

Les séries retenues suggèrent une différence de coût importante entre certaines variantes de shading, et une différence entre variantes à coordonnées constantes et dynamiques. Elles justifient de cibler l’échantillonnage dans l’investigation. Elles ne démontrent pas que celui-ci explique à lui seul la différence.

La proximité de plusieurs résultats un/quatre taps mérite d’être reproduite. Elle ne prouve ni « taps gratuits », ni coût fixe de transition Metal, ni cache de texture particulier.

### Ce qu’ils ne permettent pas de conclure

Le tableau suivant distingue les observations du rapport et les interprétations qu’il discute, notamment celles des documents perf antérieurs. Les lignes réfutées ne sont donc pas toutes des affirmations que le rapport d’archéologie lui-même défend.

| Conclusion performance examinée | Évaluation | Motif vérifié |
|---|---|---|
| Certaines variantes historiques sont beaucoup plus lentes | Supportée | Distribution des temps enregistrés. |
| `glFinish_ms` mesure exactement le temps GPU de la frame | Réfutée | Attente résiduelle après soumission, toutes passes antérieures concernées. |
| `render_ms` est une mesure GPU | Réfutée | Intervalle CPU autour de `render`, susceptible aussi d’inclure blocages driver et uploads. |
| Le `glFinish` empêche le chevauchement normal entre frames | Supportée | La frame suivante n’est soumise qu’après achèvement ; cela n’interdit pas tout chevauchement CPU/GPU à l’intérieur d’une frame. |
| Son surcoût explique une fraction importante précise des lenteurs | Non démontrée | Pas de comparaison équivalente sans cette synchronisation. |
| Stage 4 → 5 isole un seul fetch ajouté | Réfutée | `main()` stage 4 et chemin général diffèrent aussi pour AO/ambient ; calcul de cascade, biais et interfaces actives changent. |
| Tous les stages constituent une échelle cumulative | Réfutée | Stage 3 ajoute AO, stage 4 suit un chemin fog distinct ; stage 0 commenté « normal » produit du magenta. |
| R32F constant/dynamique isole uniquement le coût d’un index de couche | Réfutée | Coordonnées et couche changent ; contenu du test couleur non initialisé ; branches et résultats différents. |
| Les temps d’un/quatre taps sont proches dans certaines séries | Supportée | Valeurs observées ; nombre de fetchs exécutés réellement par fragment non instrumenté. |
| Un tap correspond à une seule lecture physique de texel | Réfutée | Le code utilise LINEAR ; « tap » compte ici les appels GLSL, pas les transactions GPU. |
| Les tests 12/13 ne valident pas le résultat graphique | Supportée | `colorTestArray` alloué sans données, aucun remplissage identifié. |
| DEPTH32F cause à lui seul le surcoût observé | Non démontrée | Pas de paire conforme contrôlant contenu, état, shader, coordonnées et sortie. |
| Les chiffres démontrent cache L2, TMU, warp broadcast ou mécanisme Metal | Non démontrée | Aucun compteur ni capture établissant ces mécanismes. |
| Le framebuffer de tous les benchmarks est précisément 3024×1832 | Partiellement supportée | Dimension présente dans `skyflash_trace_20260220_191332.log`, pas dans chaque CSV de mars. |
| Le nombre de draws et de sommets est comparable entre runs | Non démontrée | Colonnes concernées à zéro ; meshing/streaming et ordonnancement des ombres non figés. |
| Le coût shadow est séparé par la colonne `shadowPassEnabled` | Réfutée | Valeur `true` codée en dur ; ne décrit pas le chemin effectif ni son coût. |
| Le problème compare mode invalide toute observation historique | Réfutée | Limite l’interprétation des cas affectés ; présence dans tous les anciens exécutables non prouvée. |

Références directes : `voxel_fragment_shader.glsl` — préprocesseur, `calculateShadow()` et `main()`, lignes 3–35, 447–600 et 735–800 ; `ShadowRenderer` — allocation de `colorTestArray`, vers les lignes 202–280 ; `PlayingState::update`, lignes 184–208.

Trois précisions supplémentaires changent le protocole :

- **Décalage des lignes CSV.** `PlayingState::update()` journalise avant le render courant ; les timings lus dans `RenderContext` viennent de la frame précédente, alors que position et état ont déjà été mis à jour. Cela compromet l’attribution fine d’un pic à un swap ou à une pose, sans invalider automatiquement les médianes d’un run stable.
- **Trajectoire non identique au bit près.** `src/main/java/engine/test/TestHarness.java` — `update()`, vers la ligne 81, intègre `dt` pour ses phases ; `game.update()` s’exécute ensuite. Un scénario temporel n’est pas un replay de matrices strictement identiques, particulièrement avec streaming asynchrone.
- **Stages compilés, pas simple interrupteur de coût.** Le préprocesseur change les chemins et le compilateur peut supprimer uniforms, varyings et instructions. Le seul numéro de stage ne décrit pas une configuration expérimentale complète. Les documents historiques mentionnent eux-mêmes des valeurs différentes de `leapfrogActive`.

Enfin, `Game::render` exécute les ombres avant les autres passes. Les draws depth dépendent des meshes déjà uploadés ; de nouveaux uploads ont lieu dans `VoxelRenderer::render`, donc après cette passe. La charge depth n’est ni strictement constante ni déductible du seul nombre de cascades. Une frame de warm-up représente neuf traversées du monde, pas neuf draw calls.

### Protocole minimal recommandé avant toute nouvelle conclusion

**RECOMMANDATION.**

1. Figer une scène et une pose, attendre la fin du meshing, fixer résolution framebuffer, viewport, VSync, paramètres d’ombre et configuration compilée. Enregistrer l’identité des sources réellement chargées.
2. Valider les images du micro-test de la section 4, avec textures initialisées. Ne pas commencer par la matrice exhaustive de quinze stages.
3. Si les capabilities le permettent, mesurer les passes par queries et récupérer les résultats plusieurs frames plus tard, sans attente immédiate. Vérifier la précision/counter bits ; ne pas imbriquer des queries `TIME_ELAPSED` sur la même cible. Avec timestamps supportés, employer leur contrat propre. Référence : [ARB_timer_query](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_timer_query.txt).
4. Sans timer GPU supporté, vider le travail antérieur avant le début de l’intervalle CPU, soumettre un workload fixé, puis attendre sa fin dans l’intervalle. Nommer la mesure « temps CPU soumission + achèvement », pas « temps GPU ». Conserver séparément le profil normal du moteur, sans prétendre que ce test sérialisé reproduit sa performance interactive.
5. Comparer une variable à la fois : d’abord lecture conforme sans CSM dynamique ; ensuite nombre de taps ; ensuite format/contenu contrôlés ; enfin coût des passes depth avec inputs figés et valides.
6. Compter réellement draws et sommets, identifier chaque rendu A/B/C, aligner identifiant de frame, pose et mesures. Répéter et comparer distributions, pas seulement une moyenne.
7. Réintroduire déplacement, swaps et streaming seulement après cette référence stable. Une comparaison Intel/M2 peut alors être informative.

## 7. GPU ownership and lifetime review

**FACTUEL.** Le passage d’une référence dans un constructeur n’est pas la preuve d’un transfert de propriété. Ici, les conclusions fortes reposent sur les appels de cleanup et les écrasements de handles.

| Ressource | Rapport initial | Review Astra | Risque réel |
|---|---|---|---|
| Programme voxel | Ownership multiple | Confirmé : créé par `PlayingState`, détruit par `VoxelRenderer`, puis par `PlayingState` | Ownership ambigu et appels de suppression invalides après la première destruction normale ; pas crash certain. |
| Programme depth | Jusqu’à trois destructions | Confirmé : chaîne `VoxelRenderer` → `ShadowRenderer`, puis appel direct et shader explicite | Même problème, avec trois tentatives. |
| `ShadowRenderer` | Partagé et nettoyé plusieurs fois | Référence empruntée pour les ombres mais cleanup déclenché aussi par voxel | Frontière de responsabilité réellement contradictoire. |
| Atlas/FBO/C/couleur de `ShadowRenderer` | Cleanup non idempotent | Allocation/stockage local cohérents ; double appel extérieur identifié | Suppressions de noms inutilisés tolérées dans la chaîne actuelle ; danger de handles périmés si une réutilisation était intercalée ultérieurement. |
| Atlas voxel | Jamais détruit | Confirmé : chargé dans `PlayingState::enter`, passé au renderer, aucun delete retrouvé dans son cleanup | Ressource abandonnée jusqu’à destruction du contexte ; accumulation si recréations dans le même contexte. |
| Ancien VAO/VBO lors d’un remesh | Perdus | Confirmé dans `MeshWorkerManager::uploadMeshesIfAny` → `Chunk::setMesh` | Lifetime bug réel et cumulatif pendant le jeu. |
| Mesh courant lors d’un unload normal | Nettoyé par `Chunk` | Correct : `World::unloadFarChunks` appelle `Chunk::cleanup`, qui remet les handles à zéro | Ressource correctement libérée dans ce chemin ordinaire. |
| Mesh calculé après unload | Upload orphelin possible | Possible : résultat garde l’objet `Chunk`, pas de validation de présence/version avant upload | Lifetime bug conditionnel ; occurrence runtime à instrumenter, pas preuve de corruption systématique. |
| Chunks restants à `World::cleanup` | Pas de cleanup GPU | Confirmé : arrêt des services, pas de parcours des meshes vivants | Abandon jusqu’à fin du contexte ; accumulation lors de changements de monde possibles. |
| `MeshData` en attente à l’arrêt | Peu développé | `memAllocFloat` libéré après upload ; `shutdownNow()` ne draine pas la file et n’attend pas nécessairement la fin des calculs | Fuite native possible en plus des objets GPU ; la GC Java n’est pas un contrat de libération de cette allocation explicite. |
| `Texture.fromId` | Handle généré puis écrasé | Confirmé, mais wrapper dormant | Fuite de nom réservé ; ne prouve pas l’allocation d’une image GPU de taille non nulle. |
| Attachments externes du wrapper `Framebuffer` | Ownership ambigu | Confirmé comme contrat non explicité, pas comme double propriétaire actif démontré | Un transfert de propriété serait légal ; aucune utilisation active ne permet de conclure davantage. |
| Sun/UI/Text, nettoyage normal | Cohérent | Confirmé sur le chemin normal : ressources locales détruites localement | À laisser stable ; ne pas généraliser les défauts voxel/depth à tous les renderers. |

Preuves : `PlayingState::enter/exit`, lignes 115–122 et 241–278 ; `VoxelRenderer::cleanup`, lignes 265–267 ; `ShadowRenderer::cleanup`, lignes 755–760 ; `Shader::cleanup`, lignes 86–88 ; `MeshWorkerManager::uploadMeshesIfAny/shutdown`, lignes 57–95 ; `Chunk::setMesh/cleanup`, lignes 133–160 ; `World::unloadFarChunks/cleanup`, lignes 327 et 415 ; `MeshBuilder::generateMeshData`, vers la ligne 41 ; `MeshData::free` ; `render/core/Texture::fromId`, ligne 194 ; `render/core/Framebuffer::attachColorTexture/attachDepthTexture/cleanup`.

Le remeshing n’est pas seulement une possibilité liée à une future édition de blocs : `World::generateChunk`, vers la ligne 227, marque les voisins pour remeshing via `markNeighborsDirty`. Le streaming peut donc atteindre le chemin qui remplace un mesh existant.

**Contexte et fin de vie.** `Application::cleanup`, vers la ligne 297, nettoie l’état avant de détruire la fenêtre/contexte. Aucun destructeur Java/finalizer invoquant OpenGL après la destruction du contexte n’a été identifié dans ce chemin. C’est un invariant existant correct. Une ressource abandonnée jusqu’à destruction du contexte reste un défaut de lifetime, mais ne signifie pas une fuite GPU persistante après la fin du processus.

**Exceptions.** `Shader` ne libère pas toutes les ressources intermédiaires lorsque compilation/link échouent, lignes 26–43 et 65–73. C’est un chemin d’échec distinct du cleanup normal, à documenter ; il n’explique pas une lenteur dans un programme correctement lié. Aucun nouveau système générique de ressources n’est nécessaire pour comprendre ce cas.

## 8. CSM / leapfrog review

### Architecture réellement active

**FACTUEL — Haute confiance.** `ShadowRenderer` alloue un atlas depth de six couches, deux groupes de trois pour A/B ; une array C depth séparée de trois couches ; une array couleur R32F de trois couches. Avec 2048² et quatre octets par texel, cela représente **192 MiB de stockage logique d’images**. Ce n’est pas une mesure de mémoire physique résidente du driver, qui peut ajouter padding ou employer d’autres représentations.

Chemin temporel vérifié dans `ShadowRenderer::renderInternal`, lignes 392–596 :

| Phase | Travail réellement effectué | Consommation |
|---|---|---|
| Mode OFF | Recalcule/rend A complet, trois cascades ; synchronise certaines données B | Shader actuel lit A. Ce mode n’est pas « aucune shadow pass ». |
| Warm-up | Rend A, B et C complets | Neuf traversées du monde ; nombre de draws dépend des chunks avec mesh. |
| Running | Évalue les candidats A/B et leurs snaps ; rend les cartes nécessaires | A alimente les lookups courants ; B prépare une future A. |
| Mise à jour C découpée | Rend une couche C sur les frames éligibles | Pixels C sans consommateur final identifié ; travail GPU réel. |
| Fin de crossfade CPU / swap | Échange les offsets physiques A/B ; `dataA = dataB`, `dataB = dataC` | Ancienne B devient la nouvelle A consommée. |
| Après swap | Redessine la nouvelle B avec les données transférées de C | Pas de copie de pixels de C vers B. |

Trace minimale de dépendance :

```text
pixels B avant swap ── échange des offsets ──► pixels A lus ensuite
données CPU C ──► données CPU B ── nouveau rendu dans B ──► future A
pixels de la texture C ──► aucun lecteur d’image finale identifié
```

Références : `ShadowRenderer::renderInternal`, lignes 503–545 ; `getCascadeData/getAtlasOffsetA`, lignes 767–776 ; `VoxelRenderer::render`, lignes 165–176 et 203 ; `voxel_fragment_shader.glsl::calculateShadow`, lignes 463–465.

### Infrastructure expérimentale désactivée

Le crossfade GLSL est neutralisé par `leapfrogActive = 0`. Le stage 5 est également une sélection compile-time A seul : réactiver seulement cet uniform ne suffirait pas à rendre ce stage « full leapfrog ».

Les branches PCF, A/B et hardware compare existent pour d’autres stages ou chemins ; elles ne sont pas toutes du code mort. Modifier le define puis recompiler peut les rendre actives. Le contenu d’un commentaire « normal » n’est pas une preuve du chemin exécuté : le stage 0 actuel produit notamment une sortie de diagnostic magenta.

`colorTestArray` est une ressource de micro-tests, pas une passe couleur rendue cachée. Son uniform peut être éliminé au stage 5 ; son allocation Java subsiste. Changer le mode leapfrog CPU par la touche prévue modifie aussi le scheduling des rendus depth, pas seulement la présence d’un blend.

### Code potentiellement mort ou incohérent

Les **pixels** de C sont produits sans copie ni échantillonnage final identifié. Cette conclusion ne s’étend pas à `dataC`, aux bases de lumière C ni à la machine qui les prépare. La promotion de ces données est active.

Le commentaire de copie C → B ne décrit pas l’implémentation présente. L’intention probable est un staging temporel de carte future ; le code actuel réutilise les paramètres mais refait le dessin. Il faut décider humainement si c’est un compromis voulu, une expérience provisoire ou une étape inachevée.

Les 48 MiB logiques de C depth et 48 MiB du test couleur n’ont pas de contribution d’image directe identifiée au stage 5. Cela justifie une expérience ultérieure de coût, **pas la suppression automatique de C ou de toute l’architecture leapfrog**.

### Mathématique du snap

Le soupçon du rapport est étayé plus précisément. `CSMCalculator::computeCascadeMatrixWithBasis`, lignes 504–562, utilise un demi-extent `R`, un texel monde `t = 2R / S`, une translation vue `-snapX` et une projection orthographique de largeur `2R`. La composante de translation de la matrice combinée vaut donc `-snapX / R`. `ShadowRenderer::snapIndexX`, lignes 836–840, calcule pourtant l’arrondi de `-m30 / t`, soit `snapX / (R·t)`, au lieu de retrouver une cellule correspondant à `snapX / t`. Même question pour Y.

**Haute confiance** dans l’incohérence d’unités sur ce chemin ; **Confiance moyenne** dans son impact graphique exact. La vérification de la formule de projection s’appuie aussi sur le source JOML 1.10.5 de `Matrix4f::orthoLH`. Le bon test est un déplacement connu d’un texel en espace lumière, pas un jugement sur le nom de la méthode ou sur l’emploi d’une projection LH.

## 9. OpenGL state review

### Dépendances réellement dangereuses

| Dépendance | Précondition réelle et constat | Invariant à expliciter ultérieurement |
|---|---|---|
| Compare mode partagé | `VoxelRenderer::render`, 203–233, modifie le même objet que le sampler manuel consomme | Avant chaque draw, couple type de sampler / format / compare mode cohérent ; pas seulement bonne unité. |
| Depth dans les menus et le loading | `MenuState` désactive à l’entrée ; `TextRenderer::drawText`, 160, réactive au milieu ; `UIRenderer` ne corrige pas | Toute la séquence UI, y compris texte et sous-composants, doit respecter le même contrat de depth. |
| Erreurs GL consommées silencieusement | `ShadowRenderer::drainGLErrors`, 858, et `VoxelRenderer::drainGLErrors`, 89, vident les erreurs | Une erreur doit garder une provenance exploitable pendant le diagnostic ; zéro après drainage n’est pas une preuve de validité. |
| Attributs supposés plutôt que vérifiés | `Shader` lie `aTex`, mais Text déclare `aUV`, UI `aTexCoord` ; VAO attendent 1 | Interface du programme lié et layout VAO doivent correspondre explicitement. |

La dernière ligne est une incohérence potentielle d’interface CPU/GLSL, pas un bug de blend ou de texture binding. `CubeTest` reproduit le problème de nom d’attribut, mais via son propre code de compilation/link, lignes 69–70, et non via le wrapper `Shader`.

### Dépendances déterministes aujourd’hui, fragiles si l’ordre change

- **Culling et front face.** Les passes depth activent culling/back, sans fixer le winding : `ShadowRenderer::renderShadowMapIntoAtlas`, lignes 620–625, et méthodes analogues. Dans le chemin actuel, le winding initial CCW et `VoxelRenderer` qui réimpose CCW concordent ; aucun chemin actif CW n’a été trouvé. Dépendance implicite à documenter, pas ombres actuellement invalides prouvées. `SunRenderer` et `TextRenderer` laissent le culling désactivé ; la prochaine passe qui en a besoin le réactive.
- **FBO/viewport/color mask.** Les `finally` de `ShadowRenderer` imposent framebuffer 0, viewport fenêtre, color mask vrai, depth write vrai et `LESS`, au lieu de restaurer arbitrairement le caller. C’est compatible avec `Game::render` qui enchaîne Sky/Sun/Voxel sur le framebuffer par défaut. Cela deviendrait incorrect si ce renderer était utilisé comme sous-passe d’un autre FBO sans changement de contrat.
- **Draw/read buffers.** Le FBO depth est configuré sans sorties couleur ; le retour à `GL_BACK` s’effectue après rebinding de 0. Ne pas interpréter `GL_NONE` pour un FBO depth-only comme une erreur ni supposer qu’il reste automatiquement appliqué à tous les autres FBO. `SkyRenderer::render`, lignes 46–60, réaffirme également le framebuffer et ses buffers.
- **Blend/depth du soleil.** `SunRenderer::render`, lignes 101–112, dessine sans depth test, avec alpha blending, puis réactive le depth et désactive blend. Comme le monde est dessiné ensuite, les voxels peuvent recouvrir le soleil. Pas de passe deferred ou de bug d’occlusion démontré par ce seul ordre.
- **Textures, VAO et programme courant.** Les renderers et helpers font leurs bindings, puis délient partiellement. Revenir systématiquement à zéro n’est pas une restauration du caller, mais cela n’est pas intrinsèquement erroné dans cette séquence. `Shader::setUniform` dépend du programme correctement bindé : cet ordre est respecté dans les chemins principaux inspectés.
- **Scissor et polygon mode.** Les passes 3D désactivent scissor ; l’application initialise le fill. Aucun producteur actif d’un état alternatif persistant n’a été identifié. Ne pas inventer ici une fuite d’état.
- **sRGB.** Activation globale connue, encodage effectif inconnu. À vérifier pour comparer les pixels, pas à transformer immédiatement en refactor colorimétrique.

**Faux positif à éviter :** l’atlas peut rester bindé sur l’unité 3 lorsque la passe depth suivante l’attache au FBO. Le depth shader n’échantillonne aucune texture. Ce binding seul ne démontre pas une boucle de feedback lecture/écriture. La condition pertinente concerne la possibilité de lecture par le programme du draw, pas la simple présence d’un nom dans une unité. Voir [OpenGL 3.2 Core, §4.4.3](https://registry.khronos.org/OpenGL/specs/gl/glspec32.core.pdf).

**Nuance sur les exceptions :** le restore du compare mode n’est pas dans un `finally`, comme le relève le rapport. Toutefois `Application::loop` intercepte une exception de rendu et quitte la boucle ; il ne poursuit pas normalement une longue série de frames empoisonnées. La configuration erronée pendant le draw normal est le défaut prioritaire, pas une propagation après exception supposée.

## 10. Architectural review

### Problèmes architecturaux réellement établis

1. **Plusieurs composants détruisent les mêmes programmes et le même collaborateur.** Le contrat `PlayingState` / `VoxelRenderer` / `ShadowRenderer` ne distingue pas emprunt et possession. Le graphe de cleanup, et non le style de programmation, établit le problème.
2. **Le remplacement du mesh n’inclut pas la retraite de l’ancien.** `MeshWorkerManager` crée, `Chunk` stocke, mais `setMesh` écrase sans libérer. L’invariant « chaque mesh remplacé a une fin de vie » n’a pas de responsable dans le chemin actif.
3. **Le résultat asynchrone reste applicable sans vérifier le chunk destinataire.** `MeshData` référence un objet qui peut avoir quitté le monde. La frontière génération/upload/unload doit être comprise avant un refactor de streaming.
4. **Une opération UI interne modifie les préconditions des opérations suivantes.** `drawText` n’est pas neutre sur le depth attendu par ses frères. Une frontière locale de passe UI est justifiée ; pas un système universel d’états.
5. **Configuration déclarée, programme compilé et métriques ne désignent pas toujours la même expérience.** Stage compilé, uniform forcé, toggles CPU et colonnes codées en dur coexistent. Un contrat diagnostic explicite est nécessaire avant de donner une architecture définitive au CSM.

### Dette locale ne nécessitant pas encore de refactor

Le compare mode, le nombre de sommets texte, les noms d’attributs et la conservation de la provenance des erreurs sont des corrections locales de validité ou de diagnostic. Leur existence ne démontre pas le besoin d’un backend abstrait.

Les uniforms recherchés par nom à chaque envoi, les matrices temporaires ou l’absence de culling par cascade peuvent coûter du temps, mais leur coût pertinent doit être mesuré. Le layout voxel de neuf floats, la transformation de chunks par translation et les uniforms classiques ont des contrats suffisamment lisibles. Une normal matrix inverse-transposée n’est pas exigée uniquement parce que c’est un patron courant : le modèle actif ne comporte pas de scale non uniforme.

L’interface `Renderer` et la séquence explicite dans `Game::render` sont petites et compréhensibles. Aucune preuve ne justifie de les remplacer par un RenderGraph, CommandBuffer ou GraphicsDevice.

### Legacy / strates dormantes

La lecture du rapport est globalement juste : `render/core/Window`, `Texture`, `Framebuffer` ne possèdent pas les ressources du chemin actif, contrairement à `RenderContext` qui y participe. Leur présence n’implique pas deux backends actifs.

`engine/menu/**` est exclu par `build.gradle`, lignes 26–37 ; le menu actif est dans `engine/ui`. Le chemin synchrone `MeshBuilder::buildChunkMesh` n’est pas celui de `MeshWorkerManager`. `OrthographicCamera`, l’ancien `engine/time/Sun` et `CubeTest` ne doivent pas être traités comme s’ils gouvernaient la frame de jeu courante.

**INTENTION PROBABLE.** Ces ensembles correspondent à des périodes différentes de développement et d’expérimentation. Ni leur forme ni leurs commentaires ne permettent d’en attribuer fiablement l’origine à un agent.

### Zones encore trop incertaines

Le choix voulu entre crossfade, dithering et promotion brutale ; l’utilité produit du staging C ; les paramètres de stabilité CSM ; le chemin « normal » voulu après bisect ; le budget GPU visé à la résolution Retina. Ces décisions ne doivent pas être figées par un refactor supposant que les réglages de diagnostic représentent le produit final.

## 11. Missing findings

Les ajouts matériels sont limités aux points suivants ; il n’est pas nécessaire d’inventer une seconde architecture cachée.

1. **B a un consommateur temporel et C a deux natures différentes.** Le rapport contient les opérations de swap, mais n’en tire pas correctement la conséquence pour la consommation de B. C CPU et C pixels ne peuvent pas partager le même verdict d’inutilité.
2. **La taille uploadée du texte est inférieure à la capacité allouée.** Le rapport identifie l’omission de glyphes, mais lui attribue une protection inexistante. Finding 4.
3. **Le depth du menu change pendant son propre rendu.** La précondition vérifiée seulement à l’entrée du menu est insuffisante. Finding 5.
4. **Le logger associe des timings précédents à un état courant.** Important pour toute tentative de corrélation avec les événements leapfrog, moins pour des médianes stationnaires. `PlayingState::update`, 190, et `Application::loop`, 265–269.
5. **Désactiver la shadow pass coupe aussi les données CPU de cascades.** L’expérience proposée doit préserver ces inputs. Finding 7.
6. **Le fallback de format ne constitue pas une validation complète.** `ShadowRenderer::checkAndFixAtlasFBO`, lignes 287–303, teste une couche de l’atlas, recrée celui-ci si nécessaire, mais ne recontrôle pas le nouvel objet dans cette méthode. Il restaure ensuite la configuration pour que les logs reflètent l’intention, pas nécessairement le format obtenu. C n’est pas recréée par ce fallback. Aucun échec runtime n’est prouvé, mais un benchmark de formats doit relever les formats effectivement créés et la complétude de chaque cible utilisée.
7. **L’arrêt des workers peut abandonner des buffers natifs en attente.** `MeshWorkerManager::shutdown` ne traite pas la file d’upload. Le compteur de `MeshData` ne mesure donc pas à lui seul toutes les ressources GPU du moteur.
8. **Le système de tests n’offre pas encore la garantie implicite attendue.** `build.gradle`, lignes 33–36, vide les sources Java de test. Une commande de test réussie ne démontrerait pas l’exécution des tests présents ailleurs dans le dépôt. Aucun test n’a été lancé pendant cette review.

Une nuance de frontière s’ajoute : CubeTest utilise sa propre compilation/link. Le défaut de nom d’attribut est similaire à UI/Text, mais ne serait pas corrigé automatiquement par une modification du wrapper `Shader`.

## 12. Revised risk register

Les types A–H reprennent la grille demandée : A résultat/API hors garanties normatives ; B version supérieure ; C extension ; D non-portabilité ; E état ; F interface/données ; G architecture/lifetime ; H correct mais sous-optimal. Pour le compare mode, « A » ne signifie pas qu’une erreur API doit être levée. Une seule catégorie principale est choisie par ligne ; les défauts de protocole sont explicités comme tels.

| Priorité | Problème | Type | Gravité | Confiance | Avant refactor ? |
|---:|---|---|---|---|---|
| 1 | Lookup manuel avec compare mode actif dans le code courant | A | Critique pour la validité du diagnostic | Haute confiance | OUI — obtenir une référence conforme. |
| 2 | Anciennes mesures non reliées à une configuration exécutable complète ; interprétations causales excessives | G — protocole | Critique pour les décisions | Haute confiance | OUI — avant tout refactor motivé par les performances. |
| 3 | Draw texte pouvant dépasser le VBO uploadé | F | Élevée, conditionnelle aux caractères | Haute confiance | OUI — pour une référence UI fiable ; indépendant du CSM. |
| 4 | Perte des VAO/VBO lors du remeshing actif | G — lifetime | Élevée en session longue | Haute confiance | OUI — comprendre avant refactor du monde/meshing et mesures longues. |
| 5 | Modèle mental « B inutilisée » incorrect | G — modèle de dépendances | Élevée | Haute confiance | OUI — avant toute modification du leapfrog. |
| 6 | Indice de snap extrait d’une matrice déjà projetée | F | Potentiellement élevée | Haute confiance sur la formule | OUI — avant stabilisation/refactor CSM. |
| 7 | Upload possible après unload ; buffers natifs abandonnés à l’arrêt | G — lifetime/concurrence | Élevée, conditionnelle | Confiance moyenne sur occurrence | DÉPEND — impératif pour streaming/lifetime, pas prérequis d’un micro-test texture autonome. |
| 8 | Programmes voxel/depth avec plusieurs responsables de destruction | G | Moyenne | Haute confiance | OUI — comprendre ; candidat de petit refactor après validité. |
| 9 | Atlas voxel et meshes restants abandonnés à la sortie de Playing | G — lifetime | Moyenne à élevée selon transitions | Haute confiance | DÉPEND — avant répétitions de chargements dans le même contexte. |
| 10 | Depth UI modifié au milieu de la passe | E | Moyenne | Haute confiance | DÉPEND — avant refactor ou validation visuelle UI. |
| 11 | Drainage silencieux des erreurs, colonnes factices et retard du logger | E — diagnostic | Élevée pour l’investigation | Haute confiance | OUI — ne pas utiliser ces sorties comme preuves sans correction du protocole. |
| 12 | Timer queries non garanties en 3.2 | B | Moyenne, risque d’investigation incorrecte | Haute confiance | OUI — vérifier le support avant instrumentation proposée. |
| 13 | Test R32F non initialisé ; fallback/format réel non contrôlé | F — protocole | Moyenne | Haute confiance | OUI — avant comparaison de formats et samplers. |
| 14 | Locations d’attributs UI/Text/Cube supposées | D | Moyenne | Haute confiance sur absence de contrat | DÉPEND — avant comparaison de drivers ou refactor d’interface shader. |
| 15 | Rendus des pixels C sans lecteur final ; scheduling sans crossfade visible | G | Coût potentiel moyen à élevé | Haute confiance sur les appels, Confiance moyenne sur le coût | NON — ne pas supprimer avant trace et décision fonctionnelle. |
| 16 | Pas de culling par cascade, synchronisation finale systématique | H | Coût à mesurer | Haute confiance sur les appels | NON — optimisation après référence valide. |
| 17 | Encodage framebuffer sRGB non vérifié | D | Faible à moyenne | Confiance moyenne | DÉPEND — à relever avant comparaison de pixels, pas refactor global. |
| 18 | Wrappers dormants, menus exclus, chemins legacy | G — dette dormante | Faible pour le rendu actuel | Haute confiance | NON — documenter, décider plus tard. |

## 13. Revised investigation order

### Review des dix étapes originales

| Étape originale | Décision | Justification |
|---|---|---|
| 1. Vérité runtime | Garder ; ajouter une étape préalable | Identifier d’abord l’exécutable et les sources chargées ; inclure capabilities de timing, formats et taille framebuffer. |
| 2. Micro-test conforme | Diviser | Vérifier d’abord l’image connue, puis mesurer ; ne pas dépendre de tout le CSM pour cette première preuve. |
| 3. Timer queries | Garder sous condition | Support/qualité vérifiés avant usage ; résultats différés ; fallback de mesure clairement nommé. |
| 4. Matrice de tests | Diviser et réduire au départ | Commencer par deux chemins conformes à inputs identiques ; nombre de taps, formats, CPU scheduling viennent ensuite. |
| 5. Vérifier le graphique | Déplacer plus tôt | C’est un prérequis du chronométrage interprétable, pas une validation finale. |
| 6. Décider si leapfrog est actif/expérimental | Diviser et reformuler | Établir immédiatement que promotion CPU et crossfade shader sont distincts ; reporter la décision produit après la trace A/B/C. |
| 7. Mathématique CSM/snap | Garder après la référence texture | Réduit une incertitude fonctionnelle avant tout refactor CSM ; une vérification CPU peut être menée sans nouveau benchmark GPU. |
| 8. Ownership | Déplacer l’inventaire plus tôt ; séparer la correction | Comprendre le lifetime avant les tests longs et transitions ; ne pas mélanger sa correction avec l’expérience sampler. |
| 9. Optimisations/culling/suppressions | Retirer de cette phase d’investigation | Remplacer par quantification du coût et inventaire des consommateurs ; optimisation ultérieure seulement si justifiée. |
| 10. Comparer les plateformes | Garder après conformité et reproductibilité | Ni nécessaire pour prouver le défaut courant, ni interdit une fois une expérience contrôlée disponible. |

### Ordre final recommandé

**RECOMMANDATION — priorité à la réduction d’incertitude, pas au nombre de changements.**

1. Établir une fiche d’expérience : hash du build et des GLSL chargés, stage, toggles CPU/uniforms effectifs, scène, pose, dimensions framebuffer, contexte, extensions et GPU exact.
2. Lire et vérifier les bindings et les uniforms actifs au draw. Préserver la provenance des erreurs. Séparer immédiatement le diagnostic présent des CSV sans provenance suffisante.
3. Valider les pixels du plus petit test manuel/shadow conforme, sans CSM dynamique, textures initialisées, nearest. Ne chronométrer qu’après cette validation.
4. Choisir l’instrument de timing réellement supporté ; distinguer profil normal du moteur et micro-test sérialisé. Aligner frames, états et mesures.
5. Reproduire une scène fixe du moteur avec le seul contrat sampler rendu conforme. Compter les draws et mesurer les passes. Déterminer ce qui reste lent sans nouvelle théorie GPU.
6. Avant de prolonger les essais, vérifier l’évolution des handles lors des remeshs, l’unload et la chaîne de cleanup. Corriger les défauts locaux de texte si l’UI du scénario les déclenche ; ne pas attendre un refactor général.
7. Tracer A/B/C autour d’un swap : identités physiques, générations de contenu, matrices, offsets et frame de consommation. Vérifier séparément le calcul des indices de snap avec des entrées connues.
8. Faire choisir au développeur le comportement visuel de référence : crossfade voulu ou non, rôle de C, stabilité attendue. Ensuite seulement étendre les benchmarks aux taps, formats et scheduling.
9. Si une question de portabilité subsiste, exécuter le même cas conforme sur un autre GPU/driver, éventuellement un Mac Intel, à résolution identique et GPU identifié. Comparer d’abord la correction, puis les temps.
10. Choisir le petit refactor d’ownership de la section 15. Les optimisations, suppressions de ressources et réorganisation du CSM restent des décisions ultérieures fondées sur ces résultats.

Cette liste n’oblige pas à résoudre le CSM entier avant un exercice indépendant sur les programmes. Elle interdit surtout de transformer une incertitude causale ou fonctionnelle en architecture définitive.

## 14. Zones à ne pas refactorer pour l’instant

| Zone | Pourquoi attendre | Information ou expérience préalable |
|---|---|---|
| Architecture complète A/B/C | Le consommateur temporel de B et le transfert des données C ont été mal interprétés | Trace de swap et décision humaine sur le comportement attendu. |
| Suppression de C en bloc | Pixels C et paramètres C ont des consommateurs différents | Isoler le coût des dessins C sans supprimer les paramètres promus. |
| Atlas unique contre textures séparées | La comparaison historique ne contrôle pas suffisamment les états et sources | Micro-tests conformes à contenus, filtres et interfaces comparables. |
| Migration Metal ou backend multi-API | Aucun besoin de version supérieure n’est établi pour le rendu actuel | Cas conforme restant problématique, objectif produit et budget de portage explicites. |
| Système générique d’état OpenGL | La plupart des états 3D sont déterministes dans l’ordre actuel | Petits contrats de passes écrits et tests des véritables dépendances, notamment UI. |
| Adoption des wrappers `render/core` | Ils ne gouvernent pas les ressources actives et comportent leur propre dette | Décider quel invariant réel ils apporteraient ; ne pas migrer pour homogénéiser les noms. |
| Réécriture du meshing/streaming | La fuite de remplacement est établie, mais le problème asynchrone doit être caractérisé | Observer remplacement, génération, unload et shutdown ; correction ciblée de lifetime avant refonte. |
| Conversion générale des uniforms en UBO | Aucun coût mesuré ne la justifie ; interfaces actuelles compréhensibles | Profil CPU et inventaire des données réellement partagées. |
| Nouveau système de lumière/normal matrices | Les transformations actives ne prouvent pas les défauts qu’un schéma plus générique résoudrait | Cas graphique reproduisant un problème concret. |
| Culling par cascade et autres optimisations structurelles | Correctitude et coût des passes ne sont pas encore isolés | Compteurs réels et comparaison à image équivalente. |
| Suppression immédiate du legacy | Dormant ne signifie pas dépourvu de valeur documentaire | Inventaire des usages externes et décision du développeur ; hors urgence renderer. |

« Ne pas refactorer » n’interdit pas de corriger un bug local établi après validation du développeur. Cela signifie ne pas transformer cette correction en changement de frontière architecturale non justifié.

## 15. Premier refactor architectural pédagogique recommandé

### Candidat unique : clarifier l’ownership des programmes voxel/depth et le graphe de cleanup associé

**Problème.** Deux programmes sont créés par `PlayingState`, transmis à des renderers, puis détruits par plusieurs chemins. `VoxelRenderer` détruit également `ShadowRenderer`, dont il a besoin comme fournisseur de données. Le développeur peut dessiner et comprendre entièrement ce petit graphe sans maîtriser immédiatement toutes les mathématiques CSM.

**Responsabilités actuelles — FACTUEL.**

- `PlayingState::enter` assemble et crée les programmes ainsi que les renderers.
- `VoxelRenderer` utilise le programme voxel et consulte les données/texture de `ShadowRenderer`.
- `ShadowRenderer` utilise le programme depth et possède localement ses textures/FBO.
- `PlayingState::exit`, `VoxelRenderer::cleanup` et `ShadowRenderer::cleanup` revendiquent une partie des mêmes destructions.

**Décision architecturale à prendre par le développeur.** Pour chaque programme et chaque renderer partagé, qui est propriétaire, qui est emprunteur et quel événement met fin à sa vie ? La décision doit apparaître dans les responsabilités et non seulement dans un garde « déjà détruit ».

### Alternatives raisonnables

| Alternative | Avantage | Compromis |
|---|---|---|
| `PlayingState` possède les programmes qu’il crée ; les renderers les empruntent | Composition centralisée, partage éventuel facile, frontière de fin d’état visible | Le propriétaire doit respecter l’ordre de destruction et garder connaissance des deux programmes. |
| Chaque renderer possède son programme ; `PlayingState` possède les renderers | Encapsulation locale proche de `SunRenderer`, moins de handles au niveau état | Partage moins naturel ; création/configuration du programme doit avoir un contrat de transfert explicite si elle reste extérieure. |

Les deux peuvent convenir. Rien ne justifie ici du reference counting, un framework d’injection, un registre global de ressources ou une couche GraphicsDevice. Le rôle emprunté de `ShadowRenderer` dans `VoxelRenderer` doit être tranché explicitement quelle que soit l’alternative retenue.

### Invariants à préserver

1. Chaque programme a un propriétaire unique et une destruction normale unique.
2. Aucun renderer ne détruit un programme ou collaborateur déclaré emprunté.
3. Les utilisateurs cessent de dessiner avant la destruction des programmes ; le contexte est encore courant pendant les appels GL.
4. Les chemins de création partielle/échec ont une responsabilité identifiable, même si leur amélioration complète est séparée de cet exercice.
5. Aucun changement de GLSL, d’ordre de passes, de matrices, de texture units, de scheduling A/B/C ou de layout de mesh n’entre dans ce refactor.
6. Le cleanup des ressources internes de `ShadowRenderer` reste local et intervient une seule fois via son propriétaire.

### Validation à prévoir, sans implémentation dans cette review

Avant modification, le développeur prépare une table création → usage → destruction et prédit le nombre d’appels. Après sa modification, il vérifie sur un cycle entrée/sortie que chaque identifiant créé reçoit la destruction attendue et qu’aucun draw ne suit sa fin de vie. Tester la fermeture normale et, si le flux applicatif le permet réellement, une seconde entrée dans le même contexte. Ne pas ajouter artificiellement un nouveau système de pause pour obtenir ce test.

Comparer les images d’une pose fixe avant/après, contrôler l’absence de nouvelles erreurs GL sans drainage silencieux, et vérifier que les textures/FBO d’ombre ont toujours leur cleanup. Les défauts de mesh et d’atlas voxel connus doivent être suivis séparément, pour ne pas les confondre avec une régression de cet exercice.

**Rôle recommandé des agents :** challenger la table d’ownership, demander au développeur d’expliquer son choix, puis reviewer son diff et ses preuves de validation. L’agent n’écrit pas l’implémentation et ne décide pas à sa place. La petite taille du graphe est ici un avantage pédagogique plus important qu’un gain de performance immédiat.

## 16. Corrections proposées au rapport original

### Corrections nécessaires

1. **Executive summary, points 6–7 :** remplacer l’inactivité visuelle globale du leapfrog/B par une distinction entre crossfade GLSL désactivé et promotion temporelle B → A active. Les deux affirmations ne sont pas équivalentes.
2. **Section 6.4, texte :** retirer l’assurance que le buffer GPU reste dimensionné au maximum. Documenter `flip()` → `remaining()` → taille du stockage uploadé, puis le draw count calculé sur la longueur non filtrée.
3. **Section 7, UI :** corriger « vrai dans MenuState » : vrai à l’entrée seulement, pas après un appel à `drawText` qui réactive le depth.
4. **Executive summary 17 et investigation 3 :** ajouter la condition OpenGL 3.3 / extension pour les timer queries ; prévoir un protocole alternatif qualifié si elles sont absentes.
5. **Executive summary 13 et section 10, cas manuels 5–11 :** retirer l’affirmation historique globale non prouvée. La combinaison actuelle est démontrée ; la correspondance avec chaque ancien exécutable ne l’est pas.
6. **Section 10, « ce que les logs prouvent » :** remplacer la causalité attribuée aux coordonnées dynamiques par une différence observée entre variantes. Ne pas attribuer à tous les benchmarks la résolution d’un log de février sans preuve par run.

### Nuances recommandées

- **Diagnostic principal :** priorité certaine de validité ; responsabilité dans les lenteurs et flashes encore hypothétique. Conserver le terme normatif « résultat du lookup undefined », sans le transformer en erreur API obligatoire.
- **Bindings des samplers :** même objet sur plusieurs unités confirmé ; deux samplers actifs ou deux lectures effectives non garantis au stage 5. La mutation de l’objet explique le défaut sans cette hypothèse supplémentaire.
- **Métriques :** `glFinish_ms` est une attente résiduelle et non un cumul de temps GPU mesurés ; `render_ms` inclut du travail CPU et peut inclure des blocages. La sérialisation est certaine, son amplitude de surcoût reste inconnue.
- **Ownership :** distinguer les erreurs sur programmes déjà détruits, les suppressions répétées de noms inutilisés tolérées et les vraies ressources abandonnées. Ne pas assimiler tous les `glDelete*` à un double-free fatal.
- **C et mémoire :** absence de lecteur des pixels C, mais réemploi des données CPU C ; 192 MiB désigne un volume logique d’images, pas une mesure de résidence GPU.
- **Snap :** renforcer la preuve algébrique du mélange d’unités, tout en gardant une réserve sur son effet visuel effectif.
- **State 3D :** restauration vers des valeurs fixes ne veut pas dire bug actuel lorsqu’elles constituent le contrat de l’appelant. Front face, scissor, FBO/viewport et programme doivent être analysés dans l’ordre réel des passes.
- **Wrappers :** un attachment externe détruit par un wrapper peut résulter d’un transfert de propriété voulu ; l’absence de contrat explicite ne prouve pas une double destruction active.
- **Tests un/quatre taps :** nombre d’appels GLSL, fragments exécutant réellement le lookup et lectures physiques filtrées sont trois quantités différentes.
- **Mac Intel :** conserver sa place de comparaison secondaire ; ne pas promettre un comportement plus correct ou plus rapide simplement parce que le CPU est Intel.

### Ajouts utiles

- Tableau runtime minimal : capabilities, sources chargées, programme lié, paramètres effectifs de texture, formats obtenus, taille et encodage framebuffer.
- Décalage frame/mesures du CSV et colonnes codées en dur ; distinction entre scénario temporel et replay déterministe.
- Mise en garde sur `SHADOW_DEPTH_PASS_ENABLED` qui coupe aussi les données de cascades au démarrage.
- Précondition d’initialisation des textures et différence LINEAR manuel / comparaison shadow pour les tests de correction.
- Chemins d’abandon de buffers natifs dans la file d’upload et chemins d’échec de compilation/link.
- Limites du fallback FBO/format et absence de tests Java compilés dans la configuration Gradle actuelle.
- Graphe d’ownership restreint comme exercice pédagogique, avant toute abstraction plus ambitieuse.

**Conclusion de la contre-review.** Le rapport original reste utile et largement exploitable comme carte du renderer. Il ne doit pas servir tel quel de justification à une réécriture, à la suppression de B/C ou à une attribution des performances au matériel. Le prochain progrès doit être une petite preuve graphique conforme, puis une mesure correctement identifiée. Le premier refactor peut ensuite porter sur un ownership limité, choisi, expliqué et implémenté par le développeur.
