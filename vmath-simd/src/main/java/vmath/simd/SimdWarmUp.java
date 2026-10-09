package vmath.simd;

import java.lang.System.Logger;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import vmath.bulk.BoundsArray;
import vmath.bulk.MatrixKernel;
import vmath.bulk.MatrixKernels;
import vmath.bulk.VisibilitySet;
import vmath.core.ClipSpace;
import vmath.core.Mat4f;
import vmath.geo.Aabbf;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;
import vmath.spatial.FrustumCuller;

/**
 * Brings the Vector API kernels to the speed they have once the JIT compiler has compiled them, and
 * tells whether they are there yet.
 *
 * <p><b>Why.</b> The Vector API is fast only in code that C2 has compiled. Before that, in the
 * interpreter and in C1, every vector operation allocates an object and a call costs hundreds of
 * microseconds. Measured on one machine (JDK 25, 256 boxes, the first calls of a fresh JVM): the
 * first 100 calls of the frustum kernel took 680 us each and allocated 185 kB each, the next 900
 * took 100 us and 169 kB, and only after about 5 000 calls the kernel took 0.5 us and allocated
 * nothing (the scalar kernel: 1.6 us once compiled, and it never allocates). A program that culls
 * once per frame spends its first minute in that state, which is what the terrain demo saw
 * (technical debt TD-32). The batch matrix kernel does the same (834 us and 444 kB per call for
 * 256 products in the first 100 calls, 1.5 us after about 1 000).
 *
 * <p><b>What the library does.</b> The frustum kernel that {@code FrustumKernels.best()} returns
 * from this module ({@code "simd"}) culls with the scalar kernel, which gives bit-identical results,
 * until a background thread has run the vector kernel on a synthetic input and seen it beat the
 * scalar kernel; from then on it uses the vector kernel. If the vector kernel is never faster (an
 * interpreter-only JVM, a machine with narrow vectors) it never switches, which is the right
 * decision there. The matrix kernel is <em>not</em> switched on the fly: its results differ from the
 * scalar ones in the last bit, so a switch in the middle of a run would change the numbers of a
 * running program (and {@code -Dvmath.deterministic=true} exists for the opposite wish). Call
 * {@link #warmUp(long)} while loading to have the matrix kernel compiled before the first frame.
 *
 * <p><b>The property</b> {@code -Dvmath.simd.warmup=} chooses how the frustum kernel is warmed:
 * {@code async} (the default: a daemon thread named {@code vmath-simd-warmup}), {@code sync} (the
 * thread that creates the first kernel runs the warm-up before it returns, for at most about a
 * second) or {@code off} (no warm-up: the vector kernel is used from the first call, as before).
 *
 * <p><b>Thread safety.</b> Safe to call from any number of threads; the state is shared by the whole
 * JVM.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * // in the loading screen, so that the first frame does not pay for the JIT
 * boolean ready = SimdWarmUp.warmUp(2000);                                // true if both kernels beat the scalar ones
 * }</pre>
 */
public final class SimdWarmUp {

    /** The system property that chooses {@code async}, {@code sync} or {@code off}. */
    static final String PROPERTY = "vmath.simd.warmup";

    private static final Logger LOG = System.getLogger("vmath.kernel");
    private static final int CALLS = 100;
    private static final int ROUNDS_IN_A_ROW = 3;
    private static final long DEFAULT_MILLIS = 5_000;
    private static final long SYNC_MILLIS = 1_000;
    private static final int BOXES = 1024, MATRICES = 256;

    private static final AtomicBoolean FRUSTUM_STARTED = new AtomicBoolean();
    private static volatile boolean frustumReady;
    private static volatile boolean matrixReady;

    private SimdWarmUp() {
    }

    /**
     * Runs the vector kernels on a synthetic input in the calling thread until each of them has been
     * seen to beat the scalar kernel, or the time is out.
     *
     * <p>Takes up to a few hundred milliseconds on a machine where it works (the JIT compiles the
     * kernels during it); the result of every kernel is unchanged. Calling it again once the kernels
     * are ready returns at once.
     *
     * @param maxMillis the longest time to spend, in milliseconds; 0 or less makes it return what is
     *     known without running anything
     * @return {@code true} if both the frustum kernel and the matrix kernel are ready to be used
     */
    public static boolean warmUp(long maxMillis) {
        long deadline = System.nanoTime() + Math.max(0, maxMillis) * 1_000_000L;
        if (maxMillis > 0) {
            if (!frustumReady) {
                frustumReady = raceFrustum(deadline);
            }
            if (!matrixReady) {
                matrixReady = raceMatrix(deadline);
            }
        }
        return frustumReady && matrixReady;
    }

    /**
     * Tells whether the vector frustum kernel has been seen to beat the scalar one (or the warm-up is
     * switched off), which is when the {@code "simd"} frustum kernel starts to use it.
     *
     * @return {@code true} if the {@code "simd"} frustum kernel runs the vector code
     */
    public static boolean isFrustumKernelReady() {
        return frustumReady;
    }

    /**
     * Tells whether the vector matrix kernel has been seen to beat the scalar one in
     * {@link #warmUp(long)}.
     *
     * @return {@code true} if {@link #warmUp(long)} has found the matrix kernel faster than the scalar one
     */
    public static boolean isMatrixKernelReady() {
        return matrixReady;
    }

    /** Starts the warm-up of the frustum kernel once, in the way the property says. */
    static void startFrustumWarmUp() {
        if (frustumReady || !FRUSTUM_STARTED.compareAndSet(false, true)) {
            return;
        }
        String mode = System.getProperty(PROPERTY, "async").trim().toLowerCase(java.util.Locale.ROOT);
        switch (mode) {
            case "off" -> frustumReady = true;
            case "sync" -> frustumReady = raceFrustum(System.nanoTime() + SYNC_MILLIS * 1_000_000L);
            default -> {
                Thread t = new Thread(() -> frustumReady = raceFrustum(System.nanoTime() + DEFAULT_MILLIS * 1_000_000L), "vmath-simd-warmup");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                t.start();
            }
        }
    }

    /** Forgets what was learned, so that a test can start from the cold state. */
    static void resetForTest() {
        frustumReady = false;
        matrixReady = false;
        FRUSTUM_STARTED.set(false);
    }

    private static boolean raceFrustum(long deadline) {
        SplittableRandom r = new SplittableRandom(1);
        BoundsArray bounds = new BoundsArray(BOXES);
        for (int i = 0; i < BOXES; i++) {
            float x = (float) (r.nextDouble() * 4 - 2), y = (float) (r.nextDouble() * 4 - 2), z = (float) (r.nextDouble() * 4 - 2);
            bounds.add(new Aabbf(x, y, z, x + 0.1f, y + 0.1f, z + 0.1f));
        }
        Frustumf frustum = Frustumf.fromViewProjection(Mat4f.IDENTITY, DepthRange.of(ClipSpace.OPENGL));
        VisibilitySet visible = new VisibilitySet(BOXES);
        SimdFrustumCuller vector = new SimdFrustumCuller();
        FrustumCuller scalar = new FrustumCuller();
        boolean ready = race(() -> {
            visible.setAll(1);
            vector.cull(frustum, bounds, 0, BOXES, visible);
        }, () -> {
            visible.setAll(1);
            scalar.cull(frustum, bounds, 0, BOXES, visible);
        }, deadline);
        if (!ready) {
            LOG.log(Logger.Level.INFO, "the vector frustum kernel was not seen to beat the scalar one; the scalar kernel is used (-Dvmath.simd.warmup=off uses the vector kernel anyway)");
        }
        return ready;
    }

    private static boolean raceMatrix(long deadline) {
        SplittableRandom r = new SplittableRandom(2);
        float[] a = new float[16 * MATRICES], b = new float[16 * MATRICES], out = new float[16 * MATRICES];
        for (int i = 0; i < a.length; i++) {
            a[i] = (float) r.nextDouble();
            b[i] = (float) r.nextDouble();
        }
        MatrixKernel vector = new SimdMatrixKernelProvider().create();
        MatrixKernel scalar = MatrixKernels.scalar();
        return race(() -> vector.multiply(a, 0, b, 0, out, 0, MATRICES), () -> scalar.multiply(a, 0, b, 0, out, 0, MATRICES), deadline);
    }

    /**
     * Alternates blocks of calls of the two and reports whether the vector one was faster by at least
     * 30% in several blocks in a row, which is when it has been compiled and is worth using.
     */
    private static boolean race(Runnable vector, Runnable scalar, long deadline) {
        int streak = 0;
        while (System.nanoTime() < deadline) {
            long v = time(vector), s = time(scalar);
            if (v * 10 < s * 7) {
                if (++streak >= ROUNDS_IN_A_ROW) {
                    return true;
                }
            } else {
                streak = 0;
            }
        }
        return false;
    }

    private static long time(Runnable call) {
        long t0 = System.nanoTime();
        for (int i = 0; i < CALLS; i++) {
            call.run();
        }
        return System.nanoTime() - t0;
    }
}
