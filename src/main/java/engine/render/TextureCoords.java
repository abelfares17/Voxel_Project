package engine.render;

/**
 * Représente les coordonnées de texture (UV) dans un atlas.
 */
public class TextureCoords {
    private final float uMin, vMin;
    private final float uMax, vMax;
    public static int tileSize = 16;    // pixels

    public TextureCoords(float uMin, float vMin, float uMax, float vMax) {
        this.uMin = uMin;
        this.vMin = vMin;
        this.uMax = uMax;
        this.vMax = vMax;
    }

    public float getUMin() { return uMin; }
    public float getVMin() { return vMin; }
    public float getUMax() { return uMax; }
    public float getVMax() { return vMax; }

    /**
     * Fabrique une TextureCoords à partir de la position dans la grille.
     * @param col numéro de colonne (à partir de 1)
     * @param row numéro de ligne (à partir de 1)
     * @param atlasSize nombre de cases par ligne/colonne (ex: 32 pour une grille 32x32)
     * @return TextureCoords avec coordonnées UV
     */
    public static TextureCoords fromGrid(int col, int row, int atlasSize) {
        float tileSize = 1.0f / atlasSize;
        float uMin = (col - 1) * tileSize;
        float uMax = col * tileSize;
        // Inversion de V pour OpenGL
        float vMax = 1.0f - (row - 1) * tileSize;
        float vMin = vMax - tileSize;
        return new TextureCoords(uMin, vMin, uMax, vMax);
    }

    @Override
    public String toString() {
        return String.format("UV[(%.3f, %.3f) -> (%.3f, %.3f)]", uMin, vMin, uMax, vMax);
    }
}