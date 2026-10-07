package vmath.lines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The geometry of {@link LineGeometry} against an independent definition of the same thing: the
 * stroke of Java 2D ({@link BasicStroke}), which has the semantics of SVG for caps, joins and the
 * miter limit. The triangles of {@link LineExpander} are rasterised with {@link CoverageRaster}
 * and compared sample by sample with {@code Shape.contains} on the stroked outline.
 *
 * <p>The viewport maps a world unit to a pixel, so a width is the same in both.
 */
class LineGeometryTest {

    private static final int SIZE = 160;

    private static float[] identityView() {
        float w = SIZE;
        return new float[] {2f / w, 0, 0, 0, 0, 2f / w, 0, 0, 0, 0, 1f, 0, -1f, -1f, 0f, 1f};
    }

    private static CoverageRaster raster(LineBatch batch) {
        CoverageRaster r = new CoverageRaster(SIZE, SIZE);
        LineExpander.expand(batch, identityView(), SIZE, SIZE, 1f, (x0, y0, a0, x1, y1, a1, x2, y2, a2, dash, dashCount, color) -> r.fill(x0, y0, a0, x1, y1, a1, x2, y2, a2, dash, dashCount));
        return r;
    }

    private static int bj(LineStyle.Join j) {
        return switch (j) {
            case MITER -> BasicStroke.JOIN_MITER;
            case BEVEL -> BasicStroke.JOIN_BEVEL;
            case ROUND -> BasicStroke.JOIN_ROUND;
        };
    }

    private static int bc(LineStyle.Cap c) {
        return switch (c) {
            case BUTT -> BasicStroke.CAP_BUTT;
            case SQUARE -> BasicStroke.CAP_SQUARE;
            case ROUND -> BasicStroke.CAP_ROUND;
        };
    }

    /** The samples that the stroke of Java 2D covers, flipped to the lower-left origin of the raster. */
    private static boolean[] oracle(double[] xy, boolean closed, LineStyle s) {
        Path2D.Double path = new Path2D.Double();
        for (int i = 0; i < xy.length / 2; i++) {
            if (i == 0) {
                path.moveTo(xy[0], SIZE - xy[1]);
            } else {
                path.lineTo(xy[2 * i], SIZE - xy[2 * i + 1]);
            }
        }
        if (closed) {
            path.closePath();
        }
        float[] dash = s.dashCount() > 0 ? s.dash() : null;
        Shape shape = new BasicStroke(s.width(), bc(s.cap()), bj(s.join()), s.miterLimit(), dash, 0f).createStrokedShape(path);
        boolean[] out = new boolean[SIZE * SIZE * CoverageRaster.SAMPLES];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                for (int k = 0; k < CoverageRaster.SAMPLES; k++) {
                    double sx = x + ((k % 4) + 0.5) / 4, sy = y + ((k / 4) + 0.5) / 4;
                    out[(y * SIZE + x) * CoverageRaster.SAMPLES + k] = shape.contains(sx, SIZE - sy);
                }
            }
        }
        return out;
    }

    private static double mismatch(CoverageRaster r, boolean[] expected) {
        int diff = 0, expectedCount = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                for (int k = 0; k < CoverageRaster.SAMPLES; k++) {
                    boolean e = expected[(y * SIZE + x) * CoverageRaster.SAMPLES + k];
                    expectedCount += e ? 1 : 0;
                    if (e != r.sample(x, y, k)) {
                        diff++;
                    }
                }
            }
        }
        return diff / (double) Math.max(1, expectedCount);
    }

    private static LineBatch batch(double[] xy, boolean closed, LineStyle s) {
        double[] xyz = new double[xy.length / 2 * 3];
        for (int i = 0; i < xy.length / 2; i++) {
            xyz[3 * i] = xy[2 * i];
            xyz[3 * i + 1] = xy[2 * i + 1];
        }
        LineBatch b = new LineBatch();
        b.addPolyline(xyz, 0, xy.length / 2, closed, s);
        return b;
    }

    private static void check(String what, double[] xy, boolean closed, LineStyle s, double tolerance) {
        double m = mismatch(raster(batch(xy, closed, s)), oracle(xy, closed, s));
        assertTrue(m <= tolerance, what + ": " + String.format("%.4f", m) + " of the covered samples differ from Java 2D (allowed " + tolerance + ") for " + s);
    }

    private static final double[] SEGMENT = {30, 80, 130, 80};
    private static final double[] L_SHAPE = {30, 30, 110, 30, 110, 110};
    private static final double[] ZIGZAG = {20, 40, 80, 120, 140, 40};
    private static final double[] SHARP = {20, 80, 130, 90, 20, 100};

    @Test
    void aSingleSegmentWithEveryCap() {
        for (LineStyle.Cap cap : LineStyle.Cap.values()) {
            for (float w : new float[] {1f, 4f, 13f, 30f}) {
                check("segment, " + cap, SEGMENT, false, LineStyle.pixels(w).withCap(cap), cap == LineStyle.Cap.ROUND ? 0.04 : 0.01);
            }
        }
    }

    @Test
    void aSlantedSegment() {
        for (LineStyle.Cap cap : LineStyle.Cap.values()) {
            check("slanted, " + cap, new double[] {20, 25, 130, 120}, false, LineStyle.pixels(9f).withCap(cap), cap == LineStyle.Cap.ROUND ? 0.04 : 0.01);
        }
    }

    @Test
    void everyJoinOnARightAngleAndOnAnAcuteOne() {
        for (LineStyle.Join join : LineStyle.Join.values()) {
            for (double[] pts : new double[][] {L_SHAPE, ZIGZAG}) {
                for (float w : new float[] {4f, 14f}) {
                    check("join " + join, pts, false, LineStyle.pixels(w).withJoin(join), join == LineStyle.Join.ROUND ? 0.04 : 0.012);
                }
            }
        }
    }

    @Test
    void theMiterLimitTurnsASharpMiterIntoABevel() {
        // the corner of SHARP is about 10 degrees: the miter is many times the width
        for (float limit : new float[] {1f, 2f, 4f, 10f, 40f}) {
            check("sharp corner, limit " + limit, SHARP, false, LineStyle.pixels(6f).withJoin(LineStyle.Join.MITER).withMiterLimit(limit), 0.015);
        }
    }

    @Test
    void closedPolylinesJoinAtTheStartToo() {
        double[] square = {40, 40, 120, 40, 120, 120, 40, 120};
        double[] triangle = {30, 30, 130, 40, 70, 125};
        for (LineStyle.Join join : LineStyle.Join.values()) {
            check("closed square, " + join, square, true, LineStyle.pixels(10f).withJoin(join), join == LineStyle.Join.ROUND ? 0.04 : 0.012);
            check("closed triangle, " + join, triangle, true, LineStyle.pixels(7f).withJoin(join), join == LineStyle.Join.ROUND ? 0.04 : 0.012);
        }
    }

    @Test
    void aStraightRunOfPointsHasNoSeams() {
        double[] straight = {20, 80, 50, 80, 90, 80, 140, 80};
        check("collinear", straight, false, LineStyle.pixels(12f).withJoin(LineStyle.Join.ROUND).withCap(LineStyle.Cap.ROUND), 0.04);
        check("collinear butt", straight, false, LineStyle.pixels(12f), 0.01);
    }

    @Test
    void aReversalIsRoundedOnTheFrontSide() {
        double[] back = {30, 80, 110, 80, 60, 80};
        check("reversal, round join", back, false, LineStyle.pixels(16f).withJoin(LineStyle.Join.ROUND), 0.05);
    }

    @Test
    void dashesFollowThePolylineLikeJava2D() {
        // Java 2D lets the dashes run through the corners; here they are constant over a join, so the check is on straight runs
        for (float[] dash : new float[][] {{10f, 6f}, {4f, 4f, 12f, 3f}, {20f, 5f, 3f, 5f}}) {
            check("dashed " + dash.length, SEGMENT, false, LineStyle.pixels(5f).withDash(dash), 0.02);
            check("dashed, long", new double[] {10, 30, 150, 130}, false, LineStyle.pixels(3f).withDash(dash), 0.02);
        }
    }

    @Test
    void randomPolylinesWithRandomStyles() {
        Random rnd = new Random(Long.getLong("vmath.seed", 2024L));
        int cases = 0;
        for (int rep = 0; rep < 60; rep++) {
            int n = 2 + rnd.nextInt(5);
            double[] xy = new double[2 * n];
            for (int i = 0; i < n; i++) {
                xy[2 * i] = 25 + rnd.nextInt(110);
                xy[2 * i + 1] = 25 + rnd.nextInt(110);
            }
            boolean ok = true;
            for (int i = 0; i + 1 < n; i++) {
                ok &= Math.hypot(xy[2 * i] - xy[2 * i + 2], xy[2 * i + 1] - xy[2 * i + 3]) >= 30;
            }
            if (!ok) {
                continue;
            }
            boolean closed = n >= 3 && rnd.nextBoolean();
            if (closed) {
                ok = Math.hypot(xy[0] - xy[2 * n - 2], xy[1] - xy[2 * n - 1]) >= 30;
                if (!ok) {
                    continue;
                }
            }
            LineStyle s = LineStyle.pixels(2f + rnd.nextInt(10)).withCap(LineStyle.Cap.values()[rnd.nextInt(3)]).withJoin(LineStyle.Join.values()[rnd.nextInt(3)])
                    .withMiterLimit(1f + rnd.nextInt(6));
            boolean round = s.cap() == LineStyle.Cap.ROUND || s.join() == LineStyle.Join.ROUND;
            check("random " + rep, xy, closed, s, round ? 0.06 : 0.03);
            cases++;
        }
        assertTrue(cases >= 15, "enough random cases were usable: " + cases);
    }

    @Test
    void aLongSegmentOfAThinLineCoversWhatItShould() {
        CoverageRaster r = raster(batch(new double[] {10, 80, 150, 80}, false, LineStyle.pixels(2f)));
        assertEquals(280.0, r.area(), 1.0, "140 long and 2 wide");
    }

    @Test
    void theWidthOfAWorldUnitLineFollowsThePixelsPerUnit() {
        LineBatch b = batch(new double[] {10, 80, 150, 80}, false, LineStyle.world(2f));
        for (float scale : new float[] {1f, 3f, 0.5f}) {
            CoverageRaster r = new CoverageRaster(SIZE, SIZE);
            LineExpander.expand(b, identityView(), SIZE, SIZE, scale, (x0, y0, a0, x1, y1, a1, x2, y2, a2, dash, dashCount, color) -> r.fill(x0, y0, a0, x1, y1, a1, x2, y2, a2, dash, dashCount));
            assertEquals(140.0 * 2 * scale, r.area(), 2.0 + 140 * scale * 0.02, "scale " + scale);
        }
    }

    @Test
    void aSegmentBehindTheCameraHasNoGeometry() {
        LineBatch b = new LineBatch();
        b.addPolyline(new double[] {0, 0, -5, 10, 0, 5}, 0, 2, false, LineStyle.pixels(4f));
        float[] persp = {1f, 0, 0, 0, 0, 1f, 0, 0, 0, 0, -1f, -1f, 0, 0, -0.2f, 0};
        int[] triangles = {0};
        LineExpander.expand(b, persp, 100f, 100f, 50f, (x0, y0, a0, x1, y1, a1, x2, y2, a2, dash, dashCount, color) -> triangles[0]++);
        assertEquals(0, triangles[0], "one end of the segment is at or behind the eye");
    }

    @Test
    void aZeroLengthOnScreenSegmentIsNotDrawn() {
        LineBatch b = new LineBatch();
        b.addPolyline(new double[] {10, 10, 0, 10, 10, 5}, 0, 2, false, LineStyle.pixels(4f)); // the two points differ only in z, which an orthographic view drops
        int[] triangles = {0};
        LineExpander.expand(b, identityView(), SIZE, SIZE, 1f, (x0, y0, a0, x1, y1, a1, x2, y2, a2, dash, dashCount, color) -> triangles[0]++);
        assertEquals(0, triangles[0]);
    }

    @Test
    void theOracleCanTellDifferentStylesApart() {
        double[] pts = L_SHAPE;
        LineStyle butt = LineStyle.pixels(14f), square = butt.withCap(LineStyle.Cap.SQUARE), bevel = butt.withJoin(LineStyle.Join.BEVEL);
        CoverageRaster r = raster(batch(pts, false, butt));
        assertTrue(mismatch(r, oracle(pts, false, square)) > 0.05, "square caps are not butt caps");
        assertTrue(mismatch(r, oracle(pts, false, bevel)) > 0.01, "a bevel is not a miter");
        assertTrue(mismatch(r, oracle(pts, false, butt.withWidth(10f))) > 0.1, "10 pixels are not 14");
    }
}
