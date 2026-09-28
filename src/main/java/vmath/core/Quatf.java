package vmath.core;

/**
 * Immutable float quaternion {@code (x, y, z, w)} with {@code w} as the scalar part.
 *
 * <p>Conventions: Hamilton product, {@code a.mul(b)} applies {@code b} first then {@code a}
 * (same as JOML and matrix multiplication). Rotations follow the right-hand rule.
 *
 * <p>Valhalla: {@code -Pvalhalla} builds rewrite the {@code value} marker below into a real
 * {@code value record}. Never use {@code ==}, {@code synchronized} or identity-based APIs on it.
 */
public /*value*/ record Quatf(float x, float y, float z, float w) {

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

    public Quatf normalize() {
        float inv = 1f / length();
        return new Quatf(x * inv, y * inv, z * inv, w * inv);
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

    // @float-only-begin
    public Quatd toDouble() {
        return new Quatd(x, y, z, w);
    }
    // @float-only-end
}
