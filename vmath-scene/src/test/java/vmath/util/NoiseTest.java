package vmath.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Rnd;
import vmath.util.Noise.Kind;

class NoiseTest {

    private final SplittableRandom rng = new SplittableRandom(Rnd.SEED);
    private final int samples = Math.max(Rnd.N * 50, 100_000);

    private double coordinate() {
        return rng.nextDouble() * 200 - 100;
    }

    @Test
    void hashIsUniform() {
        int[] buckets = new int[64];
        int n = 640_000;
        for (int i = 0; i < n; i++) {
            buckets[(int) (Noise.toUnit(Noise.hash(i, i >> 3, -i, 0, 11)) * 64)]++;
        }
        double expected = n / 64.0, chi2 = 0;
        for (int b : buckets) {
            chi2 += (b - expected) * (b - expected) / expected;
        }
        assertTrue(chi2 < 63 + 8 * Math.sqrt(2 * 63.0), "chi2 = " + chi2);
        // each argument and the seed matter
        int h = Noise.hash(1, 2, 3, 4, 5);
        assertNotEquals(h, Noise.hash(2, 2, 3, 4, 5));
        assertNotEquals(h, Noise.hash(1, 3, 3, 4, 5));
        assertNotEquals(h, Noise.hash(1, 2, 4, 4, 5));
        assertNotEquals(h, Noise.hash(1, 2, 3, 5, 5));
        assertNotEquals(h, Noise.hash(1, 2, 3, 4, 6));
    }

    @Test
    void allNoisesAreDeterministicAndDependOnTheSeed() {
        for (int i = 0; i < 100; i++) {
            double x = coordinate(), y = coordinate(), z = coordinate(), w = coordinate();
            assertEquals(Noise.value2(x, y, 1), Noise.value2(x, y, 1));
            assertEquals(Noise.perlin3(x, y, z, 1), Noise.perlin3(x, y, z, 1));
            assertEquals(Noise.simplex3(x, y, z, 1), Noise.simplex3(x, y, z, 1));
            assertEquals(Noise.perlin4(x, y, z, w, 1), Noise.perlin4(x, y, z, w, 1));
        }
        int differ = 0;
        for (int i = 0; i < 100; i++) {
            double x = coordinate(), y = coordinate();
            if (Noise.perlin2(x, y, 1) != Noise.perlin2(x, y, 2)) {
                differ++;
            }
        }
        assertTrue(differ > 95);
    }

    @Test
    void valueNoiseInterpolatesTheLatticeValues() {
        double min = 1, max = -1;
        for (int i = -5; i <= 5; i++) {
            for (int j = -5; j <= 5; j++) {
                double v = Noise.value2(i, j, 4);
                assertEquals(v, Noise.value3(i, j, 0, 4), 0.0, "2D and 3D agree on the lattice with z = 0: both use the same lattice hash");
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        assertTrue(min < -0.4 && max > 0.4, "lattice values spread over [-1, 1]");
        for (int i = 0; i < samples; i++) {
            double x = coordinate(), y = coordinate(), z = coordinate();
            assertTrue(Math.abs(Noise.value2(x, y, 4)) <= 1.0);
            assertTrue(Math.abs(Noise.value3(x, y, z, 4)) <= 1.0);
        }
    }

    @Test
    void perlinNoiseIsBoundedZeroOnTheLatticeAndHasTheMeasuredAmplitude() {
        for (int i = -4; i <= 4; i++) {
            assertEquals(0.0, Noise.perlin2(i, 3 - i, 9), 1e-15);
            assertEquals(0.0, Noise.perlin3(i, 3 - i, 2 * i, 9), 1e-15);
            assertEquals(0.0, Noise.perlin4(i, 3 - i, 2 * i, 1 - i, 9), 1e-15);
        }
        double s2 = 0, s3 = 0, s4 = 0;
        for (int i = 0; i < samples; i++) {
            double x = coordinate(), y = coordinate(), z = coordinate(), w = coordinate();
            double a = Noise.perlin2(x, y, 9), b = Noise.perlin3(x, y, z, 9), c = Noise.perlin4(x, y, z, w, 9);
            assertTrue(Math.abs(a) <= 1 && Math.abs(b) <= 1 && Math.abs(c) <= 1, "guaranteed bound");
            s2 += a * a;
            s3 += b * b;
            s4 += c * c;
        }
        // the mean square of the values: 0.305, 0.221 and 0.169 measured over 2e7 samples (docs/NOISE.md); the sample here is far smaller, so the limits are wide
        assertEquals(0.305, Math.sqrt(s2 / samples), 0.03);
        assertEquals(0.221, Math.sqrt(s3 / samples), 0.03);
        assertEquals(0.169, Math.sqrt(s4 / samples), 0.03);
    }

    @Test
    void simplexNoiseStaysInsideTheUnitRange() {
        double s2 = 0, s3 = 0;
        for (int i = 0; i < samples; i++) {
            double x = coordinate(), y = coordinate(), z = coordinate();
            double a = Noise.simplex2(x, y, 3), b = Noise.simplex3(x, y, z, 3);
            assertTrue(Math.abs(a) <= 1.0 && Math.abs(b) <= 1.0, a + " " + b);
            s2 += a * a;
            s3 += b * b;
        }
        assertEquals(0.538, Math.sqrt(s2 / samples), 0.03);
        assertEquals(0.383, Math.sqrt(s3 / samples), 0.03);
    }

    @Test
    void noisesAreContinuous() {
        // the slope of a continuous function does not grow as the step shrinks; a jump of height j shows as a slope of j / h, a thousand times larger for a thousand times smaller step
        double[] steps = {1e-3, 1e-6};
        double[][] worst = new double[2][7];
        for (int i = 0; i < 30_000; i++) {
            double x = coordinate(), y = coordinate(), z = coordinate();
            for (int k = 0; k < 2; k++) {
                double h = steps[k];
                worst[k][0] = Math.max(worst[k][0], Math.abs(Noise.perlin2(x + h, y, 1) - Noise.perlin2(x, y, 1)) / h);
                worst[k][1] = Math.max(worst[k][1], Math.abs(Noise.perlin3(x, y + h, z, 1) - Noise.perlin3(x, y, z, 1)) / h);
                worst[k][2] = Math.max(worst[k][2], Math.abs(Noise.perlin4(x, y, z, 5 + h, 1) - Noise.perlin4(x, y, z, 5, 1)) / h);
                worst[k][3] = Math.max(worst[k][3], Math.abs(Noise.simplex2(x, y + h, 1) - Noise.simplex2(x, y, 1)) / h);
                worst[k][4] = Math.max(worst[k][4], Math.abs(Noise.simplex3(x + h, y, z, 1) - Noise.simplex3(x, y, z, 1)) / h);
                worst[k][5] = Math.max(worst[k][5], Math.abs(Noise.value2(x + h, y, 1) - Noise.value2(x, y, 1)) / h);
                worst[k][6] = Math.max(worst[k][6], Math.abs(Noise.value3(x, y, z + h, 1) - Noise.value3(x, y, z, 1)) / h);
            }
        }
        String[] names = {"perlin2", "perlin3", "perlin4", "simplex2", "simplex3", "value2", "value3"};
        for (int n = 0; n < 7; n++) {
            assertTrue(worst[1][n] < worst[0][n] * 1.5 + 0.05, names[n] + ": the slope grows as the step shrinks (a jump): " + worst[0][n] + " at 1e-3, " + worst[1][n] + " at 1e-6");
            // the steepest slopes measured over 3e7 samples were 2.75 (Perlin), 7.03 (simplex 2D), 6.7 (simplex 3D) and 3.7 (value noise)
            assertTrue(worst[1][n] < 10, names[n] + ": steepest slope " + worst[1][n]);
        }
        // and the noise does change: it is not constant
        assertNotEquals(Noise.simplex2(0.3, 0.4, 1), Noise.simplex2(0.8, 0.1, 1));
    }

    @Test
    void worleyDistancesMatchABruteForceSearch() {
        double[] f = new double[2];
        double[] f3 = new double[2];
        int bad2 = 0, bad3 = 0, n = 100_000;
        for (int t = 0; t < n; t++) {
            double x = rng.nextDouble() * 50, y = rng.nextDouble() * 50, z = rng.nextDouble() * 50;
            Noise.worley2(x, y, 5, 1.0, f);
            assertTrue(f[0] <= f[1]);
            double[] ref = brute2(x, y, 5, 1.0);
            assertEquals(ref[0], f[0], 1e-12, "the nearest distance is exact");
            if (Math.abs(ref[1] - f[1]) > 1e-12) {
                bad2++;
            }
            Noise.worley3(x, y, z, 5, 1.0, f3);
            assertTrue(f3[0] <= f3[1]);
            if (t < 20_000) {
                double[] ref3 = brute3(x, y, z, 5, 1.0);
                assertEquals(ref3[0], f3[0], 1e-12);
                if (Math.abs(ref3[1] - f3[1]) > 1e-12) {
                    bad3++;
                }
            }
        }
        // measured: 272 wrong second distances in 2e6 samples in 2D (0.014%) and 11 in 3e5 in 3D (0.004%); here 1e5 and 2e4 samples
        assertTrue(bad2 < 60, "2D second distance wrong " + bad2 + " times");
        assertTrue(bad3 < 10, "3D second distance wrong " + bad3 + " times");
        // up to a jitter of 0.5 the nine (27) cells always hold the two nearest points
        for (int t = 0; t < 20_000; t++) {
            double x = rng.nextDouble() * 50, y = rng.nextDouble() * 50;
            Noise.worley2(x, y, 5, 0.5, f);
            assertEquals(brute2(x, y, 5, 0.5)[1], f[1], 1e-12);
        }
    }

    private static double[] brute2(double x, double y, int seed, double jitter) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
        double b1 = Double.MAX_VALUE, b2 = Double.MAX_VALUE;
        for (int dj = -4; dj <= 4; dj++) {
            for (int di = -4; di <= 4; di++) {
                int cx = xi + di, cy = yi + dj;
                double px = cx + 0.5 + (Noise.toUnit(Noise.hash(cx, cy, 0, 0, seed)) - 0.5) * jitter;
                double py = cy + 0.5 + (Noise.toUnit(Noise.hash(cx, cy, 1, 0, seed)) - 0.5) * jitter;
                double d = (px - x) * (px - x) + (py - y) * (py - y);
                if (d < b1) {
                    b2 = b1;
                    b1 = d;
                } else if (d < b2) {
                    b2 = d;
                }
            }
        }
        return new double[] {Math.sqrt(b1), Math.sqrt(b2)};
    }

    private static double[] brute3(double x, double y, double z, int seed, double jitter) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
        double b1 = Double.MAX_VALUE, b2 = Double.MAX_VALUE;
        for (int dk = -3; dk <= 3; dk++) {
            for (int dj = -3; dj <= 3; dj++) {
                for (int di = -3; di <= 3; di++) {
                    int cx = xi + di, cy = yi + dj, cz = zi + dk;
                    double px = cx + 0.5 + (Noise.toUnit(Noise.hash(cx, cy, cz, 0, seed)) - 0.5) * jitter;
                    double py = cy + 0.5 + (Noise.toUnit(Noise.hash(cx, cy, cz, 1, seed)) - 0.5) * jitter;
                    double pz = cz + 0.5 + (Noise.toUnit(Noise.hash(cx, cy, cz, 2, seed)) - 0.5) * jitter;
                    double d = (px - x) * (px - x) + (py - y) * (py - y) + (pz - z) * (pz - z);
                    if (d < b1) {
                        b2 = b1;
                        b1 = d;
                    } else if (d < b2) {
                        b2 = d;
                    }
                }
            }
        }
        return new double[] {Math.sqrt(b1), Math.sqrt(b2)};
    }

    @Test
    void worleyWithoutJitterIsARegularGrid() {
        double[] f = new double[2];
        Noise.worley2(3.5, 4.5, 1, 0.0, f);
        assertEquals(0.0, f[0], 1e-12);
        assertEquals(1.0, f[1], 1e-12);
        Noise.worley3(3.5, 4.5, 5.5, 1, 0.0, f);
        assertEquals(0.0, f[0], 1e-12);
        assertEquals(1.0, f[1], 1e-12);
    }

    @Test
    void curlFieldsAreDivergenceFree() {
        double[] a = new double[3], b = new double[3];
        double h = 1e-3;
        double worst2 = 0, worst3 = 0, size = 0;
        for (int t = 0; t < 2000; t++) {
            double x = coordinate(), y = coordinate(), z = coordinate();
            Noise.curl2(x + h, y, 2, a);
            Noise.curl2(x - h, y, 2, b);
            double du = (a[0] - b[0]) / (2 * h);
            Noise.curl2(x, y + h, 2, a);
            Noise.curl2(x, y - h, 2, b);
            double dv = (a[1] - b[1]) / (2 * h);
            worst2 = Math.max(worst2, Math.abs(du + dv));
            Noise.curl2(x, y, 2, a);
            size = Math.max(size, Math.hypot(a[0], a[1]));
            Noise.curl3(x + h, y, z, 2, a);
            Noise.curl3(x - h, y, z, 2, b);
            double dx = (a[0] - b[0]) / (2 * h);
            Noise.curl3(x, y + h, z, 2, a);
            Noise.curl3(x, y - h, z, 2, b);
            double dy = (a[1] - b[1]) / (2 * h);
            Noise.curl3(x, y, z + h, 2, a);
            Noise.curl3(x, y, z - h, 2, b);
            double dz = (a[2] - b[2]) / (2 * h);
            worst3 = Math.max(worst3, Math.abs(dx + dy + dz));
        }
        assertTrue(size > 0.5, "the field is not zero: " + size);
        assertTrue(worst2 < 1e-3, "2D divergence " + worst2);
        assertTrue(worst3 < 1e-3, "3D divergence " + worst3);
    }

    @Test
    void fractalSumsStayInRangeAndReduceToOneOctave() {
        for (Kind kind : Kind.values()) {
            for (int t = 0; t < 2000; t++) {
                double x = coordinate(), y = coordinate(), z = coordinate();
                assertEquals(Noise.sample2(kind, x, y, 3), Noise.fbm2(kind, x, y, 3, 1, 2.0, 0.5), 1e-12);
                assertEquals(Noise.sample3(kind, x, y, z, 3), Noise.fbm3(kind, x, y, z, 3, 1, 2.0, 0.5), 1e-12);
                assertTrue(Math.abs(Noise.fbm2(kind, x, y, 3, 6, 2.0, 0.5)) <= 1.0 + 1e-12);
                assertTrue(Math.abs(Noise.fbm3(kind, x, y, z, 3, 6, 2.0, 0.5)) <= 1.0 + 1e-12);
                assertEquals(Noise.fbm2(kind, x, y, 3, 4, 2.0, 0.5), Noise.warpedFbm2(kind, x, y, 3, 4, 2.0, 0.5, 0.0), 1e-12);
            }
            assertEquals(0.0, Noise.fbm2(kind, 1.5, 2.5, 3, 0, 2.0, 0.5));
        }
        assertNotEquals(Noise.fbm2(Kind.PERLIN, 1.37, 2.71, 3, 4, 2.0, 0.5), Noise.warpedFbm2(Kind.PERLIN, 1.37, 2.71, 3, 4, 2.0, 0.5, 2.0));
        // more octaves add finer detail: the sum differs from the first octave
        assertNotEquals(Noise.fbm2(Kind.SIMPLEX, 1.3, 2.7, 3, 1, 2.0, 0.5), Noise.fbm2(Kind.SIMPLEX, 1.3, 2.7, 3, 5, 2.0, 0.5));
    }

    @Test
    void fillWritesTheGridRowByRow() {
        for (Kind kind : Kind.values()) {
            float[] out = new float[5 * 3 + 2];
            java.util.Arrays.fill(out, Float.NaN);
            Noise.fill2(kind, out, 5, 3, 1.5, -2.0, 0.25, 0.5, 8, 3, 2.0, 0.5);
            for (int j = 0; j < 3; j++) {
                for (int i = 0; i < 5; i++) {
                    assertEquals((float) Noise.fbm2(kind, 1.5 + i * 0.25, -2.0 + j * 0.5, 8, 3, 2.0, 0.5), out[j * 5 + i]);
                }
            }
            assertTrue(Float.isNaN(out[15]), "nothing is written beyond the grid");
        }
        assertThrows(IllegalArgumentException.class, () -> Noise.fill2(Kind.PERLIN, new float[5], 3, 2, 0, 0, 1, 1, 0, 1, 2, 0.5));
        assertThrows(IllegalArgumentException.class, () -> Noise.fill2(Kind.PERLIN, new float[5], -1, 2, 0, 0, 1, 1, 0, 1, 2, 0.5));
    }
}
