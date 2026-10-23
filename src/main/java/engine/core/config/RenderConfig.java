package engine.core.config;

import org.joml.Vector3f;

/**
 * Rendering configuration constants.
 * Contains all rendering-related parameters including:
 * - Camera settings
 * - Sky colors
 * - Lighting parameters
 * - Shadow mapping configuration
 * - Fog settings
 */
public class RenderConfig {
    /** Per-mesh uint32 indices; override false for the non-indexed benchmark reference. */
    public static final boolean INDEXED_MESHES =
            Boolean.parseBoolean(System.getProperty("voxel.indexedMeshes", "true"));
    // Implemented and validated, but opt-in: the mobile ABBA runs showed no robust FPS gain.
    // See docs/Rapport_AABB_meshes_2026-10-22.md. Bounds are still computed at mesh generation.
    public static final boolean TIGHT_SHADOW_BOUNDS =
            Boolean.parseBoolean(System.getProperty("voxel.tightShadowBounds", "false"));
    public static final boolean TIGHT_CAMERA_BOUNDS =
            Boolean.parseBoolean(System.getProperty("voxel.tightCameraBounds", "false"));

    // === CAMERA CONFIGURATION ===

    /** Field of view in degrees */
    public static final float CAMERA_FOV = 90.0f;

    /** Near clipping plane distance */
    public static final float CAMERA_NEAR = 0.1f;

    /** Far clipping plane distance */
    public static final float CAMERA_FAR = 1000.0f;


    // === SKY COLORS ===

    /** Night sky color (RGB) */
    public static final Vector3f SKY_COLOR_NIGHT = new Vector3f(0.02f, 0.04f, 0.08f);

    /** Dawn/dusk sky color (RGB) */
    public static final Vector3f SKY_COLOR_DAWN = new Vector3f(0.6f, 0.35f, 0.22f);

    /** Day sky color (RGB) */
    public static final Vector3f SKY_COLOR_DAY = new Vector3f(0.53f, 0.81f, 0.92f);

    /**
     * When true, sky color is frozen to SKY_COLOR_DAY instead of evolving with time.
     * This is a workaround for visual flashes during shadow map transitions.
     * Toggle with K key.
     */
    private static boolean freezeSkyColor = false;


    // === TIME THRESHOLDS (0.0 to 1.0 range, where 0.0 = 6:00 AM) ===

    /** Dawn starts at 4:00 AM (0.1667 in normalized time) */
    public static final float TIME_DAWN_START = 4f / 24f;

    /** Dawn middle at 6:00 AM (0.25 in normalized time) */
    public static final float TIME_DAWN_MID = 6f / 24f;

    /** Day starts at 8:00 AM (0.3333 in normalized time) */
    public static final float TIME_DAY_START = 8f / 24f;

    /** Dusk starts at 4:00 PM (0.6667 in normalized time) */
    public static final float TIME_DUSK_START = 16f / 24f;

    /** Dusk middle at 6:00 PM (0.75 in normalized time) */
    public static final float TIME_DUSK_MID = 18f / 24f;

    /** Night starts at 8:00 PM (0.8333 in normalized time) */
    public static final float TIME_NIGHT_START = 20f / 24f;


    // === SUN RENDERING ===

    /** Sun fade starts when sunY drops below this value */
    public static final float SUN_FADE_START = -0.2f;

    /** Sun fully faded (black) when sunY drops below this value */
    public static final float SUN_FADE_END = -0.3f;

    /** Minimum sun intensity (ambient light) */
    public static final float SUN_MIN_INTENSITY = 0.15f;

    /** Sun color red channel multiplier */
    public static final float SUN_COLOR_RED = 1.0f;

    /** Sun color green channel minimum */
    public static final float SUN_COLOR_GREEN_MIN = 0.6f;

    /** Sun color blue channel minimum */
    public static final float SUN_COLOR_BLUE_MIN = 0.5f;

    /** Sun color blue channel intensity multiplier */
    public static final float SUN_COLOR_BLUE_INTENSITY = 0.8f;


    // === SHADOW MAPPING (Legacy Single Shadow Map) ===

    /** Shadow map width in pixels (higher = sharper shadows, more VRAM) */
    public static final int SHADOW_MAP_WIDTH = 4096;

    /** Shadow map height in pixels (square for uniform quality) */
    public static final int SHADOW_MAP_HEIGHT = 4096;


    // === CASCADED SHADOW MAPPING (CSM) ===

    /** Master shadow switch. false = shadowsEnabled uniform = 0 (no fetches), true = normal. */
    public static boolean SHADOWS_ENABLED = true;

    /**
     * Skip the GPU shadow depth pass entirely (ShadowRenderer.render() returns immediately).
     * The atlas retains its previous content (stale depths = fully lit = ok for perf isolation).
     * PERF TEST ONLY — hypothesis 6.3 experiment C1. Default: true (normal operation).
     */
    public static boolean SHADOW_DEPTH_PASS_ENABLED = true;

    /** Reject chunk bounds entirely outside the actual light clip volume; false is the A/B reference. */
    public static final boolean SHADOW_FRUSTUM_CULLING_ENABLED =
            Boolean.parseBoolean(System.getProperty("voxel.shadowFrustumCulling", "true"));

    /**
     * Activer le système Leapfrog (double tampon temporel A/B).
     * false = Mode CSM direct (recommandé : évolution continue et fluide du soleil, pas de diaporama/slideshow).
     * true  = Mode Leapfrog (double tampon prévisionnel).
     */
    public static boolean LEAPFROG_SHADOWS_ENABLED = false;

    /**
     * Shadow atlas min/mag filter mode.
     * 0 = GL_LINEAR (default — current production setting)
     * 1 = GL_NEAREST
     * Change requires restart (texture is created once at init).
     */
    public static int SHADOW_MAP_FILTER = 1;

    /**
     * Shadow atlas internal depth format.
     * 0 = GL_DEPTH_COMPONENT32F (default)
     * 1 = GL_DEPTH_COMPONENT24
     * 2 = GL_DEPTH_COMPONENT16
     * Change requires restart. FBO completeness is checked; falls back to 32F if unsupported.
     */
    public static int SHADOW_MAP_FORMAT = 0;

    /**
     * Force GL_TEXTURE_BASE_LEVEL=0 and GL_TEXTURE_MAX_LEVEL=0 on the shadow atlas.
     * Prevents any mipmap stack allocation (depth arrays have 1 level anyway, but explicit is safer).
     * Change requires restart.
     */
    public static boolean SHADOW_BASE_MAX_LEVEL = false;

    /** Number of shadow cascades (2-6 supported, 3 is typical) */
    public static final int CSM_CASCADE_COUNT = 3;

    /** Shadow map resolution per cascade (square) */
    public static final int CSM_SHADOW_MAP_SIZE = 2048;

    /** Practical split lambda: 0 = linear, 1 = logarithmic, 0.62 = practical blend extending near field */
    public static final float CSM_SPLIT_LAMBDA = 0.62f;

    /** Depth margin ratio for shadow casters behind the view frustum (0.5 = 50% extension) */
    public static final float CSM_DEPTH_MARGIN_RATIO = 0.5f;

    /** Shadow far plane for CSM (matches loaded chunk visibility before fog) */
    public static final float CSM_SHADOW_FAR = 200.0f;

    /** Light basis transition start (alignment with world up) */
    public static final float CSM_LIGHT_BASIS_TRANSITION_START = 0.9f;

    /** Light basis transition end (alignment with world up) */
    public static final float CSM_LIGHT_BASIS_TRANSITION_END = 0.995f;

    /** Base shadow bias (scales with cascade texel size) */
    public static final float CSM_BASE_SHADOW_BIAS = 0.0005f;


    // === FOG CONFIGURATION ===

    /** Fog start distance offset (in blocks from render distance) */
    public static final float FOG_START_OFFSET = -10.0f;

    /** Fog end distance offset (in blocks from render distance) */
    public static final float FOG_END_OFFSET = 10.0f;


    // === UI TEXT RENDERING ===

    /** UI text scale factor */
    public static final float UI_TEXT_SCALE = 2.0f;

    /** Time display X position (pixels from left) */
    public static final float TIME_DISPLAY_X = 20.0f;

    /** Time display Y offset from bottom (pixels) */
    public static final float TIME_DISPLAY_Y_OFFSET = 100.0f;

    /** Time display color (yellowish) */
    public static final Vector3f TIME_DISPLAY_COLOR = new Vector3f(1.0f, 1.0f, 0.85f);

    /** FPS display Y offset from bottom (pixels) */
    public static final float FPS_DISPLAY_Y_OFFSET = 140.0f;

    /** FPS display color (greenish) */
    public static final Vector3f FPS_DISPLAY_COLOR = new Vector3f(0.9f, 1.0f, 0.9f);

    /** Debug info Y offset increment (pixels) */
    public static final float DEBUG_INFO_Y_INCREMENT = 40.0f;


    // === TEXTURE CONFIGURATION ===

    /** Texture atlas grid size (64x64 tiles) */
    public static final int ATLAS_GRID_SIZE = 64;

    /** Individual texture tile size in pixels */
    public static final int TEXTURE_TILE_SIZE = 16;


    // === MESH RENDERING ===

    /** Chunk mesh stride in bytes (per vertex) */
    public static final int MESH_VERTEX_STRIDE = 40; // pos(12) + tex(8) + normal(12) + light(4) + ao(4)

    /** Position attribute size (floats) */
    public static final int MESH_POS_SIZE = 3;

    /** Texture coordinate attribute size (floats) */
    public static final int MESH_TEX_SIZE = 2;

    /** Normal attribute size (floats) */
    public static final int MESH_NORMAL_SIZE = 3;

    /** Light attribute size (floats) */
    public static final int MESH_LIGHT_SIZE = 1;

    /** Ambient occlusion attribute size (floats) */
    public static final int MESH_AO_SIZE = 1;


    // === PRIVATE CONSTRUCTOR ===

    /**
     * Private constructor to prevent instantiation.
     * This is a utility class with only static members.
     */
    private RenderConfig() {
        throw new AssertionError("RenderConfig should not be instantiated");
    }


    // === UTILITY METHODS ===

    /**
     * Calculate fog start distance based on render distance.
     *
     * @param renderDistance Render distance in chunks
     * @return Fog start distance in blocks
     */
    public static float getFogStart(int renderDistance) {
        return GameConfig.CHUNK_WIDTH * renderDistance + FOG_START_OFFSET;
    }

    /**
     * Calculate fog end distance based on render distance.
     *
     * @param renderDistance Render distance in chunks
     * @return Fog end distance in blocks
     */
    public static float getFogEnd(int renderDistance) {
        return GameConfig.CHUNK_WIDTH * renderDistance + FOG_END_OFFSET;
    }

    /**
     * Interpolate between two colors.
     *
     * @param a First color
     * @param b Second color
     * @param t Interpolation factor (0.0 to 1.0)
     * @return Interpolated color
     */
    public static Vector3f lerpColor(Vector3f a, Vector3f b, float t) {
        return new Vector3f(
                a.x * (1 - t) + b.x * t,
                a.y * (1 - t) + b.y * t,
                a.z * (1 - t) + b.z * t
        );
    }

    /**
     * Calculate sky color based on time of day.
     * If freezeSkyColor is enabled, always returns SKY_COLOR_DAY.
     *
     * @param timeOfDay Normalized time (0.0 to 1.0, where 0.0 = 6:00 AM)
     * @return Sky color for the given time
     */
    public static Vector3f getSkyColor(float timeOfDay) {
        // When frozen, always return day sky color (workaround for shadow transition flashes)
        if (freezeSkyColor) {
            return new Vector3f(SKY_COLOR_DAY);
        }

        // Normalize to 0-1 range with 6AM offset
        float t = (timeOfDay + 0.25f) % 1.0f;

        if (t < TIME_DAWN_START) {
            // Deep night (before 4:00 AM)
            return new Vector3f(SKY_COLOR_NIGHT);
        } else if (t < TIME_DAWN_MID) {
            // 4:00 AM - 6:00 AM: Night → Dawn transition
            float alpha = (t - TIME_DAWN_START) / (TIME_DAWN_MID - TIME_DAWN_START);
            return lerpColor(SKY_COLOR_NIGHT, SKY_COLOR_DAWN, alpha);
        } else if (t < TIME_DAY_START) {
            // 6:00 AM - 8:00 AM: Dawn → Day transition
            float alpha = (t - TIME_DAWN_MID) / (TIME_DAY_START - TIME_DAWN_MID);
            return lerpColor(SKY_COLOR_DAWN, SKY_COLOR_DAY, alpha);
        } else if (t < TIME_DUSK_START) {
            // 8:00 AM - 4:00 PM: Full daylight
            return new Vector3f(SKY_COLOR_DAY);
        } else if (t < TIME_DUSK_MID) {
            // 4:00 PM - 6:00 PM: Day → Dusk transition
            float alpha = (t - TIME_DUSK_START) / (TIME_DUSK_MID - TIME_DUSK_START);
            return lerpColor(SKY_COLOR_DAY, SKY_COLOR_DAWN, alpha);
        } else if (t < TIME_NIGHT_START) {
            // 6:00 PM - 8:00 PM: Dusk → Night transition
            float alpha = (t - TIME_DUSK_MID) / (TIME_NIGHT_START - TIME_DUSK_MID);
            return lerpColor(SKY_COLOR_DAWN, SKY_COLOR_NIGHT, alpha);
        } else {
            // 8:00 PM - 4:00 AM: Night
            return new Vector3f(SKY_COLOR_NIGHT);
        }
    }

    /**
     * Toggle sky color freeze mode.
     * When frozen, sky is always day color (workaround for shadow transition flashes).
     */
    public static void toggleFreezeSkyColor() {
        freezeSkyColor = !freezeSkyColor;
        System.out.println("Sky Color: " + (freezeSkyColor ? "FROZEN (day)" : "DYNAMIC"));
    }

    /**
     * Check if sky color is frozen.
     */
    public static boolean isFreezeSkyColor() {
        return freezeSkyColor;
    }

    /**
     * Set sky color freeze state.
     */
    public static void setFreezeSkyColor(boolean freeze) {
        freezeSkyColor = freeze;
    }

    /**
     * Calculate sun color based on sun position and fade.
     *
     * @param sunY Sun Y position
     * @return Sun color with fade applied
     */
    public static Vector3f getSunColor(float sunY) {
        // Calculate fade factor
        float fade = 1.0f;
        if (sunY < SUN_FADE_START) {
            if (sunY > SUN_FADE_END) {
                fade = (sunY - SUN_FADE_END) / (SUN_FADE_START - SUN_FADE_END);
            } else {
                fade = 0.0f;
            }
        }

        // Calculate intensity-based color
        float intensity = Math.max(SUN_MIN_INTENSITY, sunY);
        Vector3f baseColor = new Vector3f(
                SUN_COLOR_RED,
                Math.max(SUN_COLOR_GREEN_MIN, intensity),
                Math.max(SUN_COLOR_BLUE_MIN, intensity * SUN_COLOR_BLUE_INTENSITY)
        );

        // Apply fade
        return new Vector3f(
                baseColor.x * fade,
                baseColor.y * fade,
                baseColor.z * fade
        );
    }
}
