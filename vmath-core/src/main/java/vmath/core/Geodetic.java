package vmath.core;

import vmath.annotations.ValueType;

/**
 * A position on or near the Earth as latitude, longitude and height above the WGS-84 ellipsoid.
 *
 * <p>Latitude and longitude are in <em>radians</em> (the unit of the math functions); use
 * {@link #ofDegrees} and the {@code ...Degrees} accessors at the edges of a program. Latitude is
 * the <em>geodetic</em> latitude: the angle between the ellipsoid normal and the equatorial plane,
 * from {@code -pi / 2} (south pole) to {@code pi / 2} (north pole). Longitude is measured east of
 * the prime meridian. The height is in metres above the ellipsoid, not above sea level (the geoid
 * can differ from the ellipsoid by up to about a hundred metres). {@link Wgs84} converts between
 * this and Earth-centred Earth-fixed (ECEF) coordinates.
 *
 * <p>Nothing is normalised or checked: a latitude beyond the poles or a longitude outside
 * {@code (-pi, pi]} is kept as given and still gives the point it names.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Geodetic stockholm = Geodetic.ofDegrees(59.33, 18.07, 28.0);
 * Vec3d ecef = stockholm.toEcef();                                           // metres from the centre of the Earth
 * double degrees = stockholm.latitudeDegrees();                              // 59.33
 * }</pre>
 *
 * @param latitude the geodetic latitude in radians
 * @param longitude the longitude in radians, east positive
 * @param height the height above the ellipsoid in metres
 */
@ValueType
public record Geodetic(double latitude, double longitude, double height) {

    /**
     * Builds a position from angles in degrees.
     *
     * @param latitudeDegrees the geodetic latitude in degrees, north positive
     * @param longitudeDegrees the longitude in degrees, east positive
     * @param height the height above the ellipsoid in metres
     * @return the position, with the angles converted to radians
     */
    public static Geodetic ofDegrees(double latitudeDegrees, double longitudeDegrees, double height) {
        return new Geodetic(Math.toRadians(latitudeDegrees), Math.toRadians(longitudeDegrees), height);
    }

    /**
     * Reads the latitude in degrees.
     *
     * @return the geodetic latitude in degrees, north positive
     */
    public double latitudeDegrees() {
        return Math.toDegrees(latitude);
    }

    /**
     * Reads the longitude in degrees.
     *
     * @return the longitude in degrees, east positive
     */
    public double longitudeDegrees() {
        return Math.toDegrees(longitude);
    }

    /**
     * Converts the position to Earth-centred Earth-fixed coordinates; see {@link Wgs84#toEcef}.
     *
     * @return the ECEF position in metres
     */
    public Vec3d toEcef() {
        return Wgs84.toEcef(latitude, longitude, height);
    }

    /**
     * Compares two positions with an absolute tolerance on each component, in radians for the
     * angles and metres for the height. Longitudes that differ by a whole turn are different here.
     *
     * @param o the other position; must not be {@code null}
     * @param eps the tolerance
     * @return {@code true} when every component differs by at most {@code eps}
     */
    public boolean approxEquals(Geodetic o, double eps) {
        return Math.abs(latitude - o.latitude) <= eps && Math.abs(longitude - o.longitude) <= eps && Math.abs(height - o.height) <= eps;
    }

    /**
     * Checks all components for NaN and infinity.
     *
     * @return {@code true} when every component is finite
     */
    public boolean isFinite() {
        return Double.isFinite(latitude) && Double.isFinite(longitude) && Double.isFinite(height);
    }
}
