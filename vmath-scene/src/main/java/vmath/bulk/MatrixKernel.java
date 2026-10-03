package vmath.bulk;

import vmath.annotations.Experimental;

/**
 * A batch 4x4 matrix product on the {@code float[]} layout of {@link Mat4fArray} (16 floats per
 * matrix, column-major).
 *
 * <p>The scalar implementation is always there ({@link MatrixKernels#scalar()}); an optional module
 * can contribute a faster one through {@link MatrixKernelProvider}, and
 * {@link MatrixKernels#best()} picks it. Implementations must allow {@code out} to be the same
 * array as {@code a} or {@code b} (element for element), must not allocate, and are
 * single-threaded.
 *
 * <p><b>Thread safety.</b> Implementations are single-threaded and, in the library, hold no state,
 * so one instance can be shared between threads as long as the calls write to different outputs.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * MatrixKernel kernel = MatrixKernels.best();                                // SIMD when vmath-simd is available
 * float[] a = new float[16 * 2];
 * float[] b = new float[16 * 2];
 * float[] out = new float[16 * 2];
 * kernel.multiply(a, 0, b, 0, out, 0, 2);                                    // two products of column-major matrices
 * }</pre>
 */
@Experimental("the SPI may change")
public interface MatrixKernel {

    /**
     * Exposes the name by which this kernel is selected through the system property.
     *
     * @return identifier, as used by {@code -Dvmath.matrixKernel=<name>}
     */
    String name();

    /**
     * Multiplies the matrices: {@code out[oo + 16 i ..] = a[ao + 16 i ..] * b[bo + 16 i ..]} for
     * {@code i} in {@code [0, count)}.
     *
     * @param a the left matrices, 16 floats each, column-major
     * @param ao the index of the first float of the first left matrix
     * @param b the right matrices, 16 floats each, column-major
     * @param bo the index of the first float of the first right matrix
     * @param out receives the result
     * @param oo the index of the first float of the first result
     * @param count the number of elements
     */
    void multiply(float[] a, int ao, float[] b, int bo, float[] out, int oo, int count);
}
