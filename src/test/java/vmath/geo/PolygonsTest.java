package vmath.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.Report;
import vmath.core.Predicates;
import vmath.core.Rnd;

/**
 * {@link Polygons}: orientation, containment, simplicity, ear-clipping triangulation (with holes and in 3D) and clipping. The triangulation is checked without a second
 * triangulator: the triangles must be counter-clockwise (exact predicate), their areas must add up to the area of the region, no vertex may lie strictly inside a triangle,
 * and random sample points of the region must each fall in exactly one triangle.
 */
class PolygonsTest {

    private final SplittableRandom r = new SplittableRandom(Rnd.SEED);

    // ---------------------------------------------------------------- generators

    /** A star-shaped (hence simple) polygon around the origin: sorted angles, random radii. */
    private float[] star(int n, double minRadius, double maxRadius, boolean ccw) {
        double[] angles = new double[n];
        for (int i = 0; i < n; i++) {
            angles[i] = (i + r.nextDouble() * 0.4) * 2 * Math.PI / n;
        }
        float[] xy = new float[2 * n];
        for (int i = 0; i < n; i++) {
            int k = ccw ? i : n - 1 - i;
            double rad = minRadius + r.nextDouble() * (maxRadius - minRadius);
            xy[2 * k] = (float) (Math.cos(angles[i]) * rad);
            xy[2 * k + 1] = (float) (Math.sin(angles[i]) * rad);
        }
        return xy;
    }

    private static float[] regular(int n, double radius, double cx, double cy, boolean ccw) {
        float[] xy = new float[2 * n];
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n * (ccw ? 1 : -1);
            xy[2 * i] = (float) (cx + Math.cos(a) * radius);
            xy[2 * i + 1] = (float) (cy + Math.sin(a) * radius);
        }
        return xy;
    }

    private static double area(float[] xy, int a, int b, int c) {
        return 0.5 * (((double) xy[2 * b] - xy[2 * a]) * ((double) xy[2 * c + 1] - xy[2 * a + 1]) - ((double) xy[2 * c] - xy[2 * a]) * ((double) xy[2 * b + 1] - xy[2 * a + 1]));
    }

    /** Checks a triangulation of the region bounded by the rings and returns the number of triangles; the area of the region is the outer area minus the holes. */
    private int checkTriangulation(float[] xy, int[] ringEnds, int[] tris, int triangleCount, String what) {
        assertTrue(triangleCount > 0, what + ": no triangles");
        double sum = 0;
        for (int t = 0; t < triangleCount; t++) {
            int a = tris[3 * t], b = tris[3 * t + 1], c = tris[3 * t + 2];
            assertTrue(Predicates.orient2d(xy[2 * a], xy[2 * a + 1], xy[2 * b], xy[2 * b + 1], xy[2 * c], xy[2 * c + 1]) > 0, what + ": triangle " + t + " is not counter-clockwise");
            sum += area(xy, a, b, c);
        }
        double region = 0;
        int start = 0;
        for (int ring = 0; ring < ringEnds.length; ring++) {
            float[] pts = java.util.Arrays.copyOfRange(xy, 2 * start, 2 * ringEnds[ring]);
            double a = Math.abs(Polygons.signedArea(pts, ringEnds[ring] - start));
            region += ring == 0 ? a : -a;
            start = ringEnds[ring];
        }
        assertEquals(region, sum, 1e-6 * Math.max(1.0, region), what + ": the triangle areas must add up to the region");
        // no vertex strictly inside a triangle (it would be a T-junction or an overlap)
        int total = ringEnds[ringEnds.length - 1];
        for (int t = 0; t < triangleCount; t++) {
            int a = tris[3 * t], b = tris[3 * t + 1], c = tris[3 * t + 2];
            for (int v = 0; v < total; v++) {
                if (v == a || v == b || v == c) {
                    continue;
                }
                boolean inside = Predicates.orient2d(xy[2 * a], xy[2 * a + 1], xy[2 * b], xy[2 * b + 1], xy[2 * v], xy[2 * v + 1]) > 0
                        && Predicates.orient2d(xy[2 * b], xy[2 * b + 1], xy[2 * c], xy[2 * c + 1], xy[2 * v], xy[2 * v + 1]) > 0
                        && Predicates.orient2d(xy[2 * c], xy[2 * c + 1], xy[2 * a], xy[2 * a + 1], xy[2 * v], xy[2 * v + 1]) > 0;
                assertFalse(inside, what + ": vertex " + v + " is strictly inside triangle " + t);
            }
        }
        return triangleCount;
    }

    private void checkCoverage(float[] xy, int[] ringEnds, int[] tris, int triangleCount, double extent, String what) {
        int sampled = 0;
        for (int s = 0; s < 3000; s++) {
            float px = (float) ((r.nextDouble() * 2 - 1) * extent), py = (float) ((r.nextDouble() * 2 - 1) * extent);
            boolean in = Polygons.contains(java.util.Arrays.copyOfRange(xy, 0, 2 * ringEnds[0]), ringEnds[0], px, py);
            boolean boundary = false;
            int start = ringEnds[0];
            for (int ring = 1; ring < ringEnds.length; ring++) {
                float[] hole = java.util.Arrays.copyOfRange(xy, 2 * start, 2 * ringEnds[ring]);
                if (Polygons.contains(hole, ringEnds[ring] - start, px, py)) {
                    in = false;
                }
                start = ringEnds[ring];
            }
            int hits = 0, touches = 0;
            for (int t = 0; t < triangleCount; t++) {
                int a = tris[3 * t], b = tris[3 * t + 1], c = tris[3 * t + 2];
                double o1 = Predicates.orient2d(xy[2 * a], xy[2 * a + 1], xy[2 * b], xy[2 * b + 1], px, py);
                double o2 = Predicates.orient2d(xy[2 * b], xy[2 * b + 1], xy[2 * c], xy[2 * c + 1], px, py);
                double o3 = Predicates.orient2d(xy[2 * c], xy[2 * c + 1], xy[2 * a], xy[2 * a + 1], px, py);
                if (o1 > 0 && o2 > 0 && o3 > 0) {
                    hits++;
                } else if (o1 >= 0 && o2 >= 0 && o3 >= 0) {
                    touches++;
                }
            }
            if (touches > 0) {
                continue; // a sample exactly on a triangle edge: skip
            }
            assertEquals(in ? 1 : 0, hits, what + ": point " + px + "," + py + " must be in " + (in ? "exactly one triangle" : "no triangle"));
            sampled++;
        }
        assertTrue(sampled > 2000, what + ": too few samples");
    }

    // ---------------------------------------------------------------- orientation, area, containment, simplicity

    @Test
    void areaWindingContainmentOfASquare() {
        float[] ccw = {0, 0, 4, 0, 4, 3, 0, 3};
        float[] cw = {0, 0, 0, 3, 4, 3, 4, 0};
        assertEquals(12.0, Polygons.signedArea(ccw, 4));
        assertEquals(-12.0, Polygons.signedArea(cw, 4));
        assertEquals(1, Polygons.winding(ccw, 4));
        assertEquals(-1, Polygons.winding(cw, 4));
        assertEquals(0, Polygons.winding(new float[] {0, 0, 1, 1, 2, 2}, 3), "collinear");
        assertEquals(0, Polygons.winding(ccw, 2));
        for (float[] p : new float[][] {ccw, cw}) {
            assertTrue(Polygons.contains(p, 4, 1, 1));
            assertTrue(Polygons.contains(p, 4, 0, 0), "a vertex is on the boundary");
            assertTrue(Polygons.contains(p, 4, 2, 0), "a point on an edge");
            assertTrue(Polygons.contains(p, 4, 4, 1.5f));
            assertFalse(Polygons.contains(p, 4, -0.001f, 1));
            assertFalse(Polygons.contains(p, 4, 2, 3.001f));
            assertFalse(Polygons.contains(p, 4, 5, 5));
        }
        // a concave "L"
        float[] ell = {0, 0, 4, 0, 4, 1, 1, 1, 1, 4, 0, 4};
        assertTrue(Polygons.contains(ell, 6, 0.5f, 3.5f));
        assertFalse(Polygons.contains(ell, 6, 2, 2));
        assertTrue(Polygons.contains(ell, 6, 1, 2), "on the inner edge");
    }

    @Test
    void windingIsExactWhereAnAreaSumIsNot() {
        // a long thin triangle far from the origin: the shoelace sum of its three terms has rounding errors larger than its area
        float[] thin = {100000f, 100000f, 100000.01f, 100000.01f, 100000.02f, 100000.0f};
        int w = Polygons.winding(thin, 3);
        double o = Predicates.orient2d(thin[0], thin[1], thin[2], thin[3], thin[4], thin[5]);
        assertEquals(o > 0 ? 1 : -1, w);
        float[] reversed = {thin[4], thin[5], thin[2], thin[3], thin[0], thin[1]};
        assertEquals(-w, Polygons.winding(reversed, 3));
    }

    @Test
    void randomStarsHaveTheRightWindingAndContainTheirCentre() {
        for (int i = 0; i < 500; i++) {
            int n = 3 + r.nextInt(40);
            boolean ccw = r.nextBoolean();
            float[] xy = star(n, 1, 3, ccw);
            assertEquals(ccw ? 1 : -1, Polygons.winding(xy, n));
            assertTrue(Polygons.isSimple(xy, n), "a star is simple");
            assertTrue(Polygons.contains(xy, n, 0f, 0f), "the centre of a star");
            assertFalse(Polygons.contains(xy, n, 3.5f, 0f));
            assertEquals(ccw, Polygons.signedArea(xy, n) > 0);
        }
    }

    @Test
    void simplicityTestFindsCrossingsTouchesAndRepeats() {
        assertTrue(Polygons.isSimple(new float[] {0, 0, 1, 0, 1, 1, 0, 1}, 4));
        assertFalse(Polygons.isSimple(new float[] {0, 0, 1, 1, 1, 0, 0, 1}, 4), "a bow tie crosses itself");
        assertFalse(Polygons.isSimple(new float[] {0, 0, 2, 0, 2, 2, 1, 0, 0, 2}, 5), "a vertex on another edge");
        assertFalse(Polygons.isSimple(new float[] {0, 0, 1, 0, 1, 0, 1, 1, 0, 1}, 5), "a repeated vertex");
        assertFalse(Polygons.isSimple(new float[] {0, 0, 2, 0, 1, 0}, 3), "a spike folding back along the same line");
        assertFalse(Polygons.isSimple(new float[] {0, 0, 1, 0}, 2));
    }

    // ---------------------------------------------------------------- triangulation

    @Test
    void trianglesAndConvexPolygons() {
        int[] out = new int[30];
        assertEquals(1, Polygons.triangulate(new float[] {0, 0, 1, 0, 0, 1}, 3, out));
        assertEquals(1, Polygons.triangulate(new float[] {0, 0, 0, 1, 1, 0}, 3, out), "a clockwise triangle comes out counter-clockwise");
        float[] hex = regular(6, 2, 0, 0, true);
        assertEquals(4, Polygons.triangulate(hex, 6, out));
        assertEquals(-1, Polygons.triangulate(new float[] {0, 0, 1, 0}, 2, out));
        assertEquals(-1, Polygons.triangulate(new float[] {0, 0, 1, 1, 2, 2, 3, 3}, 4, out), "no area");
    }

    @Test
    void randomSimplePolygonsTriangulateCorrectly() {
        int cases = Math.max(400, Rnd.N / 5);
        for (int i = 0; i < cases; i++) {
            int n = 3 + r.nextInt(60);
            boolean ccw = r.nextBoolean();
            float[] xy = star(n, 0.2, 3, ccw);
            int[] out = new int[3 * (n - 2)];
            int count = Polygons.triangulate(xy, n, out);
            assertEquals(n - 2, count, "a polygon without collinear vertices has n - 2 triangles, case " + i);
            checkTriangulation(xy, new int[] {n}, out, count, "star " + i);
            checkCoverage(xy, new int[] {n}, out, count, 3.2, "star " + i);
        }
    }

    @Test
    void combsAndOtherConcavePolygons() {
        // a comb: a long base with k teeth, many reflex vertices
        for (int k = 2; k <= 30; k += 7) {
            int n = 4 * k + 2;
            float[] xy = new float[2 * n];
            int v = 0;
            xy[v++] = 0;
            xy[v++] = 0;
            xy[v++] = 2 * k;
            xy[v++] = 0;
            for (int t = k - 1; t >= 0; t--) { // walking back along the top, tooth by tooth
                xy[v++] = 2 * t + 2;
                xy[v++] = 3;
                xy[v++] = 2 * t + 2;
                xy[v++] = 1;
                xy[v++] = 2 * t + 1;
                xy[v++] = 1;
                xy[v++] = 2 * t + 1;
                xy[v++] = 3;
            }
            int vertices = v / 2;
            xy = java.util.Arrays.copyOf(xy, v);
            xy[v - 2] = 0;
            xy[v - 1] = 3;
            int[] out = new int[3 * vertices];
            int count = Polygons.triangulate(xy, vertices, out);
            assertTrue(count > 0, "comb " + k);
            if (Polygons.isSimple(xy, vertices)) {
                checkTriangulation(xy, new int[] {vertices}, out, count, "comb " + k);
            }
        }
    }

    @Test
    void collinearVerticesAreDroppedWithoutChangingTheArea() {
        // a square with an extra vertex in the middle of each side
        float[] xy = {0, 0, 2, 0, 4, 0, 4, 2, 4, 4, 2, 4, 0, 4, 0, 2};
        int[] out = new int[30];
        int count = Polygons.triangulate(xy, 8, out);
        assertEquals(2, count);
        double sum = 0;
        for (int t = 0; t < count; t++) {
            sum += area(xy, out[3 * t], out[3 * t + 1], out[3 * t + 2]);
        }
        assertEquals(16.0, sum, 1e-9);
    }

    @Test
    void polygonsWithHolesTriangulate() {
        int cases = Math.max(200, Rnd.N / 10);
        for (int i = 0; i < cases; i++) {
            // an outer ring (star or square) and 1 to 4 small holes placed on a grid inside it so that they cannot overlap
            int holes = 1 + r.nextInt(4);
            int outerN = 4 + r.nextInt(12);
            float[] outer = i % 2 == 0 ? regular(outerN, 10, 0, 0, i % 4 == 0) : new float[] {-10, -10, 10, -10, 10, 10, -10, 10};
            int outerCount = outer.length / 2;
            int total = outerCount;
            float[][] rings = new float[holes][];
            double[][] centres = {{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}; // with radius <= 2 they stay inside even a square outer ring of 4 sides turned to a diamond
            for (int h = 0; h < holes; h++) {
                int hn = 3 + r.nextInt(8);
                rings[h] = regular(hn, 0.8 + r.nextDouble() * 1.2, centres[h][0], centres[h][1], r.nextBoolean());
                total += hn;
            }
            float[] xy = new float[2 * total];
            System.arraycopy(outer, 0, xy, 0, outer.length);
            int[] ends = new int[holes + 1];
            ends[0] = outerCount;
            int pos = outerCount;
            for (int h = 0; h < holes; h++) {
                System.arraycopy(rings[h], 0, xy, 2 * pos, rings[h].length);
                pos += rings[h].length / 2;
                ends[h + 1] = pos;
            }
            int[] out = new int[3 * (total + 2 * holes)];
            int count = Polygons.triangulate(xy, ends, out);
            assertTrue(count > 0, "case " + i + " found no triangulation");
            checkTriangulation(xy, ends, out, count, "holes " + i);
            checkCoverage(xy, ends, out, count, 10.5, "holes " + i);
        }
    }

    @Test
    void aHoleInAFrameLikeAnAnnulus() {
        float[] outer = regular(32, 5, 0, 0, true);
        float[] inner = regular(32, 3, 0, 0, false);
        float[] xy = new float[outer.length + inner.length];
        System.arraycopy(outer, 0, xy, 0, outer.length);
        System.arraycopy(inner, 0, xy, outer.length, inner.length);
        int[] ends = {32, 64};
        int[] out = new int[3 * 70];
        int count = Polygons.triangulate(xy, ends, out);
        assertEquals(64, count, "n + 2h - 2 triangles");
        checkTriangulation(xy, ends, out, count, "annulus");
        checkCoverage(xy, ends, out, count, 5.2, "annulus");
    }

    @Test
    void planarPolygonsInThreeDimensions() {
        for (int i = 0; i < 100; i++) {
            int n = 3 + r.nextInt(30);
            float[] flat = star(n, 0.5, 2, true);
            // an orthonormal frame (u, v, w) from a random rotation; the polygon lives in the plane spanned by u and v, and is counter-clockwise seen from w
            double ax = r.nextGaussian(), ay = r.nextGaussian(), az = r.nextGaussian();
            double len = Math.sqrt(ax * ax + ay * ay + az * az);
            ax /= len;
            ay /= len;
            az /= len;
            double[] u = {ay, -ax, 0};
            if (Math.abs(az) > 0.99) {
                u = new double[] {0, az, -ay};
            }
            double ul = Math.sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2]);
            for (int k = 0; k < 3; k++) {
                u[k] /= ul;
            }
            double[] w = {ax, ay, az};
            double[] v = {w[1] * u[2] - w[2] * u[1], w[2] * u[0] - w[0] * u[2], w[0] * u[1] - w[1] * u[0]};
            float[] xyz = new float[3 * n];
            for (int k = 0; k < n; k++) {
                for (int c = 0; c < 3; c++) {
                    xyz[3 * k + c] = (float) (flat[2 * k] * u[c] + flat[2 * k + 1] * v[c]);
                }
            }
            int[] out = new int[3 * (n - 2)];
            int count = Polygons.triangulate3(xyz, n, out);
            assertEquals(n - 2, count);
            double flatArea = Polygons.signedArea(flat, n);
            double sum = 0;
            for (int t = 0; t < count; t++) {
                int a = out[3 * t], b = out[3 * t + 1], c = out[3 * t + 2];
                double ex = xyz[3 * b] - xyz[3 * a], ey = xyz[3 * b + 1] - xyz[3 * a + 1], ez = xyz[3 * b + 2] - xyz[3 * a + 2];
                double fx = xyz[3 * c] - xyz[3 * a], fy = xyz[3 * c + 1] - xyz[3 * a + 1], fz = xyz[3 * c + 2] - xyz[3 * a + 2];
                double cx = ey * fz - ez * fy, cy = ez * fx - ex * fz, cz = ex * fy - ey * fx;
                double along = cx * w[0] + cy * w[1] + cz * w[2];
                assertTrue(along > 0, "triangle " + t + " must face the polygon's normal");
                sum += 0.5 * along;
            }
            assertEquals(flatArea, sum, 1e-4 * flatArea);
            // the same polygon listed in the other direction: its normal flips and the triangles follow it
            float[] reversed = new float[3 * n];
            for (int k = 0; k < n; k++) {
                System.arraycopy(xyz, 3 * (n - 1 - k), reversed, 3 * k, 3);
            }
            int[] out2 = new int[3 * (n - 2)];
            assertEquals(n - 2, Polygons.triangulate3(reversed, n, out2));
        }
    }

    // ---------------------------------------------------------------- clipping

    @Test
    void halfPlaneClipping() {
        float[] square = {0, 0, 4, 0, 4, 4, 0, 4};
        float[] out = new float[20];
        int n = Polygons.clipHalfPlane(square, 4, 1, 0, -1, out); // x >= 1
        assertEquals(4, n);
        assertEquals(12.0, Math.abs(Polygons.signedArea(out, n)), 1e-6);
        n = Polygons.clipHalfPlane(square, 4, 1, 1, -4, out); // x + y >= 4: the upper-right triangle
        assertEquals(3, n);
        assertEquals(8.0, Math.abs(Polygons.signedArea(out, n)), 1e-5);
        assertEquals(0, Polygons.clipHalfPlane(square, 4, 1, 0, -5, out), "entirely outside");
        assertEquals(4, Polygons.clipHalfPlane(square, 4, 1, 0, 1, out), "entirely inside");
        // random convex polygons: every output vertex is on the inside, every input vertex that is inside survives
        for (int i = 0; i < 500; i++) {
            int m = 3 + r.nextInt(20);
            float[] poly = regular(m, 2, 0, 0, true);
            float a = (float) r.nextGaussian(), b = (float) r.nextGaussian(), c = (float) (r.nextGaussian() * 1.5);
            float[] res = new float[2 * (m + 1)];
            int k = Polygons.clipHalfPlane(poly, m, a, b, c, res);
            for (int j = 0; j < k; j++) {
                assertTrue((double) a * res[2 * j] + (double) b * res[2 * j + 1] + c >= -1e-5, "an output vertex on the wrong side");
            }
            for (int j = 0; j < m; j++) {
                if ((double) a * poly[2 * j] + (double) b * poly[2 * j + 1] + c >= 0) {
                    boolean found = false;
                    for (int q = 0; q < k; q++) {
                        found |= res[2 * q] == poly[2 * j] && res[2 * q + 1] == poly[2 * j + 1];
                    }
                    assertTrue(found, "an inside vertex was lost");
                }
            }
        }
    }

    @Test
    void convexPolygonClippingMatchesAMonteCarloAreaAndKeepsOutputInsideBoth() {
        for (int i = 0; i < 60; i++) {
            int n = 3 + r.nextInt(8), m = 3 + r.nextInt(8);
            float[] a = regular(n, 2, r.nextGaussian() * 0.5, r.nextGaussian() * 0.5, r.nextBoolean());
            float[] b = regular(m, 1.5 + r.nextDouble(), r.nextGaussian() * 0.8, r.nextGaussian() * 0.8, r.nextBoolean());
            float[] out = new float[2 * (n + m + 2)];
            int k = Polygons.clipConvex(a, n, b, m, out);
            int hits = 0, samples = 120_000;
            SplittableRandom s = new SplittableRandom(i);
            for (int q = 0; q < samples; q++) {
                float x = (float) (s.nextDouble() * 8 - 4), y = (float) (s.nextDouble() * 8 - 4);
                if (Polygons.contains(a, n, x, y) && Polygons.contains(b, m, x, y)) {
                    hits++;
                }
            }
            double estimate = 64.0 * hits / samples;
            double area = k >= 3 ? Math.abs(Polygons.signedArea(out, k)) : 0;
            assertEquals(estimate, area, 0.4, "clip " + i + ": area of the intersection");
            for (int j = 0; j < k; j++) {
                float x = out[2 * j], y = out[2 * j + 1];
                assertTrue(Polygons.contains(widen(a, n), n, x, y) && Polygons.contains(widen(b, m), m, x, y), "output vertex " + j + " is outside the inputs (beyond rounding)");
            }
        }
        float[] sq = {0, 0, 2, 0, 2, 2, 0, 2};
        float[] far = {5, 5, 6, 5, 6, 6, 5, 6};
        float[] out = new float[40];
        assertEquals(0, Polygons.clipConvex(sq, 4, far, 4, out), "disjoint");
        assertEquals(4, Polygons.clipConvex(sq, 4, new float[] {-1, -1, 3, -1, 3, 3, -1, 3}, 4, out), "the clip contains the subject");
    }

    /** The polygon grown by a hair, so that vertices that were rounded to float on the boundary still count as inside. */
    private static float[] widen(float[] p, int n) {
        double cx = 0, cy = 0;
        for (int i = 0; i < n; i++) {
            cx += p[2 * i];
            cy += p[2 * i + 1];
        }
        cx /= n;
        cy /= n;
        float[] w = new float[2 * n];
        for (int i = 0; i < n; i++) {
            w[2 * i] = (float) (cx + (p[2 * i] - cx) * 1.0001);
            w[2 * i + 1] = (float) (cy + (p[2 * i + 1] - cy) * 1.0001);
        }
        return w;
    }

    @Test
    void planeClipping() {
        float[] tri = {0, 0, 0, 2, 0, 2, 0, 2, 2};
        float[] out = new float[12];
        int n = Polygons.clipPlane3(tri, 3, 0, 0, 1, -1, out); // z >= 1
        assertEquals(4, n);
        for (int i = 0; i < n; i++) {
            assertTrue(out[3 * i + 2] >= 1 - 1e-6f);
        }
        assertEquals(3, Polygons.clipPlane3(tri, 3, 0, 0, 1, 1, out), "entirely inside");
        assertEquals(0, Polygons.clipPlane3(tri, 3, 0, 0, 1, -3, out), "entirely outside");
        Report.printf("Polygons: all checks done%n");
    }
}
