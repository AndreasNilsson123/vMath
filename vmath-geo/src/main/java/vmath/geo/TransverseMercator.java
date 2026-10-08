package vmath.geo;

import vmath.annotations.Experimental;

/**
 * The transverse Mercator projection on an ellipsoid, by the series of Krueger in the third
 * flattening {@code n} to order 6 (Karney, 2011): the projection of the UTM zones, of the British
 * National Grid and of most national grids.
 *
 * <p>The projection is conformal, with the scale {@code k0} on the central meridian. The series
 * are accurate to nanometres within 3900 km of the central meridian and to a few millimetres out
 * to 90 degrees from it, where the projection is singular at the equator; they are exact
 * enough for any use within a zone (6 degrees) and a grid ({@code +-15} degrees or so). They are
 * not the series of Redfearn that some survey software uses, which lose precision beyond 3
 * degrees.
 *
 * <p>The convergence and the scale come from the numerical derivatives of {@link MapProjection}
 * (about 1e-10); on the central meridian the convergence is zero and the scale is {@code k0}.
 *
 * <p><b>Thread safety.</b> Immutable: safe from any number of threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * // the British National Grid
 * TransverseMercator osgb = new TransverseMercator(Ellipsoid.AIRY_1830, Math.toRadians(49), Math.toRadians(-2), 0.9996012717, 400000, -100000);
 * double[] en = new double[2];
 * osgb.forward(Math.toRadians(50.5), Math.toRadians(0.5), en);        // 577274.99 E, 69740.50 N
 * }</pre>
 */
@Experimental("new in 0.2: the projections of the map layer may change")
public final class TransverseMercator implements MapProjection {

    private final Ellipsoid ell;
    private final double lon0;
    private final double k0;
    private final double falseEasting;
    private final double falseNorthing;
    private final double e;
    private final double kA;          // k0 * A
    private final double[] alpha = new double[7];
    private final double[] beta = new double[7];
    private final double y0;          // k0 A xi of the latitude of origin: subtracted from the northing

    /**
     * Makes the projection.
     *
     * @param ell the ellipsoid; must not be {@code null}
     * @param lat0 the latitude of the origin in radians (0 for a UTM zone)
     * @param lon0 the longitude of the central meridian in radians
     * @param k0 the scale factor on the central meridian, positive
     * @param falseEasting the false easting in metres
     * @param falseNorthing the false northing in metres, the northing of the latitude of origin
     * @throws IllegalArgumentException if a number is not finite, the latitude is out of range or
     *     {@code k0} is not positive
     */
    public TransverseMercator(Ellipsoid ell, double lat0, double lon0, double k0, double falseEasting, double falseNorthing) {
        if (!Double.isFinite(lat0) || !Double.isFinite(lon0) || !Double.isFinite(falseEasting) || !Double.isFinite(falseNorthing) || !(k0 > 0) || Math.abs(lat0) > Math.PI / 2) {
            throw new IllegalArgumentException("need finite numbers, a latitude of origin in [-pi/2, pi/2] and k0 > 0: " + lat0 + ", " + lon0 + ", " + k0);
        }
        this.ell = ell;
        this.lon0 = lon0;
        this.k0 = k0;
        this.falseEasting = falseEasting;
        this.falseNorthing = falseNorthing;
        this.e = ell.e();
        double n = ell.n(), n2 = n * n, n3 = n2 * n, n4 = n2 * n2, n5 = n4 * n, n6 = n3 * n3;
        double bigA = ell.a() / (1.0 + n) * (1.0 + n2 / 4 + n4 / 64 + n6 / 256);
        this.kA = k0 * bigA;
        alpha[1] = n / 2 - 2 * n2 / 3 + 5 * n3 / 16 + 41 * n4 / 180 - 127 * n5 / 288 + 7891 * n6 / 37800;
        alpha[2] = 13 * n2 / 48 - 3 * n3 / 5 + 557 * n4 / 1440 + 281 * n5 / 630 - 1983433 * n6 / 1935360;
        alpha[3] = 61 * n3 / 240 - 103 * n4 / 140 + 15061 * n5 / 26880 + 167603 * n6 / 181440;
        alpha[4] = 49561 * n4 / 161280 - 179 * n5 / 168 + 6601661 * n6 / 7257600;
        alpha[5] = 34729 * n5 / 80640 - 3418889 * n6 / 1995840;
        alpha[6] = 212378941 * n6 / 319334400;
        beta[1] = n / 2 - 2 * n2 / 3 + 37 * n3 / 96 - n4 / 360 - 81 * n5 / 512 + 96199 * n6 / 604800;
        beta[2] = n2 / 48 + n3 / 15 - 437 * n4 / 1440 + 46 * n5 / 105 - 1118711 * n6 / 3870720;
        beta[3] = 17 * n3 / 480 - 37 * n4 / 840 - 209 * n5 / 4480 + 5569 * n6 / 90720;
        beta[4] = 4397 * n4 / 161280 - 11 * n5 / 504 - 830251 * n6 / 7257600;
        beta[5] = 4583 * n5 / 161280 - 108847 * n6 / 3991680;
        beta[6] = 20648693 * n6 / 638668800;
        double[] xy = new double[2];
        project(lat0, 0.0, xy);
        this.y0 = xy[1];
    }

    /** Projects relative to the central meridian: {@code (k0 A eta, k0 A xi)}, without the false origin. */
    private void project(double lat, double dLon, double[] xy) {
        double psi = MapProjections.isometricLatitude(lat, e);
        double tauc = Math.abs(lat) >= Math.PI / 2 ? Math.copySign(1e300, lat) : Math.sinh(psi);
        double cl = Math.cos(dLon), sl = Math.sin(dLon);
        double xip = Math.atan2(tauc, cl);
        double h = Math.hypot(tauc, cl);
        double etap = asinh(sl / h);
        double xi = xip, eta = etap;
        for (int j = 1; j <= 6; j++) {
            double s2 = Math.sin(2 * j * xip), c2 = Math.cos(2 * j * xip), ch = Math.cosh(2 * j * etap), sh = Math.sinh(2 * j * etap);
            xi += alpha[j] * s2 * ch;
            eta += alpha[j] * c2 * sh;
        }
        xy[0] = kA * eta;
        xy[1] = kA * xi;
    }

    private static double asinh(double x) {
        return Math.copySign(Math.log1p(Math.abs(x) + x * x / (1.0 + Math.sqrt(1.0 + x * x))), x);
    }

    /**
     * Gives the longitude of the central meridian.
     *
     * @return the longitude in radians
     */
    public double centralMeridian() {
        return lon0;
    }

    /**
     * Gives the scale factor on the central meridian.
     *
     * @return {@code k0}
     */
    public double scaleFactor() {
        return k0;
    }

    @Override
    public String name() {
        return "transverse Mercator";
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
        double d = Geodesy.wrapPi(longitude - lon0);
        project(latitude, d, xy);
        xy[0] = falseEasting + xy[0];
        xy[1] = falseNorthing + xy[1] - y0;
    }

    @Override
    public void inverse(double x, double y, double[] latLon) {
        MapProjections.check(x, y, latLon);
        double xi = (y - falseNorthing + y0) / kA, eta = (x - falseEasting) / kA;
        double xip = xi, etap = eta;
        for (int j = 1; j <= 6; j++) {
            double s2 = Math.sin(2 * j * xi), c2 = Math.cos(2 * j * xi), ch = Math.cosh(2 * j * eta), sh = Math.sinh(2 * j * eta);
            xip -= beta[j] * s2 * ch;
            etap -= beta[j] * c2 * sh;
        }
        double sinhEta = Math.sinh(etap);
        double h = Math.hypot(sinhEta, Math.cos(xip));
        double lat;
        if (h == 0 || Double.isInfinite(sinhEta)) {
            lat = Math.copySign(Math.PI / 2, Math.sin(xip));
        } else {
            double tauc = Math.sin(xip) / h;
            lat = MapProjections.latitudeOfIsometric(asinh(tauc), e);
        }
        double dLon = Math.atan2(sinhEta, Math.cos(xip));
        latLon[0] = lat;
        latLon[1] = Geodesy.wrapPi(lon0 + dLon);
    }
}
