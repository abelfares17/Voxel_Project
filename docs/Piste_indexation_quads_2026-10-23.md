# Prochaine expérience FPS : indexer les quads

23 octobre 2026 — proposition initiale conservée ci-dessous. **Implémentée et benchmarkée depuis : voir le [rapport des résultats](Rapport_indexation_2026-10-23.md).**

## Pourquoi cette piste

Le [rapport AABB](Rapport_AABB_meshes_2026-10-22.md) montre que resserrer les boîtes retire peu de géométrie et ne donne pas de gain FPS robuste. La prochaine expérience doit réduire le travail pour les chunks qui restent visibles, plutôt que continuer à affiner leurs boîtes.

`MeshBuilder::generateMeshData` écrit actuellement les vertices dans cet ordre : `v0,v1,v2,v0,v2,v3`. Chaque vertex occupe 36 octets (position, UV, normale, AO). Les deux répétitions sont les mêmes records, donc leurs neuf composantes sont identiques : on peut les remplacer par des références sans fusionner des attributs incompatibles.

Proposition : stocker `v0,v1,v2,v3`, puis les indices `0,1,2,0,2,3`, décalés de quatre par quad. Ne partager aucun sommet entre deux faces pour cette première expérience.

| Par quad | Actuel | Indexé avec indices uint32 |
|---|---:|---:|
| Vertices stockés | 6 | 4 |
| VBO | 216 octets | 144 octets |
| EBO | 0 | 24 octets |
| Total de données GPU | 216 octets | 168 octets |
| Triangles / références soumises | 2 / 6 | 2 / 6 |

**Gain certain sur les données de ces buffers : −22,22 %**, hors overhead des objets. VBO seul : −33,33 %. Ni le nombre de triangles, ni les pixels, ni les draw calls ne diminuent. La réutilisation du résultat du vertex shader peut réduire le travail GPU ; **le gain de FPS n'est pas garanti**, notamment si le fragment shader ou une autre partie domine. Ne pas annoncer « un tiers de vertices soumis en moins » à partir du seul nombre stocké.

## Contrat OpenGL

`glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0L)` convient au contrat OpenGL 3.2 du moteur. Le dernier argument est un offset dans l'EBO ; `indexCount` compte les indices, pas les vertices distincts. L'association de l'EBO appartient au VAO : ne pas détacher l'EBO en le mettant à zéro pendant que le VAO du chunk est encore lié. Sources : [Khronos — glDrawElements](https://wikis.khronos.org/opengl/GLAPI/glDrawElements), [Khronos — vertex specification](https://wikis.khronos.org/opengl/Vertex_Specification).

Prendre des indices 32 bits pour ne pas introduire une limite de 65 536 vertices par chunk. Un EBO par mesh est plus simple à posséder pour le premier essai ; ne pas ajouter immédiatement un EBO global partagé.

## Changements à isoler

1. Introduire une option de démarrage `voxel.indexedMeshes`, initialement OFF. Préserver le producteur non indexé comme référence. Les AABB restent OFF pour les deux variantes.
2. Dans `MeshData`, distinguer `vertexCount`, `indexCount`, buffer d'indices optionnel et bornes. Libérer chacun des buffers natifs, y compris lors d'une erreur de construction. Les bornes parcourent les quatre vertices stockés par quad, pas six indices.
3. Modifier `MeshBuilder::generateMeshData` : conserver exactement l'ordre des deux triangles et toutes leurs composantes. Aucun changement aux UV, normales, AO, diagonales ou winding.
4. Dans `MeshWorkerManager::uploadMeshesIfAny` et `MeshBuilder::buildChunkMesh`, créer/uploader l'EBO avec le VAO lié. Installer tous les handles et compteurs du même résultat avant de rendre le chunk prêt.
5. Dans `Chunk::setMesh` et `cleanup`, définir un propriétaire unique de l'EBO, supprimer l'ancien au remplacement et remettre handle/count à zéro. Le chemin legacy doit effacer l'ancien EBO, pas le réutiliser silencieusement.
6. Adapter **les deux draws** : `MeshBuilder::renderChunk` pour les voxels et `World::renderDepth` pour les ombres. Choisir le draw à partir du mesh installé, et non seulement d'un flag global. Ne pas oublier le chemin sans frustum du validateur shadow.
7. Adapter les compteurs : conserver les références soumises comme métrique comparable, ajouter séparément vertices stockés et octets VBO/EBO. Ne pas présenter `4Q` comme une mesure des invocations GPU réellement exécutées.

Aucun shader, biais, résolution, rythme solaire, format d'attribut ou système de culling ne doit changer dans cette expérience.

## Tests avant les FPS

1. Développer le mesh indexé via ses indices et comparer **bit à bit les neuf floats de chaque vertex** à l'ancien flux non indexé. Tester terrain irrégulier, AO non uniforme, faces aux bords, chunk vide et mesh dépassant 65 536 vertices stockés. Vérifier tous les indices dans `[0, vertexCount)`.
2. Tester avec contexte GL : upload, remesh, remplacement indexé → non indexé → indexé, cleanup/ré-upload. Vérifier la disparition des anciens EBO avec `glIsBuffer`, pas seulement celle des VAO.
3. Comparer images couleur/depth et les trois shadow maps entre les deux représentations **du même snapshot de mesh**, avec mêmes matrices, soleil et échantillon temporel. Une capture de deux processus indépendants n'est pas une comparaison exacte fiable.
4. Attention : les validateurs AABB actuels comparent deux choix de culling sur le même mesh. Ils **ne prouvent pas** l'équivalence indexed/non-indexed. Leur réussite ne doit pas être utilisée à la place du contrôle précédent.
5. Séparer validation synchrone et benchmark ; arrêter l'expérience au premier mismatch ou défaut de lifetime.

## Mesure et décision

Garder World 10, Retina 2268 × 1382, fenêtre 75 %, trois cascades 2048², stage 6/filtre 1, soleil et caméra mobiles. Mesurer A = ancien flux et B = indexé, en ABBA, sans queries/readbacks pour les FPS ; analyser `3 < somme(dt) ≤ 19 s` avec `tools/analyse_aabb_logs.py`.

Faire séparément un profil GPU des passes shadow et voxel, et mesurer les octets uploadés ainsi que le coût de génération/upload. En cas de petit gain seulement, répéter avant activation par défaut. Une réduction mémoire sans progression FPS reste utile, mais doit être annoncée comme telle. Ajouter une scène plus dense uniquement comme second scénario, pas en remplacement opportuniste du témoin défavorable.

## Ce qui attend

Le greedy meshing peut retirer des triangles, mais doit préserver l'atlas et l'AO : chantier plus risqué. Un cache de shadow maps avec soleil mobile pose des questions de qualité temporelle distinctes. Enfin, simplifier les calculs B du vertex shader n'est pas un gain prouvé : le stage 6 retourne le résultat A et le compilateur peut déjà éliminer du travail inactif. L'indexation fournit d'abord une expérience locale, quantifiable et compatible avec les deux passes.
