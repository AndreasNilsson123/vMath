package vmath.bulk;

import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import vmath.core.Mat4f;

/**
 * Many 4x4 matrices in one {@code float[]}, 16 floats each in column-major (GPU) order, so a whole array can be
 * uploaded as it is. One matrix is a single 64-byte contiguous read, which suits per-object kernels; there is no
 * {@code Mat4f} object per element.
 *
 * <p><b>Thread safety.</b> Not thread-safe: it is mutable, so use one instance per thread or synchronise externally. Concurrent reads are safe only
 * while no thread is writing.
 */
public final class Mat4fArray {

    /** Floats per matrix. */
    public static final int STRIDE = 16;

    private float[] data;
    private int size;

    public Mat4fArray(int capacity) {
        this.data = new float[Math.max(capacity, 1) * STRIDE];
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return data.length / STRIDE;
    }

    public void clear() {
        size = 0;
    }

    /** Sets the element count after writing into {@link #data()} directly. */
    public void setSize(int n) {
        if (n < 0 || n > capacity()) {
            throw new IllegalArgumentException("size " + n + " outside 0.." + capacity());
        }
        size = n;
    }

    public void ensureCapacity(int n) {
        if (n * STRIDE > data.length) {
            data = Arrays.copyOf(data, Math.max(n, capacity() * 2) * STRIDE);
        }
    }

    public int add(Mat4f m) {
        ensureCapacity(size + 1);
        m.writeTo(data, size * STRIDE);
        return size++;
    }

    public void set(int i, Mat4f m) {
        checkIndex(i);
        m.writeTo(data, i * STRIDE);
    }

    public Mat4f get(int i) {
        checkIndex(i);
        return Mat4f.fromArray(data, i * STRIDE);
    }

    private void checkIndex(int i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException("index " + i + ", size " + size);
        }
    }

    /** The live backing array (matrix {@code i} starts at {@code i * STRIDE}); replaced when the array grows. */
    public float[] data() {
        return data;
    }

    /**
     * Writes all matrices (16 floats each, column-major) into {@code dst} starting at byte {@code offset}, {@code strideBytes} apart (at least 64), in the
     * given byte order.
     */
    public void writeTo(MemorySegment dst, long offset, long strideBytes, ByteOrder order) {
        Strided.write(data, 0, STRIDE, size, dst, offset, strideBytes, order);
    }

    /** Replaces the contents with {@code count} matrices read from {@code src} ({@link #writeTo(MemorySegment, long, long, ByteOrder)} reversed). */
    public void readFrom(MemorySegment src, long offset, long strideBytes, ByteOrder order, int count) {
        ensureCapacity(count);
        Strided.read(src, offset, strideBytes, order, data, 0, STRIDE, count);
        size = count;
    }

    /** Absolute write of all matrices at {@code index}; does not move the buffer position. */
    public void writeTo(FloatBuffer dst, int index) {
        dst.put(index, data, 0, size * STRIDE);
    }

    // ---------------------------------------------------------------- compaction

    /**
     * Removes element {@code i} by moving the last element into its place: O(1), the order of the others is kept except for that one. Returns the index the
     * moved element had before (the old last index), or -1 if {@code i} was the last element. Mirror it in parallel arrays with the same call.
     */
    public int removeSwap(int i) {
        checkIndex(i);
        int moved = Compaction.swapRemove(data, STRIDE, size, i);
        size--;
        return moved;
    }

    /** Keeps only the elements whose bit is set in {@code keep} (bit {@code i} for element {@code i}), in their original order. Returns the new size. */
    public int compact(VisibilitySet keep) {
        size = Compaction.stable(data, STRIDE, size, keep);
        return size;
    }

    // ---------------------------------------------------------------- batch kernels

    /**
     * {@code out[i] = a[i] * b[i]} for every element (the product as {@link Mat4f#mul} computes it, column-major, so {@code b} is applied first). The arrays must have
     * the same size; {@code out} is resized and may be {@code a} or {@code b}. Nothing is allocated.
     */
    public static void multiply(Mat4fArray a, Mat4fArray b, Mat4fArray out) {
        multiply(a, b, out, DefaultKernel.INSTANCE);
    }

    /** As {@link #multiply(Mat4fArray, Mat4fArray, Mat4fArray)} with an explicit kernel (for example {@link MatrixKernels#scalar()}). */
    public static void multiply(Mat4fArray a, Mat4fArray b, Mat4fArray out, MatrixKernel kernel) {
        if (a.size != b.size) {
            throw new IllegalArgumentException("sizes differ: " + a.size + " and " + b.size);
        }
        out.ensureCapacity(a.size);
        kernel.multiply(a.data, 0, b.data, 0, out.data, 0, a.size);
        out.size = a.size;
    }

    /** The kernel chosen once at startup by {@link MatrixKernels#best()}. */
    private static final class DefaultKernel {
        static final MatrixKernel INSTANCE = MatrixKernels.best();
    }

    /** {@code out[i] = m * this[i]}: applies a common matrix (a parent transform, a view matrix) to every element. {@code out} may be this array. */
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

    /** The 4x4 product of the matrices at {@code a[ao..]} and {@code b[bo..]} written to {@code out[oo..]}; the arrays may be the same. */
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
