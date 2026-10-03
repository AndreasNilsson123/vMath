package vmath.samples;

import vmath.camera.Cameraf;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;

/**
 * The camera of {@link MillionInstances}: a free-flying one steered with the keyboard and the
 * mouse, and a scripted flight along a circle for the numbers, which is the same on every run.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: it is updated and read by the render thread.
 */
final class FlyCamera {

    private static final float FOVY = 1.0f;
    private static final float NEAR = 0.5f;
    private static final float FAR = 6000f;

    private float x;
    private float y;
    private float z;
    private float yaw;
    private float pitch;
    private final Vec3f start;
    private final float startYaw;
    private final float startPitch;

    /**
     * Creates a camera at a position, looking in a direction given by two angles.
     *
     * @param position where the camera starts; must not be {@code null}
     * @param yaw the heading in radians, 0 looking along {@code -z}, positive turning towards
     *     {@code +x}
     * @param pitch the elevation in radians, positive looking up
     */
    FlyCamera(Vec3f position, float yaw, float pitch) {
        this.start = position;
        this.startYaw = yaw;
        this.startPitch = pitch;
        reset();
    }

    /**
     * Puts the camera back where it started.
     */
    void reset() {
        x = start.x();
        y = start.y();
        z = start.z();
        yaw = startYaw;
        pitch = startPitch;
    }

    /**
     * Turns the camera by mouse movement.
     *
     * @param dx the movement to the right in pixels
     * @param dy the movement down in pixels
     */
    void look(double dx, double dy) {
        yaw += (float) dx * 0.0025f;
        pitch = Math.max(-1.55f, Math.min(1.55f, pitch - (float) dy * 0.0025f));
    }

    /**
     * Moves the camera along its own axes.
     *
     * @param forward the distance along the viewing direction, negative to go back
     * @param right the distance to the right
     * @param up the distance along the world's up axis
     */
    void move(float forward, float right, float up) {
        float fx = (float) Math.sin(yaw), fz = -(float) Math.cos(yaw);
        x += fx * forward + -fz * right;
        z += fz * forward + fx * right;
        y += up;
    }

    /**
     * Places the camera on the scripted flight: a circle around the city centre, looking at the
     * centre a little downwards, which depends only on the frame number.
     *
     * @param frame the number of the frame
     * @param radius the radius of the circle
     * @param height the height of the camera above the ground
     */
    void scripted(int frame, float radius, float height) {
        float angle = frame * 0.004f;
        x = radius * (float) Math.sin(angle);
        z = -radius * (float) Math.cos(angle);
        y = height;
        yaw = angle + (float) Math.PI;
        pitch = -0.12f;
    }

    /**
     * Builds the camera for the current position and direction.
     *
     * @param aspect the aspect ratio of the viewport, width over height
     * @return the camera with an OpenGL depth convention
     */
    Cameraf camera(float aspect) {
        Vec3f position = new Vec3f(x, y, z);
        float cp = (float) Math.cos(pitch);
        Vec3f forward = new Vec3f((float) Math.sin(yaw) * cp, (float) Math.sin(pitch), -(float) Math.cos(yaw) * cp);
        return Cameraf.lookingAt(position, position.add(forward), Vec3f.UNIT_Y, FOVY, aspect, NEAR, FAR, DepthRange.NEGATIVE_ONE_TO_ONE);
    }

    /**
     * Reads the vertical field of view that {@link #camera} uses.
     *
     * @return the field of view in radians
     */
    static float fovy() {
        return FOVY;
    }
}
