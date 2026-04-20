package engine.core;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import engine.core.config.GameConfig;

/**
 * Handles fatal exceptions with logging and graceful shutdown.
 *
 * When a fatal error occurs:
 * 1. Logs detailed error information to console
 * 2. Creates a crash log file (in test mode)
 * 3. Triggers application shutdown
 */
public class CrashHandler {
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

    /**
     * Handle a fatal exception and initiate graceful shutdown.
     *
     * @param context Description of where/when the error occurred
     * @param throwable The exception that caused the crash
     */
    public static void fatal(String context, Throwable throwable) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);

        // Log to console
        System.err.println("═══════════════════════════════════════════════════════════");
        System.err.println("FATAL ERROR: " + context);
        System.err.println("Time: " + timestamp);
        System.err.println("Thread: " + Thread.currentThread().getName());
        System.err.println("Exception: " + throwable.getClass().getSimpleName());
        System.err.println("Message: " + throwable.getMessage());
        System.err.println("═══════════════════════════════════════════════════════════");
        throwable.printStackTrace(System.err);
        System.err.println("═══════════════════════════════════════════════════════════");

        // Write crash log file (in test mode)
        if (GameConfig.TEST_HARNESS_ENABLED) {
            writeCrashLog(context, timestamp, throwable);
        }

        // Note: Application.loop() should call glfwSetWindowShouldClose(window, true)
        // after calling this method to trigger shutdown
    }

    /**
     * Write detailed crash log to file.
     */
    private static void writeCrashLog(String context, String timestamp, Throwable throwable) {
        try {
            File logFile = new File("logs/crash_" + timestamp + ".log");
            try (PrintWriter writer = new PrintWriter(new FileWriter(logFile))) {
                writer.println("═══════════════════════════════════════════════════════════");
                writer.println("CRASH REPORT");
                writer.println("═══════════════════════════════════════════════════════════");
                writer.println("Time: " + timestamp);
                writer.println("Context: " + context);
                writer.println("Thread: " + Thread.currentThread().getName());
                writer.println("Exception: " + throwable.getClass().getName());
                writer.println("Message: " + throwable.getMessage());
                writer.println("═══════════════════════════════════════════════════════════");
                writer.println("STACK TRACE:");
                writer.println("═══════════════════════════════════════════════════════════");
                throwable.printStackTrace(writer);
                writer.println("═══════════════════════════════════════════════════════════");
            }
            System.err.println("Crash log written to: " + logFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("Failed to write crash log: " + e.getMessage());
        }
    }
}
