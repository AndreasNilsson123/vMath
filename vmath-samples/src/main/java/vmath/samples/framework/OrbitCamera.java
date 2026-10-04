package vmath.samples.framework;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_R;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

import vmath.camera.Cameraf;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;

/**
 * A camera that orbits a target point: drag with the left mouse button to turn around it, turn the
 * wheel to move closer or further away, {@code R} to go back to the start. A scripted run turns
 * the camera slowly around the target, which depends only on the frame number.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: it is updated and read by the render thread.
 */
public final class OrbitCamera {

    private final Vec3f target;
    private final float startYaw;
    private final float startPitch;
    private final float startDistance;
    private final float fovy;
    private final float near;
    private final float far;
    private float yaw;
    private float pitch;
    private float distance;

    /**
     * Creates a camera that looks at a point from a distance.
     *
     * @param target the point to orbit; must not be {@code null}
     * @param yaw the heading of the camera around the target in radians, 0 looking along
     *     {@code -z} from {@code +z}
     * @param pitch the elevation of the camera above the target in radians
     * @param distance the distance from the target; positive
     * @param fovy the vertical field of view in radians
     * @param near the distance of the near plane; positive
     * @param far the distance of the far plane; greater than {@code near}
     */
    public OrbitCamera(Vec3f target, float yaw, float pitch, float distance, float fovy, float near, float far) {
        this.target = target;
        this.startYaw = yaw;
        this.startPitch = pitch;
        this.startDistance = distance;
        this.fovy = fovy;
        this.near = near;
        this.far = far;
        reset();
    }

    /**
     * Puts the camera back where it started.
     */
    public void reset() {
        yaw = startYaw;
        pitch = startPitch;
        distance = startDistance;
    }

    /**
     * Applies the controls for one frame, unless the run is scripted.
     *
     * @param frame the frame; must not be {@code null}; the input is not read in a scripted run
     */
    public void update(FrameInfo frame) {
        if (frame.benchmark()) {
            return;
        }
        Input in = frame.input();
        if (in.mouseDown(GLFW_MOUSE_BUTTON_LEFT)) {
            yaw -= in.mouseDx() * 0.005f;
            pitch = Math.max(-1.5f, Math.min(1.5f, pitch + in.mouseDy() * 0.005f));
        }
        distance = Math.max(0.5f, distance * (float) Math.pow(0.9, in.scroll()));
        if (in.pressed(GLFW_KEY_R)) {
            reset();
        }
    }

    /**
     * Places the camera on the scripted orbit: a full turn around the target every
     * {@code 1 / speed} frames, at the start elevation and distance.
     *
     * @param frame the number of the frame
     * @param speed the turn per frame in radians
     */
    public void scripted(int frame, float speed) {
        yaw = startYaw + frame * speed;
        pitch = startPitch;
        distance = startDistance;
    }

    /**
     * Puts the camera at given angles and distance, for a demo's own scripted path.
     *
     * @param yawAngle the heading around the target in radians
     * @param pitchAngle the elevation above the target in radians
     * @param dist the distance from the target; positive
     */
    public void place(float yawAngle, float pitchAngle, float dist) {
        yaw = yawAngle;
        pitch = pitchAngle;
        distance = dist;
    }

    /**
     * Builds the camera for the current angles and distance.
     *
     * @param aspect the aspect ratio of the viewport, width over height
     * @return the camera with an OpenGL depth convention
     */
    public Cameraf camera(float aspect) {
        float cp = (float) Math.cos(pitch);
        Vec3f eye = new Vec3f(target.x() + distance * cp * (float) Math.sin(yaw), target.y() + distance * (float) Math.sin(pitch),
                target.z() + distance * cp * (float) Math.cos(yaw));
        return Cameraf.lookingAt(eye, target, Vec3f.UNIT_Y, fovy, aspect, near, far, DepthRange.NEGATIVE_ONE_TO_ONE);
    }
}
