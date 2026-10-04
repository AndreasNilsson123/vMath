package vmath.samples.framework;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_D;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_R;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_S;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

import vmath.camera.Cameraf;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;

/**
 * A free-flying camera steered with the keyboard and the mouse, and a scripted flight along a
 * circle for the numbers, which is the same on every run.
 *
 * <p>The standard controls, applied by {@link #update}: hold the left mouse button and move to
 * look, {@code W A S D} to fly, {@code Space} and {@code Left Control} for up and down,
 * {@code Left Shift} for the fast speed, {@code R} to go back to the start.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: it is updated and read by the render thread.
 */
public final class FlyCamera {

    private final float fovy;
    private final float near;
    private final float far;
    private final float speed;
    private final float fastSpeed;
    private final Vec3f start;
    private final float startYaw;
    private final float startPitch;
    private float x;
    private float y;
    private float z;
    private float yaw;
    private float pitch;

    /**
     * Creates a camera at a position, looking in a direction given by two angles.
     *
     * @param position where the camera starts; must not be {@code null}
     * @param yaw the heading in radians, 0 looking along {@code -z}, positive turning towards
     *     {@code +x}
     * @param pitch the elevation in radians, positive looking up
     * @param fovy the vertical field of view in radians
     * @param near the distance of the near plane; positive
     * @param far the distance of the far plane; greater than {@code near}
     * @param speed the flying speed in world units per second
     * @param fastSpeed the flying speed with {@code Left Shift} held
     */
    public FlyCamera(Vec3f position, float yaw, float pitch, float fovy, float near, float far, float speed, float fastSpeed) {
        this.start = position;
        this.startYaw = yaw;
        this.startPitch = pitch;
        this.fovy = fovy;
        this.near = near;
        this.far = far;
        this.speed = speed;
        this.fastSpeed = fastSpeed;
        reset();
    }

    /**
     * Puts the camera back where it started.
     */
    public void reset() {
        x = start.x();
        y = start.y();
        z = start.z();
        yaw = startYaw;
        pitch = startPitch;
    }

    /**
     * Applies the standard controls for one frame, unless the run is scripted.
     *
     * @param frame the frame; must not be {@code null}; the input is not read in a scripted run
     */
    public void update(FrameInfo frame) {
        if (frame.benchmark()) {
            return;
        }
        Input in = frame.input();
        if (in.mouseDown(GLFW_MOUSE_BUTTON_LEFT)) {
            look(in.mouseDx(), in.mouseDy());
        }
        float step = (in.down(GLFW_KEY_LEFT_SHIFT) ? fastSpeed : speed) * frame.dt();
        move(step * in.axis(GLFW_KEY_W, GLFW_KEY_S), step * in.axis(GLFW_KEY_D, GLFW_KEY_A), step * in.axis(GLFW_KEY_SPACE, GLFW_KEY_LEFT_CONTROL));
        if (in.pressed(GLFW_KEY_R)) {
            reset();
        }
    }

    /**
     * Turns the camera by mouse movement.
     *
     * @param dx the movement to the right in pixels
     * @param dy the movement down in pixels
     */
    public void look(double dx, double dy) {
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
    public void move(float forward, float right, float up) {
        float fx = (float) Math.sin(yaw), fz = -(float) Math.cos(yaw);
        x += fx * forward + -fz * right;
        z += fz * forward + fx * right;
        y += up;
    }

    /**
     * Places the camera on the scripted flight: a circle around the origin, looking at the centre
     * a little downwards, which depends only on the frame number.
     *
     * @param frame the number of the frame
     * @param radius the radius of the circle
     * @param height the height of the camera above the ground
     */
    public void scripted(int frame, float radius, float height) {
        float angle = frame * 0.004f;
        x = radius * (float) Math.sin(angle);
        z = -radius * (float) Math.cos(angle);
        y = height;
        yaw = angle + (float) Math.PI;
        pitch = -0.12f;
    }

    /**
     * Puts the camera at a position and direction, for a demo's own scripted flight.
     *
     * @param x the x coordinate
     * @param y the y coordinate
     * @param z the z coordinate
     * @param yaw the heading in radians, 0 looking along {@code -z}
     * @param pitch the elevation in radians, positive looking up
     */
    public void place(float x, float y, float z, float yaw, float pitch) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    /**
     * Builds the camera for the current position and direction.
     *
     * @param aspect the aspect ratio of the viewport, width over height
     * @return the camera with an OpenGL depth convention
     */
    public Cameraf camera(float aspect) {
        Vec3f position = new Vec3f(x, y, z);
        float cp = (float) Math.cos(pitch);
        Vec3f forward = new Vec3f((float) Math.sin(yaw) * cp, (float) Math.sin(pitch), -(float) Math.cos(yaw) * cp);
        return Cameraf.lookingAt(position, position.add(forward), Vec3f.UNIT_Y, fovy, aspect, near, far, DepthRange.NEGATIVE_ONE_TO_ONE);
    }

    /**
     * Reads the vertical field of view that {@link #camera} uses.
     *
     * @return the field of view in radians
     */
    public float fovy() {
        return fovy;
    }
}
