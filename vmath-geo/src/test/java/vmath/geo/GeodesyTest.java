package vmath.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Vec3d;
import vmath.core.Wgs84;

/**
 * {@link Geodesy} and {@link GeodesicLine}: published vectors, a numerical integration of the
 * geodesic differential equations (which shares no formula with the implementation), round trips
 * of the direct and inverse problems all over the globe including the poles and near-antipodal
 * pairs, and the other tools (spherical bounds, rhumb lines, route legs, clipping).
 */
class GeodesyTest {

    private final SplittableRandom rnd = new SplittableRandom(2025);

    private double r(double lo, double hi) {
        return lo + (hi - lo) * rnd.nextDouble();
    }

    private static double deg(double d) {
        return Math.toRadians(d);
    }

    private static double ecefDistance(double lat1, double lon1, double lat2, double lon2) {
        return Wgs84.toEcef(lat1, lon1, 0).sub(Wgs84.toEcef(lat2, lon2, 0)).length();
    }

    // ---------------------------------------------------------------- published vectors

    @Test
    void theFlindersPeakToBuninyongVectorOfVincenty() {
        // the classic test of the geodetic literature (Geoscience Australia): 54 972.271 m, azimuths 306 52 05.37 and 127 10 25.07
        double lat1 = -deg(37 + 57 / 60.0 + 3.72030 / 3600), lon1 = deg(144 + 25 / 60.0 + 29.52440 / 3600);
        double lat2 = -deg(37 + 39 / 60.0 + 10.15610 / 3600), lon2 = deg(143 + 55 / 60.0 + 35.38390 / 3600);
        double[] r = new double[3];
        Geodesy.inverse(lat1, lon1, lat2, lon2, r);
        assertEquals(54972.271, r[0], 0.002);
        assertEquals(deg(306 + 52 / 60.0 + 5.37 / 3600), Geodesy.normalizeBearing(r[1]), deg(0.02 / 3600));
        // the published 127 10 25.07 is the reverse azimuth (from Buninyong back to Flinders Peak); ours is the direction of travel at the end
        assertEquals(deg(127 + 10 / 60.0 + 25.07 / 3600), Geodesy.normalizeBearing(r[2] + Math.PI), deg(0.02 / 3600));
        // and the direct problem from the published start, azimuth and distance
        double[] d = new double[3];
        Geodesy.direct(lat1, lon1, deg(306 + 52 / 60.0 + 5.37 / 3600), 54972.271, d);
        assertEquals(lat2, d[0], deg(0.02 / 3600));
        assertEquals(lon2, d[1], deg(0.02 / 3600));
    }

    @Test
    void theQuarterMeridianTheEquatorAndTheAntipodes() {
        double[] r = new double[3];
        Geodesy.inverse(0, 0, Math.PI / 2, 0, r);
        assertEquals(10001965.7293127, r[0], 1e-3, "the quarter of the meridian of WGS-84");
        assertEquals(0.0, r[1], 1e-12);
        Geodesy.inverse(0, 0, 0, 1.0, r);
        assertEquals(Wgs84.A, r[0], 1e-6, "a radian of longitude on the equator");
        assertEquals(Math.PI / 2, r[1], 1e-12);
        Geodesy.inverse(0, 0, 0, Math.PI, r);
        assertEquals(20003931.4586254, r[0], 1e-3, "the antipodes on the equator: over the poles, shorter than the equator");
        Geodesy.inverse(Math.PI / 2, 0.3, -Math.PI / 2, 2.0, r);
        assertEquals(20003931.4586254, r[0], 1e-3, "pole to pole");
        assertEquals(0.0, Geodesy.distance(deg(12), deg(34), deg(12), deg(34)), 1e-9, "the same point");
        assertEquals(Math.PI * Wgs84.A * Math.cos(deg(60)) / Math.sqrt(1 - Wgs84.E2 * Math.sin(deg(60)) * Math.sin(deg(60))) / 180.0 * 1.0,
                Geodesy.distance(deg(60), 0, deg(60), deg(1)), 25.0, "a degree along a parallel is a little longer than its geodesic (the parallel is not a geodesic)");
    }

    // ---------------------------------------------------------------- the differential equations as an oracle

    private static void derivative(double[] y, double[] dy) {
        double phi = y[0], alpha = y[2];
        double s = Math.sin(phi), w = Math.sqrt(1 - Wgs84.E2 * s * s);
        double n = Wgs84.A / w, m = Wgs84.A * (1 - Wgs84.E2) / (w * w * w);
        dy[0] = Math.cos(alpha) / m;
        dy[1] = Math.sin(alpha) / (n * Math.cos(phi));
        dy[2] = Math.sin(alpha) * Math.tan(phi) / n;
    }

    /** Integrates the geodesic equations with RK4; returns {lat, lon, azimuth, max |lat|}. */
    private static double[] integrate(double lat, double lon, double az, double distance, double step) {
        double[] y = {lat, lon, az};
        double[] k1 = new double[3], k2 = new double[3], k3 = new double[3], k4 = new double[3], t = new double[3];
        int n = (int) Math.ceil(distance / step);
        double h = distance / n, maxLat = Math.abs(lat);
        for (int i = 0; i < n; i++) {
            derivative(y, k1);
            for (int j = 0; j < 3; j++) {
                t[j] = y[j] + 0.5 * h * k1[j];
            }
            derivative(t, k2);
            for (int j = 0; j < 3; j++) {
                t[j] = y[j] + 0.5 * h * k2[j];
            }
            derivative(t, k3);
            for (int j = 0; j < 3; j++) {
                t[j] = y[j] + h * k3[j];
            }
            derivative(t, k4);
            for (int j = 0; j < 3; j++) {
                y[j] += h / 6.0 * (k1[j] + 2 * k2[j] + 2 * k3[j] + k4[j]);
            }
            maxLat = Math.max(maxLat, Math.abs(y[0]));
        }
        return new double[] {y[0], y[1], y[2], maxLat};
    }

    @Test
    void theDirectProblemAgreesWithTheNumericalIntegrationOfTheGeodesicEquations() {
        int checked = 0;
        for (int i = 0; i < 120; i++) {
            double lat = deg(r(-70, 70)), lon = deg(r(-180, 180)), az = r(-Math.PI, Math.PI), s = r(1e3, 1.8e7);
            double[] ref = integrate(lat, lon, az, s, 250.0);
            if (ref[3] > deg(82)) {
                continue;   // the equations are singular at the poles
            }
            double[] p = new double[3];
            Geodesy.direct(lat, lon, az, s, p);
            double err = ecefDistance(ref[0], ref[1], p[0], p[1]);
            assertTrue(err < 5e-6, "position differs by " + err + " m after " + s + " m from " + Math.toDegrees(lat) + ", " + Math.toDegrees(lon) + " az " + Math.toDegrees(az));
            assertEquals(0.0, Geodesy.wrapPi(ref[2] - p[2]), 1e-9, "azimuth");
            checked++;
        }
        assertTrue(checked > 60, "enough paths stay away from the poles: " + checked);
    }

    @Test
    void aLineGivesTheSamePointsAsSingleDirectProblemsAndNegativeDistancesGoBack() {
        GeodesicLine line = new GeodesicLine(deg(35), deg(139), deg(40));
        double[] a = new double[3], b = new double[3], c = new double[3];
        for (double s : new double[] {0, 1, 1e3, 5e6, 2.5e7, 4.2e7, -3e6}) {
            line.position(s, a);
            Geodesy.direct(deg(35), deg(139), deg(40), s, b);
            assertEquals(b[0], a[0], 1e-15);
            assertEquals(b[1], a[1], 1e-15);
            assertEquals(b[2], a[2], 1e-15);
        }
        line.position(0, a);
        assertEquals(deg(35), a[0], 1e-14);
        assertEquals(deg(139), a[1], 1e-14);
        assertEquals(deg(40), a[2], 1e-14);
        line.position(2e6, a);
        new GeodesicLine(a[0], a[1], a[2] + Math.PI).position(2e6, c);
        assertEquals(deg(35), c[0], 1e-11, "going back along the geodesic returns to the start");
        assertEquals(deg(139), Geodesy.wrapPi(c[1]), 1e-11);
        // the unwrapped longitude changes continuously: successive points 100 km apart never differ by more than a degree or two, in either direction, round the Earth and over a pole
        for (double azimuth : new double[] {deg(40), deg(-75), deg(-170), deg(95), deg(0), deg(180), deg(-90)}) {
            GeodesicLine g = new GeodesicLine(deg(51), deg(-5), azimuth);
            double[] prev = new double[3], cur = new double[3];
            g.positionContinuous(0, prev);
            for (double s = 100_000; s <= 4.5e7; s += 100_000) {
                g.positionContinuous(s, cur);
                double jump = Math.abs(cur[1] - prev[1]);
                assertTrue(jump < deg(9) || Math.abs(jump - Math.PI) < deg(9) && Math.abs(cur[0]) > deg(80) || Math.abs(jump - Math.PI) < 1e-9, "no jump of 360 degrees at " + s + " m, azimuth " + Math.toDegrees(azimuth) + ": " + Math.toDegrees(jump));
                assertEquals(0.0, Geodesy.wrapPi(Geodesy.wrapPi(cur[1]) - g.longitude1() - Geodesy.wrapPi(cur[1] - g.longitude1())), 1e-9);
                prev = cur.clone();
            }
        }
        g1WestwardEndsWest();
        assertThrows(IllegalArgumentException.class, () -> new GeodesicLine(2.0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new GeodesicLine(0, Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> line.position(Double.NaN, new double[3]));
        assertThrows(IllegalArgumentException.class, () -> line.position(1.0, new double[2]));
    }

    private void g1WestwardEndsWest() {
        double[] p = new double[3];
        new GeodesicLine(deg(51), deg(-5), deg(-75)).positionContinuous(5_000_000, p);
        assertTrue(p[1] < deg(-5), "a geodesic that leaves to the west has a longitude below the start's: " + Math.toDegrees(p[1]));
        new GeodesicLine(deg(51), deg(-5), deg(75)).positionContinuous(5_000_000, p);
        assertTrue(p[1] > deg(-5));
    }

    // ---------------------------------------------------------------- round trips

    private void assertRoundTrip(double lat1, double lon1, double lat2, double lon2, String what) {
        double[] inv = new double[3], dir = new double[3];
        Geodesy.inverse(lat1, lon1, lat2, lon2, inv);
        Geodesy.direct(lat1, lon1, inv[1], inv[0], dir);
        double err = ecefDistance(lat2, lon2, dir[0], dir[1]);
        assertTrue(err < 1e-5, what + ": the direct problem from the inverse answer misses by " + err + " m (distance " + inv[0] + ")");
        double[] back = new double[3];
        Geodesy.inverse(lat2, lon2, lat1, lon1, back);
        assertEquals(inv[0], back[0], 2e-6, what + ": the distance is symmetric");
        // the azimuth at the end is the reverse of the azimuth at the end of the way back
        if (inv[0] > 1.0 && inv[0] < 1.99e7) {   // a nearly antipodal pair near the equator has two paths of almost the same length, and the way back may take the other
            assertEquals(0.0, Geodesy.wrapPi(inv[2] + Math.PI - back[1]), 1e-8, what + ": the forward azimuth at the end is the reverse of the first azimuth back");
        }
        assertTrue(inv[0] >= ecefDistance(lat1, lon1, lat2, lon2) - 1e-6, what + ": a path is not shorter than the chord");
        double[] rhumb = new double[2];
        Geodesy.rhumbInverse(lat1, lon1, lat2, lon2, rhumb);
        if (Math.abs(lat1) < deg(89.9) && Math.abs(lat2) < deg(89.9)) {
            assertTrue(inv[0] <= rhumb[0] + 1e-6, what + ": the geodesic is not longer than the rhumb line: " + inv[0] + " against " + rhumb[0]);
        }
    }

    @Test
    void inverseAndDirectRoundTripAllOverTheGlobe() {
        for (int i = 0; i < 3000; i++) {
            assertRoundTrip(deg(r(-90, 90)), deg(r(-180, 180)), deg(r(-90, 90)), deg(r(-180, 180)), "random pair " + i);
        }
        for (int i = 0; i < 1000; i++) {
            // short lines, down to metres
            double lat = deg(r(-80, 80)), lon = deg(r(-180, 180)), len = Math.pow(10, r(-1, 4)) / Wgs84.A;
            assertRoundTrip(lat, lon, lat + r(-1, 1) * len, lon + r(-1, 1) * len, "short pair " + i);
        }
    }

    @Test
    void polesAndTheEquatorAndTheAntimeridianAreRegular() {
        double[] lats = {-Math.PI / 2, -deg(60), -deg(1e-6), 0.0, deg(1e-6), deg(33), Math.PI / 2};
        double[] lons = {0.0, deg(1e-9), deg(90), deg(179.999), Math.PI, -Math.PI, deg(-120)};
        for (double a : lats) {
            for (double b : lats) {
                for (double lo : lons) {
                    assertRoundTrip(a, 0.4, b, 0.4 + lo, "(" + Math.toDegrees(a) + " to " + Math.toDegrees(b) + ", " + Math.toDegrees(lo) + ")");
                }
            }
        }
    }

    @Test
    void nearlyAntipodalPairsAreSolvedAndThePathIsTheShortestOfTheTwoClosedForms() {
        for (int i = 0; i < 1500; i++) {
            double lat1 = deg(r(-89, 89)), lon1 = deg(r(-180, 180));
            double e = Math.pow(10, r(-9, -3));
            double lat2 = -lat1 + r(-1, 1) * e, lon2 = lon1 + Math.PI + r(-1, 1) * e;
            lat2 = Math.max(-Math.PI / 2, Math.min(Math.PI / 2, lat2));
            assertRoundTrip(lat1, lon1, lat2, lon2, "near-antipodal pair " + i);
            double s = Geodesy.distance(lat1, lon1, lat2, lon2);
            assertTrue(s > 19_960_000 - 3 * e * 6.4e6 && s < 20_040_000 + 3 * e * 6.4e6, "a near-antipodal distance is close to half the circumference: " + s);
        }
        // along the equator the distance grows with the longitude difference and is continuous where the equator stops being the shortest path
        double previous = 0;
        double limit = (1 - Wgs84.FLATTENING) * Math.PI;
        for (double lon = deg(170); lon <= Math.PI + 1e-12; lon += deg(0.01)) {
            double s = Geodesy.distance(0, 0, 0, lon);
            assertTrue(s >= previous - 1e-6, "the distance does not decrease with the longitude difference at " + Math.toDegrees(lon));
            if (lon <= limit) {
                assertEquals(Wgs84.A * lon, s, 1e-6);
            }
            previous = s;
        }
        assertEquals(Wgs84.A * limit, Geodesy.distance(0, 0, 0, limit + 1e-9), 1e-2, "continuous at the end of the equatorial branch");
        // on the other branch the path leaves the equator: shorter than the meridian and longer than the equatorial arc
        double beyond = Geodesy.distance(0, 0, 0, limit + deg(0.1));
        assertTrue(beyond > Wgs84.A * limit && beyond < 20003931.4587, "between the equator and the meridian: " + beyond);
    }

    @Test
    void theDistanceObeysTheTriangleInequalityAndTheInverseIsExactOnTheMeridianAndTheEquator() {
        for (int i = 0; i < 500; i++) {
            double[] a = {deg(r(-85, 85)), deg(r(-180, 180))}, b = {deg(r(-85, 85)), deg(r(-180, 180))}, c = {deg(r(-85, 85)), deg(r(-180, 180))};
            double ab = Geodesy.distance(a[0], a[1], b[0], b[1]), bc = Geodesy.distance(b[0], b[1], c[0], c[1]), ac = Geodesy.distance(a[0], a[1], c[0], c[1]);
            assertTrue(ac <= ab + bc + 1e-6, "triangle inequality");
        }
        for (int i = 0; i < 200; i++) {
            double l1 = deg(r(-90, 90)), l2 = deg(r(-90, 90)), lon = deg(r(-180, 180));
            assertEquals(Math.abs(Geodesy.meridianDistance(l2) - Geodesy.meridianDistance(l1)), Geodesy.distance(l1, lon, l2, lon), 1e-6, "along a meridian");
        }
    }

    // ---------------------------------------------------------------- spherical forms

    @Test
    void theSphericalFormsStayWithinTheirStatedBounds() {
        double worstDistance = 0, worstBearing = 0, worstDestination = 0;
        double[] e = new double[3], d = new double[2], g = new double[3];
        for (int i = 0; i < 20000; i++) {
            double lat1 = deg(r(-85, 85)), lon1 = deg(r(-180, 180)), lat2 = deg(r(-85, 85)), lon2 = deg(r(-180, 180));
            Geodesy.inverse(lat1, lon1, lat2, lon2, e);
            double sd = Geodesy.sphericalDistance(lat1, lon1, lat2, lon2);
            if (e[0] > 1e5) {
                worstDistance = Math.max(worstDistance, Math.abs(sd - e[0]) / e[0]);
            }
            // a destination within 1000 km
            double az = r(-Math.PI, Math.PI), dist = r(1e4, 1e6);
            Geodesy.direct(lat1, lon1, az, dist, g);
            Geodesy.sphericalDestination(lat1, lon1, az, dist, d);
            worstDestination = Math.max(worstDestination, ecefDistance(g[0], g[1], d[0], d[1]) / dist);
            double sb = Geodesy.sphericalBearing(lat1, lon1, g[0], g[1]);
            worstBearing = Math.max(worstBearing, Math.abs(Geodesy.wrapPi(sb - az)));
        }
        System.out.printf("spherical forms: distance error %.4f%%, destination %.4f%% of the distance, bearing %.3f degrees%n", 100 * worstDistance, 100 * worstDestination, Math.toDegrees(worstBearing));
        assertTrue(worstDistance <= Geodesy.SPHERICAL_DISTANCE_ERROR, "distance error " + worstDistance);
        assertTrue(worstBearing <= Geodesy.SPHERICAL_BEARING_ERROR, "bearing error " + Math.toDegrees(worstBearing) + " degrees");
        assertTrue(worstDestination <= Geodesy.SPHERICAL_DISTANCE_ERROR, "destination error " + worstDestination);
        assertTrue(worstDistance > 0.001, "the bound is not far above what was measured: " + worstDistance);
    }

    @Test
    void theSphericalHelpersAgreeWithTheirDefinitions() {
        double[] mid = new double[2];
        Geodesy.sphericalIntermediate(0, 0, 0, deg(90), 0.5, mid);
        assertEquals(0.0, mid[0], 1e-12);
        assertEquals(deg(45), mid[1], 1e-12);
        Geodesy.sphericalIntermediate(deg(10), deg(20), deg(10), deg(20), 0.3, mid);
        assertEquals(deg(10), mid[0], 1e-12);
        assertEquals(Math.PI / 2 * Geodesy.MEAN_RADIUS, Geodesy.sphericalDistance(0, 0, 0, deg(90)), 1e-6);
        double[] xt = new double[2];
        // the great circle along the equator, a point 1 degree north of it, 30 degrees along
        Geodesy.sphericalCrossTrack(0, 0, 0, deg(90), deg(1), deg(30), xt);
        assertEquals(-deg(1) * Geodesy.MEAN_RADIUS, xt[0], 1e-3 * deg(1) * Geodesy.MEAN_RADIUS, "north of an eastbound leg is on the left");
        assertEquals(deg(30) * Geodesy.MEAN_RADIUS, xt[1], 50.0);
        assertThrows(IllegalArgumentException.class, () -> Geodesy.sphericalDestination(0, 0, 0, 1, new double[1]));
        assertThrows(IllegalArgumentException.class, () -> Geodesy.sphericalIntermediate(0, 0, 1, 1, 0.5, new double[1]));
        assertThrows(IllegalArgumentException.class, () -> Geodesy.sphericalCrossTrack(0, 0, 1, 1, 0, 0, new double[1]));
    }

    // ---------------------------------------------------------------- rhumb lines

    @Test
    void theMeridianArcAndTheIsometricLatitudeAreInvertible() {
        assertEquals(10001965.7293127, Geodesy.meridianDistance(Math.PI / 2), 1e-3);
        assertEquals(0.0, Geodesy.meridianDistance(0), 0);
        assertEquals(110574.3886, Geodesy.meridianDistance(deg(1)), 0.2, "a degree of latitude from the equator");
        for (int i = 0; i < 500; i++) {
            double lat = deg(r(-89.9, 89.9));
            assertEquals(lat, Geodesy.latitudeOfMeridianDistance(Geodesy.meridianDistance(lat)), 1e-14);
            assertEquals(lat, Geodesy.latitudeOfIsometric(Geodesy.isometricLatitude(lat)), 1e-13);
        }
        assertEquals(Math.PI / 2, Geodesy.latitudeOfMeridianDistance(2e7), 0);
        assertEquals(-Math.PI / 2, Geodesy.latitudeOfMeridianDistance(-2e7), 0);
        assertThrows(IllegalArgumentException.class, () -> Geodesy.meridianDistance(2.0));
    }

    @Test
    void aRhumbLineHasAConstantBearingAndTheLengthOfItsPolyline() {
        for (int i = 0; i < 100; i++) {
            double lat = deg(r(-70, 70)), lon = deg(r(-180, 180)), bearing = r(-Math.PI, Math.PI), dist = r(1e4, 5e6);
            double[] end = new double[2], seg = new double[2];
            Geodesy.rhumbDirect(lat, lon, bearing, dist, end);
            if (Math.abs(end[0]) > deg(85)) {
                continue;
            }
            // the polyline of 4000 points: its length is the distance, and every piece has the same bearing
            int n = 4000;
            double length = 0, prevLat = lat, prevLon = lon;
            double[] p = new double[2];
            for (int k = 1; k <= n; k++) {
                Geodesy.rhumbDirect(lat, lon, bearing, dist * k / n, p);
                length += ecefDistance(prevLat, prevLon, p[0], p[1]);
                if (k % 400 == 0) {
                    Geodesy.rhumbInverse(prevLat, prevLon, p[0], p[1], seg);
                    assertEquals(0.0, Geodesy.wrapPi(seg[1] - bearing), 2e-6, "constant bearing");
                }
                prevLat = p[0];
                prevLon = p[1];
            }
            assertEquals(dist, length, dist * 2e-6, "the polyline is as long as the distance");
            Geodesy.rhumbInverse(lat, lon, end[0], end[1], seg);
            assertEquals(dist, seg[0], 1e-4 + dist * 1e-9, "inverse of direct: distance");
            if (Math.abs(Math.cos(bearing)) > 1e-3) {
                assertEquals(0.0, Geodesy.wrapPi(seg[1] - bearing), 1e-9, "inverse of direct: bearing");
            }
        }
        double[] r = new double[2];
        Geodesy.rhumbInverse(deg(50), deg(0), deg(50), deg(10), r);
        assertEquals(Math.PI / 2, r[1], 1e-12, "along a parallel: due east");
        assertEquals(deg(10) * Wgs84.A * Math.cos(deg(50)) / Math.sqrt(1 - Wgs84.E2 * Math.sin(deg(50)) * Math.sin(deg(50))), r[0], 1e-6, "the length of the arc of the parallel");
        Geodesy.rhumbInverse(deg(10), deg(30), deg(40), deg(30), r);
        assertEquals(0.0, r[1], 1e-12);
        assertEquals(Geodesy.meridianDistance(deg(40)) - Geodesy.meridianDistance(deg(10)), r[0], 1e-6, "along a meridian");
        assertThrows(IllegalArgumentException.class, () -> Geodesy.rhumbInverse(0, 0, 0, 0, new double[1]));
        assertThrows(IllegalArgumentException.class, () -> Geodesy.rhumbDirect(0, 0, 0, 1, new double[1]));
        Geodesy.rhumbDirect(deg(80), 0, 0.0, 5e6, r);
        assertEquals(Math.PI / 2, r[0], 0, "a rhumb line north past the pole ends at the pole");
    }

    // ---------------------------------------------------------------- legs

    @Test
    void crossTrackAndAlongTrackOfAPointBuiltAtAKnownOffset() {
        for (int i = 0; i < 300; i++) {
            double latA = deg(r(-70, 70)), lonA = deg(r(-180, 180)), latB = latA + deg(r(-20, 20)), lonB = lonA + deg(r(-30, 30));
            double[] ab = new double[3];
            Geodesy.inverse(latA, lonA, latB, lonB, ab);
            if (ab[0] < 1e5) {
                continue;
            }
            double along = r(-0.3, 1.3) * ab[0], offset = r(-1, 1) * Math.pow(10, r(2, 6.3));
            double[] foot = new double[3], p = new double[3];
            new GeodesicLine(latA, lonA, ab[1]).position(along, foot);
            Geodesy.direct(foot[0], foot[1], foot[2] + Math.PI / 2, offset, p);   // to the right for a positive offset
            double[] r3 = new double[3];
            Geodesy.crossTrack(latA, lonA, latB, lonB, p[0], p[1], r3);
            assertEquals(offset, r3[0], 1e-5 + Math.abs(offset) * 1e-9, "cross-track " + i);
            assertEquals(along, r3[1], 2e-4 + Math.abs(along) * 1e-9, "along-track " + i);
            assertEquals(ab[0], r3[2], 1e-6);
            double leg = Geodesy.distanceToLeg(latA, lonA, latB, lonB, p[0], p[1]);
            double expected = along <= 0 ? Geodesy.distance(latA, lonA, p[0], p[1]) : along >= ab[0] ? Geodesy.distance(latB, lonB, p[0], p[1]) : Math.abs(offset);
            assertEquals(expected, leg, 1e-4 + expected * 1e-9, "distance to the leg " + i);
        }
        assertThrows(IllegalArgumentException.class, () -> Geodesy.crossTrack(0, 0, 0, 0, 1, 1, new double[3]));
        assertThrows(IllegalArgumentException.class, () -> Geodesy.crossTrack(0, 0, 1, 1, 1, 1, new double[2]));
    }

    // ---------------------------------------------------------------- clipping

    @Test
    void clippingAGeodesicToABoxAgreesWithAFineSampling() {
        int checked = 0;
        for (int i = 0; i < 60; i++) {
            double lat1 = deg(r(-50, 50)), lon1 = deg(r(-100, 100));
            double lat2 = lat1 + deg(r(-30, 30)), lon2 = lon1 + deg(r(-60, 60));
            double south = deg(r(-30, 10)), north = south + deg(r(5, 40)), west = deg(r(-60, 20)), east = west + deg(r(5, 60));
            double[] ab = new double[3];
            Geodesy.inverse(lat1, lon1, lat2, lon2, ab);
            double[] got = new double[16];
            int n = Geodesy.clipGeodesic(lat1, lon1, lat2, lon2, south, west, north, east, got);
            GeodesicLine line = new GeodesicLine(lat1, lon1, ab[1]);
            int steps = 200_000;
            boolean prev = false;
            double[] p = new double[3], expected = new double[16];
            int m = 0;
            for (int k = 0; k <= steps; k++) {
                line.position(ab[0] * k / steps, p);
                boolean in = p[0] >= south && p[0] <= north && p[1] >= west && p[1] <= east;
                if (in && !prev) {
                    expected[2 * m] = ab[0] * k / steps;
                }
                if (!in && prev) {
                    expected[2 * m + 1] = ab[0] * (k - 1) / steps;
                    m++;
                }
                prev = in;
            }
            if (prev) {
                expected[2 * m + 1] = ab[0];
                m++;
            }
            assertEquals(m, n, "stretches " + i);
            for (int k = 0; k < 2 * m; k++) {
                assertEquals(expected[k], got[k], ab[0] / steps * 1.5 + 1e-6, "stretch edge " + k + " of " + i);
            }
            checked += m;
        }
        assertTrue(checked > 3, "some paths cross the boxes: " + checked);
    }

    @Test
    void clippingAcrossTheAntimeridianAndACircleToABox() {
        // a leg across the antimeridian in a box that straddles it
        double[] out = new double[8];
        int n = Geodesy.clipGeodesic(0, deg(170), 0, deg(-170), -deg(5), deg(175), deg(5), deg(-175), out);
        assertEquals(1, n);
        double[] ab = new double[3];
        Geodesy.inverse(0, deg(170), 0, deg(-170), ab);
        assertEquals(ab[0] * 0.25, out[0], 2.0, "from 175 east");
        assertEquals(ab[0] * 0.75, out[1], 2.0, "to 175 west");
        // a circle of 500 km around a point, in a box that contains it, in one that cuts it and in one that misses it
        double[] arcs = new double[16];
        assertEquals(1, Geodesy.clipCircle(0.5, 0.5, 5e5, 0.0, 0.0, 1.0, 1.0, arcs));
        assertEquals(0.0, arcs[0], 0);
        assertEquals(2 * Math.PI, arcs[1], 1e-12);
        assertEquals(0, Geodesy.clipCircle(0.5, 0.5, 5e5, 1.2, 1.2, 1.4, 1.4, arcs));
        int cut = Geodesy.clipCircle(0.5, 0.5, 5e5, 0.5, 0.0, 1.0, 1.0, arcs);   // the northern half
        assertEquals(1, cut);
        double[] p = new double[3];
        double mid = 0.5 * (arcs[0] + arcs[1]);
        Geodesy.direct(0.5, 0.5, mid, 5e5, p);
        assertTrue(p[0] > 0.5, "the middle of the arc is north of the box edge");
        Geodesy.direct(0.5, 0.5, arcs[0], 5e5, p);
        assertEquals(0.5, p[0], 1e-9, "the arc ends on the edge of the box");
        Geodesy.direct(0.5, 0.5, arcs[1], 5e5, p);
        assertEquals(0.5, p[0], 1e-9);
        assertThrows(IllegalArgumentException.class, () -> Geodesy.clipCircle(0, 0, -1, 0, 0, 1, 1, new double[4]));
        assertThrows(IllegalArgumentException.class, () -> Geodesy.clipGeodesic(0, 0, 0, 0, 0, 0, 1, 1, new double[4]));
        assertThrows(IllegalArgumentException.class, () -> Geodesy.clipGeodesic(0, 0, 1, 1, 1, 0, 0, 1, new double[4]));
    }

    @Test
    void anglesAreWrappedAsDocumented() {
        assertEquals(Math.PI, Math.abs(Geodesy.wrapPi(3 * Math.PI)), 1e-12);
        assertEquals(0.5, Geodesy.wrapPi(0.5 + 2 * Math.PI), 1e-12);
        assertEquals(1.5 * Math.PI, Geodesy.normalizeBearing(-0.5 * Math.PI), 1e-12);
        assertEquals(0.0, Geodesy.normalizeBearing(2 * Math.PI), 0);
        assertFalse(Geodesy.normalizeBearing(-1e-20) >= 2 * Math.PI);
        assertThrows(IllegalArgumentException.class, () -> Geodesy.inverse(2.0, 0, 0, 0, new double[3]));
        assertThrows(IllegalArgumentException.class, () -> Geodesy.inverse(0, Double.NaN, 0, 0, new double[3]));
        assertThrows(IllegalArgumentException.class, () -> Geodesy.inverse(0, 0, 0, 0, new double[2]));
    }
}
