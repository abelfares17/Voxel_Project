package engine.menu;

import engine.Main;
import engine.input.Input;
import engine.render.TextRenderer;
import engine.render.UIScreenScaler;

public class LoadingScreen implements MenuScreen {
    private final Main main;
    private final Input input;
    private final UIScreenScaler scaler;

    public LoadingScreen(Main main, Input input, UIScreenScaler scaler) {
        this.main = main;
        this.input = input;
        this.scaler = scaler;
    }

    @Override
    public void render(TextRenderer text) {
        int centerX = (int) scaler.getCenterX();
        int centerY = (int) scaler.getCenterY();

        text.drawCentered("Chargement du monde...", centerX, centerY, 2f);
    }

    @Override
    public void handleClick(float x, float y) {
        // rien pendant le chargement
    }

    @Override
    public void handleKeyTyped(char character) {}

    @Override
    public boolean blocksGame() {
        return false;
    }
}