package vmath.geo;

/**
 * Codecs for heights stored as colours, the way elevation tiles are served: Terrarium and Mapbox
 * terrain-RGB, plus the small operations on a decoded height grid that a tile renderer needs.
 *
 * <p><b>Encodings.</b> A height in metres above the ellipsoid (or sea level, whichever the data set
 * uses) is turned into three 8-bit channels:
 *
 * <ul>
 *   <li>{@link Encoding#TERRARIUM}: {@code h = R * 256 + G + B / 256 - 32768}, a range of about
 *       -32768 to +32768 m with a step of 1/256 m;
 *   <li>{@link Encoding#MAPBOX}: {@code h = -10000 + (R * 65536 + G * 256 + B) * 0.1}, a range of
 *       -10000 to about +1.67 million m with a step of 0.1 m.
 * </ul>
 *
 * <p>{@code encode} rounds to the step and clamps to the range of the encoding; {@code decode} of an
 * encoded height is within half a step of it.
 *
 * <p><b>Height grids.</b> A decoded tile is a {@code float[]} in row-major order, row 0 first (the
 * north row), {@code width * height} values. The grid is <em>node-registered</em>: the samples are
 * the corners of {@code width - 1} by {@code height - 1} cells and the first and last column and
 * row lie on the edges of the tile, so two neighbouring tiles share their edge heights exactly and
 * a mesh built on the grid has no cracks along the border at the same zoom level. (Tile servers
 * usually deliver cell-centred grids; resample those to nodes before use.) {@link #sample} reads the
 * grid bilinearly at tile coordinates {@code u, v} from 0 to 1, and {@link #normal} gives a normal
 * from central differences for cells of a given ground size.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time, each with its own arrays.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * byte[] rgb = new byte[3];
 * TerrainRgb.encode(TerrainRgb.Encoding.TERRARIUM, 1234.5, rgb, 0);
 * double h = TerrainRgb.decode(TerrainRgb.Encoding.TERRARIUM, rgb[0] & 0xFF, rgb[1] & 0xFF, rgb[2] & 0xFF);   // 1234.5
 * }</pre>
 */
public final class TerrainRgb {

    /**
     * The ways to store a height in three bytes.
     */
    public enum Encoding {

        /**
         * Terrarium: {@code R * 256 + G + B / 256 - 32768}.
         */
        TERRARIUM(-32768.0, 1.0 / 256.0),

        /**
         * Mapbox terrain-RGB: {@code -10000 + (R * 65536 + G * 256 + B) * 0.1}.
         */
        MAPBOX(-10000.0, 0.1);

        private final double offset;
        private final double step;

        Encoding(double offset, double step) {
            this.offset = offset;
            this.step = step;
        }

        /**
         * The smallest height that the encoding stores.
         *
         * @return metres
         */
        public double minimum() {
            return offset;
        }

        /**
         * The largest height that the encoding stores.
         *
         * @return metres
         */
        public double maximum() {
            return offset + 0xFFFFFF * step;
        }

        /**
         * The distance between two stored heights.
         *
         * @return metres
         */
        public double step() {
            return step;
        }
    }

    private TerrainRgb() {
    }

    /**
     * Decodes one colour to a height.
     *
     * @param encoding the encoding; must not be {@code null}
     * @param r the red channel, 0 to 255
     * @param g the green channel, 0 to 255
     * @param b the blue channel, 0 to 255
     * @return the height in metres
     */
    public static double decode(Encoding encoding, int r, int g, int b) {
        if (encoding == Encoding.TERRARIUM) {
            return r * 256.0 + g + b / 256.0 - 32768.0;
        }
        return -10000.0 + (r * 65536.0 + g * 256.0 + b) * 0.1;
    }

    /**
     * Encodes a height as three bytes, rounded to the step of the encoding and clamped to its
     * range.
     *
     * @param encoding the encoding; must not be {@code null}
     * @param height the height in metres; NaN is stored as the lowest height
     * @param rgb receives the red, green and blue bytes; must not be {@code null}
     * @param offset the index of the red byte in {@code rgb}
     */
    public static void encode(Encoding encoding, double height, byte[] rgb, int offset) {
        double steps = Math.rint((height - encoding.offset) / encoding.step);
        long v = Double.isNaN(steps) ? 0 : (long) Math.max(0.0, Math.min(0xFFFFFF, steps));
        rgb[offset] = (byte) (v >> 16);
        rgb[offset + 1] = (byte) (v >> 8);
        rgb[offset + 2] = (byte) v;
    }

    /**
     * Decodes a tile of packed colours to heights.
     *
     * @param encoding the encoding; must not be {@code null}
     * @param rgb three bytes per sample, red first; must not be {@code null}
     * @param count the number of samples to decode
     * @param heights receives {@code count} heights in metres; must not be {@code null}
     */
    public static void decode(Encoding encoding, byte[] rgb, int count, float[] heights) {
        for (int i = 0; i < count; i++) {
            heights[i] = (float) decode(encoding, rgb[3 * i] & 0xFF, rgb[3 * i + 1] & 0xFF, rgb[3 * i + 2] & 0xFF);
        }
    }

    /**
     * Finds the lowest and highest height of a grid, which is what the bounding volume of a tile
     * needs.
     *
     * @param heights the grid; must not be {@code null}
     * @param count the number of values to look at, at least 1
     * @param out receives the lowest height at index 0 and the highest at index 1; must not be
     *     {@code null}
     */
    public static void minMax(float[] heights, int count, float[] out) {
        float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            lo = Math.min(lo, heights[i]);
            hi = Math.max(hi, heights[i]);
        }
        out[0] = lo;
        out[1] = hi;
    }

    /**
     * Reads a node-registered height grid bilinearly.
     *
     * @param heights the grid, row 0 first, {@code width * height} values; must not be
     *     {@code null}
     * @param width the number of columns, at least 2
     * @param height the number of rows, at least 2
     * @param u the position across the tile, 0 at the west edge and 1 at the east edge; clamped
     * @param v the position down the tile, 0 at the north edge and 1 at the south edge; clamped
     * @return the interpolated height in metres
     */
    public static float sample(float[] heights, int width, int height, double u, double v) {
        double fx = Math.max(0.0, Math.min(1.0, u)) * (width - 1);
        double fy = Math.max(0.0, Math.min(1.0, v)) * (height - 1);
        int x0 = Math.min((int) fx, width - 2), y0 = Math.min((int) fy, height - 2);
        double tx = fx - x0, ty = fy - y0;
        double top = heights[y0 * width + x0] * (1.0 - tx) + heights[y0 * width + x0 + 1] * tx;
        double bottom = heights[(y0 + 1) * width + x0] * (1.0 - tx) + heights[(y0 + 1) * width + x0 + 1] * tx;
        return (float) (top * (1.0 - ty) + bottom * ty);
    }

    /**
     * Computes the unit normal of a height grid at a node from central differences (one-sided at
     * the edges).
     *
     * <p>The result is in the tile's local frame: x east, y north, z up, so it can be rotated into
     * any frame together with the tile.
     *
     * @param heights the grid, row 0 first; must not be {@code null}
     * @param width the number of columns, at least 2
     * @param height the number of rows, at least 2
     * @param i the column of the node
     * @param j the row of the node
     * @param cellEast the ground size of a cell in the east direction, in metres, positive
     * @param cellNorth the ground size of a cell in the north direction, in metres, positive
     * @param exaggeration the factor applied to the heights before the slope is taken, 1 for none
     * @param out receives the normal (x, y, z); must not be {@code null}
     */
    public static void normal(float[] heights, int width, int height, int i, int j, double cellEast, double cellNorth, double exaggeration, double[] out) {
        int i0 = Math.max(0, i - 1), i1 = Math.min(width - 1, i + 1);
        int j0 = Math.max(0, j - 1), j1 = Math.min(height - 1, j + 1);
        double dhdx = exaggeration * (heights[j * width + i1] - heights[j * width + i0]) / ((i1 - i0) * cellEast);
        // rows count south, so the north slope is the row difference with the opposite sign
        double dhdy = exaggeration * (heights[j0 * width + i] - heights[j1 * width + i]) / ((j1 - j0) * cellNorth);
        double len = Math.sqrt(dhdx * dhdx + dhdy * dhdy + 1.0);
        out[0] = -dhdx / len;
        out[1] = -dhdy / len;
        out[2] = 1.0 / len;
    }
}
