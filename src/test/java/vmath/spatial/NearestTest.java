package vmath.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.bulk.BoundsArray;
import vmath.core.Rnd;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;

/** k-nearest-neighbour queries against a brute-force scan, for every structure that offers them. */
class NearestTest {

    final Rnd rnd = Rnd.create();

    /** Same formula as the queries use, so distances compare exactly. */
    static float dist2(Aabbf b, float x, float y, float z) {
        float dx = Math.max(Math.max(b.minX() - x, 0f), x - b.maxX());
        float dy = Math.max(Math.max(b.minY() - y, 0f), y - b.maxY());
        float dz = Math.max(Math.max(b.minZ() - z, 0f), z - b.maxZ());
        return dx * dx + dy * dy + dz * dz;
    }

    Aabbf randomBox(float spread) {
        Vec3f c = rnd.nextVec3f().mul(spread);
        Vec3f h = new Vec3f((float) rnd.range(0.02, 1.5), (float) rnd.range(0.02, 1.5), (float) rnd.range(0.02, 1.5));
        return Aabbf.fromCenterHalfExtent(c, h);
    }

    /** Expected result: the k smallest (distance, id) pairs. */
    static int[] expectedIds(List<Aabbf> boxes, List<Integer> ids, float x, float y, float z, int k) {
        List<int[]> order = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) {
            order.add(new int[] {i});
        }
        order.sort((a, b) -> {
            int c = Float.compare(dist2(boxes.get(a[0]), x, y, z), dist2(boxes.get(b[0]), x, y, z));
            return c != 0 ? c : Integer.compare(ids.get(a[0]), ids.get(b[0]));
        });
        int n = Math.min(k, boxes.size());
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = ids.get(order.get(i)[0]);
        }
        return out;
    }

    static void assertSameAsBruteForce(List<Aabbf> boxes, List<Integer> ids, float x, float y, float z, int k,
                                       Neighbors got, String what) {
        int[] want = expectedIds(boxes, ids, x, y, z, k);
        assertEquals(want.length, got.size(), what + ": count");
        for (int i = 0; i < want.length; i++) {
            assertEquals(want[i], got.index(i), what + ": rank " + i);
            int idx = ids.indexOf(want[i]);
            assertEquals(dist2(boxes.get(idx), x, y, z), got.distanceSquared(i), what + ": distance of rank " + i);
        }
    }

    @Test
    void neighborsKeepsTheKBestSortedAndBreaksTiesByIndex() {
        Neighbors n = new Neighbors(4);
        n.reset(3);
        float[] d = {5f, 1f, 3f, 1f, 9f, 0f, 3f};
        for (int i = 0; i < d.length; i++) {
            n.offer(10 + i, d[i]);
        }
        n.offer(99, Float.NaN); // never kept
        n.finish();
        assertEquals(3, n.size());
        assertEquals(15, n.index(0)); // distance 0
        assertEquals(11, n.index(1)); // distance 1, smaller index of the two ties
        assertEquals(13, n.index(2));
        assertEquals(1f, n.distanceSquared(2));
        assertThrows(IndexOutOfBoundsException.class, () -> n.index(3));
        assertThrows(IllegalArgumentException.class, () -> n.reset(5));
        assertThrows(IllegalArgumentException.class, () -> n.reset(0));
        assertThrows(IllegalArgumentException.class, () -> new Neighbors(0));
    }

    @Test
    void staticBvhMatchesBruteForce() {
        for (int trial = 0; trial < 60; trial++) {
            int n = (int) rnd.range(0, 400);
            BoundsArray bounds = new BoundsArray(Math.max(n, 1));
            List<Aabbf> boxes = new ArrayList<>();
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Aabbf b = randomBox(12f);
                bounds.add(b);
                boxes.add(b);
                ids.add(i);
            }
            StaticBvh bvh = StaticBvh.build(bounds, 1 + (int) rnd.range(0, 8));
            BvhQuery q = new BvhQuery(bvh);
            Neighbors out = new Neighbors(24);
            for (int i = 0; i < 12; i++) {
                Vec3f p = rnd.nextVec3f().mul(16f);
                int k = 1 + (int) rnd.range(0, 24);
                out.reset(k);
                q.nearest(p.x(), p.y(), p.z(), bounds, out);
                assertSameAsBruteForce(boxes, ids, p.x(), p.y(), p.z(), k, out, "bvh n=" + n + " k=" + k);
            }
        }
    }

    @Test
    void dynamicTreeMatchesBruteForceAfterMovesAndRemovals() {
        for (int trial = 0; trial < 30; trial++) {
            DynamicAabbTree tree = new DynamicAabbTree(0.05f, 8);
            List<Aabbf> boxes = new ArrayList<>();
            List<Integer> ids = new ArrayList<>();
            List<Integer> handles = new ArrayList<>();
            int n = 20 + (int) rnd.range(0, 300);
            for (int i = 0; i < n; i++) {
                Aabbf b = randomBox(12f);
                boxes.add(b);
                ids.add(i);
                handles.add(tree.insert(b, i));
            }
            for (int i = 0; i < n / 2; i++) {
                int k = (int) rnd.range(0, boxes.size());
                Aabbf b = randomBox(12f);
                boxes.set(k, b);
                tree.move(handles.get(k), b, 0f, 0f, 0f);
            }
            for (int i = 0; i < n / 5; i++) {
                int k = (int) rnd.range(0, boxes.size());
                tree.remove(handles.get(k));
                handles.remove(k);
                boxes.remove(k);
                ids.remove(k);
            }
            DynamicAabbTree.Query q = tree.newQuery();
            Neighbors out = new Neighbors(16);
            for (int i = 0; i < 12; i++) {
                Vec3f p = rnd.nextVec3f().mul(16f);
                int k = 1 + (int) rnd.range(0, 16);
                out.reset(k);
                q.nearest(p.x(), p.y(), p.z(), out);
                assertSameAsBruteForce(boxes, ids, p.x(), p.y(), p.z(), k, out, "dynamic tree n=" + boxes.size() + " k=" + k);
            }
            tree.optimize();
            out.reset(5);
            q.nearest(0f, 0f, 0f, out);
            assertSameAsBruteForce(boxes, ids, 0f, 0f, 0f, 5, out, "after optimize");
        }
    }

    @Test
    void emptyStructuresAnswerWithNothing() {
        Neighbors out = new Neighbors(3);
        out.reset(3);
        new DynamicAabbTree().newQuery().nearest(1f, 2f, 3f, out);
        assertEquals(0, out.size());
        BoundsArray none = new BoundsArray(1);
        new BvhQuery(StaticBvh.build(none)).nearest(1f, 2f, 3f, none, out);
        assertEquals(0, out.size());
    }
}
