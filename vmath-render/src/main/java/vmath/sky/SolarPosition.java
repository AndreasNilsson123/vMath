package vmath.sky;

/**
 * Where the sun is: its position in the sky for a place and a moment, the times of sunrise, solar
 * noon and sunset, and the direction towards it as a vector for lighting.
 *
 * <p>It follows the low-precision solar coordinates of Jean Meeus ("Astronomical Algorithms",
 * chapter 25: the equation of the centre for the Earth's orbit, the apparent longitude with the
 * nutation and aberration correction, the true obliquity), the same series the NOAA solar
 * calculator uses. Its accuracy is about 0.01 degree for years around 2000 and degrades slowly away
 * from them; atmospheric refraction is a separate correction ({@link #refraction}), and the time
 * scale is treated as UT (the difference to dynamical time, about a minute, changes the position by
 * well under 0.001 degree).
 *
 * <p>Angles in the API are in <b>radians</b>, except the latitude and longitude arguments, which
 * are in <b>degrees</b> (latitude positive north, longitude positive east, the usual convention of
 * maps and GPS). Times are Julian day numbers ({@link #julianDay}) in UT; a Julian day starts at
 * noon, so midnight is {@code .5}. The azimuth is measured from north through east (north 0, east
 * 90 degrees, south 180, west 270).
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * double jd = SolarPosition.julianDay(2026, 6, 21, 12, 0, 0.0);
 * SolarPosition.Sun sun = SolarPosition.position(jd, 59.33, 18.07);                    // latitude and longitude in degrees
 * double[] direction = new double[3];
 * SolarPosition.direction(sun.azimuth(), sun.elevation(), direction);                  // the unit vector towards the sun
 * }</pre>
 */
public final class SolarPosition {

    /**
     * The altitude of the sun's centre in degrees at which its upper edge touches the horizon,
     * allowing for refraction and the sun's radius: the standard definition of sunrise and sunset.
     */
    public static final double SUNRISE_SUNSET = -0.833;
    /**
     * The altitude in degrees that ends civil twilight (the sun 6 degrees below the horizon).
     */
    public static final double CIVIL_TWILIGHT = -6.0;
    /**
     * The altitude in degrees that ends nautical twilight.
     */
    public static final double NAUTICAL_TWILIGHT = -12.0;
    /**
     * The altitude in degrees that ends astronomical twilight.
     */
    public static final double ASTRONOMICAL_TWILIGHT = -18.0;

    /**
     * The Julian day of the epoch J2000.0, 2000 January 1 at 12:00 UT.
     */
    public static final double J2000 = 2451545.0;

    private static final double DEG = Math.PI / 180.0;

    private SolarPosition() {
    }

    /**
     * The position of the sun for one instant and place.
     *
     * @param azimuth the direction along the horizon in radians, from north through east, in
     *     {@code [0, 2 pi)}
     * @param elevation the angle above the geometric horizon in radians (no refraction), negative
     *     when the sun is down
     * @param declination the angle of the sun north of the celestial equator in radians
     * @param rightAscension the angle of the sun east of the vernal equinox along the equator in
     *     radians, in {@code [0, 2 pi)}
     * @param hourAngle the angle of the sun west of the local meridian in radians, in
     *     {@code (-pi, pi]}: 0 at solar noon, negative in the morning
     * @param distance the distance from the earth to the sun in astronomical units
     */
    public record Sun(double azimuth, double elevation, double declination, double rightAscension, double hourAngle, double distance) {
    }

    /**
     * What happens to the sun's altitude over a day at a place.
     */
    public enum DayKind {
        /**
         * The sun crosses the chosen altitude twice: it rises and sets.
         */
        NORMAL,
        /**
         * The sun stays above the chosen altitude all day (polar day).
         */
        ALWAYS_ABOVE,
        /**
         * The sun stays below the chosen altitude all day (polar night).
         */
        ALWAYS_BELOW
    }

    /**
     * Solar noon and the rising and setting times for a day and place.
     *
     * @param kind whether the sun crosses the altitude at all
     * @param noon the time of the sun's highest point as a Julian day
     * @param rise when the sun rises through the chosen altitude as a Julian day; {@code NaN}
     *     unless {@code kind} is {@code NORMAL}
     * @param set when the sun sets through the chosen altitude as a Julian day; {@code NaN} unless
     *     {@code kind} is {@code NORMAL}
     */
    public record Day(DayKind kind, double noon, double rise, double set) {
        /**
         * Reads the length of daylight for the chosen altitude threshold; polar day and polar night
         * are the limits.
         *
         * @return the time the sun spends above the chosen altitude, in hours: 24 for polar day, 0
         *     for polar night
         */
        public double length() {
            return switch (kind) {
                case NORMAL -> (set - rise) * 24.0;
                case ALWAYS_ABOVE -> 24.0;
                case ALWAYS_BELOW -> 0.0;
            };
        }
    }

    // ------------------------------------------------------------ time

    /**
     * Converts a calendar date and a fractional day to a Julian day using the algorithm of Meeus,
     * chapter 7, for the Gregorian calendar.
     *
     * <p>The formula is valid for the Gregorian calendar, that is for dates from 1582-10-15 on.
     *
     * @param year the year
     * @param month the month
     * @param day the day
     * @return the Julian day number (with the fraction of the day) of a moment in UT in the
     *     Gregorian calendar, from Meeus chapter 7. {@code month} is 1 to 12, {@code day} is 1 to
     *     31 and may carry a fraction
     */
    public static double julianDay(int year, int month, double day) {
        int y = year, m = month;
        if (m <= 2) {
            y -= 1;
            m += 12;
        }
        int a = Math.floorDiv(y, 100);
        int b = 2 - a + Math.floorDiv(a, 4);
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + day + b - 1524.5;
    }

    /**
     * Converts a calendar date and a time of day to a Julian day, in UT.
     *
     * @param year the year
     * @param month the month
     * @param day the day
     * @param hour the hour
     * @param minute the minute
     * @param second the second
     * @return the Julian day of a moment in UT given with its time of day: hour 0 to 23, minute 0
     *     to 59 and second (which may have a fraction)
     */
    public static double julianDay(int year, int month, int day, int hour, int minute, double second) {
        return julianDay(year, month, day + (hour + (minute + second / 60.0) / 60.0) / 24.0);
    }

    /**
     * Converts a Unix timestamp in milliseconds to a Julian day, so that the current time can be
     * used directly.
     *
     * @param millis the millis
     * @return the Julian day of a moment given as milliseconds since 1970-01-01T00:00:00Z (the Unix
     *     epoch, as {@code System.currentTimeMillis} gives)
     */
    public static double julianDayOfEpochMillis(long millis) {
        return 2440587.5 + millis / 86400000.0;
    }

    // ------------------------------------------------------------ position

    /**
     * Computes the position of the sun in the sky with a low-precision astronomical algorithm
     * (accurate to a small fraction of a degree), for an observer on the ground.
     *
     * <p>The elevation is geometric: add {@link #refraction} for the apparent one.
     *
     * @param jd the Julian day (UT)
     * @param latitude the latitude
     * @param longitude the longitude
     * @return the position of the sun at the Julian day {@code jd} (UT) as seen from the place at
     *     {@code latitude} and {@code longitude} (degrees, north and east positive)
     */
    public static Sun position(double jd, double latitude, double longitude) {
        double t = (jd - J2000) / 36525.0;
        double l0 = 280.46646 + t * (36000.76983 + t * 0.0003032);
        double m = 357.52911 + t * (35999.05029 - t * 0.0001537);
        double e = 0.016708634 - t * (0.000042037 + t * 0.0000001267);
        double mr = m * DEG;
        double c = (1.914602 - t * (0.004817 + t * 0.000014)) * Math.sin(mr) + (0.019993 - 0.000101 * t) * Math.sin(2 * mr) + 0.000289 * Math.sin(3 * mr);
        double trueLongitude = l0 + c;
        double nu = (m + c) * DEG;
        double r = 1.000001018 * (1 - e * e) / (1 + e * Math.cos(nu));
        double omega = (125.04 - 1934.136 * t) * DEG;
        double lambda = (trueLongitude - 0.00569 - 0.00478 * Math.sin(omega)) * DEG;
        double eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0;
        double eps = (eps0 + 0.00256 * Math.cos(omega)) * DEG;
        double ra = Math.atan2(Math.cos(eps) * Math.sin(lambda), Math.cos(lambda));
        double dec = Math.asin(Math.sin(eps) * Math.sin(lambda));
        // the mean sidereal time at Greenwich, then the hour angle for the longitude
        double gmst = 280.46061837 + 360.98564736629 * (jd - J2000) + t * t * (0.000387933 - t / 38710000.0);
        double h = normalizeSigned((gmst + longitude) * DEG - ra);
        double phi = latitude * DEG;
        double sinElevation = Math.sin(phi) * Math.sin(dec) + Math.cos(phi) * Math.cos(dec) * Math.cos(h);
        double elevation = Math.asin(Math.max(-1.0, Math.min(1.0, sinElevation)));
        // Meeus measures the azimuth from the south towards the west: turn it by half a circle to measure from the north towards the east
        double az = Math.atan2(Math.sin(h), Math.cos(h) * Math.sin(phi) - Math.tan(dec) * Math.cos(phi)) + Math.PI;
        return new Sun(normalizePositive(az), elevation, dec, normalizePositive(ra), h, r);
    }

    /**
     * Computes the difference between apparent and mean solar time, the amount that a sundial runs
     * ahead of a clock.
     *
     * <p>It stays within about +-16.5 minutes over the year (about -14 in mid-February, +16 in
     * early November).
     *
     * @param jd the Julian day (UT)
     * @return the equation of time at {@code jd} in minutes: apparent solar time minus mean solar
     *     time, the amount by which a sundial runs ahead of a clock
     */
    public static double equationOfTime(double jd) {
        double t = (jd - J2000) / 36525.0;
        double l0 = 280.46646 + t * (36000.76983 + t * 0.0003032);
        double m = 357.52911 + t * (35999.05029 - t * 0.0001537);
        double e = 0.016708634 - t * (0.000042037 + t * 0.0000001267);
        double eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0;
        double omega = (125.04 - 1934.136 * t) * DEG;
        double eps = (eps0 + 0.00256 * Math.cos(omega)) * DEG;
        // the formula of Smart: y = tan^2(eps / 2), E = y sin(2 L0) - 2 e sin M + 4 e y sin M cos(2 L0) - y^2 sin(4 L0) / 2 - 5 e^2 sin(2 M) / 4, in radians
        double y = Math.tan(eps / 2) * Math.tan(eps / 2);
        double l = l0 * DEG, mr = m * DEG;
        double eq = y * Math.sin(2 * l) - 2 * e * Math.sin(mr) + 4 * e * y * Math.sin(mr) * Math.cos(2 * l) - 0.5 * y * y * Math.sin(4 * l) - 1.25 * e * e * Math.sin(2 * mr);
        return eq / DEG * 4.0;
    }

    // ------------------------------------------------------------ rise and set

    /**
     * Computes the sun's culmination and when it crosses a given altitude on a day, which gives
     * sunrise, sunset and twilight; near the poles the sun may not cross the altitude at all.
     *
     * <p>The times are Julian days and may fall before or after the given UT day for places far
     * from the Greenwich meridian: the local day is meant, centred on the local solar noon nearest
     * to {@code jdMidnight + 0.5 - longitude / 360}. The declination and the equation of time are
     * evaluated at the event itself (a fixed-point iteration).
     *
     * @param jdMidnight the jd midnight
     * @param latitude the latitude
     * @param longitude the longitude
     * @param altitude the altitude
     * @return the sun's highest point and its crossing of the altitude {@code altitude} (degrees:
     *     {@link #SUNRISE_SUNSET}, {@link #CIVIL_TWILIGHT} and so on) on the UT day that starts at
     *     the Julian day {@code jdMidnight} (a number ending in {@code .5}), for the place at
     *     {@code latitude} and {@code longitude}
     */
    public static Day day(double jdMidnight, double latitude, double longitude, double altitude) {
        double base = jdMidnight + 0.5 - longitude / 360.0; // mean solar noon at this longitude
        double noon = base;
        for (int i = 0; i < 3; i++) {
            noon = base - equationOfTime(noon) / 1440.0;
        }
        double phi = latitude * DEG;
        double sinAlt = Math.sin(altitude * DEG);
        double cosAtNoon = cosHourAngle(noon, latitude, longitude, phi, sinAlt);
        if (cosAtNoon > 1.0) {
            return new Day(DayKind.ALWAYS_BELOW, noon, Double.NaN, Double.NaN);
        }
        if (cosAtNoon < -1.0) {
            return new Day(DayKind.ALWAYS_ABOVE, noon, Double.NaN, Double.NaN);
        }
        // each event is a fixed point of t = mean noon - equation of time(t) -+ hour angle(t): the declination and the equation of time move during the day
        double rise = noon, set = noon;
        for (int i = 0; i < 5; i++) {
            rise = base - equationOfTime(rise) / 1440.0 - hourAngleOfAltitude(rise, latitude, longitude, phi, sinAlt) / (2 * Math.PI);
            set = base - equationOfTime(set) / 1440.0 + hourAngleOfAltitude(set, latitude, longitude, phi, sinAlt) / (2 * Math.PI);
        }
        return new Day(DayKind.NORMAL, noon, rise, set);
    }

    private static double cosHourAngle(double jd, double latitude, double longitude, double phi, double sinAlt) {
        double dec = position(jd, latitude, longitude).declination();
        return (sinAlt - Math.sin(phi) * Math.sin(dec)) / (Math.cos(phi) * Math.cos(dec));
    }

    /**
     * The hour angle in radians (0 to pi) at which the sun is at the altitude, with the declination
     * of the given moment; clamped where the sun would not reach it.
     */
    private static double hourAngleOfAltitude(double jd, double latitude, double longitude, double phi, double sinAlt) {
        return Math.acos(Math.max(-1.0, Math.min(1.0, cosHourAngle(jd, latitude, longitude, phi, sinAlt))));
    }

    // ------------------------------------------------------------ direction and refraction

    /**
     * Computes the unit vector towards the sun in a right-handed frame with the y axis up: x
     * towards the east, z towards the south (north is -z, as in the usual OpenGL convention where
     * the camera looks down -z).
     *
     * <p>Writes {@code out[0 .. 3)}. Rotate it about y if the world's north is somewhere else.
     *
     * @param azimuth the azimuth
     * @param elevation the elevation
     * @param out receives the result in {@code [0, 3)}
     */
    public static void direction(double azimuth, double elevation, double[] out) {
        double ce = Math.cos(elevation);
        out[0] = ce * Math.sin(azimuth);
        out[1] = Math.sin(elevation);
        out[2] = -ce * Math.cos(azimuth);
    }

    /**
     * Estimates the bending of light by the atmosphere with Saemundsson's formula for a standard
     * atmosphere, which lifts the sun slightly near the horizon.
     *
     * <p>It is about 0.48 degree for a true elevation of 0 (0.57 degree where the sun is seen at
     * the horizon) and about 1 arcminute at 45 degrees. Not valid far below the horizon: it returns
     * 0 for elevations below -5 degrees, where the formula has no meaning.
     *
     * @param elevation the elevation
     * @return the atmospheric refraction in radians to add to a true elevation to get the apparent
     *     one, for a standard atmosphere (1010 mbar, 10 degrees Celsius), by the formula of
     *     Saemundsson (as in Meeus chapter 16)
     */
    public static double refraction(double elevation) {
        return refraction(elevation, 1010.0, 10.0);
    }

    /**
     * Estimates the bending of light by the atmosphere like the standard formula, corrected for the
     * actual pressure and temperature.
     *
     * @param elevation the elevation
     * @param pressureMillibar the pressure millibar
     * @param temperatureCelsius the temperature celsius
     * @return {@link #refraction(double)} for the air pressure in millibar and temperature in
     *     degrees Celsius at the observer: the standard value times
     *     {@code (P / 1010) (283 / (273 + T))}
     */
    public static double refraction(double elevation, double pressureMillibar, double temperatureCelsius) {
        double h = elevation / DEG;
        if (h < -5.0) {
            return 0.0;
        }
        double arcminutes = 1.02 / Math.tan((h + 10.3 / (h + 5.11)) * DEG);
        return arcminutes / 60.0 * DEG * (pressureMillibar / 1010.0) * (283.0 / (273.0 + temperatureCelsius));
    }

    private static double normalizePositive(double a) {
        double r = a % (2 * Math.PI);
        return r < 0 ? r + 2 * Math.PI : r;
    }

    private static double normalizeSigned(double a) {
        double r = normalizePositive(a);
        return r > Math.PI ? r - 2 * Math.PI : r;
    }
}
