package engine.menu;

import engine.Main;
import engine.input.Input;
import engine.render.Button;
import engine.render.TextRenderer;
import engine.render.UIScreenScaler;

/**
 * Gère l'écran de menu actif (main menu, loading, etc.)
 * et route les clics, touches et entrées clavier vers le bon écran.
 */
public class MenuManager {
    private MenuScreen current;
    private final Main main;
    private final Input input;
    private final UIScreenScaler scaler;
    private final Button uiButton;
    private final TextRenderer textRenderer;

    public MenuManager(Main main, Input input, UIScreenScaler scaler, TextRenderer textRenderer) {
        this.main = main;
        this.input = input;
        this.scaler = scaler;
        this.textRenderer = textRenderer;
        this.uiButton = new Button(textRenderer, input);
        this.current = new MainMenuScreen(main, input, scaler, this); // Correction ici aussi
    }

    public void setScreen(MenuScreen screen) {
        this.current = screen;
    }

    // expose uiButton pour les écrans :
    public Button getUIButton() {
        return uiButton;
    }

    public void render() {
        if (current != null) {
            current.render(this.textRenderer);
        }

        // Gérer les clics de souris ici (plus dans Main)
        if (input.isMouseClicked(0)) {
            float x = scaler.scaleX(input.getMouseX());
            float y = scaler.scaleY(input.getMouseY());
            current.handleClick(x, y);
        }
    }

    public void handleKey(int key, int action) {
        if (current != null) {
            current.handleKey(key, action);
        }
    }

    public void handleChar(char c) {
        if (current != null) {
            current.handleKeyTyped(c);
        }
    }

    public boolean isBlocking() {
        return current != null && current.blocksGame();
    }

    public Main getMain() {
        return main;
    }

    public Input getInput() {
        return input;
    }

    public MenuScreen getActiveScreen() {
        return current;
    }
}