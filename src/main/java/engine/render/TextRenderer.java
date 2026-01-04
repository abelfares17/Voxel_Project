package engine.render;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_RED;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;
import org.lwjgl.stb.STBTTAlignedQuad;
import org.lwjgl.stb.STBTTBakedChar;
import static org.lwjgl.stb.STBTruetype.stbtt_BakeFontBitmap;
import static org.lwjgl.stb.STBTruetype.stbtt_GetBakedQuad;
import org.lwjgl.system.MemoryStack;

import engine.core.config.GameConfig;

public class TextRenderer {
    private static final int BITMAP_W = 512, BITMAP_H = 512, FIRST_CHAR = 32, NUM_CHARS = 96;
    private final int vao, vbo, fontHeight, textureId;
    private final STBTTBakedChar.Buffer charData;
    private final Shader shader;

    public TextRenderer(String fontResourcePath, int fontHeight) {
        this.fontHeight = fontHeight;
        // Charger police TTF
        ByteBuffer fontBuffer;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(fontResourcePath)) {
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
        shader = new Shader(GameConfig.TEXT_VERTEX_SHADER, GameConfig.TEXT_FRAGMENT_SHADER);
    }

    /**
     * Dessine une ligne de texte (x,y en pixels, y depuis haut de l'écran).
     * @param text Texte à afficher
     * @param x    Abscisse en pixels (gauche)
     * @param y    Ordonnée en pixels (depuis haut)
     * @param color Couleur (vec3)
     * @param ortho Matrice ortho2D (largeur, hauteur fenêtre)
     * @param windowHeight Hauteur fenêtre (pixels) - NOTE: Currently unused as ortho handles coordinate system
     */
    public void drawText(String text, float x, float y, Vector3f color, Matrix4f ortho, int windowHeight, float scale) {
        // Préparer les quads pour tout le texte
        FloatBuffer verts = BufferUtils.createFloatBuffer(6 * 4 * text.length());
        // Use Y coordinate directly - ortho projection already handles top-origin coordinates
        // Start cursor at (0, 0) to get relative positions, then add base position after scaling
        float cursorX = 0, cursorY = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer px = stack.floats(cursorX), py = stack.floats(cursorY);
            STBTTAlignedQuad quad = STBTTAlignedQuad.mallocStack(stack);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                int ci = c - FIRST_CHAR;
                if (ci < 0 || ci >= NUM_CHARS) continue;
                stbtt_GetBakedQuad(charData, BITMAP_W, BITMAP_H, ci, px, py, quad, true);
                // Scale the quad dimensions, then add base position
                float x0 = x + quad.x0() * scale, y0 = y + quad.y0() * scale;
                float x1 = x + quad.x1() * scale, y1 = y + quad.y1() * scale;
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

    // ===== CONVENIENCE METHODS FOR MENU COMPATIBILITY =====

    /**
     * Draw text with default white color.
     * Convenience method for menu rendering.
     *
     * @param text  Text to render
     * @param x     X position in pixels
     * @param y     Y position in pixels
     * @param scale Text scale multiplier
     */
    public void draw(String text, float x, float y, float scale, Vector3f color, int windowWidth, int windowHeight) {
        Matrix4f ortho = new Matrix4f().ortho2D(0, windowWidth, windowHeight, 0);
        drawText(text, x, y, color, ortho, windowHeight, scale);
    }

    /**
     * Draw centered text.
     * Convenience method for menu rendering.
     *
     * @param text         Text to render
     * @param centerX      X center position
     * @param centerY      Y center position
     * @param scale        Text scale multiplier
     * @param windowWidth  Window width for ortho projection
     * @param windowHeight Window height for ortho projection
     */
    public void drawCentered(String text, float centerX, float centerY, float scale, Vector3f color, int windowWidth, int windowHeight) {
        float width = getTextWidth(text, scale);
        float x = centerX - width / 2;
        draw(text, x, centerY, scale, color, windowWidth, windowHeight);
    }

    /**
     * Get text width with scale applied.
     *
     * @param text  Text to measure
     * @param scale Scale multiplier
     * @return Scaled width in pixels
     */
    public float getTextWidth(String text, float scale) {
        return getTextWidth(text) * scale;
    }

    /**
     * Get font height.
     *
     * @return Font height in pixels
     */
    public float getFontHeight() {
        return fontHeight;
    }
}