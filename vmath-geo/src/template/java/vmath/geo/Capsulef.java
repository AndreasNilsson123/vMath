package vmath.geo;

import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import vmath.core.Mat4f;
import vmath.core.Vec3f;

/**
 * A capsule: every point within {@code radius} of the segment from {@code a} to {@code b}, that is
 * a cylinder with a hemisphere on each end.
 *
 * <p>The usual shape for characters and swept spheres. A capsule whose ends coincide is a sphere.
 *
 * <p>Valhalla: {@code -Pvalhalla} builds turn {@code @ValueType} into a real {@code value record}.
 * Never use {@code ==}, {@code synchronized} or identity-based APIs on it.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads without
 * synchronization.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Capsulef capsule = Capsulef.of(new Vec3f(0f, 0f, 0f), new Vec3f(0f, 1.8f, 0f), 0.3f);
 * boolean hit = capsule.contains(new Vec3f(0.2f, 0.9f, 0f));           // true
 * Vec3f nearest = capsule.closestPoint(new Vec3f(2f, 0.9f, 0f));       // on its surface
 * Aabbf bounds = capsule.aabb();
 * }</pre>
 *
 * @param ax the x coordinate of the first end of the axis
 * @param ay the y coordinate of the first end of the axis
 * @param az the z coordinate of the first end of the axis
 * @param bx the x coordinate of the second end of the axis
 * @param by the y coordinate of the second end of the axis
 * @param bz the z coordinate of the second end of the axis
 * @param radius the radius
 */
@GenerateDouble
@ValueType
public record Capsulef(float ax, float ay, float az, float bx, float by, float bz, float radius) {

    /**
     * Builds a capsule from the end points of its axis and a radius.
     *
     * @param a the first vector; must not be {@code null}
     * @param b the second vector; must not be {@code null}
     * @param radius the radius
     * @return the capsule around the segment from {@code a} to {@code b}
     */
    public static Capsulef of(Vec3f a, Vec3f b, float radius) {
        return new Capsulef(a.x(), a.y(), a.z(), b.x(), b.y(), b.z(), radius);
    }

    /**
     * Builds a capsule from its axis segment and a radius.
     *
     * @param axis the axis; must not be {@code null}
     * @param radius the radius
     * @return the capsule around {@code axis}
     */
    public static Capsulef of(Segmentf axis, float radius) {
        return new Capsulef(axis.ax(), axis.ay(), axis.az(), axis.bx(), axis.by(), axis.bz(), radius);
    }

    /**
     * Exposes the first end point of the axis.
     *
     * @return the centre of the first end sphere
     */
    public Vec3f a() {
        return new Vec3f(ax, ay, az);
    }

    /**
     * Exposes the second end point of the axis.
     *
     * @return the centre of the second end sphere
     */
    public Vec3f b() {
        return new Vec3f(bx, by, bz);
    }

    /**
     * Exposes the axis of the capsule as a segment.
     *
     * @return the segment between the centres of the two end spheres
     */
    public Segmentf segment() {
        return new Segmentf(ax, ay, az, bx, by, bz);
    }

    /**
     * Tests whether a point lies inside the capsule by comparing its distance to the axis with the
     * radius; the surface counts as inside.
     *
     * @param p the vector; must not be {@code null}
     * @return {@code true} when {@code p} is inside or on the capsule
     */
    public boolean contains(Vec3f p) {
        return segment().distanceSquared(p) <= radius * radius;
    }

    /**
     * Finds the nearest point of the solid capsule by first projecting onto the axis segment.
     *
     * @param p the vector; must not be {@code null}
     * @return the point of the solid capsule nearest to {@code p} ({@code p} itself when inside)
     */
    public Vec3f closestPoint(Vec3f p) {
        Vec3f onAxis = segment().closestPoint(p);
        float d2 = onAxis.distanceSquared(p);
        if (d2 <= radius * radius) {
            return p;
        }
        float s = radius / (float) Math.sqrt(d2);
        return new Vec3f(onAxis.x() + (p.x() - onAxis.x()) * s, onAxis.y() + (p.y() - onAxis.y()) * s, onAxis.z() + (p.z() - onAxis.z()) * s);
    }

    /**
     * Computes the axis-aligned box around the capsule.
     *
     * @return the tight axis-aligned box around the capsule
     */
    public Aabbf aabb() {
        return new Aabbf(Math.min(ax, bx) - radius, Math.min(ay, by) - radius, Math.min(az, bz) - radius,
                Math.max(ax, bx) + radius, Math.max(ay, by) + radius, Math.max(az, bz) + radius);
    }

    /**
     * Computes the volume of the capsule from the cylinder and the two hemispheres.
     *
     * @return {@code pi r^2 L + 4/3 pi r^3}: the cylinder of the axis length {@code L} plus the two
     *     hemispheres (one sphere)
     */
    public float volume() {
        float r2 = radius * radius;
        return (float) Math.PI * r2 * segment().length() + 4f / 3f * (float) Math.PI * r2 * radius;
    }

    /**
     * Transforms the capsule by transforming its axis and scaling the radius by the largest axis
     * scale of the matrix; for non-uniform scale the result is only a conservative approximation,
     * since a transformed capsule is not a capsule.
     *
     * @param m the matrix; must not be {@code null}
     * @return the capsule after transforming space by the affine matrix {@code m}: the axis is
     *     transformed exactly and the radius is scaled by the largest axis scale of {@code m}, so
     *     the result contains the transformed capsule (it is exact for uniform scale, conservative
     *     for non-uniform scale)
     */
    public Capsulef transform(Mat4f m) {
        float sx = m.m00() * m.m00() + m.m01() * m.m01() + m.m02() * m.m02();
        float sy = m.m10() * m.m10() + m.m11() * m.m11() + m.m12() * m.m12();
        float sz = m.m20() * m.m20() + m.m21() * m.m21() + m.m22() * m.m22();
        float s = (float) Math.sqrt(Math.max(sx, Math.max(sy, sz)));
        return of(segment().transform(m), radius * s);
    }

    /**
     * Converts the components to {@code double}, which is exact.
     *
     * @return the same capsule with double-precision components
     */
    @FloatOnly
    public Capsuled toDouble() {
        return new Capsuled(ax, ay, az, bx, by, bz, radius);
    }

    /**
     * Converts the components to {@code float}, which rounds values that need more precision.
     *
     * @return the same capsule with float components, each rounded to the nearest float
     */
    @DoubleOnly
    public Capsulef toFloat() {
        return new Capsulef((float) ax, (float) ay, (float) az, (float) bx, (float) by, (float) bz, (float) radius);
    }
}
