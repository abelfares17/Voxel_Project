# Orientation caméra et curseur — 25 octobre 2026

## Corrections

`WorldStorage` sauvegardait déjà x/y/z/yaw/pitch. Le constructeur de `Game` remettait ensuite yaw/pitch à zéro après `loadPlayer` : cette réinitialisation est supprimée. Le format des sauvegardes ne change pas. Le harnais conserve son orientation forcée de test.

`Input` n’émet plus de delta de rotation lorsque le curseur est libre ou la fenêtre sans focus. La première mesure après capture/reprise de focus sert de référence, sans rotation. Les coordonnées UI restent mises à jour. `Game` traite Échap/capture avant que le joueur consomme les deltas : Échap libère le curseur, le clic gauche le recapture, sans réorienter la caméra.

Build `installDist` : PASS. `PlayerOrientationTest` : PASS avec fenêtre GLFW cachée, sauvegardes temporaires, vraie construction de Game après lecture de player.dat, mouvements capturés, libération/recapture, perte/reprise de focus simulée via le traitement des échantillons, deuxième sauvegarde/rechargement. Le test n’injecte pas une touche physique Échap dans le système d’exploitation.

Depuis la racine, pour reproduire sans toucher aux sauvegardes du jeu :

```sh
./gradlew --offline installDist
repo_path="$PWD"
orientation_test_dir=$(mktemp -d /tmp/voxel-orientation.XXXXXX)
javac -cp "$repo_path/build/install/TOB_Voxel_Project/lib/*" \
  -d "$orientation_test_dir/classes" tools/tests/PlayerOrientationTest.java
(
  cd "$orientation_test_dir" &&
  java -XstartOnFirstThread -Dvoxel.testHarness=false \
    -cp "$orientation_test_dir/classes:$repo_path/build/install/TOB_Voxel_Project/lib/*" \
    engine.input.PlayerOrientationTest
)
```

Contrôle manuel : regarder dans une direction oblique, quitter et recharger ; vérifier yaw/pitch. Puis Échap, déplacer le curseur, cliquer pour recapturer : pas de changement de direction jusqu’au mouvement capturé suivant. Vérifier également un aller-retour de focus.

## Ombres au lancement : explication et prochaine étape

Aucun paramètre CSM n’a été modifié ici. `TimeManager` démarre à timeOfDay=0 ; `getSunDataAtTimeOfDay` donne alors une direction proportionnelle à (1,0,0.2), donc exactement horizontale. Le temps solaire n’est pas restauré par la sauvegarde du joueur. Le cycle vaut 600 secondes avec le multiplicateur actuel de 1.

Sur un sol horizontal, la longueur d’ombre est h/tan(élévation). Près de zéro, sa variation est très forte. Avec ce modèle, un obstacle de 10 blocs peut théoriquement produire une ombre d’environ 974 blocs à une seconde de temps solaire écoulé, 195 à cinq secondes, 97 à dix secondes. Les durées murales peuvent différer à cause du clamp dt et du chargement. Le raccourcissement rapide est donc attendu ; un saut précis ou une déformation n’est pas pour autant prouvé normal. Le monde chargé reste fini et les biais/décalages deviennent particulièrement sensibles à incidence rasante.

Prochaine expérience recommandée : rendre l’heure initiale contrôlable, tester exactement la même scène préchargée avec soleil fixe à plusieurs élévations (par exemple 0.5°, 2°, 5°, 15°), puis animer depuis la même pose. Cela distingue géométrie physique, limites du monde chargé et instabilité du CSM. Ne pas masquer les symptômes en augmentant blur ou biais. Ensuite décider séparément si le jeu doit restaurer l’heure du monde ou démarrer au-dessus de l’horizon ; ce choix produit n’a pas été imposé par ce correctif caméra.
