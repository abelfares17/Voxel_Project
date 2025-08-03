package engine.menu;

import engine.render.TextRenderer;

public class TextField {
    private String text = "";
    private boolean focused = false;
    private long lastBlink = System.currentTimeMillis();
    private boolean showCursor = true;

    /**
     * Affiche le champ texte.
     * @param textRenderer le renderer de texte
     * @param x position X du champ (en pixels)
     * @param y position Y du champ (en pixels)
     * @param w largeur du champ (non utilisée ici pour le texte)
     * @param h hauteur du champ (utilisée pour aligner verticalement)
     */
    public void render(TextRenderer textRenderer, float x, float y, float w, float h) {
        // Calcul du clignotement du curseur
        if (System.currentTimeMillis() - lastBlink > 500) {
            showCursor = !showCursor;
            lastBlink = System.currentTimeMillis();
        }

        // Texte à afficher (+ curseur si focus)
        String display = text + (focused && showCursor ? "|" : "");

        // Appel à draw(text, x, y, scale)
        textRenderer.draw(display, x + 5f, y + h / 2f, 2.0f);
    }

    /**
     * Gère le focus lors d'un clic.
     */
    public void handleClick(float mx, float my, float boxX, float boxY, float boxW, float boxH) {
        focused = mx >= boxX && mx <= boxX + boxW
                && my >= boxY && my <= boxY + boxH;
    }

    /**
     * Gère les touches spéciales (Backspace).
     */
    public void handleKey(int key, int action, int mods) {
        if (!focused || action != 1) return;
        if (key == 259 && text.length() > 0) { // GLFW_KEY_BACKSPACE
            text = text.substring(0, text.length() - 1);
        }
    }

    /**
     * Gère les caractères saisis.
     */
    public void handleChar(char c) {
        if (focused && (Character.isLetterOrDigit(c) || Character.isSpaceChar(c))) {
            text += c;
        }
    }

    public String getText() {
        return text.trim().isEmpty() ? "New World" : text.trim();
    }

   public void setText(String value) {
        this.text = (value != null) ? value : "";
        // Remise à jour du curseur pour qu'il s'affiche immédiatement
        this.showCursor = true;
        this.lastBlink = System.currentTimeMillis();
    }
}
