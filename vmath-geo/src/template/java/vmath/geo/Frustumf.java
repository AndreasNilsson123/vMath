package vmath.geo;

import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import vmath.core.Mat3f;
import vmath.core.Mat4f;
import vmath.core.Vec3f;

/**
 * A view frustum as six inward-facing planes (normal points into the frustum, so a point is inside
 * when every signed distance is {@code >= 0}).
 *
 * <p>Built from a view-projection matrix with {@link #fromViewProjection}.
 *
 * <p>All classification tests are <b>conservative</b>: they never report a volume that touches the
 * frustum as outside. They may report a few volumes near a corner as
 * {@link Containment#INTERSECTING} (or intersecting) although they are just outside, which is the
 * standard plane-test trade-off.
 *
 * <p>For an infinite far plane (reversed-Z infinite projection) the far plane degenerates to a zero
 * normal with a positive offset, which every point satisfies.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads without
 * synchronization.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Mat4f viewProjection = Mat4f.perspective(1f, 16f / 9f, 0.1f, 100f, ClipSpace.OPENGL)
 *         .mul(Mat4f.lookAt(new Vec3f(0f, 0f, 5f), Vec3f.ZERO, Vec3f.UNIT_Y));
 * Frustumf frustum = Frustumf.fromViewProjection(viewProjection, DepthRange.of(ClipSpace.OPENGL));
 * Aabbf object = Aabbf.of(new Vec3f(-1f, -1f, -1f), new Vec3f(1f, 1f, 1f));
 * boolean visible = frustum.intersects(object);                         // conservative: false only when certainly outside
 * int where = frustum.classify(object);                                 // Containment.OUTSIDE, INSIDE or INTERSECTING
 * }</pre>
 *
 * @param left the left; must not be {@code null}
 * @param right the right; must not be {@code null}
 * @param bottom the bottom; must not be {@code null}
 * @param top the top; must not be {@code null}
 * @param near the near; must not be {@code null}
 * @param far the far; must not be {@code null}
 */
@GenerateDouble
@ValueType
public record Frustumf(Planef left, Planef right, Planef bottom, Planef top, Planef near, Planef far) {

    /**
     * Extracts the planes from {@code viewProjection} (Gribb and Hartmann): each is a sum or
     * difference of matrix rows, normalized.
     *
     * <p>{@code depth} must match how the projection maps depth.
     *
     * @param m the matrix; must not be {@code null}
     * @param depth the depth; must not be {@code null}
     * @return the frustum, never {@code null}
     */
    public static Frustumf fromViewProjection(Mat4f m, DepthRange depth) {
        // rows of the column-major matrix
        float r0x = m.m00(), r0y = m.m10(), r0z = m.m20(), r0w = m.m30();
        float r1x = m.m01(), r1y = m.m11(), r1z = m.m21(), r1w = m.m31();
        float r2x = m.m02(), r2y = m.m12(), r2z = m.m22(), r2w = m.m32();
        float r3x = m.m03(), r3y = m.m13(), r3z = m.m23(), r3w = m.m33();
        Planef left = new Planef(r3x + r0x, r3y + r0y, r3z + r0z, r3w + r0w).normalize();
        Planef right = new Planef(r3x - r0x, r3y - r0y, r3z - r0z, r3w - r0w).normalize();
        Planef bottom = new Planef(r3x + r1x, r3y + r1y, r3z + r1z, r3w + r1w).normalize();
        Planef top = new Planef(r3x - r1x, r3y - r1y, r3z - r1z, r3w - r1w).normalize();
        Planef rowZ = new Planef(r2x, r2y, r2z, r2w);
        Planef rowWPlusZ = new Planef(r3x + r2x, r3y + r2y, r3z + r2z, r3w + r2w);
        Planef rowWMinusZ = new Planef(r3x - r2x, r3y - r2y, r3z - r2z, r3w - r2w);
        Planef near;
        Planef far;
        switch (depth) {
            case NEGATIVE_ONE_TO_ONE -> {
                near = rowWPlusZ.normalize();
                far = rowWMinusZ.normalize();
            }
            case ZERO_TO_ONE -> {
                near = rowZ.normalize();
                far = rowWMinusZ.normalize();
            }
            default -> {
                near = rowWMinusZ.normalize();
                far = rowZ.normalize();
            }
        }
        return new Frustumf(left, right, bottom, top, near, far);
    }

    /**
     * Reads one of the six frustum planes, which point inwards.
     *
     * @param i the index
     * @return plane {@code i}: 0 left, 1 right, 2 bottom, 3 top, 4 near, 5 far
     * @throws IndexOutOfBoundsException if {@code i} is not in {@code [0, 6)}
     */
    public Planef plane(int i) {
        return switch (i) {
            case 0 -> left;
            case 1 -> right;
            case 2 -> bottom;
            case 3 -> top;
            case 4 -> near;
            case 5 -> far;
            default -> throw new IndexOutOfBoundsException(i);
        };
    }

    /**
     * Writes the planes as 24 floats: per plane {@code nx, ny, nz, d}, in {@link #plane} order.
     *
     * @param dst receives the result
     * @param off the index of the first element to read or write
     */
    public void writeTo(float[] dst, int off) {
        for (int i = 0; i < 6; i++) {
            Planef p = plane(i);
            int o = off + i * 4;
            dst[o] = p.nx();
            dst[o + 1] = p.ny();
            dst[o + 2] = p.nz();
            dst[o + 3] = p.d();
        }
    }

    /**
     * Tests whether a point lies inside all six planes; the surface counts as inside.
     *
     * @param p the vector; must not be {@code null}
     * @return {@code true} when the point is inside all six planes or on one
     */
    public boolean contains(Vec3f p) {
        for (int i = 0; i < 6; i++) {
            if (plane(i).distance(p) < 0f) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the conservative box test: false only when the box is entirely outside one plane.
     *
     * @param box the box; must not be {@code null}
     * @return {@code false} only when the box is entirely outside one plane, {@code true} otherwise
     */
    public boolean intersects(Aabbf box) {
        return classify(box) != Containment.OUTSIDE;
    }

    /**
     * Classifies a box against the six planes, telling whether it is fully inside, fully outside or
     * straddling the boundary; the straddling case is conservative, and a box near a frustum corner
     * can be reported as intersecting although it lies outside.
     *
     * @param box the box; must not be {@code null}
     * @return {@link Containment#OUTSIDE}, {@link Containment#INTERSECTING} or
     *     {@link Containment#INSIDE}
     */
    public int classify(Aabbf box) {
        float cx = (box.minX() + box.maxX()) * 0.5f, cy = (box.minY() + box.maxY()) * 0.5f;
        float cz = (box.minZ() + box.maxZ()) * 0.5f;
        float hx = (box.maxX() - box.minX()) * 0.5f, hy = (box.maxY() - box.minY()) * 0.5f;
        float hz = (box.maxZ() - box.minZ()) * 0.5f;
        int result = Containment.INSIDE;
        for (int i = 0; i < 6; i++) {
            Planef p = plane(i);
            float s = p.nx() * cx + p.ny() * cy + p.nz() * cz + p.d();
            float r = hx * Math.abs(p.nx()) + hy * Math.abs(p.ny()) + hz * Math.abs(p.nz());
            if (s + r < 0f) {
                return Containment.OUTSIDE;
            }
            if (s - r < 0f) {
                result = Containment.INTERSECTING;
            }
        }
        return result;
    }

    /**
     * Tests a sphere against the six planes; conservative, so spheres near the frustum corners can
     * be reported as intersecting although they lie outside.
     *
     * <p>Conservative at the corners and edges: the six plane tests can keep a sphere that touches
     * none of the frustum's volume.
     *
     * @param s the sphere; must not be {@code null}
     * @return {@code true} when the sphere is not entirely outside the frustum
     */
    public boolean intersects(Spheref s) {
        return classify(s) != Containment.OUTSIDE;
    }

    /**
     * Returns the where the sphere is relative to the frustum: {@code Containment.OUTSIDE}
     * (entirely outside one plane), {@code INSIDE} (entirely inside all six) or
     * {@code INTERSECTING}.
     *
     * @param s the sphere; must not be {@code null}
     * @return {@link Containment#OUTSIDE}, {@link Containment#INSIDE} or
     *     {@link Containment#INTERSECTING}
     */
    public int classify(Spheref s) {
        int result = Containment.INSIDE;
        for (int i = 0; i < 6; i++) {
            Planef p = plane(i);
            float dist = p.nx() * s.cx() + p.ny() * s.cy() + p.nz() * s.cz() + p.d();
            if (dist < -s.radius()) {
                return Containment.OUTSIDE;
            }
            if (dist < s.radius()) {
                result = Containment.INTERSECTING;
            }
        }
        return result;
    }

    /**
     * Tests an oriented box against the six planes; conservative, so boxes near the frustum corners
     * can be reported as intersecting although they lie outside.
     *
     * <p>Conservative at the corners and edges, as for spheres.
     *
     * @param box the box; must not be {@code null}
     * @return {@code true} when the box is not entirely outside the frustum
     */
    public boolean intersects(Obbf box) {
        return classify(box) != Containment.OUTSIDE;
    }

    /**
     * Returns the where the box is relative to the frustum: {@code Containment.OUTSIDE},
     * {@code INSIDE} or {@code INTERSECTING}, tested against the six planes.
     *
     * @param box the box; must not be {@code null}
     * @return {@link Containment#OUTSIDE}, {@link Containment#INSIDE} or
     *     {@link Containment#INTERSECTING}
     */
    public int classify(Obbf box) {
        Mat3f r = box.axes();
        int result = Containment.INSIDE;
        for (int i = 0; i < 6; i++) {
            Planef p = plane(i);
            float s = p.nx() * box.cx() + p.ny() * box.cy() + p.nz() * box.cz() + p.d();
            float radius = box.hx() * Math.abs(p.nx() * r.m00() + p.ny() * r.m01() + p.nz() * r.m02())
                    + box.hy() * Math.abs(p.nx() * r.m10() + p.ny() * r.m11() + p.nz() * r.m12())
                    + box.hz() * Math.abs(p.nx() * r.m20() + p.ny() * r.m21() + p.nz() * r.m22());
            if (s + radius < 0f) {
                return Containment.OUTSIDE;
            }
            if (s - radius < 0f) {
                result = Containment.INTERSECTING;
            }
        }
        return result;
    }

    /**
     * Converts the planes to {@code double}, which is exact.
     *
     * @return the same frustum with double-precision planes
     */
    @FloatOnly
    public Frustumd toDouble() {
        return new Frustumd(left.toDouble(), right.toDouble(), bottom.toDouble(), top.toDouble(),
                near.toDouble(), far.toDouble());
    }

    /**
     * Converts the planes to {@code float}, which rounds values that need more precision.
     *
     * @return the same frustum with float planes, each rounded to the nearest float
     */
    @DoubleOnly
    public Frustumf toFloat() {
        return new Frustumf(left.toFloat(), right.toFloat(), bottom.toFloat(), top.toFloat(), near.toFloat(), far.toFloat());
    }
}
