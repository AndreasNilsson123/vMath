package vmath.physics;

import vmath.geo.ConvexShape;

/**
 * A box in a pose: a centre, the three half extents and the three axes (the columns of the rotation
 * matrix), in {@code double}, for {@link ManifoldBuilder#boxes}.
 *
 * <p>Mutable on purpose: a world that moves its boxes every step sets the same instances again
 * ({@link #set}) and allocates nothing. It holds no reference to a body; {@link #set(RigidBody,
 * double, double, double)} reads the pose of one.
 *
 * <p>It is also a {@link ConvexShape} (the support point is the corner on the side of the direction), so
 * a sphere or a capsule can be tested against it with {@link ManifoldBuilder#shapes} without making a
 * polytope.
 *
 * <p><b>Thread safety.</b> Mutable and not thread-safe: one writer, and no readers while it writes.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * OrientedBox a = new OrientedBox().set(0.0, 0.5, 0.0, 0.0, 0.0, 0.0, 1.0, 0.5, 0.5, 0.5);   // centre, quaternion x y z w, half extents
 * OrientedBox b = new OrientedBox().set(0.0, 1.45, 0.0, 0.0, 0.0, 0.0, 1.0, 0.5, 0.5, 0.5);
 * ContactManifold manifold = new ContactManifold();
 * boolean touching = new ManifoldBuilder().boxes(a, b, 0.02, manifold);
 * }</pre>
 */
public final class OrientedBox implements ConvexShape {

    double cx, cy, cz;
    double hx, hy, hz;
    /** The axes, three numbers each: axis {@code i} is {@code axes[3 i .. 3 i + 2]}. */
    final double[] axes = new double[9];

    /**
     * Creates a unit box at the origin with no rotation (half extents 0.5).
     */
    public OrientedBox() {
        set(0, 0, 0, 0, 0, 0, 1, 0.5, 0.5, 0.5);
    }

    /**
     * Sets the pose and size.
     *
     * <p>The quaternion is normalised here, so one of rounded components is accepted.
     *
     * @param px the x coordinate of the centre
     * @param py the y coordinate of the centre
     * @param pz the z coordinate of the centre
     * @param qx the x component of the rotation quaternion
     * @param qy the y component
     * @param qz the z component
     * @param qw the w component
     * @param halfX the half extent along the first axis; not negative
     * @param halfY the half extent along the second axis; not negative
     * @param halfZ the half extent along the third axis; not negative
     * @return this box, for chaining
     * @throws IllegalArgumentException if a half extent is negative or NaN, or the quaternion has no length
     */
    public OrientedBox set(double px, double py, double pz, double qx, double qy, double qz, double qw, double halfX, double halfY, double halfZ) {
        if (!(halfX >= 0 && halfY >= 0 && halfZ >= 0)) {
            throw new IllegalArgumentException("the half extents must not be negative: " + halfX + ", " + halfY + ", " + halfZ);
        }
        double norm = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        if (!(norm > 0) || Double.isInfinite(norm)) {
            throw new IllegalArgumentException("the quaternion has no usable length: " + qx + ", " + qy + ", " + qz + ", " + qw);
        }
        qx /= norm;
        qy /= norm;
        qz /= norm;
        qw /= norm;
        cx = px;
        cy = py;
        cz = pz;
        hx = halfX;
        hy = halfY;
        hz = halfZ;
        // the columns of the rotation matrix
        axes[0] = 1 - 2 * (qy * qy + qz * qz);
        axes[1] = 2 * (qx * qy + qz * qw);
        axes[2] = 2 * (qx * qz - qy * qw);
        axes[3] = 2 * (qx * qy - qz * qw);
        axes[4] = 1 - 2 * (qx * qx + qz * qz);
        axes[5] = 2 * (qy * qz + qx * qw);
        axes[6] = 2 * (qx * qz + qy * qw);
        axes[7] = 2 * (qy * qz - qx * qw);
        axes[8] = 1 - 2 * (qx * qx + qy * qy);
        return this;
    }

    /**
     * Sets the pose from a body and the size from the half extents.
     *
     * @param body the body whose position and orientation are read; must not be {@code null}
     * @param halfX the half extent along the first axis of the body; not negative
     * @param halfY the half extent along the second axis; not negative
     * @param halfZ the half extent along the third axis; not negative
     * @return this box, for chaining
     * @throws IllegalArgumentException as {@link #set(double, double, double, double, double, double, double, double, double, double)}
     */
    public OrientedBox set(RigidBody body, double halfX, double halfY, double halfZ) {
        return set(body.px, body.py, body.pz, body.qx, body.qy, body.qz, body.qw, halfX, halfY, halfZ);
    }

    @Override
    public void support(double dx, double dy, double dz, double[] out) {
        double x = cx, y = cy, z = cz;
        for (int k = 0; k < 3; k++) {
            double along = dx * axes[3 * k] + dy * axes[3 * k + 1] + dz * axes[3 * k + 2];
            double h = (k == 0 ? hx : k == 1 ? hy : hz) * (along >= 0 ? 1 : -1);
            x += h * axes[3 * k];
            y += h * axes[3 * k + 1];
            z += h * axes[3 * k + 2];
        }
        out[0] = x;
        out[1] = y;
        out[2] = z;
    }

    /**
     * Reads a coordinate of the centre.
     *
     * @param axis 0 for x, 1 for y, 2 for z
     * @return the coordinate
     * @throws IllegalArgumentException if {@code axis} is not 0, 1 or 2
     */
    public double center(int axis) {
        return switch (axis) {
            case 0 -> cx;
            case 1 -> cy;
            case 2 -> cz;
            default -> throw new IllegalArgumentException("axis must be 0, 1 or 2: " + axis);
        };
    }

    /**
     * Reads a half extent.
     *
     * @param axis 0 for the first axis of the box, 1 for the second, 2 for the third
     * @return the half extent along that axis
     * @throws IllegalArgumentException if {@code axis} is not 0, 1 or 2
     */
    public double halfExtent(int axis) {
        return switch (axis) {
            case 0 -> hx;
            case 1 -> hy;
            case 2 -> hz;
            default -> throw new IllegalArgumentException("axis must be 0, 1 or 2: " + axis);
        };
    }

    /**
     * Reads a component of an axis of the box.
     *
     * @param axis 0, 1 or 2: which axis of the box
     * @param component 0 for x, 1 for y, 2 for z
     * @return that component of the unit vector along the box axis
     * @throws IllegalArgumentException if {@code axis} or {@code component} is not 0, 1 or 2
     */
    public double axis(int axis, int component) {
        if (axis < 0 || axis > 2 || component < 0 || component > 2) {
            throw new IllegalArgumentException("axis and component must be 0, 1 or 2: " + axis + ", " + component);
        }
        return axes[3 * axis + component];
    }
}
