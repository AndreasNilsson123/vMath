package vmath.map;

import vmath.annotations.Experimental;

/**
 * Hillshade and slope of a {@link TerrainGrid} by Horn's method: the gradient at a node from its
 * eight neighbours, the weights 1-2-1 along the sides, as GDAL and every GIS do it.
 *
 * <p>With the cell sizes {@code dx}, {@code dy} on the ground and the neighbours {@code a b c / d e f
 * / g h i} (row 0 the north row), {@code dz/dx = ((c + 2f + i) - (a + 2d + g)) / (8 dx)} and
 * {@code dz/dy = ((a + 2b + c) - (g + 2h + i)) / (8 dy)} (northwards), the normal is
 * {@code (-k dz/dx, -k dz/dy, 1)} for a vertical exaggeration {@code k}, and the shade is the
 * cosine between it and the direction of the light, from 0 (facing away) to 1 (facing the light).
 * The light is given by its <em>azimuth</em> (the direction it comes from, clockwise from north) and
 * its <em>altitude</em> above the horizon. A flat node therefore has the shade {@code sin(altitude)}.
 * At the edge of the grid the missing neighbours repeat the nearest node, and a neighbour with no
 * data repeats the node; a node with no data has no shade ({@code NaN}).
 *
 * <p>The same arithmetic is in the GLSL of {@link TerrainShader}.
 *
 * <p><b>Thread safety.</b> Stateless: safe to call from any number of threads that write to
 * different arrays.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * float[] shade = new float[grid.width() * grid.height()];
 * Hillshade.shade(grid, Math.toRadians(315), Math.toRadians(45), 1.0, shade);       // the light from the north west
 * }</pre>
 */
@Experimental("new in 0.2: the map layer may change")
public final class Hillshade {

    private Hillshade() {
    }

    private static float node(float[] h, int w, int hh, int c, int r, float centre) {
        float v = h[Math.min(hh - 1, Math.max(0, r)) * w + Math.min(w - 1, Math.max(0, c))];
        return v == v ? v : centre;
    }

    /**
     * Computes the gradient at a node.
     *
     * @param grid the grid; must not be {@code null}
     * @param column the column of the node
     * @param row the row of the node
     * @param out receives {@code dz/dx} (eastwards) at {@code out[0]} and {@code dz/dy} (northwards)
     *     at {@code out[1]}, metres per ground metre; both {@code NaN} where the node has no data
     * @throws IndexOutOfBoundsException if the node is outside the grid
     * @throws IllegalArgumentException if {@code out} is too short
     */
    public static void gradient(TerrainGrid grid, int column, int row, double[] out) {
        if (out.length < 2) {
            throw new IllegalArgumentException("out must have room for 2 values");
        }
        float e = grid.at(column, row);
        if (e != e) {
            out[0] = Double.NaN;
            out[1] = Double.NaN;
            return;
        }
        float[] h = grid.heights();
        int w = grid.width(), hh = grid.height();
        float a = node(h, w, hh, column - 1, row - 1, e), b = node(h, w, hh, column, row - 1, e), c = node(h, w, hh, column + 1, row - 1, e);
        float d = node(h, w, hh, column - 1, row, e), f = node(h, w, hh, column + 1, row, e);
        float g = node(h, w, hh, column - 1, row + 1, e), hn = node(h, w, hh, column, row + 1, e), i = node(h, w, hh, column + 1, row + 1, e);
        double dx = grid.cellEast() * grid.groundScale(), dy = grid.cellNorth() * grid.groundScale();
        out[0] = ((c + 2f * f + i) - (a + 2f * d + g)) / (8.0 * dx);
        out[1] = ((a + 2f * b + c) - (g + 2f * hn + i)) / (8.0 * dy);
    }

    /**
     * Computes the hillshade of every node.
     *
     * @param grid the grid; must not be {@code null}
     * @param azimuth the direction the light comes from, in radians clockwise from north
     * @param altitude the height of the light above the horizon in radians, 0 to pi/2
     * @param exaggeration the vertical exaggeration, positive
     * @param out receives {@code width * height} values from 0 to 1 in the order of the grid; {@code NaN} where there is no data
     * @throws IllegalArgumentException if {@code out} is too short, the altitude is outside 0 to pi/2 or the exaggeration is not positive
     */
    public static void shade(TerrainGrid grid, double azimuth, double altitude, double exaggeration, float[] out) {
        if (out.length < grid.width() * grid.height()) {
            throw new IllegalArgumentException("out must have room for " + grid.width() * grid.height() + " values");
        }
        if (!(altitude >= 0.0 && altitude <= Math.PI / 2) || !(exaggeration > 0.0)) {
            throw new IllegalArgumentException("the altitude must be 0 to pi/2 and the exaggeration positive: " + altitude + ", " + exaggeration);
        }
        double[] sun = sunVector(azimuth, altitude);
        double[] g = new double[2];
        for (int r = 0; r < grid.height(); r++) {
            for (int c = 0; c < grid.width(); c++) {
                gradient(grid, c, r, g);
                out[r * grid.width() + c] = (float) shadeOf(g[0], g[1], exaggeration, sun);
            }
        }
    }

    /**
     * Gives the unit vector towards the light.
     *
     * @param azimuth the direction the light comes from, radians clockwise from north
     * @param altitude the height above the horizon, radians
     * @return {@code {east, north, up}}
     */
    public static double[] sunVector(double azimuth, double altitude) {
        return new double[] {Math.cos(altitude) * Math.sin(azimuth), Math.cos(altitude) * Math.cos(azimuth), Math.sin(altitude)};
    }

    /**
     * Gives the shade of a gradient.
     *
     * @param dzdx the eastward gradient
     * @param dzdy the northward gradient
     * @param exaggeration the vertical exaggeration
     * @param sun the unit vector towards the light from {@link #sunVector}
     * @return 0 to 1; {@code NaN} if a gradient is {@code NaN}
     */
    public static double shadeOf(double dzdx, double dzdy, double exaggeration, double[] sun) {
        double nx = -exaggeration * dzdx, ny = -exaggeration * dzdy;
        double inv = 1.0 / Math.sqrt(nx * nx + ny * ny + 1.0);
        return Math.max(0.0, (nx * sun[0] + ny * sun[1] + sun[2]) * inv);
    }

    /**
     * Computes the slope of every node.
     *
     * @param grid the grid; must not be {@code null}
     * @param out receives {@code width * height} slopes in radians above the horizontal, 0 to pi/2; {@code NaN} where there is no data
     * @throws IllegalArgumentException if {@code out} is too short
     */
    public static void slope(TerrainGrid grid, float[] out) {
        if (out.length < grid.width() * grid.height()) {
            throw new IllegalArgumentException("out must have room for " + grid.width() * grid.height() + " values");
        }
        double[] g = new double[2];
        for (int r = 0; r < grid.height(); r++) {
            for (int c = 0; c < grid.width(); c++) {
                gradient(grid, c, r, g);
                out[r * grid.width() + c] = (float) Math.atan(Math.hypot(g[0], g[1]));
            }
        }
    }

    /**
     * Computes the aspect of every node: the compass direction the slope faces.
     *
     * @param grid the grid; must not be {@code null}
     * @param out receives {@code width * height} bearings in radians clockwise from north, 0 to 2 pi
     *     (the direction downhill); {@code NaN} where there is no data or the node is flat
     * @throws IllegalArgumentException if {@code out} is too short
     */
    public static void aspect(TerrainGrid grid, float[] out) {
        if (out.length < grid.width() * grid.height()) {
            throw new IllegalArgumentException("out must have room for " + grid.width() * grid.height() + " values");
        }
        double[] g = new double[2];
        for (int r = 0; r < grid.height(); r++) {
            for (int c = 0; c < grid.width(); c++) {
                gradient(grid, c, r, g);
                if (!(g[0] == g[0]) || g[0] == 0.0 && g[1] == 0.0) {
                    out[r * grid.width() + c] = Float.NaN;
                } else {
                    double a = Math.atan2(-g[0], -g[1]);              // downhill is against the gradient
                    out[r * grid.width() + c] = (float) ((a < 0 ? a + 2 * Math.PI : a) + 0.0);
                }
            }
        }
    }
}
