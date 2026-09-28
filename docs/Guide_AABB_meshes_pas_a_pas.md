# Prochaine optimisation : borner les meshes réellement rendus

Guide d'implémentation manuelle — 28 septembre 2026.

**Statut : proposition vérifiée dans le code, non implémentée et non benchmarkée.** Cette intervention ne change aucun fichier du moteur. Les mesures citées proviennent de la [section 16 du guide de performance](Guide_performance_CSM_Retina.md#16-culling-des-cascades-et-mesures-gpu--28-septembre-2026).

## 1. Décision : une seule piste principale

Remplacer les boîtes de culling surdimensionnées par une **AABB du mesh effectivement uploadé**. Une AABB est une boîte alignée sur les axes : trois coordonnées minimales et trois maximales.

Faire deux petites expériences successives :

1. bornes resserrées pour le culling des **ombres** ;
2. bornes resserrées pour le culling de la **caméra**.

Ne pas toucher aux triangles, shaders, textures, biais, matrices de cascades ou cadence solaire. Ne pas lancer en parallèle un chantier EBO, greedy meshing ou cache de shadow maps.

### Ce qui est prouvé et ce qui ne l'est pas

| Niveau | Conclusion |
|---|---|
| FACTUEL — haute confiance | `VoxelRenderer::renderVisibleChunks()` utilise une boîte de hauteur `[-32, 288]` pour chaque chunk. |
| FACTUEL — haute confiance | `World::intersectsShadowFrustum()` utilise `[0, Chunk.HEIGHT]`, soit `[0, 256]`, avec une marge de 0,01. |
| FACTUEL — haute confiance | `MeshBuilder::generateMeshData()` connaît les positions exactes envoyées au GPU. Elles peuvent donc servir à calculer les bornes sans relire le monde. |
| FACTUEL — mesures précédentes | Avec le culling lumineux actuel : environ 6,46 millions de vertices voxel et 31,89 millions de vertices shadow soumis par frame sur le parcours testé. |
| HYPOTHÈSE — confiance moyenne | Des boîtes plus petites permettront de rejeter davantage de meshes entièrement hors des volumes de rendu. Le potentiel dépend de leur occupation et des vues. |
| NON PROUVÉ | La proportion de rejets supplémentaires, le gain GPU ou les FPS obtenus. Une boîte plus petite peut tout à fait rester visible dans toutes les vues testées. |
| RECOMMANDATION | Essayer cette réduction de soumissions avant de changer le format des meshes : c'est local, réversible et vérifiable sans altérer l'image attendue. |

Le gain précédent de 41,3 à 45,1 FPS concerne le **frustum lumineux ajouté**, pas les bornes resserrées proposées ici. Ne pas réutiliser ce pourcentage comme prédiction.

### Pourquoi pas immédiatement des indices ou du greedy meshing ?

Le code émet bien six vertices par quad, avec duplication de `v0` et `v2`. Un EBO pourrait ramener le stockage à quatre vertices par quad, mais impose de modifier upload, VAO, ownership, cleanup et les deux chemins de draw. Il ne garantit pas un gain de FPS équivalent à la réduction de stockage. Le greedy meshing doit en plus préserver UV de l'atlas, normales et AO. Ce sont des pistes ultérieures, pas des prérequis de ce guide.

## 2. Comprendre le trajet à modifier

```text
Thread worker
  MeshWorkerManager.processMeshes()
    → MeshBuilder.generateMeshData(chunk, world)
        → FloatBuffer interleavé + vertexCount + bornes locales à ajouter
    → MeshData
    → MeshUploadQueue / ConcurrentLinkedQueue

Thread OpenGL
  MeshWorkerManager.uploadMeshesIfAny()
    → upload VBO + configuration VAO
    → Chunk.setMesh(..., bornes du même MeshData)
    → MeshData.free() libère uniquement le buffer natif

Rendu
  VoxelRenderer.renderVisibleChunks()
    → bornes du mesh courant + origine du chunk → frustum caméra
  ShadowRenderer.renderCascadeDepth()
    → World.renderDepth(..., lightFrustum)
    → mêmes bornes + origine du chunk → frustum de chaque lumière
```

**Invariant central : les bornes appartiennent à la même version que le VAO/VBO utilisé par le draw.** Elles ne décrivent pas forcément l'état le plus récent des blocs pendant un remeshing.

Fichiers à ouvrir, dans cet ordre :

| Fichier relatif à `src/main/java/engine/` | Méthodes / données à examiner |
|---|---|
| `render/MeshBuilder.java` | `generateMeshData`, `buildChunkMesh`, `putVertex`, `STRIDE` |
| `render/MeshData.java` | constructeur, champs `final`, `free` |
| `render/MeshWorkerManager.java` | worker de `processMeshes`, `uploadMeshesIfAny` |
| `world/Chunk.java` | `setMesh`, `cleanup`, `isReady`, `setBlock` |
| `world/World.java` | `renderDepth`, `intersectsShadowFrustum` |
| `render/renderer/ShadowRenderer.java` | `renderCascadeDepth`, `validateCascadeDepth` |
| `render/renderer/VoxelRenderer.java` | `renderVisibleChunks` |
| `core/config/RenderConfig.java` | réglages A/B |
| `test/PerfLogger.java`, `render/GpuPassTimer.java` | métadonnées et compteurs |

Il existe un second producteur, `MeshBuilder::buildChunkMesh()`, qui fait directement l'upload. Aucun appel depuis `src/main/java` n'a été trouvé lors de cette lecture, mais sa déclaration existe. Ne le supprime pas dans ce chantier ; son comportement doit rester cohérent si un appel externe ou futur l'utilise.

## 3. Étape 0 — figer le point de départ

### À faire

1. Conserver un point de restauration du working tree actuel dans ton IDE ou Git. Il contient déjà de nombreuses modifications ; ne pas faire un reset global ni attribuer tous les changements à ce chantier.
2. Ne plus changer les réglages pendant la comparaison : filtre 1, stage 6, trois cascades 2048², culling lumineux actuel actif, fenêtre 75 %, Retina actif.
3. Garder le même monde de test et le même spawn. Préserver les sauvegardes ; le harnais actuel saute leur sauvegarde à la sortie.
4. Construire et faire un run témoin mobile, puis noter les chemins imprimés et la configuration.

Commandes **déjà fonctionnelles dans le code actuel** :

```sh
./gradlew --offline installDist
JAVA_OPTS='-Dvoxel.testHarness=true -Dvoxel.testStatic=false -Dvoxel.windowScale=0.75 -Dvoxel.shadowFrustumCulling=true -Dvoxel.gpuTiming=false' \
  build/install/TOB_Voxel_Project/bin/TOB_Voxel_Project
```

`--offline` suppose les dépendances Gradle déjà présentes. Exécuter depuis la racine du dépôt pour retrouver `saves/` et `logs/`.

### Vérification / arrêt

La console doit annoncer `movement timeline`, `paused=false`, puis une heure solaire croissante. Ne pas utiliser `testStatic=true` pour cette validation de performances. Si le monde ne charge pas ou que les dimensions diffèrent, régler ce problème avant de comparer.

## 4. Étape 1 — définir des données simples et leur contrat

### Choix recommandé

Créer une petite valeur immutable `MeshBounds` dans `engine.render`, avec six floats :

```text
minX, minY, minZ, maxX, maxY, maxZ
```

Un record Java convient. Ce n'est pas une nouvelle couche de rendu : il encode seulement des bornes **locales** d'un mesh. Ne pas lui donner un handle GL, un `cleanup`, une référence au monde ou un calcul de caméra.

Contrat à écrire avant de coder :

- Les six coordonnées sont finies ; `min <= max` sur chaque axe.
- Une épaisseur nulle est autorisée : un mesh plan peut avoir `minY == maxY`.
- Tout vertex contenu dans le buffer uploadé est dans cette boîte, bornes incluses.
- Le padding de culling n'est pas incorporé aux données ; il sera ajouté au test.
- Pour cette première version, utiliser **`null` = aucune borne validée disponible**. Cela couvre le mesh vide et les anciens appels non encore renseignés. Un mesh vide est identifié par `vertexCount == 0`, pas par une AABB artificielle à l'origine.
- Si `vertexCount > 0` mais les bornes sont absentes, **revenir à la grande boîte existante**, jamais considérer le mesh invisible.

Ne pas ajouter un `AtomicReference` ou un nouvel objet générique « GPU mesh » juste pour ce changement. Le remplacement complet du mesh est déjà effectué sur le thread OpenGL ; les autres threads ne doivent pas modifier les bornes installées.

### Vérification / arrêt

Tu dois pouvoir expliquer cette différence :

> `MeshData.bounds` décrit le résultat en attente d'upload ; `Chunk.meshBounds` décrit le résultat actuellement installé.

Si tu envisages de mettre à jour `Chunk.meshBounds` depuis `setBlock()` ou depuis le worker avant l'upload, arrêter : ce serait mélanger deux versions.

## 5. Étape 2 — calculer les bornes sans casser le buffer

### Méthode recommandée pour la première version

Dans `MeshBuilder::generateMeshData()`, après l'écriture et le `flip()` du buffer, calculer les minima/maxima sur les **positions de ce buffer exact**. C'est un passage CPU supplémentaire au meshing, pas à chaque frame. Mesurer ensuite son coût ; ne pas optimiser prématurément ce petit calcul.

Layout actuel à respecter :

| Composante | Offset en floats | Offset en octets |
|---|---:|---:|
| Position XYZ | 0, 1, 2 | 0, 4, 8 |
| UV | 3, 4 | 12, 16 |
| Normale XYZ | 5, 6, 7 | 20, 24, 28 |
| AO | 8 | 32 |
| Stride | **9 floats** | **36 octets** |

`MeshBuilder.STRIDE` est en **octets**. Pour un `FloatBuffer`, calculer `floatsPerVertex = STRIDE / Float.BYTES`, pas `i += STRIDE`.

Algorithme à implémenter dans un helper CPU testable :

1. Vérifier le compte non négatif et le layout attendu.
2. Si `vertexCount == 0`, retourner l'absence de bornes, sans lire un élément.
3. Mémoriser `base = buffer.position()` et `limit = buffer.limit()`.
4. Vérifier que les `vertexCount × 9` floats attendus sont disponibles ; calculer le produit avec un entier assez large pour détecter un overflow. Dans le producteur actuel, le nombre restant doit être exactement celui attendu.
5. Initialiser les trois minima à `+infini`, les maxima à `−infini`.
6. Pour chaque vertex `i`, lire XYZ aux indices absolus `base + i × 9 + {0,1,2}`.
7. Rejeter explicitement une position NaN/infinie dans le helper : ne pas produire silencieusement des bornes inutilisables.
8. Mettre à jour les six extrema ; construire la valeur immutable.
9. Vérifier que position et limite du buffer n'ont pas changé.

Les lectures absolues `get(index)` n'avancent pas la position. Autre possibilité : lire une vue `duplicate()`, mais cette vue partage le stockage ; ne jamais la libérer séparément. [Contrat Java 17 de `FloatBuffer`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/FloatBuffer.html).

**Ne pas rappeler `flip()` après des lectures absolues.** Le buffer déjà flippé aurait une position zéro ; un second flip lui donnerait une limite zéro, donc aucun vertex à uploader.

### Pourquoi ne pas utiliser la hauteur du terrain ou `findSurfaceY()` ?

Ces valeurs décrivent les blocs courants, pas nécessairement les triangles uploadés. Elles deviennent fragiles avec grottes, trous, ajout de blocs en hauteur et remeshing concurrent. Le vertex buffer est l'autorité pertinente pour le culling du mesh. Ne pas estimer les bornes depuis la seed ou les constantes de `WorldGenerator`.

### Tests immédiats

Créer un test autonome, par exemple `tools/tests/MeshBoundsTest.java`, dans le package donnant accès au helper. Ne pas attendre d'avoir modifié le renderer.

| Cas | Données / vérification attendue |
|---|---|
| Buffer vide | Compte zéro → absence de bornes ; aucune lecture. |
| Une position | Min = max ; aucun rejet au motif d'une épaisseur nulle. |
| Plusieurs positions | `(1,2,3)`, `(15,10,8)`, `(4,-1,16)` → min `(1,-1,3)`, max `(15,10,16)`. Le Y négatif vérifie le helper général, pas une promesse du mesher actuel. |
| Attributs sentinelles | UV/normales/AO très différents des positions : aucun de ces nombres ne doit influencer les bornes. |
| Buffer à position non nulle | Lire depuis cette position, pas depuis zéro ; ne pas lire les données de préfixe. |
| Bornes de stockage | Capacité supérieure à limite : ne pas incorporer la partie non utilisée. |
| Mauvais compte | Buffer trop court → erreur explicite, pas une lecture hors limites non expliquée. |
| NaN / infini | Position invalide → erreur explicite. |
| État NIO | `position` et `limit` strictement identiques avant/après chaque calcul. |
| Containment | Tous les vertices du buffer satisfont les six inégalités inclusives. |

**Point d'arrêt :** ne pas continuer tant que le layout et la préservation du buffer ne sont pas testés.

## 6. Étape 3 — transporter les bornes avec le résultat de meshing

### Dans `MeshData`

1. Ajouter un champ `final` contenant les bornes locales.
2. Étendre le constructeur pour recevoir ce résultat avec `vertexBuffer`, `vertexCount`, `chunk`.
3. Mettre à jour l'appel dans `MeshBuilder::generateMeshData()`.
4. Ne changer ni le propriétaire du buffer ni `free()` : les bornes sont une valeur Java, elles ne nécessitent pas de libération native.

Traiter aussi le nouveau chemin d'erreur : si le calcul/contrôle des bounds échoue après `memAllocFloat` mais avant la construction et la mise en file du résultat, le producteur doit libérer le buffer qu'il possède encore. Après transfert réussi, ne pas le libérer à cet endroit : `MeshData.free()` reste la libération normale côté consommateur. Dans les tests avec buffers natifs, utiliser également un `finally` pour les libérer. Une validation supplémentaire ne doit pas réintroduire une fuite ou une double libération.

### Dans `MeshWorkerManager`

La file concurrente transporte déjà le résultat complet. Ne pas créer une seconde file pour les bounds et ne pas ajouter un champ mutable partagé recalculé au rendu.

Dans `uploadMeshesIfAny()`, transmettre les bornes du **même `MeshData`** au moment d'installer le VAO/VBO. Ne pas les recalculer depuis le monde à cet endroit. Ne pas conserver le `FloatBuffer` dans le chunk après `data.free()`.

### Vérification

Faire un passage en mode diagnostic : pour quelques uploads, noter coordonnées du chunk, vertexCount et les six bornes. Vérifier la plausibilité et le containment, puis retirer les impressions par chunk avant les benchmarks. Cela vérifie le transport, pas encore le gain.

**Ne modifier aucun test de frustum à cette étape.** Le rendu doit être inchangé parce que personne ne consomme encore les nouvelles données.

## 7. Étape 4 — installer et invalider les bounds avec le mesh GPU

Dans `Chunk` :

1. Ajouter un champ privé pour les bornes installées et un getter en lecture seule.
2. Étendre `setMesh` avec les bornes du nouveau mesh.
3. Conserver les règles existantes de destruction des anciens VAO/VBO.
4. Installer handles, nombre de vertices et bornes dans la même méthode exécutée sur le thread OpenGL.
5. Positionner `meshDirty = false` **après** cette installation.
6. Dans `cleanup()`, remettre les bornes à l'état absent, en même temps que handles/count.
7. Mettre à jour les deux producteurs : `MeshWorkerManager::uploadMeshesIfAny()` et le chemin direct `MeshBuilder::buildChunkMesh()`.

Si tu conserves la signature historique `setMesh(vao, vbo, count)` comme overload, elle doit installer des bornes **absentes**, pas conserver celles de l'ancien mesh. Le fallback large préservera le rendu. Préférer néanmoins fournir les bornes dans les deux producteurs connus.

Ne pas calculer de bounds dans `setBlock()`. Garder les anciennes données jusqu'au remplacement ; `isReady()` continue actuellement à refuser un chunk dirty. Ne pas modifier cette politique dans le même chantier.

### Deux subtilités à ne pas masquer

- Plusieurs champs `volatile` ne constituent pas une transaction atomique. Le contrat utilisé ici est que l'installation et les draws s'exécutent sur le même thread OpenGL, sans draw au milieu de `setMesh`. Ne pas prétendre avoir rendu tout le meshing concurrent thread-safe.
- Le code existant peut produire des résultats périmés si le monde évolue pendant une tâche. Des bounds calculées depuis **son propre buffer** restent cohérentes avec ce mesh, même si le mesh est périmé. Le versionnement des tâches est un sujet distinct. Si tes tests révèlent des blocs absents même avec les flags OFF, ne pas « corriger » cela en gonflant arbitrairement l'AABB.

### Tests de remplacement

Sur un monde de test jetable, vérifier successivement : mesh bas → ajout d'un bloc haut → upload → retrait du bloc haut → upload → unload/reload. Les bornes doivent évoluer avec chaque mesh installé, pas avant. Faire également grand mesh → petit mesh : aucun ancien maximum ne doit rester mémorisé.

Pour un mesh entièrement vide, le code actuel `Chunk::isReady()` retourne faux ; certaines fonctions de chargement attendent `isReady()`. Tester le helper et l'installation du résultat vide isolément. **Ne pas utiliser un monde totalement vide pour le benchmark principal sans avoir clarifié ce comportement existant**, et ne pas refactorer le chargement dans ce chantier.

## 8. Étape 5 — préparer deux interrupteurs indépendants

Ajouter dans `RenderConfig` deux flags lus au démarrage. Noms proposés :

```text
voxel.tightShadowBounds
voxel.tightCameraBounds
```

**Ces propriétés n'existent pas encore.** Avant leur implémentation, le moteur se lancera mais ignorera ces deux flags : ces commandes n'activeront donc pas les bornes resserrées.

Pendant le développement, leurs valeurs par défaut doivent être **false**. Calculer/transporter les bounds même quand les flags sont OFF : les runs A/B auront alors la même fabrication de meshes. Le témoin initial de l'étape 0 permettra séparément de vérifier le coût ajouté au meshing.

| Variante | Frustum lumineux existant | Tight shadow | Tight camera | Ce qu'elle isole |
|---|---|---|---|---|
| A | ON | OFF | OFF | Référence actuelle : boîtes larges. |
| S | ON | ON | OFF | Effet des bounds shadow uniquement. |
| C | ON | OFF | ON | Effet des bounds caméra uniquement. |
| B | ON | ON | ON | Effet combiné, après validation séparée. |

Ne pas prendre `voxel.shadowFrustumCulling=false` comme référence A : cela retirerait aussi l'optimisation précédente.

Ajouter les deux états à la console de début de run et aux métadonnées `PerfLogger` et `GpuPassTimer`. Ne pas les déduire des FPS. Garder le sens existant des compteurs : vertices **soumis**, pas vertices uniques ni fragments visibles.

## 9. Étape 6 — activer les bornes réelles dans les ombres

### Modification localisée

Dans `World::renderDepth(..., lightFrustum)` :

1. Conserver le test de disponibilité du chunk et le filtre de distance existant.
2. Garder `worldX = chunkX × WIDTH`, `worldZ = chunkZ × DEPTH` comme origine du modèle.
3. Si `lightFrustum == null`, ne pas faire ce test : le chemin de référence du validateur doit rester sans culling lumineux.
4. Si le flag tight shadow est OFF ou les bornes absentes, utiliser exactement le helper actuel de boîte complète.
5. Sinon, construire les six coordonnées monde depuis les bornes locales et l'origine du chunk, ajouter le padding de 0,01, puis appeler le `testAab` du **frustum lumineux**.
6. Si rejet, sauter le draw ; sinon, rendre exactement comme avant et incrémenter les compteurs au même endroit.

Formules, avec `ox = chunkX × 16`, `oz = chunkZ × 16`, `p = 0,01` :

```text
minWorld = (ox + minLocalX - p, minLocalY - p, oz + minLocalZ - p)
maxWorld = (ox + maxLocalX + p, maxLocalY + p, oz + maxLocalZ + p)
```

Ne pas créer deux `Vector3f` pour chaque chunk à chaque cascade : les six scalaires suffisent à `testAab`. Le frustum est toujours recalculé à partir de la matrice réellement utilisée par la cascade, pas mis en cache selon la position seule.

**Piège critique :** le modèle doit rester `translation(ox, 0, oz)`. Le minimum de la boîte n'est pas l'origine du chunk. Si `minLocalX = 3`, utiliser `ox + 3` comme translation déplacerait les vertices une seconde fois.

Garder la version actuelle de `intersectsShadowFrustum(frustum, worldX, worldZ)` comme fallback et pour ses tests existants. Ajouter le chemin tight sans supprimer immédiatement ce contrôle de référence.

### Validation avant toute mesure

Lancer le validateur existant, tight shadow ON et tight camera OFF :

```sh
JAVA_OPTS='-Dvoxel.testHarness=true -Dvoxel.testStatic=false -Dvoxel.windowScale=0.75 -Dvoxel.shadowFrustumCulling=true -Dvoxel.tightShadowBounds=true -Dvoxel.tightCameraBounds=false -Dvoxel.validateShadowCulling=true' \
  build/install/TOB_Voxel_Project/bin/TOB_Voxel_Project
```

Reconstruire avant ce lancement après chaque changement Java. Le validateur rend une cascade avec le culling choisi, puis sans frustum lumineux, avec les mêmes matrices et meshes, et compare les depth. Vérifier que son appel de référence à `World.renderDepth(shader, center, radius)` continue à transmettre un frustum nul.

Attendu : comparaisons PASS sur les trois cascades aux quatre moments. **Au premier texel différent, ne pas mesurer les performances.** Identifier la cascade, le chunk retiré et sa boîte. Ne pas simplement augmenter le padding jusqu'à masquer une erreur de version ou d'espace de coordonnées.

Ce run effectue des readbacks synchrones : ses timings ne sont pas des résultats de performance.

## 10. Étape 7 — appliquer les bounds au culling caméra

Dans `VoxelRenderer::renderVisibleChunks()`, les bounds actuelles sont construites **avant** de récupérer le chunk. Pour connaître celles du mesh :

1. Calculer `originX`, `originZ` depuis les coordonnées du chunk.
2. Récupérer le chunk ; sauter s'il est absent ou non ready.
3. Choisir les bounds : ancienne boîte si flag OFF ou absence de données ; boîte réelle si flag ON et données valides.
4. Transformer uniquement les bounds locales par la translation d'origine, comme à l'étape 6.
5. Tester contre `cameraFrustum` déjà extrait de `projection × view`.
6. Garder `chunkModelMatrix.translation(originX, 0, originZ)` ; ne pas réutiliser les minima de culling comme translation.
7. Conserver le même `MeshBuilder::renderChunk()` et les mêmes compteurs après le draw.

Pour le fallback, conserver exactement les dimensions précédentes `[-32, 288]`, pas les remplacer discrètement par `[0,256]` : sinon la variante OFF ne serait plus la référence annoncée.

Tu peux conserver le premier test de grande boîte avant le lookup comme broad phase, puis un second test tight après récupération. Cela évite de déplacer le lookup mais ajoute un test aux candidats. Choisir **une seule** variante ; pour une première implémentation simple, lookup puis test choisi est plus facile à lire. Mesurer si le surcoût CPU devient visible.

### Ne pas confondre offset d'ombre et déplacement géométrique

Dans `voxel_vertex_shader.glsl`, `shadowWorld` reçoit le normal offset pour calculer les coordonnées de lecture des ombres. Mais `gl_Position = projectionMatrix × viewPos`, où `viewPos` vient de la position originale. Le depth shader utilise aussi la géométrie originale. **Il n'est donc pas nécessaire d'ajouter l'offset des receivers à l'AABB géométrique actuelle.** Un futur shader qui déplace réellement `gl_Position` imposera de revoir ce contrat.

### Test caméra : ce que le validateur shadow ne couvre pas

Le test depth précédent ne détecte pas un chunk disparu uniquement de la passe voxel. Au minimum :

- tourner complètement autour d'une colline, regarder presque verticalement, franchir plusieurs frontières de chunks ;
- regarder un objet haut dont le bas est hors écran ;
- tester des coordonnées X/Z négatives ;
- ajouter puis retirer un bloc élevé, attendre le remesh, et traverser le bord du frustum ;
- comparer C à A, avec tight shadow OFF pour isoler la caméra.

Pour une vérification automatique forte, ajouter **un mode de diagnostic seulement** qui compare deux rendus monde dans la même frame :

1. Geler la simulation uniquement pendant ces deux rendus, sans update/streaming/upload entre eux ; conserver pose, soleil, matrices et mêmes meshes. Ce contrôle de correction n'est pas le benchmark mobile.
2. Générer les shadow maps une seule fois pour cette frame.
3. Rendre `sky → sun → voxels` avec bounds caméra larges ; lire couleur et depth avant l'UI et avant le swap.
4. Effacer à nouveau **couleur et profondeur** avec les mêmes états, puis rerendre `sky → sun → voxels` avec bounds serrées ; lire couleur et depth.
5. Comparer les résultats de même format, avec vérification des erreurs GL ; exclure le HUD/FPS de la comparaison.
6. Reprendre ensuite la frame normale. Ne laisser ni scissor, ni viewport, ni framebuffer, ni depth mask altérés par le validateur.

Il n'est pas nécessaire d'ajouter une architecture de FBO pour cela : un diagnostic sur le backbuffer, avant swap, suffit. Attention toutefois à son format sRGB et aux états de dithering : garder les mêmes dans les deux rendus. Sans double clear, le depth du premier rendu peut masquer les différences du second. Ne pas appeler simplement `Game.render()` deux fois : cela répéterait aussi les ombres, les timers et l'UI.

Vérifier aussi les effets de bord des méthodes appelées : `VoxelRenderer::render()` traite actuellement les uploads et incrémente son `frameIndex`. Pour ce validateur, aucun upload ne doit s'intercaler entre les deux draws, et les paramètres temporels/jitter doivent être identiques. Utiliser un override de sélection des bounds limité au diagnostic, sans changer le flag global définitif au milieu d'une frame normale.

Si tu ne construis pas ce contrôle automatique, documenter honnêtement « validation visuelle caméra » ; ne pas la présenter comme une identité pixel à pixel prouvée.

## 11. Étape 8 — étendre les tests CPU de culling

Le test existant `tools/tests/ShadowFrustumCullingTest.java` vérifie le culling des chunks complets. Conserver ses cas, puis ajouter des bounds de meshes plus petits.

Cas à couvrir :

1. Boîte large acceptée, boîte réelle entièrement hors volume : le nouveau chemin doit rejeter.
2. Les deux boîtes dans le volume : les deux doivent accepter.
3. Boîte qui touche un plan : conserver le cas avec padding.
4. Mesh horizontal d'épaisseur nulle.
5. Chunk négatif avec bornes locales non nulles ; vérifier la translation une seule fois.
6. Géométrie atteignant `x=16`, `z=16`, `y=256`.
7. Bounds absentes : réponse identique à l'ancien helper, pas rejet systématique.
8. Changement de mesh grand → petit puis petit → grand : utiliser les bornes de la version installée.
9. Containment : pour chaque buffer de test, tous ses points sont dans les bornes calculées.

Pour l'oracle de frustum, adapter le test existant : transformer les **huit coins de l'AABB réelle**, pas ceux du chunk complet, puis tester les six inégalités clip. Ajouter des volumes orthographiques lumineux et des projections perspective caméra. Les conditions OpenGL sont `−w ≤ x,y,z ≤ w` ; ne pas remplacer le near par `z >= 0`.

Ne pas tester uniquement si un coin est dans le frustum : une boîte peut intersecter ou contenir le frustum sans avoir de coin dedans. Pour valider un rejet, vérifier qu'un même plan exclut tous ses coins. Le helper JOML est un test conservateur adapté à ce rôle. [Documentation `FrustumIntersection`](https://joml-ci.github.io/JOML/apidocs/org/joml/FrustumIntersection.html).

Exécution manuelle du test existant :

```sh
./gradlew --offline installDist
javac -cp 'build/classes/java/main:build/install/TOB_Voxel_Project/lib/*' \
  -d build/diagnostic-tests tools/tests/ShadowFrustumCullingTest.java
java -cp 'build/diagnostic-tests:build/classes/java/main:build/install/TOB_Voxel_Project/lib/*' \
  engine.world.ShadowFrustumCullingTest
```

Compiler/exécuter de la même façon ton nouveau test de bounds, avec son nom de classe et package. `build.gradle` désactive actuellement les sources JUnit : un `gradle test` sans tests exécutés ne valide pas ce changement.

## 12. Étape 9 — mesurer sans inventer un bénéfice

### 12.1. D'abord vérifier que la piste réduit réellement le travail

Faire A et S avec `voxel.gpuTiming=true`, puis A et C si les tests de correction caméra passent. Conserver dimensions et scènes identiques.

Comparer `shadow_draws`, `shadow_vertices`, `voxel_draws`, `voxel_vertices`, puis les intervalles GPU. En S, la géométrie voxel ne doit pas changer à pose/meshes identiques. En C, les soumissions shadow ne doivent pas changer. Les runs mobiles indépendants ne reproduisent pas exactement chaque pose ; comparer les phases temporelles et compléter par les contrôles dans une même frame.

Pour la première fois seulement, inspecter quelques bounds produites : si elles couvrent presque toujours tout le chunk, il est normal que l'effet soit faible. Ce constat évite de consacrer du temps à une optimisation sans potentiel sur ta scène.

**Critère d'arrêt :** s'il n'y a pratiquement aucun draw/vertex retiré sur le parcours et sur une vue de colline représentative, ne pas commencer un système plus complexe de culling. Conserver les mesures et reconsidérer la piste.

### 12.2. Ensuite mesurer le jeu sans instrumentation

Les queries ont perturbé le driver dans les essais précédents. Retirer `gpuTiming=true` et `validateShadowCulling=true` pour les résultats FPS.

Commencer par A–S–S–A, puis A–C–C–A. Tester A–B–B–A seulement si les deux variantes sont correctes et prometteuses. Pour économiser du temps, ne pas multiplier ces séries avant le contrôle des compteurs.

Commande de la variante S, **après implémentation des deux propriétés** :

```sh
JAVA_OPTS='-Dvoxel.testHarness=true -Dvoxel.testStatic=false -Dvoxel.windowScale=0.75 -Dvoxel.shadowFrustumCulling=true -Dvoxel.tightShadowBounds=true -Dvoxel.tightCameraBounds=false -Dvoxel.gpuTiming=false -Dvoxel.validateShadowCulling=false' \
  build/install/TOB_Voxel_Project/bin/TOB_Voxel_Project
```

Changer seulement les deux flags tight pour A/C/B. Le scénario doit garder le soleil mobile. Les tests de correction éventuellement figés restent dans une catégorie distincte.

Ne pas lancer une compilation ou un second jeu pendant un benchmark. Fermer la fenêtre précédente avant le suivant ; noter secteur/batterie, réglage d'énergie, taille réelle du framebuffer et activités système inhabituelles. Une variation de quelques dixièmes de milliseconde peut être du bruit.

### 12.3. Statistiques à calculer

Pour rester comparable au guide :

- CPU : cumuler `dt`, retenir `3 < somme(dt) ≤ 19 s`.
- GPU : utiliser directement `elapsed_s` ; les résultats sont différés et leur ordre de lecture n'est pas une horloge.
- Temps moyen CPU : `1000 × somme(dt) / N`.
- FPS : `N / somme(dt)`, pas moyenne de `fps_smooth` ni moyenne de `1/dt`.
- P50/P95 : trier les temps et utiliser l'indice arrondi `(N−1) × p`.
- Ne pas soustraire directement un `swap_ms` CPU d'un temps GPU pour calculer un « overhead driver ».

Commande d'analyse **en lecture seule** pour tes CSV CPU, à copier en remplaçant les deux chemins d'exemple :

```sh
ruby -rcsv - logs/perf_test_RUN_A.csv logs/perf_test_RUN_S.csv <<'RUBY'
ARGV.each do |path|
  elapsed = 0.0
  rows = CSV.read(path, headers: true).select do |r|
    elapsed += r['dt'].to_f
    elapsed > 3.0 && elapsed <= 19.0
  end
  abort "Aucune frame retenue : #{path}" if rows.empty?
  times = rows.map { |r| r['dt'].to_f * 1000.0 }.sort
  mean = times.sum / times.length
  p50 = times[((times.length - 1) * 0.50).round]
  p95 = times[((times.length - 1) * 0.95).round]
  puts format('%s | n=%d | moyenne=%.3f ms | FPS=%.2f | P50=%.2f | P95=%.2f',
              path, times.length, mean, 1000.0 / mean, p50, p95)
end
RUBY
```

Cette commande ne lance pas le moteur et ne crée aucun fichier. Les chemins `RUN_A`/`RUN_S` sont des placeholders, pas des logs existants.

### 12.4. Contrôler aussi le coût déplacé vers le meshing

Le calcul des bounds ajoute une lecture CPU du vertex buffer lors de sa génération. Noter séparément durée de chargement, temps de génération de mesh si disponible, pics de remeshing et de streaming. Ne pas ralentir chaque frame pour économiser une passe CPU ponctuelle ; inversement, ne pas annoncer un succès si la moyenne gagne mais que les modifications de terrain deviennent très coûteuses.

Si ce coût se révèle significatif, intégrer les extrema à la boucle qui écrit les vertices ou aux quatre coins de chaque quad **dans un second petit changement**, en réutilisant les tests du helper comme référence. Ne pas faire cette optimisation avant d'avoir une implémentation correcte et mesurée.

### Tableau à remplir

| Variante / log | Flags caméra/ombre | Draws voxel | Vertices voxel | Draws shadow | Vertices shadow | Mean CPU | P95 CPU | Validation |
|---|---|---:|---:|---:|---:|---:|---:|---|
| A | OFF/OFF | | | | | | | |
| S | OFF/ON | | | | | | | |
| C | ON/OFF | | | | | | | |
| B | ON/ON | | | | | | | |

Indiquer pour les colonnes de géométrie quel run instrumenté les fournit. Ne pas laisser entendre qu'elles proviennent d'un run sans ce relevé si le CSV CPU ne les contient pas.

## 13. Symptômes fréquents et où chercher

| Symptôme | Vérification prioritaire |
|---|---|
| Tout disparaît après ajout du calcul | `position/limit` modifiées, second `flip()`, stride 36 utilisé comme nombre de floats. |
| Chunks décalés, surtout avec min local non nul | Minimum de l'AABB utilisé à la place de l'origine dans `modelMatrix`. |
| Popping après modification d'un bloc | Bounds de la nouvelle géométrie associées à l'ancien VBO, ou bounds anciennes conservées lors du remplacement. |
| Ombres manquantes hors écran | Frustum caméra utilisé pour la shadow pass ; ce n'est pas autorisé. |
| Sommet d'un bloc haut absent | `maxY` pris sur l'indice du bloc, sans sa face à `y+1`, ou helper qui exclut `min == max`. |
| Mesh inconnu invisible | `null` interprété comme rejet au lieu du fallback large. |
| Problème seulement en coordonnées négatives | Origine monde erronée, translation appliquée deux fois, troncature des coordonnées à la place des coordonnées de chunk existantes. |
| Validateur shadow ne teste plus rien | Référence du validateur passée par le même nouveau test malgré `lightFrustum == null`. |
| Très bon FPS mais image différente | Optimisation invalide jusqu'à preuve du contraire : inspecter bounds et version installée. |
| Draws réduits mais FPS quasi identiques | Coût dominant ailleurs ou baisse trop faible ; résultat possible, pas raison d'élargir le refactor. |
| Chargement infini avec monde vide | Examiner l'ancien contrat `isReady()` / attente de chargement, indépendamment du culling. |

## 14. Critères de fin et livraison personnelle

- [ ] Bounds calculées depuis le mesh réellement uploadé ; containment testé.
- [ ] État du `FloatBuffer` inchangé par leur calcul.
- [ ] Données transportées avec `MeshData`, sans transfert de propriété du buffer.
- [ ] Installation et cleanup cohérents dans `Chunk` ; deux producteurs traités.
- [ ] Fallback conservateur pour bounds absentes.
- [ ] Origine du modèle distincte du minimum de la boîte.
- [ ] Ombres et caméra activables séparément ; flags consignés dans les logs.
- [ ] Tests CPU passés, cas limites et remeshing compris.
- [ ] Validateur GPU shadow passé ; type de validation caméra explicité.
- [ ] Benchmarks mobiles sans profiler séparés des tests de correction et de profilage.
- [ ] Coût de génération/streaming examiné, pas seulement FPS moyen.
- [ ] Variantes sans bénéfice laissées désactivées ; aucun chiffre de gain inventé.

Activer par défaut uniquement la ou les variantes dont la correction et l'intérêt sont établis. Garder temporairement les interrupteurs pour un retour arrière local ; il n'est pas nécessaire de réorganiser tout le renderer.

Si la piste échoue à améliorer la scène pertinente, la suite raisonnable sera d'examiner le coût et la duplication des vertices, notamment un EBO par quad, **dans un guide et une expérience distincts**. Ne pas lancer automatiquement cette deuxième optimisation à la fin de celle-ci.

**Objectif pédagogique :** être capable de montrer quel mesh une borne décrit, quand cette borne devient visible au renderer et pourquoi un rejet ne peut pas supprimer un triangle qui aurait contribué à l'image. C'est cette compréhension — puis la mesure — qui justifie le changement, pas le nom « AABB optimisée ».
