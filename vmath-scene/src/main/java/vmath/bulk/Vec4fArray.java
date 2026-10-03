package vmath.bulk;

import vmath.annotations.Experimental;
import vmath.core.Mat4f;
import vmath.core.Vec4f;

/**
 * Many 4D vectors (homogeneous positions, colours, tangents with a sign, plane equations) in one
 * {@code float[]}, four floats each.
 *
 * <p>The batch methods read and write the array directly and allocate nothing; they accept the same
 * array as input and output. The shape is the same as {@link Vec3fArray}.
 *
 * <p><b>Thread safety.</b> Not thread-safe: it is mutable, so use one instance per thread or
 * synchronise externally. Concurrent reads are safe only while no thread is writing.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Vec4fArray clip = new Vec4fArray(1);
 * clip.add(2f, 4f, 6f, 2f);
 * Vec3fArray ndc = new Vec3fArray(1);
 * ndc.add(0f, 0f, 0f);
 * clip.divideByW(ndc);                                                        // (1, 2, 3)
 * }</pre>
 */
@Experimental("the kernel set may grow")
public final class Vec4fArray extends FloatElements {

    /**
     * Floats per vector.
     */
    public static final int STRIDE = 4;

    /**
     * Creates an empty array with room for {@code capacity} vectors (at least 1).
     *
     * @param capacity the capacity in elements
     */
    public Vec4fArray(int capacity) {
        super(capacity, STRIDE);
    }

    /**
     * Appends a vector and returns its index.
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @param w the w component
     * @return its index
     */
    public int add(float x, float y, float z, float w) {
        ensureCapacity(size + 1);
        int o = size * STRIDE;
        data[o] = x;
        data[o + 1] = y;
        data[o + 2] = z;
        data[o + 3] = w;
        return size++;
    }

    /**
     * Appends a vector and returns its index.
     *
     * @param v the vector; must not be {@code null}
     * @return its index
     */
    public int add(Vec4f v) {
        return add(v.x(), v.y(), v.z(), v.w());
    }

    /**
     * Replaces vector {@code i}; {@link IndexOutOfBoundsException} for an index that is not below
     * {@link #size()}.
     *
     * @param i the index
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @param w the w component
     */
    public void set(int i, float x, float y, float z, float w) {
        checkIndex(i);
        int o = i * STRIDE;
        data[o] = x;
        data[o + 1] = y;
        data[o + 2] = z;
        data[o + 3] = w;
    }

    /**
     * Replaces vector {@code i}; {@link IndexOutOfBoundsException} for an index that is not below
     * {@link #size()}.
     *
     * @param i the index
     * @param v the vector; must not be {@code null}
     */
    public void set(int i, Vec4f v) {
        set(i, v.x(), v.y(), v.z(), v.w());
    }

    /**
     * Reads a vector as an object; allocates, so use the component accessors in loops.
     *
     * @param i the index
     * @return vector {@code i} as a value (allocates); {@link IndexOutOfBoundsException} for an
     *     index that is not below {@link #size()}
     */
    public Vec4f get(int i) {
        checkIndex(i);
        int o = i * STRIDE;
        return new Vec4f(data[o], data[o + 1], data[o + 2], data[o + 3]);
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

    /**
     * Reads the w component of a vector without allocating.
     *
     * @param i the index
     * @return the w of vector {@code i}
     */
    public float w(int i) {
        checkIndex(i);
        return data[i * STRIDE + 3];
    }

    // ---------------------------------------------------------------- compaction

    // ---------------------------------------------------------------- batch kernels

    /**
     * Transforms every element with the full 4x4 product, {@code out[i] = m * this[i]}, so that a
     * perspective matrix gives clip-space positions.
     *
     * <p>{@code out} is resized and may be this array.
     *
     * @param m the matrix; must not be {@code null}
     * @param out receives the result; must not be {@code null}
     */
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

    /**
     * Performs the perspective divide: {@code out[i] = (x, y, z) / w}.
     *
     * <p>A zero {@code w} gives infinities or NaN, as the division does. {@code out} is resized.
     *
     * @param out receives the result; must not be {@code null}
     */
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

    /**
     * Multiplies every component of every vector by {@code s}.
     *
     * @param s the factor
     */
    public void scaleAll(float s) {
        for (int i = 0, n = size * STRIDE; i < n; i++) {
            data[i] *= s;
        }
    }
}
