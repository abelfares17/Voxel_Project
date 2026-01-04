package engine.core.states;

import engine.core.Application;
import engine.core.GameState;
import engine.input.Input;
import engine.render.core.RenderContext;
import engine.ui.UIManager;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/**
 * Menu state using UIManager for all menu screens.
 */
public class MenuState implements GameState {
    private final Application app;
    private final RenderContext renderContext;
    private final Input input;
    private UIManager uiManager;

    public MenuState(Application app, RenderContext renderContext, Input input) {
        this.app = app;
        this.renderContext = renderContext;
        this.input = input;
    }

    @Override
    public void enter() {
        System.out.println("Entering Menu state...");

        // Show cursor for menu navigation
        glfwSetInputMode(app.getWindow(), GLFW_CURSOR, GLFW_CURSOR_NORMAL);

        uiManager = new UIManager(app, renderContext, input);
        uiManager.showScreen("main_menu");
    }

    @Override
    public void update(float deltaTime) {
        uiManager.update(deltaTime);
        uiManager.handleInput();
    }

    @Override
    public void render(RenderContext context) {
        // Clear screen
        glClearColor(0.1f, 0.1f, 0.15f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        // Disable depth testing for 2D UI rendering
        glDisable(GL_DEPTH_TEST);

        // Render UI
        uiManager.render(app.getWindowWidth(), app.getWindowHeight());

        // Re-enable depth testing for 3D rendering (if needed later)
        glEnable(GL_DEPTH_TEST);
    }

    @Override
    public void exit() {
        System.out.println("Exiting Menu state...");
        uiManager.cleanup();
    }

    @Override
    public void onResize(int newWidth, int newHeight) {
        if (uiManager != null) {
            uiManager.onResize(newWidth, newHeight);
        }
    }

    @Override
    public boolean blocksGameUpdates() {
        return true;
    }

    public UIManager getUIManager() {
        return uiManager;
    }
}
