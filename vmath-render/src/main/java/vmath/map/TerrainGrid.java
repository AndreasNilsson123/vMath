package vmath.map;

import vmath.annotations.Experimental;
import vmath.geo.TerrainRgb;

/**
 * A grid of heights over a rectangle of a map projection: the elevation tile that the terrain
 * functions of {@code vmath.map} work on.
 *
 * <p>The samples are <em>node-registered</em> as in {@link TerrainRgb}: {@code width} by {@code
 * height} values in row-major order, row 0 the north row, the first and last column and row on the
 * edges of the rectangle, so neighbouring tiles share their edge heights. The rectangle is in
 * projected metres; the heights are in metres. The size of a cell on the <em>ground</em> is its
 * projected size times {@code groundScale}: 1 for a projection that keeps distances (a UTM zone
 * near its central meridian, an azimuthal equidistant map), {@code cos(latitude)} for Web Mercator.
 * Slope and shade depend on it, and it is the caller's to give because only the caller knows where
 * the tile is. A height of {@code NaN} means no data.
 *
 * <p><b>Thread safety.</b> Immutable if nobody writes the array the grid was made from (it is not
 * copied, because tiles are large): safe to share between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * TerrainGrid grid = TerrainGrid.fromTerrainRgb(TerrainRgb.Encoding.TERRARIUM, rgb, 257, 257, minX, minY, maxX, maxY, Math.cos(lat));
 * double h = grid.heightAt(x, y);
 * }</pre>
 */
@Experimental("new in 0.2: the map layer may change")
public final class TerrainGrid {

    private final float[] heights;
    private final int width;
    private final int height;
    private final double minX;
    private final double minY;
    private final double maxX;
    private final double maxY;
    private final double groundScale;

    /**
     * Makes a grid over a float array, which is not copied.
     *
     * @param heights the heights, {@code width * height} or more values, row 0 the north row; must not be {@code null}
     * @param width the number of columns, at least 2
     * @param height the number of rows, at least 2
     * @param minX the west edge in projected metres
     * @param minY the south edge
     * @param maxX the east edge, above {@code minX}
     * @param maxY the north edge, above {@code minY}
     * @param groundScale ground metres per projected metre, positive
     * @throws IllegalArgumentException if the array is too short, a size is below 2, the rectangle is empty or not finite, or the scale is not positive
     */
    public TerrainGrid(float[] heights, int width, int height, double minX, double minY, double maxX, double maxY, double groundScale) {
        if (width < 2 || height < 2 || (long) width * height > heights.length) {
            throw new IllegalArgumentException("need at least 2 x 2 samples that fit in the array: " + width + " x " + height + " in " + heights.length);
        }
        if (!(maxX > minX) || !(maxY > minY) || !Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !(groundScale > 0.0)) {
            throw new IllegalArgumentException("need a finite rectangle with positive size and a positive ground scale");
        }
        this.heights = heights;
        this.width = width;
        this.height = height;
        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;
        this.groundScale = groundScale;
    }

    /**
     * Makes a grid from an elevation tile stored as colours.
     *
     * @param encoding the encoding of the colours
     * @param rgb {@code 3 * width * height} bytes, row 0 first
     * @param width the number of columns, at least 2
     * @param height the number of rows, at least 2
     * @param minX the west edge in projected metres
     * @param minY the south edge
     * @param maxX the east edge
     * @param maxY the north edge
     * @param groundScale ground metres per projected metre
     * @return the grid, with a new array of heights
     * @throws IllegalArgumentException as for the constructor, or if {@code rgb} is too short
     */
    public static TerrainGrid fromTerrainRgb(TerrainRgb.Encoding encoding, byte[] rgb, int width, int height, double minX, double minY, double maxX, double maxY, double groundScale) {
        if (width < 2 || height < 2 || 3L * width * height > rgb.length) {
            throw new IllegalArgumentException("need at least 2 x 2 samples that fit in the array");
        }
        float[] h = new float[width * height];
        TerrainRgb.decode(encoding, rgb, width * height, h);
        return new TerrainGrid(h, width, height, minX, minY, maxX, maxY, groundScale);
    }

    /**
     * Gives the number of columns.
     *
     * @return the count
     */
    public int width() {
        return width;
    }

    /**
     * Gives the number of rows.
     *
     * @return the count
     */
    public int height() {
        return height;
    }

    /**
     * Gives the heights without a copy.
     *
     * @return the array, row 0 the north row; do not write to it
     */
    public float[] heights() {
        return heights;
    }

    /**
     * Gives the west edge.
     *
     * @return projected metres
     */
    public double minX() {
        return minX;
    }

    /**
     * Gives the south edge.
     *
     * @return projected metres
     */
    public double minY() {
        return minY;
    }

    /**
     * Gives the east edge.
     *
     * @return projected metres
     */
    public double maxX() {
        return maxX;
    }

    /**
     * Gives the north edge.
     *
     * @return projected metres
     */
    public double maxY() {
        return maxY;
    }

    /**
     * Gives the ground scale.
     *
     * @return ground metres per projected metre
     */
    public double groundScale() {
        return groundScale;
    }

    /**
     * Gives the projected distance between neighbouring columns.
     *
     * @return projected metres
     */
    public double cellEast() {
        return (maxX - minX) / (width - 1);
    }

    /**
     * Gives the projected distance between neighbouring rows.
     *
     * @return projected metres
     */
    public double cellNorth() {
        return (maxY - minY) / (height - 1);
    }

    /**
     * Gives the x of a column.
     *
     * @param column the column, 0 to {@code width() - 1}
     * @return projected metres
     */
    public double xOf(int column) {
        return minX + column * cellEast();
    }

    /**
     * Gives the y of a row.
     *
     * @param row the row, 0 (north) to {@code height() - 1}
     * @return projected metres
     */
    public double yOf(int row) {
        return maxY - row * cellNorth();
    }

    /**
     * Gives the height of a node.
     *
     * @param column the column
     * @param row the row
     * @return metres, {@code NaN} for no data
     * @throws IndexOutOfBoundsException if the node is outside the grid
     */
    public float at(int column, int row) {
        if (column < 0 || column >= width || row < 0 || row >= height) {
            throw new IndexOutOfBoundsException("node " + column + "," + row + " of " + width + " x " + height);
        }
        return heights[row * width + column];
    }

    /**
     * Gives the height at a point by bilinear interpolation.
     *
     * @param x the projected x in metres
     * @param y the projected y in metres
     * @return metres; {@code NaN} outside the rectangle or where one of the four nodes has no data
     */
    public double heightAt(double x, double y) {
        if (!(x >= minX && x <= maxX && y >= minY && y <= maxY)) {
            return Double.NaN;
        }
        double fx = (x - minX) / cellEast(), fy = (maxY - y) / cellNorth();
        int c = Math.min(width - 2, (int) fx), r = Math.min(height - 2, (int) fy);
        double tx = fx - c, ty = fy - r;
        double h00 = heights[r * width + c], h10 = heights[r * width + c + 1], h01 = heights[(r + 1) * width + c], h11 = heights[(r + 1) * width + c + 1];
        return (h00 * (1 - tx) + h10 * tx) * (1 - ty) + (h01 * (1 - tx) + h11 * tx) * ty;
    }

    /**
     * Finds the lowest and highest height, ignoring no-data nodes.
     *
     * @param out receives the minimum at {@code out[0]} and the maximum at {@code out[1]}; both {@code NaN} if there is no data
     * @throws IllegalArgumentException if {@code out} is too short
     */
    public void range(float[] out) {
        if (out.length < 2) {
            throw new IllegalArgumentException("out must have room for 2 values");
        }
        float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < width * height; i++) {
            float h = heights[i];
            if (h == h) {
                lo = Math.min(lo, h);
                hi = Math.max(hi, h);
            }
        }
        out[0] = lo > hi ? Float.NaN : lo;
        out[1] = lo > hi ? Float.NaN : hi;
    }
}
