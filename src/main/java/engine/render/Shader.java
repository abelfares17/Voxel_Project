package engine.render;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

public class Shader {
    private final int programId;

    public Shader(String vertexPath, String fragmentPath) {
        String vertSrc = readFile(vertexPath);
        String fragSrc = readFile(fragmentPath);

        int vertId = compileShader(vertSrc, GL_VERTEX_SHADER);
        int fragId = compileShader(fragSrc, GL_FRAGMENT_SHADER);

        programId = glCreateProgram();
        glAttachShader(programId, vertId);
        glAttachShader(programId, fragId);

        // Bind des attributs AVANT le link
        glBindAttribLocation(programId, 0, "aPos");
        glBindAttribLocation(programId, 1, "aTex");
        glBindAttribLocation(programId, 2, "aNormal");

        glLinkProgram(programId);
        System.out.println("aPos: " + glGetAttribLocation(programId, "aPos"));
        System.out.println("aTex: " + glGetAttribLocation(programId, "aTex"));
        System.out.println("aNormal: " + glGetAttribLocation(programId, "aNormal"));
        if (glGetProgrami(programId, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(programId);
            throw new RuntimeException("Shader linking failed:\n" + log);
        }

        // On peut supprimer les objets shader compilés
        glDeleteShader(vertId);
        glDeleteShader(fragId);
    }

    private String readFile(String path) {
        try (InputStream in = getClass()
                .getClassLoader()
                .getResourceAsStream(path)) {
            if (in == null) {
                throw new RuntimeException("Cannot find resource: " + path);
            }
            byte[] bytes = in.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Cannot read shader resource: " + path, e);
        }
    }

    private int compileShader(String src, int type) {
        int id = glCreateShader(type);
        glShaderSource(id, src);
        glCompileShader(id);
        if (glGetShaderi(id, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(id);
            String typeName = (type == GL_VERTEX_SHADER ? "VERTEX" : "FRAGMENT");
            throw new RuntimeException(typeName + " shader compile error:\n" + log);
        }
        return id;
    }

    public void bind() {
        glUseProgram(programId);
    }

    public void unbind() {
        glUseProgram(0);
    }

    /** À appeler à la fermeture de l’application **/
    public void cleanup() {
        unbind();
        glDeleteProgram(programId);
    }

    public int getId() {
        return programId;
    }

    /*---------------------------
      Méthodes pour envoyer des uniformes
     ---------------------------*/

    public void setUniform(String name, int value) {
        int loc = glGetUniformLocation(programId, name);
        if (loc >= 0) glUniform1i(loc, value);
    }

    public void setUniform(String name, float value) {
        int loc = glGetUniformLocation(programId, name);
        if (loc >= 0) glUniform1f(loc, value);
    }

    public void setUniform(String name, org.joml.Vector3f vec) {
        int loc = glGetUniformLocation(programId, name);
        if (loc >= 0) glUniform3f(loc, vec.x, vec.y, vec.z);
    }

    public void setUniform(String name, org.joml.Vector4f vec) {
        int loc = glGetUniformLocation(programId, name);
        if (loc >= 0) glUniform4f(loc, vec.x, vec.y, vec.z, vec.w);
    }

    public void setUniform(String name, org.joml.Matrix4f mat) {
        int loc = glGetUniformLocation(programId, name);
        if (loc >= 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer fb = stack.mallocFloat(16);
                mat.get(fb);
                glUniformMatrix4fv(loc, false, fb);
            }
        }
    }

    public void setMat4(String name, Matrix4f matrix) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer fb = stack.mallocFloat(16);
            matrix.get(fb);
            int loc = glGetUniformLocation(programId, name);
            if (loc != -1) glUniformMatrix4fv(loc, false, fb);
        }
    }

    public void setTexture(String name, int textureId, int unit) {
        int loc = glGetUniformLocation(programId, name);
        if (loc != -1) {
            glActiveTexture(GL_TEXTURE0 + unit);
            glBindTexture(GL_TEXTURE_2D, textureId);
            glUniform1i(loc, unit);
        }
    }
}