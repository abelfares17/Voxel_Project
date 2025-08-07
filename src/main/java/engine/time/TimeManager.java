package engine.time;

/**
 * Gère le temps in-game (ex: cycle jour/nuit)
 */
public class TimeManager {
    private float timeOfDay; // entre 0.0 (minuit) et 1.0 (prochain minuit)
    private static final float SPEED = 1f / 600000f; // 1 cycle complet = 60 sec
    // Ajout pour FPS
    private int frames = 0;
    private long lastFpsTime = System.currentTimeMillis();
    private int currentFps = 0;


    public void update(long deltaMillis) {
        //System.out.println("deltaMillis = " + deltaMillis); // DEBUG
        timeOfDay += deltaMillis * SPEED;
        if (timeOfDay > 1.0f) timeOfDay -= 1.0f;
        // FPS tracking
        frames++;
        long now = System.currentTimeMillis();
        if (now - lastFpsTime >= 1000) {
            currentFps = frames;
            frames = 0;
            lastFpsTime = now;
        }
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
}