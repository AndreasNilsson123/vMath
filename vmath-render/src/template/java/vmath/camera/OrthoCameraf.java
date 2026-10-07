package vmath.camera;

import vmath.annotations.DoubleOnly;
import vmath.annotations.Experimental;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import vmath.core.ClipSpace;
import vmath.core.Mat4f;
import vmath.core.Quatf;
import vmath.core.Vec3f;
import vmath.core.Vec4f;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;
import vmath.geo.Rayf;

/**
 * An orthographic camera: where it is, which way it faces and which box of the view it shows.
 *
 * <p>The counterpart of {@link Cameraf} for 2D views, user interfaces, maps, CAD and technical
 * drawings. It is a separate type because the two lenses do not share a meaning for
 * {@code fovy} and {@code aspect}, and a camera type that accepts both would make every consumer
 * guess; a consumer that needs a perspective camera cannot be handed this one by mistake.
 *
 * <p>Like {@link Cameraf}, the camera looks down its local <b>-Z</b> axis with +Y up and +X to the
 * right, angles are radians, screen coordinates have their origin at the top-left with y pointing
 * down, and NDC has +y up. The view volume is the box {@code left..right} by {@code bottom..top} in
 * the view plane (view-space x and y, in world units) and {@code near..far} along the view
 * direction; {@code near} may be zero or negative (a plane behind the camera), {@code far} must be
 * greater. Both are finite: there is no infinite orthographic projection, and for
 * {@link DepthRange#REVERSED_ZERO_TO_ONE} the far plane is a real plane.
 *
 * <p><b>Pixel-exact views.</b> {@link #forViewport} makes the box of a window with a given number
 * of world units per pixel, so that one unit of the world is one pixel (or any other ratio) and
 * {@link #pixelSize} gives it back.
 *
 * <p>For scenes measured in double precision use the generated double twin and
 * {@code cameraRelative()}.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads without
 * synchronization.
 *
 * <p><b>Example:</b> a 2D view where one world unit is one pixel
 *
 * <pre>{@code
 * OrthoCameraf camera = OrthoCameraf.forViewport(new Vec3f(960f, 540f, 10f), 1920, 1080, 1f, 0.1f, 100f, DepthRange.of(ClipSpace.OPENGL));
 * Mat4f viewProjection = camera.viewProjection();
 * Vec3f pixel = camera.toScreen(new Vec3f(100f, 200f, 0f), 1920, 1080);        // x 100 - 0 = 100 from the left, y 880 from the top
 * Rayf ray = camera.pickRay(400.5f, 300.5f, 1920, 1080);                       // the direction of the view, an origin that moves
 * }</pre>
 *
 * @param position the position; must not be {@code null}
 * @param orientation the orientation; must not be {@code null}
 * @param left the view-space x of the left edge of the view
 * @param right the view-space x of the right edge, greater than {@code left}
 * @param bottom the view-space y of the bottom edge
 * @param top the view-space y of the top edge, greater than {@code bottom}
 * @param near the distance to the near plane, which may be zero or negative
 * @param far the distance to the far plane, greater than {@code near}
 * @param depth the depth; must not be {@code null}
 */
@GenerateDouble
@ValueType
@Experimental("new in 0.2: the set of members may grow")
public record OrthoCameraf(Vec3f position, Quatf orientation, float left, float right, float bottom, float top, float near, float far, DepthRange depth) {

    /**
     * Checks the arguments: every number finite, {@code right} greater than {@code left},
     * {@code top} greater than {@code bottom} and {@code far} greater than {@code near}.
     *
     * @param position the position; must not be {@code null}
     * @param orientation the orientation; must not be {@code null}
     * @param left the view-space x of the left edge
     * @param right the view-space x of the right edge
     * @param bottom the view-space y of the bottom edge
     * @param top the view-space y of the top edge
     * @param near the distance to the near plane
     * @param far the distance to the far plane
     * @param depth the depth; must not be {@code null}
     * @throws IllegalArgumentException if a number is not finite or an interval is empty
     */
    public OrthoCameraf {
        java.util.Objects.requireNonNull(position);
        java.util.Objects.requireNonNull(orientation);
        java.util.Objects.requireNonNull(depth);
        if (!Float.isFinite(left) || !Float.isFinite(right) || !Float.isFinite(bottom) || !Float.isFinite(top) || !Float.isFinite(near) || !Float.isFinite(far)) {
            throw new IllegalArgumentException("the edges and the planes must be finite: " + left + " " + right + " " + bottom + " " + top + " " + near + " " + far);
        }
        if (!(right > left) || !(top > bottom)) {
            throw new IllegalArgumentException("right must be greater than left and top greater than bottom: x " + left + " .. " + right + ", y " + bottom + " .. " + top);
        }
        if (!(far > near)) {
            throw new IllegalArgumentException("far must be greater than near: " + near + " .. " + far);
        }
    }

    /**
     * Builds a camera at a position that looks at a target, showing a box centred on the view
     * axis, given by its height and aspect ratio.
     *
     * @param position the position; must not be {@code null}
     * @param target the target; must not be {@code null}
     * @param up the up hint; must not be {@code null} or parallel to the view direction
     * @param height the height of the view in world units, positive
     * @param aspect the aspect ratio, width divided by height, positive
     * @param near the distance to the near plane
     * @param far the distance to the far plane
     * @param depth the depth; must not be {@code null}
     * @return the camera
     * @throws IllegalArgumentException if {@code height} or {@code aspect} is not positive, or the planes are invalid
     */
    public static OrthoCameraf lookingAt(Vec3f position, Vec3f target, Vec3f up, float height, float aspect, float near, float far, DepthRange depth) {
        return of(position, Quatf.lookRotation(target.sub(position), up), height, aspect, near, far, depth);
    }

    /**
     * Builds a camera with a given orientation showing a box centred on the view axis.
     *
     * @param position the position; must not be {@code null}
     * @param orientation the orientation; must not be {@code null}
     * @param height the height of the view in world units, positive
     * @param aspect the aspect ratio, width divided by height, positive
     * @param near the distance to the near plane
     * @param far the distance to the far plane
     * @param depth the depth; must not be {@code null}
     * @return the camera
     * @throws IllegalArgumentException if {@code height} or {@code aspect} is not positive, or the planes are invalid
     */
    public static OrthoCameraf of(Vec3f position, Quatf orientation, float height, float aspect, float near, float far, DepthRange depth) {
        if (!(height > 0f) || !(aspect > 0f) || !Float.isFinite(height) || !Float.isFinite(aspect)) {
            throw new IllegalArgumentException("height and aspect must be positive and finite: " + height + ", " + aspect);
        }
        float halfH = height * 0.5f, halfW = halfH * aspect;
        return new OrthoCameraf(position, orientation, -halfW, halfW, -halfH, halfH, near, far, depth);
    }

    /**
     * Builds the camera of a window where one pixel is a given number of world units, looking
     * down the world -Z axis with +Y up: with a size of 1 one world unit is one pixel.
     *
     * @param center the point of the world at the middle of the window, which is also the position of the camera; must not be {@code null}
     * @param viewportWidth the width of the window in pixels, positive
     * @param viewportHeight the height of the window in pixels, positive
     * @param unitsPerPixel the size of a pixel in world units, positive
     * @param near the distance to the near plane
     * @param far the distance to the far plane
     * @param depth the depth; must not be {@code null}
     * @return the camera, whose {@link #pixelSize} is {@code unitsPerPixel}
     * @throws IllegalArgumentException if a size is not positive or the planes are invalid
     */
    public static OrthoCameraf forViewport(Vec3f center, int viewportWidth, int viewportHeight, float unitsPerPixel, float near, float far, DepthRange depth) {
        if (viewportWidth < 1 || viewportHeight < 1 || !(unitsPerPixel > 0f) || !Float.isFinite(unitsPerPixel)) {
            throw new IllegalArgumentException("the viewport and the pixel size must be positive: " + viewportWidth + " x " + viewportHeight + ", " + unitsPerPixel);
        }
        float halfW = viewportWidth * unitsPerPixel * 0.5f, halfH = viewportHeight * unitsPerPixel * 0.5f;
        return new OrthoCameraf(center, Quatf.IDENTITY, -halfW, halfW, -halfH, halfH, near, far, depth);
    }

    /**
     * Derives a camera at another position with the same orientation and box.
     *
     * @param p the position; must not be {@code null}
     * @return the same camera at another position
     */
    public OrthoCameraf withPosition(Vec3f p) {
        return new OrthoCameraf(p, orientation, left, right, bottom, top, near, far, depth);
    }

    /**
     * Derives a camera with another orientation and the same position and box.
     *
     * @param q the orientation; must not be {@code null}
     * @return the same camera with another orientation
     */
    public OrthoCameraf withOrientation(Quatf q) {
        return new OrthoCameraf(position, q, left, right, bottom, top, near, far, depth);
    }

    /**
     * Derives a camera for another aspect ratio: the height of the view stays, the width follows,
     * around the same middle, as needed after a window resize.
     *
     * @param a the new aspect ratio, width over height, positive
     * @return the same camera with another width
     * @throws IllegalArgumentException if {@code a} is not positive
     */
    public OrthoCameraf withAspect(float a) {
        if (!(a > 0f) || !Float.isFinite(a)) {
            throw new IllegalArgumentException("aspect must be positive and finite: " + a);
        }
        float middle = (left + right) * 0.5f, half = height() * a * 0.5f;
        return new OrthoCameraf(position, orientation, middle - half, middle + half, bottom, top, near, far, depth);
    }

    /**
     * Derives a camera that shows a larger or smaller part of the world around the same middle:
     * a factor above 1 zooms in (the box shrinks).
     *
     * @param factor the zoom factor, positive
     * @return the same camera with the box divided by {@code factor} about its middle
     * @throws IllegalArgumentException if {@code factor} is not positive
     */
    public OrthoCameraf zoomed(float factor) {
        if (!(factor > 0f) || !Float.isFinite(factor)) {
            throw new IllegalArgumentException("the zoom factor must be positive and finite: " + factor);
        }
        float cx = (left + right) * 0.5f, cy = (bottom + top) * 0.5f, hw = width() * 0.5f / factor, hh = height() * 0.5f / factor;
        return new OrthoCameraf(position, orientation, cx - hw, cx + hw, cy - hh, cy + hh, near, far, depth);
    }

    /**
     * Turns the camera to face {@code target}, keeping its position.
     *
     * @param target the target; must not be {@code null}
     * @param up the up hint; must not be {@code null}
     * @return the camera turned towards the target
     */
    public OrthoCameraf lookAt(Vec3f target, Vec3f up) {
        return withOrientation(Quatf.lookRotation(target.sub(position), up));
    }

    // ---------------------------------------------------------------- the box

    /**
     * Gives the width of the view.
     *
     * @return {@code right - left}, in world units
     */
    public float width() {
        return right - left;
    }

    /**
     * Gives the height of the view.
     *
     * @return {@code top - bottom}, in world units
     */
    public float height() {
        return top - bottom;
    }

    /**
     * Gives the aspect ratio of the box.
     *
     * @return width divided by height
     */
    public float aspect() {
        return width() / height();
    }

    /**
     * Gives the size of a pixel on the ground, which is the same everywhere in an orthographic
     * view.
     *
     * @param viewportHeight the height of the viewport in pixels, positive
     * @return world units per pixel: {@code height / viewportHeight}
     * @throws IllegalArgumentException if {@code viewportHeight} is below 1
     */
    public float pixelSize(int viewportHeight) {
        if (viewportHeight < 1) {
            throw new IllegalArgumentException("the viewport height must be positive: " + viewportHeight);
        }
        return height() / viewportHeight;
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
     * @return the camera's +X axis in world space (named {@code rightAxis} because {@link #right()} is the right edge of the box)
     */
    public Vec3f rightAxis() {
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
     * Builds the view matrix by inverting the rigid camera transform.
     *
     * @return world to view
     */
    public Mat4f view() {
        return Mat4f.rotation(orientation.conjugate()).mul(Mat4f.translation(position.negate()));
    }

    /**
     * Builds the projection matrix for the box and the depth convention.
     *
     * @return view to clip space, with clip {@code w} equal to 1 for every point
     */
    public Mat4f projection() {
        return switch (depth) {
            case NEGATIVE_ONE_TO_ONE -> Mat4f.ortho(left, right, bottom, top, near, far, ClipSpace.OPENGL);
            case ZERO_TO_ONE -> Mat4f.ortho(left, right, bottom, top, near, far, ClipSpace.D3D);
            case REVERSED_ZERO_TO_ONE -> Mat4f.orthoReversedZ(left, right, bottom, top, near, far, ClipSpace.D3D);
        };
    }

    /**
     * Builds the combined view and projection matrix.
     *
     * @return world to clip space: {@code projection() * view()}
     */
    public Mat4f viewProjection() {
        return projection().mul(view());
    }

    /**
     * Builds the culling frustum (a box) from the combined matrix.
     *
     * @return the view volume as six planes, for culling
     */
    public Frustumf frustum() {
        return Frustumf.fromViewProjection(viewProjection(), depth);
    }

    /**
     * Builds a projection shifted by a fraction of a pixel, for temporal anti-aliasing.
     *
     * <p>{@code jitterX} and {@code jitterY} are in pixels (typically from {@link Jitter}); positive
     * x moves the image right, positive y up. The shift is the same in NDC for every point:
     * {@code (2 jitterX / width, 2 jitterY / height)}. In world units it is the pixel size times the
     * jitter, which is what an orthographic view needs (no angles are involved).
     *
     * @param jitterX the jitter x in pixels
     * @param jitterY the jitter y in pixels
     * @param width the width of the viewport in pixels
     * @param height the height of the viewport in pixels
     * @return the projection shifted by a sub-pixel offset
     */
    public Mat4f jitteredProjection(float jitterX, float jitterY, int width, int height) {
        Mat4f p = projection();
        float dx = 2f * jitterX / width;
        float dy = 2f * jitterY / height;
        return new Mat4f(
                p.m00(), p.m01(), p.m02(), p.m03(),
                p.m10(), p.m11(), p.m12(), p.m13(),
                p.m20(), p.m21(), p.m22(), p.m23(),
                p.m30() + dx, p.m31() + dy, p.m32(), p.m33());
    }

    /**
     * Builds the matrix that carries clip positions of this frame into the previous frame's clip
     * space, for motion vectors.
     *
     * @param previousViewProjection the previous view projection; must not be {@code null}
     * @return {@code previous * inverse(current)}
     */
    public Mat4f reprojection(Mat4f previousViewProjection) {
        return previousViewProjection.mul(viewProjection().invert());
    }

    // ---------------------------------------------------------------- projecting points

    /**
     * Transforms a world position by the combined matrix.
     *
     * @param world the world position; must not be {@code null}
     * @return clip-space position, with {@code w} equal to 1
     */
    public Vec4f toClip(Vec3f world) {
        return viewProjection().transform(Vec4f.point(world));
    }

    /**
     * Projects a world position to normalised device coordinates. Unlike a perspective view, a
     * point behind the camera is projected correctly (it is outside the near plane only if it is
     * nearer than {@code near}).
     *
     * @param world the world position; must not be {@code null}
     * @return NDC position: x and y in [-1, 1] inside the view, z per the depth convention
     */
    public Vec3f project(Vec3f world) {
        return viewProjection().transformProject(world);
    }

    /**
     * Projects a world position to pixel coordinates, with the origin at the top left corner.
     *
     * @param world the world position; must not be {@code null}
     * @param width the width of the viewport in pixels
     * @param height the height of the viewport in pixels
     * @return pixel position: x right and y down from the top-left corner, z the NDC depth
     */
    public Vec3f toScreen(Vec3f world, int width, int height) {
        Vec3f ndc = project(world);
        return new Vec3f((ndc.x() * 0.5f + 0.5f) * width, (0.5f - ndc.y() * 0.5f) * height, ndc.z());
    }

    /**
     * Reverses the projection from normalised device coordinates to the world.
     *
     * @param ndc the NDC position; must not be {@code null}
     * @return the world point at an NDC position
     */
    public Vec3f unproject(Vec3f ndc) {
        return viewProjection().invert().transformProject(ndc);
    }

    /**
     * Builds a picking ray from a pixel position. In an orthographic view all rays are parallel:
     * the direction is the viewing direction and the origin moves with the pixel, on the plane
     * through the camera position (view-space z of 0); the perspective ray is the other way round.
     *
     * <p>Pixel (0, 0) is the top-left corner; pass pixel centres (x + 0.5) for exact picking.
     *
     * @param pixelX the pixel x
     * @param pixelY the pixel y
     * @param width the width of the viewport in pixels
     * @param height the height of the viewport in pixels
     * @return the ray through the pixel
     */
    public Rayf pickRay(float pixelX, float pixelY, int width, int height) {
        float u = pixelX / width, v = 1f - pixelY / height;
        float x = left + u * (right - left), y = bottom + v * (top - bottom);
        return Rayf.of(position.add(rightAxis().mul(x)).add(up().mul(y)), forward());
    }

    // ---------------------------------------------------------------- depth

    /**
     * Converts a stored depth value back to a distance along the view direction. Depth is linear
     * in an orthographic projection.
     *
     * @param ndcDepth the NDC depth
     * @return distance along the view direction in world units (between {@code near} and
     *     {@code far} for a point inside the view)
     */
    public float linearizeDepth(float ndcDepth) {
        return switch (depth) {
            case NEGATIVE_ONE_TO_ONE -> near + (ndcDepth + 1f) * 0.5f * (far - near);
            case ZERO_TO_ONE -> near + ndcDepth * (far - near);
            case REVERSED_ZERO_TO_ONE -> far - ndcDepth * (far - near);
        };
    }

    /**
     * Reconstructs a view-space position from a screen position and a depth value, the way a
     * shader does from a depth buffer.
     *
     * @param ndcX the NDC x
     * @param ndcY the NDC y
     * @param ndcDepth the NDC depth
     * @return view-space position (x right, y up, z negative forward)
     */
    public Vec3f viewPositionFromDepth(float ndcX, float ndcY, float ndcDepth) {
        float d = linearizeDepth(ndcDepth);
        return new Vec3f(left + (ndcX * 0.5f + 0.5f) * (right - left), bottom + (ndcY * 0.5f + 0.5f) * (top - bottom), -d);
    }

    /**
     * Reconstructs a world-space position from a screen position and a depth value.
     *
     * @param ndcX the NDC x
     * @param ndcY the NDC y
     * @param ndcDepth the NDC depth
     * @return {@link #viewPositionFromDepth} transformed to world space
     */
    public Vec3f worldPositionFromDepth(float ndcX, float ndcY, float ndcDepth) {
        return position.add(orientation.transform(viewPositionFromDepth(ndcX, ndcY, ndcDepth)));
    }

    // ---------------------------------------------------------------- precision

    /**
     * Converts the camera to double precision, which is exact.
     *
     * @return the same camera with double-precision values
     */
    @FloatOnly
    public OrthoCamerad toDouble() {
        return new OrthoCamerad(position.toDouble(), orientation.toDouble(), left, right, bottom, top, near, far, depth);
    }

    /**
     * Narrows every value to float.
     *
     * <p>Prefer {@code cameraRelative()} for world-scale positions.
     *
     * @return the camera narrowed to float
     */
    @DoubleOnly
    public OrthoCameraf toFloat() {
        return new OrthoCameraf(position.toFloat(), orientation.toFloat(), (float) left, (float) right, (float) bottom, (float) top, (float) near, (float) far, depth);
    }

    /**
     * Rebases the camera to the origin, which keeps precision when the camera is far from the
     * world origin; positions must then be made relative as well ({@code Vec3d.relativeTo}).
     *
     * @return the same view as a float camera located at the origin
     */
    @DoubleOnly
    public OrthoCameraf cameraRelative() {
        // fully qualified: the double twin's imports name the double types only
        return new OrthoCameraf(vmath.core.Vec3f.ZERO, orientation.toFloat(), (float) left, (float) right, (float) bottom, (float) top, (float) near, (float) far, depth);
    }
}
