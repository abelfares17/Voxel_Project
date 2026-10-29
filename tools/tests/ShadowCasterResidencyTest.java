import engine.time.CelestialState;
import engine.world.*;
import java.util.*;
import java.nio.file.*;

/** CPU test, including READ-ONLY saved mountain samples when run from repository root. */
public class ShadowCasterResidencyTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static final Map<ChunkPos,Chunk> savedChunks=new HashMap<>();
    static boolean savedSolid(int x,int y,int z)throws Exception {
        if(y<0||y>=Chunk.HEIGHT)return false;
        var p=new ChunkPos(Math.floorDiv(x,16),Math.floorDiv(z,16));
        var c=savedChunks.get(p);
        if(c==null) {
            c=ChunkCompressor.decompress(p.x(),p.z(),Files.readAllBytes(Path.of("saves/World_10/chunks/chunk_"+p.x()+"_"+p.z()+".dat")));
            savedChunks.put(p,c);
        }
        return c.getBlockId(Math.floorMod(x,16),y,Math.floorMod(z,16))!=0;
    }
    public static void main(String[] args)throws Exception {
        var sunset=CelestialState.at((17+19/60.0)/24);
        var mountain=ShadowCasterResidency.plan(64,39,15,sunset,256,640);
        for(int x=44;x<=48;x++)for(int z=39;z<=42;z++)
            check(mountain.extra().contains(new ChunkPos(x,z)),"mountain source missing "+x+","+z);
        check(!mountain.extra().contains(new ChunkPos(64,39)),"visible chunk duplicated");
        check(new HashSet<>(mountain.extra()).size()==mountain.extra().size(),"duplicates");
        check(mountain.equals(ShadowCasterResidency.plan(64,39,15,sunset,256,640)),"nondeterministic");
        var morning=ShadowCasterResidency.plan(0,0,2,CelestialState.at(7.0/24),128,2048);
        check(morning.extra().contains(new ChunkPos(8,0)),"morning must extend toward +X");
        check(!morning.extra().contains(new ChunkPos(-8,0)),"wrong side of light");
        var night=ShadowCasterResidency.plan(0,0,2,CelestialState.at(19.0/24),128,2048);
        check(night.extra().equals(morning.extra()),"opposite moon should use same sweep after 12h");
        check(ShadowCasterResidency.plan(0,0,2,CelestialState.at(.25),256,640).extra().isEmpty(),"NONE");
        check(ShadowCasterResidency.plan(0,0,2,sunset,0,640).extra().isEmpty(),"disabled");
        var limited=ShadowCasterResidency.plan(0,0,2,sunset,256,5);
        check(limited.extra().size()==5&&limited.budgetLimited()&&limited.reachLimited(),"budget flags");
        var noon=ShadowCasterResidency.plan(0,0,2,CelestialState.at(.5),256,2048);
        check(!noon.reachLimited()&&noon.reach()<128,"physical height / tan(elevation) bound");
        try{ShadowCasterResidency.plan(0,0,2,sunset,Float.NaN,5);throw new AssertionError("NaN accepted");}
        catch(IllegalArgumentException expected){}
        Chunk original=new Chunk(-3,5);var random=new Random(1);
        for(int x=0;x<16;x++)for(int y=0;y<256;y++)for(int z=0;z<16;z++)original.setBlock(x,y,z,(short)random.nextInt(4));
        Chunk copy=ChunkCompressor.decompress(-3,5,ChunkCompressor.compress(original));
        for(int x=0;x<16;x++)for(int y=0;y<256;y++)for(int z=0;z<16;z++)
            check(original.getBlockId(x,y,z)==copy.getBlockId(x,y,z),"compression roundtrip");
        check(!copy.isDataDirty(),"loaded chunk dirty");
        Path file=Path.of("saves/World_10/chunks/chunk_45_40.dat");
        if(Files.exists(file)) {
            Chunk saved=ChunkCompressor.decompress(45,40,Files.readAllBytes(file));
            int top=0;for(int x=0;x<16;x++)for(int y=0;y<256;y++)for(int z=0;z<16;z++)if(saved.getBlockId(x,y,z)!=0)top=Math.max(top,y);
            check(top>100,"saved mountain unexpectedly flat");
            System.out.println("Read-only mountain chunk45,40 top="+top);
            // Ray from the observed shadow toward the sun: record first mountain entry and
            // exit of that solid interval: a sun-facing surface needed by GL_BACK.
            var light=sunset.lightDirection();int first=-1,last=-1;
            for(int step=1;step<=6000;step++) {
                int x=(int)Math.floor(990+light.x*step*.1),y=(int)Math.floor(100+light.y*step*.1),z=(int)Math.floor(635+light.z*step*.1);
                if(savedSolid(x,y,z)) {if(first<0)first=x;last=x;}
                else if(first>=0)break;
            }
            check(first>0&&last<first,"mountain ray missed");
            int sourceX=Math.floorDiv(last,16);
            check(sourceX<64-17,"test source was already in legacy retention");
            // z varies mildly along this ray; the entire relevant source band is retained.
            for(int z=39;z<=42;z++)check(mountain.extra().contains(new ChunkPos(sourceX,z)),"ray source omitted");
            System.out.println("Mountain ray receiver=(990,100,635), first solid X="+first+", last solid X="+last+", source chunkX="+sourceX+", legacy minX=47");
        }
        System.out.println("PASS caster residency: direction, SUN/MOON/NONE, caps, mountain coverage, compression. Mountain extra="+mountain.extra().size()+" candidates="+mountain.candidates());
    }
}
