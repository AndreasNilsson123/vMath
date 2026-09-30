package vmath.geo;

import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import vmath.core.Mat4f;
import vmath.core.Vec3f;

/**
 * A capsule: every point within {@code radius} of the segment from {@code a} to {@code b}, that is a cylinder with a hemisphere on each end. The usual
 * shape for characters and swept spheres. A capsule whose ends coincide is a sphere.
 *
 * <p>Valhalla: {@code -Pvalhalla} builds turn {@code @ValueType} into a real {@code value record}. Never use {@code ==}, {@code synchronized} or
 * identity-based APIs on it.
 */
@GenerateDouble
@ValueType
public record Capsulef(float ax, float ay, float az, float bx, float by, float bz, float radius) {

    public static Capsulef of(Vec3f a, Vec3f b, float radius) {
        return new Capsulef(a.x(), a.y(), a.z(), b.x(), b.y(), b.z(), radius);
    }

    public static Capsulef of(Segmentf axis, float radius) {
        return new Capsulef(axis.ax(), axis.ay(), axis.az(), axis.bx(), axis.by(), axis.bz(), radius);
    }

    public Vec3f a() {
        return new Vec3f(ax, ay, az);
    }

    public Vec3f b() {
        return new Vec3f(bx, by, bz);
    }

    /** The segment between the centres of the two end spheres. */
    public Segmentf segment() {
        return new Segmentf(ax, ay, az, bx, by, bz);
    }

    /** True when {@code p} is inside or on the capsule. */
    public boolean contains(Vec3f p) {
        return segment().distanceSquared(p) <= radius * radius;
    }

    /** The point of the solid capsule nearest to {@code p} ({@code p} itself when inside). */
    public Vec3f closestPoint(Vec3f p) {
        Vec3f onAxis = segment().closestPoint(p);
        float d2 = onAxis.distanceSquared(p);
        if (d2 <= radius * radius) {
            return p;
        }
        float s = radius / (float) Math.sqrt(d2);
        return new Vec3f(onAxis.x() + (p.x() - onAxis.x()) * s, onAxis.y() + (p.y() - onAxis.y()) * s, onAxis.z() + (p.z() - onAxis.z()) * s);
    }

    /** The tight axis-aligned box around the capsule. */
    public Aabbf aabb() {
        return new Aabbf(Math.min(ax, bx) - radius, Math.min(ay, by) - radius, Math.min(az, bz) - radius,
                Math.max(ax, bx) + radius, Math.max(ay, by) + radius, Math.max(az, bz) + radius);
    }

    /** {@code pi r^2 L + 4/3 pi r^3}: the cylinder of the axis length {@code L} plus the two hemispheres (one sphere). */
    public float volume() {
        float r2 = radius * radius;
        return (float) Math.PI * r2 * segment().length() + 4f / 3f * (float) Math.PI * r2 * radius;
    }

    /**
     * The capsule after transforming space by the affine matrix {@code m}: the axis is transformed exactly and the radius is scaled by the largest axis
     * scale of {@code m}, so the result contains the transformed capsule (it is exact for uniform scale, conservative for non-uniform scale).
     */
    public Capsulef transform(Mat4f m) {
        float sx = m.m00() * m.m00() + m.m01() * m.m01() + m.m02() * m.m02();
        float sy = m.m10() * m.m10() + m.m11() * m.m11() + m.m12() * m.m12();
        float sz = m.m20() * m.m20() + m.m21() * m.m21() + m.m22() * m.m22();
        float s = (float) Math.sqrt(Math.max(sx, Math.max(sy, sz)));
        return of(segment().transform(m), radius * s);
    }

    @FloatOnly
    public Capsuled toDouble() {
        return new Capsuled(ax, ay, az, bx, by, bz, radius);
    }

    @DoubleOnly
    public Capsulef toFloat() {
        return new Capsulef((float) ax, (float) ay, (float) az, (float) bx, (float) by, (float) bz, (float) radius);
    }
}
