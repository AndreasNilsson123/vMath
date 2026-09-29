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

class UniformGridTest {

    final Rnd rnd = Rnd.create();

    /** What the grid should contain, in plain lists. */
    private static final class Model {
        final List<Integer> handles = new ArrayList<>();
        final List<Aabbf> boxes = new ArrayList<>();
        final List<Integer> ids = new ArrayList<>();
        int nextId;
    }

    private Aabbf randomBox(float spread, float maxHalf) {
        Vec3f c = rnd.nextVec3f().mul(spread);
        Vec3f h = new Vec3f((float) rnd.range(0.02, maxHalf), (float) rnd.range(0.02, maxHalf), (float) rnd.range(0.02, maxHalf));
        return Aabbf.fromCenterHalfExtent(c, h);
    }

    private void add(UniformGrid g, Model m, Aabbf b) {
        m.handles.add(g.insert(b, m.nextId));
        m.boxes.add(b);
        m.ids.add(m.nextId++);
    }

    private static int[] sorted(IntList l) {
        int[] a = l.toArray();
        java.util.Arrays.sort(a);
        return a;
    }

    private void assertQueriesMatch(UniformGrid g, Model m, String when) {
        UniformGrid.Query q = g.newQuery();
        IntList out = new IntList();
        for (int i = 0; i < 10; i++) {
            Aabbf probe = Aabbf.fromCenterHalfExtent(rnd.nextVec3f().mul(14f),
                    new Vec3f((float) rnd.range(0.05, 6), (float) rnd.range(0.05, 6), (float) rnd.range(0.05, 6)));
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

            Spheref s = Spheref.of(rnd.nextVec3f().mul(14f), (float) rnd.range(0.05, 7));
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
            Vec3f p = rnd.nextVec3f().mul(18f);
            int k = 1 + (int) rnd.range(0, 12);
            nn.reset(k);
            q.nearest(p.x(), p.y(), p.z(), nn);
            NearestTest.assertSameAsBruteForce(m.boxes, m.ids, p.x(), p.y(), p.z(), k, nn, when + ": nearest k=" + k);
        }
    }

    @Test
    void basicsAndHandles() {
        UniformGrid g = new UniformGrid(2f);
        assertEquals(0, g.size());
        int a = g.insert(Aabbf.of(new Vec3f(0f, 0f, 0f), new Vec3f(1f, 1f, 1f)), 10);
        int b = g.insert(Aabbf.of(new Vec3f(5f, 5f, 5f), new Vec3f(6f, 6f, 6f)), 20);
        g.validate();
        assertEquals(2, g.size());
        assertEquals(10, g.userData(a));
        assertEquals(Aabbf.of(new Vec3f(5f, 5f, 5f), new Vec3f(6f, 6f, 6f)), g.bounds(b));
        IntList out = new IntList();
        g.newQuery().overlapAabb(Aabbf.of(new Vec3f(0.5f, 0.5f, 0.5f), new Vec3f(0.6f, 0.6f, 0.6f)), out);
        assertArrayEquals(new int[] {10}, out.toArray());
        assertFalse(g.move(a, 0.1f, 0.1f, 0.1f, 1.1f, 1.1f, 1.1f), "still the same cells: only the box changes");
        assertTrue(g.move(a, 9f, 9f, 9f, 10f, 10f, 10f), "different cells");
        g.validate();
        g.remove(a);
        assertFalse(g.isValid(a));
        assertThrows(IllegalArgumentException.class, () -> g.userData(a));
        int c = g.insert(Aabbf.of(new Vec3f(-3f, 0f, 0f), new Vec3f(-2f, 1f, 1f)), 30);
        assertEquals(a, c, "a freed handle is reused");
        g.clear();
        assertEquals(0, g.size());
        assertEquals(0, g.entryCount());
        g.validate();
    }

    @Test
    void badArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new UniformGrid(0f));
        assertThrows(IllegalArgumentException.class, () -> new UniformGrid(Float.NaN));
        UniformGrid g = new UniformGrid(1f);
        assertThrows(IllegalArgumentException.class, () -> g.insert(Float.NaN, 0f, 0f, 1f, 1f, 1f, 0));
        assertThrows(IllegalArgumentException.class, () -> g.insert(0f, 0f, 0f, Float.POSITIVE_INFINITY, 1f, 1f, 0));
        assertThrows(IllegalArgumentException.class, () -> g.insert(2e6f, 0f, 0f, 2e6f, 1f, 1f, 0));
        assertThrows(IllegalArgumentException.class, () -> g.insert(1f, 0f, 0f, 0f, 1f, 1f, 0));
    }

    @Test
    void randomOperationsMatchBruteForceForSeveralCellSizes() {
        for (float cell : new float[] {0.7f, 2.5f, 9f}) {
            UniformGrid g = new UniformGrid(cell, 4);
            Model m = new Model();
            for (int round = 0; round < 14; round++) {
                for (int i = 0; i < 50; i++) {
                    add(g, m, randomBox(12f, 1.2f));
                }
                // a few big objects that go on the oversize list
                if (round % 4 == 0) {
                    add(g, m, randomBox(6f, 30f));
                }
                for (int i = 0; i < 70 && !m.boxes.isEmpty(); i++) {
                    int k = (int) rnd.range(0, m.boxes.size());
                    Aabbf old = m.boxes.get(k);
                    Vec3f d = rnd.nextVec3f().mul((float) rnd.range(0.01, 3));
                    Aabbf moved = Aabbf.fromCenterHalfExtent(old.center().add(d), old.halfSize());
                    m.boxes.set(k, moved);
                    g.move(m.handles.get(k), moved);
                }
                for (int i = 0; i < 20 && m.boxes.size() > 1; i++) {
                    int k = (int) rnd.range(0, m.boxes.size());
                    g.remove(m.handles.get(k));
                    m.handles.remove(k);
                    m.boxes.remove(k);
                    m.ids.remove(k);
                }
                g.validate();
                assertEquals(m.boxes.size(), g.size());
                assertQueriesMatch(g, m, "cell " + cell + " round " + round);
            }
        }
    }

    @Test
    void anObjectSpanningManyCellsIsOversizeAndStillFound() {
        UniformGrid g = new UniformGrid(1f);
        int big = g.insert(-40f, -40f, -40f, 40f, 40f, 40f, 7);
        assertEquals(1, g.oversizeCount());
        assertEquals(0, g.entryCount());
        int small = g.insert(0.1f, 0.1f, 0.1f, 0.2f, 0.2f, 0.2f, 8);
        IntList out = new IntList();
        UniformGrid.Query q = g.newQuery();
        q.overlapAabb(Aabbf.of(new Vec3f(0f, 0f, 0f), new Vec3f(0.3f, 0.3f, 0.3f)), out);
        assertArrayEquals(new int[] {7, 8}, sorted(out));
        Neighbors nn = new Neighbors(2);
        nn.reset(2);
        q.nearest(30f, 30f, 30f, nn);
        assertEquals(7, nn.index(0));
        assertEquals(0f, nn.distanceSquared(0));
        g.move(big, 0f, 0f, 0f, 1f, 1f, 1f); // shrinks: leaves the oversize list
        assertEquals(0, g.oversizeCount());
        g.validate();
        g.remove(small);
        g.remove(big);
        assertEquals(0, g.size());
        g.validate();
    }

    @Test
    void emptyGridAndQueriesFarOutsideBehave() {
        UniformGrid g = new UniformGrid(1f);
        UniformGrid.Query q = g.newQuery();
        IntList out = new IntList();
        q.overlapAabb(Aabbf.of(new Vec3f(-1f, -1f, -1f), new Vec3f(1f, 1f, 1f)), out);
        assertEquals(0, out.size());
        Neighbors nn = new Neighbors(3);
        nn.reset(3);
        q.nearest(0f, 0f, 0f, nn);
        assertEquals(0, nn.size());
        g.insert(1f, 1f, 1f, 2f, 2f, 2f, 5);
        q.nearest(1e9f, -1e9f, 3f, nn); // far beyond the grid's range: clamped internally
        assertEquals(1, nn.size());
        assertEquals(5, nn.index(0));
        q.nearest(Float.NaN, 0f, 0f, nn);
        assertEquals(0, nn.size());
        q.overlapAabb(Aabbf.of(new Vec3f(-1e9f, -1e9f, -1e9f), new Vec3f(1e9f, 1e9f, 1e9f)), out);
        assertArrayEquals(new int[] {5}, out.toArray());
    }

    @Test
    void manyObjectsGrowThePoolsAndBucketTable() {
        UniformGrid g = new UniformGrid(1f, 2);
        Model m = new Model();
        for (int i = 0; i < 3000; i++) {
            add(g, m, randomBox(30f, 0.6f));
        }
        g.validate();
        assertTrue(g.entryCount() >= 3000);
        assertQueriesMatch(g, m, "3000 objects");
    }
}
