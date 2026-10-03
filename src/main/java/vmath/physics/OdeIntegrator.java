package vmath.physics;

/**
 * Time integration of second-order systems {@code x'' = a(t, x, x')}: particles, springs, cloth, anything whose state is positions and velocities in arrays. Four methods trade accuracy
 * against cost and against the way they treat energy:
 *
 * <ul>
 *   <li>{@link Method#EXPLICIT_EULER}: advance the positions with the old velocities, then the velocities; first order, and the energy of an oscillator <b>grows</b> without bound: for demonstration
 *       and for comparison only.</li>
 *   <li>{@link Method#SEMI_IMPLICIT_EULER}: update the velocities first and advance the positions with the new ones (symplectic Euler); first order, but the energy of an oscillator stays bounded,
 *       which is why it is the standard choice of games.</li>
 *   <li>{@link Method#VELOCITY_VERLET}: kick-drift-kick (leapfrog); second order and symplectic for forces that depend on the position only. With a velocity-dependent force (damping) the
 *       velocity used by the second kick is the half-step one and the order drops.</li>
 *   <li>{@link Method#RK4}: the classical fourth-order Runge-Kutta method; four evaluations per step, error proportional to {@code dt^4}, not symplectic (energy drifts slowly).</li>
 * </ul>
 *
 * <p>One integrator owns the scratch arrays for systems of a fixed number of coordinates, so a step allocates nothing. <b>Thread safety.</b> Not thread-safe: one integrator per thread.
 */
public final class OdeIntegrator {

    /** The integration method. */
    public enum Method {
        /** Explicit Euler: first order, unstable for oscillators. */
        EXPLICIT_EULER,
        /** Symplectic Euler: first order, bounded energy. */
        SEMI_IMPLICIT_EULER,
        /** Velocity Verlet (leapfrog): second order. */
        VELOCITY_VERLET,
        /** Classical Runge-Kutta: fourth order. */
        RK4
    }

    /** The acceleration of the system. */
    @FunctionalInterface
    public interface Acceleration {
        /** Writes the accelerations {@code a} for the positions {@code x} and velocities {@code v} at time {@code t}; all three arrays have the dimension of the system. */
        void evaluate(double t, double[] x, double[] v, double[] a);
    }

    private final int n;
    private final double[] a0, a1, a2, a3, x1, v1, x2, v2, x3, v3, k1v, k2v, k3v, k4v;

    /** An integrator for systems of {@code dimension} coordinates (the length of the position and velocity arrays). */
    public OdeIntegrator(int dimension) {
        if (dimension < 1) {
            throw new IllegalArgumentException("the dimension must be positive: " + dimension);
        }
        n = dimension;
        a0 = new double[n];
        a1 = new double[n];
        a2 = new double[n];
        a3 = new double[n];
        x1 = new double[n];
        v1 = new double[n];
        x2 = new double[n];
        v2 = new double[n];
        x3 = new double[n];
        v3 = new double[n];
        k1v = new double[n];
        k2v = new double[n];
        k3v = new double[n];
        k4v = new double[n];
    }

    /** The number of coordinates. */
    public int dimension() {
        return n;
    }

    /** Advances the state {@code (x, v)} from time {@code t} by {@code dt} (which may be negative) with the given method, in place. */
    public void step(Method method, Acceleration f, double t, double dt, double[] x, double[] v) {
        if (x.length < n || v.length < n) {
            throw new IllegalArgumentException("the state arrays need " + n + " elements");
        }
        switch (method) {
            case EXPLICIT_EULER -> {
                f.evaluate(t, x, v, a0);
                for (int i = 0; i < n; i++) {
                    x[i] += v[i] * dt;
                    v[i] += a0[i] * dt;
                }
            }
            case SEMI_IMPLICIT_EULER -> {
                f.evaluate(t, x, v, a0);
                for (int i = 0; i < n; i++) {
                    v[i] += a0[i] * dt;
                    x[i] += v[i] * dt;
                }
            }
            case VELOCITY_VERLET -> {
                f.evaluate(t, x, v, a0);
                for (int i = 0; i < n; i++) {
                    v1[i] = v[i] + 0.5 * dt * a0[i]; // the half kick
                    x[i] += v1[i] * dt; // the drift
                }
                f.evaluate(t + dt, x, v1, a1);
                for (int i = 0; i < n; i++) {
                    v[i] = v1[i] + 0.5 * dt * a1[i]; // the second half kick
                }
            }
            case RK4 -> {
                // k1
                f.evaluate(t, x, v, a0);
                for (int i = 0; i < n; i++) {
                    k1v[i] = v[i];
                    x1[i] = x[i] + 0.5 * dt * v[i];
                    v1[i] = v[i] + 0.5 * dt * a0[i];
                }
                // k2
                f.evaluate(t + 0.5 * dt, x1, v1, a1);
                for (int i = 0; i < n; i++) {
                    k2v[i] = v1[i];
                    x2[i] = x[i] + 0.5 * dt * v1[i];
                    v2[i] = v[i] + 0.5 * dt * a1[i];
                }
                // k3
                f.evaluate(t + 0.5 * dt, x2, v2, a2);
                for (int i = 0; i < n; i++) {
                    k3v[i] = v2[i];
                    x3[i] = x[i] + dt * v2[i];
                    v3[i] = v[i] + dt * a2[i];
                }
                // k4
                f.evaluate(t + dt, x3, v3, a3);
                for (int i = 0; i < n; i++) {
                    k4v[i] = v3[i];
                    x[i] += dt / 6.0 * (k1v[i] + 2 * k2v[i] + 2 * k3v[i] + k4v[i]);
                    v[i] += dt / 6.0 * (a0[i] + 2 * a1[i] + 2 * a2[i] + a3[i]);
                }
            }
            default -> throw new AssertionError(method);
        }
    }
}
