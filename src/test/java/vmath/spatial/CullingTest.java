package vmath.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.core.Mat4f;
import vmath.core.Rnd;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;

class CullingTest {

    final Rnd rnd = Rnd.create();

    /** A random camera with the frustum extracted for its depth convention. */
    private record Camera(Frustumf frustum, Vec3f eye, float fovy) {
    }

    private Camera randomCamera() {
        float fovy = (float) rnd.range(0.5, 1.8);
        float aspect = (float) rnd.range(0.8, 2.2);
        float near = (float) rnd.range(0.1, 1);
        float far = near * (float) rnd.range(50, 400);
        Vec3f eye = rnd.nextVec3f();
        Vec3f dir = rnd.nextVec3f();
        Mat4f view = Mat4f.lookAt(eye, eye.add(dir), Math.abs(dir.normalize().y()) > 0.95f ? Vec3f.UNIT_X : Vec3f.UNIT_Y);
        Frustumf f = switch ((int) rnd.range(0, 3)) {
            case 0 -> Frustumf.fromViewProjection(Mat4f.perspective(fovy, aspect, near, far, false).mul(view),
                    DepthRange.NEGATIVE_ONE_TO_ONE);
            case 1 -> Frustumf.fromViewProjection(Mat4f.perspective(fovy, aspect, near, far, true).mul(view),
                    DepthRange.ZERO_TO_ONE);
            default -> Frustumf.fromViewProjection(Mat4f.perspectiveReversedZ(fovy, aspect, near).mul(view),
                    DepthRange.REVERSED_ZERO_TO_ONE);
        };
        return new Camera(f, eye, fovy);
    }

    /** Scene of boxes scattered around the origin, sized from tiny to large. */
    private BoundsArray randomScene(int n) {
        BoundsArray b = new BoundsArray(Math.max(n, 1));
        for (int i = 0; i < n; i++) {
            Vec3f c = rnd.nextVec3f().mul(8f);
            Vec3f h = new Vec3f((float) rnd.range(0.01, 2), (float) rnd.range(0.01, 2), (float) rnd.range(0.01, 2));
            b.add(Aabbf.fromCenterHalfExtent(c, h));
        }
        return b;
    }

    /** Minimum over the six planes of the p-vertex signed distance, computed in double as the oracle. */
    private static double slack(Frustumf f, BoundsArray b, int i) {
        double min = Double.POSITIVE_INFINITY;
        for (int p = 0; p < 6; p++) {
            var pl = f.plane(p);
            double px = pl.nx() >= 0 ? b.maxX(i) : b.minX(i);
            double py = pl.ny() >= 0 ? b.maxY(i) : b.minY(i);
            double pz = pl.nz() >= 0 ? b.maxZ(i) : b.minZ(i);
            min = Math.min(min, (double) pl.d() + (double) pl.nx() * px + (double) pl.ny() * py + (double) pl.nz() * pz);
        }
        return min;
    }

    // ------------------------------------------------------------ flat frustum kernel

    @Test
    void frustumCullerMatchesTheDoubleOracleForAllSizes() {
        FrustumCuller culler = new FrustumCuller();
        int[] sizes = {0, 1, 2, 63, 64, 65, 127, 128, 129, 1023, 1024, 1025, 2047, 2048, 2049, 5000};
        for (int n : sizes) {
            for (int rep = 0; rep < 3; rep++) {
                Camera cam = randomCamera();
                BoundsArray b = randomScene(n);
                VisibilitySet vis = new VisibilitySet(n + 130); // spare capacity: bits past n must stay untouched
                vis.setAll(n);
                culler.cull(cam.frustum(), b, vis);
                int visible = 0;
                for (int i = 0; i < n; i++) {
                    double s = slack(cam.frustum(), b, i);
                    if (Math.abs(s) > 1e-3) {
                        assertEquals(s >= 0, vis.get(i), "n=" + n + " object " + i + " slack " + s);
                    }
                    visible += vis.get(i) ? 1 : 0;
                }
                assertEquals(visible, vis.count(), "no stray bits beyond n=" + n);
                for (int i = n; i < vis.capacity(); i++) {
                    assertFalse(vis.get(i), "bit " + i + " beyond the objects must stay clear");
                }
            }
        }
    }

    @Test
    void frustumCullerOnlyClearsAndHonoursRanges() {
        FrustumCuller culler = new FrustumCuller();
        Camera cam = randomCamera();
        int n = 3000;
        BoundsArray b = randomScene(n);
        VisibilitySet all = new VisibilitySet(n);
        all.setAll(n);
        culler.cull(cam.frustum(), b, all);

        // objects already rejected by an earlier stage stay rejected
        VisibilitySet narrowed = new VisibilitySet(n);
        narrowed.setAll(n);
        for (int i = 0; i < n; i += 3) {
            narrowed.clear(i);
        }
        culler.cull(cam.frustum(), b, narrowed);
        for (int i = 0; i < n; i++) {
            assertEquals(i % 3 != 0 && all.get(i), narrowed.get(i), "object " + i);
        }

        // a sub-range leaves everything outside it alone
        VisibilitySet part = new VisibilitySet(n);
        part.setAll(n);
        culler.cull(cam.frustum(), b, 640, 2000, part);
        for (int i = 0; i < n; i++) {
            boolean inRange = i >= 640 && i < 2000;
            assertEquals(inRange ? all.get(i) : true, part.get(i), "object " + i);
        }
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> culler.cull(cam.frustum(), b, 5, 100, part)).getMessage().contains("64"));
    }

    // ------------------------------------------------------------ stages and pipeline

    @Test
    void distanceStageMatchesBruteForce() {
        for (int rep = 0; rep < 20; rep++) {
            Camera cam = randomCamera();
            BoundsArray b = randomScene(800);
            float maxDist = (float) rnd.range(2, 25);
            VisibilitySet vis = new VisibilitySet(800);
            vis.setAll(800);
            new CullStages.Distance(maxDist).cull(new CullContext(cam.frustum(), cam.eye(), 0f), b, vis);
            for (int i = 0; i < 800; i++) {
                double d2 = b.get(i).distanceSquared(cam.eye());
                if (Math.abs(Math.sqrt(d2) - maxDist) > 1e-3) {
                    assertEquals(Math.sqrt(d2) <= maxDist, vis.get(i), "object " + i);
                }
            }
        }
    }

    @Test
    void smallFeatureStageRejectsOnProjectedSize() {
        Camera cam = randomCamera();
        BoundsArray b = new BoundsArray(4);
        Vec3f ahead = cam.eye().add(Vec3f.UNIT_Z.mul(10f));
        b.add(Aabbf.fromCenterHalfExtent(ahead, Vec3f.splat(0.001f))); // tiny at distance 10
        b.add(Aabbf.fromCenterHalfExtent(ahead, Vec3f.splat(3f)));     // large
        b.add(Aabbf.fromCenterHalfExtent(cam.eye().add(Vec3f.UNIT_Z.mul(0.5f)), Vec3f.splat(0.05f)));
        CullContext ctx = CullContext.perspective(cam.frustum(), cam.eye(), 1.0f, 1080);
        VisibilitySet vis = new VisibilitySet(4);
        vis.setAll(3);
        new CullStages.SmallFeature(2f).cull(ctx, b, vis);
        assertFalse(vis.get(0), "a 0.001 box 10 units away covers well under 2 pixels");
        assertTrue(vis.get(1), "a large box stays");
        // the third is 0.5 away with radius ~0.087: about 0.087 * pixelScale / 0.5 px, far above 2
        assertTrue(vis.get(2), "a close box stays");
        // disabled when pixelScale is zero
        VisibilitySet all = new VisibilitySet(4);
        all.setAll(3);
        new CullStages.SmallFeature(2f).cull(new CullContext(cam.frustum(), cam.eye(), 0f), b, all);
        assertEquals(3, all.count());
    }

    @Test
    void pipelineComposesStagesAndResizesTheSet() {
        Camera cam = randomCamera();
        BoundsArray b = randomScene(2000);
        CullContext ctx = CullContext.perspective(cam.frustum(), cam.eye(), cam.fovy(), 1080);
        CullPipeline pipeline = CullPipeline.of(new CullStages.Distance(20f), new CullStages.Frustum(),
                new CullStages.SmallFeature(1f));
        VisibilitySet vis = new VisibilitySet(1); // too small: the pipeline must grow it
        int count = pipeline.run(ctx, b, vis);
        assertEquals(vis.count(), count);

        VisibilitySet expected = new VisibilitySet(2000);
        expected.setAll(2000);
        new CullStages.Distance(20f).cull(ctx, b, expected);
        new CullStages.Frustum().cull(ctx, b, expected);
        new CullStages.SmallFeature(1f).cull(ctx, b, expected);
        for (int i = 0; i < 2000; i++) {
            assertEquals(expected.get(i), vis.get(i), "object " + i);
        }
        // running again must give the same answer (the pipeline resets the set itself)
        assertEquals(count, pipeline.run(ctx, b, vis));
        assertEquals(0, CullPipeline.of().run(ctx, new BoundsArray(1), vis));
        assertEquals(3, new CullPipeline(List.of()).run(ctx, randomScene(3), vis));
    }
}
