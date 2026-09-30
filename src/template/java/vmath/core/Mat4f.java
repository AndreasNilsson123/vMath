package vmath.core;

import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import java.nio.FloatBuffer;

/**
 * Immutable 4x4 float matrix, column-major, right-handed.
 *
 * <p>Component {@code mCR} is column {@code C}, row {@code R} (JOML naming), so the translation
 * lives in {@code m30, m31, m32}. The canonical constructor takes components in memory order:
 * column 0 top-to-bottom, then columns 1, 2, 3. That is exactly the layout OpenGL expects with
 * {@code transpose = false}.
 *
 * <p>Valhalla: {@code -Pvalhalla} builds turn {@code @ValueType} into a real
 * {@code value record}. At 64 bytes this type is unlikely to be heap-flattened by early Valhalla
 * builds, but it still loses identity and benefits from scalarization in compiled code. Keep
 * bulk matrix data in plain arrays or buffers, not in {@code Mat4f[]}.
 */
@GenerateDouble
@ValueType
public record Mat4f(
        float m00, float m01, float m02, float m03,
        float m10, float m11, float m12, float m13,
        float m20, float m21, float m22, float m23,
        float m30, float m31, float m32, float m33) {

    public static final Mat4f IDENTITY = new Mat4f(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f);

    // ---------------------------------------------------------------- factories

    public static Mat4f fromColumns(Vec4f c0, Vec4f c1, Vec4f c2, Vec4f c3) {
        return new Mat4f(
                c0.x(), c0.y(), c0.z(), c0.w(),
                c1.x(), c1.y(), c1.z(), c1.w(),
                c2.x(), c2.y(), c2.z(), c2.w(),
                c3.x(), c3.y(), c3.z(), c3.w());
    }

    /** Reads 16 column-major values starting at {@code off}. */
    public static Mat4f fromArray(float[] src, int off) {
        return new Mat4f(
                src[off], src[off + 1], src[off + 2], src[off + 3],
                src[off + 4], src[off + 5], src[off + 6], src[off + 7],
                src[off + 8], src[off + 9], src[off + 10], src[off + 11],
                src[off + 12], src[off + 13], src[off + 14], src[off + 15]);
    }

    public static Mat4f translation(float x, float y, float z) {
        return new Mat4f(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                x, y, z, 1f);
    }

    public static Mat4f translation(Vec3f t) {
        return translation(t.x(), t.y(), t.z());
    }

    public static Mat4f scaling(float sx, float sy, float sz) {
        return new Mat4f(
                sx, 0f, 0f, 0f,
                0f, sy, 0f, 0f,
                0f, 0f, sz, 0f,
                0f, 0f, 0f, 1f);
    }

    /** Rotation matrix for a unit quaternion. */
    public static Mat4f rotation(Quatf q) {
        return translationRotateScale(Vec3f.ZERO, q, Vec3f.ONE);
    }

    /** Model matrix {@code T * R * S}: scales first, then rotates, then translates. */
    public static Mat4f translationRotateScale(Vec3f t, Quatf q, Vec3f s) {
        Mat3f r = Mat3f.rotation(q);
        float sx = s.x(), sy = s.y(), sz = s.z();
        return new Mat4f(
                r.m00() * sx, r.m01() * sx, r.m02() * sx, 0f,
                r.m10() * sy, r.m11() * sy, r.m12() * sy, 0f,
                r.m20() * sz, r.m21() * sz, r.m22() * sz, 0f,
                t.x(), t.y(), t.z(), 1f);
    }

    /** Embeds a 3x3 matrix in the upper-left corner of an identity matrix. */
    public static Mat4f fromMat3(Mat3f m) {
        return new Mat4f(
                m.m00(), m.m01(), m.m02(), 0f,
                m.m10(), m.m11(), m.m12(), 0f,
                m.m20(), m.m21(), m.m22(), 0f,
                0f, 0f, 0f, 1f);
    }

    /**
     * Symmetric perspective projection with finite near and far planes.
     *
     * @param fovy       vertical field of view in radians
     * @param zZeroToOne {@code true} for NDC depth [0, 1] (Vulkan, D3D, GL with
     *                   {@code glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)}), {@code false} for GL's [-1, 1]
     */
    public static Mat4f perspective(float fovy, float aspect, float near, float far, boolean zZeroToOne) {
        float h = (float) Math.tan(fovy * 0.5f);
        float m22 = (zZeroToOne ? far : far + near) / (near - far);
        float m32 = (zZeroToOne ? far : far + far) * near / (near - far);
        return new Mat4f(
                1f / (h * aspect), 0f, 0f, 0f,
                0f, 1f / h, 0f, 0f,
                0f, 0f, m22, -1f,
                0f, 0f, m32, 0f);
    }

    /** Symmetric perspective projection for a graphics API's {@link ClipSpace} (depth range and Y direction). */
    public static Mat4f perspective(float fovy, float aspect, float near, float far, ClipSpace space) {
        Mat4f m = perspective(fovy, aspect, near, far, space.zeroToOne());
        return space.yDown() ? m.flipY() : m;
    }

    /** Infinite-far perspective (conventional depth) for a {@link ClipSpace}. */
    public static Mat4f perspectiveInfinite(float fovy, float aspect, float near, ClipSpace space) {
        Mat4f m = perspectiveInfinite(fovy, aspect, near, space.zeroToOne());
        return space.yDown() ? m.flipY() : m;
    }

    /**
     * Reversed-Z infinite perspective for a {@link ClipSpace}. Reversed depth needs a [0, 1] range, so {@link ClipSpace#OPENGL} is rejected
     * (in OpenGL use {@link ClipSpace#D3D} together with {@code glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)}).
     */
    public static Mat4f perspectiveReversedZ(float fovy, float aspect, float near, ClipSpace space) {
        if (!space.zeroToOne()) {
            throw new IllegalArgumentException("reversed-Z needs a [0, 1] depth range; use ClipSpace.D3D with glClipControl in OpenGL");
        }
        Mat4f m = perspectiveReversedZ(fovy, aspect, near);
        return space.yDown() ? m.flipY() : m;
    }

    /**
     * Reversed-Z perspective with an infinite far plane and NDC depth [0, 1]: the near plane maps
     * to depth 1 and infinity to depth 0. Pair with a floating-point depth buffer,
     * {@code glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)}, {@code glClearDepth(0)} and
     * {@code GL_GREATER}. This gives near-uniform depth precision, which is what globe-scale scenes need.
     */
    public static Mat4f perspectiveReversedZ(float fovy, float aspect, float near) {
        float h = (float) Math.tan(fovy * 0.5f);
        return new Mat4f(
                1f / (h * aspect), 0f, 0f, 0f,
                0f, 1f / h, 0f, 0f,
                0f, 0f, 0f, -1f,
                0f, 0f, near, 0f);
    }

    /** Orthographic projection. See {@link #perspective} for {@code zZeroToOne}. */
    public static Mat4f ortho(float left, float right, float bottom, float top,
                              float near, float far, boolean zZeroToOne) {
        return new Mat4f(
                2f / (right - left), 0f, 0f, 0f,
                0f, 2f / (top - bottom), 0f, 0f,
                0f, 0f, (zZeroToOne ? 1f : 2f) / (near - far), 0f,
                (right + left) / (left - right),
                (top + bottom) / (bottom - top),
                (zZeroToOne ? near : far + near) / (near - far),
                1f);
    }

    /** Orthographic projection for a {@link ClipSpace}. */
    public static Mat4f ortho(float left, float right, float bottom, float top, float near, float far, ClipSpace space) {
        Mat4f m = ortho(left, right, bottom, top, near, far, space.zeroToOne());
        return space.yDown() ? m.flipY() : m;
    }

    /** Asymmetric perspective frustum for a {@link ClipSpace}. */
    public static Mat4f frustum(float left, float right, float bottom, float top, float near, float far, ClipSpace space) {
        Mat4f m = frustum(left, right, bottom, top, near, far, space.zeroToOne());
        return space.yDown() ? m.flipY() : m;
    }

    /**
     * This matrix with the output {@code y} mirrored: {@code diag(1, -1, 1, 1) * this}, that is, row 1 negated. Turns a y-up projection into the y-down
     * clip space of Vulkan. Flipping twice gives the original; a matrix applied to a view-space point and then flipped has the same {@code x, z, w}.
     */
    public Mat4f flipY() {
        return new Mat4f(
                m00, -m01, m02, m03,
                m10, -m11, m12, m13,
                m20, -m21, m22, m23,
                m30, -m31, m32, m33);
    }

    /** Right-handed view matrix looking from {@code eye} towards {@code center}. */
    public static Mat4f lookAt(Vec3f eye, Vec3f center, Vec3f up) {
        Vec3f dir = eye.sub(center).normalize();
        Vec3f left = up.cross(dir).normalize();
        Vec3f upn = dir.cross(left);
        return new Mat4f(
                left.x(), upn.x(), dir.x(), 0f,
                left.y(), upn.y(), dir.y(), 0f,
                left.z(), upn.z(), dir.z(), 0f,
                -left.dot(eye), -upn.dot(eye), -dir.dot(eye), 1f);
    }

    public static Mat4f rotationX(float angle) {
        return fromMat3(Mat3f.rotationX(angle));
    }

    public static Mat4f rotationY(float angle) {
        return fromMat3(Mat3f.rotationY(angle));
    }

    public static Mat4f rotationZ(float angle) {
        return fromMat3(Mat3f.rotationZ(angle));
    }

    /** Rotation of {@code angle} radians about {@code axis} (normalized internally). */
    public static Mat4f rotationAxis(float angle, Vec3f axis) {
        return fromMat3(Mat3f.rotationAxis(angle, axis));
    }

    /**
     * Asymmetric perspective frustum (off-center projection, e.g. for stereo, tiles or portals). See
     * {@link #perspective} for {@code zZeroToOne}.
     */
    public static Mat4f frustum(float left, float right, float bottom, float top,
                                float near, float far, boolean zZeroToOne) {
        float m22 = (zZeroToOne ? far : far + near) / (near - far);
        float m32 = (zZeroToOne ? far : far + far) * near / (near - far);
        return new Mat4f(
                2f * near / (right - left), 0f, 0f, 0f,
                0f, 2f * near / (top - bottom), 0f, 0f,
                (right + left) / (right - left), (top + bottom) / (top - bottom), m22, -1f,
                0f, 0f, m32, 0f);
    }

    /**
     * Perspective projection with the far plane at infinity and conventional (non-reversed) depth. For reversed
     * infinite depth, which has better precision, use {@link #perspectiveReversedZ}.
     */
    public static Mat4f perspectiveInfinite(float fovy, float aspect, float near, boolean zZeroToOne) {
        float h = (float) Math.tan(fovy * 0.5f);
        return new Mat4f(
                1f / (h * aspect), 0f, 0f, 0f,
                0f, 1f / h, 0f, 0f,
                0f, 0f, -1f, -1f,
                0f, 0f, zZeroToOne ? -near : -2f * near, 0f);
    }

    /** Right-handed view matrix for a camera at {@code eye} looking along {@code direction}. */
    public static Mat4f lookTo(Vec3f eye, Vec3f direction, Vec3f up) {
        return lookAt(eye, eye.add(direction), up);
    }

    // ---------------------------------------------------------------- algebra

    /**
     * Matrix product {@code this * b}: transforming by the result applies {@code b} first.
     *
     * <p>Written as four column products on purpose. A single 16-expression body is ~630 bytecodes, above HotSpot's
     * inlining limit (325), so the JIT would not inline it and could not scalar-replace the result. Each
     * {@link #mulColumn} is small enough to inline, which keeps chains like {@code a.mul(b).transformPosition(p)}
     * allocation-free (see {@code docs/PERFORMANCE.md}).
     */
    public Mat4f mul(Mat4f b) {
        Vec4f c0 = mulColumn(b.m00, b.m01, b.m02, b.m03);
        Vec4f c1 = mulColumn(b.m10, b.m11, b.m12, b.m13);
        Vec4f c2 = mulColumn(b.m20, b.m21, b.m22, b.m23);
        Vec4f c3 = mulColumn(b.m30, b.m31, b.m32, b.m33);
        return new Mat4f(
                c0.x(), c0.y(), c0.z(), c0.w(),
                c1.x(), c1.y(), c1.z(), c1.w(),
                c2.x(), c2.y(), c2.z(), c2.w(),
                c3.x(), c3.y(), c3.z(), c3.w());
    }

    /** {@code this * (x, y, z, w)}. */
    private Vec4f mulColumn(float x, float y, float z, float w) {
        return new Vec4f(
                m00 * x + m10 * y + m20 * z + m30 * w,
                m01 * x + m11 * y + m21 * z + m31 * w,
                m02 * x + m12 * y + m22 * z + m32 * w,
                m03 * x + m13 * y + m23 * z + m33 * w);
    }

    public Mat4f transpose() {
        return new Mat4f(
                m00, m10, m20, m30,
                m01, m11, m21, m31,
                m02, m12, m22, m32,
                m03, m13, m23, m33);
    }

    public float determinant() {
        return (m00 * m11 - m01 * m10) * (m22 * m33 - m23 * m32)
                + (m02 * m10 - m00 * m12) * (m21 * m33 - m23 * m31)
                + (m00 * m13 - m03 * m10) * (m21 * m32 - m22 * m31)
                + (m01 * m12 - m02 * m11) * (m20 * m33 - m23 * m30)
                + (m03 * m11 - m01 * m13) * (m20 * m32 - m22 * m30)
                + (m02 * m13 - m03 * m12) * (m20 * m31 - m21 * m30);
    }

    /** General inverse. A singular matrix yields non-finite components. */
    public Mat4f invert() {
        float a = m00 * m11 - m01 * m10;
        float b = m00 * m12 - m02 * m10;
        float c = m00 * m13 - m03 * m10;
        float d = m01 * m12 - m02 * m11;
        float e = m01 * m13 - m03 * m11;
        float f = m02 * m13 - m03 * m12;
        float g = m20 * m31 - m21 * m30;
        float h = m20 * m32 - m22 * m30;
        float i = m20 * m33 - m23 * m30;
        float j = m21 * m32 - m22 * m31;
        float k = m21 * m33 - m23 * m31;
        float l = m22 * m33 - m23 * m32;
        float det = 1f / (a * l - b * k + c * j + d * i - e * h + f * g);
        return new Mat4f(
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
    public Mat4f invertAffine() {
        Mat3f r = upperLeft3x3().invert();
        float tx = -(r.m00() * m30 + r.m10() * m31 + r.m20() * m32);
        float ty = -(r.m01() * m30 + r.m11() * m31 + r.m21() * m32);
        float tz = -(r.m02() * m30 + r.m12() * m31 + r.m22() * m32);
        return new Mat4f(
                r.m00(), r.m01(), r.m02(), 0f,
                r.m10(), r.m11(), r.m12(), 0f,
                r.m20(), r.m21(), r.m22(), 0f,
                tx, ty, tz, 1f);
    }

    // ---------------------------------------------------------------- transforms

    public Vec4f transform(Vec4f v) {
        float x = v.x(), y = v.y(), z = v.z(), w = v.w();
        return new Vec4f(
                m00 * x + m10 * y + m20 * z + m30 * w,
                m01 * x + m11 * y + m21 * z + m31 * w,
                m02 * x + m12 * y + m22 * z + m32 * w,
                m03 * x + m13 * y + m23 * z + m33 * w);
    }

    /** Transforms a point ({@code w = 1}) and ignores the projective row. Use for affine matrices. */
    public Vec3f transformPosition(Vec3f p) {
        float x = p.x(), y = p.y(), z = p.z();
        return new Vec3f(
                m00 * x + m10 * y + m20 * z + m30,
                m01 * x + m11 * y + m21 * z + m31,
                m02 * x + m12 * y + m22 * z + m32);
    }

    /** Transforms a direction ({@code w = 0}): no translation. */
    public Vec3f transformDirection(Vec3f d) {
        float x = d.x(), y = d.y(), z = d.z();
        return new Vec3f(
                m00 * x + m10 * y + m20 * z,
                m01 * x + m11 * y + m21 * z,
                m02 * x + m12 * y + m22 * z);
    }

    /** Transforms a point ({@code w = 1}) and performs the perspective divide. */
    public Vec3f transformProject(Vec3f p) {
        float x = p.x(), y = p.y(), z = p.z();
        float invW = 1f / (m03 * x + m13 * y + m23 * z + m33);
        return new Vec3f(
                (m00 * x + m10 * y + m20 * z + m30) * invW,
                (m01 * x + m11 * y + m21 * z + m31) * invW,
                (m02 * x + m12 * y + m22 * z + m32) * invW);
    }

    // ---------------------------------------------------------------- accessors

    public Mat3f upperLeft3x3() {
        return new Mat3f(
                m00, m01, m02,
                m10, m11, m12,
                m20, m21, m22);
    }

    /** Inverse-transpose of the upper-left 3x3: the matrix for transforming normals. */
    public Mat3f normalMatrix() {
        return upperLeft3x3().normal();
    }

    public Vec3f getTranslation() {
        return new Vec3f(m30, m31, m32);
    }

    /** Returns a copy with the translation column replaced. */
    public Mat4f withTranslation(Vec3f t) {
        return new Mat4f(
                m00, m01, m02, m03,
                m10, m11, m12, m13,
                m20, m21, m22, m23,
                t.x(), t.y(), t.z(), m33);
    }

    public Vec4f column(int c) {
        return switch (c) {
            case 0 -> new Vec4f(m00, m01, m02, m03);
            case 1 -> new Vec4f(m10, m11, m12, m13);
            case 2 -> new Vec4f(m20, m21, m22, m23);
            case 3 -> new Vec4f(m30, m31, m32, m33);
            default -> throw new IndexOutOfBoundsException(c);
        };
    }

    public Vec4f row(int r) {
        return switch (r) {
            case 0 -> new Vec4f(m00, m10, m20, m30);
            case 1 -> new Vec4f(m01, m11, m21, m31);
            case 2 -> new Vec4f(m02, m12, m22, m32);
            case 3 -> new Vec4f(m03, m13, m23, m33);
            default -> throw new IndexOutOfBoundsException(r);
        };
    }

    public float get(int column, int row) {
        return column(column).get(row);
    }

    /** Result of {@link #decompose()}. */
    @ValueType
    public record Trs(Vec3f translation, Quatf rotation, Vec3f scale) {
    }

    /**
     * Splits a model matrix {@code T * R * S} back into translation, rotation and scale. Handles mirrored
     * matrices by folding the reflection into a negative x scale. Shear is not represented: for a sheared matrix
     * the result recomposes to a different matrix. A zero-scale axis yields NaN.
     */
    public Trs decompose() {
        float sx = (float) Math.sqrt(m00 * m00 + m01 * m01 + m02 * m02);
        float sy = (float) Math.sqrt(m10 * m10 + m11 * m11 + m12 * m12);
        float sz = (float) Math.sqrt(m20 * m20 + m21 * m21 + m22 * m22);
        if (upperLeft3x3().determinant() < 0f) {
            sx = -sx;
        }
        float ix = 1f / sx, iy = 1f / sy, iz = 1f / sz;
        Mat3f rot = new Mat3f(
                m00 * ix, m01 * ix, m02 * ix,
                m10 * iy, m11 * iy, m12 * iy,
                m20 * iz, m21 * iz, m22 * iz);
        return new Trs(getTranslation(), Quatf.fromMat3(rot), new Vec3f(sx, sy, sz));
    }

    /** True when the bottom row is {@code (0, 0, 0, 1)} within {@code eps}: no projection component. */
    public boolean isAffine(float eps) {
        return Math.abs(m03) <= eps && Math.abs(m13) <= eps && Math.abs(m23) <= eps && Math.abs(m33 - 1f) <= eps;
    }

    public boolean approxEquals(Mat4f o, float eps) {
        for (int c = 0; c < 4; c++) {
            if (!column(c).approxEquals(o.column(c), eps)) {
                return false;
            }
        }
        return true;
    }

    public boolean isFinite() {
        for (int c = 0; c < 4; c++) {
            Vec4f v = column(c);
            if (!Float.isFinite(v.x()) || !Float.isFinite(v.y()) || !Float.isFinite(v.z()) || !Float.isFinite(v.w())) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- output

    /** Writes 16 column-major values. */
    public void writeTo(float[] dst, int off) {
        dst[off] = m00; dst[off + 1] = m01; dst[off + 2] = m02; dst[off + 3] = m03;
        dst[off + 4] = m10; dst[off + 5] = m11; dst[off + 6] = m12; dst[off + 7] = m13;
        dst[off + 8] = m20; dst[off + 9] = m21; dst[off + 10] = m22; dst[off + 11] = m23;
        dst[off + 12] = m30; dst[off + 13] = m31; dst[off + 14] = m32; dst[off + 15] = m33;
    }

    /**
     * Absolute write of 16 column-major values at {@code index}; does not change the buffer
     * position. Ready for {@code glUniformMatrix4*v(loc, false, buf)}.
     */
    public void writeTo(FloatBuffer dst, int index) {
        dst.put(index, m00).put(index + 1, m01).put(index + 2, m02).put(index + 3, m03)
                .put(index + 4, m10).put(index + 5, m11).put(index + 6, m12).put(index + 7, m13)
                .put(index + 8, m20).put(index + 9, m21).put(index + 10, m22).put(index + 11, m23)
                .put(index + 12, m30).put(index + 13, m31).put(index + 14, m32).put(index + 15, m33);
    }

    @FloatOnly
    public Mat4d toDouble() {
        return new Mat4d(
                m00, m01, m02, m03,
                m10, m11, m12, m13,
                m20, m21, m22, m23,
                m30, m31, m32, m33);
    }

    @DoubleOnly
    public Mat4f toFloat() {
        return new Mat4f(
                (float) m00, (float) m01, (float) m02, (float) m03,
                (float) m10, (float) m11, (float) m12, (float) m13,
                (float) m20, (float) m21, (float) m22, (float) m23,
                (float) m30, (float) m31, (float) m32, (float) m33);
    }
}
