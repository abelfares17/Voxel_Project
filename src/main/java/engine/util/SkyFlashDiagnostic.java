package engine.util;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Diagnostic logger for sky flash issues.
 * Logs GL state at critical moments to identify what causes sky color flashes.
 */
public class SkyFlashDiagnostic {

    private static SkyFlashDiagnostic instance;
    private PrintWriter logWriter;
    private long frameNumber = 0;
    private long startTime;

    // Track previous frame state for delta detection
    private int prevFBO = -1;
    private int prevDrawBuffer = -1;
    private int prevReadBuffer = -1;
    private boolean[] prevColorMask = {true, true, true, true};
    private float prevSkyR = -1, prevSkyG = -1, prevSkyB = -1;

    // Track leapfrog state
    private boolean prevIsTransitioning = false;
    private float prevCrossfadeProgress = 1.0f;
    private boolean prevPrimaryIsA = true;

    // Anomaly detection
    private int anomalyCount = 0;
    private static final int MAX_ANOMALIES_TO_LOG = 100;

    private SkyFlashDiagnostic() {
        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            logWriter = new PrintWriter(new FileWriter("logs/sky_flash_diagnostic_" + timestamp + ".log"));
            startTime = System.currentTimeMillis();
            logWriter.println("=== SKY FLASH DIAGNOSTIC LOG ===");
            logWriter.println("Started: " + new Date());
            logWriter.println("Purpose: Identify root cause of sky color flashes during leapfrog CSM transitions");
            logWriter.println();
            logWriter.println("HYPOTHESIS TO TEST:");
            logWriter.println("  H1: GL state leak from shadow pass (FBO, colorMask, drawBuffer)");
            logWriter.println("  H2: Leapfrog transition START causes flash");
            logWriter.println("  H3: Leapfrog transition END (swap) causes flash");
            logWriter.println("  H4: renderShadowMap() itself causes flash");
            logWriter.println("  H5: Crossfade progress jump causes flash");
            logWriter.println();
            logWriter.println("LEGEND:");
            logWriter.println("  [SHADOW_ENTER] = About to enter shadow pass");
            logWriter.println("  [SHADOW_EXIT]  = Just exited shadow pass");
            logWriter.println("  [SKY_RENDER]   = SkyRenderer.render() called");
            logWriter.println("  [LEAPFROG]     = Leapfrog state change");
            logWriter.println("  [ANOMALY]      = Unexpected state detected");
            logWriter.println();
            logWriter.flush();
        } catch (IOException e) {
            System.err.println("Failed to create sky flash diagnostic log: " + e.getMessage());
        }
    }

    public static SkyFlashDiagnostic get() {
        if (instance == null) {
            instance = new SkyFlashDiagnostic();
        }
        return instance;
    }

    public void nextFrame() {
        frameNumber++;
    }

    public long getFrameNumber() {
        return frameNumber;
    }

    /**
     * Log GL state at shadow pass entry.
     */
    public void logShadowEnter(String location, boolean isTransitioning, float crossfadeProgress, boolean primaryIsA) {
        if (logWriter == null) return;

        int fbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
        int drawBuffer = glGetInteger(GL_DRAW_BUFFER);
        int readBuffer = glGetInteger(GL_READ_BUFFER);
        boolean[] colorMask = getColorMask();

        boolean stateOK = (fbo == 0) && (drawBuffer == GL_BACK || drawBuffer == GL_FRONT) &&
                          colorMask[0] && colorMask[1] && colorMask[2] && colorMask[3];

        if (!stateOK) {
            logAnomaly("SHADOW_ENTER", location, fbo, drawBuffer, readBuffer, colorMask,
                       isTransitioning, crossfadeProgress, primaryIsA);
        }

        // Always log leapfrog state changes
        if (isTransitioning != prevIsTransitioning ||
            Math.abs(crossfadeProgress - prevCrossfadeProgress) > 0.1f ||
            primaryIsA != prevPrimaryIsA) {

            logLeapfrogChange("SHADOW_ENTER", location, isTransitioning, crossfadeProgress, primaryIsA);
        }

        prevFBO = fbo;
        prevDrawBuffer = drawBuffer;
        prevReadBuffer = readBuffer;
        System.arraycopy(colorMask, 0, prevColorMask, 0, 4);
        prevIsTransitioning = isTransitioning;
        prevCrossfadeProgress = crossfadeProgress;
        prevPrimaryIsA = primaryIsA;
    }

    /**
     * Log GL state at shadow pass exit.
     */
    public void logShadowExit(String location, int depthPasses) {
        if (logWriter == null) return;

        int fbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
        int drawBuffer = glGetInteger(GL_DRAW_BUFFER);
        int readBuffer = glGetInteger(GL_READ_BUFFER);
        boolean[] colorMask = getColorMask();

        boolean stateOK = (fbo == 0) && (drawBuffer == GL_BACK || drawBuffer == GL_FRONT) &&
                          colorMask[0] && colorMask[1] && colorMask[2] && colorMask[3];

        if (!stateOK) {
            logAnomaly("SHADOW_EXIT", location + " (depthPasses=" + depthPasses + ")",
                       fbo, drawBuffer, readBuffer, colorMask,
                       prevIsTransitioning, prevCrossfadeProgress, prevPrimaryIsA);
        }

        // Check for state change during shadow pass
        if (fbo != 0 || drawBuffer != prevDrawBuffer || !colorMasksEqual(colorMask, prevColorMask)) {
            synchronized (logWriter) {
                logWriter.printf("[%d] [SHADOW_EXIT] %s: STATE CHANGED DURING PASS%n", frameNumber, location);
                logWriter.printf("    Before: FBO=%d draw=%d mask=[%b,%b,%b,%b]%n",
                    prevFBO, prevDrawBuffer, prevColorMask[0], prevColorMask[1], prevColorMask[2], prevColorMask[3]);
                logWriter.printf("    After:  FBO=%d draw=%d mask=[%b,%b,%b,%b]%n",
                    fbo, drawBuffer, colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
                logWriter.flush();
            }
        }

        prevFBO = fbo;
        prevDrawBuffer = drawBuffer;
        prevReadBuffer = readBuffer;
        System.arraycopy(colorMask, 0, prevColorMask, 0, 4);
    }

    /**
     * Log state at SkyRenderer entry.
     */
    public void logSkyRender(float skyR, float skyG, float skyB) {
        if (logWriter == null) return;

        int fbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
        int drawBuffer = glGetInteger(GL_DRAW_BUFFER);
        int readBuffer = glGetInteger(GL_READ_BUFFER);
        boolean[] colorMask = getColorMask();

        boolean stateOK = (fbo == 0) && (drawBuffer == GL_BACK || drawBuffer == GL_FRONT) &&
                          colorMask[0] && colorMask[1] && colorMask[2] && colorMask[3];

        // Check for sky color jump
        boolean skyJump = false;
        if (prevSkyR >= 0) {
            float deltaR = Math.abs(skyR - prevSkyR);
            float deltaG = Math.abs(skyG - prevSkyG);
            float deltaB = Math.abs(skyB - prevSkyB);
            skyJump = (deltaR > 0.1f || deltaG > 0.1f || deltaB > 0.1f);
        }

        if (!stateOK || skyJump) {
            synchronized (logWriter) {
                logWriter.printf("[%d] [SKY_RENDER] %s%n", frameNumber,
                    !stateOK ? "*** GL STATE LEAK DETECTED ***" : "");
                logWriter.printf("    GL State: FBO=%d draw=%d read=%d mask=[%b,%b,%b,%b]%n",
                    fbo, drawBuffer, readBuffer, colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
                if (skyJump) {
                    logWriter.printf("    *** SKY COLOR JUMP: (%.3f,%.3f,%.3f) -> (%.3f,%.3f,%.3f)%n",
                        prevSkyR, prevSkyG, prevSkyB, skyR, skyG, skyB);
                }
                logWriter.printf("    Leapfrog: transitioning=%b progress=%.2f primaryIsA=%b%n",
                    prevIsTransitioning, prevCrossfadeProgress, prevPrimaryIsA);
                logWriter.flush();
            }
            anomalyCount++;
        }

        prevSkyR = skyR;
        prevSkyG = skyG;
        prevSkyB = skyB;
        prevFBO = fbo;
        prevDrawBuffer = drawBuffer;
        prevReadBuffer = readBuffer;
        System.arraycopy(colorMask, 0, prevColorMask, 0, 4);
    }

    /**
     * Log leapfrog transition events.
     */
    public void logLeapfrogEvent(String event, boolean isTransitioning, float crossfadeProgress,
                                  boolean primaryIsA, int depthPasses) {
        if (logWriter == null) return;

        int fbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
        int drawBuffer = glGetInteger(GL_DRAW_BUFFER);
        boolean[] colorMask = getColorMask();

        synchronized (logWriter) {
            logWriter.printf("[%d] [LEAPFROG] %s%n", frameNumber, event);
            logWriter.printf("    State: transitioning=%b progress=%.3f primaryIsA=%b depthPasses=%d%n",
                isTransitioning, crossfadeProgress, primaryIsA, depthPasses);
            logWriter.printf("    GL: FBO=%d draw=%d mask=[%b,%b,%b,%b]%n",
                fbo, drawBuffer, colorMask[0], colorMask[1], colorMask[2], colorMask[3]);

            // Detect specific transition events
            if (event.contains("START")) {
                logWriter.println("    >>> TRANSITION START - Watch for flash on next frames <<<");
            } else if (event.contains("END") || event.contains("SWAP")) {
                logWriter.println("    >>> TRANSITION END/SWAP - Watch for flash on this frame <<<");
            }

            logWriter.flush();
        }

        prevIsTransitioning = isTransitioning;
        prevCrossfadeProgress = crossfadeProgress;
        prevPrimaryIsA = primaryIsA;
    }

    /**
     * Log when a depth pass is rendered.
     */
    public void logDepthPass(String target, int arrayId) {
        if (logWriter == null) return;

        int fbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
        int drawBuffer = glGetInteger(GL_DRAW_BUFFER);

        // Only log if state is unexpected during depth pass
        // During depth pass, FBO should be non-zero, drawBuffer should be GL_NONE
        if (fbo == 0) {
            synchronized (logWriter) {
                logWriter.printf("[%d] [DEPTH_PASS] %s (array=%d) - WARNING: FBO=0 during depth pass!%n",
                    frameNumber, target, arrayId);
                logWriter.flush();
            }
        }
    }

    private void logAnomaly(String phase, String location, int fbo, int drawBuffer, int readBuffer,
                           boolean[] colorMask, boolean isTransitioning, float crossfadeProgress, boolean primaryIsA) {
        if (logWriter == null || anomalyCount >= MAX_ANOMALIES_TO_LOG) return;

        synchronized (logWriter) {
            logWriter.printf("[%d] [ANOMALY] at %s (%s)%n", frameNumber, phase, location);
            logWriter.printf("    GL State: FBO=%d draw=%d read=%d mask=[%b,%b,%b,%b]%n",
                fbo, drawBuffer, readBuffer, colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
            logWriter.printf("    Expected: FBO=0 draw=%d read=%d mask=[true,true,true,true]%n",
                GL_BACK, GL_BACK);
            logWriter.printf("    Leapfrog: transitioning=%b progress=%.3f primaryIsA=%b%n",
                isTransitioning, crossfadeProgress, primaryIsA);
            logWriter.flush();
        }
        anomalyCount++;
    }

    private void logLeapfrogChange(String phase, String location, boolean isTransitioning,
                                   float crossfadeProgress, boolean primaryIsA) {
        if (logWriter == null) return;

        synchronized (logWriter) {
            logWriter.printf("[%d] [LEAPFROG_CHANGE] at %s (%s)%n", frameNumber, phase, location);
            logWriter.printf("    Previous: transitioning=%b progress=%.3f primaryIsA=%b%n",
                prevIsTransitioning, prevCrossfadeProgress, prevPrimaryIsA);
            logWriter.printf("    Current:  transitioning=%b progress=%.3f primaryIsA=%b%n",
                isTransitioning, crossfadeProgress, primaryIsA);

            // Highlight critical transitions
            if (!prevIsTransitioning && isTransitioning) {
                logWriter.println("    >>> TRANSITION STARTED - This frame renders to secondary <<<");
            }
            if (prevIsTransitioning && !isTransitioning) {
                logWriter.println("    >>> TRANSITION ENDED - Primary/secondary swapped <<<");
            }
            if (prevPrimaryIsA != primaryIsA) {
                logWriter.println("    >>> PRIMARY SWAPPED: " + (primaryIsA ? "A" : "B") + " is now primary <<<");
            }

            logWriter.flush();
        }
    }

    private boolean[] getColorMask() {
        int[] mask = new int[4];
        glGetIntegerv(GL_COLOR_WRITEMASK, mask);
        return new boolean[] {mask[0] != 0, mask[1] != 0, mask[2] != 0, mask[3] != 0};
    }

    private boolean colorMasksEqual(boolean[] a, boolean[] b) {
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2] && a[3] == b[3];
    }

    public void close() {
        if (logWriter != null) {
            logWriter.println();
            logWriter.println("=== DIAGNOSTIC SUMMARY ===");
            logWriter.printf("Total frames: %d%n", frameNumber);
            logWriter.printf("Total anomalies: %d%n", anomalyCount);
            logWriter.printf("Runtime: %d ms%n", System.currentTimeMillis() - startTime);
            logWriter.close();
        }
    }
}
