package engine.render;

import engine.render.camera.Camera;
import engine.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL32.*;

public class ShadowMapRenderer {
    private static final int SHADOW_WIDTH = 3072, SHADOW_HEIGHT = 2048;
    private final int fbo, depthMap;
    private final Matrix4f lightSpaceMatrix = new Matrix4f();

    public ShadowMapRenderer() {
        // Texture de profondeur
        depthMap = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, depthMap);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT,
                SHADOW_WIDTH, SHADOW_HEIGHT, 0, GL_DEPTH_COMPONENT, GL_FLOAT, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);

        // FBO
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthMap, 0);
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    public void render(World world, Vector3f sunDir, Shader depthShader, Camera camera) {
        // Calcul de la matrice de lumière
        Vector3f sunPos = new Vector3f(camera.getPosition()).sub(sunDir.mul(100.0f, new Vector3f()));
        Vector3f center = new Vector3f(camera.getPosition());
        Matrix4f lightView = new Matrix4f().lookAt(sunPos, center, new Vector3f(0, 1, 0));
        Matrix4f lightProj = new Matrix4f().ortho(-100, 100, -100, 100, 0.1f, 300);
        lightSpaceMatrix.set(lightProj).mul(lightView);

        // Rendu depth
        glViewport(0, 0, SHADOW_WIDTH, SHADOW_HEIGHT);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glClear(GL_DEPTH_BUFFER_BIT);

        depthShader.bind();
        depthShader.setMat4("lightSpaceMatrix", lightSpaceMatrix);
        world.renderDepth(depthShader); // méthode à ajouter dans World

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    public Matrix4f getLightSpaceMatrix() {
        return lightSpaceMatrix;
    }

    public int getDepthTexture() {
        return depthMap;
    }

    /**
     * Clean up shadow map resources (FBO and depth texture).
     * Must be called before application exit to prevent GPU memory leaks.
     */
    public void cleanup() {
        if (depthMap != 0) {
            glDeleteTextures(depthMap);
        }
        if (fbo != 0) {
            glDeleteFramebuffers(fbo);
        }
    }
}