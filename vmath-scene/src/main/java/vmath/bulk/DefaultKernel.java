package vmath.bulk;

import vmath.core.Mat4f;

/**
 * The kernel chosen once at startup by {@link MatrixKernels#best()}, shared by the containers, and
 * a per-thread scratch array for passing a {@link Mat4f} to it without allocating.
 *
 * <p>Internal: not part of the API, used by the bulk containers only.
 *
 * <p><b>Thread safety.</b> Safe to use from any number of threads: the kernel is stateless and
 * every thread has its own scratch array.
 */
final class DefaultKernel {

    static final MatrixKernel INSTANCE = MatrixKernels.best();

    private static final ThreadLocal<float[]> SCRATCH = ThreadLocal.withInitial(() -> new float[16]);

    private DefaultKernel() {
    }

    /**
     * Writes a matrix column-major into a per-thread array, so that a batch kernel can read it
     * without an allocation per call.
     *
     * @param m the matrix; must not be {@code null}
     * @return the 16 floats of the matrix, in an array that belongs to the calling thread and is
     *     valid until the next call on this thread; never {@code null}
     */
    static float[] matrix(Mat4f m) {
        float[] s = SCRATCH.get();
        m.writeTo(s, 0);
        return s;
    }
}
