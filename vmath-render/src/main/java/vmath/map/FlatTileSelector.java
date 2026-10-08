package vmath.map;

import java.util.Arrays;
import vmath.annotations.Experimental;
import vmath.geo.TileId;

/**
 * Chooses the map tiles that cover a 2D view at the pixel density that the view needs: the
 * counterpart for a flat map of the globe's {@code TileSelector}, which is built on a perspective
 * camera.
 *
 * <p><b>Zoom.</b> The ground size of a screen pixel decides it: the zoom whose tile pixel is not
 * larger than a screen pixel, relaxed by a <em>slack</em> (a slack of 0.25 lets a tile pixel be up to
 * 19 % larger, which is hardly seen and saves a zoom level of tiles most of the time). <b>Hysteresis</b>
 * keeps the zoom of the last call until the exact zoom has moved a given fraction of a level beyond
 * the band of that zoom, so a map that is zoomed in and out around a level boundary does not flicker
 * between two sets of tiles and does not load both.
 *
 * <p><b>Cover.</b> The tiles at that zoom that intersect the window of the view, enlarged by a
 * margin of {@code overscanPixels} (a preload of what is just about to be panned in), found exactly
 * for a view that is turned to any angle (a separating-axis test of each candidate tile against the
 * rotated window), nearest to the centre first (the order to load them in). A grid that repeats east
 * and west reports the tile and the copy of the world it belongs to ({@link #world}).
 *
 * <p><b>Budget.</b> At most {@code capacity} tiles: if the cover is larger, the zoom is reduced
 * one level at a time (a coarser, blurrier map instead of a missing one) until it fits or the
 * coarsest zoom is reached; at the coarsest zoom what does not fit is dropped, farthest first, and
 * {@link #truncated()} says so. {@link #budgetReductions()} says how many levels the budget cost.
 *
 * <p>The numbering of the tiles is that of the {@link TileGrid}: XYZ or TMS rows, columns taken
 * modulo the world for a wrapping grid.
 *
 * <p><b>Thread safety.</b> Not thread-safe: an instance keeps its result and its last zoom; use one
 * thread at a time, one instance per view.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * FlatTileSelector selector = new FlatTileSelector(TileGrid.webMercator(256, TileGrid.Scheme.XYZ), 2, 18, 128, 0.25, 0.2, 64.0);
 * int n = selector.select(view);
 * for (int i = 0; i < n; i++) {
 *     request(selector.zoom(), selector.x(i), selector.y(i), selector.world(i));
 * }
 * }</pre>
 */
@Experimental("new in 0.2: the map layer may change")
public final class FlatTileSelector {

    private final TileGrid grid;
    private final int minZoom;
    private final int maxZoom;
    private final int capacity;
    private final double slack;
    private final double hysteresis;
    private final double overscan;

    private int wantedZoom = -1;
    private int zoom;
    private double exactZoom;
    private int budgetReductions;
    private boolean truncated;
    private int count;
    private final long[] xs;
    private final long[] ys;
    private final int[] worlds;
    private long[] candX = new long[256];
    private long[] candY = new long[256];
    private int[] candWorld = new int[256];
    private double[] candDist = new double[256];
    private final double[] corners = new double[8];
    private final double[] tile = new double[4];
    private double centerX;
    private double centerY;

    /**
     * Makes a selector.
     *
     * @param grid the tile grid; must not be {@code null}
     * @param minZoom the coarsest zoom to use, 0 or more
     * @param maxZoom the finest zoom to use, at most {@link TileGrid#MAX_ZOOM}
     * @param capacity the most tiles in a result, at least 1
     * @param slack how much larger than a screen pixel a tile pixel may be, in levels of zoom, in {@code [0, 1)}
     * @param hysteresis how far in levels of zoom the exact zoom must leave the band of the current
     *     one before the zoom changes, in {@code [0, 1)}
     * @param overscanPixels the margin around the window to cover, in pixels, at least 0
     * @throws IllegalArgumentException if an argument is out of range
     */
    public FlatTileSelector(TileGrid grid, int minZoom, int maxZoom, int capacity, double slack, double hysteresis, double overscanPixels) {
        if (grid == null) {
            throw new NullPointerException("grid must not be null");
        }
        if (minZoom < 0 || maxZoom > TileGrid.MAX_ZOOM || minZoom > maxZoom || capacity < 1 || !(slack >= 0 && slack < 1) || !(hysteresis >= 0 && hysteresis < 1) || !(overscanPixels >= 0)) {
            throw new IllegalArgumentException("need 0 <= minZoom <= maxZoom <= " + TileGrid.MAX_ZOOM + ", capacity >= 1, slack and hysteresis in [0, 1), overscan >= 0");
        }
        this.grid = grid;
        this.minZoom = minZoom;
        this.maxZoom = maxZoom;
        this.capacity = capacity;
        this.slack = slack;
        this.hysteresis = hysteresis;
        this.overscan = overscanPixels;
        this.xs = new long[capacity];
        this.ys = new long[capacity];
        this.worlds = new int[capacity];
    }

    /**
     * Chooses the tiles for a view.
     *
     * @param view the view; must not be {@code null}
     * @return the number of tiles, at most the capacity
     */
    public int select(MapView2d view) {
        exactZoom = grid.zoomFor(view.mapUnitsPerPixel());
        double e = exactZoom - slack;
        int wanted = wantedZoom >= 0 && e > wantedZoom - 1 - hysteresis && e <= wantedZoom + hysteresis ? wantedZoom : (int) Math.ceil(e);
        wanted = Math.max(minZoom, Math.min(maxZoom, wanted));
        wantedZoom = wanted;
        // the window of the view in projected coordinates, enlarged by the margin
        double[] p = new double[2];
        double w = view.width(), h = view.height();
        double[][] px = {{-overscan, -overscan}, {w + overscan, -overscan}, {w + overscan, h + overscan}, {-overscan, h + overscan}};
        for (int i = 0; i < 4; i++) {
            view.screenToProjected(px[i][0], px[i][1], p);
            corners[2 * i] = p[0];
            corners[2 * i + 1] = p[1];
        }
        centerX = view.centerX();
        centerY = view.centerY();
        int z = wanted;
        budgetReductions = 0;
        int n = cover(z, true);
        while (n > capacity && z > minZoom) {
            z--;
            budgetReductions++;
            n = cover(z, true);
        }
        n = cover(z, false);
        zoom = z;
        truncated = n > capacity;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        final double[] dist = candDist;
        Arrays.sort(order, (a, b) -> Double.compare(dist[a], dist[b]));
        count = Math.min(n, capacity);
        for (int i = 0; i < count; i++) {
            xs[i] = candX[order[i]];
            ys[i] = candY[order[i]];
            worlds[i] = candWorld[order[i]];
        }
        return count;
    }

    /** Projects the window and a tile on an axis and tells whether the intervals overlap. */
    private boolean overlaps(double ax, double ay, double[] t) {
        double wmin = Double.POSITIVE_INFINITY, wmax = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            double d = corners[2 * i] * ax + corners[2 * i + 1] * ay;
            wmin = Math.min(wmin, d);
            wmax = Math.max(wmax, d);
        }
        double tmin = Double.POSITIVE_INFINITY, tmax = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            double x = (i & 1) == 0 ? t[0] : t[2], y = (i & 2) == 0 ? t[1] : t[3];
            double d = x * ax + y * ay;
            tmin = Math.min(tmin, d);
            tmax = Math.max(tmax, d);
        }
        double eps = 1e-9 * Math.max(1.0, Math.max(Math.abs(wmax), Math.abs(tmax)));
        return wmax - tmin > eps && tmax - wmin > eps;
    }

    private void add(long x, long y, int world, double dist) {
        if (count == candX.length) {
            candX = Arrays.copyOf(candX, count * 2);
            candY = Arrays.copyOf(candY, count * 2);
            candWorld = Arrays.copyOf(candWorld, count * 2);
            candDist = Arrays.copyOf(candDist, count * 2);
        }
        candX[count] = x;
        candY[count] = y;
        candWorld[count] = world;
        candDist[count] = dist;
        count++;
    }

    /** Finds the tiles of zoom z that intersect the window; stores them as candidates unless only counting. */
    private int cover(int z, boolean countOnly) {
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY, minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            minX = Math.min(minX, corners[2 * i]);
            maxX = Math.max(maxX, corners[2 * i]);
            minY = Math.min(minY, corners[2 * i + 1]);
            maxY = Math.max(maxY, corners[2 * i + 1]);
        }
        long cols = grid.columns(z), rows = grid.rows(z);
        long c0 = grid.columnOf(z, minX), c1 = grid.columnOf(z, maxX);
        long ra = grid.rowOf(z, minY), rb = grid.rowOf(z, maxY);
        long r0 = Math.max(0, Math.min(ra, rb)), r1 = Math.min(rows - 1, Math.max(ra, rb));
        if (!grid.wrapX()) {
            c0 = Math.max(0, c0);
            c1 = Math.min(cols - 1, c1);
        } else if (c1 - c0 > 4 * cols) {
            c1 = c0 + 4 * cols;   // a window wider than a few worlds: a bound on the work
        }
        // the axes of the window: x, y and its two edge directions
        double ex = corners[2] - corners[0], ey = corners[3] - corners[1], fx = corners[6] - corners[0], fy = corners[7] - corners[1];
        double el = Math.hypot(ex, ey), fl = Math.hypot(fx, fy);
        count = 0;
        int found = 0;
        for (long r = r0; r <= r1; r++) {
            for (long c = c0; c <= c1; c++) {
                long wrapped = grid.wrapX() ? Math.floorMod(c, cols) : c;
                int world = grid.wrapX() ? (int) Math.floorDiv(c, cols) : 0;
                grid.tileBounds(z, c, r, tile);
                if (!overlaps(1, 0, tile) || !overlaps(0, 1, tile) || el > 0 && !overlaps(ex / el, ey / el, tile) || fl > 0 && !overlaps(fx / fl, fy / fl, tile)) {
                    continue;
                }
                found++;
                if (!countOnly) {
                    add(wrapped, r, world, Math.hypot(0.5 * (tile[0] + tile[2]) - centerX, 0.5 * (tile[1] + tile[3]) - centerY));
                }
            }
        }
        return found;
    }

    /**
     * Gives the zoom of the tiles of the last result: the wanted zoom, less what the budget cost.
     *
     * @return the zoom
     */
    public int zoom() {
        return zoom;
    }

    /**
     * Gives the exact, fractional zoom that the last view asked for.
     *
     * @return {@code log2} of the tile pixels per screen pixel inverse, see {@link TileGrid#zoomFor}
     */
    public double exactZoom() {
        return exactZoom;
    }

    /**
     * Gives the number of tiles of the last result.
     *
     * @return the number of tiles
     */
    public int count() {
        return count;
    }

    /**
     * Gives the column of a tile of the last result.
     *
     * @param i the index, 0 for the tile nearest the centre
     * @return the column, in {@code [0, columns)} for a wrapping grid
     * @throws IndexOutOfBoundsException if there is no such tile
     */
    public long x(int i) {
        check(i);
        return xs[i];
    }

    /**
     * Gives the row of a tile of the last result, numbered by the scheme of the grid.
     *
     * @param i the index
     * @return the row
     * @throws IndexOutOfBoundsException if there is no such tile
     */
    public long y(int i) {
        check(i);
        return ys[i];
    }

    /**
     * Gives the copy of the world that a tile of the last result is in: 0 for the main one, 1 for
     * the one to the east, -1 to the west, for a grid that wraps.
     *
     * @param i the index
     * @return the copy; draw the tile shifted by this many widths of the world
     * @throws IndexOutOfBoundsException if there is no such tile
     */
    public int world(int i) {
        check(i);
        return worlds[i];
    }

    /**
     * Gives the identifier of a tile of the last result as a {@link TileId}, for a Web Mercator grid
     * in the XYZ scheme.
     *
     * @param i the index
     * @return the tile id
     * @throws IndexOutOfBoundsException if there is no such tile
     * @throws IllegalStateException if the grid is not an XYZ Web Mercator one
     */
    public TileId tileId(int i) {
        check(i);
        if (grid.scheme() != TileGrid.Scheme.XYZ || !grid.wrapX() || !(grid.projection() instanceof vmath.geo.WebMercatorProjection)) {
            throw new IllegalStateException("only an XYZ Web Mercator grid has TileId identifiers");
        }
        return new TileId(zoom, (int) xs[i], (int) ys[i]);
    }

    private void check(int i) {
        if (i < 0 || i >= count) {
            throw new IndexOutOfBoundsException("tile " + i + " of " + count);
        }
    }

    /**
     * Tells whether tiles were dropped for want of room even at the coarsest zoom.
     *
     * @return {@code true} if the result is incomplete
     */
    public boolean truncated() {
        return truncated;
    }

    /**
     * Gives the number of zoom levels that the budget cost in the last call.
     *
     * @return the number of levels, 0 if the cover fitted at the wanted zoom
     */
    public int budgetReductions() {
        return budgetReductions;
    }
}
