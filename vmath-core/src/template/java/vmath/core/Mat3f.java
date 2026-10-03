package vmath.core;

import vmath.annotations.Bulk;
import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import java.nio.FloatBuffer;

/**
 * Immutable 3x3 float matrix, column-major.
 *
 * <p>Component {@code mCR} is column {@code C}, row {@code R} (JOML naming). The canonical
 * constructor takes components in memory order: column 0 top-to-bottom, then column 1, then 2.
 *
 * <p>Valhalla: {@code -Pvalhalla} builds turn {@code @ValueType} into a real {@code value record}.
 * Never use {@code ==}, {@code synchronized} or identity-based APIs on it.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads without
 * synchronization.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Mat3f rotation = Mat3f.rotationY(0.5f);
 * Mat3f scaled = rotation.mul(Mat3f.scaling(2f, 2f, 2f));
 * Vec3f v = scaled.transform(Vec3f.UNIT_X);
 * Mat3f forNormals = scaled.normal();                    // inverse transpose, for shading normals
 * }</pre>
 *
 * @param m00 the m00
 * @param m01 the m01
 * @param m02 the m02
 * @param m10 the m10
 * @param m11 the m11
 * @param m12 the m12
 * @param m20 the m20
 * @param m21 the m21
 * @param m22 the m22
 */
@GenerateDouble
@ValueType
public record Mat3f(
        float m00, float m01, float m02,
        float m10, float m11, float m12,
        float m20, float m21, float m22) {

    /**
     * The identity matrix.
     */
    public static final Mat3f IDENTITY = new Mat3f(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f);

    /**
     * Builds a matrix from its three column vectors; the usual way to write a basis.
     *
     * @param c0 the vector; must not be {@code null}
     * @param c1 the vector; must not be {@code null}
     * @param c2 the vector; must not be {@code null}
     * @return the matrix with the given columns
     */
    public static Mat3f fromColumns(Vec3f c0, Vec3f c1, Vec3f c2) {
        return new Mat3f(
                c0.x(), c0.y(), c0.z(),
                c1.x(), c1.y(), c1.z(),
                c2.x(), c2.y(), c2.z());
    }

    /**
     * Reads a matrix from an array in column-major order, the order {@link #writeTo} writes and
     * OpenGL and Vulkan expect.
     *
     * @param src the source to read from
     * @param off the index of the first element to read or write
     * @return the matrix stored column-major in {@code src[off..off+8]} (the order {@link #writeTo}
     *     writes)
     */
    public static Mat3f fromArray(float[] src, int off) {
        return new Mat3f(
                src[off], src[off + 1], src[off + 2],
                src[off + 3], src[off + 4], src[off + 5],
                src[off + 6], src[off + 7], src[off + 8]);
    }

    /**
     * Builds a non-uniform scale matrix.
     *
     * @param sx the scale along x
     * @param sy the scale along y
     * @param sz the scale along z
     * @return a scale by the given factors along each axis
     */
    public static Mat3f scaling(float sx, float sy, float sz) {
        return new Mat3f(
                sx, 0f, 0f,
                0f, sy, 0f,
                0f, 0f, sz);
    }

    /**
     * Builds a rotation about the X axis, using the right-handed convention.
     *
     * @param angle the angle in radians
     * @return a rotation about the X axis by {@code angle} radians (right-handed: counter-clockwise
     *     looking down the axis toward the origin)
     */
    public static Mat3f rotationX(float angle) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        return new Mat3f(
                1f, 0f, 0f,
                0f, c, s,
                0f, -s, c);
    }

    /**
     * Builds a rotation about the Y axis, using the right-handed convention.
     *
     * @param angle the angle in radians
     * @return a rotation about the Y axis by {@code angle} radians (right-handed: counter-clockwise
     *     looking down the axis toward the origin)
     */
    public static Mat3f rotationY(float angle) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        return new Mat3f(
                c, 0f, -s,
                0f, 1f, 0f,
                s, 0f, c);
    }

    /**
     * Builds a rotation about the Z axis, using the right-handed convention.
     *
     * @param angle the angle in radians
     * @return a rotation about the Z axis by {@code angle} radians (right-handed: counter-clockwise
     *     looking down the axis toward the origin)
     */
    public static Mat3f rotationZ(float angle) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        return new Mat3f(
                c, s, 0f,
                -s, c, 0f,
                0f, 0f, 1f);
    }

    /**
     * Builds a rotation about an arbitrary axis with the Rodrigues formula; the axis is normalised
     * first, so a zero axis gives non-finite values.
     *
     * @param angle the angle in radians
     * @param axis the axis; must not be {@code null}
     * @return rotation of {@code angle} radians about {@code axis} (normalized internally)
     */
    public static Mat3f rotationAxis(float angle, Vec3f axis) {
        return rotation(Quatf.fromAxisAngle(angle, axis));
    }

    /**
     * Builds the skew-symmetric (cross-product) matrix of a vector, which turns the cross product
     * into a matrix multiplication.
     *
     * @param v the vector; must not be {@code null}
     * @return the skew-symmetric matrix {@code [v]x}, so that
     *     {@code skew(v).transform(u) == v.cross(u)}
     */
    public static Mat3f skew(Vec3f v) {
        return new Mat3f(
                0f, v.z(), -v.y(),
                -v.z(), 0f, v.x(),
                v.y(), -v.x(), 0f);
    }

    /**
     * Constructs an orthonormal basis around a unit normal without branches, following Duff et al.
     *
     * <p>(2017); a standard way to get tangent vectors when the mesh has none. The normal must have
     * unit length. {@code n} must be unit length. Continuous except at {@code n.z == 0} sign flips,
     * and free of the singularity that cross-product constructions have.
     *
     * @param n the vector; must not be {@code null}
     * @return orthonormal basis with {@code n} as the third column (branchless, after Duff et al.
     *     2017): tangent and bitangent are the first two columns
     */
    public static Mat3f basisFromNormal(Vec3f n) {
        float sign = Math.copySign(1f, n.z());
        float a = -1f / (sign + n.z());
        float b = n.x() * n.y() * a;
        Vec3f tangent = new Vec3f(1f + sign * n.x() * n.x() * a, sign * b, -sign * n.x());
        Vec3f bitangent = new Vec3f(b, sign + n.y() * n.y() * a, -n.y());
        return fromColumns(tangent, bitangent, n);
    }

    /**
     * Converts a unit quaternion to the equivalent rotation matrix; the quaternion is assumed to be
     * normalised, and a non-unit one yields a scaled matrix.
     *
     * @param q the quaternion; must not be {@code null}
     * @return rotation matrix for a unit quaternion
     */
    public static Mat3f rotation(Quatf q) {
        float x = q.x(), y = q.y(), z = q.z(), w = q.w();
        float xx = x * x, yy = y * y, zz = z * z, ww = w * w;
        float xy = x * y, xz = x * z, yz = y * z, xw = x * w, yw = y * w, zw = z * w;
        return new Mat3f(
                ww + xx - yy - zz, 2f * (xy + zw), 2f * (xz - yw),
                2f * (xy - zw), ww - xx + yy - zz, 2f * (yz + xw),
                2f * (xz + yw), 2f * (yz - xw), ww - xx - yy + zz);
    }

    /**
     * Composes two transforms by matrix multiplication; the product is not commutative, and the
     * right operand acts on the vector first.
     *
     * @param b the second matrix; must not be {@code null}
     * @return matrix product {@code this * b}: transforming by the result applies {@code b} first
     */
    public Mat3f mul(Mat3f b) {
        return new Mat3f(
                m00 * b.m00 + m10 * b.m01 + m20 * b.m02,
                m01 * b.m00 + m11 * b.m01 + m21 * b.m02,
                m02 * b.m00 + m12 * b.m01 + m22 * b.m02,
                m00 * b.m10 + m10 * b.m11 + m20 * b.m12,
                m01 * b.m10 + m11 * b.m11 + m21 * b.m12,
                m02 * b.m10 + m12 * b.m11 + m22 * b.m12,
                m00 * b.m20 + m10 * b.m21 + m20 * b.m22,
                m01 * b.m20 + m11 * b.m21 + m21 * b.m22,
                m02 * b.m20 + m12 * b.m21 + m22 * b.m22);
    }

    /**
     * Applies the matrix to a column vector.
     *
     * @param v the vector; must not be {@code null}
     * @return the matrix times the vector {@code v}
     */
    @Bulk(uniform = "this")
    public Vec3f transform(Vec3f v) {
        float x = v.x(), y = v.y(), z = v.z();
        return new Vec3f(
                m00 * x + m10 * y + m20 * z,
                m01 * x + m11 * y + m21 * z,
                m02 * x + m12 * y + m22 * z);
    }

    /**
     * Swaps rows and columns; the inverse of a rotation matrix.
     *
     * @return the transpose: rows and columns exchanged
     */
    public Mat3f transpose() {
        return new Mat3f(
                m00, m10, m20,
                m01, m11, m21,
                m02, m12, m22);
    }

    /**
     * Computes the determinant by cofactor expansion; zero for a singular matrix, negative for a
     * mirroring transform.
     *
     * @return the determinant
     */
    public float determinant() {
        return (m00 * m11 - m01 * m10) * m22
                + (m02 * m10 - m00 * m12) * m21
                + (m01 * m12 - m02 * m11) * m20;
    }

    /**
     * Inverts the matrix as the adjugate divided by the determinant.
     *
     * <p>A singular matrix yields non-finite components.
     *
     * @return general inverse
     */
    public Mat3f invert() {
        float a = m00 * m11 - m01 * m10;
        float b = m02 * m10 - m00 * m12;
        float c = m01 * m12 - m02 * m11;
        float s = 1f / (a * m22 + b * m21 + c * m20);
        return new Mat3f(
                (m11 * m22 - m21 * m12) * s,
                (m21 * m02 - m01 * m22) * s,
                c * s,
                (m20 * m12 - m10 * m22) * s,
                (m00 * m22 - m20 * m02) * s,
                b * s,
                (m10 * m21 - m20 * m11) * s,
                (m20 * m01 - m00 * m21) * s,
                a * s);
    }

    /**
     * Derives the matrix for transforming normals, the inverse transpose, which keeps normals
     * perpendicular to the surface under non-uniform scale.
     *
     * @return inverse-transpose: transforms normals correctly under non-uniform scale
     */
    public Mat3f normal() {
        return invert().transpose();
    }

    /**
     * Extracts one column as a vector; allocates a vector.
     *
     * @param c the column index
     * @return column {@code c}
     * @throws IndexOutOfBoundsException if {@code c} is not a column index
     */
    public Vec3f column(int c) {
        return switch (c) {
            case 0 -> new Vec3f(m00, m01, m02);
            case 1 -> new Vec3f(m10, m11, m12);
            case 2 -> new Vec3f(m20, m21, m22);
            default -> throw new IndexOutOfBoundsException(c);
        };
    }

    /**
     * Extracts one row as a vector; allocates a vector.
     *
     * @param r the row index
     * @return row {@code r}
     * @throws IndexOutOfBoundsException if {@code r} is not a row index
     */
    public Vec3f row(int r) {
        return switch (r) {
            case 0 -> new Vec3f(m00, m10, m20);
            case 1 -> new Vec3f(m01, m11, m21);
            case 2 -> new Vec3f(m02, m12, m22);
            default -> throw new IndexOutOfBoundsException(r);
        };
    }

    /**
     * Reads one element by column and row index; out-of-range indices are rejected.
     *
     * @param column the column, counted from 0
     * @param row the row, counted from 0
     * @return the element at {@code column} and {@code row}
     * @throws IndexOutOfBoundsException if {@code column} or {@code row} is out of range
     */
    public float get(int column, int row) {
        if (row < 0 || row > 2) {
            throw new IndexOutOfBoundsException(column + "," + row);
        }
        return switch (column * 3 + row) {
            case 0 -> m00; case 1 -> m01; case 2 -> m02;
            case 3 -> m10; case 4 -> m11; case 5 -> m12;
            case 6 -> m20; case 7 -> m21; case 8 -> m22;
            default -> throw new IndexOutOfBoundsException(column + "," + row);
        };
    }

    /**
     * Checks all elements for NaN and infinity, which is the cheap way to detect a failed
     * inversion.
     *
     * @return {@code true} when no component is NaN or infinite
     */
    public boolean isFinite() {
        return Float.isFinite(m00) && Float.isFinite(m01) && Float.isFinite(m02)
                && Float.isFinite(m10) && Float.isFinite(m11) && Float.isFinite(m12)
                && Float.isFinite(m20) && Float.isFinite(m21) && Float.isFinite(m22);
    }

    /**
     * Compares two matrices element by element with an absolute tolerance, for tests and for
     * detecting changes; not a scale-relative comparison.
     *
     * @param o the other matrix; must not be {@code null}
     * @param eps the tolerance
     * @return {@code true} when every element differs from that of {@code o} by at most {@code eps}
     */
    public boolean approxEquals(Mat3f o, float eps) {
        return Math.abs(m00 - o.m00) <= eps && Math.abs(m01 - o.m01) <= eps && Math.abs(m02 - o.m02) <= eps
                && Math.abs(m10 - o.m10) <= eps && Math.abs(m11 - o.m11) <= eps && Math.abs(m12 - o.m12) <= eps
                && Math.abs(m20 - o.m20) <= eps && Math.abs(m21 - o.m21) <= eps && Math.abs(m22 - o.m22) <= eps;
    }

    /**
     * Writes 9 tightly packed column-major values.
     *
     * <p>For std140 uniforms use {@code vmath.gl.GpuWriter} or a generated {@code @GpuStruct}
     * writer.
     *
     * @param dst receives the result
     * @param off the index of the first element to read or write
     */
    public void writeTo(float[] dst, int off) {
        dst[off] = m00; dst[off + 1] = m01; dst[off + 2] = m02;
        dst[off + 3] = m10; dst[off + 4] = m11; dst[off + 5] = m12;
        dst[off + 6] = m20; dst[off + 7] = m21; dst[off + 8] = m22;
    }

    /**
     * Writes the 9 column-major values at the absolute position {@code index}; does not change the
     * buffer position.
     *
     * @param dst receives the result; must not be {@code null}
     * @param index the index
     */
    public void writeTo(FloatBuffer dst, int index) {
        dst.put(index, m00).put(index + 1, m01).put(index + 2, m02)
                .put(index + 3, m10).put(index + 4, m11).put(index + 5, m12)
                .put(index + 6, m20).put(index + 7, m21).put(index + 8, m22);
    }

    /**
     * Converts the elements to {@code double}, which is exact.
     *
     * @return the same value with double components
     */
    @FloatOnly
    public Mat3d toDouble() {
        return new Mat3d(m00, m01, m02, m10, m11, m12, m20, m21, m22);
    }

    /**
     * Converts the elements to {@code float}, which rounds values that need more precision.
     *
     * @return the same value with float components (rounded to the nearest float for double types)
     */
    @DoubleOnly
    public Mat3f toFloat() {
        return new Mat3f(
                (float) m00, (float) m01, (float) m02,
                (float) m10, (float) m11, (float) m12,
                (float) m20, (float) m21, (float) m22);
    }
}
