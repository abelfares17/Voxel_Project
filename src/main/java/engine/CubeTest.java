package engine;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.stb.STBImage;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

public class CubeTest {
    private long window;
    private int shaderProgram;
    private int vao, vbo;
    private int texId;
    private int width = 1280, height = 720;

    public static void main(String[] args) {
        new CubeTest().run();
    }

    public void run() {
        initWindow();
        initGL();
        loop();
        cleanup();
    }

    private void initWindow() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("GLFW init failed");
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        window = glfwCreateWindow(width, height, "CubeTest Atlas", 0, 0);
        if (window == 0) throw new RuntimeException("Failed to create window");
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        glfwShowWindow(window);
        GL.createCapabilities();
        glEnable(GL_DEPTH_TEST);
        glfwSetFramebufferSizeCallback(window, (win, w, h) -> {
            glViewport(0, 0, w, h);
            // Si tu utilises la largeur/hauteur ailleurs, pense à les mettre à jour !
            this.width = w;
            this.height = h;
        });
    }

    private void initGL() {
        // === 1. Shaders
        int vert = compileShader(readFileFromClasspath("shaders/cube_vertex.glsl"), GL_VERTEX_SHADER);
        int frag = compileShader(readFileFromClasspath("shaders/cube_fragment.glsl"), GL_FRAGMENT_SHADER);
        shaderProgram = glCreateProgram();
        glAttachShader(shaderProgram, vert);
        glAttachShader(shaderProgram, frag);
        glBindAttribLocation(shaderProgram, 0, "aPos");
        glBindAttribLocation(shaderProgram, 1, "aTex");
        glLinkProgram(shaderProgram);
        if (glGetProgrami(shaderProgram, GL_LINK_STATUS) == GL_FALSE)
            throw new RuntimeException("Link error: " + glGetProgramInfoLog(shaderProgram));
        glDeleteShader(vert); glDeleteShader(frag);

        // === 2. VBO/VAO, Cube positions & UV atlas
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        float[] cube = makeCubeWithAtlasUVs();
        FloatBuffer fb = BufferUtils.createFloatBuffer(cube.length);
        fb.put(cube).flip();

        glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);
        // Attribs
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 5 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
        glEnableVertexAttribArray(1);

        glBindVertexArray(0); glBindBuffer(GL_ARRAY_BUFFER, 0);

        // === 3. Charger Atlas
        texId = loadTextureFromClasspath("textures/atlas.png");
    }

    private float[] makeCubeWithAtlasUVs() {
        // Taille atlas : 1024x1024, tuile : 16x16, colonne 18, ligne 11
        final int atlasSize = 1024;
        final int tileSize = 16;
        final int col = 17, row = 10;
        float u0 = (col * tileSize) / (float)atlasSize;
        float v0 = (row * tileSize) / (float)atlasSize;
        float u1 = ((col + 1) * tileSize) / (float)atlasSize;
        float v1 = ((row + 1) * tileSize) / (float)atlasSize;
        // Cube centré en (0,0,0), taille 1
        // 6 faces, 2 triangles/face, 6 vertices/face, 5 attribs/vert (x,y,z,u,v)
        float[] vertices = {
                // Face +Z
                -0.5f, -0.5f,  0.5f,  u0, v1,
                0.5f, -0.5f,  0.5f,  u1, v1,
                0.5f,  0.5f,  0.5f,  u1, v0,
                -0.5f,  0.5f,  0.5f,  u0, v0,
                // Face -Z
                -0.5f, -0.5f, -0.5f,  u1, v1,
                -0.5f,  0.5f, -0.5f,  u1, v0,
                0.5f,  0.5f, -0.5f,  u0, v0,
                0.5f, -0.5f, -0.5f,  u0, v1,
                // Face +Y
                -0.5f,  0.5f, -0.5f,  u0, v1,
                -0.5f,  0.5f,  0.5f,  u0, v0,
                0.5f,  0.5f,  0.5f,  u1, v0,
                0.5f,  0.5f, -0.5f,  u1, v1,
                // Face -Y
                -0.5f, -0.5f, -0.5f,  u1, v1,
                0.5f, -0.5f, -0.5f,  u0, v1,
                0.5f, -0.5f,  0.5f,  u0, v0,
                -0.5f, -0.5f,  0.5f,  u1, v0,
                // Face +X
                0.5f, -0.5f, -0.5f,  u0, v1,
                0.5f,  0.5f, -0.5f,  u0, v0,
                0.5f,  0.5f,  0.5f,  u1, v0,
                0.5f, -0.5f,  0.5f,  u1, v1,
                // Face -X
                -0.5f, -0.5f, -0.5f,  u1, v1,
                -0.5f, -0.5f,  0.5f,  u0, v1,
                -0.5f,  0.5f,  0.5f,  u0, v0,
                -0.5f,  0.5f, -0.5f,  u1, v0,
        };
        // Indices pour 12 triangles (6 faces)
        int[] indices = {
                0,  1,  2,  2,  3,  0,   // +Z
                4,  5,  6,  6,  7,  4,   // -Z
                8,  9, 10, 10, 11,  8,   // +Y
                12, 13, 14, 14, 15, 12,   // -Y
                16, 17, 18, 18, 19, 16,   // +X
                20, 21, 22, 22, 23, 20    // -X
        };
        // Réorganise pour triangles list
        float[] result = new float[36 * 5];
        for (int i = 0; i < 36; i++) {
            int idx = indices[i];
            System.arraycopy(vertices, idx * 5, result, i * 5, 5);
        }
        return result;
    }

    private void loop() {
        float angle = 0;
        while (!glfwWindowShouldClose(window)) {
            glClearColor(0.5f, 0.7f, 1.0f, 1f); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            glUseProgram(shaderProgram);

            // Matrices
            Matrix4f projection = new Matrix4f().perspective((float)Math.toRadians(70.0), width/(float)height, 0.1f, 100f);
            Matrix4f view = new Matrix4f().lookAt(
                    new Vector3f(2,2,3), new Vector3f(0,0,0), new Vector3f(0,1,0));
            Matrix4f model = new Matrix4f().rotateY(angle);

            int projLoc = glGetUniformLocation(shaderProgram, "projectionMatrix");
            int viewLoc = glGetUniformLocation(shaderProgram, "viewMatrix");
            int modelLoc = glGetUniformLocation(shaderProgram, "modelMatrix");
            int samplerLoc = glGetUniformLocation(shaderProgram, "texAtlas");

            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                FloatBuffer fb = stack.mallocFloat(16);
                glUniformMatrix4fv(projLoc, false, projection.get(fb));
                glUniformMatrix4fv(viewLoc, false, view.get(fb));
                glUniformMatrix4fv(modelLoc, false, model.get(fb));
            }
            glUniform1i(samplerLoc, 0);

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, texId);

            glBindVertexArray(vao);
            glDrawArrays(GL_TRIANGLES, 0, 36);
            glBindVertexArray(0);

            glUseProgram(0);
            glfwSwapBuffers(window);
            glfwPollEvents();

            angle += 0.01f;
        }
    }

    private void cleanup() {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        glDeleteProgram(shaderProgram);
        glDeleteTextures(texId);
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    private int loadTextureFromClasspath(String path) {
        int[] w = new int[1], h = new int[1], c = new int[1];
        ByteBuffer image;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new RuntimeException("Texture not found: " + path);
            byte[] data = in.readAllBytes();
            ByteBuffer buf = BufferUtils.createByteBuffer(data.length);
            buf.put(data).flip();
            image = STBImage.stbi_load_from_memory(buf, w, h, c, 4);
            if (image == null) throw new RuntimeException("STBImage failed: " + STBImage.stbi_failure_reason());
        } catch (Exception e) {
            throw new RuntimeException("Error loading texture " + path, e);
        }
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w[0], h[0], 0, GL_RGBA, GL_UNSIGNED_BYTE, image);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);
        STBImage.stbi_image_free(image);
        return id;
    }

    private String readFileFromClasspath(String path) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new RuntimeException("Shader file not found: " + path);
            return new String(in.readAllBytes());
        } catch (Exception e) {
            throw new RuntimeException("Shader file not found: " + path, e);
        }
    }

    private int compileShader(String src, int type) {
        int id = glCreateShader(type);
        glShaderSource(id, src);
        glCompileShader(id);
        if (glGetShaderi(id, GL_COMPILE_STATUS) == GL_FALSE)
            throw new RuntimeException("Shader compile error:\n" + glGetShaderInfoLog(id));
        return id;
    }
}