package vmath.core;

// GENERATED from Mat3f.java by tools/GenDouble.java. Do not edit; edit the float source.

import java.nio.DoubleBuffer;

/**
 * Immutable 3x3 double matrix, column-major.
 *
 * <p>Component {@code mCR} is column {@code C}, row {@code R} (JOML naming). The canonical
 * constructor takes components in memory order: column 0 top-to-bottom, then column 1, then 2.
 *
 * <p>Valhalla: {@code -Pvalhalla} builds rewrite the {@code value} marker below into a real
 * {@code value record}. Never use {@code ==}, {@code synchronized} or identity-based APIs on it.
 */
public /*value*/ record Mat3d(
        double m00, double m01, double m02,
        double m10, double m11, double m12,
        double m20, double m21, double m22) {

    public static final Mat3d IDENTITY = new Mat3d(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0);

    public static Mat3d fromColumns(Vec3d c0, Vec3d c1, Vec3d c2) {
        return new Mat3d(
                c0.x(), c0.y(), c0.z(),
                c1.x(), c1.y(), c1.z(),
                c2.x(), c2.y(), c2.z());
    }

    public static Mat3d scaling(double sx, double sy, double sz) {
        return new Mat3d(
                sx, 0.0, 0.0,
                0.0, sy, 0.0,
                0.0, 0.0, sz);
    }

    /** Rotation matrix for a unit quaternion. */
    public static Mat3d rotation(Quatd q) {
        double x = q.x(), y = q.y(), z = q.z(), w = q.w();
        double xx = x * x, yy = y * y, zz = z * z, ww = w * w;
        double xy = x * y, xz = x * z, yz = y * z, xw = x * w, yw = y * w, zw = z * w;
        return new Mat3d(
                ww + xx - yy - zz, 2.0 * (xy + zw), 2.0 * (xz - yw),
                2.0 * (xy - zw), ww - xx + yy - zz, 2.0 * (yz + xw),
                2.0 * (xz + yw), 2.0 * (yz - xw), ww - xx - yy + zz);
    }

    /** Matrix product {@code this * b}: transforming by the result applies {@code b} first. */
    public Mat3d mul(Mat3d b) {
        return new Mat3d(
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

    public Vec3d transform(Vec3d v) {
        double x = v.x(), y = v.y(), z = v.z();
        return new Vec3d(
                m00 * x + m10 * y + m20 * z,
                m01 * x + m11 * y + m21 * z,
                m02 * x + m12 * y + m22 * z);
    }

    public Mat3d transpose() {
        return new Mat3d(
                m00, m10, m20,
                m01, m11, m21,
                m02, m12, m22);
    }

    public double determinant() {
        return (m00 * m11 - m01 * m10) * m22
                + (m02 * m10 - m00 * m12) * m21
                + (m01 * m12 - m02 * m11) * m20;
    }

    /** General inverse. A singular matrix yields non-finite components. */
    public Mat3d invert() {
        double a = m00 * m11 - m01 * m10;
        double b = m02 * m10 - m00 * m12;
        double c = m01 * m12 - m02 * m11;
        double s = 1.0 / (a * m22 + b * m21 + c * m20);
        return new Mat3d(
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

    /** Inverse-transpose: transforms normals correctly under non-uniform scale. */
    public Mat3d normal() {
        return invert().transpose();
    }

    public Vec3d column(int c) {
        return switch (c) {
            case 0 -> new Vec3d(m00, m01, m02);
            case 1 -> new Vec3d(m10, m11, m12);
            case 2 -> new Vec3d(m20, m21, m22);
            default -> throw new IndexOutOfBoundsException(c);
        };
    }

    public double get(int column, int row) {
        return switch (column * 3 + row) {
            case 0 -> m00; case 1 -> m01; case 2 -> m02;
            case 3 -> m10; case 4 -> m11; case 5 -> m12;
            case 6 -> m20; case 7 -> m21; case 8 -> m22;
            default -> throw new IndexOutOfBoundsException(column + "," + row);
        };
    }

    public boolean approxEquals(Mat3d o, double eps) {
        return Math.abs(m00 - o.m00) <= eps && Math.abs(m01 - o.m01) <= eps && Math.abs(m02 - o.m02) <= eps
                && Math.abs(m10 - o.m10) <= eps && Math.abs(m11 - o.m11) <= eps && Math.abs(m12 - o.m12) <= eps
                && Math.abs(m20 - o.m20) <= eps && Math.abs(m21 - o.m21) <= eps && Math.abs(m22 - o.m22) <= eps;
    }

    /** Writes 9 tightly packed column-major values. For std140 uniforms use {@code vmath.gl.Std140}. */
    public void writeTo(double[] dst, int off) {
        dst[off] = m00; dst[off + 1] = m01; dst[off + 2] = m02;
        dst[off + 3] = m10; dst[off + 4] = m11; dst[off + 5] = m12;
        dst[off + 6] = m20; dst[off + 7] = m21; dst[off + 8] = m22;
    }

    /** Absolute write of 9 column-major values at {@code index}; does not change the buffer position. */
    public void writeTo(DoubleBuffer dst, int index) {
        dst.put(index, m00).put(index + 1, m01).put(index + 2, m02)
                .put(index + 3, m10).put(index + 4, m11).put(index + 5, m12)
                .put(index + 6, m20).put(index + 7, m21).put(index + 8, m22);
    }

    public Mat3f toFloat() {
        return new Mat3f(
                (float) m00, (float) m01, (float) m02,
                (float) m10, (float) m11, (float) m12,
                (float) m20, (float) m21, (float) m22);
    }
}
