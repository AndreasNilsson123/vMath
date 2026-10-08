package vmath.geo;

import vmath.annotations.Experimental;

/**
 * The ellipsoidal polar stereographic projection (Snyder, EPSG 9810 and 9829): a conformal
 * projection of a polar cap onto a plane tangent at, or cutting, the pole, for charts of the
 * polar regions and the UPS grid.
 *
 * <p>There are two ways to fix the scale: a scale factor {@code k0} at the pole
 * ({@link #ofScaleFactor}, as the Universal Polar Stereographic grid does with 0.994) or a latitude
 * of true scale ({@link #ofTrueScaleLatitude}, as the polar ice charts do with 70 degrees north or
 * 71 degrees south). {@code x} and {@code y} follow the usual orientation: for the north pole the
 * longitude {@code lon0} runs down the page ({@code x = rho sin(lon - lon0)},
 * {@code y = -rho cos(lon - lon0)}), for the south pole up it.
 *
 * <p>Conformal; the scale and the convergence are in closed form ({@code k = rho / (a m)};
 * the convergence is {@code +-(lon - lon0)}). The point of the opposite pole has no image
 * (it is at infinity) and is refused.
 *
 * <p><b>Thread safety.</b> Immutable: safe from any number of threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * PolarStereographic ups = PolarStereographic.ups(true);                    // the north polar UPS grid
 * double[] xy = new double[2];
 * ups.forward(Math.toRadians(85.0), Math.toRadians(30.0), xy);              // metres from the false origin
 * }</pre>
 */
@Experimental("new in 0.2: the projections of the map layer may change")
public final class PolarStereographic implements MapProjection {

    private final Ellipsoid ell;
    private final boolean north;
    private final double lon0;
    private final double falseEasting;
    private final double falseNorthing;
    private final double c;       // rho = c * t
    private final double k0AtPole;

    private PolarStereographic(Ellipsoid ell, boolean north, double lon0, double c, double falseEasting, double falseNorthing) {
        this.ell = ell;
        this.north = north;
        this.lon0 = lon0;
        this.c = c;
        this.falseEasting = falseEasting;
        this.falseNorthing = falseNorthing;
        double e = ell.e();
        this.k0AtPole = c * Math.sqrt(Math.pow(1 + e, 1 + e) * Math.pow(1 - e, 1 - e)) / (2.0 * ell.a());
    }

    private static double requireFinite(double v, String what) {
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException(what + " must be finite: " + v);
        }
        return v;
    }

    /**
     * Makes the projection with a scale factor at the pole (EPSG variant A).
     *
     * @param ell the ellipsoid; must not be {@code null}
     * @param north {@code true} for the north pole, {@code false} for the south pole
     * @param lon0 the longitude of the meridian that runs down the page (north) or up it (south), in radians
     * @param k0 the scale factor at the pole, positive (0.994 for UPS)
     * @param falseEasting the false easting in metres
     * @param falseNorthing the false northing in metres
     * @return the projection
     * @throws IllegalArgumentException if a number is not finite or {@code k0} is not positive
     */
    public static PolarStereographic ofScaleFactor(Ellipsoid ell, boolean north, double lon0, double k0, double falseEasting, double falseNorthing) {
        if (!(k0 > 0)) {
            throw new IllegalArgumentException("the scale factor must be positive: " + k0);
        }
        double e = ell.e();
        double c = 2.0 * ell.a() * k0 / Math.sqrt(Math.pow(1 + e, 1 + e) * Math.pow(1 - e, 1 - e));
        return new PolarStereographic(ell, north, requireFinite(lon0, "the longitude of origin"), c, requireFinite(falseEasting, "the false easting"), requireFinite(falseNorthing, "the false northing"));
    }

    /**
     * Makes the projection with a latitude of true scale (EPSG variant B); the hemisphere is that
     * of the latitude.
     *
     * @param ell the ellipsoid; must not be {@code null}
     * @param trueScaleLatitude the latitude where the scale is 1 in radians, not zero, within
     *     {@code (-pi/2, pi/2)} (for example {@code -71} degrees or {@code 70} degrees)
     * @param lon0 the longitude of the meridian that runs down the page (north) or up it (south), in radians
     * @param falseEasting the false easting in metres
     * @param falseNorthing the false northing in metres
     * @return the projection
     * @throws IllegalArgumentException if the latitude is not strictly between the equator and a pole
     */
    public static PolarStereographic ofTrueScaleLatitude(Ellipsoid ell, double trueScaleLatitude, double lon0, double falseEasting, double falseNorthing) {
        if (!(Math.abs(trueScaleLatitude) > 0 && Math.abs(trueScaleLatitude) < Math.PI / 2)) {
            throw new IllegalArgumentException("the latitude of true scale must be strictly between the equator and a pole: " + trueScaleLatitude);
        }
        double e = ell.e(), lc = Math.abs(trueScaleLatitude);
        double mc = Math.cos(lc) / Math.sqrt(1.0 - ell.e2() * Math.sin(lc) * Math.sin(lc));
        double tc = Math.exp(-MapProjections.isometricLatitude(lc, e));
        return new PolarStereographic(ell, trueScaleLatitude > 0, requireFinite(lon0, "the longitude of origin"), ell.a() * mc / tc, requireFinite(falseEasting, "the false easting"),
                requireFinite(falseNorthing, "the false northing"));
    }

    /**
     * Makes the Universal Polar Stereographic grid of a pole on WGS-84: scale factor 0.994 at the
     * pole, false easting and northing of 2 000 000 m, longitude of origin 0.
     *
     * @param north {@code true} for the north pole, {@code false} for the south pole
     * @return the projection
     */
    public static PolarStereographic ups(boolean north) {
        return ofScaleFactor(Ellipsoid.WGS84, north, 0.0, 0.994, 2_000_000.0, 2_000_000.0);
    }

    @Override
    public String name() {
        return north ? "polar stereographic (north)" : "polar stereographic (south)";
    }

    @Override
    public boolean isConformal() {
        return true;
    }

    @Override
    public Ellipsoid ellipsoid() {
        return ell;
    }

    @Override
    public void forward(double latitude, double longitude, double[] xy) {
        MapProjections.check(latitude, longitude, xy);
        double phi = north ? latitude : -latitude;     // work in the north polar aspect
        if (!(phi > -Math.PI / 2 + 1e-9) || phi > Math.PI / 2 + 1e-12) {
            throw new IllegalArgumentException("the opposite pole has no image, and the latitude must be at most 90 degrees: " + latitude);
        }
        double rho = phi >= Math.PI / 2 ? 0.0 : c * Math.exp(-MapProjections.isometricLatitude(phi, ell.e()));
        double d = longitude - lon0;
        xy[0] = falseEasting + rho * Math.sin(d);
        xy[1] = falseNorthing + (north ? -rho * Math.cos(d) : rho * Math.cos(d));
    }

    @Override
    public void inverse(double x, double y, double[] latLon) {
        MapProjections.check(x, y, latLon);
        double dx = x - falseEasting, dy = y - falseNorthing;
        double rho = Math.hypot(dx, dy);
        double phi = rho == 0 ? Math.PI / 2 : MapProjections.latitudeOfIsometric(-Math.log(rho / c), ell.e());
        double d = rho == 0 ? 0.0 : Math.atan2(dx, north ? -dy : dy);
        latLon[0] = north ? phi : -phi;
        latLon[1] = Geodesy.wrapPi(lon0 + d);
    }

    @Override
    public void scales(double latitude, double longitude, double[] out) {
        if (out.length < 2) {
            throw new IllegalArgumentException("out must have room for 2 values");
        }
        double phi = north ? latitude : -latitude;
        double k;
        if (phi >= Math.PI / 2 - 1e-12) {
            k = k0AtPole;
        } else {
            double s = Math.sin(phi);
            double rho = c * Math.exp(-MapProjections.isometricLatitude(phi, ell.e()));
            double m = Math.cos(phi) / Math.sqrt(1.0 - ell.e2() * s * s);
            k = rho / (ell.a() * m);
        }
        out[0] = k;
        out[1] = k;
    }

    @Override
    public double convergence(double latitude, double longitude) {
        double d = Geodesy.wrapPi(longitude - lon0);
        return north ? d : -d;
    }
}
