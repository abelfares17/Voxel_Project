# Voxel Game Engine

A lightweight 3D voxel-based game engine built with Java and OpenGL, featuring chunk-based world generation, multithreaded rendering, and a modern UI system with transparent glass-like components.

![Java](https://img.shields.io/badge/Java-17-orange)
![LWJGL](https://img.shields.io/badge/LWJGL-3.3.1-blue)
![OpenGL](https://img.shields.io/badge/OpenGL-3.2-green)
![License](https://img.shields.io/badge/license-MIT-brightgreen)

## 🎮 Features

### Core Engine
- **OpenGL 3.2 Core Profile** - Modern rendering pipeline with VAO/VBO
- **Chunk-based World System** - Efficient infinite world generation
- **Multithreaded Mesh Generation** - Parallel chunk processing for optimal performance
- **Game State Management** - Clean separation between menu, loading, and game states
- **World Persistence** - Save/load worlds with metadata (name, seed, game mode, timestamps)

### Rendering
- **Greedy Meshing Algorithm** - Optimized face culling and mesh generation
- **Texture Atlas** - Efficient block texture management
- **Dynamic Sun/Lighting** - Time-based lighting system with shadow calculations
- **Camera System** - First-person camera with smooth movement
- **UI Rendering** - 2D overlay system with texture and text support

### User Interface
- **Transparent Glass UI** - Modern minimal design with border outlines
- **Interactive Components**:
  - Buttons with hover/click brightening effects
  - Text fields with focus indicators
  - Scrollable lists with selection highlighting
  - High-resolution title fonts (64px)
- **Background Textures** - Full-screen customizable backgrounds per screen
- **Responsive Layout** - Automatic repositioning on window resize

### World Generation
- **Procedural Terrain** - Noise-based height map generation
- **Block Types** - Grass, dirt, stone with distinct textures
- **Chunk Management** - 16×16×256 chunks with efficient neighbor tracking
- **World Compression** - GZip compression for save files

## 🛠️ Technical Stack

| Component | Technology |
|-----------|-----------|
| Language | Java 17 |
| Graphics API | OpenGL 3.2 Core |
| Window/Input | GLFW 3.3.1 |
| Math Library | JOML 1.10.5 |
| Texture Loading | STB Image |
| Font Rendering | STB TrueType |
| Build System | Gradle 8.13 |
| Serialization | Gson 2.10.1 |

## 📁 Project Structure

```
TOB_Voxel_Project/
├── src/main/
│   ├── java/engine/
│   │   ├── core/              # Core engine systems
│   │   │   ├── Application.java
│   │   │   ├── GameState.java
│   │   │   └── states/        # Menu, Loading, Game states
│   │   │       ├── MenuState.java
│   │   │       ├── LoadingState.java
│   │   │       └── GameState.java
│   │   ├── entity/            # Player and entity management
│   │   │   └── Player.java
│   │   ├── input/             # Keyboard and mouse input
│   │   │   └── Input.java
│   │   ├── physics/           # AABB collision detection
│   │   │   ├── AABB.java
│   │   │   └── CollisionDetector.java
│   │   ├── render/            # Rendering systems
│   │   │   ├── camera/        # Camera implementation
│   │   │   ├── core/          # RenderContext, RenderManager
│   │   │   ├── renderer/      # VoxelRenderer, ShadowRenderer
│   │   │   ├── Shader.java
│   │   │   ├── TextRenderer.java
│   │   │   ├── TextureLoader.java
│   │   │   └── UIRenderer.java
│   │   ├── time/              # Time and sun management
│   │   │   ├── TimeManager.java
│   │   │   ├── Sun.java
│   │   │   └── SunData.java
│   │   ├── ui/                # UI system
│   │   │   ├── UIManager.java
│   │   │   ├── Screen.java
│   │   │   ├── component/     # UI components
│   │   │   │   ├── Button.java
│   │   │   │   ├── TextField.java
│   │   │   │   ├── ScrollableList.java
│   │   │   │   └── Label.java
│   │   │   └── screens/       # Menu screens
│   │   │       ├── MainMenuScreen.java
│   │   │       ├── WorldSelectionScreen.java
│   │   │       ├── WorldCreationScreen.java
│   │   │       ├── SettingsScreen.java
│   │   │       └── LoadingScreen.java
│   │   ├── world/             # World management
│   │   │   ├── World.java
│   │   │   ├── Chunk.java
│   │   │   ├── ChunkPos.java
│   │   │   ├── WorldStorage.java
│   │   │   ├── WorldMetadata.java
│   │   │   └── ChunkCompressor.java
│   │   └── Main.java          # Application entry point
│   └── resources/
│       ├── fonts/
│       │   └── Minecraftia.ttf    # Bitmap font
│       ├── shaders/
│       │   ├── voxel_vertex_shader.glsl
│       │   ├── voxel_fragment_shader.glsl
│       │   ├── ui_vertex.glsl
│       │   ├── ui_fragment.glsl
│       │   ├── text_vertex.glsl
│       │   ├── text_fragment.glsl
│       │   ├── depth_vertex_shader.glsl
│       │   └── depth_fragment_shader.glsl
│       └── textures/
│           ├── atlas.png              # Block texture atlas
│           └── backgrounds/
│               └── dirt_background.png
├── saves/                 # World save files (gitignored)
├── build.gradle
├── settings.gradle
└── README.md
```

## 🚀 Getting Started

### Prerequisites

- **Java 17 or higher** ([Download](https://adoptium.net/))
- **Gradle 8.x** (included via wrapper)
- **OpenGL 3.2 compatible GPU**

### Platform-Specific Requirements

- **macOS**: Apple Silicon (M1/M2) or Intel x64
- **Windows**: 64-bit Windows 10/11
- **Linux**: 64-bit with OpenGL support

### Installation

1. **Clone the repository**
   ```bash
   git clone https://github.com/yourusername/voxel-game.git
   cd voxel-game
   ```

2. **Build the project**
   ```bash
   ./gradlew build
   ```

3. **Run the game**
   ```bash
   ./gradlew run
   ```

   On Windows, use `gradlew.bat` instead of `./gradlew`.

### 🚀 Command-Line Launch Guide & JVM Flags (`-Dvoxel.*`)

#### Recommended Ways to Pass Flags

1. **Directly via `./gradlew run` (Recommended — always keeps `-XstartOnFirstThread` on macOS and works with warm Gradle daemons):**
   ```bash
   ./gradlew run -Dvoxel.startHour=17.3 -Dvoxel.timeScale=60 -Dvoxel.skyMode=procedural
   ```

2. **Via `JAVA_OPTS` or `VOXEL_OPTS` with `./gradlew run`:**
   *(Note: `build.gradle` automatically preserves `-XstartOnFirstThread` when parsing `JAVA_OPTS` / `VOXEL_OPTS`)*
   ```bash
   JAVA_OPTS="-Dvoxel.startHour=17.3 -Dvoxel.timeScale=60 -Dvoxel.skyMode=procedural" ./gradlew run
   ```

3. **Via the compiled distribution binary (`installDist` — fastest startup, ideal for benchmarks):**
   *(Note: When running the generated `build/install/TOB_Voxel_Project/bin/TOB_Voxel_Project` script with `JAVA_OPTS` on macOS, or `java -jar`, include `-XstartOnFirstThread` if your shell overrides default JVM args)*
   ```bash
   ./gradlew --offline installDist
   JAVA_OPTS="-Dvoxel.startHour=14.5 -Dvoxel.timeScale=90" ./build/install/TOB_Voxel_Project/bin/TOB_Voxel_Project
   ```

*(If `JAVA_HOME` is not set to Java 17 in your shell on macOS Homebrew, prefix the command with `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.15/libexec/openjdk.jdk/Contents/Home`).*

---

#### Complete Reference of `-Dvoxel.*` System Properties

##### 🌅 Time, Sun/Moon Orbit & Skybox (`TimeManager`, `CelestialState`, `SkyRenderer`)
| Flag | Default | Values | Description |
| :--- | :---: | :---: | :--- |
| `-Dvoxel.startHour` | *(saved / `14.0`)* | `0.0` .. `24.0` | Overrides the starting hour of the day (`6.0` = sunrise, `12.0` = solar noon, `17.5` = sunset, `0.0` = midnight). |
| `-Dvoxel.timeScale` | `1.0` | `>= 0.0` | Multiplies the day/night cycle speed (`60` = 60× faster, `0` = frozen time). |
| `-Dvoxel.pauseTime` | `false` | `true` / `false` | Starts the game with the solar/lunar cycle paused (can also be toggled in-game with `F6`). |
| `-Dvoxel.skyMode` | `procedural` | `procedural` / `legacy` | `procedural` enables the fullfullscreen ray-reconstructed sky dome (twilight scattering, solar/lunar discs, and 512 deterministic stars). |

##### 🧱 Meshing & Vertex Pipeline (`RenderConfig`, `MeshBuilder`, `ShadowMesh`)
| Flag | Default | Values | Description |
| :--- | :---: | :---: | :--- |
| `-Dvoxel.texturePack` | `modern` | `modern` / `legacy` | `modern` loads individual harmonized 16×16 PNGs per block face from `src/main/resources/textures/blocks/*.png` (`grass_top`, `grass_side`, `dirt`, `stone`, `bedrock`); `legacy` uses the original `atlas.png`. |
| `-Dvoxel.naturalTextures` | `true` | `true` / `false` | Enables stochastic per-block UV rotation/mirroring, 4 CTM random variants per block, undulating grass side fringes, and low-frequency biome/strata macro-tinting in the fragment shader. |
| `-Dvoxel.greedyVisual` | `true` | `true` / `false` | Enables 2D Greedy Meshing on visual chunks with repeating intra-tile atlas UVs and preserved per-vertex AO gradients (`+21.5%` FPS, `-185 MB` VRAM). Set `false` for legacy 1×1 unit quads. |
| `-Dvoxel.greedyShadows` | `true` | `true` / `false` | Enables position-only 2D Greedy Meshing with unit-edge ear-clipping triangulation (no T-junction depth cracks) for the shadow depth pass. |
| `-Dvoxel.indexedMeshes` | `true` | `true` / `false` | Uses indexed `GL_UNSIGNED_INT` EBOs (4 vertices / 6 indices per quad) instead of non-indexed `glDrawArrays` (6 vertices per quad). |
| `-Dvoxel.compactVertices` | `true` | `true` / `false` | Packs visual chunk vertices into a 24-byte layout (`ushort3 pos + byte3 normal + float2 UV + float AO`) instead of 36-byte floats. |
| `-Dvoxel.tightCameraBounds` | `false` | `true` / `false` | Tests exact occupied vertical AABB bounds of each chunk against the camera frustum. |
| `-Dvoxel.tightShadowBounds` | `false` | `true` / `false` | Tests exact occupied vertical AABB bounds of each chunk against the cascade light frustum. |

##### ☀️ Cascaded Shadow Mapping (CSM) & Anti-Shimmering (`RenderConfig`, `CSMCalculator`, `ShadowRenderer`)
| Flag | Default | Values | Description |
| :--- | :---: | :---: | :--- |
| `-Dvoxel.shimmerFix` | `true` | `true` / `false` | Enables continuous 4-tap bilinear $3\times 3$ tent PCF filtering, wider inter-cascade blend bands (`15%`), and rotation-invariant quantized Z bounds. Set `false` for legacy 1-tap bilinear PCF. |
| `-Dvoxel.shadowFrustumCulling` | `true` | `true` / `false` | Rejects chunks outside each cascade's orthographic light frustum during the shadow pass. |
| `-Dvoxel.shadowCasterResidency` | `true` | `true` / `false` | Keeps upstream solar shadow-caster chunks resident in memory (lightweight `ShadowMesh`-only residency) so distant mountains cast coherent low-angle shadows. |
| `-Dvoxel.legacyCasterResidency` | `false` | `true` / `false` | Uses the older non-coherent caster residency policy (for A/B benchmarking only). |
| `-Dvoxel.shadowCasterReach` | `320` | `0` .. `1024` | Maximum upstream lightward reach (in blocks) for shadow-caster chunk residency. |
| `-Dvoxel.shadowCasterBudget` | `1024` | `0` .. `2048` | Maximum number of extra shadow-caster chunks kept resident beyond the visible render distance. |
| `-Dvoxel.shadowCasterLoadsPerUpdate` | `4` | `1` .. `32` | Maximum upstream shadow-caster chunks generated per world update tick. |
| `-Dvoxel.shadowFade` | `true` | `true` / `false` | Smoothly fades out shadows cast by obstacles beyond `[shadowFadeStart .. shadowFadeEnd]` blocks along the light ray (can also be toggled in-game with `Shift+F4`). |
| `-Dvoxel.shadowFadeStart` | `160` | `>= 0` | Ray distance (in blocks) where distant caster shadows begin to fade. |
| `-Dvoxel.shadowFadeEnd` | `256` | `> start` | Ray distance (in blocks) where distant caster shadows are completely faded out. |
| `-Dvoxel.skipUnlitShadows` | `false` | `true` / `false` | Skips shadow-map sampling in the fragment shader for back-facing surfaces (`dot(N, L) <= 0`). |

##### 🧪 Automated Benchmarking & Diagnostics (`GameConfig`, `TestHarness`, `GpuPassTimer`)
| Flag | Default | Values | Description |
| :--- | :---: | :---: | :--- |
| `-Dvoxel.testHarness` | `false` | `true` / `false` | Bypasses the main menu, loads `World 10` at a deterministic spawn pose, runs a 20-second automated camera/movement timeline, and writes frame metrics to `logs/perf_test_*.csv`. |
| `-Ddiagnostic.windowScale` | `0.75` | `0.25` .. `1.0` | Scales the initial window dimensions relative to the primary monitor workarea. |
| `-Dvoxel.cacheUniforms` | `false` | `true` / `false` | Caches OpenGL uniform locations in `Shader.java` instead of querying `glGetUniformLocation`. |

---

#### 🎬 Ready-to-Run Preset Commands (Portfolio Captures & Benchmarks)

- **Clip 1 — Sunset, Twilight Scattering & Starfield Skybox:**
  ```bash
  ./gradlew run -Dvoxel.startHour=17.3 -Dvoxel.timeScale=60 -Dvoxel.skyMode=procedural
  ```
- **Clip 2 — Accelerated CSM Shadow Sweep Across Valleys:**
  ```bash
  ./gradlew run -Dvoxel.startHour=14.5 -Dvoxel.timeScale=90 -Dvoxel.shadowFade=true
  ```
- **Clip 3 — Pipeline Debug Views & Greedy Meshing Wireframe (`F4`, `F7`, `Shift+F7`, `Shift+F3`):**
  ```bash
  ./gradlew run -Dvoxel.startHour=15.5 -Dvoxel.timeScale=20 -Dvoxel.skyMode=procedural
  ```
- **Compare New Engine vs Legacy Mode (Visual & Performance A/B):**
  ```bash
  # Default (Greedy Visual Meshing + Shimmer Fix ON)
  ./gradlew run -Dvoxel.greedyVisual=true -Dvoxel.shimmerFix=true

  # Legacy (Unmerged 1x1 Visual Quads + Legacy 1-tap Bilinear Shadow Filter)
  ./gradlew run -Dvoxel.greedyVisual=false -Dvoxel.shimmerFix=false
  ```

### Building a Standalone JAR

```bash
./gradlew jar
```

The JAR will be in `build/libs/`. Run it with:
```bash
java -XstartOnFirstThread -jar build/libs/TOB_Voxel_Project-1.0-SNAPSHOT.jar
```

**Note**: The `-XstartOnFirstThread` flag is required for macOS when invoking `java` directly.

## 🎮 Controls

### In-Game Movement
- **W/A/S/D** - Move forward/left/backward/right
- **Space** - Jump / Fly up (in spectator mode)
- **Shift** - Crouch / Fly down (in spectator mode)
- **P** - Toggle Spectator (free-flight) mode
- **Mouse** - Look around (`Left Click` to capture cursor, `ESC` to release cursor)

### Debug & Rendering Pipeline Keys
| Key | Action |
| :--- | :--- |
| **`F2`** | Capture screenshot to `screenshots/` |
| **`F3`** | Toggle full Debug HUD (coordinates, yaw/pitch, caster residency, shadow coverage, time, solar angle, and FPS). Screen is 100% clean when `F3` is off. |
| **`Shift + F3`** | Toggle Vertex Ambient Occlusion (`AO Debug`) isolation view |
| **`F4`** | Cycle Shader Debug modes: `OFF` → `Shadow Factor` → `Cascade Colors` → `World-Cell Hash` → `Fog Factor` → `Cascade SOLID` → `A/B Diff` → `Lit Before Fog` |
| **`Shift + F4`** | Toggle ray-distance Shadow Fade (`160..256` blocks) |
| **`F5`** | Toggle directional CSM Shadow Caster Coverage (`corrected` vs `legacy`) |
| **`F6`** | Pause / Resume solar & lunar time progression |
| **`F7`** | Toggle **Visual Mesh Wireframe** (`GL_LINE` — shows greedy-merged visual quads) |
| **`Shift + F7`** | Toggle **Greedy Shadow Mesh Wireframe** (shows merged shadow-pass polygons & ear-clipping triangulation) |
| **`F8`** | Cycle forced CSM Cascade isolation (`Normal` → `Force 0` → `Force 1` → `Force 2`) |
| **`F9`** | Cycle Shadow Bias mode (`Normal` → `Constant` → `Zero` → `Doubled`) |
| **`F10`** | Toggle Vertex Normal-Offset Shadow Bias (`ON` / `OFF`) |
| **`F11`** | Freeze / Unfreeze Pixar/Duff Light Basis vectors |
| **`F12`** | Freeze / Unfreeze CSM Texel-Snapped XY projection center |
| **`Shift + F12`** | Freeze / Unfreeze CSM Orthographic Z depth range |
| **`L`** | Toggle experimental Leapfrog shadow crossfade mode |
| **`B`** | Toggle Leapfrog blend mode (`DITHER` vs `LINEAR`) |

### Menu Navigation
- **Mouse** - Hover and click buttons
- **Arrow Keys** - Navigate lists
- **Tab/Enter** - Navigate text fields

## 🔧 Configuration

### Game Settings

Located in `src/main/java/engine/core/config/GameConfig.java`:

```java
// Display settings
public static final int WINDOW_WIDTH = 1280;
public static final int WINDOW_HEIGHT = 720;
public static final String WINDOW_TITLE = "Voxel Game";

// Rendering settings
public static final int RENDER_DISTANCE = 8;  // Chunks
public static final boolean VSYNC_ENABLED = true;

// Font settings
public static final String FONT_PATH = "fonts/Minecraftia.ttf";
public static final int FONT_SIZE = 24;
public static final int TITLE_FONT_SIZE = 64;
```

### World Settings

World saves are stored in `saves/` directory:
```
saves/
└── world_name_12345/
    ├── metadata.json         # World info
    └── chunks/
        └── 0_0.dat.gz       # Compressed chunk data
```

## 🖼️ UI System

### Transparent Glass UI Design

The UI uses a modern transparent design with these principles:

- **Normal state**: Only border outlines visible, background fully transparent
- **Hover state**: Semi-transparent white overlay (25% opacity) brightens background
- **Focus/Select state**: More visible white overlay (35-40% opacity)

### Adding Custom UI Components

```java
// Create a button
Button button = new Button(x, y, width, height, "Click Me", textRenderer, uiRenderer);
button.setOnClick(() -> System.out.println("Clicked!"));

// Create a text field
TextField field = new TextField(x, y, width, height, "Enter text...", textRenderer, uiRenderer);
String input = field.getText();

// Create a scrollable list
ScrollableList<String> list = new ScrollableList<>(x, y, width, height, itemHeight, uiRenderer);
list.setItems(Arrays.asList("Item 1", "Item 2", "Item 3"));
```

## 🌍 World Generation

### Chunk System

- **Chunk size**: 16×16×256 (X×Z×Y)
- **Block types**: 0=Air, 1=Grass, 2=Dirt, 3=Stone
- **Generation**: Perlin noise-based heightmap
- **Meshing**: Greedy algorithm with face culling

### Adding New Block Types

1. Add texture to `textures/atlas.png`
2. Update texture coordinates in shader
3. Add block type constant in world generation code

## 🎨 Rendering Pipeline

1. **Clear buffers** (color + depth)
2. **Disable depth test** for UI
3. **Render background texture** (full-screen quad)
4. **Render UI components** with blending:
   - Buttons (borders + optional fill)
   - Text fields
   - Scrollable lists
   - Labels
5. **Render text** (bitmap font rendering)
6. **Re-enable depth test** for 3D

### Shader Architecture

- **Voxel shaders**: Chunk rendering with lighting
- **UI shaders**: 2D orthographic with texture/color modes
- **Text shaders**: SDF bitmap font rendering
- **Depth shaders**: Shadow map generation

## 🧵 Multithreading

The engine uses a worker thread pool for mesh generation:

```java
MeshWorkerManager workerManager = new MeshWorkerManager(4); // 4 threads
workerManager.submitMeshTask(chunk, () -> {
    // Generate mesh in parallel
    chunk.generateMesh();
});
```

## 📝 Planned Features

- [ ] Block placement/breaking
- [ ] Inventory system
- [ ] Multiplayer support
- [ ] Advanced lighting (ambient occlusion)
- [ ] Biomes and structures
- [ ] Sound effects and music
- [ ] Settings menu implementation
- [ ] Shader-based water rendering

## 🐛 Known Issues

- Text rendering may have alignment issues on some platforms
- World generation can be slow on first load (mesh generation bottleneck)
- macOS requires `-XstartOnFirstThread` JVM flag

## 🤝 Contributing

No contributions for now.
<!-- Contributions are welcome! Please follow these guidelines: -->

<!-- 1. Fork the repository
2. Create a feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request -->

### Code Style

- Use 4 spaces for indentation
- Follow Java naming conventions
- Document public methods with Javadoc
- Keep methods under 50 lines when possible

## 📄 License

MIT License.

## 🙏 Acknowledgments

- **LWJGL** - Lightweight Java Game Library
- **JOML** - Java OpenGL Math Library
- **Minecraftia Font** - Andrew Tyler (http://andrewtyler.net/)
- **Minecraft** - Inspiration for voxel-based world generation

## 📧 Contact

Project Link: [https://github.com/abelfares17/Voxel_Project](https://github.com/abelfares17/Voxel_Project)

---

**Built with ❤️ using Java and OpenGL**
