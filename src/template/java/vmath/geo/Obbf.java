package vmath.geo;

import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import vmath.core.Mat3f;
import vmath.core.Quatf;
import vmath.core.Vec3f;

/**
 * Oriented bounding box: centre, half extents and a unit-quaternion orientation (40 bytes). The local axes are the
 * columns of {@code rotation().toMat3()}.
 */
@GenerateDouble
@ValueType
public record Obbf(float cx, float cy, float cz, float hx, float hy, float hz,
                   float qx, float qy, float qz, float qw) {

    public static Obbf of(Vec3f center, Vec3f halfExtents, Quatf rotation) {
        return new Obbf(center.x(), center.y(), center.z(), halfExtents.x(), halfExtents.y(), halfExtents.z(),
                rotation.x(), rotation.y(), rotation.z(), rotation.w());
    }

    /** An axis-aligned box as an OBB. */
    public static Obbf fromAabb(Aabbf box) {
        return of(box.center(), box.halfSize(), Quatf.IDENTITY);
    }

    public Vec3f center() {
        return new Vec3f(cx, cy, cz);
    }

    public Vec3f halfExtents() {
        return new Vec3f(hx, hy, hz);
    }

    public Quatf rotation() {
        return new Quatf(qx, qy, qz, qw);
    }

    /** The three unit axes as columns. */
    public Mat3f axes() {
        return Mat3f.rotation(rotation());
    }

    /** The tight axis-aligned box around this box. */
    public Aabbf aabb() {
        Mat3f r = axes();
        float ex = Math.abs(r.m00()) * hx + Math.abs(r.m10()) * hy + Math.abs(r.m20()) * hz;
        float ey = Math.abs(r.m01()) * hx + Math.abs(r.m11()) * hy + Math.abs(r.m21()) * hz;
        float ez = Math.abs(r.m02()) * hx + Math.abs(r.m12()) * hy + Math.abs(r.m22()) * hz;
        return new Aabbf(cx - ex, cy - ey, cz - ez, cx + ex, cy + ey, cz + ez);
    }

    /** {@code p} in the box's local frame (centred, axes aligned). */
    public Vec3f toLocal(Vec3f p) {
        return rotation().conjugate().transform(p.sub(center()));
    }

    public boolean contains(Vec3f p) {
        Vec3f l = toLocal(p);
        return Math.abs(l.x()) <= hx && Math.abs(l.y()) <= hy && Math.abs(l.z()) <= hz;
    }

    /** The point of the box nearest to {@code p} ({@code p} itself when inside). */
    public Vec3f closestPoint(Vec3f p) {
        Vec3f l = toLocal(p);
        Vec3f clamped = new Vec3f(
                Math.min(Math.max(l.x(), -hx), hx),
                Math.min(Math.max(l.y(), -hy), hy),
                Math.min(Math.max(l.z(), -hz), hz));
        return rotation().transform(clamped).add(center());
    }

    public float distanceSquared(Vec3f p) {
        return closestPoint(p).distanceSquared(p);
    }

    @FloatOnly
    public Obbd toDouble() {
        return new Obbd(cx, cy, cz, hx, hy, hz, qx, qy, qz, qw);
    }

    @DoubleOnly
    public Obbf toFloat() {
        return new Obbf((float) cx, (float) cy, (float) cz, (float) hx, (float) hy, (float) hz, (float) qx, (float) qy, (float) qz, (float) qw);
    }
}
