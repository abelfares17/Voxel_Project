package engine.render;

import engine.input.Input;

import static org.lwjgl.opengl.GL11.*;

/**
 * Petite classe utilitaire pour dessiner des boutons rectangulaires
 * avec effet hover et détection de clic.
 */
public class Button {
    private final TextRenderer textRenderer;
    private final Input input;

    public Button(TextRenderer textRenderer, Input input) {
        this.textRenderer = textRenderer;
        this.input        = input;
    }

    /**
     * Dessine un bouton, gère l’effet hover + clic, et centre le texte.
     *
     * @param x       coin haut-gauche X en pixels
     * @param y       coin haut-gauche Y en pixels
     * @param w       largeur en pixels
     * @param h       hauteur en pixels
     * @param label   texte du bouton
     * @param action  callback exécuté sur edge-click
     */
    public void draw(float x, float y, float w, float h,
                     String label, float scale, Runnable action) {
        // Récupère la souris en PIXELS
        double mx = input.getMouseX(), my = input.getMouseY();

        // 1) Hover
        boolean hovered = (mx >= x && mx <= x + w
                && my >= y && my <= y + h);

        // 2) Fond du bouton
        if (hovered) {
            glColor4f(0.3f, 0.7f, 1.0f, 1.0f);
        } else {
            glColor4f(0.2f, 0.6f, 0.9f, 1.0f);
        }
        glBegin(GL_QUADS);
        glVertex2f(x,     y);
        glVertex2f(x + w, y);
        glVertex2f(x + w, y + h);
        glVertex2f(x,     y + h);
        glEnd();

        // 3) Texte centré
        float textW = textRenderer.getTextWidth(label, scale);
        float textH = textRenderer.getFontHeight();
        float textX = x + (w - textW) / 2f;
        float textY = y + (h - textH);
        glColor3f(1f, 1f, 1f);
        textRenderer.draw(label, textX, textY, scale);

        // 4) Edge-click
        if (hovered && input.isMouseClicked(0)) {
            action.run();
        }
    }
}