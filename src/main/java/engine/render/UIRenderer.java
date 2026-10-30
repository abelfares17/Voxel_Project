package engine.render;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glBufferSubData;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;

/**
 * Simple UI renderer for drawing rectangles and UI elements.
 * Uses modern OpenGL with VAO/VBO (no immediate mode).
 */
public class UIRenderer {
    private final Shader shader;
    private int vao, vbo;

    // Vertex data for a quad (6 vertices, 2 triangles)
    private float[] quadVertices;

    public UIRenderer() {
        this.shader = new Shader("shaders/ui_vertex.glsl", "shaders/ui_fragment.glsl");
        initQuad();
    }

    private void initQuad() {
        vao = glGenVertexArrays();
        vbo = glGenBuffers();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        // Allocate space for quad vertices (6 vertices * 4 floats per vertex = 24 floats)
        glBufferData(GL_ARRAY_BUFFER, 24 * Float.BYTES, GL_DYNAMIC_DRAW);

        // Position attribute (location = 0)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 4 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);

        // TexCoord attribute (location = 1)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
        glEnableVertexAttribArray(1);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    /**
     * Draw a filled rectangle.
     */
    public void drawRect(float x, float y, float width, float height, Vector4f color, Matrix4f projection) {
        quadVertices = new float[]{
            x, y, 0.0f, 0.0f,
            x + width, y, 0.0f, 0.0f,
            x + width, y + height, 0.0f, 0.0f,

            x + width, y + height, 0.0f, 0.0f,
            x, y + height, 0.0f, 0.0f,
            x, y, 0.0f, 0.0f
        };

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, quadVertices);

        shader.bind();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uColor", color);
        shader.setUniform("uUseTexture", 0);

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glDrawArrays(GL_TRIANGLES, 0, 6);

        glDisable(GL_BLEND);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        shader.unbind();
    }

    /**
     * Draw a textured rectangle (e.g., background image).
     */
    public void drawTexture(float x, float y, float width, float height,
                           int textureId, Vector4f tintColor, Matrix4f projection) {
        quadVertices = new float[]{
            x, y, 0.0f, 1.0f,
            x + width, y, 1.0f, 1.0f,
            x + width, y + height, 1.0f, 0.0f,

            x + width, y + height, 1.0f, 0.0f,
            x, y + height, 0.0f, 0.0f,
            x, y, 0.0f, 1.0f
        };

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, quadVertices);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureId);

        shader.bind();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uColor", tintColor);
        shader.setUniform("uTexture", 0);
        shader.setUniform("uUseTexture", 1);

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glDrawArrays(GL_TRIANGLES, 0, 6);

        glDisable(GL_BLEND);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindTexture(GL_TEXTURE_2D, 0);
        shader.unbind();
    }

    /**
     * Draw a rectangle with an outline/border.
     */
    public void drawRectWithBorder(float x, float y, float width, float height,
                                   Vector4f fillColor, Vector4f borderColor,
                                   float borderWidth, Matrix4f projection) {
        if (fillColor.w > 0.0f) {
            drawRect(x, y, width, height, fillColor, projection);
        }
        drawBorder(x, y, width, height, borderColor, borderWidth, projection);
    }

    /**
     * Draw only a border outline (no fill).
     */
    public void drawBorder(float x, float y, float width, float height,
                          Vector4f borderColor, float borderWidth, Matrix4f projection) {
        drawRect(x - borderWidth, y - borderWidth, width + 2 * borderWidth, borderWidth, borderColor, projection);
        drawRect(x - borderWidth, y + height, width + 2 * borderWidth, borderWidth, borderColor, projection);
        drawRect(x - borderWidth, y, borderWidth, height, borderColor, projection);
        drawRect(x + width, y, borderWidth, height, borderColor, projection);
    }

    /**
     * Cleanup OpenGL resources.
     */
    public void cleanup() {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        shader.cleanup();
    }
}
