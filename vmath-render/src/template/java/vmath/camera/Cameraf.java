package vmath.camera;

import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import vmath.core.Mat4f;
import vmath.core.Quatf;
import vmath.core.Vec3f;
import vmath.core.Vec4f;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;
import vmath.geo.Rayf;

/**
 * A perspective camera: where it is, which way it faces and how it projects.
 *
 * <p>Derived data (view, projection, their product, the frustum, unprojection) is computed on
 * demand from these seven values, so the record can never be inconsistent.
 *
 * <p>The camera looks down its local <b>-Z</b> axis with +Y up and +X to the right, like
 * {@link Mat4f#lookAt}. Angles are radians. Screen coordinates ({@link #toScreen},
 * {@link #pickRay}) have their origin at the <b>top-left</b> with y pointing down, and NDC has +y
 * up, as in GL and Vulkan-after-flip conventions.
 *
 * <p>{@code depth} chooses the clip-space depth convention (see {@link DepthRange}).
 * {@link DepthRange#REVERSED_ZERO_TO_ONE} always uses an infinite far plane and ignores
 * {@code far}. A finite {@code far} of {@code +Infinity} with the other two conventions gives an
 * infinite projection as well.
 *
 * <p>For scenes measured in double precision use the generated double twin and
 * {@code cameraRelative()}: render everything relative to the camera position so float precision is
 * spent near the eye.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads without
 * synchronization.
 *
 * <p><b>Example:</b> a perspective camera and its matrices
 *
 * <pre>{@code
 * Cameraf camera = Cameraf.lookingAt(new Vec3f(0f, 2f, 6f), Vec3f.ZERO, Vec3f.UNIT_Y,
 *         (float) Math.toRadians(60), 16f / 9f, 0.1f, 500f, DepthRange.of(ClipSpace.OPENGL));
 * Mat4f viewProjection = camera.viewProjection();
 * Frustumf frustum = camera.frustum();                                   // for culling
 * Vec3f screen = camera.toScreen(Vec3f.ZERO, 1920, 1080);                // pixels, depth in z
 * }</pre>
 *
 * <p><b>Example:</b> picking
 *
 * <pre>{@code
 * Cameraf camera = Cameraf.lookingAt(new Vec3f(0f, 0f, 5f), Vec3f.ZERO, Vec3f.UNIT_Y, 1f, 1.5f, 0.1f, 100f, DepthRange.of(ClipSpace.OPENGL));
 * Rayf ray = camera.pickRay(960f, 540f, 1920, 1080);                      // through the middle of the screen
 * float t = Intersectionf.raySphere(ray, Spheref.of(Vec3f.ZERO, 1f), 1000f);
 * }</pre>
 *
 * @param position the position; must not be {@code null}
 * @param orientation the orientation; must not be {@code null}
 * @param fovy the vertical field of view in radians
 * @param aspect the aspect ratio, width divided by height
 * @param near the distance to the near plane
 * @param far the distance to the far plane
 * @param depth the depth; must not be {@code null}
 */
@GenerateDouble
@ValueType
public record Cameraf(Vec3f position, Quatf orientation, float fovy, float aspect, float near, float far, DepthRange depth) {

    /**
     * Checks the arguments: {@code fovy} in (0, pi) radians, {@code aspect} and {@code near}
     * positive, {@code far} greater than {@code near}; {@link IllegalArgumentException} otherwise.
     *
     * @param position the position; must not be {@code null}
     * @param orientation the orientation; must not be {@code null}
     * @param fovy the vertical field of view in radians
     * @param aspect the aspect ratio, width divided by height
     * @param near the distance to the near plane
     * @param far the distance to the far plane
     * @param depth the depth; must not be {@code null}
     * @throws IllegalArgumentException if {@code fovy} is not in {@code (0, PI)}, {@code aspect} or
     *     {@code near} is not positive, or {@code far} is not greater than {@code near}
     */
    public Cameraf {
        if (!(fovy > 0f && fovy < (float) Math.PI)) {
            throw new IllegalArgumentException("fovy must be in (0, PI) radians: " + fovy);
        }
        if (!(aspect > 0f)) {
            throw new IllegalArgumentException("aspect must be positive: " + aspect);
        }
        if (!(near > 0f)) {
            throw new IllegalArgumentException("near must be positive: " + near);
        }
        if (!(far > near)) {
            throw new IllegalArgumentException("far must be greater than near: " + near + " .. " + far);
        }
    }

    /**
     * Builds a camera at a position that looks at a target; the up hint must not be parallel to the
     * view direction.
     *
     * @param position the position; must not be {@code null}
     * @param target the target; must not be {@code null}
     * @param up the vector; must not be {@code null}
     * @param fovy the vertical field of view in radians
     * @param aspect the aspect ratio, width divided by height
     * @param near the distance to the near plane
     * @param far the distance to the far plane
     * @param depth the depth; must not be {@code null}
     * @return a camera at {@code position} looking toward {@code target}, with the given
     *     approximate up direction
     */
    public static Cameraf lookingAt(Vec3f position, Vec3f target, Vec3f up, float fovy, float aspect, float near, float far,
                                    DepthRange depth) {
        return new Cameraf(position, Quatf.lookRotation(target.sub(position), up), fovy, aspect, near, far, depth);
    }

    /**
     * Derives a camera at another position with the same orientation and lens; the original is not
     * modified.
     *
     * @param p the vector; must not be {@code null}
     * @return the same camera at another position
     */
    public Cameraf withPosition(Vec3f p) {
        return new Cameraf(p, orientation, fovy, aspect, near, far, depth);
    }

    /**
     * Derives a camera with another orientation and the same position and lens; the original is not
     * modified.
     *
     * @param q the quaternion; must not be {@code null}
     * @return the same camera with another orientation
     */
    public Cameraf withOrientation(Quatf q) {
        return new Cameraf(position, q, fovy, aspect, near, far, depth);
    }

    /**
     * Derives a camera with another aspect ratio and the same position and orientation, as needed
     * after a window resize; the original is not modified.
     *
     * @param a the new aspect ratio, width over height
     * @return the same camera with another aspect ratio (width over height), for example after a
     *     window resize
     */
    public Cameraf withAspect(float a) {
        return new Cameraf(position, orientation, fovy, a, near, far, depth);
    }

    /**
     * Turns the camera to face {@code target}, keeping its position.
     *
     * @param target the target; must not be {@code null}
     * @param up the vector; must not be {@code null}
     * @return the camera turned towards the target, never {@code null}
     */
    public Cameraf lookAt(Vec3f target, Vec3f up) {
        return withOrientation(Quatf.lookRotation(target.sub(position), up));
    }

    // ---------------------------------------------------------------- axes

    /**
     * Computes the viewing direction from the orientation.
     *
     * @return unit vector the camera looks along (local -Z)
     */
    public Vec3f forward() {
        return orientation.transform(new Vec3f(0f, 0f, -1f));
    }

    /**
     * Computes the right direction in world space from the orientation.
     *
     * @return the camera's +X axis in world space
     */
    public Vec3f right() {
        return orientation.transform(Vec3f.UNIT_X);
    }

    /**
     * Computes the up direction in world space from the orientation.
     *
     * @return the camera's +Y axis in world space
     */
    public Vec3f up() {
        return orientation.transform(Vec3f.UNIT_Y);
    }

    // ---------------------------------------------------------------- matrices

    /**
     * Builds the view matrix by inverting the rigid camera transform, which is cheaper and more
     * accurate than a general inverse.
     *
     * @return world to view: the inverse of the camera's own transform
     */
    public Mat4f view() {
        return Mat4f.rotation(orientation.conjugate()).mul(Mat4f.translation(position.negate()));
    }

    /**
     * Builds the projection matrix for the camera's lens and depth convention.
     *
     * @return view to clip space, in the depth convention of {@link #depth()}
     */
    public Mat4f projection() {
        boolean infinite = Float.isInfinite(far);
        return switch (depth) {
            case NEGATIVE_ONE_TO_ONE -> infinite ? Mat4f.perspectiveInfinite(fovy, aspect, near, false)
                    : Mat4f.perspective(fovy, aspect, near, far, false);
            case ZERO_TO_ONE -> infinite ? Mat4f.perspectiveInfinite(fovy, aspect, near, true)
                    : Mat4f.perspective(fovy, aspect, near, far, true);
            case REVERSED_ZERO_TO_ONE -> Mat4f.perspectiveReversedZ(fovy, aspect, near);
        };
    }

    /**
     * Builds the combined view and projection matrix, which culling and shaders usually want.
     *
     * @return world to clip space: {@code projection() * view()}
     */
    public Mat4f viewProjection() {
        return projection().mul(view());
    }

    /**
     * Builds the culling frustum from the combined matrix.
     *
     * @return the view volume as six planes, for culling
     */
    public Frustumf frustum() {
        return Frustumf.fromViewProjection(viewProjection(), depth);
    }

    /**
     * Builds a projection that is shifted by a fraction of a pixel, which is how temporal
     * anti-aliasing varies the sample position from frame to frame.
     *
     * <p>{@code jitterX} and {@code jitterY} are in pixels (typically from {@link Jitter});
     * positive x moves the image right, positive y up. The shift is exact in NDC: every projected
     * point moves by {@code (2 jitterX / width, 2 jitterY / height)}, whatever its depth.
     *
     * @param jitterX the jitter x
     * @param jitterY the jitter y
     * @param width the width
     * @param height the height
     * @return the projection shifted by a sub-pixel offset, for temporal anti-aliasing
     */
    public Mat4f jitteredProjection(float jitterX, float jitterY, int width, int height) {
        Mat4f p = projection();
        float dx = 2f * jitterX / width;
        float dy = 2f * jitterY / height;
        return new Mat4f(
                p.m00(), p.m01(), p.m02(), p.m03(),
                p.m10(), p.m11(), p.m12(), p.m13(),
                p.m20() - dx, p.m21() - dy, p.m22(), p.m23(),
                p.m30(), p.m31(), p.m32(), p.m33());
    }

    /**
     * Builds the matrix that carries clip positions of this frame into the previous frame's clip
     * space, from which per-pixel motion vectors for temporal effects are derived.
     *
     * @param previousViewProjection the previous view projection; must not be {@code null}
     * @return matrix taking a clip-space position of <em>this</em> frame to clip space of the frame
     *     described by {@code previousViewProjection}, the basis of motion vectors:
     *     {@code previous * inverse(current)}
     */
    public Mat4f reprojection(Mat4f previousViewProjection) {
        return previousViewProjection.mul(viewProjection().invert());
    }

    // ---------------------------------------------------------------- projecting points

    /**
     * Transforms a world position by the combined matrix, without the perspective divide.
     *
     * @param world the world; must not be {@code null}
     * @return clip-space position of a world point
     */
    public Vec4f toClip(Vec3f world) {
        return viewProjection().transform(Vec4f.point(world));
    }

    /**
     * Projects a world position to normalised device coordinates by transforming it and dividing by
     * the homogeneous coordinate; a point behind the camera gives meaningless values.
     *
     * <p>A point exactly in the camera plane (clip {@code w = 0}) yields infinite components.
     *
     * @param world the world; must not be {@code null}
     * @return NDC position of a world point: x and y in [-1, 1] inside the view, z per the depth
     *     convention
     */
    public Vec3f project(Vec3f world) {
        return viewProjection().transformProject(world);
    }

    /**
     * Projects a world position to pixel coordinates, with the origin at the top left corner; a
     * point behind the camera gives meaningless values.
     *
     * @param world the world; must not be {@code null}
     * @param width the width
     * @param height the height
     * @return pixel position of a world point: x right and y down from the top-left corner, z the
     *     NDC depth
     */
    public Vec3f toScreen(Vec3f world, int width, int height) {
        Vec3f ndc = project(world);
        return new Vec3f((ndc.x() * 0.5f + 0.5f) * width, (0.5f - ndc.y() * 0.5f) * height, ndc.z());
    }

    /**
     * Reverses the projection from normalised device coordinates to the world by multiplying with
     * the inverse of the combined matrix.
     *
     * @param ndc the ndc; must not be {@code null}
     * @return world point at an NDC position, using the inverse of the view-projection matrix
     */
    public Vec3f unproject(Vec3f ndc) {
        return viewProjection().invert().transformProject(ndc);
    }

    /**
     * Builds a picking ray from a pixel position for hit testing against scene geometry.
     *
     * <p>Pixel (0, 0) is the top-left corner; pass pixel centres (x + 0.5) for exact picking. Built
     * analytically from the field of view, so it is valid for infinite projections too.
     *
     * @param pixelX the pixel x
     * @param pixelY the pixel y
     * @param width the width
     * @param height the height
     * @return the ray through a pixel, from the camera position along a unit direction
     */
    public Rayf pickRay(float pixelX, float pixelY, int width, int height) {
        float ndcX = pixelX / width * 2f - 1f;
        float ndcY = 1f - pixelY / height * 2f;
        float t = (float) Math.tan(fovy * 0.5f);
        Vec3f dir = forward().add(right().mul(ndcX * aspect * t)).add(up().mul(ndcY * t)).normalize();
        return Rayf.of(position, dir);
    }

    // ---------------------------------------------------------------- depth

    /**
     * Converts a stored depth value back to a distance along the view direction, taking the
     * camera's depth convention and projection into account.
     *
     * <p>The inverse of what the projection does to depth, for whichever {@link DepthRange} and far
     * plane the camera uses.
     *
     * @param ndcDepth the ndc depth
     * @return distance along the view direction (positive, in world units) that an NDC depth value
     *     stands for
     */
    public float linearizeDepth(float ndcDepth) {
        boolean infinite = Float.isInfinite(far);
        return switch (depth) {
            case NEGATIVE_ONE_TO_ONE -> infinite ? 2f * near / (1f - ndcDepth)
                    : 2f * near * far / (far + near - ndcDepth * (far - near));
            case ZERO_TO_ONE -> infinite ? near / (1f - ndcDepth) : near * far / (far - ndcDepth * (far - near));
            case REVERSED_ZERO_TO_ONE -> near / ndcDepth;
        };
    }

    /**
     * Reconstructs a view-space position from a screen position and a depth value, the way a shader
     * does from a depth buffer.
     *
     * @param ndcX the ndc x
     * @param ndcY the ndc y
     * @param ndcDepth the ndc depth
     * @return view-space position (x right, y up, z negative forward) of the surface seen at NDC
     *     {@code (ndcX, ndcY)} with depth value {@code ndcDepth}: the reconstruction a shader does
     *     from a depth buffer
     */
    public Vec3f viewPositionFromDepth(float ndcX, float ndcY, float ndcDepth) {
        float d = linearizeDepth(ndcDepth);
        float t = (float) Math.tan(fovy * 0.5f);
        return new Vec3f(ndcX * aspect * t * d, ndcY * t * d, -d);
    }

    /**
     * Reconstructs a world-space position from a screen position and a depth value, the way a
     * shader does from a depth buffer.
     *
     * @param ndcX the ndc x
     * @param ndcY the ndc y
     * @param ndcDepth the ndc depth
     * @return {@link #viewPositionFromDepth} transformed to world space
     */
    public Vec3f worldPositionFromDepth(float ndcX, float ndcY, float ndcDepth) {
        return position.add(orientation.transform(viewPositionFromDepth(ndcX, ndcY, ndcDepth)));
    }

    // ---------------------------------------------------------------- precision

    /**
     * Converts the camera to double precision, which is exact.
     *
     * @return the same camera with double-precision position and orientation
     */
    @FloatOnly
    public Camerad toDouble() {
        return new Camerad(position.toDouble(), orientation.toDouble(), fovy, aspect, near, far, depth);
    }

    /**
     * Narrows every value to float.
     *
     * <p>Prefer {@code cameraRelative()} for world-scale positions.
     *
     * @return the camera narrowed to float, never {@code null}
     */
    @DoubleOnly
    public Cameraf toFloat() {
        return new Cameraf(position.toFloat(), orientation.toFloat(), (float) fovy, (float) aspect, (float) near, (float) far, depth);
    }

    /**
     * Rebases the camera to the origin, which keeps precision when the camera is far from the world
     * origin; positions must then be made relative as well.
     *
     * <p>Render with every object's position also taken relative to this camera's position
     * ({@code Vec3d.relativeTo(cameraPosition)}), so the large world coordinates cancel in double
     * precision before anything is narrowed to float.
     *
     * @return the same view as a float camera located at the origin
     */
    @DoubleOnly
    public Cameraf cameraRelative() {
        // fully qualified: the double twin's imports name the double types only
        return new Cameraf(vmath.core.Vec3f.ZERO, orientation.toFloat(), (float) fovy, (float) aspect, (float) near, (float) far, depth);
    }
}
