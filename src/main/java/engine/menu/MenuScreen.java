package engine.menu;

import engine.render.TextRenderer;
import org.lwjgl.glfw.GLFW;

/**
 * Interface de base pour tous les écrans de menu.
 */
public interface MenuScreen {
    /**
     * Appelé pour rendre l’écran avec mise à l’échelle High-DPI.
     */
    void render(TextRenderer textRenderer);

    /**
     * Appelé lorsqu’un clic gauche est détecté (coordonnées déjà corrigées pour High-DPI).
     */
    void handleClick(float x, float y);

    /**
     * Appelé lorsqu’il faut réagir à une touche.
     */
    default void handleKey(int key, int action) {}

    /**
     * Retourne true si ce menu masque le jeu (ex : écran de chargement = true).
     */
    boolean blocksGame();

    void handleKeyTyped(char c);
}