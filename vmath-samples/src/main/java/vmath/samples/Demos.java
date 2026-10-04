package vmath.samples;

import java.util.List;
import vmath.samples.demos.city.CityDemo;
import vmath.samples.demos.clusters.ClusterLodDemo;
import vmath.samples.demos.culling.CullingLabDemo;
import vmath.samples.demos.globe.GlobeDemo;
import vmath.samples.demos.gpucull.GpuCullDemo;
import vmath.samples.demos.lights.LightsDemo;
import vmath.samples.demos.occlusion.OcclusionDemo;
import vmath.samples.demos.physics.PileDemo;
import vmath.samples.demos.portals.PortalsDemo;
import vmath.samples.demos.sculpt.SculptDemo;
import vmath.samples.demos.skinning.SkinningDemo;
import vmath.samples.demos.sky.SkyDemo;
import vmath.samples.demos.terrain.TerrainDemo;
import vmath.samples.framework.DemoEntry;

/**
 * The registry of the demos: the list that the launcher, the menu, the smoke run and the tests of
 * the cards read.
 *
 * <p>To add a demo, write its class in {@code vmath.samples.demos.<id>}, give it a public
 * {@code DemoInfo} constant, add an entry here, a card to {@code docs/DEMOS.md} and a run
 * configuration (the registry test checks the last two).
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Stateless: the method may be called from any number of threads.
 */
public final class Demos {

    private Demos() {
    }

    /**
     * Lists the registered demos in menu order.
     *
     * @return an unmodifiable list; never {@code null} or empty
     */
    public static List<DemoEntry> all() {
        return List.of(new DemoEntry(CityDemo.INFO, CityDemo::new), new DemoEntry(CullingLabDemo.INFO, CullingLabDemo::new),
                new DemoEntry(PortalsDemo.INFO, PortalsDemo::new), new DemoEntry(OcclusionDemo.INFO, OcclusionDemo::new), new DemoEntry(PileDemo.INFO, PileDemo::new), new DemoEntry(SkinningDemo.INFO, SkinningDemo::new),
                new DemoEntry(SculptDemo.INFO, SculptDemo::new), new DemoEntry(TerrainDemo.INFO, TerrainDemo::new),
                new DemoEntry(SkyDemo.INFO, SkyDemo::new),
                new DemoEntry(LightsDemo.INFO, LightsDemo::new),
                new DemoEntry(ClusterLodDemo.INFO, ClusterLodDemo::new),
                new DemoEntry(GpuCullDemo.INFO, GpuCullDemo::new),
                new DemoEntry(GlobeDemo.INFO, GlobeDemo::new));
    }
}
