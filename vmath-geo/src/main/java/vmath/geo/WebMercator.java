package vmath.geo;

import vmath.core.Wgs84;

/**
 * The spherical Web Mercator projection (EPSG:3857) that map-tile servers use, with the conversions
 * between longitude and latitude, projected metres and the normalised tile square.
 *
 * <p><b>Spherical, on purpose.</b> The projection treats the Earth as a sphere whose radius is the
 * equatorial radius of WGS-84, {@link Wgs84#A}, but is fed the geodetic latitude of the ellipsoid.
 * This is what EPSG:3857 and every slippy-map tile set do, and it is what makes the tiles of one
 * zoom level a regular grid. It is a tile addressing scheme, not a measuring projection: scales and
 * angles are not those of the ellipsoid (the ground size of a pixel from {@link #metersPerPixel}
 * is that of the sphere, and the ellipsoid differs from it by up to a third of a percent in the
 * meridian direction). Positions in 3D come from {@link Wgs84}, never from the projected metres.
 *
 * <p><b>The tile square.</b> The normalised coordinates {@code (u, v)} cover the whole map as a
 * unit square: {@code u} is 0 at longitude -180 degrees and 1 at +180 degrees, {@code v} is 0 at
 * the northern limit {@link #MAX_LATITUDE} and 1 at the southern one (rows count down, as in XYZ
 * tile numbering). A tile of zoom {@code z} is the square of side {@code 1 / 2^z}. The poles are not
 * part of the map: the projection goes to infinity there, and the limit is where the map becomes a
 * square.
 *
 * <p>All angles are in radians and all lengths in metres.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * double u = WebMercator.u(Math.toRadians(18.07));                 // 0.5502: a little east of the middle
 * double v = WebMercator.v(Math.toRadians(59.33));                 // north of the equator, so below 0.5
 * double lat = WebMercator.latitudeOfV(v);                         // 59.33 degrees again, in radians
 * }</pre>
 */
public final class WebMercator {

    /**
     * The northern limit of the map in radians, {@code atan(sinh(pi))}, about 85.0511 degrees; the
     * southern limit is its negative.
     */
    public static final double MAX_LATITUDE = Math.atan(Math.sinh(Math.PI));

    /**
     * The sphere radius of the projection in metres, the equatorial radius of WGS-84.
     */
    public static final double RADIUS = Wgs84.A;

    /**
     * Half the width of the map in projected metres, {@code pi * RADIUS}.
     */
    public static final double HALF_WORLD = Math.PI * RADIUS;

    private WebMercator() {
    }

    /**
     * Clamps a latitude to the area that the projection covers.
     *
     * @param latitude the latitude in radians
     * @return the latitude within {@code [-MAX_LATITUDE, MAX_LATITUDE]}
     */
    public static double clampLatitude(double latitude) {
        return Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, latitude));
    }

    /**
     * Converts a longitude to the horizontal coordinate of the tile square.
     *
     * @param longitude the longitude in radians; {@code -pi} gives 0 and {@code pi} gives 1
     * @return {@code u} in {@code [0, 1]} for a longitude in {@code [-pi, pi]}
     */
    public static double u(double longitude) {
        return (longitude + Math.PI) / (2.0 * Math.PI);
    }

    /**
     * Converts a latitude to the vertical coordinate of the tile square, which counts down from
     * the north.
     *
     * <p>The latitude is clamped to {@link #MAX_LATITUDE} first, so a point at a pole lands on the
     * edge of the map.
     *
     * @param latitude the latitude in radians
     * @return {@code v} in {@code [0, 1]}: 0 at the northern limit, 0.5 at the equator, 1 at the
     *     southern limit
     */
    public static double v(double latitude) {
        double s = Math.sin(clampLatitude(latitude));
        return 0.5 - Math.log((1.0 + s) / (1.0 - s)) / (4.0 * Math.PI);
    }

    /**
     * Converts the horizontal coordinate of the tile square back to a longitude.
     *
     * @param u the coordinate; 0 to 1 covers the map
     * @return the longitude in radians
     */
    public static double longitudeOfU(double u) {
        return u * 2.0 * Math.PI - Math.PI;
    }

    /**
     * Converts the vertical coordinate of the tile square back to a latitude.
     *
     * @param v the coordinate; 0 to 1 covers the map
     * @return the latitude in radians, in {@code [-MAX_LATITUDE, MAX_LATITUDE]} for {@code v} in
     *     {@code [0, 1]}
     */
    public static double latitudeOfV(double v) {
        return Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * v)));
    }

    /**
     * Converts a longitude to the projected x coordinate in metres.
     *
     * @param longitude the longitude in radians
     * @return the distance east of the prime meridian on the map, in metres
     */
    public static double x(double longitude) {
        return longitude * RADIUS;
    }

    /**
     * Converts a latitude to the projected y coordinate in metres.
     *
     * @param latitude the latitude in radians, clamped to the area of the map
     * @return the distance north of the equator on the map, in metres
     */
    public static double y(double latitude) {
        double s = Math.sin(clampLatitude(latitude));
        return 0.5 * RADIUS * Math.log((1.0 + s) / (1.0 - s));
    }

    /**
     * Converts a projected x coordinate in metres back to a longitude.
     *
     * @param x the distance east of the prime meridian on the map, in metres
     * @return the longitude in radians
     */
    public static double longitudeOfX(double x) {
        return x / RADIUS;
    }

    /**
     * Converts a projected y coordinate in metres back to a latitude.
     *
     * @param y the distance north of the equator on the map, in metres
     * @return the latitude in radians
     */
    public static double latitudeOfY(double y) {
        return Math.atan(Math.sinh(y / RADIUS));
    }

    /**
     * The size of a map pixel in projected metres, which is the scale of the projection at a
     * latitude.
     *
     * <p>The value is {@code 2 pi RADIUS cos(lat) / (tileSize 2^zoom)}: it is the true ground size
     * of a pixel on a sphere of radius {@link #RADIUS}, which is why it shrinks towards the poles
     * while the pixel grid stays square.
     *
     * @param latitude the latitude in radians
     * @param zoom the zoom level, 0 for the whole map in one tile
     * @param tileSize the width of a tile in pixels, typically 256
     * @return the ground size of one pixel in metres
     */
    public static double metersPerPixel(double latitude, int zoom, int tileSize) {
        return 2.0 * Math.PI * RADIUS * Math.cos(latitude) / ((double) tileSize * (1L << zoom));
    }
}
