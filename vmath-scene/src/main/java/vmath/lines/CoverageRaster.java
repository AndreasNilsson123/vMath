package vmath.lines;

import vmath.annotations.Experimental;

/**
 * A software rasteriser that records which parts of a small image the triangles of a line cover:
 * the oracle against which the strategies of {@link LineRenderPlan} are compared.
 *
 * <p>Every pixel has {@value #SAMPLES} samples on a regular grid (4 by 4); a sample is covered when
 * its centre is inside a triangle (edges included) and, for a dashed line, when the dash pattern
 * is "on" at the distance along the polyline that the sample interpolates from the corners of the
 * triangle. Covering twice is the same as covering once, so the result does not depend on the
 * order or the overlap of the triangles. The coordinates are pixels with the origin in the
 * lower left corner, like the screen coordinates of {@link LineGeometry}.
 *
 * <p>This is a test and reference tool, not a renderer: it is slow and has no colour.
 *
 * <p><b>Thread safety.</b> Not thread-safe.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * CoverageRaster raster = new CoverageRaster(64, 64);
 * raster.fill(10f, 10f, 0f, 50f, 10f, 0f, 10f, 50f, 0f, null, 0);
 * double area = raster.area();                   // about 800 pixels
 * }</pre>
 */
@Experimental("a test tool")
public final class CoverageRaster {

    /** The samples per pixel. */
    public static final int SAMPLES = 16;
    private static final int GRID = 4;

    private final int width;
    private final int height;
    private final boolean[] covered;

    /**
     * Creates an empty raster.
     *
     * @param width the width in pixels, at least 1
     * @param height the height in pixels, at least 1
     * @throws IllegalArgumentException if a size is below 1
     */
    public CoverageRaster(int width, int height) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("the size must be positive: " + width + " x " + height);
        }
        this.width = width;
        this.height = height;
        this.covered = new boolean[width * height * SAMPLES];
    }

    /**
     * Gives the width.
     *
     * @return the width in pixels
     */
    public int width() {
        return width;
    }

    /**
     * Gives the height.
     *
     * @return the height in pixels
     */
    public int height() {
        return height;
    }

    /**
     * Empties the raster.
     */
    public void clear() {
        java.util.Arrays.fill(covered, false);
    }

    /**
     * Tells whether the dash pattern is "on" at a distance along the line.
     *
     * @param dash the pattern in world units, "on, off, ..."; {@code null} or a count of 0 is a
     *     solid line
     * @param dashCount the numbers of the pattern
     * @param along the distance along the line
     * @return {@code true} if the line is drawn there
     */
    public static boolean dashOn(float[] dash, int dashCount, float along) {
        if (dash == null || dashCount == 0) {
            return true;
        }
        float total = 0f;
        for (int i = 0; i < dashCount; i++) {
            total += dash[i];
        }
        float m = along - total * (float) Math.floor(along / total);
        for (int i = 0; i < dashCount; i += 2) {
            if (m < dash[i]) {
                return true;
            }
            m -= dash[i];
            if (m < dash[i + 1]) {
                return false;
            }
            m -= dash[i + 1];
        }
        return true;
    }

    /**
     * Covers the samples inside a triangle.
     *
     * @param x0 the x coordinate of the first corner in pixels
     * @param y0 the y coordinate of the first corner
     * @param a0 the distance along the line at the first corner
     * @param x1 the x coordinate of the second corner
     * @param y1 the y coordinate of the second corner
     * @param a1 the distance at the second corner
     * @param x2 the x coordinate of the third corner
     * @param y2 the y coordinate of the third corner
     * @param a2 the distance at the third corner
     * @param dash the dash pattern, or {@code null} for a solid line
     * @param dashCount the numbers of the pattern
     */
    public void fill(float x0, float y0, float a0, float x1, float y1, float a1, float x2, float y2, float a2, float[] dash, int dashCount) {
        double area = (double) (x1 - x0) * (y2 - y0) - (double) (x2 - x0) * (y1 - y0);
        if (area == 0.0) {
            return;
        }
        int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = Math.min(width - 1, (int) Math.floor(Math.max(x0, Math.max(x1, x2))));
        int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = Math.min(height - 1, (int) Math.floor(Math.max(y0, Math.max(y1, y2))));
        double inv = 1.0 / area;
        for (int py = minY; py <= maxY; py++) {
            for (int px = minX; px <= maxX; px++) {
                for (int s = 0; s < SAMPLES; s++) {
                    double sx = px + ((s % GRID) + 0.5) / GRID, sy = py + ((s / GRID) + 0.5) / GRID;
                    double w0 = ((x1 - sx) * (y2 - sy) - (x2 - sx) * (y1 - sy)) * inv;
                    double w1 = ((x2 - sx) * (y0 - sy) - (x0 - sx) * (y2 - sy)) * inv;
                    double w2 = 1.0 - w0 - w1;
                    if (w0 < 0 || w1 < 0 || w2 < 0) {
                        continue;
                    }
                    if (dash != null && dashCount > 0 && !dashOn(dash, dashCount, (float) (w0 * a0 + w1 * a1 + w2 * a2))) {
                        continue;
                    }
                    covered[(py * width + px) * SAMPLES + s] = true;
                }
            }
        }
    }

    /**
     * Tells whether a sample is covered.
     *
     * @param x the pixel column
     * @param y the pixel row, from the bottom
     * @param sample the sample, 0 to {@link #SAMPLES} minus one
     * @return {@code true} if a triangle covered it
     */
    public boolean sample(int x, int y, int sample) {
        return covered[(y * width + x) * SAMPLES + sample];
    }

    /**
     * Gives the coverage of a pixel.
     *
     * @param x the pixel column
     * @param y the pixel row, from the bottom
     * @return the fraction of its samples that are covered, 0 to 1
     */
    public float coverage(int x, int y) {
        int n = 0;
        for (int s = 0; s < SAMPLES; s++) {
            if (covered[(y * width + x) * SAMPLES + s]) {
                n++;
            }
        }
        return n / (float) SAMPLES;
    }

    /**
     * Gives the covered area.
     *
     * @return the sum of the coverage of all pixels, in pixels
     */
    public double area() {
        int n = 0;
        for (boolean b : covered) {
            if (b) {
                n++;
            }
        }
        return n / (double) SAMPLES;
    }

    /**
     * Counts the samples that are covered in one raster and not in the other.
     *
     * @param other the raster to compare with; must have the same size
     * @return the number of samples that differ
     * @throws IllegalArgumentException if the sizes differ
     */
    public int differences(CoverageRaster other) {
        if (other.width != width || other.height != height) {
            throw new IllegalArgumentException("the rasters differ in size");
        }
        int n = 0;
        for (int i = 0; i < covered.length; i++) {
            if (covered[i] != other.covered[i]) {
                n++;
            }
        }
        return n;
    }

    /**
     * Counts the covered samples.
     *
     * @return the number of covered samples
     */
    public int coveredSamples() {
        int n = 0;
        for (boolean b : covered) {
            if (b) {
                n++;
            }
        }
        return n;
    }
}
