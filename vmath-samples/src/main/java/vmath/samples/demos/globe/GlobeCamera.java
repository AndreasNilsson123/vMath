package vmath.samples.demos.globe;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_D;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_S;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

import vmath.camera.Cameraf;
import vmath.core.ClipSpace;
import vmath.core.Geodetic;
import vmath.core.Mat4d;
import vmath.core.Vec3d;
import vmath.core.Vec3f;
import vmath.core.Wgs84;
import vmath.geo.DepthRange;
import vmath.geo.Frustumd;
import vmath.samples.framework.FrameInfo;
import vmath.samples.framework.Input;

/**
 * A camera for flying from orbit to street level: its position is an ECEF position in
 * {@code double}, its orientation a heading and a pitch in the local East-North-Up frame at that
 * position, so that "forward" stays level with the horizon wherever on the globe it is.
 *
 * <p><b>Orientation.</b> Heading 0 looks north and grows clockwise (towards the east); pitch 0
 * looks at the horizon, positive up, and -pi/2 straight down. The viewing direction and the up
 * direction of the image are built from the local frame, not from a fixed world axis, so the camera
 * has no singularity at the poles or when looking down.
 *
 * <p><b>Free flight.</b> The speed is proportional to the height above the ground (half of it per
 * second, four times that with shift, never below 1 m/s), so a flight from orbit takes about as long
 * as one close to the ground: the same keys work at every scale. The camera is kept 1.7 m above the
 * ground of {@link Planet}.
 *
 * <p><b>Clip planes.</b> The near plane grows with the height above the ground (a twentieth of it,
 * at least 0.3 m) and the far plane reaches the horizon plus the highest mountain, so the ratio of
 * the two is moderate from orbit and at most a few million at street level.
 *
 * <p><b>Scripted flight.</b> {@link #scripted} places the camera for a time on a descent from
 * 20,000 km to 2 m above a summit, tilting from straight down to nearly level and turning slowly;
 * it depends only on the time, which makes a run repeatable.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: call it from the thread that owns the OpenGL context.
 */
final class GlobeCamera {

    /**
     * The vertical field of view in radians.
     */
    static final double FOVY = 1.0;

    /**
     * The height at which the scripted flight starts, in metres.
     */
    static final double START_ALTITUDE = 2.0e7;

    /**
     * The height above the ground at which the scripted flight ends, in metres.
     */
    static final double END_ALTITUDE = 2.0;

    private static final double EYE_HEIGHT = 1.7;

    private Vec3d position = Wgs84.toEcef(0.0, 0.0, START_ALTITUDE);
    private double heading;
    private double pitch = -Math.PI / 2;
    private double latitude;
    private double longitude;
    private double height = START_ALTITUDE;
    private double ground;
    private Vec3d forward = Vec3d.ZERO;
    private Vec3d up = Vec3d.ZERO;
    private double speedFactor = 1.0;

    GlobeCamera() {
        refresh();
    }

    /**
     * Finds the place that the scripted flight ends on: the highest point of a coarse search of the
     * temperate latitudes that is below the snow line, so that the last view has grass, rock and
     * relief rather than a white plain.
     *
     * @return the longitude and latitude in radians, and the height in metres
     */
    static double[] findSummit() {
        double bestLon = 0.0, bestLat = 0.0, best = -1e9;
        for (int la = -50; la <= 60; la++) {
            for (int lo = -180; lo < 180; lo++) {
                double lon = Math.toRadians(lo), lat = Math.toRadians(la);
                double h = Planet.height(lon, lat);
                if (h > best && h <= 1500.0) {
                    best = h;
                    bestLon = lon;
                    bestLat = lat;
                }
            }
        }
        return new double[] {bestLon, bestLat, best};
    }

    /**
     * Places the camera at a geodetic position and direction.
     *
     * @param lon the longitude in radians
     * @param lat the geodetic latitude in radians
     * @param alt the height above the ellipsoid in metres
     * @param headingRadians the heading in radians, 0 north
     * @param pitchRadians the pitch in radians, 0 level and -pi/2 down
     */
    void place(double lon, double lat, double alt, double headingRadians, double pitchRadians) {
        position = Wgs84.toEcef(lat, lon, alt);
        heading = headingRadians;
        pitch = pitchRadians;
        refresh();
    }

    /**
     * Places the camera on the scripted descent.
     *
     * @param time the time in seconds
     * @param seconds how long the descent takes; after it the camera stays at the end
     * @param summit the longitude, latitude and height of the summit that the descent ends on, from
     *     {@link #findSummit}
     */
    void scripted(double time, double seconds, double[] summit) {
        double u = Math.max(0.0, Math.min(1.0, time / seconds));
        double e = u * u * (3.0 - 2.0 * u) * 0.35 + u * 0.65;
        double above = START_ALTITUDE * Math.pow(END_ALTITUDE / START_ALTITUDE, e);
        double pitchNow = -Math.PI / 2 + (Math.PI / 2 - 0.12) * Math.pow(u, 1.6);
        place(summit[0], summit[1], summit[2] + above, 0.4 + 2.5 * u, pitchNow);
    }

    /**
     * Applies the mouse and the keys for one frame.
     *
     * @param frame the frame; must not be {@code null}
     */
    void update(FrameInfo frame) {
        Input in = frame.input();
        if (in.mouseDown(GLFW_MOUSE_BUTTON_LEFT)) {
            heading += in.mouseDx() * 0.0025;
            pitch = Math.max(-Math.PI / 2 + 1e-3, Math.min(Math.PI / 2 - 1e-3, pitch - in.mouseDy() * 0.0025));
        }
        speedFactor = Math.max(0.05, Math.min(50.0, speedFactor * Math.pow(1.15, in.scroll())));
        double aboveGround = Math.max(1.0, height - ground);
        double speed = Math.max(1.0, 0.5 * aboveGround) * speedFactor * (in.down(GLFW_KEY_LEFT_SHIFT) ? 4.0 : 1.0);
        double step = speed * frame.dt();
        double f = step * in.axis(GLFW_KEY_W, GLFW_KEY_S), r = step * in.axis(GLFW_KEY_D, GLFW_KEY_A), v = step * in.axis(GLFW_KEY_SPACE, GLFW_KEY_LEFT_CONTROL);
        if (f != 0.0 || r != 0.0 || v != 0.0) {
            Vec3d right = forward.cross(up).normalize();
            Vec3d localUp = Wgs84.up(latitude, longitude);
            position = position.add(forward.mul(f)).add(right.mul(r)).add(localUp.mul(v));
        }
        refresh();
        double minHeight = ground + EYE_HEIGHT;
        if (height < minHeight) {
            position = Wgs84.toEcef(latitude, longitude, minHeight);
            refresh();
        }
    }

    // recomputes the geodetic position, the ground below and the viewing directions from the position
    private void refresh() {
        Geodetic g = Wgs84.toGeodetic(position);
        latitude = g.latitude();
        longitude = g.longitude();
        height = g.height();
        ground = Math.max(0.0, Planet.height(longitude, latitude));
        Vec3d east = Wgs84.east(longitude), north = Wgs84.north(latitude, longitude), localUp = Wgs84.up(latitude, longitude);
        Vec3d level = east.mul(Math.sin(heading)).add(north.mul(Math.cos(heading)));
        forward = level.mul(Math.cos(pitch)).add(localUp.mul(Math.sin(pitch)));
        up = level.mul(-Math.sin(pitch)).add(localUp.mul(Math.cos(pitch)));
    }

    /**
     * Gives the camera position.
     *
     * @return ECEF metres
     */
    Vec3d position() {
        return position;
    }

    /**
     * Gives the viewing direction.
     *
     * @return a unit vector in ECEF axes
     */
    Vec3d forward() {
        return forward;
    }

    /**
     * Gives the direction of the top of the image.
     *
     * @return a unit vector in ECEF axes
     */
    Vec3d up() {
        return up;
    }

    /**
     * Gives the direction of the right of the image.
     *
     * @return a unit vector in ECEF axes
     */
    Vec3d right() {
        return forward.cross(up).normalize();
    }

    /**
     * Gives the geodetic longitude of the camera.
     *
     * @return radians
     */
    double longitude() {
        return longitude;
    }

    /**
     * Gives the geodetic latitude of the camera.
     *
     * @return radians
     */
    double latitude() {
        return latitude;
    }

    /**
     * Gives the height above the ellipsoid.
     *
     * @return metres
     */
    double altitude() {
        return height;
    }

    /**
     * Gives the height above the ground below the camera (the sea counts as ground at height 0).
     *
     * @return metres
     */
    double aboveGround() {
        return Math.max(0.0, height - ground);
    }

    /**
     * Gives the near plane distance.
     *
     * @return metres
     */
    double near() {
        return Math.max(0.3, aboveGround() * 0.05);
    }

    /**
     * Gives the far plane distance: the distance to the horizon from this height plus the distance
     * from which the highest mountain could still be seen over it.
     *
     * @return metres
     */
    double far() {
        double h = Math.max(0.0, height);
        return (Math.sqrt(h * (h + 2.0 * Wgs84.A)) + Math.sqrt(2.0 * Wgs84.A * Planet.MAX_HEIGHT)) * 1.05;
    }

    /**
     * Builds the view frustum in ECEF coordinates in {@code double}, for the culling.
     *
     * @param aspect the width over the height of the viewport
     * @return the frustum
     */
    Frustumd frustum(double aspect) {
        Mat4d view = Mat4d.lookAt(position, position.add(forward), up);
        Mat4d projection = Mat4d.perspective(FOVY, aspect, near(), far(), ClipSpace.OPENGL);
        return Frustumd.fromViewProjection(projection.mul(view), DepthRange.NEGATIVE_ONE_TO_ONE);
    }

    /**
     * Builds the camera for drawing: the view-projection with the camera at the origin, because the
     * vertex shader subtracts the camera position from the tile positions itself.
     *
     * @param aspect the width over the height of the viewport
     * @return the camera, looking from (0, 0, 0)
     */
    Cameraf drawCamera(float aspect) {
        Vec3f f = new Vec3f((float) forward.x(), (float) forward.y(), (float) forward.z());
        Vec3f u = new Vec3f((float) up.x(), (float) up.y(), (float) up.z());
        return Cameraf.lookingAt(Vec3f.ZERO, f, u, (float) FOVY, aspect, (float) near(), (float) far(), DepthRange.NEGATIVE_ONE_TO_ONE);
    }
}
