package vmath.core;

import java.util.Arrays;

/**
 * Exact arithmetic on floating-point expansions, after J.
 *
 * <p>R. Shewchuk, "Adaptive Precision Floating-Point Arithmetic and Fast Robust Geometric
 * Predicates" (1997). An expansion is a {@code double[]} of non-overlapping components in
 * increasing order of magnitude whose exact sum is the number; sums, differences and products of
 * expansions are exact (no rounding), so the sign of the result is the sign of the largest
 * component. The cost grows with the number of components, which is why {@link Predicates} only
 * comes here when its floating-point filter cannot decide.
 *
 * <p>Exact as long as no intermediate value overflows and no product underflows below
 * {@code 2^-969}: the callers document the range of coordinates they accept.
 */
final class Expansions {

    private Expansions() {
    }

    /**
     * The expansion of one double.
     */
    static double[] of(double a) {
        return new double[] {a};
    }

    /**
     * The exact difference {@code a - b} as an expansion of one or two components.
     */
    static double[] diff(double a, double b) {
        double x = a - b;
        double bv = a - x;
        double av = x + bv;
        double br = bv - b;
        double ar = a - av;
        double y = ar + br;
        return y != 0.0 ? new double[] {y, x} : new double[] {x};
    }

    /**
     * The exact sum of two expansions (Shewchuk's fast-expansion-sum with zero elimination).
     */
    static double[] sum(double[] e, double[] f) {
        int elen = e.length, flen = f.length;
        double[] h = new double[elen + flen];
        double enow = e[0], fnow = f[0];
        int eindex = 0, findex = 0, hindex = 0;
        double q;
        if ((fnow > enow) == (fnow > -enow)) {
            q = enow;
            enow = ++eindex < elen ? e[eindex] : 0.0;
        } else {
            q = fnow;
            fnow = ++findex < flen ? f[findex] : 0.0;
        }
        if (eindex < elen && findex < flen) {
            double qnew, hh;
            if ((fnow > enow) == (fnow > -enow)) {
                qnew = enow + q;
                hh = q - (qnew - enow);
                enow = ++eindex < elen ? e[eindex] : 0.0;
            } else {
                qnew = fnow + q;
                hh = q - (qnew - fnow);
                fnow = ++findex < flen ? f[findex] : 0.0;
            }
            q = qnew;
            if (hh != 0.0) {
                h[hindex++] = hh;
            }
            while (eindex < elen && findex < flen) {
                if ((fnow > enow) == (fnow > -enow)) {
                    qnew = q + enow;
                    double bv = qnew - q;
                    double av = qnew - bv;
                    hh = (q - av) + (enow - bv);
                    enow = ++eindex < elen ? e[eindex] : 0.0;
                } else {
                    qnew = q + fnow;
                    double bv = qnew - q;
                    double av = qnew - bv;
                    hh = (q - av) + (fnow - bv);
                    fnow = ++findex < flen ? f[findex] : 0.0;
                }
                q = qnew;
                if (hh != 0.0) {
                    h[hindex++] = hh;
                }
            }
        }
        while (eindex < elen) {
            double qnew = q + enow;
            double bv = qnew - q;
            double av = qnew - bv;
            double hh = (q - av) + (enow - bv);
            enow = ++eindex < elen ? e[eindex] : 0.0;
            q = qnew;
            if (hh != 0.0) {
                h[hindex++] = hh;
            }
        }
        while (findex < flen) {
            double qnew = q + fnow;
            double bv = qnew - q;
            double av = qnew - bv;
            double hh = (q - av) + (fnow - bv);
            fnow = ++findex < flen ? f[findex] : 0.0;
            q = qnew;
            if (hh != 0.0) {
                h[hindex++] = hh;
            }
        }
        if (q != 0.0 || hindex == 0) {
            h[hindex++] = q;
        }
        return hindex == h.length ? h : Arrays.copyOf(h, hindex);
    }

    /**
     * {@code -e}.
     */
    static double[] negate(double[] e) {
        double[] h = new double[e.length];
        for (int i = 0; i < e.length; i++) {
            h[i] = -e[i];
        }
        return h;
    }

    /**
     * The exact difference of two expansions.
     */
    static double[] sub(double[] e, double[] f) {
        return sum(e, negate(f));
    }

    /**
     * The exact product of an expansion and a double (Shewchuk's scale-expansion with zero
     * elimination).
     */
    static double[] scale(double[] e, double b) {
        double[] h = new double[2 * e.length];
        int hindex = 0;
        double p1 = e[0] * b;
        double p0 = Math.fma(e[0], b, -p1);
        double q = p1;
        if (p0 != 0.0) {
            h[hindex++] = p0;
        }
        for (int i = 1; i < e.length; i++) {
            double t1 = e[i] * b;
            double t0 = Math.fma(e[i], b, -t1);
            double sum = q + t0;
            double bv = sum - q;
            double av = sum - bv;
            double hh = (q - av) + (t0 - bv);
            if (hh != 0.0) {
                h[hindex++] = hh;
            }
            q = t1 + sum;
            hh = sum - (q - t1);
            if (hh != 0.0) {
                h[hindex++] = hh;
            }
        }
        if (q != 0.0 || hindex == 0) {
            h[hindex++] = q;
        }
        return hindex == h.length ? h : Arrays.copyOf(h, hindex);
    }

    /**
     * The exact product of two expansions.
     */
    static double[] mul(double[] e, double[] f) {
        double[] big = e.length >= f.length ? e : f;
        double[] small = big == e ? f : e;
        double[] acc = scale(big, small[0]);
        for (int i = 1; i < small.length; i++) {
            acc = sum(acc, scale(big, small[i]));
        }
        return acc;
    }

    /**
     * The sign of the expansion: that of its largest (last) component, 0 when it is zero.
     */
    static int sign(double[] e) {
        double top = e[e.length - 1];
        return top > 0.0 ? 1 : top < 0.0 ? -1 : 0;
    }

    /**
     * An estimate of the value: the sum of the components, accurate to one rounding.
     */
    static double estimate(double[] e) {
        double s = 0.0;
        for (double c : e) {
            s += c;
        }
        return s;
    }
}
