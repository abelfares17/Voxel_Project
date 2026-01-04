package engine.render.renderer;

import engine.core.config.RenderConfig;
import engine.render.Shader;
import engine.render.camera.Camera;
import engine.render.core.RenderContext;
import engine.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Renderer responsible for shadow mapping.
 * Renders depth map from light's perspective for shadow calculation.
 */
public class ShadowRenderer implements Renderer {

    private final int fbo;
    private final int depthMap;
    private final Matrix4f lightSpaceMatrix = new Matrix4f();
    private final Shader depthShader;

    // Viewport to restore after shadow pass
    private int savedViewportWidth;
    private int savedViewportHeight;

    /**
     * Create a new shadow renderer with depth shader.
     *
     * @param depthShader Shader for depth-only rendering
     */
    public ShadowRenderer(Shader depthShader) {
        this.depthShader = depthShader;

        // Create depth texture
        depthMap = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, depthMap);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT,
                RenderConfig.SHADOW_MAP_WIDTH, RenderConfig.SHADOW_MAP_HEIGHT,
                0, GL_DEPTH_COMPONENT, GL_FLOAT, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);

        // Create FBO
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthMap, 0);
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);

        // Validate framebuffer
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Shadow framebuffer incomplete: " + status);
        }

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    @Override
    public void render(RenderContext context) {
        // Get sun direction and camera
        Vector3f sunDir = context.getSunDirection();
        if (sunDir == null) {
            sunDir = context.getWorld().getTimeManager().getSunData().sunDir();
        }
        Camera camera = context.getCamera();

        // Calculate light space matrix
        Vector3f cameraPos = camera.getPosition();
        Vector3f sunPos = new Vector3f(cameraPos).sub(new Vector3f(sunDir).mul(100.0f));
        Vector3f center = new Vector3f(cameraPos);

        Matrix4f lightView = new Matrix4f().lookAt(sunPos, center, new Vector3f(0, 1, 0));
        Matrix4f lightProj = new Matrix4f().ortho(-100, 100, -100, 100, 0.1f, 300);
        lightSpaceMatrix.set(lightProj).mul(lightView);

        // Save current viewport
        savedViewportWidth = context.getWindowWidth();
        savedViewportHeight = context.getWindowHeight();

        // Set shadow map viewport
        glViewport(0, 0, RenderConfig.SHADOW_MAP_WIDTH, RenderConfig.SHADOW_MAP_HEIGHT);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glClear(GL_DEPTH_BUFFER_BIT);

        // Render depth map
        depthShader.bind();
        depthShader.setMat4("lightSpaceMatrix", lightSpaceMatrix);
        context.getWorld().renderDepth(depthShader);
        depthShader.unbind();

        // Restore framebuffer and viewport
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, savedViewportWidth, savedViewportHeight);
    }

    @Override
    public void cleanup() {
        depthShader.cleanup();
        if (depthMap != 0) {
            glDeleteTextures(depthMap);
        }
        if (fbo != 0) {
            glDeleteFramebuffers(fbo);
        }
    }

    /**
     * Get light space matrix for shadow mapping.
     *
     * @return Light space transformation matrix
     */
    public Matrix4f getLightSpaceMatrix() {
        return lightSpaceMatrix;
    }

    /**
     * Get depth texture ID.
     *
     * @return OpenGL texture ID of shadow depth map
     */
    public int getDepthTexture() {
        return depthMap;
    }
}
