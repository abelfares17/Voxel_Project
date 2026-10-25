package engine.time;

import org.joml.Vector3f;

/** Immutable frame snapshot. Directions point TOWARDS the bodies; all colors are linear RGB.
 * Intensities and colors are artistic starting values, not radiometric quantities.
 */
public record CelestialState(
        Vector3f sunDirection, Vector3f moonDirection,
        float sunElevation, float moonElevation, float sunIntensity, float moonIntensity,
        Light activeLight, Vector3f lightDirection, Vector3f lightColor,
        float ambient, float daylight, float stars, Vector3f horizon, Vector3f zenith) {
    public enum Light { SUN, MOON, NONE }
    public static final float TILT_DEGREES = 23.5f;
    // Artistic apparent diameters; independent of directional-light intensity and shadow filtering.
    public static final float SUN_DIAMETER_DEGREES = 3f;
    public static final float MOON_DIAMETER_DEGREES = 2f;
    public static final float DIRECT_START_DEGREES = 2f, DIRECT_FULL_DEGREES = 8f;
    public static final float MOON_INTENSITY = .08f, NIGHT_AMBIENT = .04f, DAY_AMBIENT = .45f;

    public CelestialState {
        sunDirection = new Vector3f(sunDirection); moonDirection = new Vector3f(moonDirection);
        lightDirection = new Vector3f(lightDirection); lightColor = new Vector3f(lightColor);
        horizon = new Vector3f(horizon); zenith = new Vector3f(zenith);
    }
    @Override public Vector3f sunDirection() { return new Vector3f(sunDirection); }
    @Override public Vector3f moonDirection() { return new Vector3f(moonDirection); }
    @Override public Vector3f lightDirection() { return new Vector3f(lightDirection); }
    @Override public Vector3f lightColor() { return new Vector3f(lightColor); }
    @Override public Vector3f horizon() { return new Vector3f(horizon); }
    @Override public Vector3f zenith() { return new Vector3f(zenith); }
    public float activeElevation() {
        return activeLight == Light.MOON ? moonElevation : activeLight == Light.SUN ? sunElevation : 0;
    }

    public static CelestialState at(double days) {
        if (!Double.isFinite(days)) throw new IllegalArgumentException("Invalid celestial time");
        double angle = 2 * Math.PI * (days - Math.floor(days) - .25);
        double tilt = Math.toRadians(TILT_DEGREES);
        Vector3f sun = new Vector3f((float)Math.cos(angle),
                (float)(Math.sin(angle) * Math.cos(tilt)), (float)(Math.sin(angle) * Math.sin(tilt))).normalize();
        Vector3f moon = new Vector3f(sun).negate();
        float elevation = (float)Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, sun.y))));
        float si = smooth(DIRECT_START_DEGREES, DIRECT_FULL_DEGREES, elevation);
        float mi = MOON_INTENSITY * smooth(DIRECT_START_DEGREES, DIRECT_FULL_DEGREES, -elevation);
        Light light = si > 0 ? Light.SUN : mi > 0 ? Light.MOON : Light.NONE;
        float day = smooth(-6, 10, elevation);
        Vector3f color = light == Light.SUN ? solarColor(sun.y)
                : light == Light.MOON ? new Vector3f(.55f, .65f, 1).mul(mi) : new Vector3f();
        return new CelestialState(sun, moon, elevation, -elevation, si, mi, light,
                light == Light.MOON ? moon : sun, color,
                NIGHT_AMBIENT + (DAY_AMBIENT - NIGHT_AMBIENT) * day, day,
                1 - smooth(-12, -2, elevation),
                new Vector3f(.008f, .012f, .025f).lerp(new Vector3f(.45f, .62f, .8f), day),
                new Vector3f(.001f, .003f, .012f).lerp(new Vector3f(.06f, .23f, .55f), day));
    }

    public static float smooth(float low, float high, float value) {
        float t = Math.max(0, Math.min(1, (value - low) / (high - low)));
        return t * t * (3 - 2 * t);
    }

    public static Vector3f solarColor(float sunY) {
        float elevation = (float)Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, sunY))));
        float warm = 1 - smooth(0, 18, elevation);
        return new Vector3f(1, 1 - .35f * warm, 1 - .65f * warm)
                .mul(smooth(DIRECT_START_DEGREES, DIRECT_FULL_DEGREES, elevation));
    }
}
