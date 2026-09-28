package vmath.core;

// GENERATED from Vec2f.java by tools/GenDouble.java. Do not edit; edit the float source.

import java.nio.DoubleBuffer;

/**
 * Immutable 2-component double vector.
 *
 * <p>Valhalla: {@code -Pvalhalla} builds rewrite the {@code value} marker below into a real
 * {@code value record}. Never use {@code ==}, {@code synchronized} or identity-based APIs on it.
 */
public /*value*/ record Vec2d(double x, double y) {

    public static final Vec2d ZERO = new Vec2d(0.0, 0.0);
    public static final Vec2d ONE = new Vec2d(1.0, 1.0);
    public static final Vec2d UNIT_X = new Vec2d(1.0, 0.0);
    public static final Vec2d UNIT_Y = new Vec2d(0.0, 1.0);

    public static Vec2d splat(double s) {
        return new Vec2d(s, s);
    }

    public Vec2d add(Vec2d o) {
        return new Vec2d(x + o.x, y + o.y);
    }

    public Vec2d sub(Vec2d o) {
        return new Vec2d(x - o.x, y - o.y);
    }

    public Vec2d mul(double s) {
        return new Vec2d(x * s, y * s);
    }

    /** Component-wise product. */
    public Vec2d mul(Vec2d o) {
        return new Vec2d(x * o.x, y * o.y);
    }

    public Vec2d div(double s) {
        double inv = 1.0 / s;
        return new Vec2d(x * inv, y * inv);
    }

    public Vec2d negate() {
        return new Vec2d(-x, -y);
    }

    /** {@code this + a * s}. */
    public Vec2d fma(Vec2d a, double s) {
        return new Vec2d(x + a.x * s, y + a.y * s);
    }

    public double dot(Vec2d o) {
        return x * o.x + y * o.y;
    }

    /** Z component of the 3D cross product (signed parallelogram area). */
    public double cross(Vec2d o) {
        return x * o.y - y * o.x;
    }

    /** Counter-clockwise perpendicular. */
    public Vec2d perpendicular() {
        return new Vec2d(-y, x);
    }

    public double lengthSquared() {
        return x * x + y * y;
    }

    public double length() {
        return Math.sqrt(lengthSquared());
    }

    public double distance(Vec2d o) {
        return sub(o).length();
    }

    /** Unit vector in the same direction. A zero vector yields NaN components. */
    public Vec2d normalize() {
        double inv = 1.0 / length();
        return new Vec2d(x * inv, y * inv);
    }

    public Vec2d lerp(Vec2d o, double t) {
        return new Vec2d(x + (o.x - x) * t, y + (o.y - y) * t);
    }

    public Vec2d min(Vec2d o) {
        return new Vec2d(Math.min(x, o.x), Math.min(y, o.y));
    }

    public Vec2d max(Vec2d o) {
        return new Vec2d(Math.max(x, o.x), Math.max(y, o.y));
    }

    public double get(int i) {
        return switch (i) {
            case 0 -> x;
            case 1 -> y;
            default -> throw new IndexOutOfBoundsException(i);
        };
    }

    public boolean approxEquals(Vec2d o, double eps) {
        return Math.abs(x - o.x) <= eps && Math.abs(y - o.y) <= eps;
    }

    public void writeTo(double[] dst, int off) {
        dst[off] = x;
        dst[off + 1] = y;
    }

    /** Absolute write at {@code index}; does not change the buffer position. */
    public void writeTo(DoubleBuffer dst, int index) {
        dst.put(index, x).put(index + 1, y);
    }

    public Vec2f toFloat() {
        return new Vec2f((float) x, (float) y);
    }
}
