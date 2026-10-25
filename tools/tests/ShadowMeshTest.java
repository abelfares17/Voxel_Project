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
            if(last.length != 18) throw new AssertionError("not merged");
            float[] a = new float[3], b = new float[3];
            for(int j=0;j<3;j++){a[j]=last[3+j]-last[j];b[j]=last[6+j]-last[j];}
            float cross = a[(axis+1)%3]*b[(axis+2)%3]-a[(axis+2)%3]*b[(axis+1)%3];
            if(cross*sign<=0) throw new AssertionError("winding");
            faces.remove(100);
            if(ShadowMergeProbe.measure(faces).faces()!=255) throw new AssertionError("hole");
        }
        if(ShadowMesh.positions(List.of()).length!=0) throw new AssertionError("empty");
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
            int vao=vf.getInt(mesh), vbo=bf.getInt(mesh);
            if(!glIsVertexArray(vao)||!glIsBuffer(vbo)) throw new AssertionError("upload");
            mesh.cleanup(); mesh.cleanup();
            if(glIsVertexArray(vao)||glIsBuffer(vbo)) throw new AssertionError("leak");
            var chunk = new Chunk(0,0);
            var first = ShadowMesh.upload(last);
            chunk.setShadowMesh(first);
            int firstVao = vf.getInt(first), firstVbo = bf.getInt(first);
            var second = ShadowMesh.upload(last);
            chunk.setShadowMesh(second);
            if(glIsVertexArray(firstVao)||glIsBuffer(firstVbo)) throw new AssertionError("replacement leak");
            int secondVao = vf.getInt(second), secondVbo = bf.getInt(second);
            chunk.setMesh(0,0,0);
            if(chunk.getShadowMesh()!=null || glIsVertexArray(secondVao)||glIsBuffer(secondVbo))
                throw new AssertionError("stale shadow after visual replacement");
            var third = ShadowMesh.upload(last);
            chunk.setShadowMesh(third);
            int thirdVao=vf.getInt(third), thirdVbo=bf.getInt(third);
            chunk.cleanup(); chunk.cleanup();
            if(glIsVertexArray(thirdVao)||glIsBuffer(thirdVbo)) throw new AssertionError("chunk cleanup leak");
            if(glGetError()!=GL_NO_ERROR) throw new AssertionError("GL error");
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        finally { glfwDestroyWindow(window); glfwTerminate(); }
        System.out.println("PASS: six orientations, fusion, hole, empty, GL upload/deletion/idempotence");
    }
}
