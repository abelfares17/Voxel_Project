package engine.world;

import java.util.Objects;

/**
 * Représente les coordonnées d'un chunk dans la grille du monde.
 * Utilisé comme clé dans le HashMap de World.
 */
public record ChunkPos(int x, int z) {
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChunkPos that)) return false;
        return x == that.x && z == that.z;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, z);
    }

    @Override
    public String toString() {
        return "ChunkPos[" + x + ", " + z + "]";
    }
}