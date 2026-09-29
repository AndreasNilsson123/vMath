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
 * A perspective camera: where it is, which way it faces and how it projects. Derived data (view, projection, their product,
 * the frustum, unprojection) is computed on demand from these seven values, so the record can never be inconsistent.
 *
 * <p>The camera looks down its local <b>-Z</b> axis with +Y up and +X to the right, like {@link Mat4f#lookAt}. Angles are
 * radians. Screen coordinates ({@link #toScreen}, {@link #pickRay}) have their origin at the <b>top-left</b> with y pointing
 * down, and NDC has +y up, as in GL and Vulkan-after-flip conventions.
 *
 * <p>{@code depth} chooses the clip-space depth convention (see {@link DepthRange}). {@link DepthRange#REVERSED_ZERO_TO_ONE}
 * always uses an infinite far plane and ignores {@code far}. A finite {@code far} of {@code +Infinity} with the other two
 * conventions gives an infinite projection as well.
 *
 * <p>For scenes measured in double precision use the generated double twin and {@link #cameraRelative()}: render everything
 * relative to the camera position so float precision is spent near the eye.
 */
@GenerateDouble
@ValueType
public record Cameraf(Vec3f position, Quatf orientation, float fovy, float aspect, float near, float far, DepthRange depth) {

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

    /** A camera at {@code position} looking toward {@code target}, with the given approximate up direction. */
    public static Cameraf lookingAt(Vec3f position, Vec3f target, Vec3f up, float fovy, float aspect, float near, float far,
                                    DepthRange depth) {
        return new Cameraf(position, Quatf.lookRotation(target.sub(position), up), fovy, aspect, near, far, depth);
    }

    public Cameraf withPosition(Vec3f p) {
        return new Cameraf(p, orientation, fovy, aspect, near, far, depth);
    }

    public Cameraf withOrientation(Quatf q) {
        return new Cameraf(position, q, fovy, aspect, near, far, depth);
    }

    public Cameraf withAspect(float a) {
        return new Cameraf(position, orientation, fovy, a, near, far, depth);
    }

    /** Turns the camera to face {@code target}, keeping its position. */
    public Cameraf lookAt(Vec3f target, Vec3f up) {
        return withOrientation(Quatf.lookRotation(target.sub(position), up));
    }

    // ---------------------------------------------------------------- axes

    /** Unit vector the camera looks along (local -Z). */
    public Vec3f forward() {
        return orientation.transform(new Vec3f(0f, 0f, -1f));
    }

    public Vec3f right() {
        return orientation.transform(Vec3f.UNIT_X);
    }

    public Vec3f up() {
        return orientation.transform(Vec3f.UNIT_Y);
    }

    // ---------------------------------------------------------------- matrices

    /** World to view: the inverse of the camera's own transform. */
    public Mat4f view() {
        return Mat4f.rotation(orientation.conjugate()).mul(Mat4f.translation(position.negate()));
    }

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

    public Mat4f viewProjection() {
        return projection().mul(view());
    }

    /** The view volume as six planes, for culling. */
    public Frustumf frustum() {
        return Frustumf.fromViewProjection(viewProjection(), depth);
    }

    /**
     * The projection shifted by a sub-pixel offset, for temporal anti-aliasing. {@code jitterX} and {@code jitterY} are in
     * pixels (typically from {@link Jitter}); positive x moves the image right, positive y up. The shift is exact in NDC:
     * every projected point moves by {@code (2 jitterX / width, 2 jitterY / height)}, whatever its depth.
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
     * Matrix taking a clip-space position of <em>this</em> frame to clip space of the frame described by
     * {@code previousViewProjection}, the basis of motion vectors: {@code previous * inverse(current)}.
     */
    public Mat4f reprojection(Mat4f previousViewProjection) {
        return previousViewProjection.mul(viewProjection().invert());
    }

    // ---------------------------------------------------------------- projecting points

    /** Clip-space position of a world point. */
    public Vec4f toClip(Vec3f world) {
        return viewProjection().transform(Vec4f.point(world));
    }

    /**
     * NDC position of a world point: x and y in [-1, 1] inside the view, z per the depth convention. A point exactly in
     * the camera plane (clip {@code w = 0}) yields infinite components.
     */
    public Vec3f project(Vec3f world) {
        return viewProjection().transformProject(world);
    }

    /** Pixel position of a world point: x right and y down from the top-left corner, z the NDC depth. */
    public Vec3f toScreen(Vec3f world, int width, int height) {
        Vec3f ndc = project(world);
        return new Vec3f((ndc.x() * 0.5f + 0.5f) * width, (0.5f - ndc.y() * 0.5f) * height, ndc.z());
    }

    /** World point at an NDC position, using the inverse of the view-projection matrix. */
    public Vec3f unproject(Vec3f ndc) {
        return viewProjection().invert().transformProject(ndc);
    }

    /**
     * The ray through a pixel, from the camera position along a unit direction. Pixel (0, 0) is the top-left corner; pass
     * pixel centres (x + 0.5) for exact picking. Built analytically from the field of view, so it is valid for infinite
     * projections too.
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
     * Distance along the view direction (positive, in world units) that an NDC depth value stands for. The inverse of
     * what the projection does to depth, for whichever {@link DepthRange} and far plane the camera uses.
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
     * View-space position (x right, y up, z negative forward) of the surface seen at NDC {@code (ndcX, ndcY)} with depth
     * value {@code ndcDepth}: the reconstruction a shader does from a depth buffer.
     */
    public Vec3f viewPositionFromDepth(float ndcX, float ndcY, float ndcDepth) {
        float d = linearizeDepth(ndcDepth);
        float t = (float) Math.tan(fovy * 0.5f);
        return new Vec3f(ndcX * aspect * t * d, ndcY * t * d, -d);
    }

    /** {@link #viewPositionFromDepth} transformed to world space. */
    public Vec3f worldPositionFromDepth(float ndcX, float ndcY, float ndcDepth) {
        return position.add(orientation.transform(viewPositionFromDepth(ndcX, ndcY, ndcDepth)));
    }

    // ---------------------------------------------------------------- precision

    @FloatOnly
    public Camerad toDouble() {
        return new Camerad(position.toDouble(), orientation.toDouble(), fovy, aspect, near, far, depth);
    }

    /** Narrows every value to float. Prefer {@link #cameraRelative()} for world-scale positions. */
    @DoubleOnly
    public Cameraf toFloat() {
        return new Cameraf(position.toFloat(), orientation.toFloat(), (float) fovy, (float) aspect, (float) near, (float) far, depth);
    }

    /**
     * The same view as a float camera located at the origin. Render with every object's position also taken relative to
     * this camera's position ({@code Vec3d.relativeTo(cameraPosition)}), so the large world coordinates cancel in double
     * precision before anything is narrowed to float.
     */
    @DoubleOnly
    public Cameraf cameraRelative() {
        // fully qualified: the double twin's imports name the double types only
        return new Cameraf(vmath.core.Vec3f.ZERO, orientation.toFloat(), (float) fovy, (float) aspect, (float) near, (float) far, depth);
    }
}
