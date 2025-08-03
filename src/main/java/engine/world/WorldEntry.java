package engine.world;
// Classe WorldEntry : contient les infos d'un monde existant
public class WorldEntry {
    public final String name;
    public final String folderName;
    public final long lastModified;
    public boolean selected;

    public WorldEntry(String name, String folderName, long lastModified) {
        this.name = name;
        this.folderName = folderName;
        this.lastModified = lastModified;
        this.selected = false;
    }
}
