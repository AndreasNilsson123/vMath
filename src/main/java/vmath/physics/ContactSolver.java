package vmath.physics;

/**
 * The impulse math of contacts between two {@link RigidBody}s: relative velocity at a contact point, the effective mass that the contact sees, the impulse that stops (or bounces) an
 * approach along the normal, Coulomb friction along two tangents, and a sequential-impulse solver for a whole {@link ContactManifold}. Only the math: finding the contacts, the
 * broad phase, islands and sleeping are the job of an engine.
 *
 * <p><b>The normal constraint.</b> For a contact point {@code p} with normal {@code n} (from body A to body B) the speed with which the bodies separate there is
 * {@code vn = (v_B(p) - v_A(p)) . n}, where {@code v(p) = v + w x r} and {@code r} is the point relative to the centre of mass. An impulse {@code J = lambda n} on B and {@code -J} on A changes
 * it by {@code lambda / k} with the <em>effective mass</em> {@code 1 / k},
 * {@code k = 1/m_A + 1/m_B + ((r_A x n) . I_A^-1 (r_A x n)) + ((r_B x n) . I_B^-1 (r_B x n))}. The solver wants {@code vn >= s}, with {@code s} the target separating speed made of the
 * restitution (the bounce: {@code e * (-vn_0)} when the bodies approach faster than a threshold) and the position correction (the Baumgarte term {@code beta / dt * max(0, depth - slop)}
 * that pushes overlapping bodies apart over a few frames); the accumulated impulse of a point is clamped to be non-negative, since contacts push and never pull. A speculative contact (negative
 * depth) lets the bodies approach by exactly the gap in one step.
 *
 * <p><b>Friction</b> acts along two tangents perpendicular to the normal ({@link #tangentBasis}): each accumulated friction impulse is clamped to {@code mu} times the accumulated normal
 * impulse of the point (the friction pyramid; the cone would clamp the length of the pair instead).
 *
 * <p><b>Thread safety.</b> Stateless apart from the bodies and manifold you pass in, which it modifies: do not share them between threads.
 */
public final class ContactSolver {

    /** The settings of {@link #solve}: a struct of public fields with the usual defaults. */
    public static final class Params {
        /** The coefficient of restitution of the pair: 0 stops the approach, 1 reverses it elastically. Default 0. */
        public double restitution;
        /** The coefficient of friction of the pair. Default 0.5. */
        public double friction = 0.5;
        /** The share of the penetration that is removed per second as a speed, times the time step: the Baumgarte factor, usually 0.1 to 0.3. Default 0.2. */
        public double beta = 0.2;
        /** The penetration that is tolerated without correction, which keeps resting contacts from jittering. Default 0.005. */
        public double slop = 0.005;
        /** The approach speed below which there is no bounce, which keeps resting bodies from bouncing forever. Default 1. */
        public double restitutionThreshold = 1.0;
        /**
         * Whether the penetration is removed with split impulses: the correction moves the bodies through bias velocities that are discarded after the step, so that it adds no energy to the
         * real velocities (a box on the ground no longer creeps and rolls). When off, the correction is added to the normal velocity target (plain Baumgarte). Default true.
         */
        public boolean splitImpulse = true;
        /** The number of sweeps over the contact points. Default 10. */
        public int iterations = 10;
        private final double[] t1 = new double[3], t2 = new double[3];

        /** The default settings. */
        public Params() {
        }
    }

    private ContactSolver() {
    }

    /** The friction coefficient of a pair of surfaces: the geometric mean, the usual rule. */
    public static double combineFriction(double a, double b) {
        return Math.sqrt(a * b);
    }

    /** The restitution of a pair of surfaces: the larger of the two, the usual rule. */
    public static double combineRestitution(double a, double b) {
        return Math.max(a, b);
    }

    /**
     * Two unit vectors {@code t1}, {@code t2} that are perpendicular to the unit vector {@code n} and to each other (the continuous construction of Duff et al.), written to the arrays'
     * first three elements.
     */
    public static void tangentBasis(double nx, double ny, double nz, double[] t1, double[] t2) {
        double sign = Math.copySign(1.0, nz);
        double c = -1.0 / (sign + nz), d = nx * ny * c;
        t1[0] = 1.0 + sign * nx * nx * c;
        t1[1] = sign * d;
        t1[2] = -sign * nx;
        t2[0] = d;
        t2[1] = sign + ny * ny * c;
        t2[2] = -ny;
    }

    /** The speed with which the bodies separate at the world point {@code (px, py, pz)} along the unit normal {@code n} pointing from A to B: {@code (v_B(p) - v_A(p)) . n}; negative when they approach. */
    public static double relativeNormalVelocity(RigidBody a, RigidBody b, double px, double py, double pz, double nx, double ny, double nz) {
        double rax = px - a.px, ray = py - a.py, raz = pz - a.pz, rbx = px - b.px, rby = py - b.py, rbz = pz - b.pz;
        double vx = (b.vx + b.wy * rbz - b.wz * rby) - (a.vx + a.wy * raz - a.wz * ray);
        double vy = (b.vy + b.wz * rbx - b.wx * rbz) - (a.vy + a.wz * rax - a.wx * raz);
        double vz = (b.vz + b.wx * rby - b.wy * rbx) - (a.vz + a.wx * ray - a.wy * rax);
        return vx * nx + vy * ny + vz * nz;
    }

    /** As {@link #relativeNormalVelocity} but for the bias velocities of the split impulse. */
    private static double relativeBiasVelocity(RigidBody a, RigidBody b, double px, double py, double pz, double nx, double ny, double nz) {
        double rax = px - a.px, ray = py - a.py, raz = pz - a.pz, rbx = px - b.px, rby = py - b.py, rbz = pz - b.pz;
        double vx = (b.bvx + b.bwy * rbz - b.bwz * rby) - (a.bvx + a.bwy * raz - a.bwz * ray);
        double vy = (b.bvy + b.bwz * rbx - b.bwx * rbz) - (a.bvy + a.bwz * rax - a.bwx * raz);
        double vz = (b.bvz + b.bwx * rby - b.bwy * rbx) - (a.bvz + a.bwx * ray - a.bwy * rax);
        return vx * nx + vy * ny + vz * nz;
    }

    /**
     * The effective mass for impulses along the unit direction {@code d} at the world point {@code p}: {@code 1 / (1/m_A + 1/m_B + (r_A x d) . I_A^-1 (r_A x d) + (r_B x d) . I_B^-1 (r_B x d))}.
     * Zero when both bodies are static.
     */
    public static double effectiveMass(RigidBody a, RigidBody b, double px, double py, double pz, double dx, double dy, double dz) {
        double k = a.inverseMass() + b.inverseMass() + angularTerm(a, px, py, pz, dx, dy, dz) + angularTerm(b, px, py, pz, dx, dy, dz);
        return k > 0 ? 1.0 / k : 0.0;
    }

    private static double angularTerm(RigidBody body, double px, double py, double pz, double dx, double dy, double dz) {
        if (body.isStatic()) {
            return 0;
        }
        double rx = px - body.px, ry = py - body.py, rz = pz - body.pz;
        double cx = ry * dz - rz * dy, cy = rz * dx - rx * dz, cz = rx * dy - ry * dx;
        double[] iw = body.worldInverseInertia(body.scratch());
        double ix = iw[0] * cx + iw[3] * cy + iw[4] * cz, iy = iw[3] * cx + iw[1] * cy + iw[5] * cz, iz = iw[4] * cx + iw[5] * cy + iw[2] * cz;
        return cx * ix + cy * iy + cz * iz;
    }

    /**
     * One normal impulse step at the world point {@code p} with the unit normal {@code n} (from A to B): changes the velocities of the bodies so that the separating speed there becomes
     * {@code targetSeparatingVelocity} as far as the clamping allows (the accumulated impulse stays non-negative), and returns the new accumulated impulse. Pass the accumulated impulse of earlier
     * calls for the same point, or 0.
     */
    public static double solveNormal(RigidBody a, RigidBody b, double px, double py, double pz, double nx, double ny, double nz, double targetSeparatingVelocity, double accumulated) {
        double k = effectiveMass(a, b, px, py, pz, nx, ny, nz);
        double vn = relativeNormalVelocity(a, b, px, py, pz, nx, ny, nz);
        double lambda = (targetSeparatingVelocity - vn) * k;
        double next = Math.max(accumulated + lambda, 0.0);
        lambda = next - accumulated;
        apply(a, b, px, py, pz, nx * lambda, ny * lambda, nz * lambda);
        return next;
    }

    /**
     * One friction impulse step along the unit tangent {@code t} at the point {@code p}: removes the sliding speed along {@code t} as far as the friction limit {@code maxImpulse}
     * ({@code mu} times the normal impulse) allows, and returns the new accumulated friction impulse, which stays within {@code [-maxImpulse, maxImpulse]}.
     */
    public static double solveFriction(RigidBody a, RigidBody b, double px, double py, double pz, double tx, double ty, double tz, double maxImpulse, double accumulated) {
        double k = effectiveMass(a, b, px, py, pz, tx, ty, tz);
        double vt = relativeNormalVelocity(a, b, px, py, pz, tx, ty, tz); // the same formula along t
        double lambda = -vt * k;
        double next = Math.max(-maxImpulse, Math.min(maxImpulse, accumulated + lambda));
        lambda = next - accumulated;
        apply(a, b, px, py, pz, tx * lambda, ty * lambda, tz * lambda);
        return next;
    }

    /** Applies the impulse {@code J} at the world point {@code p} to B and {@code -J} to A. */
    public static void apply(RigidBody a, RigidBody b, double px, double py, double pz, double jx, double jy, double jz) {
        a.applyImpulseAtPoint(-jx, -jy, -jz, px, py, pz);
        b.applyImpulseAtPoint(jx, jy, jz, px, py, pz);
    }

    /**
     * Solves the contact of one manifold by sequential impulses over {@code dt}: {@link #prepare}, {@code params.iterations} calls of {@link #sweep}, then {@link #correct}. Use this for a pair that is
     * independent of the others; for bodies that touch several others (a stack, a pile) use {@link #solveAll}, because the contacts must be solved together to converge.
     */
    public static void solve(RigidBody a, RigidBody b, ContactManifold m, Params params, double dt) {
        prepare(a, b, m, params, dt);
        for (int it = 0; it < params.iterations; it++) {
            sweep(a, b, m, params);
        }
        correct(a, b, m, params);
    }

    /**
     * Solves a set of manifolds together: {@code manifolds[k]} is the contact between {@code a[k]} and {@code b[k]} for {@code k < count}. All are prepared (warm started), then
     * {@code params.iterations} sweeps run over the whole set, so that impulses propagate through stacks, and the penetration is removed last. The manifolds keep their impulses for the next frame.
     */
    public static void solveAll(RigidBody[] a, RigidBody[] b, ContactManifold[] manifolds, int count, Params params, double dt) {
        for (int k = 0; k < count; k++) {
            prepare(a[k], b[k], manifolds[k], params, dt);
        }
        for (int it = 0; it < params.iterations; it++) {
            for (int k = 0; k < count; k++) {
                sweep(a[k], b[k], manifolds[k], params);
            }
        }
        for (int it = 0; params.splitImpulse && it < params.iterations; it++) {
            boolean any = false;
            for (int k = 0; k < count; k++) {
                any |= biasSweep(a[k], b[k], manifolds[k]);
            }
            if (!any) {
                break;
            }
        }
    }

    /**
     * Prepares the manifold for the sweeps: computes the velocity targets of the normal constraints (restitution from the approach speed before any solving, speculative gaps, and the
     * penetration push when split impulses are off) and applies the impulses stored from the last frame as a warm start.
     */
    public static void prepare(RigidBody a, RigidBody b, ContactManifold m, Params params, double dt) {
        int n = m.count();
        if (n == 0 || (a.isStatic() && b.isStatic())) {
            return;
        }
        double nx = m.nx, ny = m.ny, nz = m.nz;
        double[] t1 = params.t1, t2 = params.t2;
        tangentBasis(nx, ny, nz, t1, t2);
        for (int i = 0; i < n; i++) {
            double px = m.point(i, 0), py = m.point(i, 1), pz = m.point(i, 2);
            double vn = relativeNormalVelocity(a, b, px, py, pz, nx, ny, nz);
            double depth = m.depth(i);
            double push = depth > 0 ? params.beta / dt * Math.max(0.0, depth - params.slop) : 0.0;
            double s = depth > 0 ? 0.0 : depth / dt; // a gap lets the bodies approach by exactly the gap in one step
            if (params.splitImpulse) {
                m.bias[i] = push;
            } else {
                s += push;
                m.bias[i] = 0;
            }
            if (depth > -1e-12 && vn < -params.restitutionThreshold) {
                s += -params.restitution * vn;
            }
            m.target[i] = s;
            m.biasImpulse[i] = 0;
            double jn = m.normalImpulse(i), j1 = m.tangentImpulse1(i), j2 = m.tangentImpulse2(i);
            apply(a, b, px, py, pz, nx * jn + t1[0] * j1 + t2[0] * j2, ny * jn + t1[1] * j1 + t2[1] * j2, nz * jn + t1[2] * j1 + t2[2] * j2);
        }
    }

    /** One sweep over the points of a prepared manifold: friction first, then the normal constraint, point by point. */
    public static void sweep(RigidBody a, RigidBody b, ContactManifold m, Params params) {
        int n = m.count();
        if (n == 0 || (a.isStatic() && b.isStatic())) {
            return;
        }
        double nx = m.nx, ny = m.ny, nz = m.nz;
        double[] t1 = params.t1, t2 = params.t2;
        tangentBasis(nx, ny, nz, t1, t2);
        for (int i = 0; i < n; i++) {
            double px = m.point(i, 0), py = m.point(i, 1), pz = m.point(i, 2);
            double limit = params.friction * m.normalImpulse(i);
            double j1 = solveFriction(a, b, px, py, pz, t1[0], t1[1], t1[2], limit, m.tangentImpulse1(i));
            double j2 = solveFriction(a, b, px, py, pz, t2[0], t2[1], t2[2], limit, m.tangentImpulse2(i));
            m.setTangentImpulses(i, j1, j2);
            m.setNormalImpulse(i, solveNormal(a, b, px, py, pz, nx, ny, nz, m.target[i], m.normalImpulse(i)));
        }
    }

    /** Removes the penetration of a solved manifold with split impulses (does nothing when {@link Params#splitImpulse} is off): the bodies get bias velocities that {@link RigidBody#integrate} uses once. */
    public static void correct(RigidBody a, RigidBody b, ContactManifold m, Params params) {
        if (!params.splitImpulse) {
            return;
        }
        for (int it = 0; it < params.iterations && biasSweep(a, b, m); it++) {
            // sweep until the iterations are used up or no point has anything to push out
        }
    }

    private static boolean biasSweep(RigidBody a, RigidBody b, ContactManifold m) {
        int n = m.count();
        if (n == 0 || (a.isStatic() && b.isStatic())) {
            return false;
        }
        double nx = m.nx, ny = m.ny, nz = m.nz;
        boolean any = false;
        for (int i = 0; i < n; i++) {
            double bias = m.bias[i];
            if (bias <= 0) {
                continue;
            }
            any = true;
            double px = m.point(i, 0), py = m.point(i, 1), pz = m.point(i, 2);
            double lambda = (bias - relativeBiasVelocity(a, b, px, py, pz, nx, ny, nz)) * effectiveMass(a, b, px, py, pz, nx, ny, nz);
            double next = Math.max(m.biasImpulse[i] + lambda, 0.0);
            lambda = next - m.biasImpulse[i];
            m.biasImpulse[i] = next;
            a.applyBiasImpulseAtPoint(-nx * lambda, -ny * lambda, -nz * lambda, px, py, pz);
            b.applyBiasImpulseAtPoint(nx * lambda, ny * lambda, nz * lambda, px, py, pz);
        }
        return any;
    }
}
