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
import static org.lwjgl.opengl.GL30.*;

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
            image = stbi_load_from_memory(buf, w, h, channels, 4); // Force RGBA
            if (image == null) {
                throw new RuntimeException("Erreur chargement image : " + stbi_failure_reason());
            }
            width = w.get(0);
            height = h.get(0);
            int originalChannels = channels.get(0);
            System.out.println("Chargement texture: " + resourcePath + " -> " + width + "x" + height + " (orig: " + originalChannels + " channels, forced to 4)");
            int texId = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, texId);

            // We forced 4 channels (RGBA) in stbi_load_from_memory, so always use RGBA format
            int internalFormat = GL_SRGB8_ALPHA8;
            int format = GL_RGBA;

            // Upload texture data (single call, not duplicated)
            glTexImage2D(GL_TEXTURE_2D, 0, internalFormat,
                    width, height, 0,
                    format, GL_UNSIGNED_BYTE, image);

            // Overlay individual 16x16 block face textures from textures/blocks/*.png when modern pack is active
            if ("textures/atlas.png".equals(resourcePath)
                    && !"legacy".equalsIgnoreCase(engine.core.config.RenderConfig.TEXTURE_PACK)) {
                overlayBlockFaceTextures(width, height, format);
            }

            //glGenerateMipmap(GL_TEXTURE_2D); //Pas possible à cause de OpenGL3.0
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            stbi_image_free(image);
            glBindTexture(GL_TEXTURE_2D, 0);
            return texId;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private record BlockTileOverride(String baseName, int col, int row) {}

    private static final BlockTileOverride[] MODERN_BLOCK_TILES = {
        new BlockTileOverride("textures/blocks/grass_top",   22, 6),
        new BlockTileOverride("textures/blocks/grass_side",  21, 7),
        new BlockTileOverride("textures/blocks/dirt",        18, 11),
        new BlockTileOverride("textures/blocks/stone",       31, 19),
        new BlockTileOverride("textures/blocks/bedrock",     13, 8),
        new BlockTileOverride("textures/blocks/log_side",     5, 3),
        new BlockTileOverride("textures/blocks/log_top",     10, 3),
        new BlockTileOverride("textures/blocks/leaves",      15, 3),
        new BlockTileOverride("textures/blocks/glass",       25, 3),
        new BlockTileOverride("textures/blocks/planks",       4, 4),
        new BlockTileOverride("textures/blocks/cobblestone", 12, 4),
        new BlockTileOverride("textures/blocks/sand",        18, 4),
        new BlockTileOverride("textures/blocks/bricks",      24, 4)
    };

    private static void overlayBlockFaceTextures(int atlasWidth, int atlasHeight, int format) {
        final int gridSize = 64;
        final int tileSize = 16;
        if (atlasWidth < gridSize * tileSize || atlasHeight < gridSize * tileSize) {
            return;
        }
        int loadedCount = 0;
        for (BlockTileOverride tile : MODERN_BLOCK_TILES) {
            for (int variant = 0; variant < 4; variant++) {
                String variantPath = tile.baseName() + "_" + variant + ".png";
                String fallbackPath = tile.baseName() + ".png";
                InputStream in = TextureLoader.class.getClassLoader().getResourceAsStream(variantPath);
                if (in == null) {
                    in = TextureLoader.class.getClassLoader().getResourceAsStream(fallbackPath);
                }
                if (in == null) continue;
                try (InputStream stream = in; MemoryStack stack = MemoryStack.stackPush()) {
                    byte[] bytes = stream.readAllBytes();
                    ByteBuffer buf = ByteBuffer.allocateDirect(bytes.length).put(bytes);
                    buf.flip();

                    IntBuffer w = stack.mallocInt(1);
                    IntBuffer h = stack.mallocInt(1);
                    IntBuffer channels = stack.mallocInt(1);

                    stbi_set_flip_vertically_on_load(true);
                    ByteBuffer tileImg = stbi_load_from_memory(buf, w, h, channels, 4);
                    if (tileImg == null) continue;
                    try {
                        if (w.get(0) == tileSize && h.get(0) == tileSize) {
                            int targetCol = tile.col() + variant;
                            int xOffset = (targetCol - 1) * tileSize;
                            int yOffset = (gridSize - tile.row()) * tileSize;
                            glTexSubImage2D(GL_TEXTURE_2D, 0, xOffset, yOffset,
                                    tileSize, tileSize, format, GL_UNSIGNED_BYTE, tileImg);
                            loadedCount++;
                        }
                    } finally {
                        stbi_image_free(tileImg);
                    }
                } catch (Exception e) {
                    System.err.println("[TextureLoader] Failed to load block tile " + variantPath + ": " + e.getMessage());
                }
            }
        }
        System.out.println("[TextureLoader] Texture pack 'modern' active: overlaid "
                + loadedCount + " harmonized 16x16 block variant textures from textures/blocks/");
    }
}