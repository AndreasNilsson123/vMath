package vmath.core;

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
 * <p>Valhalla: {@code -Pvalhalla} builds turn {@code @ValueType} into a real
 * {@code value record}. Never use {@code ==}, {@code synchronized} or identity-based APIs on it.
 */
@GenerateDouble
@ValueType
public record Mat3f(
        float m00, float m01, float m02,
        float m10, float m11, float m12,
        float m20, float m21, float m22) {

    public static final Mat3f IDENTITY = new Mat3f(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f);

    public static Mat3f fromColumns(Vec3f c0, Vec3f c1, Vec3f c2) {
        return new Mat3f(
                c0.x(), c0.y(), c0.z(),
                c1.x(), c1.y(), c1.z(),
                c2.x(), c2.y(), c2.z());
    }

    /** The matrix stored column-major in {@code src[off..off+8]} (the order {@link #writeTo} writes). */
    public static Mat3f fromArray(float[] src, int off) {
        return new Mat3f(
                src[off], src[off + 1], src[off + 2],
                src[off + 3], src[off + 4], src[off + 5],
                src[off + 6], src[off + 7], src[off + 8]);
    }

    public static Mat3f scaling(float sx, float sy, float sz) {
        return new Mat3f(
                sx, 0f, 0f,
                0f, sy, 0f,
                0f, 0f, sz);
    }

    public static Mat3f rotationX(float angle) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        return new Mat3f(
                1f, 0f, 0f,
                0f, c, s,
                0f, -s, c);
    }

    public static Mat3f rotationY(float angle) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        return new Mat3f(
                c, 0f, -s,
                0f, 1f, 0f,
                s, 0f, c);
    }

    public static Mat3f rotationZ(float angle) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        return new Mat3f(
                c, s, 0f,
                -s, c, 0f,
                0f, 0f, 1f);
    }

    /** Rotation of {@code angle} radians about {@code axis} (normalized internally). */
    public static Mat3f rotationAxis(float angle, Vec3f axis) {
        return rotation(Quatf.fromAxisAngle(angle, axis));
    }

    /** The skew-symmetric matrix {@code [v]x}, so that {@code skew(v).transform(u) == v.cross(u)}. */
    public static Mat3f skew(Vec3f v) {
        return new Mat3f(
                0f, v.z(), -v.y(),
                -v.z(), 0f, v.x(),
                v.y(), -v.x(), 0f);
    }

    /**
     * Orthonormal basis with {@code n} as the third column (branchless, after Duff et al. 2017): tangent and
     * bitangent are the first two columns. {@code n} must be unit length. Continuous except at {@code n.z == 0}
     * sign flips, and free of the singularity that cross-product constructions have.
     */
    public static Mat3f basisFromNormal(Vec3f n) {
        float sign = Math.copySign(1f, n.z());
        float a = -1f / (sign + n.z());
        float b = n.x() * n.y() * a;
        Vec3f tangent = new Vec3f(1f + sign * n.x() * n.x() * a, sign * b, -sign * n.x());
        Vec3f bitangent = new Vec3f(b, sign + n.y() * n.y() * a, -n.y());
        return fromColumns(tangent, bitangent, n);
    }

    /** Rotation matrix for a unit quaternion. */
    public static Mat3f rotation(Quatf q) {
        float x = q.x(), y = q.y(), z = q.z(), w = q.w();
        float xx = x * x, yy = y * y, zz = z * z, ww = w * w;
        float xy = x * y, xz = x * z, yz = y * z, xw = x * w, yw = y * w, zw = z * w;
        return new Mat3f(
                ww + xx - yy - zz, 2f * (xy + zw), 2f * (xz - yw),
                2f * (xy - zw), ww - xx + yy - zz, 2f * (yz + xw),
                2f * (xz + yw), 2f * (yz - xw), ww - xx - yy + zz);
    }

    /** Matrix product {@code this * b}: transforming by the result applies {@code b} first. */
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

    public Vec3f transform(Vec3f v) {
        float x = v.x(), y = v.y(), z = v.z();
        return new Vec3f(
                m00 * x + m10 * y + m20 * z,
                m01 * x + m11 * y + m21 * z,
                m02 * x + m12 * y + m22 * z);
    }

    public Mat3f transpose() {
        return new Mat3f(
                m00, m10, m20,
                m01, m11, m21,
                m02, m12, m22);
    }

    public float determinant() {
        return (m00 * m11 - m01 * m10) * m22
                + (m02 * m10 - m00 * m12) * m21
                + (m01 * m12 - m02 * m11) * m20;
    }

    /** General inverse. A singular matrix yields non-finite components. */
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

    /** Inverse-transpose: transforms normals correctly under non-uniform scale. */
    public Mat3f normal() {
        return invert().transpose();
    }

    public Vec3f column(int c) {
        return switch (c) {
            case 0 -> new Vec3f(m00, m01, m02);
            case 1 -> new Vec3f(m10, m11, m12);
            case 2 -> new Vec3f(m20, m21, m22);
            default -> throw new IndexOutOfBoundsException(c);
        };
    }

    public Vec3f row(int r) {
        return switch (r) {
            case 0 -> new Vec3f(m00, m10, m20);
            case 1 -> new Vec3f(m01, m11, m21);
            case 2 -> new Vec3f(m02, m12, m22);
            default -> throw new IndexOutOfBoundsException(r);
        };
    }

    public float get(int column, int row) {
        return switch (column * 3 + row) {
            case 0 -> m00; case 1 -> m01; case 2 -> m02;
            case 3 -> m10; case 4 -> m11; case 5 -> m12;
            case 6 -> m20; case 7 -> m21; case 8 -> m22;
            default -> throw new IndexOutOfBoundsException(column + "," + row);
        };
    }

    /** True when no component is NaN or infinite. */
    public boolean isFinite() {
        return Float.isFinite(m00) && Float.isFinite(m01) && Float.isFinite(m02)
                && Float.isFinite(m10) && Float.isFinite(m11) && Float.isFinite(m12)
                && Float.isFinite(m20) && Float.isFinite(m21) && Float.isFinite(m22);
    }

    public boolean approxEquals(Mat3f o, float eps) {
        return Math.abs(m00 - o.m00) <= eps && Math.abs(m01 - o.m01) <= eps && Math.abs(m02 - o.m02) <= eps
                && Math.abs(m10 - o.m10) <= eps && Math.abs(m11 - o.m11) <= eps && Math.abs(m12 - o.m12) <= eps
                && Math.abs(m20 - o.m20) <= eps && Math.abs(m21 - o.m21) <= eps && Math.abs(m22 - o.m22) <= eps;
    }

    /** Writes 9 tightly packed column-major values. For std140 uniforms use {@code vmath.gl.GpuWriter} or a generated {@code @GpuStruct} writer. */
    public void writeTo(float[] dst, int off) {
        dst[off] = m00; dst[off + 1] = m01; dst[off + 2] = m02;
        dst[off + 3] = m10; dst[off + 4] = m11; dst[off + 5] = m12;
        dst[off + 6] = m20; dst[off + 7] = m21; dst[off + 8] = m22;
    }

    /** Absolute write of 9 column-major values at {@code index}; does not change the buffer position. */
    public void writeTo(FloatBuffer dst, int index) {
        dst.put(index, m00).put(index + 1, m01).put(index + 2, m02)
                .put(index + 3, m10).put(index + 4, m11).put(index + 5, m12)
                .put(index + 6, m20).put(index + 7, m21).put(index + 8, m22);
    }

    @FloatOnly
    public Mat3d toDouble() {
        return new Mat3d(m00, m01, m02, m10, m11, m12, m20, m21, m22);
    }

    @DoubleOnly
    public Mat3f toFloat() {
        return new Mat3f(
                (float) m00, (float) m01, (float) m02,
                (float) m10, (float) m11, (float) m12,
                (float) m20, (float) m21, (float) m22);
    }
}
