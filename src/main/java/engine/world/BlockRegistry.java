package engine.world;

import engine.render.TextureCoords;
import java.util.HashMap;
import java.util.Map;

/**
 * Enregistre les types de blocs (ID → Block avec textures, nom, solidité).
 */
public class BlockRegistry {
    private static final Map<Short, Block> blocks = new HashMap<>();
    private static final int ATLAS_SIZE = 64;

    public static void registerAll() {
        // Bloc 0 = Air (non solide)
        register(0, "air", false,
            tex(1, 1), tex(1, 1), tex(1, 1)
        );

        // Bloc 1 = Herbe
        System.out.println("Herbe");
        register(1, "grass", true,
            tex(22, 6), // top
            tex(18, 11), // bottom
            tex(21, 7)  // side
        );

        // Bloc 2 = Terre
        System.out.println("Terre");
        register(2, "dirt", true,
            tex(18, 11),
            tex(18, 11),
            tex(18, 11)
        );

        // Bloc 3 = Pierre
        System.out.println("Pierre");
        register(3, "stone", true,
            tex(31, 19),
            tex(31, 19),
            tex(31, 19)
        );

        // Bloc 4 = Bedrock
        //TODO
        register(4, "bedrock", true,
            tex(13, 8),
            tex(13, 8),
            tex(13, 8)
        );
        /*
        // Bloc 5 = Bois (tronc)
        //TODO
        register(5, "log", true,
            tex(6, 3),
            tex(6, 3),
            tex(5, 3)
        );

        // Bloc 6 = Planches
        //TODO
        register(6, "planks", true,
            tex(4, 3), tex(4, 3), tex(4, 3)
        );

        // Bloc 7 = Feuilles
        //TODO
        register(7, "leaves", false,
            tex(7, 3), tex(7, 3), tex(7, 3)
        );

        // Bloc 8 = Verre
        //TODO
        register(8, "glass", false,
            tex(25, 3), tex(25, 3), tex(25, 3)
        );

        // Bloc 9 = Pierre taillée
        //TODO
        register(9, "cobblestone", true,
            tex(12, 4), tex(12, 4), tex(12, 4)
        );
         */
    }

    private static void register(int id, String name, boolean solid,
                                 TextureCoords top, TextureCoords bottom, TextureCoords side) {
        Block block = new Block((short) id, name, solid, top, bottom, side);
        blocks.put((short) id, block);
    }

    public static Block getBlockByName(String name) {
        for (Block block : blocks.values()) {
            if (block.getName().equalsIgnoreCase(name)) {
                return block;
            }
        }
        return blocks.get((short) 0); // Retourne le bloc "air" par défaut
    }

    public static Block getBlock(short id) {
        return blocks.getOrDefault(id, blocks.get((short) 0)); // Air par défaut
    }

    private static TextureCoords tex(int col, int row) {
        //return new TextureCoords(0f, 0f, 1f, 1f);
        return TextureCoords.fromGrid(col, row, ATLAS_SIZE);
    }
}