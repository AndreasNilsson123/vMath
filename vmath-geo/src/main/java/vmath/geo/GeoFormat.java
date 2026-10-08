package vmath.geo;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import vmath.annotations.Experimental;

/**
 * Text for positions, bearings and ranges, and the parsing of position text.
 *
 * <p><b>Positions.</b> A latitude or longitude (radians in, radians out) is written in one of
 * three {@link Format}s with a number of decimals in the last unit, the hemisphere as a letter
 * after the value: {@code 51.50760° N}, {@code 51°30.456' N}, {@code 51°30'27.4" N}. Rounding is
 * done once, on the value in the smallest unit, so {@code 59.9996'} at three decimals becomes the
 * next degree and never {@code 60.000'}. The longitude has three digits of degrees (so the columns
 * line up) unless {@code fixedWidth} is false. {@link #parseLatitude} and {@link #parseLongitude}
 * read these forms and the common variants (the hemisphere letter before or after, a minus sign
 * instead, spaces or colons for the symbols, a missing seconds or minutes part); text that does not
 * fit is refused with a message, never guessed.
 *
 * <p><b>Bearings and ranges.</b> A bearing is three digits of degrees, {@code 045°}, rounded so that
 * {@code 359.6} is {@code 000°}; {@link #distance} writes a length in a chosen unit and
 * {@link #distanceAuto} chooses the unit and the decimals the way a display does (an aviation scale
 * switches from feet to nautical miles at a tenth of a mile). All text uses a decimal point and no
 * locale: the output is the same on every machine.
 *
 * <p><b>Thread safety.</b> Stateless: safe to call from any number of threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * String lat = GeoFormat.latitude(Math.toRadians(51.5076), GeoFormat.Format.DEGREES_MINUTES, 3);     // 51°30.456' N
 * double back = GeoFormat.parseLatitude(lat);
 * String leg = GeoFormat.bearingAndRange(Math.toRadians(45.0), 22_800.0);                              // 045° 12.3 NM
 * }</pre>
 */
@Experimental("new in 0.2: the map layer may change")
public final class GeoFormat {

    /** How a coordinate is written. */
    public enum Format {
        /** Degrees with decimals. */
        DECIMAL,
        /** Whole degrees and minutes with decimals. */
        DEGREES_MINUTES,
        /** Whole degrees and minutes and seconds with decimals. */
        DEGREES_MINUTES_SECONDS
    }

    /** The systems of units that {@link #distanceAuto} chooses within. */
    public enum UnitSystem {
        /** Metres below a kilometre, else kilometres. */
        METRIC,
        /** Feet below a tenth of a nautical mile, else nautical miles. */
        AVIATION,
        /** Feet below a tenth of a statute mile, else statute miles. */
        IMPERIAL
    }

    private GeoFormat() {
    }

    /**
     * Writes a latitude.
     *
     * @param radians the latitude, -pi/2 to pi/2
     * @param format the form
     * @param decimals the decimals of the last unit, 0 to 9
     * @return the text, for example {@code 51°30.456' N}
     * @throws IllegalArgumentException if the latitude is outside the range or not finite, or the decimals are out of range
     */
    public static String latitude(double radians, Format format, int decimals) {
        if (!(Math.abs(radians) <= Math.PI / 2 + 1e-12)) {
            throw new IllegalArgumentException("the latitude must be within +-pi/2: " + radians);
        }
        return coordinate(Math.toDegrees(radians), format, decimals, 2, radians < 0 ? 'S' : 'N');
    }

    /**
     * Writes a longitude.
     *
     * @param radians the longitude, -pi to pi (a longitude outside is wrapped)
     * @param format the form
     * @param decimals the decimals of the last unit, 0 to 9
     * @param fixedWidth {@code true} for three digits of degrees ({@code 007}), {@code false} for as many as needed
     * @return the text, for example {@code 000°07.667' W}
     * @throws IllegalArgumentException if the longitude is not finite or the decimals are out of range
     */
    public static String longitude(double radians, Format format, int decimals, boolean fixedWidth) {
        if (!Double.isFinite(radians)) {
            throw new IllegalArgumentException("the longitude must be finite: " + radians);
        }
        double wrapped = Geodesy.wrapPi(radians);
        return coordinate(Math.toDegrees(wrapped), format, decimals, fixedWidth ? 3 : 1, wrapped < 0 ? 'W' : 'E');
    }

    private static String coordinate(double degrees, Format format, int decimals, int degreeDigits, char hemisphere) {
        if (decimals < 0 || decimals > 9) {
            throw new IllegalArgumentException("the decimals must be 0 to 9: " + decimals);
        }
        double value = Math.abs(degrees);
        double scale = Math.pow(10, decimals);
        StringBuilder sb = new StringBuilder();
        switch (format) {
            case DECIMAL -> {
                long units = Math.round(value * scale);
                sb.append(pad(units / (long) scale, degreeDigits)).append(fraction(units % (long) scale, decimals)).append('\u00B0');
            }
            case DEGREES_MINUTES -> {
                long units = Math.round(value * 60.0 * scale);              // minutes in the last decimal
                long perDegree = 60L * (long) scale;
                sb.append(pad(units / perDegree, degreeDigits)).append('\u00B0').append(pad2((units % perDegree) / (long) scale)).append(fraction(units % (long) scale, decimals)).append('\'');
            }
            case DEGREES_MINUTES_SECONDS -> {
                long units = Math.round(value * 3600.0 * scale);
                long perDegree = 3600L * (long) scale, perMinute = 60L * (long) scale;
                long rest = units % perDegree;
                sb.append(pad(units / perDegree, degreeDigits)).append('\u00B0').append(pad2(rest / perMinute)).append('\'').append(pad2((rest % perMinute) / (long) scale))
                        .append(fraction(units % (long) scale, decimals)).append('"');
            }
        }
        return sb.append(' ').append(hemisphere).toString();
    }

    private static String pad(long v, int digits) {
        String s = Long.toString(v);
        return s.length() >= digits ? s : "0".repeat(digits - s.length()) + s;
    }

    private static String pad2(long v) {
        return v < 10 ? "0" + v : Long.toString(v);
    }

    private static String fraction(long value, int decimals) {
        return decimals == 0 ? "" : "." + pad(value, decimals);
    }

    private static final Pattern NUMBER = Pattern.compile("[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+");

    /**
     * Reads a latitude.
     *
     * @param text a latitude such as {@code 51°30.456' N}, {@code N 51 30 27.4}, {@code -51.5076}
     *     or {@code 5130N}-less forms with the symbols replaced by spaces or colons; must not be {@code null}
     * @return radians, -pi/2 to pi/2
     * @throws IllegalArgumentException if the text is not a latitude: no number, more than three numbers,
     *     minutes or seconds of 60 or more, a decimal in a part that is followed by another, a sign
     *     that contradicts the letter, a longitude hemisphere, or a value beyond 90 degrees
     */
    public static double parseLatitude(String text) {
        return Math.toRadians(parse(text, "NS", 90.0, "latitude"));
    }

    /**
     * Reads a longitude.
     *
     * @param text a longitude such as {@code 000°07.667' W}, {@code W 0 7 40} or {@code -0.1278}; must not be {@code null}
     * @return radians, -pi to pi
     * @throws IllegalArgumentException if the text is not a longitude (as for {@link #parseLatitude}, with a limit of 180 degrees)
     */
    public static double parseLongitude(String text) {
        return Math.toRadians(parse(text, "EW", 180.0, "longitude"));
    }

    private static double parse(String text, String hemispheres, double limit, String what) {
        String s = text.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) {
            throw new IllegalArgumentException("empty " + what);
        }
        char letter = 0;
        for (char c : s.toCharArray()) {
            if (Character.isLetter(c)) {
                if (hemispheres.indexOf(c) < 0 || letter != 0) {
                    throw new IllegalArgumentException("cannot read a " + what + " from \"" + text + "\": unexpected letter " + c);
                }
                letter = c;
                int at = s.indexOf(c);
                if (at != 0 && at != s.length() - 1) {
                    throw new IllegalArgumentException("cannot read a " + what + " from \"" + text + "\": the hemisphere letter belongs before or after the numbers");
                }
            }
        }
        boolean minus = s.contains("-");
        if (minus && letter != 0) {
            throw new IllegalArgumentException("cannot read a " + what + " from \"" + text + "\": a minus sign and a hemisphere letter");
        }
        Matcher m = NUMBER.matcher(s);
        double[] part = new double[3];
        boolean[] fractional = new boolean[3];
        int n = 0;
        int last = 0;
        while (m.find()) {
            if (n == 3) {
                throw new IllegalArgumentException("cannot read a " + what + " from \"" + text + "\": more than degrees, minutes and seconds");
            }
            String between = s.substring(last, m.start());
            if (!between.replaceAll("[\\s\u00B0'\"\u2032\u2033:NSEW+\\-]", "").isEmpty()) {
                throw new IllegalArgumentException("cannot read a " + what + " from \"" + text + "\": unexpected characters \"" + between + "\"");
            }
            part[n] = Double.parseDouble(m.group().startsWith(".") ? "0" + m.group() : m.group().endsWith(".") ? m.group() + "0" : m.group());
            fractional[n] = m.group().contains(".");
            n++;
            last = m.end();
        }
        if (n == 0 || !s.substring(last).replaceAll("[\\s\u00B0'\"\u2032\u2033:NSEW]", "").isEmpty()) {
            throw new IllegalArgumentException("cannot read a " + what + " from \"" + text + "\"");
        }
        for (int i = 0; i + 1 < n; i++) {
            if (fractional[i]) {
                throw new IllegalArgumentException("cannot read a " + what + " from \"" + text + "\": only the last part can have decimals");
            }
        }
        if (n > 1 && part[1] >= 60.0 || n > 2 && part[2] >= 60.0) {
            throw new IllegalArgumentException("cannot read a " + what + " from \"" + text + "\": minutes and seconds must be below 60");
        }
        double value = part[0] + (n > 1 ? part[1] / 60.0 : 0.0) + (n > 2 ? part[2] / 3600.0 : 0.0);
        if (value > limit + 1e-12) {
            throw new IllegalArgumentException("a " + what + " is within " + limit + " degrees: " + text);
        }
        boolean negative = minus || letter == 'S' || letter == 'W';
        return negative ? -value : value;
    }

    /**
     * Writes a bearing.
     *
     * @param radians the bearing, any angle (wrapped to 0 to 360 degrees)
     * @param decimals the decimals of the degrees, 0 to 6
     * @return three digits of degrees and the degree sign, for example {@code 045°}; a bearing that
     *     rounds to 360 is written {@code 000°}
     * @throws IllegalArgumentException if the bearing is not finite or the decimals are out of range
     */
    public static String bearing(double radians, int decimals) {
        if (!Double.isFinite(radians) || decimals < 0 || decimals > 6) {
            throw new IllegalArgumentException("need a finite bearing and 0 to 6 decimals");
        }
        double degrees = Math.toDegrees(Geodesy.normalizeBearing(radians));
        long scale = (long) Math.pow(10, decimals);
        long units = Math.round(degrees * scale) % (360L * scale);
        return pad(units / scale, 3) + (decimals == 0 ? "" : "." + pad(units % scale, decimals)) + '\u00B0';
    }

    /**
     * Writes a length in a given unit.
     *
     * @param meters the length in metres
     * @param unit the unit; must not be {@code null}
     * @param decimals the decimals, 0 to 6
     * @return the number and the symbol, for example {@code 12.3 NM}
     * @throws IllegalArgumentException if the length is not finite or the decimals are out of range
     */
    public static String distance(double meters, Units.Length unit, int decimals) {
        if (!Double.isFinite(meters) || decimals < 0 || decimals > 6) {
            throw new IllegalArgumentException("need a finite length and 0 to 6 decimals");
        }
        String number = String.format(Locale.ROOT, "%." + decimals + "f", unit.fromMeters(meters));
        if (number.startsWith("-") && Double.parseDouble(number) == 0.0) {
            number = number.substring(1);              // "-0.0" is just "0.0"
        }
        return number + ' ' + unit.symbol();
    }

    /**
     * Writes a length in the unit and with the decimals a display would choose.
     *
     * <p>{@link UnitSystem#AVIATION}: feet below a tenth of a nautical mile (whole feet), then nautical
     * miles with two decimals below one, one decimal below a hundred and none above;
     * {@link UnitSystem#METRIC}: whole metres below a kilometre, kilometres with two decimals below ten,
     * one below a hundred, none above; {@link UnitSystem#IMPERIAL}: as aviation with statute miles.
     *
     * @param meters the length in metres, not negative
     * @param system the system; must not be {@code null}
     * @return the text
     * @throws IllegalArgumentException if the length is negative or not finite
     */
    public static String distanceAuto(double meters, UnitSystem system) {
        if (!(meters >= 0.0) || !Double.isFinite(meters)) {
            throw new IllegalArgumentException("the length must be finite and not negative: " + meters);
        }
        switch (system) {
            case METRIC -> {
                if (meters < 1000.0) {
                    return distance(meters, Units.Length.METERS, 0);
                }
                double km = meters / 1000.0;
                return distance(meters, Units.Length.KILOMETERS, km < 10.0 ? 2 : km < 100.0 ? 1 : 0);
            }
            case AVIATION -> {
                double nm = Units.metersToNauticalMiles(meters);
                if (nm < 0.1) {
                    return distance(meters, Units.Length.FEET, 0);
                }
                return distance(meters, Units.Length.NAUTICAL_MILES, nm < 1.0 ? 2 : nm < 100.0 ? 1 : 0);
            }
            default -> {
                double mi = meters / Units.METERS_PER_STATUTE_MILE;
                if (mi < 0.1) {
                    return distance(meters, Units.Length.FEET, 0);
                }
                return distance(meters, Units.Length.STATUTE_MILES, mi < 1.0 ? 2 : mi < 100.0 ? 1 : 0);
            }
        }
    }

    /**
     * Writes a bearing and a range together, in nautical miles.
     *
     * @param bearingRadians the bearing
     * @param meters the range in metres, not negative
     * @return for example {@code 045° 12.3 NM}
     * @throws IllegalArgumentException if a value is not finite or the range is negative
     */
    public static String bearingAndRange(double bearingRadians, double meters) {
        return bearing(bearingRadians, 0) + ' ' + distanceAuto(meters, UnitSystem.AVIATION);
    }

    /**
     * Writes a speed.
     *
     * @param metersPerSecond the speed in metres per second
     * @param unit the unit; must not be {@code null}
     * @param decimals the decimals, 0 to 6
     * @return the number and the symbol, for example {@code 252.7 kt}
     * @throws IllegalArgumentException if the speed is not finite or the decimals are out of range
     */
    public static String speed(double metersPerSecond, Units.Speed unit, int decimals) {
        if (!Double.isFinite(metersPerSecond) || decimals < 0 || decimals > 6) {
            throw new IllegalArgumentException("need a finite speed and 0 to 6 decimals");
        }
        String number = String.format(Locale.ROOT, "%." + decimals + "f", unit.fromMetersPerSecond(metersPerSecond));
        if (number.startsWith("-") && Double.parseDouble(number) == 0.0) {
            number = number.substring(1);
        }
        return number + ' ' + unit.symbol();
    }
}
