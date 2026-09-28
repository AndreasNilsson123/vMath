package vmath.core;

// GENERATED from Vec4f.java by tools/GenDouble.java. Do not edit; edit the float source.

import java.nio.DoubleBuffer;

/**
 * Immutable 4-component double vector (homogeneous coordinates, colors, plane equations).
 *
 * <p>Valhalla: {@code -Pvalhalla} builds rewrite the {@code value} marker below into a real
 * {@code value record}. Never use {@code ==}, {@code synchronized} or identity-based APIs on it.
 */
public /*value*/ record Vec4d(double x, double y, double z, double w) {

    public static final Vec4d ZERO = new Vec4d(0.0, 0.0, 0.0, 0.0);
    public static final Vec4d ONE = new Vec4d(1.0, 1.0, 1.0, 1.0);

    /** Point: {@code (v, 1)}. */
    public static Vec4d point(Vec3d v) {
        return new Vec4d(v.x(), v.y(), v.z(), 1.0);
    }

    /** Direction: {@code (v, 0)}. */
    public static Vec4d direction(Vec3d v) {
        return new Vec4d(v.x(), v.y(), v.z(), 0.0);
    }

    public Vec4d add(Vec4d o) {
        return new Vec4d(x + o.x, y + o.y, z + o.z, w + o.w);
    }

    public Vec4d sub(Vec4d o) {
        return new Vec4d(x - o.x, y - o.y, z - o.z, w - o.w);
    }

    public Vec4d mul(double s) {
        return new Vec4d(x * s, y * s, z * s, w * s);
    }

    /** Component-wise product. */
    public Vec4d mul(Vec4d o) {
        return new Vec4d(x * o.x, y * o.y, z * o.z, w * o.w);
    }

    public Vec4d negate() {
        return new Vec4d(-x, -y, -z, -w);
    }

    public double dot(Vec4d o) {
        return x * o.x + y * o.y + z * o.z + w * o.w;
    }

    public double lengthSquared() {
        return x * x + y * y + z * z + w * w;
    }

    public double length() {
        return Math.sqrt(lengthSquared());
    }

    /** Unit vector in the same direction. A zero vector yields NaN components. */
    public Vec4d normalize() {
        double inv = 1.0 / length();
        return new Vec4d(x * inv, y * inv, z * inv, w * inv);
    }

    public Vec4d lerp(Vec4d o, double t) {
        return new Vec4d(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t, w + (o.w - w) * t);
    }

    public Vec3d xyz() {
        return new Vec3d(x, y, z);
    }

    /** Perspective divide: {@code xyz / w}. */
    public Vec3d divideByW() {
        double inv = 1.0 / w;
        return new Vec3d(x * inv, y * inv, z * inv);
    }

    public double get(int i) {
        return switch (i) {
            case 0 -> x;
            case 1 -> y;
            case 2 -> z;
            case 3 -> w;
            default -> throw new IndexOutOfBoundsException(i);
        };
    }

    public boolean approxEquals(Vec4d o, double eps) {
        return Math.abs(x - o.x) <= eps && Math.abs(y - o.y) <= eps
                && Math.abs(z - o.z) <= eps && Math.abs(w - o.w) <= eps;
    }

    public void writeTo(double[] dst, int off) {
        dst[off] = x;
        dst[off + 1] = y;
        dst[off + 2] = z;
        dst[off + 3] = w;
    }

    /** Absolute write at {@code index}; does not change the buffer position. */
    public void writeTo(DoubleBuffer dst, int index) {
        dst.put(index, x).put(index + 1, y).put(index + 2, z).put(index + 3, w);
    }

    public Vec4f toFloat() {
        return new Vec4f((float) x, (float) y, (float) z, (float) w);
    }
}
