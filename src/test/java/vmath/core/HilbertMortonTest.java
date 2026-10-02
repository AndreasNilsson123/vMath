package vmath.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class HilbertMortonTest {

    private static final long SEED = Long.getLong("vmath.seed", 5L);

    private static int l1(Vec2i a, Vec2i b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y());
    }

    private static int l1(Vec3i a, Vec3i b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z());
    }

    @Test
    void hilbert2dVisitsEveryCellOnceAndMovesToANeighbourEachStep() {
        for (int bits = 1; bits <= 7; bits++) {
            long cells = 1L << (2 * bits);
            boolean[] seen = new boolean[(int) cells];
            Vec2i previous = Hilbert.decode2(0, bits);
            assertEquals(new Vec2i(0, 0), previous, "the curve starts at the origin");
            for (long code = 0; code < cells; code++) {
                Vec2i cell = Hilbert.decode2(code, bits);
                int index = cell.y() * (1 << bits) + cell.x();
                assertTrue(!seen[index], "cell visited twice at bits=" + bits + " code=" + code);
                seen[index] = true;
                if (code > 0) {
                    assertEquals(1, l1(previous, cell), "bits=" + bits + " code=" + code);
                }
                assertEquals(code, Hilbert.encode2(cell.x(), cell.y(), bits));
                previous = cell;
            }
        }
    }

    @Test
    void hilbert3dVisitsEveryCellOnceAndMovesToANeighbourEachStep() {
        for (int bits = 1; bits <= 4; bits++) {
            long cells = 1L << (3 * bits);
            boolean[] seen = new boolean[(int) cells];
            Vec3i previous = Hilbert.decode3(0, bits);
            assertEquals(new Vec3i(0, 0, 0), previous);
            for (long code = 0; code < cells; code++) {
                Vec3i cell = Hilbert.decode3(code, bits);
                int index = (cell.z() << (2 * bits)) | (cell.y() << bits) | cell.x();
                assertTrue(!seen[index], "cell visited twice at bits=" + bits + " code=" + code);
                seen[index] = true;
                if (code > 0) {
                    assertEquals(1, l1(previous, cell), "bits=" + bits + " code=" + code);
                }
                assertEquals(code, Hilbert.encode3(cell.x(), cell.y(), cell.z(), bits));
                previous = cell;
            }
        }
    }

    @Test
    void largeGridsRoundTripAndKeepTheNeighbourProperty() {
        SplittableRandom r = new SplittableRandom(SEED);
        for (int k = 0; k < 20000; k++) {
            int bits = 1 + r.nextInt(32);
            long mask = (1L << bits) - 1;
            int x = (int) (r.nextLong() & mask), y = (int) (r.nextLong() & mask);
            long code = Hilbert.encode2(x, y, bits);
            assertEquals(new Vec2i(x, y), Hilbert.decode2(code, bits), "2d bits=" + bits);
            // the next code (if any) is a neighbouring cell
            long last = bits == 32 ? -1L : (1L << (2 * bits)) - 1;
            if (Long.compareUnsigned(code, last) < 0) {
                assertEquals(1, l1(new Vec2i(x, y), Hilbert.decode2(code + 1, bits)), "2d bits=" + bits);
            }
        }
        for (int k = 0; k < 20000; k++) {
            int bits = 1 + r.nextInt(21);
            long mask = (1L << bits) - 1;
            int x = (int) (r.nextLong() & mask), y = (int) (r.nextLong() & mask), z = (int) (r.nextLong() & mask);
            long code = Hilbert.encode3(x, y, z, bits);
            assertEquals(new Vec3i(x, y, z), Hilbert.decode3(code, bits), "3d bits=" + bits);
            assertTrue(code >= 0);
            if (code < (1L << (3 * bits)) - 1) {
                assertEquals(1, l1(new Vec3i(x, y, z), Hilbert.decode3(code + 1, bits)), "3d bits=" + bits);
            }
        }
    }

    @Test
    void aCodePrefixNamesTheCellsOfACoarserGrid() {
        SplittableRandom r = new SplittableRandom(SEED + 1);
        for (int k = 0; k < 10000; k++) {
            int bits = 2 + r.nextInt(20);
            int coarse = 1 + r.nextInt(bits - 1);
            int shift = bits - coarse;
            int x = r.nextInt(1 << bits), y = r.nextInt(1 << bits), z = r.nextInt(1 << bits);
            assertEquals(Hilbert.encode2(x >> shift, y >> shift, coarse), Hilbert.encode2(x, y, bits) >>> (2 * shift));
            assertEquals(Hilbert.encode3(x >> shift, y >> shift, z >> shift, coarse), Hilbert.encode3(x, y, z, bits) >>> (3 * shift));
        }
    }

    @Test
    void hilbertRejectsOutOfRangeInput() {
        assertThrows(IllegalArgumentException.class, () -> Hilbert.encode2(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> Hilbert.encode2(0, 0, 33));
        assertThrows(IllegalArgumentException.class, () -> Hilbert.encode2(4, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> Hilbert.encode2(-1, 0, 31));
        assertThrows(IllegalArgumentException.class, () -> Hilbert.encode3(0, 0, 8, 3));
        assertThrows(IllegalArgumentException.class, () -> Hilbert.encode3(0, 0, 0, 22));
        assertThrows(IllegalArgumentException.class, () -> Hilbert.decode3(512, 3));
        assertThrows(IllegalArgumentException.class, () -> Hilbert.decode2(16, 2));
        // every argument is checked on its own
        assertThrows(IllegalArgumentException.class, () -> Hilbert.encode2(0, 4, 2), "y");
        assertThrows(IllegalArgumentException.class, () -> Hilbert.encode3(8, 0, 0, 3), "x");
        assertThrows(IllegalArgumentException.class, () -> Hilbert.encode3(0, 8, 0, 3), "y");
        assertThrows(IllegalArgumentException.class, () -> Hilbert.decode2(0, 0));
        assertThrows(IllegalArgumentException.class, () -> Hilbert.decode2(0, 33));
        assertThrows(IllegalArgumentException.class, () -> Hilbert.decode3(0, 0));
        assertThrows(IllegalArgumentException.class, () -> Hilbert.decode3(0, 22));
        assertEquals(new Vec2i(0, 0), Hilbert.decode2(0, 1), "one bit");
        assertEquals(1, l1(new Vec2i(0, 0), Hilbert.decode2(1, 1)), "the second cell of the one-bit curve is a neighbour of the first");
        // the full 32-bit range works, and the code is unsigned
        long code = Hilbert.encode2(-1, -1, 32);
        assertEquals(new Vec2i(-1, -1), Hilbert.decode2(code, 32));
    }

    @Test
    void thirtyTwoBitCodesAndFloatEncodingAgreeWithTheLongVersions() {
        SplittableRandom r = new SplittableRandom(SEED + 2);
        for (int k = 0; k < 5000; k++) {
            int x = r.nextInt(1 << 16), y = r.nextInt(1 << 16);
            assertEquals((int) Hilbert.encode2(x, y, 16), Hilbert.encode2Int(x, y));
            assertEquals((int) Morton.encode2(x, y), Morton.encode2Int(x, y));
            assertEquals(new Vec2i(x, y), Morton.decode2Int(Morton.encode2Int(x, y)));
            int a = r.nextInt(1024), b = r.nextInt(1024), c = r.nextInt(1024);
            assertEquals((int) Hilbert.encode3(a, b, c, 10), Hilbert.encode3Int(a, b, c));
            assertTrue(Hilbert.encode3Int(a, b, c) >= 0 && Morton.encode3Int(a, b, c) >= 0);
            assertEquals(new Vec3i(a, b, c), Morton.decode3Int(Morton.encode3Int(a, b, c)));
        }
        // a point at the minimum corner is cell 0, at the maximum corner the last cell of its axis
        assertEquals(0L, Hilbert.encode3(new Vec3f(0f, 0f, 0f), 0f, 0f, 0f, 1f, 1f, 1f));
        Vec3i last = Hilbert.decode3(Hilbert.encode3(new Vec3f(1f, 1f, 1f), 0f, 0f, 0f, 1f, 1f, 1f), 21);
        assertEquals(Morton.MAX_3D, last.x());
    }

    @Test
    void hilbertOrderIsMoreLocalThanZOrder() {
        // walk all cells of a 64 x 64 grid in code order: the Hilbert walk has unit steps, the Z walk has long jumps
        int bits = 6;
        long cells = 1L << (2 * bits);
        long hilbertTotal = 0, mortonTotal = 0;
        int mortonWorst = 0;
        Vec2i ph = Hilbert.decode2(0, bits), pm = Morton.decode2(0);
        for (long code = 1; code < cells; code++) {
            Vec2i h = Hilbert.decode2(code, bits), m = Morton.decode2(code);
            hilbertTotal += l1(ph, h);
            int d = l1(pm, m);
            mortonTotal += d;
            mortonWorst = Math.max(mortonWorst, d);
            ph = h;
            pm = m;
        }
        assertEquals(cells - 1, hilbertTotal);
        assertTrue(mortonTotal > hilbertTotal && mortonWorst > 10, "z-order total " + mortonTotal + ", worst jump " + mortonWorst);
    }
}
