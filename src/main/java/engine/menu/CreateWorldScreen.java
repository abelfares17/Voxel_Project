package engine.menu;

import static org.lwjgl.glfw.GLFW.*;

import engine.Main;
import engine.input.Input;
import engine.render.TextRenderer;
import engine.render.UIScreenScaler;
import engine.world.World;
import engine.render.Button;
import static org.lwjgl.opengl.GL11.*;

import java.util.Random;

public class CreateWorldScreen implements MenuScreen {
    private final Main main;
    private final MenuManager manager;
    private final Input input;
    private final UIScreenScaler scaler;
    private final TextField nameField;
    private final long seed;

    public CreateWorldScreen(Main main, Input input, UIScreenScaler scaler, MenuManager manager) {
        this.main      = main;
        this.manager = manager;
        this.input     = input;
        this.scaler    = scaler;
        this.seed      = new Random().nextLong();
        this.nameField = new TextField();
        this.nameField.setText("New World");
    }

    @Override
    public void render(TextRenderer text) {
        float s      = 2f;
        float cx     = scaler.getCenterX();
        float topY   = 100f;
        float fieldY = 300f;
        float bottom = scaler.getHeight() - 40f;
        Button btn   = manager.getUIButton();

        // 1) Texte saisi
        for (char c : input.consumeChars()) {
            nameField.handleChar(c);
        }
        if (input.isKeyPressed(GLFW_KEY_BACKSPACE)) {
            nameField.handleKey(GLFW_KEY_BACKSPACE, 1, 0);
        }

        // 2) Titre
        text.drawCentered("Create New World",
                cx, topY, s);

        // 3) Label + champ
        float labelX = 200f;
        text.draw("World Name:", labelX, fieldY - 30f, s);
        nameField.render(text,
                labelX, fieldY,
                400f, 100f);

        // Focus du TextField si clic
        if (input.isMouseClicked(0)) {
            float mx = input.getMouseX();
            float my = input.getMouseY();
            nameField.handleClick(mx, my,
                    labelX, fieldY,
                    400f, 100f);
        }

        // 4) Seed
        text.draw("Seed: " + seed,
                labelX, fieldY + 120f, s);

        // 5) Boutons
        float btnW = 400f, btnH = 100f;

        // Create
        btn.draw(
                cx - btnW/2f + 300,
                bottom - 400f,
                btnW, btnH,
                "Create",
                s,
                () -> {
                    String folder = nameField.getText()
                            .replace(' ', '_');
                    World w = new World(seed);
                    main.loadWorld(w);
                    manager.setScreen(new LoadingScreen(main, input, scaler));
                }
        );

        // Back
        btn.draw(
                cx - btnW/2f - 300,
                bottom - 400f,
                btnW, btnH,
                "Back",
                s,
                () -> manager.setScreen(
                        new WorldSelectionScreen(
                                main, input, scaler, manager))
        );
    }

    @Override public void handleClick(float x, float y) { /* plus d’override nécessaire */ }
    @Override public void handleKey(int key, int action)     { /* non utilisé */ }
    @Override public void handleKeyTyped(char c)             { /* non utilisé */ }
    @Override public boolean blocksGame()                    { return true; }

    // ------------------------------------------------------------------------
    // Méthode générique pour un bouton CENTRÉ (texte + détection de clic)
    private void drawButton(TextRenderer text,
                            String label,
                            float x, float y,
                            float scale,
                            Runnable action) {
        // 1) Dessin centré
        text.drawCenteredWithBox(label, x, y, scale);

        // 2) Si clic et souris dans la zone du label, on déclenche action
        if (input.isMouseClicked(0)) {
            float mx = input.getMouseX();
            float my = input.getMouseY();
            float halfW = text.getTextWidth(label, scale) * scale * 0.5f;
            float lineH = text.getFontHeight() * scale * 0.5f;
            if (mx >= x - halfW && mx <= x + halfW
                    && my >= y - lineH && my <= y + lineH) {
                action.run();
            }
        }
    }

    // ------------------------------------------------------------------------
    // Nouvelle méthode : zone rectangulaire NON centrée, gère juste le clic
    private void drawButtonRegion(float rx, float ry,
                                  float rw, float rh,
                                  Runnable action) {
        glBegin(GL_LINE_LOOP);
        glVertex2f(rx,        ry);
        glVertex2f(rx+rw,     ry);
        glVertex2f(rx+rw,     ry+rh);
        glVertex2f(rx,        ry+rh);
        glEnd();
        if (input.isMouseClicked(0)) {
            float mx = input.getMouseX();
            float my = input.getMouseY();
            if (mx >= rx && mx <= rx + rw
                    && my >= ry && my <= ry + rh) {
                action.run();
            }
        }
    }
}
