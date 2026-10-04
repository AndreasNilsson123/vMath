package vmath.geo;

import vmath.core.Morton;
import vmath.core.Vec2i;

/**
 * The address of a map tile in the Web Mercator pyramid: a zoom level and a column and row in the
 * grid of {@code 2^zoom} by {@code 2^zoom} tiles.
 *
 * <p><b>Numbering.</b> This is the XYZ (slippy-map) scheme: column {@code x} counts east from the
 * antimeridian at -180 degrees and row {@code y} counts south from the northern limit of the map
 * ({@link WebMercator#MAX_LATITUDE}), so tile 0/0/0 is the whole map. The TMS scheme counts rows
 * from the south; {@link #fromTms} and {@link #tmsY} convert. Zoom levels go up to {@link #MAX_ZOOM}
 * so that {@link #key()} fits in a {@code long}.
 *
 * <p><b>Keys.</b> {@link #key()} packs a tile into one {@code long} (a marker bit above the Morton
 * interleave of the column and row), which is unique over all zoom levels, orders the four children
 * of a tile next to each other and is cheap to use as a hash key. {@link #quadkey()} is the string
 * form of the same path from the root, one digit 0 to 3 per level, the Bing Maps naming.
 *
 * <p>Longitudes and latitudes of the edges are in radians; the latitudes are the geodetic latitudes
 * that {@link WebMercator} maps to rows.
 *
 * <p><b>Thread safety.</b> Immutable: instances and the static methods may be used from any number
 * of threads at the same time.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * TileId t = TileId.containing(5, Math.toRadians(18.07), Math.toRadians(59.33));   // 5/17/9
 * TileId parent = t.parent();                                                        // 4/8/4
 * double west = t.west(), north = t.north();                                         // edges in radians
 * String quadkey = t.quadkey();                                                      // "12003"
 * }</pre>
 *
 * @param zoom the zoom level, 0 to {@link #MAX_ZOOM}
 * @param x the column, 0 to {@code 2^zoom - 1}, counted from the west
 * @param y the row, 0 to {@code 2^zoom - 1}, counted from the north
 */
public record TileId(int zoom, int x, int y) {

    /**
     * The deepest zoom level that is supported, 29, at which the tiles are a few centimetres
     * wide at the equator for a 256-pixel tile.
     */
    public static final int MAX_ZOOM = 29;

    /**
     * Creates a tile address and checks it.
     *
     * @throws IllegalArgumentException if the zoom is outside {@code [0, MAX_ZOOM]} or the column
     *     or row is outside the grid of that zoom
     */
    public TileId {
        if (zoom < 0 || zoom > MAX_ZOOM) {
            throw new IllegalArgumentException("zoom " + zoom + " is outside 0.." + MAX_ZOOM);
        }
        int n = 1 << zoom;
        if (x < 0 || x >= n || y < 0 || y >= n) {
            throw new IllegalArgumentException("tile " + zoom + "/" + x + "/" + y + " is outside the grid of " + n + " by " + n);
        }
    }

    /**
     * Finds the tile of a zoom level that contains a position.
     *
     * <p>A position exactly on an edge belongs to the tile east of and south of that edge; a
     * longitude of +180 degrees and a latitude beyond the limits of the map belong to the last
     * column and the first or last row.
     *
     * @param zoom the zoom level, 0 to {@link #MAX_ZOOM}
     * @param longitude the longitude in radians
     * @param latitude the geodetic latitude in radians
     * @return the tile
     * @throws IllegalArgumentException if the zoom is out of range
     */
    public static TileId containing(int zoom, double longitude, double latitude) {
        int n = 1 << zoom;
        int x = (int) Math.min(n - 1, Math.max(0, Math.floor(WebMercator.u(longitude) * n)));
        int y = (int) Math.min(n - 1, Math.max(0, Math.floor(WebMercator.v(latitude) * n)));
        return new TileId(zoom, x, y);
    }

    /**
     * Builds a tile from TMS numbering, where rows count from the south.
     *
     * @param zoom the zoom level, 0 to {@link #MAX_ZOOM}
     * @param x the column, counted from the west
     * @param tmsY the row counted from the south
     * @return the tile in XYZ numbering
     * @throws IllegalArgumentException if the address is outside the grid
     */
    public static TileId fromTms(int zoom, int x, int tmsY) {
        return new TileId(zoom, x, (1 << zoom) - 1 - tmsY);
    }

    /**
     * Reads a tile from the packed form of {@link #key()}.
     *
     * @param key a value that {@link #key()} returned
     * @return the tile
     * @throws IllegalArgumentException if the value is not a key
     */
    public static TileId fromKey(long key) {
        if (key < 1) {
            throw new IllegalArgumentException("not a tile key: " + key);
        }
        int zoom = (63 - Long.numberOfLeadingZeros(key)) / 2;
        Vec2i xy = Morton.decode2(key & ((1L << (2 * zoom)) - 1));
        return new TileId(zoom, xy.x(), xy.y());
    }

    /**
     * Reads a tile from its quadkey.
     *
     * @param quadkey digits 0 to 3, one per level, the empty string for the whole map; must not be
     *     {@code null}
     * @return the tile
     * @throws IllegalArgumentException if the string is longer than {@link #MAX_ZOOM} or has a
     *     character other than 0 to 3
     */
    public static TileId fromQuadkey(String quadkey) {
        int zoom = quadkey.length();
        if (zoom > MAX_ZOOM) {
            throw new IllegalArgumentException("quadkey longer than " + MAX_ZOOM);
        }
        int x = 0, y = 0;
        for (int i = 0; i < zoom; i++) {
            int d = quadkey.charAt(i) - '0';
            if (d < 0 || d > 3) {
                throw new IllegalArgumentException("not a quadkey digit: " + quadkey.charAt(i));
            }
            x = (x << 1) | (d & 1);
            y = (y << 1) | (d >> 1);
        }
        return new TileId(zoom, x, y);
    }

    /**
     * Converts the row to TMS numbering.
     *
     * @return the row counted from the south
     */
    public int tmsY() {
        return (1 << zoom) - 1 - y;
    }

    /**
     * Packs the tile into one number that is unique over all zoom levels.
     *
     * <p>The number is a marker bit at position {@code 2 zoom} above the Morton interleave of the
     * column and the row, so it is positive, and {@link #fromKey} reverses it.
     *
     * @return the key
     */
    public long key() {
        return (1L << (2 * zoom)) | Morton.encode2(x, y);
    }

    /**
     * Writes the tile as a quadkey: the path from the root, a digit 0 to 3 per level, where the
     * low bit of a digit is the column bit and the high bit the row bit.
     *
     * @return the string, empty for tile 0/0/0
     */
    public String quadkey() {
        char[] c = new char[zoom];
        for (int i = 0; i < zoom; i++) {
            int bit = zoom - 1 - i;
            c[i] = (char) ('0' + (((x >> bit) & 1) | (((y >> bit) & 1) << 1)));
        }
        return new String(c);
    }

    /**
     * Gives the tile that contains this one one zoom level up.
     *
     * @return the parent
     * @throws IllegalStateException if this is the root tile
     */
    public TileId parent() {
        if (zoom == 0) {
            throw new IllegalStateException("the root tile has no parent");
        }
        return new TileId(zoom - 1, x >> 1, y >> 1);
    }

    /**
     * Gives one of the four tiles that this one is divided into one zoom level down.
     *
     * @param index 0 north-west, 1 north-east, 2 south-west, 3 south-east (the quadkey digit)
     * @return the child
     * @throws IllegalArgumentException if the index is not 0 to 3 or this tile is at
     *     {@link #MAX_ZOOM}
     */
    public TileId child(int index) {
        if (index < 0 || index > 3) {
            throw new IllegalArgumentException("child index " + index);
        }
        return new TileId(zoom + 1, (x << 1) | (index & 1), (y << 1) | (index >> 1));
    }

    /**
     * Gives the tile next to this one at the same zoom level.
     *
     * <p>The map wraps around in the east-west direction, so the column is taken modulo the grid
     * width; the map ends in the north-south direction.
     *
     * @param dx the number of columns to the east (negative to the west)
     * @param dy the number of rows to the south (negative to the north)
     * @return the neighbour, or {@code null} beyond the northern or southern edge of the map
     */
    public TileId neighbour(int dx, int dy) {
        int n = 1 << zoom;
        int ny = y + dy;
        if (ny < 0 || ny >= n) {
            return null;
        }
        return new TileId(zoom, Math.floorMod(x + dx, n), ny);
    }

    /**
     * The longitude of the west edge.
     *
     * @return radians in {@code [-pi, pi)}
     */
    public double west() {
        return WebMercator.longitudeOfU((double) x / (1 << zoom));
    }

    /**
     * The longitude of the east edge.
     *
     * @return radians in {@code (-pi, pi]}
     */
    public double east() {
        return WebMercator.longitudeOfU((double) (x + 1) / (1 << zoom));
    }

    /**
     * The latitude of the north edge.
     *
     * @return the geodetic latitude in radians
     */
    public double north() {
        return WebMercator.latitudeOfV((double) y / (1 << zoom));
    }

    /**
     * The latitude of the south edge.
     *
     * @return the geodetic latitude in radians
     */
    public double south() {
        return WebMercator.latitudeOfV((double) (y + 1) / (1 << zoom));
    }

    /**
     * The longitude of the middle of the tile.
     *
     * @return radians
     */
    public double centerLongitude() {
        return WebMercator.longitudeOfU((x + 0.5) / (1 << zoom));
    }

    /**
     * The latitude of the middle of the tile in the projection, which is where the tile's rows are
     * halved and is a little closer to the equator than the average of the edges.
     *
     * @return the geodetic latitude in radians
     */
    public double centerLatitude() {
        return WebMercator.latitudeOfV((y + 0.5) / (1 << zoom));
    }

    /**
     * Tests whether a position is inside the tile, edges included.
     *
     * @param longitude the longitude in radians
     * @param latitude the geodetic latitude in radians
     * @return whether the position is within the tile's longitude and latitude range
     */
    public boolean contains(double longitude, double latitude) {
        return longitude >= west() && longitude <= east() && latitude >= south() && latitude <= north();
    }
}
