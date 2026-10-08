package vmath.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** {@link Units} and {@link GeoFormat}. */
class GeoFormatTest {

    // ---------------------------------------------------------------- units

    @Test
    void theConstantsAreTheDefinedRatios() {
        assertEquals(1852.0, Units.METERS_PER_NAUTICAL_MILE);
        assertEquals(0.3048, Units.METERS_PER_FOOT);
        assertEquals(5280 * 0.3048, Units.METERS_PER_STATUTE_MILE, 1e-9);
        assertEquals(1852.0 / 3600.0, Units.METERS_PER_SECOND_PER_KNOT);
        assertEquals(0.3048 / 60.0, Units.METERS_PER_SECOND_PER_FOOT_PER_MINUTE);
        assertEquals(0.44704, Units.METERS_PER_SECOND_PER_MILE_PER_HOUR, 1e-12);
        assertEquals(30.48, Units.METERS_PER_FLIGHT_LEVEL, 1e-12);
    }

    @Test
    void conversionsKnownByHeartAndTheirInverses() {
        assertEquals(1852.0, Units.nauticalMilesToMeters(1.0));
        assertEquals(10_000.0, Units.metersToFeet(3048.0), 1e-9);
        assertEquals(1.0, Units.metersPerSecondToKnots(1852.0 / 3600.0), 1e-12);
        assertEquals(500.0, Units.metersPerSecondToFeetPerMinute(2.54), 1e-9, "2.54 m/s is 500 fpm");
        assertEquals(100.0, Units.metersToFlightLevel(10_000 * 0.3048), 1e-9);
        assertEquals(51.4444, Units.knotsToMetersPerSecond(100.0), 1e-4);
        Random rnd = new Random(1);
        for (int i = 0; i < 200; i++) {
            double x = (rnd.nextDouble() - 0.5) * 1e6;
            assertEquals(x, Units.metersToFeet(Units.feetToMeters(x)), 1e-9 * Math.abs(x) + 1e-12);
            assertEquals(x, Units.metersToNauticalMiles(Units.nauticalMilesToMeters(x)), 1e-9 * Math.abs(x) + 1e-12);
            for (Units.Length l : Units.Length.values()) {
                assertEquals(x, l.fromMeters(l.toMeters(x)), 1e-9 * Math.abs(x) + 1e-12);
            }
            for (Units.Speed s : Units.Speed.values()) {
                assertEquals(x, s.fromMetersPerSecond(s.toMetersPerSecond(x)), 1e-9 * Math.abs(x) + 1e-12);
            }
        }
        assertEquals("NM", Units.Length.NAUTICAL_MILES.symbol());
        assertEquals("fpm", Units.Speed.FEET_PER_MINUTE.symbol());
        assertEquals(0.3048, Units.Length.FEET.meters());
    }

    // ---------------------------------------------------------------- positions

    @Test
    void positionsAreWrittenInThreeForms() {
        double lat = Math.toRadians(51.5076), lon = Math.toRadians(-0.1278);
        assertEquals("51.50760° N", GeoFormat.latitude(lat, GeoFormat.Format.DECIMAL, 5));
        assertEquals("51°30.456' N", GeoFormat.latitude(lat, GeoFormat.Format.DEGREES_MINUTES, 3));
        assertEquals("51°30'27.4\" N", GeoFormat.latitude(lat, GeoFormat.Format.DEGREES_MINUTES_SECONDS, 1));
        assertEquals("000.12780° W", GeoFormat.longitude(lon, GeoFormat.Format.DECIMAL, 5, true));
        assertEquals("000°07.668' W", GeoFormat.longitude(lon, GeoFormat.Format.DEGREES_MINUTES, 3, true));
        assertEquals("0°07'40\" W", GeoFormat.longitude(lon, GeoFormat.Format.DEGREES_MINUTES_SECONDS, 0, false));
        assertEquals("33°52' S", GeoFormat.latitude(Math.toRadians(-33.8667), GeoFormat.Format.DEGREES_MINUTES, 0));
    }

    @Test
    void roundingCarriesIntoTheNextUnit() {
        assertEquals("52°00.000' N", GeoFormat.latitude(Math.toRadians(51.9999999), GeoFormat.Format.DEGREES_MINUTES, 3));
        assertEquals("51°31'00.0\" N", GeoFormat.latitude(Math.toRadians(51.0 + 30.0 / 60 + 59.99 / 3600), GeoFormat.Format.DEGREES_MINUTES_SECONDS, 1));
        assertEquals("010.000° E", GeoFormat.longitude(Math.toRadians(9.9999999), GeoFormat.Format.DECIMAL, 3, true));
        assertEquals("000°00.00' E", GeoFormat.longitude(0.0, GeoFormat.Format.DEGREES_MINUTES, 2, true));
        assertEquals("180°00.0' W", GeoFormat.longitude(Math.PI, GeoFormat.Format.DEGREES_MINUTES, 1, true).replace(" E", " W"));
    }

    @Test
    void whatIsWrittenIsReadBackWithinTheResolution() {
        Random rnd = new Random(42);
        for (GeoFormat.Format f : GeoFormat.Format.values()) {
            for (int decimals = 0; decimals <= 6; decimals += 2) {
                double unit = f == GeoFormat.Format.DECIMAL ? 1.0 : f == GeoFormat.Format.DEGREES_MINUTES ? 1.0 / 60 : 1.0 / 3600;
                double resolution = unit / Math.pow(10, decimals);
                for (int i = 0; i < 300; i++) {
                    double lat = (rnd.nextDouble() - 0.5) * 180.0, lon = (rnd.nextDouble() - 0.5) * 360.0;
                    String tl = GeoFormat.latitude(Math.toRadians(lat), f, decimals), tn = GeoFormat.longitude(Math.toRadians(lon), f, decimals, i % 2 == 0);
                    assertEquals(lat, Math.toDegrees(GeoFormat.parseLatitude(tl)), 0.5 * resolution + 1e-9, tl);
                    assertEquals(lon, Math.toDegrees(GeoFormat.parseLongitude(tn)), 0.5 * resolution + 1e-9, tn);
                }
            }
        }
    }

    @Test
    void theCommonVariantsAreRead() {
        double expected = 51.508333333333;
        for (String s : new String[] {"51.508333333 N", "N51.508333333", "N 51° 30.5'", "51 30.5 N", "51:30:30 N", "51°30'30\"N", "n51 30 30", "51° 30' 30.0\" N", "  +51.508333333 ", "51°30.5'N"}) {
            assertEquals(expected, Math.toDegrees(GeoFormat.parseLatitude(s)), 1e-8, s);
        }
        assertEquals(-expected, Math.toDegrees(GeoFormat.parseLatitude("-51 30 30")), 1e-8);
        assertEquals(-expected, Math.toDegrees(GeoFormat.parseLatitude("51 30 30 S")), 1e-8);
        assertEquals(-0.1278, Math.toDegrees(GeoFormat.parseLongitude("W 0° 7' 40.08\"")), 1e-6);
        assertEquals(179.5, Math.toDegrees(GeoFormat.parseLongitude("179.5E")), 1e-12);
        assertEquals(-180.0, Math.toDegrees(GeoFormat.parseLongitude("-180")), 1e-12);
        assertEquals(.5, Math.toDegrees(GeoFormat.parseLatitude(".5")), 1e-12);
    }

    @Test
    void textThatIsNotAPositionIsRefused() {
        for (String s : new String[] {"", "N", "abc", "51 N 30", "91 N", "51 60 N", "51 30 60 N", "51.5 30 N", "-51 N", "51 E", "51 30 30 15", "51 x 30", "5 1 N N"}) {
            assertThrows(IllegalArgumentException.class, () -> GeoFormat.parseLatitude(s), s);
        }
        assertThrows(IllegalArgumentException.class, () -> GeoFormat.parseLongitude("181 E"));
        assertThrows(IllegalArgumentException.class, () -> GeoFormat.parseLongitude("10 N"));
        assertThrows(IllegalArgumentException.class, () -> GeoFormat.latitude(2.0, GeoFormat.Format.DECIMAL, 2));
        assertThrows(IllegalArgumentException.class, () -> GeoFormat.latitude(0.0, GeoFormat.Format.DECIMAL, 10));
        assertThrows(IllegalArgumentException.class, () -> GeoFormat.longitude(Double.NaN, GeoFormat.Format.DECIMAL, 2, true));
    }

    // ---------------------------------------------------------------- bearings and ranges

    @Test
    void bearingsHaveThreeDigitsAndWrap() {
        assertEquals("045°", GeoFormat.bearing(Math.toRadians(45.0), 0));
        assertEquals("000°", GeoFormat.bearing(0.0, 0));
        assertEquals("000°", GeoFormat.bearing(Math.toRadians(359.6), 0), "rounds to 360, which is 000");
        assertEquals("359°", GeoFormat.bearing(Math.toRadians(359.4), 0));
        assertEquals("270°", GeoFormat.bearing(Math.toRadians(-90.0), 0));
        assertEquals("007.5°", GeoFormat.bearing(Math.toRadians(7.5), 1));
        assertEquals("000.0°", GeoFormat.bearing(Math.toRadians(359.99), 1));
        assertEquals("090°", GeoFormat.bearing(Math.toRadians(450.0), 0));
        assertThrows(IllegalArgumentException.class, () -> GeoFormat.bearing(Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> GeoFormat.bearing(0.0, 7));
    }

    @Test
    void rangesPickTheirUnits() {
        assertEquals("12.3 NM", GeoFormat.distance(22_780.0, Units.Length.NAUTICAL_MILES, 1));
        assertEquals("0.0 m", GeoFormat.distance(-0.001, Units.Length.METERS, 1), "no negative zero");
        assertEquals("850 m", GeoFormat.distanceAuto(850.0, GeoFormat.UnitSystem.METRIC));
        assertEquals("1.50 km", GeoFormat.distanceAuto(1500.0, GeoFormat.UnitSystem.METRIC));
        assertEquals("25.0 km", GeoFormat.distanceAuto(25_000.0, GeoFormat.UnitSystem.METRIC));
        assertEquals("250 km", GeoFormat.distanceAuto(250_000.0, GeoFormat.UnitSystem.METRIC));
        assertEquals("328 ft", GeoFormat.distanceAuto(100.0, GeoFormat.UnitSystem.AVIATION));
        assertEquals("0.54 NM", GeoFormat.distanceAuto(1000.0, GeoFormat.UnitSystem.AVIATION));
        assertEquals("12.3 NM", GeoFormat.distanceAuto(22_780.0, GeoFormat.UnitSystem.AVIATION));
        assertEquals("120 NM", GeoFormat.distanceAuto(222_240.0, GeoFormat.UnitSystem.AVIATION));
        assertEquals("0.62 mi", GeoFormat.distanceAuto(1000.0, GeoFormat.UnitSystem.IMPERIAL));
        assertEquals("045° 12.3 NM", GeoFormat.bearingAndRange(Math.toRadians(45.0), 22_780.0));
        assertEquals("252.7 kt", GeoFormat.speed(130.0, Units.Speed.KNOTS, 1));
        assertEquals("-500 fpm", GeoFormat.speed(-2.54, Units.Speed.FEET_PER_MINUTE, 0));
        assertThrows(IllegalArgumentException.class, () -> GeoFormat.distanceAuto(-1.0, GeoFormat.UnitSystem.METRIC));
        assertTrue(GeoFormat.distance(1e12, Units.Length.KILOMETERS, 0).endsWith("km"));
    }

    @Test
    void theTextDoesNotDependOnTheDefaultLocale() {
        java.util.Locale old = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY);
            assertEquals("12.3 NM", GeoFormat.distance(22_780.0, Units.Length.NAUTICAL_MILES, 1));
            assertEquals("51.5000° N", GeoFormat.latitude(Math.toRadians(51.5), GeoFormat.Format.DECIMAL, 4));
        } finally {
            java.util.Locale.setDefault(old);
        }
    }
}
