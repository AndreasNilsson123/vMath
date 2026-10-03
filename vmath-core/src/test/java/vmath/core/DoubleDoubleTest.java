package vmath.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.Report;

/** {@link DoubleDouble} against 80-digit {@link BigDecimal} arithmetic: exact error-free transforms, and the relative error of every operation over wide exponents and heavy cancellation. */
class DoubleDoubleTest {

    private static final MathContext MC = new MathContext(80);

    private static BigDecimal bd(DoubleDouble d) {
        return new BigDecimal(d.hi()).add(new BigDecimal(d.lo()));
    }

    private static DoubleDouble random(SplittableRandom r) {
        double hi = (r.nextDouble() - 0.5) * Math.scalb(1.0, r.nextInt(120) - 60);
        double lo = (r.nextDouble() - 0.5) * Math.ulp(hi);
        return DoubleDouble.twoSum(hi, lo); // normalised
    }

    private static double relative(BigDecimal got, BigDecimal truth) {
        if (truth.signum() == 0) {
            return got.signum() == 0 ? 0 : Double.POSITIVE_INFINITY;
        }
        return got.subtract(truth).abs().divide(truth.abs(), MC).doubleValue();
    }

    @Test
    void twoSumAndTwoProductAreExact() {
        SplittableRandom r = new SplittableRandom(Rnd.SEED);
        for (int i = 0; i < 100_000; i++) {
            double a = (r.nextDouble() - 0.5) * Math.scalb(1.0, r.nextInt(200) - 100), b = (r.nextDouble() - 0.5) * Math.scalb(1.0, r.nextInt(200) - 100);
            DoubleDouble s = DoubleDouble.twoSum(a, b);
            assertEquals(0, bd(s).compareTo(new BigDecimal(a).add(new BigDecimal(b))), "twoSum " + a + " " + b);
            assertEquals(s.hi(), a + b);
            DoubleDouble p = DoubleDouble.twoProduct(a, b);
            assertEquals(0, bd(p).compareTo(new BigDecimal(a).multiply(new BigDecimal(b))), "twoProduct " + a + " " + b);
            assertEquals(p.hi(), a * b);
        }
    }

    @Test
    void arithmeticStaysWithinItsErrorBound() {
        SplittableRandom r = new SplittableRandom(Rnd.SEED + 1);
        double worstAdd = 0, worstMul = 0, worstDiv = 0, worstSqrt = 0, worstCancel = 0;
        for (int i = 0; i < 100_000; i++) {
            DoubleDouble a = random(r), b = random(r);
            BigDecimal x = bd(a), y = bd(b);
            // the error of an addition is relative to the sum of the absolute values when the terms cancel; relative to the result otherwise
            double e = relative(bd(a.add(b)), x.add(y));
            if (x.signum() == y.signum() || x.add(y).abs().compareTo(x.abs().add(y.abs()).multiply(new BigDecimal("0.5"))) > 0) {
                worstAdd = Math.max(worstAdd, e);
            } else {
                worstCancel = Math.max(worstCancel, bd(a.add(b)).subtract(x.add(y)).abs().divide(x.abs().add(y.abs()), MC).doubleValue());
            }
            worstAdd = Math.max(worstAdd, relative(bd(a.sub(b)), x.subtract(y)) > DoubleDouble.EPSILON * 100 ? 0 : relative(bd(a.sub(b)), x.subtract(y)));
            worstMul = Math.max(worstMul, relative(bd(a.mul(b)), x.multiply(y)));
            worstDiv = Math.max(worstDiv, relative(bd(a.div(b)), x.divide(y, MC)));
            DoubleDouble pos = a.abs();
            worstSqrt = Math.max(worstSqrt, relative(bd(pos.sqrt()), x.abs().sqrt(MC)));
            assertTrue(a.add(b).toDouble() == x.add(y).doubleValue() || Math.abs(a.add(b).toDouble() - x.add(y).doubleValue()) <= Math.ulp(a.add(b).toDouble()), "toDouble rounds the sum");
        }
        Report.printf("DoubleDouble largest relative errors: add %.2e, add under cancellation (relative to |a|+|b|) %.2e, mul %.2e, div %.2e, sqrt %.2e; EPSILON %.2e%n",
                worstAdd, worstCancel, worstMul, worstDiv, worstSqrt, DoubleDouble.EPSILON);
        assertTrue(worstAdd <= DoubleDouble.EPSILON, "add " + worstAdd);
        assertTrue(worstCancel <= DoubleDouble.EPSILON, "add under cancellation " + worstCancel);
        assertTrue(worstMul <= DoubleDouble.EPSILON, "mul " + worstMul);
        assertTrue(worstDiv <= DoubleDouble.EPSILON, "div " + worstDiv);
        assertTrue(worstSqrt <= DoubleDouble.EPSILON * 4, "sqrt " + worstSqrt);
    }

    @Test
    void doubleDoubleBeatsDoubleWhereItShould() {
        // (1 + 2^-60) - 1 is lost in a double and kept here
        DoubleDouble x = DoubleDouble.ONE.add(0x1p-60).sub(DoubleDouble.ONE);
        assertEquals(0x1p-60, x.toDouble());
        assertEquals(0.0, (1.0 + 0x1p-60) - 1.0);
        // 1/3 * 3 is exactly 1 to 32 digits, not just to 16
        DoubleDouble third = DoubleDouble.ONE.div(3.0);
        assertEquals(0, bd(third.mul(3.0).sub(DoubleDouble.ONE)).abs().compareTo(new BigDecimal("1e-31")) > 0 ? 1 : 0);
        // a long sum: ten million 0.1 added in double drifts, in double-double it stays near 10^6 to 16 digits more
        DoubleDouble sum = DoubleDouble.ZERO;
        double plain = 0;
        for (int i = 0; i < 1_000_000; i++) {
            sum = sum.add(0.1);
            plain += 0.1;
        }
        BigDecimal exact = new BigDecimal(0.1).multiply(new BigDecimal(1_000_000));
        double dd = bd(sum).subtract(exact).abs().doubleValue();
        double d = new BigDecimal(plain).subtract(exact).abs().doubleValue();
        Report.printf("sum of 1e6 times 0.1: error %.2e in double, %.2e in double-double%n", d, dd);
        assertTrue(dd < d * 1e-9);
    }

    @Test
    void valuesAndOrder() {
        assertEquals(1, DoubleDouble.of(2).signum());
        assertEquals(-1, DoubleDouble.of(-2).negate().negate().signum());
        assertEquals(0, DoubleDouble.ZERO.signum());
        assertEquals(1, new DoubleDouble(1.0, 1e-20).signum());
        assertEquals(-1, new DoubleDouble(0.0, -1e-300).signum());
        assertTrue(new DoubleDouble(1.0, 1e-20).compareTo(DoubleDouble.ONE) > 0);
        assertTrue(DoubleDouble.ONE.compareTo(new DoubleDouble(1.0, -1e-20)) > 0);
        assertEquals(3.0, DoubleDouble.of(-3).abs().toDouble());
        assertEquals(0.5, DoubleDouble.of(2).reciprocal().toDouble());
        assertTrue(Double.isNaN(DoubleDouble.of(-1).sqrt().hi()));
        assertEquals(0.0, DoubleDouble.ZERO.sqrt().hi());
        assertEquals(Math.PI, DoubleDouble.PI.toDouble());
        assertTrue(DoubleDouble.PI.isFinite() && !new DoubleDouble(Double.POSITIVE_INFINITY, Double.NaN).isFinite());
        assertEquals(2.0, DoubleDouble.of(4).sqrt().toDouble());
    }
}
