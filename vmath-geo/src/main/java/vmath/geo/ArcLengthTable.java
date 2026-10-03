package vmath.geo;

import vmath.annotations.Experimental;

/**
 * A table of the cumulative length of a curve at evenly spaced parameter values, to answer "how
 * long is it" and, more usefully, "which parameter is this far along": moving a point along a
 * spline by equal steps of the parameter makes it speed up and slow down where the control points
 * are unevenly spaced, while moving by equal steps of arc length does not.
 *
 * <p>The curve is any function of a parameter that writes a point of {@code dim} coordinates
 * ({@link Curve}); {@link Curves} has the standard ones. The length is approximated by the polyline
 * through {@code segments} samples, which underestimates a curved arc: the error shrinks with the
 * square of the segment length (see {@code docs/CURVES.md} for measured values). The inverse
 * ({@link #parameterAt}) interpolates linearly inside a table segment, so its position error is of
 * the same order.
 *
 * <p><b>Thread safety.</b> Immutable once built (building evaluates the curve function, which must
 * be safe to call from the building thread): safe to share between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * ArcLengthTable table = new ArcLengthTable((t, out) -> { out[0] = t; out[1] = t * t; }, 2, 0.0, 1.0, 256);   // a parabola
 * double length = table.length();
 * double halfway = table.parameterAtFraction(0.5);                 // the parameter at half of the length
 * }</pre>
 */
@Experimental("adaptive subdivision may replace the uniform table")
public final class ArcLengthTable {

    /**
     * A curve as a function of its parameter.
     */
    @FunctionalInterface
    public interface Curve {
        /**
         * Writes the point at {@code t} to {@code out[0 .. dim)}.
         *
         * @param t the curve parameter
         * @param out receives the result
         */
        void evaluate(double t, double[] out);
    }

    private final double t0, t1;
    private final double[] cumulative; // cumulative[i] is the polyline length from the start to sample i

    /**
     * Samples {@code curve} at {@code segments + 1} evenly spaced parameters from {@code t0} to
     * {@code t1}, with points of {@code dim} coordinates (1 to 4).
     *
     * <p>A larger table is more accurate and costs more memory and build time.
     *
     * @param curve the curve; must not be {@code null}
     * @param dim the dimension
     * @param t0 the first curve parameter
     * @param t1 the last curve parameter
     * @param segments the number of segments
     * @throws IllegalArgumentException if {@code dim} is not in {@code [1, 4]}, {@code segments} is
     *     below 1 or {@code t0} is not less than {@code t1}
     */
    public ArcLengthTable(Curve curve, int dim, double t0, double t1, int segments) {
        if (dim < 1 || dim > 4 || segments < 1 || !(t1 > t0)) {
            throw new IllegalArgumentException("need 1 <= dim <= 4, at least one segment and t0 < t1: " + dim + ", " + segments + ", " + t0 + ", " + t1);
        }
        this.t0 = t0;
        this.t1 = t1;
        cumulative = new double[segments + 1];
        double[] prev = new double[dim], cur = new double[dim];
        curve.evaluate(t0, prev);
        for (int i = 1; i <= segments; i++) {
            curve.evaluate(t0 + (t1 - t0) * i / segments, cur);
            double d2 = 0;
            for (int c = 0; c < dim; c++) {
                double d = cur[c] - prev[c];
                d2 += d * d;
            }
            cumulative[i] = cumulative[i - 1] + Math.sqrt(d2);
            double[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
    }

    /**
     * Reports the total length of the curve as estimated from the table, so the accuracy depends on
     * the table resolution.
     *
     * @return the approximate length of the whole curve
     */
    public double length() {
        return cumulative[cumulative.length - 1];
    }

    /**
     * Estimates the length of a curve segment by interpolating the table, which is cheap but only
     * as accurate as the table; the parameter is clamped to the curve's range.
     *
     * @param t the curve parameter, clamped to the sampled range
     * @return the approximate length of the curve from {@code t0} to the parameter {@code t}
     *     (clamped to the range)
     */
    public double lengthAt(double t) {
        int n = cumulative.length - 1;
        double x = Math.max(0, Math.min(1, (t - t0) / (t1 - t0))) * n;
        int i = Math.min((int) x, n - 1);
        return cumulative[i] + (cumulative[i + 1] - cumulative[i]) * (x - i);
    }

    /**
     * Inverts the length table with a binary search and interpolation, so that points can be spaced
     * evenly along the curve; the distance is clamped to the curve's length.
     *
     * @param distance the distance
     * @return the parameter at which the curve has covered {@code distance} of its length (clamped
     *     to {@code [0, length()]}): the inverse of {@link #lengthAt}
     */
    public double parameterAt(double distance) {
        int n = cumulative.length - 1;
        if (!(distance > 0)) {
            return t0;
        }
        if (distance >= cumulative[n]) {
            return t1;
        }
        // the last table entry that is not beyond the distance
        int lo = 0, hi = n;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (cumulative[mid] <= distance) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double span = cumulative[lo + 1] - cumulative[lo];
        double frac = span > 0 ? (distance - cumulative[lo]) / span : 0;
        return t0 + (t1 - t0) * (lo + frac) / n;
    }

    /**
     * Maps a fraction of the total length to a curve parameter, for even spacing of samples along
     * the curve.
     *
     * @param f the fraction of the total length, in {@code [0, 1]}
     * @return the parameter at the fraction {@code f} (0 to 1) of the total length:
     *     {@code parameterAt(f * length())}
     */
    public double parameterAtFraction(double f) {
        return parameterAt(f * length());
    }
}
