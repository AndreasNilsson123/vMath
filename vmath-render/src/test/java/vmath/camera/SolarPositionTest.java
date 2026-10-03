package vmath.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vmath.camera.SolarPosition.Day;
import vmath.camera.SolarPosition.DayKind;
import vmath.camera.SolarPosition.Sun;

class SolarPositionTest {

    private static double deg(double radians) {
        return Math.toDegrees(radians);
    }

    @Test
    void julianDayOfKnownMoments() {
        // Meeus, "Astronomical Algorithms", chapter 7
        assertEquals(2451545.0, SolarPosition.julianDay(2000, 1, 1.5), 0.0);
        assertEquals(2446895.5, SolarPosition.julianDay(1987, 4, 10), 0.0);
        assertEquals(2436116.31, SolarPosition.julianDay(1957, 10, 4.81), 1e-9);
        assertEquals(SolarPosition.J2000, SolarPosition.julianDay(2000, 1, 1, 12, 0, 0.0), 0.0);
        assertEquals(SolarPosition.julianDay(2024, 2, 29.75), SolarPosition.julianDay(2024, 2, 29, 18, 0, 0.0), 1e-12);
        // January and February count as months 13 and 14 of the previous year: continuity across the boundary
        assertEquals(1.0, SolarPosition.julianDay(2023, 3, 1, 0, 0, 0.0) - SolarPosition.julianDay(2023, 2, 28, 0, 0, 0.0), 0.0);
        assertEquals(1.0, SolarPosition.julianDay(2024, 1, 1, 0, 0, 0.0) - SolarPosition.julianDay(2023, 12, 31, 0, 0, 0.0), 0.0);
        // the Unix epoch and J2000 as milliseconds
        assertEquals(2440587.5, SolarPosition.julianDayOfEpochMillis(0), 0.0);
        assertEquals(SolarPosition.J2000, SolarPosition.julianDayOfEpochMillis(946728000000L), 1e-9);
        assertEquals(SolarPosition.julianDay(2021, 7, 4, 15, 30, 0.0), SolarPosition.julianDayOfEpochMillis(1625412600000L), 1e-9);
    }

    @Test
    void theSolarCoordinatesMatchTheWorkedExampleOfMeeus() {
        // Meeus example 25.a: 1992 October 13, 0h: right ascension 198.38083 degrees, declination -7.78507 degrees, distance 0.99766 AU
        double jd = 2448908.5;
        Sun s = SolarPosition.position(jd, 0.0, 0.0);
        assertEquals(198.38083, deg(s.rightAscension()), 0.02);
        assertEquals(-7.78507, deg(s.declination()), 0.02);
        assertEquals(0.99766, s.distance(), 1e-4);
    }

    @Test
    void solsticesEquinoxesAndTheDistance() {
        // 2000: the March equinox was March 20 at 07:35 UT, the June solstice June 21 at 01:48 UT, the December solstice December 21 at 13:37 UT
        assertEquals(0.0, deg(SolarPosition.position(SolarPosition.julianDay(2000, 3, 20, 7, 35, 0), 0, 0).declination()), 0.02);
        assertEquals(23.44, deg(SolarPosition.position(SolarPosition.julianDay(2000, 6, 21, 1, 48, 0), 0, 0).declination()), 0.02);
        assertEquals(-23.44, deg(SolarPosition.position(SolarPosition.julianDay(2000, 12, 21, 13, 37, 0), 0, 0).declination()), 0.02);
        // the earth is closest to the sun in early January (0.983 AU) and farthest in early July (1.017 AU)
        assertEquals(0.9833, SolarPosition.position(SolarPosition.julianDay(2000, 1, 3, 5, 0, 0), 0, 0).distance(), 5e-4);
        assertEquals(1.0167, SolarPosition.position(SolarPosition.julianDay(2000, 7, 3, 23, 0, 0), 0, 0).distance(), 5e-4);
    }

    @Test
    void theEquationOfTimeHasItsKnownExtremesAndZeros() {
        // the four extremes of the year, to within half a minute
        assertEquals(-14.2, SolarPosition.equationOfTime(SolarPosition.julianDay(2000, 2, 11, 12, 0, 0)), 0.5);
        assertEquals(3.6, SolarPosition.equationOfTime(SolarPosition.julianDay(2000, 5, 14, 12, 0, 0)), 0.5);
        assertEquals(-6.5, SolarPosition.equationOfTime(SolarPosition.julianDay(2000, 7, 26, 12, 0, 0)), 0.5);
        assertEquals(16.4, SolarPosition.equationOfTime(SolarPosition.julianDay(2000, 11, 3, 12, 0, 0)), 0.5);
        double min = 100, max = -100, sum = 0;
        int days = 366;
        for (int d = 0; d < days; d++) {
            double e = SolarPosition.equationOfTime(SolarPosition.julianDay(2000, 1, 1 + d, 12, 0, 0));
            min = Math.min(min, e);
            max = Math.max(max, e);
            sum += e;
        }
        assertEquals(-14.3, min, 0.4);
        assertEquals(16.4, max, 0.4);
        assertTrue(Math.abs(sum / days) < 0.6, "mean " + sum / days);
        // it crosses zero around April 15, June 13, September 1 and December 25
        assertEquals(0.0, SolarPosition.equationOfTime(SolarPosition.julianDay(2000, 4, 15, 12, 0, 0)), 0.5);
        assertEquals(0.0, SolarPosition.equationOfTime(SolarPosition.julianDay(2000, 6, 13, 12, 0, 0)), 0.5);
        assertEquals(0.0, SolarPosition.equationOfTime(SolarPosition.julianDay(2000, 9, 1, 12, 0, 0)), 0.5);
        assertEquals(0.0, SolarPosition.equationOfTime(SolarPosition.julianDay(2000, 12, 25, 12, 0, 0)), 0.5);
    }

    @Test
    void theSunMovesEastToWestAndCulminatesInTheSouthAtMidLatitudes() {
        double lat = 48.0, lon = 11.0; // Munich
        double jd0 = SolarPosition.julianDay(2023, 6, 21, 0, 0, 0.0);
        Day day = SolarPosition.day(jd0, lat, lon, SolarPosition.SUNRISE_SUNSET);
        Sun morning = SolarPosition.position(day.noon() - 0.2, lat, lon), noon = SolarPosition.position(day.noon(), lat, lon), evening = SolarPosition.position(day.noon() + 0.2, lat, lon);
        assertTrue(morning.hourAngle() < 0 && evening.hourAngle() > 0);
        assertEquals(0.0, deg(noon.hourAngle()), 0.05);
        assertEquals(180.0, deg(noon.azimuth()), 0.5);
        assertTrue(deg(morning.azimuth()) < 180 && deg(morning.azimuth()) > 60, "the morning sun is in the east: " + deg(morning.azimuth()));
        assertTrue(deg(evening.azimuth()) > 180 && deg(evening.azimuth()) < 300);
        // the noon elevation at the June solstice is 90 - latitude + 23.44
        assertEquals(90 - lat + 23.44, deg(noon.elevation()), 0.05);
        // the day's highest point found by scanning agrees with the computed solar noon, to a minute
        double best = -10, bestJd = 0;
        for (double jd = day.noon() - 0.1; jd <= day.noon() + 0.1; jd += 1.0 / 14400) {
            double el = SolarPosition.position(jd, lat, lon).elevation();
            if (el > best) {
                best = el;
                bestJd = jd;
            }
        }
        assertEquals(day.noon(), bestJd, 1.0 / 1440);
    }

    @Test
    void sunriseAndSunsetHaveTheDefinedAltitudeAndKnownDayLengths() {
        double[][] places = {{51.5, -0.1}, {-33.9, 151.2}, {35.7, 139.7}, {0.0, 0.0}, {64.1, -21.9}, {40.7, -74.0}, {-54.8, -68.3}};
        for (double[] p : places) {
            for (int month = 1; month <= 12; month++) {
                double jd0 = SolarPosition.julianDay(2023, month, 15);
                Day d = SolarPosition.day(jd0, p[0], p[1], SolarPosition.SUNRISE_SUNSET);
                if (d.kind() != DayKind.NORMAL) {
                    continue;
                }
                assertTrue(d.rise() < d.noon() && d.noon() < d.set());
                assertEquals(SolarPosition.SUNRISE_SUNSET, deg(SolarPosition.position(d.rise(), p[0], p[1]).elevation()), 0.02, "sunrise at " + p[0] + ", " + p[1] + " month " + month);
                assertEquals(SolarPosition.SUNRISE_SUNSET, deg(SolarPosition.position(d.set(), p[0], p[1]).elevation()), 0.02, "sunset at " + p[0] + ", " + p[1] + " month " + month);
                assertEquals((d.set() - d.rise()) * 24, d.length(), 1e-12);
            }
        }
        // London at the June solstice 2000: sunrise 03:43 UT, sunset 20:21 UT, 16 h 38 min of daylight
        Day london = SolarPosition.day(SolarPosition.julianDay(2000, 6, 21), 51.5, -0.12, SolarPosition.SUNRISE_SUNSET);
        assertEquals(16.63, london.length(), 0.06);
        assertEquals(3 + 43 / 60.0, (london.rise() - SolarPosition.julianDay(2000, 6, 21)) * 24, 0.06);
        assertEquals(20 + 21 / 60.0, (london.set() - SolarPosition.julianDay(2000, 6, 21)) * 24, 0.06);
        // the equator at an equinox: a little over 12 hours (refraction and the sun's radius)
        Day equator = SolarPosition.day(SolarPosition.julianDay(2000, 3, 20), 0.0, 0.0, SolarPosition.SUNRISE_SUNSET);
        assertEquals(12.12, equator.length(), 0.06);
        // twilight lengthens the day
        Day civil = SolarPosition.day(SolarPosition.julianDay(2000, 3, 20), 51.5, 0.0, SolarPosition.CIVIL_TWILIGHT);
        Day plain = SolarPosition.day(SolarPosition.julianDay(2000, 3, 20), 51.5, 0.0, SolarPosition.SUNRISE_SUNSET);
        assertTrue(civil.length() > plain.length() + 0.5);
        Day astro = SolarPosition.day(SolarPosition.julianDay(2000, 3, 20), 51.5, 0.0, SolarPosition.ASTRONOMICAL_TWILIGHT);
        Day nautical = SolarPosition.day(SolarPosition.julianDay(2000, 3, 20), 51.5, 0.0, SolarPosition.NAUTICAL_TWILIGHT);
        assertTrue(astro.length() > nautical.length() && nautical.length() > civil.length());
    }

    @Test
    void polarDayAndNight() {
        // Tromso (69.65 N): midnight sun at the June solstice, polar night at the December solstice
        assertEquals(DayKind.ALWAYS_ABOVE, SolarPosition.day(SolarPosition.julianDay(2023, 6, 21), 69.65, 18.96, SolarPosition.SUNRISE_SUNSET).kind());
        assertEquals(DayKind.ALWAYS_BELOW, SolarPosition.day(SolarPosition.julianDay(2023, 12, 21), 69.65, 18.96, SolarPosition.SUNRISE_SUNSET).kind());
        Day above = SolarPosition.day(SolarPosition.julianDay(2023, 6, 21), 80.0, 0.0, SolarPosition.SUNRISE_SUNSET);
        assertEquals(24.0, above.length(), 0.0);
        assertTrue(Double.isNaN(above.rise()) && Double.isNaN(above.set()));
        Day below = SolarPosition.day(SolarPosition.julianDay(2023, 12, 21), 80.0, 0.0, SolarPosition.SUNRISE_SUNSET);
        assertEquals(0.0, below.length(), 0.0);
        // the sun still rises and sets in Tromso in March
        assertEquals(DayKind.NORMAL, SolarPosition.day(SolarPosition.julianDay(2023, 3, 20), 69.65, 18.96, SolarPosition.SUNRISE_SUNSET).kind());
        // in the summer at 60 N it never gets as dark as the astronomical twilight
        assertEquals(DayKind.ALWAYS_ABOVE, SolarPosition.day(SolarPosition.julianDay(2023, 6, 21), 60.0, 10.0, SolarPosition.ASTRONOMICAL_TWILIGHT).kind());
    }

    @Test
    void theDirectionVectorPointsAtTheSun() {
        double[] v = new double[3];
        SolarPosition.direction(0.0, 0.0, v);
        assertEquals(0.0, v[0], 1e-15);
        assertEquals(0.0, v[1], 1e-15);
        assertEquals(-1.0, v[2], 1e-15); // north is -z
        SolarPosition.direction(Math.PI / 2, 0.0, v);
        assertEquals(1.0, v[0], 1e-15); // east is +x
        SolarPosition.direction(Math.PI, 0.0, v);
        assertEquals(1.0, v[2], 1e-15); // south is +z
        SolarPosition.direction(1.0, Math.PI / 2, v);
        assertEquals(1.0, v[1], 1e-15);
        for (int i = 0; i < 100; i++) {
            SolarPosition.direction(i * 0.37, (i % 19 - 9) * 0.15, v);
            assertEquals(1.0, Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]), 1e-12);
        }
        // the elevation of the vector agrees with the elevation of the position: the height is the sine of the elevation
        Sun s = SolarPosition.position(SolarPosition.julianDay(2023, 9, 1, 9, 30, 0), 40.0, -3.7);
        SolarPosition.direction(s.azimuth(), s.elevation(), v);
        assertEquals(Math.sin(s.elevation()), v[1], 1e-15);
    }

    @Test
    void refractionOfTheAtmosphere() {
        // Saemundsson: 28.97 arcminutes at a true elevation of 0, about 1 arcminute at 45 degrees, rapidly less above
        assertEquals(28.97, deg(SolarPosition.refraction(0.0)) * 60, 0.05);
        assertEquals(1.016, deg(SolarPosition.refraction(Math.toRadians(45))) * 60, 0.01);
        assertTrue(SolarPosition.refraction(Math.toRadians(10)) > SolarPosition.refraction(Math.toRadians(20)));
        assertEquals(0.0, SolarPosition.refraction(Math.toRadians(-6)), 0.0);
        // pressure and temperature scale it
        double standard = SolarPosition.refraction(Math.toRadians(5));
        assertEquals(standard * 0.5, SolarPosition.refraction(Math.toRadians(5), 505.0, 10.0), 1e-15);
        assertEquals(standard * 283.0 / 263.0, SolarPosition.refraction(Math.toRadians(5), 1010.0, -10.0), 1e-15);
    }
}
