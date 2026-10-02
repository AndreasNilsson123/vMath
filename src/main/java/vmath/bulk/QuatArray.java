package vmath.bulk;

import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import vmath.core.Quatf;

/**
 * Many quaternions in one {@code float[]}, four floats each ({@code x, y, z, w}), with batch normalise, multiply and slerp kernels that work on
 * the array directly and allocate nothing. The kernels accept the same array as input and output.
 *
 * <p><b>Thread safety.</b> Not thread-safe: it is mutable, so use one instance per thread or synchronise externally. Concurrent reads are safe only
 * while no thread is writing.
 */
public final class QuatArray {

    /** Floats per quaternion. */
    public static final int STRIDE = 4;

    private float[] data;
    private int size;

    public QuatArray(int capacity) {
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

    public int add(Quatf q) {
        return add(q.x(), q.y(), q.z(), q.w());
    }

    public void set(int i, Quatf q) {
        checkIndex(i);
        int o = i * STRIDE;
        data[o] = q.x();
        data[o + 1] = q.y();
        data[o + 2] = q.z();
        data[o + 3] = q.w();
    }

    public Quatf get(int i) {
        checkIndex(i);
        int o = i * STRIDE;
        return new Quatf(data[o], data[o + 1], data[o + 2], data[o + 3]);
    }

    private void checkIndex(int i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException("index " + i + ", size " + size);
        }
    }

    /** The live backing array (quaternion {@code i} starts at {@code i * STRIDE}); replaced when the array grows. */
    public float[] data() {
        return data;
    }

    /**
     * Writes all quaternions ({@code x, y, z, w}) into {@code dst} starting at byte {@code offset}, {@code strideBytes} apart (at least 16), in the given byte
     * order. A stride above 16 leaves the bytes between quaternions alone, so this fills one attribute of an interleaved buffer.
     */
    public void writeTo(MemorySegment dst, long offset, long strideBytes, ByteOrder order) {
        Strided.write(data, 0, STRIDE, size, dst, offset, strideBytes, order);
    }

    /** Replaces the contents with {@code count} quaternions read from {@code src} ({@link #writeTo(MemorySegment, long, long, ByteOrder)} reversed). */
    public void readFrom(MemorySegment src, long offset, long strideBytes, ByteOrder order, int count) {
        ensureCapacity(count);
        Strided.read(src, offset, strideBytes, order, data, 0, STRIDE, count);
        size = count;
    }

    /** Absolute write of all quaternions at {@code index}; does not move the buffer position. */
    public void writeTo(FloatBuffer dst, int index) {
        dst.put(index, data, 0, size * STRIDE);
    }

    // ---------------------------------------------------------------- batch kernels

    /** Scales every quaternion to unit length in place; a zero (or non-finite) quaternion becomes the identity. */
    public void normalizeAll() {
        for (int i = 0, o = 0; i < size; i++, o += STRIDE) {
            float x = data[o], y = data[o + 1], z = data[o + 2], w = data[o + 3];
            float len = (float) Math.sqrt(x * x + y * y + z * z + w * w);
            if (len > 1e-20f && len < Float.POSITIVE_INFINITY) {
                float inv = 1f / len;
                data[o] = x * inv;
                data[o + 1] = y * inv;
                data[o + 2] = z * inv;
                data[o + 3] = w * inv;
            } else {
                data[o] = 0f;
                data[o + 1] = 0f;
                data[o + 2] = 0f;
                data[o + 3] = 1f;
            }
        }
    }

    /**
     * {@code out[i] = a[i] * b[i]} (Hamilton product, the composition order of {@link Quatf#mul}: {@code b} is applied first). The three arrays must
     * have the same size (checked for {@code a} and {@code b}); {@code out} is resized and may be {@code a} or {@code b}.
     */
    public static void multiply(QuatArray a, QuatArray b, QuatArray out) {
        requireSameSize(a, b);
        out.ensureCapacity(a.size);
        float[] pa = a.data, pb = b.data, po = out.data;
        for (int i = 0, o = 0; i < a.size; i++, o += STRIDE) {
            float ax = pa[o], ay = pa[o + 1], az = pa[o + 2], aw = pa[o + 3];
            float bx = pb[o], by = pb[o + 1], bz = pb[o + 2], bw = pb[o + 3];
            po[o] = aw * bx + ax * bw + ay * bz - az * by;
            po[o + 1] = aw * by - ax * bz + ay * bw + az * bx;
            po[o + 2] = aw * bz + ax * by - ay * bx + az * bw;
            po[o + 3] = aw * bw - ax * bx - ay * by - az * bz;
        }
        out.size = a.size;
    }

    /**
     * {@code out[i] = slerp(a[i], b[i], t)} along the shortest arc, renormalised. Same sizes as for {@link #multiply}; {@code out} may be {@code a} or
     * {@code b}. {@code t = 0} gives {@code a}, {@code t = 1} gives {@code b}.
     */
    public static void slerp(QuatArray a, QuatArray b, float t, QuatArray out) {
        requireSameSize(a, b);
        out.ensureCapacity(a.size);
        for (int i = 0, o = 0; i < a.size; i++, o += STRIDE) {
            slerp(a.data, o, b.data, o, t, out.data, o);
        }
        out.size = a.size;
    }

    private static void requireSameSize(QuatArray a, QuatArray b) {
        if (a.size != b.size) {
            throw new IllegalArgumentException("array sizes differ: " + a.size + " and " + b.size);
        }
    }

    /**
     * Spherical interpolation of the quaternions at {@code a[ao..ao+3]} and {@code b[bo..bo+3]} along the shortest arc into {@code out[oo..oo+3]}: the
     * same formula as {@link Quatf#slerp}, on plain arrays so that nothing is allocated. The result is renormalised (which also keeps long chains of
     * blends from drifting off unit length). The output may overlap either input: all inputs are read before anything is written.
     */
    public static void slerp(float[] a, int ao, float[] b, int bo, float t, float[] out, int oo) {
        float ax = a[ao], ay = a[ao + 1], az = a[ao + 2], aw = a[ao + 3];
        float bx = b[bo], by = b[bo + 1], bz = b[bo + 2], bw = b[bo + 3];
        float cos = ax * bx + ay * by + az * bz + aw * bw;
        float abs = Math.abs(cos);
        float s0, s1;
        if (1f - abs > 1e-6f) {
            float sinSqr = 1f - abs * abs;
            float sinom = 1f / (float) Math.sqrt(sinSqr);
            float omega = (float) Math.atan2(sinSqr * sinom, abs);
            s0 = (float) (Math.sin((1.0 - t) * omega) * sinom);
            s1 = (float) (Math.sin(t * omega) * sinom);
        } else {
            s0 = 1f - t;
            s1 = t;
        }
        if (cos < 0f) {
            s1 = -s1;
        }
        float x = s0 * ax + s1 * bx, y = s0 * ay + s1 * by, z = s0 * az + s1 * bz, w = s0 * aw + s1 * bw;
        float len = (float) Math.sqrt(x * x + y * y + z * z + w * w);
        if (len > 1e-20f) {
            float inv = 1f / len;
            x *= inv;
            y *= inv;
            z *= inv;
            w *= inv;
        } else {
            x = 0f;
            y = 0f;
            z = 0f;
            w = 1f;
        }
        out[oo] = x;
        out[oo + 1] = y;
        out[oo + 2] = z;
        out[oo + 3] = w;
    }

    /**
     * Writes the rotation matrix of every (unit) quaternion to {@code out} as 4x4 matrices with no translation; {@code out} is resized to the element count.
     */
    public void toMatrices(Mat4fArray out) {
        out.ensureCapacity(size);
        float[] m = out.data();
        for (int i = 0, o = 0, d = 0; i < size; i++, o += STRIDE, d += Mat4fArray.STRIDE) {
            float x = data[o], y = data[o + 1], z = data[o + 2], w = data[o + 3];
            float xx = x * x, yy = y * y, zz = z * z, xy = x * y, xz = x * z, yz = y * z, wx = w * x, wy = w * y, wz = w * z;
            m[d] = 1f - 2f * (yy + zz);
            m[d + 1] = 2f * (xy + wz);
            m[d + 2] = 2f * (xz - wy);
            m[d + 3] = 0f;
            m[d + 4] = 2f * (xy - wz);
            m[d + 5] = 1f - 2f * (xx + zz);
            m[d + 6] = 2f * (yz + wx);
            m[d + 7] = 0f;
            m[d + 8] = 2f * (xz + wy);
            m[d + 9] = 2f * (yz - wx);
            m[d + 10] = 1f - 2f * (xx + yy);
            m[d + 11] = 0f;
            m[d + 12] = 0f;
            m[d + 13] = 0f;
            m[d + 14] = 0f;
            m[d + 15] = 1f;
        }
        out.setSize(size);
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
     * Normalized linear interpolation along the shortest arc: {@code normalize((1 - t) a + sign * t b)}. About as accurate as {@link #slerp} when the rotations are
     * close (animation frames) and several times cheaper; for rotations far apart the angular speed is not constant. Same size and aliasing rules as {@code slerp}.
     */
    public static void nlerp(QuatArray a, QuatArray b, float t, QuatArray out) {
        requireSameSize(a, b);
        out.ensureCapacity(a.size);
        float[] x = a.data, y = b.data, z = out.data;
        float s0 = 1f - t;
        for (int i = 0, o = 0; i < a.size; i++, o += STRIDE) {
            float ax = x[o], ay = x[o + 1], az = x[o + 2], aw = x[o + 3];
            float bx = y[o], by = y[o + 1], bz = y[o + 2], bw = y[o + 3];
            float s1 = ax * bx + ay * by + az * bz + aw * bw < 0f ? -t : t;
            float qx = s0 * ax + s1 * bx, qy = s0 * ay + s1 * by, qz = s0 * az + s1 * bz, qw = s0 * aw + s1 * bw;
            float len = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
            if (len > 1e-20f) {
                float inv = 1f / len;
                z[o] = qx * inv;
                z[o + 1] = qy * inv;
                z[o + 2] = qz * inv;
                z[o + 3] = qw * inv;
            } else {
                z[o] = 0f;
                z[o + 1] = 0f;
                z[o + 2] = 0f;
                z[o + 3] = 1f;
            }
        }
        out.size = a.size;
    }
}
