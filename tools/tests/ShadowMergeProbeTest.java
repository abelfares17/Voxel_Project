import engine.render.MeshBuilder;
import engine.render.ShadowMergeProbe;
import java.util.ArrayList;

public class ShadowMergeProbeTest {
    static MeshBuilder.Vertex v(int x, int z) {
        return new MeshBuilder.Vertex(x, 0, z, 0, 0, 0, 1, 0, 1);
    }
    public static void main(String[] args) {
        var faces = new ArrayList<MeshBuilder.Quad>();
        if (ShadowMergeProbe.measure(faces).rectangles() != 0) throw new AssertionError();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
            faces.add(new MeshBuilder.Quad(v(x,z), v(x+1,z), v(x+1,z+1), v(x,z+1)));
        var flat = ShadowMergeProbe.measure(faces);
        if (flat.faces() != 256 || flat.rectangles() != 1) throw new AssertionError(flat);
        faces.remove(100);
        var hole = ShadowMergeProbe.measure(faces);
        if (hole.faces() != 255 || hole.rectangles() <= 1) throw new AssertionError(hole);
        System.out.println("ShadowMergeProbeTest PASS: empty, flat plane, hole and coverage");
    }
}
