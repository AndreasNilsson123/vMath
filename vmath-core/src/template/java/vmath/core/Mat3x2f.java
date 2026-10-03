package vmath.core;

import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import java.nio.FloatBuffer;

/**
 * Immutable 2D affine transform: a 2x2 linear part and a translation, stored as three columns of
 * two rows (24 bytes instead of the 36 of a {@link Mat3f}), the 2D counterpart of {@link Mat4x3f}.
 *
 * <p>Use it for sprites, UI layouts, 2D cameras and texture-coordinate transforms; it is exactly
 * the homogeneous 3x3 matrix whose last row is {@code 0 0 1} ({@link #toMat3()}).
 *
 * <p>Component {@code mCR} is column {@code C}, row {@code R}: the linear part is
 * {@code m00, m01, m10, m11} (the columns the x and y axes map to) and the translation is
 * {@code m20, m21}. The canonical constructor takes the components in memory order, which is the
 * layout of the GLSL {@code mat3x2}.
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
 * Mat3x2f model = Mat3x2f.translationRotateScale(new Vec2f(10f, 5f), 0.5f, new Vec2f(2f, 2f));
 * Vec2f p = model.transformPosition(new Vec2f(1f, 0f));
 * Vec2f d = model.transformDirection(new Vec2f(1f, 0f));
 * Mat3x2f back = model.invert();
 * }</pre>
 *
 * @param m00 the m00
 * @param m01 the m01
 * @param m10 the m10
 * @param m11 the m11
 * @param m20 the m20
 * @param m21 the m21
 */
@GenerateDouble
@ValueType
public record Mat3x2f(float m00, float m01, float m10, float m11, float m20, float m21) {

    /**
     * The identity transform.
     */
    public static final Mat3x2f IDENTITY = new Mat3x2f(1f, 0f, 0f, 1f, 0f, 0f);

    /**
     * Builds an affine 2D transform from its linear columns and translation column.
     *
     * @param c0 the vector; must not be {@code null}
     * @param c1 the vector; must not be {@code null}
     * @param translation the translation; must not be {@code null}
     * @return the transform with the given linear columns and translation
     */
    public static Mat3x2f fromColumns(Vec2f c0, Vec2f c1, Vec2f translation) {
        return new Mat3x2f(c0.x(), c0.y(), c1.x(), c1.y(), translation.x(), translation.y());
    }

    /**
     * Reads an affine 2D transform from an array in column-major order, the order {@link #writeTo}
     * writes.
     *
     * @param src the source to read from
     * @param off the index of the first element to read or write
     * @return the transform stored column-major in {@code src[off..off+5]} (the order
     *     {@link #writeTo} writes)
     */
    public static Mat3x2f fromArray(float[] src, int off) {
        return new Mat3x2f(src[off], src[off + 1], src[off + 2], src[off + 3], src[off + 4], src[off + 5]);
    }

    /**
     * Embeds a 2x2 linear map as an affine transform with zero translation.
     *
     * @param m the matrix; must not be {@code null}
     * @return the transform with the linear part {@code m} and no translation
     */
    public static Mat3x2f fromMat2(Mat2f m) {
        return new Mat3x2f(m.m00(), m.m01(), m.m10(), m.m11(), 0f, 0f);
    }

    /**
     * Combines a 2x2 linear map and a translation into one affine transform.
     *
     * @param m the matrix; must not be {@code null}
     * @param t the vector; must not be {@code null}
     * @return the transform with the linear part {@code m} followed by the translation {@code t}
     */
    public static Mat3x2f fromMat2(Mat2f m, Vec2f t) {
        return new Mat3x2f(m.m00(), m.m01(), m.m10(), m.m11(), t.x(), t.y());
    }

    /**
     * Drops the projective row of a homogeneous 3x3 matrix and keeps the affine part; the result is
     * only equivalent when that row was {@code 0 0 1}.
     *
     * @param m the matrix; must not be {@code null}
     * @return the affine part of a homogeneous matrix: its last row is ignored
     */
    public static Mat3x2f fromMat3(Mat3f m) {
        return new Mat3x2f(m.m00(), m.m01(), m.m10(), m.m11(), m.m20(), m.m21());
    }

    /**
     * Builds a translation.
     *
     * @param x the x component
     * @param y the y component
     * @return a translation by {@code (x, y)}
     */
    public static Mat3x2f translation(float x, float y) {
        return new Mat3x2f(1f, 0f, 0f, 1f, x, y);
    }

    /**
     * Builds a translation from a vector.
     *
     * @param t the vector; must not be {@code null}
     * @return a translation by {@code t}
     */
    public static Mat3x2f translation(Vec2f t) {
        return new Mat3x2f(1f, 0f, 0f, 1f, t.x(), t.y());
    }

    /**
     * Builds a non-uniform scale about the origin.
     *
     * @param sx the scale along x
     * @param sy the scale along y
     * @return a scale about the origin by the given factors along each axis
     */
    public static Mat3x2f scaling(float sx, float sy) {
        return new Mat3x2f(sx, 0f, 0f, sy, 0f, 0f);
    }

    /**
     * Builds a uniform scale about the origin.
     *
     * @param s the scale factor
     * @return a uniform scale about the origin
     */
    public static Mat3x2f scaling(float s) {
        return new Mat3x2f(s, 0f, 0f, s, 0f, 0f);
    }

    /**
     * Builds a rotation about the origin, turning counter-clockwise for a y-up coordinate system.
     *
     * @param angle the angle in radians
     * @return a counter-clockwise rotation about the origin by {@code angle} radians
     */
    public static Mat3x2f rotation(float angle) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        return new Mat3x2f(c, s, -s, c, 0f, 0f);
    }

    /**
     * Builds a rotation about an arbitrary pivot by conjugating a rotation with translations, so
     * the pivot is a fixed point.
     *
     * @param angle the angle in radians
     * @param pivot the pivot; must not be {@code null}
     * @return a counter-clockwise rotation about the point {@code pivot} by {@code angle} radians:
     *     the pivot stays where it is
     */
    public static Mat3x2f rotationAround(float angle, Vec2f pivot) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        return new Mat3x2f(c, s, -s, c, pivot.x() - c * pivot.x() + s * pivot.y(), pivot.y() - s * pivot.x() - c * pivot.y());
    }

    /**
     * Composes a 2D model transform in the usual order: scale first, then rotate, then translate.
     *
     * @param t the vector; must not be {@code null}
     * @param angle the angle in radians
     * @param s the vector; must not be {@code null}
     * @return the transform {@code T * R * S}: scale by {@code s}, rotate counter-clockwise by
     *     {@code angle} radians, then translate by {@code t}
     */
    public static Mat3x2f translationRotateScale(Vec2f t, float angle, Vec2f s) {
        float c = (float) Math.cos(angle), sn = (float) Math.sin(angle);
        return new Mat3x2f(c * s.x(), sn * s.x(), -sn * s.y(), c * s.y(), t.x(), t.y());
    }

    /**
     * Composes a 2D model transform with shear: scale first, then shear, rotate, and translate; the
     * shear acts before the rotation, so it stays aligned with the object.
     *
     * <p>With zero shear it equals {@link #translationRotateScale}; {@link #decomposeWithShear()}
     * is its inverse.
     *
     * @param t the vector; must not be {@code null}
     * @param angle the angle in radians
     * @param shear the shear
     * @param s the vector; must not be {@code null}
     * @return the transform {@code T * R * Sh * S}: scale by {@code s}, shear {@code (x, y)} to
     *     {@code (x + shear y, y)}, rotate by {@code angle} and translate by {@code t}
     */
    public static Mat3x2f translationRotateShearScale(Vec2f t, float angle, float shear, Vec2f s) {
        float c = (float) Math.cos(angle), sn = (float) Math.sin(angle);
        float u10 = shear * s.y();
        return new Mat3x2f(c * s.x(), sn * s.x(), c * u10 - sn * s.y(), sn * u10 + c * s.y(), t.x(), t.y());
    }

    /**
     * Result of {@link #decompose()}.
     *
     * @param translation the translation; must not be {@code null}
     * @param rotation the rotation
     * @param scale the scale; must not be {@code null}
     */
    @ValueType
    public record Trs(Vec2f translation, float rotation, Vec2f scale) {
    }

    /**
     * Result of {@link #decomposeWithShear()}.
     *
     * @param translation the translation; must not be {@code null}
     * @param rotation the rotation
     * @param scale the scale; must not be {@code null}
     * @param shear the shear
     */
    @ValueType
    public record ShearDecomposition(Vec2f translation, float rotation, Vec2f scale, float shear) {
    }

    /**
     * Composes two affine transforms by multiplication; the product is not commutative, and the
     * right operand acts on the point first.
     *
     * @param b the second matrix; must not be {@code null}
     * @return matrix product {@code this * b}: transforming by the result applies {@code b} first
     */
    public Mat3x2f mul(Mat3x2f b) {
        return new Mat3x2f(
                m00 * b.m00 + m10 * b.m01,
                m01 * b.m00 + m11 * b.m01,
                m00 * b.m10 + m10 * b.m11,
                m01 * b.m10 + m11 * b.m11,
                m00 * b.m20 + m10 * b.m21 + m20,
                m01 * b.m20 + m11 * b.m21 + m21);
    }

    /**
     * Transforms a point: the linear part is applied and then the translation is added.
     *
     * @param p the vector; must not be {@code null}
     * @return the transformed point: the linear part applied, then the translation added
     */
    public Vec2f transformPosition(Vec2f p) {
        return new Vec2f(m00 * p.x() + m10 * p.y() + m20, m01 * p.x() + m11 * p.y() + m21);
    }

    /**
     * Transforms a direction: only the linear part applies, so translations do not move direction
     * vectors.
     *
     * @param d the vector; must not be {@code null}
     * @return the transformed direction: the linear part only, the translation does not apply to
     *     vectors
     */
    public Vec2f transformDirection(Vec2f d) {
        return new Vec2f(m00 * d.x() + m10 * d.y(), m01 * d.x() + m11 * d.y());
    }

    /**
     * Computes the determinant of the linear part; the translation does not affect it.
     *
     * @return the determinant of the linear part: the factor by which areas scale, negative when
     *     the transform mirrors
     */
    public float determinant() {
        return m00 * m11 - m10 * m01;
    }

    /**
     * Inverts the affine transform from the inverse of its linear part, without building a full 3x3
     * inverse.
     *
     * <p>A transform whose linear part is singular yields non-finite components.
     *
     * @return general inverse
     */
    public Mat3x2f invert() {
        float inv = 1f / determinant();
        float i00 = m11 * inv, i01 = -m01 * inv, i10 = -m10 * inv, i11 = m00 * inv;
        return new Mat3x2f(i00, i01, i10, i11, -(i00 * m20 + i10 * m21), -(i01 * m20 + i11 * m21));
    }

    /**
     * Extracts the linear part as a 2x2 matrix, dropping the translation.
     *
     * @return the linear part as a 2x2 matrix
     */
    public Mat2f linear() {
        return new Mat2f(m00, m01, m10, m11);
    }

    /**
     * Extracts the translation column.
     *
     * @return the translation
     */
    public Vec2f getTranslation() {
        return new Vec2f(m20, m21);
    }

    /**
     * Converts the affine transform to a homogeneous 3x3 matrix by appending the row {@code 0 0 1}.
     *
     * @return the equivalent homogeneous 3x3 matrix, with the last row {@code 0 0 1}
     */
    public Mat3f toMat3() {
        return new Mat3f(m00, m01, 0f, m10, m11, 0f, m20, m21, 1f);
    }

    /**
     * Splits the transform {@code T * R * S} back into translation, rotation (radians,
     * counter-clockwise) and scale.
     *
     * <p>A mirrored transform folds the reflection into a negative x scale. Shear is not
     * represented: for a sheared transform the result recomposes to a different one (see
     * {@link #decomposeWithShear()}). A zero-scale axis yields NaN.
     *
     * @return the translation, rotation (radians, counter-clockwise) and scale, never {@code null}
     */
    public Trs decompose() {
        float sx = (float) Math.sqrt(m00 * m00 + m01 * m01);
        float sy = (float) Math.sqrt(m10 * m10 + m11 * m11);
        if (determinant() < 0f) {
            sx = -sx;
        }
        return new Trs(getTranslation(), (float) Math.atan2(m01 / sx, m00 / sx), new Vec2f(sx, sy));
    }

    /**
     * Splits the transform into translation, rotation, shear and scale such that
     * {@link #translationRotateShearScale} recomposes to it exactly, also when it is sheared (a
     * non-uniform scale below a rotation shears).
     *
     * <p>It is the Gram-Schmidt factorisation of the linear part; a mirror is folded into a
     * negative x scale. A zero-scale axis yields NaN.
     *
     * @return the translation, rotation, shear and scale, never {@code null}
     */
    public ShearDecomposition decomposeWithShear() {
        float sx = (float) Math.sqrt(m00 * m00 + m01 * m01);
        float r0x = m00 / sx, r0y = m01 / sx;
        float u01 = r0x * m10 + r0y * m11;
        float c1x = m10 - r0x * u01, c1y = m11 - r0y * u01;
        float sy = (float) Math.sqrt(c1x * c1x + c1y * c1y);
        float r1x = c1x / sy, r1y = c1y / sy;
        // a left-handed frame is a reflection: fold it into a negative x scale, which flips the first axis of the rotation
        if (r0x * r1y - r0y * r1x < 0f) {
            sx = -sx;
            r0x = -r0x;
            r0y = -r0y;
            u01 = -u01;
        }
        return new ShearDecomposition(getTranslation(), (float) Math.atan2(r0y, r0x), new Vec2f(sx, sy), u01 / sy);
    }

    /**
     * Extracts one column as a vector; allocates a vector.
     *
     * @param c the column index
     * @return column {@code c} (0 and 1 are the linear columns, 2 is the translation)
     * @throws IndexOutOfBoundsException if {@code c} is not a column index
     */
    public Vec2f column(int c) {
        return switch (c) {
            case 0 -> new Vec2f(m00, m01);
            case 1 -> new Vec2f(m10, m11);
            case 2 -> new Vec2f(m20, m21);
            default -> throw new IndexOutOfBoundsException(c);
        };
    }

    /**
     * Extracts one row as a vector; allocates a vector.
     *
     * @param r the row index
     * @return row {@code r} (0 or 1) as the three elements across the columns
     * @throws IndexOutOfBoundsException if {@code r} is not a row index
     */
    public Vec3f row(int r) {
        return switch (r) {
            case 0 -> new Vec3f(m00, m10, m20);
            case 1 -> new Vec3f(m01, m11, m21);
            default -> throw new IndexOutOfBoundsException(r);
        };
    }

    /**
     * Reads one element by column and row index; out-of-range indices are rejected.
     *
     * @param column the column, counted from 0
     * @param row the row, counted from 0
     * @return the element at {@code column} (0 to 2) and {@code row} (0 or 1)
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
            case 4 -> m20;
            case 5 -> m21;
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
        return Float.isFinite(m00) && Float.isFinite(m01) && Float.isFinite(m10) && Float.isFinite(m11) && Float.isFinite(m20) && Float.isFinite(m21);
    }

    /**
     * Compares two transforms element by element with an absolute tolerance, for tests and for
     * detecting changes; not a scale-relative comparison.
     *
     * @param o the other matrix; must not be {@code null}
     * @param eps the tolerance
     * @return {@code true} when every element differs from that of {@code o} by at most {@code eps}
     */
    public boolean approxEquals(Mat3x2f o, float eps) {
        return Math.abs(m00 - o.m00) <= eps && Math.abs(m01 - o.m01) <= eps && Math.abs(m10 - o.m10) <= eps && Math.abs(m11 - o.m11) <= eps
                && Math.abs(m20 - o.m20) <= eps && Math.abs(m21 - o.m21) <= eps;
    }

    /**
     * Writes 6 tightly packed column-major values (the GLSL {@code mat3x2} memory order, not
     * std140).
     *
     * @param dst receives the result
     * @param off the index of the first element to read or write
     */
    public void writeTo(float[] dst, int off) {
        dst[off] = m00;
        dst[off + 1] = m01;
        dst[off + 2] = m10;
        dst[off + 3] = m11;
        dst[off + 4] = m20;
        dst[off + 5] = m21;
    }

    /**
     * Writes the 6 column-major values at the absolute position {@code index}; does not change the
     * buffer position.
     *
     * @param dst receives the result; must not be {@code null}
     * @param index the index
     */
    public void writeTo(FloatBuffer dst, int index) {
        dst.put(index, m00).put(index + 1, m01).put(index + 2, m10).put(index + 3, m11).put(index + 4, m20).put(index + 5, m21);
    }

    /**
     * Converts the elements to {@code double}, which is exact.
     *
     * @return the same value with double components
     */
    @FloatOnly
    public Mat3x2d toDouble() {
        return new Mat3x2d(m00, m01, m10, m11, m20, m21);
    }

    /**
     * Converts the elements to {@code float}, which rounds values that need more precision.
     *
     * @return the same value with float components (rounded to the nearest float for double types)
     */
    @DoubleOnly
    public Mat3x2f toFloat() {
        return new Mat3x2f((float) m00, (float) m01, (float) m10, (float) m11, (float) m20, (float) m21);
    }
}
