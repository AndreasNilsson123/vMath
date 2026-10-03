package vmath.core;

import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import java.nio.FloatBuffer;

/**
 * Immutable 3-component float vector.
 *
 * <p>Valhalla: {@code -Pvalhalla} builds turn {@code @ValueType} into a real
 * {@code value record}. Never use {@code ==}, {@code synchronized} or identity-based APIs on it.
 */
@GenerateDouble
@ValueType
public record Vec3f(float x, float y, float z) {

    /** The zero vector. */
    public static final Vec3f ZERO = new Vec3f(0f, 0f, 0f);
    /** The vector with every component 1. */
    public static final Vec3f ONE = new Vec3f(1f, 1f, 1f);
    /** The unit vector along +X. */
    public static final Vec3f UNIT_X = new Vec3f(1f, 0f, 0f);
    /** The unit vector along +Y. */
    public static final Vec3f UNIT_Y = new Vec3f(0f, 1f, 0f);
    /** The unit vector along +Z. */
    public static final Vec3f UNIT_Z = new Vec3f(0f, 0f, 1f);

    /** A vector with every component equal to {@code s}. */
    public static Vec3f splat(float s) {
        return new Vec3f(s, s, s);
    }

    /** The sum {@code this + o}. */
    public Vec3f add(Vec3f o) {
        return new Vec3f(x + o.x, y + o.y, z + o.z);
    }

    /** The sum of this and the vector with the given components. */
    public Vec3f add(float ox, float oy, float oz) {
        return new Vec3f(x + ox, y + oy, z + oz);
    }

    /** The difference {@code this - o}. */
    public Vec3f sub(Vec3f o) {
        return new Vec3f(x - o.x, y - o.y, z - o.z);
    }

    /** Every component multiplied by {@code s}. */
    public Vec3f mul(float s) {
        return new Vec3f(x * s, y * s, z * s);
    }

    /** Component-wise product. */
    public Vec3f mul(Vec3f o) {
        return new Vec3f(x * o.x, y * o.y, z * o.z);
    }

    /** Every component divided by {@code s}; a zero divisor gives infinities or NaN, as in IEEE arithmetic. */
    public Vec3f div(float s) {
        float inv = 1f / s;
        return new Vec3f(x * inv, y * inv, z * inv);
    }

    /** Every component negated. */
    public Vec3f negate() {
        return new Vec3f(-x, -y, -z);
    }

    /** {@code this + a * s}. */
    public Vec3f fma(Vec3f a, float s) {
        return new Vec3f(x + a.x * s, y + a.y * s, z + a.z * s);
    }

    /** The dot product. */
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

    /** The squared length. */
    public float lengthSquared() {
        return x * x + y * y + z * z;
    }

    /** The length. */
    public float length() {
        return (float) Math.sqrt(lengthSquared());
    }

    /** The squared distance to {@code o}; cheaper than {@link #distance}. */
    public float distanceSquared(Vec3f o) {
        float dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** The distance to {@code o}. */
    public float distance(Vec3f o) {
        return (float) Math.sqrt(distanceSquared(o));
    }

    /**
     * Unit vector in the same direction. A zero, NaN or infinite input yields NaN components. A very large or very small vector, whose squared length would
     * overflow or underflow, is scaled first and still gives the right direction (the plain formula would return zeros or infinities for it).
     */
    public Vec3f normalize() {
        float len2 = x * x + y * y + z * z;
        if (len2 >= Float.MIN_NORMAL && len2 <= Float.MAX_VALUE) {
            float inv = 1f / (float) Math.sqrt(len2);
            return new Vec3f(x * inv, y * inv, z * inv);
        }
        return normalizeScaled();
    }

    /** The slow path of {@link #normalize()}: divide by the largest component first so that the squares neither overflow nor underflow. */
    private Vec3f normalizeScaled() {
        float m = Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
        if (!(m > 0f) || m == Float.POSITIVE_INFINITY) {
            return new Vec3f(Float.NaN, Float.NaN, Float.NaN);
        }
        float a = x / m, b = y / m, c = z / m;
        float inv = 1f / (float) Math.sqrt(a * a + b * b + c * c);
        return new Vec3f(a * inv, b * inv, c * inv);
    }

    /** Like {@link #normalize()} but returns {@link #ZERO} for (near-)zero input. */
    public Vec3f normalizeOrZero() {
        float len2 = lengthSquared();
        if (len2 <= 1e-30f) {
            return ZERO;
        }
        if (!(len2 <= Float.MAX_VALUE)) { // overflowed (a huge vector keeps its direction) or NaN (stays NaN)
            return normalizeScaled();
        }
        float inv = 1f / (float) Math.sqrt(len2);
        return new Vec3f(x * inv, y * inv, z * inv);
    }

    /** The linear interpolation {@code this + (o - this) * t}; {@code t} is not clamped. */
    public Vec3f lerp(Vec3f o, float t) {
        return new Vec3f(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t);
    }

    /** Angle between this and {@code o} in radians, in [0, PI]. */
    public float angle(Vec3f o) {
        return (float) Math.atan2(cross(o).length(), dot(o));
    }

    /** The component-wise minimum. */
    public Vec3f min(Vec3f o) {
        return new Vec3f(Math.min(x, o.x), Math.min(y, o.y), Math.min(z, o.z));
    }

    /** The component-wise maximum. */
    public Vec3f max(Vec3f o) {
        return new Vec3f(Math.max(x, o.x), Math.max(y, o.y), Math.max(z, o.z));
    }

    /** The absolute value of every component. */
    public Vec3f abs() {
        return new Vec3f(Math.abs(x), Math.abs(y), Math.abs(z));
    }

    /**
     * A unit vector perpendicular to this one. Zero input yields NaN components.
     */
    public Vec3f anyPerpendicular() {
        float ax = Math.abs(x), ay = Math.abs(y), az = Math.abs(z);
        Vec3f axis = ax <= ay && ax <= az ? UNIT_X : ay <= az ? UNIT_Y : UNIT_Z;
        return cross(axis).normalize();
    }

    /** The smallest component. */
    public float minComponent() {
        return Math.min(Math.min(x, y), z);
    }

    /** The largest component. */
    public float maxComponent() {
        return Math.max(Math.max(x, y), z);
    }

    /** True when no component is NaN or infinite. */
    public boolean isFinite() {
        return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z);
    }

    /** Component-wise clamp to {@code [lo, hi]}. */
    public Vec3f clamp(Vec3f lo, Vec3f hi) {
        return new Vec3f(Math.min(Math.max(x, lo.x), hi.x), Math.min(Math.max(y, lo.y), hi.y), Math.min(Math.max(z, lo.z), hi.z));
    }

    /** Every component clamped to {@code [lo, hi]}. */
    public Vec3f clamp(float lo, float hi) {
        return new Vec3f(Math.min(Math.max(x, lo), hi), Math.min(Math.max(y, lo), hi), Math.min(Math.max(z, lo), hi));
    }

    /** Clamps every component to [0, 1]. */
    public Vec3f saturate() {
        return clamp(0f, 1f);
    }

    /** Every component rounded down to a whole number. */
    public Vec3f floor() {
        return new Vec3f((float) Math.floor(x), (float) Math.floor(y), (float) Math.floor(z));
    }

    /** Every component rounded up to a whole number. */
    public Vec3f ceil() {
        return new Vec3f((float) Math.ceil(x), (float) Math.ceil(y), (float) Math.ceil(z));
    }

    /** Fractional part as in GLSL: {@code v - floor(v)}, always in [0, 1). */
    public Vec3f fract() {
        return new Vec3f(x - (float) Math.floor(x), y - (float) Math.floor(y), z - (float) Math.floor(z));
    }

    /** -1, 0 or +1 per component (NaN stays NaN). */
    public Vec3f sign() {
        return new Vec3f(Math.signum(x), Math.signum(y), Math.signum(z));
    }

    /** 0 where the component is below {@code edge}, else 1. */
    public Vec3f step(float edge) {
        return new Vec3f(x < edge ? 0f : 1f, y < edge ? 0f : 1f, z < edge ? 0f : 1f);
    }

    /** Hermite interpolation of each component between {@code e0} and {@code e1}, as in GLSL. */
    public Vec3f smoothstep(float e0, float e1) {
        return new Vec3f(smooth(x, e0, e1), smooth(y, e0, e1), smooth(z, e0, e1));
    }

    private static float smooth(float v, float e0, float e1) {
        float t = Math.min(Math.max((v - e0) / (e1 - e0), 0f), 1f);
        return t * t * (3f - 2f * t);
    }

    /** Mirror around the unit normal {@code n}: {@code this - 2 (this . n) n}. */
    public Vec3f reflect(Vec3f n) {
        float d = 2f * dot(n);
        return new Vec3f(x - d * n.x, y - d * n.y, z - d * n.z);
    }

    /**
     * Refraction through a surface with unit normal {@code n} and index ratio {@code eta} (GLSL
     * {@code refract}). This vector must be a unit incident direction. Returns {@link #ZERO} on total
     * internal reflection.
     */
    public Vec3f refract(Vec3f n, float eta) {
        float d = dot(n);
        float k = 1f - eta * eta * (1f - d * d);
        if (k < 0f) {
            return ZERO;
        }
        float s = eta * d + (float) Math.sqrt(k);
        return new Vec3f(eta * x - s * n.x, eta * y - s * n.y, eta * z - s * n.z);
    }

    /** Component of this vector along {@code onto}. Returns {@link #ZERO} when {@code onto} is zero. */
    public Vec3f project(Vec3f onto) {
        float d = onto.lengthSquared();
        if (d == 0f) {
            return ZERO;
        }
        float s = dot(onto) / d;
        return new Vec3f(onto.x * s, onto.y * s, onto.z * s);
    }

    /** Component perpendicular to {@code onto}: {@code this - project(onto)}. */
    public Vec3f reject(Vec3f onto) {
        return sub(project(onto));
    }

    /** GLSL {@code faceforward}: this vector, flipped when {@code ref . incident >= 0}. */
    public Vec3f faceForward(Vec3f incident, Vec3f ref) {
        return ref.dot(incident) < 0f ? this : negate();
    }

    /** Component {@code i} (0 is x, 1 is y, and so on); {@link IndexOutOfBoundsException} for any other index. */
    public float get(int i) {
        return switch (i) {
            case 0 -> x;
            case 1 -> y;
            case 2 -> z;
            default -> throw new IndexOutOfBoundsException(i);
        };
    }

    /** True when every component differs from that of {@code o} by at most {@code eps}. */
    public boolean approxEquals(Vec3f o, float eps) {
        return Math.abs(x - o.x) <= eps && Math.abs(y - o.y) <= eps && Math.abs(z - o.z) <= eps;
    }

    /** Writes the components to {@code dst[off ..]} in order. */
    public void writeTo(float[] dst, int off) {
        dst[off] = x;
        dst[off + 1] = y;
        dst[off + 2] = z;
    }

    /** Absolute write at {@code index}; does not change the buffer position. */
    public void writeTo(FloatBuffer dst, int index) {
        dst.put(index, x).put(index + 1, y).put(index + 2, z);
    }

    /** The same value with double components. */
    @FloatOnly
    public Vec3d toDouble() {
        return new Vec3d(x, y, z);
    }

    /** The same value with float components (rounded to the nearest float for double types). */
    @DoubleOnly
    public Vec3f toFloat() {
        return new Vec3f((float) x, (float) y, (float) z);
    }

    /**
     * {@code this - origin}, subtracted in double and then narrowed to float. This is the core of
     * camera-relative rendering: pass the camera's world position as {@code origin} so vertex
     * data stays small enough for float precision on the GPU.
     */
    @DoubleOnly
    public Vec3f relativeTo(Vec3d origin) {
        return new Vec3f((float) (x - origin.x), (float) (y - origin.y), (float) (z - origin.z));
    }
}
