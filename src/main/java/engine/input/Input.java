package engine.input;

import engine.render.UIScreenScaler;

import static org.lwjgl.glfw.GLFW.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Gère l’input de toute l’app :
 * – polling des touches et de la souris pour le jeu
 * – callback char pour la saisie de texte (menus)
 */
public class Input {
    private final long window;
    private final UIScreenScaler scaler;

    // Touches de jeu
    public boolean forward, backward, left, right, jump, crouch, coords;
    // Position et delta souris
    private double lastX, lastY, x, y;
    private double deltaX, deltaY;
    // Buffer des caractères saisis
    private final List<Character> charBuffer = new ArrayList<>();
    private final boolean[] prevMouse = new boolean[GLFW_MOUSE_BUTTON_LAST];
    private final boolean[] currMouse = new boolean[GLFW_MOUSE_BUTTON_LAST];
    // Keyboard state tracking for edge detection (just-pressed detection)
    private final boolean[] prevKeys = new boolean[GLFW_KEY_LAST + 1];
    private final boolean[] currKeys = new boolean[GLFW_KEY_LAST + 1];
    // Mouse scroll tracking
    private double scrollX = 0.0;
    private double scrollY = 0.0;


    public Input(long window, UIScreenScaler scaler) {
        this.window = window;
        this.scaler = scaler;
        // Callback pour les caractères (TextField)
        glfwSetCharCallback(window, (win, codepoint) -> {
            try {
                charBuffer.add((char) codepoint);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        // Callback pour le scroll (molette / trackpad)
        glfwSetScrollCallback(window, (win, xoffset, yoffset) -> {
            scrollX += xoffset;
            scrollY += yoffset;
        });
    }

    /** À appeler en début de chaque frame */
    public void update() {
        // Polling touches
        forward  = glfwGetKey(window, GLFW_KEY_W)     == GLFW_PRESS;
        backward = glfwGetKey(window, GLFW_KEY_S)     == GLFW_PRESS;
        left     = glfwGetKey(window, GLFW_KEY_A)     == GLFW_PRESS;
        right    = glfwGetKey(window, GLFW_KEY_D)     == GLFW_PRESS;
        jump     = glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS;
        crouch   = glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS || glfwGetKey(window, GLFW_KEY_RIGHT_SHIFT) == GLFW_PRESS;
        coords   = glfwGetKey(window, GLFW_KEY_C) == GLFW_PRESS;

        // Polling position souris
        double[] px = new double[1], py = new double[1];
        glfwGetCursorPos(window, px, py);
        x = px[0]; y = py[0];
        deltaX = x - lastX;
        deltaY = y - lastY;
        lastX = x; lastY = y;

        // Update mouse button states
        for (int b = 0; b < currMouse.length; b++) {
            prevMouse[b] = currMouse[b];
            currMouse[b] = (glfwGetMouseButton(window, b) == GLFW_PRESS);
        }

        // Update keyboard states for commonly used keys (Backspace, Enter, etc.)
        // We don't need to track all keys, just the ones used for UI input and special actions
        int[] trackedKeys = {GLFW_KEY_BACKSPACE, GLFW_KEY_ENTER, GLFW_KEY_ESCAPE,
                            GLFW_KEY_DELETE, GLFW_KEY_UP, GLFW_KEY_DOWN,
                            GLFW_KEY_LEFT, GLFW_KEY_RIGHT, GLFW_KEY_HOME, GLFW_KEY_END,
                            GLFW_KEY_F2, GLFW_KEY_F3, GLFW_KEY_F4, GLFW_KEY_F5, GLFW_KEY_F6,
                            GLFW_KEY_F7, GLFW_KEY_F8, GLFW_KEY_F9, GLFW_KEY_F10,
                            GLFW_KEY_F11, GLFW_KEY_F12,  // Debug keys
                            GLFW_KEY_0, GLFW_KEY_9,  // Number keys for slow motion
                            GLFW_KEY_B, GLFW_KEY_J, GLFW_KEY_K, GLFW_KEY_L, GLFW_KEY_M, GLFW_KEY_P};  // Toggle keys
        for (int key : trackedKeys) {
            prevKeys[key] = currKeys[key];
            currKeys[key] = (glfwGetKey(window, key) == GLFW_PRESS);
        }
    }

    /** @return delta X de la souris depuis la dernière update */
    public float getMouseDeltaX() { return (float) deltaX; }
    /** @return delta Y de la souris depuis la dernière update */
    public float getMouseDeltaY() { return (float) deltaY; }
    /** @return position X de la souris (en pixels) */
    public float getMouseX() {
        return scaler.scaleX((float) this.x) ;
    }
    /** @return position Y de la souris (en pixels) */
    public float getMouseY() {
        return scaler.scaleY((float) this.y) ;
    }

    /** Récupère et vide le buffer des caractères saisis */
    public List<Character> consumeChars() {
        List<Character> out = new ArrayList<>(charBuffer);
        charBuffer.clear();
        return out;
    }

    /** Récupère et consomme le scroll vertical (molette / trackpad) */
    public float consumeScrollY() {
        float result = (float) scrollY;
        scrollY = 0.0;
        return result;
    }

    /** Teste si une touche est pressée (utile en menu) - returns true while key is held */
    public boolean isKeyPressed(int key) {
        return glfwGetKey(window, key) == GLFW_PRESS;
    }

    /**
     * Teste si une touche vient d'être pressée ce frame (edge detection)
     * Returns true only on the frame when the key transitions from released to pressed.
     * This prevents key-repeat issues - perfect for single-action inputs like Backspace.
     *
     * @param key GLFW key code
     * @return true if key was just pressed this frame
     */
    public boolean isKeyJustPressed(int key) {
        if (key < 0 || key >= currKeys.length) return false;
        return currKeys[key] && !prevKeys[key];
    }

    /** Teste si un bouton de souris est pressé (0 = gauche, 1 = droit) */
    public boolean isMousePressed(int button) {
        return glfwGetMouseButton(window, button) == GLFW_PRESS;
    }
    /** Passe de relâché → appuyé ce frame */
    public boolean isMouseClicked(int button) {
        return currMouse[button] && !prevMouse[button];
    }
}