package vmath.bulk;

import java.util.stream.IntStream;
import vmath.annotations.Experimental;

/**
 * Prefix sums (scans) over the first {@code n} elements of an array: the building block for turning
 * counts into offsets (counting sorts, compaction, building index buffers).
 *
 * <p>The sums wrap on overflow like ordinary {@code int} arithmetic; use the {@code long[]}
 * overloads when totals can exceed 2^31.
 *
 * <p>The sequential scans are single passes. For large arrays the scan splits into independent
 * chunks: {@link #chunkSum} on every chunk (in parallel), a short sequential scan of the chunk
 * totals, then {@link #scanChunkExclusive} on every chunk (in parallel) with its starting offset.
 * {@link #exclusiveParallel} does exactly that on the common fork-join pool (it allocates a small
 * amount for the pool tasks, so it is not for per-frame hot paths); to schedule the chunks
 * yourself, call the two primitives.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time. The arrays and buffers you pass in are not synchronised, so two threads must not write
 * the same one.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * int[] counts = {3, 1, 4, 1, 5};
 * int total = PrefixSum.exclusive(counts, counts.length);                    // counts becomes {0, 3, 4, 8, 9}; total is 14
 * }</pre>
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

    /**
     * Returns in place: {@code a[i]} becomes the sum of the elements before it ({@code a[0]}
     * becomes 0).
     *
     * <p>Returns the total of all {@code n} elements.
     *
     * @param a the array that is scanned in place
     * @param n the number of elements
     * @return the total of all {@code n} elements
     */
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

    /**
     * Returns in place: {@code a[i]} becomes the sum of the elements up to and including it.
     *
     * <p>Returns the total.
     *
     * @param a the array that is scanned in place
     * @param n the number of elements
     * @return the total
     */
    public static int inclusive(int[] a, int n) {
        check(a.length, n);
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum += a[i];
            a[i] = sum;
        }
        return sum;
    }

    /**
     * Computes the exclusive prefix sum, in which each output is the sum of all inputs before it;
     * the output may be the input array.
     *
     * <p>Returns the total.
     *
     * @param in the input array, not changed
     * @param out receives the result
     * @param n the number of elements
     * @return {@code out[i]} is the sum of {@code in[0..i)}; {@code in} is unchanged ({@code out}
     *     may be the same array)
     */
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

    /**
     * Computes the exclusive prefix sum of a long array in place.
     *
     * @param a the array that is scanned in place
     * @param n the number of elements
     * @return {@link #exclusive(int[], int)} for {@code long} elements
     */
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

    /**
     * Computes the exclusive prefix sum of an int array into a long array, which cannot overflow
     * for large counts.
     *
     * <p>Returns the total.
     *
     * @param in the input array, not changed
     * @param out receives the result
     * @param n the number of elements
     * @return widening scan: {@code out[i]} is the sum of {@code in[0..i)} as a {@code long}, so
     *     counts of many elements cannot overflow
     */
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

    /**
     * Sums a chunk of an array, which is the first pass of a parallel scan.
     *
     * @param a the array that is scanned in place
     * @param from the first index, inclusive
     * @param to the last index, exclusive
     * @return the sum of {@code a[from..to)}: step one of a chunked scan
     */
    public static int chunkSum(int[] a, int from, int to) {
        int sum = 0;
        for (int i = from; i < to; i++) {
            sum += a[i];
        }
        return sum;
    }

    /**
     * Scans a chunk in place starting from an offset, which is the second pass of a parallel scan,
     * and reports the running total for the next chunk.
     *
     * <p>Step three of a chunked scan.
     *
     * @param a the array that is scanned in place
     * @param from the first index, inclusive
     * @param to the last index, exclusive
     * @param offset the index of the first element to read or write
     * @return exclusive scan of {@code a[from..to)} in place, starting at {@code offset}; returns
     *     {@code offset} plus the chunk's sum
     */
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
     * Computes the exclusive prefix sum in place with a chunked parallel algorithm on the common
     * pool; for small arrays the sequential version is faster.
     *
     * <p>The result is identical to {@link #exclusive(int[], int)}. Worth it only for arrays of
     * millions of elements (a scan is memory-bound); see {@code docs/BULK.md} for the measurement.
     *
     * @param a the array that is scanned in place
     * @param n the number of elements
     * @param chunks the chunks
     * @return the total
     * @throws IllegalArgumentException if {@code chunks} is below 1
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

    /**
     * Computes the exclusive prefix sum in place with a chunked parallel algorithm on a given
     * executor; for small arrays the sequential version is faster.
     *
     * <p>The calling thread runs the first chunk itself and waits for the others, so the executor
     * must eventually run every task it accepts (see {@link vmath.spatial.ParallelFrustumKernel}'s
     * executor contract) and the call must not be made from a thread that the executor needs in
     * order to make progress. Failures of a chunk are rethrown after all chunks have finished.
     *
     * @param a the array that is scanned in place
     * @param n the number of elements
     * @param chunks the chunks
     * @param executor the executor; must not be {@code null}
     * @return the total
     * @throws IllegalArgumentException if {@code chunks} is below 1
     * @throws NullPointerException if {@code executor} is {@code null}
     */
    public static int exclusiveParallel(int[] a, int n, int chunks, java.util.concurrent.Executor executor) {
        check(a.length, n);
        if (chunks < 1) {
            throw new IllegalArgumentException("chunks must be at least 1: " + chunks);
        }
        java.util.Objects.requireNonNull(executor, "executor");
        if (chunks == 1 || n < 2 * chunks) {
            return exclusive(a, n);
        }
        int size = (n + chunks - 1) / chunks;
        int count = (n + size - 1) / size;
        int[] totals = new int[count + 1];
        runChunks(count, executor, c -> totals[c + 1] = chunkSum(a, c * size, Math.min(n, (c + 1) * size)));
        for (int c = 0; c < count; c++) {
            totals[c + 1] += totals[c];
        }
        runChunks(count, executor, c -> scanChunkExclusive(a, c * size, Math.min(n, (c + 1) * size), totals[c]));
        return totals[count];
    }

    /**
     * Runs {@code body(0 .. count - 1)}: chunk 0 here, the others on the executor, and waits for
     * all of them.
     */
    private static void runChunks(int count, java.util.concurrent.Executor executor, java.util.function.IntConsumer body) {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(count - 1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        int handed = 0;
        try {
            for (int c = 1; c < count; c++) {
                int chunk = c;
                executor.execute(() -> {
                    try {
                        body.accept(chunk);
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                });
                handed++;
            }
        } catch (RuntimeException | Error e) {
            // the executor refused a task: wait for the ones it took, then report the refusal
            for (int c = handed + 1; c < count; c++) {
                done.countDown();
            }
            awaitUninterruptibly(done);
            throw e;
        }
        try {
            body.accept(0);
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
        awaitUninterruptibly(done);
        Throwable t = failure.get();
        if (t instanceof RuntimeException re) {
            throw re;
        }
        if (t instanceof Error err) {
            throw err;
        }
        if (t != null) {
            throw new IllegalStateException(t);
        }
    }

    private static void awaitUninterruptibly(java.util.concurrent.CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
