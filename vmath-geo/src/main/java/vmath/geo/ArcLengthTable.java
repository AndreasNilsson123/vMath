package vmath.geo;

import vmath.annotations.Experimental;

/**
 * A table of the cumulative length of a curve at evenly spaced parameter values, to answer "how long is it" and, more usefully, "which parameter is this far along": moving a point
 * along a spline by equal steps of the parameter makes it speed up and slow down where the control points are unevenly spaced, while moving by equal steps of arc length does not.
 *
 * <p>The curve is any function of a parameter that writes a point of {@code dim} coordinates ({@link Curve}); {@link Curves} has the standard ones. The length is approximated by
 * the polyline through {@code segments} samples, which underestimates a curved arc: the error shrinks with the square of the segment length (see {@code docs/CURVES.md} for measured
 * values). The inverse ({@link #parameterAt}) interpolates linearly inside a table segment, so its position error is of the same order.
 *
 * <p><b>Thread safety.</b> Immutable once built (building evaluates the curve function, which must be safe to call from the building thread): safe to share between threads.
 */
@Experimental("adaptive subdivision may replace the uniform table")
public final class ArcLengthTable {

    /** A curve as a function of its parameter. */
    @FunctionalInterface
    public interface Curve {
        /** Writes the point at {@code t} to {@code out[0 .. dim)}. */
        void evaluate(double t, double[] out);
    }

    private final double t0, t1;
    private final double[] cumulative; // cumulative[i] is the polyline length from the start to sample i

    /**
     * Samples {@code curve} at {@code segments + 1} evenly spaced parameters from {@code t0} to {@code t1}, with points of {@code dim} coordinates (1 to 4). A larger table is more
     * accurate and costs more memory and build time.
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

    /** The approximate length of the whole curve. */
    public double length() {
        return cumulative[cumulative.length - 1];
    }

    /** The approximate length of the curve from {@code t0} to the parameter {@code t} (clamped to the range). */
    public double lengthAt(double t) {
        int n = cumulative.length - 1;
        double x = Math.max(0, Math.min(1, (t - t0) / (t1 - t0))) * n;
        int i = Math.min((int) x, n - 1);
        return cumulative[i] + (cumulative[i + 1] - cumulative[i]) * (x - i);
    }

    /** The parameter at which the curve has covered {@code distance} of its length (clamped to {@code [0, length()]}): the inverse of {@link #lengthAt}. */
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

    /** The parameter at the fraction {@code f} (0 to 1) of the total length: {@code parameterAt(f * length())}. */
    public double parameterAtFraction(double f) {
        return parameterAt(f * length());
    }
}
