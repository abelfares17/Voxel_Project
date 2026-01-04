package engine.ui.component;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import engine.input.Input;
import engine.render.TextRenderer;
import engine.render.UIRenderer;

/**
 * Button UI component with hover and click effects.
 */
public class Button implements UIComponent {
    private float x, y, width, height;
    private String text;
    private boolean visible = true;
    private boolean hovered = false;
    private boolean clicked = false;

    private final TextRenderer textRenderer;
    private final UIRenderer uiRenderer;
    private Runnable onClick;

    // Colors
    private Vector4f normalFillColor = new Vector4f(0, 0, 0, 0);  // Fully transparent (no fill)
    private Vector4f hoverFillColor = new Vector4f(1, 1, 1, 0.25f);  // Semi-transparent white (brightening)
    private Vector4f clickFillColor = new Vector4f(1, 1, 1, 0.4f);  // More visible white on click
    private Vector4f borderColor = new Vector4f(0.8f, 0.8f, 0.8f, 1.0f);
    private Vector4f hoverBorderColor = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);  // Full white on hover
    private Vector3f textColor = new Vector3f(1, 1, 1);
    private float borderWidth = 2.0f;

    public Button(float x, float y, float width, float height, String text,
                  TextRenderer textRenderer, UIRenderer uiRenderer) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.text = text;
        this.textRenderer = textRenderer;
        this.uiRenderer = uiRenderer;
    }

    @Override
    public void update(float deltaTime) {
        // Button logic (animations could go here)
    }

    @Override
    public void render(Matrix4f orthoProjection, int windowWidth, int windowHeight, float scale) {
        if (!visible) return;

        // Choose fill color based on state (transparent when normal, bright overlay when hovered)
        Vector4f fillColor = clicked ? clickFillColor : (hovered ? hoverFillColor : normalFillColor);
        Vector4f currentBorderColor = hovered ? hoverBorderColor : borderColor;

        // Render border with optional fill (fill is transparent when not hovered)
        uiRenderer.drawRectWithBorder(x, y, width, height, fillColor, currentBorderColor, borderWidth, orthoProjection);

        // Render text (properly centered using actual text dimensions)
        float textWidth = textRenderer.getTextWidth(text, 1.0f);
        float textHeight = textRenderer.getFontHeight();
        float textX = x + (width - textWidth) / 2;
        float textY = y + (height + textHeight) / 2;
        textRenderer.drawText(text, textX, textY, textColor, orthoProjection, windowHeight, scale);
    }

    @Override
    public void handleInput(Input input) {
        if (!visible) return;

        float mouseX = input.getMouseX();
        float mouseY = input.getMouseY();

        boolean wasHovered = hovered;
        hovered = contains(mouseX, mouseY);

        // Click detection
        if (hovered && input.isMouseClicked(0)) { // Left click
            clicked = true;
            if (onClick != null) {
                onClick.run();
            }
        } else {
            clicked = false;
        }
    }

    @Override
    public boolean contains(float px, float py) {
        return px >= x && px <= x + width && py >= y && py <= y + height;
    }

    // Getters/Setters
    @Override public float getX() { return x; }
    @Override public float getY() { return y; }
    @Override public float getWidth() { return width; }
    @Override public float getHeight() { return height; }
    @Override public void setPosition(float x, float y) { this.x = x; this.y = y; }
    @Override public void setSize(float width, float height) { this.width = width; this.height = height; }
    @Override public boolean isVisible() { return visible; }
    @Override public void setVisible(boolean visible) { this.visible = visible; }

    public void setOnClick(Runnable onClick) { this.onClick = onClick; }
    public void setText(String text) { this.text = text; }
    public String getText() { return text; }
}
