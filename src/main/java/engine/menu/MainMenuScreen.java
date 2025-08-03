package engine.menu;

import engine.Main;
import engine.input.Input;
import engine.render.TextRenderer;
import engine.render.Button;
import engine.render.UIScreenScaler;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Écran principal : Play, Select World, Quit
 */
public class MainMenuScreen implements MenuScreen {
    private final Main main;
    private final UIScreenScaler scaler;
    private final Input input;
    private final MenuManager manager;

    public MainMenuScreen(Main main, Input input, UIScreenScaler scaler, MenuManager manager) {
        this.main  = main;
        this.scaler = scaler;
        this.input = input;
        this.manager = manager;

    }

    @Override
    public void render(TextRenderer text) {
        float s = 2f;

        // 2) coordonnées “points” pour centrer les éléments
        float ux = scaler.getCenterX();
        float uy = scaler.getCenterY();

        // Boutons
        Button btn = this.manager.getUIButton();
        float BtnW = 400f;
        float BtnH = 100f;

        // 4) Titre centré
        text.drawCentered("VOXEL ENGINE",
                ux, 300f, s);

        // 5) Boutons : en points, juste offseté par 50 points
        btn.draw(
                ux - BtnW/2f,
                600f,
                BtnW, BtnH,
                "Play", s,
                () -> this.manager.setScreen(
                        new WorldSelectionScreen(this.main, this.input, this.scaler, this.manager))
        );

        btn.draw(
                ux - BtnW/2f,
                900f,
                BtnW, BtnH,
                "Quit", s,
                () -> glfwSetWindowShouldClose(main.getWindow(), true)
        );
    }

    @Override
    public void handleClick(float x, float y) {
        // on délègue au drawButton qui gère input.isMousePressed()
        // ici rien de plus à faire
    }

    @Override
    public void handleKey(int key, int action) {
        // Échap pour quitter ou revenir en arrière
        if (key == GLFW_KEY_ESCAPE && action == 1) {
            glfwSetWindowShouldClose(main.getWindow(), true);
        }
    }

    @Override
    public void handleKeyTyped(char c) {
        // non utilisé ici
    }

    @Override
    public boolean blocksGame() {
        return true;
    }
}