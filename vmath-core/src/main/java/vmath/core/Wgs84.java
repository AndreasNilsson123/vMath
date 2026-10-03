package vmath.core;

/**
 * The WGS-84 ellipsoid and the conversions between geodetic coordinates, Earth-centred
 * Earth-fixed (ECEF) coordinates and local East-North-Up (ENU) frames.
 *
 * <p><b>Frames.</b> ECEF has its origin at the centre of the Earth, the {@code z} axis through the
 * north pole, the {@code x} axis through the point where the equator meets the prime meridian and
 * the {@code y} axis completing a right-handed system (through 90 degrees east); the unit is the
 * metre. The local ENU frame at a point has {@code x} pointing east, {@code y} north and {@code z}
 * up (along the ellipsoid normal), a right-handed system, which is the frame a game world or a
 * simulation on a patch of the Earth wants. {@link #enuFrame} gives it as a transform, and
 * {@link #ecefToEnu} and {@link #enuToEcef} convert points.
 *
 * <p><b>Precision.</b> Everything is in {@code double}: ECEF coordinates are millions of metres,
 * and a {@code float} cannot hold a position on the Earth to better than a metre. Convert to a
 * local frame (or subtract an origin, see {@link Rebase}) <em>in double</em> before narrowing to
 * {@code float}. The conversion from ECEF to geodetic coordinates is iterative and converges to
 * the precision of {@code double}; its accuracy is checked against the conversion in the other
 * direction.
 *
 * <p>The constants are the defining parameters of WGS-84 (the equatorial radius and the inverse
 * flattening) and the quantities derived from them.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Geodetic here = Geodetic.ofDegrees(59.33, 18.07, 28.0);
 * Vec3d ecef = Wgs84.toEcef(here);
 * Geodetic back = Wgs84.toGeodetic(ecef);                                    // here again, to rounding
 * Vec3d target = Wgs84.toEcef(Geodetic.ofDegrees(59.34, 18.07, 28.0));
 * Vec3d enu = Wgs84.ecefToEnu(here, target);                                 // about 1.1 km north: enu.y() is large, enu.x() near 0
 * }</pre>
 */
public final class Wgs84 {

    /**
     * The equatorial radius (the semi-major axis) in metres.
     */
    public static final double A = 6378137.0;

    /**
     * The inverse of the flattening, {@code 1 / f}.
     */
    public static final double INVERSE_FLATTENING = 298.257223563;

    /**
     * The flattening {@code (a - b) / a}.
     */
    public static final double FLATTENING = 1.0 / INVERSE_FLATTENING;

    /**
     * The polar radius (the semi-minor axis) in metres, {@code a (1 - f)}.
     */
    public static final double B = A * (1.0 - FLATTENING);

    /**
     * The square of the first eccentricity, {@code f (2 - f)}.
     */
    public static final double E2 = FLATTENING * (2.0 - FLATTENING);

    /**
     * The square of the second eccentricity, {@code e^2 / (1 - e^2)}.
     */
    public static final double EP2 = E2 / (1.0 - E2);

    private Wgs84() {
    }

    /**
     * Converts geodetic coordinates to ECEF.
     *
     * @param g the position; must not be {@code null}
     * @return the position in ECEF coordinates, in metres
     */
    public static Vec3d toEcef(Geodetic g) {
        return toEcef(g.latitude(), g.longitude(), g.height());
    }

    /**
     * Converts geodetic coordinates to ECEF, with the prime vertical radius of curvature:
     * {@code x = (N + h) cos(lat) cos(lon)}, {@code y = (N + h) cos(lat) sin(lon)},
     * {@code z = (N (1 - e^2) + h) sin(lat)}.
     *
     * @param latitude the geodetic latitude in radians
     * @param longitude the longitude in radians
     * @param height the height above the ellipsoid in metres
     * @return the position in ECEF coordinates, in metres
     */
    public static Vec3d toEcef(double latitude, double longitude, double height) {
        double sinLat = Math.sin(latitude), cosLat = Math.cos(latitude);
        double n = primeVerticalRadius(latitude);
        return new Vec3d((n + height) * cosLat * Math.cos(longitude), (n + height) * cosLat * Math.sin(longitude),
                (n * (1.0 - E2) + height) * sinLat);
    }

    /**
     * Converts ECEF coordinates to geodetic coordinates by iterating the latitude (Bowring's
     * method) until it changes by less than about {@code 1e-15} radians, which takes a few
     * rounds, and takes the height from the form that is accurate near the equator or near the
     * poles.
     *
     * <p>On the polar axis the longitude is 0 and the latitude is plus or minus a quarter turn.
     * The centre of the Earth gives latitude {@code pi / 2} and the height {@code -B}. The
     * longitude is in {@code (-pi, pi]}.
     *
     * @param ecef the position in ECEF coordinates, in metres; must not be {@code null}
     * @return the geodetic latitude, longitude and height
     */
    public static Geodetic toGeodetic(Vec3d ecef) {
        double x = ecef.x(), y = ecef.y(), z = ecef.z();
        double p = Math.hypot(x, y);
        double longitude = p == 0.0 ? 0.0 : Math.atan2(y, x);
        if (p < 1e-9) {
            return new Geodetic(z >= 0.0 ? Math.PI / 2 : -Math.PI / 2, longitude, Math.abs(z) - B);
        }
        double lat = Math.atan2(z, p * (1.0 - E2));
        for (int i = 0; i < 12; i++) {
            double sin = Math.sin(lat);
            double n = A / Math.sqrt(1.0 - E2 * sin * sin);
            double next = Math.atan2(z + E2 * n * sin, p);
            boolean done = Math.abs(next - lat) < 1e-15;
            lat = next;
            if (done) {
                break;
            }
        }
        double sin = Math.sin(lat), cos = Math.cos(lat);
        double n = A / Math.sqrt(1.0 - E2 * sin * sin);
        double height = Math.abs(cos) > 0.7071067811865476 ? p / cos - n : z / sin - n * (1.0 - E2);
        return new Geodetic(lat, longitude, height);
    }

    /**
     * Computes the prime vertical radius of curvature {@code N}: the distance from the surface to
     * the polar axis along the ellipsoid normal.
     *
     * @param latitude the geodetic latitude in radians
     * @return {@code a / sqrt(1 - e^2 sin^2(lat))} in metres
     */
    public static double primeVerticalRadius(double latitude) {
        double sin = Math.sin(latitude);
        return A / Math.sqrt(1.0 - E2 * sin * sin);
    }

    /**
     * Computes the meridional radius of curvature {@code M}: the radius of the circle that fits
     * the meridian at that latitude, which turns a north-south distance into an angle.
     *
     * @param latitude the geodetic latitude in radians
     * @return {@code a (1 - e^2) / (1 - e^2 sin^2(lat))^(3/2)} in metres
     */
    public static double meridionalRadius(double latitude) {
        double sin = Math.sin(latitude);
        double w = 1.0 - E2 * sin * sin;
        return A * (1.0 - E2) / (w * Math.sqrt(w));
    }

    /**
     * Computes the unit vector that points up at a place, along the ellipsoid normal, in ECEF
     * coordinates. It is not the direction to the centre of the Earth.
     *
     * @param latitude the geodetic latitude in radians
     * @param longitude the longitude in radians
     * @return the unit normal of the ellipsoid
     */
    public static Vec3d up(double latitude, double longitude) {
        double cosLat = Math.cos(latitude);
        return new Vec3d(cosLat * Math.cos(longitude), cosLat * Math.sin(longitude), Math.sin(latitude));
    }

    /**
     * Computes the unit vector that points east at a place, in ECEF coordinates.
     *
     * @param longitude the longitude in radians
     * @return the east direction, which does not depend on the latitude
     */
    public static Vec3d east(double longitude) {
        return new Vec3d(-Math.sin(longitude), Math.cos(longitude), 0.0);
    }

    /**
     * Computes the unit vector that points north at a place, in ECEF coordinates.
     *
     * @param latitude the geodetic latitude in radians
     * @param longitude the longitude in radians
     * @return the north direction, tangent to the meridian
     */
    public static Vec3d north(double latitude, double longitude) {
        double sinLat = Math.sin(latitude);
        return new Vec3d(-sinLat * Math.cos(longitude), -sinLat * Math.sin(longitude), Math.cos(latitude));
    }

    /**
     * Builds the rotation from the local East-North-Up frame at a place to ECEF.
     *
     * @param latitude the geodetic latitude in radians
     * @param longitude the longitude in radians
     * @return the rotation that takes east, north and up to their ECEF directions
     */
    public static Quatd enuToEcefRotation(double latitude, double longitude) {
        return Quatd.fromMat3(Mat3d.fromColumns(east(longitude), north(latitude, longitude), up(latitude, longitude)));
    }

    /**
     * Builds the transform from the local East-North-Up frame at a place to ECEF: points given as
     * east, north and up metres from the place become ECEF positions.
     *
     * @param origin the origin of the local frame; must not be {@code null}
     * @return the rigid transform whose translation is the ECEF position of {@code origin}
     */
    public static RigidTransformd enuFrame(Geodetic origin) {
        return new RigidTransformd(toEcef(origin), enuToEcefRotation(origin.latitude(), origin.longitude()));
    }

    /**
     * Converts an ECEF position to east, north and up metres from a place.
     *
     * @param origin the origin of the local frame; must not be {@code null}
     * @param ecef the position in ECEF coordinates; must not be {@code null}
     * @return the position in the local East-North-Up frame at {@code origin}
     */
    public static Vec3d ecefToEnu(Geodetic origin, Vec3d ecef) {
        Vec3d d = ecef.sub(toEcef(origin));
        return new Vec3d(east(origin.longitude()).dot(d), north(origin.latitude(), origin.longitude()).dot(d),
                up(origin.latitude(), origin.longitude()).dot(d));
    }

    /**
     * Converts east, north and up metres from a place to an ECEF position.
     *
     * @param origin the origin of the local frame; must not be {@code null}
     * @param enu the position in the local East-North-Up frame at {@code origin}; must not be
     *     {@code null}
     * @return the position in ECEF coordinates
     */
    public static Vec3d enuToEcef(Geodetic origin, Vec3d enu) {
        Vec3d e = east(origin.longitude()), n = north(origin.latitude(), origin.longitude()), u = up(origin.latitude(), origin.longitude());
        return toEcef(origin).add(e.mul(enu.x())).add(n.mul(enu.y())).add(u.mul(enu.z()));
    }

    /**
     * Converts a direction (a velocity, a wind) from ECEF to the East-North-Up frame at a place:
     * only the rotation applies.
     *
     * @param latitude the geodetic latitude in radians
     * @param longitude the longitude in radians
     * @param ecefDirection the direction in ECEF coordinates; must not be {@code null}
     * @return the direction as east, north and up components
     */
    public static Vec3d ecefToEnuDirection(double latitude, double longitude, Vec3d ecefDirection) {
        return new Vec3d(east(longitude).dot(ecefDirection), north(latitude, longitude).dot(ecefDirection), up(latitude, longitude).dot(ecefDirection));
    }
}
