package vmath.core;

import vmath.annotations.DoubleOnly;
import vmath.annotations.Eps;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;

/**
 * Immutable float quaternion {@code (x, y, z, w)} with {@code w} as the scalar part.
 *
 * <p>Conventions: Hamilton product, {@code a.mul(b)} applies {@code b} first then {@code a}
 * (same as JOML and matrix multiplication). Rotations follow the right-hand rule.
 *
 * <p>Valhalla: {@code -Pvalhalla} builds turn {@code @ValueType} into a real
 * {@code value record}. Never use {@code ==}, {@code synchronized} or identity-based APIs on it.
 */
@GenerateDouble
@ValueType
public record Quatf(float x, float y, float z, float w) {

    public static final Quatf IDENTITY = new Quatf(0f, 0f, 0f, 1f);

    /** Rotation of {@code angle} radians about {@code axis} (normalized internally). */
    public static Quatf fromAxisAngle(float angle, Vec3f axis) {
        return fromAxisAngle(angle, axis.x(), axis.y(), axis.z());
    }

    public static Quatf fromAxisAngle(float angle, float ax, float ay, float az) {
        float half = angle * 0.5f;
        float s = (float) Math.sin(half);
        float invLen = 1f / (float) Math.sqrt(ax * ax + ay * ay + az * az);
        return new Quatf(ax * invLen * s, ay * invLen * s, az * invLen * s, (float) Math.cos(half));
    }

    public static Quatf rotationX(float angle) {
        float half = angle * 0.5f;
        return new Quatf((float) Math.sin(half), 0f, 0f, (float) Math.cos(half));
    }

    public static Quatf rotationY(float angle) {
        float half = angle * 0.5f;
        return new Quatf(0f, (float) Math.sin(half), 0f, (float) Math.cos(half));
    }

    public static Quatf rotationZ(float angle) {
        float half = angle * 0.5f;
        return new Quatf(0f, 0f, (float) Math.sin(half), (float) Math.cos(half));
    }

    /** Hamilton product {@code this * r}: applies {@code r} first, then {@code this}. */
    public Quatf mul(Quatf r) {
        return new Quatf(
                w * r.x + x * r.w + y * r.z - z * r.y,
                w * r.y - x * r.z + y * r.w + z * r.x,
                w * r.z + x * r.y - y * r.x + z * r.w,
                w * r.w - x * r.x - y * r.y - z * r.z);
    }

    public Quatf conjugate() {
        return new Quatf(-x, -y, -z, w);
    }

    /** Multiplicative inverse. For unit quaternions prefer the cheaper {@link #conjugate()}. */
    public Quatf invert() {
        float inv = 1f / lengthSquared();
        return new Quatf(-x * inv, -y * inv, -z * inv, w * inv);
    }

    public float dot(Quatf o) {
        return x * o.x + y * o.y + z * o.z + w * o.w;
    }

    public float lengthSquared() {
        return x * x + y * y + z * z + w * w;
    }

    public float length() {
        return (float) Math.sqrt(lengthSquared());
    }

    /**
     * Unit vector in the same direction. A zero, NaN or infinite input yields NaN components. A very large or very small vector, whose squared length would
     * overflow or underflow, is scaled first and still gives the right direction (the plain formula would return zeros or infinities for it).
     */
    public Quatf normalize() {
        float len2 = x * x + y * y + z * z + w * w;
        if (len2 >= Float.MIN_NORMAL && len2 <= Float.MAX_VALUE) {
            float inv = 1f / (float) Math.sqrt(len2);
            return new Quatf(x * inv, y * inv, z * inv, w * inv);
        }
        return normalizeScaled();
    }

    /** The slow path of {@link #normalize()}: divide by the largest component first so that the squares neither overflow nor underflow. */
    private Quatf normalizeScaled() {
        float m = Math.max(Math.abs(x), Math.max(Math.abs(y), Math.max(Math.abs(z), Math.abs(w))));
        if (!(m > 0f) || m == Float.POSITIVE_INFINITY) {
            return new Quatf(Float.NaN, Float.NaN, Float.NaN, Float.NaN);
        }
        float a = x / m, b = y / m, c = z / m, d = w / m;
        float inv = 1f / (float) Math.sqrt(a * a + b * b + c * c + d * d);
        return new Quatf(a * inv, b * inv, c * inv, d * inv);
    }

    /**
     * Rotates {@code v}. Assumes this quaternion is unit length; call {@link #normalize()}
     * first after long chains of multiplications.
     */
    public Vec3f transform(Vec3f v) {
        // t = 2 * cross(q.xyz, v); v' = v + w * t + cross(q.xyz, t)
        float tx = 2f * (y * v.z() - z * v.y());
        float ty = 2f * (z * v.x() - x * v.z());
        float tz = 2f * (x * v.y() - y * v.x());
        return new Vec3f(
                v.x() + w * tx + (y * tz - z * ty),
                v.y() + w * ty + (z * tx - x * tz),
                v.z() + w * tz + (x * ty - y * tx));
    }

    /** Spherical interpolation along the shortest arc; falls back to lerp for nearly equal inputs. */
    public Quatf slerp(Quatf target, float alpha) {
        float cosom = dot(target);
        float absCosom = Math.abs(cosom);
        float scale0;
        float scale1;
        if (1f - absCosom > 1e-6f) {
            float sinSqr = 1f - absCosom * absCosom;
            float sinom = 1f / (float) Math.sqrt(sinSqr);
            float omega = (float) Math.atan2(sinSqr * sinom, absCosom);
            scale0 = (float) (Math.sin((1.0 - alpha) * omega) * sinom);
            scale1 = (float) (Math.sin(alpha * omega) * sinom);
        } else {
            scale0 = 1f - alpha;
            scale1 = alpha;
        }
        scale1 = cosom >= 0f ? scale1 : -scale1;
        return new Quatf(
                scale0 * x + scale1 * target.x,
                scale0 * y + scale1 * target.y,
                scale0 * z + scale1 * target.z,
                scale0 * w + scale1 * target.w);
    }

    /** Normalized linear interpolation along the shortest arc. Cheaper than slerp, not constant speed. */
    public Quatf nlerp(Quatf target, float alpha) {
        float s1 = dot(target) >= 0f ? alpha : -alpha;
        float s0 = 1f - alpha;
        return new Quatf(
                s0 * x + s1 * target.x,
                s0 * y + s1 * target.y,
                s0 * z + s1 * target.z,
                s0 * w + s1 * target.w).normalize();
    }

    /** Rotation angle in radians along the shortest arc, in [0, PI]. Assumes unit length. */
    public float angle() {
        double a = 2.0 * Math.acos(Math.max(-1.0, Math.min(1.0, w)));
        return (float) (a <= Math.PI ? a : 2.0 * Math.PI - a);
    }

    public Mat3f toMat3() {
        return Mat3f.rotation(this);
    }

    public Mat4f toMat4() {
        return Mat4f.rotation(this);
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

    /** True when no component is NaN or infinite. */
    public boolean isFinite() {
        return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z) && Float.isFinite(w);
    }

    /**
     * Unit rotation axis consistent with {@link #angle()} (shortest arc), so {@code fromAxisAngle(angle(), axis())}
     * reproduces this rotation. Returns {@link Vec3f#UNIT_X} for a (near-)zero rotation. Assumes unit length.
     */
    public Vec3f axis() {
        float s2 = x * x + y * y + z * z;
        if (s2 <= 1e-12f) {
            return Vec3f.UNIT_X;
        }
        float inv = (w < 0f ? -1f : 1f) / (float) Math.sqrt(s2);
        return new Vec3f(x * inv, y * inv, z * inv);
    }

    /**
     * Rotation from a rotation matrix (orthonormal, determinant +1). Uses the numerically stable branch on the
     * largest of trace and diagonal, and normalizes the result to absorb small drift.
     */
    public static Quatf fromMat3(Mat3f m) {
        float t = m.m00() + m.m11() + m.m22();
        float qx, qy, qz, qw;
        if (t > 0f) {
            float s = (float) Math.sqrt(t + 1f) * 2f;
            qw = 0.25f * s;
            qx = (m.m12() - m.m21()) / s;
            qy = (m.m20() - m.m02()) / s;
            qz = (m.m01() - m.m10()) / s;
        } else if (m.m00() > m.m11() && m.m00() > m.m22()) {
            float s = (float) Math.sqrt(1f + m.m00() - m.m11() - m.m22()) * 2f;
            qw = (m.m12() - m.m21()) / s;
            qx = 0.25f * s;
            qy = (m.m10() + m.m01()) / s;
            qz = (m.m20() + m.m02()) / s;
        } else if (m.m11() > m.m22()) {
            float s = (float) Math.sqrt(1f + m.m11() - m.m00() - m.m22()) * 2f;
            qw = (m.m20() - m.m02()) / s;
            qx = (m.m10() + m.m01()) / s;
            qy = 0.25f * s;
            qz = (m.m21() + m.m12()) / s;
        } else {
            float s = (float) Math.sqrt(1f + m.m22() - m.m00() - m.m11()) * 2f;
            qw = (m.m01() - m.m10()) / s;
            qx = (m.m20() + m.m02()) / s;
            qy = (m.m21() + m.m12()) / s;
            qz = 0.25f * s;
        }
        return new Quatf(qx, qy, qz, qw).normalize();
    }

    /**
     * Shortest-arc rotation taking direction {@code from} to direction {@code to}. The inputs need not be unit
     * length. Uses the half-vector form {@code (a x h, a . h)} with {@code h = normalize(a + b)}, which stays
     * accurate right up to nearly opposite directions. Exactly opposite directions give a 180 degree rotation about
     * an arbitrary perpendicular axis.
     */
    public static Quatf fromTo(Vec3f from, Vec3f to) {
        Vec3f a = from.normalize();
        Vec3f b = to.normalize();
        Vec3f h = a.add(b);
        float len2 = h.lengthSquared();
        if (len2 < OPPOSITE_EPS) {
            Vec3f p = a.anyPerpendicular();
            return new Quatf(p.x(), p.y(), p.z(), 0f);
        }
        h = h.mul(1f / (float) Math.sqrt(len2));
        Vec3f c = a.cross(h);
        return new Quatf(c.x(), c.y(), c.z(), a.dot(h));
    }

    @Eps(d = 1e-24)
    private static final float OPPOSITE_EPS = 1e-10f;

    private static Quatf axisRotation(int axis, float angle) {
        return switch (axis) {
            case 0 -> rotationX(angle);
            case 1 -> rotationY(angle);
            default -> rotationZ(angle);
        };
    }

    /**
     * Rotation from three angles in the given {@linkplain EulerOrder extrinsic order}: {@code a} about the first axis
     * is applied first, then {@code b} about the second, then {@code c} about the third.
     */
    public static Quatf fromEuler(EulerOrder order, float a, float b, float c) {
        return axisRotation(order.third(), c).mul(axisRotation(order.second(), b)).mul(axisRotation(order.first(), a));
    }

    public static Quatf fromEuler(EulerOrder order, Vec3f angles) {
        return fromEuler(order, angles.x(), angles.y(), angles.z());
    }

    /**
     * The angles {@code (a, b, c)} such that {@code fromEuler(order, a, b, c)} reproduces this rotation. Works for all
     * twelve orders (Bernardes and Viollet's direct method). The middle angle is in [-PI/2, PI/2] for Tait-Bryan
     * orders and [0, PI] for proper orders. At gimbal lock the third angle is set to zero. Assumes unit length.
     */
    public Vec3f toEuler(EulerOrder order) {
        int i = order.first();
        int j = order.second();
        int k = order.third();
        boolean proper = i == k;
        if (proper) {
            k = 3 - i - j;
        }
        float sign = (i - j) * (j - k) * (k - i) / 2;
        float a, b, c, d;
        if (proper) {
            a = w;
            b = get(i);
            c = get(j);
            d = get(k) * sign;
        } else {
            a = w - get(j);
            b = get(i) + get(k) * sign;
            c = get(j) + w;
            d = get(k) * sign - get(i);
        }
        double t2 = 2.0 * Math.atan2(Math.hypot(c, d), Math.hypot(a, b));
        double plus = Math.atan2(b, a);
        double minus = Math.atan2(d, c);
        double t1;
        double t3;
        if (Math.abs(t2) < LOCK_EPS) {
            t1 = 2.0 * plus;
            t3 = 0.0;
        } else if (Math.abs(t2 - Math.PI) < LOCK_EPS) {
            t1 = -2.0 * minus;
            t3 = 0.0;
        } else {
            t1 = plus - minus;
            t3 = plus + minus;
        }
        if (!proper) {
            t3 *= sign;
            t2 -= Math.PI / 2.0;
        }
        return new Vec3f(wrapPi(t1), (float) t2, wrapPi(t3));
    }

    @Eps(d = 1e-12)
    private static final float LOCK_EPS = 1e-6f;

    private static float wrapPi(double a) {
        double r = a;
        if (r > Math.PI) {
            r -= 2.0 * Math.PI;
        } else if (r < -Math.PI) {
            r += 2.0 * Math.PI;
        }
        return (float) r;
    }

    /**
     * Orientation whose forward direction ({@code -Z}, as for {@link Mat4f#lookAt}) points along {@code forward} with
     * {@code +Y} as close to {@code up} as possible. The inputs need not be unit length; {@code up} must not be
     * parallel to {@code forward} (that yields NaN).
     */
    public static Quatf lookRotation(Vec3f forward, Vec3f up) {
        Vec3f zAxis = forward.negate().normalize();
        Vec3f xAxis = up.cross(zAxis).normalize();
        Vec3f yAxis = zAxis.cross(xAxis);
        return fromMat3(Mat3f.fromColumns(xAxis, yAxis, zAxis));
    }

    /**
     * Twist part of the swing-twist decomposition about {@code axis}: the rotation around exactly that axis such that
     * {@code this == swing(axis).mul(twist(axis))}. Assumes unit length.
     */
    public Quatf twist(Vec3f axis) {
        Vec3f n = axis.normalize();
        float p = x * n.x() + y * n.y() + z * n.z();
        float len = (float) Math.sqrt(p * p + w * w);
        if (len < 1e-12f) {
            return IDENTITY; // a half turn about an axis perpendicular to `axis` has no twist
        }
        float inv = 1f / len;
        return new Quatf(n.x() * p * inv, n.y() * p * inv, n.z() * p * inv, w * inv);
    }

    /** Swing part of the swing-twist decomposition about {@code axis}: {@code this * twist(axis)^-1}. */
    public Quatf swing(Vec3f axis) {
        return mul(twist(axis).conjugate());
    }

    /**
     * Logarithm of a unit quaternion: the pure quaternion {@code (axis * halfAngle, 0)}. Zero for the identity.
     */
    public Quatf log() {
        float vlen = (float) Math.sqrt(x * x + y * y + z * z);
        if (vlen < 1e-12f) {
            return new Quatf(0f, 0f, 0f, 0f);
        }
        float s = (float) Math.atan2(vlen, w) / vlen;
        return new Quatf(x * s, y * s, z * s, 0f);
    }

    /** Exponential: {@code e^w (cos|v|, sin|v| v/|v|)}. The inverse of {@link #log()} for pure quaternions. */
    public Quatf exp() {
        float vlen = (float) Math.sqrt(x * x + y * y + z * z);
        float ew = (float) Math.exp(w);
        if (vlen < 1e-12f) {
            return new Quatf(0f, 0f, 0f, ew);
        }
        float s = ew * (float) Math.sin(vlen) / vlen;
        return new Quatf(x * s, y * s, z * s, ew * (float) Math.cos(vlen));
    }

    /**
     * Fractional power: rotation about the same axis by {@code t} times the angle. {@code pow(0)} is the identity
     * and {@code pow(1)} is this quaternion. Assumes unit length.
     */
    public Quatf pow(float t) {
        float vlen = (float) Math.sqrt(x * x + y * y + z * z);
        if (vlen < 1e-12f) {
            return IDENTITY;
        }
        float half = (float) Math.atan2(vlen, w) * t;
        float s = (float) Math.sin(half) / vlen;
        return new Quatf(x * s, y * s, z * s, (float) Math.cos(half));
    }

    /**
     * Control quaternion for {@link #squad} at {@code cur}, given its neighbours in a keyframe sequence. Computed for
     * every interior key once; the two ends can pass themselves as the missing neighbour.
     */
    public static Quatf squadControl(Quatf prev, Quatf cur, Quatf next) {
        Quatf inv = cur.conjugate();
        Quatf n = cur.dot(next) < 0f ? next.negateAll() : next;
        Quatf p = cur.dot(prev) < 0f ? prev.negateAll() : prev;
        Quatf l1 = inv.mul(n).log();
        Quatf l0 = inv.mul(p).log();
        Quatf s = new Quatf(-(l1.x + l0.x) * 0.25f, -(l1.y + l0.y) * 0.25f, -(l1.z + l0.z) * 0.25f, 0f).exp();
        return cur.mul(s);
    }

    /**
     * Spherical cubic interpolation between this key and {@code next}, with {@link #squadControl} outputs {@code a}
     * (for this key) and {@code b} (for {@code next}). C1-continuous across keyframes, unlike chained slerps.
     */
    public Quatf squad(Quatf next, Quatf a, Quatf b, float t) {
        Quatf p = slerp(next, t);
        Quatf q = a.slerp(b, t);
        return p.slerp(q, 2f * t * (1f - t));
    }

    /**
     * Advances this orientation by a world-space angular velocity (radians per second) over {@code dt} seconds.
     * The result is normalized.
     */
    public Quatf integrate(Vec3f omega, float dt) {
        float len = omega.length();
        if (len < 1e-12f) {
            return this;
        }
        return fromAxisAngle(len * dt, omega).mul(this).normalize();
    }

    public boolean approxEquals(Quatf o, float eps) {
        return Math.abs(x - o.x) <= eps && Math.abs(y - o.y) <= eps
                && Math.abs(z - o.z) <= eps && Math.abs(w - o.w) <= eps;
    }

    /** True if both represent the same rotation ({@code q} and {@code -q} are equivalent). */
    public boolean sameRotation(Quatf o, float eps) {
        return approxEquals(o, eps) || approxEquals(o.negateAll(), eps);
    }

    private Quatf negateAll() {
        return new Quatf(-x, -y, -z, -w);
    }

    @FloatOnly
    public Quatd toDouble() {
        return new Quatd(x, y, z, w);
    }

    @DoubleOnly
    public Quatf toFloat() {
        return new Quatf((float) x, (float) y, (float) z, (float) w);
    }
}
