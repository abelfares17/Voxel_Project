import engine.time.*;
import engine.world.*;
import engine.render.renderer.SkyRenderer;
import java.nio.file.*;
import java.util.Arrays;

/** Run from a temporary working directory: writes only the test world's saves. */
public class CelestialTimeTest {
    static void check(boolean b, String message) { if (!b) throw new AssertionError(message); }
    static void near(double a, double b, double tolerance, String message) {
        check(Math.abs(a-b) <= tolerance, message + ": " + a + " != " + b);
    }
    static void clearOverrides() {
        for (String key : new String[]{"startHour","timeScale","pauseTime"}) System.clearProperty("voxel."+key);
    }
    public static void main(String[] args) throws Exception {
        // Entering a game must not consume time measured in a menu/loading state.
        class State implements engine.core.GameState {
            float delta;
            public void enter() {}
            public void exit() {}
            public void render(engine.render.core.RenderContext c) {}
            public void update(float dt) { delta=dt; }
        }
        var first=new State(); var manager=new engine.core.StateManager(first);
        check(manager.update(5),"initial transition"); near(first.delta,0,0,"no loading delta");
        check(!manager.update(.016f),"normal frame"); near(first.delta,.016f,0,"active delta");
        var second=new State(); manager.setState(second); manager.update(60);
        near(second.delta,0,0,"no previous state time");
        clearOverrides();
        var time = new TimeManager();
        near(time.getWorldDays(), 1.0/3, 1e-14, "default 08h");
        time.setWorldDays(0);
        for (int i=0;i<12000;i++) time.updateSeconds(.1);
        near(time.getWorldDays(),1,1e-12,"20 min/day");
        time.setWorldDays(2.999);
        time.setDebugSpeedMultiplier(12000);
        time.updateSeconds(.1);
        near(time.getWorldDays(),3.999,1e-12,"multi-day");
        time.setDebugSpeedMultiplier(1);
        double before=time.getWorldDays();
        time.updateSeconds(500);
        near(time.getWorldDays()-before,.1/1200,1e-12,"stall clamp");
        before=time.getWorldDays(); time.updateSeconds(.00025);
        near(time.getWorldDays()-before,.00025/1200,1e-12,"sub-millisecond");
        time.setPaused(true); before=time.getWorldDays(); time.updateSeconds(1);
        near(time.getWorldDays(),before,0,"pause");
        for (Double invalid : new Double[]{null, -1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            time.restoreWorldDays(invalid);
            near(time.getWorldDays(),TimeManager.DEFAULT_DAYS,0,"fallback");
        }
        var midnight=CelestialState.at(0); var dawn=CelestialState.at(.25);
        var noon=CelestialState.at(.5); var dusk=CelestialState.at(.75);
        check(midnight.activeLight()==CelestialState.Light.MOON,"midnight moon");
        check(noon.activeLight()==CelestialState.Light.SUN,"noon sun");
        check(dawn.activeLight()==CelestialState.Light.NONE && dusk.activeLight()==CelestialState.Light.NONE,"horizon none");
        for(double elevation:new double[]{.5,2,5,8,15}) {
            double fraction=.25+Math.asin(Math.sin(Math.toRadians(elevation))
                    /Math.cos(Math.toRadians(CelestialState.TILT_DEGREES)))/(2*Math.PI);
            var lowSun=CelestialState.at(fraction); var lowMoon=CelestialState.at(fraction+.5);
            near(lowSun.sunElevation(),elevation,1e-4,"solar elevation");
            near(lowMoon.moonElevation(),elevation,1e-4,"lunar elevation");
            near(lowSun.sunIntensity(),CelestialState.smooth(2,8,(float)elevation),1e-5,"solar fade");
            near(lowMoon.moonIntensity(),.08*lowSun.sunIntensity(),1e-7,"lunar fade");
        }
        near(noon.sunElevation(),66.5,1e-4,"tilt");
        near(dawn.sunElevation(),0,1e-4,"rise 6h"); near(dusk.sunElevation(),0,1e-4,"set 18h");
        for(int i=0;i<=10000;i++) {
            double day=i/10000.0;
            var s=CelestialState.at(day); var next=CelestialState.at(day+.000001);
            near(s.sunDirection().length(),1,2e-7,"normalized");
            near(s.sunDirection().dot(s.moonDirection()),-1,1e-6,"opposed");
            check(!(s.sunIntensity()>0 && s.moonIntensity()>0),"one light only");
            check(s.sunDirection().distance(next.sunDirection())<.00001,"direction continuous");
            check(s.lightColor().distance(next.lightColor())<.001,"color continuous");
        }
        var sun=noon.sunDirection(); sun.zero();
        near(noon.sunDirection().length(),1,1e-7,"immutable snapshot");
        check(Arrays.equals(SkyRenderer.starCatalogue(42), SkyRenderer.starCatalogue(42)),"deterministic stars");
        check(!Arrays.equals(SkyRenderer.starCatalogue(42), SkyRenderer.starCatalogue(43)),"seeded stars");
        float[] stars=SkyRenderer.starCatalogue(42);
        check(stars.length==512*4,"512 stars");
        for(int i=0;i<512;i++) near(stars[4*i]*stars[4*i]+stars[4*i+1]*stars[4*i+1]+stars[4*i+2]*stars[4*i+2],1,2e-7,"star direction");

        String name="Celestial persistence test";
        Path dir=Path.of("saves", WorldNaming.toFolderName(name));
        Files.createDirectories(dir);
        var legacy=new WorldMetadata(); legacy.version=1; legacy.name=name; legacy.seed=123;
        legacy.mode="FLAT"; legacy.creationDate="2000-01-01"; legacy.statistics=new WorldMetadata.Statistics();
        legacy.statistics.blocksPlaced=137; legacy.worldDays=null; legacy.save(dir);
        var storage=new WorldStorage(name,999,"PERLIN");
        check(storage.getMetadata().seed==123,"preserve seed");
        check(storage.getMetadata().name.equals(name),"preserve name");
        check(storage.getMetadata().creationDate.equals("2000-01-01"),"preserve creation");
        check(storage.getMetadata().statistics.blocksPlaced==137,"preserve stats");
        time.restoreWorldDays(storage.getWorldDays());
        storage.setWorldDays(12.456789012345); storage.saveMetadata(); storage.shutdown();
        var reloaded=new WorldStorage(name,1,"FLAT");
        time.restoreWorldDays(reloaded.getWorldDays());
        near(time.getWorldDays(),12.456789012345,0,"roundtrip/offline");
        check(reloaded.getMetadata().version==2,"migration v2"); reloaded.shutdown();
        // World integration: an explicit debug time must never replace saved simulation time.
        System.setProperty("voxel.startHour","22"); System.setProperty("voxel.timeScale","8");
        System.setProperty("voxel.pauseTime","true");
        var overridden=new TimeManager();
        var world=new World(name,123,World.Mode.FLAT,0,overridden);
        near(overridden.getTimeOfDay()*24,22,2e-6,"override restored time");
        check(overridden.isPaused() && overridden.getDebugSpeedMultiplier()==8,"override flags");
        world.save(); world.cleanup();
        near(WorldMetadata.load(dir).worldDays,12.456789012345,0,"override not persisted");
        clearOverrides();
        world=new World(name,123,World.Mode.FLAT,0,new TimeManager());
        near(world.getTimeManager().getWorldDays(),12.456789012345,0,"restore before rendering");
        world.getTimeManager().setWorldDays(15.25); world.save(); world.cleanup();
        near(WorldMetadata.load(dir).worldDays,15.25,0,"world saves clock");
        System.out.println("PASS: clock, orbit, continuity, immutable state, 512 seeded stars, v1 migration, preservation, persistence, overrides");
    }
}
