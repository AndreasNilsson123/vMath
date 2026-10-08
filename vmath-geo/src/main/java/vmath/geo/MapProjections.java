package vmath.geo;

import vmath.annotations.Experimental;

/**
 * Helpers shared by the map projections: the numerical derivatives behind the default scale and
 * convergence of {@link MapProjection}, and the conformal-latitude functions of the conformal
 * projections.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads.
 */
@Experimental("new in 0.2: the projections of the map layer may change")
public final class MapProjections {

    private MapProjections() {
    }

    /** The partial derivatives of a projection with respect to latitude and longitude: out = {xPhi, yPhi, xLam, yLam}. */
    private static void jacobian(MapProjection p, double lat, double lon, double[] out) {
        double[] a = new double[2], b = new double[2], c = new double[2], d = new double[2];
        double step = 2e-5;
        // keep the stencil away from the poles
        double centre = Math.max(-Math.PI / 2 + 2 * step, Math.min(Math.PI / 2 - 2 * step, lat));
        for (int pass = 0; pass < 2; pass++) {
            double h = pass == 0 ? step : step / 2;
            double[] r = pass == 0 ? a : c;
            p.forward(centre + h, lon, r);
            p.forward(centre - h, lon, b);
            double xp = (r[0] - b[0]) / (2 * h), yp = (r[1] - b[1]) / (2 * h);
            if (pass == 0) {
                out[0] = xp;
                out[1] = yp;
            } else {
                out[0] = (4 * xp - out[0]) / 3;
                out[1] = (4 * yp - out[1]) / 3;
            }
            p.forward(centre, lon + h, d);
            p.forward(centre, lon - h, b);
            double xl = (d[0] - b[0]) / (2 * h), yl = (d[1] - b[1]) / (2 * h);
            if (pass == 0) {
                out[2] = xl;
                out[3] = yl;
            } else {
                out[2] = (4 * xl - out[2]) / 3;
                out[3] = (4 * yl - out[3]) / 3;
            }
        }
    }

    /**
     * Computes the scale along the meridian and along the parallel by differentiating the
     * projection.
     *
     * @param p the projection; must not be {@code null}
     * @param lat the latitude in radians
     * @param lon the longitude in radians
     * @param out receives {@code h} and {@code k} at {@code out[0..1]}
     * @throws IllegalArgumentException if {@code out} is too short
     */
    public static void numericScales(MapProjection p, double lat, double lon, double[] out) {
        if (out.length < 2) {
            throw new IllegalArgumentException("out must have room for 2 values");
        }
        double[] j = new double[4];
        jacobian(p, lat, lon, j);
        double centre = Math.max(-Math.PI / 2 + 1e-4, Math.min(Math.PI / 2 - 1e-4, lat));
        Ellipsoid ell = p.ellipsoid();
        double s = Math.sin(centre), w = Math.sqrt(1.0 - ell.e2() * s * s);
        double meridianRadius = ell.a() * (1.0 - ell.e2()) / (w * w * w), parallelRadius = ell.a() * Math.cos(centre) / w;
        out[0] = Math.hypot(j[0], j[1]) / meridianRadius;
        out[1] = Math.hypot(j[2], j[3]) / parallelRadius;
    }

    /**
     * Computes the convergence by differentiating the projection: minus the bearing, on the map,
     * of the direction of true north.
     *
     * @param p the projection; must not be {@code null}
     * @param lat the latitude in radians
     * @param lon the longitude in radians
     * @return the convergence in radians
     */
    public static double numericConvergence(MapProjection p, double lat, double lon) {
        double[] j = new double[4];
        jacobian(p, lat, lon, j);
        return -Math.atan2(j[0], j[1]);
    }

    /**
     * Gives the isometric latitude on an ellipsoid, {@code asinh(tan(lat)) - e atanh(e sin(lat))},
     * in forms that keep their relative precision near the equator.
     *
     * @param lat the latitude in radians
     * @param e the eccentricity
     * @return the isometric latitude in radians
     */
    public static double isometricLatitude(double lat, double e) {
        double t = Math.tan(lat);
        double asinh = Math.copySign(Math.log1p(Math.abs(t) + t * t / (1.0 + Math.sqrt(1.0 + t * t))), t);
        double y = e * Math.sin(lat);
        return asinh - e * 0.5 * Math.log1p(2.0 * y / (1.0 - y));
    }

    /**
     * Inverts {@link #isometricLatitude} by Newton's method.
     *
     * @param psi the isometric latitude in radians
     * @param e the eccentricity
     * @return the latitude in radians
     */
    public static double latitudeOfIsometric(double psi, double e) {
        double e2 = e * e;
        double lat = 2.0 * Math.atan(Math.exp(psi)) - Math.PI / 2;
        for (int it = 0; it < 50; it++) {
            double s = Math.sin(lat), c = Math.cos(lat);
            double d = (isometricLatitude(lat, e) - psi) * (1.0 - e2 * s * s) * c / (1.0 - e2);
            lat -= d;
            if (Math.abs(d) < 1e-16) {
                break;
            }
        }
        return lat;
    }

    /**
     * Checks that two numbers are finite and a result array has room.
     *
     * @param a the first number
     * @param b the second number
     * @param out the array that must have at least two values
     * @throws IllegalArgumentException if a number is not finite or the array is too short
     */
    static void check(double a, double b, double[] out) {
        if (!Double.isFinite(a) || !Double.isFinite(b)) {
            throw new IllegalArgumentException("the coordinates must be finite: " + a + ", " + b);
        }
        if (out.length < 2) {
            throw new IllegalArgumentException("the result array must have room for 2 values");
        }
    }
}
