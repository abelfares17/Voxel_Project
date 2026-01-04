package engine.ui.component;

import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;

import engine.input.Input;
import engine.render.TextRenderer;
import engine.render.UIRenderer;

/**
 * Text input field component.
 */
public class TextField implements UIComponent {
    private float x, y, width, height;
    private String text = "";
    private String placeholder = "";
    private boolean focused = false;
    private boolean visible = true;

    private final TextRenderer textRenderer;
    private final UIRenderer uiRenderer;

    private Vector4f normalFillColor = new Vector4f(0, 0, 0, 0);  // Fully transparent (no fill)
    private Vector4f focusFillColor = new Vector4f(1, 1, 1, 0.15f);  // Slight white brightening when focused
    private Vector4f borderColor = new Vector4f(0.7f, 0.7f, 0.7f, 1.0f);
    private Vector4f focusBorderColor = new Vector4f(0.9f, 0.9f, 1.0f, 1.0f);  // Slightly blue-tinted white
    private Vector3f textColor = new Vector3f(1, 1, 1);
    private Vector3f placeholderColor = new Vector3f(0.7f, 0.7f, 0.7f);
    private float borderWidth = 2.0f;

    public TextField(float x, float y, float width, float height, String placeholder,
                     TextRenderer textRenderer, UIRenderer uiRenderer) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.placeholder = placeholder;
        this.textRenderer = textRenderer;
        this.uiRenderer = uiRenderer;
    }

    @Override
    public void update(float deltaTime) {
        // TextField logic
    }

    @Override
    public void render(Matrix4f orthoProjection, int windowWidth, int windowHeight, float scale) {
        if (!visible) return;

        // Render border with optional fill (transparent when not focused, slight brightening when focused)
        Vector4f fillColor = focused ? focusFillColor : normalFillColor;
        Vector4f currentBorderColor = focused ? focusBorderColor : borderColor;
        uiRenderer.drawRectWithBorder(x, y, width, height, fillColor, currentBorderColor, borderWidth, orthoProjection);

        // Render text or placeholder
        String displayText = text.isEmpty() ? placeholder : text;
        Vector3f displayColor = text.isEmpty() ? placeholderColor : textColor;

        float textX = x + 10; // Padding
        float textY = y + height - 12;
        textRenderer.drawText(displayText, textX, textY, displayColor, orthoProjection, windowHeight, scale);

        // Render cursor if focused
        if (focused) {
            float cursorX = textX + text.length() * 12;
            uiRenderer.drawRect(cursorX, textY, 2, 24, new Vector4f(1, 1, 1, 1), orthoProjection);
        }
    }

    @Override
    public void handleInput(Input input) {
        if (!visible) return;

        float mouseX = input.getMouseX();
        float mouseY = input.getMouseY();

        // Focus on click
        if (input.isMouseClicked(0)) {
            focused = contains(mouseX, mouseY);
        }

        // Handle text input if focused
        if (focused) {
            // Get typed characters
            List<Character> chars = input.consumeChars();
            for (char c : chars) {
                text += c;
            }

            // Handle backspace
            if (input.isKeyPressed(GLFW_KEY_BACKSPACE) && !text.isEmpty()) {
                text = text.substring(0, text.length() - 1);
            }

            // Handle Enter to unfocus
            if (input.isKeyPressed(GLFW_KEY_ENTER)) {
                focused = false;
            }
        }
    }

    @Override
    public boolean contains(float px, float py) {
        return px >= x && px <= x + width && py >= y && py <= y + height;
    }

    @Override public float getX() { return x; }
    @Override public float getY() { return y; }
    @Override public float getWidth() { return width; }
    @Override public float getHeight() { return height; }
    @Override public void setPosition(float x, float y) { this.x = x; this.y = y; }
    @Override public void setSize(float width, float height) { this.width = width; this.height = height; }
    @Override public boolean isVisible() { return visible; }
    @Override public void setVisible(boolean visible) { this.visible = visible; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public boolean isFocused() { return focused; }
}
