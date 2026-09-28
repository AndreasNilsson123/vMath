package vmath.core;

// GENERATED from Quatf.java by tools/GenDouble.java. Do not edit; edit the float source.

/**
 * Immutable double quaternion {@code (x, y, z, w)} with {@code w} as the scalar part.
 *
 * <p>Conventions: Hamilton product, {@code a.mul(b)} applies {@code b} first then {@code a}
 * (same as JOML and matrix multiplication). Rotations follow the right-hand rule.
 *
 * <p>Valhalla: {@code -Pvalhalla} builds rewrite the {@code value} marker below into a real
 * {@code value record}. Never use {@code ==}, {@code synchronized} or identity-based APIs on it.
 */
public /*value*/ record Quatd(double x, double y, double z, double w) {

    public static final Quatd IDENTITY = new Quatd(0.0, 0.0, 0.0, 1.0);

    /** Rotation of {@code angle} radians about {@code axis} (normalized internally). */
    public static Quatd fromAxisAngle(double angle, Vec3d axis) {
        return fromAxisAngle(angle, axis.x(), axis.y(), axis.z());
    }

    public static Quatd fromAxisAngle(double angle, double ax, double ay, double az) {
        double half = angle * 0.5;
        double s = Math.sin(half);
        double invLen = 1.0 / Math.sqrt(ax * ax + ay * ay + az * az);
        return new Quatd(ax * invLen * s, ay * invLen * s, az * invLen * s, Math.cos(half));
    }

    public static Quatd rotationX(double angle) {
        double half = angle * 0.5;
        return new Quatd(Math.sin(half), 0.0, 0.0, Math.cos(half));
    }

    public static Quatd rotationY(double angle) {
        double half = angle * 0.5;
        return new Quatd(0.0, Math.sin(half), 0.0, Math.cos(half));
    }

    public static Quatd rotationZ(double angle) {
        double half = angle * 0.5;
        return new Quatd(0.0, 0.0, Math.sin(half), Math.cos(half));
    }

    /** Hamilton product {@code this * r}: applies {@code r} first, then {@code this}. */
    public Quatd mul(Quatd r) {
        return new Quatd(
                w * r.x + x * r.w + y * r.z - z * r.y,
                w * r.y - x * r.z + y * r.w + z * r.x,
                w * r.z + x * r.y - y * r.x + z * r.w,
                w * r.w - x * r.x - y * r.y - z * r.z);
    }

    public Quatd conjugate() {
        return new Quatd(-x, -y, -z, w);
    }

    /** Multiplicative inverse. For unit quaternions prefer the cheaper {@link #conjugate()}. */
    public Quatd invert() {
        double inv = 1.0 / lengthSquared();
        return new Quatd(-x * inv, -y * inv, -z * inv, w * inv);
    }

    public double dot(Quatd o) {
        return x * o.x + y * o.y + z * o.z + w * o.w;
    }

    public double lengthSquared() {
        return x * x + y * y + z * z + w * w;
    }

    public double length() {
        return Math.sqrt(lengthSquared());
    }

    public Quatd normalize() {
        double inv = 1.0 / length();
        return new Quatd(x * inv, y * inv, z * inv, w * inv);
    }

    /**
     * Rotates {@code v}. Assumes this quaternion is unit length; call {@link #normalize()}
     * first after long chains of multiplications.
     */
    public Vec3d transform(Vec3d v) {
        // t = 2 * cross(q.xyz, v); v' = v + w * t + cross(q.xyz, t)
        double tx = 2.0 * (y * v.z() - z * v.y());
        double ty = 2.0 * (z * v.x() - x * v.z());
        double tz = 2.0 * (x * v.y() - y * v.x());
        return new Vec3d(
                v.x() + w * tx + (y * tz - z * ty),
                v.y() + w * ty + (z * tx - x * tz),
                v.z() + w * tz + (x * ty - y * tx));
    }

    /** Spherical interpolation along the shortest arc; falls back to lerp for nearly equal inputs. */
    public Quatd slerp(Quatd target, double alpha) {
        double cosom = dot(target);
        double absCosom = Math.abs(cosom);
        double scale0;
        double scale1;
        if (1.0 - absCosom > 1e-6) {
            double sinSqr = 1.0 - absCosom * absCosom;
            double sinom = 1.0 / Math.sqrt(sinSqr);
            double omega = Math.atan2(sinSqr * sinom, absCosom);
            scale0 = (Math.sin((1.0 - alpha) * omega) * sinom);
            scale1 = (Math.sin(alpha * omega) * sinom);
        } else {
            scale0 = 1.0 - alpha;
            scale1 = alpha;
        }
        scale1 = cosom >= 0.0 ? scale1 : -scale1;
        return new Quatd(
                scale0 * x + scale1 * target.x,
                scale0 * y + scale1 * target.y,
                scale0 * z + scale1 * target.z,
                scale0 * w + scale1 * target.w);
    }

    /** Normalized linear interpolation along the shortest arc. Cheaper than slerp, not constant speed. */
    public Quatd nlerp(Quatd target, double alpha) {
        double s1 = dot(target) >= 0.0 ? alpha : -alpha;
        double s0 = 1.0 - alpha;
        return new Quatd(
                s0 * x + s1 * target.x,
                s0 * y + s1 * target.y,
                s0 * z + s1 * target.z,
                s0 * w + s1 * target.w).normalize();
    }

    /** Rotation angle in radians along the shortest arc, in [0, PI]. Assumes unit length. */
    public double angle() {
        double a = 2.0 * Math.acos(Math.max(-1.0, Math.min(1.0, w)));
        return (a <= Math.PI ? a : 2.0 * Math.PI - a);
    }

    public Mat3d toMat3() {
        return Mat3d.rotation(this);
    }

    public Mat4d toMat4() {
        return Mat4d.rotation(this);
    }

    public boolean approxEquals(Quatd o, double eps) {
        return Math.abs(x - o.x) <= eps && Math.abs(y - o.y) <= eps
                && Math.abs(z - o.z) <= eps && Math.abs(w - o.w) <= eps;
    }

    /** True if both represent the same rotation ({@code q} and {@code -q} are equivalent). */
    public boolean sameRotation(Quatd o, double eps) {
        return approxEquals(o, eps) || approxEquals(o.negateAll(), eps);
    }

    private Quatd negateAll() {
        return new Quatd(-x, -y, -z, -w);
    }

    public Quatf toFloat() {
        return new Quatf((float) x, (float) y, (float) z, (float) w);
    }
}
