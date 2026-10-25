package engine.time;

import org.joml.Vector3f;

/** Simulation clock: 0 = midnight; one day = 1200 real seconds at speed 1.
 * Only the active game's update advances it, never loading or wall-clock elapsed time.
 */
public class TimeManager {
    public static final double DEFAULT_DAYS = 8.0 / 24.0;
    public static final double DAY_SECONDS = 1200.0;
    private double worldDays = DEFAULT_DAYS;
    private float speed = 1;
    private boolean paused;
    private final boolean launchOverrides;
    private long lastDeltaMillis, lastDeltaMillisClamped;
    private float lastDeltaSecondsClamped, lastTimeBefore, lastTimeAfter;
    private int frames, fps;
    private long fpsStart = System.nanoTime();

    public TimeManager() {
        launchOverrides = System.getProperty("voxel.startHour") != null
                || System.getProperty("voxel.timeScale") != null
                || System.getProperty("voxel.pauseTime") != null;
        if (System.getProperty("voxel.startHour") != null)
            setHour(Double.parseDouble(System.getProperty("voxel.startHour")));
        if (System.getProperty("voxel.timeScale") != null)
            setDebugSpeedMultiplier(Float.parseFloat(System.getProperty("voxel.timeScale")));
        paused = Boolean.getBoolean("voxel.pauseTime");
    }

    public void restoreWorldDays(Double saved) {
        if (saved == null || !Double.isFinite(saved) || saved < 0) {
            System.err.println("[TIME] Missing/invalid worldDays; using 08:00.");
            saved = DEFAULT_DAYS;
        }
        if (System.getProperty("voxel.startHour") == null) setWorldDays(saved);
    }

    public void setWorldDays(double days) {
        if (!Double.isFinite(days) || days < 0) throw new IllegalArgumentException("Invalid worldDays");
        worldDays = days;
    }

    public void setHour(double hour) {
        if (!Double.isFinite(hour) || hour < 0 || hour >= 24)
            throw new IllegalArgumentException("Hour must be finite and in [0,24)");
        worldDays = Math.floor(worldDays) + hour / 24.0;
    }

    public void updateSeconds(double seconds) {
        if (!Double.isFinite(seconds)) throw new IllegalArgumentException("Invalid delta");
        lastTimeBefore = getTimeOfDay();
        lastDeltaMillis = (long)(seconds * 1000);
        double consumed = paused ? 0 : Math.min(0.1, Math.max(0, seconds));
        lastDeltaSecondsClamped = (float)consumed;
        lastDeltaMillisClamped = (long)(consumed * 1000);
        worldDays += consumed * speed / DAY_SECONDS;
        lastTimeAfter = getTimeOfDay();
        frames++;
        long now = System.nanoTime();
        if (now - fpsStart >= 1_000_000_000L) {
            fps = frames; frames = 0; fpsStart = now;
        }
    }

    /** Compatibility API; production update uses seconds without millisecond truncation. */
    public void update(long milliseconds) { updateSeconds(milliseconds / 1000.0); }
    public double getWorldDays() { return worldDays; }
    public boolean hasLaunchOverrides() { return launchOverrides; }
    public float getTimeOfDay() { return (float)(worldDays - Math.floor(worldDays)); }
    public CelestialState celestialState() { return CelestialState.at(worldDays); }
    public SunData getSunData() { return getSunDataAtTimeOfDay(getTimeOfDay()); }
    public SunData getSunDataAtTimeOfDay(float t) {
        Vector3f direction = CelestialState.at(t).sunDirection();
        return new SunData(direction, direction.y);
    }
    public Vector3f getSunDirForShadows(float unused) { return getSunData().sunDir(); }
    public float getDayNightPeriodSec() { return speed == 0 ? Float.POSITIVE_INFINITY : (float)DAY_SECONDS / speed; }
    public float getAmbient() { return celestialState().ambient(); }
    public float getBrightness() { return celestialState().daylight(); }
    public float getSunAngleRadians() { return (float)(2 * Math.PI * (getTimeOfDay() - .25)); }
    public void togglePause() { paused = !paused; }
    public void setPaused(boolean value) { paused = value; }
    public boolean isPaused() { return paused; }
    public float getDebugSpeedMultiplier() { return speed; }
    public void setDebugSpeedMultiplier(float value) {
        if (!Float.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid time scale");
        speed = value;
    }
    public int getFps() { return fps; }
    public long getLastDeltaMillis() { return lastDeltaMillis; }
    public long getLastDeltaMillisClamped() { return lastDeltaMillisClamped; }
    public float getLastDeltaSecondsClamped() { return lastDeltaSecondsClamped; }
    public float getLastTimeBefore() { return lastTimeBefore; }
    public float getLastTimeAfter() { return lastTimeAfter; }
}
