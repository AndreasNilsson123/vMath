package vmath.core;

import vmath.annotations.Bulk;
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
 * <p>Valhalla: {@code -Pvalhalla} builds turn {@code @ValueType} into a real {@code value record}.
 * At 64 bytes this type is unlikely to be heap-flattened by early Valhalla builds, but it still
 * loses identity and benefits from scalarization in compiled code. Keep bulk matrix data in plain
 * arrays or buffers, not in {@code Mat4f[]}.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads without
 * synchronization.
 *
 * <p><b>Example:</b> a model-view-projection matrix
 *
 * <pre>{@code
 * Mat4f projection = Mat4f.perspective((float) Math.toRadians(60), 16f / 9f, 0.1f, 500f, ClipSpace.VULKAN);
 * Mat4f view = Mat4f.lookAt(new Vec3f(0f, 2f, 5f), Vec3f.ZERO, Vec3f.UNIT_Y);
 * Mat4f model = Mat4f.translationRotateScale(new Vec3f(1f, 0f, 0f), Quatf.rotationY(0.5f), Vec3f.ONE);
 * Mat4f mvp = projection.mul(view).mul(model);            // the right factor is applied first
 * Vec4f clip = mvp.transform(new Vec4f(0f, 0f, 0f, 1f));
 * }</pre>
 *
 * <p><b>Example:</b> writing a uniform for the GPU
 *
 * <pre>{@code
 * Mat4f m = Mat4f.IDENTITY;
 * float[] uniform = new float[16];
 * m.writeTo(uniform, 0);                                  // column-major
 * FloatBuffer buffer = FloatBuffer.allocate(16);
 * m.writeTo(buffer, 0);                                   // absolute write: the position does not change
 * }</pre>
 *
 * @param m00 the m00
 * @param m01 the m01
 * @param m02 the m02
 * @param m03 the m03
 * @param m10 the m10
 * @param m11 the m11
 * @param m12 the m12
 * @param m13 the m13
 * @param m20 the m20
 * @param m21 the m21
 * @param m22 the m22
 * @param m23 the m23
 * @param m30 the m30
 * @param m31 the m31
 * @param m32 the m32
 * @param m33 the m33
 */
@GenerateDouble
@ValueType
public record Mat4f(
        float m00, float m01, float m02, float m03,
        float m10, float m11, float m12, float m13,
        float m20, float m21, float m22, float m23,
        float m30, float m31, float m32, float m33) {

    /**
     * The identity matrix.
     */
    public static final Mat4f IDENTITY = new Mat4f(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f);

    // ---------------------------------------------------------------- factories

    /**
     * Builds a matrix from its four column vectors; the usual way to write a basis plus origin.
     *
     * @param c0 the vector; must not be {@code null}
     * @param c1 the vector; must not be {@code null}
     * @param c2 the vector; must not be {@code null}
     * @param c3 the vector; must not be {@code null}
     * @return the matrix with the given columns
     */
    public static Mat4f fromColumns(Vec4f c0, Vec4f c1, Vec4f c2, Vec4f c3) {
        return new Mat4f(
                c0.x(), c0.y(), c0.z(), c0.w(),
                c1.x(), c1.y(), c1.z(), c1.w(),
                c2.x(), c2.y(), c2.z(), c2.w(),
                c3.x(), c3.y(), c3.z(), c3.w());
    }

    /**
     * Reads 16 column-major values starting at {@code off}.
     *
     * @param src the source to read from
     * @param off the index of the first element to read or write
     * @return the matrix, never {@code null}
     */
    public static Mat4f fromArray(float[] src, int off) {
        return new Mat4f(
                src[off], src[off + 1], src[off + 2], src[off + 3],
                src[off + 4], src[off + 5], src[off + 6], src[off + 7],
                src[off + 8], src[off + 9], src[off + 10], src[off + 11],
                src[off + 12], src[off + 13], src[off + 14], src[off + 15]);
    }

    /**
     * Builds a translation.
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @return a translation by the given offset
     */
    public static Mat4f translation(float x, float y, float z) {
        return new Mat4f(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                x, y, z, 1f);
    }

    /**
     * Builds a translation from a vector.
     *
     * @param t the vector; must not be {@code null}
     * @return a translation by the given offset
     */
    public static Mat4f translation(Vec3f t) {
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
    public static Mat4f scaling(float sx, float sy, float sz) {
        return new Mat4f(
                sx, 0f, 0f, 0f,
                0f, sy, 0f, 0f,
                0f, 0f, sz, 0f,
                0f, 0f, 0f, 1f);
    }

    /**
     * Converts a unit quaternion to the equivalent rotation matrix; the quaternion is assumed to be
     * normalised, and a non-unit one yields a scaled matrix.
     *
     * @param q the quaternion; must not be {@code null}
     * @return rotation matrix for a unit quaternion
     */
    public static Mat4f rotation(Quatf q) {
        return translationRotateScale(Vec3f.ZERO, q, Vec3f.ONE);
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
    public static Mat4f translationRotateScale(Vec3f t, Quatf q, Vec3f s) {
        Mat3f r = Mat3f.rotation(q);
        float sx = s.x(), sy = s.y(), sz = s.z();
        return new Mat4f(
                r.m00() * sx, r.m01() * sx, r.m02() * sx, 0f,
                r.m10() * sy, r.m11() * sy, r.m12() * sy, 0f,
                r.m20() * sz, r.m21() * sz, r.m22() * sz, 0f,
                t.x(), t.y(), t.z(), 1f);
    }

    /**
     * Embeds a 3x3 matrix in the upper-left corner of an identity matrix.
     *
     * @param m the matrix; must not be {@code null}
     * @return the 4x4 matrix, never {@code null}
     */
    public static Mat4f fromMat3(Mat3f m) {
        return new Mat4f(
                m.m00(), m.m01(), m.m02(), 0f,
                m.m10(), m.m11(), m.m12(), 0f,
                m.m20(), m.m21(), m.m22(), 0f,
                0f, 0f, 0f, 1f);
    }

    /**
     * Builds a perspective projection with a finite far plane; this overload takes the depth
     * convention as a flag, and the {@link ClipSpace} overload is preferred for new code.
     *
     * @param fovy       vertical field of view in radians
     *
     * @param aspect the aspect ratio, width divided by height
     * @param near the distance to the near plane
     * @param far the distance to the far plane
     * @param zZeroToOne {@code true} for NDC depth [0, 1] (Vulkan, D3D, GL with
     *     {@code glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)}), {@code false} for GL's [-1, 1]
     * @return symmetric perspective projection with finite near and far planes
     * @deprecated Use the {@link ClipSpace} overload: {@code true} is {@link ClipSpace#D3D} and
     *     {@code false} is {@link ClipSpace#OPENGL}.
     */
    @Deprecated(since = "0.2.0")
    public static Mat4f perspective(float fovy, float aspect, float near, float far, boolean zZeroToOne) {
        return perspectiveDepth(fovy, aspect, near, far, zZeroToOne);
    }

    // the boolean conventions: true is NDC depth [0, 1], false is [-1, 1]
    private static Mat4f perspectiveDepth(float fovy, float aspect, float near, float far, boolean zZeroToOne) {
        float h = (float) Math.tan(fovy * 0.5f);
        float m22 = (zZeroToOne ? far : far + near) / (near - far);
        float m32 = (zZeroToOne ? far : far + far) * near / (near - far);
        return new Mat4f(
                1f / (h * aspect), 0f, 0f, 0f,
                0f, 1f / h, 0f, 0f,
                0f, 0f, m22, -1f,
                0f, 0f, m32, 0f);
    }

    /**
     * Builds a perspective projection for the conventions of a graphics API, including the depth
     * range and the direction of the y axis.
     *
     * @param fovy the vertical field of view in radians
     * @param aspect the aspect ratio, width divided by height
     * @param near the distance to the near plane
     * @param far the distance to the far plane
     * @param space the space; must not be {@code null}
     * @return symmetric perspective projection for a graphics API's {@link ClipSpace} (depth range
     *     and Y direction)
     */
    public static Mat4f perspective(float fovy, float aspect, float near, float far, ClipSpace space) {
        Mat4f m = perspectiveDepth(fovy, aspect, near, far, space.zeroToOne());
        return space.yDown() ? m.flipY() : m;
    }

    /**
     * Builds a perspective projection with the far plane at infinity, which removes far-plane
     * clipping; depth precision is conventional, so see the reversed-z variant for better
     * precision.
     *
     * @param fovy the vertical field of view in radians
     * @param aspect the aspect ratio, width divided by height
     * @param near the distance to the near plane
     * @param space the space; must not be {@code null}
     * @return infinite-far perspective (conventional depth) for a {@link ClipSpace}
     */
    public static Mat4f perspectiveInfinite(float fovy, float aspect, float near, ClipSpace space) {
        Mat4f m = perspectiveInfiniteDepth(fovy, aspect, near, space.zeroToOne());
        return space.yDown() ? m.flipY() : m;
    }

    /**
     * Builds a perspective projection with infinite far plane and reversed depth, which gives the
     * best depth-buffer precision when combined with a floating-point depth buffer and a
     * greater-than depth test.
     *
     * <p>Reversed depth needs a [0, 1] range, so {@link ClipSpace#OPENGL} is rejected (in OpenGL
     * use {@link ClipSpace#D3D} together with
     * {@code glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)}).
     *
     * @param fovy the vertical field of view in radians
     * @param aspect the aspect ratio, width divided by height
     * @param near the distance to the near plane
     * @param space the space; must not be {@code null}
     * @return Reversed-Z infinite perspective for a {@link ClipSpace}
     * @throws IllegalArgumentException if the clip space does not have a depth range of 0 to 1
     */
    public static Mat4f perspectiveReversedZ(float fovy, float aspect, float near, ClipSpace space) {
        if (!space.zeroToOne()) {
            throw new IllegalArgumentException("reversed-Z needs a [0, 1] depth range; use ClipSpace.D3D with glClipControl in OpenGL");
        }
        Mat4f m = perspectiveReversedZ(fovy, aspect, near);
        return space.yDown() ? m.flipY() : m;
    }

    /**
     * Builds a reversed-z perspective projection with infinite far plane for a zero-to-one depth
     * range; requires a floating-point depth buffer and a greater-than depth test to pay off.
     *
     * <p>Pair with a floating-point depth buffer,
     * {@code glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)}, {@code glClearDepth(0)} and
     * {@code GL_GREATER}. This gives near-uniform depth precision, which is what globe-scale scenes
     * need.
     *
     * @param fovy the vertical field of view in radians
     * @param aspect the aspect ratio, width divided by height
     * @param near the distance to the near plane
     * @return Reversed-Z perspective with an infinite far plane and NDC depth [0, 1]: the near
     *     plane maps to depth 1 and infinity to depth 0
     */
    public static Mat4f perspectiveReversedZ(float fovy, float aspect, float near) {
        float h = (float) Math.tan(fovy * 0.5f);
        return new Mat4f(
                1f / (h * aspect), 0f, 0f, 0f,
                0f, 1f / h, 0f, 0f,
                0f, 0f, 0f, -1f,
                0f, 0f, near, 0f);
    }

    /**
     * Builds an orthographic projection; this overload takes the depth convention as a flag, and
     * the {@link ClipSpace} overload is preferred for new code.
     *
     * <p>See {@link #perspective} for {@code zZeroToOne}.
     *
     * @param left the left
     * @param right the right
     * @param bottom the bottom
     * @param top the top
     * @param near the distance to the near plane
     * @param far the distance to the far plane
     * @param zZeroToOne whether z zero to one
     * @return orthographic projection
     * @deprecated Use the {@link ClipSpace} overload: {@code true} is {@link ClipSpace#D3D} and
     *     {@code false} is {@link ClipSpace#OPENGL}.
     */
    @Deprecated(since = "0.2.0")
    public static Mat4f ortho(float left, float right, float bottom, float top,
                              float near, float far, boolean zZeroToOne) {
        return orthoDepth(left, right, bottom, top, near, far, zZeroToOne);
    }

    // the boolean conventions: true is NDC depth [0, 1], false is [-1, 1]
    private static Mat4f orthoDepth(float left, float right, float bottom, float top,
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

    /**
     * Builds an orthographic projection for the conventions of a graphics API, including the depth
     * range and the direction of the y axis.
     *
     * @param left the left
     * @param right the right
     * @param bottom the bottom
     * @param top the top
     * @param near the distance to the near plane
     * @param far the distance to the far plane
     * @param space the space; must not be {@code null}
     * @return orthographic projection for a {@link ClipSpace}
     */
    public static Mat4f ortho(float left, float right, float bottom, float top, float near, float far, ClipSpace space) {
        Mat4f m = orthoDepth(left, right, bottom, top, near, far, space.zeroToOne());
        return space.yDown() ? m.flipY() : m;
    }

    /**
     * Builds an orthographic projection with reversed depth, which together with a floating-point
     * depth buffer spreads depth precision more evenly; requires a greater-than depth test.
     *
     * <p>Needs a clip space with a {@code [0, 1]} depth range ({@link ClipSpace#D3D} or
     * {@link ClipSpace#VULKAN}); {@link IllegalArgumentException} otherwise, as for
     * {@link #perspectiveReversedZ(float, float, float, ClipSpace)}.
     *
     * @param left the left
     * @param right the right
     * @param bottom the bottom
     * @param top the top
     * @param near the distance to the near plane
     * @param far the distance to the far plane
     * @param space the space; must not be {@code null}
     * @return orthographic projection with reversed depth: the near plane maps to depth 1 and the
     *     far plane to 0, which together with a floating-point depth buffer spreads precision
     *     evenly
     * @throws IllegalArgumentException if the clip space does not have a depth range of 0 to 1
     */
    public static Mat4f orthoReversedZ(float left, float right, float bottom, float top, float near, float far, ClipSpace space) {
        if (!space.zeroToOne()) {
            throw new IllegalArgumentException("reversed-Z needs a [0, 1] depth range; use ClipSpace.D3D with glClipControl in OpenGL");
        }
        Mat4f m = ortho(left, right, bottom, top, near, far, ClipSpace.D3D);
        // depth z' = w - z: the same projection with the depth row replaced by the w row minus the depth row
        Mat4f r = new Mat4f(
                m.m00, m.m01, m.m03 - m.m02, m.m03,
                m.m10, m.m11, m.m13 - m.m12, m.m13,
                m.m20, m.m21, m.m23 - m.m22, m.m23,
                m.m30, m.m31, m.m33 - m.m32, m.m33);
        return space.yDown() ? r.flipY() : r;
    }

    /**
     * Builds an off-centre perspective projection from the edges of the near plane, for conventions
     * of a graphics API; used for stereo, tiled rendering and portals.
     *
     * @param left the left
     * @param right the right
     * @param bottom the bottom
     * @param top the top
     * @param near the distance to the near plane
     * @param far the distance to the far plane
     * @param space the space; must not be {@code null}
     * @return asymmetric perspective frustum for a {@link ClipSpace}
     */
    public static Mat4f frustum(float left, float right, float bottom, float top, float near, float far, ClipSpace space) {
        Mat4f m = frustumDepth(left, right, bottom, top, near, far, space.zeroToOne());
        return space.yDown() ? m.flipY() : m;
    }

    /**
     * Returns this matrix with the output {@code y} mirrored: {@code diag(1, -1, 1, 1) * this},
     * that is, row 1 negated.
     *
     * <p>Turns a y-up projection into the y-down clip space of Vulkan. Flipping twice gives the
     * original; a matrix applied to a view-space point and then flipped has the same
     * {@code x, z, w}.
     *
     * @return the matrix with the output y mirrored, never {@code null}
     */
    public Mat4f flipY() {
        return new Mat4f(
                m00, -m01, m02, m03,
                m10, -m11, m12, m13,
                m20, -m21, m22, m23,
                m30, -m31, m32, m33);
    }

    /**
     * Builds a right-handed view matrix from an eye position, a target and an up direction; the up
     * direction must not be parallel to the view direction.
     *
     * @param eye the eye; must not be {@code null}
     * @param center the center; must not be {@code null}
     * @param up the vector; must not be {@code null}
     * @return right-handed view matrix looking from {@code eye} towards {@code center}
     */
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

    /**
     * Builds a rotation about the X axis, using the right-handed convention.
     *
     * @param angle the angle in radians
     * @return a rotation about the X axis by {@code angle} radians (right-handed: counter-clockwise
     *     looking down the axis toward the origin)
     */
    public static Mat4f rotationX(float angle) {
        return fromMat3(Mat3f.rotationX(angle));
    }

    /**
     * Builds a rotation about the Y axis, using the right-handed convention.
     *
     * @param angle the angle in radians
     * @return a rotation about the Y axis by {@code angle} radians (right-handed: counter-clockwise
     *     looking down the axis toward the origin)
     */
    public static Mat4f rotationY(float angle) {
        return fromMat3(Mat3f.rotationY(angle));
    }

    /**
     * Builds a rotation about the Z axis, using the right-handed convention.
     *
     * @param angle the angle in radians
     * @return a rotation about the Z axis by {@code angle} radians (right-handed: counter-clockwise
     *     looking down the axis toward the origin)
     */
    public static Mat4f rotationZ(float angle) {
        return fromMat3(Mat3f.rotationZ(angle));
    }

    /**
     * Builds a rotation about an arbitrary axis with the Rodrigues formula; the axis is normalised
     * first, so a zero axis gives non-finite values.
     *
     * @param angle the angle in radians
     * @param axis the axis; must not be {@code null}
     * @return rotation of {@code angle} radians about {@code axis} (normalized internally)
     */
    public static Mat4f rotationAxis(float angle, Vec3f axis) {
        return fromMat3(Mat3f.rotationAxis(angle, axis));
    }

    /**
     * Builds an off-centre perspective projection from the edges of the near plane; used for
     * stereo, tiled rendering and portals.
     *
     * <p>See {@link #perspective} for {@code zZeroToOne}.
     *
     * @param left the left
     * @param right the right
     * @param bottom the bottom
     * @param top the top
     * @param near the distance to the near plane
     * @param far the distance to the far plane
     * @param zZeroToOne whether z zero to one
     * @return asymmetric perspective frustum (off-center projection, e.g. for stereo, tiles or
     *     portals)
     * @deprecated Use the {@link ClipSpace} overload: {@code true} is {@link ClipSpace#D3D} and
     *     {@code false} is {@link ClipSpace#OPENGL}.
     */
    @Deprecated(since = "0.2.0")
    public static Mat4f frustum(float left, float right, float bottom, float top,
                                float near, float far, boolean zZeroToOne) {
        return frustumDepth(left, right, bottom, top, near, far, zZeroToOne);
    }

    // the boolean conventions: true is NDC depth [0, 1], false is [-1, 1]
    private static Mat4f frustumDepth(float left, float right, float bottom, float top,
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
     * Builds a perspective projection with the far plane at infinity, which removes far-plane
     * clipping.
     *
     * <p>For reversed infinite depth, which has better precision, use
     * {@link #perspectiveReversedZ}.
     *
     * @param fovy the vertical field of view in radians
     * @param aspect the aspect ratio, width divided by height
     * @param near the distance to the near plane
     * @param zZeroToOne whether z zero to one
     * @return perspective projection with the far plane at infinity and conventional (non-reversed)
     *     depth
     * @deprecated Use the {@link ClipSpace} overload: {@code true} is {@link ClipSpace#D3D} and
     *     {@code false} is {@link ClipSpace#OPENGL}.
     */
    @Deprecated(since = "0.2.0")
    public static Mat4f perspectiveInfinite(float fovy, float aspect, float near, boolean zZeroToOne) {
        return perspectiveInfiniteDepth(fovy, aspect, near, zZeroToOne);
    }

    // the boolean conventions: true is NDC depth [0, 1], false is [-1, 1]
    private static Mat4f perspectiveInfiniteDepth(float fovy, float aspect, float near, boolean zZeroToOne) {
        float h = (float) Math.tan(fovy * 0.5f);
        return new Mat4f(
                1f / (h * aspect), 0f, 0f, 0f,
                0f, 1f / h, 0f, 0f,
                0f, 0f, -1f, -1f,
                0f, 0f, zZeroToOne ? -near : -2f * near, 0f);
    }

    /**
     * Builds a right-handed view matrix from an eye position and a viewing direction; the direction
     * must not be parallel to the up vector.
     *
     * @param eye the eye; must not be {@code null}
     * @param direction the direction; must not be {@code null}
     * @param up the vector; must not be {@code null}
     * @return right-handed view matrix for a camera at {@code eye} looking along {@code direction}
     */
    public static Mat4f lookTo(Vec3f eye, Vec3f direction, Vec3f up) {
        return lookAt(eye, eye.add(direction), up);
    }

    // ---------------------------------------------------------------- algebra

    /**
     * Composes two transforms by matrix multiplication; the product is not commutative, and the
     * right operand acts on the vector first.
     *
     * <p>A single 16-expression body is ~630 bytecodes, above HotSpot's inlining limit (325), so
     * the JIT would not inline it and could not scalar-replace the result. Each {@link #mulColumn}
     * is small enough to inline, which keeps chains like {@code a.mul(b).transformPosition(p)}
     * allocation-free (see {@code docs/PERFORMANCE.md}).
     *
     * @param b the second matrix; must not be {@code null}
     * @return matrix product {@code this * b}: transforming by the result applies {@code b} first.
     *     <p>Written as four column products on purpose
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

    /**
     * {@code this * (x, y, z, w)}.
     */
    private Vec4f mulColumn(float x, float y, float z, float w) {
        return new Vec4f(
                m00 * x + m10 * y + m20 * z + m30 * w,
                m01 * x + m11 * y + m21 * z + m31 * w,
                m02 * x + m12 * y + m22 * z + m32 * w,
                m03 * x + m13 * y + m23 * z + m33 * w);
    }

    /**
     * Swaps rows and columns; the inverse of a rotation matrix.
     *
     * @return the transpose: rows and columns exchanged
     */
    public Mat4f transpose() {
        return new Mat4f(
                m00, m10, m20, m30,
                m01, m11, m21, m31,
                m02, m12, m22, m32,
                m03, m13, m23, m33);
    }

    /**
     * Computes the determinant by cofactor expansion of 2x2 minors; zero for a singular matrix.
     *
     * @return the determinant
     */
    public float determinant() {
        return (m00 * m11 - m01 * m10) * (m22 * m33 - m23 * m32)
                + (m02 * m10 - m00 * m12) * (m21 * m33 - m23 * m31)
                + (m00 * m13 - m03 * m10) * (m21 * m32 - m22 * m31)
                + (m01 * m12 - m02 * m11) * (m20 * m33 - m23 * m30)
                + (m03 * m11 - m01 * m13) * (m20 * m32 - m22 * m30)
                + (m02 * m13 - m03 * m12) * (m20 * m31 - m21 * m30);
    }

    /**
     * Inverts the matrix with the cofactor method over 2x2 minors; when the matrix is known to be
     * affine, {@link #invertAffine()} is cheaper.
     *
     * <p>A singular matrix yields non-finite components.
     *
     * @return general inverse
     */
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
     * Builds a shear matrix in which each axis is displaced in proportion to the other two.
     *
     * <p>For example {@code xy} slides every point along X in proportion to its Y. The determinant
     * is 1 when only one of the six factors is non-zero.
     *
     * @param xy the amount by which y contributes to x
     * @param xz the amount by which z contributes to x
     * @param yx the amount by which x contributes to y
     * @param yz the amount by which z contributes to y
     * @param zx the amount by which x contributes to z
     * @param zy the amount by which y contributes to z
     * @return a shear matrix: {@code x' = x + xy y + xz z}, {@code y' = y + yx x + yz z},
     *     {@code z' = z + zx x + zy y}
     */
    public static Mat4f shear(float xy, float xz, float yx, float yz, float zx, float zy) {
        return new Mat4f(
                1f, yx, zx, 0f,
                xy, 1f, zy, 0f,
                xz, yz, 1f, 0f,
                0f, 0f, 0f, 1f);
    }

    /**
     * Inverts a projection matrix of the kinds this class builds in closed form, which is cheaper
     * and more accurate than the general inverse; other matrices give wrong results.
     *
     * <p>It is much cheaper than the general {@link #invert()} (2.5 times faster in the benchmark
     * of {@code docs/API.md}) and unprojects clip-space points to eye space. The result is
     * meaningless for any other matrix (check with {@link #isProjection}); a degenerate projection
     * yields non-finite components.
     *
     * @return inverse of a perspective, orthographic or off-centre frustum projection of the kind
     *     this class builds ({@link #perspective}, {@link #perspectiveInfinite},
     *     {@link #perspectiveReversedZ}, {@link #ortho}, {@link #frustum}, in any
     *     {@link ClipSpace}): a matrix in which clip x and y depend on eye x (or y) and z only, and
     *     clip z and w on eye z only
     */
    public Mat4f invertProjection() {
        float a = m00, b = m11, c = m20, d = m21, tx = m30, ty = m31, e = m22, f = m23, g = m32, h = m33;
        float det = 1f / (e * h - g * f);
        return new Mat4f(
                1f / a, 0f, 0f, 0f,
                0f, 1f / b, 0f, 0f,
                (tx * f - c * h) * det / a, (ty * f - d * h) * det / b, h * det, -f * det,
                (c * g - tx * e) * det / a, (d * g - ty * e) * det / b, -g * det, e * det);
    }

    /**
     * Tests whether the matrix has the structure that {@link #invertProjection()} requires, within
     * a tolerance.
     *
     * @param eps the tolerance
     * @return {@code true} when the matrix has the structure {@link #invertProjection()} requires,
     *     within {@code eps}: the six entries that must be zero are zero and the x and y scales are
     *     not
     */
    public boolean isProjection(float eps) {
        return Math.abs(m01) <= eps && Math.abs(m02) <= eps && Math.abs(m03) <= eps && Math.abs(m10) <= eps && Math.abs(m12) <= eps && Math.abs(m13) <= eps
                && Math.abs(m00) > eps && Math.abs(m11) > eps;
    }

    /**
     * Tests whether the upper-left 3x3 part consists of perpendicular unit vectors within a
     * tolerance, which holds for rotations and for rotations combined with a mirror.
     *
     * <p>Translation and the bottom row are ignored; combine with {@link #isAffine} for a rigid
     * transform.
     *
     * @param eps the tolerance
     * @return {@code true} when the upper-left 3x3 part is orthonormal within {@code eps}: its
     *     columns have length 1 and are perpendicular, so it is a rotation or a rotation with a
     *     reflection (check the determinant to tell them apart)
     */
    public boolean isOrthonormal(float eps) {
        float d00 = m00 * m00 + m01 * m01 + m02 * m02 - 1f, d11 = m10 * m10 + m11 * m11 + m12 * m12 - 1f, d22 = m20 * m20 + m21 * m21 + m22 * m22 - 1f;
        float d01 = m00 * m10 + m01 * m11 + m02 * m12, d02 = m00 * m20 + m01 * m21 + m02 * m22, d12 = m10 * m20 + m11 * m21 + m12 * m22;
        return Math.abs(d00) <= eps && Math.abs(d11) <= eps && Math.abs(d22) <= eps && Math.abs(d01) <= eps && Math.abs(d02) <= eps && Math.abs(d12) <= eps;
    }

    /**
     * Inverts a matrix that is known to be affine by inverting the 3x3 block and transforming the
     * translation; cheaper than the general inverse, and wrong for projections.
     *
     * <p>Do not use on projection matrices.
     *
     * @return fast inverse for affine matrices (last row {@code 0 0 0 1}): model and view matrices
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

    /**
     * Applies the matrix to a homogeneous column vector.
     *
     * @param v the vector; must not be {@code null}
     * @return the matrix times the vector {@code v}
     */
    @Bulk(uniform = "this")
    public Vec4f transform(Vec4f v) {
        float x = v.x(), y = v.y(), z = v.z(), w = v.w();
        return new Vec4f(
                m00 * x + m10 * y + m20 * z + m30 * w,
                m01 * x + m11 * y + m21 * z + m31 * w,
                m02 * x + m12 * y + m22 * z + m32 * w,
                m03 * x + m13 * y + m23 * z + m33 * w);
    }

    /**
     * Transforms a point ({@code w = 1}) and ignores the projective row.
     *
     * <p>Use for affine matrices.
     *
     * @param p the vector; must not be {@code null}
     * @return the transformed point, never {@code null}
     */
    @Bulk(uniform = "this", name = "transformPositions")
    public Vec3f transformPosition(Vec3f p) {
        float x = p.x(), y = p.y(), z = p.z();
        return new Vec3f(
                m00 * x + m10 * y + m20 * z + m30,
                m01 * x + m11 * y + m21 * z + m31,
                m02 * x + m12 * y + m22 * z + m32);
    }

    /**
     * Transforms a direction ({@code w = 0}): no translation.
     *
     * @param d the vector; must not be {@code null}
     * @return the transformed direction, never {@code null}
     */
    @Bulk(uniform = "this", name = "transformDirections")
    public Vec3f transformDirection(Vec3f d) {
        float x = d.x(), y = d.y(), z = d.z();
        return new Vec3f(
                m00 * x + m10 * y + m20 * z,
                m01 * x + m11 * y + m21 * z,
                m02 * x + m12 * y + m22 * z);
    }

    /**
     * Transforms a point ({@code w = 1}) and performs the perspective divide.
     *
     * @param p the vector; must not be {@code null}
     * @return the projected point, never {@code null}
     */
    public Vec3f transformProject(Vec3f p) {
        float x = p.x(), y = p.y(), z = p.z();
        float invW = 1f / (m03 * x + m13 * y + m23 * z + m33);
        return new Vec3f(
                (m00 * x + m10 * y + m20 * z + m30) * invW,
                (m01 * x + m11 * y + m21 * z + m31) * invW,
                (m02 * x + m12 * y + m22 * z + m32) * invW);
    }

    // ---------------------------------------------------------------- accessors

    /**
     * Extracts the 3x3 block that holds rotation, scale and shear, dropping the translation and the
     * projective row.
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
     * Derives the matrix for transforming normals, the inverse transpose of the 3x3 block, which
     * keeps normals perpendicular to the surface under non-uniform scale.
     *
     * @return inverse-transpose of the upper-left 3x3: the matrix for transforming normals
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
     * Replaces the translation column and leaves the rest of the matrix unchanged; the original is
     * not modified.
     *
     * @param t the vector; must not be {@code null}
     * @return a copy with the translation column replaced
     */
    public Mat4f withTranslation(Vec3f t) {
        return new Mat4f(
                m00, m01, m02, m03,
                m10, m11, m12, m13,
                m20, m21, m22, m23,
                t.x(), t.y(), t.z(), m33);
    }

    /**
     * Extracts one column as a vector; allocates a vector.
     *
     * @param c the column index
     * @return column {@code c}
     * @throws IndexOutOfBoundsException if {@code c} is not a column index
     */
    public Vec4f column(int c) {
        return switch (c) {
            case 0 -> new Vec4f(m00, m01, m02, m03);
            case 1 -> new Vec4f(m10, m11, m12, m13);
            case 2 -> new Vec4f(m20, m21, m22, m23);
            case 3 -> new Vec4f(m30, m31, m32, m33);
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
    public Vec4f row(int r) {
        return switch (r) {
            case 0 -> new Vec4f(m00, m10, m20, m30);
            case 1 -> new Vec4f(m01, m11, m21, m31);
            case 2 -> new Vec4f(m02, m12, m22, m32);
            case 3 -> new Vec4f(m03, m13, m23, m33);
            default -> throw new IndexOutOfBoundsException(r);
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
     * Result of {@link #decompose()}.
     *
     * @param translation the translation; must not be {@code null}
     * @param rotation the rotation; must not be {@code null}
     * @param scale the scale; must not be {@code null}
     */
    @ValueType
    public record Trs(Vec3f translation, Quatf rotation, Vec3f scale) {
    }

    /**
     * Splits a model matrix {@code T * R * S} back into translation, rotation and scale.
     *
     * <p>Handles mirrored matrices by folding the reflection into a negative x scale. Shear is not
     * represented: for a sheared matrix the result recomposes to a different matrix. A zero-scale
     * axis yields NaN.
     *
     * @return the translation, rotation and scale, never {@code null}
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

    /**
     * Result of {@link #decomposeWithShear()}.
     *
     * <p>{@code shear} holds the factors {@code (xy, xz, yz)} of
     * {@link #translationRotateShearScale}.
     *
     * @param translation the translation; must not be {@code null}
     * @param rotation the rotation; must not be {@code null}
     * @param scale the scale; must not be {@code null}
     * @param shear the shear; must not be {@code null}
     */
    @ValueType
    public record ShearDecomposition(Vec3f translation, Quatf rotation, Vec3f scale, Vec3f shear) {
    }

    /**
     * Splits an affine matrix into translation, rotation, shear and scale such that
     * {@code T * R * Sh * S} recomposes to it ({@link #translationRotateShearScale}), where
     * {@code Sh} is the upper-triangular unit shear with factors {@code (xy, xz, yz)}:
     * {@code Sh = [[1, xy, xz], [0, 1, yz], [0, 0, 1]]} and {@code S} the diagonal scale.
     *
     * <p>This is the Gram-Schmidt (QR) factorisation of the 3x3 part, so unlike
     * {@link #decompose()} it is exact for sheared matrices, such as a non-uniform scale below a
     * rotation. A mirrored matrix is folded into a negative x scale as in {@code decompose()}. A
     * zero-scale axis yields NaN.
     *
     * @return the translation, rotation, shear and scale, never {@code null}
     */
    public ShearDecomposition decomposeWithShear() {
        float sx = (float) Math.sqrt(m00 * m00 + m01 * m01 + m02 * m02);
        float r0x = m00 / sx, r0y = m01 / sx, r0z = m02 / sx;
        float u01 = r0x * m10 + r0y * m11 + r0z * m12;
        float c1x = m10 - r0x * u01, c1y = m11 - r0y * u01, c1z = m12 - r0z * u01;
        float sy = (float) Math.sqrt(c1x * c1x + c1y * c1y + c1z * c1z);
        float r1x = c1x / sy, r1y = c1y / sy, r1z = c1z / sy;
        float u02 = r0x * m20 + r0y * m21 + r0z * m22;
        float u12 = r1x * m20 + r1y * m21 + r1z * m22;
        float c2x = m20 - r0x * u02 - r1x * u12, c2y = m21 - r0y * u02 - r1y * u12, c2z = m22 - r0z * u02 - r1z * u12;
        float sz = (float) Math.sqrt(c2x * c2x + c2y * c2y + c2z * c2z);
        float r2x = c2x / sz, r2y = c2y / sz, r2z = c2z / sz;
        // a left-handed frame (r0 x r1 pointing against r2) is a reflection: fold it into a negative x scale
        float cx = r0y * r1z - r0z * r1y, cy = r0z * r1x - r0x * r1z, cz = r0x * r1y - r0y * r1x;
        if (cx * r2x + cy * r2y + cz * r2z < 0f) {
            sx = -sx;
            r0x = -r0x;
            r0y = -r0y;
            r0z = -r0z;
            u01 = -u01;
            u02 = -u02;
        }
        Mat3f rot = new Mat3f(r0x, r0y, r0z, r1x, r1y, r1z, r2x, r2y, r2z);
        return new ShearDecomposition(getTranslation(), Quatf.fromMat3(rot), new Vec3f(sx, sy, sz), new Vec3f(u01 / sy, u02 / sz, u12 / sz));
    }

    /**
     * Composes a model matrix with shear in the order scale, shear, rotate, translate; use
     * {@link #decomposeWithShear()} to take such a matrix apart again.
     *
     * <p>With zero shear it equals {@link #translationRotateScale}.
     *
     * @param t the vector; must not be {@code null}
     * @param q the quaternion; must not be {@code null}
     * @param shear the shear; must not be {@code null}
     * @param s the vector; must not be {@code null}
     * @return the matrix {@code T * R * Sh * S}: scale, then shear with the factors
     *     {@code shear = (xy, xz, yz)} (see {@link #decomposeWithShear()}), then rotate, then
     *     translate
     */
    public static Mat4f translationRotateShearScale(Vec3f t, Quatf q, Vec3f shear, Vec3f s) {
        Mat3f r = Mat3f.rotation(q);
        float u01 = shear.x() * s.y(), u02 = shear.y() * s.z(), u12 = shear.z() * s.z();
        return new Mat4f(
                r.m00() * s.x(), r.m01() * s.x(), r.m02() * s.x(), 0f,
                r.m00() * u01 + r.m10() * s.y(), r.m01() * u01 + r.m11() * s.y(), r.m02() * u01 + r.m12() * s.y(), 0f,
                r.m00() * u02 + r.m10() * u12 + r.m20() * s.z(), r.m01() * u02 + r.m11() * u12 + r.m21() * s.z(), r.m02() * u02 + r.m12() * u12 + r.m22() * s.z(), 0f,
                t.x(), t.y(), t.z(), 1f);
    }

    /**
     * Tests that the bottom row is {@code (0, 0, 0, 1)} within a tolerance, which is the
     * precondition for the fast affine routines.
     *
     * @param eps the tolerance
     * @return {@code true} when the bottom row is {@code (0, 0, 0, 1)} within {@code eps}: no
     *     projection component
     */
    public boolean isAffine(float eps) {
        return Math.abs(m03) <= eps && Math.abs(m13) <= eps && Math.abs(m23) <= eps && Math.abs(m33 - 1f) <= eps;
    }

    /**
     * Compares two matrices element by element with an absolute tolerance, for tests and for
     * detecting changes; not a scale-relative comparison.
     *
     * @param o the other matrix; must not be {@code null}
     * @param eps the tolerance
     * @return {@code true} when every element differs from that of {@code o} by at most {@code eps}
     */
    public boolean approxEquals(Mat4f o, float eps) {
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
     * @return {@code true} when every component is finite (neither infinite nor NaN)
     */
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

    /**
     * Writes 16 column-major values.
     *
     * @param dst receives the result
     * @param off the index of the first element to read or write
     */
    public void writeTo(float[] dst, int off) {
        dst[off] = m00; dst[off + 1] = m01; dst[off + 2] = m02; dst[off + 3] = m03;
        dst[off + 4] = m10; dst[off + 5] = m11; dst[off + 6] = m12; dst[off + 7] = m13;
        dst[off + 8] = m20; dst[off + 9] = m21; dst[off + 10] = m22; dst[off + 11] = m23;
        dst[off + 12] = m30; dst[off + 13] = m31; dst[off + 14] = m32; dst[off + 15] = m33;
    }

    /**
     * Writes the 16 column-major values at the absolute position {@code index}; does not change the
     * buffer position.
     *
     * <p>Ready for {@code glUniformMatrix4*v(loc, false, buf)}.
     *
     * @param dst receives the result; must not be {@code null}
     * @param index the index
     */
    public void writeTo(FloatBuffer dst, int index) {
        dst.put(index, m00).put(index + 1, m01).put(index + 2, m02).put(index + 3, m03)
                .put(index + 4, m10).put(index + 5, m11).put(index + 6, m12).put(index + 7, m13)
                .put(index + 8, m20).put(index + 9, m21).put(index + 10, m22).put(index + 11, m23)
                .put(index + 12, m30).put(index + 13, m31).put(index + 14, m32).put(index + 15, m33);
    }

    /**
     * Converts the elements to {@code double}, which is exact.
     *
     * @return the same value with double components
     */
    @FloatOnly
    public Mat4d toDouble() {
        return new Mat4d(
                m00, m01, m02, m03,
                m10, m11, m12, m13,
                m20, m21, m22, m23,
                m30, m31, m32, m33);
    }

    /**
     * Moves the translation by a double-precision origin before narrowing the matrix to
     * {@code float}: the matrix of an object in world coordinates becomes its matrix in a frame
     * whose origin is {@code origin}, which is how a model matrix is made small enough for the GPU.
     *
     * <p>The subtraction is done in double, so the result keeps its precision however far the
     * object is from the world origin; only the rotation and scale are rounded. The origin is
     * usually the camera position, or a {@link FloatingOrigin}.
     *
     * @param origin the new origin in the coordinates of this matrix; must not be {@code null}
     * @return {@code translation(-origin) * this}, subtracted in double and narrowed to float
     */
    @DoubleOnly
    public Mat4f relativeTo(Vec3d origin) {
        return new Mat4f(
                (float) m00, (float) m01, (float) m02, (float) m03,
                (float) m10, (float) m11, (float) m12, (float) m13,
                (float) m20, (float) m21, (float) m22, (float) m23,
                (float) (m30 - origin.x()), (float) (m31 - origin.y()), (float) (m32 - origin.z()), (float) m33);
    }

    /**
     * Converts the elements to {@code float}, which rounds values that need more precision.
     *
     * @return the same value with float components (rounded to the nearest float for double types)
     */
    @DoubleOnly
    public Mat4f toFloat() {
        return new Mat4f(
                (float) m00, (float) m01, (float) m02, (float) m03,
                (float) m10, (float) m11, (float) m12, (float) m13,
                (float) m20, (float) m21, (float) m22, (float) m23,
                (float) m30, (float) m31, (float) m32, (float) m33);
    }
}
