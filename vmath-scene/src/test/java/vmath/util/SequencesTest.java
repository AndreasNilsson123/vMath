package vmath.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vmath.core.Rnd;

class SequencesTest {

    @Test
    void radicalInverseMirrorsTheDigits() {
        double[] base2 = {0, 0.5, 0.25, 0.75, 0.125, 0.625, 0.375, 0.875};
        for (int i = 0; i < base2.length; i++) {
            assertEquals(base2[i], Sequences.radicalInverse(2, i), 1e-15);
        }
        assertEquals(1.0 / 3, Sequences.radicalInverse(3, 1), 1e-15);
        assertEquals(2.0 / 3, Sequences.radicalInverse(3, 2), 1e-15);
        assertEquals(1.0 / 9, Sequences.radicalInverse(3, 3), 1e-15);
        assertEquals(4.0 / 9, Sequences.radicalInverse(3, 4), 1e-15);
        assertThrows(IllegalArgumentException.class, () -> Sequences.radicalInverse(1, 5));
        assertEquals(Sequences.radicalInverse(5, 17), Sequences.halton(17, 2), 0.0);
        assertEquals(Sequences.radicalInverse(2, 17), Sequences.halton(17, 0), 0.0);
        assertThrows(IllegalArgumentException.class, () -> Sequences.halton(1, 16));
        assertThrows(IllegalArgumentException.class, () -> Sequences.halton(1, -1));
    }

    @Test
    void haltonPrefixesFillTheSquareEvenly() {
        // in any prefix of 2^a 3^b points the base-2 and base-3 coordinates fill the 2^a x 3^b grid exactly once (the Chinese remainder property)
        int n = 6 * 6;
        boolean[] seen = new boolean[4 * 9];
        for (int i = 0; i < 36; i++) {
            int cx = (int) (Sequences.halton(i, 0) * 4 + 1e-9), cy = (int) (Sequences.halton(i, 1) * 9 + 1e-9);
            assertTrue(!seen[cy * 4 + cx], "cell visited twice");
            seen[cy * 4 + cx] = true;
        }
        assertEquals(36, n);
    }

    @Test
    void sobolStartsAsPublishedAndIsAnNet() {
        float[] p = new float[2];
        float[][] first = {{0, 0}, {0.5f, 0.5f}, {0.25f, 0.75f}, {0.75f, 0.25f}};
        for (int i = 0; i < 4; i++) {
            Sequences.sobol2(i, p, 0);
            assertArrayEquals(first[i], p, 0f, "point " + i);
        }
        // every aligned block of 2^k points has exactly one point in each cell of every 2^a x 2^b grid with a + b = k
        for (int k = 1; k <= 8; k++) {
            int block = 1 << k;
            for (int m = 0; m < 3; m++) {
                for (int a = 0; a <= k; a++) {
                    int b = k - a;
                    int[] cells = new int[block];
                    for (int i = 0; i < block; i++) {
                        Sequences.sobol2((long) m * block + i, p, 0);
                        cells[(int) (p[1] * (1 << b)) * (1 << a) + (int) (p[0] * (1 << a))]++;
                    }
                    for (int c : cells) {
                        assertEquals(1, c, "k=" + k + " a=" + a + " m=" + m);
                    }
                }
            }
        }
        assertThrows(IllegalArgumentException.class, () -> Sequences.sobol2(-1, p, 0));
        assertThrows(IllegalArgumentException.class, () -> Sequences.sobol2(1L << 32, p, 0));
    }

    @Test
    void r2AndHammersleyCoverTheSquare() {
        float[] p = new float[4];
        int n = 4096;
        int[] cells = new int[16 * 16];
        double sx = 0, sy = 0;
        for (int i = 0; i < n; i++) {
            Sequences.r2(i, p, 0);
            assertTrue(p[0] >= 0 && p[0] < 1 && p[1] >= 0 && p[1] < 1);
            cells[(int) (p[1] * 16) * 16 + (int) (p[0] * 16)]++;
            sx += p[0];
            sy += p[1];
        }
        assertEquals(0.5, sx / n, 5e-3);
        assertEquals(0.5, sy / n, 5e-3);
        // 16 points per cell on average; a low-discrepancy set stays close to that
        for (int c : cells) {
            assertTrue(c >= 12 && c <= 20, "cell count " + c);
        }
        java.util.Arrays.fill(cells, 0);
        for (int i = 0; i < n; i++) {
            Sequences.hammersley(i, n, p, 2);
            assertEquals((i + 0.5) / n, p[2], 1e-6);
            cells[(int) (p[3] * 16) * 16 + (int) (p[2] * 16)]++;
        }
        for (int c : cells) {
            assertEquals(16, c);
        }
        assertThrows(IllegalArgumentException.class, () -> Sequences.hammersley(5, 5, p, 0));
        assertThrows(IllegalArgumentException.class, () -> Sequences.hammersley(-1, 5, p, 0));
        assertThrows(IllegalArgumentException.class, () -> Sequences.hammersley(0, 0, p, 0));
    }

    @Test
    void poissonDiskKeepsItsDistanceAndFillsTheRectangle() {
        float w = 40, h = 25, radius = 1.5f;
        float[] out = new float[2 * 4000];
        int n = Sequences.poissonDisk(new Rng(Rnd.SEED), w, h, radius, 30, out);
        assertTrue(n > 0 && n < 4000);
        double minD2 = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            assertTrue(out[2 * i] >= 0 && out[2 * i] < w && out[2 * i + 1] >= 0 && out[2 * i + 1] < h, "inside");
            for (int j = i + 1; j < n; j++) {
                double dx = out[2 * i] - out[2 * j], dy = out[2 * i + 1] - out[2 * j + 1];
                minD2 = Math.min(minD2, dx * dx + dy * dy);
            }
        }
        assertTrue(Math.sqrt(minD2) >= radius * (1 - 1e-5), "minimum distance " + Math.sqrt(minD2));
        // the density is within the range of a maximal packing: a hexagonal lattice would hold area / (sqrt(3) / 2 r^2) points, a random maximal set about 70% of that
        double hex = w * h / (Math.sqrt(3) / 2 * radius * radius);
        assertTrue(n > 0.45 * hex && n < hex, n + " points, hexagonal " + hex);
        // no big gaps: nearly every location is within twice the radius of a point
        Rng probe = new Rng(7);
        int covered = 0, trials = 2000;
        for (int t = 0; t < trials; t++) {
            double x = probe.nextDouble() * w, y = probe.nextDouble() * h;
            for (int i = 0; i < n; i++) {
                double dx = out[2 * i] - x, dy = out[2 * i + 1] - y;
                if (dx * dx + dy * dy <= 4.0 * radius * radius) {
                    covered++;
                    break;
                }
            }
        }
        assertTrue(covered >= 0.99 * trials, covered + " of " + trials + " locations are near a point");
        // deterministic for a seed
        float[] again = new float[out.length];
        assertEquals(n, Sequences.poissonDisk(new Rng(Rnd.SEED), w, h, radius, 30, again));
        assertArrayEquals(out, again);
    }

    @Test
    void poissonDiskStopsAtTheCapacityAndChecksItsArguments() {
        float[] small = new float[2 * 5];
        assertEquals(5, Sequences.poissonDisk(new Rng(1), 100, 100, 1, 30, small));
        assertEquals(0, Sequences.poissonDisk(new Rng(1), 10, 10, 1, 30, new float[0]));
        // a rectangle smaller than the radius holds one point
        assertEquals(1, Sequences.poissonDisk(new Rng(1), 0.5f, 0.5f, 5, 30, new float[20]));
        assertThrows(IllegalArgumentException.class, () -> Sequences.poissonDisk(new Rng(1), 0, 10, 1, 30, small));
        assertThrows(IllegalArgumentException.class, () -> Sequences.poissonDisk(new Rng(1), 10, 10, 0, 30, small));
        assertThrows(IllegalArgumentException.class, () -> Sequences.poissonDisk(new Rng(1), 10, 10, 1, 0, small));
        assertThrows(IllegalArgumentException.class, () -> Sequences.poissonDisk(new Rng(1), 1e6f, 1e6f, 1, 30, small));
    }
}
