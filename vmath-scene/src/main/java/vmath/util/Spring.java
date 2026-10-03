   package vmath.util;

import vmath.annotations.Experimental;

/**
 * A damped spring that pulls a value towards a target, for smoothing cameras, UI values and anything that should follow a moving goal without jerks. It solves
 * {@code x'' = -w^2 (x - target) - 2 z w x'} <b>exactly</b> for a time step of any size (the closed form of the damped oscillator for underdamped, critically damped and overdamped
 * springs), so the result does not depend on the frame rate and never blows up for a large {@code dt}, unlike a spring integrated with Euler steps.
 *
 * <p>{@code w} ({@code omega}) is the angular frequency in radians per second: the spring settles in a time of the order of {@code 1 / w}. {@code z} ({@code zeta}) is the damping ratio:
 * 1 is <b>critically damped</b> (the fastest approach without overshoot), below 1 it overshoots and oscillates, above 1 it creeps in slowly. {@link #halfLife} turns "reach half
 * the distance in this many seconds" into the {@code omega} of a critically damped spring.
 *
 * <p>The scalar form is an object holding the position and velocity; {@link #step(float[], float[], int, int, float[], int, double, double, double)} advances
 * several components stored in arrays (a position, a colour) with no objects.
 *
 * <p><b>Thread safety.</b> The object is mutable and not thread-safe; the static method is stateless.
 */
@Experimental("a spring on rotations (quaternions) may be added")
public final class Spring {

    /** The current value. */
    public double position;
    /** The current rate of change of the value. */
    public double velocity;

    /** A spring at rest at 0. */
    public Spring() {
    }

    /** A spring at rest at {@code position}. */
    public Spring(double position) {
        this.position = position;
    }

    /** The {@code omega} of a critically damped spring that covers half the remaining distance to a still target in {@code seconds}: {@code 1.678346990016661 / seconds}: the constant is the root of {@code (1 + u) exp(-u) = 1/2}, the remaining fraction of the distance of a critically damped spring from rest. */
    public static double halfLife(double seconds) {
        // for z = 1 the decay is (1 + w t) e^(-w t); it equals 1/2 at w t = 1.67835..., the root of (1 + u) e^(-u) = 1/2
        return 1.678346990016661 / seconds;
    }

    /**
     * Advances the spring by {@code dt} seconds towards {@code target} and returns the new position. With {@code dt <= 0} nothing changes. {@code omega} must be positive and
     * {@code zeta} not negative.
     */
    public double update(double target, double omega, double zeta, double dt) {
        double x = position, v = velocity;
        position = evolve(x, v, target, omega, zeta, dt, false);
        velocity = evolve(x, v, target, omega, zeta, dt, true);
        return position;
    }

    /**
     * The exact state of the spring after {@code dt} seconds, written to {@code out[0]} (position) and {@code out[1]} (velocity). {@code dt <= 0} gives the state unchanged.
     */
    public static void step(double x, double v, double target, double omega, double zeta, double dt, double[] out) {
        out[0] = evolve(x, v, target, omega, zeta, dt, false);
        out[1] = evolve(x, v, target, omega, zeta, dt, true);
    }

    /** The position (or, with {@code velocity}, the velocity) of the spring after {@code dt}. */
    private static double evolve(double x, double v, double target, double omega, double zeta, double dt, boolean velocity) {
        if (!(omega > 0) || !(zeta >= 0)) {
            throw new IllegalArgumentException("omega must be positive and zeta not negative: " + omega + ", " + zeta);
        }
        if (!(dt > 0)) {
            return velocity ? v : x;
        }
        double y = x - target;
        double yt, vt;
        if (Math.abs(zeta - 1) < 1e-9) {
            double e = Math.exp(-omega * dt);
            double c = v + omega * y;
            yt = (y + c * dt) * e;
            vt = (v - c * omega * dt) * e;
        } else if (zeta < 1) {
            double wd = omega * Math.sqrt(1 - zeta * zeta);
            double decay = zeta * omega;
            double e = Math.exp(-decay * dt);
            double cos = Math.cos(wd * dt), sin = Math.sin(wd * dt);
            double b = (v + decay * y) / wd;
            yt = e * (y * cos + b * sin);
            vt = e * ((b * wd - decay * y) * cos - (y * wd + decay * b) * sin);
        } else {
            double r = omega * Math.sqrt(zeta * zeta - 1);
            double s1 = -zeta * omega + r, s2 = -zeta * omega - r;
            double c1 = (v - s2 * y) / (s1 - s2), c2 = y - c1;
            double e1 = Math.exp(s1 * dt), e2 = Math.exp(s2 * dt);
            yt = c1 * e1 + c2 * e2;
            vt = c1 * s1 * e1 + c2 * s2 * e2;
        }
        return velocity ? vt : target + yt;
    }

    /**
     * Advances {@code n} independent components at once: {@code pos[offset .. offset + n)} and {@code vel[offset .. offset + n)} move towards
     * {@code target[targetOffset .. targetOffset + n)} with the same {@code omega} and {@code zeta}.
     */
    public static void step(float[] pos, float[] vel, int offset, int n, float[] target, int targetOffset, double omega, double zeta, double dt) {
        for (int i = 0; i < n; i++) {
            double x = pos[offset + i], v = vel[offset + i], g = target[targetOffset + i];
            pos[offset + i] = (float) evolve(x, v, g, omega, zeta, dt, false);
            vel[offset + i] = (float) evolve(x, v, g, omega, zeta, dt, true);
        }
    }
}
