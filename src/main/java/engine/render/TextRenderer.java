package engine.render;

import org.lwjgl.BufferUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.stb.STBTruetype.*;

import org.lwjgl.stb.STBTTAlignedQuad;
import org.lwjgl.stb.STBTTBakedChar;
import org.lwjgl.system.MemoryStack;

public class TextRenderer {

    private static final int BITMAP_W = 512;
    private static final int BITMAP_H = 512;
    private static final int FIRST_CHAR = 32;
    private static final int NUM_CHARS = 96;

    private final int fontHeight;
    private final int textureId;
    private final STBTTBakedChar.Buffer charData;

    public TextRenderer() {
        this("/fonts/Minecraftia.ttf", 24); // font par défaut incluse dans le projet
    }

    public TextRenderer(String fontResourcePath, int fontHeight) {
        this.fontHeight = fontHeight;

        ByteBuffer fontBuffer = null;
        try (InputStream is = TextRenderer.class.getResourceAsStream(fontResourcePath)) {
            if (is == null) throw new IOException("Font not found: " + fontResourcePath);
            byte[] data = is.readAllBytes();
            fontBuffer = BufferUtils.createByteBuffer(data.length);
            fontBuffer.put(data).flip();
        } catch (IOException e) {
            throw new RuntimeException("Erreur chargement font: " + e.getMessage());
        }

        ByteBuffer bitmap = BufferUtils.createByteBuffer(BITMAP_W * BITMAP_H);
        charData = STBTTBakedChar.malloc(NUM_CHARS);
        stbtt_BakeFontBitmap(fontBuffer, fontHeight, bitmap, BITMAP_W, BITMAP_H, FIRST_CHAR, charData);

        textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_ALPHA, 512, 512, 0, GL_ALPHA, GL_UNSIGNED_BYTE, bitmap);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    }

    public void draw(String text, float startX, float startY, float scale) {
        glEnable(GL_TEXTURE_2D);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glColor3f(1f, 1f, 1f); // Texte en blanc

        // 1) On déplace l'origine de dessin au point startX,startY
        //    puis on applique le scale pour "zoomer" autour de ce point.
        glPushMatrix();
        glTranslatef(startX, startY, 0f);
        glScalef(scale, scale, 1f);

        // 2) On dessine le texte à partir de (0,0) dans ce nouveau repère
        glBegin(GL_QUADS);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer xBuf = stack.floats(0f);
            FloatBuffer yBuf = stack.floats(0f);
            STBTTAlignedQuad quad = STBTTAlignedQuad.mallocStack(stack);

            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                int ci = c - FIRST_CHAR;
                if (ci < 0 || ci >= charData.capacity()) continue;

                // Remplit quad & avance xBuf/yBuf (dans le repère zoomé)
                stbtt_GetBakedQuad(charData, BITMAP_W, BITMAP_H,
                        ci, xBuf, yBuf, quad, true);

                float x0 = quad.x0(), y0 = quad.y0();
                float x1 = quad.x1(), y1 = quad.y1();
                float u0 = quad.s0(), v0 = quad.t0();
                float u1 = quad.s1(), v1 = quad.t1();

                glTexCoord2f(u0, v0); glVertex2f(x0, y0);
                glTexCoord2f(u1, v0); glVertex2f(x1, y0);
                glTexCoord2f(u1, v1); glVertex2f(x1, y1);
                glTexCoord2f(u0, v1); glVertex2f(x0, y1);
            }
        }
        glEnd();

        // 3) On restaure la matrice précédente
        glPopMatrix();
        glDisable(GL_TEXTURE_2D);
    }

    /**
     * Dessine du texte et trace un rectangle de debug autour de sa hit-box.
     *
     * @param text  la chaîne à afficher
     * @param x     position X en pixels (coin bas-gauche du texte)
     * @param y     position Y en pixels (coin bas-gauche du texte)
     * @param scale facteur d’échelle
     */


    public void drawCenteredWithBox(String text, float x, float y, float scale) {
        drawWithBox(text, x, y, scale);
    }

    /**
     * Dessine du texte centré en (centerX, centerY) **et** encadre sa hit-box.
     *
     * @param text    la chaîne à afficher
     * @param centerX abscisse du centre en pixels
     * @param centerY ordonnée du centre en pixels
     * @param scale   facteur d’échelle
     */
    public void drawWithBox(String text,
                                    float centerX, float centerY,
                                    float scale) {
        // 1) Largeur "logique" et hauteur en pixels
        float width  = getTextWidth(text, scale);       // largeur en *pixels* (xadvance*scale)
        float height = getFontHeight() * scale;         // hauteur en pixels

        // 2) coin bas-gauche du texte
        float x = centerX - width / (scale * 2f);
        float y = centerY - height / (scale * 2f);

        // 3) on dessine d’abord le texte
        draw(text, x, y, scale);

        // 4) on trace ensuite le rectangle de debug
        x = scale * x - width;
        y = scale * y - scale * height;
        glDisable(GL_TEXTURE_2D);
        glColor3f(1f, 0f, 0f);
        glBegin(GL_LINE_LOOP);
        glVertex2f(x,         y);
        glVertex2f(x + 3 * width, y);
        glVertex2f(x + 3 * width, y + 3*height);
        glVertex2f(x,         y + 3*height);
        glEnd();
        glColor3f(1f, 1f, 1f);
        glEnable(GL_TEXTURE_2D);
    }



    public void drawCentered(String text, float centerX, float centerY, float scale) {
        float width = getTextWidth(text, scale);
        float height = getFontHeight() * scale;
        float startX = centerX -  width / 2f;
        float startY = centerY - height / 2f;
        draw(text, startX, startY, scale);
    }

    public float getTextWidth(String text, float scale) {
        float width = 0f;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < FIRST_CHAR || c >= FIRST_CHAR + NUM_CHARS) continue;
            STBTTBakedChar glyph = charData.get(c - FIRST_CHAR);
            width += glyph.xadvance() * scale;
        }
        return width;
    }

    public float getFontHeight() {
        return fontHeight;
    }
}