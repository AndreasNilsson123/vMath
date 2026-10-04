package vmath.samples.framework;

import java.lang.management.ManagementFactory;

/**
 * Runs a piece of work until the JIT has compiled it, which a demo needs before its first frame so
 * that the frames measure the compiled code.
 *
 * <p>The SIMD kernels of the library allocate a vector object per operation while they are
 * interpreted or only partly compiled: about 9.5 MB per frame over the first 100 frames of a
 * 250,000-box city, and on a small scene it takes longer, because the compiler needs time and
 * not only iterations. Waiting for a fixed number of rounds does not tell when that is over;
 * measuring the allocation of the round does: the work is warm when twenty rounds in a row
 * allocate almost nothing. This does not make a short run immune: the compiler can still throw
 * compiled code away when the data takes a branch that the warm-up did not (a camera that has moved
 * on), which is why the smoke run also keeps 300 warm-up frames.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Stateless: the method measures the calling thread.
 */
public final class Warmup {

    private static final long QUIET_BYTES = 4096;
    private static final int QUIET_ROUNDS = 20;

    private Warmup() {
    }

    /**
     * Runs the round repeatedly until twenty consecutive rounds allocate less than 4 kB each on
     * the calling thread, or until the time limit passes.
     *
     * @param round one round of the work, such as one culling pass; must not be {@code null}
     * @param limitMillis the longest to keep going, in milliseconds
     * @return the number of rounds that were run
     */
    public static int untilQuiet(Runnable round, long limitMillis) {
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long deadline = System.nanoTime() + limitMillis * 1_000_000L;
        int quiet = 0, rounds = 0;
        while (quiet < QUIET_ROUNDS && System.nanoTime() < deadline) {
            long before = mx.getThreadAllocatedBytes(tid);
            round.run();
            long used = mx.getThreadAllocatedBytes(tid) - before;
            quiet = used < QUIET_BYTES ? quiet + 1 : 0;
            rounds++;
        }
        return rounds;
    }
}
