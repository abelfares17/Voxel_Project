package engine.input;

import engine.Game;
import engine.entity.Player;
import engine.physics.PhysicsEngine;
import engine.render.UIScreenScaler;
import engine.time.TimeManager;
import engine.world.Chunk;
import engine.world.World;
import static org.lwjgl.glfw.GLFW.*;

/** Run in a temporary working directory: exercises real save/load and Game construction. */
public class PlayerOrientationTest {
    static void check(boolean b, String text) { if(!b) throw new AssertionError(text); }
    static void pose(Player p, float yaw, float pitch) {
        check(Math.abs(p.getYaw()-yaw)<.0001 && Math.abs(p.getPitch()-pitch)<.0001,"orientation changed");
    }
    public static void main(String[] args) {
        check(glfwInit(),"GLFW init");
        glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CLIENT_API,GLFW_NO_API);
        long window=glfwCreateWindow(64,64,"Input regression",0,0);
        check(window!=0,"window");
        World world=new World("Orientation regression",1,World.Mode.FLAT,0,new TimeManager()) {
            @Override public int getSpawnY() { return 0; }
        };
        try {
            Player saved=new Player(12,42,-3,new PhysicsEngine());
            saved.setYaw(137.25f); saved.setPitch(-32.5f);
            world.getStorage().savePlayer(saved);
            Input input=new Input(window,new UIScreenScaler(window));
            Game game=new Game(window,input,world,null,null,null,null);
            Player player=game.getPlayer();
            pose(player,137.25f,-32.5f);
            check(player.getX()==12 && player.getY()==42 && player.getZ()==-3,"saved position");
            Chunk chunk=new Chunk(0,0);
            input.sampleMousePosition(100,100,false);
            input.sampleMousePosition(900,700,false);
            player.update(chunk,input); pose(player,137.25f,-32.5f);
            input.setCursorCaptured(true);
            check(glfwGetInputMode(window,GLFW_CURSOR)==GLFW_CURSOR_DISABLED,"capture");
            input.sampleMousePosition(32,32,true);
            player.update(chunk,input); pose(player,137.25f,-32.5f);
            input.sampleMousePosition(42,27,true);
            player.update(chunk,input); pose(player,138.25f,-33);
            input.setCursorCaptured(false);
            check(glfwGetInputMode(window,GLFW_CURSOR)==GLFW_CURSOR_NORMAL,"release");
            player.update(chunk,input); pose(player,138.25f,-33);
            input.sampleMousePosition(600,900,false);
            player.update(chunk,input); pose(player,138.25f,-33);
            input.setCursorCaptured(true);
            input.sampleMousePosition(-200,400,true);
            player.update(chunk,input); pose(player,138.25f,-33);
            // Losing/regaining focus resets the baseline as well.
            input.sampleMousePosition(0,0,false);
            input.sampleMousePosition(2000,2000,true);
            player.update(chunk,input); pose(player,138.25f,-33);
            world.getStorage().savePlayer(player);
            Game reloaded=new Game(window,input,world,null,null,null,null);
            pose(reloaded.getPlayer(),138.25f,-33);
            System.out.println("PASS: real storage + Game restore, free cursor, first capture, movement, release, recapture, focus transition, second reload");
        } finally { world.cleanup(); glfwDestroyWindow(window); glfwTerminate(); }
    }
}
