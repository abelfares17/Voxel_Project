import engine.render.*;
import engine.render.renderer.*;
import engine.render.core.RenderContext;
import engine.render.camera.PerspectiveCamera;
import engine.entity.Player;
import engine.physics.PhysicsEngine;
import engine.time.*;
import engine.world.World;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL32.*;

/** Hidden real GL context; run in temporary cwd, production shaders loaded from installDist. */
public class CelestialGlTest {
    static void check(boolean b,String m) { if(!b) throw new AssertionError(m); }
    static byte[] pixels() {
        var data=MemoryUtil.memAlloc(128*128*4);
        try { glReadPixels(0,0,128,128,GL_RGBA,GL_UNSIGNED_BYTE,data); byte[] out=new byte[data.remaining()]; data.get(out); return out; }
        finally { MemoryUtil.memFree(data); }
    }
    static int field(Object object,String name) throws Exception {
        var f=object.getClass().getDeclaredField(name); f.setAccessible(true); return f.getInt(object);
    }
    public static void main(String[] args) throws Exception {
        check(glfwInit(),"GLFW");
        glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE); glfwWindowHint(GLFW_FOCUSED,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,2);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT,GLFW_TRUE);
        long window=glfwCreateWindow(128,128,"Celestial regression",0,0);
        check(window!=0,"window"); glfwMakeContextCurrent(window); GL.createCapabilities();
        System.out.println("GL "+glGetString(GL_VERSION)+" / "+glGetString(GL_RENDERER));
        glEnable(GL_FRAMEBUFFER_SRGB);
        try {
            for(int iteration=0;iteration<3;iteration++) {
                var world=new World("Celestial GL "+iteration,42+iteration,World.Mode.FLAT,0,new TimeManager());
                var player=new Player(0,80,0,new PhysicsEngine());
                var camera=new PerspectiveCamera(70,1,.1f,300,player);
                var context=new RenderContext(); context.setCamera(camera); context.setWorld(world); context.setWindowSize(128,128);
                var depth=new Shader("shaders/depth_vertex_shader.glsl","shaders/depth_fragment_shader.glsl");
                var voxel=new Shader("shaders/voxel_vertex_shader.glsl","shaders/voxel_fragment_shader.glsl");
                var sky=new SkyRenderer(); var shadow=new ShadowRenderer(depth);
                var voxels=new VoxelRenderer(voxel,shadow,0);
                int atlas=shadow.getShadowAtlas(), vao=field(sky,"skyVao"), stars=field(sky,"starVbo");
                var skyField=SkyRenderer.class.getDeclaredField("sky"); skyField.setAccessible(true);
                var starField=SkyRenderer.class.getDeclaredField("stars"); starField.setAccessible(true);
                int skyProgram=((Shader)skyField.get(sky)).getId(), starProgram=((Shader)starField.get(sky)).getId();
                glBindTexture(GL_TEXTURE_2D_ARRAY,atlas);
                check(glGetTexLevelParameteri(GL_TEXTURE_2D_ARRAY,0,GL_TEXTURE_DEPTH)==3,"one 3-layer atlas");
                try {
                    for(double hour:new double[]{12,17.9,18,18.1,19,0,5.9,6,6.1,8}) {
                        var state=CelestialState.at(hour/24);
                        context.setCelestialState(state); context.setTimeOfDay((float)(hour/24));
                        check(!shadow.hasCurrentShadowMap(context),"stale map invalid before render");
                        shadow.render(context);
                        check(shadow.hasCurrentShadowMap(context)==(state.activeLight()!=CelestialState.Light.NONE),"transition freshness");
                        sky.render(context);
                        check(glGetBoolean(GL_DEPTH_WRITEMASK)&&glIsEnabled(GL_DEPTH_TEST),"depth restore");
                        check(!glIsEnabled(GL_BLEND)&&!glIsEnabled(GL_SCISSOR_TEST),"blend/scissor restore");
                        check(glGetInteger(GL_FRAMEBUFFER_BINDING)==0&&glGetInteger(GL_CURRENT_PROGRAM)==0,"FBO/program restore");
                        check(glGetInteger(GL_DEPTH_FUNC)==GL_LESS&&glIsEnabled(GL_CULL_FACE),"terrain state");
                        voxels.render(context);
                        check(glGetError()==GL_NO_ERROR,"GL error hour "+hour);
                        System.out.println("transition "+hour+" "+state.activeLight()+" fresh="+shadow.hasCurrentShadowMap(context));
                    }
                    // Image comparison uses exact snapshot: neither translation nor replay changes sky.
                    context.setCelestialState(CelestialState.at(0));
                    player.setPitch(-30); sky.render(context); byte[] a=pixels();
                    player.setPosition(500,400,-700); sky.render(context); byte[] b=pixels();
                    check(java.util.Arrays.equals(a,b),"infinite sky translation");
                    player.setPitch(-60); sky.render(context);
                    check(!java.util.Arrays.equals(a,pixels()),"sky rotation");
                    camera.setFov(110); sky.render(context);
                    check(glGetError()==GL_NO_ERROR,"wide FOV");
                    // Aim at each body's actual direction and measure its projected angular diameter.
                    camera.setFov(20);
                    for(double hour:new double[]{12,0}) {
                        var state=CelestialState.at(hour/24);
                        var direction=hour==12?state.sunDirection():state.moonDirection();
                        player.setYaw((float)Math.toDegrees(Math.atan2(direction.x,-direction.z)));
                        player.setPitch(-(float)Math.toDegrees(Math.asin(direction.y)));
                        context.setCelestialState(state); sky.render(context);
                        byte[] image=pixels(); int center=(64*128+64)*4;
                        check((image[center]&255)>170,"visible angular disc at "+hour);
                        float diameter=hour==12?CelestialState.SUN_DIAMETER_DEGREES:CelestialState.MOON_DIAMETER_DEGREES;
                        double expected=128*Math.tan(Math.toRadians(diameter/2))/Math.tan(Math.toRadians(20/2.0));
                        int left=64,right=64,threshold=hour==12?240:170;
                        while(left>0 && (image[(64*128+left-1)*4]&255)>threshold) left--;
                        while(right<127 && (image[(64*128+right+1)*4]&255)>threshold) right++;
                        check(Math.abs((right-left+1)-expected)<3,"angular diameter at "+hour+": "+(right-left+1)+" vs "+expected);
                        check((image[center+20*4]&255)<(image[center]&255),"bounded angular disc");
                        try(var stack=org.lwjgl.system.MemoryStack.stackPush()) {
                            var d=stack.mallocFloat(1);
                            glReadPixels(64,64,1,1,GL_DEPTH_COMPONENT,GL_FLOAT,d);
                            check(d.get(0)==1,"sky must not write depth");
                        }
                        if(args.length>0 && iteration==0) {
                            var path=java.nio.file.Path.of(args[0]);
                            java.nio.file.Files.createDirectories(path);
                            var png=new java.awt.image.BufferedImage(128,128,java.awt.image.BufferedImage.TYPE_INT_RGB);
                            for(int y=0;y<128;y++) for(int x=0;x<128;x++) {
                                int i=(y*128+x)*4;
                                png.setRGB(x,127-y,((image[i]&255)<<16)|((image[i+1]&255)<<8)|(image[i+2]&255));
                            }
                            javax.imageio.ImageIO.write(png,"png",path.resolve(hour==12?"sun.png":"moon.png").toFile());
                        }
                    }
                } finally {
                    sky.cleanup(); sky.cleanup();
                    check(!glIsVertexArray(vao)&&!glIsBuffer(stars),"sky resources deleted");
                    check(!glIsProgram(skyProgram)&&!glIsProgram(starProgram),"sky programs deleted");
                    shadow.cleanup(); check(!glIsTexture(atlas),"atlas deleted");
                    check(glIsProgram(depth.getId()),"shadow renderer must not delete borrowed program");
                    voxel.cleanup(); depth.cleanup(); world.cleanup();
                }
            }
            check(glGetError()==GL_NO_ERROR,"cleanup GL error");
            System.out.println("PASS GL: production programs, sun/none/moon transitions, one atlas, sky translation/rotation/FOV, states and repeated lifetime");
        } finally { glfwDestroyWindow(window); glfwTerminate(); }
    }
}
