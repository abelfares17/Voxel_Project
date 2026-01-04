package engine.ui.component;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import engine.input.Input;
import engine.render.TextRenderer;

/**
 * Simple text label component.
 */
public class Label implements UIComponent {
    private float x, y;
    private String text;
    private Vector3f color;
    private float scale;
    private boolean visible = true;

    private final TextRenderer textRenderer;

    public Label(float x, float y, String text, Vector3f color, float scale, TextRenderer textRenderer) {
        this.x = x;
        this.y = y;
        this.text = text;
        this.color = color;
        this.scale = scale;
        this.textRenderer = textRenderer;
    }

    @Override
    public void update(float deltaTime) {
        // Labels are static
    }

    @Override
    public void render(Matrix4f orthoProjection, int windowWidth, int windowHeight, float scale) {
        if (!visible) return;
        textRenderer.drawCentered(text, x, y, scale, color, windowWidth, windowHeight);
    }

    @Override
    public void handleInput(Input input) {
        // Labels don't handle input
    }

    @Override
    public boolean contains(float px, float py) {
        return false; // Labels aren't clickable
    }

    @Override public float getX() { return x; }
    @Override public float getY() { return y; }
    @Override public float getWidth() { return text.length() * 12 * scale; } // Approximate
    @Override public float getHeight() { return 24 * scale; } // Approximate
    @Override public void setPosition(float x, float y) { this.x = x; this.y = y; }
    @Override public void setSize(float width, float height) {} // Not applicable
    @Override public boolean isVisible() { return visible; }
    @Override public void setVisible(boolean visible) { this.visible = visible; }

    public void setText(String text) { this.text = text; }
    public String getText() { return text; }
    public void setColor(Vector3f color) { this.color = color; }
}
