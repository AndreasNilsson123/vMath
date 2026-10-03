package vmath.core;

import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import java.nio.FloatBuffer;

/**
 * Immutable 4-component float vector (homogeneous coordinates, colors, plane equations).
 *
 * <p>Valhalla: {@code -Pvalhalla} builds turn {@code @ValueType} into a real
 * {@code value record}. Never use {@code ==}, {@code synchronized} or identity-based APIs on it.
 */
@GenerateDouble
@ValueType
public record Vec4f(float x, float y, float z, float w) {

    /** The zero vector. */
    public static final Vec4f ZERO = new Vec4f(0f, 0f, 0f, 0f);
    /** The vector with every component 1. */
    public static final Vec4f ONE = new Vec4f(1f, 1f, 1f, 1f);

    /** A vector with every component equal to {@code s}. */
    public static Vec4f splat(float s) {
        return new Vec4f(s, s, s, s);
    }

    /** Point: {@code (v, 1)}. */
    public static Vec4f point(Vec3f v) {
        return new Vec4f(v.x(), v.y(), v.z(), 1f);
    }

    /** Direction: {@code (v, 0)}. */
    public static Vec4f direction(Vec3f v) {
        return new Vec4f(v.x(), v.y(), v.z(), 0f);
    }

    /** The sum {@code this + o}. */
    public Vec4f add(Vec4f o) {
        return new Vec4f(x + o.x, y + o.y, z + o.z, w + o.w);
    }

    /** The difference {@code this - o}. */
    public Vec4f sub(Vec4f o) {
        return new Vec4f(x - o.x, y - o.y, z - o.z, w - o.w);
    }

    /** Every component multiplied by {@code s}. */
    public Vec4f mul(float s) {
        return new Vec4f(x * s, y * s, z * s, w * s);
    }

    /** Component-wise product. */
    public Vec4f mul(Vec4f o) {
        return new Vec4f(x * o.x, y * o.y, z * o.z, w * o.w);
    }

    /** Every component negated. */
    public Vec4f negate() {
        return new Vec4f(-x, -y, -z, -w);
    }

    /** The dot product. */
    public float dot(Vec4f o) {
        return x * o.x + y * o.y + z * o.z + w * o.w;
    }

    /** The squared length. */
    public float lengthSquared() {
        return x * x + y * y + z * z + w * w;
    }

    /** The length. */
    public float length() {
        return (float) Math.sqrt(lengthSquared());
    }

    /**
     * Unit vector in the same direction. A zero, NaN or infinite input yields NaN components. A very large or very small vector, whose squared length would
     * overflow or underflow, is scaled first and still gives the right direction (the plain formula would return zeros or infinities for it).
     */
    public Vec4f normalize() {
        float len2 = x * x + y * y + z * z + w * w;
        if (len2 >= Float.MIN_NORMAL && len2 <= Float.MAX_VALUE) {
            float inv = 1f / (float) Math.sqrt(len2);
            return new Vec4f(x * inv, y * inv, z * inv, w * inv);
        }
        return normalizeScaled();
    }

    /** The slow path of {@link #normalize()}: divide by the largest component first so that the squares neither overflow nor underflow. */
    private Vec4f normalizeScaled() {
        float m = Math.max(Math.abs(x), Math.max(Math.abs(y), Math.max(Math.abs(z), Math.abs(w))));
        if (!(m > 0f) || m == Float.POSITIVE_INFINITY) {
            return new Vec4f(Float.NaN, Float.NaN, Float.NaN, Float.NaN);
        }
        float a = x / m, b = y / m, c = z / m, d = w / m;
        float inv = 1f / (float) Math.sqrt(a * a + b * b + c * c + d * d);
        return new Vec4f(a * inv, b * inv, c * inv, d * inv);
    }

    /** The linear interpolation {@code this + (o - this) * t}; {@code t} is not clamped. */
    public Vec4f lerp(Vec4f o, float t) {
        return new Vec4f(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t, w + (o.w - w) * t);
    }

    /** The first three components. */
    public Vec3f xyz() {
        return new Vec3f(x, y, z);
    }

    /** Perspective divide: {@code xyz / w}. */
    public Vec3f divideByW() {
        float inv = 1f / w;
        return new Vec3f(x * inv, y * inv, z * inv);
    }

    /** The sum of this and the vector with the given components. */
    public Vec4f add(float ox, float oy, float oz, float ow) {
        return new Vec4f(x + ox, y + oy, z + oz, w + ow);
    }

    /** Every component divided by {@code s}; a zero divisor gives infinities or NaN, as in IEEE arithmetic. */
    public Vec4f div(float s) {
        float inv = 1f / s;
        return new Vec4f(x * inv, y * inv, z * inv, w * inv);
    }

    /** {@code this + a * s}. */
    public Vec4f fma(Vec4f a, float s) {
        return new Vec4f(x + a.x * s, y + a.y * s, z + a.z * s, w + a.w * s);
    }

    /** The squared distance to {@code o}; cheaper than {@link #distance}. */
    public float distanceSquared(Vec4f o) {
        float dx = x - o.x, dy = y - o.y, dz = z - o.z, dw = w - o.w;
        return dx * dx + dy * dy + dz * dz + dw * dw;
    }

    /** The distance to {@code o}. */
    public float distance(Vec4f o) {
        return (float) Math.sqrt(distanceSquared(o));
    }

    /** Like {@link #normalize()} but returns {@link #ZERO} for (near-)zero input. */
    public Vec4f normalizeOrZero() {
        float len2 = lengthSquared();
        if (len2 <= 1e-30f) {
            return ZERO;
        }
        if (!(len2 <= Float.MAX_VALUE)) { // overflowed (a huge vector keeps its direction) or NaN (stays NaN)
            return normalizeScaled();
        }
        float inv = 1f / (float) Math.sqrt(len2);
        return new Vec4f(x * inv, y * inv, z * inv, w * inv);
    }

    /** The component-wise minimum. */
    public Vec4f min(Vec4f o) {
        return new Vec4f(Math.min(x, o.x), Math.min(y, o.y), Math.min(z, o.z), Math.min(w, o.w));
    }

    /** The component-wise maximum. */
    public Vec4f max(Vec4f o) {
        return new Vec4f(Math.max(x, o.x), Math.max(y, o.y), Math.max(z, o.z), Math.max(w, o.w));
    }

    /** The absolute value of every component. */
    public Vec4f abs() {
        return new Vec4f(Math.abs(x), Math.abs(y), Math.abs(z), Math.abs(w));
    }

    /** The smallest component. */
    public float minComponent() {
        return Math.min(Math.min(Math.min(x, y), z), w);
    }

    /** The largest component. */
    public float maxComponent() {
        return Math.max(Math.max(Math.max(x, y), z), w);
    }

    /** True when no component is NaN or infinite. */
    public boolean isFinite() {
        return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z) && Float.isFinite(w);
    }

    /** Component-wise clamp to {@code [lo, hi]}. */
    public Vec4f clamp(Vec4f lo, Vec4f hi) {
        return new Vec4f(Math.min(Math.max(x, lo.x), hi.x), Math.min(Math.max(y, lo.y), hi.y), Math.min(Math.max(z, lo.z), hi.z), Math.min(Math.max(w, lo.w), hi.w));
    }

    /** Every component clamped to {@code [lo, hi]}. */
    public Vec4f clamp(float lo, float hi) {
        return new Vec4f(Math.min(Math.max(x, lo), hi), Math.min(Math.max(y, lo), hi), Math.min(Math.max(z, lo), hi), Math.min(Math.max(w, lo), hi));
    }

    /** Clamps every component to [0, 1]. */
    public Vec4f saturate() {
        return clamp(0f, 1f);
    }

    /** Every component rounded down to a whole number. */
    public Vec4f floor() {
        return new Vec4f((float) Math.floor(x), (float) Math.floor(y), (float) Math.floor(z), (float) Math.floor(w));
    }

    /** Every component rounded up to a whole number. */
    public Vec4f ceil() {
        return new Vec4f((float) Math.ceil(x), (float) Math.ceil(y), (float) Math.ceil(z), (float) Math.ceil(w));
    }

    /** Fractional part as in GLSL: {@code v - floor(v)}, always in [0, 1). */
    public Vec4f fract() {
        return new Vec4f(x - (float) Math.floor(x), y - (float) Math.floor(y), z - (float) Math.floor(z), w - (float) Math.floor(w));
    }

    /** -1, 0 or +1 per component (NaN stays NaN). */
    public Vec4f sign() {
        return new Vec4f(Math.signum(x), Math.signum(y), Math.signum(z), Math.signum(w));
    }

    /** 0 where the component is below {@code edge}, else 1. */
    public Vec4f step(float edge) {
        return new Vec4f(x < edge ? 0f : 1f, y < edge ? 0f : 1f, z < edge ? 0f : 1f, w < edge ? 0f : 1f);
    }

    /** Hermite interpolation of each component between {@code e0} and {@code e1}, as in GLSL. */
    public Vec4f smoothstep(float e0, float e1) {
        return new Vec4f(smooth(x, e0, e1), smooth(y, e0, e1), smooth(z, e0, e1), smooth(w, e0, e1));
    }

    private static float smooth(float v, float e0, float e1) {
        float t = Math.min(Math.max((v - e0) / (e1 - e0), 0f), 1f);
        return t * t * (3f - 2f * t);
    }

    /** Component of this vector along {@code onto}. Returns {@link #ZERO} when {@code onto} is zero. */
    public Vec4f project(Vec4f onto) {
        float d = onto.lengthSquared();
        if (d == 0f) {
            return ZERO;
        }
        float s = dot(onto) / d;
        return new Vec4f(onto.x * s, onto.y * s, onto.z * s, onto.w * s);
    }

    /** Component perpendicular to {@code onto}: {@code this - project(onto)}. */
    public Vec4f reject(Vec4f onto) {
        return sub(project(onto));
    }

    /**
     * Angle between this and {@code o} in radians, in [0, PI]; 0 if either is zero. There is no cross product in 4D, so this uses the
     * numerically stable form {@code 2 atan2(|u - v|, |u + v|)} of the unit vectors, which stays accurate for nearly parallel and nearly opposite inputs
     * where {@code acos(dot)} loses all precision.
     */
    public float angle(Vec4f o) {
        float la = length(), lb = o.length();
        if (la == 0f || lb == 0f) {
            return 0f;
        }
        Vec4f u = mul(1f / la), v = o.mul(1f / lb);
        return 2f * (float) Math.atan2(u.sub(v).length(), u.add(v).length());
    }

    /** Mirror around the unit normal {@code n}: {@code this - 2 (this . n) n}. */
    public Vec4f reflect(Vec4f n) {
        float d = 2f * dot(n);
        return new Vec4f(x - d * n.x, y - d * n.y, z - d * n.z, w - d * n.w);
    }

    /**
     * Refraction through a surface with unit normal {@code n} and index ratio {@code eta} (GLSL {@code refract}). This vector must be a unit
     * incident direction. Returns {@link #ZERO} on total internal reflection.
     */
    public Vec4f refract(Vec4f n, float eta) {
        float d = dot(n);
        float k = 1f - eta * eta * (1f - d * d);
        if (k < 0f) {
            return ZERO;
        }
        float s = eta * d + (float) Math.sqrt(k);
        return new Vec4f(eta * x - s * n.x, eta * y - s * n.y, eta * z - s * n.z, eta * w - s * n.w);
    }

    /** GLSL {@code faceforward}: this vector, flipped when {@code ref . incident >= 0}. */
    public Vec4f faceForward(Vec4f incident, Vec4f ref) {
        return ref.dot(incident) < 0f ? this : negate();
    }

    /** Component {@code i} (0 is x, 1 is y, and so on); {@link IndexOutOfBoundsException} for any other index. */
    public float get(int i) {
        return switch (i) {
            case 0 -> x;
            case 1 -> y;
            case 2 -> z;
            case 3 -> w;
            default -> throw new IndexOutOfBoundsException(i);
        };
    }

    /** True when every component differs from that of {@code o} by at most {@code eps}. */
    public boolean approxEquals(Vec4f o, float eps) {
        return Math.abs(x - o.x) <= eps && Math.abs(y - o.y) <= eps
                && Math.abs(z - o.z) <= eps && Math.abs(w - o.w) <= eps;
    }

    /** Writes the components to {@code dst[off ..]} in order. */
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

    /** The same value with double components. */
    @FloatOnly
    public Vec4d toDouble() {
        return new Vec4d(x, y, z, w);
    }

    /** The same value with float components (rounded to the nearest float for double types). */
    @DoubleOnly
    public Vec4f toFloat() {
        return new Vec4f((float) x, (float) y, (float) z, (float) w);
    }
}
