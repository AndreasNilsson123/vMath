package vmath.lines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import vmath.bulk.VisibilitySet;
import vmath.gl.DrawList;
import vmath.gl.GraphicsCapabilities;
import vmath.geo.DepthRange;

/**
 * Simplification, clipping and culling of polylines: {@link LineSimplifier} against plain
 * recursive and quadratic reference implementations and the distance guarantee, {@link
 * LineClipper} against sampling along the segment, and {@link LineCulling} against what the set
 * draws.
 */
class LineProcessingTest {

    private final Random rnd = new Random(Long.getLong("vmath.seed", 31L));

    private double[] walk(int n) {
        double[] p = new double[3 * n];
        double x = 0, y = 0, z = 0;
        for (int i = 0; i < n; i++) {
            x += rnd.nextDouble() * 10 - 3;
            y += rnd.nextDouble() * 10 - 5;
            z += rnd.nextDouble() * 2 - 1;
            p[3 * i] = x;
            p[3 * i + 1] = y;
            p[3 * i + 2] = z;
        }
        return p;
    }

    // ---------------------------------------------------------------- simplification

    private static double distance(double[] p, int a, int b, int k) {
        double[] s = {p[3 * a], p[3 * a + 1], p[3 * a + 2]}, e = {p[3 * b], p[3 * b + 1], p[3 * b + 2]}, q = {p[3 * k], p[3 * k + 1], p[3 * k + 2]};
        double[] d = {e[0] - s[0], e[1] - s[1], e[2] - s[2]}, v = {q[0] - s[0], q[1] - s[1], q[2] - s[2]};
        double len2 = d[0] * d[0] + d[1] * d[1] + d[2] * d[2];
        double t = len2 > 0 ? Math.max(0, Math.min(1, (v[0] * d[0] + v[1] * d[1] + v[2] * d[2]) / len2)) : 0;
        double ex = v[0] - t * d[0], ey = v[1] - t * d[1], ez = v[2] - t * d[2];
        return Math.sqrt(ex * ex + ey * ey + ez * ez);
    }

    /** The textbook recursion. */
    private static void referenceDp(double[] p, int lo, int hi, double tol, boolean[] keep) {
        if (hi - lo < 2) {
            return;
        }
        int far = -1;
        double best = -1;
        for (int k = lo + 1; k < hi; k++) {
            double d = distance(p, lo, hi, k);
            if (d > best) {
                best = d;
                far = k;
            }
        }
        if (best > tol) {
            keep[far] = true;
            referenceDp(p, lo, far, tol, keep);
            referenceDp(p, far, hi, tol, keep);
        }
    }

    @Test
    void douglasPeuckerSelectionIsTheTextbookResultForEveryTolerance() {
        for (int rep = 0; rep < 30; rep++) {
            int n = 3 + rnd.nextInt(120);
            double[] p = walk(n);
            double[] importance = new double[n];
            LineSimplifier.douglasPeuckerImportance(p, 0, n, importance);
            for (double tol : new double[] {0, 0.3, 1, 2.5, 6, 20, 1000}) {
                boolean[] keep = new boolean[n];
                keep[0] = keep[n - 1] = true;
                referenceDp(p, 0, n - 1, tol, keep);
                int[] idx = new int[n];
                int kept = LineSimplifier.select(importance, n, tol, idx);
                List<Integer> expected = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    if (keep[i]) {
                        expected.add(i);
                    }
                }
                List<Integer> got = new ArrayList<>();
                for (int i = 0; i < kept; i++) {
                    got.add(idx[i]);
                }
                assertEquals(expected, got, "n=" + n + " tolerance " + tol);
            }
        }
    }

    @Test
    void everyRemovedPointIsWithinTheToleranceOfTheSimplifiedLine() {
        for (int rep = 0; rep < 20; rep++) {
            int n = 5 + rnd.nextInt(150);
            double[] p = walk(n);
            double tol = 0.5 + rnd.nextDouble() * 5;
            double[] out = new double[3 * n];
            int kept = LineSimplifier.douglasPeucker(p, 0, n, tol, out, 0);
            assertTrue(kept >= 2 && kept <= n);
            assertEquals(p[0], out[0]);
            assertEquals(p[3 * (n - 1)], out[3 * (kept - 1)], "the ends are kept");
            for (int k = 0; k < n; k++) {
                double nearest = Double.MAX_VALUE;
                for (int s = 0; s + 1 < kept; s++) {
                    nearest = Math.min(nearest, segmentDistance(out, s, p, k));
                }
                assertTrue(nearest <= tol + 1e-9, "point " + k + " is " + nearest + " from the line, tolerance " + tol);
            }
        }
    }

    private static double segmentDistance(double[] line, int s, double[] p, int k) {
        double[] t = {line[3 * s], line[3 * s + 1], line[3 * s + 2], line[3 * s + 3], line[3 * s + 4], line[3 * s + 5], p[3 * k], p[3 * k + 1], p[3 * k + 2]};
        return distance(t, 0, 1, 2);
    }

    @Test
    void visvalingamIsTheQuadraticReferenceAndNests() {
        for (int rep = 0; rep < 30; rep++) {
            int n = 3 + rnd.nextInt(60);
            double[] p = walk(n);
            double[] importance = new double[n];
            LineSimplifier.visvalingamImportance(p, 0, n, importance);
            // reference: repeatedly remove the point with the smallest triangle
            double[] expected = new double[n];
            expected[0] = expected[n - 1] = Double.POSITIVE_INFINITY;
            List<Integer> alive = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                alive.add(i);
            }
            double floor = 0;
            while (alive.size() > 2) {
                int bestAt = -1;
                double best = Double.MAX_VALUE;
                for (int a = 1; a < alive.size() - 1; a++) {
                    double area = area(p, alive.get(a - 1), alive.get(a), alive.get(a + 1));
                    if (area < best) {
                        best = area;
                        bestAt = a;
                    }
                }
                floor = Math.max(floor, best);
                expected[alive.get(bestAt)] = floor;
                alive.remove(bestAt);
            }
            for (int i = 0; i < n; i++) {
                assertEquals(expected[i], importance[i], 1e-9, "point " + i + " of " + n);
            }
            int[] idx = new int[n];
            int previous = n;
            for (double t : new double[] {0, 1, 5, 20, 100, 1e6}) {
                int kept = LineSimplifier.select(importance, n, t, idx);
                assertTrue(kept <= previous, "a larger tolerance keeps no more points");
                assertTrue(kept >= 2);
                previous = kept;
            }
        }
    }

    private static double area(double[] p, int i, int j, int k) {
        double ux = p[3 * j] - p[3 * i], uy = p[3 * j + 1] - p[3 * i + 1], uz = p[3 * j + 2] - p[3 * i + 2];
        double vx = p[3 * k] - p[3 * i], vy = p[3 * k + 1] - p[3 * i + 1], vz = p[3 * k + 2] - p[3 * i + 2];
        double cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx;
        return 0.5 * Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    @Test
    void collinearPointsGoFirstAndTheEdgeCasesHold() {
        double[] straight = {0, 0, 0, 1, 1, 0, 2, 2, 0, 3, 3, 0, 4, 4, 0};
        double[] importance = new double[5];
        LineSimplifier.douglasPeuckerImportance(straight, 0, 5, importance);
        int[] idx = new int[5];
        assertEquals(2, LineSimplifier.select(importance, 5, 0, idx), "collinear points are not needed at any tolerance");
        LineSimplifier.visvalingamImportance(straight, 0, 5, importance);
        assertEquals(2, LineSimplifier.select(importance, 5, 0, idx));
        double[] two = new double[2];
        LineSimplifier.douglasPeuckerImportance(new double[] {0, 0, 0, 1, 1, 1}, 0, 2, two);
        assertEquals(Double.POSITIVE_INFINITY, two[0]);
        assertEquals(Double.POSITIVE_INFINITY, two[1]);
        LineSimplifier.douglasPeuckerImportance(new double[0], 0, 0, new double[0]);
        assertEquals(0.5, LineSimplifier.worldTolerance(2, 4));
        assertThrows(IllegalArgumentException.class, () -> LineSimplifier.worldTolerance(1, 0));
        assertThrows(IllegalArgumentException.class, () -> LineSimplifier.douglasPeuckerImportance(straight, 0, 5, new double[2]));
        assertThrows(IllegalArgumentException.class, () -> LineSimplifier.douglasPeucker(straight, 0, 5, -1, new double[15], 0));
        assertThrows(IllegalArgumentException.class, () -> LineSimplifier.copySelected(straight, 0, 5, new double[] {9, 9, 9, 9, 9}, 0, new double[3], 0));
    }

    // ---------------------------------------------------------------- clipping

    private static boolean insideRect(double x, double y, double minX, double minY, double maxX, double maxY) {
        return x >= minX - 1e-9 && x <= maxX + 1e-9 && y >= minY - 1e-9 && y <= maxY + 1e-9;
    }

    @Test
    void rectangleClippingKeepsExactlyThePartInside() {
        double[] out = new double[8];
        for (int rep = 0; rep < 2000; rep++) {
            double[] s = new double[6];
            for (int i = 0; i < 6; i++) {
                s[i] = rnd.nextDouble() * 200 - 50;
            }
            boolean clipped = LineClipper.clipRectangle(s, 0, 0, 0, 100, 100, out, 0);
            // sample the segment: the t values inside the rectangle
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (int k = 0; k <= 2000; k++) {
                double t = k / 2000.0;
                if (insideRect(s[0] + t * (s[3] - s[0]), s[1] + t * (s[4] - s[1]), 0, 0, 100, 100)) {
                    lo = Math.min(lo, t);
                    hi = Math.max(hi, t);
                }
            }
            if (lo <= hi) {
                assertTrue(clipped, "samples are inside");
                assertEquals(lo, out[6], 1.0 / 2000 + 1e-9);
                assertEquals(hi, out[7], 1.0 / 2000 + 1e-9);
                assertTrue(insideRect(out[0], out[1], 0, 0, 100, 100) && insideRect(out[3], out[4], 0, 0, 100, 100));
                assertEquals(s[2] + out[6] * (s[5] - s[2]), out[2], 1e-9, "z follows the parameter");
            } else {
                assertFalse(clipped && out[7] - out[6] > 1.0 / 1000, "nothing inside, or only a sliver between two samples");
            }
        }
        assertFalse(LineClipper.clipRectangle(new double[] {-10, 50, 0, -1, 50, 0}, 0, 0, 0, 100, 100, out, 0), "parallel and outside");
        assertTrue(LineClipper.clipRectangle(new double[] {10, 50, 0, 20, 50, 0}, 0, 0, 0, 100, 100, out, 0));
        assertEquals(0, out[6]);
        assertEquals(1, out[7]);
    }

    @Test
    void boxAndPlanesAgreeWithTheRectangleAndTheBox() {
        double[] box = {0, 0, -5, 100, 100, 5};
        double[] planes = new double[24];
        double[] six = {1, 0, 0, 0, -1, 0, 0, 100, 0, 1, 0, 0, 0, -1, 0, 100, 0, 0, 1, 5, 0, 0, -1, 5};
        System.arraycopy(six, 0, planes, 0, 24);
        double[] a = new double[8], b = new double[8];
        for (int rep = 0; rep < 2000; rep++) {
            double[] s = new double[6];
            for (int i = 0; i < 6; i++) {
                s[i] = rnd.nextDouble() * 200 - 50;
            }
            s[2] = rnd.nextDouble() * 20 - 10;
            s[5] = rnd.nextDouble() * 20 - 10;
            boolean x = LineClipper.clipBox(s, 0, box, a, 0);
            boolean y = LineClipper.clipPlanes(s, 0, planes, 6, b, 0);
            assertEquals(x, y);
            if (x) {
                for (int i = 0; i < 8; i++) {
                    assertEquals(a[i], b[i], 1e-9);
                }
            }
        }
        double[] r = new double[16];
        LineClipper.rectanglePlanes(0, 0, 100, 100, r);
        double[] c = new double[8], d = new double[8];
        for (int rep = 0; rep < 500; rep++) {
            double[] s = {rnd.nextDouble() * 200 - 50, rnd.nextDouble() * 200 - 50, 0, rnd.nextDouble() * 200 - 50, rnd.nextDouble() * 200 - 50, 0};
            assertEquals(LineClipper.clipRectangle(s, 0, 0, 0, 100, 100, c, 0), LineClipper.clipPlanes(s, 0, r, 4, d, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> LineClipper.clipRectangle(new double[5], 0, 0, 0, 1, 1, new double[8], 0));
        assertThrows(IllegalArgumentException.class, () -> LineClipper.clipRectangle(new double[6], 0, 0, 0, 1, 1, new double[7], 0));
        assertThrows(IllegalArgumentException.class, () -> LineClipper.clipBox(new double[6], 0, new double[5], new double[8], 0));
    }

    private static double[] perspective(DepthRange depth) {
        double f = 1.0 / Math.tan(Math.toRadians(30)), n = 0.1, far = 100;
        double[] m = new double[16];
        m[0] = f;
        m[5] = f;
        m[11] = -1;
        switch (depth) {
            case NEGATIVE_ONE_TO_ONE -> {
                m[10] = (far + n) / (n - far);
                m[14] = 2 * far * n / (n - far);
            }
            case ZERO_TO_ONE -> {
                m[10] = far / (n - far);
                m[14] = far * n / (n - far);
            }
            case REVERSED_ZERO_TO_ONE -> {
                m[10] = n / (far - n);
                m[14] = far * n / (far - n);
            }
        }
        return m;
    }

    private static boolean insideClip(double[] m, double x, double y, double z, DepthRange depth) {
        double cx = m[0] * x + m[4] * y + m[8] * z + m[12], cy = m[1] * x + m[5] * y + m[9] * z + m[13];
        double cz = m[2] * x + m[6] * y + m[10] * z + m[14], cw = m[3] * x + m[7] * y + m[11] * z + m[15];
        double zNear = switch (depth) {
            case NEGATIVE_ONE_TO_ONE -> cz + cw;
            case ZERO_TO_ONE -> cz;
            case REVERSED_ZERO_TO_ONE -> cw - cz;
        };
        double zFar = switch (depth) {
            case NEGATIVE_ONE_TO_ONE -> cw - cz;
            case ZERO_TO_ONE -> cw - cz;
            case REVERSED_ZERO_TO_ONE -> cz;
        };
        return cw + cx >= 0 && cw - cx >= 0 && cw + cy >= 0 && cw - cy >= 0 && zNear >= 0 && zFar >= 0;
    }

    @Test
    void frustumPlanesSeparateInsideFromOutsideForEveryDepthRange() {
        for (DepthRange depth : DepthRange.values()) {
            double[] m = perspective(depth), planes = new double[24];
            LineClipper.frustumPlanes(m, depth, planes);
            int inside = 0;
            for (int rep = 0; rep < 5000; rep++) {
                double x = rnd.nextDouble() * 60 - 30, y = rnd.nextDouble() * 60 - 30, z = -(rnd.nextDouble() * 140 - 20);
                boolean viaPlanes = true;
                for (int i = 0; i < 6; i++) {
                    viaPlanes &= planes[4 * i] * x + planes[4 * i + 1] * y + planes[4 * i + 2] * z + planes[4 * i + 3] >= 0;
                }
                assertEquals(insideClip(m, x, y, z, depth), viaPlanes, depth + " at " + x + ", " + y + ", " + z);
                inside += viaPlanes ? 1 : 0;
            }
            assertTrue(inside > 100 && inside < 4900, "a mix of inside and outside points");
        }
        assertThrows(IllegalArgumentException.class, () -> LineClipper.frustumPlanes(new double[16], DepthRange.ZERO_TO_ONE, new double[23]));
    }

    @Test
    void aSegmentBehindTheCameraIsClippedToTheNearPlaneAndCanBeDrawn() {
        DepthRange depth = DepthRange.NEGATIVE_ONE_TO_ONE;
        double[] m = perspective(depth), planes = new double[24];
        LineClipper.frustumPlanes(m, depth, planes);
        double[] seg = {0, 0, -5, 0.5, 0, 5}, out = new double[8];   // from in front to behind the camera
        assertTrue(LineClipper.clipPlanes(seg, 0, planes, 6, out, 0));
        assertTrue(out[5] <= -0.1 + 1e-9, "the end is on the near plane or in front of it: z = " + out[5]);
        assertEquals(0, out[6]);
        LineBatch batch = new LineBatch();
        batch.addPolyline(new double[] {seg[0], seg[1], seg[2], seg[3], seg[4], seg[5]}, 0, 2, false, LineStyle.pixels(4f));
        float[] vp = new float[16];
        for (int i = 0; i < 16; i++) {
            vp[i] = (float) m[i];
        }
        CoverageRaster whole = new CoverageRaster(100, 100), clipped = new CoverageRaster(100, 100);
        LineExpander.expand(batch, vp, 100, 100, 50f * vp[5], (x0, y0, a0, x1, y1, a1, x2, y2, a2, dash, dashCount, color) -> whole.fill(x0, y0, a0, x1, y1, a1, x2, y2, a2, dash, dashCount));
        LineBatch cut = new LineBatch();
        cut.addPolyline(new double[] {out[0], out[1], out[2], out[3], out[4], out[5]}, 0, 2, false, LineStyle.pixels(4f));
        LineExpander.expand(cut, vp, 100, 100, 50f * vp[5], (x0, y0, a0, x1, y1, a1, x2, y2, a2, dash, dashCount, color) -> clipped.fill(x0, y0, a0, x1, y1, a1, x2, y2, a2, dash, dashCount));
        assertEquals(0, whole.area(), "the strategies drop a segment that reaches behind the camera");
        assertTrue(clipped.area() > 50, "the clipped segment is drawn");
    }

    @Test
    void polylinesAreCutIntoPiecesWithTheDistanceAlongThem() {
        double[] r = new double[16];
        LineClipper.rectanglePlanes(0, 0, 10, 10, r);
        // in, out through the right edge, back in: two pieces
        double[] line = {2, 5, 0, 8, 5, 0, 14, 5, 0, 14, 8, 0, 6, 8, 0};
        List<double[]> pieces = new ArrayList<>();
        List<Double> starts = new ArrayList<>();
        int count = LineClipper.clipPolyline(line, 0, 5, false, r, 4, (xyz, n, start) -> {
            pieces.add(java.util.Arrays.copyOf(xyz, 3 * n));
            starts.add(start);
        });
        assertEquals(2, count);
        assertEquals(3, pieces.get(0).length / 3);
        assertEquals(10, pieces.get(0)[6], 1e-12, "the first piece ends on the edge");
        assertEquals(0, starts.get(0), 1e-12);
        assertEquals(2, pieces.get(1).length / 3);
        assertEquals(10, pieces.get(1)[0], 1e-12, "the second starts on the edge");
        assertEquals(8, pieces.get(1)[1], 1e-12);
        assertEquals(6 + 6 + 3 + 4, starts.get(1), 1e-12 + 0.0, "the distance from the start of the polyline to where it comes back: 6 + 6 + 3 + 4");
        assertEquals(0, LineClipper.clipPolyline(new double[] {20, 20, 0, 30, 30, 0}, 0, 2, false, r, 4, (a, b, c) -> {
            throw new AssertionError();
        }));
        double[] square = {1, 1, 0, 9, 1, 0, 9, 9, 0, 1, 9, 0};
        assertEquals(1, LineClipper.clipPolyline(square, 0, 4, true, r, 4, (a, b, c) -> {
            assertEquals(5, b, "closed and inside: 4 segments in one piece, 5 points, the closing one included");
        }));
    }

    // ---------------------------------------------------------------- culling

    private static float[] orthographic(int size) {
        float w = size;
        return new float[] {2f / w, 0, 0, 0, 0, 2f / w, 0, 0, 0, 0, 1f, 0, -1f, -1f, 0f, 1f};
    }

    @Test
    void cullingFindsThePolylinesInsideAndTheDrawsLeaveTheOthersOut() {
        for (LineStrategy strategy : LineStrategy.values()) {
            LineRenderPlan plan = LineRenderPlan.force(strategy, GraphicsCapabilities.openGl(4, 6, List.of()));
            LineSet set = new LineSet(plan, 500);
            List<Long> handles = new ArrayList<>();
            List<Boolean> expectedVisible = new ArrayList<>();
            List<double[]> points = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                boolean inside = i % 3 != 0;
                double x = inside ? 20 + rnd.nextInt(140) : 300 + rnd.nextInt(200);   // the view is 0 to 200
                double[] pts = {x, 30 + rnd.nextInt(130), 0, x + 10 + rnd.nextInt(20), 40 + rnd.nextInt(120), 0, x + 5, 20 + rnd.nextInt(150), 0};
                handles.add(set.add(pts, 0, 3, false, LineStyle.pixels(3f).withColor(i % 2 == 0 ? 0xFF0000FF : 0x00FF00FF)));
                expectedVisible.add(inside);
                points.add(pts);
            }
            set.remove(handles.get(1));
            LineCulling culling = new LineCulling();
            VisibilitySet visible = new VisibilitySet(8);
            float[] vp = orthographic(200);
            int count = culling.cull(set, vp, DepthRange.NEGATIVE_ONE_TO_ONE, 3f, visible);
            int expectedCount = 0;
            for (int i = 0; i < handles.size(); i++) {
                if (i == 1) {
                    assertFalse(set.isSlotLive(1));
                    assertFalse(visible.get(1), "the slot of a removed polyline is never visible");
                    continue;
                }
                boolean v = visible.get(set.slot(handles.get(i)));
                if (expectedVisible.get(i)) {
                    assertTrue(v, "polyline " + i + " is inside the view");
                    expectedCount++;
                } else {
                    assertFalse(v, "polyline " + i + " is far outside the view");
                }
            }
            assertEquals(expectedCount, count);
            MemorySegment data = MemorySegment.ofArray(new byte[(int) set.dataBytes()]), styles = MemorySegment.ofArray(new byte[(int) set.styleBytes() + 64]);
            DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 4), all = new DrawList(DrawList.Kind.ARRAYS, 4);
            set.update(data, styles, draws, visible);
            int allDraws = set.update(data, styles, all);
            assertTrue(draws.size() <= allDraws);
            if (strategy != LineStrategy.HAIRLINE) {
                CoverageRaster got = new CoverageRaster(200, 200), expected = new CoverageRaster(200, 200);
                LineShaderModel.evaluate(plan, data, styles, draws, vp, 200, 200, 1f, (x0, y0, a0, x1, y1, a1, x2, y2, a2, dash, dashCount, color) -> got.fill(x0, y0, a0, x1, y1, a1, x2, y2, a2, dash, dashCount));
                LineBatch reference = new LineBatch();
                for (int i = 0; i < handles.size(); i++) {
                    if (i != 1 && expectedVisible.get(i)) {
                        reference.addPolyline(points.get(i), 0, 3, false,
                                LineStyle.pixels(3f).withColor(i % 2 == 0 ? 0xFF0000FF : 0x00FF00FF));
                    }
                }
                LineExpander.expand(reference, vp, 200, 200, 1f, (x0, y0, a0, x1, y1, a1, x2, y2, a2, dash, dashCount, color) -> expected.fill(x0, y0, a0, x1, y1, a1, x2, y2, a2, dash, dashCount));
                assertTrue(expected.area() > 100);
                assertEquals(0, expected.differences(got), strategy + ": the culled draws show what is inside");
            }
        }
    }

    @Test
    void cullingChecksItsArguments() {
        LineSet set = new LineSet(LineRenderPlan.force(LineStrategy.INSTANCED_LOOP, GraphicsCapabilities.baseline()), 10);
        LineCulling culling = new LineCulling();
        assertThrows(IllegalArgumentException.class, () -> culling.cull(set, new float[3], DepthRange.ZERO_TO_ONE, 0f, new VisibilitySet(4)));
        assertThrows(IllegalArgumentException.class, () -> culling.cull(set, new float[16], DepthRange.ZERO_TO_ONE, -1f, new VisibilitySet(4)));
        assertEquals(0, culling.cull(set, orthographic(10), DepthRange.NEGATIVE_ONE_TO_ONE, 0f, new VisibilitySet(4)), "an empty set has nothing visible");
        assertTrue(culling.kernelName().length() > 0);
        assertThrows(IndexOutOfBoundsException.class, () -> set.isSlotLive(3));
    }
}
