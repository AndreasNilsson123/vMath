package vmath.geo;

import vmath.core.Vec3d;
import vmath.core.Wgs84;

/**
 * Horizon culling against an ellipsoid: decides that a bounding sphere is hidden behind the curve of
 * the Earth as seen from a camera, which the view frustum cannot see because the hidden half of the
 * globe is inside it.
 *
 * <p><b>Method.</b> The space is scaled by {@code 1 / a} in x and y and by {@code 1 / b} in z, which
 * turns the ellipsoid into the unit sphere and the sphere to test into an ellipsoid that fits in a
 * ball of the radius {@code r / b} (the smallest scale factor is the largest growth, so the ball is
 * never too small). A ball is hidden from a camera at distance {@code d > 1} from the centre of the
 * unit sphere when it lies in the cone of rays that hit the sphere (the ball and its direction
 * angle fit in the cone's half angle {@code asin(1 / d)}) and its nearest point is beyond the
 * horizon distance {@code sqrt(d^2 - 1)}; both parts are conservative, so a sphere is reported as
 * hidden only if every point of it is hidden, and it can be reported as visible while it is just
 * behind the limb.
 *
 * <p><b>What it hides.</b> The occluder is the ellipsoid itself, at height 0. Terrain that rises
 * above it can hide more, which is ignored, and tiles of ground below it are hidden at least as
 * well as the ellipsoid hides them, so a tile sphere that covers heights from below zero to a peak
 * is handled correctly.
 *
 * <p>A camera inside the ellipsoid, or exactly on it, sees everything.
 *
 * <p><b>Thread safety.</b> Immutable once built: one instance may be used by any number of threads
 * at the same time. Create a new instance (it is a few multiplications) when the camera moves.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * HorizonCuller horizon = new HorizonCuller(Wgs84.toEcef(Geodetic.ofDegrees(0.0, 0.0, 400_000.0)));
 * Sphered tile = TileBounds.sphere(TileId.containing(6, Math.PI, 0.0), -100.0, 500.0);
 * boolean hidden = horizon.isHidden(tile);                       // the far side of the Earth: true
 * }</pre>
 */
public final class HorizonCuller {

    private final double sx;
    private final double sy;
    private final double sz;
    private final double cvMagnitudeSquared;
    private final double cvMagnitude;
    private final double a;
    private final double b;

    /**
     * Creates a culler for a camera position on the WGS-84 ellipsoid.
     *
     * @param camera the camera position in ECEF metres; must not be {@code null}
     */
    public HorizonCuller(Vec3d camera) {
        this(camera, Wgs84.A, Wgs84.B);
    }

    /**
     * Creates a culler for a camera and a spheroid of revolution.
     *
     * @param camera the camera position in metres in the frame of the ellipsoid; must not be
     *     {@code null}
     * @param a the equatorial radius in metres, positive
     * @param b the polar radius in metres, positive
     * @throws IllegalArgumentException if a radius is not positive
     */
    public HorizonCuller(Vec3d camera, double a, double b) {
        if (!(a > 0.0) || !(b > 0.0)) {
            throw new IllegalArgumentException("radii must be positive: " + a + ", " + b);
        }
        this.a = a;
        this.b = b;
        this.sx = camera.x() / a;
        this.sy = camera.y() / a;
        this.sz = camera.z() / b;
        this.cvMagnitudeSquared = sx * sx + sy * sy + sz * sz;
        this.cvMagnitude = Math.sqrt(cvMagnitudeSquared);
    }

    /**
     * Tests whether a sphere is certainly hidden behind the horizon.
     *
     * @param s the sphere in ECEF; must not be {@code null}
     * @return {@code true} if every point of the sphere is hidden by the ellipsoid, {@code false} if
     *     some of it may be visible
     */
    public boolean isHidden(Sphered s) {
        return isHidden(s.cx(), s.cy(), s.cz(), s.radius());
    }

    /**
     * Tests whether a sphere is certainly hidden behind the horizon.
     *
     * @param cx the x of the centre in ECEF metres
     * @param cy the y of the centre
     * @param cz the z of the centre
     * @param radius the radius in metres
     * @return {@code true} if every point of the sphere is hidden by the ellipsoid, {@code false} if
     *     some of it may be visible
     */
    public boolean isHidden(double cx, double cy, double cz, double radius) {
        if (cvMagnitudeSquared <= 1.0) {
            return false;
        }
        double rho = radius / Math.min(a, b);
        double wx = cx / a - sx, wy = cy / a - sy, wz = cz / b - sz;
        double wLength = Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (wLength <= rho) {
            return false; // the camera is inside the ball
        }
        // angle of the ball's centre from the axis that points from the camera to the globe's centre
        double dot = -(wx * sx + wy * sy + wz * sz) / (wLength * cvMagnitude);
        double beta = Math.acos(Math.max(-1.0, Math.min(1.0, dot)));
        double gamma = Math.asin(Math.min(1.0, rho / wLength));
        double alpha = Math.asin(1.0 / cvMagnitude);
        double horizonDistance = Math.sqrt(cvMagnitudeSquared - 1.0);
        return beta + gamma < alpha && wLength - rho > horizonDistance;
    }

    /**
     * Tests whether a sphere may be visible, the opposite of {@link #isHidden(Sphered)}.
     *
     * @param s the sphere in ECEF; must not be {@code null}
     * @return {@code false} if every point of the sphere is hidden by the ellipsoid
     */
    public boolean isVisible(Sphered s) {
        return !isHidden(s);
    }
}
