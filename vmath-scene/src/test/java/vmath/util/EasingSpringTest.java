package vmath.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vmath.core.Rnd;

class EasingSpringTest {

    // ------------------------------------------------------------ easing

    @Test
    void everyCurveStartsAtZeroEndsAtOneAndClamps() {
        for (Easing e : Easing.values()) {
            assertEquals(0.0, e.apply(0), 1e-12, e.name());
            assertEquals(1.0, e.apply(1), 1e-12, e.name());
            assertEquals(0.0, e.apply(-3), 0.0, e.name());
            assertEquals(1.0, e.apply(7), 0.0, e.name());
            assertTrue(Double.isNaN(e.apply(Double.NaN)), e.name());
            assertEquals(e.apply(0.3), e.mix(0, 1, 0.3), 1e-15);
            assertEquals(10 + 4 * e.apply(0.3), e.mix(10, 14, 0.3), 1e-12);
        }
    }

    @Test
    void outIsTheMirrorOfInAndInOutIsSymmetric() {
        String[] families = {"QUAD", "CUBIC", "QUART", "QUINT", "SINE", "EXPO", "CIRC", "BACK", "ELASTIC", "BOUNCE"};
        for (String f : families) {
            Easing in = Easing.valueOf(f + "_IN"), out = Easing.valueOf(f + "_OUT"), inOut = Easing.valueOf(f + "_IN_OUT");
            for (int i = 1; i < 100; i++) {
                double t = i / 100.0;
                // the elastic and back "out" curves are exact mirrors; allow rounding in the large-exponent cases
                assertEquals(1 - in.apply(1 - t), out.apply(t), 1e-9, f + " out at " + t);
                assertEquals(1.0, inOut.apply(t) + inOut.apply(1 - t), 1e-9, f + " in-out at " + t);
            }
            assertEquals(0.5, inOut.apply(0.5), 1e-9, f);
        }
    }

    @Test
    void theMonotonicFamiliesNeverGoBackwards() {
        String[] families = {"QUAD", "CUBIC", "QUART", "QUINT", "SINE", "EXPO", "CIRC"};
        for (String f : families) {
            for (String form : new String[] {"_IN", "_OUT", "_IN_OUT"}) {
                Easing e = Easing.valueOf(f + form);
                double prev = e.apply(0);
                for (int i = 1; i <= 1000; i++) {
                    double v = e.apply(i / 1000.0);
                    assertTrue(v >= prev - 1e-12, e + " at " + i / 1000.0);
                    prev = v;
                }
            }
        }
        assertEquals(0.1, Easing.LINEAR.apply(0.1), 0.0);
    }

    @Test
    void knownValues() {
        assertEquals(0.25, Easing.QUAD_IN.apply(0.5), 1e-15);
        assertEquals(0.125, Easing.CUBIC_IN.apply(0.5), 1e-15);
        assertEquals(1.0 / 16, Easing.QUART_IN.apply(0.5), 1e-15);
        assertEquals(1.0 / 32, Easing.QUINT_IN.apply(0.5), 1e-15);
        assertEquals(1 - Math.sqrt(0.5), Easing.SINE_IN.apply(0.5), 1e-15);
        assertEquals(1 - Math.sqrt(0.75), Easing.CIRC_IN.apply(0.5), 1e-15);
        assertEquals(0.765625, Easing.BOUNCE_OUT.apply(0.5), 1e-15);
        assertEquals(1.0, Easing.BOUNCE_OUT.apply(1.0 / 2.75), 1e-12); // the first touch of the ground
        // BACK_IN dips below zero (to about -0.1), BACK_OUT overshoots 1 (to about 1.1), ELASTIC_OUT swings above and below 1
        double minBack = 1, maxBack = 0, maxElastic = 0, minElastic = 2;
        for (int i = 0; i <= 1000; i++) {
            minBack = Math.min(minBack, Easing.BACK_IN.apply(i / 1000.0));
            maxBack = Math.max(maxBack, Easing.BACK_OUT.apply(i / 1000.0));
            maxElastic = Math.max(maxElastic, Easing.ELASTIC_OUT.apply(i / 1000.0));
            minElastic = Math.min(minElastic, Easing.ELASTIC_OUT.apply(i / 1000.0));
        }
        assertEquals(-0.0998, minBack, 1e-3);
        assertEquals(1.0998, maxBack, 1e-3);
        assertTrue(maxElastic > 1.3 && minElastic > -1e-9, maxElastic + " " + minElastic);
        // the exponential curves reach exactly 0 and 1 at the ends
        assertEquals(0.0, Easing.EXPO_IN.apply(1e-9), 1e-3);
        assertTrue(Easing.EXPO_IN.apply(0.5) < 0.04);
    }

    // ------------------------------------------------------------ spring

    /** The spring equation integrated with small classical Runge-Kutta steps: an oracle that shares no code with the closed form. */
    private static double[] rk4(double x, double v, double target, double w, double z, double time, int steps) {
        double h = time / steps;
        for (int i = 0; i < steps; i++) {
            double k1x = v, k1v = -w * w * (x - target) - 2 * z * w * v;
            double k2x = v + 0.5 * h * k1v, k2v = -w * w * (x + 0.5 * h * k1x - target) - 2 * z * w * (v + 0.5 * h * k1v);
            double k3x = v + 0.5 * h * k2v, k3v = -w * w * (x + 0.5 * h * k2x - target) - 2 * z * w * (v + 0.5 * h * k2v);
            double k4x = v + h * k3v, k4v = -w * w * (x + h * k3x - target) - 2 * z * w * (v + h * k3v);
            x += h / 6 * (k1x + 2 * k2x + 2 * k3x + k4x);
            v += h / 6 * (k1v + 2 * k2v + 2 * k3v + k4v);
        }
        return new double[] {x, v};
    }

    @Test
    void theClosedFormMatchesNumericalIntegration() {
        Rnd rnd = Rnd.create();
        double[] out = new double[2];
        for (int t = 0; t < 300; t++) {
            double x = rnd.range(-5, 5), v = rnd.range(-10, 10), target = rnd.range(-5, 5), w = rnd.range(0.5, 20), dt = rnd.range(0.001, 1.0);
            double z = new double[] {0.0, 0.1, 0.5, 0.9, 1.0, 1.0 + 1e-6, 1.5, 4.0}[t % 8];
            Spring.step(x, v, target, w, z, dt, out);
            double[] ref = rk4(x, v, target, w, z, dt, 4000);
            assertEquals(ref[0], out[0], 1e-6 * (1 + Math.abs(ref[0])), "position, z = " + z);
            assertEquals(ref[1], out[1], 1e-5 * (1 + Math.abs(ref[1])), "velocity, z = " + z);
        }
    }

    @Test
    void steppingIsIndependentOfHowTheTimeIsSplit() {
        double[] a = new double[2], b = new double[2], c = new double[2];
        for (double z : new double[] {0.0, 0.3, 1.0, 2.5}) {
            Spring.step(2.0, -1.0, 5.0, 7.0, z, 0.1, a);
            Spring.step(a[0], a[1], 5.0, 7.0, z, 0.25, b);
            Spring.step(2.0, -1.0, 5.0, 7.0, z, 0.35, c);
            assertEquals(c[0], b[0], 1e-12, "z = " + z);
            assertEquals(c[1], b[1], 1e-11, "z = " + z);
        }
    }

    @Test
    void criticalDampingNeverOvershootsAndLessDampingDoes() {
        Spring critical = new Spring(0.0), light = new Spring(0.0), heavy = new Spring(0.0);
        double maxCritical = 0, maxLight = 0, maxHeavy = 0;
        for (int i = 0; i < 600; i++) {
            maxCritical = Math.max(maxCritical, critical.update(1.0, 10.0, 1.0, 0.01));
            maxLight = Math.max(maxLight, light.update(1.0, 10.0, 0.2, 0.01));
            maxHeavy = Math.max(maxHeavy, heavy.update(1.0, 10.0, 3.0, 0.01));
        }
        assertTrue(maxCritical <= 1.0 + 1e-12, "critical " + maxCritical);
        assertTrue(maxHeavy <= 1.0 + 1e-12, "overdamped " + maxHeavy);
        assertTrue(maxLight > 1.2, "underdamped " + maxLight);
        assertEquals(1.0, critical.position, 1e-9);
        assertEquals(0.0, critical.velocity, 1e-9);
        assertEquals(1.0, light.position, 1e-4);
        assertEquals(1.0, heavy.position, 1e-4);
    }

    @Test
    void anUndampedSpringOscillatesForever() {
        double[] out = new double[2];
        Spring.step(1.0, 0.0, 0.0, 2.0, 0.0, Math.PI, out);
        assertEquals(1.0, out[0], 1e-12); // a full period of 2 pi / w
        Spring.step(1.0, 0.0, 0.0, 2.0, 0.0, Math.PI / 2, out);
        assertEquals(-1.0, out[0], 1e-12);
    }

    @Test
    void halfLifeMakesAnExactHalf() {
        for (double seconds : new double[] {0.05, 0.3, 2.0}) {
            double w = Spring.halfLife(seconds);
            double[] out = new double[2];
            Spring.step(0.0, 0.0, 1.0, w, 1.0, seconds, out);
            assertEquals(0.5, out[0], 1e-9, "half the distance after one half-life");
            Spring.step(0.0, 0.0, 1.0, w, 1.0, 2 * seconds, out);
            double u = 1.678346990016661;
            assertEquals(1 - (1 + 2 * u) * Math.exp(-2 * u), out[0], 1e-9, "after two half-lives");
        }
    }

    @Test
    void restingAtTheTargetStaysThereAndZeroTimeChangesNothing() {
        Spring s = new Spring(3.0);
        for (int i = 0; i < 10; i++) {
            s.update(3.0, 5.0, 0.7, 0.1);
        }
        assertEquals(3.0, s.position, 1e-15);
        assertEquals(0.0, s.velocity, 1e-15);
        s.position = 1;
        s.velocity = 2;
        assertEquals(1.0, s.update(9, 5, 1, 0), 0.0);
        assertEquals(1.0, s.update(9, 5, 1, -1), 0.0);
        assertEquals(2.0, s.velocity, 0.0);
        assertEquals(0.0, new Spring().position);
        assertThrows(IllegalArgumentException.class, () -> s.update(1, 0, 1, 0.1));
        assertThrows(IllegalArgumentException.class, () -> s.update(1, 1, -1, 0.1));
        assertThrows(IllegalArgumentException.class, () -> s.update(1, Double.NaN, 1, 0.1));
    }

    @Test
    void aHugeTimeStepIsStable() {
        double[] out = new double[2];
        for (double z : new double[] {0.1, 1.0, 5.0}) {
            Spring.step(10.0, 100.0, 2.0, 50.0, z, 1e6, out);
            assertEquals(2.0, out[0], 1e-9);
            assertEquals(0.0, out[1], 1e-9);
        }
    }

    @Test
    void springOnArraysMatchesTheScalarForm() {
        float[] pos = {0, 1, 2, 3, 4}, vel = {0, 0.5f, -1, 2, 0};
        float[] target = {9, 9, 8, 7, 6, 5};
        double[] out = new double[2];
        double[][] expected = new double[3][];
        for (int i = 0; i < 3; i++) {
            Spring.step(pos[1 + i], vel[1 + i], target[2 + i], 4.0, 0.6, 0.2, out);
            expected[i] = out.clone();
        }
        Spring.step(pos, vel, 1, 3, target, 2, 4.0, 0.6, 0.2);
        for (int i = 0; i < 3; i++) {
            assertEquals((float) expected[i][0], pos[1 + i]);
            assertEquals((float) expected[i][1], vel[1 + i]);
        }
        assertEquals(0f, pos[0]);
        assertEquals(4f, pos[4]);
    }

    // ------------------------------------------------------------ smoothing

    @Test
    void exponentialSmoothingIsFrameRateIndependent() {
        double one = Smoothing.towards(0, 10, 3.0, 1.0 / 30);
        double two = Smoothing.towards(Smoothing.towards(0, 10, 3.0, 1.0 / 60), 10, 3.0, 1.0 / 60);
        assertEquals(one, two, 1e-12);
        double sixty = 0, hundredTwenty = 0;
        for (int i = 0; i < 60; i++) {
            sixty = Smoothing.towards(sixty, 1, 5.0, 1.0 / 60);
        }
        for (int i = 0; i < 120; i++) {
            hundredTwenty = Smoothing.towards(hundredTwenty, 1, 5.0, 1.0 / 120);
        }
        assertEquals(sixty, hundredTwenty, 1e-12);
        assertEquals(1 - Math.exp(-5.0), sixty, 1e-12);
        assertEquals(0.0, Smoothing.factor(5, 0), 0.0);
        assertTrue(Smoothing.factor(5, 100) <= 1.0);
        double h = Smoothing.towardsHalfLife(0, 8, 0.5, 0.5);
        assertEquals(4.0, h, 1e-12);
        assertEquals(6.0, Smoothing.towardsHalfLife(0, 8, 0.5, 1.0), 1e-12);
        assertEquals(0.5, Smoothing.factorForHalfLife(2, 2), 1e-15);
    }

    @Test
    void vectorSmoothingMovesEveryComponent() {
        float[] v = {0, 1, 2, 3}, t = {9, 10, 20, 30, 40};
        Smoothing.towards(v, 1, t, 2, 2, 1e9, 1.0); // a huge rate reaches the target
        assertEquals(0f, v[0]);
        assertEquals(20f, v[1]);
        assertEquals(30f, v[2]);
        assertEquals(3f, v[3]);
        float[] w = {0, 0};
        Smoothing.towards(w, 0, new float[] {10, -10}, 0, 2, 1.0, 1.0);
        assertEquals((float) (10 * (1 - Math.exp(-1))), w[0], 1e-5);
        assertEquals((float) (-10 * (1 - Math.exp(-1))), w[1], 1e-5);
    }

    @Test
    void movingTowardsAndAnglesAndStepFunctions() {
        assertEquals(1.5, Smoothing.moveTowards(1, 5, 0.5));
        assertEquals(5.0, Smoothing.moveTowards(4.9, 5, 0.5));
        assertEquals(-2.0, Smoothing.moveTowards(0, -10, 2));
        assertEquals(5.0, Smoothing.moveTowards(5, 5, 1));
        assertEquals(0.2, Smoothing.angleDelta(0.1, 0.3), 1e-12);
        assertEquals(-0.2, Smoothing.angleDelta(0.3, 0.1), 1e-12);
        assertEquals(0.2, Smoothing.angleDelta(2 * Math.PI - 0.1, 0.1), 1e-12);
        assertEquals(-0.2, Smoothing.angleDelta(0.1, 2 * Math.PI - 0.1), 1e-12);
        assertEquals(0.5, Smoothing.angleDelta(0, 0.5 + 6 * Math.PI), 1e-10);
        assertEquals(-0.5, Smoothing.angleDelta(0, -0.5 - 6 * Math.PI), 1e-10);
        assertEquals(0.0, Smoothing.smoothstep(1, 3, 0.5));
        assertEquals(1.0, Smoothing.smoothstep(1, 3, 4));
        assertEquals(0.5, Smoothing.smoothstep(1, 3, 2), 1e-15);
        assertEquals(0.5, Smoothing.smootherstep(1, 3, 2), 1e-15);
        assertEquals(0.0, Smoothing.smootherstep(1, 3, 0));
        assertEquals(1.0, Smoothing.smootherstep(1, 3, 9));
        assertFalse(Double.isNaN(Smoothing.smoothstep(0, 1, 0.3)));
        // the quintic is flatter at the ends: smaller than the cubic near 0
        assertTrue(Smoothing.smootherstep(0, 1, 0.1) < Smoothing.smoothstep(0, 1, 0.1));
    }
}
