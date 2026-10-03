package vmath.util;

import vmath.annotations.Experimental;

/**
 * The standard easing curves: functions of the progress {@code t} in {@code [0, 1]} with {@code f(0) = 0} and {@code f(1) = 1}. Each family comes in three forms: <b>in</b>
 * (slow start), <b>out</b> (slow end, {@code out(t) = 1 - in(1 - t)}) and <b>in-out</b> (slow at both ends, symmetric about the middle).
 *
 * <p>The families are: {@link #LINEAR}, polynomial ({@link #QUAD_IN quad}, {@link #CUBIC_IN cubic}, {@link #QUART_IN quart}, {@link #QUINT_IN quint}), {@link #SINE_IN sine},
 * {@link #EXPO_IN expo}, {@link #CIRC_IN circ}, and three that leave the range {@code [0, 1]} on the way: {@link #BACK_IN back} (overshoot), {@link #ELASTIC_IN elastic}
 * (a decaying oscillation) and {@link #BOUNCE_OUT bounce}. The first nine are monotonic. Inputs outside {@code [0, 1]} are clamped, so {@code QUAD_IN.apply(2.0)} is 1.
 *
 * <p>The formulas are the usual published ones (Penner's equations); the exact constants are in the source of {@link #apply(double)}.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the same time.
 */
@Experimental("the set of curves may grow")
public enum Easing {
    /** {@code t}. */
    LINEAR,
    /** {@code t^2}. */
    QUAD_IN,
    /** {@code 1 - (1 - t)^2}. */
    QUAD_OUT,
    /** Quadratic, slow at both ends. */
    QUAD_IN_OUT,
    /** {@code t^3}. */
    CUBIC_IN,
    /** {@code 1 - (1 - t)^3}. */
    CUBIC_OUT,
    /** Cubic, slow at both ends. */
    CUBIC_IN_OUT,
    /** {@code t^4}. */
    QUART_IN,
    /** {@code 1 - (1 - t)^4}. */
    QUART_OUT,
    /** Quartic, slow at both ends. */
    QUART_IN_OUT,
    /** {@code t^5}. */
    QUINT_IN,
    /** {@code 1 - (1 - t)^5}. */
    QUINT_OUT,
    /** Quintic, slow at both ends. */
    QUINT_IN_OUT,
    /** {@code 1 - cos(t pi / 2)}. */
    SINE_IN,
    /** {@code sin(t pi / 2)}. */
    SINE_OUT,
    /** {@code (1 - cos(t pi)) / 2}. */
    SINE_IN_OUT,
    /** {@code 2^(10 (t - 1))}, shifted and scaled so that it is exactly 0 at 0. */
    EXPO_IN,
    /** Mirror of {@link #EXPO_IN}. */
    EXPO_OUT,
    /** Exponential, slow at both ends. */
    EXPO_IN_OUT,
    /** {@code 1 - sqrt(1 - t^2)}: a quarter circle. */
    CIRC_IN,
    /** Mirror of {@link #CIRC_IN}. */
    CIRC_OUT,
    /** Circular, slow at both ends. */
    CIRC_IN_OUT,
    /** Pulls back below 0 (to about -0.1) before moving on, with overshoot constant 1.70158. */
    BACK_IN,
    /** Overshoots 1 (to about 1.1) and settles back. */
    BACK_OUT,
    /** Pulls back at the start and overshoots at the end. */
    BACK_IN_OUT,
    /** A decaying oscillation before the start. */
    ELASTIC_IN,
    /** A decaying oscillation around 1 at the end. */
    ELASTIC_OUT,
    /** Oscillations at both ends. */
    ELASTIC_IN_OUT,
    /** Bounces off the start like a ball dropped upwards in reverse. */
    BOUNCE_IN,
    /** A ball dropped onto 1: four bounces of decreasing height. */
    BOUNCE_OUT,
    /** Bounces at both ends. */
    BOUNCE_IN_OUT;

    private static final double C1 = 1.70158;
    private static final double C2 = C1 * 1.525;
    private static final double C3 = C1 + 1;
    private static final double C4 = 2 * Math.PI / 3;
    private static final double C5 = 2 * Math.PI / 4.5;

    /** The eased value for the progress {@code t}; {@code t} is clamped to {@code [0, 1]}. */
    public double apply(double t) {
        if (!(t > 0)) {
            return t == t ? 0 : t; // 0 for t <= 0, NaN stays NaN
        }
        if (t >= 1) {
            return 1;
        }
        return switch (this) {
            case LINEAR -> t;
            case QUAD_IN -> t * t;
            case QUAD_OUT -> 1 - (1 - t) * (1 - t);
            case QUAD_IN_OUT -> t < 0.5 ? 2 * t * t : 1 - square(-2 * t + 2) / 2;
            case CUBIC_IN -> t * t * t;
            case CUBIC_OUT -> 1 - cube(1 - t);
            case CUBIC_IN_OUT -> t < 0.5 ? 4 * t * t * t : 1 - cube(-2 * t + 2) / 2;
            case QUART_IN -> square(t * t);
            case QUART_OUT -> 1 - square(square(1 - t));
            case QUART_IN_OUT -> t < 0.5 ? 8 * square(t * t) : 1 - square(square(-2 * t + 2)) / 2;
            case QUINT_IN -> square(t * t) * t;
            case QUINT_OUT -> 1 - square(square(1 - t)) * (1 - t);
            case QUINT_IN_OUT -> t < 0.5 ? 16 * square(t * t) * t : 1 - square(square(-2 * t + 2)) * (-2 * t + 2) / 2;
            case SINE_IN -> 1 - Math.cos(t * Math.PI / 2);
            case SINE_OUT -> Math.sin(t * Math.PI / 2);
            case SINE_IN_OUT -> (1 - Math.cos(t * Math.PI)) / 2;
            case EXPO_IN -> (Math.pow(2, 10 * t - 10) - 0.0009765625) / (1 - 0.0009765625);
            case EXPO_OUT -> 1 - EXPO_IN.apply(1 - t);
            case EXPO_IN_OUT -> t < 0.5 ? EXPO_IN.apply(2 * t) / 2 : 1 - EXPO_IN.apply(2 - 2 * t) / 2;
            case CIRC_IN -> 1 - Math.sqrt(1 - t * t);
            case CIRC_OUT -> Math.sqrt(1 - (t - 1) * (t - 1));
            case CIRC_IN_OUT ->
                    t < 0.5 ? (1 - Math.sqrt(1 - square(2 * t))) / 2 : (Math.sqrt(1 - square(-2 * t + 2)) + 1) / 2;
            case BACK_IN -> C3 * t * t * t - C1 * t * t;
            case BACK_OUT -> 1 + C3 * cube(t - 1) + C1 * square(t - 1);
            case BACK_IN_OUT ->
                    t < 0.5 ? square(2 * t) * ((C2 + 1) * 2 * t - C2) / 2 : (square(2 * t - 2) * ((C2 + 1) * (t * 2 - 2) + C2) + 2) / 2;
            case ELASTIC_IN -> -Math.pow(2, 10 * t - 10) * Math.sin((t * 10 - 10.75) * C4);
            case ELASTIC_OUT -> Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * C4) + 1;
            case ELASTIC_IN_OUT ->
                    t < 0.5 ? -(Math.pow(2, 20 * t - 10) * Math.sin((20 * t - 11.125) * C5)) / 2 : Math.pow(2, -20 * t + 10) * Math.sin((20 * t - 11.125) * C5) / 2 + 1;
            case BOUNCE_IN -> 1 - bounceOut(1 - t);
            case BOUNCE_OUT -> bounceOut(t);
            case BOUNCE_IN_OUT -> t < 0.5 ? (1 - bounceOut(1 - 2 * t)) / 2 : (1 + bounceOut(2 * t - 1)) / 2;
        };
    }

    private static double square(double x) {
        return x * x;
    }

    private static double cube(double x) {
        return x * x * x;
    }

    private static double bounceOut(double t) {
        final double n1 = 7.5625, d1 = 2.75;
        if (t < 1 / d1) {
            return n1 * t * t;
        } else if (t < 2 / d1) {
            t -= 1.5 / d1;
            return n1 * t * t + 0.75;
        } else if (t < 2.5 / d1) {
            t -= 2.25 / d1;
            return n1 * t * t + 0.9375;
        }
        t -= 2.625 / d1;
        return n1 * t * t + 0.984375;
    }

    /** The eased value of {@code t} for the curve, and then the linear mix between {@code a} and {@code b} at that progress: {@code a + (b - a) * apply(t)}. */
    public double mix(double a, double b, double t) {
        return a + (b - a) * apply(t);
    }
}
