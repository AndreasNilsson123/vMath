package vmath.core;

// GENERATED from Vec3f.java by tools/GenDouble.java. Do not edit; edit the float source.

import java.nio.DoubleBuffer;

/**
 * Immutable 3-component double vector.
 *
 * <p>Valhalla: {@code -Pvalhalla} builds rewrite the {@code value} marker below into a real
 * {@code value record}. Never use {@code ==}, {@code synchronized} or identity-based APIs on it.
 */
public /*value*/ record Vec3d(double x, double y, double z) {

    public static final Vec3d ZERO = new Vec3d(0.0, 0.0, 0.0);
    public static final Vec3d ONE = new Vec3d(1.0, 1.0, 1.0);
    public static final Vec3d UNIT_X = new Vec3d(1.0, 0.0, 0.0);
    public static final Vec3d UNIT_Y = new Vec3d(0.0, 1.0, 0.0);
    public static final Vec3d UNIT_Z = new Vec3d(0.0, 0.0, 1.0);

    public static Vec3d splat(double s) {
        return new Vec3d(s, s, s);
    }

    public Vec3d add(Vec3d o) {
        return new Vec3d(x + o.x, y + o.y, z + o.z);
    }

    public Vec3d add(double ox, double oy, double oz) {
        return new Vec3d(x + ox, y + oy, z + oz);
    }

    public Vec3d sub(Vec3d o) {
        return new Vec3d(x - o.x, y - o.y, z - o.z);
    }

    public Vec3d mul(double s) {
        return new Vec3d(x * s, y * s, z * s);
    }

    /** Component-wise product. */
    public Vec3d mul(Vec3d o) {
        return new Vec3d(x * o.x, y * o.y, z * o.z);
    }

    public Vec3d div(double s) {
        double inv = 1.0 / s;
        return new Vec3d(x * inv, y * inv, z * inv);
    }

    public Vec3d negate() {
        return new Vec3d(-x, -y, -z);
    }

    /** {@code this + a * s}. */
    public Vec3d fma(Vec3d a, double s) {
        return new Vec3d(x + a.x * s, y + a.y * s, z + a.z * s);
    }

    public double dot(Vec3d o) {
        return x * o.x + y * o.y + z * o.z;
    }

    /** Right-handed cross product. */
    public Vec3d cross(Vec3d o) {
        return new Vec3d(
                y * o.z - z * o.y,
                z * o.x - x * o.z,
                x * o.y - y * o.x);
    }

    public double lengthSquared() {
        return x * x + y * y + z * z;
    }

    public double length() {
        return Math.sqrt(lengthSquared());
    }

    public double distanceSquared(Vec3d o) {
        double dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distance(Vec3d o) {
        return Math.sqrt(distanceSquared(o));
    }

    /** Unit vector in the same direction. A zero vector yields NaN components. */
    public Vec3d normalize() {
        double inv = 1.0 / length();
        return new Vec3d(x * inv, y * inv, z * inv);
    }

    /** Like {@link #normalize()} but returns {@link #ZERO} for (near-)zero input. */
    public Vec3d normalizeOrZero() {
        double len2 = lengthSquared();
        if (len2 <= 1e-30) {
            return ZERO;
        }
        double inv = 1.0 / Math.sqrt(len2);
        return new Vec3d(x * inv, y * inv, z * inv);
    }

    public Vec3d lerp(Vec3d o, double t) {
        return new Vec3d(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t);
    }

    /** Angle between this and {@code o} in radians, in [0, PI]. */
    public double angle(Vec3d o) {
        return Math.atan2(cross(o).length(), dot(o));
    }

    public Vec3d min(Vec3d o) {
        return new Vec3d(Math.min(x, o.x), Math.min(y, o.y), Math.min(z, o.z));
    }

    public Vec3d max(Vec3d o) {
        return new Vec3d(Math.max(x, o.x), Math.max(y, o.y), Math.max(z, o.z));
    }

    public Vec3d abs() {
        return new Vec3d(Math.abs(x), Math.abs(y), Math.abs(z));
    }

    public double get(int i) {
        return switch (i) {
            case 0 -> x;
            case 1 -> y;
            case 2 -> z;
            default -> throw new IndexOutOfBoundsException(i);
        };
    }

    public boolean approxEquals(Vec3d o, double eps) {
        return Math.abs(x - o.x) <= eps && Math.abs(y - o.y) <= eps && Math.abs(z - o.z) <= eps;
    }

    public void writeTo(double[] dst, int off) {
        dst[off] = x;
        dst[off + 1] = y;
        dst[off + 2] = z;
    }

    /** Absolute write at {@code index}; does not change the buffer position. */
    public void writeTo(DoubleBuffer dst, int index) {
        dst.put(index, x).put(index + 1, y).put(index + 2, z);
    }

    public Vec3f toFloat() {
        return new Vec3f((float) x, (float) y, (float) z);
    }

    /**
     * {@code this - origin}, subtracted in double and then narrowed to float. This is the core of
     * camera-relative rendering: pass the camera's world position as {@code origin} so vertex
     * data stays small enough for float precision on the GPU.
     */
    public Vec3f relativeTo(Vec3d origin) {
        return new Vec3f((float) (x - origin.x), (float) (y - origin.y), (float) (z - origin.z));
    }
}
