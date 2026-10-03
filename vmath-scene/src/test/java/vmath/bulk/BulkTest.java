package vmath.bulk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.FloatBuffer;
import java.util.BitSet;
import org.junit.jupiter.api.Test;
import vmath.core.Mat4f;
import vmath.core.Rnd;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;

class BulkTest {

    final Rnd rnd = Rnd.create();

    // ------------------------------------------------------------ IntList

    @Test
    void intListGrowsAndKeepsStorageOnClear() {
        IntList list = new IntList(2);
        for (int i = 0; i < 1000; i++) {
            list.add(999 - i);
        }
        assertEquals(1000, list.size());
        assertEquals(999, list.get(0));
        list.sort();
        assertEquals(0, list.get(0));
        assertEquals(999, list.get(999));
        int[] backing = list.array();
        list.clear();
        assertTrue(list.isEmpty());
        list.add(7);
        assertTrue(backing == list.array(), "clear() must keep the backing array");
        assertThrows(IndexOutOfBoundsException.class, () -> list.get(1));
        assertArrayEquals(new int[] {7}, list.toArray());
    }

    // ------------------------------------------------------------ VisibilitySet

    @Test
    void visibilitySetMatchesJavaBitSet() {
        for (int trial = 0; trial < 200; trial++) {
            int cap = 1 + (int) rnd.range(0, 500);
            VisibilitySet a = new VisibilitySet(cap), b = new VisibilitySet(cap);
            BitSet ra = new BitSet(cap), rb = new BitSet(cap);
            for (int k = 0; k < 300; k++) {
                int i = (int) rnd.range(0, cap);
                if (rnd.nextBoolean()) {
                    a.set(i);
                    ra.set(i);
                } else {
                    a.clear(i);
                    ra.clear(i);
                }
                int j = (int) rnd.range(0, cap);
                if (rnd.nextBoolean()) {
                    b.set(j);
                    rb.set(j);
                }
            }
            for (int i = 0; i < cap; i++) {
                assertEquals(ra.get(i), a.get(i), "bit " + i);
            }
            assertEquals(ra.cardinality(), a.count());
            for (int from = 0; from < cap; from += 7) {
                assertEquals(ra.nextSetBit(from), a.nextSetBit(from), "nextSetBit from " + from);
            }
            int[] out = new int[cap];
            int n = a.toIndices(out);
            IntList list = new IntList();
            a.toIndices(list);
            assertEquals(ra.cardinality(), n);
            assertEquals(n, list.size());
            int k = 0;
            for (int i = ra.nextSetBit(0); i >= 0; i = ra.nextSetBit(i + 1)) {
                assertEquals(i, out[k]);
                assertEquals(i, list.get(k));
                k++;
            }
            VisibilitySet and = new VisibilitySet(cap), or = new VisibilitySet(cap), andNot = new VisibilitySet(cap);
            and.copyFrom(a);
            and.and(b);
            or.copyFrom(a);
            or.or(b);
            andNot.copyFrom(a);
            andNot.andNot(b);
            BitSet eAnd = (BitSet) ra.clone(), eOr = (BitSet) ra.clone(), eAndNot = (BitSet) ra.clone();
            eAnd.and(rb);
            eOr.or(rb);
            eAndNot.andNot(rb);
            for (int i = 0; i < cap; i++) {
                assertEquals(eAnd.get(i), and.get(i));
                assertEquals(eOr.get(i), or.get(i));
                assertEquals(eAndNot.get(i), andNot.get(i));
            }
        }
    }

    @Test
    void setAllSetsExactlyTheFirstBits() {
        for (int cap : new int[] {1, 63, 64, 65, 127, 128, 129, 1000}) {
            VisibilitySet s = new VisibilitySet(cap);
            for (int n : new int[] {0, 1, Math.min(cap, 63), Math.min(cap, 64), cap}) {
                s.setAll(n);
                assertEquals(n, s.count(), "cap " + cap + " n " + n);
                assertFalse(n < cap && s.get(n), "bit n must be clear");
                assertTrue(n == 0 || s.get(n - 1), "bit n-1 must be set");
            }
            assertThrows(IllegalArgumentException.class, () -> s.setAll(cap + 1));
        }
    }

    @Test
    void ensureCapacityKeepsBitsAndGrowsClear() {
        VisibilitySet s = new VisibilitySet(10);
        s.set(3);
        s.set(9);
        s.ensureCapacity(500);
        assertTrue(s.get(3) && s.get(9));
        assertEquals(2, s.count());
        assertFalse(s.get(499));
        assertEquals(500, s.capacity());
        s.clearAll();
        assertEquals(0, s.count());
        assertEquals(-1, s.nextSetBit(0));
    }

    // ------------------------------------------------------------ BoundsArray

    private Aabbf randomBox() {
        return Aabbf.of(rnd.nextVec3f(), rnd.nextVec3f());
    }

    @Test
    void boundsArrayStoresAndGrows() {
        BoundsArray b = new BoundsArray(2);
        Aabbf[] ref = new Aabbf[1000];
        for (int i = 0; i < ref.length; i++) {
            ref[i] = randomBox();
            assertEquals(i, b.add(ref[i]));
        }
        assertEquals(ref.length, b.size());
        Aabbf all = Aabbf.EMPTY;
        for (int i = 0; i < ref.length; i++) {
            assertEquals(ref[i], b.get(i));
            all = all.union(ref[i]);
        }
        assertEquals(all, b.union());
        Aabbf replacement = randomBox();
        b.set(500, replacement);
        assertEquals(replacement, b.get(500));
        assertThrows(IndexOutOfBoundsException.class, () -> b.get(1000));
        assertThrows(IndexOutOfBoundsException.class, () -> b.set(-1, replacement));
        b.clear();
        assertEquals(0, b.size());
        assertTrue(b.union().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> b.setSize(b.capacity() + 1));
    }

    @Test
    void transformFromMatchesPerBoxTransform() {
        int n = 500;
        BoundsArray local = new BoundsArray(n);
        Mat4fArray mats = new Mat4fArray(n);
        for (int i = 0; i < n; i++) {
            local.add(randomBox());
            mats.add(rnd.nextTrsMat4f());
        }
        BoundsArray world = new BoundsArray(1);
        world.transformFrom(local, mats);
        assertEquals(n, world.size());
        for (int i = 0; i < n; i++) {
            Aabbf expected = local.get(i).transform(mats.get(i));
            assertTrue(expected.approxEquals(world.get(i), 1e-4f), "box " + i + ": " + expected + " vs " + world.get(i));
        }
        mats.clear();
        assertThrows(IllegalArgumentException.class, () -> world.transformFrom(local, mats));
    }

    @Test
    void writeInterleavedLayout() {
        BoundsArray b = new BoundsArray(4);
        for (int i = 0; i < 3; i++) {
            b.add(randomBox());
        }
        float[] out = new float[1 + 18];
        b.writeInterleaved(out, 1);
        for (int i = 0; i < 3; i++) {
            Aabbf e = b.get(i);
            int o = 1 + i * 6;
            assertEquals(e.minX(), out[o]);
            assertEquals(e.minY(), out[o + 1]);
            assertEquals(e.minZ(), out[o + 2]);
            assertEquals(e.maxX(), out[o + 3]);
            assertEquals(e.maxY(), out[o + 4]);
            assertEquals(e.maxZ(), out[o + 5]);
        }
    }

    // ------------------------------------------------------------ Mat4fArray

    @Test
    void mat4ArrayRoundTripsAndUploads() {
        Mat4fArray a = new Mat4fArray(1);
        Mat4f[] ref = new Mat4f[50];
        for (int i = 0; i < ref.length; i++) {
            ref[i] = rnd.nextTrsMat4f();
            a.add(ref[i]);
        }
        for (int i = 0; i < ref.length; i++) {
            assertEquals(ref[i], a.get(i));
        }
        Mat4f replacement = rnd.nextDenseMat4f();
        a.set(7, replacement);
        assertEquals(replacement, a.get(7));
        FloatBuffer buf = FloatBuffer.allocate(3 + 50 * 16);
        a.writeTo(buf, 3);
        assertEquals(0, buf.position(), "writeTo must not move the position");
        float[] m7 = new float[16];
        replacement.writeTo(m7, 0);
        for (int k = 0; k < 16; k++) {
            assertEquals(m7[k], buf.get(3 + 7 * 16 + k));
        }
        assertThrows(IndexOutOfBoundsException.class, () -> a.get(50));
        Vec3f p = rnd.nextVec3f();
        assertEquals(ref[3].transformPosition(p), a.get(3).transformPosition(p));
    }
}
