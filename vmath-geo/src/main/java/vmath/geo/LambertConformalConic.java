package vmath.geo;

import vmath.annotations.Experimental;

/**
 * The Lambert conformal conic projection on an ellipsoid (EPSG 9801 and 9802, Snyder): the
 * projection of aeronautical charts and national grids of mid latitudes, in which great circles are
 * nearly straight lines and the scale is true on one or two standard parallels.
 *
 * <p>Conformal; the scale ({@code k = n rho / (a m)}) and the convergence ({@code n (lon - lon0)},
 * the angle between the meridians and the vertical of the map) are in closed form. The apex of the
 * cone is the pole of the hemisphere of the standard parallels; the opposite pole is at infinity
 * and is refused.
 *
 * <p><b>Thread safety.</b> Immutable: safe from any number of threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * // a chart of Sweden: standard parallels 56 and 65 degrees north, origin 62 north and 15 east
 * LambertConformalConic chart = LambertConformalConic.twoParallels(Ellipsoid.WGS84, Math.toRadians(56), Math.toRadians(65), Math.toRadians(62), Math.toRadians(15), 0, 0);
 * double[] xy = new double[2];
 * chart.forward(Math.toRadians(59.33), Math.toRadians(18.07), xy);
 * }</pre>
 */
@Experimental("new in 0.2: the projections of the map layer may change")
public final class LambertConformalConic implements MapProjection {

    private final Ellipsoid ell;
    private final double e;
    private final double lon0;
    private final double n;
    private final double aF;          // a F k0
    private final double rho0;
    private final double falseEasting;
    private final double falseNorthing;

    private LambertConformalConic(Ellipsoid ell, double lat1, double lat2, double lat0, double lon0, double k0, double fe, double fn, boolean oneParallel) {
        if (!Double.isFinite(lat0) || !Double.isFinite(lon0) || !Double.isFinite(fe) || !Double.isFinite(fn) || !(k0 > 0)) {
            throw new IllegalArgumentException("need finite numbers and k0 > 0");
        }
        if (!(Math.abs(lat1) < Math.PI / 2) || !(Math.abs(lat2) < Math.PI / 2) || !(Math.abs(lat0) < Math.PI / 2)) {
            throw new IllegalArgumentException("the latitudes must be strictly between the poles: " + lat1 + ", " + lat2 + ", " + lat0);
        }
        this.ell = ell;
        this.e = ell.e();
        this.lon0 = lon0;
        this.falseEasting = fe;
        this.falseNorthing = fn;
        double m1 = m(lat1), t1 = t(lat1);
        double nn;
        if (oneParallel || Math.abs(lat1 - lat2) < 1e-12) {
            nn = Math.sin(lat1);
        } else {
            nn = (Math.log(m1) - Math.log(m(lat2))) / (Math.log(t1) - Math.log(t(lat2)));
        }
        if (!(Math.abs(nn) > 1e-9)) {
            throw new IllegalArgumentException("the standard parallels are symmetric about the equator: the cone degenerates to a cylinder (use a Mercator projection)");
        }
        this.n = nn;
        double f = m1 / (nn * Math.pow(t1, nn));
        this.aF = ell.a() * f * k0;
        this.rho0 = aF * Math.pow(t(lat0), nn);
    }

    private double m(double lat) {
        double s = Math.sin(lat);
        return Math.cos(lat) / Math.sqrt(1.0 - ell.e2() * s * s);
    }

    private double t(double lat) {
        return Math.exp(-MapProjections.isometricLatitude(lat, e));
    }

    /**
     * Makes the projection with two standard parallels (EPSG 9802).
     *
     * @param ell the ellipsoid; must not be {@code null}
     * @param lat1 the first standard parallel in radians
     * @param lat2 the second standard parallel in radians (equal to the first for one parallel)
     * @param lat0 the latitude of the origin in radians
     * @param lon0 the longitude of the origin (the central meridian) in radians
     * @param falseEasting the false easting in metres
     * @param falseNorthing the false northing in metres, the northing of the latitude of origin
     * @return the projection
     * @throws IllegalArgumentException if a latitude is not strictly between the poles, a number is
     *     not finite, or the parallels are symmetric about the equator
     */
    public static LambertConformalConic twoParallels(Ellipsoid ell, double lat1, double lat2, double lat0, double lon0, double falseEasting, double falseNorthing) {
        return new LambertConformalConic(ell, lat1, lat2, lat0, lon0, 1.0, falseEasting, falseNorthing, false);
    }

    /**
     * Makes the projection with one standard parallel at the latitude of origin and a scale factor
     * there (EPSG 9801).
     *
     * @param ell the ellipsoid; must not be {@code null}
     * @param lat0 the latitude of the origin and of the standard parallel in radians, not zero
     * @param lon0 the longitude of the origin in radians
     * @param k0 the scale factor on the parallel, positive
     * @param falseEasting the false easting in metres
     * @param falseNorthing the false northing in metres
     * @return the projection
     * @throws IllegalArgumentException if the latitude is zero or at a pole, or a number is invalid
     */
    public static LambertConformalConic oneParallel(Ellipsoid ell, double lat0, double lon0, double k0, double falseEasting, double falseNorthing) {
        return new LambertConformalConic(ell, lat0, lat0, lat0, lon0, k0, falseEasting, falseNorthing, true);
    }

    /**
     * Gives the cone constant {@code n}, the sine of the latitude where the cone touches in the
     * one-parallel form.
     *
     * @return {@code n}, positive in the northern hemisphere
     */
    public double coneConstant() {
        return n;
    }

    @Override
    public String name() {
        return "Lambert conformal conic";
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
        if (Math.abs(latitude) > Math.PI / 2 + 1e-12) {
            throw new IllegalArgumentException("the latitude must be in [-pi/2, pi/2]: " + latitude);
        }
        double apex = Math.PI / 2 * Math.signum(n);
        double rho;
        if (Math.abs(latitude - apex) < 1e-14) {
            rho = 0.0;
        } else if (Math.abs(latitude + apex) < 1e-9) {
            throw new IllegalArgumentException("the pole opposite the apex has no image");
        } else {
            rho = aF * Math.pow(t(latitude), n);
        }
        double theta = n * Geodesy.wrapPi(longitude - lon0);
        xy[0] = falseEasting + rho * Math.sin(theta);
        xy[1] = falseNorthing + rho0 - rho * Math.cos(theta);
    }

    @Override
    public void inverse(double x, double y, double[] latLon) {
        MapProjections.check(x, y, latLon);
        double sign = Math.signum(n);
        double dx = x - falseEasting, dy = rho0 - (y - falseNorthing);
        double rho = sign * Math.hypot(dx, dy);
        double theta = Math.atan2(sign * dx, sign * dy);
        double lat;
        if (rho == 0) {
            lat = sign * Math.PI / 2;
        } else {
            double tt = Math.pow(rho / aF, 1.0 / n);
            lat = MapProjections.latitudeOfIsometric(-Math.log(tt), e);
        }
        latLon[0] = lat;
        latLon[1] = Geodesy.wrapPi(lon0 + theta / n);
    }

    @Override
    public void scales(double latitude, double longitude, double[] out) {
        if (out.length < 2) {
            throw new IllegalArgumentException("out must have room for 2 values");
        }
        double apex = Math.PI / 2 * Math.signum(n);
        double k;
        if (Math.abs(latitude - apex) < 1e-12) {
            k = n * aF * Math.pow(t(apex - Math.signum(n) * 1e-9), n) / (ell.a() * m(apex - Math.signum(n) * 1e-9));   // the limit at the apex
        } else {
            k = n * aF * Math.pow(t(latitude), n) / (ell.a() * m(latitude));
        }
        out[0] = k;
        out[1] = k;
    }

    @Override
    public double convergence(double latitude, double longitude) {
        return n * Geodesy.wrapPi(longitude - lon0);
    }
}
