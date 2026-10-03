package vmath.geo;

import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import vmath.core.Mat4f;
import vmath.core.Vec3f;

/**
 * A line segment from {@code a} to {@code b}.
 *
 * <p>Parameter {@code t} runs from 0 at {@code a} to 1 at {@code b}. A segment whose ends coincide
 * is a point and every query treats it as one.
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
 * Segmentf s = Segmentf.of(new Vec3f(0f, 0f, 0f), new Vec3f(4f, 0f, 0f));
 * Vec3f midpoint = s.midpoint();                                // (2, 0, 0)
 * Vec3f nearest = s.closestPoint(new Vec3f(9f, 1f, 0f));        // (4, 0, 0): clamped to the end
 * float distanceSquared = s.distanceSquared(new Vec3f(2f, 3f, 0f));   // 9
 * }</pre>
 *
 * @param ax the x coordinate of the start
 * @param ay the y coordinate of the start
 * @param az the z coordinate of the start
 * @param bx the x coordinate of the end
 * @param by the y coordinate of the end
 * @param bz the z coordinate of the end
 */
@GenerateDouble
@ValueType
public record Segmentf(float ax, float ay, float az, float bx, float by, float bz) {

    /**
     * Builds a segment from its two end points.
     *
     * @param a the first vector; must not be {@code null}
     * @param b the second vector; must not be {@code null}
     * @return the segment from {@code a} to {@code b}
     */
    public static Segmentf of(Vec3f a, Vec3f b) {
        return new Segmentf(a.x(), a.y(), a.z(), b.x(), b.y(), b.z());
    }

    /**
     * Exposes the start point, which is parameter zero.
     *
     * @return the start point (parameter 0)
     */
    public Vec3f a() {
        return new Vec3f(ax, ay, az);
    }

    /**
     * Exposes the end point, which is parameter one.
     *
     * @return the end point (parameter 1)
     */
    public Vec3f b() {
        return new Vec3f(bx, by, bz);
    }

    /**
     * Computes the vector from the start to the end point.
     *
     * @return {@code b - a}
     */
    public Vec3f direction() {
        return new Vec3f(bx - ax, by - ay, bz - az);
    }

    /**
     * Sums the squares of the direction components; avoids the square root, so prefer it for
     * comparisons.
     *
     * @return the squared length; cheaper than {@link #length()}
     */
    public float lengthSquared() {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Computes the length of the segment with a square root.
     *
     * @return the length
     */
    public float length() {
        return (float) Math.sqrt(lengthSquared());
    }

    /**
     * Computes the point halfway between the end points.
     *
     * @return the point halfway between the ends
     */
    public Vec3f midpoint() {
        return new Vec3f((ax + bx) * 0.5f, (ay + by) * 0.5f, (az + bz) * 0.5f);
    }

    /**
     * Evaluates the point at a parameter; values outside zero to one extrapolate along the line.
     *
     * @param t the segment parameter, 0 at the start and 1 at the end
     * @return the point {@code a + t (b - a)}; {@code t} outside [0, 1] extrapolates along the line
     */
    public Vec3f pointAt(float t) {
        return new Vec3f(ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t);
    }

    /**
     * Projects a point onto the segment's line and clamps the parameter to the segment; a
     * degenerate segment gives zero.
     *
     * @param p the vector; must not be {@code null}
     * @return the parameter in [0, 1] of the point of the segment nearest to {@code p}; 0 for a
     *     segment that is a point
     */
    public float closestParameter(Vec3f p) {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float len2 = dx * dx + dy * dy + dz * dz;
        if (!(len2 > 0f)) {
            return 0f;
        }
        float t = ((p.x() - ax) * dx + (p.y() - ay) * dy + (p.z() - az) * dz) / len2;
        return Math.min(Math.max(t, 0f), 1f);
    }

    /**
     * Finds the nearest point of the segment by clamped projection.
     *
     * @param p the vector; must not be {@code null}
     * @return the point of the segment nearest to {@code p}
     */
    public Vec3f closestPoint(Vec3f p) {
        return pointAt(closestParameter(p));
    }

    /**
     * Measures the squared distance from a point to the segment, which avoids the square root.
     *
     * @param p the vector; must not be {@code null}
     * @return squared distance from {@code p} to the segment
     */
    public float distanceSquared(Vec3f p) {
        return closestPoint(p).distanceSquared(p);
    }

    /**
     * Computes the axis-aligned box around the segment.
     *
     * @return the tight axis-aligned box around the segment
     */
    public Aabbf aabb() {
        return new Aabbf(Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz), Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz));
    }

    /**
     * Transforms both end points, which is exact for any affine matrix because affine maps send
     * segments to segments.
     *
     * @param m the matrix; must not be {@code null}
     * @return the segment after transforming space by the affine matrix {@code m}: exact, because
     *     an affine map sends segments to segments
     */
    public Segmentf transform(Mat4f m) {
        return of(m.transformPosition(a()), m.transformPosition(b()));
    }

    /**
     * Converts the components to {@code double}, which is exact.
     *
     * @return the same segment with double-precision components
     */
    @FloatOnly
    public Segmentd toDouble() {
        return new Segmentd(ax, ay, az, bx, by, bz);
    }

    /**
     * Converts the components to {@code float}, which rounds values that need more precision.
     *
     * @return the same segment with float components, each rounded to the nearest float
     */
    @DoubleOnly
    public Segmentf toFloat() {
        return new Segmentf((float) ax, (float) ay, (float) az, (float) bx, (float) by, (float) bz);
    }
}
