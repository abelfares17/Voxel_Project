package engine.ui.screens;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

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
import engine.ui.component.ScrollableList;
import engine.world.WorldMetadata;
import engine.world.WorldStorage;

/**
 * World selection screen - Browse and load saved worlds.
 */
public class WorldSelectionScreen implements Screen {
    private final UIManager uiManager;
    private final Application app;
    private final TextRenderer textRenderer;
    private final TextRenderer titleTextRenderer;
    private final UIRenderer uiRenderer;

    private Label titleLabel;
    private ScrollableList<WorldEntry> worldList;
    private Label emptyLabel;
    private Button playButton;
    private Button createButton;
    private Button backButton;
    private int backgroundTexture;

    /**
     * Internal class representing a world entry with metadata.
     */
    private static class WorldEntry {
        public final String worldName;
        public final String folderName;
        public final long seed;
        public final String mode;
        public final String lastPlayed;
        public final LocalDateTime lastPlayedDate;

        public WorldEntry(WorldMetadata metadata, String folderName) {
            this.worldName = metadata.name;
            this.folderName = folderName;
            this.seed = metadata.seed;
            this.mode = metadata.mode;
            this.lastPlayed = metadata.lastPlayed;

            // Parse date with fallback
            LocalDateTime parsedDate;
            try {
                parsedDate = LocalDateTime.parse(metadata.lastPlayed);
            } catch (Exception e) {
                // Fallback if date parsing fails
                parsedDate = LocalDateTime.now();
            }
            this.lastPlayedDate = parsedDate;
        }
    }

    public WorldSelectionScreen(UIManager uiManager, Application app,
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
            "Select World",
            new Vector3f(1, 1, 1),
            1.0f,
            titleTextRenderer
        );

        // World list (scrollable)
        worldList = new ScrollableList<>(
            100, 150,                    // x, y
            app.getWindowWidth() - 200,  // width
            windowHeight - 350,          // height
            80,                          // item height (reduced for better layout)
            uiRenderer
        );

        // Set custom item renderer
        worldList.setItemRenderer((item, x, y, width, height, selected, hovered,
                                   orthoProjection, winHeight) -> {
            // Vertically center text within item height
            float fontHeight = textRenderer.getFontHeight();
            float baselineY = y + (height + fontHeight) / 2;

            // Render world name (left side)
            textRenderer.drawText(item.worldName, x + 15, baselineY,
                                new Vector3f(1, 1, 1),
                                orthoProjection, winHeight, 1.0f);

            // Render last played date (right side, smaller)
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            String dateStr = item.lastPlayedDate.format(formatter);
            float dateWidth = textRenderer.getTextWidth(dateStr, 0.8f);
            textRenderer.drawText(dateStr, x + width - dateWidth - 15, baselineY,
                                new Vector3f(0.7f, 0.7f, 0.7f),
                                orthoProjection, winHeight, 0.8f);
        });

        // Empty message label (hidden by default)
        emptyLabel = new Label(
            centerX - 200, 300,
            "No worlds found. Create your first world!",
            new Vector3f(0.7f, 0.7f, 0.7f),
            1.2f,
            textRenderer
        );
        emptyLabel.setVisible(false);

        // Buttons at bottom
        int btnY = windowHeight - 150;

        playButton = new Button(
            centerX - 350, btnY,
            300, 60,
            "Play Selected World",
            textRenderer, uiRenderer
        );
        playButton.setOnClick(this::onPlayWorld);

        createButton = new Button(
            centerX + 50, btnY,
            300, 60,
            "Create New World",
            textRenderer, uiRenderer
        );
        createButton.setOnClick(this::onCreateNewWorld);

        backButton = new Button(
            50, btnY,
            150, 60,
            "Back",
            textRenderer, uiRenderer
        );
        backButton.setOnClick(this::onBack);
    }

    @Override
    public void onShow() {
        System.out.println("Showing World Selection Screen");
        loadWorlds();
    }

    /**
     * Load all worlds from the saves/ directory.
     */
    private void loadWorlds() {
        List<WorldEntry> worlds = new ArrayList<>();
        Path savesDir = Path.of("saves");

        // Check if saves directory exists
        if (!Files.exists(savesDir)) {
            System.out.println("No saves directory found");
            updateUIForEmptyList();
            return;
        }

        // Scan for world directories
        try (Stream<Path> paths = Files.list(savesDir)) {
            paths.filter(Files::isDirectory)
                 .forEach(worldDir -> {
                     String folderName = worldDir.getFileName().toString();
                     String displayName = folderName.replace("_", " ");
                     WorldMetadata metadata = WorldStorage.loadMetadata(displayName);

                     if (metadata != null) {
                         worlds.add(new WorldEntry(metadata, folderName));
                     } else {
                         System.err.println("Failed to load metadata for: " + folderName);
                     }
                 });
        } catch (IOException e) {
            System.err.println("Error scanning worlds: " + e.getMessage());
        }

        // Sort by last played (most recent first)
        worlds.sort(Comparator.comparing(
            (WorldEntry w) -> w.lastPlayedDate
        ).reversed());

        // Update UI based on whether we have worlds
        worldList.setItems(worlds);

        if (worlds.isEmpty()) {
            updateUIForEmptyList();
        } else {
            updateUIForWorldList();
        }
    }

    /**
     * Update UI when no worlds are available.
     */
    private void updateUIForEmptyList() {
        worldList.setItems(new ArrayList<>());
        emptyLabel.setVisible(true);
        worldList.setVisible(false);
        playButton.setVisible(false);
    }

    /**
     * Update UI when worlds are available.
     */
    private void updateUIForWorldList() {
        emptyLabel.setVisible(false);
        worldList.setVisible(true);
        playButton.setVisible(true);
        worldList.setSelectedIndex(0); // Select first world by default
    }

    /**
     * Callback for "Play Selected World" button.
     */
    private void onPlayWorld() {
        WorldEntry selected = worldList.getSelectedItem();
        if (selected == null) {
            System.err.println("No world selected");
            return;
        }

        System.out.println("Loading world: " + selected.worldName);

        // Transition to LoadingState with existing world
        app.getStateManager().setState(
            new LoadingState(app, uiManager.getRenderContext(),
                           uiManager.getInput(), selected.worldName)
        );
    }

    /**
     * Callback for "Create New World" button.
     */
    private void onCreateNewWorld() {
        uiManager.showScreen("world_creation");
    }

    /**
     * Callback for "Back" button.
     */
    private void onBack() {
        uiManager.showScreen("main_menu");
    }

    @Override
    public void onHide() {
        System.out.println("Hiding World Selection Screen");
    }

    @Override
    public void update(float deltaTime) {
        titleLabel.update(deltaTime);
        worldList.update(deltaTime);
        emptyLabel.update(deltaTime);
        playButton.update(deltaTime);
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
        titleLabel.render(orthoProjection, windowWidth ,windowHeight, scale);

        if (worldList.isVisible()) {
            worldList.render(orthoProjection, windowWidth, windowHeight, scale);
            playButton.render(orthoProjection, windowWidth, windowHeight, scale);
        } else {
            emptyLabel.render(orthoProjection, windowWidth, windowHeight, scale);
        }

        createButton.render(orthoProjection, windowWidth, windowHeight, scale);
        backButton.render(orthoProjection, windowWidth, windowHeight, scale);
    }

    @Override
    public void handleInput(Input input) {
        worldList.handleInput(input);
        playButton.handleInput(input);
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
