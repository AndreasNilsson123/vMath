package vmath.samples.demos.culling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.camera.Cameraf;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;
import vmath.geo.DepthRange;
import vmath.samples.framework.Scenes;

/**
 * Tests that every culling method of the culling lab finds the boxes that the scalar kernel finds,
 * on several views of a small city, also after boxes moved.
 *
 * <p>A box that a method finds and the scalar kernel does not is allowed (a conservative result),
 * and a box that the scalar kernel finds and the method does not is allowed only if it is within a
 * centimetre of a frustum plane, where the float rounding of two correct tests differs; the same
 * rule the demo's {@code --verify} applies.
 *
 * <p><b>Thread safety.</b> The tests share one executor and one scene that they do not modify
 * (the movement test builds its own); they may run in parallel.
 */
class CullMethodsTest {

    private static final float MARGINAL = 0.01f;
    private static ExecutorService executor;
    private static BoundsArray city;

    @BeforeAll
    static void setUp() {
        executor = Executors.newFixedThreadPool(2);
        city = Scenes.city(4000);
    }

    @AfterAll
    static void tearDown() {
        executor.shutdownNow();
    }

    private static List<CullMethod> methods(BoundsArray bounds) {
        List<CullMethod> list = new ArrayList<>();
        list.add(CullMethod.Kernel.scalar());
        list.add(CullMethod.Kernel.best());
        list.add(CullMethod.Kernel.parallel(executor, 2, () -> { }));
        list.add(new CullMethod.Bvh());
        list.add(new CullMethod.Dynamic());
        list.add(new CullMethod.Octree());
        list.add(new CullMethod.Grid());
        for (CullMethod m : list) {
            m.build(bounds);
        }
        return list;
    }

    private static Cameraf camera(float x, float y, float z, float tx, float ty, float tz, float far) {
        return Cameraf.lookingAt(new Vec3f(x, y, z), new Vec3f(tx, ty, tz), Vec3f.UNIT_Y, 1.0f, 16f / 9f, 0.5f, far, DepthRange.NEGATIVE_ONE_TO_ONE);
    }

    private static Cameraf[] views() {
        return new Cameraf[] {camera(0f, 60f, -200f, 0f, 0f, 0f, 1500f), camera(-80f, 20f, 40f, 90f, 10f, -60f, 400f), camera(150f, 300f, 150f, 0f, 0f, 0f, 2000f),
                camera(0f, 5f, 0f, 1f, 5f, 0f, 100f), camera(0f, 1000f, 0f, 0f, 0f, 1f, 3000f)};
    }

    private static void assertSameAsScalar(CullMethod scalar, CullMethod method, Cameraf cam, BoundsArray bounds, String where) {
        VisibilitySet expected = new VisibilitySet(bounds.size()), actual = new VisibilitySet(bounds.size());
        scalar.cull(cam, 900, bounds, expected);
        int n = method.cull(cam, 900, bounds, actual);
        assertEquals(actual.count(), n, method.name() + " " + where + ": the returned count is the number of set bits");
        for (int i = expected.nextSetBit(0); i >= 0 && i < bounds.size(); i = expected.nextSetBit(i + 1)) {
            if (!actual.get(i)) {
                Aabbf b = bounds.get(i);
                Aabbf slack = new Aabbf(b.minX() - MARGINAL, b.minY() - MARGINAL, b.minZ() - MARGINAL, b.maxX() + MARGINAL, b.maxY() + MARGINAL, b.maxZ() + MARGINAL);
                assertTrue(cam.frustum().intersects(slack), method.name() + " " + where + " missed box " + i + " " + b);
            }
        }
    }

    @Test
    void everyMethodFindsWhatTheScalarKernelFinds() {
        List<CullMethod> all = methods(city);
        for (Cameraf cam : views()) {
            for (CullMethod m : all) {
                assertSameAsScalar(all.get(0), m, cam, city, "static scene");
            }
        }
        all.forEach(CullMethod::close);
    }

    @Test
    void everyMethodStaysCorrectAfterBoxesMove() {
        BoundsArray bounds = Scenes.city(4000);
        BoundsArray base = Scenes.city(4000);
        List<CullMethod> all = methods(bounds);
        for (int round = 0; round < 5; round++) {
            int from = round * 700, to = from + 700;
            for (int i = from; i < to; i++) {
                float off = 40f * (float) Math.sin(i * 0.37 + round);
                bounds.set(i, base.minX(i), base.minY(i) + off, base.minZ(i), base.maxX(i), base.maxY(i) + off, base.maxZ(i));
            }
            for (CullMethod m : all) {
                m.moved(bounds, from, to, 40f);
            }
            for (Cameraf cam : views()) {
                for (CullMethod m : all) {
                    assertSameAsScalar(all.get(0), m, cam, bounds, "after round " + round);
                }
            }
        }
        all.forEach(CullMethod::close);
    }

    @Test
    void theBoxAroundTheFrustumContainsEveryVisibleBox() {
        VisibilitySet visible = new VisibilitySet(city.size());
        CullMethod scalar = CullMethod.Kernel.scalar();
        float[] box = new float[6];
        for (Cameraf cam : views()) {
            CullMethod.frustumBox(cam, box);
            scalar.cull(cam, 900, city, visible);
            for (int i = visible.nextSetBit(0); i >= 0 && i < city.size(); i = visible.nextSetBit(i + 1)) {
                assertTrue(city.maxX(i) >= box[0] - 0.01f && city.minX(i) <= box[3] + 0.01f && city.maxY(i) >= box[1] - 0.01f && city.minY(i) <= box[4] + 0.01f
                        && city.maxZ(i) >= box[2] - 0.01f && city.minZ(i) <= box[5] + 0.01f, "box " + i + " is visible but outside the box around the frustum");
            }
        }
    }

    @Test
    void theStructuresReportTheirBuildTimeAndTheKernelsDoNot() {
        assertTrue(new CullMethod.Bvh().hasStructure());
        assertTrue(new CullMethod.Dynamic().hasStructure());
        assertTrue(new CullMethod.Octree().hasStructure());
        assertTrue(new CullMethod.Grid().hasStructure());
        assertTrue(!CullMethod.Kernel.scalar().hasStructure());
    }
}
