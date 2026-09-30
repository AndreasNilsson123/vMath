package vmath.bulk;

import java.nio.FloatBuffer;
import java.util.Arrays;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;

/**
 * Many 3D vectors (positions, directions, normals) in one {@code float[]}, three floats each, so there is no {@code Vec3f} object per element.
 * The batch methods read and write the array directly and allocate nothing; they accept the same array as input and output.
 *
 * <p>The array is tightly packed ({@code x, y, z, x, y, z, ...}), the layout of a vertex position stream or a scalar-layout {@code vec3[]}; a std140 or
 * std430 {@code vec3[]} has a 16-byte stride and needs the padded writers in {@code vmath.gl}.
 */
public final class Vec3fArray {

    /** Floats per vector. */
    public static final int STRIDE = 3;

    private float[] data;
    private int size;

    public Vec3fArray(int capacity) {
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

    public int add(float x, float y, float z) {
        ensureCapacity(size + 1);
        int o = size * STRIDE;
        data[o] = x;
        data[o + 1] = y;
        data[o + 2] = z;
        return size++;
    }

    public int add(Vec3f v) {
        return add(v.x(), v.y(), v.z());
    }

    public void set(int i, float x, float y, float z) {
        checkIndex(i);
        int o = i * STRIDE;
        data[o] = x;
        data[o + 1] = y;
        data[o + 2] = z;
    }

    public void set(int i, Vec3f v) {
        set(i, v.x(), v.y(), v.z());
    }

    public Vec3f get(int i) {
        checkIndex(i);
        int o = i * STRIDE;
        return new Vec3f(data[o], data[o + 1], data[o + 2]);
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

    private void checkIndex(int i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException("index " + i + ", size " + size);
        }
    }

    /** The live backing array (vector {@code i} starts at {@code i * STRIDE}); replaced when the array grows. */
    public float[] data() {
        return data;
    }

    /** Absolute write of all vectors at {@code index}; does not move the buffer position. */
    public void writeTo(FloatBuffer dst, int index) {
        dst.put(index, data, 0, size * STRIDE);
    }

    // ---------------------------------------------------------------- batch kernels

    /**
     * {@code out[i] = m * (this[i], 1)}: transforms every element as a position (rotation, scale and translation). {@code out} is resized to the element
     * count and may be this array. The matrix is assumed affine (its bottom row is taken as 0, 0, 0, 1).
     */
    public void transformPositions(Mat4f m, Vec3fArray out) {
        transform(m, out, true);
    }

    /** {@code out[i] = upper-left 3x3 of m * this[i]}: transforms every element as a direction (no translation). {@code out} may be this array. */
    public void transformDirections(Mat4f m, Vec3fArray out) {
        transform(m, out, false);
    }

    private void transform(Mat4f m, Vec3fArray out, boolean position) {
        out.ensureCapacity(size);
        float m00 = m.m00(), m01 = m.m01(), m02 = m.m02();
        float m10 = m.m10(), m11 = m.m11(), m12 = m.m12();
        float m20 = m.m20(), m21 = m.m21(), m22 = m.m22();
        float tx = position ? m.m30() : 0f, ty = position ? m.m31() : 0f, tz = position ? m.m32() : 0f;
        float[] src = data, dst = out.data;
        for (int i = 0, o = 0; i < size; i++, o += STRIDE) {
            float x = src[o], y = src[o + 1], z = src[o + 2];
            dst[o] = m00 * x + m10 * y + m20 * z + tx;
            dst[o + 1] = m01 * x + m11 * y + m21 * z + ty;
            dst[o + 2] = m02 * x + m12 * y + m22 * z + tz;
        }
        out.size = size;
    }

    /** Scales every vector to unit length in place; a zero vector stays zero. */
    public void normalizeAll() {
        for (int i = 0, o = 0; i < size; i++, o += STRIDE) {
            float x = data[o], y = data[o + 1], z = data[o + 2];
            float len2 = x * x + y * y + z * z;
            if (len2 > 0f) {
                float inv = 1f / (float) Math.sqrt(len2);
                data[o] = x * inv;
                data[o + 1] = y * inv;
                data[o + 2] = z * inv;
            }
        }
    }

    /** The box around all vectors taken as points; {@link Aabbf#EMPTY} when there are none. */
    public Aabbf bounds() {
        if (size == 0) {
            return Aabbf.EMPTY;
        }
        float x0 = Float.POSITIVE_INFINITY, y0 = x0, z0 = x0;
        float x1 = Float.NEGATIVE_INFINITY, y1 = x1, z1 = x1;
        for (int i = 0, o = 0; i < size; i++, o += STRIDE) {
            x0 = Math.min(x0, data[o]);
            y0 = Math.min(y0, data[o + 1]);
            z0 = Math.min(z0, data[o + 2]);
            x1 = Math.max(x1, data[o]);
            y1 = Math.max(y1, data[o + 1]);
            z1 = Math.max(z1, data[o + 2]);
        }
        return new Aabbf(x0, y0, z0, x1, y1, z1);
    }
}
