package vmath.camera;

import vmath.annotations.Experimental;
import vmath.core.Mat4f;
import vmath.core.Vec4f;
import vmath.geo.DepthRange;
import vmath.geo.Planef;

/**
 * Cameras for planar reflections and portals: the mirrored view, the oblique near plane that stops geometry behind the mirror from showing in the
 * reflection, and the view through a portal.
 *
 * <p>A planar reflection is rendered in three steps: mirror the view with {@link #reflectedView}, clip with the mirror plane by replacing the near plane of the
 * projection ({@link #obliqueNearPlane}), and draw with the triangle winding flipped (a reflection reverses it).
 *
 * <p>Planes are {@link Planef}s: the kept side is {@code n . p + d >= 0}. Move a world-space plane into view space with {@code plane.transform(view)}.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the same time. The arrays and buffers you pass in are
 * not synchronised, so two threads must not write the same one.
 */
@Experimental("the portal frame conventions may change after first use")
public final class PlanarViews {

    private PlanarViews() {
    }

    /**
     * The matrix that mirrors world positions in {@code plane} (normalized here): {@code p - 2 (n . p + d) n}. It is its own inverse and has determinant -1,
     * so triangle winding flips.
     */
    public static Mat4f reflection(Planef plane) {
        Planef p = plane.normalize();
        float x = p.nx(), y = p.ny(), z = p.nz(), d = p.d();
        return new Mat4f(
                1f - 2f * x * x, -2f * x * y, -2f * x * z, 0f,
                -2f * y * x, 1f - 2f * y * y, -2f * y * z, 0f,
                -2f * z * x, -2f * z * y, 1f - 2f * z * z, 0f,
                -2f * d * x, -2f * d * y, -2f * d * z, 1f);
    }

    /** The view of the camera mirrored in a world-space {@code plane}: {@code view * reflection(plane)}. Render with flipped winding. */
    public static Mat4f reflectedView(Mat4f view, Planef plane) {
        return view.mul(reflection(plane));
    }

    /**
     * Replaces the near plane of {@code projection} by {@code clipPlane} (Lengyel's oblique frustum clipping), so that the hardware clips everything on the
     * negative side of the plane for free. The left, right, top and bottom planes are unchanged, and the far plane keeps the corner it had on the kept side,
     * so depth precision suffers a little the more the plane is tilted against the view direction.
     *
     * @param projection a perspective projection of the given depth convention (finite or infinite far; a Y flip is fine)
     * @param clipPlane  the plane in <b>view space</b>, kept side {@code n . p + d >= 0}; the camera must be on the clipped side ({@code d < 0}) and some of the
     *                   frustum must be on the kept side
     * @param depth      the depth convention the projection was built for
     * @throws IllegalArgumentException if the camera is not on the clipped side, or the plane clips the whole frustum, or an input is not finite
     */
    public static Mat4f obliqueNearPlane(Mat4f projection, Planef clipPlane, DepthRange depth) {
        float cx = clipPlane.nx(), cy = clipPlane.ny(), cz = clipPlane.nz(), cw = clipPlane.d();
        if (!(Float.isFinite(cx) && Float.isFinite(cy) && Float.isFinite(cz) && Float.isFinite(cw)) || !projection.isFinite()) {
            throw new IllegalArgumentException("the projection and the plane must be finite");
        }
        if (!(cw < 0f)) {
            throw new IllegalArgumentException("the camera must be on the clipped side of the plane (d < 0): " + cw);
        }
        float farZ = depth == DepthRange.REVERSED_ZERO_TO_ONE ? 0f : 1f;
        Mat4f inverse = projection.invert();
        float best = Float.NEGATIVE_INFINITY;
        for (int k = 0; k < 4; k++) {
            Vec4f q = inverse.transform(new Vec4f((k & 1) == 0 ? -1f : 1f, (k & 2) == 0 ? -1f : 1f, farZ, 1f));
            best = Math.max(best, cx * q.x() + cy * q.y() + cz * q.z() + cw * q.w());
        }
        if (!(best > 0f)) {
            throw new IllegalArgumentException("the plane clips the whole frustum");
        }
        float s = (depth == DepthRange.NEGATIVE_ONE_TO_ONE ? 2f : 1f) / best;
        float w0 = projection.m03(), w1 = projection.m13(), w2 = projection.m23(), w3 = projection.m33();
        float z0, z1, z2, z3;
        switch (depth) {
            case NEGATIVE_ONE_TO_ONE -> {
                z0 = s * cx - w0;
                z1 = s * cy - w1;
                z2 = s * cz - w2;
                z3 = s * cw - w3;
            }
            case ZERO_TO_ONE -> {
                z0 = s * cx;
                z1 = s * cy;
                z2 = s * cz;
                z3 = s * cw;
            }
            default -> {
                z0 = w0 - s * cx;
                z1 = w1 - s * cy;
                z2 = w2 - s * cz;
                z3 = w3 - s * cw;
            }
        }
        return new Mat4f(
                projection.m00(), projection.m01(), z0, w0,
                projection.m10(), projection.m11(), z1, w1,
                projection.m20(), projection.m21(), z2, w2,
                projection.m30(), projection.m31(), z3, w3);
    }

    /**
     * The rigid motion that carries what is in front of the source portal to the matching place behind the destination portal: {@code D * R * S^-1} with
     * {@code R} a half turn about the portal's up axis. A portal frame is a matrix from portal space to world: the origin is the centre of the portal, +Y up, and
     * +Z the side it is seen from. Put in your own scaling in the frames only if it is the same for both.
     */
    public static Mat4f portalTransform(Mat4f sourceToWorld, Mat4f destinationToWorld) {
        return destinationToWorld.mul(HALF_TURN_Y).mul(sourceToWorld.invert());
    }

    /**
     * The view of a camera looking through the source portal, as seen from the destination portal: {@code view * portalTransform^-1}. It is the same as moving the
     * camera by {@link #portalTransform}. To hide what is between the virtual camera and the destination portal, clip with the destination portal's plane in the
     * new view space ({@link #obliqueNearPlane}).
     */
    public static Mat4f portalView(Mat4f view, Mat4f sourceToWorld, Mat4f destinationToWorld) {
        return view.mul(sourceToWorld).mul(HALF_TURN_Y).mul(destinationToWorld.invert());
    }

    private static final Mat4f HALF_TURN_Y = new Mat4f(
            -1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 0f, 0f, 1f);
}
