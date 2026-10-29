package engine.render;

import engine.render.core.RenderContext;
import engine.core.config.RenderConfig;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL33.*;

/** Optional diagnostic, owned by PlayingState and closed before context destruction. */
public final class GpuPassTimer implements AutoCloseable {
    public enum Pass { SHADOW, SKY_SUN, VOXEL, UI, SCENE }
    // Alternate diagnostic: one query around sky + voxels, never nested elapsed queries.
    public static final boolean COMBINED_SCENE = Boolean.getBoolean("voxel.gpuTimingScene");
    private static final int RING_SIZE = 8;
    private static final class Slot {
        final int[] queries = new int[Pass.values().length];
        final boolean[] issued = new boolean[Pass.values().length];
        boolean pending;
        String metadata;
    }
    private final Slot[] slots = new Slot[RING_SIZE];
    private final PrintWriter writer;
    private long frame;
    private double elapsed;
    private Slot current;
    private boolean passActive;
    private int skipped;

    /** Returns null when disabled or unsupported; creates no GL objects in that case. */
    public static GpuPassTimer createIfEnabled() {
        if (!Boolean.getBoolean("voxel.gpuTiming")) return null;
        var caps = GL.getCapabilities();
        if (!(caps.OpenGL33 || caps.GL_ARB_timer_query)
                || glGetQueryi(GL_TIME_ELAPSED, GL_QUERY_COUNTER_BITS) == 0) {
            System.out.println("GPU timing unavailable: elapsed queries unsupported or zero counter bits.");
            return null;
        }
        try {
            return new GpuPassTimer();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot open GPU timing log", e);
        }
    }

    private GpuPassTimer() throws IOException {
        Path path = Path.of("logs", "gpu_pass_" + LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss_SSS")) + ".csv");
        Files.createDirectories(path.getParent());
        writer = new PrintWriter(Files.newBufferedWriter(path));
        writer.println("frameIndex,elapsed_s,fb_width,fb_height,time_of_day,pos_x,pos_y,pos_z,shadow_frustum_culling,tight_shadow_bounds,tight_camera_bounds,indexed_meshes,compact_vertices,skip_unlit_shadows,greedy_shadows,"
                + "sky_mode,active_light,light_elevation,time_scale,paused,start_hour,shadow_fade,legacy_caster_residency,shadow_draws,shadow_vertices,voxel_draws,voxel_vertices,"
                + "gpu_shadow_ms,gpu_sky_sun_ms,gpu_voxel_ms,gpu_ui_ms,gpu_scene_ms");
        for (int i = 0; i < slots.length; i++) {
            slots[i] = new Slot();
            for (int p = 0; p < slots[i].queries.length; p++) {
                slots[i].queries[p] = glGenQueries();
            }
        }
        System.out.println("GPU pass timing: " + path + "; asynchronous ring=" + RING_SIZE);
    }

    public void beginFrame(RenderContext context) {
        frame++;
        elapsed += context.getDeltaTime();
        Slot slot = slots[(int) ((frame - 1) % RING_SIZE)];
        collect(slot); // Read only after availability; never wait for an unfinished GPU frame.
        if (slot.pending) {
            skipped++;
            current = null;
            return;
        }
        current = slot;
        java.util.Arrays.fill(slot.issued, false);
        Vector3f pos = context.getCamera().getPosition();
        slot.metadata = String.format(Locale.ROOT, "%d,%.6f,%d,%d,%.7f,%.3f,%.3f,%.3f",
                frame, elapsed, context.getWindowWidth(), context.getWindowHeight(),
                context.getTimeOfDay(), pos.x, pos.y, pos.z)
                + "," + RenderConfig.SHADOW_FRUSTUM_CULLING_ENABLED
                + "," + RenderConfig.TIGHT_SHADOW_BOUNDS + "," + RenderConfig.TIGHT_CAMERA_BOUNDS
                + "," + RenderConfig.INDEXED_MESHES + "," + RenderConfig.COMPACT_VERTICES
                + "," + RenderConfig.SKIP_UNLIT_SHADOWS + "," + RenderConfig.GREEDY_SHADOWS
                + "," + System.getProperty("voxel.skyMode", "procedural")
                + "," + context.getCelestialState().activeLight()
                + "," + context.getCelestialState().activeElevation()
                + "," + context.getWorld().getTimeManager().getDebugSpeedMultiplier()
                + "," + context.getWorld().getTimeManager().isPaused()
                + "," + System.getProperty("voxel.startHour", "saved/default")
                + "," + context.getShadowCoverage().fadeEnabled() + "," + RenderConfig.LEGACY_CASTER_RESIDENCY;
    }

    public void beginPass(Pass pass) {
        if (current == null) return;
        if (passActive) throw new IllegalStateException("GPU queries must not overlap");
        glBeginQuery(GL_TIME_ELAPSED, current.queries[pass.ordinal()]);
        current.issued[pass.ordinal()] = true;
        passActive = true;
    }

    public void endPass() {
        if (!passActive) return;
        glEndQuery(GL_TIME_ELAPSED);
        passActive = false;
    }

    public void endFrame(int shadowDraws, long shadowVertices, int voxelDraws, long voxelVertices) {
        if (current == null) return;
        current.metadata += "," + shadowDraws + "," + shadowVertices + "," + voxelDraws + "," + voxelVertices;
        current.pending = true;
        current = null;
    }

    private void collect(Slot slot) {
        if (!slot.pending) return;
        for (int i = 0; i < slot.queries.length; i++) {
            if (slot.issued[i] && glGetQueryObjecti(slot.queries[i], GL_QUERY_RESULT_AVAILABLE) == 0) return;
        }
        writer.print(slot.metadata);
        for (int i = 0; i < slot.queries.length; i++) {
            if (slot.issued[i]) writer.printf(Locale.ROOT, ",%.6f", glGetQueryObjectui64(slot.queries[i], GL_QUERY_RESULT) / 1e6);
            else writer.print(",NaN"); // Deliberately unmeasured, never pretend this pass costs zero.
        }
        writer.println();
        slot.pending = false;
    }

    @Override
    public void close() {
        endPass();
        int pending = 0;
        for (Slot slot : slots) {
            collect(slot);
            if (slot.pending) pending++;
            for (int query : slot.queries) glDeleteQueries(query);
        }
        writer.close();
        System.out.println("GPU timing closed: skipped=" + skipped + "; pending samples discarded=" + pending);
    }
}
