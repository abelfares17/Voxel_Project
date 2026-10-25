package engine.render.renderer;

import java.util.Random;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import static org.lwjgl.opengl.GL32.*;
import engine.render.Shader;
import engine.render.camera.PerspectiveCamera;
import engine.render.core.RenderContext;
import engine.time.CelestialState;

/** Owns sky/disc/star programs and geometry. Colors are linear; framebuffer sRGB is external.
 * Entry: any previous pass. Exit: screen FBO, viewport restored, depth LESS/write enabled,
 * blending/scissor/program-point-size disabled, back-face CCW culling, program/VAO unbound.
 */
public final class SkyRenderer implements Renderer {
    public static final int STAR_COUNT = 512;
    private final String mode;
    private final Shader sky, stars;
    private int skyVao, starVao, starVbo;
    private Long catalogueSeed;
    private boolean closed;

    public SkyRenderer() {
        mode = System.getProperty("voxel.skyMode", "procedural");
        if (!mode.equals("flat") && !mode.equals("procedural"))
            throw new IllegalArgumentException("voxel.skyMode must be flat or procedural");
        sky = new Shader("shaders/sky_vertex.glsl", "shaders/sky_fragment.glsl");
        stars = new Shader("shaders/stars_vertex.glsl", "shaders/stars_fragment.glsl");
        skyVao = glGenVertexArrays(); // fullscreen vertices from gl_VertexID (GLSL 150)
        starVao = glGenVertexArrays();
        starVbo = glGenBuffers();
        glBindVertexArray(starVao);
        glBindBuffer(GL_ARRAY_BUFFER, starVbo);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 16, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(3, 1, GL_FLOAT, false, 16, 12);
        glEnableVertexAttribArray(3);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /** Stable world-seeded directions uniformly distributed over the sphere. */
    public static float[] starCatalogue(long seed) {
        Random random = new Random(seed ^ 0x53544152534B594CL);
        float[] data = new float[STAR_COUNT * 4];
        for (int i = 0; i < STAR_COUNT; i++) {
            double y = random.nextDouble() * 2 - 1, a = random.nextDouble() * 2 * Math.PI;
            double r = Math.sqrt(1 - y * y);
            data[i*4] = (float)(r * Math.cos(a));
            data[i*4+1] = (float)y;
            data[i*4+2] = (float)(r * Math.sin(a));
            data[i*4+3] = .35f + .65f * random.nextFloat();
        }
        return data;
    }

    @Override public void render(RenderContext context) {
        if (closed) throw new IllegalStateException("Sky renderer already closed");
        var state = context.getCelestialState();
        Vector3f horizon = state.horizon();
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glDrawBuffer(GL_BACK);
        glReadBuffer(GL_BACK);
        glViewport(0, 0, context.getWindowWidth(), context.getWindowHeight());
        glColorMask(true, true, true, true);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_BLEND);
        glDisable(GL_STENCIL_TEST);
        glDepthMask(true);
        glClearDepth(1);
        glClearColor(horizon.x, horizon.y, horizon.z, 1);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        context.recordSkyClear(horizon.x, horizon.y, horizon.z, 1, true, true);
        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);
        try {
            if (mode.equals("flat")) return;
            Matrix4f view = context.getCamera() instanceof PerspectiveCamera pc ? pc.getViewMatrix()
                    : context.getCamera().getViewMatrix(context.getCamera().getPosition(), 0, 0);
            Matrix4f rotationView = new Matrix4f(view).m30(0).m31(0).m32(0);
            sky.bind();
            sky.setUniform("inverseProjection", new Matrix4f(context.getCamera().getProjectionMatrix()).invert());
            sky.setUniform("inverseViewRotation", new Matrix4f(rotationView).invert());
            sky.setUniform("horizonColor", horizon);
            sky.setUniform("zenithColor", state.zenith());
            sky.setUniform("sunElevation", state.sunElevation());
            sky.setUniform("daylight", state.daylight());
            setBodies(sky, state);
            glBindVertexArray(skyVao);
            glDrawArrays(GL_TRIANGLES, 0, 3);

            if (state.stars() > 0) {
                long seed = context.getWorld().getSeed();
                if (catalogueSeed == null || catalogueSeed != seed) {
                    glBindBuffer(GL_ARRAY_BUFFER, starVbo);
                    glBufferData(GL_ARRAY_BUFFER, starCatalogue(seed), GL_STATIC_DRAW);
                    glBindBuffer(GL_ARRAY_BUFFER, 0);
                    catalogueSeed = seed;
                }
                stars.bind();
                stars.setUniform("rotationProjection", new Matrix4f(context.getCamera().getProjectionMatrix()).mul(rotationView));
                stars.setUniform("visibility", state.stars());
                setBodies(stars, state);
                glEnable(GL_PROGRAM_POINT_SIZE);
                glEnable(GL_BLEND);
                glBlendEquation(GL_FUNC_ADD);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                glBindVertexArray(starVao);
                glDrawArrays(GL_POINTS, 0, STAR_COUNT);
            }
        } finally {
            glBindVertexArray(0);
            glUseProgram(0);
            glDisable(GL_PROGRAM_POINT_SIZE);
            glDisable(GL_BLEND);
            glEnable(GL_DEPTH_TEST);
            glDepthMask(true);
            glDepthFunc(GL_LESS);
            glFrontFace(GL_CCW);
            glCullFace(GL_BACK);
            glEnable(GL_CULL_FACE);
        }
    }

    private static void setBodies(Shader shader, CelestialState state) {
        shader.setUniform("sunDirection", state.sunDirection());
        shader.setUniform("moonDirection", state.moonDirection());
        shader.setUniform("sunCosRadius", (float)Math.cos(Math.toRadians(CelestialState.SUN_DIAMETER_DEGREES / 2)));
        shader.setUniform("moonCosRadius", (float)Math.cos(Math.toRadians(CelestialState.MOON_DIAMETER_DEGREES / 2)));
        shader.setUniform("moonSinRadius", (float)Math.sin(Math.toRadians(CelestialState.MOON_DIAMETER_DEGREES / 2)));
    }

    @Override public void cleanup() {
        if (closed) return;
        closed = true;
        sky.cleanup(); stars.cleanup();
        glDeleteBuffers(starVbo); glDeleteVertexArrays(starVao); glDeleteVertexArrays(skyVao);
        starVbo = starVao = skyVao = 0;
    }
}
