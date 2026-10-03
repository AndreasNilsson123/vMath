package vmath.geo;

import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import vmath.core.Mat4f;
import vmath.core.Vec3d;
import vmath.core.Vec3f;

/**
 * Bounding sphere: centre and radius, 16 bytes.
 *
 * <p>A negative radius means empty.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads without
 * synchronization.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Spheref a = Spheref.of(new Vec3f(0f, 0f, 0f), 1f);
 * Spheref b = Spheref.of(new Vec3f(1.5f, 0f, 0f), 1f);
 * boolean touching = a.overlaps(b);                             // true
 * Spheref both = a.union(b);                                    // a sphere around the two
 * Aabbf bounds = a.aabb();
 * }</pre>
 *
 * @param cx the x coordinate of the center
 * @param cy the y coordinate of the center
 * @param cz the z coordinate of the center
 * @param radius the radius
 */
@GenerateDouble
@ValueType
public record Spheref(float cx, float cy, float cz, float radius) {

    /**
     * Builds a sphere from a centre and a radius.
     *
     * @param center the center; must not be {@code null}
     * @param radius the radius
     * @return the sphere with the given centre and radius
     */
    public static Spheref of(Vec3f center, float radius) {
        return new Spheref(center.x(), center.y(), center.z(), radius);
    }

    /**
     * Exposes the centre of the sphere.
     *
     * @return the sphere's centre
     */
    public Vec3f center() {
        return new Vec3f(cx, cy, cz);
    }

    /**
     * Tests whether a point lies inside the sphere by comparing squared distances; the surface
     * counts as inside.
     *
     * @param p the vector; must not be {@code null}
     * @return {@code true} when the point is inside the sphere or on its surface
     */
    public boolean contains(Vec3f p) {
        float dx = p.x() - cx, dy = p.y() - cy, dz = p.z() - cz;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    /**
     * Finds the nearest point of the solid sphere by clamping the offset from the centre to the
     * radius.
     *
     * @param p the vector; must not be {@code null}
     * @return the point of the solid sphere nearest to {@code p} ({@code p} itself when inside)
     */
    public Vec3f closestPoint(Vec3f p) {
        float dx = p.x() - cx, dy = p.y() - cy, dz = p.z() - cz;
        float d2 = dx * dx + dy * dy + dz * dz;
        if (d2 <= radius * radius) {
            return p;
        }
        float s = radius / (float) Math.sqrt(d2);
        return new Vec3f(cx + dx * s, cy + dy * s, cz + dz * s);
    }

    /**
     * Tests two spheres by comparing the squared distance of the centres with the squared sum of
     * the radii.
     *
     * @param o the other sphere; must not be {@code null}
     * @return {@code true} when the spheres share at least a point
     */
    public boolean overlaps(Spheref o) {
        float dx = o.cx - cx, dy = o.cy - cy, dz = o.cz - cz;
        float r = radius + o.radius;
        return dx * dx + dy * dy + dz * dz <= r * r;
    }

    /**
     * Tests a sphere against a box by comparing the squared distance to the box with the squared
     * radius.
     *
     * @param box the box; must not be {@code null}
     * @return {@code true} when the sphere and the box share at least a point
     */
    public boolean overlaps(Aabbf box) {
        return box.distanceSquared(center()) <= radius * radius;
    }

    /**
     * Computes the smallest sphere that contains two spheres, which is how bounding spheres of a
     * group are merged.
     *
     * @param o the other sphere; must not be {@code null}
     * @return the smallest sphere containing both
     */
    public Spheref union(Spheref o) {
        float dx = o.cx - cx, dy = o.cy - cy, dz = o.cz - cz;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist + o.radius <= radius) {
            return this;
        }
        if (dist + radius <= o.radius) {
            return o;
        }
        float r = (dist + radius + o.radius) * 0.5f;
        float k = (r - radius) / dist;
        return new Spheref(cx + dx * k, cy + dy * k, cz + dz * k, r);
    }

    /**
     * Transforms the sphere by transforming the centre and scaling the radius by the largest axis
     * scale of the matrix, which stays conservative under non-uniform scale but is then larger than
     * necessary.
     *
     * @param m the matrix; must not be {@code null}
     * @return bounding sphere after an affine transform: the centre is transformed and the radius
     *     scaled by the largest axis scale, so it stays conservative under non-uniform scaling
     */
    public Spheref transform(Mat4f m) {
        Vec3f c = m.transformPosition(center());
        float sx = m.m00() * m.m00() + m.m01() * m.m01() + m.m02() * m.m02();
        float sy = m.m10() * m.m10() + m.m11() * m.m11() + m.m12() * m.m12();
        float sz = m.m20() * m.m20() + m.m21() * m.m21() + m.m22() * m.m22();
        float s = (float) Math.sqrt(Math.max(sx, Math.max(sy, sz)));
        return new Spheref(c.x(), c.y(), c.z(), radius * s);
    }

    /**
     * Computes the axis-aligned box around the sphere.
     *
     * @return the smallest axis-aligned box that contains the sphere
     */
    public Aabbf aabb() {
        return new Aabbf(cx - radius, cy - radius, cz - radius, cx + radius, cy + radius, cz + radius);
    }

    /**
     * Converts the components to {@code double}, which is exact.
     *
     * @return the same sphere with double-precision components
     */
    @FloatOnly
    public Sphered toDouble() {
        return new Sphered(cx, cy, cz, radius);
    }

    /**
     * Converts the components to {@code float}, which rounds values that need more precision.
     *
     * @return the same sphere with float components, each rounded to the nearest float
     */
    @DoubleOnly
    public Spheref toFloat() {
        return new Spheref((float) cx, (float) cy, (float) cz, (float) radius);
    }

    /**
     * Subtracts a double-precision origin before narrowing to {@code float}, which keeps precision
     * far from the origin (camera-relative rendering).
     *
     * @param origin the origin; must not be {@code null}
     * @return sphere relative to {@code origin}, subtracted in double and then narrowed
     */
    @DoubleOnly
    public Spheref relativeTo(Vec3d origin) {
        return new Spheref((float) (cx - origin.x()), (float) (cy - origin.y()), (float) (cz - origin.z()), (float) radius);
    }
}
