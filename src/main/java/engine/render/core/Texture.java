package engine.render.core;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL30.glGenerateMipmap;

/**
 * Abstraction of an OpenGL texture.
 * Simplifies texture creation, binding, and parameter configuration.
 */
public class Texture {

    private int textureId;
    private int width;
    private int height;
    private int target;
    private boolean initialized;

    /**
     * Create a new 2D texture.
     */
    public Texture() {
        this(GL_TEXTURE_2D);
    }

    /**
     * Create a new texture with specified target.
     *
     * @param target OpenGL texture target (GL_TEXTURE_2D, GL_TEXTURE_CUBE_MAP, etc.)
     */
    public Texture(int target) {
        this.target = target;
        this.textureId = glGenTextures();
        this.initialized = false;
    }

    /**
     * Bind this texture to its target.
     */
    public void bind() {
        glBindTexture(target, textureId);
    }

    /**
     * Bind this texture to a specific texture unit.
     *
     * @param unit Texture unit (0-31)
     */
    public void bind(int unit) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(target, textureId);
    }

    /**
     * Unbind texture (bind texture 0 to this target).
     */
    public void unbind() {
        glBindTexture(target, 0);
    }

    /**
     * Upload texture data (2D texture).
     *
     * @param width          Width in pixels
     * @param height         Height in pixels
     * @param internalFormat Internal format (e.g., GL_RGBA8)
     * @param format         Pixel format (e.g., GL_RGBA)
     * @param type           Data type (e.g., GL_UNSIGNED_BYTE)
     * @param data           Pixel data (can be null for empty texture)
     */
    public void uploadData(int width, int height, int internalFormat, int format, int type, java.nio.ByteBuffer data) {
        this.width = width;
        this.height = height;

        bind();
        glTexImage2D(target, 0, internalFormat, width, height, 0, format, type, data);
        this.initialized = true;
        unbind();
    }

    /**
     * Set texture filtering mode.
     *
     * @param minFilter Minification filter (GL_NEAREST, GL_LINEAR, etc.)
     * @param magFilter Magnification filter (GL_NEAREST, GL_LINEAR)
     */
    public void setFiltering(int minFilter, int magFilter) {
        bind();
        glTexParameteri(target, GL_TEXTURE_MIN_FILTER, minFilter);
        glTexParameteri(target, GL_TEXTURE_MAG_FILTER, magFilter);
        unbind();
    }

    /**
     * Set texture wrapping mode.
     *
     * @param wrapS S-axis wrapping (GL_REPEAT, GL_CLAMP_TO_EDGE, etc.)
     * @param wrapT T-axis wrapping
     */
    public void setWrapping(int wrapS, int wrapT) {
        bind();
        glTexParameteri(target, GL_TEXTURE_WRAP_S, wrapS);
        glTexParameteri(target, GL_TEXTURE_WRAP_T, wrapT);
        unbind();
    }

    /**
     * Generate mipmaps for this texture.
     */
    public void generateMipmaps() {
        bind();
        glGenerateMipmap(target);
        unbind();
    }

    /**
     * Set a texture parameter (generic).
     *
     * @param pname Parameter name (e.g., GL_TEXTURE_MIN_FILTER)
     * @param param Parameter value
     */
    public void setParameter(int pname, int param) {
        bind();
        glTexParameteri(target, pname, param);
        unbind();
    }

    /**
     * Clean up texture resources.
     */
    public void cleanup() {
        if (textureId != 0) {
            glDeleteTextures(textureId);
            textureId = 0;
        }
        initialized = false;
    }

    /**
     * Get texture ID.
     *
     * @return OpenGL texture ID
     */
    public int getId() {
        return textureId;
    }

    /**
     * Get texture width.
     *
     * @return Width in pixels
     */
    public int getWidth() {
        return width;
    }

    /**
     * Get texture height.
     *
     * @return Height in pixels
     */
    public int getHeight() {
        return height;
    }

    /**
     * Get texture target.
     *
     * @return OpenGL texture target
     */
    public int getTarget() {
        return target;
    }

    /**
     * Check if texture has been initialized with data.
     *
     * @return true if texture data has been uploaded
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Create a texture from an existing OpenGL texture ID.
     * Useful for wrapping externally created textures.
     *
     * @param textureId Existing texture ID
     * @param width     Texture width
     * @param height    Texture height
     * @return Texture wrapper
     */
    public static Texture fromId(int textureId, int width, int height) {
        Texture texture = new Texture();
        texture.textureId = textureId;
        texture.width = width;
        texture.height = height;
        texture.initialized = true;
        return texture;
    }
}
