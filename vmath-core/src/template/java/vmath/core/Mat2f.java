package vmath.core;

import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import java.nio.FloatBuffer;

/**
 * Immutable 2x2 float matrix, column-major: the linear maps of the plane (rotation, scale, shear,
 * reflection) and the covariance and Jacobian matrices of 2D problems.
 *
 * <p>Component {@code mCR} is column {@code C}, row {@code R} (JOML naming), so {@code m00, m01} is
 * the first column and {@code m10, m11} the second. The canonical constructor takes components in
 * memory order: column 0 top-to-bottom, then column 1. For 2D transforms that include a translation
 * use {@link Mat3x2f}.
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
 * Mat2f rotation = Mat2f.rotation((float) Math.PI / 2);
 * Vec2f turned = rotation.transform(new Vec2f(1f, 0f));  // (0, 1)
 * Mat2f inverse = rotation.invert();
 * float determinant = rotation.determinant();            // 1
 * }</pre>
 *
 * @param m00 the m00
 * @param m01 the m01
 * @param m10 the m10
 * @param m11 the m11
 */
@GenerateDouble
@ValueType
public record Mat2f(float m00, float m01, float m10, float m11) {

    /**
     * The identity matrix.
     */
    public static final Mat2f IDENTITY = new Mat2f(1f, 0f, 0f, 1f);
    /**
     * The zero matrix.
     */
    public static final Mat2f ZERO = new Mat2f(0f, 0f, 0f, 0f);

    /**
     * Builds a matrix from its two column vectors; the usual way to write a basis.
     *
     * @param c0 the vector; must not be {@code null}
     * @param c1 the vector; must not be {@code null}
     * @return the matrix with the given columns
     */
    public static Mat2f fromColumns(Vec2f c0, Vec2f c1) {
        return new Mat2f(c0.x(), c0.y(), c1.x(), c1.y());
    }

    /**
     * Reads a matrix from an array in column-major order, the order {@link #writeTo} writes and
     * OpenGL and Vulkan expect.
     *
     * @param src the source to read from
     * @param off the index of the first element to read or write
     * @return the matrix stored column-major in {@code src[off..off+3]} (the order {@link #writeTo}
     *     writes)
     */
    public static Mat2f fromArray(float[] src, int off) {
        return new Mat2f(src[off], src[off + 1], src[off + 2], src[off + 3]);
    }

    /**
     * Builds a non-uniform scale matrix.
     *
     * @param sx the scale along x
     * @param sy the scale along y
     * @return a scale by the given factors along each axis
     */
    public static Mat2f scaling(float sx, float sy) {
        return new Mat2f(sx, 0f, 0f, sy);
    }

    /**
     * Builds a uniform scale matrix.
     *
     * @param s the scale factor
     * @return a uniform scale by {@code s}
     */
    public static Mat2f scaling(float s) {
        return new Mat2f(s, 0f, 0f, s);
    }

    /**
     * Builds a rotation matrix from an angle in radians, turning counter-clockwise for a y-up
     * coordinate system.
     *
     * @param angle the angle in radians
     * @return a counter-clockwise rotation by {@code angle} radians: the first column is
     *     {@code (cos, sin)}
     */
    public static Mat2f rotation(float angle) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        return new Mat2f(c, s, -s, c);
    }

    /**
     * Builds a shear matrix that displaces x in proportion to y.
     *
     * @param k the shear factor
     * @return a shear that slides points along X in proportion to their Y: {@code (x, y)} goes to
     *     {@code (x + k y, y)}
     */
    public static Mat2f shearX(float k) {
        return new Mat2f(1f, 0f, k, 1f);
    }

    /**
     * Builds a shear matrix that displaces y in proportion to x.
     *
     * @param k the shear factor
     * @return a shear that slides points along Y in proportion to their X: {@code (x, y)} goes to
     *     {@code (x, y + k x)}
     */
    public static Mat2f shearY(float k) {
        return new Mat2f(1f, k, 0f, 1f);
    }

    /**
     * Builds the rank-one matrix from two vectors; with a unit {@code a == b} it is a projector.
     *
     * @param a the first vector; must not be {@code null}
     * @param b the second vector; must not be {@code null}
     * @return the outer product {@code a * b^T}: the matrix that maps {@code v} to
     *     {@code a * (b . v)}
     */
    public static Mat2f outer(Vec2f a, Vec2f b) {
        return new Mat2f(a.x() * b.x(), a.y() * b.x(), a.x() * b.y(), a.y() * b.y());
    }

    /**
     * Builds a mirror matrix for a line through the origin; the axis must have unit length, as it
     * is not normalised here.
     *
     * @param axis the axis; must not be {@code null}
     * @return the reflection across the line through the origin with the unit direction
     *     {@code axis}
     */
    public static Mat2f reflection(Vec2f axis) {
        float xx = axis.x() * axis.x(), yy = axis.y() * axis.y(), xy = axis.x() * axis.y();
        return new Mat2f(xx - yy, 2f * xy, 2f * xy, yy - xx);
    }

    /**
     * Adds the matrices element by element.
     *
     * @param o the other matrix; must not be {@code null}
     * @return the sum {@code this + o}
     */
    public Mat2f add(Mat2f o) {
        return new Mat2f(m00 + o.m00, m01 + o.m01, m10 + o.m10, m11 + o.m11);
    }

    /**
     * Subtracts the matrices element by element.
     *
     * @param o the other matrix; must not be {@code null}
     * @return the difference {@code this - o}
     */
    public Mat2f sub(Mat2f o) {
        return new Mat2f(m00 - o.m00, m01 - o.m01, m10 - o.m10, m11 - o.m11);
    }

    /**
     * Scales every element by a scalar.
     *
     * @param s the factor
     * @return every element multiplied by {@code s}
     */
    public Mat2f mul(float s) {
        return new Mat2f(m00 * s, m01 * s, m10 * s, m11 * s);
    }

    /**
     * Composes two transforms by matrix multiplication; the product is not commutative, and the
     * right operand acts on the vector first.
     *
     * @param b the second matrix; must not be {@code null}
     * @return matrix product {@code this * b}: transforming by the result applies {@code b} first
     */
    public Mat2f mul(Mat2f b) {
        return new Mat2f(
                m00 * b.m00 + m10 * b.m01,
                m01 * b.m00 + m11 * b.m01,
                m00 * b.m10 + m10 * b.m11,
                m01 * b.m10 + m11 * b.m11);
    }

    /**
     * Applies the matrix to a column vector.
     *
     * @param v the vector; must not be {@code null}
     * @return the matrix times the vector {@code v}
     */
    public Vec2f transform(Vec2f v) {
        return new Vec2f(m00 * v.x() + m10 * v.y(), m01 * v.x() + m11 * v.y());
    }

    /**
     * Swaps rows and columns; the inverse of a rotation matrix.
     *
     * @return the transpose: rows and columns exchanged
     */
    public Mat2f transpose() {
        return new Mat2f(m00, m10, m01, m11);
    }

    /**
     * Computes the determinant, whose sign tells whether the transform mirrors and whose magnitude
     * is the area scale; a determinant of zero means the matrix is singular.
     *
     * @return the determinant: the factor by which the matrix scales areas, negative for a
     *     reflection
     */
    public float determinant() {
        return m00 * m11 - m10 * m01;
    }

    /**
     * Sums the diagonal elements.
     *
     * @return the trace, the sum of the diagonal
     */
    public float trace() {
        return m00 + m11;
    }

    /**
     * Computes the adjugate (the transposed cofactor matrix), which is the inverse scaled by the
     * determinant; unlike the inverse it is also defined for singular matrices, and it needs no
     * division.
     *
     * @return the adjugate (the transposed cofactor matrix): the inverse times the determinant; it
     *     exists even when the matrix is singular
     */
    public Mat2f adjugate() {
        return new Mat2f(m11, -m01, -m10, m00);
    }

    /**
     * Inverts the matrix with the closed-form 2x2 formula through the determinant.
     *
     * <p>A singular matrix yields non-finite components.
     *
     * @return general inverse
     */
    public Mat2f invert() {
        float inv = 1f / determinant();
        return new Mat2f(m11 * inv, -m01 * inv, -m10 * inv, m00 * inv);
    }

    /**
     * Derives the matrix for transforming normals, the inverse transpose, which keeps normals
     * perpendicular to the surface under non-uniform scale.
     *
     * @return inverse-transpose: transforms normals correctly under non-uniform scale
     */
    public Mat2f normal() {
        return invert().transpose();
    }

    /**
     * Extracts the rotation angle from the first column with {@code atan2}; meaningful only when
     * the matrix is a pure rotation, since scale and shear are ignored.
     *
     * <p>Exact for a rotation, possibly with a uniform scale.
     *
     * @return the angle in radians of the rotation part, as the direction of the first column:
     *     {@code atan2(m01, m00)}
     */
    public float rotationAngle() {
        return (float) Math.atan2(m01, m00);
    }

    /**
     * Tests whether the columns are perpendicular and of unit length within a tolerance, which
     * holds for rotations and for rotations combined with a mirror.
     *
     * @param eps the tolerance
     * @return {@code true} when the columns are perpendicular unit vectors within {@code eps}: a
     *     rotation or, when the determinant is negative, a rotation with a reflection
     */
    public boolean isOrthonormal(float eps) {
        return Math.abs(m00 * m00 + m01 * m01 - 1f) <= eps && Math.abs(m10 * m10 + m11 * m11 - 1f) <= eps && Math.abs(m00 * m10 + m01 * m11) <= eps;
    }

    /**
     * Extracts one column as a vector; allocates a vector.
     *
     * @param c the column index
     * @return column {@code c} (0 or 1)
     * @throws IndexOutOfBoundsException if {@code c} is not a column index
     */
    public Vec2f column(int c) {
        return switch (c) {
            case 0 -> new Vec2f(m00, m01);
            case 1 -> new Vec2f(m10, m11);
            default -> throw new IndexOutOfBoundsException(c);
        };
    }

    /**
     * Extracts one row as a vector; allocates a vector.
     *
     * @param r the row index
     * @return row {@code r} (0 or 1)
     * @throws IndexOutOfBoundsException if {@code r} is not a row index
     */
    public Vec2f row(int r) {
        return switch (r) {
            case 0 -> new Vec2f(m00, m10);
            case 1 -> new Vec2f(m01, m11);
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
        if (row < 0 || row > 1) {
            throw new IndexOutOfBoundsException(column + "," + row);
        }
        return switch (column * 2 + row) {
            case 0 -> m00;
            case 1 -> m01;
            case 2 -> m10;
            case 3 -> m11;
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
        return Float.isFinite(m00) && Float.isFinite(m01) && Float.isFinite(m10) && Float.isFinite(m11);
    }

    /**
     * Compares two matrices element by element with an absolute tolerance, for tests and for
     * detecting changes; not a scale-relative comparison.
     *
     * @param o the other matrix; must not be {@code null}
     * @param eps the tolerance
     * @return {@code true} when every element differs from that of {@code o} by at most {@code eps}
     */
    public boolean approxEquals(Mat2f o, float eps) {
        return Math.abs(m00 - o.m00) <= eps && Math.abs(m01 - o.m01) <= eps && Math.abs(m10 - o.m10) <= eps && Math.abs(m11 - o.m11) <= eps;
    }

    /**
     * Writes 4 tightly packed column-major values.
     *
     * <p>For std140 uniforms use {@code vmath.gl.GpuWriter} or a generated {@code @GpuStruct}
     * writer (a {@code mat2} column is padded to 16 bytes there).
     *
     * @param dst receives the result
     * @param off the index of the first element to read or write
     */
    public void writeTo(float[] dst, int off) {
        dst[off] = m00;
        dst[off + 1] = m01;
        dst[off + 2] = m10;
        dst[off + 3] = m11;
    }

    /**
     * Writes the 4 column-major values at the absolute position {@code index}; does not change the
     * buffer position.
     *
     * @param dst receives the result; must not be {@code null}
     * @param index the index
     */
    public void writeTo(FloatBuffer dst, int index) {
        dst.put(index, m00).put(index + 1, m01).put(index + 2, m10).put(index + 3, m11);
    }

    /**
     * Converts the elements to {@code double}, which is exact.
     *
     * @return the same value with double components
     */
    @FloatOnly
    public Mat2d toDouble() {
        return new Mat2d(m00, m01, m10, m11);
    }

    /**
     * Converts the elements to {@code float}, which rounds values that need more precision.
     *
     * @return the same value with float components (rounded to the nearest float for double types)
     */
    @DoubleOnly
    public Mat2f toFloat() {
        return new Mat2f((float) m00, (float) m01, (float) m10, (float) m11);
    }
}
