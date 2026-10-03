package vmath.geo;

import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import vmath.core.Mat4f;
import vmath.core.Vec3d;
import vmath.core.Vec3f;

/**
 * Axis-aligned bounding box.
 *
 * <p>Six primitives, so it stays a flat 24-byte value; bulk data belongs in {@code float[]}
 * storage, not in {@code Aabbf[]}.
 *
 * <p>An empty box has {@code min > max} on some axis ({@link #EMPTY} is the identity for
 * {@link #union}). Operations that need a real box document what they do with an empty one.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads without
 * synchronization.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Aabbf box = Aabbf.of(new Vec3f(-1f, 0f, -1f), new Vec3f(1f, 2f, 1f));
 * Vec3f center = box.center();                         // (0, 1, 0)
 * boolean inside = box.contains(new Vec3f(0f, 1f, 0f));
 * Aabbf grown = box.union(new Vec3f(3f, 0f, 0f));
 * Aabbf moved = box.transform(Mat4f.translation(5f, 0f, 0f));   // a box around the transformed corners
 * boolean touching = box.overlaps(moved);
 * }</pre>
 *
 * @param minX the smallest x coordinate
 * @param minY the smallest y coordinate
 * @param minZ the smallest z coordinate
 * @param maxX the largest x coordinate
 * @param maxY the largest y coordinate
 * @param maxZ the largest z coordinate
 */
@GenerateDouble
@ValueType
public record Aabbf(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {

    /**
     * Contains nothing; {@code EMPTY.union(x) == x}.
     */
    public static final Aabbf EMPTY = new Aabbf(
            Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);

    /**
     * Builds a box from two opposite corners given in any order.
     *
     * @param a the first vector; must not be {@code null}
     * @param b the second vector; must not be {@code null}
     * @return the box spanned by two arbitrary corners
     */
    public static Aabbf of(Vec3f a, Vec3f b) {
        return new Aabbf(
                Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()),
                Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
    }

    /**
     * Builds a box from its centre and half extents.
     *
     * @param center the center; must not be {@code null}
     * @param half the half; must not be {@code null}
     * @return the box with the given centre and half the edge lengths on each axis
     */
    public static Aabbf fromCenterHalfExtent(Vec3f center, Vec3f half) {
        return new Aabbf(
                center.x() - half.x(), center.y() - half.y(), center.z() - half.z(),
                center.x() + half.x(), center.y() + half.y(), center.z() + half.z());
    }

    /**
     * Fits a box around packed positions, scanning them once.
     *
     * @param xyz the three components
     * @param offset the index of the first element to read or write
     * @param vertexCount the number of vertices
     * @return bounds of {@code vertexCount} packed {@code x, y, z} positions starting at
     *     {@code offset}
     */
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

    /**
     * Exposes the corner with the smallest coordinates.
     *
     * @return the corner with the smallest coordinates
     */
    public Vec3f min() {
        return new Vec3f(minX, minY, minZ);
    }

    /**
     * Exposes the corner with the largest coordinates.
     *
     * @return the corner with the largest coordinates
     */
    public Vec3f max() {
        return new Vec3f(maxX, maxY, maxZ);
    }

    /**
     * Computes the midpoint of the box.
     *
     * @return the centre of the box
     */
    public Vec3f center() {
        return new Vec3f((minX + maxX) * 0.5f, (minY + maxY) * 0.5f, (minZ + maxZ) * 0.5f);
    }

    /**
     * Computes the edge lengths of the box.
     *
     * @return full edge lengths
     */
    public Vec3f size() {
        return new Vec3f(maxX - minX, maxY - minY, maxZ - minZ);
    }

    /**
     * Computes half the edge lengths, which is the distance from the centre to each face.
     *
     * @return half the edge lengths: the distance from the centre to each face
     */
    public Vec3f halfSize() {
        return new Vec3f((maxX - minX) * 0.5f, (maxY - minY) * 0.5f, (maxZ - minZ) * 0.5f);
    }

    /**
     * Tests whether the box contains no point, which is the case for an inverted box such as the
     * empty constant.
     *
     * @return {@code true} when some minimum is above its maximum: the box holds no point (see
     *     {@code EMPTY})
     */
    public boolean isEmpty() {
        return minX > maxX || minY > maxY || minZ > maxZ;
    }

    /**
     * Reads a corner by index, where each bit of the index selects the maximum for one axis.
     *
     * @param i the index
     * @return corner {@code i}: bit 0 selects max x, bit 1 max y, bit 2 max z
     */
    public Vec3f corner(int i) {
        return new Vec3f((i & 1) == 0 ? minX : maxX, (i & 2) == 0 ? minY : maxY, (i & 4) == 0 ? minZ : maxZ);
    }

    /**
     * Grows a box to contain another, which is how bounds of a group are accumulated.
     *
     * @param o the other box; must not be {@code null}
     * @return the smallest box that contains both boxes
     */
    public Aabbf union(Aabbf o) {
        return new Aabbf(
                Math.min(minX, o.minX), Math.min(minY, o.minY), Math.min(minZ, o.minZ),
                Math.max(maxX, o.maxX), Math.max(maxY, o.maxY), Math.max(maxZ, o.maxZ));
    }

    /**
     * Grows a box to contain a point.
     *
     * @param p the vector; must not be {@code null}
     * @return the smallest box that contains this box and the point
     */
    public Aabbf union(Vec3f p) {
        return new Aabbf(
                Math.min(minX, p.x()), Math.min(minY, p.y()), Math.min(minZ, p.z()),
                Math.max(maxX, p.x()), Math.max(maxY, p.y()), Math.max(maxZ, p.z()));
    }

    /**
     * Clips a box to another; disjoint boxes give an empty box.
     *
     * @param o the other box; must not be {@code null}
     * @return the overlap of two boxes; empty when they are disjoint
     */
    public Aabbf intersection(Aabbf o) {
        return new Aabbf(
                Math.max(minX, o.minX), Math.max(minY, o.minY), Math.max(minZ, o.minZ),
                Math.min(maxX, o.maxX), Math.min(maxY, o.maxY), Math.min(maxZ, o.maxZ));
    }

    /**
     * Grows every face outward by {@code margin} (shrinks for a negative margin).
     *
     * @param margin the margin
     * @return the inflated box, never {@code null}
     */
    public Aabbf inflate(float margin) {
        return new Aabbf(minX - margin, minY - margin, minZ - margin, maxX + margin, maxY + margin, maxZ + margin);
    }

    /**
     * Tests two boxes for overlap; boxes that only touch count as overlapping.
     *
     * @param o the other box; must not be {@code null}
     * @return {@code true} when the boxes share at least a point (touching counts)
     */
    public boolean overlaps(Aabbf o) {
        return minX <= o.maxX && maxX >= o.minX
                && minY <= o.maxY && maxY >= o.minY
                && minZ <= o.maxZ && maxZ >= o.minZ;
    }

    /**
     * Tests whether a point lies inside the box; the surface counts as inside.
     *
     * @param p the vector; must not be {@code null}
     * @return {@code true} when the point is inside the box or on its surface
     */
    public boolean contains(Vec3f p) {
        return p.x() >= minX && p.x() <= maxX && p.y() >= minY && p.y() <= maxY && p.z() >= minZ && p.z() <= maxZ;
    }

    /**
     * Tests whether another box lies completely inside this one.
     *
     * @param o the other box; must not be {@code null}
     * @return {@code true} when {@code o} lies entirely inside this box
     */
    public boolean contains(Aabbf o) {
        return o.minX >= minX && o.maxX <= maxX && o.minY >= minY && o.maxY <= maxY && o.minZ >= minZ && o.maxZ <= maxZ;
    }

    /**
     * Measures the volume of the box.
     *
     * @return the volume, or 0 for an empty box
     */
    public float volume() {
        return isEmpty() ? 0f : (maxX - minX) * (maxY - minY) * (maxZ - minZ);
    }

    /**
     * Measures the total area of the six faces.
     *
     * <p>This is the quantity the surface-area heuristic of BVH builders uses.
     *
     * @return the surface area, or 0 for an empty box
     */
    public float surfaceArea() {
        if (isEmpty()) {
            return 0f;
        }
        float dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
        return 2f * (dx * dy + dy * dz + dz * dx);
    }

    /**
     * Clamps a point to the box, which gives the nearest point of the solid box.
     *
     * @param p the vector; must not be {@code null}
     * @return the point of the box nearest to {@code p} ({@code p} itself when inside)
     */
    public Vec3f closestPoint(Vec3f p) {
        return new Vec3f(
                Math.min(Math.max(p.x(), minX), maxX),
                Math.min(Math.max(p.y(), minY), maxY),
                Math.min(Math.max(p.z(), minZ), maxZ));
    }

    /**
     * Measures the squared distance between a point and the box by clamping, which avoids the
     * square root.
     *
     * @param p the vector; must not be {@code null}
     * @return squared distance from {@code p} to the box; zero inside
     */
    public float distanceSquared(Vec3f p) {
        float dx = Math.max(Math.max(minX - p.x(), 0f), p.x() - maxX);
        float dy = Math.max(Math.max(minY - p.y(), 0f), p.y() - maxY);
        float dz = Math.max(Math.max(minZ - p.z(), 0f), p.z() - maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Computes the box around a transformed box with Arvo's method, which works from the matrix
     * elements directly and is exact for any affine matrix; the result is generally larger than the
     * transformed shape, and a projective matrix is not supported.
     *
     * <p>The projection row of {@code m} is ignored.
     *
     * @param m the matrix; must not be {@code null}
     * @return the tightest axis-aligned box around this box after an affine transform (Arvo's
     *     method: exact for any affine matrix, no need to transform eight corners)
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

    /**
     * Computes the sphere around the box with its centre at the box centre, which is cheap but not
     * the smallest sphere that contains the box's contents.
     *
     * @return the smallest sphere centred on the box centre that contains it
     */
    public Spheref boundingSphere() {
        float cx = (minX + maxX) * 0.5f, cy = (minY + maxY) * 0.5f, cz = (minZ + maxZ) * 0.5f;
        float hx = maxX - cx, hy = maxY - cy, hz = maxZ - cz;
        return new Spheref(cx, cy, cz, (float) Math.sqrt(hx * hx + hy * hy + hz * hz));
    }

    /**
     * Compares two boxes bound by bound with an absolute tolerance, for tests and for detecting
     * changes.
     *
     * @param o the other box; must not be {@code null}
     * @param eps the tolerance
     * @return {@code true} when every one of the six bounds is within {@code eps} of the other
     *     box's
     */
    public boolean approxEquals(Aabbf o, float eps) {
        return Math.abs(minX - o.minX) <= eps && Math.abs(minY - o.minY) <= eps && Math.abs(minZ - o.minZ) <= eps
                && Math.abs(maxX - o.maxX) <= eps && Math.abs(maxY - o.maxY) <= eps && Math.abs(maxZ - o.maxZ) <= eps;
    }

    /**
     * Converts the bounds to {@code double}, which is exact.
     *
     * @return the same box with double-precision bounds
     */
    @FloatOnly
    public Aabbd toDouble() {
        return new Aabbd(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Converts the bounds to {@code float}, which rounds values that need more precision.
     *
     * @return the same box with float bounds, each rounded to the nearest float
     */
    @DoubleOnly
    public Aabbf toFloat() {
        return new Aabbf((float) minX, (float) minY, (float) minZ, (float) maxX, (float) maxY, (float) maxZ);
    }

    /**
     * Subtracts a double-precision origin before narrowing the bounds to {@code float}, which keeps
     * precision far from the origin (camera-relative rendering).
     *
     * @param origin the origin; must not be {@code null}
     * @return box relative to {@code origin}, subtracted in double and then narrowed
     *     (camera-relative rendering)
     */
    @DoubleOnly
    public Aabbf relativeTo(Vec3d origin) {
        return new Aabbf(
                (float) (minX - origin.x()), (float) (minY - origin.y()), (float) (minZ - origin.z()),
                (float) (maxX - origin.x()), (float) (maxY - origin.y()), (float) (maxZ - origin.z()));
    }
}
