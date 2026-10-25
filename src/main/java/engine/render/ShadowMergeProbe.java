package engine.render;

import java.util.*;

/** CPU rectangle partition shared by the diagnostic and position-only shadow mesher. */
public final class ShadowMergeProbe {
    private ShadowMergeProbe() {}
    private record Plane(int axis, int sign, int coordinate) {}
    public record Result(int faces, int rectangles) {}
    public record Rectangle(int axis, int sign, int plane, int u, int v, int width, int height) {}

    public static Result measure(List<MeshBuilder.Quad> quads) {
        return new Result(quads.size(), merge(quads).size());
    }

    public static List<Rectangle> merge(List<MeshBuilder.Quad> quads) {
        List<Rectangle> output = new ArrayList<>();
        Map<Plane, TreeSet<Long>> planes = new HashMap<>();
        for (var q : quads) {
            var v = q.v0();
            int axis = v.nx() != 0 ? 0 : v.ny() != 0 ? 1 : 2;
            int sign = (int) (axis == 0 ? v.nx() : axis == 1 ? v.ny() : v.nz());
            int coordinate = (int) component(v, axis);
            int uAxis = (axis + 1) % 3, vAxis = (axis + 2) % 3;
            int u = Integer.MAX_VALUE, w = Integer.MAX_VALUE;
            for (var corner : List.of(q.v0(), q.v1(), q.v2(), q.v3())) {
                u = Math.min(u, (int) component(corner, uAxis));
                w = Math.min(w, (int) component(corner, vAxis));
            }
            if (!planes.computeIfAbsent(new Plane(axis, sign, coordinate), k -> new TreeSet<>()).add(key(u, w)))
                throw new IllegalArgumentException("Duplicate shadow face");
        }
        int covered = 0;
        for (var entry : planes.entrySet()) {
            var cells = entry.getValue();
            while (!cells.isEmpty()) {
                long first = cells.first();
                int u = (int) first, v = (int) (first >> 32);
                int width = 1, height = 1;
                while (cells.contains(key(u + width, v))) width++;
                boolean extend = true;
                while (extend) {
                    for (int x = 0; x < width; x++) {
                        if (!cells.contains(key(u + x, v + height))) { extend = false; break; }
                    }
                    if (extend) height++;
                }
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    if (!cells.remove(key(u + x, v + y))) throw new AssertionError("Coverage overlap");
                    covered++;
                }
                var p = entry.getKey();
                output.add(new Rectangle(p.axis(), p.sign(), p.coordinate(), u, v, width, height));
            }
        }
        if (covered != quads.size()) throw new AssertionError("Coverage lost");
        return output;
    }

    private static long key(int u, int v) { return ((long) v << 32) | (u & 0xffffffffL); }
    private static float component(MeshBuilder.Vertex v, int axis) {
        return axis == 0 ? v.x() : axis == 1 ? v.y() : v.z();
    }
}
