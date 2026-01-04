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
import engine.ui.component.Label;

import static org.lwjgl.opengl.GL11.glDeleteTextures;

/**
 * Loading screen displayed during world generation.
 * Shows progress bar and percentage.
 */
public class LoadingScreen implements Screen {
    private final UIManager uiManager;
    private final Application app;
    private final TextRenderer textRenderer;
    private final TextRenderer titleTextRenderer;
    private final UIRenderer uiRenderer;

    private Label titleLabel;
    private Label progressLabel;
    private float progress; // 0.0 to 1.0
    private int backgroundTexture;

    // Progress bar dimensions
    private static final int BAR_WIDTH = 400;
    private static final int BAR_HEIGHT = 30;
    private static final Vector4f BAR_BG_COLOR = new Vector4f(0.2f, 0.2f, 0.2f, 0.8f);
    private static final Vector4f BAR_FG_COLOR = new Vector4f(0.3f, 0.6f, 0.9f, 1.0f);

    public LoadingScreen(UIManager uiManager, Application app,
                        TextRenderer textRenderer, TextRenderer titleTextRenderer, UIRenderer uiRenderer) {
        this.uiManager = uiManager;
        this.app = app;
        this.textRenderer = textRenderer;
        this.titleTextRenderer = titleTextRenderer;
        this.uiRenderer = uiRenderer;
        this.progress = 0.0f;

        // Load background texture (using shared dirt background for now)
        backgroundTexture = TextureLoader.loadTextureFromClasspath("textures/backgrounds/dirt_background.png");

        initComponents();
    }

    private void initComponents() {
        int centerX = app.getWindowWidth() / 2;
        int centerY = app.getWindowHeight() / 2;

        // Title (using high-resolution title font)
        titleLabel = new Label(
            centerX - 100, centerY - 100,
            "Loading World...",
            new Vector3f(1, 1, 1),
            1.0f,
            titleTextRenderer
        );

        // Progress percentage
        progressLabel = new Label(
            centerX - 50, centerY + 50,
            "0%",
            new Vector3f(1, 1, 1),
            1.0f,
            textRenderer
        );
    }

    /**
     * Update progress (0.0 to 1.0).
     */
    public void setProgress(float progress) {
        this.progress = Math.max(0.0f, Math.min(1.0f, progress));

        // Update percentage label
        int percentage = (int) (progress * 100);
        progressLabel.setText(percentage + "%");

        // Recenter the percentage text
        int centerX = app.getWindowWidth() / 2;
        float textWidth = textRenderer.getTextWidth(progressLabel.getText(), 1.0f);
        progressLabel.setPosition(centerX - textWidth / 2, progressLabel.getY());
    }

    @Override
    public void onShow() {
        System.out.println("Showing Loading Screen");
    }

    @Override
    public void onHide() {
        System.out.println("Hiding Loading Screen");
    }

    @Override
    public void update(float deltaTime) {
        titleLabel.update(deltaTime);
        progressLabel.update(deltaTime);
    }

    @Override
    public void render(Matrix4f orthoProjection, int windowWidth, int windowHeight) {
        // Draw background first
        uiRenderer.drawTexture(0, 0, windowWidth, windowHeight,
                              backgroundTexture,
                              new Vector4f(1, 1, 1, 1), // White tint = no color modification
                              orthoProjection);

        // Draw UI components on top
        int centerX = windowWidth / 2;
        int centerY = windowHeight / 2;

        // Render title
        float scale = app.getScaler().getScale()/2;
        titleLabel.render(orthoProjection, windowWidth, windowHeight, scale);

        // Render progress bar background
        int barX = centerX - BAR_WIDTH / 2;
        int barY = centerY - BAR_HEIGHT / 2;
        uiRenderer.drawRect(barX, barY, BAR_WIDTH, BAR_HEIGHT, BAR_BG_COLOR, orthoProjection);

        // Render progress bar foreground (filled portion)
        int filledWidth = (int) (BAR_WIDTH * progress);
        if (filledWidth > 0) {
            uiRenderer.drawRect(barX, barY, filledWidth, BAR_HEIGHT, BAR_FG_COLOR, orthoProjection);
        }

        // Render percentage
        progressLabel.render(orthoProjection, windowWidth, windowHeight, scale);
    }

    @Override
    public void handleInput(Input input) {
        // No input during loading
    }

    @Override
    public void cleanup() {
        if (backgroundTexture != 0) {
            glDeleteTextures(backgroundTexture);
        }
    }
}
