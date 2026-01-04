package engine.ui;

import engine.core.Application;
import engine.core.config.GameConfig;
import engine.input.Input;
import engine.render.TextRenderer;
import engine.render.UIRenderer;
import engine.render.core.RenderContext;
import engine.ui.screens.*;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages UI screens and transitions.
 * Central hub for all menu navigation.
 */
public class UIManager {
    private final Application app;
    private final RenderContext renderContext;
    private final Input input;
    private final TextRenderer textRenderer;
    private final TextRenderer titleTextRenderer;
    private final UIRenderer uiRenderer;

    private final Map<String, Screen> screens = new HashMap<>();
    private Screen currentScreen;

    public UIManager(Application app, RenderContext renderContext, Input input) {
        this.app = app;
        this.renderContext = renderContext;
        this.input = input;
        this.textRenderer = new TextRenderer(
            GameConfig.FONT_PATH,
            GameConfig.FONT_SIZE
        );
        this.titleTextRenderer = new TextRenderer(
            GameConfig.TITLE_FONT_PATH,
            GameConfig.TITLE_FONT_SIZE
        );
        this.uiRenderer = new UIRenderer();

        registerScreens();
    }

    /**
     * Register all available screens.
     */
    private void registerScreens() {
        screens.put("main_menu", new MainMenuScreen(this, app, textRenderer, titleTextRenderer, uiRenderer));
        screens.put("settings", new SettingsScreen(this, app, textRenderer, titleTextRenderer, uiRenderer));
        screens.put("loading", new LoadingScreen(this, app, textRenderer, titleTextRenderer, uiRenderer));
        screens.put("world_selection", new WorldSelectionScreen(this, app, textRenderer, titleTextRenderer, uiRenderer));
        screens.put("world_creation", new WorldCreationScreen(this, app, textRenderer, titleTextRenderer, uiRenderer));
        // TODO: Add PauseMenuScreen
    }

    /**
     * Show a screen by name.
     * @param screenName Screen identifier
     */
    public void showScreen(String screenName) {
        Screen newScreen = screens.get(screenName);

        if (newScreen == null) {
            System.err.println("Screen not found: " + screenName);
            return;
        }

        // Hide current screen
        if (currentScreen != null) {
            currentScreen.onHide();
        }

        // Show new screen
        currentScreen = newScreen;
        currentScreen.onShow();
    }

    /**
     * Update current screen.
     */
    public void update(float deltaTime) {
        if (currentScreen != null) {
            currentScreen.update(deltaTime);
        }
    }

    /**
     * Render current screen.
     */
    public void render(int windowWidth, int windowHeight) {
        if (currentScreen != null) {
            Matrix4f ortho = new Matrix4f().ortho2D(0, windowWidth, windowHeight, 0);
            currentScreen.render(ortho, windowWidth, windowHeight);
        }
    }

    /**
     * Handle input for current screen.
     */
    public void handleInput() {
        if (currentScreen != null) {
            currentScreen.handleInput(input);
        }
    }

    /**
     * Called when window is resized.
     * Notifies current screen to recalculate layout.
     */
    public void onResize(int newWidth, int newHeight) {
        if (currentScreen != null) {
            currentScreen.onResize(newWidth, newHeight);
        }
    }

    /**
     * Cleanup all screens and resources.
     */
    public void cleanup() {
        for (Screen screen : screens.values()) {
            screen.cleanup();
        }
        textRenderer.cleanup();
        titleTextRenderer.cleanup();
        uiRenderer.cleanup();
    }

    // Getters
    public RenderContext getRenderContext() { return renderContext; }
    public Input getInput() { return input; }
    public Application getApp() { return app; }

    /**
     * Get the currently active screen.
     */
    public Screen getCurrentScreen() {
        return currentScreen;
    }
}
