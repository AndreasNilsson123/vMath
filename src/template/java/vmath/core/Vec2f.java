package vmath.core;

import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import java.nio.FloatBuffer;

/**
 * Immutable 2-component float vector.
 *
 * <p>Valhalla: {@code -Pvalhalla} builds turn {@code @ValueType} into a real
 * {@code value record}. Never use {@code ==}, {@code synchronized} or identity-based APIs on it.
 */
@GenerateDouble
@ValueType
public record Vec2f(float x, float y) {

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

    /**
     * Unit vector in the same direction. A zero, NaN or infinite input yields NaN components. A very large or very small vector, whose squared length would
     * overflow or underflow, is scaled first and still gives the right direction (the plain formula would return zeros or infinities for it).
     */
    public Vec2f normalize() {
        float len2 = x * x + y * y;
        if (len2 >= Float.MIN_NORMAL && len2 <= Float.MAX_VALUE) {
            float inv = 1f / (float) Math.sqrt(len2);
            return new Vec2f(x * inv, y * inv);
        }
        return normalizeScaled();
    }

    /** The slow path of {@link #normalize()}: divide by the largest component first so that the squares neither overflow nor underflow. */
    private Vec2f normalizeScaled() {
        float m = Math.max(Math.abs(x), Math.abs(y));
        if (!(m > 0f) || m == Float.POSITIVE_INFINITY) {
            return new Vec2f(Float.NaN, Float.NaN);
        }
        float a = x / m, b = y / m;
        float inv = 1f / (float) Math.sqrt(a * a + b * b);
        return new Vec2f(a * inv, b * inv);
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

    public Vec2f add(float ox, float oy) {
        return new Vec2f(x + ox, y + oy);
    }

    public float distanceSquared(Vec2f o) {
        float dx = x - o.x, dy = y - o.y;
        return dx * dx + dy * dy;
    }

    /** Like {@link #normalize()} but returns {@link #ZERO} for (near-)zero input. */
    public Vec2f normalizeOrZero() {
        float len2 = lengthSquared();
        if (len2 <= 1e-30f) {
            return ZERO;
        }
        if (!(len2 <= Float.MAX_VALUE)) { // overflowed (a huge vector keeps its direction) or NaN (stays NaN)
            return normalizeScaled();
        }
        float inv = 1f / (float) Math.sqrt(len2);
        return new Vec2f(x * inv, y * inv);
    }

    /** Unsigned angle between this and {@code o} in radians, in [0, PI]. */
    public float angle(Vec2f o) {
        return (float) Math.atan2(Math.abs(cross(o)), dot(o));
    }

    public Vec2f abs() {
        return new Vec2f(Math.abs(x), Math.abs(y));
    }

    /** Rotates counter-clockwise by {@code angle} radians. */
    public Vec2f rotate(float angle) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        return new Vec2f(c * x - s * y, s * x + c * y);
    }

    public float minComponent() {
        return Math.min(x, y);
    }

    public float maxComponent() {
        return Math.max(x, y);
    }

    /** True when no component is NaN or infinite. */
    public boolean isFinite() {
        return Float.isFinite(x) && Float.isFinite(y);
    }

    /** Component-wise clamp to {@code [lo, hi]}. */
    public Vec2f clamp(Vec2f lo, Vec2f hi) {
        return new Vec2f(Math.min(Math.max(x, lo.x), hi.x), Math.min(Math.max(y, lo.y), hi.y));
    }

    public Vec2f clamp(float lo, float hi) {
        return new Vec2f(Math.min(Math.max(x, lo), hi), Math.min(Math.max(y, lo), hi));
    }

    /** Clamps every component to [0, 1]. */
    public Vec2f saturate() {
        return clamp(0f, 1f);
    }

    public Vec2f floor() {
        return new Vec2f((float) Math.floor(x), (float) Math.floor(y));
    }

    public Vec2f ceil() {
        return new Vec2f((float) Math.ceil(x), (float) Math.ceil(y));
    }

    /** Fractional part as in GLSL: {@code v - floor(v)}, always in [0, 1). */
    public Vec2f fract() {
        return new Vec2f(x - (float) Math.floor(x), y - (float) Math.floor(y));
    }

    /** -1, 0 or +1 per component (NaN stays NaN). */
    public Vec2f sign() {
        return new Vec2f(Math.signum(x), Math.signum(y));
    }

    /** 0 where the component is below {@code edge}, else 1. */
    public Vec2f step(float edge) {
        return new Vec2f(x < edge ? 0f : 1f, y < edge ? 0f : 1f);
    }

    /** Hermite interpolation of each component between {@code e0} and {@code e1}, as in GLSL. */
    public Vec2f smoothstep(float e0, float e1) {
        return new Vec2f(smooth(x, e0, e1), smooth(y, e0, e1));
    }

    private static float smooth(float v, float e0, float e1) {
        float t = Math.min(Math.max((v - e0) / (e1 - e0), 0f), 1f);
        return t * t * (3f - 2f * t);
    }

    /** Mirror around the unit normal {@code n}: {@code this - 2 (this . n) n}. */
    public Vec2f reflect(Vec2f n) {
        float d = 2f * dot(n);
        return new Vec2f(x - d * n.x, y - d * n.y);
    }

    /**
     * Refraction through a surface with unit normal {@code n} and index ratio {@code eta} (GLSL
     * {@code refract}). This vector must be a unit incident direction. Returns {@link #ZERO} on total
     * internal reflection.
     */
    public Vec2f refract(Vec2f n, float eta) {
        float d = dot(n);
        float k = 1f - eta * eta * (1f - d * d);
        if (k < 0f) {
            return ZERO;
        }
        float s = eta * d + (float) Math.sqrt(k);
        return new Vec2f(eta * x - s * n.x, eta * y - s * n.y);
    }

    /** Component of this vector along {@code onto}. Returns {@link #ZERO} when {@code onto} is zero. */
    public Vec2f project(Vec2f onto) {
        float d = onto.lengthSquared();
        if (d == 0f) {
            return ZERO;
        }
        float s = dot(onto) / d;
        return new Vec2f(onto.x * s, onto.y * s);
    }

    /** Component perpendicular to {@code onto}: {@code this - project(onto)}. */
    public Vec2f reject(Vec2f onto) {
        return sub(project(onto));
    }

    /** GLSL {@code faceforward}: this vector, flipped when {@code ref . incident >= 0}. */
    public Vec2f faceForward(Vec2f incident, Vec2f ref) {
        return ref.dot(incident) < 0f ? this : negate();
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

    @FloatOnly
    public Vec2d toDouble() {
        return new Vec2d(x, y);
    }

    @DoubleOnly
    public Vec2f toFloat() {
        return new Vec2f((float) x, (float) y);
    }
}
