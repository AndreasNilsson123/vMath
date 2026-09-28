package vmath.core;

import java.nio.FloatBuffer;

/**
 * Immutable 3-component float vector.
 *
 * <p>Valhalla: {@code -Pvalhalla} builds rewrite the {@code value} marker below into a real
 * {@code value record}. Never use {@code ==}, {@code synchronized} or identity-based APIs on it.
 */
public /*value*/ record Vec3f(float x, float y, float z) {

    public static final Vec3f ZERO = new Vec3f(0f, 0f, 0f);
    public static final Vec3f ONE = new Vec3f(1f, 1f, 1f);
    public static final Vec3f UNIT_X = new Vec3f(1f, 0f, 0f);
    public static final Vec3f UNIT_Y = new Vec3f(0f, 1f, 0f);
    public static final Vec3f UNIT_Z = new Vec3f(0f, 0f, 1f);

    public static Vec3f splat(float s) {
        return new Vec3f(s, s, s);
    }

    public Vec3f add(Vec3f o) {
        return new Vec3f(x + o.x, y + o.y, z + o.z);
    }

    public Vec3f add(float ox, float oy, float oz) {
        return new Vec3f(x + ox, y + oy, z + oz);
    }

    public Vec3f sub(Vec3f o) {
        return new Vec3f(x - o.x, y - o.y, z - o.z);
    }

    public Vec3f mul(float s) {
        return new Vec3f(x * s, y * s, z * s);
    }

    /** Component-wise product. */
    public Vec3f mul(Vec3f o) {
        return new Vec3f(x * o.x, y * o.y, z * o.z);
    }

    public Vec3f div(float s) {
        float inv = 1f / s;
        return new Vec3f(x * inv, y * inv, z * inv);
    }

    public Vec3f negate() {
        return new Vec3f(-x, -y, -z);
    }

    /** {@code this + a * s}. */
    public Vec3f fma(Vec3f a, float s) {
        return new Vec3f(x + a.x * s, y + a.y * s, z + a.z * s);
    }

    public float dot(Vec3f o) {
        return x * o.x + y * o.y + z * o.z;
    }

    /** Right-handed cross product. */
    public Vec3f cross(Vec3f o) {
        return new Vec3f(
                y * o.z - z * o.y,
                z * o.x - x * o.z,
                x * o.y - y * o.x);
    }

    public float lengthSquared() {
        return x * x + y * y + z * z;
    }

    public float length() {
        return (float) Math.sqrt(lengthSquared());
    }

    public float distanceSquared(Vec3f o) {
        float dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public float distance(Vec3f o) {
        return (float) Math.sqrt(distanceSquared(o));
    }

    /** Unit vector in the same direction. A zero vector yields NaN components. */
    public Vec3f normalize() {
        float inv = 1f / length();
        return new Vec3f(x * inv, y * inv, z * inv);
    }

    /** Like {@link #normalize()} but returns {@link #ZERO} for (near-)zero input. */
    public Vec3f normalizeOrZero() {
        float len2 = lengthSquared();
        if (len2 <= 1e-30f) {
            return ZERO;
        }
        float inv = 1f / (float) Math.sqrt(len2);
        return new Vec3f(x * inv, y * inv, z * inv);
    }

    public Vec3f lerp(Vec3f o, float t) {
        return new Vec3f(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t);
    }

    /** Angle between this and {@code o} in radians, in [0, PI]. */
    public float angle(Vec3f o) {
        return (float) Math.atan2(cross(o).length(), dot(o));
    }

    public Vec3f min(Vec3f o) {
        return new Vec3f(Math.min(x, o.x), Math.min(y, o.y), Math.min(z, o.z));
    }

    public Vec3f max(Vec3f o) {
        return new Vec3f(Math.max(x, o.x), Math.max(y, o.y), Math.max(z, o.z));
    }

    public Vec3f abs() {
        return new Vec3f(Math.abs(x), Math.abs(y), Math.abs(z));
    }

    public float get(int i) {
        return switch (i) {
            case 0 -> x;
            case 1 -> y;
            case 2 -> z;
            default -> throw new IndexOutOfBoundsException(i);
        };
    }

    public boolean approxEquals(Vec3f o, float eps) {
        return Math.abs(x - o.x) <= eps && Math.abs(y - o.y) <= eps && Math.abs(z - o.z) <= eps;
    }

    public void writeTo(float[] dst, int off) {
        dst[off] = x;
        dst[off + 1] = y;
        dst[off + 2] = z;
    }

    /** Absolute write at {@code index}; does not change the buffer position. */
    public void writeTo(FloatBuffer dst, int index) {
        dst.put(index, x).put(index + 1, y).put(index + 2, z);
    }

    // @float-only-begin
    public Vec3d toDouble() {
        return new Vec3d(x, y, z);
    }
    // @float-only-end
}
