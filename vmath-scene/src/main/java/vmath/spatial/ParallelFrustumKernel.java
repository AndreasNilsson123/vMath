package vmath.spatial;

import java.util.concurrent.Executor;
import java.util.concurrent.Phaser;
import java.util.function.Supplier;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.geo.Frustumf;

/**
 * Runs a {@link FrustumKernel} on several threads.
 *
 * <p>The object range is cut into chunks that start at multiples of 64, so every chunk writes its
 * own words of the {@link VisibilitySet} and no synchronisation is needed while culling; the result
 * is <b>bit-identical</b> to the serial kernel.
 *
 * <p>The caller supplies the {@link Executor} (a fixed pool or {@code ForkJoinPool.commonPool()});
 * this class never creates threads. The calling thread does one chunk itself, so {@code parts}
 * chunks need only {@code parts - 1} pool threads. Each chunk has its own kernel instance (kernels
 * own scratch memory). The driver itself allocates nothing per call; the executor may (a
 * {@code ThreadPoolExecutor} queues a node of about 50 bytes per handed-out chunk).
 *
 * <p>Measured at 1M objects on 12 logical cores ({@code ParallelCullBench}): 2.25 ms serial, 1.56
 * ms with 2 chunks, 0.99 ms with 4, 0.76 ms with 8. The kernel is memory-bound, so the gain
 * flattens well before the core count.
 *
 * <p><b>Executor contract.</b> The executor must eventually run every task it accepts. {@code cull}
 * returns only when all chunks have finished, with no timeout (a chunk that is still running after
 * a timeout would write into the result set after the call returned, which is worse than waiting),
 * so an executor that accepts a task and then discards it, for example a pool shut down with
 * {@code shutdownNow()} while the task was still queued, blocks the calling thread forever. An
 * executor that <em>rejects</em> a task (throws from {@code execute}) is handled: the chunks
 * already handed out are awaited and the exception is rethrown.
 *
 * <p>Ranges too small to pay for the hand-off ({@code < 2 * MIN_CHUNK} objects) run serially on the
 * calling thread.
 *
 * <p>An instance is not thread-safe: one call at a time.
 *
 * <p><b>Thread safety.</b> Not specified for concurrent calls of {@code cull} on one instance; use
 * one call at a time. The chunks run on the threads of the supplied executor and the calling thread
 * runs one of them; the class creates no threads, and the call returns after all chunks finished.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * ExecutorService pool = Executors.newFixedThreadPool(3);
 * FrustumKernel kernel = new ParallelFrustumKernel(pool, 4);                 // four chunks: the caller runs one itself
 * BoundsArray bounds = new BoundsArray(100000);
 * VisibilitySet visible = new VisibilitySet(100000);
 * Frustumf frustum = Frustumf.fromViewProjection(Mat4f.IDENTITY, DepthRange.of(ClipSpace.OPENGL));
 * kernel.cull(frustum, bounds, visible);
 * pool.shutdown();
 * }</pre>
 */
public final class ParallelFrustumKernel implements FrustumKernel {

    /**
     * Smallest chunk worth handing to another thread.
     */
    public static final int MIN_CHUNK = 8192;

    private final Executor executor;
    private final Part[] parts;
    private final Phaser phaser = new Phaser(1);
    private volatile Throwable failure;

    /**
     * Creates a kernel that cuts every range into {@code parts} chunks, runs them on the executor
     * and evaluates each with a kernel made by the factory.
     *
     * @param executor runs the chunks; must be able to run {@code parts - 1} tasks concurrently for
     *     full speed
     * @param parts    how many chunks to cut a range into (typically the number of cores)
     * @param factory makes one kernel per chunk, for example {@code FrustumKernels::best}
     * @throws IllegalArgumentException if {@code parts} is below 1
     * @throws NullPointerException if {@code executor} is {@code null}
     */
    public ParallelFrustumKernel(Executor executor, int parts, Supplier<? extends FrustumKernel> factory) {
        if (parts < 1) {
            throw new IllegalArgumentException("parts must be >= 1: " + parts);
        }
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
        this.parts = new Part[parts];
        for (int i = 0; i < parts; i++) {
            this.parts[i] = new Part(factory.get());
        }
    }

    /**
     * Creates a kernel of {@code parts} chunks, each evaluated by the best available kernel.
     *
     * @param executor the executor; must not be {@code null}
     * @param parts the parts
     */
    public ParallelFrustumKernel(Executor executor, int parts) {
        this(executor, parts, FrustumKernels::best);
    }

    @Override
    public void cull(Frustumf frustum, BoundsArray bounds, int from, int to, VisibilitySet visible) {
        if ((from & 63) != 0) {
            throw new IllegalArgumentException("from must be a multiple of 64: " + from);
        }
        int n = to - from;
        if (n <= 0) {
            return;
        }
        int wanted = Math.min(parts.length, n / MIN_CHUNK);
        if (wanted < 2) {
            parts[0].kernel.cull(frustum, bounds, from, to, visible);
            return;
        }
        int chunk = ((n + wanted - 1) / wanted + 63) & ~63;
        int used = (n + chunk - 1) / chunk;
        failure = null;
        // hand chunks 1.. to the executor and keep chunk 0 for this thread
        for (int i = 1; i < used; i++) {
            Part p = parts[i];
            p.set(frustum, bounds, from + i * chunk, Math.min(to, from + (i + 1) * chunk), visible);
            phaser.register();
            try {
                executor.execute(p);
            } catch (RuntimeException | Error e) {
                phaser.arriveAndDeregister();
                finish();
                throw e;
            }
        }
        try {
            parts[0].kernel.cull(frustum, bounds, from, Math.min(to, from + chunk), visible);
        } catch (RuntimeException | Error e) {
            finish();
            throw e;
        }
        finish();
        Throwable t = failure;
        if (t != null) {
            failure = null;
            if (t instanceof RuntimeException re) {
                throw re;
            }
            if (t instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(t);
        }
    }

    /**
     * Waits until every chunk handed out has finished.
     */
    private void finish() {
        phaser.arriveAndAwaitAdvance();
    }

    @Override
    public String name() {
        return "parallel(" + parts[0].kernel.name() + " x" + parts.length + ")";
    }

    private final class Part implements Runnable {
        final FrustumKernel kernel;
        private Frustumf frustum;
        private BoundsArray bounds;
        private int from;
        private int to;
        private VisibilitySet visible;

        Part(FrustumKernel kernel) {
            this.kernel = kernel;
        }

        void set(Frustumf frustum, BoundsArray bounds, int from, int to, VisibilitySet visible) {
            this.frustum = frustum;
            this.bounds = bounds;
            this.from = from;
            this.to = to;
            this.visible = visible;
        }

        @Override
        public void run() {
            try {
                kernel.cull(frustum, bounds, from, to, visible);
            } catch (Throwable t) {
                failure = t;
            } finally {
                phaser.arriveAndDeregister(); // registered once per hand-out, so leave again to keep the next phase clean
            }
        }
    }
}
