package vmath.bulk;

import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import vmath.core.Quatf;
import vmath.core.Transformf;
import vmath.core.Vec3f;

/**
 * Many translation-rotation-scale transforms in one {@code float[]}, ten floats each: translation {@code x, y, z}, unit quaternion
 * {@code x, y, z, w}, scale {@code x, y, z}. This is the layout of {@code vmath.anim.Pose} and of the local transforms in
 * {@code vmath.anim.TransformHierarchy}, so data can move between them without conversion.
 *
 * <p>The quaternions are assumed to be unit length (use {@link QuatArray#normalizeAll} on a copy, or normalise when writing). The batch kernels read and
 * write the arrays directly and allocate nothing.
 */
public final class TransformArray {

    /** Floats per transform. */
    public static final int STRIDE = 10;

    private float[] data;
    private int size;

    public TransformArray(int capacity) {
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

    public int add(float tx, float ty, float tz, float qx, float qy, float qz, float qw, float sx, float sy, float sz) {
        ensureCapacity(size + 1);
        write(size, tx, ty, tz, qx, qy, qz, qw, sx, sy, sz);
        return size++;
    }

    public int add(Transformf t) {
        ensureCapacity(size + 1);
        store(size, t);
        return size++;
    }

    public void set(int i, Transformf t) {
        checkIndex(i);
        store(i, t);
    }

    private void store(int i, Transformf t) {
        Vec3f p = t.translation(), s = t.scale();
        Quatf q = t.rotation();
        write(i, p.x(), p.y(), p.z(), q.x(), q.y(), q.z(), q.w(), s.x(), s.y(), s.z());
    }

    private void write(int i, float tx, float ty, float tz, float qx, float qy, float qz, float qw, float sx, float sy, float sz) {
        int o = i * STRIDE;
        data[o] = tx;
        data[o + 1] = ty;
        data[o + 2] = tz;
        data[o + 3] = qx;
        data[o + 4] = qy;
        data[o + 5] = qz;
        data[o + 6] = qw;
        data[o + 7] = sx;
        data[o + 8] = sy;
        data[o + 9] = sz;
    }

    public Transformf get(int i) {
        checkIndex(i);
        int o = i * STRIDE;
        return new Transformf(new Vec3f(data[o], data[o + 1], data[o + 2]),
                new Quatf(data[o + 3], data[o + 4], data[o + 5], data[o + 6]),
                new Vec3f(data[o + 7], data[o + 8], data[o + 9]));
    }

    private void checkIndex(int i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException("index " + i + ", size " + size);
        }
    }

    /** The live backing array (transform {@code i} starts at {@code i * STRIDE}); replaced when the array grows. */
    public float[] data() {
        return data;
    }

    /**
     * Writes all transforms (10 floats each, the layout of this array) into {@code dst} starting at byte {@code offset}, {@code strideBytes} apart (at least
     * 40), in the given byte order.
     */
    public void writeTo(MemorySegment dst, long offset, long strideBytes, ByteOrder order) {
        Strided.write(data, 0, STRIDE, size, dst, offset, strideBytes, order);
    }

    /** Replaces the contents with {@code count} transforms read from {@code src} ({@link #writeTo(MemorySegment, long, long, ByteOrder)} reversed). */
    public void readFrom(MemorySegment src, long offset, long strideBytes, ByteOrder order, int count) {
        ensureCapacity(count);
        Strided.read(src, offset, strideBytes, order, data, 0, STRIDE, count);
        size = count;
    }

    /** Absolute write of all transforms at {@code index}; does not move the buffer position. */
    public void writeTo(FloatBuffer dst, int index) {
        dst.put(index, data, 0, size * STRIDE);
    }

    // ---------------------------------------------------------------- batch kernels

    /**
     * Writes the model matrix {@code T * R * S} of every transform to {@code out} (16 floats each, column-major, bottom row 0, 0, 0, 1), resizing
     * {@code out} to the element count: the matrices to upload for rendering.
     */
    public void toMatrices(Mat4fArray out) {
        out.ensureCapacity(size);
        float[] m = out.data();
        for (int i = 0, o = 0, d = 0; i < size; i++, o += STRIDE, d += Mat4fArray.STRIDE) {
            float x = data[o + 3], y = data[o + 4], z = data[o + 5], w = data[o + 6];
            float sx = data[o + 7], sy = data[o + 8], sz = data[o + 9];
            float xx = x * x, yy = y * y, zz = z * z, xy = x * y, xz = x * z, yz = y * z, wx = w * x, wy = w * y, wz = w * z;
            m[d] = (1f - 2f * (yy + zz)) * sx;
            m[d + 1] = 2f * (xy + wz) * sx;
            m[d + 2] = 2f * (xz - wy) * sx;
            m[d + 3] = 0f;
            m[d + 4] = 2f * (xy - wz) * sy;
            m[d + 5] = (1f - 2f * (xx + zz)) * sy;
            m[d + 6] = 2f * (yz + wx) * sy;
            m[d + 7] = 0f;
            m[d + 8] = 2f * (xz + wy) * sz;
            m[d + 9] = 2f * (yz - wx) * sz;
            m[d + 10] = (1f - 2f * (xx + yy)) * sz;
            m[d + 11] = 0f;
            m[d + 12] = data[o];
            m[d + 13] = data[o + 1];
            m[d + 14] = data[o + 2];
            m[d + 15] = 1f;
        }
        out.setSize(size);
    }

    /**
     * {@code out[i]} is {@code a[i]} blended toward {@code b[i]} by {@code t}: translation and scale linearly, rotation by slerp along the shortest arc
     * ({@code t = 0} gives {@code a}, {@code t = 1} gives {@code b}). {@code a} and {@code b} must have the same size; {@code out} is resized and may
     * be either of them.
     */
    public static void blend(TransformArray a, TransformArray b, float t, TransformArray out) {
        if (a.size != b.size) {
            throw new IllegalArgumentException("array sizes differ: " + a.size + " and " + b.size);
        }
        out.ensureCapacity(a.size);
        float[] pa = a.data, pb = b.data, po = out.data;
        for (int i = 0, o = 0; i < a.size; i++, o += STRIDE) {
            for (int k = 0; k < 3; k++) {
                po[o + k] = pa[o + k] + (pb[o + k] - pa[o + k]) * t;
                po[o + 7 + k] = pa[o + 7 + k] + (pb[o + 7 + k] - pa[o + 7 + k]) * t;
            }
            QuatArray.slerp(pa, o + 3, pb, o + 3, t, po, o + 3);
        }
        out.size = a.size;
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

    /**
     * As {@link #toMatrices(Mat4fArray)}, but writes the model matrices (16 native-order floats, column-major) straight into {@code dst}: element {@code i} at byte
     * {@code offset + i * strideBytes}, the way a persistently mapped instance buffer wants them (the stride may be larger than 64 to leave room for per-instance
     * data, which is left untouched). Nothing is allocated and no heap matrix array is involved.
     */
    public void toMatrices(java.lang.foreign.MemorySegment dst, long offset, long strideBytes) {
        if (strideBytes < 64) {
            throw new IllegalArgumentException("stride " + strideBytes + " is smaller than one matrix (64 bytes)");
        }
        java.lang.foreign.ValueLayout.OfFloat f = java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED;
        for (int i = 0, o = 0; i < size; i++, o += STRIDE) {
            long d = offset + i * strideBytes;
            float x = data[o + 3], y = data[o + 4], z = data[o + 5], w = data[o + 6];
            float sx = data[o + 7], sy = data[o + 8], sz = data[o + 9];
            float xx = x * x, yy = y * y, zz = z * z, xy = x * y, xz = x * z, yz = y * z, wx = w * x, wy = w * y, wz = w * z;
            dst.set(f, d, (1f - 2f * (yy + zz)) * sx);
            dst.set(f, d + 4, 2f * (xy + wz) * sx);
            dst.set(f, d + 8, 2f * (xz - wy) * sx);
            dst.set(f, d + 12, 0f);
            dst.set(f, d + 16, 2f * (xy - wz) * sy);
            dst.set(f, d + 20, (1f - 2f * (xx + zz)) * sy);
            dst.set(f, d + 24, 2f * (yz + wx) * sy);
            dst.set(f, d + 28, 0f);
            dst.set(f, d + 32, 2f * (xz + wy) * sz);
            dst.set(f, d + 36, 2f * (yz - wx) * sz);
            dst.set(f, d + 40, (1f - 2f * (xx + yy)) * sz);
            dst.set(f, d + 44, 0f);
            dst.set(f, d + 48, data[o]);
            dst.set(f, d + 52, data[o + 1]);
            dst.set(f, d + 56, data[o + 2]);
            dst.set(f, d + 60, 1f);
        }
    }
}
