import engine.render.*;
import engine.world.Chunk;
import java.util.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL30.*;

public class ShadowMeshTest {
    static MeshBuilder.Vertex vertex(int axis, int sign, int u, int v) {
        float[] p = new float[3], n = new float[3];
        p[axis] = 16; p[(axis+1)%3] = u; p[(axis+2)%3] = v; n[axis] = sign;
        return new MeshBuilder.Vertex(p[0],p[1],p[2],0,0,n[0],n[1],n[2],1);
    }
    public static void main(String[] args) {
        float[] last = null;
        for (int axis=0; axis<3; axis++) for (int sign : new int[]{-1,1}) {
            var faces = new ArrayList<MeshBuilder.Quad>();
            for (int u=0;u<16;u++) for(int v=0;v<16;v++)
                faces.add(new MeshBuilder.Quad(vertex(axis,sign,u,v), vertex(axis,sign,u+1,v),
                        vertex(axis,sign,u+1,v+1), vertex(axis,sign,u,v+1)));
            last = ShadowMesh.positions(faces);
            if(last.length != (2*(16+16)-2)*9) throw new AssertionError("conforming triangle budget");
            verify(last,axis,sign,256);
            var indexed=ShadowMesh.generate(faces);
            if(indexed.indices().length*3!=last.length)throw new AssertionError("index count");
            for(int i=0;i<indexed.indices().length;i++)for(int k=0;k<3;k++)
                if(indexed.vertices()[indexed.indices()[i]*3+k]!=last[i*3+k])
                    throw new AssertionError("indexing changed geometry or winding");
            faces.remove(100);
            if(ShadowMergeProbe.measure(faces).faces()!=255) throw new AssertionError("hole");
            verify(ShadowMesh.positions(faces),axis,sign,255);
            // Strips and random stepped surfaces exercise collinear ears / holes / winding.
            var random=new Random(1234);
            faces.clear();
            for(int u=0;u<16;u++)for(int v=0;v<16;v++)if(u==0||random.nextBoolean())
                faces.add(new MeshBuilder.Quad(vertex(axis,sign,u,v),vertex(axis,sign,u+1,v),
                        vertex(axis,sign,u+1,v+1),vertex(axis,sign,u,v+1)));
            verify(ShadowMesh.positions(faces),axis,sign,faces.size());
        }
        if(ShadowMesh.positions(List.of()).length!=0) throw new AssertionError("empty");
        var tallWall=new ArrayList<MeshBuilder.Quad>();
        for(int u=0;u<256;u++)for(int v=0;v<16;v++)
            tallWall.add(new MeshBuilder.Quad(vertex(0,1,u,v),vertex(0,1,u+1,v),
                    vertex(0,1,u+1,v+1),vertex(0,1,u,v+1)));
        verify(ShadowMesh.positions(tallWall),0,1,4096);
        if (!glfwInit()) throw new AssertionError("GLFW");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,2);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT,GLFW_TRUE);
        long window = glfwCreateWindow(32,32,"Shadow mesh test",0,0);
        if(window==0) throw new AssertionError("context");
        glfwMakeContextCurrent(window); org.lwjgl.opengl.GL.createCapabilities();
        try {
            var mesh = ShadowMesh.upload(last);
            var vf = ShadowMesh.class.getDeclaredField("vao"); vf.setAccessible(true);
            var bf = ShadowMesh.class.getDeclaredField("vbo"); bf.setAccessible(true);
            var ef = ShadowMesh.class.getDeclaredField("ebo"); ef.setAccessible(true);
            int vao=vf.getInt(mesh), vbo=bf.getInt(mesh), ebo=ef.getInt(mesh);
            if(!glIsVertexArray(vao)||!glIsBuffer(vbo)||!glIsBuffer(ebo)) throw new AssertionError("upload");
            if(mesh.getMeshBytes()<=0||mesh.getMeshBytes()>=last.length*4L)throw new AssertionError("indexed memory budget");
            mesh.cleanup(); mesh.cleanup();
            if(glIsVertexArray(vao)||glIsBuffer(vbo)||glIsBuffer(ebo)) throw new AssertionError("leak");
            var chunk = new Chunk(0,0);
            var first = ShadowMesh.upload(last);
            chunk.setShadowMesh(first);
            int firstVao = vf.getInt(first), firstVbo = bf.getInt(first), firstEbo=ef.getInt(first);
            var second = ShadowMesh.upload(last);
            chunk.setShadowMesh(second);
            if(glIsVertexArray(firstVao)||glIsBuffer(firstVbo)||glIsBuffer(firstEbo)) throw new AssertionError("replacement leak");
            int secondVao = vf.getInt(second), secondVbo = bf.getInt(second), secondEbo=ef.getInt(second);
            chunk.setMesh(0,0,0);
            if(chunk.getShadowMesh()!=null || glIsVertexArray(secondVao)||glIsBuffer(secondVbo)||glIsBuffer(secondEbo))
                throw new AssertionError("stale shadow after visual replacement");
            var third = ShadowMesh.upload(last);
            chunk.setShadowMesh(third);
            int thirdVao=vf.getInt(third), thirdVbo=bf.getInt(third), thirdEbo=ef.getInt(third);
            chunk.cleanup(); chunk.cleanup();
            if(glIsVertexArray(thirdVao)||glIsBuffer(thirdVbo)||glIsBuffer(thirdEbo)) throw new AssertionError("chunk cleanup leak");
            if(glGetError()!=GL_NO_ERROR) throw new AssertionError("GL error");
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        finally { glfwDestroyWindow(window); glfwTerminate(); }
        System.out.println("PASS: six orientations, fusion, hole, empty, GL upload/deletion/idempotence");
    }

    static void verify(float[] vertices,int axis,int sign,int faces) {
        if(vertices.length>faces*18)throw new AssertionError("more triangles than unmerged");
        double area=0;
        var edges=new HashMap<String,Integer>();
        for(int i=0;i<vertices.length;i+=9) {
            int u=(axis+1)%3,v=(axis+2)%3;
            double cross=(vertices[i+3+u]-vertices[i+u])*(vertices[i+6+v]-vertices[i+v])
                    -(vertices[i+3+v]-vertices[i+v])*(vertices[i+6+u]-vertices[i+u]);
            if(cross*sign<=0)throw new AssertionError("winding or degenerate triangle");
            area+=Math.abs(cross)*.5;
            for(int k=0;k<3;k++) {
                int a=i+k*3,b=i+((k+1)%3)*3;
                int au=(int)vertices[a+u],av=(int)vertices[a+v],bu=(int)vertices[b+u],bv=(int)vertices[b+v];
                String s=au+","+av,t=bu+","+bv;
                String key=s.compareTo(t)<0?s+":"+t:t+":"+s;
                edges.merge(key,1,Integer::sum);
            }
        }
        if(area!=faces)throw new AssertionError("area mismatch");
        for(var e:edges.entrySet()) {
            if(e.getValue()==2)continue;
            if(e.getValue()!=1)throw new AssertionError("non-manifold edge");
            String[] p=e.getKey().split("[:,]");
            int length=Math.abs(Integer.parseInt(p[0])-Integer.parseInt(p[2]))
                    +Math.abs(Integer.parseInt(p[1])-Integer.parseInt(p[3]));
            if(length!=1)throw new AssertionError("non-unit exposed edge / T junction");
        }
    }
}
