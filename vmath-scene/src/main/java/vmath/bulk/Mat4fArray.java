package vmath.bulk;

import vmath.core.Mat4f;

/**
 * Many 4x4 matrices in one {@code float[]}, 16 floats each in column-major (GPU) order, so a whole
 * array can be uploaded as it is.
 *
 * <p>One matrix is a single 64-byte contiguous read, which suits per-object kernels; there is no
 * {@code Mat4f} object per element.
 *
 * <p><b>Thread safety.</b> Not thread-safe: it is mutable, so use one instance per thread or
 * synchronise externally. Concurrent reads are safe only while no thread is writing.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Mat4fArray world = new Mat4fArray(2);
 * world.add(Mat4f.translation(1f, 0f, 0f));
 * world.add(Mat4f.translation(0f, 2f, 0f));
 * Mat4fArray viewed = new Mat4fArray(2);
 * viewed.add(Mat4f.IDENTITY);
 * viewed.add(Mat4f.IDENTITY);
 * world.premultiply(Mat4f.lookAt(new Vec3f(0f, 0f, 5f), Vec3f.ZERO, Vec3f.UNIT_Y), viewed);   // viewed[i] = view * world[i]
 * }</pre>
 */
public final class Mat4fArray extends FloatElements {

    /**
     * Floats per matrix.
     */
    public static final int STRIDE = 16;

    /**
     * Creates an empty array with room for {@code capacity} matrices (at least 1).
     *
     * @param capacity the capacity in elements
     */
    public Mat4fArray(int capacity) {
        super(capacity, STRIDE);
    }

    /**
     * Appends a matrix and returns its index.
     *
     * @param m the matrix; must not be {@code null}
     * @return its index
     */
    public int add(Mat4f m) {
        ensureCapacity(size + 1);
        m.writeTo(data, size * STRIDE);
        return size++;
    }

    /**
     * Replaces matrix {@code i}; {@link IndexOutOfBoundsException} for an index that is not below
     * {@link #size()}.
     *
     * @param i the index
     * @param m the matrix; must not be {@code null}
     */
    public void set(int i, Mat4f m) {
        checkIndex(i);
        m.writeTo(data, i * STRIDE);
    }

    /**
     * Reads a matrix as an object; allocates, so use the array form in loops.
     *
     * @param i the index
     * @return matrix {@code i} as a value (allocates); {@link IndexOutOfBoundsException} for an
     *     index that is not below {@link #size()}
     */
    public Mat4f get(int i) {
        checkIndex(i);
        return Mat4f.fromArray(data, i * STRIDE);
    }

    // ---------------------------------------------------------------- compaction

    // ---------------------------------------------------------------- batch kernels

    /**
     * Multiplies the matrices: {@code out[i] = a[i] * b[i]} for every element (the product as
     * {@link Mat4f#mul} computes it, column-major, so {@code b} is applied first).
     *
     * <p>The arrays must have the same size; {@code out} is resized and may be {@code a} or
     * {@code b}. Nothing is allocated.
     *
     * @param a the first mat4f array; must not be {@code null}
     * @param b the second mat4f array; must not be {@code null}
     * @param out receives the result; must not be {@code null}
     */
    public static void multiply(Mat4fArray a, Mat4fArray b, Mat4fArray out) {
        multiply(a, b, out, DefaultKernel.INSTANCE);
    }

    /**
     * As {@link #multiply(Mat4fArray, Mat4fArray, Mat4fArray)} with an explicit kernel (for example
     * {@link MatrixKernels#scalar()}).
     *
     * @param a the first mat4f array; must not be {@code null}
     * @param b the second mat4f array; must not be {@code null}
     * @param out receives the result; must not be {@code null}
     * @param kernel the kernel; must not be {@code null}
     * @throws IllegalArgumentException if the arrays differ in size
     */
    public static void multiply(Mat4fArray a, Mat4fArray b, Mat4fArray out, MatrixKernel kernel) {
        if (a.size != b.size) {
            throw new IllegalArgumentException("sizes differ: " + a.size + " and " + b.size);
        }
        out.ensureCapacity(a.size);
        kernel.multiply(a.data, 0, b.data, 0, out.data, 0, a.size);
        out.size = a.size;
    }

    /**
     * The kernel chosen once at startup by {@link MatrixKernels#best()}.
     */
    private static final class DefaultKernel {
        static final MatrixKernel INSTANCE = MatrixKernels.best();
    }

    /**
     * Applies a common matrix (a parent transform, a view matrix) to every element:
     * {@code out[i] = m * this[i]}.
     *
     * <p>{@code out} may be this array.
     *
     * @param m the matrix; must not be {@code null}
     * @param out receives the result; must not be {@code null}
     */
    public void premultiply(Mat4f m, Mat4fArray out) {
        out.ensureCapacity(size);
        float[] src = data, dst = out.data;
        float m00 = m.m00(), m01 = m.m01(), m02 = m.m02(), m03 = m.m03();
        float m10 = m.m10(), m11 = m.m11(), m12 = m.m12(), m13 = m.m13();
        float m20 = m.m20(), m21 = m.m21(), m22 = m.m22(), m23 = m.m23();
        float m30 = m.m30(), m31 = m.m31(), m32 = m.m32(), m33 = m.m33();
        for (int i = 0, o = 0; i < size; i++, o += STRIDE) {
            for (int c = 0; c < 16; c += 4) {
                float x = src[o + c], y = src[o + c + 1], z = src[o + c + 2], w = src[o + c + 3];
                dst[o + c] = m00 * x + m10 * y + m20 * z + m30 * w;
                dst[o + c + 1] = m01 * x + m11 * y + m21 * z + m31 * w;
                dst[o + c + 2] = m02 * x + m12 * y + m22 * z + m32 * w;
                dst[o + c + 3] = m03 * x + m13 * y + m23 * z + m33 * w;
            }
        }
        out.size = size;
    }

    /**
     * The 4x4 product of the matrices at {@code a[ao..]} and {@code b[bo..]} written to
     * {@code out[oo..]}; the arrays may be the same.
     */
    static void multiply(float[] a, int ao, float[] b, int bo, float[] out, int oo) {
        float a00 = a[ao], a01 = a[ao + 1], a02 = a[ao + 2], a03 = a[ao + 3];
        float a10 = a[ao + 4], a11 = a[ao + 5], a12 = a[ao + 6], a13 = a[ao + 7];
        float a20 = a[ao + 8], a21 = a[ao + 9], a22 = a[ao + 10], a23 = a[ao + 11];
        float a30 = a[ao + 12], a31 = a[ao + 13], a32 = a[ao + 14], a33 = a[ao + 15];
        for (int c = 0; c < 16; c += 4) {
            float x = b[bo + c], y = b[bo + c + 1], z = b[bo + c + 2], w = b[bo + c + 3];
            out[oo + c] = a00 * x + a10 * y + a20 * z + a30 * w;
            out[oo + c + 1] = a01 * x + a11 * y + a21 * z + a31 * w;
            out[oo + c + 2] = a02 * x + a12 * y + a22 * z + a32 * w;
            out[oo + c + 3] = a03 * x + a13 * y + a23 * z + a33 * w;
        }
    }
}
