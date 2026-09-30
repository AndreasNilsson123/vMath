package vmath.geo;

import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import vmath.core.Mat4f;
import vmath.core.Vec3d;
import vmath.core.Vec3f;

/** Bounding sphere: centre and radius, 16 bytes. A negative radius means empty. */
@GenerateDouble
@ValueType
public record Spheref(float cx, float cy, float cz, float radius) {

    public static Spheref of(Vec3f center, float radius) {
        return new Spheref(center.x(), center.y(), center.z(), radius);
    }

    public Vec3f center() {
        return new Vec3f(cx, cy, cz);
    }

    public boolean contains(Vec3f p) {
        float dx = p.x() - cx, dy = p.y() - cy, dz = p.z() - cz;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    /** The point of the solid sphere nearest to {@code p} ({@code p} itself when inside). */
    public Vec3f closestPoint(Vec3f p) {
        float dx = p.x() - cx, dy = p.y() - cy, dz = p.z() - cz;
        float d2 = dx * dx + dy * dy + dz * dz;
        if (d2 <= radius * radius) {
            return p;
        }
        float s = radius / (float) Math.sqrt(d2);
        return new Vec3f(cx + dx * s, cy + dy * s, cz + dz * s);
    }

    /** True when the spheres share at least a point. */
    public boolean overlaps(Spheref o) {
        float dx = o.cx - cx, dy = o.cy - cy, dz = o.cz - cz;
        float r = radius + o.radius;
        return dx * dx + dy * dy + dz * dz <= r * r;
    }

    public boolean overlaps(Aabbf box) {
        return box.distanceSquared(center()) <= radius * radius;
    }

    /** The smallest sphere containing both. */
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
     * Bounding sphere after an affine transform: the centre is transformed and the radius scaled by the largest
     * axis scale, so it stays conservative under non-uniform scaling.
     */
    public Spheref transform(Mat4f m) {
        Vec3f c = m.transformPosition(center());
        float sx = m.m00() * m.m00() + m.m01() * m.m01() + m.m02() * m.m02();
        float sy = m.m10() * m.m10() + m.m11() * m.m11() + m.m12() * m.m12();
        float sz = m.m20() * m.m20() + m.m21() * m.m21() + m.m22() * m.m22();
        float s = (float) Math.sqrt(Math.max(sx, Math.max(sy, sz)));
        return new Spheref(c.x(), c.y(), c.z(), radius * s);
    }

    public Aabbf aabb() {
        return new Aabbf(cx - radius, cy - radius, cz - radius, cx + radius, cy + radius, cz + radius);
    }

    @FloatOnly
    public Sphered toDouble() {
        return new Sphered(cx, cy, cz, radius);
    }

    @DoubleOnly
    public Spheref toFloat() {
        return new Spheref((float) cx, (float) cy, (float) cz, (float) radius);
    }

    /** Sphere relative to {@code origin}, subtracted in double and then narrowed. */
    @DoubleOnly
    public Spheref relativeTo(Vec3d origin) {
        return new Spheref((float) (cx - origin.x()), (float) (cy - origin.y()), (float) (cz - origin.z()), (float) radius);
    }
}
