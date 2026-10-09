package vmath.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Rnd;

class RollingStatsTest {

    private final SplittableRandom rng = new SplittableRandom(Rnd.SEED);

    /** The percentile by the definition: sort and interpolate linearly between the closest ranks. */
    private static double reference(double[] window, double p) {
        double[] s = window.clone();
        Arrays.sort(s);
        double rank = p / 100.0 * (s.length - 1);
        int lo = (int) Math.floor(rank);
        double frac = rank - lo;
        return lo + 1 >= s.length ? s[lo] : s[lo] + frac * (s[lo + 1] - s[lo]);
    }

    @Test
    void anEmptyWindowGivesNaNEverywhere() {
        RollingStats s = new RollingStats(4);
        assertEquals(0, s.size());
        assertTrue(Double.isNaN(s.min()) && Double.isNaN(s.max()) && Double.isNaN(s.mean()) && Double.isNaN(s.stdDev()) && Double.isNaN(s.percentile(50)));
        double[] out = new double[2];
        s.percentiles(new double[] {10, 90}, out);
        assertTrue(Double.isNaN(out[0]) && Double.isNaN(out[1]));
        assertEquals(0, s.countAbove(-1e9));
    }

    @Test
    void knownValues() {
        RollingStats s = new RollingStats(10);
        for (double v : new double[] {1, 2, 3, 4}) {
            s.add(v);
        }
        assertEquals(1, s.min());
        assertEquals(4, s.max());
        assertEquals(2.5, s.mean(), 1e-15);
        assertEquals(2.5, s.percentile(50), 1e-15);
        assertEquals(1, s.percentile(0));
        assertEquals(4, s.percentile(100));
        assertEquals(1.75, s.percentile(25), 1e-15);
        assertEquals(Math.sqrt(1.25), s.stdDev(), 1e-15, "population standard deviation of 1 2 3 4");
        assertEquals(2, s.countAbove(2));
        // one sample: every percentile is that sample
        RollingStats one = new RollingStats(3);
        one.add(7.5);
        assertEquals(7.5, one.percentile(0));
        assertEquals(7.5, one.percentile(63));
        assertEquals(7.5, one.percentile(100));
        assertEquals(0.0, one.stdDev());
    }

    @Test
    void bulkPercentilesOrderSignedZeroLikeDoubleSort() {
        RollingStats s = new RollingStats(2);
        s.add(0.0);
        s.add(-0.0);
        double[] out = new double[2];

        s.percentiles(new double[] {0, 100}, out);

        assertEquals(-0.0, out[0]);
        assertEquals(0.0, out[1]);
    }

    @Test
    void theWindowKeepsTheLastSamplesAndCountsAllOfThem() {
        RollingStats s = new RollingStats(5);
        for (int i = 1; i <= 12; i++) {
            s.add(i);
        }
        assertEquals(5, s.size());
        assertEquals(12, s.totalCount());
        assertEquals(8, s.get(0), "the oldest of the window");
        assertEquals(12, s.get(4), "the newest");
        assertEquals(8, s.min());
        assertEquals(12, s.max());
        assertEquals(10, s.mean(), 1e-15);
        assertThrows(IndexOutOfBoundsException.class, () -> s.get(5));
        s.reset();
        assertEquals(0, s.size());
        assertEquals(0, s.totalCount());
        assertTrue(Double.isNaN(s.mean()));
    }

    @Test
    void percentilesAgreeWithTheSortedDefinitionOnRandomWindowsWithRepeats() {
        for (int trial = 0; trial < 300; trial++) {
            int window = 1 + rng.nextInt(60);
            RollingStats s = new RollingStats(window);
            int n = rng.nextInt(2 * window + 1);
            int distinct = 1 + rng.nextInt(8); // many repeated values stress the partition
            double[] all = new double[n];
            for (int i = 0; i < n; i++) {
                all[i] = rng.nextBoolean() ? rng.nextInt(distinct) : rng.nextDouble(-100, 100);
                s.add(all[i]);
            }
            if (n == 0) {
                continue;
            }
            double[] last = Arrays.copyOfRange(all, Math.max(0, n - window), n);
            double[] ps = {0, 1, 25, 33.3, 50, 75, 99, 99.9, 100};
            double[] out = new double[ps.length];
            s.percentiles(ps, out);
            for (int k = 0; k < ps.length; k++) {
                assertEquals(reference(last, ps[k]), s.percentile(ps[k]), 1e-12, "trial " + trial + " p " + ps[k]);
                assertEquals(reference(last, ps[k]), out[k], 1e-12);
            }
            assertEquals(Arrays.stream(last).min().getAsDouble(), s.min());
            assertEquals(Arrays.stream(last).max().getAsDouble(), s.max());
            assertEquals(Arrays.stream(last).sum() / last.length, s.mean(), 1e-10);
        }
    }

    @Test
    void badInputIsRefused() {
        RollingStats s = new RollingStats(3);
        assertThrows(IllegalArgumentException.class, () -> new RollingStats(0));
        assertThrows(IllegalArgumentException.class, () -> s.add(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> s.add(Double.POSITIVE_INFINITY));
        assertEquals(0, s.size(), "a refused sample is not added");
        s.add(1);
        assertThrows(IllegalArgumentException.class, () -> s.percentile(-0.1));
        assertThrows(IllegalArgumentException.class, () -> s.percentile(100.1));
        assertThrows(IllegalArgumentException.class, () -> s.percentile(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> s.percentiles(new double[] {50}, new double[0]));
        assertThrows(IllegalArgumentException.class, () -> s.percentiles(new double[] {101}, new double[1]));
    }
}
