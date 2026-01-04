package engine.ui.screens;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import engine.core.Application;
import engine.input.Input;
import engine.render.TextRenderer;
import engine.render.TextureLoader;
import engine.render.UIRenderer;
import engine.ui.Screen;
import engine.ui.UIManager;
import engine.ui.component.Button;
import engine.ui.component.Label;

import static org.lwjgl.opengl.GL11.glDeleteTextures;

/**
 * Settings screen (stub implementation).
 * TODO: Add actual settings controls in future iterations.
 */
public class SettingsScreen implements Screen {
    private final UIManager uiManager;
    private final Application app;
    private final TextRenderer textRenderer;
    private final TextRenderer titleTextRenderer;
    private final UIRenderer uiRenderer;

    private Label titleLabel;
    private Button backButton;
    private int backgroundTexture;

    public SettingsScreen(UIManager uiManager, Application app,
                         TextRenderer textRenderer, TextRenderer titleTextRenderer, UIRenderer uiRenderer) {
        this.uiManager = uiManager;
        this.app = app;
        this.textRenderer = textRenderer;
        this.titleTextRenderer = titleTextRenderer;
        this.uiRenderer = uiRenderer;

        // Load background texture (using shared dirt background for now)
        backgroundTexture = TextureLoader.loadTextureFromClasspath("textures/backgrounds/dirt_background.png");

        initComponents();
    }

    private void initComponents() {
        int centerX = app.getWindowWidth() / 2;
        int centerY = app.getWindowHeight() / 2;

        // Title (using high-resolution title font)
        titleLabel = new Label(
            centerX, centerY - 150,
            "Settings",
            new Vector3f(1, 1, 1),
            1.0f,
            titleTextRenderer
        );

        // Back button
        backButton = new Button(
            centerX - 100, centerY + 100,
            200, 50,
            "Back",
            textRenderer, uiRenderer
        );
        backButton.setOnClick(() -> uiManager.showScreen("main_menu"));
    }

    @Override
    public void onShow() {
        System.out.println("Showing Settings");
    }

    @Override
    public void onHide() {
        System.out.println("Hiding Settings");
    }

    @Override
    public void update(float deltaTime) {
        titleLabel.update(deltaTime);
        backButton.update(deltaTime);
    }

    @Override
    public void render(Matrix4f orthoProjection, int windowWidth, int windowHeight) {
        // Draw background first
        uiRenderer.drawTexture(0, 0, windowWidth, windowHeight,
                              backgroundTexture,
                              new Vector4f(1, 1, 1, 1), // White tint = no color modification
                              orthoProjection);

        // Draw UI components on top
        float scale = app.getScaler().getScale()/2;
        titleLabel.render(orthoProjection, windowWidth, windowHeight, 2);
        backButton.render(orthoProjection, windowWidth, windowHeight, scale);
    }

    @Override
    public void handleInput(Input input) {
        backButton.handleInput(input);
    }

    @Override
    public void onResize(int newWidth, int newHeight) {
        // Recalculate positions based on new dimensions
        int centerX = newWidth / 2;
        int centerY = newHeight / 2;

        // Update title position
        titleLabel.setPosition(centerX, centerY - 150);

        // Update button positions
        backButton.setPosition(centerX -100, centerY + 100);  // Back
    }

    @Override
    public void cleanup() {
        if (backgroundTexture != 0) {
            glDeleteTextures(backgroundTexture);
        }
    }
}
