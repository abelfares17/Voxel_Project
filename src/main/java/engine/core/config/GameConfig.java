package engine.core.config;

/**
 * Central configuration for the game.
 * Contains all game-wide constants extracted from hardcoded values.
 *
 * All values are in standard units:
 * - Distances: blocks
 * - Time: milliseconds
 * - Angles: degrees
 */
public class GameConfig {

    // === WINDOW CONFIGURATION ===

    /** Window title displayed in title bar */
    public static final String WINDOW_TITLE = "Voxel Engine 3D";

    /** Window offset from top of screen (pixels) */
    public static final int WINDOW_TOP_OFFSET = 10;

    /** Height reduction for window to account for taskbar (pixels) */
    public static final int WINDOW_HEIGHT_REDUCTION = 10;

    /** Enable/disable window decoration (borders, title bar) */
    public static final boolean WINDOW_DECORATED = true;

    /** Enable/disable window resizing */
    public static final boolean WINDOW_RESIZABLE = true;

    /** Initial logical size relative to the desktop-constrained full window; Retina stays enabled. */
    public static final float WINDOW_SCALE = readWindowScale();

    private static float readWindowScale() {
        float scale = Float.parseFloat(System.getProperty("voxel.windowScale", "0.75"));
        if (!Float.isFinite(scale) || scale < 0.25f || scale > 1.0f) {
            throw new IllegalArgumentException("voxel.windowScale must be in [0.25, 1.0]");
        }
        return scale;
    }

    /**
     * Enable Retina framebuffer on macOS (true = native resolution, required for correct Cocoa/OpenGL viewport on macOS).
     */
    public static final boolean RETINA_FRAMEBUFFER_ENABLED = true;

    /** Enable V-Sync (1 = enabled, 0 = disabled) */
    public static final int VSYNC_ENABLED = 0;


    // === TIME CONFIGURATION ===

    /**
     * Day/night cycle speed multiplier.
     * 0.1f = normal 1x game speed (10x slower than 1.0f test speed, 1 cycle = 6000s = 100 min).
     * 1.0f = fast test speed (1 cycle = 600s = 10 min).
     */
    public static final float SUN_SPEED_MULTIPLIER = 1.0f;


    // === OPENGL CONFIGURATION ===

    /** OpenGL context major version */
    public static final int OPENGL_VERSION_MAJOR = 3;

    /** OpenGL context minor version */
    public static final int OPENGL_VERSION_MINOR = 2;

    /** Use OpenGL Core Profile (required for macOS) */
    public static final boolean OPENGL_CORE_PROFILE = true;

    /** Forward compatibility mode (required for macOS) */
    public static final boolean OPENGL_FORWARD_COMPAT = true;


    // === WORLD CONFIGURATION ===

    /** Default render distance in chunks */
    public static final int DEFAULT_RENDER_DISTANCE = 15;

    /** Chunk width (X-axis) in blocks */
    public static final int CHUNK_WIDTH = 16;

    /** Chunk height (Y-axis) in blocks */
    public static final int CHUNK_HEIGHT = 256;

    /** Chunk depth (Z-axis) in blocks */
    public static final int CHUNK_DEPTH = 16;


    // === PLAYER CONFIGURATION ===

    /** Player height in blocks */
    public static final float PLAYER_HEIGHT = 2.0f;

    /** Player width (bounding box) in blocks */
    public static final float PLAYER_WIDTH = 0.6f;

    /** Player movement speed (blocks per tick) */
    public static final float PLAYER_SPEED = 0.5f;

    /** Gravity acceleration (blocks per tick squared) */
    public static final float GRAVITY = -0.04f;

    /** Jump velocity (blocks per tick) */
    public static final float JUMP_VELOCITY = 0.42f;

    /** Friction coefficient for horizontal movement (0-1) */
    public static final float PLAYER_FRICTION = 0.6f;


    // === INPUT CONFIGURATION ===

    /** Initial cursor mode (GLFW_CURSOR_NORMAL = visible, since we start in menu) */
    public static final int INITIAL_CURSOR_MODE = org.lwjgl.glfw.GLFW.GLFW_CURSOR_NORMAL;


    // === RESOURCE PATHS ===

    /** Path to default font (TTF) */
    public static final String FONT_PATH = "fonts/Minecraftia.ttf";

    /** Font size in points */
    public static final int FONT_SIZE = 24;

    /** Path to title font (TTF) - same as default for consistency */
    public static final String TITLE_FONT_PATH = "fonts/Minecraftia.ttf";

    /** Title font size in points (larger for prominent display) */
    public static final int TITLE_FONT_SIZE = 64;

    /** Path to texture atlas */
    public static final String ATLAS_TEXTURE_PATH = "textures/atlas.png";


    // === SHADER PATHS ===

    /** Voxel vertex shader path */
    public static final String VOXEL_VERTEX_SHADER = "shaders/voxel_vertex_shader.glsl";

    /** Voxel fragment shader path */
    public static final String VOXEL_FRAGMENT_SHADER = "shaders/voxel_fragment_shader.glsl";

    /** Depth (shadow) vertex shader path */
    public static final String DEPTH_VERTEX_SHADER = "shaders/depth_vertex_shader.glsl";

    /** Depth (shadow) fragment shader path */
    public static final String DEPTH_FRAGMENT_SHADER = "shaders/depth_fragment_shader.glsl";

    /** Sun vertex shader path */
    public static final String SUN_VERTEX_SHADER = "shaders/sun_vertex.glsl";

    /** Sun fragment shader path */
    public static final String SUN_FRAGMENT_SHADER = "shaders/sun_fragment.glsl";

    /** Text vertex shader path */
    public static final String TEXT_VERTEX_SHADER = "shaders/text_vertex.glsl";

    /** Text fragment shader path */
    public static final String TEXT_FRAGMENT_SHADER = "shaders/text_fragment.glsl";

    /** UI vertex shader path */
    public static final String UI_VERTEX_SHADER = "shaders/ui_vertex.glsl";

    /** UI fragment shader path */
    public static final String UI_FRAGMENT_SHADER = "shaders/ui_fragment.glsl";


    // === SAVE SYSTEM ===

    /** Root directory for world saves */
    public static final String SAVES_DIRECTORY = "saves";

    /** Chunk file naming pattern */
    public static final String CHUNK_FILE_PATTERN = "chunk_%d_%d.bin";

    /** Player data file name */
    public static final String PLAYER_DATA_FILE = "player.dat";


    // === THREADING ===

    /** Use system CPU count for mesh worker threads */
    public static final boolean USE_SYSTEM_CPU_COUNT = true;

    /** Fixed thread count (only used if USE_SYSTEM_CPU_COUNT = false) */
    public static final int FIXED_THREAD_COUNT = 4;


    // === DEBUG ===

    /** Enable verbose logging */
    public static final boolean VERBOSE_LOGGING = true;

    /** Print framebuffer size on initialization */
    public static final boolean DEBUG_FRAMEBUFFER_SIZE = true;

    /**
     * Active le traqueur de fuites mémoire natives de LWJGL (DebugAllocator).
     * Désactivé par défaut (false) pour éviter les logs de chargement de bibliothèques dans le terminal.
     * Mettre à true uniquement pour une session de diagnostic de fuites mémoire.
     */
    public static final boolean DEBUG_MEMORY_ALLOCATOR = false;


    // === PERFORMANCE LOGGING ===

    /**
     * Enable CSV performance logging to file (logs/perf_test_*.csv) during normal gameplay.
     * Records frame times, FPS, and player position for performance analysis without terminal spam.
     */
    public static final boolean PERF_LOG_ENABLED = true;

    /**
     * Frequency of CSV logging in frames (e.g. 60 = 1 record every ~1 sec at 60 FPS / ~0.5 sec at 120 FPS).
     * Prevents giant log files and excessive disk writes.
     */
    public static final int PERF_LOG_FREQUENCY = 60;


    // === TEST HARNESS ===

    /**
     * Master switch: Enable automated testing harness.
     * When true:
     * - Bypasses menu and loads directly into PlayingState
     * - Loads a deterministic test world (TEST_WORLD_NAME)
     * - Forces spawn position/orientation to eliminate drift
     * - Runs automated movement scenario
     * - Logs performance metrics to CSV
     * When false: normal game behavior (unchanged)
     */
    public static final boolean TEST_HARNESS_ENABLED = Boolean.getBoolean("voxel.testHarness");

    /** Name of the test world to load when harness is enabled */
    public static final String TEST_WORLD_NAME = "World 10";

    /** Forced spawn position for deterministic testing (X, Y, Z in blocks) */
    public static final org.joml.Vector3f TEST_FORCE_SPAWN_POS = new org.joml.Vector3f(0.5f, 100f, 0.5f);

    /** Forced spawn yaw angle (degrees) */
    public static final float TEST_FORCE_SPAWN_YAW = 0.0f;

    /** Forced spawn pitch angle (degrees) */
    public static final float TEST_FORCE_SPAWN_PITCH = 0.0f;

    /** Duration of automated test scenario (seconds) */
    public static final float TEST_SCENARIO_DURATION = 20.0f;

    /** Performance logging frequency (1 = every frame, 10 = every 10th frame) */
    public static final int TEST_LOG_FREQUENCY = 1;

    /**
     * Insert a glFinish after ShadowRenderer.render() to measure shadow pass GPU time separately.
     * WARNING: breaks GPU pipelining → artificially increases total frame time.
     * Set to true ONLY for hypothesis 6.3 isolation test (C3), false in production.
     */
    public static final boolean SHADOW_SPLIT_TIMING = false;


    // === PRIVATE CONSTRUCTOR ===

    /**
     * Private constructor to prevent instantiation.
     * This is a utility class with only static members.
     */
    private GameConfig() {
        throw new AssertionError("GameConfig should not be instantiated");
    }
}
