package vmath.bulk;

import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import vmath.annotations.Experimental;
import vmath.core.Mat4f;
import vmath.core.Vec4f;

/**
 * Many 4D vectors (homogeneous positions, colours, tangents with a sign, plane equations) in one {@code float[]}, four floats each. The batch methods read and
 * write the array directly and allocate nothing; they accept the same array as input and output. The shape is the same as {@link Vec3fArray}.
 */
@Experimental("the kernel set may grow")
public final class Vec4fArray {

    /** Floats per vector. */
    public static final int STRIDE = 4;

    private float[] data;
    private int size;

    public Vec4fArray(int capacity) {
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

    public int add(float x, float y, float z, float w) {
        ensureCapacity(size + 1);
        int o = size * STRIDE;
        data[o] = x;
        data[o + 1] = y;
        data[o + 2] = z;
        data[o + 3] = w;
        return size++;
    }

    public int add(Vec4f v) {
        return add(v.x(), v.y(), v.z(), v.w());
    }

    public void set(int i, float x, float y, float z, float w) {
        checkIndex(i);
        int o = i * STRIDE;
        data[o] = x;
        data[o + 1] = y;
        data[o + 2] = z;
        data[o + 3] = w;
    }

    public void set(int i, Vec4f v) {
        set(i, v.x(), v.y(), v.z(), v.w());
    }

    public Vec4f get(int i) {
        checkIndex(i);
        int o = i * STRIDE;
        return new Vec4f(data[o], data[o + 1], data[o + 2], data[o + 3]);
    }

    public float x(int i) {
        checkIndex(i);
        return data[i * STRIDE];
    }

    public float y(int i) {
        checkIndex(i);
        return data[i * STRIDE + 1];
    }

    public float z(int i) {
        checkIndex(i);
        return data[i * STRIDE + 2];
    }

    public float w(int i) {
        checkIndex(i);
        return data[i * STRIDE + 3];
    }

    private void checkIndex(int i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException("index " + i + ", size " + size);
        }
    }

    /** The live backing array (vector {@code i} starts at {@code i * STRIDE}); replaced when the array grows. */
    public float[] data() {
        return data;
    }

    /** Writes all vectors into {@code dst} starting at byte {@code offset}, {@code strideBytes} apart (at least 16), in the given byte order. */
    public void writeTo(MemorySegment dst, long offset, long strideBytes, ByteOrder order) {
        Strided.write(data, 0, STRIDE, size, dst, offset, strideBytes, order);
    }

    /** Replaces the contents with {@code count} vectors read from {@code src} ({@link #writeTo(MemorySegment, long, long, ByteOrder)} reversed). */
    public void readFrom(MemorySegment src, long offset, long strideBytes, ByteOrder order, int count) {
        ensureCapacity(count);
        Strided.read(src, offset, strideBytes, order, data, 0, STRIDE, count);
        size = count;
    }

    /** Absolute write of all vectors at {@code index}; does not move the buffer position. */
    public void writeTo(FloatBuffer dst, int index) {
        dst.put(index, data, 0, size * STRIDE);
    }

    // ---------------------------------------------------------------- compaction

    /** Removes element {@code i} by moving the last into its place; returns the index the moved element had, or -1 if {@code i} was last. */
    public int removeSwap(int i) {
        checkIndex(i);
        int moved = Compaction.swapRemove(data, STRIDE, size, i);
        size--;
        return moved;
    }

    /** Keeps only the elements whose bit is set in {@code keep}, in order. Returns the new size. */
    public int compact(VisibilitySet keep) {
        size = Compaction.stable(data, STRIDE, size, keep);
        return size;
    }

    // ---------------------------------------------------------------- batch kernels

    /** {@code out[i] = m * this[i]}: the full 4x4 product, so a perspective matrix gives clip-space positions. {@code out} is resized and may be this array. */
    public void transform(Mat4f m, Vec4fArray out) {
        out.ensureCapacity(size);
        float m00 = m.m00(), m01 = m.m01(), m02 = m.m02(), m03 = m.m03();
        float m10 = m.m10(), m11 = m.m11(), m12 = m.m12(), m13 = m.m13();
        float m20 = m.m20(), m21 = m.m21(), m22 = m.m22(), m23 = m.m23();
        float m30 = m.m30(), m31 = m.m31(), m32 = m.m32(), m33 = m.m33();
        float[] src = data, dst = out.data;
        for (int i = 0, o = 0; i < size; i++, o += STRIDE) {
            float x = src[o], y = src[o + 1], z = src[o + 2], w = src[o + 3];
            dst[o] = m00 * x + m10 * y + m20 * z + m30 * w;
            dst[o + 1] = m01 * x + m11 * y + m21 * z + m31 * w;
            dst[o + 2] = m02 * x + m12 * y + m22 * z + m32 * w;
            dst[o + 3] = m03 * x + m13 * y + m23 * z + m33 * w;
        }
        out.size = size;
    }

    /** {@code out[i] = (x, y, z) / w}: the perspective divide. A zero {@code w} gives infinities or NaN, as the division does. {@code out} is resized. */
    public void divideByW(Vec3fArray out) {
        out.ensureCapacity(size);
        float[] src = data, dst = out.data();
        for (int i = 0, o = 0, p = 0; i < size; i++, o += STRIDE, p += Vec3fArray.STRIDE) {
            float inv = 1f / src[o + 3];
            dst[p] = src[o] * inv;
            dst[p + 1] = src[o + 1] * inv;
            dst[p + 2] = src[o + 2] * inv;
        }
        out.setSize(size);
    }

    /** Multiplies every component of every vector by {@code s}. */
    public void scaleAll(float s) {
        for (int i = 0, n = size * STRIDE; i < n; i++) {
            data[i] *= s;
        }
    }
}
