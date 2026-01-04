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


    // === SHADOW MAPPING ===

    /** Shadow map width in pixels */
    public static final int SHADOW_MAP_WIDTH = 3072;

    /** Shadow map height in pixels */
    public static final int SHADOW_MAP_HEIGHT = 2048;


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
     *
     * @param timeOfDay Normalized time (0.0 to 1.0, where 0.0 = 6:00 AM)
     * @return Sky color for the given time
     */
    public static Vector3f getSkyColor(float timeOfDay) {
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