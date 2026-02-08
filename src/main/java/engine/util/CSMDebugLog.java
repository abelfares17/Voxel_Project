package engine.util;

import engine.core.config.RenderConfig;
import org.joml.Vector3f;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Minimal CSM debug logger. Writes JSONL to logs/ directory.
 * Rate-limited to prevent flooding. Alerts bypass rate limit.
 */
public class CSMDebugLog {
    private static CSMDebugLog instance;
    private PrintWriter writer;
    private long lastLogTime = 0;
    private static final long LOG_INTERVAL_MS = 100;
    private int frameCounter = 0;
    private boolean enabled = true;

    private CSMDebugLog() {
        try {
            Path logsDir = Paths.get("logs");
            if (!Files.exists(logsDir)) {
                Files.createDirectories(logsDir);
            }
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path logFile = logsDir.resolve("csm_debug_" + timestamp + ".jsonl");
            writer = new PrintWriter(new BufferedWriter(new FileWriter(logFile.toFile())));
            System.out.println("[CSMDebugLog] Writing to: " + logFile.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("[CSMDebugLog] Failed to create log file: " + e.getMessage());
            enabled = false;
        }
    }

    public static CSMDebugLog get() {
        if (instance == null) {
            instance = new CSMDebugLog();
        }
        return instance;
    }

    public void logMetadata(String worldName, long seed) {
        if (!enabled) return;
        writer.printf("{\"type\":\"META\",\"worldName\":\"%s\",\"seed\":%d,\"cascadeCount\":%d,\"shadowMapSize\":%d,\"shadowFar\":%.1f}%n",
            worldName != null ? worldName : "unknown",
            seed,
            RenderConfig.CSM_CASCADE_COUNT,
            RenderConfig.CSM_SHADOW_MAP_SIZE,
            RenderConfig.CSM_SHADOW_FAR);
        writer.flush();
    }

    public void nextFrame() {
        frameCounter++;
    }

    public boolean shouldLog() {
        if (!enabled) return false;
        long now = System.currentTimeMillis();
        if (now - lastLogTime >= LOG_INTERVAL_MS) {
            lastLogTime = now;
            return true;
        }
        return false;
    }

    public void logCascade(int cascade, float yaw, float pitch, Vector3f pos,
                           float halfSize, float sphereRadius, float texelSize,
                           float snappedX, float snappedY,
                           Vector3f camFwd, Vector3f lightDir) {
        if (!enabled) return;
        writer.printf("{\"ts\":%d,\"frame\":%d,\"type\":\"CASCADE\",\"cascade\":%d," +
                "\"yaw\":%.2f,\"pitch\":%.2f,\"pos\":[%.2f,%.2f,%.2f]," +
                "\"halfSize\":%.4f,\"sphereRadius\":%.4f,\"texelSize\":%.6f," +
                "\"snappedX\":%.4f,\"snappedY\":%.4f," +
                "\"camFwd\":[%.4f,%.4f,%.4f],\"lightDir\":[%.4f,%.4f,%.4f]}%n",
            System.currentTimeMillis(), frameCounter, cascade,
            yaw, pitch, pos.x, pos.y, pos.z,
            halfSize, sphereRadius, texelSize,
            snappedX, snappedY,
            camFwd.x, camFwd.y, camFwd.z,
            lightDir.x, lightDir.y, lightDir.z);
        writer.flush();
    }

    public void logBasis(float yaw, float pitch, Vector3f camFwd, Vector3f camRight, Vector3f camUp) {
        if (!enabled) return;
        writer.printf("{\"ts\":%d,\"frame\":%d,\"type\":\"BASIS\"," +
                "\"yaw\":%.2f,\"pitch\":%.2f," +
                "\"camFwd\":[%.6f,%.6f,%.6f]," +
                "\"camRight\":[%.6f,%.6f,%.6f]," +
                "\"camUp\":[%.6f,%.6f,%.6f]}%n",
            System.currentTimeMillis(), frameCounter,
            yaw, pitch,
            camFwd.x, camFwd.y, camFwd.z,
            camRight.x, camRight.y, camRight.z,
            camUp.x, camUp.y, camUp.z);
        writer.flush();
    }

    // ALERTS - always logged, bypass rate limit
    public void alertHalfSize(int cascade, float halfSize, float sphereRadius) {
        if (!enabled) return;
        float overPercent = 100f * (halfSize / sphereRadius - 1f);
        writer.printf("{\"ts\":%d,\"frame\":%d,\"type\":\"ALERT\",\"alert\":\"HALFSIZE\"," +
                "\"cascade\":%d,\"halfSize\":%.4f,\"sphereRadius\":%.4f,\"overPercent\":%.2f}%n",
            System.currentTimeMillis(), frameCounter, cascade, halfSize, sphereRadius, overPercent);
        writer.flush();
        System.out.printf("[ALERT:HALFSIZE] Cascade %d: halfSize=%.4f > sphere=%.4f (+%.2f%%)%n",
            cascade, halfSize, sphereRadius, overPercent);
    }

    public void alertTexelSize(int cascade, float cached, float computed) {
        if (!enabled) return;
        float driftPercent = 100f * Math.abs(computed - cached) / cached;
        writer.printf("{\"ts\":%d,\"frame\":%d,\"type\":\"ALERT\",\"alert\":\"TEXELSIZE\"," +
                "\"cascade\":%d,\"cached\":%.6f,\"computed\":%.6f,\"driftPercent\":%.2f}%n",
            System.currentTimeMillis(), frameCounter, cascade, cached, computed, driftPercent);
        writer.flush();
        System.out.printf("[ALERT:TEXELSIZE] Cascade %d: cached=%.6f computed=%.6f (drift %.2f%%)%n",
            cascade, cached, computed, driftPercent);
    }

    public void alertBasisDrift(float yaw, float distance) {
        if (!enabled) return;
        writer.printf("{\"ts\":%d,\"frame\":%d,\"type\":\"ALERT\",\"alert\":\"BASIS_DRIFT\"," +
                "\"yaw\":%.2f,\"distance\":%.6f}%n",
            System.currentTimeMillis(), frameCounter, yaw, distance);
        writer.flush();
        System.out.printf("[ALERT:BASIS_DRIFT] yaw=%.2f distance=%.6f%n", yaw, distance);
    }

    public void close() {
        if (writer != null) {
            writer.close();
        }
    }
}
