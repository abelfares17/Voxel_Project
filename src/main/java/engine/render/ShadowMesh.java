package engine.render;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import static org.lwjgl.opengl.GL30.*;

/** Position-only shadow geometry. Owned by one chunk, GL-thread lifetime only. */
public final class ShadowMesh {
    private int vao, vbo, ebo;
    /** Submitted index count (same draw-count convention as indexed voxel meshes). */
    public final int count;
    private final long bytes;
    private ShadowMesh(int vao, int vbo, int ebo, int count, long bytes) {
        this.vao = vao; this.vbo = vbo; this.ebo = ebo; this.count = count; this.bytes = bytes;
    }

    /** Heap-only worker result. No GL objects or native memory are owned here. */
    public record Data(float[] vertices, int[] indices) {}

    public static Data generate(List<MeshBuilder.Quad> quads) { return index(positions(quads)); }

    private static Data index(float[] positions) {
        if(positions.length%9!=0)throw new IllegalArgumentException("Shadow triangles required");
        var vertices=new float[positions.length];var indices=new int[positions.length/3];
        var lookup=new HashMap<Integer,Integer>();int size=0;
        for(int i=0;i<indices.length;i++) {
            // Half-grid also supports diagnostic center fans; production vertices are integers.
            int x=Math.round(positions[i*3]*2),y=Math.round(positions[i*3+1]*2),z=Math.round(positions[i*3+2]*2);
            if(x<0||x>32||y<0||y>512||z<0||z>32
                    ||x*.5f!=positions[i*3]||y*.5f!=positions[i*3+1]||z*.5f!=positions[i*3+2])
                throw new IllegalArgumentException("Shadow position outside chunk half-grid");
            int key=(x*513+y)*33+z;
            Integer found=lookup.get(key);
            if(found==null) {
                found=size/3;lookup.put(key,found);
                vertices[size++]=positions[i*3];vertices[size++]=positions[i*3+1];vertices[size++]=positions[i*3+2];
            }
            indices[i]=found;
        }
        return new Data(Arrays.copyOf(vertices,size),indices);
    }

    public static float[] positions(List<MeshBuilder.Quad> quads) {
        var rectangles = ShadowMergeProbe.merge(quads);
        var out = new Positions(Math.multiplyExact(rectangles.size(), 18));
        for (var r : rectangles) {
            var polygon = new ArrayList<Point>();
            edge(polygon,r.u(),r.v(),1,0,r.width());
            edge(polygon,r.u()+r.width(),r.v(),0,1,r.height());
            edge(polygon,r.u()+r.width(),r.v()+r.height(),-1,0,r.width());
            edge(polygon,r.u(),r.v()+r.height(),0,-1,r.height());
            triangulate(out,r,polygon);
        }
        return Arrays.copyOf(out.data,out.size);
    }

    private record Point(int u, int v) {}
    private static int[] xyz(ShadowMergeProbe.Rectangle r,int u,int v) {
        int[] p=new int[3];p[r.axis()]=r.plane();p[(r.axis()+1)%3]=u;p[(r.axis()+2)%3]=v;return p;
    }
    private static void edge(List<Point> polygon,int u,int v,int du,int dv,int length) {
        // Preserve the original voxel edge sampling, including across chunk boundaries.
        // Splitting only at merged rectangle corners still produced depth cracks on M2.
        // No neighbor lookup is required: independently rebuilt chunks remain compatible.
        for(int i=0;i<length;i++)polygon.add(new Point(u+du*i,v+dv*i));
    }
    private static long cross(Point a,Point b,Point c) {
        return (long)(b.u-a.u)*(c.v-a.v)-(long)(b.v-a.v)*(c.u-a.u);
    }
    private static boolean onSegment(Point a,Point b,Point p) {
        return cross(a,b,p)==0 && p.u>=Math.min(a.u,b.u)&&p.u<=Math.max(a.u,b.u)
                && p.v>=Math.min(a.v,b.v)&&p.v<=Math.max(a.v,b.v);
    }
    private static void triangulate(Positions out,ShadowMergeProbe.Rectangle r,ArrayList<Point> polygon) {
        // Convex rectangle with collinear boundary vertices. Ear clipping gives n-2
        // non-degenerate triangles, unlike the more expensive center fan (n triangles).
        // For w*h source faces: 2*(w+h)-2 <= 2*w*h; never more triangles than source.
        while(polygon.size()>3) {
            boolean clipped=false;int n=polygon.size();
            for(int i=0;i<n;i++) {
                int prev=(i+n-1)%n,next=(i+1)%n;
                Point a=polygon.get(prev),b=polygon.get(i),c=polygon.get(next);
                if(cross(a,b,c)<=0)continue;
                boolean blocked=false;
                for(int j=0;j<n;j++)if(j!=prev&&j!=i&&j!=next&&onSegment(a,c,polygon.get(j))) {blocked=true;break;}
                if(blocked)continue;
                out.triangle(r,a,b,c);polygon.remove(i);clipped=true;break;
            }
            if(!clipped)throw new IllegalStateException("Cannot triangulate shadow rectangle");
        }
        if(cross(polygon.get(0),polygon.get(1),polygon.get(2))<=0)
            throw new IllegalStateException("Degenerate shadow triangle");
        out.triangle(r,polygon.get(0),polygon.get(1),polygon.get(2));
    }
    private static final class Positions {
        float[] data;int size;
        Positions(int capacity) {data=new float[capacity];}
        void vertex(ShadowMergeProbe.Rectangle r,Point point) {
            if(size+3>data.length)data=Arrays.copyOf(data,Math.max(size+3,data.length+data.length/2+18));
            int[] p=xyz(r,point.u,point.v);for(int value:p)data[size++]=value;
        }
        void triangle(ShadowMergeProbe.Rectangle r,Point a,Point b,Point c) {
            vertex(r,a);vertex(r,r.sign()>0?b:c);vertex(r,r.sign()>0?c:b);
        }
    }

    public static ShadowMesh upload(float[] positions) {
        return upload(index(positions));
    }

    public static ShadowMesh upload(Data data) {
        int vao = glGenVertexArrays(), vbo = glGenBuffers(), ebo = glGenBuffers();
        try {
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, data.vertices(), GL_STATIC_DRAW);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, data.indices(), GL_STATIC_DRAW);
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0L);
            glEnableVertexAttribArray(0);
            return new ShadowMesh(vao, vbo, ebo, data.indices().length,
                    (long)data.vertices().length*Float.BYTES+(long)data.indices().length*Integer.BYTES);
        } catch (RuntimeException | Error e) {
            glDeleteBuffers(ebo); glDeleteBuffers(vbo); glDeleteVertexArrays(vao); throw e;
        } finally { glBindVertexArray(0); glBindBuffer(GL_ARRAY_BUFFER, 0); }
    }
    public void draw() { glBindVertexArray(vao); glDrawElements(GL_TRIANGLES, count, GL_UNSIGNED_INT, 0L); }
    public long getMeshBytes() { return bytes; }
    public void cleanup() {
        if (vao != 0) glDeleteVertexArrays(vao);
        if (vbo != 0) glDeleteBuffers(vbo);
        if (ebo != 0) glDeleteBuffers(ebo);
        vao = vbo = ebo = 0;
    }
}
