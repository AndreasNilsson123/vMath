package vmath.geo;

import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import vmath.core.Mat4f;
import vmath.core.Vec3f;

/**
 * Plane {@code n . p + d = 0}. The signed {@link #distance} is positive on the side the normal points to, and it is
 * a true distance only while {@code n} is unit length (see {@link #normalize()}).
 */
@GenerateDouble
@ValueType
public record Planef(float nx, float ny, float nz, float d) {

    /** The plane through {@code point} with the given normal (normalized here). */
    public static Planef fromPointNormal(Vec3f point, Vec3f normal) {
        Vec3f n = normal.normalize();
        return new Planef(n.x(), n.y(), n.z(), -n.dot(point));
    }

    /** The plane through three points; the normal is {@code (b - a) x (c - a)}, so counter-clockwise faces it. */
    public static Planef fromPoints(Vec3f a, Vec3f b, Vec3f c) {
        return fromPointNormal(a, b.sub(a).cross(c.sub(a)));
    }

    public Vec3f normal() {
        return new Vec3f(nx, ny, nz);
    }

    /** Signed distance of {@code p}: positive in front, negative behind. */
    public float distance(Vec3f p) {
        return nx * p.x() + ny * p.y() + nz * p.z() + d;
    }

    /** Scales so the normal is unit length. A plane with a (near-)zero normal is returned unchanged. */
    public Planef normalize() {
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-12f) {
            return this;
        }
        float inv = 1f / len;
        return new Planef(nx * inv, ny * inv, nz * inv, d * inv);
    }

    /** The same plane facing the other way. */
    public Planef flip() {
        return new Planef(-nx, -ny, -nz, -d);
    }

    public Vec3f closestPoint(Vec3f p) {
        float dist = distance(p);
        return new Vec3f(p.x() - nx * dist, p.y() - ny * dist, p.z() - nz * dist);
    }

    /**
     * The plane after transforming space by {@code m}: {@code (m^-1)^T} applied to the plane vector. The matrix must
     * be invertible; the result is renormalized.
     */
    public Planef transform(Mat4f m) {
        Mat4f it = m.invert().transpose();
        return new Planef(
                it.m00() * nx + it.m10() * ny + it.m20() * nz + it.m30() * d,
                it.m01() * nx + it.m11() * ny + it.m21() * nz + it.m31() * d,
                it.m02() * nx + it.m12() * ny + it.m22() * nz + it.m32() * d,
                it.m03() * nx + it.m13() * ny + it.m23() * nz + it.m33() * d).normalize();
    }

    @FloatOnly
    public Planed toDouble() {
        return new Planed(nx, ny, nz, d);
    }
}
