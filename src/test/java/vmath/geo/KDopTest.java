package vmath.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Rnd;
import vmath.core.Vec3f;

class KDopTest {

    private static final int[] KS = {6, 14, 18, 26};
    private final SplittableRandom rng = new SplittableRandom(Rnd.SEED);

    private float[] cloud(int n, double cx, double spread) {
        float[] p = new float[3 * n];
        for (int i = 0; i < p.length; i++) {
            p[i] = (float) (cx + (rng.nextDouble() * 2 - 1) * spread);
        }
        return p;
    }

    @Test
    void everyPointUsedToBuildItIsInside() {
        for (int k : KS) {
            for (int t = 0; t < 100; t++) {
                int n = 1 + rng.nextInt(50);
                float[] p = cloud(n, rng.nextDouble() * 10, 3);
                KDop d = KDop.of(k, p, n);
                assertEquals(k, d.k());
                assertEquals(k / 2, d.slabCount());
                for (int i = 0; i < n; i++) {
                    assertTrue(d.contains(p[3 * i], p[3 * i + 1], p[3 * i + 2]), "k = " + k);
                }
            }
        }
    }

    @Test
    void theAxisSlabsAreTheBoundingBox() {
        for (int k : KS) {
            float[] p = cloud(40, 0, 5);
            KDop d = KDop.of(k, p, 40);
            Aabbf box = Aabbf.fromPoints(p, 0, 40);
            assertTrue(d.aabb().approxEquals(box, 1e-5f));
            float[] dir = new float[3];
            d.direction(0, dir);
            assertEquals(1f, dir[0]);
            d.direction(1, dir);
            assertEquals(1f, dir[1]);
            d.direction(2, dir);
            assertEquals(1f, dir[2]);
        }
    }

    @Test
    void moreDirectionsAreNeverLooser() {
        // every k-DOP's slabs are a subset of those of the 26-DOP, so testing the points against the larger set cuts away at least as much
        for (int t = 0; t < 100; t++) {
            float[] p = cloud(30, 0, 5);
            KDop[] d = new KDop[4];
            for (int i = 0; i < 4; i++) {
                d[i] = KDop.of(KS[i], p, 30);
            }
            for (int q = 0; q < 200; q++) {
                float x = (float) (rng.nextDouble() * 12 - 6), y = (float) (rng.nextDouble() * 12 - 6), z = (float) (rng.nextDouble() * 12 - 6);
                if (d[1].contains(x, y, z)) {
                    assertTrue(d[0].contains(x, y, z));
                }
                if (d[2].contains(x, y, z)) {
                    assertTrue(d[0].contains(x, y, z));
                }
                if (d[3].contains(x, y, z)) {
                    assertTrue(d[1].contains(x, y, z) && d[2].contains(x, y, z));
                }
            }
        }
        // the corner of a box that the diagonal slab cuts off: the point (1,1,1) of the box (0,0,0)-(1,1,1) is in the 6-DOP and the 14-DOP, the point just outside the sphere-like shape of a ball is not
        float[] ball = new float[3 * 6];
        float[][] axes = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int i = 0; i < 6; i++) {
            System.arraycopy(axes[i], 0, ball, 3 * i, 3);
        }
        assertTrue(KDop.of(6, ball, 6).contains(0.9f, 0.9f, 0.9f));
        assertFalse(KDop.of(14, ball, 6).contains(0.9f, 0.9f, 0.9f), "the space diagonals cut the corner of the box off an octahedron");
        assertFalse(KDop.of(18, ball, 6).contains(0.9f, 0.9f, 0.0f), "the face diagonals cut the edge");
        assertTrue(KDop.of(6, ball, 6).contains(0.9f, 0.9f, 0.0f));
    }

    @Test
    void overlapIsConservativeAndAgreesWithBoxesForK6() {
        for (int k : KS) {
            for (int t = 0; t < 300; t++) {
                float[] a = cloud(8, 0, 2), b = cloud(8, rng.nextDouble() * 6 - 3, 2);
                KDop da = KDop.of(k, a, 8), db = KDop.of(k, b, 8);
                assertEquals(da.overlaps(db), db.overlaps(da));
                // a point inside both proves the volumes overlap, so the test must say so
                for (int q = 0; q < 30; q++) {
                    float x = (float) (rng.nextDouble() * 8 - 4), y = (float) (rng.nextDouble() * 8 - 4), z = (float) (rng.nextDouble() * 8 - 4);
                    if (da.contains(x, y, z) && db.contains(x, y, z)) {
                        assertTrue(da.overlaps(db), "k = " + k);
                    }
                }
                if (k == 6) {
                    assertEquals(Aabbf.fromPoints(a, 0, 8).overlaps(Aabbf.fromPoints(b, 0, 8)), da.overlaps(db));
                }
            }
        }
        // touching counts as overlapping, a gap does not
        KDop a = KDop.of(14, Aabbf.of(new Vec3f(0, 0, 0), new Vec3f(1, 1, 1)));
        KDop touching = KDop.of(14, Aabbf.of(new Vec3f(1, 0, 0), new Vec3f(2, 1, 1)));
        KDop apart = KDop.of(14, Aabbf.of(new Vec3f(1.5f, 0, 0), new Vec3f(2.5f, 1, 1)));
        assertTrue(a.overlaps(touching));
        assertFalse(a.overlaps(apart));
        // two boxes whose AABBs overlap but which are separated along a diagonal: the 14-DOP of two tilted slabs sees it
        KDop d1 = KDop.of(14, new float[] {0, 0, 0, 1, 1, 0, 0, 1, 0, 1, 0, 0}, 4);
        KDop d2 = KDop.of(14, new float[] {1.6f, 1.6f, 0, 2.2f, 2.2f, 0, 1.6f, 2.2f, 0, 2.2f, 1.6f, 0}, 4);
        assertTrue(d1.aabb().overlaps(Aabbf.of(new Vec3f(0, 0, 0), new Vec3f(1, 1, 0))));
        assertFalse(d1.overlaps(d2));
    }

    @Test
    void unionContainmentAndEmpty() {
        for (int k : KS) {
            float[] p = cloud(10, 0, 3), q = cloud(10, 4, 3);
            KDop a = KDop.of(k, p, 10), b = KDop.of(k, q, 10);
            KDop u = a.union(b);
            assertTrue(u.contains(a) && u.contains(b));
            assertEquals(KDop.of(k, concat(p, q), 20), u);
            assertEquals(a, a.union(KDop.empty(k)));
            assertEquals(a, KDop.empty(k).union(a));
            assertTrue(KDop.empty(k).isEmpty());
            assertFalse(KDop.empty(k).overlaps(a));
            assertFalse(a.overlaps(KDop.empty(k)));
            assertTrue(a.contains(KDop.empty(k)));
            assertFalse(KDop.empty(k).contains(0f, 0f, 0f));
            assertEquals(Aabbf.EMPTY, KDop.empty(k).aabb());
            assertEquals(KDop.empty(k), KDop.of(k, new float[0], 0));
            assertEquals(KDop.empty(k), KDop.of(k, Aabbf.EMPTY));
            // expanding by a point equals building with it
            KDop e = a.expand(9f, 9f, 9f);
            assertTrue(e.contains(9f, 9f, 9f) && e.contains(a));
            float[] withPoint = concat(p, new float[] {9f, 9f, 9f});
            assertEquals(KDop.of(k, withPoint, 11), e);
            assertEquals(e.hashCode(), KDop.of(k, withPoint, 11).hashCode());
            assertNotEquals(a, e);
            assertTrue(a.toString().contains("k=" + k));
            assertTrue(Float.isInfinite(KDop.empty(k).min(0)) && Float.isInfinite(KDop.empty(k).max(0)));
        }
    }

    @Test
    void aBoxHasTheSlabsOfItsCorners() {
        Aabbf box = Aabbf.of(new Vec3f(-1, -2, -3), new Vec3f(4, 5, 6));
        KDop d = KDop.of(26, box);
        assertEquals(-1f, d.min(0));
        assertEquals(4f, d.max(0));
        float[] dir = new float[3];
        for (int i = 0; i < d.slabCount(); i++) {
            d.direction(i, dir);
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (int c = 0; c < 8; c++) {
                Vec3f p = box.corner(c);
                double v = dir[0] * p.x() + dir[1] * p.y() + dir[2] * p.z();
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
            assertEquals(lo, d.min(i), 1e-5);
            assertEquals(hi, d.max(i), 1e-5);
        }
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> KDop.empty(8));
        assertThrows(IllegalArgumentException.class, () -> KDop.of(7, new float[3], 1));
        assertThrows(IllegalArgumentException.class, () -> KDop.of(14, new float[3], 0, 2));
        assertThrows(IllegalArgumentException.class, () -> KDop.of(14, new float[3], -1, 1));
        assertThrows(IllegalArgumentException.class, () -> KDop.empty(6).overlaps(KDop.empty(14)));
        assertThrows(IllegalArgumentException.class, () -> KDop.empty(6).union(KDop.empty(14)));
        assertThrows(IllegalArgumentException.class, () -> KDop.empty(6).contains(KDop.empty(18)));
        assertThrows(IndexOutOfBoundsException.class, () -> KDop.empty(6).direction(3, new float[3]));
        assertThrows(IndexOutOfBoundsException.class, () -> KDop.empty(6).direction(-1, new float[3]));
        assertFalse(KDop.empty(6).equals("x"));
    }

    private static float[] concat(float[] a, float[] b) {
        float[] c = new float[a.length + b.length];
        System.arraycopy(a, 0, c, 0, a.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }
}
