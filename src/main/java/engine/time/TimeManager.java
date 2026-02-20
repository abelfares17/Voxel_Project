package engine.time;

import org.joml.Vector3f;

/**
 * Gère le temps in-game (ex: cycle jour/nuit)
 */
public class TimeManager {
    private float timeOfDay; // entre 0.0 (minuit) et 1.0 (prochain minuit)
    private static final float BASE_SPEED = 1f / 600000f; // 1 cycle complet = 60 sec

    // Debug: time speed multiplier (1.0 = normal, 0.1 = 10x slower, 10.0 = 10x faster)
    private float debugSpeedMultiplier = 1.0f;  // Default: normal speed

    // Ajout pour FPS
    private int frames = 0;
    private long lastFpsTime = System.currentTimeMillis();
    private int currentFps = 0;

    // Debug: pause sun/time progression
    private boolean paused = false;


    public void update(long deltaMillis) {
        //System.out.println("deltaMillis = " + deltaMillis); // DEBUG
        if (!paused) {
            timeOfDay += deltaMillis * BASE_SPEED * debugSpeedMultiplier;
            if (timeOfDay > 1.0f) timeOfDay -= 1.0f;
        }
        // FPS tracking
        frames++;
        long now = System.currentTimeMillis();
        if (now - lastFpsTime >= 1000) {
            currentFps = frames;
            frames = 0;
            lastFpsTime = now;
        }
    }

    /**
     * Get raw sun data with continuous (unfiltered) sun direction.
     * Use this for lighting, sky rendering, and visual effects.
     */
    public SunData getSunData(){
        float t = timeOfDay;
        t = (t + 0.25f) % 1.0f;
        float angle = (float) (2 * Math.PI * (t - 0.25));

        // Continuous sun direction (no quantization - smooth motion)
        float sunY = (float) Math.sin(angle);
        float sunX = (float) Math.cos(angle);
        Vector3f sunDir = new Vector3f(sunX, sunY, 0.2f).normalize();
        return new SunData(sunDir, sunY);
    }

    /**
     * Get sun direction for shadow mapping.
     * Returns the raw sun direction - shadow stability is handled by
     * texel-aligned projection in ShadowRenderer.
     *
     * @param deltaTime Time since last frame in seconds (unused, kept for API compatibility)
     * @return Sun direction for shadow calculations
     */
    public Vector3f getSunDirForShadows(float deltaTime) {
        return getSunData().sunDir();
    }

    public int getFps() {
        return currentFps;
    }

    public float getTimeOfDay() {
        return timeOfDay;
    }

    public float getBrightness() {
        float t = (timeOfDay + 0.25f) % 1.0f;        // 0 = 6h du matin
        float phase = (t - 0.25f + 1f) % 1.0f;        // 0 = midi, 0.5 = minuit
        float brightness = (float)Math.cos(phase * 2 * Math.PI);
        brightness = 0.05f + 0.95f * Math.max(0.0f, brightness); // Clamp et élève la nuit
        return brightness;
    }

    // Valeur d'ambient selon l'heure : plus élevé le jour, faible la nuit
    public float getAmbient() {
        float t = (timeOfDay + 0.25f) % 1.0f;
        // Plage de l'ambient (0.18 la nuit → 0.45 le jour)
        float minAmb = 0.18f;
        float maxAmb = 0.45f;
        // Repère le jour (ex: 8h–16h = plein jour)
        float dayStart  = 8f / 24f;
        float dayEnd    = 16f / 24f;
        if (t >= dayStart && t <= dayEnd) {
            return maxAmb;
        } else if (t < dayStart) {
            float alpha = (t - 0f) / (dayStart - 0f); // nuit → jour
            return minAmb + alpha * (maxAmb - minAmb);
        } else {
            float alpha = 1f - (t - dayEnd) / (1f - dayEnd); // jour → nuit
            return minAmb + alpha * (maxAmb - minAmb);
        }
    }

    public float getSunAngleRadians() {
        return (float)(Math.PI * timeOfDay); // soleil 0 (est) → pi (ouest)
    }

    /**
     * Toggle time/sun pause for debugging.
     * When paused, timeOfDay stops updating so sun direction remains constant.
     */
    public void togglePause() {
        paused = !paused;
    }

    public boolean isPaused() {
        return paused;
    }

    /**
     * Get debug time speed multiplier.
     * 1.0 = normal, 0.1 = 10x slower, 10.0 = 10x faster
     */
    public float getDebugSpeedMultiplier() {
        return debugSpeedMultiplier;
    }

    /**
     * Set debug time speed multiplier for CSM debugging.
     * @param multiplier 1.0 = normal, 0.1 = 10x slower, 10.0 = 10x faster
     */
    public void setDebugSpeedMultiplier(float multiplier) {
        this.debugSpeedMultiplier = multiplier;
    }
}