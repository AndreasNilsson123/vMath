package vmath.occlusion;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicReference;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.spatial.CullContext;
import vmath.spatial.CullStage;

/**
 * A {@link CullStage} that removes objects hidden behind the occluders in a {@link DepthBuffer}.
 *
 * <p>Fill the buffer once per frame (begin, add occluders) before the pipeline runs; the stage only
 * reads it. Put it after the cheap stages (distance, frustum): it works on the objects that survive
 * them, and each test costs a few texel reads. The decision per object is the buffer's
 * {@link DepthBuffer#isHidden}, made for the whole set by
 * {@link DepthBuffer#cull(BoundsArray, VisibilitySet)}: conservative, so an object that could be seen is
 * kept.
 *
 * <p><b>Cost.</b> About 70 ns per tested box on one thread (100 000 boxes of a city scene, 26 105 of them in the
 * frustum, 99.6% removed, 256 x 128 or 512 x 256 pixels, one machine, JDK 25), against 103 ns before the batch
 * entry point (PERF-1); 36 ns with two threads and 21 ns with four. A box nearer than every occluder is decided without projection; a box that is
 * small against its distance is decided from its centre and extent (a conservative screen rectangle
 * from a few multiplications) and goes to the eight-corner test only when that cannot decide it
 * (0.4% of the boxes in that scene). The culling is the same as before: the same boxes are removed (checked against the old eight-corner test on 60 000 boxes). With an
 * executor (the second constructor) the set is cut into ranges of whole words, as the demo did by hand.
 *
 * <p>The buffer's camera must be the one the rest of the pipeline uses; the {@link CullContext} is
 * not consulted.
 *
 * <p><b>Thread safety.</b> Not thread-safe: one call at a time per instance (an instance with an
 * executor keeps the hand-out state of the call). Use one instance per thread.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * DepthBuffer depth = new DepthBuffer(256, 128);
 * OcclusionStage stage = new OcclusionStage(depth);                     // build the depth buffer before the pipeline runs
 * CullPipeline pipeline = CullPipeline.of(new CullStage[] {new CullStages.Frustum(), stage});
 * OcclusionStage parallel = new OcclusionStage(depth, ForkJoinPool.commonPool(), 4);   // the same on four threads
 * }</pre>
 */
public final class OcclusionStage implements CullStage {

    /**
     * Smallest range worth handing to another thread, in objects.
     */
    public static final int MIN_CHUNK = 4096;

    private final DepthBuffer buffer;
    private final Executor executor;
    private final int parts;
    private final Phaser phaser = new Phaser(1);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Part[] workers;

    /**
     * Creates a stage that tests against {@code buffer}, which the caller keeps filled with the
     * occluders of the current frame, on the calling thread.
     *
     * @param buffer the buffer; must not be {@code null}
     */
    public OcclusionStage(DepthBuffer buffer) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.executor = null;
        this.parts = 1;
        this.workers = new Part[0];
    }

    /**
     * Creates a stage that cuts the set into {@code parts} ranges of whole words and tests them on the
     * executor; the calling thread tests one range itself. Sets smaller than two ranges of
     * {@value #MIN_CHUNK} objects are tested on the calling thread. The result is the same as with one thread.
     *
     * <p>The executor must eventually run every task it accepts; {@link #cull} returns only when all
     * ranges are done (see {@code ParallelFrustumKernel} for the contract in full). An executor that rejects a
     * task makes the call wait for the ranges already handed out and rethrow.
     *
     * @param buffer the buffer; must not be {@code null}
     * @param executor runs the ranges; must not be {@code null}
     * @param parts how many ranges to cut a set into (typically the number of cores)
     * @throws IllegalArgumentException if {@code parts} is below 1
     */
    public OcclusionStage(DepthBuffer buffer, Executor executor, int parts) {
        if (parts < 1) {
            throw new IllegalArgumentException("parts must be >= 1: " + parts);
        }
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.parts = parts;
        this.workers = new Part[parts];
        for (int i = 0; i < parts; i++) {
            workers[i] = new Part();
        }
    }

    @Override
    public void cull(CullContext ctx, BoundsArray bounds, VisibilitySet visible) {
        int n = bounds.size();
        int wanted = executor == null ? 1 : Math.min(parts, n / MIN_CHUNK);
        if (wanted < 2) {
            buffer.cull(bounds, visible);
            return;
        }
        buffer.finish(); // once, here: the ranges then only read
        int chunk = ((n + wanted - 1) / wanted + 63) & ~63;
        int used = (n + chunk - 1) / chunk;
        failure.set(null);
        for (int i = 1; i < used; i++) {
            Part p = workers[i];
            p.set(bounds, i * chunk, Math.min(n, (i + 1) * chunk), visible);
            phaser.register();
            try {
                executor.execute(p);
            } catch (RuntimeException | Error e) {
                phaser.arriveAndDeregister();
                phaser.arriveAndAwaitAdvance();
                throw e;
            }
        }
        try {
            buffer.cull(bounds, 0, Math.min(n, chunk), visible);
        } catch (RuntimeException | Error e) {
            phaser.arriveAndAwaitAdvance();
            throw e;
        }
        phaser.arriveAndAwaitAdvance();
        Throwable t = failure.getAndSet(null);
        if (t != null) {
            if (t instanceof RuntimeException re) {
                throw re;
            }
            if (t instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(t);
        }
    }

    private final class Part implements Runnable {
        private BoundsArray bounds;
        private int from;
        private int to;
        private VisibilitySet visible;

        void set(BoundsArray bounds, int from, int to, VisibilitySet visible) {
            this.bounds = bounds;
            this.from = from;
            this.to = to;
            this.visible = visible;
        }

        @Override
        public void run() {
            try {
                buffer.cull(bounds, from, to, visible);
            } catch (Throwable t) {
                if (!failure.compareAndSet(null, t)) {
                    Throwable first = failure.get();
                    if (first != t) {
                        first.addSuppressed(t);
                    }
                }
            } finally {
                bounds = null;
                visible = null;
                phaser.arriveAndDeregister();
            }
        }
    }
}
