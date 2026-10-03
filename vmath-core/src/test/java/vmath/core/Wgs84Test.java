package vmath.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Wgs84Test {

    final Rnd rnd = Rnd.create();

    private static void assertVec(Vec3d expected, Vec3d actual, double eps, String what) {
        assertTrue(expected.approxEquals(actual, eps), what + ": expected " + expected + " but was " + actual);
    }

    @Test
    void theConstantsAreTheDefiningAndDerivedParametersOfWgs84() {
        assertEquals(6378137.0, Wgs84.A);
        assertEquals(6356752.314245179, Wgs84.B, 1e-6, "the polar radius");
        assertEquals(0.00669437999014, Wgs84.E2, 1e-14, "the first eccentricity squared");
        assertEquals(0.00673949674228, Wgs84.EP2, 1e-14, "the second eccentricity squared");
        assertEquals(1.0 / 298.257223563, Wgs84.FLATTENING, 0.0);
    }

    @Test
    void knownPointsOnTheAxes() {
        assertVec(new Vec3d(Wgs84.A, 0, 0), Wgs84.toEcef(0, 0, 0), 1e-6, "equator, prime meridian");
        assertVec(new Vec3d(0, Wgs84.A, 0), Wgs84.toEcef(0, Math.PI / 2, 0), 1e-6, "equator, 90 degrees east");
        assertVec(new Vec3d(0, 0, Wgs84.B), Wgs84.toEcef(Math.PI / 2, 0, 0), 1e-6, "north pole");
        assertVec(new Vec3d(0, 0, -Wgs84.B), Wgs84.toEcef(-Math.PI / 2, 0.3, 0), 1e-6, "south pole");
        assertVec(new Vec3d(Wgs84.A + 1000.0, 0, 0), Geodetic.ofDegrees(0, 0, 1000).toEcef(), 1e-6, "height");
    }

    @Test
    void toEcefMatchesTheClosedFormInTermsOfTheSemiAxes() {
        for (int i = 0; i < 2000; i++) {
            double lat = rnd.range(-Math.PI / 2, Math.PI / 2), lon = rnd.range(-Math.PI, Math.PI), h = rnd.range(-1e4, 1e6);
            double c = Math.cos(lat), s = Math.sin(lat);
            double den = Math.sqrt(Wgs84.A * Wgs84.A * c * c + Wgs84.B * Wgs84.B * s * s);
            Vec3d surface = new Vec3d(Wgs84.A * Wgs84.A * c * Math.cos(lon) / den, Wgs84.A * Wgs84.A * c * Math.sin(lon) / den, Wgs84.B * Wgs84.B * s / den);
            Vec3d expected = surface.add(Wgs84.up(lat, lon).mul(h));
            assertVec(expected, Wgs84.toEcef(lat, lon, h), 1e-6, "lat " + lat + " lon " + lon + " h " + h);
        }
    }

    @Test
    void toGeodeticInvertsToEcefOverTheWholeEarthAndBeyond() {
        for (int i = 0; i < 5000; i++) {
            double lat = rnd.range(-Math.PI / 2, Math.PI / 2), lon = rnd.range(-Math.PI, Math.PI);
            double h = i % 5 == 0 ? rnd.range(1e6, 4e7) : i % 7 == 0 ? rnd.range(-6e6, -1e3) : rnd.range(-1e4, 1e5);
            Geodetic back = Wgs84.toGeodetic(Wgs84.toEcef(lat, lon, h));
            assertEquals(lat, back.latitude(), 1e-12, "latitude, lat " + lat + " h " + h);
            assertEquals(lon, back.longitude(), 1e-12, "longitude");
            assertEquals(h, back.height(), 1e-6, "height, lat " + lat + " h " + h);
        }
    }

    @Test
    void nearThePolesAndOnTheAxis() {
        for (double lat : new double[] {Math.PI / 2 - 1e-9, Math.PI / 2 - 1e-6, -Math.PI / 2 + 1e-9, 1e-12, -1e-12}) {
            Geodetic back = Wgs84.toGeodetic(Wgs84.toEcef(lat, 1.0, 123.0));
            assertEquals(lat, back.latitude(), 1e-12, "latitude " + lat);
            assertEquals(123.0, back.height(), 1e-6, "height at latitude " + lat);
        }
        Geodetic north = Wgs84.toGeodetic(new Vec3d(0, 0, Wgs84.B + 100.0));
        assertEquals(Math.PI / 2, north.latitude(), 0.0);
        assertEquals(100.0, north.height(), 1e-6);
        assertEquals(0.0, north.longitude(), 0.0, "no longitude on the axis");
        Geodetic south = Wgs84.toGeodetic(new Vec3d(0, 0, -Wgs84.B - 5.0));
        assertEquals(-Math.PI / 2, south.latitude(), 0.0);
        assertEquals(5.0, south.height(), 1e-6);
        Geodetic centre = Wgs84.toGeodetic(Vec3d.ZERO);
        assertEquals(-Wgs84.B, centre.height(), 1e-6, "the centre of the Earth is B below the surface at the pole");
        assertEquals(Math.PI, Wgs84.toGeodetic(new Vec3d(-Wgs84.A, 0, 0)).longitude(), 0.0, "longitude of 180 degrees is +pi");
        assertEquals(0.0, Wgs84.toGeodetic(new Vec3d(-Wgs84.A, 0, 0)).latitude(), 1e-15);
    }

    @Test
    void theRadiiOfCurvature() {
        assertEquals(Wgs84.A, Wgs84.primeVerticalRadius(0.0), 1e-9);
        assertEquals(Wgs84.A * (1.0 - Wgs84.E2), Wgs84.meridionalRadius(0.0), 1e-9);
        double polar = Wgs84.A * Wgs84.A / Wgs84.B;
        assertEquals(polar, Wgs84.primeVerticalRadius(Math.PI / 2), 1e-6, "both radii agree at the pole");
        assertEquals(polar, Wgs84.meridionalRadius(Math.PI / 2), 1e-6);
        // a short step north changes the latitude by distance / M
        double lat = 0.8, step = 100.0;
        Geodetic a = new Geodetic(lat, 0.2, 0.0);
        Geodetic b = Wgs84.toGeodetic(Wgs84.enuToEcef(a, new Vec3d(0, step, 0)));
        assertEquals(step / Wgs84.meridionalRadius(lat), b.latitude() - lat, 1e-9);
    }

    @Test
    void theLocalFrameIsRightHandedAndPointsEastNorthUp() {
        for (int i = 0; i < 1000; i++) {
            double lat = rnd.range(-1.5, 1.5), lon = rnd.range(-Math.PI, Math.PI);
            Vec3d e = Wgs84.east(lon), n = Wgs84.north(lat, lon), u = Wgs84.up(lat, lon);
            assertEquals(1.0, e.length(), 1e-14);
            assertEquals(1.0, n.length(), 1e-14);
            assertEquals(1.0, u.length(), 1e-14);
            assertEquals(0.0, e.dot(n), 1e-14);
            assertEquals(0.0, e.dot(u), 1e-14);
            assertEquals(0.0, n.dot(u), 1e-14);
            assertVec(u, e.cross(n), 1e-14, "east x north is up");
            Quatd q = Wgs84.enuToEcefRotation(lat, lon);
            assertVec(e, q.transform(new Vec3d(1, 0, 0)), 1e-12, "x of the frame is east");
            assertVec(n, q.transform(new Vec3d(0, 1, 0)), 1e-12, "y is north");
            assertVec(u, q.transform(new Vec3d(0, 0, 1)), 1e-12, "z is up");
            // the ellipsoid normal: going up by 1 m raises the height by 1 m and changes nothing else
            Geodetic g = new Geodetic(lat, lon, 50.0);
            Geodetic raised = Wgs84.toGeodetic(Wgs84.toEcef(g).add(u));
            assertEquals(51.0, raised.height(), 1e-7);
            assertEquals(lat, raised.latitude(), 1e-12);
        }
        assertVec(new Vec3d(0, 1, 0), Wgs84.east(0.0), 1e-15, "east at the prime meridian");
        assertVec(new Vec3d(0, 0, 1), Wgs84.north(0.0, 0.0), 1e-15, "north on the equator");
        assertVec(new Vec3d(1, 0, 0), Wgs84.up(0.0, 0.0), 1e-15, "up at the origin of the longitudes");
    }

    @Test
    void enuConversionsAreInverseAndAgreeWithTheFrameTransform() {
        for (int i = 0; i < 1000; i++) {
            Geodetic origin = new Geodetic(rnd.range(-1.5, 1.5), rnd.range(-Math.PI, Math.PI), rnd.range(-100, 5000));
            Vec3d enu = new Vec3d(rnd.range(-5000, 5000), rnd.range(-5000, 5000), rnd.range(-500, 500));
            Vec3d ecef = Wgs84.enuToEcef(origin, enu);
            assertVec(enu, Wgs84.ecefToEnu(origin, ecef), 1e-7, "round trip");
            assertVec(ecef, Wgs84.enuFrame(origin).transformPosition(enu), 1e-6, "the frame transform");
            Vec3d direction = new Vec3d(rnd.range(-1, 1), rnd.range(-1, 1), rnd.range(-1, 1));
            Vec3d local = Wgs84.ecefToEnuDirection(origin.latitude(), origin.longitude(), direction);
            assertEquals(direction.length(), local.length(), 1e-12, "a rotation keeps the length");
            assertVec(direction, Wgs84.enuFrame(origin).transformDirection(local), 1e-12, "and is the inverse of the frame rotation");
        }
        Geodetic origin = Geodetic.ofDegrees(59.33, 18.07, 28.0);
        Vec3d north = Wgs84.ecefToEnu(origin, Wgs84.toEcef(Geodetic.ofDegrees(59.34, 18.07, 28.0)));
        assertTrue(north.y() > 1000.0 && north.y() < 1200.0, "0.01 degrees of latitude is about 1.1 km: " + north);
        assertTrue(Math.abs(north.x()) < 1e-6 && Math.abs(north.z()) < 1.0, "straight north, and the curvature drops it by a metre at most: " + north);
    }

    @Test
    void geodeticValuesConvertAndCompare() {
        Geodetic g = Geodetic.ofDegrees(59.33, 18.07, 28.0);
        assertEquals(59.33, g.latitudeDegrees(), 1e-12);
        assertEquals(18.07, g.longitudeDegrees(), 1e-12);
        assertEquals(Math.toRadians(59.33), g.latitude());
        assertTrue(g.approxEquals(Wgs84.toGeodetic(g.toEcef()), 1e-9));
        assertTrue(!g.approxEquals(new Geodetic(g.latitude(), g.longitude(), 30.0), 1.0));
        assertTrue(g.isFinite());
        assertTrue(!new Geodetic(Double.NaN, 0, 0).isFinite());
        assertTrue(!new Geodetic(0, Double.POSITIVE_INFINITY, 0).isFinite());
    }
}
