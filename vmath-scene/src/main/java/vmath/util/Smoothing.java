package vmath.util;

import vmath.annotations.Experimental;

/**
 * Frame-rate independent smoothing: ways to move a value towards a target by a fraction that
 * depends on the elapsed time, not on the number of frames.
 *
 * <p>The classic {@code value += (target - value) * 0.1} each frame moves faster at 120 frames per
 * second than at 60; the exponential forms here give the same result however the time is split:
 * smoothing for {@code dt1} and then {@code dt2} equals smoothing once for {@code dt1 + dt2}.
 *
 * <p>For smoothing with momentum (no abrupt change of speed) use {@link Spring}.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * double position = 0.0;
 * for (int frame = 0; frame < 60; frame++) {
 *     position = Smoothing.towardsHalfLife(position, 10.0, 0.25, 1.0 / 60);       // frame-rate independent smoothing
 * }
 * double angle = Smoothing.angleDelta(0.1, 6.2);                                  // the short way round, in radians
 * }</pre>
 */
@Experimental("a smoothing filter for angles and quaternions may be added")
public final class Smoothing {

    private Smoothing() {
    }

    /**
     * Computes the blend factor for exponential smoothing that is independent of the frame rate,
     * from the time step and a rate.
     *
     * @param rate the rate
     * @param dt the time step in seconds
     * @return the fraction of the remaining distance covered in {@code dt} seconds at {@code rate}
     *     (per second): {@code 1 - exp(-rate dt)}, in {@code [0, 1)}
     */
    public static double factor(double rate, double dt) {
        return -Math.expm1(-rate * dt);
    }

    /**
     * Computes the blend factor for exponential smoothing from a half-life instead of a rate,
     * independent of the frame rate.
     *
     * @param halfLife the half life
     * @param dt the time step in seconds
     * @return {@link #factor} for a half-life: the time in which half the distance is covered, so
     *     {@code 1 - 2^(-dt / halfLife)}
     */
    public static double factorForHalfLife(double halfLife, double dt) {
        return 1 - Math.pow(0.5, dt / halfLife);
    }

    /**
     * Moves a value towards a target with frame-rate independent exponential smoothing, which eases
     * out as it approaches.
     *
     * @param current the current
     * @param target the target
     * @param rate the rate
     * @param dt the time step in seconds
     * @return {@code current} moved towards {@code target} by {@link #factor}{@code (rate, dt)} of
     *     the distance
     */
    public static double towards(double current, double target, double rate, double dt) {
        return current + (target - current) * factor(rate, dt);
    }

    /**
     * Moves a value towards a target with frame-rate independent exponential smoothing specified by
     * a half-life.
     *
     * @param current the current
     * @param target the target
     * @param halfLife the half life
     * @param dt the time step in seconds
     * @return {@code current} moved towards {@code target} so that half the distance is covered
     *     every {@code halfLife} seconds
     */
    public static double towardsHalfLife(double current, double target, double halfLife, double dt) {
        return current + (target - current) * factorForHalfLife(halfLife, dt);
    }

    /**
     * Moves {@code n} components of {@code values} (from {@code offset}) towards the ones of
     * {@code targets} (from {@code targetOffset}) by the same {@link #factor}; for a position, a
     * colour or any vector.
     *
     * @param values the values
     * @param offset the index of the first element to read or write
     * @param targets the targets
     * @param targetOffset the target offset
     * @param n the number of elements
     * @param rate the rate
     * @param dt the time step in seconds
     */
    public static void towards(float[] values, int offset, float[] targets, int targetOffset, int n, double rate, double dt) {
        float f = (float) factor(rate, dt);
        for (int i = 0; i < n; i++) {
            values[offset + i] += (targets[targetOffset + i] - values[offset + i]) * f;
        }
    }

    /**
     * Moves a value towards a target at constant speed without easing; unlike exponential smoothing
     * it arrives in finite time and never overshoots.
     *
     * @param current the current
     * @param target the target
     * @param maxDelta the max delta
     * @return {@code current} moved towards {@code target} by at most {@code maxDelta} (a constant
     *     speed with no easing); never overshoots
     */
    public static double moveTowards(double current, double target, double maxDelta) {
        double d = target - current;
        return Math.abs(d) <= maxDelta ? target : current + Math.signum(d) * maxDelta;
    }

    /**
     * Computes the shortest signed difference between two angles across the wrap-around, so that
     * smoothing an angle takes the short way round.
     *
     * @param from the start angle in radians
     * @param to the target angle in radians
     * @return the shortest signed angle in radians from {@code from} to {@code to}, in
     *     {@code [-pi, pi]}: use it to smooth angles across the wrap-around
     */
    public static double angleDelta(double from, double to) {
        double d = (to - from) % (2 * Math.PI);
        if (d > Math.PI) {
            d -= 2 * Math.PI;
        } else if (d < -Math.PI) {
            d += 2 * Math.PI;
        }
        return d;
    }

    /**
     * Applies the smooth cubic blend between two edges, as the GLSL function of the same name does;
     * the first derivative is zero at both ends.
     *
     * @param edge0 the edge0
     * @param edge1 the edge1
     * @param x the x component
     * @return the cubic {@code t^2 (3 - 2 t)} with {@code t} clamped to {@code [0, 1]} after
     *     mapping {@code [edge0, edge1]} to it (GLSL {@code smoothstep})
     */
    public static double smoothstep(double edge0, double edge1, double x) {
        double t = Math.max(0, Math.min(1, (x - edge0) / (edge1 - edge0)));
        return t * t * (3 - 2 * t);
    }

    /**
     * Applies the smoother quintic blend between two edges, which has zero first and second
     * derivatives at both ends.
     *
     * @param edge0 the edge0
     * @param edge1 the edge1
     * @param x the x component
     * @return the quintic {@code t^3 (t (6 t - 15) + 10)}: like {@link #smoothstep} but with zero
     *     first and second derivative at both ends
     */
    public static double smootherstep(double edge0, double edge1, double x) {
        double t = Math.max(0, Math.min(1, (x - edge0) / (edge1 - edge0)));
        return t * t * t * (t * (6 * t - 15) + 10);
    }
}
