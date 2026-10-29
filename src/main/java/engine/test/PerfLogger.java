package engine.test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.joml.Vector3f;

/**
 * Performance logger for automated test harness.
 * Logs frame metrics to CSV file and console for reproducible performance analysis.
 */
public class PerfLogger implements AutoCloseable {
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

    private final PrintWriter csvWriter;
    private final String csvFilePath;
    private final int logFrequency;
    private int frameCounter = 0;

    /**
     * Create a performance logger.
     *
     * @param worldName Name of the test world (for filename)
     * @param logFrequency How often to log (1 = every frame, 10 = every 10th frame)
     * @throws IOException If file creation fails
     */
    public PerfLogger(String worldName, int logFrequency) throws IOException {
        this.logFrequency = logFrequency;

        // Create timestamped CSV file
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String safeWorldName = worldName.replace(" ", "_").replaceAll("[^a-zA-Z0-9_-]", "");
        this.csvFilePath = "logs/perf_test_" + safeWorldName + "_" + timestamp + ".csv";

        File csvFile = new File(csvFilePath);
        if (csvFile.getParentFile() != null) {
            csvFile.getParentFile().mkdirs();
        }
        this.csvWriter = new PrintWriter(new FileWriter(csvFile));

        // Write CSV header
        csvWriter.println("frameIndex,dt,update_ms,render_ms,swap_ms,glfinish_ms,shadow_finish_ms,fps_instant,fps_smooth," +
                          "pos_x,pos_y,pos_z,yaw,pitch," +
                          "visibleChunks,renderedVertices," +
                          "shadowPassEnabled,leapfrogEnabled,debugShadowMode,perfBisectStage," +
                          "shadowFrustumCulling,fbWidth,fbHeight,timeOfDay,tightShadowBounds,tightCameraBounds,indexedMeshes,storedVertices,meshBytes,focused,visible,iconified,cursorMode,cacheUniforms,uniformLocationQueries,compactVertices,skipUnlitShadows,greedyShadows,skyMode,activeLightPreviousFrame,lightElevationPreviousFrame,shadowCasterResidency,residentChunks,casterRequested,casterReady,casterCandidates,casterReach,casterReachLimited,casterBudgetLimited,legacyCasterResidency,shadowFadePreviousFrame,fadeStart,fadeEnd,requiredReadyPreviousFrame,requiredPreviousFrame,prefetchReadyPreviousFrame,prefetchPreviousFrame,coverageCompletePreviousFrame,samplingGuardValidPreviousFrame");

        System.out.println("Performance logging started: " + csvFilePath);
    }

    /**
     * Log a frame's performance metrics.
     *
     * @param frameIndex Current frame number
     * @param dt Delta time (seconds)
     * @param updateMs Update time (milliseconds)
     * @param renderMs Render time (milliseconds)
     * @param swapMs Swap buffers time (milliseconds)
     * @param glFinishMs glFinish time (milliseconds)
     * @param fpsInstant Instantaneous FPS (1/dt)
     * @param fpsSmooth Smoothed FPS (e.g., from 1-second window)
     * @param position Player position (x, y, z)
     * @param yaw Camera yaw angle (degrees)
     * @param pitch Camera pitch angle (degrees)
     * @param visibleChunks Number of visible chunks
     * @param renderedVertices Number of rendered vertices
     * @param shadowPassEnabled Is shadow rendering enabled?
     * @param leapfrogEnabled Is leapfrog shadow system enabled?
     * @param debugShadowMode Shadow debug visualization mode
     * @param perfBisectStage Performance bisection stage identifier
     * @param shadowFrustumCulling Whether the additional light-frustum culling is enabled
     * @param fbWidth Actual framebuffer width in pixels
     * @param fbHeight Actual framebuffer height in pixels
     * @param timeOfDay Current world time (pose/time are from update; CPU timings describe the previous frame)
     * @param tightShadowBounds Whether shadow culling uses uploaded mesh bounds
     * @param tightCameraBounds Whether camera culling uses uploaded mesh bounds
     */
    public void logFrame(int frameIndex, float dt,
                        double updateMs, double renderMs, double swapMs, double glFinishMs,
                        double shadowFinishMs,
                        float fpsInstant, int fpsSmooth,
                        Vector3f position, float yaw, float pitch,
                        int visibleChunks, long renderedVertices,
                        boolean shadowPassEnabled, boolean leapfrogEnabled,
                        int debugShadowMode, String perfBisectStage,
                        boolean shadowFrustumCulling, int fbWidth, int fbHeight, float timeOfDay,
                        boolean tightShadowBounds, boolean tightCameraBounds, String meshWindowMetrics) {

        frameCounter++;

        // Only log every Nth frame (based on frequency)
        if (frameCounter % logFrequency != 0) {
            return;
        }

        // Write to CSV (Locale.US ensures '.' as decimal separator for parseable CSV)
        csvWriter.printf(Locale.US, "%d,%.4f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%d," +
                        "%.3f,%.3f,%.3f,%.2f,%.2f," +
                        "%d,%d," +
                        "%b,%b,%d,%s,%b,%d,%d,%.7f,%b,%b,%s%n",
                frameIndex, dt, updateMs, renderMs, swapMs, glFinishMs, shadowFinishMs,
                fpsInstant, fpsSmooth,
                position.x, position.y, position.z, yaw, pitch,
                visibleChunks, renderedVertices,
                shadowPassEnabled, leapfrogEnabled, debugShadowMode,
                perfBisectStage != null ? perfBisectStage : "",
                shadowFrustumCulling, fbWidth, fbHeight, timeOfDay, tightShadowBounds, tightCameraBounds, meshWindowMetrics);

        csvWriter.flush(); // Ensure data is written immediately

        // Echo to console disabled to avoid terminal spam
        // if (frameCounter % 30 == 0) {
        //     System.out.printf("[PERF] Frame %d | FPS: %d | Pos: (%.1f, %.1f, %.1f) | " +
        //                      "Update: %.2fms | Render: %.2fms%n",
        //             frameIndex, fpsSmooth, position.x, position.y, position.z,
        //             updateMs, renderMs);
        // }
    }

    @Override
    public void close() {
        if (csvWriter != null) {
            csvWriter.close();
            System.out.println("Performance logging complete: " + csvFilePath);
        }
    }
}
