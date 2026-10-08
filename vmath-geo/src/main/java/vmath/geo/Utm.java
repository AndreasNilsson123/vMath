package vmath.geo;

import vmath.annotations.Experimental;

/**
 * The Universal Transverse Mercator grid on WGS-84: the sixty zones of six degrees, the latitude
 * bands and the conversions between latitude and longitude and zone, hemisphere, easting and
 * northing.
 *
 * <p>Each zone is a {@link TransverseMercator} with the scale {@code 0.9996} on the central
 * meridian, a false easting of 500 000 m and a false northing of 0 in the northern hemisphere and
 * 10 000 000 m in the southern one. The grid covers latitudes from 80 degrees south to 84 degrees
 * north; beyond them the polar grid is {@link PolarStereographic#ups}. The zone of a point follows
 * the two exceptions of the system: zone 32 is widened to 3 to 12 degrees east between 56 and 64
 * degrees north (south-west Norway), and between 72 and 84 degrees north (Svalbard) the zones 31,
 * 33, 35 and 37 are widened to 0 to 9, 9 to 21, 21 to 33 and 33 to 42 degrees east, so that the
 * zones 32, 34 and 36 are not used there. {@link Mgrs} names the squares of the grid.
 *
 * <p><b>Thread safety.</b> Stateless apart from the cache of the zone projections, which is
 * immutable once built: safe from any number of threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Utm.Coordinate c = Utm.fromGeodetic(Math.toRadians(48.8584), Math.toRadians(2.2945));     // the Eiffel Tower
 * // zone 31, northern hemisphere, easting about 448 252 m, northing about 5 411 932 m
 * double[] latLon = new double[2];
 * Utm.toGeodetic(c, latLon);
 * }</pre>
 */
@Experimental("new in 0.2: the projections of the map layer may change")
public final class Utm {

    /** The scale factor on the central meridian of a zone. */
    public static final double SCALE_FACTOR = 0.9996;

    /** The false easting of a zone in metres. */
    public static final double FALSE_EASTING = 500_000.0;

    /** The false northing of the southern hemisphere in metres. */
    public static final double FALSE_NORTHING_SOUTH = 10_000_000.0;

    /** The latitude bands, from 80 degrees south up in steps of 8 degrees, and X from 72 to 84 degrees north. */
    private static final String BANDS = "CDEFGHJKLMNPQRSTUVWX";

    private static final TransverseMercator[] NORTH = new TransverseMercator[61];
    private static final TransverseMercator[] SOUTH = new TransverseMercator[61];

    private Utm() {
    }

    /**
     * A point of the grid.
     *
     * @param zone the zone, 1 to 60
     * @param north whether the point is in the northern hemisphere
     * @param easting the easting in metres
     * @param northing the northing in metres
     */
    public record Coordinate(int zone, boolean north, double easting, double northing) {

        /**
         * Checks the zone.
         *
         * @throws IllegalArgumentException if the zone is not between 1 and 60 or a number is not finite
         */
        public Coordinate {
            if (zone < 1 || zone > 60 || !Double.isFinite(easting) || !Double.isFinite(northing)) {
                throw new IllegalArgumentException("a UTM coordinate needs a zone from 1 to 60 and finite numbers: " + zone + ", " + easting + ", " + northing);
            }
        }
    }

    /**
     * Finds the zone of a point, with the exceptions of Norway and Svalbard.
     *
     * @param latitude the latitude in radians
     * @param longitude the longitude in radians
     * @return the zone, 1 to 60
     */
    public static int zoneOf(double latitude, double longitude) {
        double lat = Math.toDegrees(latitude);
        double lon = Math.toDegrees(Geodesy.wrapPi(longitude));
        if (lon >= 180.0) {
            lon = -180.0;
        }
        int zone = (int) Math.floor((lon + 180.0) / 6.0) + 1;
        if (lat >= 56.0 && lat < 64.0 && lon >= 3.0 && lon < 12.0) {
            zone = 32;
        } else if (lat >= 72.0 && lat <= 84.0) {
            if (lon >= 0.0 && lon < 9.0) {
                zone = 31;
            } else if (lon >= 9.0 && lon < 21.0) {
                zone = 33;
            } else if (lon >= 21.0 && lon < 33.0) {
                zone = 35;
            } else if (lon >= 33.0 && lon < 42.0) {
                zone = 37;
            }
        }
        return zone;
    }

    /**
     * Finds the latitude band letter.
     *
     * @param latitude the latitude in radians, from -80 to 84 degrees
     * @return the letter C to X (without I and O)
     * @throws IllegalArgumentException if the latitude is outside the grid
     */
    public static char bandOf(double latitude) {
        double lat = Math.toDegrees(latitude);
        if (!(lat >= -80.0 && lat <= 84.0)) {
            throw new IllegalArgumentException("UTM covers latitudes from 80 degrees south to 84 degrees north: " + lat);
        }
        int i = (int) Math.floor((lat + 80.0) / 8.0);
        return BANDS.charAt(Math.min(i, BANDS.length() - 1));
    }

    /**
     * Gives the longitude of the central meridian of a zone.
     *
     * @param zone the zone, 1 to 60
     * @return the longitude in radians
     * @throws IllegalArgumentException if the zone is not between 1 and 60
     */
    public static double centralMeridian(int zone) {
        if (zone < 1 || zone > 60) {
            throw new IllegalArgumentException("the zone must be 1 to 60: " + zone);
        }
        return Math.toRadians(6.0 * zone - 183.0);
    }

    /**
     * Gives the projection of a zone.
     *
     * @param zone the zone, 1 to 60
     * @param north {@code true} for the northern hemisphere (false northing 0), {@code false} for
     *     the southern one (10 000 000 m)
     * @return the projection, shared
     * @throws IllegalArgumentException if the zone is not between 1 and 60
     */
    public static synchronized TransverseMercator projection(int zone, boolean north) {
        double cm = centralMeridian(zone);
        TransverseMercator[] cache = north ? NORTH : SOUTH;
        if (cache[zone] == null) {
            cache[zone] = new TransverseMercator(Ellipsoid.WGS84, 0.0, cm, SCALE_FACTOR, FALSE_EASTING, north ? 0.0 : FALSE_NORTHING_SOUTH);
        }
        return cache[zone];
    }

    /**
     * Converts a position to the grid, in the zone it belongs to.
     *
     * @param latitude the latitude in radians, from -80 to 84 degrees
     * @param longitude the longitude in radians
     * @return the grid coordinate
     * @throws IllegalArgumentException if the latitude is outside the grid
     */
    public static Coordinate fromGeodetic(double latitude, double longitude) {
        bandOf(latitude);
        return fromGeodetic(latitude, longitude, zoneOf(latitude, longitude));
    }

    /**
     * Converts a position to the grid in a chosen zone (a position can be projected in a zone it is
     * not in, for a map that crosses a zone boundary; the distortion grows with the distance from
     * the central meridian).
     *
     * @param latitude the latitude in radians
     * @param longitude the longitude in radians
     * @param zone the zone, 1 to 60
     * @return the grid coordinate; the hemisphere is that of the latitude
     * @throws IllegalArgumentException if the zone is out of range or a number is not finite
     */
    public static Coordinate fromGeodetic(double latitude, double longitude, int zone) {
        boolean north = latitude >= 0;
        double[] xy = new double[2];
        projection(zone, north).forward(latitude, longitude, xy);
        return new Coordinate(zone, north, xy[0], xy[1]);
    }

    /**
     * Converts a grid coordinate back to a position.
     *
     * @param c the coordinate; must not be {@code null}
     * @param latLon receives the latitude and the longitude in radians at {@code latLon[0..1]}
     * @throws IllegalArgumentException if {@code latLon} is too short
     */
    public static void toGeodetic(Coordinate c, double[] latLon) {
        projection(c.zone(), c.north()).inverse(c.easting(), c.northing(), latLon);
    }
}
