package engine.ui.component;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;

import engine.input.Input;
import engine.render.UIRenderer;

/**
 * Generic scrollable list component with keyboard and mouse navigation.
 * Uses Strategy pattern for custom item rendering.
 *
 * @param <T> Type of items in the list
 */
public class ScrollableList<T> implements UIComponent {

    /**
     * Interface for custom item rendering strategy.
     * Allows flexible rendering of different item types.
     */
    public interface ItemRenderer<T> {
        void render(T item, float x, float y, float width, float height,
                   boolean selected, boolean hovered,
                   Matrix4f orthoProjection, int windowHeight);
    }

    // Data model
    private List<T> items = new ArrayList<>();
    private ItemRenderer<T> itemRenderer;

    // Layout configuration
    private float x, y, width, height;
    private float itemHeight;
    private boolean visible = true;

    // State
    private int selectedIndex = -1;
    private int scrollOffset = 0; // Offset in items, not pixels
    private int hoveredIndex = -1;

    // Dependencies
    private final UIRenderer uiRenderer;

    // Colors
    private Vector4f bgFillColor = new Vector4f(0, 0, 0, 0);  // Transparent background
    private Vector4f selectionColor = new Vector4f(1, 1, 1, 0.35f);  // White overlay for selected items
    private Vector4f hoverColor = new Vector4f(1, 1, 1, 0.2f);  // Lighter white overlay for hover
    private Vector4f borderColor = new Vector4f(0.7f, 0.7f, 0.7f, 1.0f);
    private float borderWidth = 2.0f;

    /**
     * Create a scrollable list component.
     *
     * @param x X position
     * @param y Y position
     * @param width Width of the list
     * @param height Height of the list viewport
     * @param itemHeight Height of each item
     * @param uiRenderer UI renderer for drawing
     */
    public ScrollableList(float x, float y, float width, float height,
                         float itemHeight, UIRenderer uiRenderer) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.itemHeight = itemHeight;
        this.uiRenderer = uiRenderer;
    }

    /**
     * Set the items to display in the list.
     * Resets selection if it's out of bounds.
     */
    public void setItems(List<T> items) {
        this.items = new ArrayList<>(items);
        // Reset selection if out of bounds
        if (selectedIndex >= items.size()) {
            selectedIndex = items.isEmpty() ? -1 : 0;
        }
        // Reset scroll if needed
        if (scrollOffset >= items.size()) {
            scrollOffset = Math.max(0, items.size() - 1);
        }
    }

    /**
     * Set the item renderer for custom drawing.
     */
    public void setItemRenderer(ItemRenderer<T> renderer) {
        this.itemRenderer = renderer;
    }

    @Override
    public void render(Matrix4f orthoProjection, int windowWidth, int windowHeight,float scale) {
        if (!visible || itemRenderer == null) return;

        // Draw background with border (transparent background, just the border)
        uiRenderer.drawRectWithBorder(x, y, width, height, bgFillColor, borderColor, borderWidth, orthoProjection);

        // Calculate visible range
        int maxVisibleItems = (int) (height / itemHeight);
        int startIndex = scrollOffset;
        int endIndex = Math.min(startIndex + maxVisibleItems, items.size());

        // Render visible items
        for (int i = startIndex; i < endIndex; i++) {
            T item = items.get(i);
            float itemY = y + (i - scrollOffset) * itemHeight;

            boolean isSelected = (i == selectedIndex);
            boolean isHovered = (i == hoveredIndex);

            // Draw selection/hover overlay
            if (isSelected) {
                uiRenderer.drawRect(x, itemY, width, itemHeight,
                                   selectionColor, orthoProjection);
            } else if (isHovered) {
                uiRenderer.drawRect(x, itemY, width, itemHeight,
                                   hoverColor, orthoProjection);
            }

            // Delegate item rendering to custom renderer
            itemRenderer.render(item, x, itemY, width, itemHeight,
                              isSelected, isHovered, orthoProjection, windowHeight);
        }
    }

    @Override
    public void handleInput(Input input) {
        if (!visible) return;

        float mouseX = input.getMouseX();
        float mouseY = input.getMouseY();

        // Update hovered index
        hoveredIndex = -1;
        if (contains(mouseX, mouseY)) {
            int relativeY = (int) (mouseY - y);
            int itemIndex = scrollOffset + (int) (relativeY / itemHeight);
            if (itemIndex >= 0 && itemIndex < items.size()) {
                hoveredIndex = itemIndex;
            }

            // Handle mouse click
            if (input.isMouseClicked(0) && hoveredIndex != -1) {
                selectedIndex = hoveredIndex;
            }
        }

        // Keyboard navigation
        if (input.isKeyPressed(GLFW_KEY_UP)) {
            if (selectedIndex > 0) {
                selectedIndex--;
                scrollToSelection();
            }
        } else if (input.isKeyPressed(GLFW_KEY_DOWN)) {
            if (selectedIndex < items.size() - 1) {
                selectedIndex++;
                scrollToSelection();
            } else if (selectedIndex == -1 && !items.isEmpty()) {
                // If nothing selected, select first item
                selectedIndex = 0;
                scrollToSelection();
            }
        }
    }

    /**
     * Scroll the viewport to ensure the selected item is visible.
     */
    private void scrollToSelection() {
        if (selectedIndex < 0) return;

        int maxVisibleItems = (int) (height / itemHeight);

        // Scroll up if selection is above viewport
        if (selectedIndex < scrollOffset) {
            scrollOffset = selectedIndex;
        }
        // Scroll down if selection is below viewport
        else if (selectedIndex >= scrollOffset + maxVisibleItems) {
            scrollOffset = selectedIndex - maxVisibleItems + 1;
        }
    }

    @Override
    public void update(float deltaTime) {
        // No animation for now
    }

    @Override
    public boolean contains(float px, float py) {
        return px >= x && px <= x + width && py >= y && py <= y + height;
    }

    /**
     * Get the currently selected item.
     * @return Selected item, or null if no selection
     */
    public T getSelectedItem() {
        return (selectedIndex >= 0 && selectedIndex < items.size())
               ? items.get(selectedIndex) : null;
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public void setSelectedIndex(int index) {
        if (index >= 0 && index < items.size()) {
            selectedIndex = index;
            scrollToSelection();
        }
    }

    public List<T> getItems() {
        return new ArrayList<>(items);
    }

    // UIComponent interface implementation
    @Override public float getX() { return x; }
    @Override public float getY() { return y; }
    @Override public float getWidth() { return width; }
    @Override public float getHeight() { return height; }
    @Override public void setPosition(float x, float y) { this.x = x; this.y = y; }
    @Override public void setSize(float w, float h) { this.width = w; this.height = h; }
    @Override public boolean isVisible() { return visible; }
    @Override public void setVisible(boolean visible) { this.visible = visible; }

    // Setters for customization
    public void setBgFillColor(Vector4f color) { this.bgFillColor = color; }
    public void setSelectionColor(Vector4f color) { this.selectionColor = color; }
    public void setHoverColor(Vector4f color) { this.hoverColor = color; }
}
