package vmath.camera;

import vmath.annotations.Experimental;
import vmath.core.ClipSpace;
import vmath.core.Mat4f;

/**
 * Projections and views for stereo and head-mounted displays: one view per eye, and the asymmetric frustum each eye needs.
 *
 * <p>Head space is the usual view space: +X right, +Y up, the head looks along -Z. The left eye sits at {@code x = -ipd / 2} and the right eye at {@code +ipd / 2}.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the same time. The arrays and buffers you pass in are
 * not synchronised, so two threads must not write the same one.
 */
@Experimental("the set of helpers may grow; the signatures are expected to stay")
public final class Stereo {

    public static final int LEFT = 0;
    public static final int RIGHT = 1;

    private Stereo() {
    }

    private static void checkEye(int eye) {
        if (eye != LEFT && eye != RIGHT) {
            throw new IllegalArgumentException("eye must be LEFT (0) or RIGHT (1): " + eye);
        }
    }

    /** The eye's offset along head X: {@code -ipd / 2} for the left eye and {@code +ipd / 2} for the right. */
    public static float eyeOffset(float ipd, int eye) {
        checkEye(eye);
        return eye == LEFT ? -0.5f * ipd : 0.5f * ipd;
    }

    /** World to eye: the head's view with the world shifted the other way by the eye's offset, {@code translation(-offset, 0, 0) * headView}. */
    public static Mat4f eyeView(Mat4f headView, float ipd, int eye) {
        return Mat4f.translation(-eyeOffset(ipd, eye), 0f, 0f).mul(headView);
    }

    /**
     * An asymmetric perspective projection from the four half angles of a field of view, as head-mounted display runtimes report them.
     *
     * @param angleLeft  angle to the left edge in radians, <b>negative</b> for a view that extends to the left of the axis
     * @param angleRight angle to the right edge, positive for a view that extends to the right
     * @param angleUp    angle to the top edge, positive for a view that extends up
     * @param angleDown  angle to the bottom edge, <b>negative</b> for a view that extends down
     */
    public static Mat4f projection(float angleLeft, float angleRight, float angleUp, float angleDown, float near, float far, ClipSpace space) {
        float t = near;
        return Mat4f.frustum(t * (float) Math.tan(angleLeft), t * (float) Math.tan(angleRight), t * (float) Math.tan(angleDown), t * (float) Math.tan(angleUp),
                near, far, space);
    }

    /**
     * As {@link #projection} with reversed depth and an infinite far plane (near maps to depth 1, infinity to 0). Needs a [0, 1] clip space, so
     * {@link ClipSpace#OPENGL} is rejected.
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
     * The off-axis projection of an eye that looks at a physical screen (a stereo monitor, a CAVE wall, a projector): the screen is a rectangle of
     * {@code 2 * halfWidth} by {@code 2 * halfHeight} centred on the head's axis at distance {@code screenDistance}, and the eye is at {@code eyeOffset} along X. Use it with
     * {@link #eyeView} for the same eye. A point on the screen plane lands at the same screen position in both eyes (no parallax at the screen), nearer points shift
     * one way and farther points the other, which is the stereo effect.
     */
    public static Mat4f offAxis(float halfWidth, float halfHeight, float screenDistance, float eyeOffset, float near, float far, ClipSpace space) {
        float k = near / screenDistance;
        return Mat4f.frustum((-halfWidth - eyeOffset) * k, (halfWidth - eyeOffset) * k, -halfHeight * k, halfHeight * k, near, far, space);
    }
}
