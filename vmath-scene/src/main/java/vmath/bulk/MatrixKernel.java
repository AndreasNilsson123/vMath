package vmath.bulk;

import vmath.annotations.Experimental;

/**
 * Batch kernels on the {@code float[]} layouts of the bulk containers: the 4x4 matrix product of
 * {@link Mat4fArray} (16 floats per matrix, column-major), the transform of the vectors of
 * {@link Vec3fArray} and {@link Vec4fArray} by one matrix, and the normalisation of the quaternions
 * of {@link QuatArray}.
 *
 * <p>The scalar implementation is always there ({@link MatrixKernels#scalar()}); an optional module
 * can contribute a faster one through {@link MatrixKernelProvider}, and
 * {@link MatrixKernels#best()} picks it. Implementations must allow the output to be the same
 * array as an input (element for element, at the same offset), must not allocate, and are
 * single-threaded. The methods other than {@link #multiply} have a default that uses the scalar
 * kernel, so that a kernel can speed up only the ones where it wins; a faster kernel may differ
 * from the scalar one in the last bit.
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

    /**
     * Transforms {@code count} four-component vectors by a matrix: {@code dst[i] = m * src[i]}.
     *
     * @param m the matrix, 16 floats, column-major
     * @param mo the index of the first float of the matrix
     * @param src the vectors, four floats each
     * @param so the index of the first float of the first vector
     * @param dst receives the result; may be {@code src} when {@code d == so}
     * @param d the index of the first float of the first result
     * @param count the number of vectors
     */
    default void transformVec4(float[] m, int mo, float[] src, int so, float[] dst, int d, int count) {
        ScalarMatrixKernel.INSTANCE.transformVec4(m, mo, src, so, dst, d, count);
    }

    /**
     * Transforms {@code count} positions of three floats by an affine matrix (its bottom row is
     * taken as {@code 0 0 0 1}): rotation, scale and translation.
     *
     * @param m the matrix, 16 floats, column-major
     * @param mo the index of the first float of the matrix
     * @param src the positions, three floats each
     * @param so the index of the first float of the first position
     * @param dst receives the result; may be {@code src} when {@code d == so}
     * @param d the index of the first float of the first result
     * @param count the number of positions
     */
    default void transformPositions(float[] m, int mo, float[] src, int so, float[] dst, int d, int count) {
        ScalarMatrixKernel.INSTANCE.transformPositions(m, mo, src, so, dst, d, count);
    }

    /**
     * Transforms {@code count} directions of three floats by the upper-left 3x3 part of a matrix
     * (no translation).
     *
     * @param m the matrix, 16 floats, column-major
     * @param mo the index of the first float of the matrix
     * @param src the directions, three floats each
     * @param so the index of the first float of the first direction
     * @param dst receives the result; may be {@code src} when {@code d == so}
     * @param d the index of the first float of the first result
     * @param count the number of directions
     */
    default void transformDirections(float[] m, int mo, float[] src, int so, float[] dst, int d, int count) {
        ScalarMatrixKernel.INSTANCE.transformDirections(m, mo, src, so, dst, d, count);
    }

    /**
     * Scales {@code count} quaternions of four floats to unit length in place; a zero or non-finite
     * quaternion becomes the identity.
     *
     * @param q the quaternions, {@code x, y, z, w} each
     * @param off the index of the first float of the first quaternion
     * @param count the number of quaternions
     */
    default void normalizeQuaternions(float[] q, int off, int count) {
        ScalarMatrixKernel.INSTANCE.normalizeQuaternions(q, off, count);
    }
}
