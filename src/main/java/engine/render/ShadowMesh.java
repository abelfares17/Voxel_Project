package engine.render;

import java.util.List;
import static org.lwjgl.opengl.GL30.*;

/** Position-only shadow geometry. Owned by one chunk, GL-thread lifetime only. */
public final class ShadowMesh {
    private int vao, vbo;
    public final int count;
    private ShadowMesh(int vao, int vbo, int count) { this.vao = vao; this.vbo = vbo; this.count = count; }

    public static float[] positions(List<MeshBuilder.Quad> quads) {
        var rectangles = ShadowMergeProbe.merge(quads);
        float[] data = new float[Math.multiplyExact(rectangles.size(), 18)];
        int cursor = 0;
        for (var r : rectangles) {
            int[] order = r.sign() > 0 ? new int[]{0,1,2,0,2,3} : new int[]{0,2,1,0,3,2};
            for (int corner : order) {
                float[] p = new float[3];
                p[r.axis()] = r.plane();
                p[(r.axis()+1)%3] = r.u() + ((corner == 1 || corner == 2) ? r.width() : 0);
                p[(r.axis()+2)%3] = r.v() + (corner >= 2 ? r.height() : 0);
                for (float value : p) data[cursor++] = value;
            }
        }
        return data;
    }

    public static ShadowMesh upload(float[] positions) {
        int vao = glGenVertexArrays(), vbo = glGenBuffers();
        try {
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, positions, GL_STATIC_DRAW);
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0L);
            glEnableVertexAttribArray(0);
            return new ShadowMesh(vao, vbo, positions.length / 3);
        } catch (RuntimeException | Error e) {
            glDeleteBuffers(vbo); glDeleteVertexArrays(vao); throw e;
        } finally { glBindVertexArray(0); glBindBuffer(GL_ARRAY_BUFFER, 0); }
    }
    public void draw() { glBindVertexArray(vao); glDrawArrays(GL_TRIANGLES, 0, count); }
    public void cleanup() {
        if (vao != 0) glDeleteVertexArrays(vao);
        if (vbo != 0) glDeleteBuffers(vbo);
        vao = vbo = 0;
    }
}
