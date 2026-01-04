package engine.render.core;

import static org.lwjgl.opengl.GL30.*;

/**
 * Abstraction of an OpenGL framebuffer object (FBO).
 * Simplifies FBO creation, attachment, and lifecycle management.
 */
public class Framebuffer {

    private int fboId;
    private int width;
    private int height;
    private boolean hasColorAttachment;
    private boolean hasDepthAttachment;
    private int colorTexture;
    private int depthTexture;

    /**
     * Create a new framebuffer with the specified dimensions.
     *
     * @param width  Framebuffer width in pixels
     * @param height Framebuffer height in pixels
     */
    public Framebuffer(int width, int height) {
        this.width = width;
        this.height = height;
        this.fboId = glGenFramebuffers();
        this.hasColorAttachment = false;
        this.hasDepthAttachment = false;
    }

    /**
     * Bind this framebuffer for rendering.
     */
    public void bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, fboId);
    }

    /**
     * Unbind framebuffer (bind default framebuffer 0).
     */
    public void unbind() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * Attach a color texture to this framebuffer.
     *
     * @param textureId OpenGL texture ID
     */
    public void attachColorTexture(int textureId) {
        bind();
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textureId, 0);
        this.colorTexture = textureId;
        this.hasColorAttachment = true;
        unbind();
    }

    /**
     * Attach a depth texture to this framebuffer.
     *
     * @param textureId OpenGL texture ID
     */
    public void attachDepthTexture(int textureId) {
        bind();
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, textureId, 0);
        this.depthTexture = textureId;
        this.hasDepthAttachment = true;
        unbind();
    }

    /**
     * Create and attach a color texture with default settings.
     *
     * @return Created texture ID
     */
    public int createAndAttachColorTexture() {
        int textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);

        attachColorTexture(textureId);
        return textureId;
    }

    /**
     * Create and attach a depth texture with default settings.
     *
     * @return Created texture ID
     */
    public int createAndAttachDepthTexture() {
        int textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, width, height, 0, GL_DEPTH_COMPONENT, GL_FLOAT, 0);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);

        attachDepthTexture(textureId);
        return textureId;
    }

    /**
     * Disable color buffer writing (depth-only rendering).
     */
    public void disableColorBuffer() {
        bind();
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);
        unbind();
    }

    /**
     * Check if framebuffer is complete and ready for use.
     *
     * @return true if framebuffer is complete
     * @throws RuntimeException if framebuffer is incomplete
     */
    public boolean checkStatus() {
        bind();
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        unbind();

        if (status != GL_FRAMEBUFFER_COMPLETE) {
            String error = switch (status) {
                case GL_FRAMEBUFFER_UNDEFINED -> "Framebuffer undefined";
                case GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT -> "Incomplete attachment";
                case GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT -> "Missing attachment";
                case GL_FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER -> "Incomplete draw buffer";
                case GL_FRAMEBUFFER_INCOMPLETE_READ_BUFFER -> "Incomplete read buffer";
                case GL_FRAMEBUFFER_UNSUPPORTED -> "Framebuffer unsupported";
                case GL_FRAMEBUFFER_INCOMPLETE_MULTISAMPLE -> "Incomplete multisample";
                default -> "Unknown error: " + status;
            };
            throw new RuntimeException("Framebuffer incomplete: " + error);
        }

        return true;
    }

    /**
     * Resize the framebuffer and its attachments.
     *
     * @param newWidth  New width in pixels
     * @param newHeight New height in pixels
     */
    public void resize(int newWidth, int newHeight) {
        if (newWidth == this.width && newHeight == this.height) {
            return; // No change
        }

        this.width = newWidth;
        this.height = newHeight;

        // Recreate attachments if they exist
        if (hasColorAttachment) {
            glDeleteTextures(colorTexture);
            createAndAttachColorTexture();
        }

        if (hasDepthAttachment) {
            glDeleteTextures(depthTexture);
            createAndAttachDepthTexture();
        }
    }

    /**
     * Clean up framebuffer and its attachments.
     */
    public void cleanup() {
        if (hasColorAttachment && colorTexture != 0) {
            glDeleteTextures(colorTexture);
            colorTexture = 0;
        }

        if (hasDepthAttachment && depthTexture != 0) {
            glDeleteTextures(depthTexture);
            depthTexture = 0;
        }

        if (fboId != 0) {
            glDeleteFramebuffers(fboId);
            fboId = 0;
        }
    }

    /**
     * Get framebuffer ID.
     *
     * @return OpenGL FBO ID
     */
    public int getId() {
        return fboId;
    }

    /**
     * Get width in pixels.
     *
     * @return Framebuffer width
     */
    public int getWidth() {
        return width;
    }

    /**
     * Get height in pixels.
     *
     * @return Framebuffer height
     */
    public int getHeight() {
        return height;
    }

    /**
     * Get attached color texture ID.
     *
     * @return Color texture ID, or 0 if none
     */
    public int getColorTexture() {
        return colorTexture;
    }

    /**
     * Get attached depth texture ID.
     *
     * @return Depth texture ID, or 0 if none
     */
    public int getDepthTexture() {
        return depthTexture;
    }

    /**
     * Check if framebuffer has color attachment.
     *
     * @return true if color attachment exists
     */
    public boolean hasColorAttachment() {
        return hasColorAttachment;
    }

    /**
     * Check if framebuffer has depth attachment.
     *
     * @return true if depth attachment exists
     */
    public boolean hasDepthAttachment() {
        return hasDepthAttachment;
    }
}
