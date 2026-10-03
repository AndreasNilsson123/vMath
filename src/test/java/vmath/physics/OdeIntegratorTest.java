package vmath.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vmath.physics.OdeIntegrator.Method;

class OdeIntegratorTest {

    private static final OdeIntegrator.Acceleration OSCILLATOR = (t, x, v, a) -> a[0] = -x[0];

    /** The position error at time {@code time} of a unit oscillator started at x = 1, v = 0 (exact: cos t). */
    private static double error(Method method, double dt, double time) {
        OdeIntegrator ode = new OdeIntegrator(1);
        double[] x = {1}, v = {0};
        int steps = (int) Math.round(time / dt);
        for (int i = 0; i < steps; i++) {
            ode.step(method, OSCILLATOR, i * dt, dt, x, v);
        }
        return Math.abs(x[0] - Math.cos(steps * dt));
    }

    @Test
    void theOrderOfEachMethodIsMeasured() {
        double[] expected = {1, 1, 2, 4};
        Method[] methods = Method.values();
        for (int m = 0; m < methods.length; m++) {
            double dt = methods[m] == Method.RK4 ? 0.2 : 0.02;
            double e1 = error(methods[m], dt, 2.0), e2 = error(methods[m], dt / 2, 2.0), e3 = error(methods[m], dt / 4, 2.0);
            double p1 = Math.log(e1 / e2) / Math.log(2), p2 = Math.log(e2 / e3) / Math.log(2);
            assertEquals(expected[m], p1, 0.25, methods[m] + " between dt and dt / 2: " + p1);
            assertEquals(expected[m], p2, 0.25, methods[m] + " between dt / 2 and dt / 4: " + p2);
        }
    }

    @Test
    void energyOfAnOscillator() {
        double dt = 0.05;
        int steps = (int) (100 * 2 * Math.PI / dt); // 100 periods
        for (Method method : Method.values()) {
            OdeIntegrator ode = new OdeIntegrator(1);
            double[] x = {1}, v = {0};
            double e0 = 0.5, worst = 0;
            for (int i = 0; i < steps; i++) {
                ode.step(method, OSCILLATOR, i * dt, dt, x, v);
                worst = Math.max(worst, Math.abs(0.5 * (x[0] * x[0] + v[0] * v[0]) - e0) / e0);
            }
            double finalRatio = 0.5 * (x[0] * x[0] + v[0] * v[0]) / e0;
            switch (method) {
                case EXPLICIT_EULER -> assertTrue(finalRatio > 100, "explicit Euler gains energy without bound: " + finalRatio);
                case SEMI_IMPLICIT_EULER -> assertTrue(worst < dt, "symplectic Euler keeps the energy within O(dt): " + worst);
                case VELOCITY_VERLET -> assertTrue(worst < dt * dt, "velocity Verlet keeps the energy within O(dt^2): " + worst);
                case RK4 -> assertTrue(worst < 1e-5 && finalRatio <= 1.0, "RK4 loses energy only slowly: " + worst + " " + finalRatio);
                default -> throw new AssertionError(method);
            }
        }
    }

    @Test
    void aVelocityDependentForceAndSeveralCoordinates() {
        // x'' = -x - c v: the damped oscillator, exact solution exp(-c t / 2) (cos(w t) + c / (2 w) sin(w t)) with w = sqrt(1 - c^2 / 4)
        double c = 0.4, w = Math.sqrt(1 - c * c / 4);
        OdeIntegrator.Acceleration damped = (t, x, v, a) -> {
            a[0] = -x[0] - c * v[0];
            a[1] = -4 * x[1]; // a second, independent oscillator with the frequency 2
        };
        OdeIntegrator ode = new OdeIntegrator(2);
        assertEquals(2, ode.dimension());
        double[] x = {1, 1}, v = {0, 0};
        double dt = 0.01;
        for (int i = 0; i < 500; i++) {
            ode.step(Method.RK4, damped, i * dt, dt, x, v);
        }
        double t = 5.0;
        assertEquals(Math.exp(-c * t / 2) * (Math.cos(w * t) + c / (2 * w) * Math.sin(w * t)), x[0], 1e-8);
        assertEquals(Math.cos(2 * t), x[1], 1e-8);
        // explicit time dependence: x'' = cos(t), x(0) = x'(0) = 0 gives x = 1 - cos(t)
        OdeIntegrator one = new OdeIntegrator(1);
        double[] y = {0}, u = {0};
        for (int i = 0; i < 100; i++) {
            one.step(Method.RK4, (tt, xx, vv, a) -> a[0] = Math.cos(tt), i * 0.02, 0.02, y, u);
        }
        assertEquals(1 - Math.cos(2.0), y[0], 1e-8);
    }

    @Test
    void verletRunsBackwards() {
        OdeIntegrator ode = new OdeIntegrator(1);
        double[] x = {1}, v = {0.3};
        for (int i = 0; i < 300; i++) {
            ode.step(Method.VELOCITY_VERLET, OSCILLATOR, 0, 0.03, x, v);
        }
        for (int i = 0; i < 300; i++) {
            ode.step(Method.VELOCITY_VERLET, OSCILLATOR, 0, -0.03, x, v);
        }
        assertEquals(1.0, x[0], 1e-11);
        assertEquals(0.3, v[0], 1e-11);
    }

    @Test
    void argumentsAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new OdeIntegrator(0));
        OdeIntegrator ode = new OdeIntegrator(3);
        assertThrows(IllegalArgumentException.class, () -> ode.step(Method.RK4, OSCILLATOR, 0, 0.1, new double[2], new double[3]));
        assertThrows(IllegalArgumentException.class, () -> ode.step(Method.RK4, OSCILLATOR, 0, 0.1, new double[3], new double[1]));
    }
}
