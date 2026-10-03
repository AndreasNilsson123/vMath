package vmath.camera;

import vmath.annotations.Experimental;
import vmath.core.ClipSpace;
import vmath.core.Mat4f;

/**
 * Projections and views for stereo and head-mounted displays: one view per eye, and the asymmetric
 * frustum each eye needs.
 *
 * <p>Head space is the usual view space: +X right, +Y up, the head looks along -Z. The left eye
 * sits at {@code x = -ipd / 2} and the right eye at {@code +ipd / 2}.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time. The arrays and buffers you pass in are not synchronised, so two threads must not write
 * the same one.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Mat4f headView = Mat4f.lookAt(new Vec3f(0f, 1.7f, 0f), new Vec3f(0f, 1.7f, -1f), Vec3f.UNIT_Y);
 * Mat4f leftEye = Stereo.eyeView(headView, 0.064f, 0);                                  // interpupillary distance in metres
 * Mat4f rightEye = Stereo.eyeView(headView, 0.064f, 1);
 * Mat4f projection = Stereo.projection(-0.8f, 0.7f, 0.8f, -0.8f, 0.1f, 100f, ClipSpace.VULKAN);   // field-of-view angles of the headset
 * }</pre>
 */
@Experimental("the set of helpers may grow; the signatures are expected to stay")
public final class Stereo {

    /**
     * The left eye.
     */
    public static final int LEFT = 0;
    /**
     * The right eye.
     */
    public static final int RIGHT = 1;

    private Stereo() {
    }

    private static void checkEye(int eye) {
        if (eye != LEFT && eye != RIGHT) {
            throw new IllegalArgumentException("eye must be LEFT (0) or RIGHT (1): " + eye);
        }
    }

    /**
     * Computes the sideways offset of an eye from the head centre.
     *
     * @param ipd the ipd
     * @param eye the eye
     * @return the eye's offset along head X: {@code -ipd / 2} for the left eye and {@code +ipd / 2}
     *     for the right
     */
    public static float eyeOffset(float ipd, int eye) {
        checkEye(eye);
        return eye == LEFT ? -0.5f * ipd : 0.5f * ipd;
    }

    /**
     * Builds the view matrix of one eye from the head's view by shifting the world by the eye
     * offset.
     *
     * @param headView the head view; must not be {@code null}
     * @param ipd the ipd
     * @param eye the eye
     * @return world to eye: the head's view with the world shifted the other way by the eye's
     *     offset, {@code translation(-offset, 0, 0) * headView}
     */
    public static Mat4f eyeView(Mat4f headView, float ipd, int eye) {
        return Mat4f.translation(-eyeOffset(ipd, eye), 0f, 0f).mul(headView);
    }

    /**
     * Builds an asymmetric projection from the four field-of-view half angles that head-mounted
     * display runtimes report.
     *
     * @param angleLeft angle to the left edge in radians, <b>negative</b> for a view that extends
     *     to the left of the axis
     * @param angleRight angle to the right edge, positive for a view that extends to the right
     * @param angleUp    angle to the top edge, positive for a view that extends up
     * @param angleDown angle to the bottom edge, <b>negative</b> for a view that extends down
     * @param near the distance to the near plane
     * @param far the distance to the far plane
     * @param space the space; must not be {@code null}
     * @return an asymmetric perspective projection from the four half angles of a field of view, as
     *     head-mounted display runtimes report them
     */
    public static Mat4f projection(float angleLeft, float angleRight, float angleUp, float angleDown, float near, float far, ClipSpace space) {
        float t = near;
        return Mat4f.frustum(t * (float) Math.tan(angleLeft), t * (float) Math.tan(angleRight), t * (float) Math.tan(angleDown), t * (float) Math.tan(angleUp),
                near, far, space);
    }

    /**
     * Builds a reversed-z asymmetric projection with infinite far plane from the field-of-view half
     * angles, which needs a floating-point depth buffer and a greater-than depth test.
     *
     * <p>Needs a [0, 1] clip space, so {@link ClipSpace#OPENGL} is rejected.
     *
     * @param angleLeft the angle left
     * @param angleRight the angle right
     * @param angleUp the angle up
     * @param angleDown the angle down
     * @param near the distance to the near plane
     * @param space the space; must not be {@code null}
     * @return as {@link #projection} with reversed depth and an infinite far plane (near maps to
     *     depth 1, infinity to 0)
     * @throws IllegalArgumentException if the clip space does not have a depth range of 0 to 1
     */
    public static Mat4f projectionReversedZ(float angleLeft, float angleRight, float angleUp, float angleDown, float near, ClipSpace space) {
        if (!space.zeroToOne()) {
            throw new IllegalArgumentException("reversed-Z needs a [0, 1] depth range; use ClipSpace.D3D with glClipControl in OpenGL");
        }
        float l = near * (float) Math.tan(angleLeft), r = near * (float) Math.tan(angleRight);
        float b = near * (float) Math.tan(angleDown), t = near * (float) Math.tan(angleUp);
        Mat4f m = new Mat4f(
                2f * near / (r - l), 0f, 0f, 0f,
                0f, 2f * near / (t - b), 0f, 0f,
                (r + l) / (r - l), (t + b) / (t - b), 0f, -1f,
                0f, 0f, near, 0f);
        return space.yDown() ? m.flipY() : m;
    }

    /**
     * Builds the off-axis projection for an eye that looks at a fixed physical screen, such as a
     * stereo monitor, a CAVE wall or a projector, where the frustum follows the screen rectangle
     * rather than the view direction.
     *
     * <p>Use it with {@link #eyeView} for the same eye. A point on the screen plane lands at the
     * same screen position in both eyes (no parallax at the screen), nearer points shift one way
     * and farther points the other, which is the stereo effect.
     *
     * @param halfWidth the half width
     * @param halfHeight the half height
     * @param screenDistance the screen distance
     * @param eyeOffset the eye offset
     * @param near the distance to the near plane
     * @param far the distance to the far plane
     * @param space the space; must not be {@code null}
     * @return the off-axis projection of an eye that looks at a physical screen (a stereo monitor,
     *     a CAVE wall, a projector): the screen is a rectangle of {@code 2 * halfWidth} by
     *     {@code 2 * halfHeight} centred on the head's axis at distance {@code screenDistance}, and
     *     the eye is at {@code eyeOffset} along X
     */
    public static Mat4f offAxis(float halfWidth, float halfHeight, float screenDistance, float eyeOffset, float near, float far, ClipSpace space) {
        float k = near / screenDistance;
        return Mat4f.frustum((-halfWidth - eyeOffset) * k, (halfWidth - eyeOffset) * k, -halfHeight * k, halfHeight * k, near, far, space);
    }
}
