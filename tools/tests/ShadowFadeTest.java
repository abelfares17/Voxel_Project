import engine.render.shadow.*;
import engine.time.CelestialState;
import engine.world.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.HashSet;
import java.util.Random;

public class ShadowFadeTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args) {
        float previous=-1;
        for(int i=0;i<=2000;i++) {
            float v=ShadowDistance.visibility(i*.1f,80,128);
            check(v>=previous&&v>=0&&v<=1,"monotone visibility");previous=v;
            if(i<=800)check(v==0,"short shadows");if(i>=1280)check(v==1,"extinction");
        }
        check(ShadowDistance.visibility(104,80,128)==.5f,"midpoint");
        for(float span:new float[]{64,200,900})for(float elevation:new float[]{.05f,.5f,1.2f}) {
            var dir=new Vector3f((float)Math.cos(elevation),(float)Math.sin(elevation),0);
            var matrix=new Matrix4f().ortho(-200,200,-200,200,-span/2,span/2)
                    .lookAt(new Vector3f(dir).mul(20),new Vector3f(),new Vector3f(0,0,1));
            var receiver=new Vector3f(2,3,4);var caster=new Vector3f(dir).mul(104).add(receiver);
            var fit=new ShadowCasterVolume(matrix);
            fit.include(new engine.render.MeshBounds(caster.x-1,caster.y-1,caster.z-1,caster.x+1,caster.y+1,caster.z+1),0,0);
            for(int f=0;f<2;f++) {
                float delta=(matrix.transformPosition(new Vector3f(receiver)).z-matrix.transformPosition(new Vector3f(caster)).z)*.5f;
                check(Math.abs(delta*ShadowDistance.depthSpan(matrix)-104)<.002,"world distance after fit");
                fit.apply(matrix);
            }
        }
        check(ShadowDistance.samplingGuardFits(.5f)&&!ShadowDistance.samplingGuardFits(3),"sampling guard");
        ShadowCasterResidency.validateOrbitBudget(15,128,640);
        try {ShadowCasterResidency.validateOrbitBudget(15,128,1);throw new AssertionError("budget accepted");}
        catch(IllegalArgumentException expected){}
        var random=new Random(4);int maximum=0;
        for(int minute=0;minute<1440;minute+=2) {
            var sky=CelestialState.at(minute/1440.0);
            var plan=ShadowCasterResidency.coherentPlan(-64,39,15,sky,128,640);
            maximum=Math.max(maximum,plan.extra().size());
            check(plan.extra().size()<=640,"budget");
            var extra=new HashSet<>(plan.extra());var required=new HashSet<>(plan.required());
            check(extra.size()==plan.extra().size()&&extra.containsAll(required),"partition");
            for(var p:plan.prefetch())check(!required.contains(p),"prefetch duplicate");
            var dir=ShadowCasterResidency.residencyDirection(sky);
            for(int j=0;j<30;j++) {
                float x=(-64-15)*16+random.nextFloat()*31*16;
                float z=(39-15)*16+random.nextFloat()*31*16;
                float d=random.nextFloat()*128;
                int cx=(int)Math.floor((x+dir.x*d)/16),cz=(int)Math.floor((z+dir.z*d)/16);
                if(Math.abs(cx+64)>15||Math.abs(cz-39)>15)
                    check(required.contains(new ChunkPos(cx,cz)),"missing segment caster");
            }
            // Closed visible-square boundaries, negative chunk coordinates and the entire
            // horizontal guard: random interior samples alone cannot establish these cases.
            for(int ix=0;ix<3;ix++)for(int iz=0;iz<3;iz++)
                for(double d:new double[]{0,80,128})for(int sx:new int[]{-1,1})for(int sz:new int[]{-1,1}) {
                    double x=(-64-15)*16+ix*248.0+dir.x*d+sx*4;
                    double z=(39-15)*16+iz*248.0+dir.z*d+sz*4;
                    int cx=(int)Math.floor(x/16),cz=(int)Math.floor(z/16);
                    if(Math.abs(cx+64)>15||Math.abs(cz-39)>15)
                        check(required.contains(new ChunkPos(cx,cz)),"missing boundary/guard caster at minute "+minute);
                }
        }
        var full=ShadowCasterResidency.coherentPlan(0,0,15,CelestialState.at(.7),128,640);
        var tight=ShadowCasterResidency.coherentPlan(0,0,15,CelestialState.at(.7),128,full.required().size());
        check(tight.required().equals(full.required())&&tight.prefetch().isEmpty()&&tight.budgetLimited(),
                "budget pressure must truncate only prefetch");
        check(!ShadowCasterResidency.coherentPlan(0,0,15,CelestialState.at(.25),128,640).extra().isEmpty(),"twilight prefetch");
        ShadowCasterResidency.validateOrbitBudget(15,256,1024);
        try {ShadowCasterResidency.validateOrbitBudget(15,256,640);throw new AssertionError("old budget accepted for extended range");}
        catch(IllegalArgumentException expected){}
        int extendedMaximum=0;
        for(int minute=0;minute<1440;minute+=2) {
            var sky=CelestialState.at(minute/1440.0);
            var plan=ShadowCasterResidency.coherentPlan(-64,39,15,sky,256,1024);
            extendedMaximum=Math.max(extendedMaximum,plan.extra().size());
            check(!plan.budgetLimited(),"extended prefetch truncated");
            var required=new HashSet<>(plan.required());var dir=ShadowCasterResidency.residencyDirection(sky);
            for(int ix=0;ix<3;ix++)for(int iz=0;iz<3;iz++)
                for(double d:new double[]{0,160,256})for(int sx:new int[]{-1,1})for(int sz:new int[]{-1,1}) {
                    int cx=(int)Math.floor(((-64-15)*16+ix*248.0+dir.x*d+sx*4)/16);
                    int cz=(int)Math.floor(((39-15)*16+iz*248.0+dir.z*d+sz*4)/16);
                    if(Math.abs(cx+64)>15||Math.abs(cz-39)>15)
                        check(required.contains(new ChunkPos(cx,cz)),"extended boundary/guard missing");
                }
        }
        check(ShadowDistance.visibility(104,160,256)==0 && ShadowDistance.visibility(160,160,256)==0,
                "extended profile must preserve formerly attenuated isolated shadows");
        check(ShadowDistance.visibility(208,160,256)==.5f && ShadowDistance.visibility(256,160,256)==1,
                "extended midpoint/extinction");
        System.out.println("PASS extended range: orbit budget 1024, old budget rejected, 77760 boundary/guard segments. Max extra="+extendedMaximum);
        System.out.println("PASS fade CPU: 2001 weights, fitted depth distances, orbit certificate, 21600 random + 77760 boundary/guard segments, prefetch-only truncation, NONE prefetch. Max extra="+maximum);
    }
}
