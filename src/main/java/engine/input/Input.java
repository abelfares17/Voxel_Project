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
        for (int b = 0; b < currMouse.length; b++) {
            prevMouse[b] = currMouse[b];
            currMouse[b] = (glfwGetMouseButton(window, b) == GLFW_PRESS);
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

    /** Teste si une touche est pressée (utile en menu) */
    public boolean isKeyPressed(int key) {
        return glfwGetKey(window, key) == GLFW_PRESS;
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