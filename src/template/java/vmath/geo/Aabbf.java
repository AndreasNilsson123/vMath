package vmath.geo;

import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import vmath.core.Mat4f;
import vmath.core.Vec3d;
import vmath.core.Vec3f;

/**
 * Axis-aligned bounding box. Six primitives, so it stays a flat 24-byte value; bulk data belongs in
 * {@code float[]} storage, not in {@code Aabbf[]}.
 *
 * <p>An empty box has {@code min > max} on some axis ({@link #EMPTY} is the identity for {@link #union}). Operations
 * that need a real box document what they do with an empty one.
 */
@GenerateDouble
@ValueType
public record Aabbf(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {

    /** Contains nothing; {@code EMPTY.union(x) == x}. */
    public static final Aabbf EMPTY = new Aabbf(
            Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);

    /** The box spanned by two arbitrary corners. */
    public static Aabbf of(Vec3f a, Vec3f b) {
        return new Aabbf(
                Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()),
                Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
    }

    public static Aabbf fromCenterHalfExtent(Vec3f center, Vec3f half) {
        return new Aabbf(
                center.x() - half.x(), center.y() - half.y(), center.z() - half.z(),
                center.x() + half.x(), center.y() + half.y(), center.z() + half.z());
    }

    /** Bounds of {@code vertexCount} packed {@code x, y, z} positions starting at {@code offset}. */
    public static Aabbf fromPoints(float[] xyz, int offset, int vertexCount) {
        float x0 = Float.POSITIVE_INFINITY, y0 = x0, z0 = x0;
        float x1 = Float.NEGATIVE_INFINITY, y1 = x1, z1 = x1;
        for (int i = 0, p = offset; i < vertexCount; i++, p += 3) {
            float x = xyz[p], y = xyz[p + 1], z = xyz[p + 2];
            x0 = Math.min(x0, x);
            y0 = Math.min(y0, y);
            z0 = Math.min(z0, z);
            x1 = Math.max(x1, x);
            y1 = Math.max(y1, y);
            z1 = Math.max(z1, z);
        }
        return new Aabbf(x0, y0, z0, x1, y1, z1);
    }

    public Vec3f min() {
        return new Vec3f(minX, minY, minZ);
    }

    public Vec3f max() {
        return new Vec3f(maxX, maxY, maxZ);
    }

    public Vec3f center() {
        return new Vec3f((minX + maxX) * 0.5f, (minY + maxY) * 0.5f, (minZ + maxZ) * 0.5f);
    }

    /** Full edge lengths. */
    public Vec3f size() {
        return new Vec3f(maxX - minX, maxY - minY, maxZ - minZ);
    }

    public Vec3f halfSize() {
        return new Vec3f((maxX - minX) * 0.5f, (maxY - minY) * 0.5f, (maxZ - minZ) * 0.5f);
    }

    public boolean isEmpty() {
        return minX > maxX || minY > maxY || minZ > maxZ;
    }

    /** Corner {@code i}: bit 0 selects max x, bit 1 max y, bit 2 max z. */
    public Vec3f corner(int i) {
        return new Vec3f((i & 1) == 0 ? minX : maxX, (i & 2) == 0 ? minY : maxY, (i & 4) == 0 ? minZ : maxZ);
    }

    public Aabbf union(Aabbf o) {
        return new Aabbf(
                Math.min(minX, o.minX), Math.min(minY, o.minY), Math.min(minZ, o.minZ),
                Math.max(maxX, o.maxX), Math.max(maxY, o.maxY), Math.max(maxZ, o.maxZ));
    }

    public Aabbf union(Vec3f p) {
        return new Aabbf(
                Math.min(minX, p.x()), Math.min(minY, p.y()), Math.min(minZ, p.z()),
                Math.max(maxX, p.x()), Math.max(maxY, p.y()), Math.max(maxZ, p.z()));
    }

    /** The overlap of two boxes; empty when they are disjoint. */
    public Aabbf intersection(Aabbf o) {
        return new Aabbf(
                Math.max(minX, o.minX), Math.max(minY, o.minY), Math.max(minZ, o.minZ),
                Math.min(maxX, o.maxX), Math.min(maxY, o.maxY), Math.min(maxZ, o.maxZ));
    }

    /** Grows every face outward by {@code margin} (shrinks for a negative margin). */
    public Aabbf inflate(float margin) {
        return new Aabbf(minX - margin, minY - margin, minZ - margin, maxX + margin, maxY + margin, maxZ + margin);
    }

    /** True when the boxes share at least a point (touching counts). */
    public boolean overlaps(Aabbf o) {
        return minX <= o.maxX && maxX >= o.minX
                && minY <= o.maxY && maxY >= o.minY
                && minZ <= o.maxZ && maxZ >= o.minZ;
    }

    public boolean contains(Vec3f p) {
        return p.x() >= minX && p.x() <= maxX && p.y() >= minY && p.y() <= maxY && p.z() >= minZ && p.z() <= maxZ;
    }

    /** True when {@code o} lies entirely inside this box. */
    public boolean contains(Aabbf o) {
        return o.minX >= minX && o.maxX <= maxX && o.minY >= minY && o.maxY <= maxY && o.minZ >= minZ && o.maxZ <= maxZ;
    }

    /** Zero for an empty box. */
    public float volume() {
        return isEmpty() ? 0f : (maxX - minX) * (maxY - minY) * (maxZ - minZ);
    }

    /** Zero for an empty box. This is the quantity the surface-area heuristic of BVH builders uses. */
    public float surfaceArea() {
        if (isEmpty()) {
            return 0f;
        }
        float dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
        return 2f * (dx * dy + dy * dz + dz * dx);
    }

    /** The point of the box nearest to {@code p} ({@code p} itself when inside). */
    public Vec3f closestPoint(Vec3f p) {
        return new Vec3f(
                Math.min(Math.max(p.x(), minX), maxX),
                Math.min(Math.max(p.y(), minY), maxY),
                Math.min(Math.max(p.z(), minZ), maxZ));
    }

    /** Squared distance from {@code p} to the box; zero inside. */
    public float distanceSquared(Vec3f p) {
        float dx = Math.max(Math.max(minX - p.x(), 0f), p.x() - maxX);
        float dy = Math.max(Math.max(minY - p.y(), 0f), p.y() - maxY);
        float dz = Math.max(Math.max(minZ - p.z(), 0f), p.z() - maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * The tightest axis-aligned box around this box after an affine transform (Arvo's method: exact for any
     * affine matrix, no need to transform eight corners). The projection row of {@code m} is ignored.
     */
    public Aabbf transform(Mat4f m) {
        float cx = (minX + maxX) * 0.5f, cy = (minY + maxY) * 0.5f, cz = (minZ + maxZ) * 0.5f;
        float hx = (maxX - minX) * 0.5f, hy = (maxY - minY) * 0.5f, hz = (maxZ - minZ) * 0.5f;
        float ncx = m.m00() * cx + m.m10() * cy + m.m20() * cz + m.m30();
        float ncy = m.m01() * cx + m.m11() * cy + m.m21() * cz + m.m31();
        float ncz = m.m02() * cx + m.m12() * cy + m.m22() * cz + m.m32();
        float nhx = Math.abs(m.m00()) * hx + Math.abs(m.m10()) * hy + Math.abs(m.m20()) * hz;
        float nhy = Math.abs(m.m01()) * hx + Math.abs(m.m11()) * hy + Math.abs(m.m21()) * hz;
        float nhz = Math.abs(m.m02()) * hx + Math.abs(m.m12()) * hy + Math.abs(m.m22()) * hz;
        return new Aabbf(ncx - nhx, ncy - nhy, ncz - nhz, ncx + nhx, ncy + nhy, ncz + nhz);
    }

    /** The smallest sphere centred on the box centre that contains it. */
    public Spheref boundingSphere() {
        float cx = (minX + maxX) * 0.5f, cy = (minY + maxY) * 0.5f, cz = (minZ + maxZ) * 0.5f;
        float hx = maxX - cx, hy = maxY - cy, hz = maxZ - cz;
        return new Spheref(cx, cy, cz, (float) Math.sqrt(hx * hx + hy * hy + hz * hz));
    }

    public boolean approxEquals(Aabbf o, float eps) {
        return Math.abs(minX - o.minX) <= eps && Math.abs(minY - o.minY) <= eps && Math.abs(minZ - o.minZ) <= eps
                && Math.abs(maxX - o.maxX) <= eps && Math.abs(maxY - o.maxY) <= eps && Math.abs(maxZ - o.maxZ) <= eps;
    }

    @FloatOnly
    public Aabbd toDouble() {
        return new Aabbd(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @DoubleOnly
    public Aabbf toFloat() {
        return new Aabbf((float) minX, (float) minY, (float) minZ, (float) maxX, (float) maxY, (float) maxZ);
    }

    /** Box relative to {@code origin}, subtracted in double and then narrowed (camera-relative rendering). */
    @DoubleOnly
    public Aabbf relativeTo(Vec3d origin) {
        return new Aabbf(
                (float) (minX - origin.x()), (float) (minY - origin.y()), (float) (minZ - origin.z()),
                (float) (maxX - origin.x()), (float) (maxY - origin.y()), (float) (maxZ - origin.z()));
    }
}
