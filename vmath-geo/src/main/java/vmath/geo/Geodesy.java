package vmath.geo;

import vmath.annotations.Experimental;
import vmath.core.Wgs84;

/**
 * Distance, bearing and destination on the WGS-84 ellipsoid and on a sphere: geodesics, the fast
 * spherical forms, rhumb lines, the distance of a point from a route leg, and the clipping of a
 * geodesic or a circle to a box of latitude and longitude.
 *
 * <p>All angles are in radians (azimuths clockwise from north, in {@code [-pi, pi]} unless
 * {@link #normalizeBearing} is applied) and all lengths in metres. Heights are not used: the
 * surface is the ellipsoid.
 *
 * <p><b>Geodesics</b> ({@link #inverse}, {@link #direct}, {@link GeodesicLine}) are the shortest
 * paths on the ellipsoid, near-antipodal pairs and the poles included (the method is in
 * {@link GeodesicLine}). The tests check the direct problem against a numerical integration of the
 * geodesic equations to within 5 micrometres and the inverse problem by solving it and then the
 * direct problem from the answer, to within 10 micrometres, on random, short, polar, equatorial and
 * near-antipodal pairs. They cost about 2 microseconds for a direct problem and 9 for an inverse one
 * (random pairs, one thread, the development machine: {@code docs/MAPS.md}), against under a tenth of a
 * microsecond for the spherical forms.
 *
 * <p><b>Spherical forms</b> ({@code spherical*}) use a sphere of the mean radius
 * {@link #MEAN_RADIUS} and a closed formula; they are several times faster and wrong by a bounded
 * amount that the tests measure on random points all over the globe: distances by at most
 * {@link #SPHERICAL_DISTANCE_ERROR} of the distance (0.6 %), and the bearing of the destination
 * by at most {@link #SPHERICAL_BEARING_ERROR} radians (0.4 degrees) for distances up to 1000 km
 * (the position error for 1000 km is below 0.6 % of the distance, 6 km). Use them where that is
 * far below a pixel or where the quantity is only displayed roughly.
 *
 * <p><b>Rhumb lines</b> ({@code rhumb*}) are paths of constant bearing, exact on the ellipsoid
 * (isometric latitude and the meridian arc, by quadrature), and are straight lines on a Mercator
 * map. Between two points a rhumb line is longer than the geodesic, by little over short
 * distances.
 *
 * <p><b>Route legs.</b> {@link #crossTrack} gives how far a point is from the extended geodesic
 * of a leg and where along it the nearest point lies; {@link #distanceToLeg} limits that to the
 * leg itself.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time. The methods that take an output array write only to it.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * double[] r = new double[3];
 * Geodesy.inverse(Math.toRadians(40.64), Math.toRadians(-73.78), Math.toRadians(1.36), Math.toRadians(103.99), r);
 * // r[0] is 15 347 628 m, r[1] and r[2] are the azimuths at the two ends (53.47 and 111.59 degrees)
 * Geodesy.direct(Math.toRadians(40.64), Math.toRadians(-73.78), r[1], 1_000_000.0, r);   // latitude, longitude, azimuth 1000 km on
 * }</pre>
 */
@Experimental("new in 0.2: the geodesy of the map layer may change")
public final class Geodesy {

    /** The mean radius of the Earth in metres, {@code (2a + b) / 3}, used by the spherical forms. */
    public static final double MEAN_RADIUS = (2.0 * Wgs84.A + Wgs84.B) / 3.0;

    /** The largest relative error of a spherical distance against the geodesic distance (measured: 0.56 %). */
    public static final double SPHERICAL_DISTANCE_ERROR = 0.006;

    /** The largest error in radians of the initial bearing of the spherical forms (measured: 0.19 degrees). */
    public static final double SPHERICAL_BEARING_ERROR = Math.toRadians(0.4);

    private static final double TWO_PI = 2.0 * Math.PI;

    private Geodesy() {
    }

    // ---------------------------------------------------------------- angles

    /**
     * Wraps an angle to {@code [-pi, pi]}.
     *
     * @param angle the angle in radians
     * @return the equivalent angle in {@code [-pi, pi]}
     */
    public static double wrapPi(double angle) {
        return Math.IEEEremainder(angle, TWO_PI);
    }

    /**
     * Wraps a bearing to {@code [0, 2 pi)}, the form that is shown to a person.
     *
     * @param bearing the bearing in radians
     * @return the equivalent bearing in {@code [0, 2 pi)}
     */
    public static double normalizeBearing(double bearing) {
        double b = bearing % TWO_PI;
        if (b < 0) {
            b += TWO_PI;
        }
        return b >= TWO_PI ? 0.0 : b;
    }

    private static void checkLat(double lat) {
        if (!(Math.abs(lat) <= Math.PI / 2 + 1e-12)) {
            throw new IllegalArgumentException("the latitude must be in [-pi/2, pi/2] radians: " + lat);
        }
    }

    // ---------------------------------------------------------------- geodesics

    /**
     * Solves the direct problem: where a geodesic from a point in a direction ends after a
     * distance.
     *
     * @param lat1 the latitude of the start in radians
     * @param lon1 the longitude of the start in radians
     * @param azimuth1 the azimuth at the start in radians
     * @param distance the distance in metres, which may be negative
     * @param out receives the latitude, the longitude (wrapped to {@code [-pi, pi]}) and the azimuth
     *     at the end, in radians, at {@code out[0..2]}
     * @throws IllegalArgumentException if an argument is not finite, the latitude is out of range or
     *     {@code out} is too short
     */
    public static void direct(double lat1, double lon1, double azimuth1, double distance, double[] out) {
        new GeodesicLine(lat1, lon1, azimuth1).position(distance, out);
    }

    /** What one evaluation of the longitude function of the inverse problem leaves behind. */
    private static final class Lam {
        double lam12;
        double sig1;
        double sig12;
        double salp2;
        double calp2;
        double k2;
    }

    /** The longitude difference of the geodesic from P1 with azimuth (salp1, calp1) to the first crossing of the parallel of P2 (Karney's Lambda12). */
    private static void lambda12(double sbet1, double cbet1, double sbet2, double cbet2, double salp1, double calp1, Lam st) {
        if (sbet1 == 0 && calp1 == 0) {
            calp1 = -GeodesicLine.TINY;   // breaks the degeneracy of the equatorial line
        }
        double salp0 = salp1 * cbet1;
        double calp0 = Math.hypot(calp1, salp1 * sbet1);
        double ssig1 = sbet1, csig1 = calp1 * cbet1;
        double somg1 = salp0 * sbet1, comg1 = csig1;
        double n = Math.hypot(ssig1, csig1);
        ssig1 /= n;
        csig1 /= n;
        n = Math.hypot(somg1, comg1);
        somg1 /= n;
        comg1 /= n;
        double salp2 = cbet2 != cbet1 ? salp0 / cbet2 : salp1;
        double calp2 = cbet2 != cbet1 || Math.abs(sbet2) != -sbet1
                ? Math.sqrt(sq(calp1 * cbet1) + (cbet1 < -sbet1 ? (cbet2 - cbet1) * (cbet1 + cbet2) : (sbet1 - sbet2) * (sbet1 + sbet2))) / cbet2
                : Math.abs(calp1);
        double ssig2 = sbet2, csig2 = calp2 * cbet2;
        double somg2 = salp0 * sbet2, comg2 = csig2;
        n = Math.hypot(ssig2, csig2);
        ssig2 /= n;
        csig2 /= n;
        n = Math.hypot(somg2, comg2);
        somg2 /= n;
        comg2 /= n;
        double sig12 = Math.atan2(Math.max(0.0, csig1 * ssig2 - ssig1 * csig2), csig1 * csig2 + ssig1 * ssig2);
        double omg12 = Math.atan2(Math.max(0.0, comg1 * somg2 - somg1 * comg2), comg1 * comg2 + somg1 * somg2);
        double k2 = calp0 * calp0 * GeodesicLine.EP2;
        double sig1 = Math.atan2(ssig1, csig1);
        st.k2 = k2;
        st.sig1 = sig1;
        st.sig12 = sig12;
        st.salp2 = salp2;
        st.calp2 = calp2;
        st.lam12 = omg12 - GeodesicLine.F * salp0 * GeodesicLine.i3Between(k2, sig1, sig12);
    }

    private static double sq(double x) {
        return x * x;
    }

    /**
     * Solves the inverse problem: the shortest path between two points.
     *
     * @param lat1 the latitude of the first point in radians
     * @param lon1 the longitude of the first point in radians
     * @param lat2 the latitude of the second point in radians
     * @param lon2 the longitude of the second point in radians
     * @param out receives the distance in metres, the azimuth at the first point and the azimuth at
     *     the second point (the direction of travel there), in radians, at {@code out[0..2]}
     * @throws IllegalArgumentException if an argument is not finite, a latitude is out of range or
     *     {@code out} is too short
     */
    public static void inverse(double lat1, double lon1, double lat2, double lon2, double[] out) {
        if (!Double.isFinite(lat1) || !Double.isFinite(lon1) || !Double.isFinite(lat2) || !Double.isFinite(lon2) || out.length < 3) {
            throw new IllegalArgumentException("the angles must be finite and out must have room for 3 values");
        }
        checkLat(lat1);
        checkLat(lat2);
        double lon12 = wrapPi(lon2 - lon1);
        int lonsign = lon12 >= 0 ? 1 : -1;
        lon12 *= lonsign;
        int swapp = Math.abs(lat1) >= Math.abs(lat2) ? 1 : -1;
        if (swapp < 0) {
            lonsign = -lonsign;
            double t = lat1;
            lat1 = lat2;
            lat2 = t;
        }
        int latsign = lat1 < 0 ? 1 : -1;
        lat1 *= latsign;
        lat2 *= latsign;
        // now |lat1| >= |lat2|, lat1 <= 0 and 0 <= lon12 <= pi
        double sbet1 = GeodesicLine.F1 * Math.sin(lat1), cbet1 = Math.cos(lat1);
        double n = Math.hypot(sbet1, cbet1);
        sbet1 /= n;
        cbet1 = Math.max(GeodesicLine.TINY, cbet1 / n);
        double sbet2 = GeodesicLine.F1 * Math.sin(lat2), cbet2 = Math.cos(lat2);
        n = Math.hypot(sbet2, cbet2);
        sbet2 /= n;
        cbet2 = Math.max(GeodesicLine.TINY, cbet2 / n);
        if (cbet1 < -sbet1) {
            if (cbet2 == cbet1) {
                sbet2 = Math.copySign(sbet1, sbet2);
            }
        } else if (Math.abs(sbet2) == -sbet1) {
            cbet2 = cbet1;
        }
        double salp1, calp1, salp2, calp2, s12;
        if (sbet1 == 0 && lon12 <= (1.0 - GeodesicLine.F) * Math.PI) {
            // both points on the equator and not nearly antipodal: the equator is the geodesic
            salp1 = 1;
            calp1 = 0;
            salp2 = 1;
            calp2 = 0;
            s12 = GeodesicLine.A * lon12;
        } else {
            Lam st = new Lam();
            double[] sc = new double[2];
            solveAzimuth(sbet1, cbet1, sbet2, cbet2, lon12, st, sc);
            salp1 = sc[0];
            calp1 = sc[1];
            lambda12(sbet1, cbet1, sbet2, cbet2, salp1, calp1, st);
            salp2 = st.salp2;
            calp2 = st.calp2;
            s12 = GeodesicLine.distance(st.k2, st.sig1, st.sig12);
        }
        if (swapp < 0) {
            double t = salp1;
            salp1 = salp2;
            salp2 = t;
            t = calp1;
            calp1 = calp2;
            calp2 = t;
        }
        salp1 *= swapp * lonsign;
        calp1 *= swapp * latsign;
        salp2 *= swapp * lonsign;
        calp2 *= swapp * latsign;
        out[0] = s12;
        out[1] = Math.atan2(salp1, calp1);
        out[2] = Math.atan2(salp2, calp2);
    }

    /**
     * Finds the azimuth at the first point whose geodesic reaches the longitude difference (the first crossing from below), as a
     * pair {sin, cos}: the root is refined in a variable that keeps its relative precision near the azimuths 0, 90 and 180 degrees,
     * where an angle near a multiple of pi/2 cannot be stored finely enough (a geodesic between two close points near the
     * equator leaves at 90 degrees plus an angle of the order of 1e-15).
     */
    private static void solveAzimuth(double sbet1, double cbet1, double sbet2, double cbet2, double lon12, Lam st, double[] sc) {
        double lo = sbet1 == 0 ? Math.PI / 2 : 0.0, hi = Math.PI;
        double guess = Math.atan2(cbet2 * Math.sin(lon12), cbet1 * sbet2 - sbet1 * cbet2 * Math.cos(lon12));
        guess = Math.max(lo, Math.min(hi, guess));
        double coarse = refine(sbet1, cbet1, sbet2, cbet2, lon12, lo, hi, guess, st);
        if (Double.isNaN(coarse)) {
            coarse = scan(sbet1, cbet1, sbet2, cbet2, lon12, lo, hi, st);
        }
        int sector = coarse < Math.PI / 4 ? 0 : coarse > 3 * Math.PI / 4 ? 2 : 1;
        double vc = sector == 0 ? coarse : sector == 1 ? coarse - Math.PI / 2 : Math.PI - coarse;
        double vlo = sector == 1 ? -Math.PI / 4 : 0.0, vhi = Math.PI / 4;
        // the function is increasing in the azimuth, so decreasing in v in the last sector
        double orient = sector == 2 ? -1.0 : 1.0;
        double w = Math.max(1e-9, 1e-9 * Math.abs(vc));
        double a = Math.max(vlo, vc - w), b = Math.min(vhi, vc + w);
        double ga = orient * fv(sbet1, cbet1, sbet2, cbet2, sector, a, lon12, st), gb = orient * fv(sbet1, cbet1, sbet2, cbet2, sector, b, lon12, st);
        for (int k = 0; k < 40 && !(ga <= 0 && gb >= 0); k++) {
            if (ga > 0) {
                a = Math.max(vlo, a - (b - a) * 2);
                ga = orient * fv(sbet1, cbet1, sbet2, cbet2, sector, a, lon12, st);
            }
            if (gb < 0) {
                b = Math.min(vhi, b + (b - a) * 2);
                gb = orient * fv(sbet1, cbet1, sbet2, cbet2, sector, b, lon12, st);
            }
        }
        double v = vc;
        if (ga <= 0 && gb >= 0) {
            int side = 0;
            for (int it = 0; it < 300; it++) {
                if (ga == 0) {
                    v = a;
                    break;
                }
                if (gb == 0) {
                    v = b;
                    break;
                }
                double c = (a * gb - b * ga) / (gb - ga);
                if (!(c > Math.min(a, b) && c < Math.max(a, b))) {
                    c = 0.5 * (a + b);
                }
                double gc = orient * fv(sbet1, cbet1, sbet2, cbet2, sector, c, lon12, st);
                v = c;
                if (gc == 0 || Math.abs(b - a) <= 4e-16 * Math.max(Math.abs(a), Math.abs(b))) {
                    break;
                }
                if (gc < 0) {
                    a = c;
                    ga = gc;
                    if (side == -1) {
                        gb *= 0.5;
                    }
                    side = -1;
                } else {
                    b = c;
                    gb = gc;
                    if (side == 1) {
                        ga *= 0.5;
                    }
                    side = 1;
                }
            }
        }
        sector(sector, v, sc);
    }

    private static void sector(int sector, double v, double[] sc) {
        switch (sector) {
            case 0 -> {
                sc[0] = Math.sin(v);
                sc[1] = Math.cos(v);
            }
            case 1 -> {
                sc[0] = Math.cos(v);
                sc[1] = -Math.sin(v);
            }
            default -> {
                sc[0] = Math.sin(v);
                sc[1] = -Math.cos(v);
            }
        }
    }

    private static double fv(double sbet1, double cbet1, double sbet2, double cbet2, int sector, double v, double lon12, Lam st) {
        double[] sc = new double[2];
        sector(sector, v, sc);
        lambda12(sbet1, cbet1, sbet2, cbet2, sc[0], sc[1], st);
        return st.lam12 - lon12;
    }

    private static double f(double sbet1, double cbet1, double sbet2, double cbet2, double alp, double lon12, Lam st) {
        lambda12(sbet1, cbet1, sbet2, cbet2, Math.sin(alp), Math.cos(alp), st);
        return st.lam12 - lon12;
    }

    /** Brackets the root near a guess and solves it; NaN if the bracket cannot be found or the answer is not on the first rising branch. */
    private static double refine(double sbet1, double cbet1, double sbet2, double cbet2, double lon12, double lo, double hi, double guess, Lam st) {
        double fg = f(sbet1, cbet1, sbet2, cbet2, guess, lon12, st);
        if (fg == 0) {
            return guess;
        }
        double a, b, fa, fb;
        double step = 1e-4;
        if (fg < 0) {
            a = guess;
            fa = fg;
            b = guess;
            fb = fg;
            while (fb < 0) {
                if (b >= hi) {
                    return Double.NaN;
                }
                a = b;
                fa = fb;
                b = Math.min(hi, b + step);
                step *= 2;
                fb = f(sbet1, cbet1, sbet2, cbet2, b, lon12, st);
            }
        } else {
            b = guess;
            fb = fg;
            a = guess;
            fa = fg;
            while (fa > 0) {
                if (a <= lo) {
                    return Double.NaN;
                }
                b = a;
                fb = fa;
                a = Math.max(lo, a - step);
                step *= 2;
                fa = f(sbet1, cbet1, sbet2, cbet2, a, lon12, st);
            }
        }
        if (fa == 0) {
            return a;
        }
        if (fb == 0) {
            return b;
        }
        // Illinois method on [a, b] with fa < 0 < fb
        int side = 0;
        for (int it = 0; it < 200; it++) {
            double c = (a * fb - b * fa) / (fb - fa);
            if (!(c > Math.min(a, b) && c < Math.max(a, b))) {
                c = 0.5 * (a + b);
            }
            double fc = f(sbet1, cbet1, sbet2, cbet2, c, lon12, st);
            if (fc == 0 || Math.abs(b - a) <= 4e-16 * Math.max(1.0, Math.abs(c))) {
                return c;
            }
            if (fc < 0) {
                a = c;
                fa = fc;
                if (side == -1) {
                    fb *= 0.5;
                }
                side = -1;
            } else {
                b = c;
                fb = fc;
                if (side == 1) {
                    fa *= 0.5;
                }
                side = 1;
            }
            if (Math.abs(b - a) <= 4e-16) {
                return 0.5 * (a + b);
            }
        }
        return 0.5 * (a + b);
    }

    /** The slow, safe search: the first crossing from below on a fine grid, then refined. */
    private static double scan(double sbet1, double cbet1, double sbet2, double cbet2, double lon12, double lo, double hi, Lam st) {
        int n = 720;
        double prev = lo, fprev = f(sbet1, cbet1, sbet2, cbet2, lo, lon12, st);
        if (fprev >= 0) {
            return lo;
        }
        for (int i = 1; i <= n; i++) {
            double a = lo + (hi - lo) * i / n;
            double fa = f(sbet1, cbet1, sbet2, cbet2, a, lon12, st);
            if (fa >= 0) {
                double r = refine(sbet1, cbet1, sbet2, cbet2, lon12, prev, a, 0.5 * (prev + a), st);
                return Double.isNaN(r) ? a : r;
            }
            prev = a;
        }
        return hi;
    }

    /**
     * Gives the length of the geodesic between two points.
     *
     * @param lat1 the latitude of the first point in radians
     * @param lon1 the longitude of the first point in radians
     * @param lat2 the latitude of the second point in radians
     * @param lon2 the longitude of the second point in radians
     * @return the distance in metres
     * @throws IllegalArgumentException if an argument is not finite or a latitude is out of range
     */
    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        double[] r = new double[3];
        inverse(lat1, lon1, lat2, lon2, r);
        return r[0];
    }

    /**
     * Gives the azimuth of the geodesic at its start.
     *
     * @param lat1 the latitude of the first point in radians
     * @param lon1 the longitude of the first point in radians
     * @param lat2 the latitude of the second point in radians
     * @param lon2 the longitude of the second point in radians
     * @return the initial bearing in radians, in {@code [-pi, pi]}
     * @throws IllegalArgumentException if an argument is not finite or a latitude is out of range
     */
    public static double initialBearing(double lat1, double lon1, double lat2, double lon2) {
        double[] r = new double[3];
        inverse(lat1, lon1, lat2, lon2, r);
        return r[1];
    }

    // ---------------------------------------------------------------- spherical forms

    /**
     * Gives the great-circle distance on the mean sphere (the haversine formula, stable for small
     * distances).
     *
     * @param lat1 the latitude of the first point in radians
     * @param lon1 the longitude of the first point in radians
     * @param lat2 the latitude of the second point in radians
     * @param lon2 the longitude of the second point in radians
     * @return the distance in metres, within {@link #SPHERICAL_DISTANCE_ERROR} of the geodesic distance
     */
    public static double sphericalDistance(double lat1, double lon1, double lat2, double lon2) {
        double sdlat = Math.sin(0.5 * (lat2 - lat1)), sdlon = Math.sin(0.5 * (lon2 - lon1));
        double h = sdlat * sdlat + Math.cos(lat1) * Math.cos(lat2) * sdlon * sdlon;
        return 2.0 * MEAN_RADIUS * Math.atan2(Math.sqrt(h), Math.sqrt(Math.max(0.0, 1.0 - h)));
    }

    /**
     * Gives the initial bearing of the great circle on the sphere.
     *
     * @param lat1 the latitude of the first point in radians
     * @param lon1 the longitude of the first point in radians
     * @param lat2 the latitude of the second point in radians
     * @param lon2 the longitude of the second point in radians
     * @return the bearing in radians, in {@code [-pi, pi]}
     */
    public static double sphericalBearing(double lat1, double lon1, double lat2, double lon2) {
        double dlon = lon2 - lon1;
        return Math.atan2(Math.sin(dlon) * Math.cos(lat2), Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dlon));
    }

    /**
     * Finds the destination on the sphere.
     *
     * @param lat1 the latitude of the start in radians
     * @param lon1 the longitude of the start in radians
     * @param bearing the bearing in radians
     * @param distance the distance in metres
     * @param out receives the latitude and the longitude (wrapped) in radians at {@code out[0..1]}
     * @throws IllegalArgumentException if {@code out} is too short
     */
    public static void sphericalDestination(double lat1, double lon1, double bearing, double distance, double[] out) {
        if (out.length < 2) {
            throw new IllegalArgumentException("out must have room for 2 values");
        }
        double d = distance / MEAN_RADIUS;
        double sl = Math.sin(lat1), cl = Math.cos(lat1), sd = Math.sin(d), cd = Math.cos(d);
        double sinLat2 = sl * cd + cl * sd * Math.cos(bearing);
        double lat2 = Math.asin(Math.max(-1.0, Math.min(1.0, sinLat2)));
        double lon2 = lon1 + Math.atan2(Math.sin(bearing) * sd * cl, cd - sl * sinLat2);
        out[0] = lat2;
        out[1] = wrapPi(lon2);
    }

    /**
     * Finds the point a fraction of the way along the great circle on the sphere.
     *
     * @param lat1 the latitude of the first point in radians
     * @param lon1 the longitude of the first point in radians
     * @param lat2 the latitude of the second point in radians
     * @param lon2 the longitude of the second point in radians
     * @param fraction the fraction of the way, 0 for the first point and 1 for the second
     * @param out receives the latitude and the longitude in radians at {@code out[0..1]}
     * @throws IllegalArgumentException if {@code out} is too short
     */
    public static void sphericalIntermediate(double lat1, double lon1, double lat2, double lon2, double fraction, double[] out) {
        if (out.length < 2) {
            throw new IllegalArgumentException("out must have room for 2 values");
        }
        double d = sphericalDistance(lat1, lon1, lat2, lon2) / MEAN_RADIUS;
        if (d < 1e-15) {
            out[0] = lat1;
            out[1] = lon1;
            return;
        }
        double a = Math.sin((1.0 - fraction) * d) / Math.sin(d), b = Math.sin(fraction * d) / Math.sin(d);
        double x = a * Math.cos(lat1) * Math.cos(lon1) + b * Math.cos(lat2) * Math.cos(lon2);
        double y = a * Math.cos(lat1) * Math.sin(lon1) + b * Math.cos(lat2) * Math.sin(lon2);
        double z = a * Math.sin(lat1) + b * Math.sin(lat2);
        out[0] = Math.atan2(z, Math.hypot(x, y));
        out[1] = Math.atan2(y, x);
    }

    /**
     * Gives the cross-track and along-track distance of a point from the great circle through two
     * points on the sphere.
     *
     * @param latA the latitude of the start of the leg in radians
     * @param lonA the longitude of the start of the leg in radians
     * @param latB the latitude of the end of the leg in radians
     * @param lonB the longitude of the end of the leg in radians
     * @param latP the latitude of the point in radians
     * @param lonP the longitude of the point in radians
     * @param out receives the signed cross-track distance in metres (positive to the right of the
     *     direction of travel) and the along-track distance from A in metres (negative behind A) at
     *     {@code out[0..1]}
     * @throws IllegalArgumentException if {@code out} is too short
     */
    public static void sphericalCrossTrack(double latA, double lonA, double latB, double lonB, double latP, double lonP, double[] out) {
        if (out.length < 2) {
            throw new IllegalArgumentException("out must have room for 2 values");
        }
        double d13 = sphericalDistance(latA, lonA, latP, lonP) / MEAN_RADIUS;
        double t13 = sphericalBearing(latA, lonA, latP, lonP), t12 = sphericalBearing(latA, lonA, latB, lonB);
        double xt = Math.asin(Math.max(-1.0, Math.min(1.0, Math.sin(d13) * Math.sin(t13 - t12))));
        double cxt = Math.cos(xt);
        double along = cxt == 0 ? 0 : Math.acos(Math.max(-1.0, Math.min(1.0, Math.cos(d13) / cxt)));
        out[0] = xt * MEAN_RADIUS;
        out[1] = (Math.cos(t13 - t12) >= 0 ? along : -along) * MEAN_RADIUS;
    }

    // ---------------------------------------------------------------- rhumb lines

    private static final int N = 16;
    private static final double[] GL_NODE = new double[N];
    private static final double[] GL_WEIGHT = new double[N];

    static {
        for (int i = 0; i < N / 2; i++) {
            double xi = Math.cos(Math.PI * (i + 0.75) / (N + 0.5));
            double pp = 1;
            for (int it = 0; it < 100; it++) {
                double p1 = 1, p2 = 0;
                for (int j = 1; j <= N; j++) {
                    double p3 = p2;
                    p2 = p1;
                    p1 = ((2.0 * j - 1.0) * xi * p2 - (j - 1.0) * p3) / j;
                }
                pp = N * (xi * p1 - p2) / (xi * xi - 1.0);
                double dx = p1 / pp;
                xi -= dx;
                if (Math.abs(dx) < 1e-16) {
                    break;
                }
            }
            double w = 2.0 / ((1.0 - xi * xi) * pp * pp);
            GL_NODE[i] = 0.5 * (1.0 - xi);
            GL_NODE[N - 1 - i] = 0.5 * (1.0 + xi);
            GL_WEIGHT[i] = 0.5 * w;
            GL_WEIGHT[N - 1 - i] = 0.5 * w;
        }
    }

    private static final double E2 = Wgs84.E2;
    private static final double E = Math.sqrt(Wgs84.E2);
    private static final double MERIDIAN_QUARTER = meridianDistanceRaw(Math.PI / 2);

    private static double meridianDistanceRaw(double lat) {
        // the integral of a (1 - e^2) / (1 - e^2 sin^2 t)^(3/2) from 0 to lat, in two pieces for the sake of a long arc
        double sum = 0;
        int pieces = Math.max(1, (int) Math.ceil(Math.abs(lat) / (Math.PI / 4)));
        double h = lat / pieces;
        for (int p = 0; p < pieces; p++) {
            double a = p * h;
            double s = 0;
            for (int i = 0; i < N; i++) {
                double st = Math.sin(a + h * GL_NODE[i]);
                s += GL_WEIGHT[i] / Math.pow(1.0 - E2 * st * st, 1.5);
            }
            sum += s * h;
        }
        return Wgs84.A * (1.0 - E2) * sum;
    }

    /**
     * Gives the length of the meridian from the equator to a latitude.
     *
     * @param latitude the latitude in radians, in {@code [-pi/2, pi/2]}
     * @return the signed distance along the meridian in metres (10 001 965.729 m at the pole)
     */
    public static double meridianDistance(double latitude) {
        checkLat(latitude);
        return meridianDistanceRaw(latitude);
    }

    /**
     * Inverts {@link #meridianDistance}.
     *
     * @param distance the distance from the equator along the meridian in metres
     * @return the latitude in radians, clamped to the poles for a distance beyond them
     */
    public static double latitudeOfMeridianDistance(double distance) {
        if (distance >= MERIDIAN_QUARTER) {
            return Math.PI / 2;
        }
        if (distance <= -MERIDIAN_QUARTER) {
            return -Math.PI / 2;
        }
        double lat = distance / MERIDIAN_QUARTER * (Math.PI / 2);
        for (int it = 0; it < 50; it++) {
            double s = Math.sin(lat);
            double dm = Wgs84.A * (1.0 - E2) / Math.pow(1.0 - E2 * s * s, 1.5);
            double d = (meridianDistanceRaw(lat) - distance) / dm;
            lat -= d;
            if (Math.abs(d) < 1e-16) {
                break;
            }
        }
        return lat;
    }

    /**
     * Gives the isometric latitude, the vertical coordinate of the ellipsoidal Mercator
     * projection divided by the equatorial radius.
     *
     * @param latitude the latitude in radians, strictly between the poles
     * @return the isometric latitude in radians, infinite at the poles
     */
    public static double isometricLatitude(double latitude) {
        double s = Math.sin(latitude);
        // asinh(tan(lat)) - e atanh(e sin(lat)), in forms that keep their relative precision near the equator
        double t = Math.tan(latitude);
        double asinh = Math.copySign(Math.log1p(Math.abs(t) + t * t / (1.0 + Math.sqrt(1.0 + t * t))), t);
        double y = E * s;
        return asinh - E * 0.5 * Math.log1p(2.0 * y / (1.0 - y));
    }

    /**
     * Inverts {@link #isometricLatitude}.
     *
     * @param psi the isometric latitude in radians
     * @return the latitude in radians
     */
    public static double latitudeOfIsometric(double psi) {
        double lat = 2.0 * Math.atan(Math.exp(psi)) - Math.PI / 2;
        for (int it = 0; it < 50; it++) {
            double s = Math.sin(lat), c = Math.cos(lat);
            double d = (isometricLatitude(lat) - psi) * (1.0 - E2 * s * s) * c / (1.0 - E2);
            lat -= d;
            if (Math.abs(d) < 1e-16) {
                break;
            }
        }
        return lat;
    }

    /**
     * Solves the inverse problem for a rhumb line: the path of constant bearing between two points.
     *
     * @param lat1 the latitude of the first point in radians
     * @param lon1 the longitude of the first point in radians
     * @param lat2 the latitude of the second point in radians
     * @param lon2 the longitude of the second point in radians
     * @param out receives the distance in metres and the constant bearing in radians at
     *     {@code out[0..1]}; the way round the world is the shorter one in longitude
     * @throws IllegalArgumentException if a latitude is out of range or {@code out} is too short
     */
    public static void rhumbInverse(double lat1, double lon1, double lat2, double lon2, double[] out) {
        if (out.length < 2) {
            throw new IllegalArgumentException("out must have room for 2 values");
        }
        checkLat(lat1);
        checkLat(lat2);
        double dlon = wrapPi(lon2 - lon1);
        if (Math.abs(lat2 - lat1) < 1e-14 || Math.abs(Math.abs(lat1) - Math.PI / 2) < 1e-14 && Math.abs(Math.abs(lat2) - Math.PI / 2) < 1e-14) {
            double lat = 0.5 * (lat1 + lat2);
            double s = Math.sin(lat);
            double radius = Wgs84.A * Math.cos(lat) / Math.sqrt(1.0 - E2 * s * s);   // the radius of the parallel
            out[0] = Math.abs(dlon) * radius;
            out[1] = dlon >= 0 ? Math.PI / 2 : -Math.PI / 2;
            return;
        }
        double dpsi = isometricLatitude(lat2) - isometricLatitude(lat1);
        double dm = meridianDistanceRaw(lat2) - meridianDistanceRaw(lat1);
        out[0] = Math.abs(dm) * Math.hypot(dlon, dpsi) / Math.abs(dpsi);   // dm / cos(bearing), without the cosine of an angle near 90 degrees
        out[1] = Math.atan2(dlon, dpsi);
    }

    /**
     * Solves the direct problem for a rhumb line.
     *
     * @param lat1 the latitude of the start in radians
     * @param lon1 the longitude of the start in radians
     * @param bearing the constant bearing in radians
     * @param distance the distance in metres; a path that would pass a pole ends there
     * @param out receives the latitude and the longitude (unwrapped, so that a path round the world
     *     is not cut) in radians at {@code out[0..1]}
     * @throws IllegalArgumentException if the latitude is out of range or {@code out} is too short
     */
    public static void rhumbDirect(double lat1, double lon1, double bearing, double distance, double[] out) {
        if (out.length < 2) {
            throw new IllegalArgumentException("out must have room for 2 values");
        }
        checkLat(lat1);
        double c = Math.cos(bearing), sn = Math.sin(bearing);
        if (Math.abs(c) < 1e-14) {
            double s = Math.sin(lat1);
            double radius = Wgs84.A * Math.cos(lat1) / Math.sqrt(1.0 - E2 * s * s);
            out[0] = lat1;
            out[1] = lon1 + (radius == 0 ? 0 : distance * Math.signum(sn) / radius);
            return;
        }
        double lat2 = latitudeOfMeridianDistance(meridianDistanceRaw(lat1) + distance * c);
        double dlon;
        if (Math.abs(lat2) >= Math.PI / 2 - 1e-14) {
            dlon = 0;   // the pole: the longitude no longer matters
        } else {
            dlon = Math.tan(bearing) * (isometricLatitude(lat2) - isometricLatitude(lat1));
        }
        out[0] = lat2;
        out[1] = lon1 + dlon;
    }

    // ---------------------------------------------------------------- route legs

    /**
     * Gives how far a point is from the extended geodesic through two points and where along it
     * the nearest point is.
     *
     * <p>The nearest point is found by Newton-like iteration from the spherical answer and
     * converges in a handful of steps; it is the one within a quarter of the circumference of the
     * leg (a point nearly opposite the leg has several nearest points, and any may be returned).
     *
     * @param latA the latitude of the start of the leg in radians
     * @param lonA the longitude of the start of the leg in radians
     * @param latB the latitude of the end of the leg in radians
     * @param lonB the longitude of the end of the leg in radians
     * @param latP the latitude of the point in radians
     * @param lonP the longitude of the point in radians
     * @param out receives the signed cross-track distance in metres (positive to the right of the
     *     direction A to B), the signed along-track distance of the nearest point from A in metres
     *     and the length of the leg in metres at {@code out[0..2]}
     * @throws IllegalArgumentException if an argument is not finite, a latitude is out of range, the
     *     two ends are the same point or {@code out} is too short
     */
    public static void crossTrack(double latA, double lonA, double latB, double lonB, double latP, double lonP, double[] out) {
        if (out.length < 3) {
            throw new IllegalArgumentException("out must have room for 3 values");
        }
        double[] ab = new double[3];
        inverse(latA, lonA, latB, lonB, ab);
        if (!(ab[0] > 0)) {
            throw new IllegalArgumentException("the two ends of the leg are the same point");
        }
        GeodesicLine line = new GeodesicLine(latA, lonA, ab[1]);
        double[] sp = new double[2];
        sphericalCrossTrack(latA, lonA, latB, lonB, latP, lonP, sp);
        double s = sp[1];
        double[] q = new double[3], pq = new double[3];
        double d = 0, sinDelta = 0;
        for (int it = 0; it < 60; it++) {
            line.positionContinuous(s, q);
            inverse(q[0], q[1], latP, lonP, pq);
            d = pq[0];
            double delta = wrapPi(pq[1] - q[2]);
            sinDelta = Math.sin(delta);
            if (d < 1e-9) {
                sinDelta = 0;
                break;
            }
            // the right spherical triangle with the hypotenuse d and the angle delta at the foot: the leg is c = atan(tan(d/R) cos(delta))
            double radius = MEAN_RADIUS;
            double step = radius * Math.atan(Math.tan(Math.min(d / radius, 1.5)) * Math.cos(delta));
            s += step;
            if (Math.abs(step) < 1e-10) {
                line.positionContinuous(s, q);
                inverse(q[0], q[1], latP, lonP, pq);
                d = pq[0];
                sinDelta = Math.sin(wrapPi(pq[1] - q[2]));
                break;
            }
        }
        // the cross-track distance: the geodesic distance from the foot, signed by the side
        out[0] = sinDelta >= 0 ? d : -d;
        out[1] = s;
        out[2] = ab[0];
    }

    /**
     * Gives the distance of a point from a leg (a segment of a geodesic, not the whole line).
     *
     * @param latA the latitude of the start of the leg in radians
     * @param lonA the longitude of the start of the leg in radians
     * @param latB the latitude of the end of the leg in radians
     * @param lonB the longitude of the end of the leg in radians
     * @param latP the latitude of the point in radians
     * @param lonP the longitude of the point in radians
     * @return the distance in metres to the nearest point of the leg: the cross-track distance if
     *     the nearest point of the line is on the leg, else the distance to the nearer end
     * @throws IllegalArgumentException as {@link #crossTrack}
     */
    public static double distanceToLeg(double latA, double lonA, double latB, double lonB, double latP, double lonP) {
        double[] r = new double[3];
        crossTrack(latA, lonA, latB, lonB, latP, lonP, r);
        if (r[1] <= 0) {
            return distance(latA, lonA, latP, lonP);
        }
        if (r[1] >= r[2]) {
            return distance(latB, lonB, latP, lonP);
        }
        return Math.abs(r[0]);
    }

    // ---------------------------------------------------------------- clipping to a box

    private static boolean inBox(double lat, double lon, double south, double west, double north, double east) {
        if (lat < south || lat > north) {
            return false;
        }
        double l = wrapPi(lon);
        return west <= east ? l >= west && l <= east : l >= west || l <= east;
    }

    private static double boxSizeMeters(double south, double west, double north, double east) {
        double dlon = east >= west ? east - west : east - west + TWO_PI;
        double lat = Math.max(Math.abs(south), Math.abs(north));
        double width = dlon * Math.max(0.05, Math.cos(Math.min(lat, 1.5))) * MEAN_RADIUS;
        double height = (north - south) * MEAN_RADIUS;
        return Math.max(1.0, Math.min(width, height));
    }

    /**
     * Clips a geodesic to a box of latitude and longitude: finds the stretches of the path inside
     * the box. The box may cross the antimeridian ({@code west > east}).
     *
     * <p>The path is tested at distances no larger than a quarter of the smaller side of the box
     * and every change between inside and outside is refined by bisection to a nanometre, so a
     * stretch shorter than that spacing that lies wholly between two tests can be missed; for the
     * display of a leg on a view that is almost never a visible loss.
     *
     * @param lat1 the latitude of the start of the leg in radians
     * @param lon1 the longitude of the start of the leg in radians
     * @param lat2 the latitude of the end of the leg in radians
     * @param lon2 the longitude of the end of the leg in radians
     * @param south the southern edge of the box in radians
     * @param west the western edge in radians
     * @param north the northern edge in radians
     * @param east the eastern edge in radians
     * @param out receives the stretches as pairs of distances from the start in metres
     * @return the number of stretches, at most {@code out.length / 2} (further ones are dropped)
     * @throws IllegalArgumentException if an argument is not finite, the box is empty in latitude or
     *     the leg has no length
     */
    public static int clipGeodesic(double lat1, double lon1, double lat2, double lon2, double south, double west, double north, double east, double[] out) {
        checkBox(south, west, north, east);
        double[] ab = new double[3];
        inverse(lat1, lon1, lat2, lon2, ab);
        if (!(ab[0] > 0)) {
            throw new IllegalArgumentException("the leg has no length");
        }
        GeodesicLine line = new GeodesicLine(lat1, lon1, ab[1]);
        double total = ab[0];
        double spacing = 0.25 * boxSizeMeters(south, west, north, east);
        int steps = (int) Math.min(100_000, Math.max(8, Math.ceil(total / spacing)));
        double[] p = new double[3];
        int count = 0;
        double start = Double.NaN;
        double prevS = 0;
        line.position(0, p);
        boolean prevIn = inBox(p[0], p[1], south, west, north, east);
        if (prevIn) {
            start = 0;
        }
        for (int i = 1; i <= steps; i++) {
            double s = total * i / steps;
            line.position(s, p);
            boolean in = inBox(p[0], p[1], south, west, north, east);
            if (in != prevIn) {
                double edge = bisect(line, prevS, s, prevIn, south, west, north, east);
                if (in) {
                    start = edge;
                } else {
                    if (2 * count + 1 < out.length) {
                        out[2 * count] = start;
                        out[2 * count + 1] = edge;
                    }
                    count++;
                }
            }
            prevIn = in;
            prevS = s;
        }
        if (prevIn) {
            if (2 * count + 1 < out.length) {
                out[2 * count] = start;
                out[2 * count + 1] = total;
            }
            count++;
        }
        return Math.min(count, out.length / 2);
    }

    private static double bisect(GeodesicLine line, double a, double b, boolean insideAtA, double south, double west, double north, double east) {
        double[] p = new double[3];
        for (int i = 0; i < 100 && b - a > 1e-9; i++) {
            double m = 0.5 * (a + b);
            line.position(m, p);
            if (inBox(p[0], p[1], south, west, north, east) == insideAtA) {
                a = m;
            } else {
                b = m;
            }
        }
        return 0.5 * (a + b);
    }

    private static void checkBox(double south, double west, double north, double east) {
        if (!Double.isFinite(south) || !Double.isFinite(west) || !Double.isFinite(north) || !Double.isFinite(east) || !(north > south)) {
            throw new IllegalArgumentException("the box needs finite edges and north above south: " + south + " " + west + " " + north + " " + east);
        }
    }

    /**
     * Clips a circle of constant geodesic radius to a box: finds the arcs of it inside the box,
     * as intervals of azimuth from the centre.
     *
     * <p>The sampling and the refinement are those of {@link #clipGeodesic}. An interval that
     * contains the azimuth 0 is returned as one interval whose end is above {@code 2 pi}.
     *
     * @param centerLat the latitude of the centre in radians
     * @param centerLon the longitude of the centre in radians
     * @param radius the radius in metres, positive
     * @param south the southern edge of the box in radians
     * @param west the western edge in radians
     * @param north the northern edge in radians
     * @param east the eastern edge in radians
     * @param out receives the arcs as pairs of azimuths in radians, the start in {@code [0, 2 pi)}
     * @return the number of arcs, at most {@code out.length / 2}; a circle wholly inside the box is
     *     one arc from 0 to {@code 2 pi}
     * @throws IllegalArgumentException if an argument is not finite, the radius is not positive or
     *     the box is empty in latitude
     */
    public static int clipCircle(double centerLat, double centerLon, double radius, double south, double west, double north, double east, double[] out) {
        checkBox(south, west, north, east);
        checkLat(centerLat);
        if (!(radius > 0) || !Double.isFinite(radius) || !Double.isFinite(centerLon)) {
            throw new IllegalArgumentException("the radius must be positive and finite: " + radius);
        }
        double spacing = 0.25 * boxSizeMeters(south, west, north, east);
        int steps = (int) Math.min(100_000, Math.max(72, Math.ceil(TWO_PI * Math.min(radius, Math.PI * MEAN_RADIUS) / spacing)));
        double[] p = new double[3];
        boolean[] in = new boolean[steps + 1];
        for (int i = 0; i <= steps; i++) {
            direct(centerLat, centerLon, TWO_PI * i / steps, radius, p);
            in[i] = inBox(p[0], p[1], south, west, north, east);
        }
        in[steps] = in[0];
        int count = 0;
        boolean all = true, none = true;
        for (int i = 0; i < steps; i++) {
            all &= in[i];
            none &= !in[i];
        }
        if (none) {
            return 0;
        }
        if (all) {
            out[0] = 0;
            out[1] = TWO_PI;
            return 1;
        }
        // start at an outside sample so that no arc is split by the start of the loop
        int first = 0;
        while (in[first]) {
            first++;
        }
        double start = Double.NaN;
        for (int k = 1; k <= steps; k++) {
            int i0 = (first + k - 1) % steps, i1 = (first + k) % steps;
            if (in[i0] == in[i1]) {
                continue;
            }
            double a0 = TWO_PI * i0 / steps, a1 = a0 + TWO_PI / steps;
            double edge = bisectCircle(centerLat, centerLon, radius, a0, a1, in[i0], south, west, north, east);
            if (in[i1]) {
                start = edge;
            } else {
                double s0 = normalizeBearing(start), e0 = edge + (s0 - start);
                if (e0 < s0) {
                    e0 += TWO_PI;
                }
                if (2 * count + 1 < out.length) {
                    out[2 * count] = s0;
                    out[2 * count + 1] = e0;
                }
                count++;
            }
        }
        return Math.min(count, out.length / 2);
    }

    private static double bisectCircle(double lat, double lon, double radius, double a, double b, boolean insideAtA, double south, double west, double north, double east) {
        double[] p = new double[3];
        for (int i = 0; i < 100 && b - a > 1e-14; i++) {
            double m = 0.5 * (a + b);
            direct(lat, lon, m, radius, p);
            if (inBox(p[0], p[1], south, west, north, east) == insideAtA) {
                a = m;
            } else {
                b = m;
            }
        }
        return 0.5 * (a + b);
    }
}
