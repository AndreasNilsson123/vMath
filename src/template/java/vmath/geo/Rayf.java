package vmath.geo;

import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import vmath.core.Mat4f;
import vmath.core.Vec3f;

/**
 * A ray {@code origin + t * direction}, {@code t >= 0}. The direction need not be unit length; intersection
 * routines return {@code t} in units of the direction, so {@code pointAt(t)} is always the hit point.
 */
@GenerateDouble
@ValueType
public record Rayf(float ox, float oy, float oz, float dx, float dy, float dz) {

    /** The ray from {@code origin} along {@code direction} (not normalized; {@code t} is in units of its length). */
    public static Rayf of(Vec3f origin, Vec3f direction) {
        return new Rayf(origin.x(), origin.y(), origin.z(), direction.x(), direction.y(), direction.z());
    }

    /** The ray from {@code from} through {@code to}: {@code t = 1} lands on {@code to}. */
    public static Rayf through(Vec3f from, Vec3f to) {
        return of(from, to.sub(from));
    }

    /** The ray's start point. */
    public Vec3f origin() {
        return new Vec3f(ox, oy, oz);
    }

    /** The ray's direction, as given (not normalized). */
    public Vec3f direction() {
        return new Vec3f(dx, dy, dz);
    }

    /** The point at parameter {@code t}: {@code origin + t * direction}. */
    public Vec3f pointAt(float t) {
        return new Vec3f(ox + dx * t, oy + dy * t, oz + dz * t);
    }

    /**
     * The point of the ray ({@code t >= 0}) nearest to {@code p}: the projection of {@code p} onto the ray's line, clamped to the origin when it
     * falls behind it. A ray with a zero direction is a point and returns its origin.
     */
    public Vec3f closestPoint(Vec3f p) {
        float len2 = dx * dx + dy * dy + dz * dz;
        if (!(len2 > 0f)) {
            return origin();
        }
        float t = ((p.x() - ox) * dx + (p.y() - oy) * dy + (p.z() - oz) * dz) / len2;
        return pointAt(Math.max(0f, t));
    }

    /** The ray after transforming space by {@code m}. The direction keeps its transformed length. */
    public Rayf transform(Mat4f m) {
        return of(m.transformPosition(origin()), m.transformDirection(direction()));
    }

    /** The same ray with double-precision components. */
    @FloatOnly
    public Rayd toDouble() {
        return new Rayd(ox, oy, oz, dx, dy, dz);
    }

    /** The same ray with float components, each rounded to the nearest float. */
    @DoubleOnly
    public Rayf toFloat() {
        return new Rayf((float) ox, (float) oy, (float) oz, (float) dx, (float) dy, (float) dz);
    }
}
