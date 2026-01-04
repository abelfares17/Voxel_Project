package engine.core.states;

import engine.core.Application;
import engine.core.GameState;
import engine.core.config.GameConfig;
import engine.input.Input;
import engine.render.TextRenderer;
import engine.render.core.RenderContext;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;

/**
 * Paused state - Game paused overlay.
 * Keeps PlayingState in background, renders pause menu on top.
 */
public class PausedState implements GameState {
    private final Application app;
    private final RenderContext renderContext;
    private final Input input;
    private final PlayingState playingState; // Keep reference to resume
    private TextRenderer textRenderer;

    public PausedState(Application app, RenderContext renderContext, Input input, PlayingState playingState) {
        this.app = app;
        this.renderContext = renderContext;
        this.input = input;
        this.playingState = playingState;
    }

    @Override
    public void enter() {
        System.out.println("Entering Paused state...");
        textRenderer = new TextRenderer(GameConfig.FONT_PATH, GameConfig.FONT_SIZE);
    }

    @Override
    public void update(float deltaTime) {
        // Check for resume (ESC key)
        if (input.isKeyPressed(GLFW_KEY_ESCAPE)) {
            app.getStateManager().setState(playingState);
            playingState.resume();
        }
    }

    @Override
    public void render(RenderContext context) {
        // Render game in background (frozen)
        playingState.render(context);

        // Render pause overlay (semi-transparent)
        // TODO: Use UIRenderer for darkened overlay

        // Render pause text
        Matrix4f ortho = new Matrix4f().ortho2D(
            0, app.getWindowWidth(),
            app.getWindowHeight(), 0
        );

        textRenderer.drawText(
            "PAUSED",
            app.getWindowWidth() / 2 - 80,
            app.getWindowHeight() / 2 - 50,
            new Vector3f(1, 1, 1),
            ortho,
            app.getWindowHeight(),
            2.0f
        );

        textRenderer.drawText(
            "Press ESC to resume",
            app.getWindowWidth() / 2 - 150,
            app.getWindowHeight() / 2 + 50,
            new Vector3f(0.8f, 0.8f, 0.8f),
            ortho,
            app.getWindowHeight(),
            1.0f
        );
    }

    @Override
    public void exit() {
        System.out.println("Exiting Paused state...");
        textRenderer.cleanup();
    }

    @Override
    public boolean blocksGameUpdates() {
        return true; // Pause blocks game updates
    }
}
