package vmath.geo;

import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import vmath.core.Mat3f;
import vmath.core.Mat4f;
import vmath.core.Vec3f;

/**
 * A view frustum as six inward-facing planes (normal points into the frustum, so a point is inside when every signed
 * distance is {@code >= 0}). Built from a view-projection matrix with {@link #fromViewProjection}.
 *
 * <p>All classification tests are <b>conservative</b>: they never report a volume that touches the frustum as
 * outside. They may report a few volumes near a corner as {@link Containment#INTERSECTING} (or intersecting) although
 * they are just outside, which is the standard plane-test trade-off.
 *
 * <p>For an infinite far plane (reversed-Z infinite projection) the far plane degenerates to a zero normal with a
 * positive offset, which every point satisfies.
 */
@GenerateDouble
@ValueType
public record Frustumf(Planef left, Planef right, Planef bottom, Planef top, Planef near, Planef far) {

    /**
     * Extracts the planes from {@code viewProjection} (Gribb and Hartmann): each is a sum or difference of matrix
     * rows, normalized. {@code depth} must match how the projection maps depth.
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

    /** Plane {@code i}: 0 left, 1 right, 2 bottom, 3 top, 4 near, 5 far. */
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

    /** Writes the planes as 24 floats: per plane {@code nx, ny, nz, d}, in {@link #plane} order. */
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

    public boolean contains(Vec3f p) {
        for (int i = 0; i < 6; i++) {
            if (plane(i).distance(p) < 0f) {
                return false;
            }
        }
        return true;
    }

    /** Conservative box test: false only when the box is entirely outside one plane. */
    public boolean intersects(Aabbf box) {
        return classify(box) != Containment.OUTSIDE;
    }

    /** {@link Containment#OUTSIDE}, {@link Containment#INTERSECTING} or {@link Containment#INSIDE}. */
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

    public boolean intersects(Spheref s) {
        return classify(s) != Containment.OUTSIDE;
    }

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

    public boolean intersects(Obbf box) {
        return classify(box) != Containment.OUTSIDE;
    }

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

    @FloatOnly
    public Frustumd toDouble() {
        return new Frustumd(left.toDouble(), right.toDouble(), bottom.toDouble(), top.toDouble(),
                near.toDouble(), far.toDouble());
    }

    @DoubleOnly
    public Frustumf toFloat() {
        return new Frustumf(left.toFloat(), right.toFloat(), bottom.toFloat(), top.toFloat(), near.toFloat(), far.toFloat());
    }
}
