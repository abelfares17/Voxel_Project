import engine.render.shadow.*;
import engine.render.MeshBounds;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;
import java.nio.*;
import java.nio.file.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL30.*;

/** Hidden GL. Actual production vertex/fragment and depth shaders, linear floating-point target. */
public class ShadowFadeGlTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static int program(String vertex,String fragment)throws Exception {
        int p=glCreateProgram();
        for(int kind:new int[]{GL_VERTEX_SHADER,GL_FRAGMENT_SHADER}) {
            int s=glCreateShader(kind);glShaderSource(s,kind==GL_VERTEX_SHADER?vertex:fragment);glCompileShader(s);
            check(glGetShaderi(s,GL_COMPILE_STATUS)==GL_TRUE,glGetShaderInfoLog(s));glAttachShader(p,s);glDeleteShader(s);
        }
        glBindAttribLocation(p,0,"aPos");glBindAttribLocation(p,1,"aTex");glBindAttribLocation(p,2,"aNormal");glBindAttribLocation(p,3,"aAO");
        glLinkProgram(p);check(glGetProgrami(p,GL_LINK_STATUS)==GL_TRUE,glGetProgramInfoLog(p));return p;
    }
    static void mat(int p,String name,Matrix4f m){glUniformMatrix4fv(glGetUniformLocation(p,name),false,m.get(new float[16]));}
    static void integer(int p,String n,int x){glUniform1i(glGetUniformLocation(p,n),x);}
    static void scalar(int p,String n,float x){glUniform1f(glGetUniformLocation(p,n),x);}
    static String source(String name)throws Exception{return Files.readString(Path.of("src/main/resources/shaders/"+name));}
    static float read() {float[] pixel=new float[4];glReadPixels(16,16,1,1,GL_RGBA,GL_FLOAT,pixel);return pixel[0];}

    public static void main(String[] args)throws Exception {
        check(glfwInit(),"GLFW");glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,2);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT,GLFW_TRUE);
        long window=glfwCreateWindow(32,32,"Shadow distance",0,0);check(window!=0,"window");
        glfwMakeContextCurrent(window);GL.createCapabilities();
        int vao=glGenVertexArrays(),vbo=glGenBuffers(),atlas=glGenTextures(),target=glGenTextures(),white=glGenTextures();
        int depthFbo=glGenFramebuffers(),colorFbo=glGenFramebuffers();
        int depthProgram=program(source("depth_vertex_shader.glsl"),source("depth_fragment_shader.glsl"));
        try {
            glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,white);
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,1,1,0,GL_RGBA,GL_UNSIGNED_BYTE,new int[]{-1});
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glActiveTexture(GL_TEXTURE0);
            glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,vbo);
            glBufferData(GL_ARRAY_BUFFER,new float[]{-10,-10,0,10,-10,0,10,10,0,-10,-10,0,10,10,0,-10,10,0},GL_STATIC_DRAW);
            glVertexAttribPointer(0,3,GL_FLOAT,false,12,0L);glEnableVertexAttribArray(0);glVertexAttrib3f(2,0,0,-1);
            glBindTexture(GL_TEXTURE_2D_ARRAY,atlas);
            glTexImage3D(GL_TEXTURE_2D_ARRAY,0,GL_DEPTH_COMPONENT32F,32,32,3,0,GL_DEPTH_COMPONENT,GL_FLOAT,(ByteBuffer)null);
            glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_COMPARE_MODE,GL_NONE);
            glBindTexture(GL_TEXTURE_2D,target);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32F,32,32,0,GL_RGBA,GL_FLOAT,(ByteBuffer)null);
            glBindFramebuffer(GL_FRAMEBUFFER,colorFbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,target,0);
            glDrawBuffer(GL_COLOR_ATTACHMENT0);glReadBuffer(GL_COLOR_ATTACHMENT0);
            check(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"color FBO");
            glBindFramebuffer(GL_FRAMEBUFFER,depthFbo);glDrawBuffer(GL_NONE);glReadBuffer(GL_NONE);
            glDisable(GL_CULL_FACE);glDisable(GL_BLEND);glDisable(GL_FRAMEBUFFER_SRGB);glViewport(0,0,32,32);
            int cases=0;
            for(float[] range:new float[][]{{80,128},{160,256}})
            for(int stage:new int[]{5,6})for(int filter=0;filter<3;filter++) {
                String fragment=source("voxel_fragment_shader.glsl").replace("#define SHADOW_FILTER_MODE 1","#define SHADOW_FILTER_MODE "+filter)
                        .replace("#define PERF_BISECT_STAGE 6","#define PERF_BISECT_STAGE "+stage);
                int p=program(source("voxel_vertex_shader.glsl"),fragment);
                try {
                    for(float d:new float[]{40,range[0],(range[0]+range[1])/2,range[1],range[1]+32}) {
                        Matrix4f[] matrices=new Matrix4f[3];
                        for(int c=0;c<3;c++) {
                            var m=new Matrix4f().orthoLH(-20,20,-20,20,-64*(c+1),64*(c+1));
                            var fit=new ShadowCasterVolume(m);fit.include(new MeshBounds(-10,-10,-d,10,10,-d),0,0);
                            fit.apply(m);matrices[c]=m;
                            glBindFramebuffer(GL_FRAMEBUFFER,depthFbo);
                            glFramebufferTextureLayer(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,atlas,0,c);
                            check(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"depth FBO");
                            glEnable(GL_DEPTH_TEST);glDepthMask(true);glClearDepth(1);glClear(GL_DEPTH_BUFFER_BIT);
                            glUseProgram(depthProgram);mat(depthProgram,"lightSpaceMatrix",m);
                            mat(depthProgram,"modelMatrix",new Matrix4f().translation(0,0,-d));glDrawArrays(GL_TRIANGLES,0,6);
                            // A nearer occluder is invisible to the first-depth map when the far caster remains.
                            if(d>range[1]) {mat(depthProgram,"modelMatrix",new Matrix4f().translation(0,0,-40));glDrawArrays(GL_TRIANGLES,0,6);}
                        }
                        glBindFramebuffer(GL_FRAMEBUFFER,colorFbo);glDisable(GL_DEPTH_TEST);glUseProgram(p);
                        mat(p,"projectionMatrix",new Matrix4f().orthoLH(-2,2,-2,2,-2,2));mat(p,"modelMatrix",new Matrix4f());
                        integer(p,"debugShadow",1);integer(p,"shadowsEnabled",1);integer(p,"cascadeCount",3);
                        integer(p,"normalOffsetMode",0);integer(p,"biasMode",2);integer(p,"leapfrogActive",0);
                        integer(p,"shadowAtlas",0);integer(p,"textureSampler",1);integer(p,"atlasOffsetA",0);
                        scalar(p,"shadowMapInvSize",1f/32);scalar(p,"shadowFadeStart",range[0]);scalar(p,"shadowFadeEnd",range[1]);
                        glUniform3f(glGetUniformLocation(p,"lightDirection"),0,0,-1);
                        glUniform1fv(glGetUniformLocation(p,"cascadeSplits"),new float[]{0,1000,2000,3000});
                        for(int c=0;c<3;c++) {
                            mat(p,"lightSpaceMatrices["+c+"]",matrices[c]);mat(p,"lightSpaceMatricesB["+c+"]",matrices[c]);
                            scalar(p,"cascadeTexelSizes["+c+"]",1);scalar(p,"shadowDepthBiasScales["+c+"]",1);
                            scalar(p,"shadowDepthWorldSpans["+c+"]",ShadowDistance.depthSpan(matrices[c]));
                        }
                        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D_ARRAY,atlas);
                        for(int c=0;c<3;c++)for(int fade=0;fade<2;fade++)for(int rotation=0;rotation<2;rotation++) {
                            integer(p,"forceCascade",c);integer(p,"shadowFadeEnabled",fade);
                            mat(p,"viewMatrix",new Matrix4f().rotateY(rotation*.2f));
                            glDrawArrays(GL_TRIANGLES,0,6);float value=read();
                            float expected=fade==0?0:ShadowDistance.visibility(d,range[0],range[1]);
                            check(Math.abs(value-expected)<.002,"stage/filter/cascade/d="+stage+"/"+filter+"/"+c+"/"+d+" got "+value+" expected "+expected);
                            cases++;
                        }
                        // Removing an isolated extinguished blocker leaves the same white visibility.
                        if(d==range[1]) {
                            glBindFramebuffer(GL_FRAMEBUFFER,depthFbo);glFramebufferTextureLayer(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,atlas,0,2);
                            glClear(GL_DEPTH_BUFFER_BIT);glBindFramebuffer(GL_FRAMEBUFFER,colorFbo);glDrawArrays(GL_TRIANGLES,0,6);
                            check(Math.abs(read()-1)<.002,"isolated caster unload pop");
                        }
                    }
                } finally {glDeleteProgram(p);}
            }
            check(glGetError()==GL_NO_ERROR,"GL error");
            System.out.println("PASS fade GL: "+cases+" comparisons, ranges 80..128 and 160..256, production shaders, filters 0/1/2, stage 5/6, fitted cascades, view rotation, extinction. First-depth overlap limitation reproduced in both profiles.");
        } finally {
            glUseProgram(0);glBindFramebuffer(GL_FRAMEBUFFER,0);glBindVertexArray(0);glDeleteProgram(depthProgram);
            glDeleteFramebuffers(depthFbo);glDeleteFramebuffers(colorFbo);glDeleteTextures(atlas);glDeleteTextures(target);glDeleteTextures(white);
            glDeleteBuffers(vbo);glDeleteVertexArrays(vao);glfwDestroyWindow(window);glfwTerminate();
        }
    }
}
