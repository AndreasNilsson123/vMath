package vmath.core;

import java.nio.FloatBuffer;
import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;

/**
 * Affine transform as a 3x4 float matrix (three rows, four columns), column-major: the last row of
 * a full 4x4 matrix, always {@code (0, 0, 0, 1)} for a model matrix, is simply not stored.
 *
 * <p>Twelve floats instead of sixteen means a quarter less memory and upload bandwidth per object,
 * and cheaper multiply and invert.
 *
 * <p>Component {@code mCR} is column {@code C}, row {@code R}, as in {@link Mat4f}; the translation
 * is {@code m30, m31, m32}. Use it for anything that is only translated, rotated, scaled and
 * sheared. Projections need {@link Mat4f}.
 *
 * <p>Valhalla: {@code -Pvalhalla} builds turn {@code @ValueType} into a real {@code value record}.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Transformf transform = new Transformf(new Vec3f(1f, 2f, 3f), Quatf.IDENTITY, Vec3f.ONE);
 * Mat4x3f affine = transform.toMat4x3();                  // 48 bytes instead of 64
 * Vec3f p = affine.transformPosition(Vec3f.ZERO);         // (1, 2, 3)
 * Mat4f full = affine.toMat4();
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
 * @param m30 the m30
 * @param m31 the m31
 * @param m32 the m32
 */
@GenerateDouble
@ValueType
public record Mat4x3f(
        float m00, float m01, float m02,
        float m10, float m11, float m12,
        float m20, float m21, float m22,
        float m30, float m31, float m32) {

    /**
     * The identity matrix.
     */
    public static final Mat4x3f IDENTITY = new Mat4x3f(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
            0f, 0f, 0f);

    /**
     * Drops the last row of {@code m}, which must be {@code (0, 0, 0, 1)} for the result to mean
     * the same thing.
     *
     * @param m the matrix; must not be {@code null}
     * @return the matrix without its last row, never {@code null}
     */
    public static Mat4x3f fromMat4(Mat4f m) {
        return new Mat4x3f(
                m.m00(), m.m01(), m.m02(),
                m.m10(), m.m11(), m.m12(),
                m.m20(), m.m21(), m.m22(),
                m.m30(), m.m31(), m.m32());
    }

    /**
     * Reads 12 column-major values starting at {@code off}.
     *
     * @param src the source to read from
     * @param off the index of the first element to read or write
     * @return the matrix, never {@code null}
     */
    public static Mat4x3f fromArray(float[] src, int off) {
        return new Mat4x3f(
                src[off], src[off + 1], src[off + 2],
                src[off + 3], src[off + 4], src[off + 5],
                src[off + 6], src[off + 7], src[off + 8],
                src[off + 9], src[off + 10], src[off + 11]);
    }

    /**
     * Builds a 4x3 affine matrix from its four column vectors, the last one being the translation.
     *
     * @param c0 the vector; must not be {@code null}
     * @param c1 the vector; must not be {@code null}
     * @param c2 the vector; must not be {@code null}
     * @param translation the translation; must not be {@code null}
     * @return the matrix with the given columns
     */
    public static Mat4x3f fromColumns(Vec3f c0, Vec3f c1, Vec3f c2, Vec3f translation) {
        return new Mat4x3f(
                c0.x(), c0.y(), c0.z(),
                c1.x(), c1.y(), c1.z(),
                c2.x(), c2.y(), c2.z(),
                translation.x(), translation.y(), translation.z());
    }

    /**
     * Builds a translation.
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @return a translation by the given offset
     */
    public static Mat4x3f translation(float x, float y, float z) {
        return new Mat4x3f(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f,
                x, y, z);
    }

    /**
     * Builds a translation from a vector.
     *
     * @param t the vector; must not be {@code null}
     * @return a translation by the given offset
     */
    public static Mat4x3f translation(Vec3f t) {
        return translation(t.x(), t.y(), t.z());
    }

    /**
     * Builds a non-uniform scale matrix.
     *
     * @param sx the scale along x
     * @param sy the scale along y
     * @param sz the scale along z
     * @return a scale by the given factors along each axis
     */
    public static Mat4x3f scaling(float sx, float sy, float sz) {
        return new Mat4x3f(
                sx, 0f, 0f,
                0f, sy, 0f,
                0f, 0f, sz,
                0f, 0f, 0f);
    }

    /**
     * Converts a unit quaternion to the equivalent rotation matrix; the quaternion is assumed to be
     * normalised, and a non-unit one yields a scaled matrix.
     *
     * @param q the quaternion; must not be {@code null}
     * @return rotation matrix for a unit quaternion
     */
    public static Mat4x3f rotation(Quatf q) {
        return translationRotateScale(Vec3f.ZERO, q, Vec3f.ONE);
    }

    /**
     * Builds a rotation about the X axis, using the right-handed convention.
     *
     * @param angle the angle in radians
     * @return rotation about the X axis by {@code angle} radians (right-handed, counter-clockwise
     *     seen from +X)
     */
    public static Mat4x3f rotationX(float angle) {
        return ofRotation(Mat3f.rotationX(angle));
    }

    /**
     * Builds a rotation about the Y axis, using the right-handed convention.
     *
     * @param angle the angle in radians
     * @return rotation about the Y axis by {@code angle} radians
     */
    public static Mat4x3f rotationY(float angle) {
        return ofRotation(Mat3f.rotationY(angle));
    }

    /**
     * Builds a rotation about the Z axis, using the right-handed convention.
     *
     * @param angle the angle in radians
     * @return rotation about the Z axis by {@code angle} radians
     */
    public static Mat4x3f rotationZ(float angle) {
        return ofRotation(Mat3f.rotationZ(angle));
    }

    /**
     * Builds a rotation about an arbitrary axis; the axis must have unit length, as it is not
     * normalised here.
     *
     * @param angle the angle in radians
     * @param axis the axis; must not be {@code null}
     * @return rotation about a unit {@code axis} by {@code angle} radians
     */
    public static Mat4x3f rotationAxis(float angle, Vec3f axis) {
        return ofRotation(Mat3f.rotationAxis(angle, axis));
    }

    private static Mat4x3f ofRotation(Mat3f r) {
        return new Mat4x3f(
                r.m00(), r.m01(), r.m02(),
                r.m10(), r.m11(), r.m12(),
                r.m20(), r.m21(), r.m22(),
                0f, 0f, 0f);
    }

    /**
     * Transforms a homogeneous vector: the translation is scaled by {@code v.w}, and {@code w} is
     * returned unchanged (the bottom row is 0, 0, 0, 1).
     *
     * @param v the vector; must not be {@code null}
     * @return the transformed vector, never {@code null}
     */
    public Vec4f transform(Vec4f v) {
        return new Vec4f(
                m00 * v.x() + m10 * v.y() + m20 * v.z() + m30 * v.w(),
                m01 * v.x() + m11 * v.y() + m21 * v.z() + m31 * v.w(),
                m02 * v.x() + m12 * v.y() + m22 * v.z() + m32 * v.w(),
                v.w());
    }

    /**
     * Composes a model matrix in the usual order: scale first, then rotate, then translate; cheaper
     * than three matrix products.
     *
     * @param t the vector; must not be {@code null}
     * @param q the quaternion; must not be {@code null}
     * @param s the vector; must not be {@code null}
     * @return model matrix {@code T * R * S}: scales first, then rotates, then translates
     */
    public static Mat4x3f translationRotateScale(Vec3f t, Quatf q, Vec3f s) {
        Mat3f r = Mat3f.rotation(q);
        float sx = s.x(), sy = s.y(), sz = s.z();
        return new Mat4x3f(
                r.m00() * sx, r.m01() * sx, r.m02() * sx,
                r.m10() * sy, r.m11() * sy, r.m12() * sy,
                r.m20() * sz, r.m21() * sz, r.m22() * sz,
                t.x(), t.y(), t.z());
    }

    /**
     * Expands the affine matrix to 4x4 by adding the row {@code 0 0 0 1}, as needed for projection
     * or for APIs that want a square matrix.
     *
     * @return the equivalent 4x4 matrix, with bottom row {@code (0, 0, 0, 1)}
     */
    public Mat4f toMat4() {
        return new Mat4f(
                m00, m01, m02, 0f,
                m10, m11, m12, 0f,
                m20, m21, m22, 0f,
                m30, m31, m32, 1f);
    }

    // ---------------------------------------------------------------- algebra

    /**
     * Composes two affine transforms by multiplication; the product is not commutative, the right
     * operand acts on the point first, and the result stays affine.
     *
     * <p>Four small column products rather than one 12-expression body, so the JIT can inline it
     * and keep chains allocation-free (see {@link Mat4f#mul}).
     *
     * @param b the second matrix; must not be {@code null}
     * @return composition {@code this * b}: transforming by the result applies {@code b} first
     */
    public Mat4x3f mul(Mat4x3f b) {
        Vec3f c0 = mulLinear(b.m00, b.m01, b.m02);
        Vec3f c1 = mulLinear(b.m10, b.m11, b.m12);
        Vec3f c2 = mulLinear(b.m20, b.m21, b.m22);
        Vec3f c3 = mulLinear(b.m30, b.m31, b.m32);
        return new Mat4x3f(
                c0.x(), c0.y(), c0.z(),
                c1.x(), c1.y(), c1.z(),
                c2.x(), c2.y(), c2.z(),
                c3.x() + m30, c3.y() + m31, c3.z() + m32);
    }

    /**
     * Composes with a full 4x4 matrix such as a projection; the result is a full matrix.
     *
     * @param b the second matrix; must not be {@code null}
     * @return composition with a full matrix; the result is a full matrix
     */
    public Mat4f mul(Mat4f b) {
        return toMat4().mul(b);
    }

    /**
     * The 3x3 part applied to {@code (x, y, z)}: no translation.
     */
    private Vec3f mulLinear(float x, float y, float z) {
        return new Vec3f(
                m00 * x + m10 * y + m20 * z,
                m01 * x + m11 * y + m21 * z,
                m02 * x + m12 * y + m22 * z);
    }

    /**
     * Transforms a point: the 3x3 part is applied and then the translation is added.
     *
     * @param p the vector; must not be {@code null}
     * @return the point {@code p} transformed by this matrix: scaled, rotated and translated
     */
    public Vec3f transformPosition(Vec3f p) {
        return new Vec3f(
                m00 * p.x() + m10 * p.y() + m20 * p.z() + m30,
                m01 * p.x() + m11 * p.y() + m21 * p.z() + m31,
                m02 * p.x() + m12 * p.y() + m22 * p.z() + m32);
    }

    /**
     * Transforms a direction: rotation and scale, no translation.
     *
     * @param d the vector; must not be {@code null}
     * @return the transformed direction, never {@code null}
     */
    public Vec3f transformDirection(Vec3f d) {
        return mulLinear(d.x(), d.y(), d.z());
    }

    /**
     * Computes the determinant of the 3x3 part; the translation does not affect it.
     *
     * @return determinant of the 3x3 part: negative for mirrored transforms, zero for singular ones
     */
    public float determinant() {
        return m00 * (m11 * m22 - m21 * m12) - m10 * (m01 * m22 - m21 * m02) + m20 * (m01 * m12 - m11 * m02);
    }

    /**
     * Inverts the affine transform from the inverse of its 3x3 part.
     *
     * <p>The matrix must be invertible (nonzero {@link #determinant()}); a singular matrix yields
     * infinite or NaN components.
     *
     * @return inverse transform
     */
    public Mat4x3f invert() {
        float a = m11 * m22 - m21 * m12;
        float b = m21 * m02 - m01 * m22;
        float c = m01 * m12 - m11 * m02;
        float inv = 1f / (m00 * a + m10 * b + m20 * c);
        float i00 = a * inv;
        float i01 = b * inv;
        float i02 = c * inv;
        float i10 = (m20 * m12 - m10 * m22) * inv;
        float i11 = (m00 * m22 - m20 * m02) * inv;
        float i12 = (m10 * m02 - m00 * m12) * inv;
        float i20 = (m10 * m21 - m20 * m11) * inv;
        float i21 = (m20 * m01 - m00 * m21) * inv;
        float i22 = (m00 * m11 - m10 * m01) * inv;
        return new Mat4x3f(
                i00, i01, i02,
                i10, i11, i12,
                i20, i21, i22,
                -(i00 * m30 + i10 * m31 + i20 * m32),
                -(i01 * m30 + i11 * m31 + i21 * m32),
                -(i02 * m30 + i12 * m31 + i22 * m32));
    }

    /**
     * Extracts the 3x3 block that holds rotation, scale and shear, dropping the translation.
     *
     * @return the upper-left 3x3 block: the rotation and scale part
     */
    public Mat3f upperLeft3x3() {
        return new Mat3f(
                m00, m01, m02,
                m10, m11, m12,
                m20, m21, m22);
    }

    /**
     * Derives the matrix for transforming normals, the inverse transpose of the 3x3 part, which
     * keeps normals perpendicular to the surface under non-uniform scale.
     *
     * @return inverse-transpose of the 3x3 part: the matrix for transforming normals
     */
    public Mat3f normalMatrix() {
        return upperLeft3x3().normal();
    }

    /**
     * Extracts the translation from the last column.
     *
     * @return the translation: the x, y and z of the last column
     */
    public Vec3f getTranslation() {
        return new Vec3f(m30, m31, m32);
    }

    /**
     * Replaces the translation and leaves the 3x3 part unchanged; the original is not modified.
     *
     * @param t the vector; must not be {@code null}
     * @return a copy with the translation replaced
     */
    public Mat4x3f withTranslation(Vec3f t) {
        return new Mat4x3f(m00, m01, m02, m10, m11, m12, m20, m21, m22, t.x(), t.y(), t.z());
    }

    /**
     * Splits into translation, rotation and scale as {@link Mat4f#decompose()} does.
     *
     * @return the translation, rotation and scale, never {@code null}
     */
    public Mat4f.Trs decompose() {
        return toMat4().decompose();
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
            case 3 -> new Vec3f(m30, m31, m32);
            default -> throw new IndexOutOfBoundsException(c);
        };
    }

    /**
     * Reads one element by column and row index; out-of-range indices are rejected.
     *
     * @param column the column, counted from 0
     * @param row the row, counted from 0
     * @return the element at {@code column} and {@code row}
     */
    public float get(int column, int row) {
        return column(column).get(row);
    }

    /**
     * Compares two matrices element by element with an absolute tolerance, for tests and for
     * detecting changes; not a scale-relative comparison.
     *
     * @param o the other matrix; must not be {@code null}
     * @param eps the tolerance
     * @return {@code true} when every element differs from that of {@code o} by at most {@code eps}
     */
    public boolean approxEquals(Mat4x3f o, float eps) {
        for (int c = 0; c < 4; c++) {
            if (!column(c).approxEquals(o.column(c), eps)) {
                return false;
            }
        }
        return true;
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
                && Float.isFinite(m20) && Float.isFinite(m21) && Float.isFinite(m22)
                && Float.isFinite(m30) && Float.isFinite(m31) && Float.isFinite(m32);
    }

    // ---------------------------------------------------------------- output

    /**
     * Writes 12 floats, column-major: column 0, 1, 2, then the translation.
     *
     * @param dst receives the result
     * @param off the index of the first element to read or write
     */
    public void writeTo(float[] dst, int off) {
        dst[off] = m00;
        dst[off + 1] = m01;
        dst[off + 2] = m02;
        dst[off + 3] = m10;
        dst[off + 4] = m11;
        dst[off + 5] = m12;
        dst[off + 6] = m20;
        dst[off + 7] = m21;
        dst[off + 8] = m22;
        dst[off + 9] = m30;
        dst[off + 10] = m31;
        dst[off + 11] = m32;
    }

    /**
     * Writes the 12 column-major values at the absolute position {@code index}; does not change the
     * buffer position.
     *
     * @param dst receives the result; must not be {@code null}
     * @param index the index
     */
    public void writeTo(FloatBuffer dst, int index) {
        dst.put(index, m00).put(index + 1, m01).put(index + 2, m02)
                .put(index + 3, m10).put(index + 4, m11).put(index + 5, m12)
                .put(index + 6, m20).put(index + 7, m21).put(index + 8, m22)
                .put(index + 9, m30).put(index + 10, m31).put(index + 11, m32);
    }

    /**
     * Writes the GPU-friendly "three vec4 rows" layout (12 floats, 48 bytes): row 0 is
     * {@code (m00, m10, m20, m30)}, then rows 1 and 2.
     *
     * <p>A shader reads it as {@code mat3x4} / three {@code vec4}s and computes each output
     * component with a single dot product against {@code vec4(p, 1)}.
     *
     * @param dst receives the result
     * @param off the index of the first element to read or write
     */
    public void writeRows(float[] dst, int off) {
        dst[off] = m00;
        dst[off + 1] = m10;
        dst[off + 2] = m20;
        dst[off + 3] = m30;
        dst[off + 4] = m01;
        dst[off + 5] = m11;
        dst[off + 6] = m21;
        dst[off + 7] = m31;
        dst[off + 8] = m02;
        dst[off + 9] = m12;
        dst[off + 10] = m22;
        dst[off + 11] = m32;
    }

    /**
     * Converts the elements to {@code double}, which is exact.
     *
     * @return the same value with double components
     */
    @FloatOnly
    public Mat4x3d toDouble() {
        return new Mat4x3d(m00, m01, m02, m10, m11, m12, m20, m21, m22, m30, m31, m32);
    }

    /**
     * Converts the elements to {@code float}, which rounds values that need more precision.
     *
     * @return the same value with float components (rounded to the nearest float for double types)
     */
    @DoubleOnly
    public Mat4x3f toFloat() {
        return new Mat4x3f((float) m00, (float) m01, (float) m02, (float) m10, (float) m11, (float) m12,
                (float) m20, (float) m21, (float) m22, (float) m30, (float) m31, (float) m32);
    }
}
