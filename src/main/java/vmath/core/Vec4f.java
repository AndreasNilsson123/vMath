package vmath.core;

import java.nio.FloatBuffer;

/**
 * Immutable 4-component float vector (homogeneous coordinates, colors, plane equations).
 *
 * <p>Valhalla: {@code -Pvalhalla} builds rewrite the {@code value} marker below into a real
 * {@code value record}. Never use {@code ==}, {@code synchronized} or identity-based APIs on it.
 */
public /*value*/ record Vec4f(float x, float y, float z, float w) {

    public static final Vec4f ZERO = new Vec4f(0f, 0f, 0f, 0f);
    public static final Vec4f ONE = new Vec4f(1f, 1f, 1f, 1f);

    /** Point: {@code (v, 1)}. */
    public static Vec4f point(Vec3f v) {
        return new Vec4f(v.x(), v.y(), v.z(), 1f);
    }

    /** Direction: {@code (v, 0)}. */
    public static Vec4f direction(Vec3f v) {
        return new Vec4f(v.x(), v.y(), v.z(), 0f);
    }

    public Vec4f add(Vec4f o) {
        return new Vec4f(x + o.x, y + o.y, z + o.z, w + o.w);
    }

    public Vec4f sub(Vec4f o) {
        return new Vec4f(x - o.x, y - o.y, z - o.z, w - o.w);
    }

    public Vec4f mul(float s) {
        return new Vec4f(x * s, y * s, z * s, w * s);
    }

    /** Component-wise product. */
    public Vec4f mul(Vec4f o) {
        return new Vec4f(x * o.x, y * o.y, z * o.z, w * o.w);
    }

    public Vec4f negate() {
        return new Vec4f(-x, -y, -z, -w);
    }

    public float dot(Vec4f o) {
        return x * o.x + y * o.y + z * o.z + w * o.w;
    }

    public float lengthSquared() {
        return x * x + y * y + z * z + w * w;
    }

    public float length() {
        return (float) Math.sqrt(lengthSquared());
    }

    /** Unit vector in the same direction. A zero vector yields NaN components. */
    public Vec4f normalize() {
        float inv = 1f / length();
        return new Vec4f(x * inv, y * inv, z * inv, w * inv);
    }

    public Vec4f lerp(Vec4f o, float t) {
        return new Vec4f(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t, w + (o.w - w) * t);
    }

    public Vec3f xyz() {
        return new Vec3f(x, y, z);
    }

    /** Perspective divide: {@code xyz / w}. */
    public Vec3f divideByW() {
        float inv = 1f / w;
        return new Vec3f(x * inv, y * inv, z * inv);
    }

    public float get(int i) {
        return switch (i) {
            case 0 -> x;
            case 1 -> y;
            case 2 -> z;
            case 3 -> w;
            default -> throw new IndexOutOfBoundsException(i);
        };
    }

    public boolean approxEquals(Vec4f o, float eps) {
        return Math.abs(x - o.x) <= eps && Math.abs(y - o.y) <= eps
                && Math.abs(z - o.z) <= eps && Math.abs(w - o.w) <= eps;
    }

    public void writeTo(float[] dst, int off) {
        dst[off] = x;
        dst[off + 1] = y;
        dst[off + 2] = z;
        dst[off + 3] = w;
    }

    /** Absolute write at {@code index}; does not change the buffer position. */
    public void writeTo(FloatBuffer dst, int index) {
        dst.put(index, x).put(index + 1, y).put(index + 2, z).put(index + 3, w);
    }

    // @float-only-begin
    public Vec4d toDouble() {
        return new Vec4d(x, y, z, w);
    }
    // @float-only-end
}
