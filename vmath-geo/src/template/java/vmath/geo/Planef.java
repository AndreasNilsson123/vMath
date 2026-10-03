package vmath.geo;

import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import vmath.core.Mat4f;
import vmath.core.Vec3f;

/**
 * Plane {@code n . p + d = 0}.
 *
 * <p>The signed {@link #distance} is positive on the side the normal points to, and it is a true
 * distance only while {@code n} is unit length (see {@link #normalize()}).
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads without
 * synchronization.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Planef ground = Planef.fromPointNormal(Vec3f.ZERO, Vec3f.UNIT_Y);
 * float height = ground.distance(new Vec3f(3f, 2f, 1f));        // 2, positive on the side of the normal
 * Vec3f foot = ground.closestPoint(new Vec3f(3f, 2f, 1f));      // (3, 0, 1)
 * Planef above = Planef.fromPoints(new Vec3f(0f, 1f, 0f), new Vec3f(0f, 1f, 1f), new Vec3f(1f, 1f, 0f));
 * }</pre>
 *
 * @param nx the x component of the normal
 * @param ny the y component of the normal
 * @param nz the z component of the normal
 * @param d the constant term of the plane equation {@code n . p + d = 0}
 */
@GenerateDouble
@ValueType
public record Planef(float nx, float ny, float nz, float d) {

    /**
     * Builds a plane from a point on it and a normal, normalising the normal.
     *
     * @param point the point; must not be {@code null}
     * @param normal the normal; must not be {@code null}
     * @return the plane through {@code point} with the given normal (normalized here)
     */
    public static Planef fromPointNormal(Vec3f point, Vec3f normal) {
        Vec3f n = normal.normalize();
        return new Planef(n.x(), n.y(), n.z(), -n.dot(point));
    }

    /**
     * Builds a plane from three points; the winding decides which side the normal faces, and
     * collinear points give a degenerate plane.
     *
     * @param a the first vector; must not be {@code null}
     * @param b the second vector; must not be {@code null}
     * @param c the vector; must not be {@code null}
     * @return the plane through three points; the normal is {@code (b - a) x (c - a)}, so
     *     counter-clockwise faces it
     */
    public static Planef fromPoints(Vec3f a, Vec3f b, Vec3f c) {
        return fromPointNormal(a, b.sub(a).cross(c.sub(a)));
    }

    /**
     * Exposes the unit normal of the plane.
     *
     * @return the plane's unit normal
     */
    public Vec3f normal() {
        return new Vec3f(nx, ny, nz);
    }

    /**
     * Evaluates the signed distance from a point to the plane, positive on the side the normal
     * faces; the normal is a unit vector, so the value is a true distance.
     *
     * @param p the vector; must not be {@code null}
     * @return signed distance of {@code p}: positive in front, negative behind
     */
    public float distance(Vec3f p) {
        return nx * p.x() + ny * p.y() + nz * p.z() + d;
    }

    /**
     * Scales so the normal is unit length.
     *
     * <p>A plane with a (near-)zero normal is returned unchanged.
     *
     * @return the plane with a unit normal, never {@code null}
     */
    public Planef normalize() {
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-12f) {
            return this;
        }
        float inv = 1f / len;
        return new Planef(nx * inv, ny * inv, nz * inv, d * inv);
    }

    /**
     * Reverses the orientation of the plane, which swaps its front and back sides.
     *
     * @return the same plane facing the other way
     */
    public Planef flip() {
        return new Planef(-nx, -ny, -nz, -d);
    }

    /**
     * Projects a point onto the plane.
     *
     * @param p the vector; must not be {@code null}
     * @return the point of the plane nearest to {@code p}
     */
    public Vec3f closestPoint(Vec3f p) {
        float dist = distance(p);
        return new Vec3f(p.x() - nx * dist, p.y() - ny * dist, p.z() - nz * dist);
    }

    /**
     * Transforms the plane with the inverse transpose of the matrix, which is the correct way to
     * transform a plane under non-uniform scale.
     *
     * <p>The matrix must be invertible; the result is renormalized.
     *
     * @param m the matrix; must not be {@code null}
     * @return the plane after transforming space by {@code m}: {@code (m^-1)^T} applied to the
     *     plane vector
     */
    public Planef transform(Mat4f m) {
        Mat4f it = m.invert().transpose();
        return new Planef(
                it.m00() * nx + it.m10() * ny + it.m20() * nz + it.m30() * d,
                it.m01() * nx + it.m11() * ny + it.m21() * nz + it.m31() * d,
                it.m02() * nx + it.m12() * ny + it.m22() * nz + it.m32() * d,
                it.m03() * nx + it.m13() * ny + it.m23() * nz + it.m33() * d).normalize();
    }

    /**
     * Converts the components to {@code double}, which is exact.
     *
     * @return the same plane with double-precision components
     */
    @FloatOnly
    public Planed toDouble() {
        return new Planed(nx, ny, nz, d);
    }

    /**
     * Converts the components to {@code float}, which rounds values that need more precision.
     *
     * @return the same plane with float components, each rounded to the nearest float
     */
    @DoubleOnly
    public Planef toFloat() {
        return new Planef((float) nx, (float) ny, (float) nz, (float) d);
    }
}
