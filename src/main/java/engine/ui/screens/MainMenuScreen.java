package engine.ui.screens;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import engine.core.Application;
import engine.input.Input;
import engine.render.TextRenderer;
import engine.render.TextureLoader;
import engine.render.UIRenderer;
import engine.ui.Screen;
import engine.ui.UIManager;
import engine.ui.component.Button;
import engine.ui.component.Label;
import org.joml.Vector4f;

import static org.lwjgl.opengl.GL11.glDeleteTextures;

/**
 * Main menu screen with play, settings, and quit options.
 */
public class MainMenuScreen implements Screen {
    private final UIManager uiManager;
    private final Application app;
    private final TextRenderer textRenderer;
    private final TextRenderer titleTextRenderer;
    private final UIRenderer uiRenderer;

    private final List<Button> buttons = new ArrayList<>();
    private Label titleLabel;
    private int backgroundTexture;

    public MainMenuScreen(UIManager uiManager, Application app,
                         TextRenderer textRenderer, TextRenderer titleTextRenderer, UIRenderer uiRenderer) {
        this.uiManager = uiManager;
        this.app = app;
        this.textRenderer = textRenderer;
        this.titleTextRenderer = titleTextRenderer;
        this.uiRenderer = uiRenderer;

        // Load background texture (using shared dirt background for now)
        backgroundTexture = TextureLoader.loadTextureFromClasspath("textures/backgrounds/dirt_background.png");
        System.out.println("MainMenuScreen: Loaded background texture ID = " + backgroundTexture);

        initComponents();
    }

    private void initComponents() {
        int centerX = app.getWindowWidth() / 2;
        int centerY = app.getWindowHeight() / 2;

        // Title (using high-resolution title font)
        titleLabel = new Label(
            centerX, centerY - 150,
            "VOXEL GAME",
            new Vector3f(1, 1, 1),
            1.0f,
            titleTextRenderer
        );

        // Play button
        Button playButton = new Button(
            centerX - 100, centerY - 50,
            200, 50,
            "Play",
            textRenderer, uiRenderer
        );
        playButton.setOnClick(() -> {
            System.out.println("Opening world selection...");
            uiManager.showScreen("world_selection");
        });
        buttons.add(playButton);

        // Settings button
        Button settingsButton = new Button(
            centerX - 100, centerY + 20,
            200, 50,
            "Settings",
            textRenderer, uiRenderer
        );
        settingsButton.setOnClick(() -> uiManager.showScreen("settings"));
        buttons.add(settingsButton);

        // Quit button
        Button quitButton = new Button(
            centerX - 100, centerY + 90,
            200, 50,
            "Quit",
            textRenderer, uiRenderer
        );
        quitButton.setOnClick(() -> System.exit(0));
        buttons.add(quitButton);
    }

    @Override
    public void onShow() {
        System.out.println("Showing Main Menu");
    }

    @Override
    public void onHide() {
        System.out.println("Hiding Main Menu");
    }

    @Override
    public void update(float deltaTime) {
        for (Button button : buttons) {
            button.update(deltaTime);
        }
        titleLabel.update(deltaTime);
    }

    @Override
    public void render(Matrix4f orthoProjection, int windowWidth, int windowHeight) {
        // Draw background first
        uiRenderer.drawTexture(0, 0, windowWidth, windowHeight,
                              backgroundTexture,
                              new Vector4f(1, 1, 1, 1),
                              orthoProjection);

        // Draw UI components on top
        float scale = app.getScaler().getScale()/2;
        titleLabel.render(orthoProjection, windowWidth, windowHeight, 2);
        for (Button button : buttons) {
            button.render(orthoProjection, windowWidth, windowHeight, scale);
        }
    }

    @Override
    public void handleInput(Input input) {
        for (Button button : buttons) {
            button.handleInput(input);
        }
    }

    @Override
    public void cleanup() {
        if (backgroundTexture != 0) {
            glDeleteTextures(backgroundTexture);
        }
    }

    @Override
    public void onResize(int newWidth, int newHeight) {
        // Recalculate positions based on new dimensions
        int centerX = newWidth / 2;
        int centerY = newHeight / 2;

        // Update title position
        titleLabel.setPosition(centerX, centerY - 150);

        // Update button positions
        if (buttons.size() >= 1) buttons.get(0).setPosition(centerX - 100, centerY - 50);  // Play
        if (buttons.size() >= 2) buttons.get(1).setPosition(centerX - 100, centerY + 20);  // Settings
        if (buttons.size() >= 3) buttons.get(2).setPosition(centerX - 100, centerY + 90);  // Quit
    }
}
