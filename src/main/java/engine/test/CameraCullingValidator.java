package engine.test;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;
import org.lwjgl.system.MemoryUtil;
import engine.render.core.RenderContext;
import engine.render.renderer.SkyRenderer;
import engine.render.renderer.VoxelRenderer;

/** Opt-in correctness check, never a performance measurement. No persistent GPU/native resources. */
public final class CameraCullingValidator {
    private final boolean compareUnlitShadows = Boolean.getBoolean("voxel.validateUnlitShadows");
    private final boolean compareGreedy = Boolean.getBoolean("voxel.compareGreedyImages");
    private final boolean enabled = Boolean.getBoolean("voxel.validateCameraCulling") || compareUnlitShadows || compareGreedy;
    private final String comparison = compareUnlitShadows ? "Unlit shadow" : "Camera culling";
    private float elapsed, nextCheck;

    public boolean due(float dt) {
        if (compareGreedy && (compareUnlitShadows || Boolean.getBoolean("voxel.validateCameraCulling")
                || Boolean.getBoolean("voxel.validateGreedyShadows") || Boolean.getBoolean("voxel.validateShadowCulling")))
            throw new IllegalStateException("Select only greedy image comparison");
        if (compareUnlitShadows && Boolean.getBoolean("voxel.validateCameraCulling")) {
            throw new IllegalStateException("Select one image comparison at a time");
        }
        elapsed += dt;
        if (!enabled || elapsed < nextCheck) return false;
        nextCheck = elapsed + 5;
        return true;
    }

    public void validate(RenderContext context, SkyRenderer sky, VoxelRenderer voxels) {
        int pixels = Math.multiplyExact(context.getWindowWidth(), context.getWindowHeight());
        ByteBuffer colorA = null, colorB = null;
        FloatBuffer depthA = null, depthB = null;
        try {
            colorA = MemoryUtil.memAlloc(Math.multiplyExact(pixels, 4));
            colorB = MemoryUtil.memAlloc(Math.multiplyExact(pixels, 4));
            depthA = MemoryUtil.memAllocFloat(pixels);
            depthB = MemoryUtil.memAllocFloat(pixels);
            requireNoError("entry");
            // Install once BEFORE either shadow replay. No uploads allowed between A and B.
            if (compareGreedy) context.getWorld().getMeshManager().uploadMeshesIfAny();
            draw(context, sky, voxels, false, true);
            read(context, colorA, depthA);
            // No update, shadow pass, upload, UI or temporal advance between the two draws.
            draw(context, sky, voxels, true, false);
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
            if (compareGreedy) {
                compareImages(context, colorA, colorB, depthDifferences);
                if (depthDifferences != 0) throw new IllegalStateException("Camera depth changed during shadow-only comparison");
            } else if (colorDifferences != 0 || depthDifferences != 0) {
                throw new IllegalStateException(comparison + " mismatch: color bytes=" + colorDifferences
                        + "; depth texels=" + depthDifferences);
            }
            if (!compareGreedy) System.out.println(comparison + " validation PASS: elapsed=" + elapsed + "; pixels=" + pixels
                    + "; RGBA8 and depth identical; timeOfDay=" + context.getTimeOfDay());
        } finally {
            if (colorA != null) MemoryUtil.memFree(colorA);
            if (colorB != null) MemoryUtil.memFree(colorB);
            if (depthA != null) MemoryUtil.memFree(depthA);
            if (depthB != null) MemoryUtil.memFree(depthB);
        }
    }

    private void draw(RenderContext context, SkyRenderer sky,
                             VoxelRenderer voxels, boolean tight, boolean advance) {
        if (compareGreedy && voxels.getShadowRenderer().hasCurrentShadowMap(context))
            voxels.getShadowRenderer().replayForImageComparison(context,
                tight && !Boolean.getBoolean("voxel.greedyImageControl"));
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, context.getWindowWidth(), context.getWindowHeight());
        glDisable(GL_SCISSOR_TEST);
        glColorMask(true, true, true, true);
        glDepthMask(true);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        sky.render(context);
        if (compareGreedy) {
            voxels.render(context, engine.core.config.RenderConfig.TIGHT_CAMERA_BOUNDS, false);
        } else if (compareUnlitShadows) {
            voxels.render(context, engine.core.config.RenderConfig.TIGHT_CAMERA_BOUNDS, advance, tight);
        } else {
            voxels.render(context, tight, advance);
        }
        requireNoError("draw");
    }

    private void compareImages(RenderContext context, ByteBuffer a, ByteBuffer b, int depthDifferences) {
        int w = context.getWindowWidth(), h = context.getWindowHeight();
        var before = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var after = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var diff = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        long sum = 0; int changed = 0, above8 = 0, max = 0;
        for (int i = 0; i < w*h; i++) {
            int ca = 0, cb = 0, cd = 0, pixelMax = 0;
            for (int c = 0; c < 3; c++) {
                int va = a.get(i*4+c)&255, vb = b.get(i*4+c)&255, d = Math.abs(va-vb);
                ca = (ca<<8)|va; cb = (cb<<8)|vb; cd = (cd<<8)|Math.min(255,d*8);
                sum += d; pixelMax = Math.max(pixelMax,d);
            }
            if (pixelMax > 0) changed++;
            if (pixelMax > 8) above8++;
            max = Math.max(max,pixelMax);
            int x=i%w, y=h-1-i/w;
            before.setRGB(x,y,ca); after.setRGB(x,y,cb); diff.setRGB(x,y,cd);
        }
        String dir = System.getProperty("voxel.imageComparisonDir", "logs/greedy_images");
        String stem = String.format(java.util.Locale.ROOT,"frame_%.3f", elapsed);
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(dir));
            javax.imageio.ImageIO.write(before,"png",new java.io.File(dir,stem+"_off.png"));
            javax.imageio.ImageIO.write(after,"png",new java.io.File(dir,stem+"_on.png"));
            javax.imageio.ImageIO.write(diff,"png",new java.io.File(dir,stem+"_diff_x8.png"));
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        System.out.println("GREEDY_IMAGE elapsed="+elapsed+" pixels="+(w*h)+" changed="+changed
                +" above8="+above8+" max="+max+" meanAbs="+(sum/(3.0*w*h))+" depthDifferences="+depthDifferences
                +" timeOfDay="+context.getTimeOfDay()+" files="+dir+"/"+stem);
        if (Boolean.getBoolean("voxel.greedyImageControl") && changed != 0)
            throw new IllegalStateException("Identical shadow replay changed the image");
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
