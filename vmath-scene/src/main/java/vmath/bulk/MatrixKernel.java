package vmath.bulk;

import vmath.annotations.Experimental;

/**
 * A batch 4x4 matrix product on the {@code float[]} layout of {@link Mat4fArray} (16 floats per matrix, column-major). The scalar implementation is always there
 * ({@link MatrixKernels#scalar()}); an optional module can contribute a faster one through {@link MatrixKernelProvider}, and {@link MatrixKernels#best()} picks it.
 * Implementations must allow {@code out} to be the same array as {@code a} or {@code b} (element for element), must not allocate, and are single-threaded.
 */
@Experimental("the SPI may change")
public interface MatrixKernel {

    /** Identifier, as used by {@code -Dvmath.matrixKernel=<name>}. */
    String name();

    /** {@code out[oo + 16 i ..] = a[ao + 16 i ..] * b[bo + 16 i ..]} for {@code i} in {@code [0, count)}. */
    void multiply(float[] a, int ao, float[] b, int bo, float[] out, int oo, int count);
}
