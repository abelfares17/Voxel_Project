import engine.render.MeshBounds;
import engine.render.shadow.ShadowCasterVolume;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL30.*;

/** Actual depth shaders: a distant caster must occlude a near receiver in every cascade. */
public class ShadowCasterDepthGlTest {
    static void check(boolean b, String text) { if(!b) throw new AssertionError(text); }
    static int shader(int kind, String path) throws Exception {
        int id=glCreateShader(kind);
        glShaderSource(id,java.nio.file.Files.readString(java.nio.file.Path.of(path)));
        glCompileShader(id);
        check(glGetShaderi(id,GL_COMPILE_STATUS)==GL_TRUE,glGetShaderInfoLog(id));
        return id;
    }
    static float render(int program, Matrix4f matrix) {
        try(var stack=MemoryStack.stackPush()) {
            var buffer=stack.mallocFloat(16);
            glUniformMatrix4fv(glGetUniformLocation(program,"lightSpaceMatrix"),false,matrix.get(buffer));
            glClear(GL_DEPTH_BUFFER_BIT); glDrawArrays(GL_TRIANGLES,0,6);
            var depth=stack.mallocFloat(1);
            glReadPixels(32,32,1,1,GL_DEPTH_COMPONENT,GL_FLOAT,depth);
            return depth.get(0);
        }
    }
    public static void main(String[] args) throws Exception {
        check(glfwInit(),"GLFW");
        glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,2);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT,GLFW_TRUE);
        long window=glfwCreateWindow(64,64,"Caster test",0,0);
        check(window!=0,"context"); glfwMakeContextCurrent(window); GL.createCapabilities();
        int program=0, vs=0, fs=0, vao=0, vbo=0, fbo=0, texture=0;
        try {
            vs=shader(GL_VERTEX_SHADER,"src/main/resources/shaders/depth_vertex_shader.glsl");
            fs=shader(GL_FRAGMENT_SHADER,"src/main/resources/shaders/depth_fragment_shader.glsl");
            program=glCreateProgram(); glAttachShader(program,vs); glAttachShader(program,fs);
            glBindAttribLocation(program,0,"aPos"); glLinkProgram(program);
            check(glGetProgrami(program,GL_LINK_STATUS)==GL_TRUE,glGetProgramInfoLog(program));
            glUseProgram(program);
            try(var stack=MemoryStack.stackPush()) {
                glUniformMatrix4fv(glGetUniformLocation(program,"modelMatrix"),false,new Matrix4f().get(stack.mallocFloat(16)));
            }
            vao=glGenVertexArrays(); glBindVertexArray(vao);
            vbo=glGenBuffers(); glBindBuffer(GL_ARRAY_BUFFER,vbo);
            glBufferData(GL_ARRAY_BUFFER,new float[]{-2,-2,-200,2,-2,-200,2,2,-200,-2,-2,-200,2,2,-200,-2,2,-200},GL_STATIC_DRAW);
            glVertexAttribPointer(0,3,GL_FLOAT,false,12,0L); glEnableVertexAttribArray(0);
            texture=glGenTextures(); glBindTexture(GL_TEXTURE_2D,texture);
            glTexImage2D(GL_TEXTURE_2D,0,GL_DEPTH_COMPONENT32F,64,64,0,GL_DEPTH_COMPONENT,GL_FLOAT,(java.nio.ByteBuffer)null);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            fbo=glGenFramebuffers(); glBindFramebuffer(GL_FRAMEBUFFER,fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,texture,0);
            glDrawBuffer(GL_NONE); glReadBuffer(GL_NONE);
            check(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"FBO");
            glViewport(0,0,64,64); glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LESS);
            for(int radius : new int[]{10,20,40}) {
                var original=new Matrix4f().orthoLH(-radius,radius,-radius,radius,0,2*radius);
                check(render(program,original)==1f,"reference must miss distant caster");
                var fit=new ShadowCasterVolume(original);
                fit.include(new MeshBounds(-2,-2,-200,2,2,-200),0,0);
                Matrix4f corrected=new Matrix4f(); fit.apply(corrected);
                float depth=render(program,corrected);
                float receiver=corrected.transformPosition(new Vector3f(0,0,10)).z*.5f+.5f;
                check(depth<1 && depth<receiver,"caster failed to shadow receiver");
            }
            check(glGetError()==GL_NO_ERROR,"GL error");
            System.out.println("PASS GL: original projections miss remote caster; fitted near/mid/far all occlude receiver using production depth shaders");
        } finally {
            glUseProgram(0); glBindFramebuffer(GL_FRAMEBUFFER,0); glBindVertexArray(0);
            glDeleteFramebuffers(fbo); glDeleteTextures(texture); glDeleteBuffers(vbo); glDeleteVertexArrays(vao);
            glDeleteProgram(program); glDeleteShader(vs); glDeleteShader(fs);
            glfwDestroyWindow(window); glfwTerminate();
        }
    }
}
