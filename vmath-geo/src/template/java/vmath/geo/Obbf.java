package vmath.geo;

import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import vmath.core.Mat3f;
import vmath.core.Quatf;
import vmath.core.Vec3f;

/**
 * Oriented bounding box: centre, half extents and a unit-quaternion orientation (40 bytes).
 *
 * <p>The local axes are the columns of {@code rotation().toMat3()}.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads without
 * synchronization.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Obbf box = Obbf.of(new Vec3f(1f, 0f, 0f), new Vec3f(2f, 1f, 0.5f), Quatf.rotationY(0.4f));
 * Vec3f local = box.toLocal(new Vec3f(1f, 0f, 0f));      // the point in the axes of the box
 * boolean inside = box.contains(box.center());           // true
 * Aabbf bounds = box.aabb();
 * }</pre>
 *
 * @param cx the x coordinate of the center
 * @param cy the y coordinate of the center
 * @param cz the z coordinate of the center
 * @param hx the half extent along the first local axis
 * @param hy the half extent along the second local axis
 * @param hz the half extent along the third local axis
 * @param qx the x component of the orientation quaternion
 * @param qy the y component of the orientation quaternion
 * @param qz the z component of the orientation quaternion
 * @param qw the w component of the orientation quaternion
 */
@GenerateDouble
@ValueType
public record Obbf(float cx, float cy, float cz, float hx, float hy, float hz,
                   float qx, float qy, float qz, float qw) {

    /**
     * Builds an oriented box from its centre, half extents along its own axes and its rotation.
     *
     * @param center the center; must not be {@code null}
     * @param halfExtents the half extents; must not be {@code null}
     * @param rotation the rotation; must not be {@code null}
     * @return the box with the given centre, half edge lengths along its own axes and rotation
     */
    public static Obbf of(Vec3f center, Vec3f halfExtents, Quatf rotation) {
        return new Obbf(center.x(), center.y(), center.z(), halfExtents.x(), halfExtents.y(), halfExtents.z(),
                rotation.x(), rotation.y(), rotation.z(), rotation.w());
    }

    /**
     * Converts an axis-aligned box to an oriented box with identity rotation.
     *
     * @param box the box; must not be {@code null}
     * @return an axis-aligned box as an OBB
     */
    public static Obbf fromAabb(Aabbf box) {
        return of(box.center(), box.halfSize(), Quatf.IDENTITY);
    }

    /**
     * Exposes the centre of the box.
     *
     * @return the centre of the box
     */
    public Vec3f center() {
        return new Vec3f(cx, cy, cz);
    }

    /**
     * Exposes the half edge lengths along the box's own axes.
     *
     * @return half the edge lengths along the box's own axes
     */
    public Vec3f halfExtents() {
        return new Vec3f(hx, hy, hz);
    }

    /**
     * Exposes the rotation from the box's frame to world space.
     *
     * @return the rotation from the box's own frame to world space
     */
    public Quatf rotation() {
        return new Quatf(qx, qy, qz, qw);
    }

    /**
     * Computes the three box axes as the columns of the rotation matrix.
     *
     * @return the three unit axes as columns
     */
    public Mat3f axes() {
        return Mat3f.rotation(rotation());
    }

    /**
     * Computes the axis-aligned box around the oriented box, which is generally larger than the box
     * itself.
     *
     * @return the tight axis-aligned box around this box
     */
    public Aabbf aabb() {
        Mat3f r = axes();
        float ex = Math.abs(r.m00()) * hx + Math.abs(r.m10()) * hy + Math.abs(r.m20()) * hz;
        float ey = Math.abs(r.m01()) * hx + Math.abs(r.m11()) * hy + Math.abs(r.m21()) * hz;
        float ez = Math.abs(r.m02()) * hx + Math.abs(r.m12()) * hy + Math.abs(r.m22()) * hz;
        return new Aabbf(cx - ex, cy - ey, cz - ez, cx + ex, cy + ey, cz + ez);
    }

    /**
     * Transforms a world-space point into the box's own frame, where the box is centred and
     * axis-aligned.
     *
     * @param p the vector; must not be {@code null}
     * @return {@code p} in the box's local frame (centred, axes aligned)
     */
    public Vec3f toLocal(Vec3f p) {
        return rotation().conjugate().transform(p.sub(center()));
    }

    /**
     * Tests whether a point lies inside the box by transforming it to the box frame; the surface
     * counts as inside.
     *
     * @param p the vector; must not be {@code null}
     * @return {@code true} when the point is inside the box or on its surface
     */
    public boolean contains(Vec3f p) {
        Vec3f l = toLocal(p);
        return Math.abs(l.x()) <= hx && Math.abs(l.y()) <= hy && Math.abs(l.z()) <= hz;
    }

    /**
     * Finds the nearest point of the solid box by clamping the point in the box frame.
     *
     * @param p the vector; must not be {@code null}
     * @return the point of the box nearest to {@code p} ({@code p} itself when inside)
     */
    public Vec3f closestPoint(Vec3f p) {
        Vec3f l = toLocal(p);
        Vec3f clamped = new Vec3f(
                Math.min(Math.max(l.x(), -hx), hx),
                Math.min(Math.max(l.y(), -hy), hy),
                Math.min(Math.max(l.z(), -hz), hz));
        return rotation().transform(clamped).add(center());
    }

    /**
     * Measures the squared distance to the box by clamping in the box frame, which avoids the
     * square root.
     *
     * @param p the vector; must not be {@code null}
     * @return the squared distance from {@code p} to the box; 0 when {@code p} is inside
     */
    public float distanceSquared(Vec3f p) {
        return closestPoint(p).distanceSquared(p);
    }

    /**
     * Converts the components to {@code double}, which is exact.
     *
     * @return the same box with double-precision components
     */
    @FloatOnly
    public Obbd toDouble() {
        return new Obbd(cx, cy, cz, hx, hy, hz, qx, qy, qz, qw);
    }

    /**
     * Converts the components to {@code float}, which rounds values that need more precision.
     *
     * @return the same box with float components, each rounded to the nearest float
     */
    @DoubleOnly
    public Obbf toFloat() {
        return new Obbf((float) cx, (float) cy, (float) cz, (float) hx, (float) hy, (float) hz, (float) qx, (float) qy, (float) qz, (float) qw);
    }
}
