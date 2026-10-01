package vmath.bulk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Comparator;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.bulk.LocalityOrder.Curve;

class SortingTest {

    private static final long SEED = Long.getLong("vmath.seed", 3L);
    private static final int[] SIZES = {0, 1, 2, 3, 47, 48, 49, 100, 1000, 4097, 100_000};

    /** The reference: a stable sort of the indices by the comparator (Java's object sort is stable). */
    private static int[] referenceOrder(int n, Comparator<Integer> byKey) {
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, byKey);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = idx[i];
        }
        return out;
    }

    private static int[] intKeys(SplittableRandom r, int n, int kind) {
        int[] k = new int[n];
        for (int i = 0; i < n; i++) {
            k[i] = switch (kind) {
                case 0 -> r.nextInt();
                case 1 -> r.nextInt(5) - 2;             // few distinct values, both signs
                case 2 -> 7;                              // all equal
                case 3 -> i;                              // already sorted
                case 4 -> n - i;                          // reverse
                case 5 -> r.nextInt(1 << 12);             // narrow range: the upper passes are skipped
                default -> r.nextInt() | 0x00FF0000;      // one digit constant
            };
        }
        return k;
    }

    @Test
    void intKeysSortLikeTheReferenceSignedAndUnsigned() {
        SplittableRandom r = new SplittableRandom(SEED);
        RadixSorter sorter = new RadixSorter();
        for (int n : SIZES) {
            for (int kind = 0; kind < 7; kind++) {
                int[] keys = intKeys(r, n, kind);
                for (boolean signed : new boolean[] {true, false}) {
                    int[] k = keys.clone(), v = new int[n];
                    for (int i = 0; i < n; i++) {
                        v[i] = i;
                    }
                    int[] expect = referenceOrder(n, (a, b) -> signed ? Integer.compare(keys[a], keys[b]) : Integer.compareUnsigned(keys[a], keys[b]));
                    if (signed) {
                        sorter.sort(k, v, n);
                    } else {
                        sorter.sortUnsigned(k, v, n);
                    }
                    assertArrayEquals(expect, v, "n=" + n + " kind=" + kind + " signed=" + signed);
                    for (int i = 0; i < n; i++) {
                        assertEquals(keys[v[i]], k[i]);
                    }
                    // without a payload the keys come out in the same order
                    int[] solo = keys.clone();
                    if (signed) {
                        sorter.sort(solo, null, n);
                    } else {
                        sorter.sortUnsigned(solo, null, n);
                    }
                    assertArrayEquals(k, solo);
                }
            }
        }
    }

    @Test
    void longKeysSortLikeTheReferenceSignedAndUnsigned() {
        SplittableRandom r = new SplittableRandom(SEED + 1);
        RadixSorter sorter = new RadixSorter();
        for (int n : SIZES) {
            for (int kind = 0; kind < 4; kind++) {
                long[] keys = new long[n];
                for (int i = 0; i < n; i++) {
                    keys[i] = switch (kind) {
                        case 0 -> r.nextLong();
                        case 1 -> r.nextLong(4) - 2;
                        case 2 -> r.nextLong() & 0x7FFFFFFFFFFFFFFFL;
                        default -> r.nextLong(1L << 20) << 40;
                    };
                }
                for (boolean signed : new boolean[] {true, false}) {
                    long[] k = keys.clone();
                    int[] v = new int[n];
                    for (int i = 0; i < n; i++) {
                        v[i] = i;
                    }
                    int[] expect = referenceOrder(n, (a, b) -> signed ? Long.compare(keys[a], keys[b]) : Long.compareUnsigned(keys[a], keys[b]));
                    if (signed) {
                        sorter.sort(k, v, n);
                    } else {
                        sorter.sortUnsigned(k, v, n);
                    }
                    assertArrayEquals(expect, v, "n=" + n + " kind=" + kind + " signed=" + signed);
                    for (int i = 0; i < n; i++) {
                        assertEquals(keys[v[i]], k[i]);
                    }
                }
            }
        }
    }

    private static float randomFloat(SplittableRandom r, int kind) {
        return switch (kind) {
            case 0 -> (float) ((r.nextDouble() - 0.5) * 1000);
            case 1 -> Float.intBitsToFloat(r.nextInt() & 0x7FFFFFFF ^ (r.nextBoolean() ? 0x80000000 : 0)); // any bit pattern, so NaNs of every sign and payload too
            default -> new float[] {0f, -0f, 1f, -1f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN, Float.MIN_VALUE, -Float.MIN_VALUE}[r.nextInt(9)];
        };
    }

    /** Float.compare order, but with every NaN replaced by a canonical one first, so that payloads cannot matter. */
    private static int compareFloats(float a, float b) {
        return Float.compare(a, b);
    }

    @Test
    void floatKeysSortLikeFloatCompareAscendingAndDescending() {
        SplittableRandom r = new SplittableRandom(SEED + 2);
        RadixSorter sorter = new RadixSorter();
        for (int n : SIZES) {
            for (int kind : new int[] {0, 2}) {
                float[] keys = new float[n];
                for (int i = 0; i < n; i++) {
                    keys[i] = randomFloat(r, kind);
                }
                for (boolean desc : new boolean[] {false, true}) {
                    int[] expect = referenceOrder(n, (a, b) -> desc ? compareFloats(keys[b], keys[a]) : compareFloats(keys[a], keys[b]));
                    // order(): keys untouched
                    float[] copy = keys.clone();
                    int[] order = new int[n];
                    sorter.order(copy, n, order, desc);
                    assertArrayEquals(keys, copy);
                    assertArrayEquals(expect, order, "order n=" + n + " kind=" + kind + " desc=" + desc);
                    // sort(): keys and payload permuted together
                    int[] v = new int[n];
                    for (int i = 0; i < n; i++) {
                        v[i] = i;
                    }
                    sorter.sort(copy, v, n, desc);
                    assertArrayEquals(expect, v, "sort n=" + n + " kind=" + kind + " desc=" + desc);
                    for (int i = 0; i < n; i++) {
                        assertEquals(Float.floatToRawIntBits(keys[v[i]]), Float.floatToRawIntBits(copy[i]), "keys travel with their payload");
                    }
                }
            }
        }
    }

    @Test
    void floatOrderIsTheIeeeTotalOrderIncludingNegativeNaNs() {
        float negNaN = Float.intBitsToFloat(0xFFC00000);
        float[] keys = {1f, Float.NaN, -0f, 0f, negNaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -1f};
        new RadixSorter().sort(keys, null, keys.length);
        assertEquals(Float.floatToRawIntBits(negNaN), Float.floatToRawIntBits(keys[0]));
        assertEquals(Float.NEGATIVE_INFINITY, keys[1]);
        assertEquals(-1f, keys[2]);
        assertEquals(Float.floatToRawIntBits(-0f), Float.floatToRawIntBits(keys[3]));
        assertEquals(Float.floatToRawIntBits(0f), Float.floatToRawIntBits(keys[4]));
        assertEquals(1f, keys[5]);
        assertEquals(Float.POSITIVE_INFINITY, keys[6]);
        assertTrue(Float.isNaN(keys[7]));
        // all bit patterns are preserved by the key transform, and the key order is the sort order
        SplittableRandom r = new SplittableRandom(SEED + 3);
        for (int k = 0; k < 100_000; k++) {
            float f = Float.intBitsToFloat(r.nextInt());
            assertEquals(Float.floatToRawIntBits(f), Float.floatToRawIntBits(RadixSorter.floatFromKey(RadixSorter.floatKey(f))));
            double d = Double.longBitsToDouble(r.nextLong());
            assertEquals(Double.doubleToRawLongBits(d), Double.doubleToRawLongBits(RadixSorter.doubleFromKey(RadixSorter.doubleKey(d))));
            float g = (float) ((r.nextDouble() - 0.5) * 1e6);
            if (f == f && g == g && f != g) {
                assertEquals(Float.compare(f, g) < 0, Integer.compareUnsigned(RadixSorter.floatKey(f), RadixSorter.floatKey(g)) < 0);
            }
        }
    }

    @Test
    void doubleKeysSortLikeDoubleCompare() {
        SplittableRandom r = new SplittableRandom(SEED + 4);
        RadixSorter sorter = new RadixSorter();
        for (int n : new int[] {0, 1, 40, 1000, 50_000}) {
            double[] keys = new double[n];
            for (int i = 0; i < n; i++) {
                keys[i] = i % 17 == 0 ? new double[] {0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}[r.nextInt(5)] : (r.nextDouble() - 0.5) * 1e9;
            }
            for (boolean desc : new boolean[] {false, true}) {
                int[] expect = referenceOrder(n, (a, b) -> desc ? Double.compare(keys[b], keys[a]) : Double.compare(keys[a], keys[b]));
                double[] copy = keys.clone();
                int[] order = new int[n];
                sorter.order(copy, n, order, desc);
                assertArrayEquals(keys, copy);
                assertArrayEquals(expect, order, "n=" + n + " desc=" + desc);
                int[] v = new int[n];
                for (int i = 0; i < n; i++) {
                    v[i] = i;
                }
                sorter.sort(copy, v, n, desc);
                assertArrayEquals(expect, v);
            }
        }
    }

    @Test
    void sortingOnlyTheFirstNLeavesTheRestAlone() {
        RadixSorter sorter = new RadixSorter();
        int[] keys = {5, 3, 9, 1, 7, 100, 50};
        int[] vals = {0, 1, 2, 3, 4, 5, 6};
        sorter.sort(keys, vals, 5);
        assertArrayEquals(new int[] {1, 3, 5, 7, 9, 100, 50}, keys);
        assertArrayEquals(new int[] {3, 1, 0, 4, 2, 5, 6}, vals);
        assertThrows(IllegalArgumentException.class, () -> sorter.sort(keys, vals, 8));
        assertThrows(IllegalArgumentException.class, () -> sorter.sort(keys, new int[3], 5));
        assertThrows(IllegalArgumentException.class, () -> sorter.sort(keys, vals, -1));
    }

    // ---------------------------------------------------------------- prefix sums

    @Test
    void scansMatchTheDefinition() {
        SplittableRandom r = new SplittableRandom(SEED + 5);
        for (int n : new int[] {0, 1, 2, 10, 1000, 65_537}) {
            int[] a = new int[n + 3];
            for (int i = 0; i < a.length; i++) {
                a[i] = r.nextInt(1000) - 200;
            }
            int[] ex = a.clone(), in = a.clone(), out = new int[a.length];
            long total = 0;
            int[] expectEx = new int[n], expectIn = new int[n];
            for (int i = 0; i < n; i++) {
                expectEx[i] = (int) total;
                total += a[i];
                expectIn[i] = (int) total;
            }
            assertEquals((int) total, PrefixSum.exclusive(ex, n));
            assertArrayEquals(expectEx, Arrays.copyOf(ex, n));
            assertEquals(a[n], ex[n], "elements past n are untouched");
            assertEquals((int) total, PrefixSum.inclusive(in, n));
            assertArrayEquals(expectIn, Arrays.copyOf(in, n));
            assertEquals((int) total, PrefixSum.exclusive(a, out, n));
            assertArrayEquals(expectEx, Arrays.copyOf(out, n));
            long[] wide = new long[n];
            assertEquals(total, PrefixSum.exclusive(a, wide, n));
            for (int i = 0; i < n; i++) {
                assertEquals(expectEx[i], wide[i]);
            }
            long[] l = new long[n];
            for (int i = 0; i < n; i++) {
                l[i] = a[i];
            }
            assertEquals(total, PrefixSum.exclusive(l, n));
        }
        // the widening scan does not overflow where the int scan wraps
        int[] big = {Integer.MAX_VALUE, Integer.MAX_VALUE, 5};
        long[] wide = new long[3];
        assertEquals(2L * Integer.MAX_VALUE + 5, PrefixSum.exclusive(big, wide, 3));
        assertEquals(2L * Integer.MAX_VALUE, wide[2]);
        assertThrows(IllegalArgumentException.class, () -> PrefixSum.exclusive(new int[2], 3));
    }

    @Test
    void parallelScanEqualsTheSequentialOne() {
        SplittableRandom r = new SplittableRandom(SEED + 6);
        for (int n : new int[] {0, 1, 7, 100, 12_345, 1_000_003}) {
            for (int chunks : new int[] {1, 2, 3, 8, 64, 5000}) {
                int[] a = new int[n];
                for (int i = 0; i < n; i++) {
                    a[i] = r.nextInt(100);
                }
                int[] b = a.clone();
                assertEquals(PrefixSum.exclusive(a, n), PrefixSum.exclusiveParallel(b, n, chunks), "n=" + n + " chunks=" + chunks);
                assertArrayEquals(a, b, "n=" + n + " chunks=" + chunks);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> PrefixSum.exclusiveParallel(new int[4], 4, 0));
        // the two primitives compose into the same scan
        int[] a = {3, 1, 4, 1, 5, 9, 2, 6};
        int s0 = PrefixSum.chunkSum(a, 0, 4), s1 = PrefixSum.chunkSum(a, 4, 8);
        assertEquals(PrefixSum.scanChunkExclusive(a, 0, 4, 0), s0);
        assertEquals(PrefixSum.scanChunkExclusive(a, 4, 8, s0), s0 + s1);
        assertArrayEquals(new int[] {0, 3, 4, 8, 9, 14, 23, 25}, a);
    }

    // ---------------------------------------------------------------- locality order

    private static float[] cloud(SplittableRandom r, int n) {
        float[] p = new float[3 * n];
        for (int i = 0; i < p.length; i++) {
            p[i] = (float) r.nextDouble();
        }
        return p;
    }

    private static double pathLength(float[] p, int[] order) {
        double sum = 0;
        for (int i = 1; i < order.length; i++) {
            double dx = p[3 * order[i]] - p[3 * order[i - 1]], dy = p[3 * order[i] + 1] - p[3 * order[i - 1] + 1], dz = p[3 * order[i] + 2] - p[3 * order[i - 1] + 2];
            sum += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return sum;
    }

    @Test
    void localityOrderIsAPermutationAndHilbertBeatsMortonBeatsInputOrder() {
        SplittableRandom r = new SplittableRandom(SEED + 7);
        int n = 50_000;
        float[] p = cloud(r, n);
        int[] identity = new int[n];
        for (int i = 0; i < n; i++) {
            identity[i] = i;
        }
        int[] morton = LocalityOrder.order(p, n, Curve.MORTON), hilbert = LocalityOrder.order(p, n, Curve.HILBERT);
        for (int[] order : new int[][] {morton, hilbert}) {
            int[] sorted = order.clone();
            Arrays.sort(sorted);
            assertArrayEquals(identity, sorted);
        }
        double random = pathLength(p, identity), m = pathLength(p, morton), h = pathLength(p, hilbert);
        System.out.printf("locality order, %d random points in the unit cube: path length input %.0f, Morton %.0f, Hilbert %.0f%n", n, random, m, h);
        assertTrue(h < m && m < random / 10, "input " + random + " morton " + m + " hilbert " + h);
    }

    @Test
    void localityOrderHandlesFlatAndDegenerateInput() {
        float[] same = new float[3 * 10];
        Arrays.fill(same, 2.5f);
        int[] order = LocalityOrder.order(same, 10, Curve.HILBERT);
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, order, "equal codes keep their order");
        // a flat plane
        float[] plane = new float[3 * 100];
        SplittableRandom r = new SplittableRandom(SEED + 8);
        for (int i = 0; i < 100; i++) {
            plane[3 * i] = (float) r.nextDouble();
            plane[3 * i + 2] = (float) r.nextDouble();
            plane[3 * i + 1] = 4f;
        }
        assertEquals(100, LocalityOrder.order(plane, 100, Curve.MORTON).length);
        assertEquals(0, LocalityOrder.order(new float[0], 0, Curve.HILBERT).length);
        assertEquals(1, LocalityOrder.order(new float[3], 1, Curve.HILBERT).length);
        // huge but finite ranges do not overflow
        float[] wide = {-Float.MAX_VALUE, 0f, 0f, Float.MAX_VALUE, 0f, 0f, 0f, 0f, 0f};
        assertEquals(3, LocalityOrder.order(wide, 3, Curve.MORTON).length);
        assertThrows(IllegalArgumentException.class, () -> LocalityOrder.order(new float[] {0f, Float.NaN, 0f}, 1, Curve.HILBERT));
        assertThrows(IllegalArgumentException.class, () -> LocalityOrder.order(new float[] {0f, Float.POSITIVE_INFINITY, 0f}, 1, Curve.MORTON));
        assertThrows(IllegalArgumentException.class, () -> LocalityOrder.order(new float[5], 2, Curve.MORTON));
    }
}
