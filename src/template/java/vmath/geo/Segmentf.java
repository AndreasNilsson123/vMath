package vmath.geo;

import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import vmath.core.Mat4f;
import vmath.core.Vec3f;

/**
 * A line segment from {@code a} to {@code b}. Parameter {@code t} runs from 0 at {@code a} to 1 at {@code b}. A segment whose ends coincide is a point
 * and every query treats it as one.
 *
 * <p>Valhalla: {@code -Pvalhalla} builds turn {@code @ValueType} into a real {@code value record}. Never use {@code ==}, {@code synchronized} or
 * identity-based APIs on it.
 */
@GenerateDouble
@ValueType
public record Segmentf(float ax, float ay, float az, float bx, float by, float bz) {

    public static Segmentf of(Vec3f a, Vec3f b) {
        return new Segmentf(a.x(), a.y(), a.z(), b.x(), b.y(), b.z());
    }

    public Vec3f a() {
        return new Vec3f(ax, ay, az);
    }

    public Vec3f b() {
        return new Vec3f(bx, by, bz);
    }

    /** {@code b - a}. */
    public Vec3f direction() {
        return new Vec3f(bx - ax, by - ay, bz - az);
    }

    public float lengthSquared() {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        return dx * dx + dy * dy + dz * dz;
    }

    public float length() {
        return (float) Math.sqrt(lengthSquared());
    }

    public Vec3f midpoint() {
        return new Vec3f((ax + bx) * 0.5f, (ay + by) * 0.5f, (az + bz) * 0.5f);
    }

    /** The point {@code a + t (b - a)}; {@code t} outside [0, 1] extrapolates along the line. */
    public Vec3f pointAt(float t) {
        return new Vec3f(ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t);
    }

    /** The parameter in [0, 1] of the point of the segment nearest to {@code p}; 0 for a segment that is a point. */
    public float closestParameter(Vec3f p) {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float len2 = dx * dx + dy * dy + dz * dz;
        if (!(len2 > 0f)) {
            return 0f;
        }
        float t = ((p.x() - ax) * dx + (p.y() - ay) * dy + (p.z() - az) * dz) / len2;
        return Math.min(Math.max(t, 0f), 1f);
    }

    /** The point of the segment nearest to {@code p}. */
    public Vec3f closestPoint(Vec3f p) {
        return pointAt(closestParameter(p));
    }

    /** Squared distance from {@code p} to the segment. */
    public float distanceSquared(Vec3f p) {
        return closestPoint(p).distanceSquared(p);
    }

    /** The tight axis-aligned box around the segment. */
    public Aabbf aabb() {
        return new Aabbf(Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz), Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz));
    }

    /** The segment after transforming space by the affine matrix {@code m}: exact, because an affine map sends segments to segments. */
    public Segmentf transform(Mat4f m) {
        return of(m.transformPosition(a()), m.transformPosition(b()));
    }

    @FloatOnly
    public Segmentd toDouble() {
        return new Segmentd(ax, ay, az, bx, by, bz);
    }

    @DoubleOnly
    public Segmentf toFloat() {
        return new Segmentf((float) ax, (float) ay, (float) az, (float) bx, (float) by, (float) bz);
    }
}
