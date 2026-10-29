import engine.Game;
import engine.core.config.GameConfig;
import engine.input.Input;
import engine.render.TextRenderer;
import engine.render.UIScreenScaler;
import engine.render.core.RenderContext;
import engine.time.TimeManager;
import engine.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;
import java.nio.file.*;
import java.util.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL30.*;

/** Run in a temporary cwd. Hidden GL; real input edge detection, PNG IO and UI message logic. */
public class DebugHudCaptureTest {
    static void check(boolean b,String message) { if(!b) throw new AssertionError(message); }
    static java.lang.reflect.Field field(Class<?> type,String name) throws Exception {
        var f=type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    static void call(Game game,String name) throws Exception {
        var m=Game.class.getDeclaredMethod(name); m.setAccessible(true); m.invoke(game);
    }
    static class Text extends TextRenderer {
        final List<String> lines=new ArrayList<>();
        Text() { super(GameConfig.FONT_PATH,GameConfig.FONT_SIZE); }
        @Override public void drawText(String s,float x,float y,Vector3f color,Matrix4f p,int h,float scale) {
            lines.add(s);
            super.drawText(s,x,y,color,p,h,scale);
        }
    }
    public static void main(String[] args) throws Exception {
        check(glfwInit(),"GLFW");
        glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,2);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT,GLFW_TRUE);
        long window=glfwCreateWindow(640,480,"HUD capture test",0,0);
        check(window!=0,"window"); glfwMakeContextCurrent(window); GL.createCapabilities();
        World world=new World("HUD test",1,World.Mode.FLAT,0,new TimeManager()) {
            @Override public int getSpawnY() { return 0; }
            @Override public void update(int x,int z) {} // No terrain generation needed.
        };
        Text text=new Text();
        try {
            Input input=new Input(window,new UIScreenScaler(window));
            var voxels=new engine.render.renderer.VoxelRenderer(null,null,0);
            Game game=new Game(window,input,world,voxels,null,null,text);
            game.setWinDim(640,480);
            game.getPlayer().setPosition(12.345f,98.5f,-42);
            game.getPlayer().setYaw(137.25f); game.getPlayer().setPitch(-32.5f);
            boolean[] keys=(boolean[])field(Input.class,"currKeys").get(input);
            boolean[] previous=(boolean[])field(Input.class,"prevKeys").get(input);
            keys[GLFW_KEY_F3]=true; game.update(0);
            check(field(Game.class,"debugHudVisible").getBoolean(game),"F3 shows HUD");
            previous[GLFW_KEY_F3]=true; game.update(0);
            check(field(Game.class,"debugHudVisible").getBoolean(game),"held F3 must not toggle");
            RenderContext context=new RenderContext(); context.setTimeOfDay(.5f);
            var renderUI=Game.class.getDeclaredMethod("renderUI",RenderContext.class); renderUI.setAccessible(true);
            glBindFramebuffer(GL_FRAMEBUFFER,0); glViewport(0,0,640,480);
            glClearColor(0,0,0,1); glClear(GL_COLOR_BUFFER_BIT);
            renderUI.invoke(game,context);
            check(text.lines.contains("XYZ: 12.345 / 98.500 / -42.000"),"position text");
            check(text.lines.contains("Yaw: 137.250 deg  Pitch: -32.500 deg"),"orientation text");
            previous[GLFW_KEY_F3]=false; game.update(0);
            check(!field(Game.class,"debugHudVisible").getBoolean(game),"second F3 hides HUD");
            keys[GLFW_KEY_F3]=false; keys[GLFW_KEY_F2]=true; game.update(0);
            boolean fadeBefore=voxels.isShadowFade();int debugBefore=voxels.getDebugShadow();
            input.crouch=true;keys[GLFW_KEY_F4]=true;game.update(0);
            check(voxels.isShadowFade()!=fadeBefore&&voxels.getDebugShadow()==debugBefore,"Shift+F4 must only toggle fade");
            previous[GLFW_KEY_F4]=true;game.update(0);
            check(voxels.isShadowFade()!=fadeBefore,"held Shift+F4 toggled twice");
            input.crouch=false;previous[GLFW_KEY_F4]=false;game.update(0);
            check(voxels.getDebugShadow()!=debugBefore,"F4 debug cycle");keys[GLFW_KEY_F4]=false;
            check(field(Game.class,"screenshotRequested").getBoolean(game),"F2 queues capture");
            check(!Files.exists(Path.of("captures")),"capture must wait until rendering finishes");
            game.setWinDim(32,24); glClearColor(1,0,0,1); glClear(GL_COLOR_BUFFER_BIT);
            call(game,"captureScreenshot");
            Path png;
            try(var files=Files.list(Path.of("captures"))) { png=files.findFirst().orElseThrow(); }
            var image=javax.imageio.ImageIO.read(png.toFile());
            check(image.getWidth()==32 && image.getHeight()==24,"PNG size");
            check((image.getRGB(0,0)&0xFFFFFF)==0xFF0000,"back buffer captured");
            check(field(Game.class,"debugNotificationText").get(game).equals("Screenshot saved"),"success notification");
            long remaining=field(Game.class,"debugNotificationEndTime").getLong(game)-System.currentTimeMillis();
            check(remaining>2000 && remaining<=3000,"temporary 3-second confirmation");
            text.lines.clear(); renderUI.invoke(game,context);
            check(text.lines.contains("Screenshot saved"),"success sent to UI renderer");
            field(Game.class,"debugNotificationEndTime").setLong(game,System.currentTimeMillis()-1);
            text.lines.clear(); renderUI.invoke(game,context);
            check(!text.lines.contains("Screenshot saved"),"notification expires");
            Files.move(Path.of("captures"),Path.of("successful_captures"));
            Files.writeString(Path.of("captures"),"Deliberate filesystem obstruction in temporary test directory");
            call(game,"captureScreenshot");
            check(field(Game.class,"debugNotificationText").get(game).equals("Screenshot failed (see console)"),"failure must not report success");
            check(glGetError()==GL_NO_ERROR,"GL error");
            System.out.println("PASS: F3 edge toggles, XYZ/yaw/pitch, deferred F2, real PNG, success UI, expiry, filesystem failure UI");
        } finally { text.cleanup(); world.cleanup(); glfwDestroyWindow(window); glfwTerminate(); }
    }
}
