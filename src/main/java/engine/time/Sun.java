package engine.time;

import engine.core.config.GameConfig;
import engine.render.Shader;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

public class Sun {
    private final int vao, vbo;
    private final Shader shader;
    private static final float SIZE = 50.0f; // Taille du soleil
    private static final float DIST = 200.0f; // Distance à partir du joueur

    public Sun() {
        // Quad centré en (0,0,0) dans le plan XY
        float[] verts = {
                -0.5f, -0.5f, 0f,
                0.5f, -0.5f, 0f,
                0.5f,  0.5f, 0f,
                -0.5f,  0.5f, 0f
        };
        vao = glGenVertexArrays();
        vbo = glGenBuffers();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, verts, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0); // aPos
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
        glBindVertexArray(0);

        // Shader (utilise ta classe Shader utilitaire)
        shader = new Shader(
                GameConfig.SUN_VERTEX_SHADER,
                GameConfig.SUN_FRAGMENT_SHADER
        );
    }

    /**
     * Rendu du soleil, avec effet billboard
     * @param playerPos Position du joueur (caméra)
     * @param sunDir    Direction du soleil (déjà normalisée)
     * @param view      Matrice vue de la caméra
     * @param proj      Matrice de projection
     * @param color     Couleur du soleil (ex : jaune/blanc)
     */
    public void render(Vector3f playerPos, Vector3f sunDir, Matrix4f view, Matrix4f proj, Vector3f color) {
        // Calcul position soleil dans le ciel, très loin
        Vector3f sunPos = new Vector3f(
                playerPos.x + sunDir.x * DIST,
                playerPos.y + sunDir.y * DIST,
                playerPos.z + sunDir.z * DIST
        );

        // Billboard : translation puis alignement sur plan caméra
        Matrix4f model = new Matrix4f().translation(sunPos);

        // On récupère la rotation de la caméra et on la "dé-bascule" pour que le soleil soit toujours face caméra
        Matrix4f billboard = new Matrix4f(view).invert();
        billboard.m30(0).m31(0).m32(0); // enlève la translation

        model.mul(billboard);
        model.scale(SIZE);

        shader.bind();
        shader.setUniform("modelMatrix", model);
        shader.setUniform("viewMatrix", view);
        shader.setUniform("projectionMatrix", proj);
        shader.setUniform("uColor", color);

        glBindVertexArray(vao);
        glDisable(GL_DEPTH_TEST); // le soleil est "infini", évite qu'il soit caché par les blocs
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glBindVertexArray(0);

        shader.unbind();
    }

    public void cleanup() {
        shader.cleanup();
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
    }
}