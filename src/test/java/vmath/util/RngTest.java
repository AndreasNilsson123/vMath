package vmath.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vmath.core.Rnd;

class RngTest {

    private final long seed = Rnd.SEED;

    @Test
    void matchesTheReferenceSequences() {
        // xoshiro256++ from the state 1, 2, 3, 4: the values of the published reference implementation (also checked against an independent implementation of the algorithm)
        Rng r = new Rng(1, 2, 3, 4);
        long[] expected = {41943041L, 58720359L, 3588806011781223L, 3591011842654386L, -9218127359498767411L, -8473074601504656454L, -4435742961462588739L, -6040557928525160809L, -2597705026922659880L, -7996720260207963616L};
        for (long e : expected) {
            assertEquals(e, r.nextLong());
        }
        // SplitMix64 from the seed 0
        assertEquals(0xE220A8397B1DCDAFL, Rng.splitMix64(0));
        assertEquals(6457827717110365317L, Rng.splitMix64(1234567));
    }

    @Test
    void seedingIsDeterministicAndWellMixed() {
        Rng a = new Rng(42), b = new Rng(42), c = new Rng(43);
        for (int i = 0; i < 100; i++) {
            long x = a.nextLong();
            assertEquals(x, b.nextLong());
            assertNotEquals(x, c.nextLong());
        }
        // seed 0 is fine too, and re-seeding restarts the sequence (and forgets the cached normal sample)
        Rng z = new Rng(0);
        long first = z.nextLong();
        z.nextGaussian();
        z.seed(0);
        assertEquals(first, z.nextLong());
        assertThrows(IllegalArgumentException.class, () -> new Rng(0, 0, 0, 0));
    }

    @Test
    void splitGivesDifferentStreams() {
        Rng a = new Rng(seed);
        Rng b = a.split();
        int same = 0;
        for (int i = 0; i < 1000; i++) {
            if (a.nextLong() == b.nextLong()) {
                same++;
            }
        }
        assertEquals(0, same);
    }

    @Test
    void uniformNumbersHaveTheRightMomentsAndRange() {
        Rng r = new Rng(seed);
        int n = 200_000;
        double sum = 0, sum2 = 0;
        float fsum = 0;
        for (int i = 0; i < n; i++) {
            double d = r.nextDouble();
            assertTrue(d >= 0 && d < 1);
            sum += d;
            sum2 += d * d;
            float f = r.nextFloat();
            assertTrue(f >= 0 && f < 1);
            fsum += f;
            double w = r.nextDouble(-3, 5);
            assertTrue(w >= -3 && w < 5);
        }
        double mean = sum / n, var = sum2 / n - mean * mean;
        // the mean of n uniforms has the standard deviation sqrt(1/12/n) = 6.5e-4: five of them
        assertEquals(0.5, mean, 3.3e-3);
        assertEquals(1.0 / 12, var, 2e-3);
        assertEquals(0.5, fsum / n, 3.3e-3);
    }

    @Test
    void boundedIntegersAreUnbiased() {
        Rng r = new Rng(seed);
        for (int bound : new int[] {1, 2, 3, 7, 10, 1000, 65537}) {
            int n = 100_000;
            int[] counts = new int[bound];
            for (int i = 0; i < n; i++) {
                int v = r.nextInt(bound);
                assertTrue(v >= 0 && v < bound);
                counts[v]++;
            }
            double expected = (double) n / bound, chi2 = 0;
            for (int c : counts) {
                chi2 += (c - expected) * (c - expected) / expected;
            }
            // chi-square with bound - 1 degrees of freedom: mean bound - 1, standard deviation sqrt(2 (bound - 1)); the limit is 8 standard deviations plus a margin for the tiny cases
            assertTrue(chi2 < (bound - 1) + 8 * Math.sqrt(2.0 * (bound - 1)) + 12, "bound " + bound + ": chi2 = " + chi2);
        }
        for (int i = 0; i < 1000; i++) {
            int v = r.nextInt(-5, 5);
            assertTrue(v >= -5 && v < 5);
        }
        // a bound just below 2^31 forces the rejection branch for some draws; both halves of the range are reached
        boolean low = false, high = false;
        for (int i = 0; i < 2000; i++) {
            int v = r.nextInt(Integer.MAX_VALUE);
            assertTrue(v >= 0);
            low |= v < (1 << 30);
            high |= v >= (1 << 30);
        }
        assertTrue(low && high);
        assertThrows(IllegalArgumentException.class, () -> r.nextInt(0));
        assertThrows(IllegalArgumentException.class, () -> r.nextInt(5, 5));
    }

    @Test
    void booleansAndShufflesAreFair() {
        Rng r = new Rng(seed);
        int ones = 0, n = 100_000;
        for (int i = 0; i < n; i++) {
            ones += r.nextBoolean() ? 1 : 0;
        }
        assertEquals(n / 2.0, ones, 8 * Math.sqrt(n) / 2);
        // every permutation of 4 elements appears about equally often
        int[] counts = new int[24];
        for (int i = 0; i < 24_000; i++) {
            int[] a = {0, 1, 2, 3};
            r.shuffle(a, 4);
            counts[a[0] * 6 + permIndex(a[1], a[2], a[3], a[0])]++;
        }
        for (int c : counts) {
            assertTrue(c > 800 && c < 1200, "permutation count " + c);
        }
        int[] b = {5, 6, 7, 8, 9};
        r.shuffle(b, 3); // only the first three are touched
        assertEquals(8, b[3]);
        assertEquals(9, b[4]);
    }

    private static int permIndex(int a, int b, int c, int first) {
        // rank of the remaining three elements among the 6 orders, given the first
        int[] rest = new int[3];
        int k = 0;
        for (int v = 0; v < 4; v++) {
            if (v != first) {
                rest[k++] = v;
            }
        }
        int ra = a == rest[0] ? 0 : a == rest[1] ? 1 : 2;
        int rb = b == rest[0] ? 0 : b == rest[1] ? 1 : 2;
        int rc = c == rest[0] ? 0 : c == rest[1] ? 1 : 2;
        int[][] perms = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
        for (int i = 0; i < 6; i++) {
            if (perms[i][0] == ra && perms[i][1] == rb && perms[i][2] == rc) {
                return i;
            }
        }
        throw new AssertionError();
    }

    @Test
    void gaussianHasZeroMeanUnitVarianceAndTheNormalTails() {
        Rng r = new Rng(seed);
        int n = 400_000;
        double sum = 0, sum2 = 0, sum4 = 0;
        int beyond2 = 0;
        for (int i = 0; i < n; i++) {
            double g = r.nextGaussian();
            sum += g;
            sum2 += g * g;
            sum4 += g * g * g * g;
            if (Math.abs(g) > 2) {
                beyond2++;
            }
        }
        assertEquals(0.0, sum / n, 6e-3);
        assertEquals(1.0, sum2 / n, 1.5e-2);
        assertEquals(3.0, sum4 / n, 0.15); // the fourth moment of the normal distribution
        assertEquals(0.0455, (double) beyond2 / n, 2.5e-3); // P(|X| > 2) = 4.55%
    }

    @Test
    void circleDiskSphereAndBallSamplesAreUniform() {
        Rng r = new Rng(seed);
        float[] p = new float[3];
        int n = 100_000;
        double sx = 0, sy = 0, sz = 0, inner = 0, ballInner = 0;
        int[] octants = new int[8];
        for (int i = 0; i < n; i++) {
            r.onUnitCircle(p, 0);
            assertEquals(1.0, Math.hypot(p[0], p[1]), 1e-6);
            r.inUnitDisk(p, 0);
            double rad = Math.hypot(p[0], p[1]);
            assertTrue(rad <= 1 + 1e-6);
            if (rad < Math.sqrt(0.5)) {
                inner++; // the inner disk of half the area holds half the points
            }
            r.onUnitSphere(p, 0);
            assertEquals(1.0, Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]), 1e-6);
            sx += p[0];
            sy += p[1];
            sz += p[2];
            octants[(p[0] > 0 ? 1 : 0) + (p[1] > 0 ? 2 : 0) + (p[2] > 0 ? 4 : 0)]++;
            r.inUnitBall(p, 0);
            double rb = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
            assertTrue(rb <= 1 + 1e-6);
            if (rb < Math.cbrt(0.5)) {
                ballInner++; // half the volume
            }
        }
        assertEquals(0.5, inner / n, 8e-3);
        assertEquals(0.5, ballInner / n, 8e-3);
        assertEquals(0.0, sx / n, 8e-3);
        assertEquals(0.0, sy / n, 8e-3);
        assertEquals(0.0, sz / n, 8e-3);
        for (int c : octants) {
            assertEquals(n / 8.0, c, 8 * Math.sqrt(n / 8.0));
        }
    }

    @Test
    void hemisphereSamplesHaveTheirDistributions() {
        Rng r = new Rng(seed);
        float[] p = new float[3];
        int n = 200_000;
        float[][] normals = {{0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {0.6f, 0.0f, 0.8f}, {0, 1, 0}, {-0.48f, 0.64f, 0.6f}};
        for (float[] nrm : normals) {
            double meanCosUniform = 0, meanCosCosine = 0;
            for (int i = 0; i < n; i++) {
                r.onHemisphere(nrm[0], nrm[1], nrm[2], p, 0);
                double c = p[0] * nrm[0] + p[1] * nrm[1] + p[2] * nrm[2];
                assertTrue(c >= -1e-6);
                meanCosUniform += c;
                r.cosineHemisphere(nrm[0], nrm[1], nrm[2], p, 0);
                assertEquals(1.0, Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]), 1e-5);
                c = p[0] * nrm[0] + p[1] * nrm[1] + p[2] * nrm[2];
                assertTrue(c >= -1e-5);
                meanCosCosine += c;
            }
            // E[cos] is 1/2 for the uniform hemisphere and 2/3 for the cosine-weighted one
            assertEquals(0.5, meanCosUniform / n, 5e-3);
            assertEquals(2.0 / 3.0, meanCosCosine / n, 5e-3);
        }
    }

    @Test
    void outputsLandAtTheOffset() {
        Rng r = new Rng(1);
        float[] a = new float[7];
        r.onUnitSphere(a, 2);
        assertArrayEquals(new float[] {0, 0}, new float[] {a[0], a[1]});
        assertEquals(0f, a[5]);
        assertEquals(0f, a[6]);
        assertTrue(a[2] != 0 || a[3] != 0 || a[4] != 0);
    }
}
