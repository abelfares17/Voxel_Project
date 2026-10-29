import engine.core.config.RenderConfig;
import engine.render.*;
import engine.render.camera.PerspectiveCamera;
import engine.render.shadow.*;
import engine.time.CelestialState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL30.*;

/** Read-only World_10 snapshot; production merge, CSM and depth shaders, hidden GL window.
 * Diagnostic geometry contains all exposed voxel faces, without materials/AO or streaming.
 * Run from repository root with compiled production classes and LWJGL on classpath.
 */
public class ShadowMergeRasterDiagnostic {
    static final int SIZE = RenderConfig.CSM_SHADOW_MAP_SIZE;
    static final Map<String, short[]> blocks = new HashMap<>();
    record Geometry(int x, int z, ShadowMesh unit, ShadowMesh merged, ShadowMesh fan, ShadowMesh cornerFan) {}
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static short[] load(int cx, int cz) throws Exception {
        String key = cx+","+cz;
        if (!blocks.containsKey(key)) {
            byte[] bytes = Files.readAllBytes(Path.of("saves/World_10/chunks/chunk_"+cx+"_"+cz+".dat"));
            check(bytes[0]==0x56 && bytes[1]==0x58 && bytes[2]==1, "chunk format");
            try (var gzip = new GZIPInputStream(new ByteArrayInputStream(bytes,12,bytes.length-12))) {
                byte[] raw = gzip.readAllBytes(); check(raw.length==131072,"chunk length");
                short[] values=new short[65536]; ByteBuffer.wrap(raw).asShortBuffer().get(values);
                blocks.put(key,values);
            }
        }
        return blocks.get(key);
    }
    static boolean solid(int x,int y,int z) throws Exception {
        return y>=0 && y<256 && load(Math.floorDiv(x,16),Math.floorDiv(z,16))[(Math.floorMod(x,16)*256+y)*16+Math.floorMod(z,16)]!=0;
    }
    static MeshBuilder.Vertex vertex(int axis,int sign,int plane,float u,float v) {
        float[] p=new float[3],n=new float[3]; p[axis]=plane;p[(axis+1)%3]=u;p[(axis+2)%3]=v;n[axis]=sign;
        return new MeshBuilder.Vertex(p[0],p[1],p[2],0,0,n[0],n[1],n[2],1);
    }
    static List<MeshBuilder.Quad> faces(int cx,int cz) throws Exception {
        var result=new ArrayList<MeshBuilder.Quad>();
        for(int x=0;x<16;x++) for(int y=0;y<256;y++) for(int z=0;z<16;z++) {
            if(!solid(cx*16+x,y,cz*16+z))continue;
            int[] p={x,y,z};
            for(int axis=0;axis<3;axis++) for(int sign:new int[]{-1,1}) {
                int[] q={cx*16+x,y,cz*16+z}; q[axis]+=sign;
                if(solid(q[0],q[1],q[2]))continue;
                int plane=p[axis]+(sign>0?1:0),u=p[(axis+1)%3],v=p[(axis+2)%3];
                var a=vertex(axis,sign,plane,u,v);var b=vertex(axis,sign,plane,u+1,v);
                var c=vertex(axis,sign,plane,u+1,v+1);var d=vertex(axis,sign,plane,u,v+1);
                result.add(sign>0?new MeshBuilder.Quad(a,b,c,d):new MeshBuilder.Quad(a,d,c,b));
            }
        }
        return result;
    }
    static void append(List<Float> out,MeshBuilder.Vertex p) { out.add(p.x());out.add(p.y());out.add(p.z()); }
    static float[] array(List<Float> values) { float[] a=new float[values.size()];for(int i=0;i<a.length;i++)a[i]=values.get(i);return a; }
    static float[] units(List<MeshBuilder.Quad> faces) {
        var out=new ArrayList<Float>();
        for(var q:faces)for(var v:List.of(q.v0(),q.v1(),q.v2(),q.v0(),q.v2(),q.v3()))append(out,v);
        return array(out);
    }
    static String cell(int a,int s,int p,int u,int v) {return a+":"+s+":"+p+":"+u+":"+v;}
    static float component(MeshBuilder.Vertex v,int a) {return a==0?v.x():a==1?v.y():v.z();}
    static void verify(List<MeshBuilder.Quad> faces,List<ShadowMergeProbe.Rectangle> rectangles) {
        var expected=new HashSet<String>();
        for(var q:faces) {
            var p=q.v0();int a=p.nx()!=0?0:p.ny()!=0?1:2,s=(int)(a==0?p.nx():a==1?p.ny():p.nz());
            int u=999,v=999;
            for(var c:List.of(q.v0(),q.v1(),q.v2(),q.v3())) {u=Math.min(u,(int)component(c,(a+1)%3));v=Math.min(v,(int)component(c,(a+2)%3));}
            check(expected.add(cell(a,s,(int)component(p,a),u,v)),"duplicate input");
        }
        for(var r:rectangles)for(int u=0;u<r.width();u++)for(int v=0;v<r.height();v++)
            check(expected.remove(cell(r.axis(),r.sign(),r.plane(),r.u()+u,r.v()+v)),"extra/overlapping face");
        check(expected.isEmpty(),"missing faces");
    }
    // Experimental triangulation ONLY: unit-length perimeter edges, common integer endpoints.
    static float[] fans(List<ShadowMergeProbe.Rectangle> rectangles, boolean splitEdges) {
        var out=new ArrayList<Float>();
        for(var r:rectangles) {
            var edge=new ArrayList<MeshBuilder.Vertex>();
            for(int i=0;i<r.width();i+=splitEdges?1:r.width())edge.add(vertex(r.axis(),r.sign(),r.plane(),r.u()+i,r.v()));
            for(int i=0;i<r.height();i+=splitEdges?1:r.height())edge.add(vertex(r.axis(),r.sign(),r.plane(),r.u()+r.width(),r.v()+i));
            for(int i=0;i<r.width();i+=splitEdges?1:r.width())edge.add(vertex(r.axis(),r.sign(),r.plane(),r.u()+r.width()-i,r.v()+r.height()));
            for(int i=0;i<r.height();i+=splitEdges?1:r.height())edge.add(vertex(r.axis(),r.sign(),r.plane(),r.u(),r.v()+r.height()-i));
            var center=vertex(r.axis(),r.sign(),r.plane(),r.u()+r.width()*.5f,r.v()+r.height()*.5f);
            for(int i=0;i<edge.size();i++) {
                append(out,center);append(out,edge.get(r.sign()>0?i:(i+1)%edge.size()));
                append(out,edge.get(r.sign()>0?(i+1)%edge.size():i));
            }
        }
        return array(out);
    }
    static int compile(int type,String path)throws Exception {
        int s=glCreateShader(type);glShaderSource(s,Files.readString(Path.of(path)));glCompileShader(s);
        check(glGetShaderi(s,GL_COMPILE_STATUS)==GL_TRUE,glGetShaderInfoLog(s));return s;
    }
    static float[] render(int program,Matrix4f light,List<Geometry> geometry,int variant,boolean cull) {
        if(cull)glEnable(GL_CULL_FACE);else glDisable(GL_CULL_FACE);
        glClear(GL_DEPTH_BUFFER_BIT);
        try(var stack=MemoryStack.stackPush()) {
            var matrix=stack.mallocFloat(16);
            glUniformMatrix4fv(glGetUniformLocation(program,"lightSpaceMatrix"),false,light.get(matrix));
            for(var g:geometry) {
                glUniformMatrix4fv(glGetUniformLocation(program,"modelMatrix"),false,new Matrix4f().translation(g.x*16,0,g.z*16).get(matrix));
                (variant==0?g.unit:variant==1?g.merged:variant==2?g.fan:g.cornerFan).draw();
            }
        }
        float[] result=new float[SIZE*SIZE];glReadPixels(0,0,SIZE,SIZE,GL_DEPTH_COMPONENT,GL_FLOAT,result);
        check(glGetError()==GL_NO_ERROR,"GL error");return result;
    }
    static void compare(Path output,PrintWriter csv,String name,float[] ref,float[] test)throws Exception {
        int changed=0,deeper=0,clear=0,interior=0;float max=0;var image=new BufferedImage(SIZE,SIZE,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<SIZE;y++)for(int x=0;x<SIZE;x++) {
            int i=y*SIZE+x;float d=test[i]-ref[i];max=Math.max(max,Math.abs(d));
            if(Math.abs(d)>1e-5)changed++;
            if(d>.001) {
                deeper++;if(test[i]==1)clear++;
                boolean inside=x>1&&y>1&&x<SIZE-2&&y<SIZE-2;
                if(inside)for(int dy=-2;dy<=2;dy++)for(int dx=-2;dx<=2;dx++)if(ref[i+dy*SIZE+dx]>=1)inside=false;
                if(inside)interior++;
            }
            image.setRGB(x,SIZE-1-y,d>.001?(test[i]==1?0xff0000:0x00ff00):d<-.001?0x0000ff:0);
        }
        csv.printf(Locale.ROOT,"%s,%d,%d,%d,%d,%.9f%n",name,changed,deeper,clear,interior,max);csv.flush();
        System.out.printf(Locale.ROOT,"%s changed=%d deeper=%d clear=%d interior=%d max=%.9f%n",name,changed,deeper,clear,interior,max);
        ImageIO.write(image,"png",output.resolve(name+".png").toFile());
    }
    public static void main(String[] args)throws Exception {
        double hour=Double.parseDouble(System.getProperty("diagnostic.hour","17.3166666667"));
        Path output=Files.createTempDirectory(Path.of("logs"),"shadow_raster_");System.out.println("OUTPUT "+output);
        check(glfwInit(),"GLFW");glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_FOCUSED,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,2);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT,GLFW_TRUE);
        long window=glfwCreateWindow(64,64,"Shadow diagnostic",0,0);check(window!=0,"context");
        glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(glGetString(GL_RENDERER)+" / "+glGetString(GL_VERSION));
        var geometry=new ArrayList<Geometry>();int program=0,vs=0,fs=0,fbo=0,texture=0;
        try(var csv=new PrintWriter(Files.newBufferedWriter(output.resolve("results.csv")))) {
            csv.println("case,changed_gt_1e-5,deeper_gt_0.001,clear_holes,interior_deeper,max_abs_depth_delta");
            vs=compile(GL_VERTEX_SHADER,"src/main/resources/shaders/depth_vertex_shader.glsl");fs=compile(GL_FRAGMENT_SHADER,"src/main/resources/shaders/depth_fragment_shader.glsl");
            program=glCreateProgram();glAttachShader(program,vs);glAttachShader(program,fs);glBindAttribLocation(program,0,"aPos");glLinkProgram(program);
            check(glGetProgrami(program,GL_LINK_STATUS)==GL_TRUE,glGetProgramInfoLog(program));glUseProgram(program);
            long faceCount=0,rectCount=0,fanVertices=0,mergedVertices=0;
            for(int cx=40;cx<=62;cx++)for(int cz=34;cz<=46;cz++) {
                var faces=faces(cx,cz);var rects=ShadowMergeProbe.merge(faces);verify(faces,rects);faceCount+=faces.size();rectCount+=rects.size();
                float[] fan=fans(rects,true);fanVertices+=fan.length/3;
                float[] merged=ShadowMesh.positions(faces);mergedVertices+=merged.length/3;
                check(merged.length<=faces.size()*18,"corrected mesh exceeds source triangle budget");
                geometry.add(new Geometry(cx,cz,ShadowMesh.upload(units(faces)),ShadowMesh.upload(merged),ShadowMesh.upload(fan),ShadowMesh.upload(fans(rects,false))));
            }
            System.out.println("CPU exact oriented coverage PASS faces="+faceCount+" rectangles="+rectCount);
            String metadata="renderer="+glGetString(GL_RENDERER)+"\nversion="+glGetString(GL_VERSION)+"\nshadowSize="+SIZE
                    +"\nhour="+hour+"\nchunks=40..62,34..46 (plus read-only neighbors)\nfaces="+faceCount+"\nrectangles="+rectCount
                    +"\nunitVertices="+(faceCount*6)+"\nmergedVertices="+mergedVertices+"\nfanVertices="+fanVertices+"\ncornerFanVertices="+(rectCount*12)+"\n";
            Files.writeString(output.resolve("metadata.txt"),metadata);System.out.print(metadata);
            texture=glGenTextures();glBindTexture(GL_TEXTURE_2D,texture);glTexImage2D(GL_TEXTURE_2D,0,GL_DEPTH_COMPONENT32F,SIZE,SIZE,0,GL_DEPTH_COMPONENT,GL_FLOAT,(ByteBuffer)null);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            fbo=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,texture,0);
            glDrawBuffer(GL_NONE);glReadBuffer(GL_NONE);check(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"FBO");
            glViewport(0,0,SIZE,SIZE);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LESS);glDepthMask(true);glClearDepth(1);glFrontFace(GL_CCW);glCullFace(GL_BACK);
            float[][] poses={{983.214f,100.295f,634.537f,1347.163f,8.922f},{911.354f,100.295f,639.017f,1346.064f,1.322f},{827.546f,100.295f,644.727f,1346.863f,1.122f}};
            for(int p=0;p<poses.length;p++) {
                float[] pose=poses[p];var pos=new Vector3f(pose[0],pose[1],pose[2]);
                var camera=new PerspectiveCamera(90,3024f/1898,.1f,1000) {public Vector3f getPosition(){return new Vector3f(pos);}};
                var inv=camera.getViewMatrix(pos,pose[3],pose[4]).invert(new Matrix4f());
                var forward=new Vector3f(-inv.m20(),-inv.m21(),-inv.m22()).normalize();var up=new Vector3f(inv.m10(),inv.m11(),inv.m12()).normalize();var right=new Vector3f(forward).cross(up).normalize();
                var state=CelestialState.at(hour/24);
                var calc=new CSMCalculator();calc.updateLightBasis(state.activeLight()==CelestialState.Light.MOON?state.moonDirection():state.sunDirection(),true);
                var data=calc.computeCascadeMatrices(camera,forward,right,up);
                for(int c=0;c<3;c++) {
                    var light=new Matrix4f(data.lightSpaceMatrices()[c]);var fit=new ShadowCasterVolume(light);
                    for(var g:geometry)fit.include(new MeshBounds(0,0,0,16,256,16),g.x*16,g.z*16);
                    fit.apply(light);String name="pose"+p+"_cascade"+c;
                    var ref=render(program,light,geometry,0,true);
                    compare(output,csv,name+"_control",ref,render(program,light,geometry,0,true));
                    var corrected=render(program,light,geometry,1,true);
                    compare(output,csv,name+"_merged",ref,corrected);
                    for(int i=0;i<ref.length;i++)check(Math.abs(corrected[i]-ref[i])<.001f,"shadow crack: "+name+" texel="+i);
                    compare(output,csv,name+"_fan",ref,render(program,light,geometry,2,true));
                    compare(output,csv,name+"_corner_fan",ref,render(program,light,geometry,3,true));
                    compare(output,csv,name+"_merged_two_sided",ref,render(program,light,geometry,1,false));
                }
            }
        } finally {
            for(var g:geometry){g.unit.cleanup();g.merged.cleanup();g.fan.cleanup();g.cornerFan.cleanup();}
            glUseProgram(0);glBindFramebuffer(GL_FRAMEBUFFER,0);glDeleteFramebuffers(fbo);glDeleteTextures(texture);glDeleteProgram(program);glDeleteShader(vs);glDeleteShader(fs);glfwDestroyWindow(window);glfwTerminate();
        }
        System.out.println("DONE "+output);
    }
}
