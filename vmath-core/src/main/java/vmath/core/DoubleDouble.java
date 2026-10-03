package vmath.core;

import vmath.annotations.Experimental;

/**
 * A number held as the unevaluated sum of two doubles, {@code hi + lo}, with {@code |lo|} at most
 * half a unit in the last place of {@code hi}: about 106 bits of significand (32 decimal digits) at
 * the cost of a handful of double operations, for the places where a double is not enough and
 * {@link java.math.BigDecimal} is far too slow: accumulating sums of many terms, the cancellation
 * in a long chain of subtractions, evaluating a polynomial near a root.
 *
 * <p>Addition, subtraction, multiplication and division have a relative error of at most
 * {@value #EPSILON} ({@code 2^-102}); {@link #sqrt()} a little more (the figures are measured
 * against 60-digit {@code BigDecimal} arithmetic in {@code DoubleDoubleTest}). The range is that of
 * a double, with the exponent range halved at the bottom: results below about {@code 1e-290} lose
 * the low part to underflow, and an overflow turns {@code hi} infinite and {@code lo} NaN.
 * {@link #twoSum} and {@link #twoProduct} are <em>exact</em> for every finite input whose product
 * does not underflow, which is what the robust geometric predicates are built on.
 *
 * <p>Values are immutable and compare by value ({@code record} semantics: bitwise on the two
 * components, so {@code -0.0} and {@code 0.0} differ; use {@link #compareTo} for numeric order).
 *
 * <p><b>Thread safety.</b> Immutable: safe to share between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * DoubleDouble sum = DoubleDouble.twoSum(1e16, 1.0);           // exact: hi + lo = 1e16 + 1
 * DoubleDouble x = DoubleDouble.of(0.1).mul(DoubleDouble.of(3.0)).sub(DoubleDouble.of(0.3));
 * int sign = x.signum();                                       // the sign of the tiny difference
 * double rounded = sum.hi();
 * }</pre>
 *
 * @param hi the leading double, which is the value rounded to double
 * @param lo the remainder, at most half a unit in the last place of {@code hi}
 */
@Experimental("the set of operations may grow; the representation (hi, lo) is expected to stay")
public record DoubleDouble(double hi, double lo) implements Comparable<DoubleDouble> {

    /**
     * The relative error bound of the four arithmetic operations: {@code 2^-102}.
     */
    public static final double EPSILON = 0x1p-102;

    /**
     * Zero.
     */
    public static final DoubleDouble ZERO = new DoubleDouble(0.0, 0.0);
    /**
     * One.
     */
    public static final DoubleDouble ONE = new DoubleDouble(1.0, 0.0);
    /**
     * Pi to 32 digits.
     */
    public static final DoubleDouble PI = new DoubleDouble(3.141592653589793, 1.2246467991473532e-16);

    /**
     * Widens a double to a double-double with an exact zero low part; no precision is lost.
     *
     * @param x the x component
     * @return the value of the double {@code x}, exactly
     */
    public static DoubleDouble of(double x) {
        return new DoubleDouble(x, 0.0);
    }

    /**
     * Adds two doubles exactly using Knuth's branch-free two-sum, so that no bits are lost; the
     * building block for the double-double additions.
     *
     * <p>Exact for all finite {@code a} and {@code b} whose sum does not overflow.
     *
     * @param a the first addend
     * @param b the second addend
     * @return the exact sum {@code a + b} (Knuth's two-sum): {@code hi} is {@code a + b} rounded,
     *     {@code lo} the rounding error
     */
    public static DoubleDouble twoSum(double a, double b) {
        double s = a + b;
        double bb = s - a;
        double err = (a - (s - bb)) + (b - bb);
        return new DoubleDouble(s, err);
    }

    /**
     * Multiplies two doubles exactly, recovering the rounding error with a fused multiply-add; slow
     * where no hardware FMA is available.
     *
     * <p>Exact for all finite inputs whose product neither overflows nor underflows below
     * {@code 2^-969}.
     *
     * @param a the first factor
     * @param b the second factor
     * @return the exact product {@code a * b}, using a fused multiply-add for the rounding error
     */
    public static DoubleDouble twoProduct(double a, double b) {
        double p = a * b;
        return new DoubleDouble(p, Math.fma(a, b, -p));
    }

    /**
     * {@code hi + lo} renormalised, for {@code |hi| >= |lo|} or {@code hi == 0}.
     */
    private static DoubleDouble quick(double hi, double lo) {
        double s = hi + lo;
        return new DoubleDouble(s, lo - (s - hi));
    }

    /**
     * Adds two double-double numbers by combining the high and the low parts with error-free
     * two-sums; keeps roughly 106 bits of significand.
     *
     * @param o the other double double; must not be {@code null}
     * @return {@code this + o}
     */
    public DoubleDouble add(DoubleDouble o) {
        DoubleDouble s = twoSum(hi, o.hi);
        DoubleDouble t = twoSum(lo, o.lo);
        double e = s.lo + t.hi;
        DoubleDouble u = quick(s.hi, e);
        return quick(u.hi, u.lo + t.lo);
    }

    /**
     * Adds a double to this double-double; cheaper than widening the operand first.
     *
     * @param x the x component
     * @return {@code this + x}
     */
    public DoubleDouble add(double x) {
        DoubleDouble s = twoSum(hi, x);
        return quick(s.hi, s.lo + lo);
    }

    /**
     * Subtracts a double-double from this one by adding its negation.
     *
     * @param o the other double double; must not be {@code null}
     * @return {@code this - o}
     */
    public DoubleDouble sub(DoubleDouble o) {
        return add(o.negate());
    }

    /**
     * Subtracts a double from this double-double by adding its negation.
     *
     * @param x the x component
     * @return {@code this - x}
     */
    public DoubleDouble sub(double x) {
        return add(-x);
    }

    /**
     * Negates both parts of the value; exact.
     *
     * @return {@code -this}
     */
    public DoubleDouble negate() {
        return new DoubleDouble(-hi, -lo);
    }

    /**
     * Takes the absolute value; exact, and decides the sign from the high part, falling back to the
     * low part when the high part is zero.
     *
     * @return the absolute value
     */
    public DoubleDouble abs() {
        return hi < 0.0 || (hi == 0.0 && lo < 0.0) ? negate() : this;
    }

    /**
     * Multiplies two double-double numbers: an exact product of the high parts plus the cross
     * terms; about 106 bits of precision.
     *
     * @param o the other double double; must not be {@code null}
     * @return {@code this * o}
     */
    public DoubleDouble mul(DoubleDouble o) {
        DoubleDouble p = twoProduct(hi, o.hi);
        return quick(p.hi, p.lo + (hi * o.lo + lo * o.hi));
    }

    /**
     * Multiplies this double-double by a double; cheaper than widening the operand first.
     *
     * @param x the x component
     * @return {@code this * x}
     */
    public DoubleDouble mul(double x) {
        DoubleDouble p = twoProduct(hi, x);
        return quick(p.hi, p.lo + lo * x);
    }

    /**
     * Divides by a double-double using long division with two correction terms; division by zero
     * follows the IEEE rules for doubles.
     *
     * @param o the other double double; must not be {@code null}
     * @return {@code this / o}; dividing by zero gives infinities or NaN as for doubles
     */
    public DoubleDouble div(DoubleDouble o) {
        double q1 = hi / o.hi;
        DoubleDouble r = sub(o.mul(q1));
        double q2 = r.hi / o.hi;
        r = r.sub(o.mul(q2));
        double q3 = r.hi / o.hi;
        DoubleDouble q = quick(q1, q2);
        return q.add(q3);
    }

    /**
     * Divides this double-double by a double; division by zero follows the IEEE rules for doubles.
     *
     * @param x the x component
     * @return {@code this / x}
     */
    public DoubleDouble div(double x) {
        return div(of(x));
    }

    /**
     * Inverts the value by dividing one by it; division by zero follows the IEEE rules for doubles.
     *
     * @return {@code 1 / this}
     */
    public DoubleDouble reciprocal() {
        return ONE.div(this);
    }

    /**
     * Takes the square root with one Newton correction of the double square root, which roughly
     * doubles the number of correct bits.
     *
     * @return the square root (one Newton step from the double square root); NaN for a negative
     *     value
     */
    public DoubleDouble sqrt() {
        if (hi == 0.0) {
            return ZERO;
        }
        if (hi < 0.0) {
            return new DoubleDouble(Double.NaN, Double.NaN);
        }
        double y = Math.sqrt(hi);
        DoubleDouble residual = sub(twoProduct(y, y));
        return quick(y, residual.hi / (2.0 * y));
    }

    /**
     * Collapses the double-double to a single double by adding the two parts; the only step where
     * precision is lost.
     *
     * @return the value rounded to a double
     */
    public double toDouble() {
        return hi + lo;
    }

    /**
     * Classifies the sign of the value from both parts, so that a tiny nonzero low part still
     * counts.
     *
     * @return -1, 0 or 1 by the sign of the value
     */
    public int signum() {
        return hi > 0.0 || (hi == 0.0 && lo > 0.0) ? 1 : hi < 0.0 || lo < 0.0 ? -1 : 0;
    }

    /**
     * Checks that neither the high nor the low part is infinite or NaN.
     *
     * @return {@code true} when both parts are finite
     */
    public boolean isFinite() {
        return Double.isFinite(hi) && Double.isFinite(lo);
    }

    /**
     * Orders double-doubles by numeric value.
     *
     * @param o the other double double; must not be {@code null}
     * @return numeric order (NaN is not ordered: the result is then that of {@link Double#compare}
     *     on the parts)
     */
    @Override
    public int compareTo(DoubleDouble o) {
        int c = Double.compare(hi, o.hi);
        return c != 0 ? c : Double.compare(lo, o.lo);
    }

    @Override
    public String toString() {
        return "(" + hi + " + " + lo + ")";
    }
}
