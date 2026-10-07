package vmath.samples.demos.lines;

import vmath.lines.LineStyle;

/**
 * The panel of the style demo: widths from one to thirty pixels, the nine caps and joins, sharp
 * turns with two miter limits, dash patterns on lines and on a circle, and lines with a width in
 * world units next to lines with a width in pixels. One world unit is one pixel at scale 1, y up.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Stateless.
 */
final class StyleScene {

    /** The width of the panel in world units. */
    static final double WIDTH = 1640.0;
    /** The height of the panel in world units. */
    static final double HEIGHT = 920.0;

    private static final float[] WIDTHS = {1f, 2f, 3f, 5f, 8f, 12f, 20f, 30f};
    private static final float[][] PATTERNS = {{10f, 10f}, {30f, 10f}, {20f, 6f, 2f, 6f}, {4f, 4f}};
    private static final double[] ANGLES = {10, 20, 40, 60, 90, 120, 150};

    private StyleScene() {
    }

    /** Receives the polylines of the panel and gives back a handle. */
    @FunctionalInterface
    interface Adder {
        /**
         * Adds one polyline.
         *
         * @param xyz the points as triples
         * @param pointCount the number of points
         * @param closed whether the last point is joined to the first
         * @param style the style
         * @return a handle that the caller keeps for the polylines whose style changes
         */
        long add(double[] xyz, int pointCount, boolean closed, LineStyle style);
    }

    /** The number of polylines whose dashes can follow the zoom. */
    static final int DASHED = PATTERNS.length + 1;

    /**
     * Makes the style of a dashed polyline.
     *
     * @param index 0 to 3 for the lines, 4 for the ring
     * @param pixelDashes whether the lengths are in pixels (converted with {@code pixelSize}) instead of world units
     * @param pixelSize the size of a pixel on the ground
     * @return the style
     */
    static LineStyle dashed(int index, boolean pixelDashes, float pixelSize) {
        if (index < PATTERNS.length) {
            LineStyle s = LineStyle.pixels(6f).withColor(0x6B46C1FF);
            return pixelDashes ? s.dashedInPixels(pixelSize, PATTERNS[index]) : s.withDash(PATTERNS[index]);
        }
        LineStyle ring = LineStyle.pixels(8f).withColor(0xB83280FF);
        return pixelDashes ? ring.dashedInPixels(pixelSize, 14f, 8f) : ring.withDash(14f, 8f);
    }

    /**
     * Adds the panel's polylines.
     *
     * @param adder receives them
     * @param dashedHandles receives the handles of the {@link #DASHED} dashed ones, in the order of {@link #dashed}
     */
    static void populate(Adder adder, long[] dashedHandles) {
        for (int i = 0; i < WIDTHS.length; i++) {
            line(adder, 40 + 195 * i, 870, 40 + 195 * i + 150, 870, LineStyle.pixels(WIDTHS[i]).withColor(0x2B6CB0FF).withCap(LineStyle.Cap.ROUND));
        }
        LineStyle.Cap[] caps = LineStyle.Cap.values();
        LineStyle.Join[] joins = LineStyle.Join.values();
        for (int c = 0; c < caps.length; c++) {
            for (int j = 0; j < joins.length; j++) {
                double x = 50 + 215 * c, y = 770 - 95 * j;
                LineStyle s = LineStyle.pixels(16f).withColor(0xC05621FF).withCap(caps[c]).withJoin(joins[j]);
                adder.add(new double[] {x, y, 0, x + 60, y + 50, 0, x + 100, y, 0, x + 150, y + 50, 0}, 4, false, s);
            }
        }
        double[] limits = {8.0, 1.5};
        for (int r = 0; r < limits.length; r++) {
            for (int a = 0; a < ANGLES.length; a++) {
                double x = 760 + 120 * a, y = 700 - 140 * r;
                double half = Math.toRadians(ANGLES[a] / 2);
                double len = 100;
                double[] p = {x - Math.sin(half) * len, y + Math.cos(half) * len, 0, x, y, 0, x + Math.sin(half) * len, y + Math.cos(half) * len, 0};
                adder.add(p, 3, false, LineStyle.pixels(14f).withColor(0x276749FF).withJoin(LineStyle.Join.MITER).withMiterLimit((float) limits[r]));
            }
        }
        for (int i = 0; i < PATTERNS.length; i++) {
            dashedHandles[i] = adder.add(new double[] {40, 460 - 40 * i, 0, 560, 460 - 40 * i, 0}, 2, false, dashed(i, false, 1f));
        }
        int n = 96;
        double[] circle = new double[3 * n];
        for (int k = 0; k < n; k++) {
            circle[3 * k] = 760 + 70 * Math.cos(2 * Math.PI * k / n);
            circle[3 * k + 1] = 380 + 70 * Math.sin(2 * Math.PI * k / n);
        }
        dashedHandles[PATTERNS.length] = adder.add(circle, n, true, dashed(PATTERNS.length, false, 1f));
        for (int k = 0; k < 5; k++) {
            double angle = Math.toRadians(-10 + 20 * k);
            double dx = Math.cos(angle) * 420, dy = Math.sin(angle) * 420;
            line(adder, 60, 150 + 6 * k, 60 + dx, 150 + 6 * k + dy, LineStyle.world(10f).withColor(0x2C7A7BFF).withCap(LineStyle.Cap.ROUND));
            line(adder, 700, 150 + 6 * k, 700 + dx, 150 + 6 * k + dy, LineStyle.pixels(10f).withColor(0xDD6B20FF).withCap(LineStyle.Cap.ROUND));
        }
    }

    private static void line(Adder b, double x0, double y0, double x1, double y1, LineStyle s) {
        b.add(new double[] {x0, y0, 0, x1, y1, 0}, 2, false, s);
    }
}
