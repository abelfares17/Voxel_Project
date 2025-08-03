package engine.render;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL30.glGenerateMipmap;
import static org.lwjgl.stb.STBImage.*;
import static org.lwjgl.opengl.GL11.*;

public class TextureLoader {
    public static int loadTextureFromClasspath(String resourcePath) {
        stbi_set_flip_vertically_on_load(true);
        ByteBuffer image;
        int width, height;
        try (InputStream in = TextureLoader.class
                .getClassLoader()
                .getResourceAsStream(resourcePath);
             MemoryStack stack = MemoryStack.stackPush())
        {
            if (in == null) throw new RuntimeException("Ressource introuvable : " + resourcePath);

            // lit tout le flux dans un ByteBuffer (taille initiale arbitraire)
            byte[] bytes = in.readAllBytes();
            ByteBuffer buf = ByteBuffer
                    .allocateDirect(bytes.length)
                    .put(bytes);
            buf.flip();

            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);

            stbi_set_flip_vertically_on_load(true);
            image = stbi_load_from_memory(buf, w, h, channels, 4);
            if (image == null) {
                throw new RuntimeException("Erreur chargement image : " + stbi_failure_reason());
            }
            width = w.get(0);
            height = h.get(0);
            System.out.println("Chargement texture: " + resourcePath + " -> " + width + "x" + height);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        int texId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height,
                0, GL_RGBA, GL_UNSIGNED_BYTE, image);
        //glGenerateMipmap(GL_TEXTURE_2D); //Pas possible à cause de OpenGL3.0
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        stbi_image_free(image);
        glBindTexture(GL_TEXTURE_2D, 0);
        return texId;
    }
}