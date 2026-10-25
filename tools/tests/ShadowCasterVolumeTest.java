import engine.render.MeshBounds;
import engine.render.shadow.ShadowCasterVolume;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.FrustumIntersection;
import java.util.Random;

public class ShadowCasterVolumeTest {
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) {
        Matrix4f original = new Matrix4f().orthoLH(-10,10,-10,10,0,20);
        MeshBounds mountain = new MeshBounds(-2,-2,-200,2,2,-199);
        var volume = new ShadowCasterVolume(original);
        volume.include(mountain,0,0);
        Matrix4f fitted = new Matrix4f();
        float scale = volume.apply(fitted);
        check(!new FrustumIntersection(original).testAab(-2,-2,-200,2,2,-199),"reference must clip remote caster");
        check(new FrustumIntersection(fitted).testAab(-2,-2,-200,2,2,-199),"remote caster lost");
        check(scale>0 && scale<1,"depth scaling");
        check(Math.abs(fitted.transformPosition(new Vector3f(0,0,20)).z-1)<1e-5,"far receiver changed");
        var irrelevant = new ShadowCasterVolume(original);
        irrelevant.include(new MeshBounds(100,100,-1000,101,101,-999),0,0);
        irrelevant.include(new MeshBounds(-1,-1,100,1,1,101),0,0);
        check(irrelevant.apply(new Matrix4f())==1,"irrelevant caster expanded projection");
        Random random = new Random(42);
        for(int i=0;i<10000;i++) {
            float x=random.nextFloat()*16-8, y=random.nextFloat()*16-8;
            float z=-random.nextFloat()*2000;
            var fit=new ShadowCasterVolume(original);
            fit.include(new MeshBounds(x,y,z,x+.5f,y+.5f,z+.5f),0,0);
            Matrix4f m=new Matrix4f(); float s=fit.apply(m);
            for(int c=0;c<8;c++) {
                Vector3f p=new Vector3f(x+((c&1)!=0?.5f:0),y+((c&2)!=0?.5f:0),z+((c&4)!=0?.5f:0));
                Vector3f before=original.transformPosition(new Vector3f(p));
                Vector3f after=m.transformPosition(p);
                check(Math.abs(before.x-after.x)<1e-6 && Math.abs(before.y-after.y)<1e-6,"XY grid changed");
                check(after.z>=-1.00001 && after.z<=1.00001,"caster clipped");
            }
            float oldStep=original.m22()*.5f, newStep=m.m22()*.5f;
            check(Math.abs(newStep-oldStep*s)<1e-7,"world bias not preserved");
        }
        for(int i=0;i<1000;i++) {
            var rotated=new Matrix4f(original).rotateXYZ(random.nextFloat()*6,random.nextFloat()*6,random.nextFloat()*6)
                    .translate(-20,50,100);
            Vector3f center=new Matrix4f(rotated).invert().transformPosition(new Vector3f(0,0,-10-random.nextFloat()*50));
            var fit=new ShadowCasterVolume(rotated);
            fit.include(new MeshBounds(center.x-.5f,center.y-.5f,center.z-.5f,
                    center.x+.5f,center.y+.5f,center.z+.5f),0,0);
            Matrix4f corrected=new Matrix4f(); fit.apply(corrected);
            Vector3f clip=corrected.transformPosition(center);
            check(Math.abs(clip.x)<1 && Math.abs(clip.y)<1 && Math.abs(clip.z)<1.00001,"rotated caster lost");
        }
        System.out.println("PASS: remote caster, downstream/lateral rejection, receiver far plane, XY preservation, bias scaling; 10000 cases");
    }
}
