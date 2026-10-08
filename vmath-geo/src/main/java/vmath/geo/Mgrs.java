package vmath.geo;

import vmath.annotations.Experimental;

/**
 * The Military Grid Reference System on WGS-84, for the UTM part of the world (latitudes from 80
 * degrees south to 84 degrees north): a position as a zone, a latitude band, a pair of letters for
 * the 100 km square and an even number of digits for the easting and the northing inside it, such
 * as {@code 31U DQ 48252 11932}.
 *
 * <p><b>Format.</b> {@link #format} writes the reference with 0 to 5 digits for each of the
 * easting and the northing (5 digits are one metre, 4 ten metres, ... 0 the 100 km square); the
 * digits are the position <em>truncated</em>, not rounded, as the system defines, so the position
 * is always inside the square the reference names. {@link #parse} reads a reference, with or
 * without spaces, and gives the south-west corner of the square it names ({@link #parseCentre} the
 * centre).
 *
 * <p><b>The letters</b> follow the "AA" scheme of WGS-84 (also called the new scheme): the column
 * letter of a square depends on the zone (the letters {@code A} to {@code H}, {@code J} to
 * {@code R} and {@code S} to {@code Z} for the zones 1, 2 and 3 of each group of three) and the row
 * letter counts {@code A} to {@code V} (without {@code I} and {@code O}) from the equator, offset by
 * five letters in the even zones, repeating every 2 000 km; the latitude band resolves which of the
 * repetitions is meant.
 *
 * <p><b>Not covered:</b> the polar caps (the UPS part of the system, bands A, B, Y and Z), which
 * {@link #format} refuses with an exception rather than produce letters that were not checked
 * against a reference. {@link PolarStereographic#ups} is the projection of the caps.
 *
 * <p><b>Thread safety.</b> Stateless: safe from any number of threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * String ref = Mgrs.format(Math.toRadians(48.8584), Math.toRadians(2.2945), 5);   // "31UDQ4825211932"
 * double[] latLon = new double[2];
 * Mgrs.parseCentre("31U DQ 48252 11932", latLon);
 * }</pre>
 */
@Experimental("new in 0.2: the projections of the map layer may change")
public final class Mgrs {

    private static final String[] COLUMNS = {"ABCDEFGH", "JKLMNPQR", "STUVWXYZ"};
    private static final String ROWS = "ABCDEFGHJKLMNPQRSTUV";
    private static final String BANDS = "CDEFGHJKLMNPQRSTUVWX";
    /** The least northing of each latitude band, C to X, that a reference of the band can have. */
    private static final double[] BAND_MIN_NORTHING = {1100000, 2000000, 2800000, 3700000, 4600000, 5500000, 6400000, 7300000, 8200000, 9100000, 0, 800000, 1700000, 2600000, 3500000,
            4400000, 5300000, 6200000, 7000000, 7900000};

    private Mgrs() {
    }

    /**
     * Writes the reference of a position.
     *
     * @param latitude the latitude in radians, from -80 to 84 degrees
     * @param longitude the longitude in radians
     * @param digits the number of digits of the easting and of the northing each, 0 to 5
     * @return the reference without spaces, such as {@code 31UDQ4825211932}
     * @throws IllegalArgumentException if the latitude is outside the UTM grid or {@code digits} is out of range
     */
    public static String format(double latitude, double longitude, int digits) {
        if (digits < 0 || digits > 5) {
            throw new IllegalArgumentException("the number of digits must be 0 to 5: " + digits);
        }
        char band = Utm.bandOf(latitude);
        Utm.Coordinate c = Utm.fromGeodetic(latitude, longitude);
        int zone = c.zone();
        long easting = (long) Math.floor(c.easting()), northing = (long) Math.floor(c.northing());
        int column = (int) (easting / 100000) - 1;
        if (column < 0 || column > 7) {
            throw new IllegalArgumentException("the easting is outside the 100 km columns of the zone: " + c.easting());
        }
        char columnLetter = COLUMNS[(zone - 1) % 3].charAt(column);
        int row = (int) ((northing / 100000 + (zone % 2 == 0 ? 5 : 0)) % 20);
        char rowLetter = ROWS.charAt(row);
        StringBuilder sb = new StringBuilder();
        sb.append(zone < 10 ? "0" : "").append(zone).append(band).append(columnLetter).append(rowLetter);
        if (digits > 0) {
            String e = String.format("%05d", easting % 100000), n = String.format("%05d", northing % 100000);
            sb.append(e, 0, digits).append(n, 0, digits);
        }
        return sb.toString();
    }

    /** The parsed parts of a reference. */
    private record Parsed(int zone, char band, double easting, double northing, double size) {
    }

    private static Parsed parseParts(String reference) {
        String s = reference.replaceAll("\\s+", "").toUpperCase(java.util.Locale.ROOT);
        int i = 0;
        while (i < s.length() && i < 2 && Character.isDigit(s.charAt(i))) {
            i++;
        }
        if (i == 0 || s.length() < i + 3) {
            throw new IllegalArgumentException("not a grid reference: " + reference);
        }
        int zone = Integer.parseInt(s.substring(0, i));
        if (zone < 1 || zone > 60) {
            throw new IllegalArgumentException("the zone must be 1 to 60: " + reference);
        }
        char band = s.charAt(i);
        int bandIndex = BANDS.indexOf(band);
        if (bandIndex < 0) {
            throw new IllegalArgumentException("not a latitude band of the UTM grid (C to X without I and O): " + reference);
        }
        char colLetter = s.charAt(i + 1), rowLetter = s.charAt(i + 2);
        int column = COLUMNS[(zone - 1) % 3].indexOf(colLetter);
        int rowIndex = ROWS.indexOf(rowLetter);
        if (column < 0 || rowIndex < 0) {
            throw new IllegalArgumentException("the letters of the 100 km square are not valid in zone " + zone + ": " + reference);
        }
        String digits = s.substring(i + 3);
        if (digits.length() % 2 != 0 || digits.length() > 10 || !digits.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("the digits must be an even number, at most 10: " + reference);
        }
        int half = digits.length() / 2;
        double size = Math.pow(10, 5 - half);
        double e = (column + 1) * 100000.0 + (half == 0 ? 0 : Long.parseLong(digits.substring(0, half)) * size);
        double n100 = ((rowIndex - (zone % 2 == 0 ? 5 : 0)) % 20 + 20) % 20 * 100000.0;
        double n = n100 + (half == 0 ? 0 : Long.parseLong(digits.substring(half)) * size);
        double min = BAND_MIN_NORTHING[bandIndex];
        while (n < min) {
            n += 2_000_000.0;
        }
        return new Parsed(zone, band, e, n, half == 0 ? 100000.0 : size);
    }

    /**
     * Reads a reference and gives the south-west corner of the square it names.
     *
     * @param reference the reference, with or without spaces, such as {@code 31U DQ 48252 11932}
     * @param latLon receives the latitude and longitude in radians at {@code latLon[0..1]}
     * @throws IllegalArgumentException if the reference is malformed, or {@code latLon} is too short
     */
    public static void parse(String reference, double[] latLon) {
        Parsed p = parseParts(reference);
        Utm.toGeodetic(new Utm.Coordinate(p.zone(), p.band() >= 'N', p.easting(), p.northing()), latLon);
    }

    /**
     * Reads a reference and gives the centre of the square it names.
     *
     * @param reference the reference, with or without spaces
     * @param latLon receives the latitude and longitude in radians at {@code latLon[0..1]}
     * @throws IllegalArgumentException if the reference is malformed, or {@code latLon} is too short
     */
    public static void parseCentre(String reference, double[] latLon) {
        Parsed p = parseParts(reference);
        Utm.toGeodetic(new Utm.Coordinate(p.zone(), p.band() >= 'N', p.easting() + p.size() / 2, p.northing() + p.size() / 2), latLon);
    }

    /**
     * Gives the size of the square that a reference names.
     *
     * @param reference the reference
     * @return the side in metres: 100 000 for no digits down to 1 for ten digits
     * @throws IllegalArgumentException if the reference is malformed
     */
    public static double squareSize(String reference) {
        return parseParts(reference).size();
    }
}
