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

    public static final Vec4f ZERO = new Vec4f(0f, 0f, 0f, 0f);
    public static final Vec4f ONE = new Vec4f(1f, 1f, 1f, 1f);

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

    public Vec4f add(float ox, float oy, float oz, float ow) {
        return new Vec4f(x + ox, y + oy, z + oz, w + ow);
    }

    public Vec4f div(float s) {
        float inv = 1f / s;
        return new Vec4f(x * inv, y * inv, z * inv, w * inv);
    }

    /** {@code this + a * s}. */
    public Vec4f fma(Vec4f a, float s) {
        return new Vec4f(x + a.x * s, y + a.y * s, z + a.z * s, w + a.w * s);
    }

    public float distanceSquared(Vec4f o) {
        float dx = x - o.x, dy = y - o.y, dz = z - o.z, dw = w - o.w;
        return dx * dx + dy * dy + dz * dz + dw * dw;
    }

    public float distance(Vec4f o) {
        return (float) Math.sqrt(distanceSquared(o));
    }

    /** Like {@link #normalize()} but returns {@link #ZERO} for (near-)zero input. */
    public Vec4f normalizeOrZero() {
        float len2 = lengthSquared();
        if (len2 <= 1e-30f) {
            return ZERO;
        }
        float inv = 1f / (float) Math.sqrt(len2);
        return new Vec4f(x * inv, y * inv, z * inv, w * inv);
    }

    public Vec4f min(Vec4f o) {
        return new Vec4f(Math.min(x, o.x), Math.min(y, o.y), Math.min(z, o.z), Math.min(w, o.w));
    }

    public Vec4f max(Vec4f o) {
        return new Vec4f(Math.max(x, o.x), Math.max(y, o.y), Math.max(z, o.z), Math.max(w, o.w));
    }

    public Vec4f abs() {
        return new Vec4f(Math.abs(x), Math.abs(y), Math.abs(z), Math.abs(w));
    }

    public float minComponent() {
        return Math.min(Math.min(Math.min(x, y), z), w);
    }

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

    public Vec4f clamp(float lo, float hi) {
        return new Vec4f(Math.min(Math.max(x, lo), hi), Math.min(Math.max(y, lo), hi), Math.min(Math.max(z, lo), hi), Math.min(Math.max(w, lo), hi));
    }

    /** Clamps every component to [0, 1]. */
    public Vec4f saturate() {
        return clamp(0f, 1f);
    }

    public Vec4f floor() {
        return new Vec4f((float) Math.floor(x), (float) Math.floor(y), (float) Math.floor(z), (float) Math.floor(w));
    }

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

    @FloatOnly
    public Vec4d toDouble() {
        return new Vec4d(x, y, z, w);
    }

    @DoubleOnly
    public Vec4f toFloat() {
        return new Vec4f((float) x, (float) y, (float) z, (float) w);
    }
}
