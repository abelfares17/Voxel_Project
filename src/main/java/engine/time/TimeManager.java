package engine.time;

/**
 * Gère le temps in-game (ex: cycle jour/nuit)
 */
public class TimeManager {
    private float timeOfDay; // entre 0.0 (minuit) et 1.0 (prochain minuit)
    private static final float SPEED = 1f / 60000f; // 1 cycle complet = 60 sec

    public void update(long deltaMillis) {
        timeOfDay += deltaMillis * SPEED;
        if (timeOfDay > 1.0f) timeOfDay -= 1.0f;
    }

    public float getTimeOfDay() {
        return timeOfDay;
    }

    public float getBrightness() {
        return Math.max(0.0f, (float)Math.sin(timeOfDay * Math.PI));
    }

    public float getAmbient() {
        return 0.2f; // lumière minimale la nuit
    }

    public float getSunAngleRadians() {
        return (float)(Math.PI * timeOfDay); // soleil 0 (est) → pi (ouest)
    }
}