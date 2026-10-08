package vmath.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The map projections against published coordinates (EPSG guidance note 7-2 and the usual UTM
 * examples), against quantities that no formula of the implementation is shared with (the meridian
 * arc by quadrature, the geodesic distance, conformality by differentiation), for round trips, and
 * for the known distortion of each.
 */
class MapProjectionTest {

    private final SplittableRandom rnd = new SplittableRandom(77);

    private double r(double lo, double hi) {
        return lo + (hi - lo) * rnd.nextDouble();
    }

    private static double deg(double d) {
        return Math.toRadians(d);
    }

    private static double dms(double d, double m, double s) {
        return Math.toRadians(d + m / 60.0 + s / 3600.0);
    }

    private static final double US_FOOT = 1200.0 / 3937.0;

    // ---------------------------------------------------------------- published coordinates

    @Test
    void transverseMercatorOfTheBritishGridAsInEpsgGuidanceNote7() {
        TransverseMercator osgb = new TransverseMercator(Ellipsoid.AIRY_1830, deg(49), deg(-2), 0.9996012717, 400000, -100000);
        double[] en = new double[2];
        osgb.forward(dms(50, 30, 0), dms(0, 30, 0), en);
        assertEquals(577274.99, en[0], 0.01, "easting of EPSG example 9807");
        assertEquals(69740.50, en[1], 0.01, "northing of EPSG example 9807");
        double[] ll = new double[2];
        osgb.inverse(577274.99, 69740.50, ll);
        assertEquals(dms(50, 30, 0), ll[0], dms(0, 0, 0.0005));
        assertEquals(dms(0, 30, 0), ll[1], dms(0, 0, 0.0005));
        osgb.forward(deg(49), deg(-2), en);
        assertEquals(400000.0, en[0], 1e-6, "the true origin has the false easting");
        assertEquals(-100000.0, en[1], 1e-6, "and the false northing");
    }

    @Test
    void lambertConformalConicOfTexasAsInEpsgGuidanceNote7() {
        LambertConformalConic texas = LambertConformalConic.twoParallels(Ellipsoid.CLARKE_1866, dms(28, 23, 0), dms(30, 17, 0), dms(27, 50, 0), deg(-99), 2_000_000 * US_FOOT, 0);
        double[] en = new double[2];
        texas.forward(dms(28, 30, 0), deg(-96), en);
        assertEquals(2963503.91 * US_FOOT, en[0], 0.004, "easting of EPSG example 9802");
        assertEquals(254759.80 * US_FOOT, en[1], 0.004, "northing of EPSG example 9802");
        double[] ll = new double[2];
        texas.inverse(2963503.91 * US_FOOT, 254759.80 * US_FOOT, ll);
        assertEquals(dms(28, 30, 0), ll[0], dms(0, 0, 0.001));
        assertEquals(deg(-96), ll[1], dms(0, 0, 0.001));
    }

    @Test
    void polarStereographicOfTheEpsgExamples() {
        // variant B: latitude of true scale 71 degrees south
        PolarStereographic south = PolarStereographic.ofTrueScaleLatitude(Ellipsoid.WGS84, deg(-71), 0.0, 0, 0);
        double[] en = new double[2];
        south.forward(deg(-75), deg(120), en);
        // the published radius from the pole (1 638 783.24 m); the easting is rho sin(120 degrees) and the northing rho cos(120 degrees) for the south pole
        assertEquals(1638783.24, Math.hypot(en[0], en[1]), 0.05, "EPSG example 9829: the distance from the pole");
        assertEquals(1638783.24 * Math.sin(deg(120)), en[0], 0.05);
        assertEquals(1638783.24 * Math.cos(deg(120)), en[1], 0.05);
        double sphere = 6378137.0 * (1 + Math.sin(deg(71))) * Math.tan(deg(7.5));
        assertEquals(sphere, Math.hypot(en[0], en[1]), 0.01 * sphere, "and a sphere gives nearly the same radius");
        // variant A: the UPS north grid (scale factor 0.994 at the pole)
        PolarStereographic ups = PolarStereographic.ups(true);
        ups.forward(deg(73), deg(44), en);
        assertEquals(3320416.75, en[0], 0.05, "EPSG example 9810 easting");
        assertEquals(632668.43, en[1], 0.05, "EPSG example 9810 northing");
        double[] ll = new double[2];
        ups.inverse(3320416.75, 632668.43, ll);
        assertEquals(deg(73), ll[0], dms(0, 0, 0.001));
        assertEquals(deg(44), ll[1], dms(0, 0, 0.001));
        ups.forward(Math.PI / 2, 0, en);
        assertEquals(2_000_000.0, en[0], 1e-9, "the pole is the false origin");
        assertEquals(2_000_000.0, en[1], 1e-9);
        assertEquals(0.994, ups.scale(Math.PI / 2, 0), 1e-12, "the scale factor at the pole");
    }

    /** The series of Redfearn (Snyder 8-9 to 8-13): accurate to a millimetre within 3 or 4 degrees of the central meridian, and independent of the Krueger series. */
    private static void redfearn(double lat, double dLon, double[] en) {
        double e2 = Ellipsoid.WGS84.e2(), ep2 = e2 / (1 - e2), a = Ellipsoid.WGS84.a(), k0 = 0.9996;
        double s = Math.sin(lat), c = Math.cos(lat), t = Math.tan(lat) * Math.tan(lat), cc = ep2 * c * c, aa = dLon * c;
        double nn = a / Math.sqrt(1 - e2 * s * s);
        double x = k0 * nn * (aa + (1 - t + cc) * Math.pow(aa, 3) / 6 + (5 - 18 * t + t * t + 72 * cc - 58 * ep2) * Math.pow(aa, 5) / 120);
        double y = k0 * (Geodesy.meridianDistance(lat) + nn * Math.tan(lat) * (aa * aa / 2 + (5 - t + 9 * cc + 4 * cc * cc) * Math.pow(aa, 4) / 24
                + (61 - 58 * t + t * t + 600 * cc - 330 * ep2) * Math.pow(aa, 6) / 720));
        en[0] = 500000.0 + x;
        en[1] = y;
    }

    @Test
    void utmAgreesWithTheClassicalSeriesWithinThreeDegreesAndWithKnownPlaces() {
        double[] k = new double[2], red = new double[2];
        for (int i = 0; i < 2000; i++) {
            double lat = deg(r(-75, 80)), d = deg(r(-3, 3));
            Utm.projection(33, true).forward(lat, deg(15) + d, k);
            redfearn(lat, d, red);
            assertEquals(red[0], k[0], 0.002, "easting at " + Math.toDegrees(lat) + ", " + Math.toDegrees(d));
            assertEquals(red[1], k[1], 0.002, "northing");
        }
        Utm.Coordinate eiffel = Utm.fromGeodetic(deg(48.8584), deg(2.2945));
        assertEquals(31, eiffel.zone());
        assertTrue(eiffel.north());
        assertEquals(448252.0, eiffel.easting(), 30.0, "a published position of the tower, to the accuracy of the remembered figure");
        assertEquals(5411932.0, eiffel.northing(), 30.0);
        Utm.Coordinate empire = Utm.fromGeodetic(deg(40.7484), deg(-73.9857));
        assertEquals(18, empire.zone());
        assertEquals(585628.0, empire.easting(), 30.0);
        assertEquals(4511322.0, empire.northing(), 30.0);
        // on the central meridian of zone 33 the northing is 0.9996 times the meridian arc: 4 982 950.40 m at 45 degrees
        Utm.Coordinate cm = Utm.fromGeodetic(deg(45), deg(15));
        assertEquals(33, cm.zone());
        assertEquals(500000.0, cm.easting(), 1e-6);
        assertEquals(4982950.40, cm.northing(), 0.01);
        Utm.Coordinate south = Utm.fromGeodetic(deg(-33.8568), deg(151.2153));   // the Sydney Opera House
        assertEquals(56, south.zone());
        assertFalse(south.north());
        assertEquals(334.0e3, south.easting(), 1.0e3);
        assertEquals(6.2523e6, south.northing(), 2.0e3);
    }

    @Test
    void theNorthingOnTheCentralMeridianIsTheMeridianArcTimesTheScaleFactor() {
        for (int i = 0; i < 100; i++) {
            double lat = deg(r(-80, 84));
            double[] xy = new double[2];
            Utm.projection(31, lat >= 0).forward(lat, Utm.centralMeridian(31), xy);
            double arc = Geodesy.meridianDistance(lat);
            assertEquals(500000.0, xy[0], 1e-8);
            assertEquals((lat >= 0 ? 0 : 10_000_000.0) + 0.9996 * arc, xy[1], 1e-6, "k0 times the arc by quadrature at " + Math.toDegrees(lat));
        }
        double[] xy = new double[2];
        Utm.projection(31, true).forward(Math.PI / 2, 0, xy);
        assertEquals(0.9996 * 10001965.7293127, xy[1], 1e-5, "the pole");
    }

    // ---------------------------------------------------------------- geometry of each projection

    private static void assertConformalAt(MapProjection p, double lat, double lon, String what) {
        double[] s = new double[2];
        MapProjections.numericScales(p, lat, lon, s);
        assertEquals(s[0], s[1], 1e-8 * s[0], what + ": equal scales along the meridian and the parallel at " + Math.toDegrees(lat) + ", " + Math.toDegrees(lon));
    }

    @Test
    void theConformalProjectionsPreserveAnglesAndTheirClosedFormsMatchTheDerivatives() {
        TransverseMercator tm = Utm.projection(33, true);
        LambertConformalConic lcc = LambertConformalConic.twoParallels(Ellipsoid.WGS84, deg(33), deg(45), deg(39), deg(-96), 0, 0);
        PolarStereographic ps = PolarStereographic.ofTrueScaleLatitude(Ellipsoid.WGS84, deg(70), deg(-45), 0, 0);
        for (int i = 0; i < 200; i++) {
            assertConformalAt(tm, deg(r(-70, 80)), deg(r(9, 21)), "transverse Mercator");
            assertConformalAt(lcc, deg(r(20, 70)), deg(r(-130, -60)), "Lambert");
            assertConformalAt(ps, deg(r(50, 88)), deg(r(-180, 180)), "polar stereographic");
        }
        double[] a = new double[2], b = new double[2];
        for (int i = 0; i < 100; i++) {
            double lat = deg(r(30, 70)), lon = deg(r(-120, -70));
            lcc.scales(lat, lon, a);
            MapProjections.numericScales(lcc, lat, lon, b);
            assertEquals(b[0], a[0], 1e-8, "Lambert scale, closed form against derivative");
            assertEquals(MapProjections.numericConvergence(lcc, lat, lon), lcc.convergence(lat, lon), 1e-8, "Lambert convergence");
            double plat = deg(r(55, 88)), plon = deg(r(-180, 180));
            ps.scales(plat, plon, a);
            MapProjections.numericScales(ps, plat, plon, b);
            assertEquals(b[0], a[0], 1e-8, "polar scale");
            assertEquals(MapProjections.numericConvergence(ps, plat, plon), ps.convergence(plat, plon), 1e-8, "polar convergence");
        }
    }

    @Test
    void theScaleIsOneWhereTheDefinitionSaysItIs() {
        LambertConformalConic lcc = LambertConformalConic.twoParallels(Ellipsoid.WGS84, deg(33), deg(45), deg(39), deg(-96), 0, 0);
        assertEquals(1.0, lcc.scale(deg(33), deg(-90)), 1e-12);
        assertEquals(1.0, lcc.scale(deg(45), deg(-100)), 1e-12);
        assertTrue(lcc.scale(deg(39), deg(-96)) < 1.0, "between the standard parallels the scale is below 1");
        assertTrue(lcc.scale(deg(55), deg(-96)) > 1.0, "beyond them above");
        PolarStereographic ps = PolarStereographic.ofTrueScaleLatitude(Ellipsoid.WGS84, deg(-71), 0, 0, 0);
        assertEquals(1.0, ps.scale(deg(-71), deg(10)), 1e-12);
        assertTrue(ps.scale(deg(-85), 0) < 1.0);
        TransverseMercator utm = Utm.projection(33, true);
        assertEquals(0.9996, utm.scale(deg(40), deg(15)), 1e-9, "on the central meridian");
        assertEquals(0.0, utm.convergence(deg(40), deg(15)), 1e-9);
        // at the edge of the zone, 3 degrees out at 45 degrees north: k = k0 (1 + A^2 (1 + e'^2 cos^2) / 2), A = d_lambda cos(lat)
        double a = deg(3) * Math.cos(deg(45)), ep2 = Ellipsoid.WGS84.e2() / (1 - Ellipsoid.WGS84.e2());
        assertEquals(0.9996 * (1 + a * a * (1 + ep2 * Math.cos(deg(45)) * Math.cos(deg(45))) / 2), utm.scale(deg(45), deg(18)), 5e-7);
        assertEquals(deg(3) * Math.sin(deg(45)), utm.convergence(deg(45), deg(18)), 2e-4, "convergence is about d_lambda sin(lat), positive east of the meridian");
        assertTrue(utm.convergence(deg(45), deg(12)) < 0, "and negative to the west");
        assertTrue(Utm.projection(33, false).convergence(deg(-45), deg(18)) < 0, "the sign follows the hemisphere");
    }

    @Test
    void everyProjectionRoundTrips() {
        double[] xy = new double[2], ll = new double[2];
        TransverseMercator tm = Utm.projection(33, true);
        for (int i = 0; i < 2000; i++) {
            double lat = deg(r(-80, 84)), lon = deg(r(15 - 20, 15 + 20));
            tm.forward(lat, lon, xy);
            tm.inverse(xy[0], xy[1], ll);
            assertEquals(lat, ll[0], 1e-13);
            assertEquals(lon, ll[1], 1e-13);
        }
        TransverseMercator osgb = new TransverseMercator(Ellipsoid.AIRY_1830, deg(49), deg(-2), 0.9996012717, 400000, -100000);
        for (int i = 0; i < 500; i++) {
            double lat = deg(r(49, 61)), lon = deg(r(-8, 2));
            osgb.forward(lat, lon, xy);
            osgb.inverse(xy[0], xy[1], ll);
            assertEquals(lat, ll[0], 1e-13);
            assertEquals(lon, ll[1], 1e-13);
        }
        LambertConformalConic lcc = LambertConformalConic.twoParallels(Ellipsoid.WGS84, deg(33), deg(45), deg(39), deg(-96), 123.0, -456.0);
        LambertConformalConic south = LambertConformalConic.twoParallels(Ellipsoid.WGS84, deg(-45), deg(-33), deg(-39), deg(140), 0, 0);
        LambertConformalConic one = LambertConformalConic.oneParallel(Ellipsoid.WGS84, deg(46), deg(2), 0.99, 1e6, 2e6);
        for (int i = 0; i < 1000; i++) {
            for (LambertConformalConic p : new LambertConformalConic[] {lcc, south, one}) {
                double lat = deg(r(p.coneConstant() > 0 ? 5 : -80, p.coneConstant() > 0 ? 80 : -5)), lon = p.coneConstant() > 0 && p == one ? deg(r(-30, 40)) : deg(r(-170, 170));
                p.forward(lat, lon, xy);
                p.inverse(xy[0], xy[1], ll);
                assertEquals(lat, ll[0], 1e-12);
                assertEquals(Geodesy.wrapPi(lon), ll[1], 1e-12);
            }
        }
        for (PolarStereographic p : new PolarStereographic[] {PolarStereographic.ups(true), PolarStereographic.ups(false), PolarStereographic.ofTrueScaleLatitude(Ellipsoid.WGS84, deg(70), deg(-45), 0, 0),
                PolarStereographic.ofTrueScaleLatitude(Ellipsoid.WGS84, deg(-71), 0, 5, 7)}) {
            boolean north = p.scale(deg(80), 0) > 0 && p.name().contains("north");
            for (int i = 0; i < 500; i++) {
                double lat = deg(r(north ? -20 : -89.9, north ? 89.9 : 20)), lon = deg(r(-180, 180));
                p.forward(lat, lon, xy);
                p.inverse(xy[0], xy[1], ll);
                assertEquals(lat, ll[0], 1e-12);
                assertEquals(lon, ll[1], 1e-12);
            }
        }
        AzimuthalEquidistant ae = new AzimuthalEquidistant(deg(59.65), deg(17.92));
        for (int i = 0; i < 500; i++) {
            double lat = deg(r(-80, 80)), lon = deg(r(-180, 180));
            if (Geodesy.distance(deg(59.65), deg(17.92), lat, lon) > 19_900_000) {
                continue;
            }
            ae.forward(lat, lon, xy);
            ae.inverse(xy[0], xy[1], ll);
            assertEquals(lat, ll[0], 1e-11);
            assertEquals(Geodesy.wrapPi(lon), ll[1], 1e-11);
        }
        for (int i = 0; i < 200; i++) {
            double lat = deg(r(-85, 85)), lon = deg(r(-180, 180));
            WebMercatorProjection.INSTANCE.forward(lat, lon, xy);
            WebMercatorProjection.INSTANCE.inverse(xy[0], xy[1], ll);
            assertEquals(lat, ll[0], 1e-12);
            assertEquals(lon, ll[1], 1e-12);
        }
    }

    @Test
    void theAzimuthalEquidistantMapGivesTrueRangeAndBearingAndRangeRingsAreCircles() {
        double lat0 = deg(59.65), lon0 = deg(17.92);
        AzimuthalEquidistant ae = new AzimuthalEquidistant(lat0, lon0);
        double[] xy = new double[2];
        ae.forward(lat0, lon0, xy);
        assertEquals(0.0, xy[0], 1e-9);
        assertEquals(0.0, xy[1], 1e-9);
        for (int i = 0; i < 200; i++) {
            double lat = deg(r(-80, 80)), lon = deg(r(-180, 180));
            double[] g = new double[3];
            Geodesy.inverse(lat0, lon0, lat, lon, g);
            if (g[0] > 19_900_000) {
                continue;
            }
            ae.forward(lat, lon, xy);
            assertEquals(g[0], Math.hypot(xy[0], xy[1]), 1e-6, "the distance from the origin is the geodesic distance");
            assertEquals(0.0, Geodesy.wrapPi(Math.atan2(xy[0], xy[1]) - g[1]), 1e-9, "the bearing from the origin is the azimuth");
        }
        for (double radius : new double[] {1e4, 3.7e5, 2.0e6, 9.0e6}) {
            for (int k = 0; k < 60; k++) {
                double[] p = new double[3];
                Geodesy.direct(lat0, lon0, deg(6 * k), radius, p);
                ae.forward(p[0], p[1], xy);
                assertEquals(radius, Math.hypot(xy[0], xy[1]), 1e-6, "a range ring of " + radius + " m is a circle of that radius");
            }
        }
        double[] s = new double[2];
        ae.scales(lat0 + 1e-6, lon0, s);
        assertEquals(1.0, s[0], 1e-5, "true scale along a meridian through the centre, near it");
        assertTrue(ae.scale(deg(40), deg(40)) > 1.0, "the scale grows away from the centre");
        assertThrows(IllegalArgumentException.class, () -> new AzimuthalEquidistant(2.0, 0));
    }

    @Test
    void webMercatorIsTheTileProjectionWithTheScaleOfTheEllipsoid() {
        double[] xy = new double[2];
        WebMercatorProjection.INSTANCE.forward(deg(59.33), deg(18.07), xy);
        assertEquals(WebMercator.x(deg(18.07)), xy[0], 0);
        assertEquals(WebMercator.y(deg(59.33)), xy[1], 0);
        assertFalse(WebMercatorProjection.INSTANCE.isConformal());
        double[] a = new double[2], b = new double[2];
        for (int i = 0; i < 100; i++) {
            double lat = deg(r(-80, 80)), lon = deg(r(-180, 180));
            WebMercatorProjection.INSTANCE.scales(lat, lon, a);
            MapProjections.numericScales(WebMercatorProjection.INSTANCE, lat, lon, b);
            assertEquals(b[0], a[0], 1e-8 * b[0]);
            assertEquals(b[1], a[1], 1e-8 * b[1]);
            assertEquals(0.0, WebMercatorProjection.INSTANCE.convergence(lat, lon), 0);
            assertTrue(Math.abs(a[0] / a[1] - 1) < 0.0068, "the two scales differ by less than 0.67 percent (N over M)");
        }
        double[] ll = new double[2];
        WebMercatorProjection.INSTANCE.inverse(WebMercator.HALF_WORLD * 1.5, 0, ll);
        assertEquals(deg(-90), ll[1], 1e-12, "x is continued periodically: 270 degrees east is 90 degrees west");
    }

    // ---------------------------------------------------------------- the grid

    @Test
    void zonesBandsAndTheirExceptions() {
        assertEquals(31, Utm.zoneOf(deg(48.8), deg(2.3)));
        assertEquals(32, Utm.zoneOf(deg(60), deg(5)), "Norway");
        assertEquals(31, Utm.zoneOf(deg(50), deg(5)), "outside the Norwegian exception");
        assertEquals(31, Utm.zoneOf(deg(78), deg(5)), "Svalbard");
        assertEquals(33, Utm.zoneOf(deg(78), deg(15)));
        assertEquals(35, Utm.zoneOf(deg(78), deg(25)));
        assertEquals(37, Utm.zoneOf(deg(78), deg(38)));
        assertEquals(1, Utm.zoneOf(deg(0), deg(-179.9)));
        assertEquals(60, Utm.zoneOf(deg(0), deg(179.9)));
        assertEquals(1, Utm.zoneOf(deg(0), deg(180)), "180 degrees is -180");
        assertEquals('C', Utm.bandOf(deg(-80)));
        assertEquals('N', Utm.bandOf(deg(0)));
        assertEquals('X', Utm.bandOf(deg(84)));
        assertEquals('X', Utm.bandOf(deg(75)));
        assertEquals('S', Utm.bandOf(deg(33)));
        assertThrows(IllegalArgumentException.class, () -> Utm.bandOf(deg(85)));
        assertThrows(IllegalArgumentException.class, () -> Utm.bandOf(deg(-81)));
        assertEquals(deg(15), Utm.centralMeridian(33), 1e-15);
        assertThrows(IllegalArgumentException.class, () -> Utm.centralMeridian(0));
        assertThrows(IllegalArgumentException.class, () -> new Utm.Coordinate(61, true, 0, 0));
        double[] ll = new double[2];
        Utm.Coordinate c = Utm.fromGeodetic(deg(-12.5), deg(100.3));
        Utm.toGeodetic(c, ll);
        assertEquals(deg(-12.5), ll[0], 1e-12);
        assertEquals(deg(100.3), ll[1], 1e-12);
    }

    @Test
    void militaryGridReferencesFormatAndParse() {
        String eiffel = Mgrs.format(deg(48.8584), deg(2.2945), 5);
        assertTrue(eiffel.startsWith("31UDQ482"), eiffel);
        assertEquals("31UDQ", Mgrs.format(deg(48.8584), deg(2.2945), 0));
        assertEquals("31UDQ41", Mgrs.format(deg(48.8584), deg(2.2945), 1));
        assertTrue(Mgrs.format(deg(48.8584), deg(2.2945), 5).length() == 15);
        // the column letters of the three sets and the row offset of the even zones
        assertEquals('D', Mgrs.format(deg(48.8584), deg(2.2945), 0).charAt(3));   // zone 31: set 1
        double[] ll = new double[2];
        for (int i = 0; i < 2000; i++) {
            double lat = deg(r(-79.9, 83.9)), lon = deg(r(-180, 180));
            String ref = Mgrs.format(lat, lon, 5);
            Mgrs.parse(ref, ll);
            double d = Geodesy.distance(lat, lon, ll[0], ll[1]);
            assertTrue(d < 1.5, ref + ": the corner of a one-metre square is within 1.5 m of the point: " + d);
            // the point is inside its square
            String coarse = Mgrs.format(lat, lon, 2);
            assertEquals(1000.0, Mgrs.squareSize(coarse), 0);
            Mgrs.parseCentre(coarse, ll);
            assertTrue(Geodesy.distance(lat, lon, ll[0], ll[1]) < 1000.0 * Math.sqrt(0.5) * 1.001, coarse + ": the point is inside the square of 1 km");
        }
        Mgrs.parse("31U DQ 48252 11932", ll);
        assertEquals(deg(48.8584), ll[0], deg(4e-4), "the published reference of the tower, to the accuracy of the remembered figure (about 25 m)");
        assertEquals(deg(2.2945), ll[1], deg(4e-4));
        assertEquals(100.0, Mgrs.squareSize("31UDQ482119"), 0);
        assertEquals(10.0, Mgrs.squareSize("31UDQ48251193"), 0);
        assertEquals(1.0, Mgrs.squareSize("31UDQ4825211932"), 0);
        assertEquals(100000.0, Mgrs.squareSize("31UDQ"), 0);
        assertThrows(IllegalArgumentException.class, () -> Mgrs.parse("31UDQ123", new double[2]), "an odd number of digits");
        assertThrows(IllegalArgumentException.class, () -> Mgrs.parse("31IDQ1212", new double[2]), "I is not a band");
        assertThrows(IllegalArgumentException.class, () -> Mgrs.parse("31UZQ1212", new double[2]), "Z is not a column of zone 31");
        assertThrows(IllegalArgumentException.class, () -> Mgrs.parse("hello", new double[2]));
        assertThrows(IllegalArgumentException.class, () -> Mgrs.parse("61UDQ1212", new double[2]));
        assertThrows(IllegalArgumentException.class, () -> Mgrs.format(deg(85), 0, 5), "the polar caps are not covered");
        assertThrows(IllegalArgumentException.class, () -> Mgrs.format(0, 0, 6));
    }

    @Test
    void ellipsoidsAndDomainsAreChecked() {
        assertEquals(6356752.314245, Ellipsoid.WGS84.b(), 1e-5);
        assertEquals(0.00669437999014, Ellipsoid.WGS84.e2(), 1e-14);
        assertEquals(Ellipsoid.WGS84.f() / (2 - Ellipsoid.WGS84.f()), Ellipsoid.WGS84.n(), 0);
        assertThrows(IllegalArgumentException.class, () -> new Ellipsoid(-1, 0.1));
        assertThrows(IllegalArgumentException.class, () -> new Ellipsoid(1, 1.0));
        assertThrows(IllegalArgumentException.class, () -> Ellipsoid.ofInverseFlattening(1, 0.5));
        TransverseMercator tm = Utm.projection(33, true);
        assertThrows(IllegalArgumentException.class, () -> tm.forward(2.0, 0, new double[2]));
        assertThrows(IllegalArgumentException.class, () -> tm.forward(0, Double.NaN, new double[2]));
        assertThrows(IllegalArgumentException.class, () -> tm.forward(0, 0, new double[1]));
        assertThrows(IllegalArgumentException.class, () -> new TransverseMercator(Ellipsoid.WGS84, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> PolarStereographic.ups(true).forward(-Math.PI / 2, 0, new double[2]));
        assertThrows(IllegalArgumentException.class, () -> PolarStereographic.ofTrueScaleLatitude(Ellipsoid.WGS84, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> PolarStereographic.ofScaleFactor(Ellipsoid.WGS84, true, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> LambertConformalConic.twoParallels(Ellipsoid.WGS84, deg(-30), deg(30), 0, 0, 0, 0), "parallels symmetric about the equator");
        assertThrows(IllegalArgumentException.class, () -> LambertConformalConic.oneParallel(Ellipsoid.WGS84, 0, 0, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> LambertConformalConic.twoParallels(Ellipsoid.WGS84, deg(30), deg(45), deg(40), 0, 0, 0).forward(-Math.PI / 2, 0, new double[2]));
    }
}
