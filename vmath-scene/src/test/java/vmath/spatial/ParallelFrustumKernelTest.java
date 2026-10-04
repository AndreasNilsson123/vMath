package vmath.spatial;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.core.Mat4f;
import vmath.core.Rnd;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;

class ParallelFrustumKernelTest {

    final Rnd rnd = Rnd.create();
    private ExecutorService pool;

    @BeforeEach
    void start() {
        pool = Executors.newFixedThreadPool(3);
    }

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    private Frustumf randomFrustum() {
        Vec3f eye = rnd.nextVec3f();
        Vec3f dir = rnd.nextVec3f();
        Mat4f view = Mat4f.lookAt(eye, eye.add(dir), Math.abs(dir.normalize().y()) > 0.95f ? Vec3f.UNIT_X : Vec3f.UNIT_Y);
        float near = (float) rnd.range(0.1, 1);
        return Frustumf.fromViewProjection(
                Mat4f.perspective((float) rnd.range(0.5, 1.8), (float) rnd.range(0.8, 2.2), near, near * 200f, true).mul(view),
                DepthRange.ZERO_TO_ONE);
    }

    private BoundsArray scene(int n) {
        BoundsArray b = new BoundsArray(Math.max(n, 1));
        for (int i = 0; i < n; i++) {
            Vec3f c = rnd.nextVec3f().mul(20f);
            Vec3f h = new Vec3f((float) rnd.range(0.01, 2), (float) rnd.range(0.01, 2), (float) rnd.range(0.01, 2));
            b.add(Aabbf.fromCenterHalfExtent(c, h));
        }
        return b;
    }

    @Test
    void resultIsBitIdenticalToTheSerialKernelForAnyRangeAndPartCount() {
        for (int trial = 0; trial < 12; trial++) {
            int n = (int) rnd.range(0, 90_000);
            BoundsArray b = scene(n);
            Frustumf f = randomFrustum();
            int parts = 1 + (int) rnd.range(0, 6);
            int from = 64 * (int) rnd.range(0, Math.max(1, n / 64 / 3));
            int to = from + (int) rnd.range(0, Math.max(1, n - from) + 1);
            to = Math.min(to, n);

            VisibilitySet expected = new VisibilitySet(n + 200);
            expected.setAll(n + 100); // bits past n must stay set: nobody may touch them
            new FrustumCuller().cull(f, b, from, to, expected);

            VisibilitySet actual = new VisibilitySet(n + 200);
            actual.setAll(n + 100);
            new ParallelFrustumKernel(pool, parts).cull(f, b, from, to, actual);

            assertArrayEquals(expected.words(), actual.words(),
                    "n=" + n + " range [" + from + "," + to + ") parts=" + parts);
        }
    }

    @Test
    void largeRangesReallyAreSplitAndTheKernelIsReusable() {
        int n = 6 * ParallelFrustumKernel.MIN_CHUNK + 100;
        BoundsArray b = scene(n);
        ParallelFrustumKernel k = new ParallelFrustumKernel(pool, 4);
        assertTrue(k.name().startsWith("parallel("), k.name());
        for (int frame = 0; frame < 20; frame++) {
            Frustumf f = randomFrustum();
            VisibilitySet expected = new VisibilitySet(n);
            expected.setAll(n);
            new FrustumCuller().cull(f, b, expected);
            VisibilitySet actual = new VisibilitySet(n);
            actual.setAll(n);
            k.cull(f, b, actual);
            assertArrayEquals(expected.words(), actual.words(), "frame " + frame);
        }
    }

    @Test
    void workRunsOnPoolThreadsWhenTheRangeIsLarge() {
        int n = 4 * ParallelFrustumKernel.MIN_CHUNK;
        BoundsArray b = scene(n);
        java.util.Set<Thread> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.function.Supplier<FrustumKernel> spy = () -> new FrustumKernel() {
            final FrustumKernel inner = new FrustumCuller();

            @Override
            public void cull(Frustumf frustum, BoundsArray bounds, int from, int to, VisibilitySet visible) {
                seen.add(Thread.currentThread());
                inner.cull(frustum, bounds, from, to, visible);
            }

            @Override
            public String name() {
                return "spy";
            }
        };
        VisibilitySet vis = new VisibilitySet(n);
        vis.setAll(n);
        new ParallelFrustumKernel(pool, 4, spy).cull(randomFrustum(), b, vis);
        assertTrue(seen.size() >= 2, "expected several threads, saw " + seen.size());
    }

    @Test
    void aSameThreadExecutorStillGivesTheRightAnswer() {
        int n = 3 * ParallelFrustumKernel.MIN_CHUNK;
        BoundsArray b = scene(n);
        Frustumf f = randomFrustum();
        VisibilitySet expected = new VisibilitySet(n);
        expected.setAll(n);
        new FrustumCuller().cull(f, b, expected);
        VisibilitySet actual = new VisibilitySet(n);
        actual.setAll(n);
        new ParallelFrustumKernel(Runnable::run, 3).cull(f, b, actual);
        assertArrayEquals(expected.words(), actual.words());
    }

    @Test
    void severalFailingWorkersAreReportedTogether() {
        int n = 4 * ParallelFrustumKernel.MIN_CHUNK;
        BoundsArray b = scene(n);
        java.util.function.Supplier<FrustumKernel> failing = () -> new FrustumKernel() {
            @Override
            public void cull(Frustumf frustum, BoundsArray bounds, int from, int to, VisibilitySet visible) {
                if (from > 0) {
                    throw new IllegalStateException("boom at " + from);
                }
            }

            @Override
            public String name() {
                return "failing";
            }
        };
        ParallelFrustumKernel k = new ParallelFrustumKernel(pool, 4, failing);
        VisibilitySet vis = new VisibilitySet(n);
        vis.setAll(n);
        Frustumf f = randomFrustum();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> k.cull(f, b, vis));
        assertEquals(2, e.getSuppressed().length, "three workers failed: one is thrown, the other two are suppressed");
    }

    @Test
    void failuresInAWorkerReachTheCallerAndTheKernelRecovers() {
        int n = 4 * ParallelFrustumKernel.MIN_CHUNK;
        BoundsArray b = scene(n);
        int[] calls = {0};
        java.util.function.Supplier<FrustumKernel> flaky = () -> new FrustumKernel() {
            final FrustumKernel inner = new FrustumCuller();

            @Override
            public void cull(Frustumf frustum, BoundsArray bounds, int from, int to, VisibilitySet visible) {
                if (from > 0 && calls[0] == 0) {
                    throw new IllegalStateException("boom");
                }
                inner.cull(frustum, bounds, from, to, visible);
            }

            @Override
            public String name() {
                return "flaky";
            }
        };
        ParallelFrustumKernel k = new ParallelFrustumKernel(pool, 4, flaky);
        VisibilitySet vis = new VisibilitySet(n);
        vis.setAll(n);
        Frustumf f = randomFrustum();
        assertThrows(IllegalStateException.class, () -> k.cull(f, b, vis));
        calls[0] = 1;
        VisibilitySet expected = new VisibilitySet(n);
        expected.setAll(n);
        new FrustumCuller().cull(f, b, expected);
        VisibilitySet actual = new VisibilitySet(n);
        actual.setAll(n);
        k.cull(f, b, actual);
        assertArrayEquals(expected.words(), actual.words());
    }

    @Test
    void anExecutorThatRejectsATaskFailsTheCallAfterTheHandedOutChunksFinishAndTheKernelRecovers() {
        int n = 4 * ParallelFrustumKernel.MIN_CHUNK;
        BoundsArray b = scene(n);
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean reject = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.Executor flaky = task -> {
            if (reject.get() && accepted.incrementAndGet() == 2) {
                throw new java.util.concurrent.RejectedExecutionException("full");
            }
            pool.execute(task);
        };
        ParallelFrustumKernel k = new ParallelFrustumKernel(flaky, 4);
        Frustumf f = randomFrustum();
        VisibilitySet vis = new VisibilitySet(n);
        vis.setAll(n);
        assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> k.cull(f, b, vis));
        reject.set(false);
        VisibilitySet expected = new VisibilitySet(n);
        expected.setAll(n);
        new FrustumCuller().cull(f, b, expected);
        VisibilitySet actual = new VisibilitySet(n);
        actual.setAll(n);
        k.cull(f, b, actual);
        assertArrayEquals(expected.words(), actual.words(), "the kernel works again after a rejected hand-out");
    }

    @Test
    void anExecutorThatDefersWorkMakesTheCallWaitUntilTheWorkRunsAndNeverReturnsEarly() throws Exception {
        int n = 3 * ParallelFrustumKernel.MIN_CHUNK;
        BoundsArray b = scene(n);
        java.util.concurrent.ConcurrentLinkedQueue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        ParallelFrustumKernel k = new ParallelFrustumKernel(held::add, 3); // accepts the tasks and keeps them: the documented way to block the caller
        Frustumf f = randomFrustum();
        VisibilitySet actual = new VisibilitySet(n);
        actual.setAll(n);
        java.util.concurrent.CompletableFuture<Void> call = java.util.concurrent.CompletableFuture.runAsync(() -> k.cull(f, b, actual));
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (held.size() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(2, held.size(), "chunks 1 and 2 were handed out");
        Thread.sleep(300);
        assertFalse(call.isDone(), "the call must not return while handed-out chunks have not run");
        Runnable r;
        while ((r = held.poll()) != null) {
            r.run();
        }
        call.get(10, java.util.concurrent.TimeUnit.SECONDS);
        VisibilitySet expected = new VisibilitySet(n);
        expected.setAll(n);
        new FrustumCuller().cull(f, b, expected);
        assertArrayEquals(expected.words(), actual.words());
    }

    @Test
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> new ParallelFrustumKernel(pool, 0));
        ParallelFrustumKernel k = new ParallelFrustumKernel(pool, 2);
        BoundsArray b = scene(200);
        VisibilitySet vis = new VisibilitySet(200);
        assertThrows(IllegalArgumentException.class, () -> k.cull(randomFrustum(), b, 10, 100, vis));
        k.cull(randomFrustum(), b, 64, 64, vis); // empty range: nothing to do
        assertEquals(0, vis.count());
    }
}
