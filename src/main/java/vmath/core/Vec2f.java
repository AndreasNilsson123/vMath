package vmath.core;

import java.nio.FloatBuffer;

/**
 * Immutable 2-component float vector.
 *
 * <p>Valhalla: {@code -Pvalhalla} builds rewrite the {@code value} marker below into a real
 * {@code value record}. Never use {@code ==}, {@code synchronized} or identity-based APIs on it.
 */
public /*value*/ record Vec2f(float x, float y) {

    public static final Vec2f ZERO = new Vec2f(0f, 0f);
    public static final Vec2f ONE = new Vec2f(1f, 1f);
    public static final Vec2f UNIT_X = new Vec2f(1f, 0f);
    public static final Vec2f UNIT_Y = new Vec2f(0f, 1f);

    public static Vec2f splat(float s) {
        return new Vec2f(s, s);
    }

    public Vec2f add(Vec2f o) {
        return new Vec2f(x + o.x, y + o.y);
    }

    public Vec2f sub(Vec2f o) {
        return new Vec2f(x - o.x, y - o.y);
    }

    public Vec2f mul(float s) {
        return new Vec2f(x * s, y * s);
    }

    /** Component-wise product. */
    public Vec2f mul(Vec2f o) {
        return new Vec2f(x * o.x, y * o.y);
    }

    public Vec2f div(float s) {
        float inv = 1f / s;
        return new Vec2f(x * inv, y * inv);
    }

    public Vec2f negate() {
        return new Vec2f(-x, -y);
    }

    /** {@code this + a * s}. */
    public Vec2f fma(Vec2f a, float s) {
        return new Vec2f(x + a.x * s, y + a.y * s);
    }

    public float dot(Vec2f o) {
        return x * o.x + y * o.y;
    }

    /** Z component of the 3D cross product (signed parallelogram area). */
    public float cross(Vec2f o) {
        return x * o.y - y * o.x;
    }

    /** Counter-clockwise perpendicular. */
    public Vec2f perpendicular() {
        return new Vec2f(-y, x);
    }

    public float lengthSquared() {
        return x * x + y * y;
    }

    public float length() {
        return (float) Math.sqrt(lengthSquared());
    }

    public float distance(Vec2f o) {
        return sub(o).length();
    }

    /** Unit vector in the same direction. A zero vector yields NaN components. */
    public Vec2f normalize() {
        float inv = 1f / length();
        return new Vec2f(x * inv, y * inv);
    }

    public Vec2f lerp(Vec2f o, float t) {
        return new Vec2f(x + (o.x - x) * t, y + (o.y - y) * t);
    }

    public Vec2f min(Vec2f o) {
        return new Vec2f(Math.min(x, o.x), Math.min(y, o.y));
    }

    public Vec2f max(Vec2f o) {
        return new Vec2f(Math.max(x, o.x), Math.max(y, o.y));
    }

    public float get(int i) {
        return switch (i) {
            case 0 -> x;
            case 1 -> y;
            default -> throw new IndexOutOfBoundsException(i);
        };
    }

    public boolean approxEquals(Vec2f o, float eps) {
        return Math.abs(x - o.x) <= eps && Math.abs(y - o.y) <= eps;
    }

    public void writeTo(float[] dst, int off) {
        dst[off] = x;
        dst[off + 1] = y;
    }

    /** Absolute write at {@code index}; does not change the buffer position. */
    public void writeTo(FloatBuffer dst, int index) {
        dst.put(index, x).put(index + 1, y);
    }

    // @float-only-begin
    public Vec2d toDouble() {
        return new Vec2d(x, y);
    }
    // @float-only-end
}
