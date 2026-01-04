package engine.render;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import static org.lwjgl.opengl.GL30.*;

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
        // Create shader for UI rendering
        this.shader = new Shader("shaders/ui_vertex.glsl", "shaders/ui_fragment.glsl");
        initQuad();
    }

    private void initQuad() {
        // Create VAO and VBO
        vao = glGenVertexArrays();
        vbo = glGenBuffers();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        // Allocate space for quad vertices (6 vertices * 4 floats per vertex = 24 floats)
        // Format: x, y, u, v (position + texcoord)
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
     * @param x X position (top-left)
     * @param y Y position (top-left)
     * @param width Rectangle width
     * @param height Rectangle height
     * @param color Rectangle color (RGBA)
     * @param projection Orthographic projection matrix
     */
    public void drawRect(float x, float y, float width, float height, Vector4f color, Matrix4f projection) {
        // Define quad vertices with dummy texture coordinates (not used in color mode)
        quadVertices = new float[]{
            // Triangle 1
            x, y, 0.0f, 0.0f,                         // Top-left
            x + width, y, 0.0f, 0.0f,                 // Top-right
            x + width, y + height, 0.0f, 0.0f,        // Bottom-right

            // Triangle 2
            x + width, y + height, 0.0f, 0.0f,        // Bottom-right
            x, y + height, 0.0f, 0.0f,                // Bottom-left
            x, y, 0.0f, 0.0f                          // Top-left
        };

        // Bind VAO first, then upload vertex data
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, quadVertices);

        // Setup shader and uniforms
        shader.bind();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uColor", color);
        shader.setUniform("uUseTexture", 0); // Color mode

        // Enable blending for transparency
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        // Draw
        glDrawArrays(GL_TRIANGLES, 0, 6);

        // Cleanup
        glDisable(GL_BLEND);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        shader.unbind();
    }

    /**
     * Draw a textured rectangle (e.g., background image).
     * @param x X position (top-left)
     * @param y Y position (top-left)
     * @param width Rectangle width
     * @param height Rectangle height
     * @param textureId OpenGL texture ID
     * @param tintColor Tint color (use white for no tint)
     * @param projection Orthographic projection matrix
     */
    public void drawTexture(float x, float y, float width, float height,
                           int textureId, Vector4f tintColor, Matrix4f projection) {
        // Define quad vertices with texture coordinates
        // Note: V coordinate is flipped (1.0 at top, 0.0 at bottom) to match PNG image origin
        quadVertices = new float[]{
            // Triangle 1 (counterclockwise winding)
            x, y, 0.0f, 1.0f,                       // Top-left (V=1.0)
            x + width, y, 1.0f, 1.0f,               // Top-right (V=1.0)
            x + width, y + height, 1.0f, 0.0f,      // Bottom-right (V=0.0)

            // Triangle 2 (counterclockwise winding)
            x + width, y + height, 1.0f, 0.0f,      // Bottom-right (V=0.0)
            x, y + height, 0.0f, 0.0f,              // Bottom-left (V=0.0)
            x, y, 0.0f, 1.0f                        // Top-left (V=1.0)
        };

        // Bind VAO first, then upload vertex data
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, quadVertices);

        // Bind texture
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureId);

        // Setup shader and uniforms
        shader.bind();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uColor", tintColor);
        shader.setUniform("uTexture", 0);
        shader.setUniform("uUseTexture", 1); // Texture mode

        // Enable blending for transparent textures
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        // Draw
        glDrawArrays(GL_TRIANGLES, 0, 6);

        // Cleanup
        glDisable(GL_BLEND);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindTexture(GL_TEXTURE_2D, 0);
        shader.unbind();
    }

    /**
     * Draw a rectangle with an outline/border.
     * @param x X position (top-left)
     * @param y Y position (top-left)
     * @param width Rectangle width
     * @param height Rectangle height
     * @param fillColor Fill color (RGBA) - if alpha is 0, no fill is drawn
     * @param borderColor Border color (RGBA)
     * @param borderWidth Border thickness in pixels
     * @param projection Orthographic projection matrix
     */
    public void drawRectWithBorder(float x, float y, float width, float height,
                                   Vector4f fillColor, Vector4f borderColor,
                                   float borderWidth, Matrix4f projection) {
        // Draw fill first (if it has alpha > 0)
        if (fillColor.w > 0.0f) {
            drawRect(x, y, width, height, fillColor, projection);
        }

        // Draw border outline on top
        drawBorder(x, y, width, height, borderColor, borderWidth, projection);
    }

    /**
     * Draw only a border outline (no fill).
     * @param x X position (top-left)
     * @param y Y position (top-left)
     * @param width Rectangle width
     * @param height Rectangle height
     * @param borderColor Border color (RGBA)
     * @param borderWidth Border thickness in pixels
     * @param projection Orthographic projection matrix
     */
    public void drawBorder(float x, float y, float width, float height,
                          Vector4f borderColor, float borderWidth, Matrix4f projection) {
        // Top border
        drawRect(x - borderWidth, y - borderWidth, width + 2 * borderWidth, borderWidth, borderColor, projection);
        // Bottom border
        drawRect(x - borderWidth, y + height, width + 2 * borderWidth, borderWidth, borderColor, projection);
        // Left border
        drawRect(x - borderWidth, y, borderWidth, height, borderColor, projection);
        // Right border
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
