package vmath.geo;

import vmath.annotations.Experimental;

/**
 * The azimuthal equidistant projection about a centre, on the WGS-84 ellipsoid: a point lands at
 * its geodesic distance and its azimuth from the centre, {@code x = s sin(azimuth)},
 * {@code y = s cos(azimuth)}.
 *
 * <p>The range and bearing from the centre read off the map are exact, so the circles of constant
 * range ({@code Shapes} range rings) are circles on the map, and a line from the centre is a
 * geodesic. It is the projection of a radar or sensor display and of the range rings of a moving
 * map centred on the own position. Nothing else is exact: the scale across the radial direction
 * grows as {@code s / m(s)} (the distance over the reduced length of the geodesic), 1 at the centre
 * and 1.57 at a quarter of the way round the Earth, and the antipode of the centre is a whole circle
 * of the map. Use it for ranges up to a few thousand kilometres.
 *
 * <p>Forward and inverse are exact (they are the inverse and the direct problems of {@link
 * Geodesy}, a few microseconds each); the scale and the convergence come from the numerical
 * derivatives of {@link MapProjection}.
 *
 * <p><b>Thread safety.</b> Immutable: safe from any number of threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * AzimuthalEquidistant view = new AzimuthalEquidistant(Math.toRadians(59.65), Math.toRadians(17.92));   // about Stockholm-Arlanda
 * double[] xy = new double[2];
 * view.forward(Math.toRadians(55.6), Math.toRadians(13.0), xy);        // x, y: 520 km away, to the south-south-west
 * }</pre>
 */
@Experimental("new in 0.2: the projections of the map layer may change")
public final class AzimuthalEquidistant implements MapProjection {

    private final double centerLat;
    private final double centerLon;

    /**
     * Makes the projection about a centre.
     *
     * @param centerLat the latitude of the centre in radians
     * @param centerLon the longitude of the centre in radians
     * @throws IllegalArgumentException if an angle is not finite or the latitude is out of range
     */
    public AzimuthalEquidistant(double centerLat, double centerLon) {
        if (!Double.isFinite(centerLat) || !Double.isFinite(centerLon) || Math.abs(centerLat) > Math.PI / 2 + 1e-12) {
            throw new IllegalArgumentException("the centre must be a latitude in [-pi/2, pi/2] and a longitude: " + centerLat + ", " + centerLon);
        }
        this.centerLat = centerLat;
        this.centerLon = centerLon;
    }

    /**
     * Gives the latitude of the centre.
     *
     * @return the latitude in radians
     */
    public double centerLatitude() {
        return centerLat;
    }

    /**
     * Gives the longitude of the centre.
     *
     * @return the longitude in radians
     */
    public double centerLongitude() {
        return centerLon;
    }

    @Override
    public String name() {
        return "azimuthal equidistant";
    }

    @Override
    public boolean isConformal() {
        return false;
    }

    @Override
    public void forward(double latitude, double longitude, double[] xy) {
        MapProjections.check(latitude, longitude, xy);
        double[] r = new double[3];
        Geodesy.inverse(centerLat, centerLon, latitude, longitude, r);
        xy[0] = r[0] * Math.sin(r[1]);
        xy[1] = r[0] * Math.cos(r[1]);
    }

    @Override
    public void inverse(double x, double y, double[] latLon) {
        MapProjections.check(x, y, latLon);
        double s = Math.hypot(x, y);
        double[] r = new double[3];
        Geodesy.direct(centerLat, centerLon, Math.atan2(x, y), s, r);
        latLon[0] = r[0];
        latLon[1] = r[1];
    }
}
