package engine.render;

import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBTTAlignedQuad;
import org.lwjgl.stb.STBTTBakedChar;
import org.lwjgl.system.MemoryStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.stb.STBTruetype.*;

public class TextRenderer {
    private static final int BITMAP_W = 512, BITMAP_H = 512, FIRST_CHAR = 32, NUM_CHARS = 96;
    private final int vao, vbo, fontHeight, textureId;
    private final STBTTBakedChar.Buffer charData;
    private final Shader shader;

    public TextRenderer(String fontResourcePath, int fontHeight) {
        this.fontHeight = fontHeight;
        // Charger police TTF
        ByteBuffer fontBuffer;
        try (InputStream is = getClass().getResourceAsStream(fontResourcePath)) {
            if (is == null) throw new IOException("Font not found: " + fontResourcePath);
            byte[] data = is.readAllBytes();
            fontBuffer = BufferUtils.createByteBuffer(data.length);
            fontBuffer.put(data).flip();
        } catch (IOException e) {
            throw new RuntimeException("Erreur chargement font: " + e.getMessage());
        }
        // Générer bitmap
        ByteBuffer bitmap = BufferUtils.createByteBuffer(BITMAP_W * BITMAP_H);
        charData = STBTTBakedChar.malloc(NUM_CHARS);
        stbtt_BakeFontBitmap(fontBuffer, fontHeight, bitmap, BITMAP_W, BITMAP_H, FIRST_CHAR, charData);

        // Générer texture OpenGL (RED channel)
        textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RED, BITMAP_W, BITMAP_H, 0, GL_RED, GL_UNSIGNED_BYTE, bitmap);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glBindTexture(GL_TEXTURE_2D, 0);

        // VAO/VBO
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glEnableVertexAttribArray(0); // aPos
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 4 * Float.BYTES, 0);
        glEnableVertexAttribArray(1); // aUV
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
        glBindVertexArray(0);

        // Shader pipeline moderne
        shader = new Shader("shaders/text_vertex.glsl", "shaders/text_fragment.glsl");
    }

    /**
     * Dessine une ligne de texte (x,y en pixels, y depuis haut de l'écran).
     * @param text Texte à afficher
     * @param x    Abscisse en pixels (gauche)
     * @param y    Ordonnée en pixels (depuis haut)
     * @param color Couleur (vec3)
     * @param ortho Matrice ortho2D (largeur, hauteur fenêtre)
     * @param windowHeight Hauteur fenêtre (pixels)
     */
    public void drawText(String text, float x, float y, Vector3f color, Matrix4f ortho, int windowHeight, float scale) {
        // Préparer les quads pour tout le texte
        FloatBuffer verts = BufferUtils.createFloatBuffer(6 * 4 * text.length());
        float sx = x, sy = windowHeight - y - fontHeight * scale;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer px = stack.floats(sx), py = stack.floats(sy);
            STBTTAlignedQuad quad = STBTTAlignedQuad.mallocStack(stack);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                int ci = c - FIRST_CHAR;
                if (ci < 0 || ci >= NUM_CHARS) continue;
                stbtt_GetBakedQuad(charData, BITMAP_W, BITMAP_H, ci, px, py, quad, true);
                float x0 = quad.x0() * scale, y0 = quad.y0() * scale, x1 = quad.x1() * scale, y1 = quad.y1() * scale;
                float u0 = quad.s0(), v0 = quad.t0(), u1 = quad.s1(), v1 = quad.t1();
                // Deux triangles (6 vertices par caractère)
                verts.put(x0).put(y0).put(u0).put(v0);
                verts.put(x1).put(y0).put(u1).put(v0);
                verts.put(x1).put(y1).put(u1).put(v1);
                verts.put(x0).put(y0).put(u0).put(v0);
                verts.put(x1).put(y1).put(u1).put(v1);
                verts.put(x0).put(y1).put(u0).put(v1);
            }
            verts.flip();
        }
        // Upload VBO + draw
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, verts, GL_DYNAMIC_DRAW);

        shader.bind();
        shader.setUniform("uColor", color);
        shader.setUniform("uProjection", ortho);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureId);
        shader.setUniform("uFontTex", 0);

        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glDrawArrays(GL_TRIANGLES, 0, 6 * text.length());

        glBindVertexArray(0);
        shader.unbind();

        glEnable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
    }

    public void cleanup() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        glDeleteTextures(textureId);
        shader.cleanup();
        charData.free();
    }

    public float getTextWidth(String text) {
        float width = 0f;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int ci = c - FIRST_CHAR;
            if (ci < 0 || ci >= NUM_CHARS) continue;
            STBTTBakedChar glyph = charData.get(ci);
            width += glyph.xadvance();
        }
        return width;
    }
}