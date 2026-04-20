# Rapport : Pipeline de rendu des ombres (mode OFF — sans leapfrogging)

**Codebase** : TOB Voxel Project — moteur Java/LWJGL, OpenGL 3.2 Core
**Scope** : `renderOffMode()` uniquement — leapfrog désactivé, buffer A seul

---

## 0. Vue d'ensemble du pipeline

```
CPU (Java, chaque frame)
│
├─ 1. Paramètres de rendu (RenderConfig) ─────────────────── constantes statiques
│
├─ 2. Cascade splits (FrustumSplitter) ───────────────────── calcul lazy-cached
│
├─ 3. Light basis (CSMCalculator.updateLightBasis) ────────── base orthonormale lumière
│
├─ 4. Sphere radii + texel sizes (updateSphereRadii) ──────── fitting frustum → sphère
│
├─ 5. Light-space matrix par cascade (computeCascadeMatrix) ─ view ortho + snap texel
│
├─ 6. Shadow depth pass (ShadowRenderer) ──────────────────── rasterisation scene → FBO
│
└─ 7. Main pass voxel (VoxelRenderer + fragment shader) ────── sampling atlas + PCF
     │
     ├─ Vertex shader : projection light-space par cascade
     └─ Fragment shader : cascade select → bias → PCF sample → shadow factor
```

---

## 1. Configuration de référence

Depuis [`RenderConfig.java`](src/main/java/engine/core/config/RenderConfig.java) :

| Paramètre | Valeur |
|---|---|
| `CSM_CASCADE_COUNT` | 3 |
| `CSM_SHADOW_MAP_SIZE` | 2048 px |
| `CSM_SHADOW_FAR` | 400.0 m |
| `CSM_SPLIT_LAMBDA` | 0.55 |
| `CSM_DEPTH_MARGIN_RATIO` | 0.5 (50%) |
| `CAMERA_FOV` | 90° |
| `CAMERA_NEAR` | 0.1 m |
| `CAMERA_FAR` | 1000.0 m |
| Format depth | `GL_DEPTH_COMPONENT32F` |
| Filtre atlas | `GL_LINEAR` |

---

## 2. Calcul des Cascade Splits

**Fichier** : [`FrustumSplitter.java`](src/main/java/engine/render/shadow/FrustumSplitter.java)

La méthode de partitionnement dite *Practical Split Scheme* (Engel 2006) blende distribution logarithmique et linéaire :

$$C_{\log}^{(i)} = z_{\text{near}} \cdot \left(\frac{z_{\text{far}}}{z_{\text{near}}}\right)^{i/n}$$

$$C_{\text{lin}}^{(i)} = z_{\text{near}} + (z_{\text{far}} - z_{\text{near}}) \cdot \frac{i}{n}$$

$$\text{split}[i] = \lambda \cdot C_{\log}^{(i)} + (1 - \lambda) \cdot C_{\text{lin}}^{(i)}$$

Le tableau de sortie a `n+1` éléments : `splits[0] = z_near`, `splits[n] = z_far`.

**Application numérique** avec $z_{\text{near}} = 0.1$, $z_{\text{far}} = 400$, $n = 3$, $\lambda = 0.55$ :

| $i$ | $p = i/3$ | $C_{\log}^{(i)}$ | $C_{\text{lin}}^{(i)}$ | $\text{split}[i]$ |
|---|---|---|---|---|
| 0 | — | — | — | **0.1** |
| 1 | 0.333 | $0.1 \cdot 4000^{0.333} \approx 7.37$ | $0.1 + 399.9 \cdot 0.333 \approx 133.4$ | $0.55 \times 7.37 + 0.45 \times 133.4 \approx$ **64.1** |
| 2 | 0.667 | $0.1 \cdot 4000^{0.667} \approx 54.3$ | $0.1 + 399.9 \cdot 0.667 \approx 266.8$ | $0.55 \times 54.3 + 0.45 \times 266.8 \approx$ **149.9** |
| 3 | — | — | — | **400.0** |

**Résultat** :
- Cascade 0 (near) : $[0.1,\ 64.1]$ m
- Cascade 1 (mid) : $[64.1,\ 149.9]$ m
- Cascade 2 (far) : $[149.9,\ 400.0]$ m

> La prédominance du terme logarithmique ($\lambda = 0.55$) concentre la résolution sur le champ proche, où les détails de l'ombrage sont perceptibles.

---

## 3. Fitting frustum → Sphère englobante

**Fichier** : [`CSMCalculator.java:138–181`](src/main/java/engine/render/shadow/CSMCalculator.java#L138-L181), méthode `updateSphereRadii()`

Pour chaque cascade $i$ de bornes $[z_{\text{near}}^{(i)}, z_{\text{far}}^{(i)}]$, on construit une sphère englobante stable en orientation :

**Demi-dimensions du plan lointain** :
$$h_{\text{far}} = z_{\text{far}}^{(i)} \cdot \tan\!\left(\frac{\text{FoV}}{2}\right), \quad w_{\text{far}} = h_{\text{far}} \cdot \text{aspect}$$

**Centre et demi-étendue axiale** :
$$z_{\text{center}}^{(i)} = \frac{z_{\text{near}}^{(i)} + z_{\text{far}}^{(i)}}{2}, \quad z_{\text{extent}}^{(i)} = z_{\text{far}}^{(i)} - z_{\text{center}}^{(i)}$$

**Rayon de la sphère** (distance au coin le plus éloigné du plan far) :
$$r_{\text{raw}}^{(i)} = \sqrt{w_{\text{far}}^2 + h_{\text{far}}^2 + \left(z_{\text{extent}}^{(i)}\right)^2}$$

**Stabilisation et quantisation** :
$$r^{(i)} = \left\lceil r_{\text{raw}}^{(i)} \times 1.05 \times 16 \right\rceil \div 16$$

Le facteur $\times 1.05$ est une marge de sécurité de 5%. La quantification $\lceil \cdot \times 16 \rceil / 16$ arrondit au multiple de $\tfrac{1}{16}$ supérieur, assurant que le rayon ne décroît jamais entre frames (stabilité shadow map).

**Taille texel par cascade** :
$$\text{texelSize}^{(i)} = \frac{2 \cdot r^{(i)}}{\text{shadowMapSize}}$$

La texture couvre $[-r, +r]$ en XY dans l'espace lumière. Un texel = $\text{texelSize}$ mètres monde dans ce référentiel.

**Application numérique** (FoV 90°, aspect 16/9 ≈ 1.778, shadowMapSize 2048) :

$\tan(45°) = 1.0$

| Cascade | $z_{\text{far}}$ | $h_{\text{far}}$ | $w_{\text{far}}$ | $z_{\text{extent}}$ | $r_{\text{raw}}$ | $r^{(i)}$ (≈) | texelSize (m) |
|---|---|---|---|---|---|---|---|
| 0 | 64.1 | 64.1 | 114.0 | 32.0 | $\sqrt{114^2 + 64.1^2 + 32^2} \approx 134$ | ~141 | ~0.138 |
| 1 | 149.9 | 149.9 | 266.6 | 74.9 | $\approx 313$ | ~330 | ~0.322 |
| 2 | 400.0 | 400.0 | 711.2 | 200.0 | $\approx 835$ | ~880 | ~0.859 |

> L'utilisation d'une sphère plutôt que d'un AABB est la clé de la **stabilité rotationnelle** : le volume englobant ne change pas quand la caméra pivote, évitant le shimmer de shadow map à chaque rotation.

---

## 4. Construction de la base orthonormale lumière

**Fichier** : [`CSMCalculator.java:220–261`](src/main/java/engine/render/shadow/CSMCalculator.java#L220-L261), méthode `updateLightBasis()`

La direction lumière est l'inverse de la direction soleil (shadow casting) :
$$\hat{L} = -\hat{S}_{\text{sun}} / \|\hat{S}_{\text{sun}}\|$$

La base $(\hat{R}, \hat{U}, \hat{L})$ est construite par la méthode **Pixar/Duff** (JCGT 2017), numériquement stable même quand $\hat{L}$ est aligné avec l'axe Z :

Soient $x, y, z$ les composantes de $\hat{L}$ :
$$\text{sign} = \text{copysign}(1, z), \quad a = \frac{-1}{\text{sign} + z}, \quad b = x \cdot y \cdot a$$

$$\hat{R} = \bigl( -(1 + \text{sign} \cdot x^2 \cdot a),\ -\text{sign} \cdot b,\ \text{sign} \cdot x \bigr)$$

$$\hat{U} = \bigl( b,\ \text{sign} + y^2 \cdot a,\ -y \bigr)$$

On vérifie : $\hat{R} \perp \hat{U} \perp \hat{L}$, et $\|\hat{R}\| = \|\hat{U}\| = 1$.

La base est **figée** après calcul (MAP↔MATRIX↔BASIS LOCK) : elle ne change pas tant que le soleil ne dépasse pas le seuil de quantisation de 0.4° (`SUN_QUANTIZATION_THRESHOLD = 0.006981317f` rad). En mode OFF, ce seuil n'est pas appliqué — `updateLightBasis(sunDir)` force toujours la mise à jour.

---

## 5. Calcul de la Light-Space Matrix

**Fichier** : [`CSMCalculator.java:341–464`](src/main/java/engine/render/shadow/CSMCalculator.java#L341-L464), méthode `computeCascadeMatrix()`

### 5.1 Centre de la sphère en espace monde

La sphère de la cascade $i$ est centrée à une distance $d^{(i)} = z_{\text{center}}^{(i)}$ devant la caméra :

$$\mathbf{C}_{\text{WS}}^{(i)} = \hat{F}_{\text{cam}} \cdot d^{(i)} + \mathbf{P}_{\text{cam}}$$

où $\hat{F}_{\text{cam}}$ est le vecteur forward caméra et $\mathbf{P}_{\text{cam}}$ la position caméra.

### 5.2 Projection en espace lumière (pré-snap)

$$c_X^{(i)} = \mathbf{C}_{\text{WS}}^{(i)} \cdot \hat{R}, \quad c_Y^{(i)} = \mathbf{C}_{\text{WS}}^{(i)} \cdot \hat{U}$$

### 5.3 Texel Snapping

C'est le mécanisme anti-shimmer fondamental. Sans snap, tout déplacement continu de caméra fait glisser la shadow map d'une fraction de texel → scintillement des pénombres.

$$s_X^{(i)} = \left\lfloor \frac{c_X^{(i)}}{\text{texelSize}^{(i)}} + 0.5 \right\rfloor \cdot \text{texelSize}^{(i)}$$

$$s_Y^{(i)} = \left\lfloor \frac{c_Y^{(i)}}{\text{texelSize}^{(i)}} + 0.5 \right\rfloor \cdot \text{texelSize}^{(i)}$$

En Java (`Math.round` = arrondi à l'entier le plus proche) :

```java
float snappedX = Math.round(centerLS_X / texelSize) * texelSize;
float snappedY = Math.round(centerLS_Y / texelSize) * texelSize;
```

**Propriété** : $s_X^{(i)}$ est toujours un multiple exact de $\text{texelSize}^{(i)}$. Le centre de la shadow map "saute" par incréments d'un texel entier. La carte se déplace par quanta discrets → **aucune translation sub-texel → pas de shimmer**.

**Granularité** :
$$\Delta s = \text{texelSize}^{(i)} = \frac{2\, r^{(i)}}{N_{\text{px}}}$$

Pour cascade 0 : $\Delta s_0 \approx 0.138$ m. La shadow map "saute" de 13.8 cm à la fois.

### 5.4 Plage Z (depth range)

$$c_Z^{(i)} = \mathbf{C}_{\text{WS}}^{(i)} \cdot \hat{L}$$

$$z_{\text{min}}^{(i)} = c_Z^{(i)} - r^{(i)}, \quad z_{\text{max}}^{(i)} = c_Z^{(i)} + r^{(i)}$$

**Marge casters** (`CSM_DEPTH_MARGIN_RATIO = 0.5`) :
$$\Delta_{\text{margin}} = (z_{\text{max}} - z_{\text{min}}) \cdot 0.5 = r^{(i)}$$

$$z_{\text{min}}^{(i)} \mathrel{-}= r^{(i)}, \quad z_{\text{max}}^{(i)} \mathrel{+}= r^{(i)}$$

Soit une profondeur ortho totale :
$$d_Z^{(i)} = z_{\text{max}}^{(i)} - z_{\text{min}}^{(i)} = 4 \cdot r^{(i)}$$

La marge de 50% de part et d'autre garantit que les casters situés **derrière** le frustum de vue (hors de la sphère) sont quand même rasterisés dans la shadow map.

### 5.5 Construction de la Light-View Matrix

La light-view est une matrice $4\times4$ colonne-major (convention JOML/OpenGL), représentant un changement de base $(\hat{R}, \hat{U}, \hat{L})$ centré en $(s_X, s_Y, z_{\text{min}})$ :

$$M_{\text{view}} = \begin{pmatrix}
R_x & U_x & L_x & 0 \\
R_y & U_y & L_y & 0 \\
R_z & U_z & L_z & 0 \\
-s_X & -s_Y & -z_{\text{min}} & 1
\end{pmatrix}$$

En notation JOML (colonne $j$, ligne $i$ = `m{j}{i}`) :

```
m00=Rx  m10=Ry  m20=Rz  m30=-sX
m01=Ux  m11=Uy  m21=Uz  m31=-sY
m02=Lx  m12=Ly  m22=Lz  m32=-zMin
m03=0   m13=0   m23=0   m33=1
```

La translation $(-s_X, -s_Y, -z_{\text{min}})$ place l'origine du repère lumière au coin near de la boîte ortho.

**Vérification** : pour un point monde $\mathbf{P}$,

$$M_{\text{view}} \cdot \mathbf{P} = \begin{pmatrix} \mathbf{P} \cdot \hat{R} - s_X \\ \mathbf{P} \cdot \hat{U} - s_Y \\ \mathbf{P} \cdot \hat{L} - z_{\text{min}} \\ 1 \end{pmatrix}$$

La composante $z$ vaut 0 sur le plan near, $d_Z$ sur le plan far.

### 5.6 Projection orthographique (LH)

JOML `orthoLH(-r, r, -r, r, 0, d_Z)` produit :

$$M_{\text{proj}} = \begin{pmatrix}
\dfrac{1}{r} & 0 & 0 & 0 \\[6pt]
0 & \dfrac{1}{r} & 0 & 0 \\[6pt]
0 & 0 & \dfrac{2}{d_Z} & 0 \\[6pt]
0 & 0 & -1 & 1
\end{pmatrix}$$

Cette matrice mappe :
- $X \in [-r,\ r] \to [-1,\ 1]$
- $Y \in [-r,\ r] \to [-1,\ 1]$
- $Z \in [0,\ d_Z] \to [-1,\ 1]$ (convention LH : near = −1, far = +1)

### 5.7 Light-Space Matrix finale

$$M_{\text{LS}}^{(i)} = M_{\text{proj}}^{(i)} \cdot M_{\text{view}}^{(i)}$$

Pour un point monde $\mathbf{P}$, la position NDC lumière est :
$$\mathbf{P}_{\text{NDC}} = M_{\text{LS}}^{(i)} \cdot \mathbf{P}$$

Dans le fragment shader (après divide perspective — trivial ici car $w = 1$ en ortho) :
$$\text{projCoords} = \frac{\mathbf{P}_{\text{NDC}}.xyz}{\mathbf{P}_{\text{NDC}}.w} \cdot 0.5 + 0.5 \in [0,1]^3$$

---

## 6. Shadow Depth Pass (Rasterisation)

**Fichier** : [`ShadowRenderer.java:606–654`](src/main/java/engine/render/renderer/ShadowRenderer.java#L606-L654), méthode `renderShadowMapIntoAtlas()`

**État GL configuré** :
- `GL_DEPTH_TEST` activé, `GL_DEPTH_FUNC = GL_LESS`
- `glColorMask(false, …)` : aucune écriture couleur
- `GL_CULL_FACE` activé, `GL_CULL_FACE = GL_BACK` : culling back-faces
- Viewport : $N \times N$ (2048 × 2048)
- FBO : `fbo` avec `glFramebufferTextureLayer(atlas, layer = atlasOffsetA + i)` pour la cascade $i$

**Boucle de rendu** (3 cascades en mode OFF) :

```java
for (int i = 0; i < cascadeCount; i++) {
    layer = atlasOffsetA + i;
    glFramebufferTextureLayer(fbo, GL_DEPTH_ATTACHMENT, shadowAtlas, 0, layer);
    glClear(GL_DEPTH_BUFFER_BIT);
    depthShader.setMat4("lightSpaceMatrix", data.lightSpaceMatrices()[i]);
    world.renderDepth(depthShader);
}
```

Chaque cascade est rendue dans une layer distincte de la `GL_TEXTURE_2D_ARRAY` (`shadowAtlas`). En mode OFF : `atlasOffsetA = 0`, donc layers 0, 1, 2.

**Depth shader** : transforme chaque vertex par `lightSpaceMatrix`, écrit $z/w$ normalisé dans le depth buffer. Pas de fragment shader custom (depth seul).

---

## 7. Vertex Shader : projection light-space

**Fichier** : [`voxel_vertex_shader.glsl:49–96`](src/main/resources/shaders/voxel_vertex_shader.glsl#L49-L96)

Pour chaque vertex, on calcule les 3 positions en espace lumière (transmises au fragment shader par interpolation) :

```glsl
vec4 world = modelMatrix * vec4(aPos, 1.0);
vViewDepth = -(viewMatrix * world).z;  // depth positif (caméra sur -Z)

for (int i = 0; i < cascadeCount; i++) {
    vLightSpacePos[i] = lightSpaceMatrices[i] * world;
}
```

`vViewDepth` est la profondeur view-space positive, utilisée pour la sélection de cascade dans le fragment shader.

---

## 8. Fragment Shader : sélection cascade, biais, PCF

**Fichier** : [`voxel_fragment_shader.glsl`](src/main/resources/shaders/voxel_fragment_shader.glsl)

### 8.1 Sélection de cascade

```glsl
int selectCascade(float viewDepth) {
    for (int i = 0; i < cascadeCount; i++) {
        if (viewDepth < cascadeSplits[i + 1])
            return i;
    }
    return cascadeCount - 1;
}
```

Le fragment appartient à la cascade $i$ si $\text{viewDepth} \in [\text{splits}[i],\ \text{splits}[i+1])$.

### 8.2 Projection NDC → UV shadow map

```glsl
vec4 lightSpacePos = vLightSpacePos[cascade];
vec3 projCoords = lightSpacePos.xyz / lightSpacePos.w;
projCoords = projCoords * 0.5 + 0.5;
```

Soit $\mathbf{p} = M_{\text{LS}}^{(c)} \cdot \mathbf{P}_{\text{world}}$ avec $w = 1$ (ortho), donc :

$$\text{UV} = \frac{\mathbf{p}.xy}{w} \cdot 0.5 + 0.5, \quad \text{refDepth} = \frac{p_z}{w} \cdot 0.5 + 0.5$$

### 8.3 Calcul du biais

**Fichier** : [`voxel_fragment_shader.glsl:201–232`](src/main/resources/shaders/voxel_fragment_shader.glsl#L201-L232)

Le biais prévient le *shadow acne* (auto-ombrage) en écartant le depth de référence du depth stocké :

$$\cos\theta = \max(\hat{N} \cdot \hat{L},\ 0.001)$$

$$\sin\theta = \sqrt{1 - \cos^2\theta}, \quad \tan\theta = \frac{\sin\theta}{\cos\theta}$$

$$k_c^{(i)} = \min\!\left(\frac{\text{texelSize}^{(i)}}{\text{texelSize}^{(0)}},\ 3\right)$$

$$\text{bias}^{(i)} = \underbrace{\bigl(0.0005 + 0.0003 \cdot \tan\theta\bigr)}_{\text{slope-scaled}} \cdot k_c^{(i)}$$

- **Terme constant** $0.0005$ : plancher pour les surfaces face à la lumière
- **Terme slope-scaled** $0.0003 \cdot \tan\theta$ : croît à l'approche de l'angle rasant (surfaces quasi-parallèles à $\hat{L}$) ; $\tan\theta$ est borné à 10 pour éviter les artefacts à 90°
- **Facteur cascade** $k_c^{(i)}$ : les cascades lointaines ont des texels plus grands → biais proportionnellement plus grand

Pour la cascade 2 (~3× texel de la cascade 0) : $\text{bias}^{(2)} \approx 3 \times \text{bias}^{(0)}$.

### 8.4 PCF — World-Stable (SHADOW_FILTER_MODE 2)

**Fichier** : [`voxel_fragment_shader.glsl:289–329`](src/main/resources/shaders/voxel_fragment_shader.glsl#L289-L329)

**Étape 1 — Cellule monde** :
$$\text{cell} = \left\lfloor \mathbf{P}_{\text{world}}.xz \;/\; 4.0 \right\rfloor \in \mathbb{Z}^2$$

**Étape 2 — Hash entier déterministe** :

```glsl
int n = cell.x * 73856093 ^ cell.y * 19349663;
n = (n << 13) ^ n;
float h = float((n*(n*n*15731 + 789221) + 1376312589) & 0x7fffffff)
        / float(0x7fffffff);
```

Produit $h \in [0,1)$ constant pour toute la durée de vie de la cellule monde.

**Étape 3 — Angle de rotation** :
$$\theta_{\text{cell}} = h \cdot 2\pi$$

**Étape 4 — Matrice de rotation 2D** :
$$R_{\theta} = \begin{pmatrix} \cos\theta_{\text{cell}} & -\sin\theta_{\text{cell}} \\ \sin\theta_{\text{cell}} & \cos\theta_{\text{cell}} \end{pmatrix}$$

**Étape 5 — Rayon PCF** (adapté à la cascade) :
$$\rho^{(c)} = 1.5 \cdot \min\!\left(\frac{\text{texelSize}^{(c)}}{\text{texelSize}^{(0)}},\ 2\right) \cdot \frac{1}{N_{\text{px}}}$$

**Étape 6 — Boucle d'accumulation** (`PCF_SAMPLE_COUNT = 4` échantillons) :

$$\text{shadow} = \frac{1}{4} \sum_{k=0}^{3} \left[ \text{depth}\!\left(\text{UV} + R_\theta \cdot \mathbf{d}_k \cdot \rho^{(c)}\right) \geq \text{refDepth} - \text{bias} \right]$$

où $\mathbf{d}_k$ est le $k$-ième point du disque de Poisson à 8 points (4 premiers utilisés). La comparaison manuelle `depth >= refDepth - bias` retourne 1.0 (éclairé) ou 0.0 (ombré).

**Propriété world-stable** : l'angle $\theta_{\text{cell}}$ dépend uniquement de la position monde quantisée, pas de l'angle solaire ni des coordonnées UV shadow map. Le pattern PCF est donc **invariant par rotation de la lumière** — pas de shimmer lors du déplacement du soleil.

---

## 9. Récapitulatif des transformations par couche

```
Vertex monde P_world
│
│  M_LS^(i) = M_proj^(i) · M_view^(i)
▼
P_NDC = M_LS^(i) · P_world         [dans vertex shader, per cascade]
│
│  / w  (trivial, w=1 en ortho)
│  * 0.5 + 0.5
▼
projCoords ∈ [0,1]³                 [dans fragment shader]
│                                   ├─ .xy → UV de sampling shadow map
│                                   └─ .z  → refDepth (à comparer)
│
│  texture(shadowAtlas, vec3(UV, layer = atlasOffsetA + cascade))
▼
storedDepth ∈ [0,1]                 [profondeur rasterisée du pass depth]
│
│  storedDepth >= refDepth - bias  ?
▼
shadowFactor ∈ {0.0, 1.0}          [puis moyenné sur 4 taps PCF → ∈ [0,1]]
```

---

## 10. Cohérence snap ↔ atlas layer

**Fichier** : [`ShadowRenderer.java:836–848`](src/main/java/engine/render/renderer/ShadowRenderer.java#L836-L848)

En mode OFF, la détection de "besoin de re-rendu" n'est pas utilisée (re-rendu systématique chaque frame). Les indices snap servent uniquement en mode `RUNNING` :

$$\text{snapIx}_A = \text{round}\!\left(\frac{-M_{\text{LS}}^{(0)}.m30}{\text{texelSize}^{(0)}}\right)$$

`m30` est la translation X de `lightView`, soit $-s_X$. Donc $-M.m30 = s_X$. Le snap index est $s_X / \text{texelSize}^{(0)}$, qui est toujours entier par construction (§5.3). Un changement de cet entier signifie que la shadow map a glissé d'un texel dans la direction X.

---

## 11. Invariants de stabilité garantis

| Propriété | Mécanisme | Garantie mathématique |
|---|---|---|
| Pas de shimmer radial | Fitting sphère | $r^{(i)}$ constant pour toute rotation caméra |
| Pas de shimmer translationnel | Texel snap | $s_X, s_Y \in \mathbb{Z} \cdot \text{texelSize}$ |
| Pas de shimmer PCF | World-stable hash | $\theta_{\text{cell}} = f\!\left(\lfloor \mathbf{P}.xz / 4 \rfloor\right)$ invariant |
| Pas d'acne slope | Biais tan-based | bias $\propto \tan\theta \to \infty$ quand $\theta \to 90°$ (borné à $\tan \leq 10$) |
| Cascades cohérentes | `copySplitDistances` | splits partagés entre tous les buffers |

---

*Rapport généré à partir de l'analyse statique complète des fichiers [`CSMCalculator.java`](src/main/java/engine/render/shadow/CSMCalculator.java), [`FrustumSplitter.java`](src/main/java/engine/render/shadow/FrustumSplitter.java), [`ShadowRenderer.java`](src/main/java/engine/render/renderer/ShadowRenderer.java), [`voxel_vertex_shader.glsl`](src/main/resources/shaders/voxel_vertex_shader.glsl), [`voxel_fragment_shader.glsl`](src/main/resources/shaders/voxel_fragment_shader.glsl), [`RenderConfig.java`](src/main/java/engine/core/config/RenderConfig.java).*
