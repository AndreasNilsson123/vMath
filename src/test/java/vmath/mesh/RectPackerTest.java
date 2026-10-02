package vmath.mesh;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vmath.core.Rnd;

class RectPackerTest {

    final Rnd rnd = Rnd.create();

    /** Every placed rectangle is inside the bin and no two overlap. */
    private static void assertValid(int[] w, int[] h, int bw, int bh, int[] x, int[] y, boolean[] rot) {
        int n = w.length;
        for (int i = 0; i < n; i++) {
            int rw = rot[i] ? h[i] : w[i], rh = rot[i] ? w[i] : h[i];
            assertTrue(x[i] >= 0 && y[i] >= 0 && x[i] + rw <= bw && y[i] + rh <= bh, "rect " + i + " inside the bin");
            for (int j = i + 1; j < n; j++) {
                int sw = rot[j] ? h[j] : w[j], sh = rot[j] ? w[j] : h[j];
                boolean apart = x[i] + rw <= x[j] || x[j] + sw <= x[i] || y[i] + rh <= y[j] || y[j] + sh <= y[i];
                assertTrue(apart, "rects " + i + " and " + j + " overlap");
            }
        }
    }

    @Test
    void randomSetsNeverOverlapAndStayInsideTheBin() {
        int packed = 0;
        for (int trial = 0; trial < 300; trial++) {
            int n = 1 + (int) rnd.range(0, 60);
            int[] w = new int[n], h = new int[n];
            long area = 0;
            for (int i = 0; i < n; i++) {
                w[i] = 1 + (int) rnd.range(0, 40);
                h[i] = 1 + (int) rnd.range(0, 40);
                area += (long) w[i] * h[i];
            }
            boolean rotate = (trial & 1) == 0;
            int size = 1;
            while ((long) size * size < area * 13 / 10) {
                size++;
            }
            int[] x = new int[n], y = new int[n];
            boolean[] rot = new boolean[n];
            if (RectPacker.pack(w, h, size, size, rotate, x, y, rot)) {
                assertValid(w, h, size, size, x, y, rot);
                if (!rotate) {
                    for (boolean r : rot) {
                        assertFalse(r, "no rotation unless allowed");
                    }
                }
                packed++;
            }
        }
        assertTrue(packed > 150, "a bin with 30% slack must usually suffice: " + packed + " of 300");
    }

    @Test
    void anExactFitIsFound() {
        // a 4 x 4 bin tiled by 1 x 4 strips and 2 x 2 squares
        int[] w = {1, 1, 2, 2}, h = {4, 4, 2, 2};
        int[] x = new int[4], y = new int[4];
        boolean[] rot = new boolean[4];
        assertTrue(RectPacker.pack(w, h, 4, 4, false, x, y, rot));
        assertValid(w, h, 4, 4, x, y, rot);
    }

    @Test
    void rotationRescuesATallRectangle() {
        int[] w = {2}, h = {8};
        int[] x = new int[1], y = new int[1];
        boolean[] rot = new boolean[1];
        assertFalse(RectPacker.pack(w, h, 8, 2, false, x, y, rot) && rot[0], "without rotation 2 x 8 does not fit an 8 x 2 bin unrotated");
        assertTrue(RectPacker.pack(w, h, 8, 2, true, x, y, rot));
        assertTrue(rot[0]);
    }

    @Test
    void tooMuchFails() {
        int[] w = {5, 5}, h = {5, 5};
        assertFalse(RectPacker.pack(w, h, 9, 9, true, new int[2], new int[2], new boolean[2]), "two 5 x 5 squares do not fit 9 x 9");
        assertFalse(RectPacker.pack(new int[] {10}, new int[] {1}, 9, 9, false, new int[1], new int[1], new boolean[1]));
        assertTrue(RectPacker.pack(new int[0], new int[0], 1, 1, false, new int[0], new int[0], new boolean[0]), "nothing to pack");
    }

    @Test
    void badInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RectPacker.pack(new int[] {0}, new int[] {1}, 4, 4, false, new int[1], new int[1], new boolean[1]));
        assertThrows(IllegalArgumentException.class, () -> RectPacker.pack(new int[] {1, 2}, new int[] {1}, 4, 4, false, new int[2], new int[2], new boolean[2]));
    }

    @Test
    void smallestPowerOfTwoBin() {
        int[] w = {64, 64, 64, 64}, h = {64, 64, 64, 64};
        RectPacker.Result r = RectPacker.packSmallestPowerOfTwo(w, h, 1024, false);
        assertNotNull(r);
        assertEquals(128L * 128L, (long) r.width() * r.height(), "the smallest area (128 x 128 and 64 x 256 tie)");
        assertValid(w, h, r.width(), r.height(), r.x(), r.y(), r.rotated());
        // three of them need area 12288, so the next power-of-two area is 16384
        RectPacker.Result three = RectPacker.packSmallestPowerOfTwo(new int[] {64, 64, 64}, new int[] {64, 64, 64}, 1024, false);
        assertEquals(128L * 128L, (long) three.width() * three.height());
        assertNull(RectPacker.packSmallestPowerOfTwo(new int[] {600, 600}, new int[] {600, 600}, 512, true), "larger than the limit");
        RectPacker.Result single = RectPacker.packSmallestPowerOfTwo(new int[] {5}, new int[] {3}, 64, false);
        assertEquals(8, single.width());
        assertEquals(4, single.height());
    }

    @Test
    void efficiencyOnTypicalAtlasInput() {
        int n = 200;
        int[] w = new int[n], h = new int[n];
        long area = 0;
        SplittableRandom atlasRnd = new SplittableRandom(0x5EEDL);
        for (int i = 0; i < n; i++) {
            w[i] = 8 + (int) (56 * atlasRnd.nextDouble());
            h[i] = 8 + (int) (56 * atlasRnd.nextDouble());
            area += (long) w[i] * h[i];
        }
        RectPacker.Result r = RectPacker.packSmallestPowerOfTwo(w, h, 4096, true);
        assertNotNull(r);
        assertValid(w, h, r.width(), r.height(), r.x(), r.y(), r.rotated());
        double fill = (double) area / ((double) r.width() * r.height());
        assertTrue(fill > 0.5, "a power-of-two bin that is at least half full: " + fill);
    }
}
