package vmath.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;

class LodSelectorTest {

    private static final float SCALE = 1000f;
    private static final float RADIUS = (float) Math.sqrt(3.0); // a box of half-extent 1

    private static CullContext ctx() {
        Frustumf f = Frustumf.fromViewProjection(Mat4f.perspective(1f, 1f, 0.1f, 1e4f, true), DepthRange.ZERO_TO_ONE);
        return new CullContext(f, Vec3f.ZERO, SCALE);
    }

    /** A unit-half-extent box centred {@code distance} in front of the camera. */
    private static void box(BoundsArray b, int i, float distance) {
        float z = -distance;
        if (i < b.size()) {
            b.set(i, -1f, -1f, z - 1f, 1f, 1f, z + 1f);
        } else {
            b.add(-1f, -1f, z - 1f, 1f, 1f, z + 1f);
        }
    }

    private static float sizeAt(float distance) {
        return 2f * RADIUS * SCALE / distance;
    }

    private static float distanceForSize(float size) {
        return 2f * RADIUS * SCALE / size;
    }

    @Test
    void constructorRejectsBadParameters() {
        assertThrows(IllegalArgumentException.class, () -> new LodSelector(new float[] {10f, 20f}, 0f, 0f, 0f));
        assertThrows(IllegalArgumentException.class, () -> new LodSelector(new float[] {10f, 10f}, 0f, 0f, 0f));
        assertThrows(IllegalArgumentException.class, () -> new LodSelector(new float[] {-1f}, 0f, 0f, 0f));
        assertThrows(IllegalArgumentException.class, () -> new LodSelector(new float[] {10f}, 11f, 0f, 0f));
        assertThrows(IllegalArgumentException.class, () -> new LodSelector(new float[] {10f}, 0f, 1f, 0f));
        assertThrows(IllegalArgumentException.class, () -> new LodSelector(new float[] {10f}, 0f, 0f, -1f));
        assertThrows(IllegalArgumentException.class, () -> new LodSelector(new float[] {Float.NaN}, 0f, 0f, 0f));
        assertEquals(1, new LodSelector(new float[0], 0f, 0f, 0f).levels());
        assertEquals(4, LodSelector.of(300f, 100f, 30f).levels());
    }

    @Test
    void levelForFollowsTheThresholds() {
        LodSelector lod = new LodSelector(new float[] {300f, 100f, 30f}, 0f, 0f, 0f);
        assertEquals(0, lod.levelFor(1e9f));
        assertEquals(0, lod.levelFor(300f));
        assertEquals(1, lod.levelFor(299.9f));
        assertEquals(1, lod.levelFor(100f));
        assertEquals(2, lod.levelFor(99f));
        assertEquals(3, lod.levelFor(29f));
        assertEquals(3, lod.levelFor(0f));
        assertEquals(0, lod.levelFor(Float.NaN), "an unknown size gets full detail");
    }

    @Test
    void levelNeverDecreasesWithDistance() {
        LodSelector lod = LodSelector.of(400f, 150f, 60f, 20f);
        int n = 200;
        BoundsArray b = new BoundsArray(n);
        for (int i = 0; i < n; i++) {
            box(b, i, 3f + i * 1.7f);
        }
        VisibilitySet vis = new VisibilitySet(n);
        vis.setAll(n);
        byte[] levels = new byte[n];
        Arrays.fill(levels, LodSelector.NO_LEVEL);
        float[] fade = new float[n];
        lod.select(ctx(), b, vis, levels, fade);
        for (int i = 1; i < n; i++) {
            assertTrue(levels[i] >= levels[i - 1], "level went up in detail at object " + i);
            assertTrue(fade[i] >= 0f && fade[i] <= 1f);
        }
        assertEquals(0, levels[0]);
        assertEquals(4, levels[n - 1]);
    }

    @Test
    void smallObjectsAreCulledAndOthersKeepTheirBits() {
        LodSelector lod = new LodSelector(new float[] {200f, 50f}, 10f, 0f, 0f);
        BoundsArray b = new BoundsArray(3);
        box(b, 0, distanceForSize(500f)); // big
        box(b, 1, distanceForSize(9f));   // below cullBelow
        box(b, 2, distanceForSize(20f));  // small but drawn
        VisibilitySet vis = new VisibilitySet(3);
        vis.setAll(3);
        byte[] levels = new byte[3];
        Arrays.fill(levels, LodSelector.NO_LEVEL);
        lod.select(ctx(), b, vis, levels, null);
        assertTrue(vis.get(0));
        assertFalse(vis.get(1));
        assertTrue(vis.get(2));
        assertEquals(0, levels[0]);
        assertEquals(LodSelector.NO_LEVEL, levels[1], "a culled object's entry is left alone");
        assertEquals(2, levels[2]);
    }

    @Test
    void objectsThatAreAlreadyInvisibleAreSkipped() {
        LodSelector lod = LodSelector.of(100f);
        BoundsArray b = new BoundsArray(2);
        box(b, 0, 10f);
        box(b, 1, 10f);
        VisibilitySet vis = new VisibilitySet(2);
        vis.set(1);
        byte[] levels = {LodSelector.NO_LEVEL, LodSelector.NO_LEVEL};
        lod.select(ctx(), b, vis, levels, null);
        assertEquals(LodSelector.NO_LEVEL, levels[0]);
        assertEquals(0, levels[1]);
    }

    @Test
    void hysteresisStopsFlickerNearAThreshold() {
        float threshold = 100f;
        float d0 = distanceForSize(threshold);
        for (float h : new float[] {0f, 0.1f}) {
            LodSelector lod = new LodSelector(new float[] {threshold}, 0f, h, 0f);
            BoundsArray b = new BoundsArray(1);
            box(b, 0, d0);
            VisibilitySet vis = new VisibilitySet(1);
            byte[] levels = {LodSelector.NO_LEVEL};
            int changes = 0;
            int prev = -1;
            for (int frame = 0; frame < 200; frame++) {
                // the camera hovers around the threshold: distance jitters by +-4%
                float d = d0 * (1f + 0.04f * (frame % 2 == 0 ? 1f : -1f));
                box(b, 0, d);
                vis.setAll(1);
                lod.select(ctx(), b, vis, levels, null);
                if (prev >= 0 && levels[0] != prev) {
                    changes++;
                }
                prev = levels[0];
            }
            if (h == 0f) {
                assertTrue(changes > 100, "without hysteresis the level flickers, saw " + changes + " changes");
            } else {
                assertEquals(0, changes, "with 10% hysteresis a +-4% wobble must not flip the level");
            }
        }
    }

    @Test
    void hysteresisStillSwitchesOnceTheSizeIsClearlyPastTheBand() {
        LodSelector lod = new LodSelector(new float[] {100f}, 0f, 0.1f, 0f);
        BoundsArray b = new BoundsArray(1);
        VisibilitySet vis = new VisibilitySet(1);
        byte[] levels = {LodSelector.NO_LEVEL};
        int[] expected = {0, 0, 0, 1, 1, 1, 0};
        float[] sizes = {150f, 105f, 95f, 89f, 105f, 109f, 111f};
        for (int i = 0; i < sizes.length; i++) {
            box(b, 0, distanceForSize(sizes[i]));
            vis.setAll(1);
            lod.select(ctx(), b, vis, levels, null);
            assertEquals(expected[i], levels[0], "size " + sizes[i]);
        }
    }

    @Test
    void crossFadeIsContinuousAcrossAThreshold() {
        LodSelector lod = new LodSelector(new float[] {100f, 40f}, 0f, 0f, 0.25f);
        assertEquals(0f, lod.fadeFor(1000f, 0));
        assertEquals(0f, lod.fadeFor(125f, 0), 1e-6f);
        assertEquals(1f, lod.fadeFor(100f, 0), 1e-6f);
        assertEquals(0.5f, lod.fadeFor(112.5f, 0), 1e-5f);
        // just above the threshold the level-0 object is already fully blended into level 1; just below, level 1 is fresh
        assertEquals(1f, lod.fadeFor(100.0001f, 0), 1e-3f);
        assertEquals(0f, lod.fadeFor(99.9999f, 1) == 0f ? 0f : lod.fadeFor(99.9999f, 1), 1e-6f);
        assertEquals(0f, lod.fadeFor(5f, 2), "the last level has nothing to fade into");
        float prev = 0f;
        for (float s = 200f; s >= 90f; s -= 1f) { // fade only ever grows as the object shrinks within a level
            int level = lod.levelFor(s);
            if (level != 0) {
                break;
            }
            float f = lod.fadeFor(s, level);
            assertTrue(f >= prev - 1e-6f);
            prev = f;
        }
        assertEquals(0f, new LodSelector(new float[] {100f}, 0f, 0f, 0f).fadeFor(101f, 0), "fadeBand 0 disables fading");
    }

    @Test
    void biasScalesSizesAndZeroScaleMeansFullDetail() {
        LodSelector lod = new LodSelector(new float[] {100f}, 0f, 0f, 0f);
        BoundsArray b = new BoundsArray(1);
        box(b, 0, distanceForSize(60f));
        VisibilitySet vis = new VisibilitySet(1);
        byte[] levels = {LodSelector.NO_LEVEL};
        vis.setAll(1);
        lod.select(ctx(), b, vis, levels, null, 1f);
        assertEquals(1, levels[0]);
        levels[0] = LodSelector.NO_LEVEL;
        lod.select(ctx(), b, vis, levels, null, 2f); // twice the detail: 60 -> 120 pixels
        assertEquals(0, levels[0]);
        levels[0] = LodSelector.NO_LEVEL;
        CullContext off = new CullContext(ctx().frustum(), Vec3f.ZERO, 0f);
        lod.select(off, b, vis, levels, null);
        assertEquals(0, levels[0]);
        assertThrows(IllegalArgumentException.class, () -> lod.select(ctx(), b, vis, new byte[0], null));
    }

    @Test
    void cameraInsideAnObjectGetsFullDetail() {
        LodSelector lod = new LodSelector(new float[] {100f}, 50f, 0f, 0f);
        BoundsArray b = new BoundsArray(1);
        b.add(-1f, -1f, -1f, 1f, 1f, 1f); // centred exactly on the camera: distance 0
        VisibilitySet vis = new VisibilitySet(1);
        vis.setAll(1);
        byte[] levels = {LodSelector.NO_LEVEL};
        lod.select(ctx(), b, vis, levels, null);
        assertTrue(vis.get(0));
        assertEquals(0, levels[0]);
    }
}
