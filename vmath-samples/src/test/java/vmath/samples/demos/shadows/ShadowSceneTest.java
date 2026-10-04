package vmath.samples.demos.shadows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.camera.Cameraf;
import vmath.camera.Cascades;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;

/**
 * Tests of the data side of the cascaded shadow demo: the sun, the fitted cascades and the casters
 * that the library's two culling stages keep, against brute force.
 *
 * <p><b>Thread safety.</b> Each test builds its own scene; the tests may run in parallel.
 */
class ShadowSceneTest {

    private static Cameraf camera(float x, float z, float yaw) {
        Vec3f eye = new Vec3f(x, 40f, z);
        Vec3f target = new Vec3f(x + (float) Math.sin(yaw) * 50f, 10f, z - (float) Math.cos(yaw) * 50f);
        return Cameraf.lookingAt(eye, target, Vec3f.UNIT_Y, 1.0f, 16f / 9f, 0.5f, 1500f, DepthRange.NEGATIVE_ONE_TO_ONE);
    }

    @Test
    void theSunIsAUnitVectorPointingDownAndTurnsThroughTheDay() {
        Vec3f first = ShadowScene.sunDirection(0.0);
        boolean turned = false;
        for (double t = 0; t < 240; t += 3) {
            Vec3f d = ShadowScene.sunDirection(t);
            assertEquals(1.0, d.length(), 1e-5);
            assertTrue(d.y() < -0.3f, "the sun is above the horizon: " + d.y());
            turned |= Math.abs(d.x() - first.x()) > 0.5f;
        }
        assertTrue(turned);
    }

    @Test
    void theCascadesSplitTheViewRangeAndTheCastersAreFewerThanTheBoxesInTheirFrustum() {
        ShadowScene scene = new ShadowScene(16, 12, 4, 1024, 0.8f, 300f, true);
        Cameraf cam = camera(0f, -60f, 0.4f);
        scene.fit(cam, ShadowScene.sunDirection(5.0), true);
        List<Cascades.Cascade> c = scene.cascades();
        assertEquals(4, c.size());
        assertEquals(cam.near(), c.get(0).sliceNear(), 1e-4f);
        for (int i = 0; i < 4; i++) {
            assertTrue(c.get(i).sliceFar() > c.get(i).sliceNear());
            if (i > 0) {
                assertEquals(c.get(i - 1).sliceFar(), c.get(i).sliceNear(), 1e-3f);
                assertTrue(c.get(i).texelSize() > c.get(i - 1).texelSize(), "farther cascades are coarser");
            }
            assertTrue(scene.casterCount(i) <= scene.frustumOnlyCount(i));
            assertEquals(scene.casterCount(i), scene.casters(i).count());
        }
        assertTrue(scene.casterCount(0) < scene.casterCount(3));
        assertTrue(scene.casterCount(3) < scene.size());
    }

    @Test
    void noBoxThatShadowsAPointOfASliceIsCulled() {
        ShadowScene scene = new ShadowScene(24, 20, 4, 1024, 0.8f, 300f, true);
        int shadowedPoints = 0;
        for (int k = 0; k < 6; k++) {
            Cameraf cam = camera(-80f + 30f * k, -100f + 25f * k, 0.3f + k);
            scene.fit(cam, ShadowScene.sunDirection(7.0 * k), true);
            for (int i = 0; i < 4; i++) {
                int[] r = scene.castsIntoSlice(i, cam, 1500, 17 + 10 * k + i);
                shadowedPoints += r[0];
                assertEquals(0, r[1], "view " + k + " cascade " + i + ": a box that shadows the slice was culled");
            }
        }
        assertTrue(shadowedPoints > 2000, "the test must see shadows: " + shadowedPoints);
    }

    @Test
    void withoutTheFootprintTestEveryBoxInTheFrustumIsACaster() {
        ShadowScene scene = new ShadowScene(16, 12, 3, 1024, 0.8f, 300f, true);
        Cameraf cam = camera(0f, -60f, 0.4f);
        scene.fit(cam, ShadowScene.sunDirection(5.0), false);
        for (int i = 0; i < 3; i++) {
            assertEquals(scene.frustumOnlyCount(i), scene.casterCount(i));
        }
    }

    @Test
    void theOptionsHaveDefaultsAndRejectWhatIsWrong() {
        ShadowOptions o = ShadowOptions.parse(List.of());
        assertEquals(4, o.cascades());
        assertTrue(o.stabilize());
        ShadowOptions p = ShadowOptions.parse(List.of("--cascades", "6", "--no-stabilize", "--tint", "--show", "--verify"));
        assertEquals(6, p.cascades());
        assertTrue(!p.stabilize() && p.tint() && p.show() && p.verify());
        assertThrows(IllegalArgumentException.class, () -> ShadowOptions.parse(List.of("--cascades", "9")));
        assertThrows(IllegalArgumentException.class, () -> ShadowOptions.parse(List.of("--map")));
        assertThrows(IllegalArgumentException.class, () -> ShadowOptions.parse(List.of("--what")));
    }
}
