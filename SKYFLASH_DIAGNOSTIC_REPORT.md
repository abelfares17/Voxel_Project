# Rapport d'analyse des Sky Flashes - SkyFlashTrace

**Date du test**: 17 février 2026, 23:09:49 - 23:10:05
**Fichier de log analysé**: `logs/skyflash_trace_20260217_230949.log`
**Conditions de test**: Position de caméra fixe, rotation de l'angle de caméra

---

## Résumé exécutif

L'analyse du fichier de log révèle que le phénomène perçu comme des "sky flashes" n'est **PAS** causé par des anomalies GL, des fuites d'état, ou des sauts de textures/matrices. Ce que l'utilisateur observe est en fait **l'évolution continue et normale de la couleur du ciel** due au cycle jour/nuit, amplifiée par le mouvement de rotation de la caméra qui modifie l'illumination perçue de la scène.

---

## Analyse détaillée

### 1. État GL - Aucune anomalie détectée

**Résultat**: Toutes les vérifications GL sont **identiques** entre les frames, y compris au moment des swaps.

| Paramètre | Valeur constante |
|-----------|-----------------|
| FBO | 0 (default framebuffer) |
| Viewport | [0, 0, 3024, 1832] |
| depthTest | true |
| depthMask | 1 |
| depthFunc | 0x201 (GL_LESS) |
| blend | false |
| colorMask | [1, 1, 1, 1] |
| glGetError | OK (toujours) |

**Conclusion**: Aucune fuite d'état GL ne peut expliquer les variations visuelles observées.

---

### 2. Bindings de textures shadow - Cohérents

Les bindings de textures sont **toujours cohérents** avec l'état leapfrog:

| État | unit1 (primaryTexId) | unit2 (secondaryTexId) |
|------|---------------------|------------------------|
| Transition (A→B) | texId=3 | texId=4 |
| Transition (B→A) | texId=4 | texId=3 |
| Stable (pas de transition) | texId=3 ou 4 (primary) | même ID |

**Exemple de swap frame 477**:
```
AVANT (frame 476): binding: primaryTexId=4 secondaryTexId=3 units=[1,2]
APRÈS (frame 477): binding: primaryTexId=3 secondaryTexId=3 units=[1,2]
```

**Conclusion**: Les textures sont correctement liées. Pas de désynchronisation matrix↔texture.

---

### 3. Matrices shadow - Cohérentes avec les textures

La règle MAP↔MATRIX LOCK est **respectée**. Au moment du swap, la matrice A correspond bien à la texture qui vient d'être renddue:

**Frame 477 - SWAP**:
```
AVANT:  matrixHash sent A: m00=-0,001697 ... m30=4,173829 m31=0,601563
        matrixHash sent B: m00=-0,001723 ... m30=4,178711 m31=0,580078

APRÈS:  matrixHash sent A: m00=-0,001723 ... m30=4,178711 m31=0,580078  ← Devient la nouvelle primary
        matrixHash sent B: null
```

La matrice B (avant swap) devient exactement la matrice A (après swap). **Pas de saut de matrice**.

**Conclusion**: Aucun mismatch matrix/texture au moment du swap.

---

### 4. Évolution du skyColor - Source du phénomène observé

**C'est ici que se trouve l'explication du comportement observé.**

| Frame | Timestamp | skyColor RGB | Delta vs précédent |
|-------|-----------|--------------|-------------------|
| 11 | 23:09:49.895 | (0.5988, 0.3578, 0.2319) | - |
| 21 (SWAP) | 23:09:49.988 | (0.5987, 0.3587, 0.2333) | R: -0.0001, G: +0.0009, B: +0.0014 |
| 56 | 23:09:50.841 | (0.5975, 0.3666, 0.2452) | R: -0.0012, G: +0.0079, B: +0.0119 |
| 344 (SWAP) | 23:10:00.923 | (0.5834, 0.4594, 0.3864) | - |
| 380 (SWAP) | 23:10:02.147 | (0.5816, 0.4706, 0.4036) | R: -0.0018, G: +0.0112, B: +0.0172 |
| 477 (SWAP) | 23:10:05.830 | (0.5765, 0.5045, 0.4551) | - |

**Évolution totale sur ~16 secondes de test**:
- **Rouge**: 0.5988 → 0.5765 = **-0.0223 (-3.7%)**
- **Vert**: 0.3578 → 0.5045 = **+0.1467 (+41%)**
- **Bleu**: 0.2319 → 0.4551 = **+0.2232 (+96%)**

Cette évolution correspond à la **transition dawn → day** dans le cycle jour/nuit:
- `timeOfDay` passe de 0.001430 à 0.028033 (~2.7% du cycle)
- La couleur passe d'un orange-dawn vers un bleu ciel plus prononcé

---

### 5. Évolution du sunDir et ambient

| Frame | sunDir (x, y, z) | ambient |
|-------|------------------|---------|
| 11 | (0.9805, 0.0088, 0.1961) | 0.3837 |
| 344 | (0.9730, 0.1218, 0.1961) | 0.3985 |
| 477 | (0.9655, 0.1716, 0.1961) | 0.4052 |

**Observations**:
- `sunDir.y` augmente de 0.0088 à 0.1716 (le soleil monte)
- `ambient` augmente de 0.3837 à 0.4052 (+5.6%)
- `sunDir.x` diminue légèrement (0.9805 → 0.9655)

Ces changements affectent directement l'illumination de la scène via le calcul de shadow et l'éclairage diffus.

---

### 6. Impact de la rotation de caméra

Bien que les logs ne capturent pas directement l'angle de caméra, l'utilisateur a confirmé avoir **tourné la caméra tout en gardant la position fixe**. Ceci a les effets suivants:

1. **Changement de l'angle soleil-surface visible**: Les surfaces vues par la caméra changent d'orientation relative au soleil
2. **Variation de la proportion ombre/lumière visible**: En tournant, on voit plus ou moins de zones ombrées
3. **Perception amplifiée**: Le mouvement de caméra fait percevoir les changements d'illumination de manière plus prononcée

---

## Réponses aux questions diagnostiques

### Q1: Y a-t-il un mismatch matrix A vs texture A au frame de flash?
**NON**. Les matrices et textures sont toujours synchronisées. Au swap, la matrice B devient A et correspond exactement à la texture fraîchement rendue.

### Q2: L'état GL est-il identique aux frames voisins au moment du flash?
**OUI**. L'état GL (FBO, viewport, depth, color mask) est **100% identique** sur tous les frames loggués.

### Q3: Le flash survient-il quand leapfrogActive passe de 1→0 ou quand primaryTexId change?
**Aucun flash détecté**. Les transitions sont lisses:
- Frame 476: `leapfrogActive=1, shadowCrossfade=0.9`
- Frame 477: `leapfrogActive=0, shadowCrossfade=1.0` (SWAP)
- La transition est graduelle via le crossfade.

---

## Conclusion

Le phénomène observé n'est **pas un bug** mais le **comportement attendu** du système d'illumination:

1. **Le cycle jour/nuit** fait évoluer continuellement `skyColor`, `sunDir`, et `ambient`
2. **La rotation de caméra** change les surfaces visibles et leur orientation par rapport au soleil
3. **La combinaison des deux** crée une perception de "changement quasi permanent de la couleur du ciel"

### Ce qui fonctionne correctement:
- Le leapfrog CSM avec crossfade smooth (0.0 → 1.0 en 10 frames)
- La synchronisation MAP↔MATRIX
- L'état GL propre entre tous les renderers
- Les bindings de textures shadow

### Recommandation

Si l'évolution de couleur est trop perceptible/gênante, les options sont:
1. **Ralentir le cycle jour/nuit** (`timeOfDay` avance de ~0.00001-0.00005 par frame)
2. **Activer le freeze du sky color** (touche K) pour figer à la couleur de jour
3. **Accepter le comportement** comme réaliste (le ciel change réellement au lever du soleil)

---

## Données brutes de référence

### Première transition (frames 11-21)
```
Frame 11 - TRANSITION_START
  skyColor=(0.5988, 0.3578, 0.2319)
  primaryTexId=3, secondaryTexId=4
  shadowCrossfade=0.0000

Frame 21 - TRANSITION_END_SWAP
  skyColor=(0.5987, 0.3587, 0.2333)
  primaryTexId=4, secondaryTexId=4 (B devient primary)
  shadowCrossfade=1.0000
```

### Dernière transition capturée (frames 468-477)
```
Frame 468 - TRANSITION_START
  skyColor=(0.5768, 0.5021, 0.4515)
  primaryTexId=4, secondaryTexId=3
  shadowCrossfade=0.0000

Frame 477 - TRANSITION_END_SWAP
  skyColor=(0.5765, 0.5045, 0.4551)
  primaryTexId=3, secondaryTexId=3 (A devient primary)
  shadowCrossfade=1.0000
```

**Delta skyColor pendant cette transition de 10 frames**:
- R: -0.0003 (négligeable)
- G: +0.0024 (2.4‰)
- B: +0.0036 (3.6‰)

Ce delta est **imperceptible visuellement** - aucun "flash" ne devrait être visible à ce niveau de changement.
