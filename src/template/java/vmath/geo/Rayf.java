package vmath.geo;

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

    public static Rayf of(Vec3f origin, Vec3f direction) {
        return new Rayf(origin.x(), origin.y(), origin.z(), direction.x(), direction.y(), direction.z());
    }

    /** The ray from {@code from} through {@code to}: {@code t = 1} lands on {@code to}. */
    public static Rayf through(Vec3f from, Vec3f to) {
        return of(from, to.sub(from));
    }

    public Vec3f origin() {
        return new Vec3f(ox, oy, oz);
    }

    public Vec3f direction() {
        return new Vec3f(dx, dy, dz);
    }

    public Vec3f pointAt(float t) {
        return new Vec3f(ox + dx * t, oy + dy * t, oz + dz * t);
    }

    /** The ray after transforming space by {@code m}. The direction keeps its transformed length. */
    public Rayf transform(Mat4f m) {
        return of(m.transformPosition(origin()), m.transformDirection(direction()));
    }

    @FloatOnly
    public Rayd toDouble() {
        return new Rayd(ox, oy, oz, dx, dy, dz);
    }
}
