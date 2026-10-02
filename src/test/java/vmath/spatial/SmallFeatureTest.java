package vmath.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.core.Rnd;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;
import vmath.geo.Frustumf;

/** {@link CullStages.SmallFeature} and {@link CullStages.Distance} against their formulas in double precision, away from the thresholds, and exactly at some of them. */
class SmallFeatureTest {

    final Rnd rnd = Rnd.create();

    private static VisibilitySet all(int n) {
        VisibilitySet v = new VisibilitySet(Math.max(n, 1));
        v.setAll(n);
        return v;
    }

    private static CullContext context(Vec3f camera, float scale) {
        return new CullContext(Frustumf.fromViewProjection(vmath.core.Mat4f.IDENTITY, vmath.geo.DepthRange.ZERO_TO_ONE), camera, scale);
    }

    @Test
    void smallFeatureMatchesTheProjectedSizeFormula() {
        int culled = 0, kept = 0;
        for (int trial = 0; trial < 60; trial++) {
            Vec3f camera = rnd.nextVec3f().mul(20f);
            float scale = (float) rnd.range(100, 2000);
            float minPixels = (float) rnd.range(0.5, 20);
            int n = 300;
            BoundsArray b = new BoundsArray(n);
            Aabbf[] boxes = new Aabbf[n];
            for (int i = 0; i < n; i++) {
                boxes[i] = Aabbf.fromCenterHalfExtent(rnd.nextVec3f().mul(300f), new Vec3f((float) rnd.range(0.05, 6), (float) rnd.range(0.05, 6), (float) rnd.range(0.05, 6)));
                b.add(boxes[i]);
            }
            VisibilitySet vis = all(n);
            new CullStages.SmallFeature(minPixels).cull(context(camera, scale), b, vis);
            for (int i = 0; i < n; i++) {
                Vec3f c = boxes[i].center(), h = boxes[i].halfSize();
                double dx = c.x() - camera.x(), dy = c.y() - camera.y(), dz = c.z() - camera.z();
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double radius = Math.sqrt((double) h.x() * h.x() + (double) h.y() * h.y() + (double) h.z() * h.z());
                double pixels = radius * scale / dist;
                if (pixels < minPixels * 0.999) {
                    assertFalse(vis.get(i), "a box of " + pixels + " pixels survived minPixels " + minPixels);
                    culled++;
                } else if (pixels > minPixels * 1.001) {
                    assertTrue(vis.get(i), "a box of " + pixels + " pixels was culled at minPixels " + minPixels);
                    kept++;
                }
            }
        }
        assertTrue(culled > 500 && kept > 500, "both outcomes must be exercised: " + culled + " culled, " + kept + " kept");
    }

    @Test
    void smallFeatureSpecialCases() {
        BoundsArray b = new BoundsArray(4);
        b.add(Aabbf.fromCenterHalfExtent(new Vec3f(10f, 0f, 0f), Vec3f.splat(0.001f))); // tiny and far
        b.add(Aabbf.fromCenterHalfExtent(new Vec3f(0f, 0f, 0f), Vec3f.splat(0.001f))); // tiny but the camera is inside it: distance 0, never culled
        b.add(Aabbf.fromCenterHalfExtent(new Vec3f(10f, 0f, 0f), Vec3f.splat(5f)));    // big
        VisibilitySet vis = all(3);
        new CullStages.SmallFeature(2f).cull(context(Vec3f.ZERO, 500f), b, vis);
        assertFalse(vis.get(0));
        assertTrue(vis.get(1));
        assertTrue(vis.get(2));
        VisibilitySet untouched = all(3);
        new CullStages.SmallFeature(2f).cull(context(Vec3f.ZERO, 0f), b, untouched);
        assertEquals(3, untouched.count(), "a zero pixel scale disables the stage");
        VisibilitySet partial = all(3);
        partial.clear(2);
        new CullStages.SmallFeature(2f).cull(context(Vec3f.ZERO, 500f), b, partial);
        assertFalse(partial.get(2), "an object that was already rejected stays rejected");
    }

    @Test
    void distanceMatchesTheDistanceToTheBox() {
        for (int trial = 0; trial < 40; trial++) {
            Vec3f camera = rnd.nextVec3f().mul(20f);
            float max = (float) rnd.range(5, 200);
            int n = 300;
            BoundsArray b = new BoundsArray(n);
            Aabbf[] boxes = new Aabbf[n];
            for (int i = 0; i < n; i++) {
                boxes[i] = Aabbf.fromCenterHalfExtent(rnd.nextVec3f().mul(200f), new Vec3f((float) rnd.range(0.05, 8), (float) rnd.range(0.05, 8), (float) rnd.range(0.05, 8)));
                b.add(boxes[i]);
            }
            VisibilitySet vis = all(n);
            new CullStages.Distance(max).cull(context(camera, 0f), b, vis);
            for (int i = 0; i < n; i++) {
                double d = Math.sqrt(boxes[i].distanceSquared(camera));
                if (d > max * 1.001) {
                    assertFalse(vis.get(i), "a box at distance " + d + " survived a range of " + max);
                } else if (d < max * 0.999) {
                    assertTrue(vis.get(i), "a box at distance " + d + " was culled at range " + max);
                }
            }
        }
    }
}
