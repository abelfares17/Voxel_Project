// src/main/java/engine/Main.java
package engine;

import engine.input.Input;
import engine.render.UIScreenScaler;
import engine.render.Shader;
import engine.render.TextureLoader;
import engine.world.BlockRegistry;
import engine.world.World;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

public class Main {

    private long window;
    private Input input;
    private Game game;
    private UIScreenScaler scaler;

    public int renderDistance = 10;

    public static void main(String[] args) {
        new Main().run();
    }

    public void run() {
        float init_aspect = initWindow();
        initGame(init_aspect);
        loop();
        cleanup();
    }

    private float initWindow() {
        // Afficher les erreurs GLFW dans la console
        GLFWErrorCallback.createPrint(System.err).set();

        if (!glfwInit()) {
            throw new IllegalStateException("Impossible d'initialiser GLFW");
        }

        // Contexte OpenGL 3.2 Core Profile (macOS compatible)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE,    GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_RESIZABLE,         GLFW_TRUE);

        window = glfwCreateWindow(1280, 720, "Voxel Engine 3D Only", 0, 0);
        if (window == 0) {
            throw new RuntimeException("Échec de la création de la fenêtre GLFW");
        }

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1); // V-Sync
        glfwShowWindow(window);
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_PRESS) {
                glfwSetWindowShouldClose(win, true);
            }
        });
        org.lwjgl.opengl.GL.createCapabilities();

        // 1) Récupère la taille réelle du framebuffer (en pixels)
        IntBuffer fbW = BufferUtils.createIntBuffer(1);
        IntBuffer fbH = BufferUtils.createIntBuffer(1);
        glfwGetFramebufferSize(window, fbW, fbH);
        int width  = fbW.get(0);
        int height = fbH.get(0);
        float init_aspect = (float)width/(float)height;
        System.out.println("Viewport = " + width + "×" + height + "; aspect = " + init_aspect);

        // 2) Définit le viewport sur tout l’écran
        glViewport(0, 0, width, height);
        // Activer le test de profondeur
        glEnable(GL_DEPTH_TEST);
        // Mode plein polygone par défaut
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        return init_aspect;
    }

    private void initGame(float aspect) {
        BlockRegistry.registerAll();
        scaler = new UIScreenScaler(window);
        input = new Input(window, scaler);

        long seed = System.currentTimeMillis();
        World world = new World(seed,renderDistance);

        Shader voxelShader = new Shader(
                "shaders/voxel_vertex_shader.glsl",
                "shaders/voxel_fragment_shader.glsl"
        );

        int atlasTextureId = TextureLoader.loadTextureFromClasspath("textures/atlas.png");
        System.out.println("Atlas texture ID: " + atlasTextureId);

        glfwSetFramebufferSizeCallback(window, (win, width, height) -> {
            if (height == 0) return; // évite division par zéro
            game.setAspect((float)width / (float) height);
            glViewport(0, 0, width, height); // très important
        });

        game = new Game(window, input, world, voxelShader, atlasTextureId);
        game.setAspect(aspect);
    }

    private void loop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            input.update();
            /*
            if (input.forward) System.out.println("AVANCE !");
            if (input.backward) System.out.println("RECULE !");
            if (input.left) System.out.println("GAUCHE !");
            if (input.right) System.out.println("DROITE !");
            if (input.jump) System.out.println("SAUTE !");
            */
            game.update();
            game.render();

            glfwSwapBuffers(window);
        }
    }

    private void cleanup() {
        if (game != null) {
            game.getWorld().save();
            game.getShader().cleanup();
        }
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    public long getWindow() {
        return window;
    }
}