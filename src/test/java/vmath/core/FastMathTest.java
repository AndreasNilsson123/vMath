package vmath.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;
import vmath.Report;

/**
 * {@link FastMath} against {@link Math} in double precision. The sweeps walk the float bit patterns with a stride (so every magnitude is covered, not only the
 * ones a uniform grid favours) and compare with the exact function evaluated in double; the largest error found must stay under the bound the class documents.
 * {@code -Dvmath.verbose=true} prints the measured maxima, which are the figures in docs/FASTMATH.md.
 */
class FastMathTest {

    private static final int SAMPLES = Math.max(4_000_000, 200 * Rnd.N);

    /** A float function of one float argument; {@code java.util.function} has none. */
    @FunctionalInterface
    interface FloatUnaryOperator {
        float apply(float x);
    }

    private record Worst(double error, float at) {
    }

    /** Walks the bit patterns of the floats in {@code [from, to]} (both of the same sign, to further from zero) with a stride and returns the largest error. */
    private static Worst sweep(float from, float to, FloatUnaryOperator fast, DoubleUnaryOperator exact, boolean relative) {
        int lo = Float.floatToRawIntBits(Math.abs(from) < Math.abs(to) ? from : to) & 0x7FFFFFFF;
        int hi = Float.floatToRawIntBits(Math.abs(from) < Math.abs(to) ? to : from) & 0x7FFFFFFF;
        int sign = from < 0f || to < 0f ? 0x80000000 : 0;
        long span = (long) hi - lo;
        long stride = Math.max(1L, span / SAMPLES);
        double worst = 0;
        float worstAt = 0;
        for (long b = lo; b <= hi; b += stride) {
            float x = Float.intBitsToFloat((int) b | sign);
            double truth = exact.applyAsDouble(x);
            double got = fast.apply(x);
            double e = Math.abs(got - truth);
            if (relative) {
                e = truth == 0 ? e : e / Math.abs(truth);
            }
            if (e > worst) {
                worst = e;
                worstAt = x;
            }
        }
        return new Worst(worst, worstAt);
    }

    private static void check(String name, Worst w, double bound) {
        Report.printf("FastMath %s: largest error %.3e at %s (documented %.1e)%n", name, w.error, w.at, bound);
        assertTrue(w.error <= bound, name + " error " + w.error + " at " + w.at + " exceeds the documented " + bound);
    }

    @Test
    void sinAndCosStayWithinTheirBound() {
        for (float[] range : new float[][] {{0f, 1e-3f}, {1e-3f, 10f}, {10f, 1000f}, {1000f, 1e6f}, {-1e6f, -1f}}) {
            check("sin " + range[0] + ".." + range[1], sweep(range[0], range[1], FastMath::sin, Math::sin, false), FastMath.SIN_MAX_ERROR);
            check("cos " + range[0] + ".." + range[1], sweep(range[0], range[1], FastMath::cos, Math::cos, false), FastMath.COS_MAX_ERROR);
        }
    }

    @Test
    void trigonometryIdentitiesAndExactPoints() {
        assertEquals(0f, FastMath.sin(0f));
        assertEquals(1f, FastMath.cos(0f));
        assertEquals(1f, FastMath.sin((float) (Math.PI / 2)), 1e-7f);
        assertEquals(-1f, FastMath.cos((float) Math.PI), 1e-7f);
        assertTrue(Float.isNaN(FastMath.sin(Float.NaN)) && Float.isNaN(FastMath.cos(Float.POSITIVE_INFINITY)));
        assertEquals((float) Math.sin(3e9f), FastMath.sin(3e9f), "beyond the limit it is Math.sin");
        assertEquals(-0f, FastMath.sin(-0f), "sin keeps the sign of zero");
        SplittableRandom r = new SplittableRandom(Rnd.SEED);
        for (int i = 0; i < 100_000; i++) {
            float x = (float) ((r.nextDouble() - 0.5) * 200);
            float s = FastMath.sin(x), c = FastMath.cos(x);
            assertEquals(1.0, (double) s * s + (double) c * c, 4e-7, "sin^2 + cos^2 at " + x);
        }
    }

    @Test
    void atanAndAtan2StayWithinTheirBound() {
        check("atan small", sweep(0f, 1f, FastMath::atan, Math::atan, false), FastMath.ATAN_MAX_ERROR);
        check("atan large", sweep(1f, 3e38f, FastMath::atan, Math::atan, false), FastMath.ATAN_MAX_ERROR);
        check("atan negative", sweep(-1e10f, -1e-10f, FastMath::atan, Math::atan, false), FastMath.ATAN_MAX_ERROR);
        SplittableRandom r = new SplittableRandom(Rnd.SEED + 1);
        double worst = 0;
        for (int i = 0; i < SAMPLES; i++) {
            float y = (float) ((r.nextDouble() - 0.5) * Math.pow(10, r.nextInt(12) - 6));
            float x = (float) ((r.nextDouble() - 0.5) * Math.pow(10, r.nextInt(12) - 6));
            if (x == 0f && y == 0f) {
                continue;
            }
            worst = Math.max(worst, Math.abs(FastMath.atan2(y, x) - Math.atan2(y, x)));
        }
        Report.printf("FastMath atan2: largest error %.3e (documented %.1e)%n", worst, FastMath.ATAN2_MAX_ERROR);
        assertTrue(worst <= FastMath.ATAN2_MAX_ERROR, "atan2 error " + worst);
    }

    @Test
    void atan2SpecialValuesAreMathsOwn() {
        float[] values = {0f, -0f, 1f, -1f, 2.5f, -2.5f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN, Float.MIN_VALUE, -Float.MIN_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE};
        for (float y : values) {
            for (float x : values) {
                float expected = (float) Math.atan2(y, x);
                float got = FastMath.atan2(y, x);
                if (Float.isNaN(expected)) {
                    assertTrue(Float.isNaN(got), "atan2(" + y + ", " + x + ")");
                } else {
                    assertEquals(expected, got, FastMath.ATAN2_MAX_ERROR, "atan2(" + y + ", " + x + ")");
                    if (expected == 0f) {
                        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(got), "sign of the zero of atan2(" + y + ", " + x + ")");
                    }
                }
            }
        }
        assertEquals(-0f, FastMath.atan2(-0f, 1f));
        assertEquals((float) -Math.PI, FastMath.atan2(-0f, -1f), 1e-7f);
    }

    @Test
    void acosAndAsinStayWithinTheirBound() {
        check("acos", sweep(0f, 1f, FastMath::acos, Math::acos, false), FastMath.ACOS_MAX_ERROR);
        check("acos negative", sweep(-1f, -0f, FastMath::acos, Math::acos, false), FastMath.ACOS_MAX_ERROR);
        check("asin", sweep(0f, 1f, FastMath::asin, Math::asin, false), FastMath.ACOS_MAX_ERROR);
        check("asin negative", sweep(-1f, -0f, FastMath::asin, Math::asin, false), FastMath.ACOS_MAX_ERROR);
        assertTrue(Float.isNaN(FastMath.acos(1.0000001f)) && Float.isNaN(FastMath.asin(-2f)) && Float.isNaN(FastMath.acos(Float.NaN)));
        assertEquals(0f, FastMath.acos(1f), 1e-7f);
        assertEquals((float) Math.PI, FastMath.acos(-1f), 1e-7f);
    }

    @Test
    void expStaysWithinItsBound() {
        check("exp -87..-1", sweep(-87f, -1f, FastMath::exp, Math::exp, true), FastMath.EXP_MAX_RELATIVE_ERROR);
        check("exp -1..1", sweep(-1f, 1f, FastMath::exp, Math::exp, true), FastMath.EXP_MAX_RELATIVE_ERROR);
        check("exp 1..88", sweep(1f, 87.99f, FastMath::exp, Math::exp, true), FastMath.EXP_MAX_RELATIVE_ERROR);
        assertEquals(1f, FastMath.exp(0f));
        assertEquals(Float.POSITIVE_INFINITY, FastMath.exp(100f));
        assertEquals(0f, FastMath.exp(-200f));
        assertTrue(Float.isNaN(FastMath.exp(Float.NaN)));
        assertEquals((float) Math.exp(-95.0), FastMath.exp(-95f), 1e-44f, "subnormal results are Math's");
    }

    @Test
    void logStaysWithinItsBound() {
        check("log 1e-37..0.5", sweep(1.2e-38f, 0.5f, FastMath::log, Math::log, true), FastMath.LOG_MAX_ERROR);
        check("log 0.5..2", sweep(0.5f, 2f, FastMath::log, Math::log, true), FastMath.LOG_MAX_ERROR);
        check("log 2..max", sweep(2f, Float.MAX_VALUE, FastMath::log, Math::log, true), FastMath.LOG_MAX_ERROR);
        assertEquals(0f, FastMath.log(1f));
        assertEquals(Float.NEGATIVE_INFINITY, FastMath.log(0f));
        assertTrue(Float.isNaN(FastMath.log(-1f)) && Float.isNaN(FastMath.log(Float.NaN)));
        assertEquals(Float.POSITIVE_INFINITY, FastMath.log(Float.POSITIVE_INFINITY));
        assertEquals((float) Math.log(Float.MIN_VALUE), FastMath.log(Float.MIN_VALUE), "subnormals are Math's");
    }
}
