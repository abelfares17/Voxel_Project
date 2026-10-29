import engine.time.TimeManager;
import engine.world.*;
import engine.render.*;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL30.*;

/** Run in a TEMPORARY working directory, never a user save. Uses a hidden GL context. */
public class ShadowResidencyGlTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static void settle(World w)throws Exception {
        long deadline=System.nanoTime()+30_000_000_000L;
        do {
            w.processChunkLoading();
            boolean ready=w.getCasterReady()==w.getCasterRequested();
            for(var c:w.getLoadedChunksForRendering())ready &= c.isMeshCurrent();
            if(ready)return;
            Thread.sleep(2);
        }while(System.nanoTime()<deadline);
        throw new AssertionError("mesh residency failed to settle");
    }
    public static void main(String[] args)throws Exception {
        check(!java.nio.file.Files.exists(java.nio.file.Path.of("build.gradle")),"run in isolated cwd");
        check(glfwInit(),"GLFW");
        glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,2);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT,GLFW_TRUE);
        long window=glfwCreateWindow(64,64,"Caster lifetime regression",0,0);check(window!=0,"window");
        glfwMakeContextCurrent(window);GL.createCapabilities();BlockRegistry.registerAll();
        var time=new TimeManager();var w=new World("Caster regression",1,World.Mode.FLAT,1,time);time.setHour(17);
        try {
            w.startAsyncChunkGeneration(64,39);settle(w);
            check(w.isInitialLoadComplete()&&w.getLoadingProgress()==1,"saved-position initial preload");
            check(w.getChunk(0,0)==null&&w.getChunk(64,39)!=null,"loaded wrong location");
            check(w.getCasterReady()>0,"no extra casters loaded");
            var retained=w.getChunk(61,39);check(retained!=null,"upstream caster missing");
            w.update(65,39);settle(w);
            check(w.getChunk(61,39)==retained&&!retained.isRetired(),"caster outside visible retention unloaded");
            // Edits must survive eviction and reload; only this temporary world's files are written.
            w.setBlockAt(61*16+1,150,39*16+1,(short)1);
            w.update(90,39);settle(w);
            check(retained.isRetired()&&retained.getShadowMesh()==null,"retired mesh resurrected");
            var saved=w.getStorage().loadChunk(61,39);
            check(saved!=null&&saved.getBlockId(1,150,1)==1,"unload discarded edited caster");
            w.update(65,39);settle(w);
            check(w.getChunk(61,39)!=retained&&w.getChunk(61,39).getBlockId(1,150,1)==1,"reload stale data");
            // Force a result built BEFORE its neighboring chunk exists to arrive AFTER insertion.
            var a=new Chunk(200,200);a.setBlock(15,120,0,(short)1);w.getAllChunks().put(new ChunkPos(200,200),a);
            w.getMeshManager().queueChunk(a);w.getMeshManager().processMeshes();
            Thread.sleep(100);
            var b=new Chunk(201,200);b.setBlock(0,120,0,(short)1);w.getStorage().saveChunk(b);
            long oldRevision=a.getMeshRevision();
            w.startAsyncChunkGeneration(201,200);settle(w);
            check(a.getMeshRevision()>oldRevision,"disk load did not invalidate neighbor");
            check(a.getDrawCount()==30,"stale boundary face survived: "+a.getDrawCount());
            w.update(197,200);settle(w);
            check(w.getChunk(201,200)==null&&w.getChunk(200,200)==a,"boundary test preconditions");
            check(a.getDrawCount()==36,"unload left an open boundary face: "+a.getDrawCount());
            w.getMeshManager().queueChunk(a);w.getMeshManager().processMeshes();
            w.update(240,200);settle(w);
            check(a.isRetired()&&a.getVao()==0,"late upload resurrected retired chunk");
            if(w.usesCoherentResidency())for(double hour:new double[]{18,19,6,8}) {
                time.setHour(hour);w.update(240,200);settle(w);
                check(w.snapshotShadowCoverage(true).complete(),"incomplete coherent coverage at "+hour);
                check(w.getCasterRequested()<=engine.core.config.RenderConfig.SHADOW_CASTER_BUDGET,"caster budget");
                check(w.getChunkCount()<=9+engine.core.config.RenderConfig.SHADOW_CASTER_BUDGET,"resident extras budget");
                check(w.getCasterRequested()>0,"NONE must prepare the rising light");
            }
            check(glGetError()==GL_NO_ERROR,"GL error");
            System.out.println("PASS caster GL: preload saved center, retain upstream, persist edits, disk neighbor remesh, reject stale upload, retirement");
        } finally {
            w.cleanup();
            long deadline=System.nanoTime()+10_000_000_000L;
            while(MeshData.COUNT.get()!=0&&System.nanoTime()<deadline)Thread.sleep(5);
            check(MeshData.COUNT.get()==0,"native mesh leak after shutdown");
            glfwDestroyWindow(window);glfwTerminate();
        }
    }
}
