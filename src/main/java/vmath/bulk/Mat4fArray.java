package vmath.bulk;

import java.nio.FloatBuffer;
import java.util.Arrays;
import vmath.core.Mat4f;

/**
 * Many 4x4 matrices in one {@code float[]}, 16 floats each in column-major (GPU) order, so a whole array can be
 * uploaded as it is. One matrix is a single 64-byte contiguous read, which suits per-object kernels; there is no
 * {@code Mat4f} object per element.
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

    /** Absolute write of all matrices at {@code index}; does not move the buffer position. */
    public void writeTo(FloatBuffer dst, int index) {
        dst.put(index, data, 0, size * STRIDE);
    }
}
