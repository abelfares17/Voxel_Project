package engine.ui.component;

import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_END;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_HOME;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;

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
    private int caretPosition = 0;  // Caret index within text [0, text.length()]

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
        Vector4f fillColor = normalFillColor;
        Vector4f currentBorderColor = focused ? focusBorderColor : borderColor;
        uiRenderer.drawRectWithBorder(x, y, width, height, fillColor, currentBorderColor, borderWidth, orthoProjection);

        // Render text or placeholder
        String displayText = text.isEmpty() ? placeholder : text;
        Vector3f displayColor = text.isEmpty() ? placeholderColor : textColor;

        // Vertically center text within the TextField
        float fontHeight = textRenderer.getFontHeight();
        float textX = x + 10; // Horizontal padding
        float textY = y + (height + fontHeight) / 2; // Centered baseline position
        textRenderer.drawText(displayText, textX, textY, displayColor, orthoProjection, windowHeight, scale);

        // Render cursor if focused - MUST be drawn AFTER border to be visible
        if (focused) {
            // Calculate cursor X position based on text BEFORE the caret
            String textBeforeCaret = text.substring(0, Math.min(caretPosition, text.length()));
            float caretX = textX + textRenderer.getTextWidth(textBeforeCaret, scale);

            // Cursor starts at top of text area and extends to full font height
            // Inset slightly to avoid overlap with border
            float cursorHeight = fontHeight;
            float cursorY = y + height/2 - cursorHeight * 0.8f;

            // Draw caret with full opacity
            uiRenderer.drawRect(caretX, cursorY, 3, cursorHeight, new Vector4f(1, 1, 1, 1), orthoProjection);
        }
    }

    @Override
    public void handleInput(Input input) {
        if (!visible) return;

        float mouseX = input.getMouseX();
        float mouseY = input.getMouseY();

        // Focus on click
        if (input.isMouseClicked(0)) {
            boolean wasFocused = focused;
            focused = contains(mouseX, mouseY);

            // When gaining focus, move caret to end
            if (focused && !wasFocused) {
                caretPosition = text.length();
                System.out.println("TextField clicked -> setting focus true");
            }
        }

        // Handle text input if focused
        if (focused) {
            // Get typed characters and insert at caret position
            List<Character> chars = input.consumeChars();
            for (char c : chars) {
                // Insert character at caret position
                text = text.substring(0, caretPosition) + c + text.substring(caretPosition);
                caretPosition++;  // Move caret forward
            }

            // Handle backspace - delete character BEFORE caret
            if (input.isKeyJustPressed(GLFW_KEY_BACKSPACE) && caretPosition > 0) {
                text = text.substring(0, caretPosition - 1) + text.substring(caretPosition);
                caretPosition--;  // Move caret back
            }

            // Handle delete - delete character AT caret position
            if (input.isKeyJustPressed(GLFW_KEY_DELETE) && caretPosition < text.length()) {
                text = text.substring(0, caretPosition) + text.substring(caretPosition + 1);
                // Caret stays at same position
            }

            // Handle left arrow - move caret left
            if (input.isKeyJustPressed(GLFW_KEY_LEFT)) {
                caretPosition = Math.max(0, caretPosition - 1);
            }

            // Handle right arrow - move caret right
            if (input.isKeyJustPressed(GLFW_KEY_RIGHT)) {
                caretPosition = Math.min(text.length(), caretPosition + 1);
            }

            // Handle Home - move caret to start
            if (input.isKeyJustPressed(GLFW_KEY_HOME)) {
                caretPosition = 0;
            }

            // Handle End - move caret to end
            if (input.isKeyJustPressed(GLFW_KEY_END)) {
                caretPosition = text.length();
            }

            // Handle Enter to unfocus - use isKeyJustPressed for single-frame action
            if (input.isKeyJustPressed(GLFW_KEY_ENTER)) {
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
    public void setText(String text) {
        this.text = text;
        // Reset caret to end when text is changed externally
        caretPosition = text.length();
    }
    public boolean isFocused() { return focused; }
}
