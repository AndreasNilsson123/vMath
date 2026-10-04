package vmath.geo;

import vmath.core.Wgs84;

/**
 * Intersection of rays with a spheroid of revolution (an ellipsoid whose two equatorial radii are
 * equal), centred on the origin with its axis along z, which is the shape of the Earth in ECEF
 * coordinates.
 *
 * <p>The ray is mapped into the space where the spheroid is the unit sphere (x and y divided by the
 * equatorial radius, z by the polar radius) and the quadratic of a ray and a sphere is solved
 * there; the ray parameter is the same in both spaces, so the result is in the units of the ray's
 * direction as for {@link Intersectiond#raySphere}.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Rayd view = Rayd.through(cameraEcef, Vec3d.ZERO);               // straight down at the centre
 * double t = Ellipsoids.rayWgs84(view, Double.POSITIVE_INFINITY); // distance to the ground
 * Vec3d hit = view.pointAt(t);
 * }</pre>
 */
public final class Ellipsoids {

    private Ellipsoids() {
    }

    /**
     * Intersects a ray with a spheroid.
     *
     * @param ray the ray in the frame of the spheroid; must not be {@code null}
     * @param a the equatorial radius in metres, positive
     * @param b the polar radius in metres, positive
     * @param tMax the largest ray parameter to test
     * @return the parameter {@code t} of the first point of the spheroid on the ray, in units of
     *     the ray's direction: 0 when the origin is inside or on it, {@code +Infinity} for a miss,
     *     a spheroid behind the origin and a hit beyond {@code tMax}
     */
    public static double raySpheroid(Rayd ray, double a, double b, double tMax) {
        double ox = ray.ox() / a, oy = ray.oy() / a, oz = ray.oz() / b;
        double dx = ray.dx() / a, dy = ray.dy() / a, dz = ray.dz() / b;
        double qa = dx * dx + dy * dy + dz * dz;
        double qb = ox * dx + oy * dy + oz * dz;
        double qc = ox * ox + oy * oy + oz * oz - 1.0;
        if (qc <= 0.0) {
            return 0.0;
        }
        double disc = qb * qb - qa * qc;
        if (qa == 0.0 || disc < 0.0 || qb >= 0.0) {
            return Double.POSITIVE_INFINITY; // no direction, a miss, or the spheroid is behind the origin
        }
        double t = (-qb - Math.sqrt(disc)) / qa;
        return t <= tMax ? t : Double.POSITIVE_INFINITY;
    }

    /**
     * Intersects a ray with the WGS-84 ellipsoid.
     *
     * @param ray the ray in ECEF coordinates; must not be {@code null}
     * @param tMax the largest ray parameter to test
     * @return the parameter of the first hit as for {@link #raySpheroid}
     */
    public static double rayWgs84(Rayd ray, double tMax) {
        return raySpheroid(ray, Wgs84.A, Wgs84.B, tMax);
    }
}
