package vmath.core;

import java.nio.FloatBuffer;
import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;

/**
 * Affine transform as a 3x4 float matrix (three rows, four columns), column-major: the last row of a full 4x4 matrix,
 * always {@code (0, 0, 0, 1)} for a model matrix, is simply not stored. Twelve floats instead of sixteen means a
 * quarter less memory and upload bandwidth per object, and cheaper multiply and invert.
 *
 * <p>Component {@code mCR} is column {@code C}, row {@code R}, as in {@link Mat4f}; the translation is
 * {@code m30, m31, m32}. Use it for anything that is only translated, rotated, scaled and sheared. Projections need
 * {@link Mat4f}.
 *
 * <p>Valhalla: {@code -Pvalhalla} builds turn {@code @ValueType} into a real {@code value record}.
 */
@GenerateDouble
@ValueType
public record Mat4x3f(
        float m00, float m01, float m02,
        float m10, float m11, float m12,
        float m20, float m21, float m22,
        float m30, float m31, float m32) {

    /** The identity matrix. */
    public static final Mat4x3f IDENTITY = new Mat4x3f(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
            0f, 0f, 0f);

    /** Drops the last row of {@code m}, which must be {@code (0, 0, 0, 1)} for the result to mean the same thing. */
    public static Mat4x3f fromMat4(Mat4f m) {
        return new Mat4x3f(
                m.m00(), m.m01(), m.m02(),
                m.m10(), m.m11(), m.m12(),
                m.m20(), m.m21(), m.m22(),
                m.m30(), m.m31(), m.m32());
    }

    /** Reads 12 column-major values starting at {@code off}. */
    public static Mat4x3f fromArray(float[] src, int off) {
        return new Mat4x3f(
                src[off], src[off + 1], src[off + 2],
                src[off + 3], src[off + 4], src[off + 5],
                src[off + 6], src[off + 7], src[off + 8],
                src[off + 9], src[off + 10], src[off + 11]);
    }

    /** The matrix with the given columns. */
    public static Mat4x3f fromColumns(Vec3f c0, Vec3f c1, Vec3f c2, Vec3f translation) {
        return new Mat4x3f(
                c0.x(), c0.y(), c0.z(),
                c1.x(), c1.y(), c1.z(),
                c2.x(), c2.y(), c2.z(),
                translation.x(), translation.y(), translation.z());
    }

    /** A translation by the given offset. */
    public static Mat4x3f translation(float x, float y, float z) {
        return new Mat4x3f(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f,
                x, y, z);
    }

    /** A translation by the given offset. */
    public static Mat4x3f translation(Vec3f t) {
        return translation(t.x(), t.y(), t.z());
    }

    /** A scale by the given factors along each axis. */
    public static Mat4x3f scaling(float sx, float sy, float sz) {
        return new Mat4x3f(
                sx, 0f, 0f,
                0f, sy, 0f,
                0f, 0f, sz,
                0f, 0f, 0f);
    }

    /** Rotation matrix for a unit quaternion. */
    public static Mat4x3f rotation(Quatf q) {
        return translationRotateScale(Vec3f.ZERO, q, Vec3f.ONE);
    }

    /** Rotation about the X axis by {@code angle} radians (right-handed, counter-clockwise seen from +X). */
    public static Mat4x3f rotationX(float angle) {
        return ofRotation(Mat3f.rotationX(angle));
    }

    /** Rotation about the Y axis by {@code angle} radians. */
    public static Mat4x3f rotationY(float angle) {
        return ofRotation(Mat3f.rotationY(angle));
    }

    /** Rotation about the Z axis by {@code angle} radians. */
    public static Mat4x3f rotationZ(float angle) {
        return ofRotation(Mat3f.rotationZ(angle));
    }

    /** Rotation about a unit {@code axis} by {@code angle} radians. */
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

    /** Transforms a homogeneous vector: the translation is scaled by {@code v.w}, and {@code w} is returned unchanged (the bottom row is 0, 0, 0, 1). */
    public Vec4f transform(Vec4f v) {
        return new Vec4f(
                m00 * v.x() + m10 * v.y() + m20 * v.z() + m30 * v.w(),
                m01 * v.x() + m11 * v.y() + m21 * v.z() + m31 * v.w(),
                m02 * v.x() + m12 * v.y() + m22 * v.z() + m32 * v.w(),
                v.w());
    }

    /** Model matrix {@code T * R * S}: scales first, then rotates, then translates. */
    public static Mat4x3f translationRotateScale(Vec3f t, Quatf q, Vec3f s) {
        Mat3f r = Mat3f.rotation(q);
        float sx = s.x(), sy = s.y(), sz = s.z();
        return new Mat4x3f(
                r.m00() * sx, r.m01() * sx, r.m02() * sx,
                r.m10() * sy, r.m11() * sy, r.m12() * sy,
                r.m20() * sz, r.m21() * sz, r.m22() * sz,
                t.x(), t.y(), t.z());
    }

    /** The equivalent 4x4 matrix, with bottom row {@code (0, 0, 0, 1)}. */
    public Mat4f toMat4() {
        return new Mat4f(
                m00, m01, m02, 0f,
                m10, m11, m12, 0f,
                m20, m21, m22, 0f,
                m30, m31, m32, 1f);
    }

    // ---------------------------------------------------------------- algebra

    /**
     * Composition {@code this * b}: transforming by the result applies {@code b} first. Four small column products
     * rather than one 12-expression body, so the JIT can inline it and keep chains allocation-free (see
     * {@link Mat4f#mul}).
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

    /** Composition with a full matrix; the result is a full matrix. */
    public Mat4f mul(Mat4f b) {
        return toMat4().mul(b);
    }

    /** The 3x3 part applied to {@code (x, y, z)}: no translation. */
    private Vec3f mulLinear(float x, float y, float z) {
        return new Vec3f(
                m00 * x + m10 * y + m20 * z,
                m01 * x + m11 * y + m21 * z,
                m02 * x + m12 * y + m22 * z);
    }

    /** The point {@code p} transformed by this matrix: scaled, rotated and translated. */
    public Vec3f transformPosition(Vec3f p) {
        return new Vec3f(
                m00 * p.x() + m10 * p.y() + m20 * p.z() + m30,
                m01 * p.x() + m11 * p.y() + m21 * p.z() + m31,
                m02 * p.x() + m12 * p.y() + m22 * p.z() + m32);
    }

    /** Transforms a direction: rotation and scale, no translation. */
    public Vec3f transformDirection(Vec3f d) {
        return mulLinear(d.x(), d.y(), d.z());
    }

    /** Determinant of the 3x3 part: negative for mirrored transforms, zero for singular ones. */
    public float determinant() {
        return m00 * (m11 * m22 - m21 * m12) - m10 * (m01 * m22 - m21 * m02) + m20 * (m01 * m12 - m11 * m02);
    }

    /**
     * Inverse transform. The matrix must be invertible (nonzero {@link #determinant()}); a singular matrix yields
     * infinite or NaN components.
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

    /** The upper-left 3x3 block: the rotation and scale part. */
    public Mat3f upperLeft3x3() {
        return new Mat3f(
                m00, m01, m02,
                m10, m11, m12,
                m20, m21, m22);
    }

    /** Inverse-transpose of the 3x3 part: the matrix for transforming normals. */
    public Mat3f normalMatrix() {
        return upperLeft3x3().normal();
    }

    /** The translation: the x, y and z of the last column. */
    public Vec3f getTranslation() {
        return new Vec3f(m30, m31, m32);
    }

    /** Returns a copy with the translation replaced. */
    public Mat4x3f withTranslation(Vec3f t) {
        return new Mat4x3f(m00, m01, m02, m10, m11, m12, m20, m21, m22, t.x(), t.y(), t.z());
    }

    /** Splits into translation, rotation and scale as {@link Mat4f#decompose()} does. */
    public Mat4f.Trs decompose() {
        return toMat4().decompose();
    }

    /** Column {@code c}. */
    public Vec3f column(int c) {
        return switch (c) {
            case 0 -> new Vec3f(m00, m01, m02);
            case 1 -> new Vec3f(m10, m11, m12);
            case 2 -> new Vec3f(m20, m21, m22);
            case 3 -> new Vec3f(m30, m31, m32);
            default -> throw new IndexOutOfBoundsException(c);
        };
    }

    /** The element at {@code column} and {@code row}. */
    public float get(int column, int row) {
        return column(column).get(row);
    }

    /** True when every element differs from that of {@code o} by at most {@code eps}. */
    public boolean approxEquals(Mat4x3f o, float eps) {
        for (int c = 0; c < 4; c++) {
            if (!column(c).approxEquals(o.column(c), eps)) {
                return false;
            }
        }
        return true;
    }

    /** True when no component is NaN or infinite. */
    public boolean isFinite() {
        return Float.isFinite(m00) && Float.isFinite(m01) && Float.isFinite(m02)
                && Float.isFinite(m10) && Float.isFinite(m11) && Float.isFinite(m12)
                && Float.isFinite(m20) && Float.isFinite(m21) && Float.isFinite(m22)
                && Float.isFinite(m30) && Float.isFinite(m31) && Float.isFinite(m32);
    }

    // ---------------------------------------------------------------- output

    /** Writes 12 floats, column-major: column 0, 1, 2, then the translation. */
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

    /** Absolute write at {@code index} (12 floats, column-major); does not change the buffer position. */
    public void writeTo(FloatBuffer dst, int index) {
        dst.put(index, m00).put(index + 1, m01).put(index + 2, m02)
                .put(index + 3, m10).put(index + 4, m11).put(index + 5, m12)
                .put(index + 6, m20).put(index + 7, m21).put(index + 8, m22)
                .put(index + 9, m30).put(index + 10, m31).put(index + 11, m32);
    }

    /**
     * Writes the GPU-friendly "three vec4 rows" layout (12 floats, 48 bytes): row 0 is {@code (m00, m10, m20, m30)}, then
     * rows 1 and 2. A shader reads it as {@code mat3x4} / three {@code vec4}s and computes each output component with a
     * single dot product against {@code vec4(p, 1)}.
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

    /** The same value with double components. */
    @FloatOnly
    public Mat4x3d toDouble() {
        return new Mat4x3d(m00, m01, m02, m10, m11, m12, m20, m21, m22, m30, m31, m32);
    }

    /** The same value with float components (rounded to the nearest float for double types). */
    @DoubleOnly
    public Mat4x3f toFloat() {
        return new Mat4x3f((float) m00, (float) m01, (float) m02, (float) m10, (float) m11, (float) m12,
                (float) m20, (float) m21, (float) m22, (float) m30, (float) m31, (float) m32);
    }
}
