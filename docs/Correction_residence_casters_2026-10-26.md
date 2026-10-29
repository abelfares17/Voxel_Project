# Casters déchargés : résidence directionnelle et frontières de chunks

## Résultat

Le monde conserve maintenant des chunks supplémentaires **vers la lumière active**, indépendamment du rayon d'affichage. La fusion corrigée reste activée. Direction solaire/lunaire, cascades, fit des casters, biais et culling des faces sont inchangés.

La correction traite la géométrie manquante et les meshes de frontière périmés. Elle **ne promet pas des ombres infinies** : portée et nombre de chunks sont plafonnés. Le cas montagne a été vérifié sur les données sauvegardées ; sa validation visuelle interactive reste à faire.

Coût ABBA sur M2 Pro : **80,12 → 75,26 FPS (−6,08 %)** ; tailles des buffers de meshes **465,87 → 739,65 MB** décimaux. C'est un compromis de correction visuelle, pas une optimisation FPS.

## Diagnostic vérifié

Le moteur déchargeait les chunks au-delà du rayon visible + 2 même si leur géométrie occultait des récepteurs visibles. Le fit CSM ne peut pas recréer un caster absent.

Test CPU en lecture seule de World 10, soleil à **17 h 19** :

- rayon depuis `(990,100,635)` vers le soleil ;
- première intersection solide à **X=774**, sortie de cet intervalle solide à **X=729**, chunk **X=45** ;
- joueur dans le chunk `(64,39)`, rayon visible 15 : ancienne limite de rétention ouest **X=47** ;
- le versant exposé au soleil n'était donc pas conservé ; le nouveau plan inclut X=44..48, Z=39..42.

Ce rayon est un échantillon reproductible, pas une capture pixel-identique de toutes les ombres de la scène.

La shadow pass utilise `GL_BACK`. Vue depuis la lumière, la surface tournée vers elle fournit normalement la première profondeur. Si ce versant disparaît, les morceaux restants et les coupes artificielles aux frontières ne représentent plus la montagne complète. Cela explique l'impression de « mauvaises faces » projetant l'ombre. **Inverser le culling ne restaure pas la montagne** : aucune modification du winding/culling n'a été utilisée comme palliatif.

Deux problèmes associés ont été vérifiés : les voisins chargés depuis disque ou déchargés ne déclenchaient pas systématiquement de remeshing ; un résultat asynchrone pouvait arriver après un changement de voisinage ou le retrait du chunk.

Enfin, `ChunkCompressor.decompress` utilisait un seul `GZIPInputStream.read`, qui ne garantit pas de remplir le tableau. Il utilise désormais `readNBytes`, pour ne pas traiter une lecture courte comme un chargement raté puis régénérer le terrain. Round-trip peu compressible et lecture du chunk montagne : PASS.

## Changements

### Plan de résidence

`ShadowCasterResidency.plan` balaie horizontalement le carré visible vers SUN/MOON, intersecte conservativement les emprises des chunks, puis trie les candidats par distance au carré visible avec ordre déterministe. NONE ne demande aucune extension.

Le déplacement est borné par `HEIGHT / tan(élévation)` puis par la portée configurée. HEIGHT=256 représente la tranche verticale du monde, pas la hauteur mesurée de chaque montagne. Un budget limite ensuite les chunks supplémentaires. La caméra peut tourner sans changer ce plan ; position joueur et direction de lumière le font évoluer. Un seuil angulaire d'environ 0,286° évite de refaire la sélection pour chaque infime déplacement solaire.

`World.planCasters`, `loadCasters`, `update` et `unloadFarChunks` chargent progressivement les candidats et les exemptent du déchargement normal. Ils utilisent pour l'instant les données et meshes habituels, **pas des proxies ou des meshes shadow-only**. `VoxelRenderer.renderVisibleChunks` conserve son carré visible : la distance d'affichage ne change pas.

### Frontières, lifetime et sauvegarde

- `World.addChunk` invalide les voisins après insertion, y compris après lecture disque ; le retrait invalide les voisins survivants. Une édition en bordure demande aussi leur remeshing.
- `Chunk` distingue mesh utilisable, mesh à jour (`isMeshCurrent`) et chunk retiré définitivement (`retire`), différent d'un remplacement de buffers.
- `MeshWorkerManager` associe une révision aux résultats, rejette les versions périmées avant allocation GL et libère les résultats arrivés après retrait/arrêt. Un ancien mesh peut rester dessiné brièvement pendant le remeshing ; il n'est pas compté comme à jour.
- Les chunks sales sont sauvegardés avant éviction, sauf dans le harnais. En cas d'échec d'écriture, ils sont conservés et l'erreur signalée : la rétention peut alors dépasser son plafond pour ne pas perdre les modifications.
- La sauvegarde à l'éviction est synchrone et peut créer des à-coups. Ce coût n'est pas mesuré par le harnais, qui interdit les écritures.

### Chargement initial

`LoadingState` précharge autour de la position sauvegardée plutôt qu'à l'origine. `Game` initialise directement le joueur à cette position puis restaure son orientation. Le chargement attend les meshes visibles **et** les casters sélectionnés, sans faire avancer le temps. Le harnais conserve sa pose forcée et son interdiction de sauvegarde.

### Réglages et HUD

| Propriété JVM | Défaut | Signification |
|---|---:|---|
| `voxel.shadowCasterResidency` | `true` | Résidence supplémentaire ; `false` pour référence A/B |
| `voxel.shadowCasterReach` | `256` | Blocs supplémentaires **au-delà du carré visible**, plafond 1024 |
| `voxel.shadowCasterBudget` | `640` | Maximum de chunks demandés hors carré visible, plafond 2048 |
| `voxel.shadowCasterLoadsPerUpdate` | `4` | Maximum de nouveaux casters chargés par update, entre 1 et 32 |

La dernière limite borne le nombre de chargements, pas leur temps CPU. Pour rayon 15, le total résident est borné par le carré de rétention 35×35 plus 640 extras, avec recouvrement partiel, sauf échec de sauvegarde. Ce n'est pas un plafond mémoire en octets.

F3 affiche `Casters: prêts/demandés extra`, `Reach`, `RANGE LIMIT` et `BUDGET LIMIT` :

- prêts < demandés : chargement/remeshing en cours ;
- RANGE LIMIT : la borne physique conservative dépasse la portée choisie, sans prouver qu'une montagne réelle manque ;
- BUDGET LIMIT : certains candidats du balayage sont exclus par le budget.

Les CSV ajoutent `shadowCasterResidency`, `residentChunks`, `casterRequested`, `casterReady`, `casterCandidates`, `casterReach`, `casterReachLimited`, `casterBudgetLimited`.

## Tests exécutés

| Test | Résultat / couverture |
|---|---|
| `installDist` | PASS |
| `ShadowCasterResidencyTest` | PASS : soleil/lune/NONE, sens de balayage, plafonds, déterminisme, lecture/rayon montagne, compression |
| `ShadowResidencyGlTest` | PASS : chargement loin de l'origine, rétention hors rayon visible, sauvegarde/relecture après éviction, voisin disque, rejet d'un résultat périmé, fermeture de frontière après déchargement, retrait sans résurrection GPU |
| `MeshBoundsGlTest` fusion ON | PASS : remplacement/destruction VAO/VBO/EBO, re-upload, mesh vide |
| `ShadowMeshTest` | PASS : orientations, fusion, trous, upload/destruction |
| `ShadowCasterVolumeTest` | PASS : 10 000 cas, préservation XY, couverture en profondeur |
| `ShadowCasterDepthGlTest` | PASS : caster distant occultant le récepteur dans near/mid/far, shaders depth de production |
| `PlayerOrientationTest` | PASS : pose sauvegardée, Game, transitions souris et orientation |
| `DebugHudCaptureTest` | PASS : F3/F2, PNG réel, notification temporaire et échec d'écriture attendu |

Tests GL en fenêtre cachée ; tests de streaming/écriture dans un répertoire temporaire. Aucun de ces tests n'écrit dans les sauvegardes utilisateur. Le test montagne lit les fichiers existants ; il ne remplace pas le contrôle visuel interactif.

Depuis la racine, reproduction des deux nouveaux tests :

```sh
./gradlew --offline installDist
java -cp 'build/install/TOB_Voxel_Project/lib/*' tools/tests/ShadowCasterResidencyTest.java

repo="$PWD"
testdir=$(mktemp -d /tmp/caster-residency.XXXXXX)
(
  cd "$testdir"
  java -XstartOnFirstThread \
    -Dvoxel.shadowCasterReach=64 -Dvoxel.shadowCasterBudget=40 \
    -cp "$repo/build/install/TOB_Voxel_Project/lib/*" \
    "$repo/tools/tests/ShadowResidencyGlTest.java"
)
```

## Benchmark : résidence OFF/ON, fusion ON dans les deux cas

[Résultats](../logs/caster_suite_u_hiwbk0/results.json) et [agrégat ABBA](../logs/caster_suite_u_hiwbk0/aggregate.json).

M2 Pro, 2268×1382 ; quatre runs de 20 s, analyse 3 < t ≤ 19 s. Caméra/soleil mobiles, fenêtre non focalisée/non minimisée, curseur libre. Aucun readback d'image ou timer query. Harnais à l'origine du monde 10, départ à 08 h : **ce n'est pas une mesure au pied de la montagne au coucher du soleil**.

| Run | Résidence | FPS | Frame moyenne | P95 |
|---|---|---:|---:|---:|
| A1 | OFF | 80,20 | 12,47 ms | 14,2 ms |
| B1 | ON | 75,20 | 13,30 ms | 15,5 ms |
| B2 | ON | 75,31 | 13,28 ms | 15,4 ms |
| A2 | OFF | 80,05 | 12,49 ms | 14,4 ms |

Agrégation frames/durée : **80,12 → 75,26 FPS (−6,08 %)**, environ **+0,81 ms/frame**. Chunks résidents moyens ≈ 1006 → 1607 ; tailles VBO/EBO ≈ 465,87 → 739,65 MB (+273,78 MB). Ce compteur ne mesure pas toute la RAM/native ni la mémoire physique du driver.

En ON : 640 casters demandés, ~638,5 à jour en moyenne, maximum 78 en attente lors d'un mouvement/changement de plan. Le monde n'est pas artificiellement stabilisé pendant toute la mesure. Au matin, ~722 candidats dépassent le budget 640. Les frontières de meshes diffèrent entre OFF et ON ; le nombre de vertices caméra n'est donc pas strictement identique.

La remise au propre finale du spawn et du wrapper de préchargement bloquant a été compilée/testée après ces mesures ; l'algorithme de résidence mesuré est inchangé. Cette série ne mesure ni les sauvegardes à l'éviction, ni le pire cas au soleil rasant, ni un cycle complet SUN→NONE→MOON en streaming. Ne pas annoncer un coût universel de 6 % ou combiner ce chiffre avec l'ancien gain de fusion pour prétendre à une nouvelle comparaison fusion ON/OFF.

Reproduction : `python3 tools/benchmark_caster_residency.py` après `installDist`.

## Protocole visuel conseillé

1. Lancer le jeu normal, charger World 10 à heure fixe :

   ```sh
   JAVA_OPTS='-Dvoxel.startHour=17.3166666667 -Dvoxel.pauseTime=true' \
     build/install/TOB_Voxel_Project/bin/TOB_Voxel_Project
   ```

2. Activer F3, reprendre la scène de la grande ombre vers X=990..1040, Z=627..640. Attendre `prêts == demandés` à pose fixe. Garder F5 sur la couverture CSM corrigée et F8 sur automatique.
3. Alterner F4 mode 1 (facteur shadow) et mode 5 (cascades unies). Reculer lentement en s'éloignant de la montagne, puis s'arrêter et attendre le streaming. Le versant X≈729 doit rester disponible au-delà de l'ancien seuil ; l'ombre ne doit plus se tronquer au même emplacement qu'avant.
4. Comparer à même heure/pose avec `-Dvoxel.shadowCasterResidency=false`. Cela rétablit l'ancienne politique de résidence, mais conserve les corrections de frontières et décompression : ce n'est pas un retour binaire complet à l'ancien moteur. **F5 ne bascule pas la résidence**, seulement le fit CSM.
5. Si une coupure persiste après stabilisation, capturer F3/F4 et noter les limites. Tester séparément portée 384 et budget 1024 pour voir si la coupure recule ; ne pas généraliser ces valeurs sans mesurer mémoire/FPS.
6. Reprendre le temps avec F6, observer coucher et nuit ; tester aussi la lune à heure fixe. Le changement d'astre peut demander une nouvelle zone et donc du chargement. Ce transitoire n'est pas nécessairement un défaut des matrices CSM.
7. Capturer avec F2 hors benchmark. Ne pas changer simultanément biais, normal-offset, fusion et résidence dans cette comparaison.

Si les casters utiles dépassent régulièrement le budget, la prochaine solution est une représentation lointaine moins coûteuse, à qualifier séparément. Agrandir indéfiniment les meshes détaillés, raccourcir artificiellement les ombres ou inverser les faces rendues ne sont pas des corrections générales.
