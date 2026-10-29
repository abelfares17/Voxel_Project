# Correction de couverture des casters CSM — 25 octobre 2026

## Ce qui est corrigé

Le CSM direct conserve les emprises XY stabilisées calculées pour les récepteurs, mais étend le plan proche lumineux vers le soleil pour inclure les meshes chargés et prêts dont les AABB projetées recouvrent cette emprise. Le test XY est conservateur ; les objets entièrement derrière le volume récepteur sont ignorés. Le plan lointain original est conservé. La transformation finale modifie uniquement la ligne Z de la matrice orthographique, pas les coordonnées XY des texels.

Le rayon CPU `2.5*R+32` est supprimé dans ce chemin corrigé. Le frustum lumineux **étendu** conserve le culling spatial. Les meshes sont uploadés avant les ombres, puis conservés pendant la passe voxel ; une nouvelle géométrie ne peut plus être installée entre ces deux passes normales. Les cascades directes sont rendues à chaque frame, y compris soleil pausé, pour prendre en compte les remeshings et variations de couverture.

Le biais normalisé est multiplié par ancienIntervalleZ/nouvelIntervalleZ : son déplacement équivalent dans le monde est conservé, afin de ne pas amplifier le peter panning en étendant la profondeur. Le normal-offset et le filtre PCF sont inchangés. La fusion des meshes shadow reste ON.

Implémentation : `ShadowCasterVolume` (projection conservative des AABB et extension du near), `ShadowRenderer::renderOffMode` (collecte et application), `World::renderDepth` (rayon désactivé par le caller corrigé), `Game::render` (uploads avant ombres), `VoxelRenderer` et fragment shader (debug et compensation du biais).

La correction est ON par défaut pour le **CSM direct**. `-Dvoxel.casterCoverage=false` sélectionne l’ancien volume au lancement. F5 permet de changer à chaud. Les chemins leapfrog dormants ne sont pas refactorés : ce correctif ne les qualifie pas.

## Tests exécutés

- Build `./gradlew --offline installDist` : PASS.
- `ShadowCasterVolumeTest` : caster distant auparavant rejeté, candidat latéral/hors récepteurs ignoré, préservation XY/far et biais, 10 000 cas plus 1 000 bases tournées : PASS.
- `ShadowCasterDepthGlTest` : shaders depth de production et FBO depth32F réel ; un caster à Z=-200 est absent avec trois projections originales, présent et occultant le récepteur après ajustement : PASS.
- Harnais World 10 mobile avec `voxel.validateShadowCulling=true` : **12 comparaisons exactes de 4 194 304 texels**, culling corrigé contre tous les chunks prêts sans culling CPU, PASS. Console temporaire `/tmp/caster_coverage_validation.txt`. Ce test utilise la même projection corrigée des deux côtés : il vérifie le culling, pas à lui seul le choix physique des limites de projection. Le test distant dédié couvre cette seconde propriété.
- Aucune mesure FPS annoncée : rendre des casters auparavant exclus peut augmenter le coût GPU. La validation avec readback n’est pas un benchmark.

Reproduction depuis la racine :

```sh
./gradlew --offline installDist
java -cp 'build/install/TOB_Voxel_Project/lib/*' tools/tests/ShadowCasterVolumeTest.java
java -XstartOnFirstThread -cp 'build/install/TOB_Voxel_Project/lib/*' tools/tests/ShadowCasterDepthGlTest.java
JAVA_OPTS='-Dvoxel.testHarness=true -Dvoxel.testStatic=false -Dvoxel.validateShadowCulling=true' build/install/TOB_Voxel_Project/bin/TOB_Voxel_Project
```

## Raccourcis pour le contrôle manuel

Lancer le jeu normal, **sans harnais**, lequel ignore les commandes manuelles. Sur macOS, utiliser Fn+F… si les touches sont attribuées aux commandes système.

| Touche | Effet |
|---|---|
| F2 | Capture de la frame terminée dans captures/ ; confirmation 3 secondes après enregistrement, message d'échec sinon |
| F3 | HUD en haut à gauche : monde, XYZ joueur, yaw/pitch en degrés ; ne modifie pas l'éclairage |
| Maj+F3 | Debug AO ON/OFF |
| F4 | Cycle des 8 modes shadow ci-dessous (de nouveau transmis au shader) |
| **F5** | Couverture des casters corrigée / legacy ; notification à l’écran |
| **F6** | Pause/reprise du soleil |
| **F8** | Cascade automatique → 0 proche → 1 moyenne → 2 lointaine → automatique |
| F7 | Alias existant de F8, pas un changement de filtre PCF |
| F9 | Biais normal → constant → zéro → doublé → normal |
| F10 | Normal-offset ON/OFF, indépendant de F9 |

F4 depuis le démarrage : 0 rendu normal ; 1 visibilité shadow en gris (sombre=occulté) ; 2 cascade colorée sur albedo ; 3 grille hash de diagnostic ; 4 fog en gris ; 5 cascades en couleurs unies ; 6 différence A/B leapfrog (non pertinent en direct) ; 7 éclairage avant fog ; puis retour à 0. Les modes 2 et 5 n’affichent pas le résultat ombré : alterner avec le mode 1 pour comparer une frontière à l’ombre.

Ne pas utiliser les anciens freezes F11/F12 pendant ce protocole. Ils figent des paramètres intermédiaires et rendent l’interprétation du mouvement caméra ou de l’ajustement final de profondeur ambiguë. Redémarrer si un freeze a été activé par inadvertance.

## Protocole montagne, environ cinq minutes

1. Rejoindre l’ombre problématique avec soleil bas, attendre le chargement des chunks, appuyer sur **F6**. Garder la position et l’orientation pour le premier A/B. Le soleil doit rester figé pendant les comparaisons.
2. En rendu normal et cascade automatique, alterner **F5**. Le mode corrigé doit conserver la grande ombre lorsque le mode legacy perd le caster. Capturer les deux états avec F2 si une différence subsiste.
3. Revenir au mode corrigé. Avec **F4 mode 1**, avancer/reculer et tourner lentement là où l’ombre disparaissait. Une grande région occultée ne doit plus devenir une zone éclairée simplement parce qu’elle passe dans near/mid.
4. Avec **F4 mode 5**, repérer les frontières ; repasser au mode 1. La densité/résolution des bords peut encore changer, mais pas la présence de la montagne comme obstacle.
5. À une pose où la région inspectée est couverte par plusieurs cartes, comparer **F8 : 0/1/2** en mode 1. La grande occultation doit rester cohérente dans leur zone commune. **Une cascade forcée hors de son emprise renvoie éclairé : ce n’est pas une preuve d’échec.** F8 force le choix, n’agrandit pas la carte ; le blend existant près des splits n’est pas neutralisé par ce diagnostic.
6. Revenir en cascade automatique et rendu normal, reprendre le soleil avec F6 et observer son coucher. Répéter au pied de la montagne, au milieu et au bout de son ombre.
7. Soleil figé, ajouter/supprimer un bloc exposé : vérifier que son ombre évolue sans devoir remettre le soleil en marche.

## Séparer les problèmes restants

- Si le **grand trou** disparaît avec F5 corrigé, le défaut de couverture est confirmé sur cette scène.
- Si seule une **fine ombre d’une marche** reste instable, comparer F9 zéro et F10 OFF séparément. Restaurer normal/ON ensuite. Ce correctif ne promet pas d’éliminer aliasing, normal-offset excessif ou différences de résolution.
- Si une grande coupure persiste : noter pose, heure, mode/cascade, captures, et si F5 change le résultat. Vérifier que la montagne est chargée et son mesh prêt. Les objets déchargés ne peuvent pas produire d’ombre.
- Ne pas augmenter le blur pour dissimuler un manque de géométrie. Ne pas conclure à un alignement parfait sur la seule comparaison de depth : la scène réelle signalée reste à contrôler manuellement.

Le fit par AABB favorise la correction : il peut inclure des casters inutiles et étendre davantage la profondeur que nécessaire. La performance et la stabilité lors du streaming pourront être mesurées ensuite, sans réintroduire un rayon qui exclut des casters valides.
