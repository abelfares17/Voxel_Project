package engine.render;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Permet de convertir des coordonnées “UI” (points/logiques)
 * en pixels réels du framebuffer, en tenant compte du DPI.
 */
public class UIScreenScaler {
    private final long window;

    private int winWidth, winHeight;   // taille en “points” (glfwGetWindowSize)
    private int fbWidth, fbHeight;     // taille en pixels  (glfwGetFramebufferSize)

    private float scaleX, scaleY;

    public UIScreenScaler(long window) {
        this.window = window;
        update();
    }

    /** Appeler une fois par frame après avoir changé la taille de la fenêtre. */
    public void update() {
        // taille “logique” (points)
        int[] w = new int[1], h = new int[1];
        glfwGetWindowSize(window, w, h);
        winWidth  = w[0];
        winHeight = h[0];

        // taille réelle (pixels)
        int[] pw = new int[1], ph = new int[1];
        glfwGetFramebufferSize(window, pw, ph);
        fbWidth  = pw[0];
        fbHeight = ph[0];

        // ratio pixels / points
        scaleX = (float) fbWidth  / (float) winWidth;
        scaleY = (float) fbHeight / (float) winHeight;
    }

    public float getScale() {
        // tu peux prendre soit scaleX, soit scaleY, ou (scaleX+scaleY)/2
        return (scaleX + scaleY) * 0.5f;
    }

    /** Convertit une abscisse UI (points) en pixels */
    public float scaleX(float x) {
        return x * scaleX;
    }

    /** Convertit une ordonnée UI (points) en pixels */
    public float scaleY(float y) {
        return y * scaleY;
    }

    /** Centre X du framebuffer, en pixels */
    public float getCenterX() {
        return fbWidth / 2f;
    }

    /** Centre Y du framebuffer, en pixels */
    public float getCenterY() {
        return fbHeight / 2f;
    }

    /** Bas du framebuffer, en pixels (utile pour placer les boutons en bas) */
    public float getBottom() {
        return fbHeight;
    }

    /** Largeur du framebuffer, en pixels */
    public float getWidth() {
        return fbWidth;
    }

    /** Hauteur du framebuffer, en pixels */
    public float getHeight() {
        return fbHeight;
    }
}
