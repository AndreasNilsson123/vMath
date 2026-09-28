package vmath.core;

// GENERATED from Mat4f.java by tools/GenDouble.java. Do not edit; edit the float source.

import java.nio.DoubleBuffer;

/**
 * Immutable 4x4 double matrix, column-major, right-handed.
 *
 * <p>Component {@code mCR} is column {@code C}, row {@code R} (JOML naming), so the translation
 * lives in {@code m30, m31, m32}. The canonical constructor takes components in memory order:
 * column 0 top-to-bottom, then columns 1, 2, 3. That is exactly the layout OpenGL expects with
 * {@code transpose = false}.
 *
 * <p>Valhalla: {@code -Pvalhalla} builds rewrite the {@code value} marker below into a real
 * {@code value record}. At 64 bytes this type is unlikely to be heap-flattened by early Valhalla
 * builds, but it still loses identity and benefits from scalarization in compiled code. Keep
 * bulk matrix data in plain arrays or buffers, not in {@code Mat4d[]}.
 */
public /*value*/ record Mat4d(
        double m00, double m01, double m02, double m03,
        double m10, double m11, double m12, double m13,
        double m20, double m21, double m22, double m23,
        double m30, double m31, double m32, double m33) {

    public static final Mat4d IDENTITY = new Mat4d(
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0);

    // ---------------------------------------------------------------- factories

    public static Mat4d fromColumns(Vec4d c0, Vec4d c1, Vec4d c2, Vec4d c3) {
        return new Mat4d(
                c0.x(), c0.y(), c0.z(), c0.w(),
                c1.x(), c1.y(), c1.z(), c1.w(),
                c2.x(), c2.y(), c2.z(), c2.w(),
                c3.x(), c3.y(), c3.z(), c3.w());
    }

    /** Reads 16 column-major values starting at {@code off}. */
    public static Mat4d fromArray(double[] src, int off) {
        return new Mat4d(
                src[off], src[off + 1], src[off + 2], src[off + 3],
                src[off + 4], src[off + 5], src[off + 6], src[off + 7],
                src[off + 8], src[off + 9], src[off + 10], src[off + 11],
                src[off + 12], src[off + 13], src[off + 14], src[off + 15]);
    }

    public static Mat4d translation(double x, double y, double z) {
        return new Mat4d(
                1.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                x, y, z, 1.0);
    }

    public static Mat4d translation(Vec3d t) {
        return translation(t.x(), t.y(), t.z());
    }

    public static Mat4d scaling(double sx, double sy, double sz) {
        return new Mat4d(
                sx, 0.0, 0.0, 0.0,
                0.0, sy, 0.0, 0.0,
                0.0, 0.0, sz, 0.0,
                0.0, 0.0, 0.0, 1.0);
    }

    /** Rotation matrix for a unit quaternion. */
    public static Mat4d rotation(Quatd q) {
        return translationRotateScale(Vec3d.ZERO, q, Vec3d.ONE);
    }

    /** Model matrix {@code T * R * S}: scales first, then rotates, then translates. */
    public static Mat4d translationRotateScale(Vec3d t, Quatd q, Vec3d s) {
        Mat3d r = Mat3d.rotation(q);
        double sx = s.x(), sy = s.y(), sz = s.z();
        return new Mat4d(
                r.m00() * sx, r.m01() * sx, r.m02() * sx, 0.0,
                r.m10() * sy, r.m11() * sy, r.m12() * sy, 0.0,
                r.m20() * sz, r.m21() * sz, r.m22() * sz, 0.0,
                t.x(), t.y(), t.z(), 1.0);
    }

    /** Embeds a 3x3 matrix in the upper-left corner of an identity matrix. */
    public static Mat4d fromMat3(Mat3d m) {
        return new Mat4d(
                m.m00(), m.m01(), m.m02(), 0.0,
                m.m10(), m.m11(), m.m12(), 0.0,
                m.m20(), m.m21(), m.m22(), 0.0,
                0.0, 0.0, 0.0, 1.0);
    }

    /**
     * Symmetric perspective projection with finite near and far planes.
     *
     * @param fovy       vertical field of view in radians
     * @param zZeroToOne {@code true} for NDC depth [0, 1] (Vulkan, D3D, GL with
     *                   {@code glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)}), {@code false} for GL's [-1, 1]
     */
    public static Mat4d perspective(double fovy, double aspect, double near, double far, boolean zZeroToOne) {
        double h = Math.tan(fovy * 0.5);
        double m22 = (zZeroToOne ? far : far + near) / (near - far);
        double m32 = (zZeroToOne ? far : far + far) * near / (near - far);
        return new Mat4d(
                1.0 / (h * aspect), 0.0, 0.0, 0.0,
                0.0, 1.0 / h, 0.0, 0.0,
                0.0, 0.0, m22, -1.0,
                0.0, 0.0, m32, 0.0);
    }

    /**
     * Reversed-Z perspective with an infinite far plane and NDC depth [0, 1]: the near plane maps
     * to depth 1 and infinity to depth 0. Pair with a floating-point depth buffer,
     * {@code glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)}, {@code glClearDepth(0)} and
     * {@code GL_GREATER}. This gives near-uniform depth precision, which is what globe-scale scenes need.
     */
    public static Mat4d perspectiveReversedZ(double fovy, double aspect, double near) {
        double h = Math.tan(fovy * 0.5);
        return new Mat4d(
                1.0 / (h * aspect), 0.0, 0.0, 0.0,
                0.0, 1.0 / h, 0.0, 0.0,
                0.0, 0.0, 0.0, -1.0,
                0.0, 0.0, near, 0.0);
    }

    /** Orthographic projection. See {@link #perspective} for {@code zZeroToOne}. */
    public static Mat4d ortho(double left, double right, double bottom, double top,
                              double near, double far, boolean zZeroToOne) {
        return new Mat4d(
                2.0 / (right - left), 0.0, 0.0, 0.0,
                0.0, 2.0 / (top - bottom), 0.0, 0.0,
                0.0, 0.0, (zZeroToOne ? 1.0 : 2.0) / (near - far), 0.0,
                (right + left) / (left - right),
                (top + bottom) / (bottom - top),
                (zZeroToOne ? near : far + near) / (near - far),
                1.0);
    }

    /** Right-handed view matrix looking from {@code eye} towards {@code center}. */
    public static Mat4d lookAt(Vec3d eye, Vec3d center, Vec3d up) {
        Vec3d dir = eye.sub(center).normalize();
        Vec3d left = up.cross(dir).normalize();
        Vec3d upn = dir.cross(left);
        return new Mat4d(
                left.x(), upn.x(), dir.x(), 0.0,
                left.y(), upn.y(), dir.y(), 0.0,
                left.z(), upn.z(), dir.z(), 0.0,
                -left.dot(eye), -upn.dot(eye), -dir.dot(eye), 1.0);
    }

    // ---------------------------------------------------------------- algebra

    /** Matrix product {@code this * b}: transforming by the result applies {@code b} first. */
    public Mat4d mul(Mat4d b) {
        return new Mat4d(
                m00 * b.m00 + m10 * b.m01 + m20 * b.m02 + m30 * b.m03,
                m01 * b.m00 + m11 * b.m01 + m21 * b.m02 + m31 * b.m03,
                m02 * b.m00 + m12 * b.m01 + m22 * b.m02 + m32 * b.m03,
                m03 * b.m00 + m13 * b.m01 + m23 * b.m02 + m33 * b.m03,
                m00 * b.m10 + m10 * b.m11 + m20 * b.m12 + m30 * b.m13,
                m01 * b.m10 + m11 * b.m11 + m21 * b.m12 + m31 * b.m13,
                m02 * b.m10 + m12 * b.m11 + m22 * b.m12 + m32 * b.m13,
                m03 * b.m10 + m13 * b.m11 + m23 * b.m12 + m33 * b.m13,
                m00 * b.m20 + m10 * b.m21 + m20 * b.m22 + m30 * b.m23,
                m01 * b.m20 + m11 * b.m21 + m21 * b.m22 + m31 * b.m23,
                m02 * b.m20 + m12 * b.m21 + m22 * b.m22 + m32 * b.m23,
                m03 * b.m20 + m13 * b.m21 + m23 * b.m22 + m33 * b.m23,
                m00 * b.m30 + m10 * b.m31 + m20 * b.m32 + m30 * b.m33,
                m01 * b.m30 + m11 * b.m31 + m21 * b.m32 + m31 * b.m33,
                m02 * b.m30 + m12 * b.m31 + m22 * b.m32 + m32 * b.m33,
                m03 * b.m30 + m13 * b.m31 + m23 * b.m32 + m33 * b.m33);
    }

    public Mat4d transpose() {
        return new Mat4d(
                m00, m10, m20, m30,
                m01, m11, m21, m31,
                m02, m12, m22, m32,
                m03, m13, m23, m33);
    }

    public double determinant() {
        return (m00 * m11 - m01 * m10) * (m22 * m33 - m23 * m32)
                + (m02 * m10 - m00 * m12) * (m21 * m33 - m23 * m31)
                + (m00 * m13 - m03 * m10) * (m21 * m32 - m22 * m31)
                + (m01 * m12 - m02 * m11) * (m20 * m33 - m23 * m30)
                + (m03 * m11 - m01 * m13) * (m20 * m32 - m22 * m30)
                + (m02 * m13 - m03 * m12) * (m20 * m31 - m21 * m30);
    }

    /** General inverse. A singular matrix yields non-finite components. */
    public Mat4d invert() {
        double a = m00 * m11 - m01 * m10;
        double b = m00 * m12 - m02 * m10;
        double c = m00 * m13 - m03 * m10;
        double d = m01 * m12 - m02 * m11;
        double e = m01 * m13 - m03 * m11;
        double f = m02 * m13 - m03 * m12;
        double g = m20 * m31 - m21 * m30;
        double h = m20 * m32 - m22 * m30;
        double i = m20 * m33 - m23 * m30;
        double j = m21 * m32 - m22 * m31;
        double k = m21 * m33 - m23 * m31;
        double l = m22 * m33 - m23 * m32;
        double det = 1.0 / (a * l - b * k + c * j + d * i - e * h + f * g);
        return new Mat4d(
                (m11 * l - m12 * k + m13 * j) * det,
                (-m01 * l + m02 * k - m03 * j) * det,
                (m31 * f - m32 * e + m33 * d) * det,
                (-m21 * f + m22 * e - m23 * d) * det,
                (-m10 * l + m12 * i - m13 * h) * det,
                (m00 * l - m02 * i + m03 * h) * det,
                (-m30 * f + m32 * c - m33 * b) * det,
                (m20 * f - m22 * c + m23 * b) * det,
                (m10 * k - m11 * i + m13 * g) * det,
                (-m00 * k + m01 * i - m03 * g) * det,
                (m30 * e - m31 * c + m33 * a) * det,
                (-m20 * e + m21 * c - m23 * a) * det,
                (-m10 * j + m11 * h - m12 * g) * det,
                (m00 * j - m01 * h + m02 * g) * det,
                (-m30 * d + m31 * b - m32 * a) * det,
                (m20 * d - m21 * b + m22 * a) * det);
    }

    /**
     * Fast inverse for affine matrices (last row {@code 0 0 0 1}): model and view matrices.
     * Do not use on projection matrices.
     */
    public Mat4d invertAffine() {
        Mat3d r = upperLeft3x3().invert();
        double tx = -(r.m00() * m30 + r.m10() * m31 + r.m20() * m32);
        double ty = -(r.m01() * m30 + r.m11() * m31 + r.m21() * m32);
        double tz = -(r.m02() * m30 + r.m12() * m31 + r.m22() * m32);
        return new Mat4d(
                r.m00(), r.m01(), r.m02(), 0.0,
                r.m10(), r.m11(), r.m12(), 0.0,
                r.m20(), r.m21(), r.m22(), 0.0,
                tx, ty, tz, 1.0);
    }

    // ---------------------------------------------------------------- transforms

    public Vec4d transform(Vec4d v) {
        double x = v.x(), y = v.y(), z = v.z(), w = v.w();
        return new Vec4d(
                m00 * x + m10 * y + m20 * z + m30 * w,
                m01 * x + m11 * y + m21 * z + m31 * w,
                m02 * x + m12 * y + m22 * z + m32 * w,
                m03 * x + m13 * y + m23 * z + m33 * w);
    }

    /** Transforms a point ({@code w = 1}) and ignores the projective row. Use for affine matrices. */
    public Vec3d transformPosition(Vec3d p) {
        double x = p.x(), y = p.y(), z = p.z();
        return new Vec3d(
                m00 * x + m10 * y + m20 * z + m30,
                m01 * x + m11 * y + m21 * z + m31,
                m02 * x + m12 * y + m22 * z + m32);
    }

    /** Transforms a direction ({@code w = 0}): no translation. */
    public Vec3d transformDirection(Vec3d d) {
        double x = d.x(), y = d.y(), z = d.z();
        return new Vec3d(
                m00 * x + m10 * y + m20 * z,
                m01 * x + m11 * y + m21 * z,
                m02 * x + m12 * y + m22 * z);
    }

    /** Transforms a point ({@code w = 1}) and performs the perspective divide. */
    public Vec3d transformProject(Vec3d p) {
        double x = p.x(), y = p.y(), z = p.z();
        double invW = 1.0 / (m03 * x + m13 * y + m23 * z + m33);
        return new Vec3d(
                (m00 * x + m10 * y + m20 * z + m30) * invW,
                (m01 * x + m11 * y + m21 * z + m31) * invW,
                (m02 * x + m12 * y + m22 * z + m32) * invW);
    }

    // ---------------------------------------------------------------- accessors

    public Mat3d upperLeft3x3() {
        return new Mat3d(
                m00, m01, m02,
                m10, m11, m12,
                m20, m21, m22);
    }

    /** Inverse-transpose of the upper-left 3x3: the matrix for transforming normals. */
    public Mat3d normalMatrix() {
        return upperLeft3x3().normal();
    }

    public Vec3d getTranslation() {
        return new Vec3d(m30, m31, m32);
    }

    /** Returns a copy with the translation column replaced. */
    public Mat4d withTranslation(Vec3d t) {
        return new Mat4d(
                m00, m01, m02, m03,
                m10, m11, m12, m13,
                m20, m21, m22, m23,
                t.x(), t.y(), t.z(), m33);
    }

    public Vec4d column(int c) {
        return switch (c) {
            case 0 -> new Vec4d(m00, m01, m02, m03);
            case 1 -> new Vec4d(m10, m11, m12, m13);
            case 2 -> new Vec4d(m20, m21, m22, m23);
            case 3 -> new Vec4d(m30, m31, m32, m33);
            default -> throw new IndexOutOfBoundsException(c);
        };
    }

    public Vec4d row(int r) {
        return switch (r) {
            case 0 -> new Vec4d(m00, m10, m20, m30);
            case 1 -> new Vec4d(m01, m11, m21, m31);
            case 2 -> new Vec4d(m02, m12, m22, m32);
            case 3 -> new Vec4d(m03, m13, m23, m33);
            default -> throw new IndexOutOfBoundsException(r);
        };
    }

    public double get(int column, int row) {
        return column(column).get(row);
    }

    public boolean approxEquals(Mat4d o, double eps) {
        for (int c = 0; c < 4; c++) {
            if (!column(c).approxEquals(o.column(c), eps)) {
                return false;
            }
        }
        return true;
    }

    public boolean isFinite() {
        for (int c = 0; c < 4; c++) {
            Vec4d v = column(c);
            if (!Double.isFinite(v.x()) || !Double.isFinite(v.y()) || !Double.isFinite(v.z()) || !Double.isFinite(v.w())) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- output

    /** Writes 16 column-major values. */
    public void writeTo(double[] dst, int off) {
        dst[off] = m00; dst[off + 1] = m01; dst[off + 2] = m02; dst[off + 3] = m03;
        dst[off + 4] = m10; dst[off + 5] = m11; dst[off + 6] = m12; dst[off + 7] = m13;
        dst[off + 8] = m20; dst[off + 9] = m21; dst[off + 10] = m22; dst[off + 11] = m23;
        dst[off + 12] = m30; dst[off + 13] = m31; dst[off + 14] = m32; dst[off + 15] = m33;
    }

    /**
     * Absolute write of 16 column-major values at {@code index}; does not change the buffer
     * position. Ready for {@code glUniformMatrix4*v(loc, false, buf)}.
     */
    public void writeTo(DoubleBuffer dst, int index) {
        dst.put(index, m00).put(index + 1, m01).put(index + 2, m02).put(index + 3, m03)
                .put(index + 4, m10).put(index + 5, m11).put(index + 6, m12).put(index + 7, m13)
                .put(index + 8, m20).put(index + 9, m21).put(index + 10, m22).put(index + 11, m23)
                .put(index + 12, m30).put(index + 13, m31).put(index + 14, m32).put(index + 15, m33);
    }

    public Mat4f toFloat() {
        return new Mat4f(
                (float) m00, (float) m01, (float) m02, (float) m03,
                (float) m10, (float) m11, (float) m12, (float) m13,
                (float) m20, (float) m21, (float) m22, (float) m23,
                (float) m30, (float) m31, (float) m32, (float) m33);
    }
}
