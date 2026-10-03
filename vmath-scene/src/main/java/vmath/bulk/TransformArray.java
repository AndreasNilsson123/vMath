package vmath.bulk;

import java.lang.foreign.MemorySegment;
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
 *
 * <p><b>Thread safety.</b> Not thread-safe: it is mutable, so use one instance per thread or synchronise externally. Concurrent reads are safe only
 * while no thread is writing.
 */
public final class TransformArray extends FloatElements {

    /** Floats per transform. */
    public static final int STRIDE = 10;

    /** An empty array with room for {@code capacity} transforms (at least 1). */
    public TransformArray(int capacity) {
        super(capacity, STRIDE);
    }

    /** Appends a transform given by translation, rotation quaternion ({@code x, y, z, w}, stored as given) and scale, and returns its index. */
    public int add(float tx, float ty, float tz, float qx, float qy, float qz, float qw, float sx, float sy, float sz) {
        ensureCapacity(size + 1);
        write(size, tx, ty, tz, qx, qy, qz, qw, sx, sy, sz);
        return size++;
    }

    /** Appends a transform and returns its index. */
    public int add(Transformf t) {
        ensureCapacity(size + 1);
        store(size, t);
        return size++;
    }

    /** Replaces transform {@code i}; {@link IndexOutOfBoundsException} for an index that is not below {@link #size()}. */
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

    /** Transform {@code i} as a value (allocates); {@link IndexOutOfBoundsException} for an index that is not below {@link #size()}. */
    public Transformf get(int i) {
        checkIndex(i);
        int o = i * STRIDE;
        return new Transformf(new Vec3f(data[o], data[o + 1], data[o + 2]),
                new Quatf(data[o + 3], data[o + 4], data[o + 5], data[o + 6]),
                new Vec3f(data[o + 7], data[o + 8], data[o + 9]));
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
