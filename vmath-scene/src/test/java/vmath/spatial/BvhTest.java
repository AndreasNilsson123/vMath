package vmath.spatial;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vmath.bulk.BoundsArray;
import vmath.bulk.IntList;
import vmath.bulk.VisibilitySet;
import vmath.core.ClipSpace;
import vmath.core.Mat4f;
import vmath.core.Rnd;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;
import vmath.geo.Intersectionf;
import vmath.geo.Rayf;
import vmath.geo.Spheref;
import vmath.geo.Trianglef;

class BvhTest {

    final Rnd rnd = Rnd.create();

    private BoundsArray randomScene(int n) {
        BoundsArray b = new BoundsArray(Math.max(n, 1));
        for (int i = 0; i < n; i++) {
            Vec3f c = rnd.nextVec3f().mul(8f);
            Vec3f h = new Vec3f((float) rnd.range(0.01, 1.5), (float) rnd.range(0.01, 1.5), (float) rnd.range(0.01, 1.5));
            b.add(Aabbf.fromCenterHalfExtent(c, h));
        }
        return b;
    }

    // ------------------------------------------------------------ structure

    /** Checks every structural invariant of the tree. */
    private static void assertWellFormed(StaticBvh bvh, BoundsArray b) {
        int n = b.size();
        assertEquals(n, bvh.primitiveCount());
        // the order array is a permutation of the primitives
        boolean[] seen = new boolean[n];
        for (int p : bvh.order()) {
            assertFalse(seen[p], "primitive " + p + " appears twice");
            seen[p] = true;
        }
        if (n == 0) {
            assertEquals(0, bvh.nodeCount());
            return;
        }
        assertEquals(n, bvh.primitiveCount(0), "root covers everything");
        assertEquals(0, bvh.firstPrimitive(0));
        float[] nb = bvh.nodeBounds();
        int leafPrims = 0;
        for (int node = 0; node < bvh.nodeCount(); node++) {
            int o = node * 6;
            if (bvh.isLeaf(node)) {
                leafPrims += bvh.primitiveCount(node);
                for (int k = bvh.firstPrimitive(node); k < bvh.firstPrimitive(node) + bvh.primitiveCount(node); k++) {
                    int p = bvh.order()[k];
                    assertTrue(nb[o] <= b.minX(p) && nb[o + 1] <= b.minY(p) && nb[o + 2] <= b.minZ(p)
                            && nb[o + 3] >= b.maxX(p) && nb[o + 4] >= b.maxY(p) && nb[o + 5] >= b.maxZ(p),
                            "leaf " + node + " must contain primitive " + p);
                }
            } else {
                int l = node + 1, r = bvh.rightChild(node);
                assertTrue(r > l && r < bvh.nodeCount(), "right child index " + r + " of node " + node);
                assertEquals(bvh.primitiveCount(node), bvh.primitiveCount(l) + bvh.primitiveCount(r), "counts of node " + node);
                assertEquals(bvh.firstPrimitive(node), bvh.firstPrimitive(l), "left range starts where the parent's does");
                assertEquals(bvh.firstPrimitive(l) + bvh.primitiveCount(l), bvh.firstPrimitive(r), "ranges are contiguous");
                for (int child : new int[] {l, r}) {
                    int c = child * 6;
                    assertTrue(nb[o] <= nb[c] && nb[o + 1] <= nb[c + 1] && nb[o + 2] <= nb[c + 2]
                            && nb[o + 3] >= nb[c + 3] && nb[o + 4] >= nb[c + 4] && nb[o + 5] >= nb[c + 5],
                            "node " + node + " must contain child " + child);
                }
            }
        }
        assertEquals(n, leafPrims, "leaves partition the primitives");
    }

    @Test
    void buildProducesWellFormedTreesForManySizes() {
        for (int n : new int[] {0, 1, 2, 3, 4, 5, 7, 16, 100, 1000, 5000}) {
            for (int leaf : new int[] {1, 4, 8}) {
                BoundsArray b = randomScene(n);
                StaticBvh bvh = StaticBvh.build(b, leaf);
                assertWellFormed(bvh, b);
                for (int node = 0; node < bvh.nodeCount(); node++) {
                    if (bvh.isLeaf(node) && bvh.primitiveCount(node) > leaf) {
                        throw new AssertionError("leaf " + node + " holds " + bvh.primitiveCount(node) + " > " + leaf);
                    }
                }
            }
        }
    }

    @Test
    void degenerateInputsBuild() {
        // all boxes identical: no spatial split exists, the builder must still terminate with a balanced tree
        BoundsArray same = new BoundsArray(500);
        for (int i = 0; i < 500; i++) {
            same.add(0f, 0f, 0f, 1f, 1f, 1f);
        }
        StaticBvh bvh = StaticBvh.build(same, 4);
        assertWellFormed(bvh, same);
        assertTrue(bvh.depth() < 20, "identical boxes must not degenerate into a chain, depth " + bvh.depth());

        // boxes along a line, and zero-volume boxes
        BoundsArray line = new BoundsArray(300);
        for (int i = 0; i < 300; i++) {
            line.add(i, 0f, 0f, i, 0f, 0f);
        }
        assertWellFormed(StaticBvh.build(line), line);
        assertThrows(IllegalArgumentException.class, () -> StaticBvh.build(line, 0));
    }

    @Test
    void depthIsLogarithmicAndSahCostBeatsAFlatScan() {
        BoundsArray b = randomScene(20000);
        StaticBvh bvh = StaticBvh.build(b);
        assertTrue(bvh.depth() < 40, "depth " + bvh.depth());
        assertTrue(bvh.nodeCount() <= 2 * 20000 - 1);
        assertTrue(bvh.sahCost() > 0f);
        // testing every box for every ray costs about n; a good tree must be far cheaper
        assertTrue(bvh.sahCost() < 20000 * 0.05f, "expected cost " + bvh.sahCost() + " should be far below a flat scan");
    }

    // ------------------------------------------------------------ queries vs brute force

    private Frustumf randomFrustum() {
        float fovy = (float) rnd.range(0.4, 1.6);
        float aspect = (float) rnd.range(0.8, 2);
        float near = (float) rnd.range(0.1, 1);
        Vec3f eye = rnd.nextVec3f().mul(1.5f);
        Vec3f dir = rnd.nextVec3f();
        Mat4f view = Mat4f.lookAt(eye, eye.add(dir), Math.abs(dir.normalize().y()) > 0.95f ? Vec3f.UNIT_X : Vec3f.UNIT_Y);
        if (rnd.nextBoolean()) {
            return Frustumf.fromViewProjection(Mat4f.perspective(fovy, aspect, near, near * 100f, ClipSpace.D3D).mul(view),
                    DepthRange.ZERO_TO_ONE);
        }
        return Frustumf.fromViewProjection(Mat4f.perspectiveReversedZ(fovy, aspect, near).mul(view),
                DepthRange.REVERSED_ZERO_TO_ONE);
    }

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

    @Test
    void frustumQueryMatchesBruteForce() {
        int accepted = 0;
        int total = 0;
        for (int n : new int[] {1, 2, 10, 500, 5000}) {
            BoundsArray b = randomScene(n);
            StaticBvh bvh = StaticBvh.build(b);
            BvhQuery q = new BvhQuery(bvh);
            for (int rep = 0; rep < 20; rep++) {
                Frustumf f = randomFrustum();
                VisibilitySet out = new VisibilitySet(n + 70);
                int reported = q.frustum(f, b, out);
                assertEquals(out.count(), reported, "reported count matches set bits");
                for (int i = 0; i < n; i++) {
                    double s = slack(f, b, i);
                    if (Math.abs(s) > 1e-3) {
                        assertEquals(s >= 0, out.get(i), "n=" + n + " object " + i + " slack " + s);
                    }
                    total++;
                    accepted += out.get(i) ? 1 : 0;
                }
            }
        }
        assertTrue(accepted > total / 50 && accepted < total * 49 / 50, "a meaningful mix of in and out: " + accepted + "/" + total);
    }

    @Test
    void bvhStageEqualsTheFlatKernelAndAndsWithEarlierStages() {
        int n = 4000;
        BoundsArray b = randomScene(n);
        StaticBvh bvh = StaticBvh.build(b);
        for (int rep = 0; rep < 10; rep++) {
            Frustumf f = randomFrustum();
            CullContext ctx = new CullContext(f, Vec3f.ZERO, 0f);
            VisibilitySet viaBvh = new VisibilitySet(n), flat = new VisibilitySet(n);
            viaBvh.setAll(n);
            flat.setAll(n);
            for (int i = 0; i < n; i += 5) {
                viaBvh.clear(i);
                flat.clear(i);
            }
            new BvhStage(bvh).cull(ctx, b, viaBvh);
            new CullStages.Frustum().cull(ctx, b, flat);
            for (int i = 0; i < n; i++) {
                if (Math.abs(slack(f, b, i)) > 1e-3) {
                    assertEquals(flat.get(i), viaBvh.get(i), "object " + i);
                }
                assertTrue(i % 5 != 0 || !viaBvh.get(i), "an object rejected earlier stays rejected");
            }
        }
    }

    @Test
    void raycastBoundsMatchesBruteForce() {
        int hits = 0;
        for (int n : new int[] {1, 3, 50, 2000}) {
            BoundsArray b = randomScene(n);
            StaticBvh bvh = StaticBvh.build(b);
            BvhQuery q = new BvhQuery(bvh);
            BvhQuery.BvhHit hit = new BvhQuery.BvhHit();
            for (int rep = 0; rep < 200; rep++) {
                // aim at a random box so that a good share of the rays hit something
                Vec3f aim = b.get((int) rnd.range(0, n)).center().add(rnd.nextVec3f().mul(0.05f));
                Vec3f dir = rnd.nextVec3f();
                if (rep % 5 == 0) {
                    dir = new Vec3f(dir.x(), 0f, 0f); // axis-parallel rays exercise the reciprocal handling
                } else if (rep % 7 == 0) {
                    dir = new Vec3f(0f, 0f, dir.z());
                }
                if (dir.lengthSquared() < 1e-3f) {
                    dir = Vec3f.UNIT_Y;
                }
                dir = dir.normalize();
                Rayf ray = Rayf.of(aim.sub(dir.mul((float) rnd.range(0, 30))), dir);
                float tMax = rnd.nextBoolean() ? Float.POSITIVE_INFINITY : (float) rnd.range(1, 30);
                float bestT = Float.POSITIVE_INFINITY;
                int bestI = -1;
                for (int i = 0; i < n; i++) {
                    float t = Intersectionf.rayAabb(ray, b.get(i), tMax);
                    if (t < bestT) {
                        bestT = t;
                        bestI = i;
                    }
                }
                boolean found = q.raycastBounds(ray, tMax, b, hit);
                assertEquals(bestI >= 0, found, "n=" + n + " ray " + ray + " tMax " + tMax + (n <= 3 ? " boxes " + b.get(0) + (n > 1 ? " " + b.get(1) : "") + (n > 2 ? " " + b.get(2) : "") + " rayAabb says " + bestT + " at " + bestI : ""));
                if (found) {
                    hits++;
                    assertEquals(bestT, hit.t, 1e-3f + 1e-4f * bestT, "distance");
                    if (hit.primitive != bestI) {
                        // a different primitive is fine only if it is an equally near tie
                        assertEquals(bestT, Intersectionf.rayAabb(ray, b.get(hit.primitive), tMax), 1e-3f, "tie");
                    }
                } else {
                    assertEquals(-1, hit.primitive);
                    assertEquals(Float.POSITIVE_INFINITY, hit.t);
                }
            }
        }
        assertTrue(hits > 100, "too few hits: " + hits);
    }

    @Test
    void raycastWithNarrowPhaseFindsTheNearestTriangle() {
        int n = 1500;
        Trianglef[] tris = new Trianglef[n];
        BoundsArray b = new BoundsArray(n);
        for (int i = 0; i < n; i++) {
            Vec3f c = rnd.nextVec3f().mul(5f);
            tris[i] = Trianglef.of(c.add(rnd.nextVec3f().mul(0.15f)), c.add(rnd.nextVec3f().mul(0.15f)),
                    c.add(rnd.nextVec3f().mul(0.15f)));
            b.add(tris[i].aabb());
        }
        StaticBvh bvh = StaticBvh.build(b);
        BvhQuery q = new BvhQuery(bvh);
        BvhQuery.BvhHit hit = new BvhQuery.BvhHit();
        BvhQuery.PrimitiveTest test = (p, ray, tMax) -> Intersectionf.rayTriangle(ray, tris[p], tMax);
        int hits = 0;
        for (int rep = 0; rep < 400; rep++) {
            Vec3f target = tris[(int) rnd.range(0, n)].centroid();
            Vec3f origin = target.add(rnd.nextVec3f().normalize().mul(15f));
            Rayf ray = Rayf.through(origin, target.add(rnd.nextVec3f().mul(0.02f)));
            float bestT = Float.POSITIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                bestT = Math.min(bestT, Intersectionf.rayTriangle(ray, tris[i], Float.POSITIVE_INFINITY));
            }
            boolean found = q.raycast(ray, Float.POSITIVE_INFINITY, b, test, hit);
            assertEquals(bestT != Float.POSITIVE_INFINITY, found, "ray " + rep);
            if (found) {
                hits++;
                assertEquals(bestT, hit.t, 1e-4f * Math.max(1f, bestT));
                assertEquals(bestT, Intersectionf.rayTriangle(ray, tris[hit.primitive], Float.POSITIVE_INFINITY), 1e-4f * Math.max(1f, bestT));
            }
        }
        assertTrue(hits > 100, "too few hits: " + hits);
    }

    @Test
    void overlapQueriesMatchBruteForceExactly() {
        for (int n : new int[] {1, 5, 300, 4000}) {
            BoundsArray b = randomScene(n);
            StaticBvh bvh = StaticBvh.build(b);
            BvhQuery q = new BvhQuery(bvh);
            IntList out = new IntList();
            for (int rep = 0; rep < 50; rep++) {
                Aabbf box = Aabbf.fromCenterHalfExtent(rnd.nextVec3f().mul(1.5f), Vec3f.splat((float) rnd.range(0.2, 4)));
                out.clear();
                q.overlapAabb(box, b, out);
                out.sort();
                IntList expected = new IntList();
                for (int i = 0; i < n; i++) {
                    if (b.get(i).overlaps(box)) {
                        expected.add(i);
                    }
                }
                assertArrayEquals(expected.toArray(), out.toArray(), "aabb n=" + n);

                Spheref s = Spheref.of(rnd.nextVec3f().mul(1.5f), (float) rnd.range(0.2, 4));
                out.clear();
                q.overlapSphere(s, b, out);
                out.sort();
                expected.clear();
                for (int i = 0; i < n; i++) {
                    if (s.overlaps(b.get(i))) {
                        expected.add(i);
                    }
                }
                // the same predicate up to float rounding of the squared distance: allow boundary disagreements only
                int[] got = out.toArray();
                for (int i : expected.toArray()) {
                    assertTrue(java.util.Arrays.binarySearch(got, i) >= 0
                            || Math.abs(b.get(i).distanceSquared(s.center()) - s.radius() * s.radius()) < 1e-3f, "sphere missed " + i);
                }
                for (int i : got) {
                    assertTrue(s.overlaps(b.get(i)) || Math.abs(b.get(i).distanceSquared(s.center()) - s.radius() * s.radius()) < 1e-3f,
                            "sphere returned " + i);
                }
            }
        }
    }

    // ------------------------------------------------------------ updates

    @Test
    void refitKeepsQueriesCorrectAfterObjectsMove() {
        int n = 3000;
        BoundsArray b = randomScene(n);
        StaticBvh bvh = StaticBvh.build(b);
        BvhQuery q = new BvhQuery(bvh);
        for (int round = 0; round < 4; round++) {
            for (int i = 0; i < n; i++) {
                Aabbf old = b.get(i);
                Vec3f shift = rnd.nextVec3f().mul(0.3f * (round + 1));
                b.set(i, Aabbf.fromCenterHalfExtent(old.center().add(shift), old.halfSize()));
            }
            bvh.refit(b);
            assertWellFormed(bvh, b);
            IntList out = new IntList();
            Aabbf probe = Aabbf.fromCenterHalfExtent(rnd.nextVec3f(), Vec3f.splat(3f));
            q.overlapAabb(probe, b, out);
            out.sort();
            IntList expected = new IntList();
            for (int i = 0; i < n; i++) {
                if (b.get(i).overlaps(probe)) {
                    expected.add(i);
                }
            }
            assertArrayEquals(expected.toArray(), out.toArray(), "round " + round);
        }
        // a rebuilt tree is at least as good as the degraded refitted one
        assertTrue(StaticBvh.build(b).sahCost() <= bvh.sahCost() * 1.001f, "rebuild should not be worse than refit");
        assertThrows(IllegalArgumentException.class, () -> bvh.refit(randomScene(n - 1)));
    }

    @Test
    void emptyTreeAnswersEverythingWithNothing() {
        BoundsArray b = new BoundsArray(1);
        StaticBvh bvh = StaticBvh.build(b);
        BvhQuery q = new BvhQuery(bvh);
        VisibilitySet out = new VisibilitySet(8);
        assertEquals(0, q.frustum(randomFrustum(), b, out));
        assertFalse(q.raycastBounds(Rayf.of(Vec3f.ZERO, Vec3f.UNIT_X), 10f, b, new BvhQuery.BvhHit()));
        IntList list = new IntList();
        q.overlapAabb(Aabbf.of(Vec3f.ZERO, Vec3f.ONE), b, list);
        q.overlapSphere(Spheref.of(Vec3f.ZERO, 1f), b, list);
        assertTrue(list.isEmpty());
        assertEquals(0f, bvh.sahCost());
    }

    @Test
    void aRayAlongAFaceOfABoxTouchesIt() {
        // found by seed 23 of the nightly sweep: the origin lies exactly on a face and the direction is zero on that axis; the slab test then produced an empty interval
        BoundsArray b = new BoundsArray(3);
        b.add(10f, -56f, -3.5f, 12.75f, -55.5f, -1.5f);
        b.add(2f, 40f, -50f, 4f, 43f, -48f);
        b.add(26f, -2f, -32f, 28f, 0.5f, -29f);
        BvhQuery q = new BvhQuery(StaticBvh.build(b));
        BvhQuery.BvhHit hit = new BvhQuery.BvhHit();
        float inf = Float.POSITIVE_INFINITY;
        Rayf onTopFace = new Rayf(-0.3f, -55.5f, -2.5f, 1f, 0f, 0f);
        Rayf onBottomFace = new Rayf(-0.3f, -56f, -2.5f, 1f, 0f, 0f);
        Rayf onBackFace = new Rayf(-0.3f, -55.7f, -1.5f, 1f, 0f, 0f);
        Rayf justInside = new Rayf(-0.3f, -55.5001f, -2.5f, 1f, 0f, 0f);
        Rayf justOutside = new Rayf(-0.3f, -55.4999f, -2.5f, 1f, 0f, 0f);
        for (Rayf ray : new Rayf[] {onTopFace, onBottomFace, onBackFace, justInside}) {
            assertTrue(Intersectionf.rayAabb(ray, b.get(0), inf) < inf, "the reference counts touching as a hit: " + ray);
            assertTrue(q.raycastBounds(ray, inf, b, hit), "the BVH must hit " + ray);
            assertEquals(0, hit.primitive);
            assertEquals(10.3f, hit.t, 1e-3f);
        }
        assertFalse(q.raycastBounds(justOutside, inf, b, hit), "a hair above the top face is a miss");
    }
}
