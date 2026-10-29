# Roadmap & Checklist — Voxel Engine (Minecraft-Grade)

Ce document structure la feuille de route technique et fonctionnelle du moteur voxel pour atteindre le niveau de finition visuel, technique et gameplay d'un **Minecraft moderne**.

**Légende de statut :**
- `[x]` **Terminé & Validé** (implémenté, testé et mesuré en jeu)
- `[~]` **Partiellement implémenté** (socle existant dans le code, nécessite fiabilisation ou extension)
- `[ ]` **À implémenter**

---

## 1. Rendering & Visual Polish (Rendu & Qualité Graphique)

### Ombres, Maillage & Pipeline Géométrique
- [x] **Correction du Shimmering des ombres (CSM Anti-Shimmering)** — [`CSMCalculator.java`](../src/main/java/engine/render/shadow/CSMCalculator.java), [`ShadowCasterVolume.java`](../src/main/java/engine/render/shadow/ShadowCasterVolume.java), [`voxel_fragment_shader.glsl`](../src/main/resources/shaders/voxel_fragment_shader.glsl)
  - Bornes orthographiques $Z$ invariantes à la rotation caméra (sphère englobante du sous-frustum).
  - Quantification de la grille d'ombre au pas exact du texel shadow-map ($\Delta = S / \text{shadowMapResolution}$).
  - Filtrage **PCF $3\times 3$ Tent pondéré par matériel `textureGather` / 4-tap bilinéaire** et fondu inter-cascades (15 % de zone de transition).
- [x] **Greedy Meshing Visuel avec Ambient Occlusion Exacte (Greedy v2.5 — 9-Point Boundary-Clamped AO)** — [`MeshBuilder.java`](../src/main/java/engine/render/MeshBuilder.java), [`voxel_fragment_shader.glsl`](../src/main/resources/shaders/voxel_fragment_shader.glsl)
  - Fusion gloutonne 2D des quads partageant le même bloc, la même variante CTM et une grille de contrôle AO $3\times 3$ exacte ($c_{00}, c_{10}, c_{11}, c_{01}, e_{v0}, e_{v1}, e_{u0}, e_{u1}, a_{\text{int}}$) encodée sur 19 bits.
  - Interpolation bilinéaire clampée à $1.0$ bloc des bords dans le fragment shader (**0 % de perte visuelle** sur l'AO par rapport au maillage `1×1`, suppression totale des bandes sombres sur les pieds de collines en diagonale).
  - **Gain validé :** **-60,6 % de triangles** (`341 463` quads vs `865 484`), **-388 Mo de VRAM** (`391 Mo` vs `779 Mo`), **96–102 FPS** moyens en $2560\times 1440$.
- [x] **Pipeline Géométrique & Shadow Culling Bas Niveau** — [`MeshVertexLayout.java`](../src/main/java/engine/render/MeshVertexLayout.java), [`ShadowMesh.java`](../src/main/java/engine/render/ShadowMesh.java), [`ShadowCasterResidency.java`](../src/main/java/engine/world/ShadowCasterResidency.java)
  - Format de sommet compact **24 octets** (`float3 pos + uint32 normal + uint32 uv/dim + uint32 packedAO`).
  - Indexation EBO `uint32` partagée (4 sommets par quad au lieu de 6).
  - Maillage d'ombre glouton dédié (`-65,9 %` de rectangles d'ombre) et résidence directionnelle des chunks projetant des ombres vers la caméra.
- [x] **Mode Diagnostic Wireframe « Solid-Shaded Clay » & HUD `F3`** — [`VoxelRenderer.java`](../src/main/java/engine/render/renderer/VoxelRenderer.java), [`Game.java`](../src/main/java/engine/Game.java)
  - Raccourci `F7` (et `Shift+F7` pour le mode classique) : rendu 2 passes (faces argile mate + AO avec test de profondeur pour masquer les grottes souterraines, puis arêtes filaires avec `glPolygonOffset`).
  - Isolation des informations de debug dans l'overlay `F3`.

### Prochaines Étapes Rendu
- [ ] **Sélection de bloc (Block Outline Highlight)**
  - Rendu d'une boîte filaire noire/blanche légèrement dilatée ($\pm 0.002$) autour du bloc visé par le joueur à moins de $5$ blocs.
- [ ] **Animation de cassage (Block Crack Overlay) & Particules de débris**
  - Superposition d'une texture d'étapes de fissuration (`destroy_stage_0..9`) lors du minage prolongé et système léger de particules 3D texturées soumises à la gravité lors de la destruction d'un bloc.
- [ ] **Éclairage Voxel Propagé (Skylight + Block Light BFS)**
  - Ajout d'un canal de lumière sur 1 octet par voxel (`4 bits skylight` + `4 bits blocklight`) propagé par parcours en largeur (BFS) à 15 niveaux, permettant des grottes réellement sombres et des blocs émetteurs de lumière (torches, glowstone, lave).
- [ ] **Brouillard Atmosphérique & Post-Processing**
  - Brouillard exponentiel de distance aux frontières de la `renderDistance` (harmonisé avec la couleur de l'horizon du [`SkyRenderer.java`](../src/main/java/engine/render/renderer/SkyRenderer.java)) pour masquer l'apparition des chunks lointains, plus filtre visuel sous-marin.

---

## 2. Textures & Matériaux (Système de Textures Minecraft-Grade)

### Acquis
- [x] **Textures Pixel-Art 16×16 Harmonisées** — [`src/main/resources/textures/blocks/`](../src/main/resources/textures/blocks/)
  - Remplacement de l'ancien atlas saturé par des textures individuelles $16\times 16$ au contraste maîtrisé : herbe prairie olive naturelle (`grass_top`), bordure latérale harmonisée (`grass_side`), terre (`dirt`), roche stratifiée (`stone`), `bedrock`.
- [x] **Chargeur Dynamique par Face & Variantes CTM** — [`TextureLoader.java`](../src/main/java/engine/render/TextureLoader.java)
  - Assemblage automatique de l'atlas à partir des PNG individuels par face avec 4 variantes procédurales par bloc (`_0.png` à `_3.png`) et bascule instantanée `-Dvoxel.texturePack=modern|legacy`.
- [x] **Pipeline Shader Anti-Répétition (4 techniques combinées)** — [`voxel_fragment_shader.glsl`](../src/main/resources/shaders/voxel_fragment_shader.glsl)
  - Hash spatial 3D déterministe par bloc entier $\lfloor \text{worldPos} \rfloor$.
  - Sélection stochastique parmi les 4 variantes CTM.
  - Symétrie carrée $D_4$ (4 rotations UV $0^\circ/90^\circ/180^\circ/270^\circ \times 2$ miroirs) sur les faces isotropes.
  - Tint macro-biomique basse fréquence par double octave de bruit de Perlin continu en coordonnées monde $(x, z)$.

### Prochaines Étapes Textures
- [ ] **Activation et Texturage de la Palette Complète de Blocs** — [`BlockRegistry.java`](../src/main/java/engine/world/BlockRegistry.java)
  - Décommenter, enregistrer et générer les textures $16\times 16$ modernes (avec variantes) pour : `log` (écorce + anneaux de tronc), `planks` (planches de chêne), `leaves` (feuillage découpé), `glass` (verre), `cobblestone` (pierre taillée), `sand` (sable), `snow` (neige), `water` (eau) et minerais (`coal_ore`, `iron_ore`).
- [ ] **Migration vers `GL_TEXTURE_2D_ARRAY` & Mipmapping sans Saignement UV**
  - Remplacer l'atlas 2D par un `sampler2DArray` OpenGL 3.3 où chaque couche correspond à une tuile $16\times 16$. Cela permet d'activer `GL_REPEAT` natif en hardware, le mipmapping (`GL_NEAREST_MIPMAP_LINEAR`) et le filtrage anisotrope sur les quads Greedy Meshing sans aucun saignement des tuiles voisines ni coût `fract()` dans le fragment shader.

---

## 3. Playability (Physique, Raycasting, Interaction Blocs & Inventaire)

### État Actuel & Fiabilisation Physique
- [~] **Moteur Physique AABB & Collisions** — [`PhysicsEngine.java`](../src/main/java/engine/physics/PhysicsEngine.java), [`CollisionDetector.java`](../src/main/java/engine/physics/CollisionDetector.java), [`Player.java`](../src/main/java/engine/entity/Player.java)
  - *Existant :* Boîtes englobantes [`AABB.java`](../src/main/java/engine/physics/AABB.java), gravité, saut, friction horizontale, résolution axe par axe ($X, Y, Z$) et bascule mode Spectateur / Physique (`P`).
  - [ ] **Correction des collisions inter-chunks :** [`CollisionDetector.java`](../src/main/java/engine/physics/CollisionDetector.java) ne teste actuellement que le `Chunk` unique sous le centre du joueur, provoquant des traversées de murs aux frontières de chunks ($x=0/15, z=0/15$). Passer `World` au détecteur de collision pour interroger `world.getBlockAt(wx, wy, wz)`.
  - [ ] **Intégration physique indépendante du framerate (`deltaTime`) :** Pondérer la gravité, la vélocité et le déplacement par `deltaTime` (ou pas de temps fixe `60 Hz`) et corriger la bascule `P` dans [`Game.java`](../src/main/java/engine/Game.java) avec `isKeyJustPressed(GLFW_KEY_P)` au lieu de `isKeyPressed`.
  - [ ] **Sprint, Accroupissement (Sneak / Edge-Guard) & Marche Fluide :** Ajouter le sprint (`Ctrl` / double-`W` avec léger élargissement dynamique du FOV) et le mode accroupi (`Shift`) empêchant de tomber du bord d'un bloc.

### Interaction Voxel & Boucle de Gameplay
- [ ] **Raycasting Voxel 3D Exact (Algorithme DDA d'Amanatides & Woo)**
  - Implémenter `VoxelRaycaster.raycast(World, origin, direction, maxDistance=5.0f)` retournant `RaycastHit(blockX, blockY, blockZ, faceNormalX, faceNormalY, faceNormalZ, hitBlock)`.
- [ ] **Cassage et Pose de Blocs en Temps Réel**
  - **Clic gauche :** Détruire le bloc visé (`world.setBlockAt(x, y, z, AIR)`) avec reconstruction immédiate du maillage du chunk (et du chunk adjacent si le bloc touché est sur une bordure $x \in \{0,15\}$ ou $z \in \{0,15\}$).
  - **Clic droit :** Poser le bloc sélectionné sur la face adjacente `(x + nx, y + ny, z + nz)` après vérification que l'AABB du nouveau bloc n'intersecte pas l'AABB du [`Player.java`](../src/main/java/engine/entity/Player.java).
- [ ] **Barre d'Action (Hotbar 9 Slots) & Inventaire**
  - Affichage HUD en bas de l'écran d'une Hotbar à 9 emplacements sélectionnables via les touches `1` à `9` et la molette de la souris, avec aperçu icône de chaque bloc.
  - Écran d'inventaire complet ouvrable avec `E` (grille de sélection de blocs en mode Créatif et gestion des piles d'objets `ItemStack(blockId, count)` en mode Survie).

---

## 4. UI & Ergonomie (Interface Utilisateur & Contrôles)

### État Actuel
- [~] **Architecture d'Écrans & Mise à l'Échelle** — [`UIManager.java`](../src/main/java/engine/ui/UIManager.java), [`UIScreenScaler.java`](../src/main/java/engine/ui/UIScreenScaler.java), [`MainMenuScreen.java`](../src/main/java/engine/ui/screens/MainMenuScreen.java), [`WorldSelectionScreen.java`](../src/main/java/engine/ui/screens/WorldSelectionScreen.java)
  - *Existant :* Machine à états (`MenuState`, `LoadingState`, `PlayingState`, `PausedState`), rendu orthographique [`UIRenderer.java`](../src/main/java/engine/render/UIRenderer.java) et composants de base (`Button`, `Label`, `Panel`, `TextField`).

### Prochaines Étapes UI
- [ ] **Mise à l'Échelle Responsive & Support HiDPI / Retina (`GUI Scale`)**
  - Unifier le système de coordonnées virtuelles dans [`UIScreenScaler.java`](../src/main/java/engine/ui/UIScreenScaler.java) (ancrages relatifs `CENTER`, `TOP_LEFT`, `BOTTOM_CENTER`) pour que le redimensionnement de fenêtre et les écrans macOS Retina conservent des proportions et zones de clic parfaites.
- [ ] **Bibliothèque de Composants UI Propres & Modernes**
  - Ajouter `Slider` (FOV, Render Distance, Sensibilité souris, Heure du jour), `ToggleButton` (VSync, Ombres, Greedy Meshing, Textures Naturelles), `ScrollPanel` (liste des mondes et contrôles) et rendu de panneaux en **9-slice** net.
- [ ] **Menu Pause Complet en Jeu (`ESC`) & Écran Settings Fonctionnel** — [`PausedState.java`](../src/main/java/engine/core/states/PausedState.java), [`SettingsScreen.java`](../src/main/java/engine/ui/screens/SettingsScreen.java)
  - Connecter `ESC` dans [`PlayingState.java`](../src/main/java/engine/core/states/PlayingState.java) vers une superposition de pause semi-transparente (*Reprendre*, *Options graphiques*, *Contrôles*, *Sauvegarder & Quitter vers le menu*).
- [ ] **Menu d'Attribution des Touches (Keys & Controls Menu)**
  - Remplacer les constantes GLFW codées en dur dans [`Input.java`](../src/main/java/engine/input/Input.java) et [`Game.java`](../src/main/java/engine/Game.java) par une table `KeyBindings` configurable par l'utilisateur avec détection de conflits et sauvegarde persistante dans `settings.properties`.
- [ ] **HUD In-Game (Crosshair + Hotbar + Indicateurs)**
  - Réticule central en inversion de couleur au centre de l'écran, barre d'action en bas, et indicateur discret du bloc sélectionné.

---

## 5. Special Blocks (Blocs Transparents, Végétation & Fluides)

- [ ] **Séparation des Passes de Rendu (Opaque / Cutout / Translucent)** — [`Chunk.java`](../src/main/java/engine/world/Chunk.java), [`MeshBuilder.java`](../src/main/java/engine/render/MeshBuilder.java), [`VoxelRenderer.java`](../src/main/java/engine/render/renderer/VoxelRenderer.java)
  - **Pass 1 (Opaque) :** Blocs pleins classiques avec écriture dans le Depth Buffer.
  - **Pass 2 (Alpha-Cutout) :** Feuillages (`leaves`), herbes hautes et fleurs (`if (texColor.a < 0.5) discard;`) avec ombres portées découpées.
  - **Pass 3 (Translucent) :** Verre (`glass`), eau (`water`) et glace avec `glEnable(GL_BLEND)`, `glDepthMask(false)` et culling interne des faces entre deux blocs transparents identiques adjacents (deux blocs d'eau ou de verre côte à côte ne doivent pas dessiner leur face mitoyenne).
- [ ] **Maillages Croisés (Cross-Quad Billboards / Flora)**
  - Support dans [`MeshBuilder.java`](../src/main/java/engine/render/MeshBuilder.java) d'une géométrie en « X » à 2 quads diagonaux sans collision physique pour les herbes hautes (`tall_grass`), fleurs et jeunes pousses d'arbres.
- [ ] **Système de Fluides (Eau & Lave)**
  - Génération d'un niveau de la mer global ($Y = 62$) remplissant les bassins naturels.
  - Simulation d'écoulement par automate cellulaire discret (niveaux de hauteur $1..8$) lors des mises à jour de blocs adjacents.
  - Shader d'eau dédié : transparence colorée selon la profondeur, ondulation sinusoïdale légère des sommets de surface, reflets spéculaires du soleil/lune et physique de nage (flottabilité et gravité réduite dans l'eau).

---

## 6. World Generation (Génération Procédurale & Biomes)

### État Actuel
- [~] **Terrain Perlin & Réseaux de Grottes** — [`WorldGenerator.java`](../src/main/java/engine/world/WorldGenerator.java)
  - *Existant :* Heightmap `OpenSimplexNoise` avec exposant montagneux, creusement de tunnels de grottes 3D (`generateChunkGrottes`) et chargement asynchrone multi-threadé autour du joueur.

### Prochaines Étapes Génération
- [ ] **Système de Biomes Multi-Bruits (inspiré de Minecraft 1.18+)**
  - Combinaison de cartes continues 2D : **Continentalité** (océans, plaines côtières, hauts plateaux), **Érosion** (vallées douces vs pics escarpés), **Température** et **Humidité**.
  - Biomes distincts : *Plaines verdoyantes*, *Forêt tempérée*, *Désert de sable*, *Toundra / Pics enneigés* (neige au-dessus de $Y = 120$), *Plages de sable* autour du niveau de l'eau.
- [ ] **Génération d'Arbres & Végétation de Surface**
  - Décorateurs procéduraux déterministes par seed : arbres (tronc en `log` + couronne en `leaves` gérant proprement le débordement sur les chunks voisins), tapis d'herbes hautes et fleurs.
- [ ] **Filons de Minerais & Strates Géologiques**
  - Génération de poches 3D de minerais selon la profondeur $Y$ (charbon, fer, or, diamant, gravier, granite/andésite).
- [ ] **Structures Procédurales**
  - Génération de structures basées sur des gabarits (templates/prefabs) : cabanes, tours de guet, entrées de mines abandonnées ou ruines.

---

## 7. Entities & Mobs (Entités, Modèles 3D & Intelligence Artificielle)

### État Actuel
- [~] **Socle Entité / Composants** — [`Entity.java`](../src/main/java/engine/entity/Entity.java), [`TransformComponent.java`](../src/main/java/engine/entity/component/TransformComponent.java), [`PhysicsComponent.java`](../src/main/java/engine/entity/component/PhysicsComponent.java)
  - *Existant :* Classe de base [`Entity.java`](../src/main/java/engine/entity/Entity.java) avec position, rotation, boîte englobante et composant physique, utilisée par [`Player.java`](../src/main/java/engine/entity/Player.java).

### Prochaines Étapes Entités
- [ ] **Système de Modèles Articulés en Boîtes (Hierarchical Box Models)**
  - `EntityRenderer` capable d'afficher des modèles hiérarchiques composés de cuboïdes texturés (tête, torse, 4 membres articulés autour de points de pivot) avec animation sinusoïdale de marche, rotation de la tête vers le joueur et éclairage cohérent avec le monde.
- [ ] **Entités d'Objets au Sol (`ItemEntity`)**
  - Lorsqu'un bloc est cassé, apparition d'un mini-cube 3D rotatif qui flotte verticalement, subit la gravité/collisions et est aspiré vers l'inventaire du joueur lorsqu'il s'en approche.
- [ ] **Mobs Passifs & Hostiles + IA Comportementale**
  - Machine à états d'IA (`Idle`, `Wander`, `FollowPlayer`, `Flee`, `Chase`) couplée à un pathfinding voxel local (franchissement automatique des marches de $1$ bloc de haut, évitement des falaises et de l'eau profonde).
  - Cycle d'apparition (`MobSpawner`) : animaux passifs le jour sur l'herbe (`Sheep`, `Cow`, `Pig`), créatures hostiles la nuit ou dans les grottes sombres.

---

## 8. Chat & Commands (`/*`) (Console Interactive & Commandes)

- [ ] **Interface de Chat en Jeu (`T` / `Entrée`)**
  - Overlay en bas à gauche affichant l'historique des messages récents (avec fondu progressif après 8 secondes lorsque le chat est fermé) et barre de saisie interactive avec historique des commandes (`Flèche Haut` / `Flèche Bas`).
- [ ] **Moteur de Commandes `/` avec Autocomplétion (`Tab`)**
  - Parseur modulaire `CommandRegistry` déclenché par `/` (ou directement avec la touche `/`) :
    - `/tp <x> <y> <z>` : Téléporte immédiatement le joueur aux coordonnées données.
    - `/time set <day|noon|sunset|night|0..24>` & `/time speed <multiplier|pause>` : Contrôle direct du [`TimeManager.java`](../src/main/java/engine/time/TimeManager.java).
    - `/gamemode <creative|survival|spectator>` : Bascule les règles de physique, de vol et d'inventaire.
    - `/give <block_name|id> [amount]` : Ajoute des blocs dans la Hotbar/inventaire.
    - `/seed` : Affiche la graine du monde courant.
    - `/setblock <x> <y> <z> <block>` & `/fill <x1> <y1> <z1> <x2> <y2> <z2> <block>` : Édition rapide du monde.
    - `/help [command]` : Liste les commandes disponibles et leur syntaxe.

---

## 9. Audio, Persistance & Immersion (Ajouts Qualité Minecraft-Grade)

- [ ] **Système Audio Spatialisé 3D (OpenAL via LWJGL)**
  - Sons de pas dynamiques synchronisés avec le déplacement du joueur et adaptés au matériau du bloc sous les pieds (herbe, pierre, terre, bois, sable).
  - Effets sonores de cassage/pose de blocs avec légère variation aléatoire de hauteur (`pitch` $\in [0.85, 1.15]$) pour éviter toute monotonie auditive.
  - Ambiances sonores (vent en haute altitude, réverbération/gouttes d'eau dans les grottes profondes).
- [ ] **Optimisation de la Sauvegarde & Compression des Chunks** — [`WorldStorage.java`](../src/main/java/engine/world/WorldStorage.java)
  - Sauvegarde différentielle / RLE ou compression LZ4/GZIP des chunks modifiés uniquement (évitant d'écrire sur disque les chunks purement procéduraux non modifiés par le joueur) et sérialisation de l'inventaire joueur.

---

## Ordre de Priorité Recommandé (Jalons d'Implémentation)

1. **Jalon 1 — Cœur du Gameplay Voxel (Playability & Interaction) :**
   - Fiabilisation des collisions inter-chunks + `deltaTime` (`PhysicsEngine` / `CollisionDetector`)
   - Raycasting DDA (`VoxelRaycaster`) + Contour de sélection du bloc visé (`Block Outline`)
   - Cassage / Pose de blocs avec remeshing instantané + Hotbar 9 slots & déblocage des blocs dans `BlockRegistry` (`log`, `planks`, `cobblestone`, `leaves`, `glass`)
2. **Jalon 2 — Outils In-Game & UI Propre :**
   - Chat interactif (`T`) & Système de commandes (`/*` : `/tp`, `/time set`, `/gamemode`)
   - Menu Pause (`ESC`), `SettingsScreen` fonctionnel et menu d'attribution des touches (`Controls`)
3. **Jalon 3 — Richesse du Monde & Blocs Spéciaux :**
   - Passe de rendu Transparente / Cutout (`glass`, `leaves`, `water`, herbes croisées)
   - Eau au niveau de la mer + Biomes multi-bruits + Génération d'arbres et minerais
4. **Jalon 4 — Vie, Lumière & Immersion :**
   - Éclairage voxel propagé (grottes sombres + torches) & Brouillard atmosphérique
   - Entités (`ItemEntity`, Mobs avec modèles articulés et IA) + Audio 3D OpenAL
