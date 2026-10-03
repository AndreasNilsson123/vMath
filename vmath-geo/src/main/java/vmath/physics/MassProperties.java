package vmath.physics;

import vmath.core.Mat3d;
import vmath.core.Quatd;
import vmath.core.Vec3d;

/**
 * The mass, the centre of mass and the inertia tensor of a rigid body: what a physics engine needs
 * to turn forces into accelerations.
 *
 * <p>The values are for a body of <b>uniform density</b>, in double precision.
 *
 * <p>The <b>inertia tensor</b> is the symmetric 3x3 matrix that relates the angular velocity to the
 * angular momentum, {@code L = I w}, taken about the <em>centre of mass</em> and written in the
 * axes of the shape's own frame. It is stored as the six independent entries
 * {@code Ixx, Iyy, Izz, Ixy, Ixz, Iyz} with the usual sign convention (the off-diagonal entries of
 * the matrix are {@code -Ixy} and so on, as for the tensor itself: {@link #inertia()} returns the
 * matrix).
 *
 * <p>The primitives have the closed forms of any mechanics textbook (the tests compare them with
 * numerical integration over the volume): {@link #sphere}, {@link #hollowSphere}, {@link #box},
 * {@link #cylinder}, {@link #capsule}, {@link #cone}, {@link #ellipsoid}. {@link #ofMesh} computes
 * the exact properties of a closed triangle mesh (the polyhedral mass properties of Mirtich and
 * Eberly, by summing signed tetrahedra) and {@link #ofPoints} those of a set of point masses.
 * {@link #transformed}, {@link #translatedInertia} and {@link #combine} move a body, change the
 * reference point of its inertia by the parallel axis theorem, and build a compound body from
 * parts. {@link #principalAxes} finds the frame in which the tensor is diagonal.
 *
 * <p>The centre of mass of a primitive is given relative to the origin of the shape: the origin is
 * the centre of the shape, except for the cone (see {@link #cone}). A rigid body placed in the
 * world is positioned by its centre of mass, so the shape is drawn with its origin offset by
 * {@link #centerOfMass()}.
 *
 * <p><b>Thread safety.</b> Immutable: safe to share between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * MassProperties box = MassProperties.box(0.5, 0.25, 0.25, 10.0);          // half extents and mass
 * MassProperties.Principal axes = box.principalAxes();
 * double mass = box.mass();
 * MassProperties compound = MassProperties.combine(new MassProperties[] {box, MassProperties.sphere(0.2, 1.0)},
 *         new Quatd[] {Quatd.IDENTITY, Quatd.IDENTITY}, new Vec3d[] {Vec3d.ZERO, new Vec3d(1.0, 0.0, 0.0)});
 * }</pre>
 */
public final class MassProperties {

    /**
     * The axis a cylinder, capsule or cone is aligned with.
     */
    public enum Axis {
        /**
         * The x axis.
         */
        X,
        /**
         * The y axis.
         */
        Y,
        /**
         * The z axis.
         */
        Z
    }

    /**
     * The principal axes of an inertia tensor: see {@link #principalAxes()}.
     *
     * @param moments the moments; must not be {@code null}
     * @param rotation the rotation; must not be {@code null}
     */
    public record Principal(Vec3d moments, Quatd rotation) {
    }

    private final double mass;
    private final double cx, cy, cz;
    private final double ixx, iyy, izz, ixy, ixz, iyz;

    private MassProperties(double mass, double cx, double cy, double cz, double ixx, double iyy, double izz, double ixy, double ixz, double iyz) {
        this.mass = mass;
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.ixx = ixx;
        this.iyy = iyy;
        this.izz = izz;
        this.ixy = ixy;
        this.ixz = ixz;
        this.iyz = iyz;
    }

    /**
     * Returns the mass properties from the entries: the mass, the centre of mass and the inertia
     * tensor about it, given as the moments of inertia {@code Ixx, Iyy, Izz} and the off-diagonal
     * entries {@code Ixy, Ixz, Iyz} of the tensor <em>matrix</em> (the negatives of the products of
     * inertia {@code integral of x y dm}).
     *
     * <p>{@link IllegalArgumentException} unless the mass is positive and the tensor is that of a
     * possible body: positive principal moments, none larger than the sum of the other two.
     *
     * @param mass the mass
     * @param centerOfMass the center of mass; must not be {@code null}
     * @param ixx the ixx
     * @param iyy the iyy
     * @param izz the izz
     * @param ixy the ixy
     * @param ixz the ixz
     * @param iyz the iyz
     * @return the mass properties, never {@code null}
     * @throws IllegalArgumentException if the mass is not positive and finite, or the tensor is not
     *     that of a physical body
     */
    public static MassProperties of(double mass, Vec3d centerOfMass, double ixx, double iyy, double izz, double ixy, double ixz, double iyz) {
        if (!(mass > 0) || Double.isInfinite(mass)) {
            throw new IllegalArgumentException("the mass must be positive and finite: " + mass);
        }
        MassProperties p = new MassProperties(mass, centerOfMass.x(), centerOfMass.y(), centerOfMass.z(), ixx, iyy, izz, ixy, ixz, iyz);
        // the tensor of a real body is positive definite and obeys the triangle inequalities of its principal moments
        double[] e = eigenvalues(p);
        if (!(e[0] > 0) || e[0] + e[1] < e[2] * (1 - 1e-9) || e[0] + e[2] < e[1] * (1 - 1e-9) || e[1] + e[2] < e[0] * (1 - 1e-9)) {
            throw new IllegalArgumentException("not the inertia tensor of a body: principal moments " + e[0] + ", " + e[1] + ", " + e[2]);
        }
        return p;
    }

    // ------------------------------------------------------------ primitives

    /**
     * Computes the inertia of a solid sphere from its radius and mass.
     *
     * @param radius the radius
     * @param mass the mass
     * @return a solid sphere of the given radius and mass: {@code I = 2/5 m r^2} about every axis
     */
    public static MassProperties sphere(double radius, double mass) {
        positive(radius, "radius");
        double i = 0.4 * mass * radius * radius;
        return new MassProperties(mass, 0, 0, 0, i, i, i, 0, 0, 0).checked();
    }

    /**
     * Computes the inertia of a thin spherical shell from its radius and mass.
     *
     * @param radius the radius
     * @param mass the mass
     * @return a thin spherical shell: {@code I = 2/3 m r^2}
     */
    public static MassProperties hollowSphere(double radius, double mass) {
        positive(radius, "radius");
        double i = 2.0 / 3.0 * mass * radius * radius;
        return new MassProperties(mass, 0, 0, 0, i, i, i, 0, 0, 0).checked();
    }

    /**
     * Computes the inertia of a solid box from its half extents and mass.
     *
     * @param hx the half extent along x
     * @param hy the half extent along y
     * @param hz the half extent along z
     * @param mass the mass
     * @return a solid box with the half extents {@code hx, hy, hz} along its axes:
     *     {@code Ixx = m/3 (hy^2 + hz^2)} and so on
     */
    public static MassProperties box(double hx, double hy, double hz, double mass) {
        positive(hx, "half extent x");
        positive(hy, "half extent y");
        positive(hz, "half extent z");
        return new MassProperties(mass, 0, 0, 0, mass / 3 * (hy * hy + hz * hz), mass / 3 * (hx * hx + hz * hz), mass / 3 * (hx * hx + hy * hy), 0, 0, 0).checked();
    }

    /**
     * Computes the inertia of a solid ellipsoid from its semi-axes and mass.
     *
     * @param a the semi-axis along x
     * @param b the semi-axis along y
     * @param c the semi-axis along z
     * @param mass the mass
     * @return a solid ellipsoid with the semi-axes {@code a, b, c} along x, y, z:
     *     {@code Ixx = m/5 (b^2 + c^2)}
     */
    public static MassProperties ellipsoid(double a, double b, double c, double mass) {
        positive(a, "semi-axis a");
        positive(b, "semi-axis b");
        positive(c, "semi-axis c");
        return new MassProperties(mass, 0, 0, 0, mass / 5 * (b * b + c * c), mass / 5 * (a * a + c * c), mass / 5 * (a * a + b * b), 0, 0, 0).checked();
    }

    /**
     * Computes the inertia of a solid cylinder aligned with an axis from its radius, half height
     * and mass.
     *
     * @param radius the radius
     * @param halfHeight the half height
     * @param axis the axis; must not be {@code null}
     * @param mass the mass
     * @return a solid cylinder of the given radius and half height along {@code axis}, centred at
     *     the origin: {@code I = m r^2 / 2} about the axis, {@code m (r^2 / 4 + h^2 / 3)} across it
     */
    public static MassProperties cylinder(double radius, double halfHeight, Axis axis, double mass) {
        positive(radius, "radius");
        positive(halfHeight, "half height");
        double along = 0.5 * mass * radius * radius, across = mass * (radius * radius / 4 + halfHeight * halfHeight / 3);
        return aligned(mass, 0, along, across, axis);
    }

    /**
     * Computes the mass properties of a capsule aligned with an axis, a cylinder with a hemisphere
     * on each end.
     *
     * <p>The mass is shared by volume between the cylinder and the two hemispheres, which together
     * make a sphere.
     *
     * @param radius the radius
     * @param halfHeight the half height
     * @param axis the axis; must not be {@code null}
     * @param mass the mass
     * @return a capsule: a cylinder of the given radius and half height along {@code axis} with a
     *     hemisphere on each end, centred at the origin
     * @throws IllegalArgumentException if {@code halfHeight} is negative
     */
    public static MassProperties capsule(double radius, double halfHeight, Axis axis, double mass) {
        positive(radius, "radius");
        if (!(halfHeight >= 0)) {
            throw new IllegalArgumentException("the half height must not be negative: " + halfHeight);
        }
        double vc = Math.PI * radius * radius * 2 * halfHeight, vs = 4.0 / 3.0 * Math.PI * radius * radius * radius;
        double mc = mass * vc / (vc + vs), ms = mass * vs / (vc + vs);
        double along = mc * radius * radius / 2 + ms * 0.4 * radius * radius;
        // the two hemispheres (mass ms / 2 each): about its own centre of mass a hemisphere has 83/320 m r^2 across the axis; the centre of mass is 3 r / 8 beyond the cylinder end
        double d = halfHeight + 3 * radius / 8;
        double across = mc * (radius * radius / 4 + halfHeight * halfHeight / 3) + ms * (83.0 / 320.0 * radius * radius + d * d);
        return aligned(mass, 0, along, across, axis);
    }

    /**
     * Computes the mass properties of a solid cone aligned with an axis, with the origin at the
     * middle of its height rather than at the centre of mass.
     *
     * <p>The tensor is about the centre of mass: {@code 3/10 m r^2} about the axis and
     * {@code m (3/20 r^2 + 3/80 h^2)} across it.
     *
     * @param radius the radius
     * @param height the height
     * @param axis the axis; must not be {@code null}
     * @param mass the mass
     * @return a solid cone of the given base radius and total height along {@code axis}, with its
     *     apex towards {@code +axis}: the origin is halfway along the height, so the base is at
     *     {@code -height / 2} and the centre of mass at {@code -height / 4} along the axis
     */
    public static MassProperties cone(double radius, double height, Axis axis, double mass) {
        positive(radius, "radius");
        positive(height, "height");
        double along = 0.3 * mass * radius * radius, across = mass * (3.0 / 20.0 * radius * radius + 3.0 / 80.0 * height * height);
        return aligned(mass, -height / 4, along, across, axis);
    }

    private static MassProperties aligned(double mass, double offset, double along, double across, Axis axis) {
        MassProperties p = switch (axis) {
            case X -> new MassProperties(mass, offset, 0, 0, along, across, across, 0, 0, 0);
            case Y -> new MassProperties(mass, 0, offset, 0, across, along, across, 0, 0, 0);
            case Z -> new MassProperties(mass, 0, 0, offset, across, across, along, 0, 0, 0);
        };
        return p.checked();
    }

    private MassProperties checked() {
        if (!(mass > 0) || Double.isInfinite(mass)) {
            throw new IllegalArgumentException("the mass must be positive and finite: " + mass);
        }
        return this;
    }

    private static void positive(double v, String what) {
        if (!(v > 0) || Double.isInfinite(v)) {
            throw new IllegalArgumentException("the " + what + " must be positive and finite: " + v);
        }
    }

    // ------------------------------------------------------------ meshes and points

    /**
     * Integrates the mass properties of a solid exactly from its triangle mesh by summing signed
     * tetrahedra; the mesh must be closed and consistently wound, otherwise the volume and the
     * inertia are meaningless.
     *
     * <p>{@code positions} holds {@code x, y, z} triples, {@code indices} three vertex indices per
     * triangle, counter-clockwise seen from outside. The mesh must be closed and consistently
     * oriented; an inside-out mesh has a negative volume and is rejected
     * ({@link IllegalArgumentException}), as is a degenerate one. The result is about the centre of
     * mass.
     *
     * @param positions the positions (at least 3 elements)
     * @param indices the indices
     * @param triangleCount the triangle count
     * @param density the density
     * @return the exact mass properties of the solid bounded by a closed triangle mesh of uniform
     *     {@code density}: the volume is the sum of the signed volumes of the tetrahedra from the
     *     origin to the triangles, and the centre of mass and the second moments follow from the
     *     same sum
     * @throws IllegalArgumentException if there are fewer than 4 triangles or the mesh does not
     *     enclose a positive volume (it is not closed, is inside out or is degenerate)
     */
    public static MassProperties ofMesh(float[] positions, int[] indices, int triangleCount, double density) {
        positive(density, "density");
        if (triangleCount < 4 || indices.length < 3 * triangleCount) {
            throw new IllegalArgumentException("a closed mesh needs at least 4 triangles, got " + triangleCount);
        }
        double volume = 0, mx = 0, my = 0, mz = 0;
        double xx = 0, yy = 0, zz = 0, xy = 0, xz = 0, yz = 0; // the integrals of x^2, y^2, z^2, xy, xz, yz over the volume
        for (int t = 0; t < triangleCount; t++) {
            int a = 3 * indices[3 * t], b = 3 * indices[3 * t + 1], c = 3 * indices[3 * t + 2];
            double ax = positions[a], ay = positions[a + 1], az = positions[a + 2];
            double bx = positions[b], by = positions[b + 1], bz = positions[b + 2];
            double cx = positions[c], cy = positions[c + 1], cz = positions[c + 2];
            double v = (ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx)) / 6.0; // signed volume of the tetrahedron 0, a, b, c
            volume += v;
            mx += v * (ax + bx + cx) / 4;
            my += v * (ay + by + cy) / 4;
            mz += v * (az + bz + cz) / 4;
            xx += v / 10 * (ax * ax + bx * bx + cx * cx + ax * bx + ax * cx + bx * cx);
            yy += v / 10 * (ay * ay + by * by + cy * cy + ay * by + ay * cy + by * cy);
            zz += v / 10 * (az * az + bz * bz + cz * cz + az * bz + az * cz + bz * cz);
            xy += v / 20 * (2 * (ax * ay + bx * by + cx * cy) + ax * by + ay * bx + ax * cy + ay * cx + bx * cy + by * cx);
            xz += v / 20 * (2 * (ax * az + bx * bz + cx * cz) + ax * bz + az * bx + ax * cz + az * cx + bx * cz + bz * cx);
            yz += v / 20 * (2 * (ay * az + by * bz + cy * cz) + ay * bz + az * by + ay * cz + az * cy + by * cz + bz * cy);
        }
        if (!(volume > 1e-300)) {
            throw new IllegalArgumentException("the mesh has a volume of " + volume + ": it is not closed, is inside out, or is degenerate");
        }
        double m = density * volume;
        double comx = mx / volume, comy = my / volume, comz = mz / volume;
        // the tensor about the origin, then moved to the centre of mass (parallel axis theorem, backwards)
        double ioxx = density * (yy + zz), ioyy = density * (xx + zz), iozz = density * (xx + yy);
        double ioxy = -density * xy, ioxz = -density * xz, ioyz = -density * yz;
        double d2 = comx * comx + comy * comy + comz * comz;
        return new MassProperties(m, comx, comy, comz,
                ioxx - m * (d2 - comx * comx), ioyy - m * (d2 - comy * comy), iozz - m * (d2 - comz * comz),
                ioxy + m * comx * comy, ioxz + m * comx * comz, ioyz + m * comy * comz);
    }

    /**
     * Computes the mass properties of a set of point masses about their common centre of mass.
     *
     * <p>At least two non-collinear points are needed for a valid tensor.
     *
     * @param xyz the three components
     * @param masses the masses
     * @param count the number of elements
     * @return the mass properties of {@code count} point masses ({@code xyz} holds {@code x, y, z}
     *     triples, {@code masses} one mass each, all positive); about the centre of mass
     * @throws IllegalArgumentException if the arrays do not hold {@code count} points and masses
     */
    public static MassProperties ofPoints(float[] xyz, double[] masses, int count) {
        if (count < 1 || xyz.length < 3 * count || masses.length < count) {
            throw new IllegalArgumentException("need " + count + " points and masses");
        }
        double m = 0, mx = 0, my = 0, mz = 0;
        for (int i = 0; i < count; i++) {
            positive(masses[i], "mass");
            m += masses[i];
            mx += masses[i] * xyz[3 * i];
            my += masses[i] * xyz[3 * i + 1];
            mz += masses[i] * xyz[3 * i + 2];
        }
        double cx = mx / m, cy = my / m, cz = mz / m;
        double xx = 0, yy = 0, zz = 0, xy = 0, xz = 0, yz = 0;
        for (int i = 0; i < count; i++) {
            double x = xyz[3 * i] - cx, y = xyz[3 * i + 1] - cy, z = xyz[3 * i + 2] - cz;
            xx += masses[i] * x * x;
            yy += masses[i] * y * y;
            zz += masses[i] * z * z;
            xy += masses[i] * x * y;
            xz += masses[i] * x * z;
            yz += masses[i] * y * z;
        }
        return new MassProperties(m, cx, cy, cz, yy + zz, xx + zz, xx + yy, -xy, -xz, -yz);
    }

    // ------------------------------------------------------------ queries

    /**
     * Exposes the total mass.
     *
     * @return the mass
     */
    public double mass() {
        return mass;
    }

    /**
     * Exposes the centre of mass in the frame of the shape.
     *
     * @return the centre of mass relative to the origin of the shape
     */
    public Vec3d centerOfMass() {
        return new Vec3d(cx, cy, cz);
    }

    /**
     * Exposes the inertia tensor about the centre of mass as a symmetric matrix in the axes of the
     * shape.
     *
     * @return the inertia tensor about the centre of mass, in the axes of the shape, as a symmetric
     *     matrix
     */
    public Mat3d inertia() {
        return new Mat3d(ixx, ixy, ixz, ixy, iyy, iyz, ixz, iyz, izz);
    }

    /**
     * Reads the diagonal element of the inertia tensor about the x axis.
     *
     * @return the moment of inertia about the x axis through the centre of mass
     */
    public double ixx() {
        return ixx;
    }

    /**
     * Reads the diagonal element of the inertia tensor about the y axis.
     *
     * @return the moment of inertia about the y axis through the centre of mass
     */
    public double iyy() {
        return iyy;
    }

    /**
     * Reads the diagonal element of the inertia tensor about the z axis.
     *
     * @return the moment of inertia about the z axis through the centre of mass
     */
    public double izz() {
        return izz;
    }

    /**
     * Evaluates the quadratic form of the inertia tensor to get the moment about an arbitrary axis;
     * the axis must have unit length.
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @return the moment of inertia about the unit axis {@code (x, y, z)} through the centre of
     *     mass: {@code u . I u}
     */
    public double momentAbout(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        x /= l;
        y /= l;
        z /= l;
        return ixx * x * x + iyy * y * y + izz * z * z + 2 * (ixy * x * y + ixz * x * z + iyz * y * z);
    }

    /**
     * Inverts the inertia tensor, which is what turns angular momentum into angular velocity; it is
     * the quantity that the solver uses.
     *
     * @return the inverse of the inertia tensor (a symmetric matrix): what the angular velocity is
     *     computed with, {@code w = I^-1 L}
     */
    public Mat3d inverseInertia() {
        double[] inv = invert(ixx, iyy, izz, ixy, ixz, iyz);
        return new Mat3d(inv[0], inv[3], inv[4], inv[3], inv[1], inv[5], inv[4], inv[5], inv[2]);
    }

    // ------------------------------------------------------------ moving and combining

    /**
     * Moves the mass properties with a rigid transform: the centre of mass follows and the tensor
     * is rotated into the new frame.
     *
     * @param rotation the rotation; must not be {@code null}
     * @param translation the translation; must not be {@code null}
     * @return the same body rotated by {@code rotation} (a unit quaternion) about the origin and
     *     then moved by {@code translation}: the centre of mass is moved with it, and the tensor,
     *     which is about the centre of mass, is rotated: {@code R I R^T}
     */
    public MassProperties transformed(Quatd rotation, Vec3d translation) {
        Mat3d r = Mat3d.rotation(rotation);
        Vec3d com = r.transform(new Vec3d(cx, cy, cz)).add(translation);
        double[] t = rotate(r, ixx, iyy, izz, ixy, ixz, iyz);
        return new MassProperties(mass, com.x(), com.y(), com.z(), t[0], t[1], t[2], t[3], t[4], t[5]);
    }

    /**
     * Shifts the reference point of the inertia tensor with the parallel axis theorem, which adds
     * the inertia that the mass has about the new point.
     *
     * <p>The axes stay those of the shape.
     *
     * @param offset the offset; must not be {@code null}
     * @return the inertia tensor about the point at {@code offset} from the centre of mass, by the
     *     parallel axis theorem: {@code I + m (|d|^2 E - d d^T)}
     */
    public Mat3d translatedInertia(Vec3d offset) {
        double dx = offset.x(), dy = offset.y(), dz = offset.z(), d2 = dx * dx + dy * dy + dz * dz;
        return new Mat3d(ixx + mass * (d2 - dx * dx), ixy - mass * dx * dy, ixz - mass * dx * dz,
                ixy - mass * dx * dy, iyy + mass * (d2 - dy * dy), iyz - mass * dy * dz,
                ixz - mass * dx * dz, iyz - mass * dy * dz, izz + mass * (d2 - dz * dz));
    }

    /**
     * Merges several bodies into one compound body, adding the masses, combining the centres of
     * mass and shifting every inertia tensor to the common centre with the parallel axis theorem.
     *
     * @param parts the parts; must not be {@code null}
     * @param rotations the rotations; must not be {@code null}
     * @param translations the translations; must not be {@code null}
     * @return the compound body of the parts: each part is placed by its own rotation and
     *     translation ({@code parts[i]} is first rotated and then moved), the masses add, the
     *     centre of mass is the mass-weighted mean, and the tensor about it is the sum of the
     *     parts' tensors, rotated and moved by the parallel axis theorem
     * @throws IllegalArgumentException if a part has no rotation or no translation
     */
    public static MassProperties combine(MassProperties[] parts, Quatd[] rotations, Vec3d[] translations) {
        if (parts.length < 1 || rotations.length != parts.length || translations.length != parts.length) {
            throw new IllegalArgumentException("each part needs a rotation and a translation");
        }
        MassProperties[] placed = new MassProperties[parts.length];
        double m = 0, mx = 0, my = 0, mz = 0;
        for (int i = 0; i < parts.length; i++) {
            placed[i] = parts[i].transformed(rotations[i], translations[i]);
            m += placed[i].mass;
            mx += placed[i].mass * placed[i].cx;
            my += placed[i].mass * placed[i].cy;
            mz += placed[i].mass * placed[i].cz;
        }
        double cx = mx / m, cy = my / m, cz = mz / m;
        double xx = 0, yy = 0, zz = 0, xy = 0, xz = 0, yz = 0;
        for (MassProperties p : placed) {
            Mat3d t = p.translatedInertia(new Vec3d(p.cx - cx, p.cy - cy, p.cz - cz));
            xx += t.m00();
            yy += t.m11();
            zz += t.m22();
            xy += t.m10();
            xz += t.m20();
            yz += t.m21();
        }
        return new MassProperties(m, cx, cy, cz, xx, yy, zz, xy, xz, yz);
    }

    /**
     * Rescales the mass, which changes the density and scales the inertia in proportion.
     *
     * @param newMass the new mass
     * @return the same shape with the mass replaced: the density changes, so the inertia scales in
     *     proportion
     */
    public MassProperties withMass(double newMass) {
        double s = newMass / mass;
        return new MassProperties(newMass, cx, cy, cz, ixx * s, iyy * s, izz * s, ixy * s, ixz * s, iyz * s).checked();
    }

    // ------------------------------------------------------------ principal axes

    /**
     * Diagonalises the inertia tensor with an eigenvalue decomposition, yielding the principal
     * moments and the frame in which the tensor is diagonal.
     *
     * <p>In the principal frame the tensor is diagonal.
     *
     * @return the principal axes: the three principal moments of inertia (the eigenvalues, in
     *     ascending order) and the rotation from the principal frame to the shape's frame, whose
     *     columns are the matching eigenvectors (a proper rotation)
     */
    public Principal principalAxes() {
        double[] a = {ixx, ixy, ixz, ixy, iyy, iyz, ixz, iyz, izz};
        double[] v = new double[9];
        jacobi(a, v);
        double[] e = {a[0], a[4], a[8]};
        int[] order = {0, 1, 2};
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2 - i; j++) {
                if (e[order[j]] > e[order[j + 1]]) {
                    int t = order[j];
                    order[j] = order[j + 1];
                    order[j + 1] = t;
                }
            }
        }
        double[] r = new double[9]; // columns in ascending order of the moment
        for (int c = 0; c < 3; c++) {
            for (int row = 0; row < 3; row++) {
                r[3 * row + c] = v[3 * row + order[c]];
            }
        }
        double det = r[0] * (r[4] * r[8] - r[5] * r[7]) - r[1] * (r[3] * r[8] - r[5] * r[6]) + r[2] * (r[3] * r[7] - r[4] * r[6]);
        if (det < 0) {
            r[2] = -r[2];
            r[5] = -r[5];
            r[8] = -r[8];
        }
        // the quaternion of the rotation matrix with columns c0, c1, c2 (row-major r)
        double trace = r[0] + r[4] + r[8], qx, qy, qz, qw;
        if (trace > 0) {
            double s = Math.sqrt(trace + 1) * 2;
            qw = s / 4;
            qx = (r[7] - r[5]) / s;
            qy = (r[2] - r[6]) / s;
            qz = (r[3] - r[1]) / s;
        } else if (r[0] > r[4] && r[0] > r[8]) {
            double s = Math.sqrt(1 + r[0] - r[4] - r[8]) * 2;
            qw = (r[7] - r[5]) / s;
            qx = s / 4;
            qy = (r[1] + r[3]) / s;
            qz = (r[2] + r[6]) / s;
        } else if (r[4] > r[8]) {
            double s = Math.sqrt(1 + r[4] - r[0] - r[8]) * 2;
            qw = (r[2] - r[6]) / s;
            qx = (r[1] + r[3]) / s;
            qy = s / 4;
            qz = (r[5] + r[7]) / s;
        } else {
            double s = Math.sqrt(1 + r[8] - r[0] - r[4]) * 2;
            qw = (r[3] - r[1]) / s;
            qx = (r[2] + r[6]) / s;
            qy = (r[5] + r[7]) / s;
            qz = s / 4;
        }
        return new Principal(new Vec3d(e[order[0]], e[order[1]], e[order[2]]), new Quatd(qx, qy, qz, qw).normalize());
    }

    private static double[] eigenvalues(MassProperties p) {
        double[] a = {p.ixx, p.ixy, p.ixz, p.ixy, p.iyy, p.iyz, p.ixz, p.iyz, p.izz};
        double[] v = new double[9];
        jacobi(a, v);
        double[] e = {a[0], a[4], a[8]};
        java.util.Arrays.sort(e);
        return e;
    }

    // ------------------------------------------------------------ small matrix helpers

    /**
     * The six entries {@code xx, yy, zz, xy, xz, yz} of {@code R S R^T} for the symmetric matrix of
     * the given entries.
     */
    static double[] rotate(Mat3d r, double sxx, double syy, double szz, double sxy, double sxz, double syz) {
        double[][] s = {{sxx, sxy, sxz}, {sxy, syy, syz}, {sxz, syz, szz}};
        double[][] m = {{r.m00(), r.m10(), r.m20()}, {r.m01(), r.m11(), r.m21()}, {r.m02(), r.m12(), r.m22()}}; // row i, column j
        double[][] rs = new double[3][3], out = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    rs[i][j] += m[i][k] * s[k][j];
                }
            }
        }
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    out[i][j] += rs[i][k] * m[j][k];
                }
            }
        }
        return new double[] {out[0][0], out[1][1], out[2][2], out[0][1], out[0][2], out[1][2]};
    }

    /**
     * The inverse of the symmetric matrix, as {@code xx, yy, zz, xy, xz, yz}.
     */
    static double[] invert(double xx, double yy, double zz, double xy, double xz, double yz) {
        double c00 = yy * zz - yz * yz, c01 = xz * yz - xy * zz, c02 = xy * yz - xz * yy;
        double det = xx * c00 + xy * c01 + xz * c02;
        double inv = 1.0 / det;
        return new double[] {c00 * inv, (xx * zz - xz * xz) * inv, (xx * yy - xy * xy) * inv, c01 * inv, c02 * inv, (xy * xz - xx * yz) * inv};
    }

    /**
     * The eigen decomposition of the symmetric 3x3 matrix {@code a} (row-major, destroyed: its
     * diagonal ends up holding the eigenvalues) by cyclic Jacobi rotations; the eigenvectors go to
     * the columns of {@code v}.
     */
    static void jacobi(double[] a, double[] v) {
        v[0] = v[4] = v[8] = 1;
        v[1] = v[2] = v[3] = v[5] = v[6] = v[7] = 0;
        for (int sweep = 0; sweep < 60; sweep++) {
            double off = a[1] * a[1] + a[2] * a[2] + a[5] * a[5];
            double diag = a[0] * a[0] + a[4] * a[4] + a[8] * a[8];
            if (off <= 1e-30 * diag || off == 0) {
                return;
            }
            rotation(a, v, 0, 1);
            rotation(a, v, 0, 2);
            rotation(a, v, 1, 2);
        }
    }

    private static void rotation(double[] a, double[] v, int p, int q) {
        double apq = a[3 * p + q];
        if (apq == 0) {
            return;
        }
        double theta = (a[3 * q + q] - a[3 * p + p]) / (2 * apq);
        double t = theta == 0 ? 1 : Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
        double c = 1 / Math.sqrt(t * t + 1), s = t * c;
        for (int k = 0; k < 3; k++) {
            double akp = a[3 * k + p], akq = a[3 * k + q];
            a[3 * k + p] = c * akp - s * akq;
            a[3 * k + q] = s * akp + c * akq;
        }
        for (int k = 0; k < 3; k++) {
            double apk = a[3 * p + k], aqk = a[3 * q + k];
            a[3 * p + k] = c * apk - s * aqk;
            a[3 * q + k] = s * apk + c * aqk;
        }
        for (int k = 0; k < 3; k++) {
            double vkp = v[3 * k + p], vkq = v[3 * k + q];
            v[3 * k + p] = c * vkp - s * vkq;
            v[3 * k + q] = s * vkp + c * vkq;
        }
    }
}
