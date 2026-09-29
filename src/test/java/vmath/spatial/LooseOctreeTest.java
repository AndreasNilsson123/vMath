package vmath.spatial;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.bulk.IntList;
import vmath.core.Rnd;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;
import vmath.geo.Spheref;

class LooseOctreeTest {

    final Rnd rnd = Rnd.create();

    private static final class Model {
        final List<Integer> handles = new ArrayList<>();
        final List<Aabbf> boxes = new ArrayList<>();
        final List<Integer> ids = new ArrayList<>();
        int nextId;
    }

    /** Sizes span three orders of magnitude, and some centres fall outside the world cube (half size 16). */
    private Aabbf randomBox() {
        float size = (float) Math.pow(10.0, rnd.range(-1.7, 1.0));
        Vec3f c = rnd.nextVec3f().mul(rnd.range(0, 1) < 0.1 ? 40f : 15f);
        return Aabbf.fromCenterHalfExtent(c, new Vec3f(size * (float) rnd.range(0.2, 1), size * (float) rnd.range(0.2, 1),
                size * (float) rnd.range(0.2, 1)));
    }

    private void add(LooseOctree t, Model m, Aabbf b) {
        m.handles.add(t.insert(b, m.nextId));
        m.boxes.add(b);
        m.ids.add(m.nextId++);
    }

    private static int[] sorted(IntList l) {
        int[] a = l.toArray();
        java.util.Arrays.sort(a);
        return a;
    }

    private void assertQueriesMatch(LooseOctree t, Model m, String when) {
        LooseOctree.Query q = t.newQuery();
        IntList out = new IntList();
        for (int i = 0; i < 10; i++) {
            Aabbf probe = Aabbf.fromCenterHalfExtent(rnd.nextVec3f().mul(18f),
                    new Vec3f((float) rnd.range(0.05, 8), (float) rnd.range(0.05, 8), (float) rnd.range(0.05, 8)));
            out.clear();
            q.overlapAabb(probe, out);
            List<Integer> want = new ArrayList<>();
            for (int k = 0; k < m.boxes.size(); k++) {
                Aabbf b = m.boxes.get(k);
                if (b.minX() <= probe.maxX() && b.maxX() >= probe.minX() && b.minY() <= probe.maxY()
                        && b.maxY() >= probe.minY() && b.minZ() <= probe.maxZ() && b.maxZ() >= probe.minZ()) {
                    want.add(m.ids.get(k));
                }
            }
            want.sort(null);
            assertArrayEquals(want.stream().mapToInt(Integer::intValue).toArray(), sorted(out), when + ": aabb overlap");

            Spheref s = Spheref.of(rnd.nextVec3f().mul(18f), (float) rnd.range(0.05, 8));
            out.clear();
            q.overlapSphere(s, out);
            want.clear();
            float r2 = s.radius() * s.radius();
            for (int k = 0; k < m.boxes.size(); k++) {
                if (NearestTest.dist2(m.boxes.get(k), s.cx(), s.cy(), s.cz()) <= r2) {
                    want.add(m.ids.get(k));
                }
            }
            want.sort(null);
            assertArrayEquals(want.stream().mapToInt(Integer::intValue).toArray(), sorted(out), when + ": sphere overlap");
        }
        Neighbors nn = new Neighbors(12);
        for (int i = 0; i < 10; i++) {
            Vec3f p = rnd.nextVec3f().mul(rnd.range(0, 1) < 0.2 ? 60f : 18f);
            int k = 1 + (int) rnd.range(0, 12);
            nn.reset(k);
            q.nearest(p.x(), p.y(), p.z(), nn);
            NearestTest.assertSameAsBruteForce(m.boxes, m.ids, p.x(), p.y(), p.z(), k, nn, when + ": nearest k=" + k);
        }
    }

    @Test
    void placementFollowsSizeAndEmptyNodesDisappear() {
        LooseOctree t = new LooseOctree(0f, 0f, 0f, 16f, 6);
        assertEquals(1, t.nodeCount());
        int small = t.insert(1f, 1f, 1f, 1.1f, 1.1f, 1.1f, 1);
        int mid = t.insert(2f, 2f, 2f, 6f, 6f, 6f, 2);
        int huge = t.insert(-20f, -20f, -20f, 20f, 20f, 20f, 3);
        int outside = t.insert(100f, 100f, 100f, 101f, 101f, 101f, 4);
        t.validate();
        assertTrue(t.depthOf(small) > t.depthOf(mid), "smaller objects go deeper");
        assertEquals(0, t.depthOf(huge));
        assertEquals(0, t.depthOf(outside), "a centre outside the world stays at the root");
        assertTrue(t.nodeCount() > 1);
        t.remove(small);
        t.remove(mid);
        t.validate();
        assertEquals(1, t.nodeCount(), "nodes that became empty are removed again");
        t.remove(huge);
        t.remove(outside);
        assertEquals(0, t.size());
        t.validate();
    }

    @Test
    void movingWithinTheSameNodeDoesNotTouchTheTree() {
        LooseOctree t = new LooseOctree(0f, 0f, 0f, 16f, 8);
        int h = t.insert(1f, 1f, 1f, 1.5f, 1.5f, 1.5f, 9);
        assertFalse(t.move(h, 1.01f, 1.01f, 1.01f, 1.51f, 1.51f, 1.51f));
        assertEquals(new Aabbf(1.01f, 1.01f, 1.01f, 1.51f, 1.51f, 1.51f), t.bounds(h));
        assertTrue(t.move(h, -9f, -9f, -9f, -8.5f, -8.5f, -8.5f));
        t.validate();
        IntList out = new IntList();
        t.newQuery().overlapAabb(Aabbf.of(new Vec3f(-9f, -9f, -9f), new Vec3f(-8f, -8f, -8f)), out);
        assertArrayEquals(new int[] {9}, out.toArray());
    }

    @Test
    void badArgumentsAndEmptyTree() {
        assertThrows(IllegalArgumentException.class, () -> new LooseOctree(0f, 0f, 0f, 0f, 4));
        assertThrows(IllegalArgumentException.class, () -> new LooseOctree(0f, 0f, 0f, 1f, 0));
        assertThrows(IllegalArgumentException.class, () -> new LooseOctree(0f, 0f, 0f, 1f, 99));
        assertThrows(IllegalArgumentException.class, () -> new LooseOctree(Float.NaN, 0f, 0f, 1f, 4));
        LooseOctree t = new LooseOctree(8f);
        assertThrows(IllegalArgumentException.class, () -> t.insert(Float.NaN, 0f, 0f, 1f, 1f, 1f, 0));
        assertThrows(IllegalArgumentException.class, () -> t.insert(1f, 0f, 0f, 0f, 1f, 1f, 0));
        assertThrows(IllegalArgumentException.class, () -> t.userData(0));
        IntList out = new IntList();
        t.newQuery().overlapAabb(Aabbf.of(new Vec3f(-1f, -1f, -1f), new Vec3f(1f, 1f, 1f)), out);
        assertEquals(0, out.size());
        Neighbors nn = new Neighbors(2);
        nn.reset(2);
        t.newQuery().nearest(0f, 0f, 0f, nn);
        assertEquals(0, nn.size());
        t.clear();
        t.validate();
    }

    @Test
    void randomOperationsMatchBruteForce() {
        for (int depth : new int[] {2, 5, 9}) {
            LooseOctree t = new LooseOctree(0f, 0f, 0f, 16f, depth);
            Model m = new Model();
            for (int round = 0; round < 14; round++) {
                for (int i = 0; i < 50; i++) {
                    add(t, m, randomBox());
                }
                for (int i = 0; i < 70 && !m.boxes.isEmpty(); i++) {
                    int k = (int) rnd.range(0, m.boxes.size());
                    Aabbf old = m.boxes.get(k);
                    Vec3f d = rnd.nextVec3f().mul((float) rnd.range(0.01, 4));
                    Aabbf moved = Aabbf.fromCenterHalfExtent(old.center().add(d), old.halfSize());
                    m.boxes.set(k, moved);
                    t.move(m.handles.get(k), moved);
                }
                for (int i = 0; i < 25 && m.boxes.size() > 1; i++) {
                    int k = (int) rnd.range(0, m.boxes.size());
                    t.remove(m.handles.get(k));
                    m.handles.remove(k);
                    m.boxes.remove(k);
                    m.ids.remove(k);
                }
                t.validate();
                assertEquals(m.boxes.size(), t.size());
                assertQueriesMatch(t, m, "depth " + depth + " round " + round);
            }
            for (int i = 0; i < m.boxes.size(); i++) {
                assertEquals(m.ids.get(i), t.userData(m.handles.get(i)));
                assertEquals(m.boxes.get(i), t.bounds(m.handles.get(i)));
            }
            while (!m.handles.isEmpty()) {
                t.remove(m.handles.remove(m.handles.size() - 1));
            }
            t.validate();
            assertEquals(1, t.nodeCount(), "all nodes but the root are gone");
        }
    }

    @Test
    void manyObjectsGrowThePools() {
        LooseOctree t = new LooseOctree(0f, 0f, 0f, 16f, 8);
        Model m = new Model();
        for (int i = 0; i < 4000; i++) {
            add(t, m, randomBox());
        }
        t.validate();
        assertQueriesMatch(t, m, "4000 objects");
    }
}
