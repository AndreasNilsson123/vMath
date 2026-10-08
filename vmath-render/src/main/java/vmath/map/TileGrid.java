package vmath.map;

import vmath.annotations.Experimental;
import vmath.geo.MapProjection;
import vmath.geo.WebMercator;
import vmath.geo.WebMercatorProjection;

/**
 * A pyramid of square tiles over a rectangle of a map projection: zoom 0 is one tile, and every
 * zoom has twice as many tiles along each side. It describes the tile sets of web maps
 * ({@link #webMercator}, the XYZ and TMS schemes) and, with {@link #of}, the tile sets that
 * other projections use (a UTM zone, a polar stereographic cap, a national grid).
 *
 * <p>The side of a tile at zoom {@code z} is {@code (maxX - minX) / 2^z} in projected metres, so
 * the pyramid is anchored at the top left corner {@code (minX, maxY)} for the <b>XYZ</b> scheme (row
 * 0 is the northern one, as in the web maps and in {@link vmath.geo.TileId}) and at the bottom left
 * corner {@code (minX, minY)} for the <b>TMS</b> scheme (row 0 is the southern one); the rows of a
 * rectangle that is not square just continue. A tile has {@link #tileSize} pixels, so
 * {@link #resolution} is the size of a tile pixel in projected metres.
 *
 * <p>A grid that is {@code wrapX} repeats east and west (the world of a Mercator map): the column
 * numbers are taken modulo the number of tiles and a view that straddles the antimeridian sees
 * tiles of the next copy of the world ({@link FlatTileSelector} reports which).
 *
 * <p><b>Thread safety.</b> Immutable: safe to share between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * TileGrid grid = TileGrid.webMercator(256, TileGrid.Scheme.XYZ);
 * double[] box = new double[4];
 * grid.tileBounds(3, 4, 2, box);                               // projected metres of the tile 3/4/2
 * double metersPerTilePixel = grid.resolution(3);
 * }</pre>
 */
@Experimental("new in 0.2: the map layer may change")
public final class TileGrid {

    /**
     * How the rows are numbered.
     */
    public enum Scheme {
        /** Row 0 is the northern row (OpenStreetMap, Google, most web maps). */
        XYZ,
        /** Row 0 is the southern row (the Tile Map Service specification). */
        TMS
    }

    /** The largest zoom, so that the number of tiles along a side fits in an int. */
    public static final int MAX_ZOOM = 29;

    private final MapProjection projection;
    private final double minX;
    private final double minY;
    private final double maxX;
    private final double maxY;
    private final int tileSize;
    private final Scheme scheme;
    private final boolean wrapX;

    private TileGrid(MapProjection projection, double minX, double minY, double maxX, double maxY, int tileSize, Scheme scheme, boolean wrapX) {
        if (projection == null || scheme == null) {
            throw new NullPointerException("projection and scheme must not be null");
        }
        if (!(maxX > minX) || !(maxY > minY) || !Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(maxX) || !Double.isFinite(maxY) || tileSize < 1) {
            throw new IllegalArgumentException("need a finite rectangle with positive size and a tile size of at least 1: " + minX + " " + minY + " " + maxX + " " + maxY + ", " + tileSize);
        }
        this.projection = projection;
        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;
        this.tileSize = tileSize;
        this.scheme = scheme;
        this.wrapX = wrapX;
    }

    /**
     * Makes the grid of the web maps: Web Mercator, the square of {@code +-HALF_WORLD}, repeating
     * east and west.
     *
     * @param tileSize the tile size in pixels, usually 256 or 512
     * @param scheme the numbering of the rows; must not be {@code null}
     * @return the grid
     * @throws IllegalArgumentException if the tile size is below 1
     */
    public static TileGrid webMercator(int tileSize, Scheme scheme) {
        return new TileGrid(WebMercatorProjection.INSTANCE, -WebMercator.HALF_WORLD, -WebMercator.HALF_WORLD, WebMercator.HALF_WORLD, WebMercator.HALF_WORLD, tileSize, scheme, true);
    }

    /**
     * Makes a grid over a rectangle of a projection.
     *
     * @param projection the projection of the rectangle; must not be {@code null}
     * @param minX the west edge in projected metres
     * @param minY the south edge in projected metres
     * @param maxX the east edge, above {@code minX}
     * @param maxY the north edge, above {@code minY}
     * @param tileSize the tile size in pixels, at least 1
     * @param scheme the numbering of the rows; must not be {@code null}
     * @param wrapX whether the grid repeats east and west
     * @return the grid
     * @throws IllegalArgumentException if the rectangle is empty or not finite, or the tile size is below 1
     */
    public static TileGrid of(MapProjection projection, double minX, double minY, double maxX, double maxY, int tileSize, Scheme scheme, boolean wrapX) {
        return new TileGrid(projection, minX, minY, maxX, maxY, tileSize, scheme, wrapX);
    }

    /**
     * Gives the projection of the grid.
     *
     * @return the projection
     */
    public MapProjection projection() {
        return projection;
    }

    /**
     * Gives the numbering of the rows.
     *
     * @return the scheme
     */
    public Scheme scheme() {
        return scheme;
    }

    /**
     * Gives the size of a tile in pixels.
     *
     * @return pixels
     */
    public int tileSize() {
        return tileSize;
    }

    /**
     * Tells whether the grid repeats east and west.
     *
     * @return {@code true} for a world that wraps
     */
    public boolean wrapX() {
        return wrapX;
    }

    /**
     * Gives the width of the rectangle.
     *
     * @return projected metres
     */
    public double width() {
        return maxX - minX;
    }

    private static void checkZoom(int zoom) {
        if (zoom < 0 || zoom > MAX_ZOOM) {
            throw new IllegalArgumentException("the zoom must be 0 to " + MAX_ZOOM + ": " + zoom);
        }
    }

    /**
     * Gives the side of a tile.
     *
     * @param zoom the zoom, 0 to {@link #MAX_ZOOM}
     * @return projected metres
     * @throws IllegalArgumentException if the zoom is out of range
     */
    public double tileSide(int zoom) {
        checkZoom(zoom);
        return (maxX - minX) / (1L << zoom);
    }

    /**
     * Gives the size of a tile pixel in projected metres.
     *
     * @param zoom the zoom, 0 to {@link #MAX_ZOOM}
     * @return projected metres per tile pixel
     * @throws IllegalArgumentException if the zoom is out of range
     */
    public double resolution(int zoom) {
        return tileSide(zoom) / tileSize;
    }

    /**
     * Gives the zoom, fractional, at which a tile pixel is a given size.
     *
     * @param unitsPerPixel the size in projected metres, positive
     * @return {@code log2(width / (tileSize * unitsPerPixel))}
     * @throws IllegalArgumentException if the size is not positive
     */
    public double zoomFor(double unitsPerPixel) {
        if (!(unitsPerPixel > 0)) {
            throw new IllegalArgumentException("the pixel size must be positive: " + unitsPerPixel);
        }
        return Math.log((maxX - minX) / (tileSize * unitsPerPixel)) / Math.log(2.0);
    }

    /**
     * Gives the number of columns at a zoom.
     *
     * @param zoom the zoom
     * @return the number of columns, {@code 2^zoom}
     * @throws IllegalArgumentException if the zoom is out of range
     */
    public long columns(int zoom) {
        checkZoom(zoom);
        return 1L << zoom;
    }

    /**
     * Gives the number of rows at a zoom.
     *
     * @param zoom the zoom
     * @return the number of rows: the height over the side of a tile, rounded up
     * @throws IllegalArgumentException if the zoom is out of range
     */
    public long rows(int zoom) {
        return Math.max(1L, (long) Math.ceil((maxY - minY) / tileSide(zoom) - 1e-9));
    }

    /**
     * Gives the projected rectangle of a tile.
     *
     * @param zoom the zoom
     * @param x the column
     * @param y the row, numbered by the scheme
     * @param out receives {@code minX, minY, maxX, maxY} at {@code out[0..3]}
     * @throws IllegalArgumentException if {@code out} is too short or the zoom is out of range
     */
    public void tileBounds(int zoom, long x, long y, double[] out) {
        if (out.length < 4) {
            throw new IllegalArgumentException("out must have room for 4 values");
        }
        double side = tileSide(zoom);
        out[0] = minX + x * side;
        out[2] = out[0] + side;
        if (scheme == Scheme.XYZ) {
            out[3] = maxY - y * side;
            out[1] = out[3] - side;
        } else {
            out[1] = minY + y * side;
            out[3] = out[1] + side;
        }
    }

    /**
     * Finds the column that contains an x coordinate (not wrapped).
     *
     * @param zoom the zoom
     * @param x the projected x in metres
     * @return the column, which may be outside {@code [0, columns)}
     */
    public long columnOf(int zoom, double x) {
        return (long) Math.floor((x - minX) / tileSide(zoom));
    }

    /**
     * Finds the row that contains a y coordinate (not clamped).
     *
     * @param zoom the zoom
     * @param y the projected y in metres
     * @return the row by the scheme, which may be outside {@code [0, rows)}
     */
    public long rowOf(int zoom, double y) {
        double side = tileSide(zoom);
        return scheme == Scheme.XYZ ? (long) Math.floor((maxY - y) / side) : (long) Math.floor((y - minY) / side);
    }
}
