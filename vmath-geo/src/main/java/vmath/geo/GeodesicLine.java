package vmath.geo;

import vmath.annotations.Experimental;
import vmath.core.Wgs84;

/**
 * A geodesic of the WGS-84 ellipsoid from a point in a direction: the shortest path on the
 * surface, evaluated at any distance along it.
 *
 * <p>Make one line and call {@link #position} for as many distances as needed (a range ring, the
 * sample points of a route leg, the points of a track prediction); the set-up costs about as
 * much as one position. {@link Geodesy#direct} is the one-off form.
 *
 * <p><b>Method.</b> The geodesic is mapped to a great circle on the auxiliary sphere (Bessel's
 * and Helmert's construction, the one that Karney's 2013 algorithm uses). On it the distance is
 * {@code s = b * I1(sigma)} and the longitude is {@code lambda = omega - f * sin(alpha0) * I3(sigma)},
 * where {@code sigma} is the arc on the auxiliary sphere, {@code alpha0} the azimuth at the equator
 * crossing,
 * {@code I1 = integral of sqrt(1 + k^2 sin^2 t)}, {@code I3 = integral of (2 - f) / (1 + (1 - f)
 * sqrt(1 + k^2 sin^2 t))} and {@code k^2 = e'^2 cos^2 alpha0}. Both integrands are smooth and
 * periodic, so they are integrated by 16-point Gauss-Legendre quadrature over at most a quarter
 * period (the quadrature error is far below 1e-16 relative: the singularities of the integrands
 * are at an imaginary distance of 5 or more) and the distance equation is solved by Newton's
 * method to the precision of a double. The result is accurate to a few nanometres over any
 * distance; the tests check it against published vectors and against a numerical integration of
 * the geodesic differential equations, which shares no formula with the above.
 *
 * <p>All angles are in radians and all lengths in metres. Latitudes of exactly +-90 degrees are
 * allowed; at a pole the azimuth is counted from the meridian of {@code lon1}.
 *
 * <p><b>Thread safety.</b> Immutable after construction; {@link #position} uses no shared state, so
 * a line may be shared between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * GeodesicLine line = new GeodesicLine(Math.toRadians(40.64), Math.toRadians(-73.78), Math.toRadians(53.47022));   // from JFK
 * double[] p = new double[3];
 * line.position(1_000_000.0, p);                                  // latitude, longitude and azimuth after 1000 km
 * }</pre>
 */
@Experimental("new in 0.2: the geodesy of the map layer may change")
public final class GeodesicLine {

    static final double F = Wgs84.FLATTENING;
    static final double F1 = 1.0 - Wgs84.FLATTENING;
    static final double A = Wgs84.A;
    static final double B = Wgs84.B;
    static final double EP2 = Wgs84.EP2;
    static final double TINY = Math.sqrt(Double.MIN_NORMAL);

    private static final int N = 16;
    private static final double[] NODE = new double[N];
    private static final double[] WEIGHT = new double[N];

    static {
        // Gauss-Legendre nodes and weights on [0, 1], by Newton's method on the Legendre polynomial
        for (int i = 0; i < N / 2; i++) {
            double x = Math.cos(Math.PI * (i + 0.75) / (N + 0.5));
            double pp = 1;
            for (int it = 0; it < 100; it++) {
                double p1 = 1, p2 = 0;
                for (int j = 1; j <= N; j++) {
                    double p3 = p2;
                    p2 = p1;
                    p1 = ((2.0 * j - 1.0) * x * p2 - (j - 1.0) * p3) / j;
                }
                pp = N * (x * p1 - p2) / (x * x - 1.0);
                double dx = p1 / pp;
                x -= dx;
                if (Math.abs(dx) < 1e-16) {
                    break;
                }
            }
            double w = 2.0 / ((1.0 - x * x) * pp * pp);
            NODE[i] = 0.5 * (1.0 - x);
            NODE[N - 1 - i] = 0.5 * (1.0 + x);
            WEIGHT[i] = 0.5 * w;
            WEIGHT[N - 1 - i] = 0.5 * w;
        }
    }

    private final double lat1;
    private final double lon1;
    private final double azimuth1;
    private final double salp0;
    private final double calp0;
    private final double sigma1;
    private final double omega1;
    private final double k2;
    private final double halfI1;
    private final double halfI3;
    private final double i1At1;
    private final double i3At1;

    /**
     * Starts a geodesic.
     *
     * @param lat1 the latitude of the start in radians, in {@code [-pi/2, pi/2]}
     * @param lon1 the longitude of the start in radians
     * @param azimuth1 the azimuth at the start in radians, clockwise from north
     * @throws IllegalArgumentException if an argument is not finite or the latitude is out of range
     */
    public GeodesicLine(double lat1, double lon1, double azimuth1) {
        if (!Double.isFinite(lat1) || !Double.isFinite(lon1) || !Double.isFinite(azimuth1) || Math.abs(lat1) > Math.PI / 2 + 1e-12) {
            throw new IllegalArgumentException("the latitude must be in [-pi/2, pi/2] and every angle finite: " + lat1 + ", " + lon1 + ", " + azimuth1);
        }
        this.lat1 = lat1;
        this.lon1 = lon1;
        this.azimuth1 = azimuth1;
        double salp1 = Math.sin(azimuth1), calp1 = Math.cos(azimuth1);
        double sbet1 = F1 * Math.sin(lat1), cbet1 = Math.cos(lat1);
        double n = Math.hypot(sbet1, cbet1);
        sbet1 /= n;
        cbet1 = Math.max(TINY, cbet1 / n);
        this.salp0 = salp1 * cbet1;
        this.calp0 = Math.hypot(calp1, salp1 * sbet1);
        this.sigma1 = Math.atan2(sbet1, calp1 * cbet1);
        this.k2 = calp0 * calp0 * EP2;
        double[] half = new double[2];
        quarter(k2, Math.PI / 2, half);
        this.halfI1 = half[0];
        this.halfI3 = half[1];
        double[] i = new double[2];
        integrals(sigma1, i);
        this.i1At1 = i[0];
        this.i3At1 = i[1];
        this.omega1 = Math.atan2(salp0 * sbet1, calp1 * cbet1);   // from the same components as sigma1: at a pole both are tiny and cos(sigma1) would not be
    }

    /**
     * Gives the latitude of the start.
     *
     * @return the latitude in radians
     */
    public double latitude1() {
        return lat1;
    }

    /**
     * Gives the longitude of the start.
     *
     * @return the longitude in radians
     */
    public double longitude1() {
        return lon1;
    }

    /**
     * Gives the azimuth at the start.
     *
     * @return the azimuth in radians, as given
     */
    public double azimuth1() {
        return azimuth1;
    }

    /**
     * Gives the azimuth where the geodesic crosses the equator going north.
     *
     * @return the azimuth in radians, in {@code [-pi/2, pi/2]}
     */
    public double equatorialAzimuth() {
        return Math.atan2(salp0, calp0);
    }

    // ---------------------------------------------------------------- the integrals

    /** The integrals over [0, x] for x in [0, pi/2]: out[0] is I1, out[1] is I3. */
    static void quarter(double k2, double x, double[] out) {
        double s1 = 0, s3 = 0;
        for (int i = 0; i < N; i++) {
            double st = Math.sin(x * NODE[i]);
            double g = Math.sqrt(1.0 + k2 * st * st);
            s1 += WEIGHT[i] * g;
            s3 += WEIGHT[i] * (2.0 - F) / (1.0 + F1 * g);
        }
        out[0] = s1 * x;
        out[1] = s3 * x;
    }

    /** The integrals over [0, sigma] (any sign and size) for this line's k2: out[0] is I1, out[1] is I3. */
    private void integrals(double sigma, double[] out) {
        integrals(k2, halfI1, halfI3, sigma, out);
    }

    static void integrals(double k2, double halfI1, double halfI3, double sigma, double[] out) {
        double m = Math.floor(sigma / Math.PI);
        double r = sigma - m * Math.PI;               // in [0, pi)
        double p1 = 2.0 * halfI1, p3 = 2.0 * halfI3;  // the integrals over a period of pi
        double a1, a3;
        if (r <= Math.PI / 2) {
            quarter(k2, r, out);
            a1 = out[0];
            a3 = out[1];
        } else {
            quarter(k2, Math.PI - r, out);
            a1 = p1 - out[0];
            a3 = p3 - out[1];
        }
        out[0] = m * p1 + a1;
        out[1] = m * p3 + a3;
    }

    /** The integral I1 only, for the Newton iteration of the distance equation. */
    private double i1(double sigma) {
        double m = Math.floor(sigma / Math.PI);
        double r = sigma - m * Math.PI;
        double[] t = new double[2];
        if (r <= Math.PI / 2) {
            quarter(k2, r, t);
            return m * 2.0 * halfI1 + t[0];
        }
        quarter(k2, Math.PI - r, t);
        return m * 2.0 * halfI1 + 2.0 * halfI1 - t[0];
    }

    /** The auxiliary longitude omega of the arc sigma, continuous in sigma. */
    private double omega(double sigma) {
        double m = Math.rint(sigma / Math.PI);
        double r = sigma - m * Math.PI;               // in [-pi/2, pi/2]
        // a half turn of sigma moves omega by pi in the direction of the geodesic: west (salp0 < 0) is minus
        return Math.atan2(salp0 * Math.sin(r), Math.cos(r)) + (salp0 < 0 ? -m : m) * Math.PI;
    }

    // ---------------------------------------------------------------- positions

    /**
     * Finds the point at a distance along the geodesic.
     *
     * @param s the distance in metres from the start; may be negative (behind the start) or longer
     *     than half the circumference of the Earth
     * @param out receives the latitude, the longitude (wrapped to {@code [-pi, pi]}) and the azimuth
     *     of the geodesic at the point, in radians, at {@code out[0..2]}
     * @throws IllegalArgumentException if {@code s} is not finite or {@code out} is too short
     */
    public void position(double s, double[] out) {
        position(s, out, true);
    }

    /**
     * Finds the point at a distance along the geodesic with a longitude that is not wrapped, which
     * is what a polyline that crosses the antimeridian needs: {@code out[1]} is the longitude of
     * the start plus the exact change of longitude, so successive points differ by small amounts.
     *
     * @param s the distance in metres from the start
     * @param out receives the latitude, the unwrapped longitude and the azimuth, in radians
     * @throws IllegalArgumentException if {@code s} is not finite or {@code out} is too short
     */
    public void positionContinuous(double s, double[] out) {
        position(s, out, false);
    }

    private void position(double s, double[] out, boolean wrap) {
        if (!Double.isFinite(s) || out.length < 3) {
            throw new IllegalArgumentException("the distance must be finite and out must have room for 3 values: " + s);
        }
        double t = i1At1 + s / B;
        double sigma = t / (1.0 + k2 / 4.0);
        for (int it = 0; it < 40; it++) {
            double st = Math.sin(sigma);
            double g = Math.sqrt(1.0 + k2 * st * st);
            double d = (i1(sigma) - t) / g;
            sigma -= d;
            if (Math.abs(d) <= 1e-15 * (1.0 + Math.abs(sigma))) {
                break;
            }
        }
        double[] i = new double[2];
        integrals(sigma, i);
        double ssig = Math.sin(sigma), csig = Math.cos(sigma);
        double sbet2 = calp0 * ssig, cbet2 = Math.hypot(salp0, calp0 * csig);
        double lat2 = Math.atan2(sbet2, F1 * cbet2);
        double alp2 = Math.atan2(salp0, calp0 * csig);
        double lam = (omega(sigma) - omega1) - F * salp0 * (i[1] - i3At1);
        out[0] = lat2;
        out[1] = wrap ? Geodesy.wrapPi(lon1 + lam) : lon1 + lam;
        out[2] = alp2;
    }

    // ---------------------------------------------------------------- used by the inverse problem

    /** The integrals I1 and I3 over the interval from {@code from} of the given length (out[0] is I1, out[1] is I3), with full relative precision for a short interval. */
    static void interval(double k2, double from, double length, double[] out) {
        int pieces = Math.max(1, (int) Math.ceil(Math.abs(length) / (Math.PI / 2)));
        double h = length / pieces, s1 = 0, s3 = 0;
        for (int p = 0; p < pieces; p++) {
            double a = from + p * h;
            double t1 = 0, t3 = 0;
            for (int i = 0; i < N; i++) {
                double st = Math.sin(a + h * NODE[i]);
                double g = Math.sqrt(1.0 + k2 * st * st);
                t1 += WEIGHT[i] * g;
                t3 += WEIGHT[i] * (2.0 - F) / (1.0 + F1 * g);
            }
            s1 += t1 * h;
            s3 += t3 * h;
        }
        out[0] = s1;
        out[1] = s3;
    }

    /** The distance along a geodesic with the given k2 from the arc sigmaA over an arc of sigmaLength. */
    static double distance(double k2, double sigmaA, double sigmaLength) {
        double[] t = new double[2];
        interval(k2, sigmaA, sigmaLength, t);
        return B * t[0];
    }

    /** The integral of I3 over an arc of sigmaLength from sigmaA, for the longitude correction of the inverse problem. */
    static double i3Between(double k2, double sigmaA, double sigmaLength) {
        double[] t = new double[2];
        interval(k2, sigmaA, sigmaLength, t);
        return t[1];
    }
}
