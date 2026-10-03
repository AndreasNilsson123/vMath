package vmath.bulk;

import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;

/**
 * Many 3D vectors (positions, directions, normals) in one {@code float[]}, three floats each, so
 * there is no {@code Vec3f} object per element.
 *
 * <p>The batch methods read and write the array directly and allocate nothing; they accept the same
 * array as input and output.
 *
 * <p>The array is tightly packed ({@code x, y, z, x, y, z, ...}), the layout of a vertex position
 * stream or a scalar-layout {@code vec3[]}; a std140 or std430 {@code vec3[]} has a 16-byte stride
 * and needs the padded writers in {@code vmath.gl}.
 *
 * <p><b>Thread safety.</b> Not thread-safe: it is mutable, so use one instance per thread or
 * synchronise externally. Concurrent reads are safe only while no thread is writing.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Vec3fArray points = new Vec3fArray(3);
 * points.add(0f, 0f, 0f);
 * points.add(1f, 0f, 0f);
 * points.add(0f, 1f, 0f);
 * Vec3fArray moved = new Vec3fArray(3);
 * points.transformPositions(Mat4f.translation(1f, 1f, 1f), moved);
 * Aabbf bounds = points.bounds();
 * }</pre>
 */
public final class Vec3fArray extends FloatElements {

    /**
     * Floats per vector.
     */
    public static final int STRIDE = 3;

    /**
     * Creates an empty array with room for {@code capacity} vectors (at least 1).
     *
     * @param capacity the capacity in elements
     */
    public Vec3fArray(int capacity) {
        super(capacity, STRIDE);
    }

    /**
     * Appends a vector and returns its index.
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @return its index
     */
    public int add(float x, float y, float z) {
        ensureCapacity(size + 1);
        int o = size * STRIDE;
        data[o] = x;
        data[o + 1] = y;
        data[o + 2] = z;
        return size++;
    }

    /**
     * Appends a vector and returns its index.
     *
     * @param v the vector; must not be {@code null}
     * @return its index
     */
    public int add(Vec3f v) {
        return add(v.x(), v.y(), v.z());
    }

    /**
     * Replaces vector {@code i}; {@link IndexOutOfBoundsException} for an index that is not below
     * {@link #size()}.
     *
     * @param i the index
     * @param x the x component
     * @param y the y component
     * @param z the z component
     */
    public void set(int i, float x, float y, float z) {
        checkIndex(i);
        int o = i * STRIDE;
        data[o] = x;
        data[o + 1] = y;
        data[o + 2] = z;
    }

    /**
     * Replaces vector {@code i}; {@link IndexOutOfBoundsException} for an index that is not below
     * {@link #size()}.
     *
     * @param i the index
     * @param v the vector; must not be {@code null}
     */
    public void set(int i, Vec3f v) {
        set(i, v.x(), v.y(), v.z());
    }

    /**
     * Reads a vector as an object; allocates, so use the component accessors in loops.
     *
     * @param i the index
     * @return vector {@code i} as a value (allocates); {@link IndexOutOfBoundsException} for an
     *     index that is not below {@link #size()}
     */
    public Vec3f get(int i) {
        checkIndex(i);
        int o = i * STRIDE;
        return new Vec3f(data[o], data[o + 1], data[o + 2]);
    }

    /**
     * Reads the x component of a vector without allocating.
     *
     * @param i the index
     * @return the x of vector {@code i}; {@link IndexOutOfBoundsException} for an index that is not
     *     below {@link #size()}
     */
    public float x(int i) {
        checkIndex(i);
        return data[i * STRIDE];
    }

    /**
     * Reads the y component of a vector without allocating.
     *
     * @param i the index
     * @return the y of vector {@code i}
     */
    public float y(int i) {
        checkIndex(i);
        return data[i * STRIDE + 1];
    }

    /**
     * Reads the z component of a vector without allocating.
     *
     * @param i the index
     * @return the z of vector {@code i}
     */
    public float z(int i) {
        checkIndex(i);
        return data[i * STRIDE + 2];
    }

    // ---------------------------------------------------------------- batch kernels

    /**
     * Transforms every element as a position (rotation, scale and translation):
     * {@code out[i] = m * (this[i], 1)}.
     *
     * <p>{@code out} is resized to the element count and may be this array. The matrix is assumed
     * affine (its bottom row is taken as 0, 0, 0, 1).
     *
     * @param m the matrix; must not be {@code null}
     * @param out receives the result; must not be {@code null}
     */
    public void transformPositions(Mat4f m, Vec3fArray out) {
        transform(m, out, true);
    }

    /**
     * Transforms every element as a direction (no translation):
     * {@code out[i] = upper-left 3x3 of m * this[i]}.
     *
     * <p>{@code out} may be this array.
     *
     * @param m the matrix; must not be {@code null}
     * @param out receives the result; must not be {@code null}
     */
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

    /**
     * Scales every vector to unit length in place; a zero vector stays zero.
     */
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

    /**
     * Computes the box around all vectors, treated as points, by a linear scan.
     *
     * @return the box around all vectors taken as points; {@link Aabbf#EMPTY} when there are none
     */
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

    // ---------------------------------------------------------------- compaction

}
