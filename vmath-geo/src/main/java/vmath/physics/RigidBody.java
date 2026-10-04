package vmath.physics;

import vmath.core.Mat3d;
import vmath.core.Quatd;
import vmath.core.Vec3d;

/**
 * The state of one rigid body and its integration in time: the position of its centre of mass, its
 * orientation, its linear and angular velocity (both in the world frame), and the forces and
 * torques collected for the next step.
 *
 * <p>All numbers are {@code double}; the fields are public in the way of a struct, since an engine
 * reads and writes them for every body every frame.
 *
 * <p><b>Integration</b> ({@link #integrate}) is by <em>semi-implicit (symplectic) Euler</em>: the
 * velocities are updated from the forces first and the positions from the new velocities. The
 * angular velocity is advanced with the <b>implicit gyroscopic step</b> of Catto ("Numerical
 * Methods", GDC 2015): in the body frame a body that is not a sphere would gain energy and tumble
 * out of control under the explicit equation {@code I dw/dt = tau - w x I w}; one Newton step of
 * the implicit equation {@code I (w' - w) + dt w' x I w' = dt tau} keeps it stable at any step size
 * (at the price of a small loss of energy that shows in the tests). The orientation is advanced by
 * the exact rotation about the angular velocity, {@code q' = exp(w dt / 2) q}, and renormalised.
 *
 * <p>A body with an inverse mass of zero (<b>static</b> or kinematic) does not respond to forces or
 * impulses and keeps its velocities. The shape is positioned by the centre of mass: offset the
 * shape by {@link MassProperties#centerOfMass()} when drawing.
 *
 * <p><b>Thread safety.</b> Not thread-safe: a mutable struct.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * RigidBody body = new RigidBody(MassProperties.box(0.5, 0.5, 0.5, 2.0));
 * body.setPose(0.0, 5.0, 0.0, 0.0, 0.0, 0.0, 1.0);
 * for (int i = 0; i < 60; i++) {
 *     body.applyForce(0.0, -9.81 * body.mass(), 0.0);                       // forces are cleared by every step
 *     body.integrate(1.0 / 60);
 * }
 * Vec3d position = body.position();
 * }</pre>
 */
public final class RigidBody {

    /**
     * The position of the centre of mass in the world.
     */
    public double px, py, pz;
    /**
     * The orientation as a unit quaternion {@code (x, y, z, w)}.
     */
    public double qx, qy, qz, qw = 1;
    /**
     * The linear velocity of the centre of mass.
     */
    public double vx, vy, vz;
    /**
     * The angular velocity in the world frame, in radians per second.
     */
    public double wx, wy, wz;
    /**
     * The force accumulated for the next {@link #integrate}, applied at the centre of mass (cleared
     * by it).
     */
    public double fx, fy, fz;
    /**
     * The bias (pseudo) velocity: a velocity that moves the body for one step and is then
     * discarded.
     *
     * <p>The contact solver uses it to push penetrating bodies apart without adding the energy of
     * the correction to the real velocity (split impulses). Cleared by {@link #integrate}.
     */
    public double bvx, bvy, bvz;
    /**
     * The bias angular velocity, see {@link #bvx}.
     */
    public double bwx, bwy, bwz;
    /**
     * The torque accumulated for the next {@link #integrate} (cleared by it).
     */
    public double tx, ty, tz;
    /**
     * Scratch for the world-frame inverse inertia, so that impulses do not allocate (a body is not
     * shared between threads).
     */
    private final double[] scratch6 = new double[6];
    private final double[] scratch9 = new double[9];
    private final double[] scratch3 = new double[3];
    /**
     * The world-frame inverse inertia for the orientation {@code cacheQ*}: it is only recomputed
     * when the orientation changes (NaN never equals, so the first call computes).
     */
    private final double[] worldCache = new double[6];
    private double cacheQx = Double.NaN, cacheQy, cacheQz, cacheQw;

    /**
     * The scratch array of six values for the solver (package-private: not for general use).
     */
    double[] scratch() {
        return scratch6;
    }
    /**
     * Damping of the linear velocity: the velocity is divided by {@code 1 + linearDamping * dt}
     * each step.
     */
    public double linearDamping;
    /**
     * Damping of the angular velocity, like {@link #linearDamping}.
     */
    public double angularDamping;

    private double invMass;
    // the inertia tensor in the body frame and its inverse, as xx, yy, zz, xy, xz, yz
    private final double[] inertia = new double[6], inverseInertia = new double[6];

    /**
     * Creates a static body: infinite mass, at the origin, not rotated.
     */
    public RigidBody() {
    }

    /**
     * Creates a dynamic body with the mass and inertia of {@code properties}, at the origin, not
     * rotated.
     *
     * @param properties the properties; must not be {@code null}
     */
    public RigidBody(MassProperties properties) {
        setMassProperties(properties);
    }

    /**
     * Gives the body the mass and the inertia tensor of {@code properties} (the tensor about the
     * centre of mass, in the body frame).
     *
     * @param properties the properties; must not be {@code null}
     */
    public void setMassProperties(MassProperties properties) {
        invMass = 1.0 / properties.mass();
        cacheQx = Double.NaN; // the cached world inertia is for the old tensor
        Mat3d i = properties.inertia();
        inertia[0] = i.m00();
        inertia[1] = i.m11();
        inertia[2] = i.m22();
        inertia[3] = i.m10();
        inertia[4] = i.m20();
        inertia[5] = i.m21();
        Mat3d inv = properties.inverseInertia();
        inverseInertia[0] = inv.m00();
        inverseInertia[1] = inv.m11();
        inverseInertia[2] = inv.m22();
        inverseInertia[3] = inv.m10();
        inverseInertia[4] = inv.m20();
        inverseInertia[5] = inv.m21();
    }

    /**
     * Makes the body static: it no longer responds to forces and impulses.
     */
    public void makeStatic() {
        invMass = 0;
        cacheQx = Double.NaN;
        java.util.Arrays.fill(inertia, 0);
        java.util.Arrays.fill(inverseInertia, 0);
    }

    /**
     * Returns whether the body is static (zero inverse mass).
     *
     * @return {@code true} if the body is static (zero inverse mass)
     */
    public boolean isStatic() {
        return invMass == 0;
    }

    /**
     * Exposes the inverse of the mass, which is zero for a static body and so lets the solver treat
     * static and dynamic bodies uniformly.
     *
     * @return the inverse of the mass; 0 for a static body
     */
    public double inverseMass() {
        return invMass;
    }

    /**
     * Exposes the mass, which is infinite for a static body.
     *
     * @return the mass, or infinity for a static body
     */
    public double mass() {
        return invMass == 0 ? Double.POSITIVE_INFINITY : 1.0 / invMass;
    }

    /**
     * Places the body: the centre of mass at {@code (x, y, z)} with the orientation
     * {@code (qx, qy, qz, qw)}, which is normalised.
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @param qx the x component of the orientation quaternion
     * @param qy the y component of the orientation quaternion
     * @param qz the z component of the orientation quaternion
     * @param qw the w component of the orientation quaternion
     */
    public void setPose(double x, double y, double z, double qx, double qy, double qz, double qw) {
        px = x;
        py = y;
        pz = z;
        double n = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        this.qx = qx / n;
        this.qy = qy / n;
        this.qz = qz / n;
        this.qw = qw / n;
    }

    /**
     * Exposes the orientation of the body.
     *
     * @return the orientation as a quaternion
     */
    public Quatd orientation() {
        return new Quatd(qx, qy, qz, qw);
    }

    /**
     * Exposes the position of the centre of mass.
     *
     * @return the position of the centre of mass
     */
    public Vec3d position() {
        return new Vec3d(px, py, pz);
    }

    // ------------------------------------------------------------ forces and impulses

    /**
     * Adds a force at the centre of mass for the next step.
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     */
    public void applyForce(double x, double y, double z) {
        fx += x;
        fy += y;
        fz += z;
    }

    /**
     * Adds a force at the world point {@code (x, y, z)}: the force, and its torque about the centre
     * of mass, for the next step.
     *
     * @param fxIn the fx in
     * @param fyIn the fy in
     * @param fzIn the fz in
     * @param x the x component
     * @param y the y component
     * @param z the z component
     */
    public void applyForceAtPoint(double fxIn, double fyIn, double fzIn, double x, double y, double z) {
        fx += fxIn;
        fy += fyIn;
        fz += fzIn;
        double rx = x - px, ry = y - py, rz = z - pz;
        tx += ry * fzIn - rz * fyIn;
        ty += rz * fxIn - rx * fzIn;
        tz += rx * fyIn - ry * fxIn;
    }

    /**
     * Adds a torque for the next step.
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     */
    public void applyTorque(double x, double y, double z) {
        tx += x;
        ty += y;
        tz += z;
    }

    /**
     * Changes the velocities at once by an impulse {@code J} at the centre of mass:
     * {@code v += J / m}.
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     */
    public void applyImpulse(double x, double y, double z) {
        vx += x * invMass;
        vy += y * invMass;
        vz += z * invMass;
    }

    /**
     * Changes the velocities at once by an impulse at the world point {@code (x, y, z)}:
     * {@code v += J / m} and {@code w += I^-1 (r x J)}.
     *
     * @param jx the x component of the impulse
     * @param jy the y component of the impulse
     * @param jz the z component of the impulse
     * @param x the x component
     * @param y the y component
     * @param z the z component
     */
    public void applyImpulseAtPoint(double jx, double jy, double jz, double x, double y, double z) {
        vx += jx * invMass;
        vy += jy * invMass;
        vz += jz * invMass;
        double rx = x - px, ry = y - py, rz = z - pz;
        applyAngularImpulse(ry * jz - rz * jy, rz * jx - rx * jz, rx * jy - ry * jx);
    }

    /**
     * Changes the angular velocity at once by an angular impulse: {@code w += I^-1 L} with the
     * inverse tensor in the world frame.
     *
     * @param lx the x component of the angular impulse
     * @param ly the y component of the angular impulse
     * @param lz the z component of the angular impulse
     */
    public void applyAngularImpulse(double lx, double ly, double lz) {
        double[] iw = worldInverseInertia(scratch6);
        wx += iw[0] * lx + iw[3] * ly + iw[4] * lz;
        wy += iw[3] * lx + iw[1] * ly + iw[5] * lz;
        wz += iw[4] * lx + iw[5] * ly + iw[2] * lz;
    }

    /**
     * Changes the bias velocities like {@link #applyImpulseAtPoint}; they move the body for the
     * next step only.
     *
     * @param jx the x component of the impulse
     * @param jy the y component of the impulse
     * @param jz the z component of the impulse
     * @param x the x component
     * @param y the y component
     * @param z the z component
     */
    public void applyBiasImpulseAtPoint(double jx, double jy, double jz, double x, double y, double z) {
        bvx += jx * invMass;
        bvy += jy * invMass;
        bvz += jz * invMass;
        double rx = x - px, ry = y - py, rz = z - pz;
        double lx = ry * jz - rz * jy, ly = rz * jx - rx * jz, lz = rx * jy - ry * jx;
        double[] iw = worldInverseInertia(scratch6);
        bwx += iw[0] * lx + iw[3] * ly + iw[4] * lz;
        bwy += iw[3] * lx + iw[1] * ly + iw[5] * lz;
        bwz += iw[4] * lx + iw[5] * ly + iw[2] * lz;
    }

    // ------------------------------------------------------------ queries

    /**
     * Computes the velocity of the point of the body that is at the world point {@code (x, y, z)}:
     * {@code v + w x r}, written to {@code out[0 .. 3)}.
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @param out receives the result in {@code [0, 3)}
     */
    public void pointVelocity(double x, double y, double z, double[] out) {
        double rx = x - px, ry = y - py, rz = z - pz;
        out[0] = vx + wy * rz - wz * ry;
        out[1] = vy + wz * rx - wx * rz;
        out[2] = vz + wx * ry - wy * rx;
    }

    /**
     * Checks that the state of the body is made of finite numbers.
     *
     * <p>{@link #integrate} does not check its input, and one NaN in a force, a velocity or the
     * orientation spreads to the whole state and stays there; call this after a step, or before
     * a body is added to a world, to find the body that went wrong.
     *
     * @return {@code true} if the position, the orientation and both velocities are finite
     */
    public boolean isFinite() {
        return Double.isFinite(px) && Double.isFinite(py) && Double.isFinite(pz) && Double.isFinite(qx) && Double.isFinite(qy) && Double.isFinite(qz) && Double.isFinite(qw)
                && Double.isFinite(vx) && Double.isFinite(vy) && Double.isFinite(vz) && Double.isFinite(wx) && Double.isFinite(wy) && Double.isFinite(wz);
    }

    /**
     * Sums the translational and the rotational kinetic energy; static bodies contribute zero.
     *
     * @return the kinetic energy: {@code m |v|^2 / 2 + w . I w / 2} (infinite mass bodies report 0:
     *     they take no part in the energy)
     */
    public double kineticEnergy() {
        if (invMass == 0) {
            return 0;
        }
        double[] w = bodyAngularVelocity(scratch3);
        double rot = 0.5 * (inertia[0] * w[0] * w[0] + inertia[1] * w[1] * w[1] + inertia[2] * w[2] * w[2] + 2 * (inertia[3] * w[0] * w[1] + inertia[4] * w[0] * w[2] + inertia[5] * w[1] * w[2]));
        return 0.5 * (vx * vx + vy * vy + vz * vz) / invMass + rot;
    }

    /**
     * Computes the angular momentum about the centre of mass in the world frame, {@code R I R^T w},
     * written to {@code out[0 .. 3)}.
     *
     * @param out receives the result
     */
    public void angularMomentum(double[] out) {
        double[] w = bodyAngularVelocity(scratch3);
        double lx = inertia[0] * w[0] + inertia[3] * w[1] + inertia[4] * w[2];
        double ly = inertia[3] * w[0] + inertia[1] * w[1] + inertia[5] * w[2];
        double lz = inertia[4] * w[0] + inertia[5] * w[1] + inertia[2] * w[2];
        toWorld(lx, ly, lz, out);
    }

    /**
     * Rotates the inverse inertia tensor from the body frame into the world frame and writes its
     * six distinct elements to a caller-supplied array, so that nothing is allocated.
     *
     * @param out receives the result
     * @return the inverse inertia tensor in the world frame, {@code R I^-1 R^T}, as
     *     {@code xx, yy, zz, xy, xz, yz} into {@code out} (and returned)
     */
    public double[] worldInverseInertia(double[] out) {
        double[] c = worldCache;
        if (qx != cacheQx || qy != cacheQy || qz != cacheQz || qw != cacheQw) { // the orientation is a public field: the cache is keyed by its value
            computeWorldInverseInertia(c);
            cacheQx = qx;
            cacheQy = qy;
            cacheQz = qz;
            cacheQw = qw;
        }
        System.arraycopy(c, 0, out, 0, 6);
        return out;
    }

    private void computeWorldInverseInertia(double[] out) {
        double[] r = rotationMatrix(scratch9);
        double dxx = inverseInertia[0], dyy = inverseInertia[1], dzz = inverseInertia[2], dxy = inverseInertia[3], dxz = inverseInertia[4], dyz = inverseInertia[5];
        // M = R D (row-major R), then out = M R^T
        double m00 = r[0] * dxx + r[1] * dxy + r[2] * dxz, m01 = r[0] * dxy + r[1] * dyy + r[2] * dyz, m02 = r[0] * dxz + r[1] * dyz + r[2] * dzz;
        double m10 = r[3] * dxx + r[4] * dxy + r[5] * dxz, m11 = r[3] * dxy + r[4] * dyy + r[5] * dyz, m12 = r[3] * dxz + r[4] * dyz + r[5] * dzz;
        double m20 = r[6] * dxx + r[7] * dxy + r[8] * dxz, m21 = r[6] * dxy + r[7] * dyy + r[8] * dyz, m22 = r[6] * dxz + r[7] * dyz + r[8] * dzz;
        out[0] = m00 * r[0] + m01 * r[1] + m02 * r[2];
        out[1] = m10 * r[3] + m11 * r[4] + m12 * r[5];
        out[2] = m20 * r[6] + m21 * r[7] + m22 * r[8];
        out[3] = m00 * r[3] + m01 * r[4] + m02 * r[5];
        out[4] = m00 * r[6] + m01 * r[7] + m02 * r[8];
        out[5] = m10 * r[6] + m11 * r[7] + m12 * r[8];
    }

    /**
     * Converts the orientation to a row-major rotation matrix and writes it to a caller-supplied
     * array, so that nothing is allocated.
     *
     * @param out receives the result in {@code [0, 9)}
     * @return the rotation matrix of the orientation, row-major, into {@code out[0 .. 9)} (and
     *     returned)
     */
    public double[] rotationMatrix(double[] out) {
        double xx = qx * qx, yy = qy * qy, zz = qz * qz, xy = qx * qy, xz = qx * qz, yz = qy * qz, wx2 = qw * qx, wy2 = qw * qy, wz2 = qw * qz;
        out[0] = 1 - 2 * (yy + zz);
        out[1] = 2 * (xy - wz2);
        out[2] = 2 * (xz + wy2);
        out[3] = 2 * (xy + wz2);
        out[4] = 1 - 2 * (xx + zz);
        out[5] = 2 * (yz - wx2);
        out[6] = 2 * (xz - wy2);
        out[7] = 2 * (yz + wx2);
        out[8] = 1 - 2 * (xx + yy);
        return out;
    }

    private double[] bodyAngularVelocity(double[] out) {
        double[] r = rotationMatrix(scratch9);
        // R^T w
        out[0] = r[0] * wx + r[3] * wy + r[6] * wz;
        out[1] = r[1] * wx + r[4] * wy + r[7] * wz;
        out[2] = r[2] * wx + r[5] * wy + r[8] * wz;
        return out;
    }

    private void toWorld(double x, double y, double z, double[] out) {
        double[] r = rotationMatrix(scratch9);
        out[0] = r[0] * x + r[1] * y + r[2] * z;
        out[1] = r[3] * x + r[4] * y + r[5] * z;
        out[2] = r[6] * x + r[7] * y + r[8] * z;
    }

    // ------------------------------------------------------------ integration

    /**
     * Advances the body by {@code dt} seconds under the accumulated force and torque (which are
     * then cleared) and the damping.
     *
     * <p>A static body only has its accumulators cleared. See the class comment for the method.
     *
     * @param dt the time step in seconds
     */
    public void integrate(double dt) {
        if (invMass == 0) {
            fx = fy = fz = tx = ty = tz = 0;
            return;
        }
        vx += fx * invMass * dt;
        vy += fy * invMass * dt;
        vz += fz * invMass * dt;
        double damp = 1.0 / (1.0 + linearDamping * dt);
        vx *= damp;
        vy *= damp;
        vz *= damp;
        // the implicit gyroscopic step in the body frame
        double[] r = rotationMatrix(scratch9);
        double w0 = r[0] * wx + r[3] * wy + r[6] * wz, w1 = r[1] * wx + r[4] * wy + r[7] * wz, w2 = r[2] * wx + r[5] * wy + r[8] * wz;
        double tau0 = r[0] * tx + r[3] * ty + r[6] * tz, tau1 = r[1] * tx + r[4] * ty + r[7] * tz, tau2 = r[2] * tx + r[5] * ty + r[8] * tz;
        double ixx = inertia[0], iyy = inertia[1], izz = inertia[2], ixy = inertia[3], ixz = inertia[4], iyz = inertia[5];
        double iw0 = ixx * w0 + ixy * w1 + ixz * w2, iw1 = ixy * w0 + iyy * w1 + iyz * w2, iw2 = ixz * w0 + iyz * w1 + izz * w2;
        // f(w') = I (w' - w) + dt w' x (I w') - dt tau, at w' = w: dt (w x I w) - dt tau
        double f0 = dt * (w1 * iw2 - w2 * iw1 - tau0), f1 = dt * (w2 * iw0 - w0 * iw2 - tau1), f2 = dt * (w0 * iw1 - w1 * iw0 - tau2);
        // the Jacobian: I + dt (skew(w) I - skew(I w)), skew(v) = [[0, -v2, v1], [v2, 0, -v0], [-v1, v0, 0]]
        double s00 = -w2 * ixy + w1 * ixz, s01 = -w2 * iyy + w1 * iyz, s02 = -w2 * iyz + w1 * izz;
        double s10 = w2 * ixx - w0 * ixz, s11 = w2 * ixy - w0 * iyz, s12 = w2 * ixz - w0 * izz;
        double s20 = -w1 * ixx + w0 * ixy, s21 = -w1 * ixy + w0 * iyy, s22 = -w1 * ixz + w0 * iyz;
        // skew(I w)
        double k00 = 0, k01 = -iw2, k02 = iw1, k10 = iw2, k11 = 0, k12 = -iw0, k20 = -iw1, k21 = iw0, k22 = 0;
        double j00 = ixx + dt * (s00 - k00), j01 = ixy + dt * (s01 - k01), j02 = ixz + dt * (s02 - k02);
        double j10 = ixy + dt * (s10 - k10), j11 = iyy + dt * (s11 - k11), j12 = iyz + dt * (s12 - k12);
        double j20 = ixz + dt * (s20 - k20), j21 = iyz + dt * (s21 - k21), j22 = izz + dt * (s22 - k22);
        // solve J delta = -f by Cramer's rule
        double det = j00 * (j11 * j22 - j12 * j21) - j01 * (j10 * j22 - j12 * j20) + j02 * (j10 * j21 - j11 * j20);
        double r0 = -f0, r1 = -f1, r2 = -f2;
        double d0 = (r0 * (j11 * j22 - j12 * j21) - j01 * (r1 * j22 - j12 * r2) + j02 * (r1 * j21 - j11 * r2)) / det;
        double d1 = (j00 * (r1 * j22 - j12 * r2) - r0 * (j10 * j22 - j12 * j20) + j02 * (j10 * r2 - r1 * j20)) / det;
        double d2 = (j00 * (j11 * r2 - r1 * j21) - j01 * (j10 * r2 - r1 * j20) + r0 * (j10 * j21 - j11 * j20)) / det;
        if (det != 0) { // a singular Jacobian leaves the angular velocity as it is instead of turning it into NaN
            w0 += d0;
            w1 += d1;
            w2 += d2;
        }
        // back to the world frame
        wx = r[0] * w0 + r[1] * w1 + r[2] * w2;
        wy = r[3] * w0 + r[4] * w1 + r[5] * w2;
        wz = r[6] * w0 + r[7] * w1 + r[8] * w2;
        double adamp = 1.0 / (1.0 + angularDamping * dt);
        wx *= adamp;
        wy *= adamp;
        wz *= adamp;
        px += (vx + bvx) * dt;
        py += (vy + bvy) * dt;
        pz += (vz + bvz) * dt;
        // the exact rotation about the angular velocity (with the bias part), applied to the orientation
        double ox = wx + bwx, oy = wy + bwy, oz = wz + bwz;
        double wl = Math.sqrt(ox * ox + oy * oy + oz * oz), half = 0.5 * wl * dt;
        double s = wl > 1e-300 ? Math.sin(half) / wl : 0.5 * dt, c = Math.cos(half);
        double dx = ox * s, dy = oy * s, dz = oz * s;
        double nx = c * qx + dx * qw + dy * qz - dz * qy;
        double ny = c * qy - dx * qz + dy * qw + dz * qx;
        double nz = c * qz + dx * qy - dy * qx + dz * qw;
        double nw = c * qw - dx * qx - dy * qy - dz * qz;
        double n = Math.sqrt(nx * nx + ny * ny + nz * nz + nw * nw);
        qx = nx / n;
        qy = ny / n;
        qz = nz / n;
        qw = nw / n;
        fx = fy = fz = tx = ty = tz = 0;
        bvx = bvy = bvz = bwx = bwy = bwz = 0;
    }
}
