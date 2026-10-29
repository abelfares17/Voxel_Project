import engine.core.config.*;
import engine.render.*;
import engine.render.camera.PerspectiveCamera;
import engine.render.core.RenderContext;
import engine.render.renderer.*;
import engine.entity.Player;
import engine.physics.PhysicsEngine;
import engine.time.TimeManager;
import engine.world.World;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL32.*;

/** READ-ONLY World 10 replay, same celestial/geometry snapshot for each OFF/ON pair. Not a benchmark. */
public class ShadowFadeMountainTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static byte[] capture(Path path,int w,int h)throws Exception {
        var buffer=MemoryUtil.memAlloc(w*h*4);
        try {
            glReadPixels(0,0,w,h,GL_RGBA,GL_UNSIGNED_BYTE,buffer);byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);
            var image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
            for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
                int p=(y*w+x)*4;image.setRGB(x,h-1-y,((bytes[p]&255)<<16)|((bytes[p+1]&255)<<8)|(bytes[p+2]&255));
            }
            ImageIO.write(image,"png",path.toFile());return bytes;
        } finally {MemoryUtil.memFree(buffer);}
    }
    public static void main(String[] args)throws Exception {
        check(GameConfig.TEST_HARNESS_ENABLED,"Must use -Dvoxel.testHarness=true to prohibit saves");
        check(Files.isDirectory(Path.of("saves/World_10")),"World 10 required");
        Path out=Files.createTempDirectory(Path.of("logs"),"shadow_fade_mountain_");System.out.println("OUTPUT "+out);
        check(glfwInit(),"GLFW");glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_FOCUSED,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,2);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT,GLFW_TRUE);
        long window=glfwCreateWindow(640,400,"Read-only mountain",0,0);check(window!=0,"window");
        glfwMakeContextCurrent(window);GL.createCapabilities();glEnable(GL_FRAMEBUFFER_SRGB);
        engine.world.BlockRegistry.registerAll();
        int[] width={0},height={0};glfwGetFramebufferSize(window,width,height);int w=width[0],h=height[0];
        var time=new TimeManager();time.setHour(17+19/60.0);time.setPaused(true);
        var world=World.loadFromSave("World 10",15,time);check(world!=null,"saved metadata");
        var player=new Player(827.546f,100.295f,644.727f,new PhysicsEngine());player.setYaw(1346.863f);player.setPitch(1.122f);
        var camera=new PerspectiveCamera(90,(float)w/h,.1f,1000,player);
        var context=new RenderContext();context.setWorld(world);context.setCamera(camera);context.setWindowSize(w,h);
        var depth=new Shader(GameConfig.DEPTH_VERTEX_SHADER,GameConfig.DEPTH_FRAGMENT_SHADER);
        var voxel=new Shader(GameConfig.VOXEL_VERTEX_SHADER,GameConfig.VOXEL_FRAGMENT_SHADER);
        var shadow=new ShadowRenderer(depth);var sky=new SkyRenderer();
        int atlas=TextureLoader.loadTextureFromClasspath(GameConfig.ATLAS_TEXTURE_PATH);
        var voxels=new VoxelRenderer(voxel,shadow,atlas);voxels.cycleShadowDebug();
        int totalChanged=0;
        try(var csv=Files.newBufferedWriter(out.resolve("results.csv"))) {
            csv.write("pose,x,z,required,prefetch,changedFadePixels,greedyChangedPixels,greedyMaxOn,greedyMaxOff,newLargeGreedyDeltas\n");
            world.startAsyncChunkGeneration(51,40);
            for(int pose=0;pose<5;pose++) {
                player.setPosition(827.546f+pose*50,100.295f,644.727f-pose*3);
                int cx=(int)Math.floor(player.getX()/16),cz=(int)Math.floor(player.getZ()/16);
                world.update(cx,cz);
                long deadline=System.nanoTime()+120_000_000_000L;
                do {world.processChunkLoading();Thread.sleep(2);}
                while((!world.allVisibleChunksReady(cx,cz,15)||world.getCasterReady()!=world.getCasterRequested())&&System.nanoTime()<deadline);
                check(world.getCasterReady()==world.getCasterRequested()&&world.allVisibleChunksReady(cx,cz,15),"streaming timeout");
                context.setCelestialState(world.getFrameCelestial());context.setTimeOfDay(time.getTimeOfDay());
                context.setShadowCoverage(world.snapshotShadowCoverage(false));shadow.render(context);
                check(context.getShadowCoverage().complete(),"incomplete coverage");
                sky.render(context);voxels.render(context,false,false);
                check(voxels.getDrawCalls()>0 && world.getMeshBytes()>0,"empty terrain cannot qualify shadows");
                byte[] off=capture(out.resolve("pose"+pose+"_fade_off.png"),w,h);
                context.setShadowCoverage(world.snapshotShadowCoverage(true));sky.render(context);voxels.render(context,false,false);
                byte[] on=capture(out.resolve("pose"+pose+"_fade_on.png"),w,h);
                shadow.replayForImageComparison(context,false);sky.render(context);voxels.render(context,false,false);
                byte[] unit=capture(out.resolve("pose"+pose+"_fade_on_unit.png"),w,h);
                context.setShadowCoverage(world.snapshotShadowCoverage(false));sky.render(context);voxels.render(context,false,false);
                byte[] unitOff=capture(out.resolve("pose"+pose+"_fade_off_unit.png"),w,h);
                context.setShadowCoverage(world.snapshotShadowCoverage(true));
                int changed=0,greedy=0,maxOn=0,maxOff=0,newLarge=0;
                for(int i=0;i<on.length;i+=4) {
                    check((on[i]&255)>=(off[i]&255),"fade darkened visibility at "+i);
                    if(on[i]!=off[i])changed++;
                    if(on[i]!=unit[i])greedy++;
                    int deltaOn=Math.abs((on[i]&255)-(unit[i]&255)),deltaOff=Math.abs((off[i]&255)-(unitOff[i]&255));
                    maxOn=Math.max(maxOn,deltaOn);maxOff=Math.max(maxOff,deltaOff);
                    if(deltaOn>16&&deltaOff<=16)newLarge++;
                }
                // Cascade solid companion image (F4 mode 5).
                for(int k=0;k<4;k++)voxels.cycleShadowDebug();sky.render(context);voxels.render(context,false,false);
                capture(out.resolve("pose"+pose+"_cascades.png"),w,h);
                for(int k=0;k<4;k++)voxels.cycleShadowDebug();
                csv.write(pose+","+player.getX()+","+player.getZ()+","+world.getCasterPlan().required().size()+","+world.getCasterPlan().prefetch().size()+","+changed+","+greedy+","+maxOn+","+maxOff+","+newLarge+"\n");csv.flush();
                totalChanged+=changed;
                check(glGetError()==GL_NO_ERROR,"GL error");
                System.out.println("pose "+pose+" changedFade="+changed+" greedy="+greedy+" max ON/OFF="+maxOn+"/"+maxOff+" newLarge="+newLarge);
                check(newLarge==0,"fade introduced large greedy deltas (inspect CSV/images)");
            }
            check(totalChanged>0,"replay did not exercise any long shadow");
        } finally {
            world.cleanup();voxels.cleanup();shadow.cleanup();sky.cleanup();voxel.cleanup();depth.cleanup();glDeleteTextures(atlas);
            glfwDestroyWindow(window);glfwTerminate();
        }
        System.out.println("PASS mountain replay "+out);
    }
}
