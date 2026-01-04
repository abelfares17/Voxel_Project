package engine.render.renderer;

import engine.core.config.GameConfig;
import engine.core.config.RenderConfig;
import engine.render.Shader;
import engine.render.camera.PerspectiveCamera;
import engine.render.core.RenderContext;
import engine.time.SunData;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Renderer responsible for drawing the sun disc in the sky.
 * Uses billboard technique to keep sun facing the camera.
 */
public class SunRenderer implements Renderer {

    private final int vao, vbo;
    private final Shader shader;
    private static final float SIZE = 50.0f;  // Sun size
    private static final float DIST = 200.0f; // Distance from player

    /**
     * Create a new sun renderer.
     */
    public SunRenderer() {
        // Quad centered at (0,0,0) in XY plane
        float[] verts = {
                -0.5f, -0.5f, 0f,
                0.5f, -0.5f, 0f,
                0.5f, 0.5f, 0f,
                -0.5f, 0.5f, 0f
        };

        vao = glGenVertexArrays();
        vbo = glGenBuffers();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, verts, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0); // aPos
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
        glBindVertexArray(0);

        // Load sun shader
        shader = new Shader(
                GameConfig.SUN_VERTEX_SHADER,
                GameConfig.SUN_FRAGMENT_SHADER
        );
    }

    @Override
    public void render(RenderContext context) {
        // Get sun data from time manager
        SunData sunData = context.getWorld().getTimeManager().getSunData();
        float sunY = sunData.sunY();
        Vector3f sunDir = sunData.sunDir();

        // Calculate sun color with fade
        Vector3f sunColor = RenderConfig.getSunColor(sunY);

        // Get camera position
        Vector3f cameraPos = context.getCamera().getPosition();

        // Calculate sun position in sky (very far away)
        Vector3f sunPos = new Vector3f(
                cameraPos.x + sunDir.x * DIST,
                cameraPos.y + sunDir.y * DIST,
                cameraPos.z + sunDir.z * DIST
        );

        // Build model matrix: translation + billboard
        Matrix4f model = new Matrix4f().translation(sunPos);

        // Get view matrix with player's rotation
        Matrix4f view;
        if (context.getCamera() instanceof PerspectiveCamera perspectiveCamera) {
            view = perspectiveCamera.getViewMatrix(); // Uses player's yaw and pitch
        } else {
            view = context.getCamera().getViewMatrix(cameraPos, 0, 0);
        }

        // Billboard: align quad to camera plane
        Matrix4f billboard = new Matrix4f(view).invert();
        billboard.m30(0).m31(0).m32(0); // Remove translation

        model.mul(billboard);
        model.scale(SIZE);

        // Set shader uniforms
        shader.bind();
        shader.setUniform("modelMatrix", model);
        shader.setUniform("viewMatrix", view);
        shader.setUniform("projectionMatrix", context.getCamera().getProjectionMatrix());
        shader.setUniform("uColor", sunColor);

        // Render sun quad
        glBindVertexArray(vao);
        glDisable(GL_DEPTH_TEST); // Sun is "infinite", shouldn't be occluded
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glBindVertexArray(0);

        shader.unbind();
    }

    @Override
    public void cleanup() {
        shader.cleanup();
        if (vbo != 0) {
            glDeleteBuffers(vbo);
        }
        if (vao != 0) {
            glDeleteVertexArrays(vao);
        }
    }
}
