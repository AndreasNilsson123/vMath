package vmath.bulk;

import java.util.Arrays;
import vmath.annotations.Experimental;

/**
 * Least-significant-digit radix sort of 32- and 64-bit keys with an optional {@code int} payload (typically an index or an id), for draw sorting, locality
 * sorting by {@link vmath.core.Morton}/{@link vmath.core.Hilbert} code and BVH construction. The sort is <b>stable</b> (equal keys keep their order), works on the
 * first {@code n} elements of the arrays, and sorts in place; the keys and the payload are permuted together.
 *
 * <p>An instance owns the scratch arrays and grows them on demand, so after the first call with a given size nothing is allocated. Use one instance per thread.
 * {@link #reserve} preallocates.
 *
 * <p><b>Order of floating-point keys</b> is the IEEE total order: {@code -NaN < -Infinity < ... < -0.0 < +0.0 < ... < +Infinity < +NaN}, which differs from
 * {@code Arrays.sort} only in that a NaN with the sign bit set sorts first instead of last. Pass {@code descending = true} for the reverse order; equal keys still
 * keep their original order.
 */
@Experimental("the set of overloads may grow")
public final class RadixSorter {

    private static final int BITS = 11;
    private static final int RADIX = 1 << BITS;
    private static final int MASK = RADIX - 1;
    private static final int PASSES_32 = 3;
    private static final int PASSES_64 = 6;
    /** Below this many elements an insertion sort beats clearing the histograms. */
    private static final int SMALL = 48;

    private final int[] histogram = new int[PASSES_64 * RADIX];
    private int[] keyBuffer = new int[0];
    private long[] longKeyBuffer = new long[0];
    private int[] valueBuffer = new int[0];
    private int[] converted = new int[0];
    private long[] longConverted = new long[0];

    /** A sorter with no buffers yet; they are allocated by the first sort, or by {@link #reserve}. */
    public RadixSorter() {
    }

    /** Makes sure that sorting up to {@code n} elements will not allocate: reserves for the 32-bit, 64-bit, payload and float/double conversion buffers. */
    public void reserve(int n) {
        keyBuffer = grow(keyBuffer, n);
        longKeyBuffer = grow(longKeyBuffer, n);
        valueBuffer = grow(valueBuffer, n);
        converted = grow(converted, n);
        longConverted = grow(longConverted, n);
    }

    private static int[] grow(int[] a, int n) {
        return a.length >= n ? a : new int[Math.max(n, a.length + (a.length >> 1))];
    }

    private static long[] grow(long[] a, int n) {
        return a.length >= n ? a : new long[Math.max(n, a.length + (a.length >> 1))];
    }

    private static void check(int keyLength, int[] values, int n) {
        if (n < 0 || n > keyLength || (values != null && n > values.length)) {
            throw new IllegalArgumentException("n = " + n + " does not fit the arrays (keys " + keyLength + ", values " + (values == null ? "none" : values.length) + ")");
        }
    }

    // ---------------------------------------------------------------- the float order keys

    /** The int whose <em>unsigned</em> order is the IEEE total order of the float. */
    public static int floatKey(float f) {
        int b = Float.floatToRawIntBits(f);
        return b ^ ((b >> 31) | 0x80000000);
    }

    /** The inverse of {@link #floatKey}. */
    public static float floatFromKey(int key) {
        return Float.intBitsToFloat(key ^ ((~key >> 31) | 0x80000000));
    }

    /** The long whose unsigned order is the IEEE total order of the double. */
    public static long doubleKey(double d) {
        long b = Double.doubleToRawLongBits(d);
        return b ^ ((b >> 63) | 0x8000000000000000L);
    }

    /** The inverse of {@link #doubleKey}. */
    public static double doubleFromKey(long key) {
        return Double.longBitsToDouble(key ^ ((~key >> 63) | 0x8000000000000000L));
    }

    // ---------------------------------------------------------------- int keys

    /** Sorts {@code keys[0..n)} as unsigned integers; {@code values} (may be {@code null}) is permuted along. */
    public void sortUnsigned(int[] keys, int[] values, int n) {
        check(keys.length, values, n);
        sort32(keys, values, n, false);
    }

    /** Sorts {@code keys[0..n)} as signed integers; {@code values} (may be {@code null}) is permuted along. */
    public void sort(int[] keys, int[] values, int n) {
        check(keys.length, values, n);
        sort32(keys, values, n, true);
    }

    /** Sorts {@code keys[0..n)} as unsigned 64-bit integers (Morton and Hilbert codes of 2D grids use all 64 bits). */
    public void sortUnsigned(long[] keys, int[] values, int n) {
        check(keys.length, values, n);
        sort64(keys, values, n, false);
    }

    /** Sorts {@code keys[0..n)} as signed 64-bit integers. */
    public void sort(long[] keys, int[] values, int n) {
        check(keys.length, values, n);
        sort64(keys, values, n, true);
    }

    // ---------------------------------------------------------------- float and double keys

    /** Sorts {@code keys[0..n)} ascending; {@code values} (may be {@code null}) is permuted along. */
    public void sort(float[] keys, int[] values, int n) {
        sort(keys, values, n, false);
    }

    /** Sorts {@code keys[0..n)} ascending or descending; equal keys keep their order either way. */
    public void sort(float[] keys, int[] values, int n, boolean descending) {
        check(keys.length, values, n);
        converted = grow(converted, n);
        int[] c = converted;
        int flip = descending ? -1 : 0;
        for (int i = 0; i < n; i++) {
            c[i] = floatKey(keys[i]) ^ flip;
        }
        sort32(c, values, n, false);
        for (int i = 0; i < n; i++) {
            keys[i] = floatFromKey(c[i] ^ flip);
        }
    }

    /**
     * Writes the permutation that sorts {@code keys[0..n)} to {@code order[0..n)} ({@code order[i]} is the index of the element that comes {@code i}-th) and leaves
     * {@code keys} untouched.
     */
    public void order(float[] keys, int n, int[] order, boolean descending) {
        check(keys.length, order, n);
        converted = grow(converted, n);
        int[] c = converted;
        int flip = descending ? -1 : 0;
        for (int i = 0; i < n; i++) {
            c[i] = floatKey(keys[i]) ^ flip;
            order[i] = i;
        }
        sort32(c, order, n, false);
    }

    /** Sorts {@code keys[0..n)} ascending or descending; {@code values} (may be {@code null}) is permuted along. */
    public void sort(double[] keys, int[] values, int n, boolean descending) {
        check(keys.length, values, n);
        longConverted = grow(longConverted, n);
        long[] c = longConverted;
        long flip = descending ? -1L : 0L;
        for (int i = 0; i < n; i++) {
            c[i] = doubleKey(keys[i]) ^ flip;
        }
        sort64(c, values, n, false);
        for (int i = 0; i < n; i++) {
            keys[i] = doubleFromKey(c[i] ^ flip);
        }
    }

    /** As {@link #order(float[], int, int[], boolean)} for double keys. */
    public void order(double[] keys, int n, int[] order, boolean descending) {
        check(keys.length, order, n);
        longConverted = grow(longConverted, n);
        long[] c = longConverted;
        long flip = descending ? -1L : 0L;
        for (int i = 0; i < n; i++) {
            c[i] = doubleKey(keys[i]) ^ flip;
            order[i] = i;
        }
        sort64(c, order, n, false);
    }

    // ---------------------------------------------------------------- the passes

    /** {@code signed} flips the top bit of the key's order, which is how signed integers become unsigned ones. */
    private void sort32(int[] k, int[] v, int n, boolean signed) {
        if (n < 2) {
            return;
        }
        int flip = signed ? Integer.MIN_VALUE : 0;
        if (n <= SMALL) {
            for (int i = 1; i < n; i++) {
                int key = k[i], ord = key ^ flip;
                int val = v == null ? 0 : v[i];
                int j = i - 1;
                while (j >= 0 && Integer.compareUnsigned(k[j] ^ flip, ord) > 0) {
                    k[j + 1] = k[j];
                    if (v != null) {
                        v[j + 1] = v[j];
                    }
                    j--;
                }
                k[j + 1] = key;
                if (v != null) {
                    v[j + 1] = val;
                }
            }
            return;
        }
        keyBuffer = grow(keyBuffer, n);
        if (v != null) {
            valueBuffer = grow(valueBuffer, n);
        }
        int[] h = histogram;
        Arrays.fill(h, 0, PASSES_32 * RADIX, 0);
        for (int i = 0; i < n; i++) {
            int x = k[i] ^ flip;
            h[x & MASK]++;
            h[RADIX + ((x >>> BITS) & MASK)]++;
            h[2 * RADIX + (x >>> (2 * BITS))]++;
        }
        int[] srcK = k, dstK = keyBuffer, srcV = v, dstV = valueBuffer;
        for (int pass = 0; pass < PASSES_32; pass++) {
            int off = pass * RADIX, shift = pass * BITS;
            if (h[off + (((srcK[0] ^ flip) >>> shift) & MASK)] == n) {
                continue; // every key has the same digit: nothing to do
            }
            int sum = 0;
            for (int d = 0; d < RADIX; d++) {
                int c = h[off + d];
                h[off + d] = sum;
                sum += c;
            }
            if (v == null) {
                for (int i = 0; i < n; i++) {
                    int x = srcK[i];
                    dstK[h[off + (((x ^ flip) >>> shift) & MASK)]++] = x;
                }
            } else {
                for (int i = 0; i < n; i++) {
                    int x = srcK[i];
                    int p = h[off + (((x ^ flip) >>> shift) & MASK)]++;
                    dstK[p] = x;
                    dstV[p] = srcV[i];
                }
            }
            int[] t = srcK;
            srcK = dstK;
            dstK = t;
            t = srcV;
            srcV = dstV;
            dstV = t;
        }
        if (srcK != k) {
            System.arraycopy(srcK, 0, k, 0, n);
            if (v != null) {
                System.arraycopy(srcV, 0, v, 0, n);
            }
        }
    }

    private void sort64(long[] k, int[] v, int n, boolean signed) {
        if (n < 2) {
            return;
        }
        long flip = signed ? Long.MIN_VALUE : 0L;
        if (n <= SMALL) {
            for (int i = 1; i < n; i++) {
                long key = k[i], ord = key ^ flip;
                int val = v == null ? 0 : v[i];
                int j = i - 1;
                while (j >= 0 && Long.compareUnsigned(k[j] ^ flip, ord) > 0) {
                    k[j + 1] = k[j];
                    if (v != null) {
                        v[j + 1] = v[j];
                    }
                    j--;
                }
                k[j + 1] = key;
                if (v != null) {
                    v[j + 1] = val;
                }
            }
            return;
        }
        longKeyBuffer = grow(longKeyBuffer, n);
        if (v != null) {
            valueBuffer = grow(valueBuffer, n);
        }
        int[] h = histogram;
        Arrays.fill(h, 0, PASSES_64 * RADIX, 0);
        for (int i = 0; i < n; i++) {
            long x = k[i] ^ flip;
            h[(int) x & MASK]++;
            h[RADIX + ((int) (x >>> BITS) & MASK)]++;
            h[2 * RADIX + ((int) (x >>> (2 * BITS)) & MASK)]++;
            h[3 * RADIX + ((int) (x >>> (3 * BITS)) & MASK)]++;
            h[4 * RADIX + ((int) (x >>> (4 * BITS)) & MASK)]++;
            h[5 * RADIX + (int) (x >>> (5 * BITS))]++;
        }
        long[] srcK = k, dstK = longKeyBuffer;
        int[] srcV = v, dstV = valueBuffer;
        for (int pass = 0; pass < PASSES_64; pass++) {
            int off = pass * RADIX, shift = pass * BITS;
            if (h[off + ((int) ((srcK[0] ^ flip) >>> shift) & MASK)] == n) {
                continue;
            }
            int sum = 0;
            for (int d = 0; d < RADIX; d++) {
                int c = h[off + d];
                h[off + d] = sum;
                sum += c;
            }
            if (v == null) {
                for (int i = 0; i < n; i++) {
                    long x = srcK[i];
                    dstK[h[off + ((int) ((x ^ flip) >>> shift) & MASK)]++] = x;
                }
            } else {
                for (int i = 0; i < n; i++) {
                    long x = srcK[i];
                    int p = h[off + ((int) ((x ^ flip) >>> shift) & MASK)]++;
                    dstK[p] = x;
                    dstV[p] = srcV[i];
                }
            }
            long[] t = srcK;
            srcK = dstK;
            dstK = t;
            int[] u = srcV;
            srcV = dstV;
            dstV = u;
        }
        if (srcK != k) {
            System.arraycopy(srcK, 0, k, 0, n);
            if (v != null) {
                System.arraycopy(srcV, 0, v, 0, n);
            }
        }
    }
}
