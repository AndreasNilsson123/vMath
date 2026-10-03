package vmath.core;

import vmath.annotations.Experimental;

/**
 * Polynomial approximations of the elementary functions for {@code float} arguments, for code that
 * calls them millions of times and can live with an error of a few units in the seventh digit.
 *
 * <p>Opt-in: nothing in the library calls this class on your behalf, and {@code Math} stays the
 * default everywhere.
 *
 * <p>Every method says how large its error is and for which arguments. The bounds are the largest
 * errors measured against {@link Math} in double precision over dense sweeps (tens of millions of
 * arguments for each function, {@code FastMathTest}); they are not proofs, and the tests fail if a
 * bound is exceeded. Arguments outside the stated range, and the special values (NaN, infinities,
 * zero where it matters), are handed to {@code Math} unchanged, so those results are exactly
 * {@code Math}'s and the speed advantage does not apply to them.
 *
 * <p>Whether they are faster than {@code Math} depends on the JVM and the machine:
 * {@code docs/FASTMATH.md} has the measurements, including the functions that were <em>not</em>
 * faster and what the class does about it.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * float s = FastMath.sin(1.0f);                                // an approximation, see the error bounds of the class
 * float c = FastMath.cos(1.0f);
 * float angle = FastMath.atan2(1f, 1f);                        // about pi / 4
 * }</pre>
 */
@Experimental("the set of functions follows what the measurements say is worth having")
public final class FastMath {

    private FastMath() {
    }

    private static final double PI = Math.PI;
    private static final double HALF_PI = Math.PI / 2;
    private static final double INV_PI = 1.0 / Math.PI;
    /**
     * Largest argument of sin and cos handled here: beyond it the reduction by pi loses digits and
     * {@code Math} takes over.
     */
    private static final float TRIG_LIMIT = 1.0e6f;

    // ---------------------------------------------------------------- sin and cos

    /**
     * Approximates the sine with a range-reduced polynomial; an alternative to {@code Math.sin}
     * that trades the last bits of accuracy for speed, and arguments beyond the reduction limit
     * fall back to the library function.
     *
     * <p>Absolute error at most {@value #SIN_MAX_ERROR} for {@code |x| <= 1e6}; larger arguments,
     * NaN and infinities are computed by {@code Math.sin}.
     *
     * @param x the x component
     * @return sine of {@code x} radians
     */
    public static float sin(float x) {
        if (!(Math.abs(x) <= TRIG_LIMIT) || x == 0f) {
            return x == 0f ? x : (float) Math.sin(x); // zero keeps its sign
        }
        double d = x;
        double q = Math.rint(d * INV_PI);
        double r = d - q * PI;
        double r2 = r * r;
        double s = r * (1.0 + r2 * (-1.0 / 6 + r2 * (1.0 / 120 + r2 * (-1.0 / 5040 + r2 * (1.0 / 362880 + r2 * (-1.0 / 39916800 + r2 * (1.0 / 6227020800.0)))))));
        return (float) (((long) q & 1L) == 0L ? s : -s);
    }

    /**
     * Approximates the cosine with a range-reduced polynomial; an alternative to {@code Math.cos}
     * that trades the last bits of accuracy for speed, and arguments beyond the reduction limit
     * fall back to the library function.
     *
     * <p>Absolute error at most {@value #COS_MAX_ERROR} for {@code |x| <= 1e6}; larger arguments,
     * NaN and infinities are computed by {@code Math.cos}.
     *
     * @param x the x component
     * @return cosine of {@code x} radians
     */
    public static float cos(float x) {
        if (!(Math.abs(x) <= TRIG_LIMIT)) {
            return (float) Math.cos(x);
        }
        double d = x;
        double q = Math.rint(d * INV_PI);
        double r = d - q * PI;
        double r2 = r * r;
        double c = 1.0 + r2 * (-0.5 + r2 * (1.0 / 24 + r2 * (-1.0 / 720 + r2 * (1.0 / 40320 + r2 * (-1.0 / 3628800 + r2 * (1.0 / 479001600))))));
        return (float) (((long) q & 1L) == 0L ? c : -c);
    }

    /**
     * Documented bound for {@link #sin}, in absolute error.
     */
    public static final float SIN_MAX_ERROR = 5.0e-8f;
    /**
     * Documented bound for {@link #cos}, in absolute error.
     */
    public static final float COS_MAX_ERROR = 5.0e-8f;

    // ---------------------------------------------------------------- inverse trigonometric functions

    // atan(x) = x * (c0 + c1 x^2 + ... + c7 x^14) on [0, 1], minimax fit (Lawson iteration on a 20 001 point grid)
    private static final double A0 = 0.9999993355437992, A1 = -0.33329860633602004, A2 = 0.19946563785653953, A3 = -0.13908619522678323;
    private static final double A4 = 0.09642169998239128, A5 = -0.0559119318200383, A6 = 0.021862669624250028, A7 = -0.004054483653039301;

    private static double atanUnit(double t) {
        double t2 = t * t;
        return t * (A0 + t2 * (A1 + t2 * (A2 + t2 * (A3 + t2 * (A4 + t2 * (A5 + t2 * (A6 + t2 * A7)))))));
    }

    /**
     * Approximates the arc tangent with a polynomial after reducing the argument to a small
     * interval; infinities and NaN fall back to {@code Math.atan}.
     *
     * <p>Absolute error at most {@value #ATAN_MAX_ERROR}; NaN is returned as NaN, infinities as
     * {@code +-pi/2}.
     *
     * @param x the x component
     * @return arc tangent, in {@code (-pi/2, pi/2)}
     */
    public static float atan(float x) {
        double a = Math.abs((double) x);
        if (!(a <= Double.MAX_VALUE)) {
            return (float) Math.atan(x); // NaN and infinities
        }
        double r = a <= 1.0 ? atanUnit(a) : HALF_PI - atanUnit(1.0 / a);
        return (float) (x < 0f ? -r : r);
    }

    /**
     * Approximates the two-argument arc tangent with a polynomial, choosing the quadrant from the
     * signs of the arguments; non-finite input falls back to {@code Math.atan2}.
     *
     * <p>Absolute error at most {@value #ATAN2_MAX_ERROR} when both arguments are finite and not
     * both zero; zero, infinite and NaN arguments are computed by {@code Math.atan2}, so the signs
     * of zeros and the quadrant conventions are exactly its.
     *
     * @param y the y component
     * @param x the x component
     * @return the angle of the point {@code (x, y)}, in {@code [-pi, pi]}, as {@code Math.atan2}
     */
    public static float atan2(float y, float x) {
        double ax = Math.abs((double) x), ay = Math.abs((double) y);
        if (!(ax <= Double.MAX_VALUE) || !(ay <= Double.MAX_VALUE) || (ax == 0.0 && ay == 0.0)) {
            return (float) Math.atan2(y, x);
        }
        double r = ay <= ax ? atanUnit(ay / ax) : HALF_PI - atanUnit(ax / ay);
        if (x < 0f || (x == 0f && Float.floatToRawIntBits(x) < 0)) {
            r = PI - r;
        }
        return (float) (Float.floatToRawIntBits(y) < 0 ? -r : r);
    }

    /**
     * Documented bound for {@link #atan}, in absolute error.
     */
    public static final float ATAN_MAX_ERROR = 1.2e-7f;
    /**
     * Documented bound for {@link #atan2}, in absolute error: the half unit in the last place of a
     * result near pi, plus the error of the polynomial.
     */
    public static final float ATAN2_MAX_ERROR = 2.0e-7f;

    // acos(x) = sqrt(1 - x) * (b0 + b1 x + ... + b7 x^7) on [0, 1] (Abramowitz and Stegun 4.4.46); acos(-x) = pi - acos(x)
    private static double acosPositive(double x) {
        double p = 1.5707963050 + x * (-0.2145988016 + x * (0.0889789874 + x * (-0.0501743046 + x * (0.0308918810 + x * (-0.0170881256 + x * (0.0066700901 + x * -0.0012624911))))));
        return Math.sqrt(1.0 - x) * p;
    }

    /**
     * Approximates the arc cosine with a polynomial; the argument must lie in {@code [-1, 1]},
     * otherwise the result is NaN.
     *
     * <p>Absolute error at most {@value #ACOS_MAX_ERROR}; NaN for arguments outside {@code [-1, 1]}
     * and for NaN.
     *
     * @param x the x component
     * @return arc cosine, in {@code [0, pi]}
     */
    public static float acos(float x) {
        if (!(Math.abs(x) <= 1f)) {
            return Float.NaN;
        }
        double d = x;
        return (float) (d >= 0.0 ? acosPositive(d) : PI - acosPositive(-d));
    }

    /**
     * Approximates the arc sine with a polynomial; the argument must lie in {@code [-1, 1]},
     * otherwise the result is NaN.
     *
     * <p>Absolute error at most {@value #ACOS_MAX_ERROR} (the relative error of a result near zero
     * can be much larger); NaN for arguments outside {@code [-1, 1]} and for NaN.
     *
     * @param x the x component
     * @return arc sine, in {@code [-pi/2, pi/2]}
     */
    public static float asin(float x) {
        if (!(Math.abs(x) <= 1f)) {
            return Float.NaN;
        }
        double d = x;
        return (float) (d >= 0.0 ? HALF_PI - acosPositive(d) : acosPositive(-d) - HALF_PI);
    }

    /**
     * Documented bound for {@link #acos} and {@link #asin}, in absolute error.
     */
    public static final float ACOS_MAX_ERROR = 1.5e-7f;

    // ---------------------------------------------------------------- exp and log

    private static final double LOG2E = 1.4426950408889634, LN2 = 0.6931471805599453;

    /**
     * Approximates the exponential function by splitting the argument into an integer power of two
     * and a polynomial; arguments outside the single-precision range fall back to {@code Math.exp}.
     *
     * <p>Relative error at most {@value #EXP_MAX_RELATIVE_ERROR} for results that are normal
     * floats; {@code +Infinity} above 88.72284, 0 below {@code -103.97} and a correctly scaled
     * subnormal in between (as {@code Math.exp}); NaN for NaN.
     *
     * @param x the x component
     * @return {@code e^x}
     */
    public static float exp(float x) {
        if (!(x > -87f && x < 88f)) {
            return (float) Math.exp(x); // overflow, underflow, subnormal results, NaN
        }
        double d = x;
        double k = Math.rint(d * LOG2E);
        double r = d - k * LN2; // |r| <= 0.3466
        double p = 1.0 + r * (1.0 + r * (0.5 + r * (1.0 / 6 + r * (1.0 / 24 + r * (1.0 / 120 + r * (1.0 / 720 + r * (1.0 / 5040 + r * (1.0 / 40320))))))));
        return (float) (p * Double.longBitsToDouble((long) (k + 1023.0) << 52));
    }

    /**
     * Documented bound for {@link #exp}, as a relative error.
     */
    public static final float EXP_MAX_RELATIVE_ERROR = 1.0e-7f;

    /**
     * Approximates the natural logarithm from the exponent bits and a polynomial in the mantissa;
     * subnormal, negative and non-finite input falls back to {@code Math.log}.
     *
     * <p>Relative error at most {@value #LOG_MAX_ERROR} for every positive normal argument (the
     * result is accurate to about half a unit in the last place); zero, negative, infinite and NaN
     * arguments, and subnormals, are computed by {@code Math.log}.
     *
     * @param x the x component
     * @return natural logarithm
     */
    public static float log(float x) {
        if (!(x >= Float.MIN_NORMAL && x <= Float.MAX_VALUE)) {
            return (float) Math.log(x);
        }
        int bits = Float.floatToRawIntBits(x);
        int e = (bits >>> 23) - 127;
        double m = Float.intBitsToFloat((bits & 0x007FFFFF) | 0x3F800000); // [1, 2)
        if (m > 1.4142135623730951) {
            m *= 0.5;
            e++;
        }
        double s = (m - 1.0) / (m + 1.0);
        double s2 = s * s;
        double ln = 2.0 * s * (1.0 + s2 * (1.0 / 3 + s2 * (1.0 / 5 + s2 * (1.0 / 7 + s2 * (1.0 / 9 + s2 * (1.0 / 11))))));
        return (float) (ln + e * LN2);
    }

    /**
     * Documented bound for {@link #log}, as a relative error.
     */
    public static final float LOG_MAX_ERROR = 1.0e-7f;
}
