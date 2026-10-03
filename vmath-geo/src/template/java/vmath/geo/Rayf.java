package vmath.geo;

import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import vmath.core.Mat4f;
import vmath.core.Vec3f;

/**
 * A ray {@code origin + t * direction}, {@code t >= 0}.
 *
 * <p>The direction need not be unit length; intersection routines return {@code t} in units of the
 * direction, so {@code pointAt(t)} is always the hit point.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads without
 * synchronization.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Rayf ray = Rayf.of(new Vec3f(0f, 0f, 10f), new Vec3f(0f, 0f, -1f));
 * Vec3f point = ray.pointAt(4f);                                // (0, 0, 6)
 * float t = Intersectionf.raySphere(ray, Spheref.of(Vec3f.ZERO, 1f), 100f);   // 9, or infinity for a miss
 * }</pre>
 *
 * @param ox the x coordinate of the origin
 * @param oy the y coordinate of the origin
 * @param oz the z coordinate of the origin
 * @param dx the x component of the direction
 * @param dy the y component of the direction
 * @param dz the z component of the direction
 */
@GenerateDouble
@ValueType
public record Rayf(float ox, float oy, float oz, float dx, float dy, float dz) {

    /**
     * Builds a ray from an origin and a direction; the direction is not normalised, so the
     * parameter of intersection routines is in units of its length.
     *
     * @param origin the origin; must not be {@code null}
     * @param direction the direction; must not be {@code null}
     * @return the ray from {@code origin} along {@code direction} (not normalized; {@code t} is in
     *     units of its length)
     */
    public static Rayf of(Vec3f origin, Vec3f direction) {
        return new Rayf(origin.x(), origin.y(), origin.z(), direction.x(), direction.y(), direction.z());
    }

    /**
     * Builds a ray from a start point through a second point, so that a parameter of one lands on
     * the second point.
     *
     * @param from the from; must not be {@code null}
     * @param to the vector; must not be {@code null}
     * @return the ray from {@code from} through {@code to}: {@code t = 1} lands on {@code to}
     */
    public static Rayf through(Vec3f from, Vec3f to) {
        return of(from, to.sub(from));
    }

    /**
     * Exposes the origin of the ray.
     *
     * @return the ray's start point
     */
    public Vec3f origin() {
        return new Vec3f(ox, oy, oz);
    }

    /**
     * Exposes the direction of the ray as it was given, which is not normalised.
     *
     * @return the ray's direction, as given (not normalized)
     */
    public Vec3f direction() {
        return new Vec3f(dx, dy, dz);
    }

    /**
     * Evaluates the point on the ray at a parameter, in units of the direction vector.
     *
     * @param t the ray parameter
     * @return the point at parameter {@code t}: {@code origin + t * direction}
     */
    public Vec3f pointAt(float t) {
        return new Vec3f(ox + dx * t, oy + dy * t, oz + dz * t);
    }

    /**
     * Projects a point onto the ray and clamps the result so that it does not lie behind the
     * origin.
     *
     * <p>A ray with a zero direction is a point and returns its origin.
     *
     * @param p the vector; must not be {@code null}
     * @return the point of the ray ({@code t >= 0}) nearest to {@code p}: the projection of
     *     {@code p} onto the ray's line, clamped to the origin when it falls behind it
     */
    public Vec3f closestPoint(Vec3f p) {
        float len2 = dx * dx + dy * dy + dz * dz;
        if (!(len2 > 0f)) {
            return origin();
        }
        float t = ((p.x() - ox) * dx + (p.y() - oy) * dy + (p.z() - oz) * dz) / len2;
        return pointAt(Math.max(0f, t));
    }

    /**
     * Transforms the origin as a point and the direction as a vector, so the parameter keeps its
     * meaning only if the transform does not scale.
     *
     * <p>The direction keeps its transformed length.
     *
     * @param m the matrix; must not be {@code null}
     * @return the ray after transforming space by {@code m}
     */
    public Rayf transform(Mat4f m) {
        return of(m.transformPosition(origin()), m.transformDirection(direction()));
    }

    /**
     * Converts the components to {@code double}, which is exact.
     *
     * @return the same ray with double-precision components
     */
    @FloatOnly
    public Rayd toDouble() {
        return new Rayd(ox, oy, oz, dx, dy, dz);
    }

    /**
     * Converts the components to {@code float}, which rounds values that need more precision.
     *
     * @return the same ray with float components, each rounded to the nearest float
     */
    @DoubleOnly
    public Rayf toFloat() {
        return new Rayf((float) ox, (float) oy, (float) oz, (float) dx, (float) dy, (float) dz);
    }
}
