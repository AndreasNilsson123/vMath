package vmath.geo;

import vmath.annotations.Experimental;

/**
 * {@link WebMercator} as a {@link MapProjection}: the spherical Mercator projection of the web map
 * tiles (EPSG:3857), applied to geodetic latitudes.
 *
 * <p>The scale is that of the map against the ground of the <em>ellipsoid</em>: along the parallel
 * {@code R / (N cos(lat))} and along the meridian {@code R / (M cos(lat))}, where {@code R} is
 * the equatorial radius, {@code N} the radius of curvature in the prime vertical and {@code M}
 * that of the meridian. They differ by up to 0.67 % (N over M; not conformal), which is the price of a tile
 * grid that is the same square at every latitude; the ground size of a pixel of a tile map is
 * therefore not exactly {@code WebMercator.metersPerPixel}.
 *
 * <p>The latitude is limited to {@link WebMercator#MAX_LATITUDE}; the longitude is continued
 * periodically ({@code x = R * longitude}), so the antimeridian is not a seam.
 *
 * <p><b>Thread safety.</b> Stateless: safe from any number of threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * double[] xy = new double[2];
 * WebMercatorProjection.INSTANCE.forward(Math.toRadians(59.33), Math.toRadians(18.07), xy);   // EPSG:3857 metres
 * }</pre>
 */
@Experimental("new in 0.2: the projections of the map layer may change")
public final class WebMercatorProjection implements MapProjection {

    /** The projection. */
    public static final WebMercatorProjection INSTANCE = new WebMercatorProjection();

    private WebMercatorProjection() {
    }

    @Override
    public String name() {
        return "Web Mercator";
    }

    @Override
    public boolean isConformal() {
        return false;
    }

    @Override
    public void forward(double latitude, double longitude, double[] xy) {
        MapProjections.check(latitude, longitude, xy);
        xy[0] = WebMercator.x(longitude);
        xy[1] = WebMercator.y(latitude);
    }

    @Override
    public void inverse(double x, double y, double[] latLon) {
        MapProjections.check(x, y, latLon);
        latLon[0] = WebMercator.latitudeOfY(y);
        latLon[1] = Geodesy.wrapPi(WebMercator.longitudeOfX(x));
    }

    @Override
    public void scales(double latitude, double longitude, double[] out) {
        if (out.length < 2) {
            throw new IllegalArgumentException("out must have room for 2 values");
        }
        double lat = WebMercator.clampLatitude(latitude);
        double s = Math.sin(lat), w = Math.sqrt(1.0 - Ellipsoid.WGS84.e2() * s * s), c = Math.cos(lat);
        double r = WebMercator.RADIUS, a = Ellipsoid.WGS84.a();
        out[0] = r / c / (a * (1.0 - Ellipsoid.WGS84.e2()) / (w * w * w));
        out[1] = r / c / (a / w);
    }

    @Override
    public double convergence(double latitude, double longitude) {
        return 0.0;
    }
}
