package engine.test;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;
import org.lwjgl.system.MemoryUtil;
import engine.render.core.RenderContext;
import engine.render.renderer.SkyRenderer;
import engine.render.renderer.SunRenderer;
import engine.render.renderer.VoxelRenderer;

/** Opt-in correctness check, never a performance measurement. No persistent GPU/native resources. */
public final class CameraCullingValidator {
    private final boolean enabled = Boolean.getBoolean("voxel.validateCameraCulling");
    private float elapsed, nextCheck;

    public boolean due(float dt) {
        elapsed += dt;
        if (!enabled || elapsed < nextCheck) return false;
        nextCheck = elapsed + 5;
        return true;
    }

    public void validate(RenderContext context, SkyRenderer sky, SunRenderer sun, VoxelRenderer voxels) {
        int pixels = Math.multiplyExact(context.getWindowWidth(), context.getWindowHeight());
        ByteBuffer colorA = null, colorB = null;
        FloatBuffer depthA = null, depthB = null;
        try {
            colorA = MemoryUtil.memAlloc(Math.multiplyExact(pixels, 4));
            colorB = MemoryUtil.memAlloc(Math.multiplyExact(pixels, 4));
            depthA = MemoryUtil.memAllocFloat(pixels);
            depthB = MemoryUtil.memAllocFloat(pixels);
            requireNoError("entry");
            draw(context, sky, sun, voxels, false, true);
            read(context, colorA, depthA);
            // No update, shadow pass, upload, UI or temporal advance between the two draws.
            draw(context, sky, sun, voxels, true, false);
            read(context, colorB, depthB);
            int colorDifferences = 0, depthDifferences = 0;
            for (int i = 0; i < colorA.capacity(); i++) {
                if (colorA.get(i) != colorB.get(i)) colorDifferences++;
            }
            for (int i = 0; i < pixels; i++) {
                if (Float.floatToRawIntBits(depthA.get(i)) != Float.floatToRawIntBits(depthB.get(i))) {
                    depthDifferences++;
                }
            }
            if (colorDifferences != 0 || depthDifferences != 0) {
                throw new IllegalStateException("Camera culling mismatch: color bytes=" + colorDifferences
                        + "; depth texels=" + depthDifferences);
            }
            System.out.println("Camera culling validation PASS: elapsed=" + elapsed + "; pixels=" + pixels
                    + "; RGBA8 and depth identical; timeOfDay=" + context.getTimeOfDay());
        } finally {
            if (colorA != null) MemoryUtil.memFree(colorA);
            if (colorB != null) MemoryUtil.memFree(colorB);
            if (depthA != null) MemoryUtil.memFree(depthA);
            if (depthB != null) MemoryUtil.memFree(depthB);
        }
    }

    private static void draw(RenderContext context, SkyRenderer sky, SunRenderer sun,
                             VoxelRenderer voxels, boolean tight, boolean advance) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, context.getWindowWidth(), context.getWindowHeight());
        glDisable(GL_SCISSOR_TEST);
        glColorMask(true, true, true, true);
        glDepthMask(true);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        sky.render(context);
        sun.render(context);
        voxels.render(context, tight, advance);
        requireNoError("draw");
    }

    private static void read(RenderContext context, ByteBuffer color, FloatBuffer depth) {
        glReadBuffer(GL_BACK);
        glReadPixels(0, 0, context.getWindowWidth(), context.getWindowHeight(), GL_RGBA, GL_UNSIGNED_BYTE, color);
        glReadPixels(0, 0, context.getWindowWidth(), context.getWindowHeight(), GL_DEPTH_COMPONENT, GL_FLOAT, depth);
        requireNoError("readback");
    }

    private static void requireNoError(String stage) {
        int error = glGetError();
        if (error != GL_NO_ERROR) throw new IllegalStateException("Camera validation GL error at " + stage + ": " + error);
    }
}
