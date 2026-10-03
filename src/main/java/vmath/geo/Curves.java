package vmath.geo;

import vmath.annotations.Experimental;

/**
 * Parametric curves in 1 to 4 dimensions on plain {@code float[]} data: Bézier curves of any degree, cubic Hermite segments, Catmull-Rom splines (uniform, centripetal or chordal)
 * and uniform cubic B-splines, open or closed. Points are stored as {@code dim} consecutive floats, so a 3D curve is {@code x, y, z, x, y, z, ...}; a result is written to
 * an array at an offset. Evaluation allocates nothing and runs in double precision.
 *
 * <ul>
 *   <li>{@link #bezier} evaluates a Bézier curve of degree {@code count - 1} (the Bernstein form, which is stable for the degrees used in practice); {@link #bezierTangent} its derivative
 *       with respect to {@code t}; {@link #bezierSplit} cuts it in two by the algorithm of de Casteljau.</li>
 *   <li>{@link #hermite} and {@link #hermiteTangent}: the cubic through two points with given tangents.</li>
 *   <li>{@link #catmullRom}: the spline that passes through every point of a list; {@code alpha} 0 is the uniform spline, 0.5 the <b>centripetal</b> one (no cusps or loops within a
 *       segment, the usual choice), 1 the chordal one.</li>
 *   <li>{@link #bSpline}: the uniform cubic B-spline, which has continuous second derivatives but only passes near the control points; {@link #bSplineTangent}.</li>
 * </ul>
 *
 * <p>For a speed that does not depend on how the parameter was chosen, measure the curve with {@link ArcLengthTable}.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the same time.
 */
@Experimental("NURBS and per-segment tangent access may be added")
public final class Curves {

    private Curves() {
    }

    // ------------------------------------------------------------ Bézier

    /**
     * The point of the Bézier curve with the {@code count} control points {@code cp[offset ..]} ({@code dim} floats each) at parameter {@code t}, written to {@code out[outOffset ..]}.
     * {@code t} is usually in {@code [0, 1]} but any value extrapolates the polynomial. A curve needs at least one control point.
     */
    public static void bezier(float[] cp, int offset, int count, int dim, double t, float[] out, int outOffset) {
        check(cp, offset, count, dim);
        int n = count - 1;
        double s = 1 - t;
        if (n <= 3) {
            // the common degrees in closed form: the Bernstein weights of a line, a quadratic and a cubic
            double w0, w1 = 0, w2 = 0, w3 = 0;
            if (n == 0) {
                w0 = 1;
            } else if (n == 1) {
                w0 = s;
                w1 = t;
            } else if (n == 2) {
                w0 = s * s;
                w1 = 2 * s * t;
                w2 = t * t;
            } else {
                w0 = s * s * s;
                w1 = 3 * s * s * t;
                w2 = 3 * s * t * t;
                w3 = t * t * t;
            }
            for (int c = 0; c < dim; c++) {
                double v = w0 * cp[offset + c];
                if (n >= 1) {
                    v += w1 * cp[offset + dim + c];
                }
                if (n >= 2) {
                    v += w2 * cp[offset + 2 * dim + c];
                }
                if (n >= 3) {
                    v += w3 * cp[offset + 3 * dim + c];
                }
                out[outOffset + c] = (float) v;
            }
            return;
        }
        // any degree: the Bernstein weights by the recurrence b(n, i) = b(n, i - 1) (n - i + 1) / i t / (1 - t), run from the nearer end of the curve for stability; the
        // starting power is the same for every component, so it is computed once
        boolean fromStart = t <= 0.5;
        double first = Math.pow(fromStart ? s : t, n), ratio = fromStart ? t / s : s / t;
        for (int c = 0; c < dim; c++) {
            double term = first, sum;
            if (fromStart) {
                sum = term * cp[offset + c];
                for (int i = 1; i <= n; i++) {
                    term *= ratio * (n - i + 1) / i;
                    sum += term * cp[offset + i * dim + c];
                }
            } else {
                sum = term * cp[offset + n * dim + c];
                for (int i = n - 1; i >= 0; i--) {
                    term *= ratio * (i + 1) / (n - i);
                    sum += term * cp[offset + i * dim + c];
                }
            }
            out[outOffset + c] = (float) sum;
        }
    }

    /** The derivative {@code dB/dt} of the Bézier curve at {@code t}: the curve of degree {@code count - 2} through the scaled differences of the control points. Zero for one point. */
    public static void bezierTangent(float[] cp, int offset, int count, int dim, double t, float[] out, int outOffset) {
        check(cp, offset, count, dim);
        int n = count - 1;
        for (int c = 0; c < dim; c++) {
            if (n == 0) {
                out[outOffset + c] = 0;
                continue;
            }
            double sum = 0;
            for (int i = 0; i < n; i++) {
                double b = binomial(n - 1, i) * Math.pow(t, i) * Math.pow(1 - t, n - 1 - i);
                sum += b * (cp[offset + (i + 1) * dim + c] - cp[offset + i * dim + c]);
            }
            out[outOffset + c] = (float) (n * sum);
        }
    }

    private static double binomial(int n, int k) {
        double r = 1;
        for (int i = 1; i <= k; i++) {
            r = r * (n - k + i) / i;
        }
        return r;
    }

    /**
     * Splits the Bézier curve at {@code t} into two curves of the same degree that together trace the original: {@code left} receives the part from 0 to {@code t} and {@code right} the
     * part from {@code t} to 1, each as {@code count} control points of {@code dim} floats (at the given offsets). The arrays must not overlap {@code cp}'s range.
     */
    public static void bezierSplit(float[] cp, int offset, int count, int dim, double t, float[] left, int leftOffset, float[] right, int rightOffset) {
        check(cp, offset, count, dim);
        if (leftOffset < 0 || rightOffset < 0 || left.length < leftOffset + count * dim || right.length < rightOffset + count * dim) {
            throw new IllegalArgumentException("the output arrays are too small for " + count + " points of " + dim + " floats");
        }
        System.arraycopy(cp, offset, right, rightOffset, count * dim);
        for (int c = 0; c < dim; c++) {
            left[leftOffset + c] = right[rightOffset + c];
        }
        int n = count - 1;
        for (int level = 1; level <= n; level++) {
            for (int i = 0; i <= n - level; i++) {
                for (int c = 0; c < dim; c++) {
                    double a = right[rightOffset + i * dim + c], b = right[rightOffset + (i + 1) * dim + c];
                    right[rightOffset + i * dim + c] = (float) (a + (b - a) * t);
                }
            }
            for (int c = 0; c < dim; c++) {
                left[leftOffset + level * dim + c] = right[rightOffset + c];
            }
        }
        // in the triangle, the right half is read from the last entry of each level: index n - level holds level `level`, so the order is already start to end
    }

    // ------------------------------------------------------------ Hermite

    /**
     * The cubic Hermite curve at {@code t} through {@code p0} (at 0) and {@code p1} (at 1) with tangents {@code m0} and {@code m1} (derivatives with respect to {@code t}). The data
     * are {@code p0, m0, p1, m1} ({@code 4 * dim} floats) starting at {@code offset}.
     */
    public static void hermite(float[] data, int offset, int dim, double t, float[] out, int outOffset) {
        check(data, offset, 4, dim);
        double t2 = t * t, t3 = t2 * t;
        double h00 = 2 * t3 - 3 * t2 + 1, h10 = t3 - 2 * t2 + t, h01 = -2 * t3 + 3 * t2, h11 = t3 - t2;
        for (int c = 0; c < dim; c++) {
            out[outOffset + c] = (float) (h00 * data[offset + c] + h10 * data[offset + dim + c] + h01 * data[offset + 2 * dim + c] + h11 * data[offset + 3 * dim + c]);
        }
    }

    /** The derivative with respect to {@code t} of the {@link #hermite} curve. */
    public static void hermiteTangent(float[] data, int offset, int dim, double t, float[] out, int outOffset) {
        check(data, offset, 4, dim);
        double t2 = t * t;
        double d00 = 6 * t2 - 6 * t, d10 = 3 * t2 - 4 * t + 1, d01 = -6 * t2 + 6 * t, d11 = 3 * t2 - 2 * t;
        for (int c = 0; c < dim; c++) {
            out[outOffset + c] = (float) (d00 * data[offset + c] + d10 * data[offset + dim + c] + d01 * data[offset + 2 * dim + c] + d11 * data[offset + 3 * dim + c]);
        }
    }

    // ------------------------------------------------------------ Catmull-Rom

    /**
     * The number of parameter units of a Catmull-Rom or B-spline through {@code count} points: the parameter {@code u} of {@link #catmullRom} runs from 0 to this value. An open
     * Catmull-Rom spline has {@code count - 1} segments (it passes through every point), a closed one {@code count}; an open B-spline has {@code count - 3} (it needs 4 points) and a closed
     * one {@code count}.
     */
    public static int segments(int count, boolean closed, boolean bSpline) {
        if (closed) {
            return count;
        }
        return bSpline ? Math.max(count - 3, 0) : Math.max(count - 1, 0);
    }

    /**
     * The point at parameter {@code u} of the Catmull-Rom spline through the {@code count} points {@code pts[offset ..]}: segment {@code i} runs from point {@code i} (at {@code u = i})
     * to point {@code i + 1}, {@code u} in {@code [0, segments(count, closed, false)]}. An open spline repeats the end points as the missing neighbours (so its end tangents point at
     * the next point); a closed one wraps around. {@code alpha} chooses the parametrization: 0 uniform, 0.5 centripetal, 1 chordal. Needs at least 2 points.
     */
    public static void catmullRom(float[] pts, int offset, int count, int dim, boolean closed, double alpha, double u, float[] out, int outOffset) {
        check(pts, offset, count, dim);
        if (count < 2) {
            throw new IllegalArgumentException("a Catmull-Rom spline needs at least 2 points");
        }
        int segs = segments(count, closed, false);
        double uc = Math.max(0, Math.min(segs, u));
        int s = Math.min((int) uc, segs - 1);
        double t = uc - s;
        int i0 = index(s - 1, count, closed), i1 = index(s, count, closed), i2 = index(s + 1, count, closed), i3 = index(s + 2, count, closed);
        // knot intervals from the chord lengths to the power alpha; a zero chord falls back to the uniform interval
        double d01 = knot(pts, offset, dim, i0, i1, alpha), d12 = knot(pts, offset, dim, i1, i2, alpha), d23 = knot(pts, offset, dim, i2, i3, alpha);
        double t0 = 0, t1 = t0 + d01, t2 = t1 + d12, t3 = t2 + d23;
        double tt = t1 + t * (t2 - t1);
        for (int c = 0; c < dim; c++) {
            double p0 = pts[offset + i0 * dim + c], p1 = pts[offset + i1 * dim + c], p2 = pts[offset + i2 * dim + c], p3 = pts[offset + i3 * dim + c];
            // the pyramidal formulation of Barry and Goldman
            double a1 = lerpKnots(p0, p1, t0, t1, tt), a2 = lerpKnots(p1, p2, t1, t2, tt), a3 = lerpKnots(p2, p3, t2, t3, tt);
            double b1 = lerpKnots(a1, a2, t0, t2, tt), b2 = lerpKnots(a2, a3, t1, t3, tt);
            out[outOffset + c] = (float) lerpKnots(b1, b2, t1, t2, tt);
        }
    }

    private static double lerpKnots(double a, double b, double ta, double tb, double t) {
        return tb == ta ? a : a + (b - a) * ((t - ta) / (tb - ta));
    }

    private static double knot(float[] p, int offset, int dim, int a, int b, double alpha) {
        double d2 = 0;
        for (int c = 0; c < dim; c++) {
            double d = p[offset + b * dim + c] - p[offset + a * dim + c];
            d2 += d * d;
        }
        if (d2 < 1e-24 || alpha == 0) {
            return 1;
        }
        return Math.pow(d2, alpha / 2);
    }

    private static int index(int i, int count, boolean closed) {
        if (closed) {
            return Math.floorMod(i, count);
        }
        return Math.max(0, Math.min(count - 1, i));
    }

    // ------------------------------------------------------------ B-spline

    /**
     * The point at parameter {@code u} of the uniform cubic B-spline with the control points {@code pts[offset ..]}: segment {@code i} (for {@code u} from {@code i} to {@code i + 1}) uses control
     * points {@code i .. i + 3}, {@code u} in {@code [0, segments(count, closed, true)]}. The curve is smooth (continuous second derivative) but passes near, not through, the control
     * points: at each knot it is the weighted mean {@code (P[i] + 4 P[i + 1] + P[i + 2]) / 6}. A closed spline wraps around and needs at least 3 points; an open one needs 4.
     */
    public static void bSpline(float[] pts, int offset, int count, int dim, boolean closed, double u, float[] out, int outOffset) {
        check(pts, offset, count, dim);
        bSplineEval(pts, offset, count, dim, closed, u, out, outOffset, false);
    }

    /** The derivative with respect to {@code u} of the {@link #bSpline} curve. */
    public static void bSplineTangent(float[] pts, int offset, int count, int dim, boolean closed, double u, float[] out, int outOffset) {
        check(pts, offset, count, dim);
        bSplineEval(pts, offset, count, dim, closed, u, out, outOffset, true);
    }

    private static void bSplineEval(float[] pts, int offset, int count, int dim, boolean closed, double u, float[] out, int outOffset, boolean derivative) {
        int segs = segments(count, closed, true);
        if (segs < 1 || (closed && count < 3)) {
            throw new IllegalArgumentException("the spline needs " + (closed ? 3 : 4) + " points, got " + count);
        }
        double uc = Math.max(0, Math.min(segs, u));
        int s = Math.min((int) uc, segs - 1);
        double t = uc - s, t2 = t * t, t3 = t2 * t;
        double b0, b1, b2, b3;
        if (derivative) {
            b0 = -(1 - t) * (1 - t) / 2;
            b1 = (3 * t2 - 4 * t) / 2;
            b2 = (-3 * t2 + 2 * t + 1) / 2;
            b3 = t2 / 2;
        } else {
            b0 = (1 - t) * (1 - t) * (1 - t) / 6;
            b1 = (3 * t3 - 6 * t2 + 4) / 6;
            b2 = (-3 * t3 + 3 * t2 + 3 * t + 1) / 6;
            b3 = t3 / 6;
        }
        for (int c = 0; c < dim; c++) {
            double v = 0;
            v += b0 * pts[offset + index(s, count, closed) * dim + c];
            v += b1 * pts[offset + index(s + 1, count, closed) * dim + c];
            v += b2 * pts[offset + index(s + 2, count, closed) * dim + c];
            v += b3 * pts[offset + index(s + 3, count, closed) * dim + c];
            out[outOffset + c] = (float) v;
        }
    }

    // ------------------------------------------------------------ checks

    private static void check(float[] a, int offset, int count, int dim) {
        if (dim < 1 || dim > 4) {
            throw new IllegalArgumentException("the dimension must be 1 to 4: " + dim);
        }
        if (count < 1 || offset < 0 || (long) offset + (long) count * dim > a.length) {
            throw new IllegalArgumentException(count + " points of " + dim + " floats at offset " + offset + " do not fit in an array of " + a.length);
        }
    }
}
