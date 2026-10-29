package engine.world;

import engine.time.CelestialState;
import engine.render.shadow.ShadowDistance;
import org.joml.Vector3f;
import java.util.*;

/** CPU-only, bounded sweep of the visible chunk square TOWARDS the active light.
 * Does not change the camera radius or the CSM matrices. All distances are world blocks.
 */
public final class ShadowCasterResidency {
    public record Plan(List<ChunkPos> extra, List<ChunkPos> required, List<ChunkPos> prefetch,
                       int candidates, float reach, boolean reachLimited) {
        public Plan(List<ChunkPos> extra,int candidates,float reach,boolean reachLimited) {
            this(extra,extra,List.of(),candidates,reach,reachLimited);
        }
        public boolean budgetLimited() { return candidates > extra.size(); }
    }

    /** At twilight prepare the rising body, without changing the actual lighting state. */
    public static Vector3f residencyDirection(CelestialState sky) {
        return sky.activeLight()!=CelestialState.Light.NONE ? sky.lightDirection()
                : sky.sunDirection().x>0 ? sky.sunDirection() : sky.moonDirection();
    }

    public static Plan coherentPlan(int cx,int cz,int radius,CelestialState sky,float end,int budget) {
        return coherentPlan(cx,cz,radius,residencyDirection(sky),end,budget);
    }

    public static Plan coherentPlan(int cx,int cz,int radius,Vector3f direction,float end,int budget) {
        var required=sweep(cx,cz,radius,direction,end,ShadowDistance.SAMPLING_GUARD);
        if(required.size()>budget)throw new IllegalArgumentException("Caster budget "+budget
                +" cannot cover "+required.size()+" required chunks at shadowFadeEnd="+end);
        var requiredSet=new HashSet<>(required);
        var prefetch=sweep(cx,cz,radius,direction,end,ShadowDistance.SAMPLING_GUARD+ShadowDistance.PREFETCH_MARGIN);
        prefetch.removeAll(requiredSet);
        int candidates=required.size()+prefetch.size();
        prefetch=new ArrayList<>(prefetch.subList(0,Math.min(prefetch.size(),budget-required.size())));
        var extra=new ArrayList<>(required);extra.addAll(prefetch);
        return new Plan(List.copyOf(extra),List.copyOf(required),List.copyOf(prefetch),candidates,end,false);
    }

    /** Conservative certificate over the ENTIRE fixed orbit, not just a few sampled hours.
     * The extra padding bounds displacement variation between interval center and endpoints.
     */
    public static void validateOrbitBudget(int radius,float end,int budget) {
        if(radius<0 || !(end>0) || !Float.isFinite(end) || budget<0)
            throw new IllegalArgumentException("Invalid coherent caster configuration");
        int intervals=720;
        float pad=ShadowDistance.SAMPLING_GUARD + end*(float)Math.PI/intervals + .001f;
        for(int i=0;i<intervals;i++) {
            double angle=2*Math.PI*(i+.5)/intervals;
            var dir=new Vector3f((float)Math.cos(angle),0,
                    (float)(Math.sin(angle)*Math.sin(Math.toRadians(CelestialState.TILT_DEGREES))));
            int count=sweep(0,0,radius,dir,end,pad).size();
            if(count>budget)throw new IllegalArgumentException("Caster budget "+budget
                    +" cannot guarantee orbit coverage (needs at least "+count+"; reduce shadowFadeEnd or render distance)");
        }
    }

    private static ArrayList<ChunkPos> sweep(int cx,int cz,int radius,Vector3f dir,float end,float pad) {
        double dx=dir.x*end/Chunk.WIDTH,dz=dir.z*end/Chunk.DEPTH;
        double extent=radius+1+pad/Chunk.WIDTH;
        var result=new ArrayList<ChunkPos>();
        for(int x=(int)Math.floor(-extent+Math.min(0,dx));x<=Math.ceil(extent+Math.max(0,dx));x++)
            for(int z=(int)Math.floor(-extent+Math.min(0,dz));z<=Math.ceil(extent+Math.max(0,dz));z++) {
                if(Math.abs(x)<=radius && Math.abs(z)<=radius)continue;
                if(intersects(x,z,-dx,-dz,extent))result.add(new ChunkPos(cx+x,cz+z));
            }
        result.sort(Comparator.comparingLong((ChunkPos p)->distance(p,cx,cz,radius))
                .thenComparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z));
        return result;
    }

    public static Plan plan(int cx, int cz, int radius, CelestialState sky, float maxReach, int budget) {
        if(radius<0 || !Float.isFinite(maxReach))throw new IllegalArgumentException("Invalid caster residency bounds");
        if(sky.activeLight()==CelestialState.Light.NONE || maxReach<=0 || budget<=0)
            return new Plan(List.of(),0,0,false);
        var light=sky.lightDirection();
        double horizontal=Math.hypot(light.x,light.z);
        if(horizontal<1e-6 || light.y<=0)return new Plan(List.of(),0,0,false);
        // All possible receivers/casters in the current 0..HEIGHT voxel slab.
        double physical=Chunk.HEIGHT*horizontal/light.y;
        float reach=(float)Math.min(maxReach,physical);
        double dx=light.x/horizontal*reach/Chunk.WIDTH,dz=light.z/horizontal*reach/Chunk.DEPTH;
        var result=new ArrayList<ChunkPos>();
        int minX=cx-radius+(int)Math.floor(Math.min(0,dx))-1,maxX=cx+radius+(int)Math.ceil(Math.max(0,dx))+1;
        int minZ=cz-radius+(int)Math.floor(Math.min(0,dz))-1,maxZ=cz+radius+(int)Math.ceil(Math.max(0,dz))+1;
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++) {
            if(Math.abs(x-cx)<=radius && Math.abs(z-cz)<=radius)continue;
            // Minkowski expansion by a chunk: overlap of caster AABB and swept receiver square.
            if(intersects(x-cx,z-cz,-dx,-dz,radius+1.001))result.add(new ChunkPos(x,z));
        }
        result.sort(Comparator.comparingLong((ChunkPos p)->distance(p,cx,cz,radius))
                .thenComparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z));
        int count=result.size();
        return new Plan(List.copyOf(result.subList(0,Math.min(budget,count))),count,reach,physical>maxReach);
    }

    private static long distance(ChunkPos p,int cx,int cz,int radius) {
        long x=Math.max(0,Math.abs(p.x()-cx)-radius),z=Math.max(0,Math.abs(p.z()-cz)-radius);
        return x*x+z*z;
    }
    private static boolean intersects(double x,double z,double dx,double dz,double radius) {
        double lo=0,hi=1;
        double[] p={x,z},d={dx,dz};
        for(int a=0;a<2;a++) {
            if(Math.abs(d[a])<1e-9) {if(Math.abs(p[a])>radius)return false;continue;}
            double t0=(-radius-p[a])/d[a],t1=(radius-p[a])/d[a];
            lo=Math.max(lo,Math.min(t0,t1));hi=Math.min(hi,Math.max(t0,t1));
            if(lo>hi)return false;
        }
        return true;
    }
    private ShadowCasterResidency() {}
}
