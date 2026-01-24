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
│   │   │   ├── renderer/      # VoxelRenderer, ShadowMapRenderer
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

### Building a Standalone JAR

```bash
./gradlew jar
```

The JAR will be in `build/libs/`. Run it with:
```bash
java -XstartOnFirstThread -jar build/libs/TOB_Voxel_Project-1.0-SNAPSHOT.jar
```

**Note**: The `-XstartOnFirstThread` flag is required for macOS.

## 🎮 Controls

### In-Game
- **W/A/S/D** - Move forward/left/backward/right
- **Space** - Jump / Fly up (in flight mode)
- **Shift** - Crouch / Fly down (in flight mode)
- **Mouse** - Look around
- **ESC** - Pause / Return to menu

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
