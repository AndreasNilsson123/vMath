package vmath.bulk;

import java.util.stream.IntStream;
import vmath.annotations.Experimental;

/**
 * Prefix sums (scans) over the first {@code n} elements of an array: the building block for turning counts into offsets (counting sorts, compaction, building
 * index buffers). The sums wrap on overflow like ordinary {@code int} arithmetic; use the {@code long[]} overloads when totals can exceed 2^31.
 *
 * <p>The sequential scans are single passes. For large arrays the scan splits into independent chunks: {@link #chunkSum} on every chunk (in parallel), a short
 * sequential scan of the chunk totals, then {@link #scanChunkExclusive} on every chunk (in parallel) with its starting offset. {@link #exclusiveParallel} does
 * exactly that on the common fork-join pool (it allocates a small amount for the pool tasks, so it is not for per-frame hot paths); to schedule the chunks
 * yourself, call the two primitives.
 */
@Experimental("the set of overloads may grow")
public final class PrefixSum {

    private PrefixSum() {
    }

    private static void check(int length, int n) {
        if (n < 0 || n > length) {
            throw new IllegalArgumentException("n = " + n + " does not fit an array of " + length);
        }
    }

    /** In place: {@code a[i]} becomes the sum of the elements before it ({@code a[0]} becomes 0). Returns the total of all {@code n} elements. */
    public static int exclusive(int[] a, int n) {
        check(a.length, n);
        int sum = 0;
        for (int i = 0; i < n; i++) {
            int v = a[i];
            a[i] = sum;
            sum += v;
        }
        return sum;
    }

    /** In place: {@code a[i]} becomes the sum of the elements up to and including it. Returns the total. */
    public static int inclusive(int[] a, int n) {
        check(a.length, n);
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum += a[i];
            a[i] = sum;
        }
        return sum;
    }

    /** {@code out[i]} is the sum of {@code in[0..i)}; {@code in} is unchanged ({@code out} may be the same array). Returns the total. */
    public static int exclusive(int[] in, int[] out, int n) {
        check(in.length, n);
        check(out.length, n);
        int sum = 0;
        for (int i = 0; i < n; i++) {
            int v = in[i];
            out[i] = sum;
            sum += v;
        }
        return sum;
    }

    /** {@link #exclusive(int[], int)} for {@code long} elements. */
    public static long exclusive(long[] a, int n) {
        check(a.length, n);
        long sum = 0;
        for (int i = 0; i < n; i++) {
            long v = a[i];
            a[i] = sum;
            sum += v;
        }
        return sum;
    }

    /** Widening scan: {@code out[i]} is the sum of {@code in[0..i)} as a {@code long}, so counts of many elements cannot overflow. Returns the total. */
    public static long exclusive(int[] in, long[] out, int n) {
        check(in.length, n);
        check(out.length, n);
        long sum = 0;
        for (int i = 0; i < n; i++) {
            out[i] = sum;
            sum += in[i];
        }
        return sum;
    }

    // ---------------------------------------------------------------- chunked scans

    /** The sum of {@code a[from..to)}: step one of a chunked scan. */
    public static int chunkSum(int[] a, int from, int to) {
        int sum = 0;
        for (int i = from; i < to; i++) {
            sum += a[i];
        }
        return sum;
    }

    /** Exclusive scan of {@code a[from..to)} in place, starting at {@code offset}; returns {@code offset} plus the chunk's sum. Step three of a chunked scan. */
    public static int scanChunkExclusive(int[] a, int from, int to, int offset) {
        int sum = offset;
        for (int i = from; i < to; i++) {
            int v = a[i];
            a[i] = sum;
            sum += v;
        }
        return sum;
    }

    /**
     * Exclusive scan in place, in {@code chunks} chunks processed in parallel. The result is identical to {@link #exclusive(int[], int)}. Worth it only for arrays of
     * millions of elements (a scan is memory-bound); see {@code docs/BULK.md} for the measurement.
     *
     * @return the total
     */
    public static int exclusiveParallel(int[] a, int n, int chunks) {
        check(a.length, n);
        if (chunks < 1) {
            throw new IllegalArgumentException("chunks must be at least 1: " + chunks);
        }
        if (chunks == 1 || n < 2 * chunks) {
            return exclusive(a, n);
        }
        int size = (n + chunks - 1) / chunks;
        int count = (n + size - 1) / size;
        int[] totals = new int[count + 1];
        IntStream.range(0, count).parallel().forEach(c -> totals[c + 1] = chunkSum(a, c * size, Math.min(n, (c + 1) * size)));
        for (int c = 0; c < count; c++) {
            totals[c + 1] += totals[c];
        }
        IntStream.range(0, count).parallel().forEach(c -> scanChunkExclusive(a, c * size, Math.min(n, (c + 1) * size), totals[c]));
        return totals[count];
    }
}
