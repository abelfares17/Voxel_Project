package engine.ui.screens;

import java.util.Random;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import static org.lwjgl.opengl.GL11.glDeleteTextures;

import engine.core.Application;
import engine.core.states.LoadingState;
import engine.input.Input;
import engine.render.TextRenderer;
import engine.render.TextureLoader;
import engine.render.UIRenderer;
import engine.ui.Screen;
import engine.ui.UIManager;
import engine.ui.component.Button;
import engine.ui.component.Label;
import engine.ui.component.TextField;
import engine.world.World;
import engine.world.WorldNaming;

/**
 * World creation screen - Create new worlds with custom name and seed.
 */
public class WorldCreationScreen implements Screen {
    private final UIManager uiManager;
    private final Application app;
    private final TextRenderer textRenderer;
    private final TextRenderer titleTextRenderer;
    private final UIRenderer uiRenderer;

    private Label titleLabel;
    private Label nameLabel;
    private TextField nameField;
    private Label seedLabel;
    private TextField seedField;
    private Label modeLabel;
    private Button createButton;
    private Button backButton;
    private int backgroundTexture;

    public WorldCreationScreen(UIManager uiManager, Application app,
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
        int windowHeight = app.getWindowHeight();

        // Title (using high-resolution title font)
        titleLabel = new Label(
            centerX, centerY - 800,
            "Create New World",
            new Vector3f(1, 1, 1),
            1.0f,
            titleTextRenderer
        );

        // World Name section
        nameLabel = new Label(
            centerX, centerY - 600,
            "World Name:",
            new Vector3f(1, 1, 1),
            3f,
            textRenderer
        );

        nameField = new TextField(
            centerX - 300, centerY - 500,
            600, 100,
            "Enter world name...",
            textRenderer, uiRenderer
        );

        // Seed section
        seedLabel = new Label(
            centerX, centerY - 300,
            "Seed (optional):",
            new Vector3f(1, 1, 1),
            1.2f,
            textRenderer
        );

        seedField = new TextField(
            centerX - 300, centerY - 200,
            600, 100,
            "Random seed",
            textRenderer, uiRenderer
        );

        // Mode label (informational only - always PERLIN for Phase 9)
        modeLabel = new Label(
            centerX, centerY - 50,
            "Generation Mode: PERLIN",
            new Vector3f(0.7f, 0.7f, 0.7f),
            1.0f,
            textRenderer
        );

        // Buttons at bottom
        int btnY = windowHeight - 150;

        createButton = new Button(
            centerX + 50, btnY,
            250, 60,
            "Create",
            textRenderer, uiRenderer
        );
        createButton.setOnClick(this::onCreate);

        backButton = new Button(
            centerX - 300, btnY,
            250, 60,
            "Back",
            textRenderer, uiRenderer
        );
        backButton.setOnClick(this::onBack);
    }

    @Override
    public void onShow() {
        System.out.println("Showing World Creation Screen");
        // Reset fields with fresh defaults
        nameField.setText(WorldNaming.generateWorldName());
        long randomSeed = new Random().nextLong();
        seedField.setText(String.valueOf(randomSeed));
    }

    /**
     * Callback for "Create" button - Validate and create world.
     */
    private void onCreate() {
        // Validate world name
        String worldName = nameField.getText().trim();
        if (worldName.isEmpty()) {
            System.err.println("World name empty, using default");
            worldName = WorldNaming.generateWorldName();
            nameField.setText(worldName);
        }

        // Parse seed with fallback
        long seed;
        try {
            seed = Long.parseLong(seedField.getText().trim());
        } catch (NumberFormatException e) {
            System.err.println("Invalid seed, using random");
            seed = new Random().nextLong();
        }

        // Mode is always PERLIN for Phase 9
        World.Mode mode = World.Mode.PERLIN;

        System.out.println("Creating world: " + worldName + " with seed: " + seed);

        // Transition to LoadingState with new world parameters
        app.getStateManager().setState(
            new LoadingState(app, uiManager.getRenderContext(),
                           uiManager.getInput(), worldName, seed, mode)
        );
    }

    /**
     * Callback for "Back" button.
     */
    private void onBack() {
        uiManager.showScreen("world_selection");
    }

    @Override
    public void onHide() {
        System.out.println("Hiding World Creation Screen");
    }

    @Override
    public void update(float deltaTime) {
        titleLabel.update(deltaTime);
        nameLabel.update(deltaTime);
        nameField.update(deltaTime);
        seedLabel.update(deltaTime);
        seedField.update(deltaTime);
        modeLabel.update(deltaTime);
        createButton.update(deltaTime);
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
        titleLabel.render(orthoProjection, windowWidth, windowHeight, scale);
        nameLabel.render(orthoProjection, windowWidth, windowHeight, scale);
        nameField.render(orthoProjection, windowWidth, windowHeight, scale);
        seedLabel.render(orthoProjection, windowWidth, windowHeight, scale);
        seedField.render(orthoProjection, windowWidth, windowHeight, scale);
        modeLabel.render(orthoProjection, windowWidth, windowHeight, scale);
        createButton.render(orthoProjection, windowWidth, windowHeight, scale);
        backButton.render(orthoProjection, windowWidth, windowHeight, scale);
    }

    @Override
    public void handleInput(Input input) {
        nameField.handleInput(input);
        seedField.handleInput(input);
        createButton.handleInput(input);
        backButton.handleInput(input);
    }

    @Override
    public void cleanup() {
        if (backgroundTexture != 0) {
            glDeleteTextures(backgroundTexture);
        }
    }
}
