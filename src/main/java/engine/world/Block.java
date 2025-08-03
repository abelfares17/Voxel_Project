package engine.world;

import engine.render.TextureCoords;

/**
 * Représente un type de bloc. Ne contient pas d’état de position.
 */
public class Block {
    private final short id;
    private final String name;
    private final boolean solid;

    private final TextureCoords topTexture;
    private final TextureCoords bottomTexture;
    private final TextureCoords sideTexture;

    public Block(short id, String name, boolean solid,
                 TextureCoords topTexture,
                 TextureCoords bottomTexture,
                 TextureCoords sideTexture) {
        this.id = id;
        this.name = name;
        this.solid = solid;
        this.topTexture = topTexture;
        this.bottomTexture = bottomTexture;
        this.sideTexture = sideTexture;
    }

    public short getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isSolid() {
        return solid;
    }

    public TextureCoords getTopTexture() {
        return topTexture;
    }

    public TextureCoords getBottomTexture() {
        return bottomTexture;
    }

    public TextureCoords getSideTexture() {
        return sideTexture;
    }
}