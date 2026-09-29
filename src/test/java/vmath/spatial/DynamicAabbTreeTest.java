package vmath.spatial;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.bulk.BoundsArray;
import vmath.bulk.IntList;
import vmath.bulk.VisibilitySet;
import vmath.core.Mat4f;
import vmath.core.Rnd;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;
import vmath.geo.Intersectionf;
import vmath.geo.Rayf;
import vmath.geo.Spheref;

class DynamicAabbTreeTest {

    final Rnd rnd = Rnd.create();

    /** Reference model: what the tree should contain, kept in plain arrays. */
    private static final class Model {
        final List<Integer> handles = new ArrayList<>();
        final List<Aabbf> boxes = new ArrayList<>();
        final List<Integer> ids = new ArrayList<>();
        int nextId;

        int add(int handle, Aabbf box) {
            handles.add(handle);
            boxes.add(box);
            ids.add(nextId);
            return nextId++;
        }
    }

    private Aabbf randomBox() {
        Vec3f c = rnd.nextVec3f().mul(6f);
        Vec3f h = new Vec3f((float) rnd.range(0.05, 1.2), (float) rnd.range(0.05, 1.2), (float) rnd.range(0.05, 1.2));
        return Aabbf.fromCenterHalfExtent(c, h);
    }

    private int insert(DynamicAabbTree tree, Model m, Aabbf box) {
        int id = m.nextId;
        int handle = tree.insert(box, id);
        m.add(handle, box);
        return handle;
    }

    // ------------------------------------------------------------ basics

    @Test
    void insertQueryRemoveOnASmallTree() {
        DynamicAabbTree tree = new DynamicAabbTree(0.1f, 4);
        assertEquals(0, tree.size());
        assertEquals(0, tree.height());
        assertTrue(tree.totalBounds().isEmpty());
        tree.validate();

        int a = tree.insert(Aabbf.of(new Vec3f(0f, 0f, 0f), new Vec3f(1f, 1f, 1f)), 10);
        int b = tree.insert(Aabbf.of(new Vec3f(5f, 5f, 5f), new Vec3f(6f, 6f, 6f)), 20);
        int c = tree.insert(Aabbf.of(new Vec3f(-3f, 0f, 0f), new Vec3f(-2f, 1f, 1f)), 30);
        tree.validate();
        assertEquals(3, tree.size());
        assertEquals(10, tree.userData(a));
        assertEquals(20, tree.userData(b));
        assertTrue(tree.isValid(c));

        DynamicAabbTree.Query q = tree.newQuery();
        IntList out = new IntList();
        q.overlapAabb(Aabbf.of(new Vec3f(-0.5f, -0.5f, -0.5f), new Vec3f(0.5f, 0.5f, 0.5f)), out);
        assertArrayEquals(new int[] {10}, out.toArray());
        out.clear();
        q.overlapAabb(Aabbf.of(new Vec3f(-10f, -10f, -10f), new Vec3f(10f, 10f, 10f)), out);
        out.sort();
        assertArrayEquals(new int[] {10, 20, 30}, out.toArray());

        tree.remove(b);
        tree.validate();
        assertEquals(2, tree.size());
        assertFalse(tree.isValid(b), "a removed handle is no longer valid");
        out.clear();
        q.overlapAabb(Aabbf.of(new Vec3f(-10f, -10f, -10f), new Vec3f(10f, 10f, 10f)), out);
        out.sort();
        assertArrayEquals(new int[] {10, 30}, out.toArray());

        tree.remove(a);
        tree.remove(c);
        tree.validate();
        assertEquals(0, tree.size());
        assertTrue(tree.totalBounds().isEmpty());
    }

    @Test
    void invalidHandlesAndArgumentsAreRejected() {
        DynamicAabbTree tree = new DynamicAabbTree();
        int h = tree.insert(randomBox(), 1);
        assertThrows(IllegalArgumentException.class, () -> tree.remove(h + 1000));
        assertThrows(IllegalArgumentException.class, () -> tree.userData(-1));
        assertThrows(IllegalArgumentException.class, () -> tree.move(999, randomBox(), 0f, 0f, 0f));
        tree.remove(h);
        assertThrows(IllegalArgumentException.class, () -> tree.remove(h), "double remove");
        assertThrows(IllegalArgumentException.class, () -> new DynamicAabbTree(-1f, 8));
        assertThrows(IllegalArgumentException.class, () -> new DynamicAabbTree(Float.NaN, 8));
    }

    /**
     * Reproducible fuzzing independent of {@code -Dvmath.seed}: many fixed seeds, validating after every few operations, and
     * reporting the seed and step of the first violation so a failure can be replayed exactly.
     */
    @Test
    void invariantsHoldAfterEveryOperationAcrossManySeeds() {
        for (long seed = 0; seed < 300; seed++) {
            java.util.SplittableRandom rng = new java.util.SplittableRandom(seed);
            DynamicAabbTree tree = new DynamicAabbTree(0.05f, 1);
            List<Integer> handles = new ArrayList<>();
            for (int step = 0; step < 400; step++) {
                String where = "seed " + seed + ", step " + step;
                double kind = rng.nextDouble();
                try {
                    if (handles.isEmpty() || kind < 0.6) {
                        float x = (float) rng.nextDouble(-20, 20), y = (float) rng.nextDouble(-20, 20), z = (float) rng.nextDouble(-20, 20);
                        float h = (float) rng.nextDouble(0.05, 1.2);
                        handles.add(tree.insert(x - h, y - h, z - h, x + h, y + h, z + h, step));
                    } else if (kind < 0.8) {
                        int k = rng.nextInt(handles.size());
                        float x = (float) rng.nextDouble(-20, 20), y = (float) rng.nextDouble(-20, 20), z = (float) rng.nextDouble(-20, 20);
                        float h = (float) rng.nextDouble(0.05, 1.2);
                        tree.move(handles.get(k), x - h, y - h, z - h, x + h, y + h, z + h, 0f, 0f, 0f);
                    } else {
                        tree.remove(handles.remove(rng.nextInt(handles.size())));
                    }
                    tree.validate();
                } catch (IllegalStateException e) {
                    throw new AssertionError(where + " (" + handles.size() + " objects): " + e.getMessage(), e);
                }
            }
            assertEquals(handles.size(), tree.size(), "seed " + seed);
        }
    }

    @Test
    void growsFromATinyInitialCapacityAndClears() {
        DynamicAabbTree tree = new DynamicAabbTree(0.05f, 1);
        Model m = new Model();
        for (int i = 0; i < 2000; i++) {
            insert(tree, m, randomBox());
        }
        tree.validate();
        assertEquals(2000, tree.size());
        tree.clear();
        tree.validate();
        assertEquals(0, tree.size());
        for (int i = 0; i < 100; i++) {
            tree.insert(randomBox(), i);
        }
        tree.validate();
        assertEquals(100, tree.size());
    }

    // ------------------------------------------------------------ fat boxes

    @Test
    void fatBoxContainsTheTightBoxAndSmallMovesAreFree() {
        DynamicAabbTree tree = new DynamicAabbTree(0.5f, 16);
        Aabbf box = Aabbf.of(new Vec3f(0f, 0f, 0f), new Vec3f(1f, 1f, 1f));
        int h = tree.insert(box, 7);
        tree.insert(Aabbf.of(new Vec3f(9f, 9f, 9f), new Vec3f(10f, 10f, 10f)), 8);
        assertTrue(tree.fatBounds(h).contains(tree.tightBounds(h)));
        assertEquals(-0.5f, tree.fatBounds(h).minX());
        assertEquals(1.5f, tree.fatBounds(h).maxX());

        long before = tree.reinsertions();
        // a drift well inside the fat box does not touch the tree, but the tight box follows the object
        Aabbf nudged = Aabbf.fromCenterHalfExtent(new Vec3f(0.6f, 0.5f, 0.5f), new Vec3f(0.5f, 0.5f, 0.5f));
        assertFalse(tree.move(h, nudged, 0.1f, 0f, 0f), "inside the fat box: nothing to do");
        assertEquals(before, tree.reinsertions());
        assertEquals(nudged, tree.tightBounds(h));
        tree.validate();

        // leaving the fat box reinserts it, stretched ahead along the movement
        Aabbf far = Aabbf.of(new Vec3f(3f, 0f, 0f), new Vec3f(4f, 1f, 1f));
        assertTrue(tree.move(h, far, 1f, 0f, 0f));
        assertEquals(before + 1, tree.reinsertions());
        Aabbf fat = tree.fatBounds(h);
        assertTrue(fat.contains(far));
        assertEquals(3f - 0.5f, fat.minX(), 1e-6f, "no stretch behind the movement");
        assertEquals(4f + 0.5f + 2f, fat.maxX(), 1e-6f, "stretched 2x the displacement ahead");
        tree.validate();
    }

    @Test
    void anObjectThatShrinksInAHugeFatBoxIsRefitted() {
        DynamicAabbTree tree = new DynamicAabbTree(0.1f, 16);
        int h = tree.insert(Aabbf.of(new Vec3f(0f, 0f, 0f), new Vec3f(1f, 1f, 1f)), 1);
        tree.insert(Aabbf.of(new Vec3f(20f, 0f, 0f), new Vec3f(21f, 1f, 1f)), 2);
        // a fast move stretches the fat box far ahead
        assertTrue(tree.move(h, Aabbf.of(new Vec3f(5f, 0f, 0f), new Vec3f(6f, 1f, 1f)), 10f, 0f, 0f));
        assertTrue(tree.fatBounds(h).maxX() > 20f);
        // the object then stops: its tight box is inside the fat box, but the fat box is far too loose, so it is refitted
        assertTrue(tree.move(h, Aabbf.of(new Vec3f(5f, 0f, 0f), new Vec3f(6f, 1f, 1f)), 0f, 0f, 0f));
        assertTrue(tree.fatBounds(h).maxX() < 7f, "refitted tightly: " + tree.fatBounds(h));
        tree.validate();
    }

    // ------------------------------------------------------------ balance and quality

    @Test
    void sortedInsertionStaysBalanced() {
        DynamicAabbTree tree = new DynamicAabbTree(0.01f, 16);
        int n = 10_000;
        for (int i = 0; i < n; i++) {
            tree.insert(i, 0f, 0f, i + 0.5f, 1f, 1f, i);
        }
        tree.validate();
        double log2 = Math.log(n) / Math.log(2);
        assertTrue(tree.height() <= 2 * log2, "height " + tree.height() + " should be O(log n), log2(n) = " + log2);
    }

    @Test
    void qualityStaysCloseToAFreshStaticBuild() {
        int n = 3000;
        DynamicAabbTree tree = new DynamicAabbTree(0.05f, n);
        BoundsArray flat = new BoundsArray(n);
        Model m = new Model();
        for (int i = 0; i < n; i++) {
            Aabbf b = randomBox();
            insert(tree, m, b);
            flat.add(b);
        }
        float staticCost = StaticBvh.build(flat).sahCost();
        assertTrue(tree.sahCost() < staticCost * 4f, "dynamic " + tree.sahCost() + " vs static " + staticCost);

        // after a lot of movement the incremental tree must not have degraded badly either
        for (int round = 0; round < 30; round++) {
            for (int i = 0; i < n; i++) {
                Aabbf old = m.boxes.get(i);
                Aabbf moved = Aabbf.fromCenterHalfExtent(old.center().add(rnd.nextVec3f().mul(0.15f)), old.halfSize());
                m.boxes.set(i, moved);
                flat.set(i, moved);
                tree.move(m.handles.get(i), moved, 0f, 0f, 0f);
            }
        }
        tree.validate();
        float freshCost = StaticBvh.build(flat).sahCost();
        assertTrue(tree.sahCost() < freshCost * 5f, "after moves: dynamic " + tree.sahCost() + " vs fresh static " + freshCost);
    }

    // ------------------------------------------------------------ randomized comparison with brute force

    private Frustumf randomFrustum() {
        float fovy = (float) rnd.range(0.5, 1.6), aspect = (float) rnd.range(0.8, 2), near = (float) rnd.range(0.1, 1);
        Vec3f eye = rnd.nextVec3f();
        Vec3f dir = rnd.nextVec3f();
        Mat4f view = Mat4f.lookAt(eye, eye.add(dir), Math.abs(dir.normalize().y()) > 0.95f ? Vec3f.UNIT_X : Vec3f.UNIT_Y);
        return Frustumf.fromViewProjection(Mat4f.perspective(fovy, aspect, near, near * 80f, true).mul(view), DepthRange.ZERO_TO_ONE);
    }

    private static double slack(Frustumf f, Aabbf b) {
        double min = Double.POSITIVE_INFINITY;
        for (int p = 0; p < 6; p++) {
            var pl = f.plane(p);
            double px = pl.nx() >= 0 ? b.maxX() : b.minX(), py = pl.ny() >= 0 ? b.maxY() : b.minY();
            double pz = pl.nz() >= 0 ? b.maxZ() : b.minZ();
            min = Math.min(min, (double) pl.d() + (double) pl.nx() * px + (double) pl.ny() * py + (double) pl.nz() * pz);
        }
        return min;
    }

    private void assertMatchesBruteForce(DynamicAabbTree tree, Model m, String when) {
        DynamicAabbTree.Query q = tree.newQuery();
        IntList out = new IntList();
        for (int rep = 0; rep < 6; rep++) {
            // AABB overlap: exactly the same predicate, so exactly the same set
            Aabbf probe = Aabbf.fromCenterHalfExtent(rnd.nextVec3f().mul(4f), Vec3f.splat((float) rnd.range(0.3, 4)));
            out.clear();
            q.overlapAabb(probe, out);
            out.sort();
            IntList expected = new IntList();
            for (int i = 0; i < m.boxes.size(); i++) {
                if (m.boxes.get(i).overlaps(probe)) {
                    expected.add(m.ids.get(i));
                }
            }
            expected.sort();
            assertArrayEquals(expected.toArray(), out.toArray(), when + ": overlapAabb");

            // sphere: the same up to rounding at the boundary
            Spheref s = Spheref.of(rnd.nextVec3f().mul(4f), (float) rnd.range(0.3, 4));
            out.clear();
            q.overlapSphere(s, out);
            List<Integer> got = new ArrayList<>();
            for (int k = 0; k < out.size(); k++) {
                got.add(out.get(k));
            }
            for (int i = 0; i < m.boxes.size(); i++) {
                double d2 = m.boxes.get(i).distanceSquared(s.center());
                if (Math.abs(d2 - (double) s.radius() * s.radius()) > 1e-3) {
                    assertEquals(d2 < (double) s.radius() * s.radius(), got.contains(m.ids.get(i)), when + ": overlapSphere id " + m.ids.get(i));
                }
            }

            // frustum: away from the boundary the tree and the oracle agree
            Frustumf f = randomFrustum();
            IntList visible = new IntList();
            int reported = q.frustum(f, visible);
            assertEquals(visible.size(), reported);
            VisibilitySet set = new VisibilitySet(m.nextId + 1);
            assertEquals(reported, q.frustum(f, set), "the set and list overloads agree");
            for (int i = 0; i < m.boxes.size(); i++) {
                double sl = slack(f, m.boxes.get(i));
                if (Math.abs(sl) > 1e-3) {
                    boolean inList = false;
                    for (int k = 0; k < visible.size(); k++) {
                        inList |= visible.get(k) == m.ids.get(i);
                    }
                    assertEquals(sl >= 0, inList, when + ": frustum id " + m.ids.get(i) + " slack " + sl);
                    assertEquals(inList, set.get(m.ids.get(i)), when + ": set and list agree");
                }
            }

            // ray: nearest box entered
            Vec3f aim = m.boxes.get((int) rnd.range(0, m.boxes.size())).center();
            Vec3f dir = rnd.nextVec3f().normalize();
            Rayf ray = Rayf.of(aim.sub(dir.mul((float) rnd.range(0, 20))), dir);
            float bestT = Float.POSITIVE_INFINITY;
            for (Aabbf b : m.boxes) {
                bestT = Math.min(bestT, Intersectionf.rayAabb(ray, b, Float.POSITIVE_INFINITY));
            }
            BvhQuery.BvhHit hit = new BvhQuery.BvhHit();
            boolean found = q.raycast(ray, Float.POSITIVE_INFINITY, null, hit);
            assertEquals(bestT != Float.POSITIVE_INFINITY, found, when + ": raycast hit/miss");
            if (found) {
                assertEquals(bestT, hit.t, 1e-3f + 1e-4f * bestT, when + ": raycast distance");
            }
        }
    }

    @Test
    void randomInsertMoveRemoveSequencesMatchBruteForce() {
        DynamicAabbTree tree = new DynamicAabbTree(0.08f, 8);
        Model m = new Model();
        for (int round = 0; round < 25; round++) {
            for (int i = 0; i < 60; i++) {
                insert(tree, m, randomBox());
            }
            for (int i = 0; i < 80 && !m.boxes.isEmpty(); i++) {
                int k = (int) rnd.range(0, m.boxes.size());
                Aabbf old = m.boxes.get(k);
                Vec3f delta = rnd.nextVec3f().mul((float) rnd.range(0.005, 0.4));
                Aabbf moved = Aabbf.fromCenterHalfExtent(old.center().add(delta), old.halfSize());
                m.boxes.set(k, moved);
                tree.move(m.handles.get(k), moved, delta.x(), delta.y(), delta.z());
            }
            for (int i = 0; i < 25 && m.boxes.size() > 1; i++) {
                int k = (int) rnd.range(0, m.boxes.size());
                tree.remove(m.handles.get(k));
                m.handles.remove(k);
                m.boxes.remove(k);
                m.ids.remove(k);
            }
            tree.validate();
            assertEquals(m.boxes.size(), tree.size());
            assertMatchesBruteForce(tree, m, "round " + round);
        }
        // handles are stable: every remaining object still maps to its user data and its own box
        for (int i = 0; i < m.boxes.size(); i++) {
            assertEquals(m.ids.get(i), tree.userData(m.handles.get(i)));
            assertEquals(m.boxes.get(i), tree.tightBounds(m.handles.get(i)));
            assertTrue(tree.fatBounds(m.handles.get(i)).contains(m.boxes.get(i)));
        }
    }

    @Test
    void handlesSurviveHeavyRestructuring() {
        DynamicAabbTree tree = new DynamicAabbTree(0.02f, 8);
        Model m = new Model();
        for (int i = 0; i < 1500; i++) {
            insert(tree, m, randomBox());
        }
        // teleport objects around: every move restructures the tree, but leaf indices must not change
        for (int i = 0; i < 6000; i++) {
            int k = (int) rnd.range(0, m.boxes.size());
            Aabbf b = randomBox();
            m.boxes.set(k, b);
            tree.move(m.handles.get(k), b, 0f, 0f, 0f);
        }
        tree.validate();
        for (int i = 0; i < m.boxes.size(); i++) {
            assertEquals(m.ids.get(i), tree.userData(m.handles.get(i)), "handle " + m.handles.get(i));
        }
    }

    @Test
    void removedHandlesAreRecycled() {
        DynamicAabbTree tree = new DynamicAabbTree(0.1f, 4);
        List<Integer> handles = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            handles.add(tree.insert(randomBox(), i));
        }
        for (int h : handles) {
            tree.remove(h);
        }
        tree.validate();
        int again = tree.insert(randomBox(), 99);
        assertTrue(handles.contains(again) || again >= 0, "a freed node index may be reused");
        assertEquals(99, tree.userData(again));
        assertEquals(1, tree.size());
    }

    @Test
    void optimizeRenumbersNodesButKeepsHandlesShapeAndAnswers() {
        for (int capacity : new int[] {4, 7, 64}) {
            DynamicAabbTree tree = new DynamicAabbTree(0.08f, capacity);
            Model m = new Model();
            new DynamicAabbTree().optimize(); // empty: nothing to do
            for (int round = 0; round < 30; round++) {
                for (int i = 0; i < 40; i++) {
                    insert(tree, m, randomBox());
                }
                for (int i = 0; i < 60 && !m.boxes.isEmpty(); i++) {
                    int k = (int) rnd.range(0, m.boxes.size());
                    Aabbf b = randomBox();
                    m.boxes.set(k, b);
                    tree.move(m.handles.get(k), b, 0f, 0f, 0f);
                }
                for (int i = 0; i < 20 && m.boxes.size() > 1; i++) {
                    int k = (int) rnd.range(0, m.boxes.size());
                    tree.remove(m.handles.get(k));
                    m.handles.remove(k);
                    m.boxes.remove(k);
                    m.ids.remove(k);
                }
                int height = tree.height();
                float sah = tree.sahCost();
                tree.optimize();
                tree.validate();
                assertEquals(height, tree.height(), "optimize must not change the shape");
                assertEquals(sah, tree.sahCost(), 1e-3f * Math.max(1f, sah), "optimize must not change the shape");
                assertEquals(m.boxes.size(), tree.size());
                for (int i = 0; i < m.boxes.size(); i++) {
                    int h = m.handles.get(i);
                    assertTrue(tree.isValid(h));
                    assertEquals(m.ids.get(i), tree.userData(h));
                    assertEquals(m.boxes.get(i), tree.tightBounds(h));
                }
                assertMatchesBruteForce(tree, m, "capacity " + capacity + " round " + round);
            }
            tree.optimize(); // idempotent
            tree.validate();
        }
    }

    // ------------------------------------------------------------ pipeline stage and edge cases

    @Test
    void stageMatchesTheFlatKernelAndKeepsEarlierRejections() {
        int n = 3000;
        DynamicAabbTree tree = new DynamicAabbTree(0.05f, n);
        BoundsArray flat = new BoundsArray(n);
        for (int i = 0; i < n; i++) {
            Aabbf b = randomBox();
            flat.add(b);
            tree.insert(b, i);
        }
        for (int rep = 0; rep < 8; rep++) {
            Frustumf f = randomFrustum();
            CullContext ctx = new CullContext(f, Vec3f.ZERO, 0f);
            VisibilitySet viaTree = new VisibilitySet(n), viaFlat = new VisibilitySet(n);
            viaTree.setAll(n);
            viaFlat.setAll(n);
            for (int i = 0; i < n; i += 7) {
                viaTree.clear(i);
                viaFlat.clear(i);
            }
            new DynamicBvhStage(tree).cull(ctx, flat, viaTree);
            new CullStages.Frustum(FrustumKernels.scalar()).cull(ctx, flat, viaFlat);
            for (int i = 0; i < n; i++) {
                if (Math.abs(slack(f, flat.get(i))) > 1e-3) {
                    assertEquals(viaFlat.get(i), viaTree.get(i), "object " + i);
                }
                assertTrue(i % 7 != 0 || !viaTree.get(i), "an object rejected earlier stays rejected");
            }
        }
    }

    @Test
    void emptyTreeAnswersEverythingWithNothing() {
        DynamicAabbTree tree = new DynamicAabbTree();
        DynamicAabbTree.Query q = tree.newQuery();
        IntList out = new IntList();
        assertEquals(0, q.frustum(randomFrustum(), out));
        assertEquals(0, q.frustum(randomFrustum(), new VisibilitySet(8)));
        q.overlapAabb(randomBox(), out);
        q.overlapSphere(Spheref.of(Vec3f.ZERO, 5f), out);
        assertTrue(out.isEmpty());
        assertFalse(q.raycast(Rayf.of(Vec3f.ZERO, Vec3f.UNIT_X), 100f, null, new BvhQuery.BvhHit()));
        assertEquals(0f, tree.sahCost());
    }

    @Test
    void raycastWithANarrowPhaseFindsTheNearestExactHit() {
        // objects are spheres inside their boxes: the exact test rejects rays that only clip a box corner
        DynamicAabbTree tree = new DynamicAabbTree(0.05f, 64);
        List<Spheref> spheres = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            Spheref s = Spheref.of(rnd.nextVec3f().mul(3f), (float) rnd.range(0.2, 0.8));
            spheres.add(s);
            tree.insert(s.aabb(), i);
        }
        DynamicAabbTree.Query q = tree.newQuery();
        BvhQuery.PrimitiveTest test = (id, ray, tMax) -> Intersectionf.raySphere(ray, spheres.get(id), tMax);
        BvhQuery.BvhHit hit = new BvhQuery.BvhHit();
        int hits = 0;
        for (int rep = 0; rep < 300; rep++) {
            Vec3f aim = spheres.get((int) rnd.range(0, spheres.size())).center().add(rnd.nextVec3f().mul(0.05f));
            Vec3f dir = rnd.nextVec3f().normalize();
            Rayf ray = Rayf.of(aim.sub(dir.mul(15f)), dir);
            float bestT = Float.POSITIVE_INFINITY;
            for (Spheref s : spheres) {
                bestT = Math.min(bestT, Intersectionf.raySphere(ray, s, Float.POSITIVE_INFINITY));
            }
            boolean found = q.raycast(ray, Float.POSITIVE_INFINITY, test, hit);
            assertEquals(bestT != Float.POSITIVE_INFINITY, found, "ray " + rep);
            if (found) {
                hits++;
                assertEquals(bestT, hit.t, 1e-3f + 1e-4f * bestT);
            }
        }
        assertTrue(hits > 100, "too few hits: " + hits);
    }
}
