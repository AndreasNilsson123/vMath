package vmath.geo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vmath.core.Rnd;

class CurvesTest {

    private final Rnd rnd = Rnd.create();

    private float[] randomPoints(int count, int dim) {
        float[] p = new float[count * dim];
        for (int i = 0; i < p.length; i++) {
            p[i] = (float) rnd.range(-5, 5);
        }
        return p;
    }

    /** Evaluation by repeated linear interpolation (de Casteljau), the textbook definition. */
    private static double[] casteljau(float[] cp, int count, int dim, double t) {
        double[] out = new double[dim];
        for (int c = 0; c < dim; c++) {
            double[] w = new double[count];
            for (int i = 0; i < count; i++) {
                w[i] = cp[i * dim + c];
            }
            for (int level = 1; level < count; level++) {
                for (int i = 0; i < count - level; i++) {
                    w[i] = w[i] + (w[i + 1] - w[i]) * t;
                }
            }
            out[c] = w[0];
        }
        return out;
    }

    @Test
    void bezierMatchesDeCasteljauForEveryDegreeAndDimension() {
        float[] out = new float[4];
        for (int dim = 1; dim <= 4; dim++) {
            for (int count = 1; count <= 12; count++) {
                float[] cp = randomPoints(count, dim);
                for (double t : new double[] {0, 0.1, 0.37, 0.5, 0.51, 0.9, 1, -0.4, 1.3}) {
                    Curves.bezier(cp, 0, count, dim, t, out, 0);
                    double[] ref = casteljau(cp, count, dim, t);
                    for (int c = 0; c < dim; c++) {
                        assertEquals(ref[c], out[c], 2e-4 * (1 + Math.abs(ref[c])), "dim " + dim + " count " + count + " t " + t);
                    }
                }
                Curves.bezier(cp, 0, count, dim, 0, out, 0);
                for (int c = 0; c < dim; c++) {
                    assertEquals(cp[c], out[c], 1e-6, "starts at the first point");
                }
                Curves.bezier(cp, 0, count, dim, 1, out, 0);
                for (int c = 0; c < dim; c++) {
                    assertEquals(cp[(count - 1) * dim + c], out[c], 1e-6, "ends at the last point");
                }
            }
        }
    }

    @Test
    void bezierWorksAtAnOffsetAndWritesAtAnOffset() {
        float[] cp = {99, 99, 0, 0, 1, 2, 4, 0, 99};
        float[] out = new float[5];
        Curves.bezier(cp, 2, 3, 2, 0.5, out, 3);
        assertArrayEquals(new float[] {0, 0, 0, 1.5f, 1f}, out, 1e-6f);
    }

    @Test
    void bezierTangentMatchesFiniteDifferences() {
        float[] out = new float[3], a = new float[3], b = new float[3];
        for (int count = 1; count <= 8; count++) {
            float[] cp = randomPoints(count, 3);
            for (double t : new double[] {0.0, 0.2, 0.5, 0.8, 1.0}) {
                Curves.bezierTangent(cp, 0, count, 3, t, out, 0);
                double h = 1e-4;
                double[] hi = casteljau(cp, count, 3, t + h), lo = casteljau(cp, count, 3, t - h);
                for (int c = 0; c < 3; c++) {
                    assertEquals((hi[c] - lo[c]) / (2 * h), out[c], 2e-2 * (1 + Math.abs(out[c])), "count " + count + " t " + t);
                }
            }
        }
        // a cubic at its ends: the tangent is 3 (P1 - P0) and 3 (P3 - P2)
        float[] cubic = {0, 0, 1, 2, 3, 2, 4, 0};
        Curves.bezierTangent(cubic, 0, 4, 2, 0, a, 0);
        Curves.bezierTangent(cubic, 0, 4, 2, 1, b, 0);
        assertArrayEquals(new float[] {3, 6}, new float[] {a[0], a[1]}, 1e-5f);
        assertArrayEquals(new float[] {3, -6}, new float[] {b[0], b[1]}, 1e-5f);
    }

    @Test
    void splittingTracesTheSameCurve() {
        float[] out = new float[3], ref = new float[3];
        for (int count = 1; count <= 7; count++) {
            float[] cp = randomPoints(count, 3);
            double t = rnd.range(0.1, 0.9);
            float[] left = new float[count * 3], right = new float[count * 3];
            Curves.bezierSplit(cp, 0, count, 3, t, left, 0, right, 0);
            for (double u : new double[] {0, 0.25, 0.5, 0.75, 1}) {
                Curves.bezier(left, 0, count, 3, u, out, 0);
                Curves.bezier(cp, 0, count, 3, t * u, ref, 0);
                assertArrayEquals(ref, out, 1e-4f, "left, count " + count);
                Curves.bezier(right, 0, count, 3, u, out, 0);
                Curves.bezier(cp, 0, count, 3, t + (1 - t) * u, ref, 0);
                assertArrayEquals(ref, out, 1e-4f, "right, count " + count);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> Curves.bezierSplit(new float[6], 0, 3, 2, 0.5, new float[3], 0, new float[6], 0));
        assertThrows(IllegalArgumentException.class, () -> Curves.bezierSplit(new float[6], 0, 3, 2, 0.5, new float[6], 0, new float[6], -1));
    }

    @Test
    void hermiteInterpolatesPositionsAndTangents() {
        float[] data = {1, 2, 3, 0, 1, 0, 5, 4, 3, 0, -1, 0}; // p0, m0, p1, m1 in 3D
        float[] out = new float[3];
        Curves.hermite(data, 0, 3, 0, out, 0);
        assertArrayEquals(new float[] {1, 2, 3}, out, 1e-6f);
        Curves.hermite(data, 0, 3, 1, out, 0);
        assertArrayEquals(new float[] {5, 4, 3}, out, 1e-6f);
        Curves.hermiteTangent(data, 0, 3, 0, out, 0);
        assertArrayEquals(new float[] {0, 1, 0}, out, 1e-6f);
        Curves.hermiteTangent(data, 0, 3, 1, out, 0);
        assertArrayEquals(new float[] {0, -1, 0}, out, 1e-6f);
        // the tangent is the derivative
        float[] a = new float[3], b = new float[3];
        for (double t : new double[] {0.2, 0.5, 0.7}) {
            double h = 1e-4;
            Curves.hermite(data, 0, 3, t + h, a, 0);
            Curves.hermite(data, 0, 3, t - h, b, 0);
            Curves.hermiteTangent(data, 0, 3, t, out, 0);
            for (int c = 0; c < 3; c++) {
                assertEquals((a[c] - b[c]) / (2 * h), out[c], 5e-2);
            }
        }
        // a Hermite segment equals the Bézier curve with control points p0, p0 + m0 / 3, p1 - m1 / 3, p1
        float[] bez = new float[12];
        for (int c = 0; c < 3; c++) {
            bez[c] = data[c];
            bez[3 + c] = data[c] + data[3 + c] / 3;
            bez[6 + c] = data[6 + c] - data[9 + c] / 3;
            bez[9 + c] = data[6 + c];
        }
        Curves.hermite(data, 0, 3, 0.37, a, 0);
        Curves.bezier(bez, 0, 4, 3, 0.37, b, 0);
        assertArrayEquals(b, a, 1e-5f);
    }

    @Test
    void catmullRomPassesThroughEveryPointForAnyAlpha() {
        float[] out = new float[3];
        for (double alpha : new double[] {0, 0.5, 1}) {
            for (boolean closed : new boolean[] {false, true}) {
                float[] pts = randomPoints(7, 3);
                int segs = Curves.segments(7, closed, false);
                assertEquals(closed ? 7 : 6, segs);
                for (int i = 0; i <= segs; i++) {
                    Curves.catmullRom(pts, 0, 7, 3, closed, alpha, i, out, 0);
                    int idx = i % 7;
                    for (int c = 0; c < 3; c++) {
                        assertEquals(pts[idx * 3 + c], out[c], 1e-4, "alpha " + alpha + " closed " + closed + " point " + i);
                    }
                }
            }
        }
    }

    @Test
    void uniformCatmullRomHasTheClassicTangentsAndIsSmooth() {
        // the uniform spline has the tangent (P[i + 1] - P[i - 1]) / 2 at point i; check by a one-sided difference
        float[] pts = randomPoints(6, 2);
        float[] a = new float[2], b = new float[2];
        double h = 1e-4;
        Curves.catmullRom(pts, 0, 6, 2, false, 0, 2, a, 0);
        Curves.catmullRom(pts, 0, 6, 2, false, 0, 2 + h, b, 0);
        for (int c = 0; c < 2; c++) {
            assertEquals((pts[3 * 2 + c] - pts[1 * 2 + c]) / 2, (b[c] - a[c]) / h, 2e-2);
        }
        // the curve is continuous across a segment boundary for every alpha
        for (double alpha : new double[] {0, 0.5, 1}) {
            Curves.catmullRom(pts, 0, 6, 2, false, alpha, 3 - 1e-6, a, 0);
            Curves.catmullRom(pts, 0, 6, 2, false, alpha, 3 + 1e-6, b, 0);
            assertArrayEquals(a, b, 1e-4f);
        }
    }

    @Test
    void centripetalSplinesHandleRepeatedPointsAndTheParameterIsClamped() {
        float[] pts = {0, 0, 1, 1, 1, 1, 2, 0}; // a repeated point: a zero chord
        float[] out = new float[2];
        for (double u = 0; u <= 3; u += 0.25) {
            Curves.catmullRom(pts, 0, 4, 2, false, 0.5, u, out, 0);
            assertTrue(Float.isFinite(out[0]) && Float.isFinite(out[1]));
        }
        Curves.catmullRom(pts, 0, 4, 2, false, 0.5, -5, out, 0);
        assertArrayEquals(new float[] {0, 0}, out, 1e-6f);
        Curves.catmullRom(pts, 0, 4, 2, false, 0.5, 50, out, 0);
        assertArrayEquals(new float[] {2, 0}, out, 1e-6f);
        // two points: a straight segment
        float[] two = {0, 0, 4, 2};
        Curves.catmullRom(two, 0, 2, 2, false, 0.5, 0.5, out, 0);
        assertArrayEquals(new float[] {2, 1}, out, 1e-5f);
        assertThrows(IllegalArgumentException.class, () -> Curves.catmullRom(new float[2], 0, 1, 2, false, 0.5, 0, out, 0));
    }

    @Test
    void bSplineHasTheKnotFormulaAndContinuousDerivatives() {
        float[] pts = randomPoints(8, 3);
        float[] out = new float[3], a = new float[3], b = new float[3];
        assertEquals(5, Curves.segments(8, false, true));
        assertEquals(8, Curves.segments(8, true, true));
        assertEquals(0, Curves.segments(3, false, true));
        for (int i = 0; i <= 5; i++) {
            Curves.bSpline(pts, 0, 8, 3, false, i, out, 0);
            for (int c = 0; c < 3; c++) {
                assertEquals((pts[i * 3 + c] + 4 * pts[(i + 1) * 3 + c] + pts[(i + 2) * 3 + c]) / 6, out[c], 1e-5, "knot " + i);
            }
        }
        // the first and second derivatives are continuous at a knot: compare the two sides
        double h = 1e-3;
        for (int knot = 1; knot < 5; knot++) {
            Curves.bSplineTangent(pts, 0, 8, 3, false, knot - 1e-9, a, 0);
            Curves.bSplineTangent(pts, 0, 8, 3, false, knot + 1e-9, b, 0);
            assertArrayEquals(a, b, 1e-4f);
            Curves.bSplineTangent(pts, 0, 8, 3, false, knot - h, a, 0);
            Curves.bSplineTangent(pts, 0, 8, 3, false, knot + h, b, 0);
            Curves.bSplineTangent(pts, 0, 8, 3, false, knot, out, 0);
            for (int c = 0; c < 3; c++) {
                // the second derivative is continuous, so the tangent at the knot is the mean of the two sides up to O(h^2) times the third derivative
                assertEquals(out[c], (a[c] + b[c]) / 2, 5e-2);
            }
        }
        // the tangent is the derivative
        for (double u : new double[] {0.3, 2.5, 4.9}) {
            Curves.bSpline(pts, 0, 8, 3, false, u + 1e-4, a, 0);
            Curves.bSpline(pts, 0, 8, 3, false, u - 1e-4, b, 0);
            Curves.bSplineTangent(pts, 0, 8, 3, false, u, out, 0);
            for (int c = 0; c < 3; c++) {
                assertEquals((a[c] - b[c]) / 2e-4, out[c], 5e-2);
            }
        }
    }

    @Test
    void closedBSplinesWrapAround() {
        float[] pts = randomPoints(5, 2);
        float[] a = new float[2], b = new float[2];
        Curves.bSpline(pts, 0, 5, 2, true, 0, a, 0);
        Curves.bSpline(pts, 0, 5, 2, true, 5, b, 0);
        assertArrayEquals(a, b, 1e-5f);
        Curves.bSpline(pts, 0, 5, 2, true, 4.5, a, 0);
        Curves.bSpline(pts, 0, 5, 2, true, 4.5 + 1e-6, b, 0);
        assertArrayEquals(a, b, 1e-4f);
        // a closed spline of the vertices of a square stays inside its hull
        float[] square = {0, 0, 4, 0, 4, 4, 0, 4};
        float[] out = new float[2];
        for (double u = 0; u <= 4; u += 0.05) {
            Curves.bSpline(square, 0, 4, 2, true, u, out, 0);
            assertTrue(out[0] >= 0 && out[0] <= 4 && out[1] >= 0 && out[1] <= 4);
        }
        assertThrows(IllegalArgumentException.class, () -> Curves.bSpline(new float[6], 0, 3, 2, false, 0, out, 0));
        assertThrows(IllegalArgumentException.class, () -> Curves.bSpline(new float[4], 0, 2, 2, true, 0, out, 0));
        assertThrows(IllegalArgumentException.class, () -> Curves.bSplineTangent(new float[6], 0, 3, 2, false, 0, out, 0));
    }

    @Test
    void argumentsAreChecked() {
        float[] out = new float[4];
        assertThrows(IllegalArgumentException.class, () -> Curves.bezier(new float[6], 0, 3, 5, 0.5, out, 0));
        assertThrows(IllegalArgumentException.class, () -> Curves.bezier(new float[6], 0, 3, 0, 0.5, out, 0));
        assertThrows(IllegalArgumentException.class, () -> Curves.bezier(new float[6], 0, 0, 2, 0.5, out, 0));
        assertThrows(IllegalArgumentException.class, () -> Curves.bezier(new float[6], 1, 3, 2, 0.5, out, 0));
        assertThrows(IllegalArgumentException.class, () -> Curves.bezier(new float[6], -1, 3, 2, 0.5, out, 0));
        assertThrows(IllegalArgumentException.class, () -> Curves.hermite(new float[6], 0, 2, 0.5, out, 0));
    }

    // ------------------------------------------------------------ arc length

    @Test
    void aCircleHasTheLengthOfACircleWithTheExpectedUnderestimate() {
        double r = 2;
        for (int n : new int[] {16, 64, 256, 1024}) {
            ArcLengthTable t = new ArcLengthTable((u, out) -> {
                out[0] = r * Math.cos(u);
                out[1] = r * Math.sin(u);
            }, 2, 0, 2 * Math.PI, n);
            double exact = 2 * Math.PI * r;
            // a regular n-gon inscribed in the circle: the perimeter is 2 n r sin(pi / n), a little under the circle
            assertEquals(2 * n * r * Math.sin(Math.PI / n), t.length(), 1e-9);
            assertTrue(t.length() < exact);
            assertTrue(exact - t.length() < exact * Math.pow(Math.PI / n, 2) / 5.9, "error for " + n);
        }
    }

    @Test
    void theParameterOfADistanceInvertsTheLengthAtAParameter() {
        // x(t) = t^2 along a line: the length up to t is t^2 exactly (a polyline along a straight monotone line has no error)
        ArcLengthTable line = new ArcLengthTable((t, out) -> {
            out[0] = t * t;
            out[1] = 0;
            out[2] = 0;
        }, 3, 0, 1, 1000);
        assertEquals(1.0, line.length(), 1e-12);
        assertEquals(0.25, line.lengthAt(0.5), 1e-6);
        assertEquals(0.5, line.parameterAt(0.25), 1e-3);
        assertEquals(Math.sqrt(0.9), line.parameterAtFraction(0.9), 1e-3);
        assertEquals(0.0, line.parameterAt(-1), 0.0);
        assertEquals(0.0, line.parameterAt(0), 0.0);
        assertEquals(1.0, line.parameterAt(5), 0.0);
        assertEquals(0.0, line.lengthAt(-3), 0.0);
        assertEquals(1.0, line.lengthAt(9), 1e-12);
        for (int i = 0; i <= 20; i++) {
            double s = i / 20.0;
            assertEquals(s, line.lengthAt(line.parameterAt(s)), 1e-3);
        }
        // equal steps of arc length give equal steps of distance along the curve
        float[] cp = {0, 0, 0, 10, 10, 10, 11, 0};
        ArcLengthTable bez = new ArcLengthTable((t, out) -> {
            float[] p = new float[2];
            Curves.bezier(cp, 0, 4, 2, t, p, 0);
            out[0] = p[0];
            out[1] = p[1];
        }, 2, 0, 1, 2000);
        float[] prev = new float[2], cur = new float[2];
        Curves.bezier(cp, 0, 4, 2, bez.parameterAtFraction(0), prev, 0);
        double step = bez.length() / 40;
        for (int i = 1; i <= 40; i++) {
            Curves.bezier(cp, 0, 4, 2, bez.parameterAtFraction(i / 40.0), cur, 0);
            assertEquals(step, Math.hypot(cur[0] - prev[0], cur[1] - prev[1]), step * 0.05, "step " + i);
            System.arraycopy(cur, 0, prev, 0, 2);
        }
        // a zero-length stretch (the curve stands still) does not divide by zero
        ArcLengthTable still = new ArcLengthTable((t, out) -> out[0] = t < 0.5 ? 0 : 1, 1, 0, 1, 10);
        assertTrue(Double.isFinite(still.parameterAt(0.5)));
        ArcLengthTable constant = new ArcLengthTable((t, out) -> out[0] = 3, 1, 0, 1, 4);
        assertEquals(0.0, constant.length());
        assertEquals(0.0, constant.parameterAtFraction(0.5), 0.0);
    }

    @Test
    void tableArgumentsAreChecked() {
        ArcLengthTable.Curve c = (t, out) -> out[0] = t;
        assertThrows(IllegalArgumentException.class, () -> new ArcLengthTable(c, 0, 0, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> new ArcLengthTable(c, 5, 0, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> new ArcLengthTable(c, 1, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ArcLengthTable(c, 1, 1, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> new ArcLengthTable(c, 1, 1, 0, 10));
    }
}
