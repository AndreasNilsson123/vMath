package vmath.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Rnd;
import vmath.core.Vec3d;

/**
 * QA-1: the impulse math of {@link ContactSolver} against the formulas of its class comment, with bodies of full, rotated inertia: the relative velocity at a
 * point, the effective mass, the targets that {@code prepare} sets (bounce, speculative gap, position correction), the carrying of impulses into the step, and
 * the split impulse that removes penetration without touching the real velocities.
 */
class ContactSolverLawsTest {

    private final SplittableRandom rng = new SplittableRandom(Rnd.SEED);

    private RigidBody body(double mass) {
        RigidBody b = new RigidBody(MassProperties.of(mass, new Vec3d(0, 0, 0), 2.0, 3.1, 4.3, 0.3, -0.2, 0.15));
        b.setPose(rng.nextDouble(-2, 2), rng.nextDouble(-2, 2), rng.nextDouble(-2, 2), rng.nextDouble(-1, 1), rng.nextDouble(-1, 1), rng.nextDouble(-1, 1), rng.nextDouble(-1, 1) + 0.1);
        b.vx = rng.nextDouble(-2, 2);
        b.vy = rng.nextDouble(-2, 2);
        b.vz = rng.nextDouble(-2, 2);
        b.wx = rng.nextDouble(-2, 2);
        b.wy = rng.nextDouble(-2, 2);
        b.wz = rng.nextDouble(-2, 2);
        return b;
    }

    private static double[] unit(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return new double[] {x / l, y / l, z / l};
    }

    private static double[] velocityAt(RigidBody b, double[] p, boolean bias) {
        double[] r = {p[0] - b.px, p[1] - b.py, p[2] - b.pz};
        double[] v = bias ? new double[] {b.bvx, b.bvy, b.bvz} : new double[] {b.vx, b.vy, b.vz};
        double[] w = bias ? new double[] {b.bwx, b.bwy, b.bwz} : new double[] {b.wx, b.wy, b.wz};
        return new double[] {v[0] + w[1] * r[2] - w[2] * r[1], v[1] + w[2] * r[0] - w[0] * r[2], v[2] + w[0] * r[1] - w[1] * r[0]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /** {@code (r x d) . I^-1 (r x d)} with the world inverse inertia of the body. */
    private static double angular(RigidBody b, double[] p, double[] d) {
        if (b.isStatic()) {
            return 0;
        }
        double[] r = {p[0] - b.px, p[1] - b.py, p[2] - b.pz};
        double[] c = {r[1] * d[2] - r[2] * d[1], r[2] * d[0] - r[0] * d[2], r[0] * d[1] - r[1] * d[0]};
        double[] iw = b.worldInverseInertia(new double[6]);
        double[] ic = {iw[0] * c[0] + iw[3] * c[1] + iw[4] * c[2], iw[3] * c[0] + iw[1] * c[1] + iw[5] * c[2], iw[4] * c[0] + iw[5] * c[1] + iw[2] * c[2]};
        return dot(c, ic);
    }

    @Test
    void theRelativeVelocityAndTheEffectiveMassFollowTheFormulas() {
        for (int trial = 0; trial < 100; trial++) {
            RigidBody a = body(2.5), b = body(rng.nextBoolean() ? 6.0 : 0.7);
            if (trial % 5 == 0) {
                a.makeStatic();
            }
            double[] p = {rng.nextDouble(-2, 2), rng.nextDouble(-2, 2), rng.nextDouble(-2, 2)};
            double[] n = unit(rng.nextDouble(-1, 1), rng.nextDouble(-1, 1), rng.nextDouble(-1, 1));
            double[] va = velocityAt(a, p, false), vb = velocityAt(b, p, false);
            double expectedVn = (vb[0] - va[0]) * n[0] + (vb[1] - va[1]) * n[1] + (vb[2] - va[2]) * n[2];
            // a static body is a body that does not move: its stored velocity is not part of the contact
            if (a.isStatic()) {
                expectedVn = (vb[0] - (a.vx + a.wy * (p[2] - a.pz) - a.wz * (p[1] - a.py))) * n[0] + (vb[1] - (a.vy + a.wz * (p[0] - a.px) - a.wx * (p[2] - a.pz))) * n[1]
                        + (vb[2] - (a.vz + a.wx * (p[1] - a.py) - a.wy * (p[0] - a.px))) * n[2];
            }
            assertEquals(expectedVn, ContactSolver.relativeNormalVelocity(a, b, p[0], p[1], p[2], n[0], n[1], n[2]), 1e-12, "trial " + trial);
            double k = a.inverseMass() + b.inverseMass() + angular(a, p, n) + angular(b, p, n);
            assertEquals(1.0 / k, ContactSolver.effectiveMass(a, b, p[0], p[1], p[2], n[0], n[1], n[2]), 1e-10 / k, "trial " + trial);
        }
        RigidBody s1 = new RigidBody(), s2 = new RigidBody();
        s1.makeStatic();
        s2.makeStatic();
        assertEquals(0.0, ContactSolver.effectiveMass(s1, s2, 0, 0, 0, 0, 1, 0), 0.0, "two static bodies: no response");
    }

    @Test
    void theNormalAndFrictionImpulsesStopTheApproachAndAreClamped() {
        for (int trial = 0; trial < 60; trial++) {
            RigidBody a = body(2.5), b = body(4.0);
            double[] p = {rng.nextDouble(-2, 2), rng.nextDouble(-2, 2), rng.nextDouble(-2, 2)};
            double[] n = unit(rng.nextDouble(-1, 1), rng.nextDouble(-1, 1), rng.nextDouble(-1, 1));
            double vn = ContactSolver.relativeNormalVelocity(a, b, p[0], p[1], p[2], n[0], n[1], n[2]);
            double target = rng.nextDouble(0, 1);
            double k = ContactSolver.effectiveMass(a, b, p[0], p[1], p[2], n[0], n[1], n[2]);
            double next = ContactSolver.solveNormal(a, b, p[0], p[1], p[2], n[0], n[1], n[2], target, 0.0);
            double lambda = (target - vn) * k;
            assertEquals(Math.max(lambda, 0.0), next, 1e-10, "the accumulated impulse is not negative");
            if (lambda > 0) {
                assertEquals(target, ContactSolver.relativeNormalVelocity(a, b, p[0], p[1], p[2], n[0], n[1], n[2]), 1e-10, "the approach now has the target speed");
            }
            // friction: clamped to the limit in both directions
            double[] t = unit(n[1], -n[0], 0.3);
            double lim = 0.05;
            double f = ContactSolver.solveFriction(a, b, p[0], p[1], p[2], t[0], t[1], t[2], lim, 0.0);
            assertTrue(Math.abs(f) <= lim + 1e-15);
            double g = ContactSolver.solveFriction(a, b, p[0], p[1], p[2], t[0], t[1], t[2], 100.0, 0.0);
            double vt = ContactSolver.relativeNormalVelocity(a, b, p[0], p[1], p[2], t[0], t[1], t[2]);
            assertEquals(0.0, vt, 1e-9 * (1 + Math.abs(g)), "unclamped friction stops the sliding");
        }
    }

    @Test
    void prepareSetsTheTargetsOfBounceGapAndCorrectionAndAppliesTheStoredImpulses() {
        ContactSolver.Params params = new ContactSolver.Params();
        params.restitution = 0.5;
        params.restitutionThreshold = 1.0;
        params.beta = 0.2;
        params.slop = 0.01;
        double dt = 0.02;
        RigidBody ground = new RigidBody();
        ground.makeStatic();
        RigidBody box = new RigidBody(MassProperties.box(0.5, 0.5, 0.5, 2.0));
        box.setPose(0, 0.5, 0, 0, 0, 0, 1);
        // approaching fast with a penetration: bounce and a correction in the bias (split impulse)
        box.vy = -4;
        ContactManifold m = new ContactManifold();
        m.setNormal(0, 1, 0);
        m.add(0, 0, 0, 0, -0.05, 0, 0.05, 1);
        ContactSolver.prepare(ground, box, m, params, dt);
        assertEquals(0.5 * 4, m.target[0], 1e-12, "restitution times the approach speed");
        assertEquals(0.2 / dt * (0.05 - 0.01), m.bias[0], 1e-12, "beta / dt times the penetration beyond the slop");
        // without the split impulse the correction is part of the target
        params.splitImpulse = false;
        ContactSolver.prepare(ground, box, m, params, dt);
        assertEquals(0.5 * 4 + 0.2 / dt * (0.05 - 0.01), m.target[0], 1e-12);
        assertEquals(0.0, m.bias[0], 0.0);
        params.splitImpulse = true;
        // slower than the threshold: no bounce
        box.vy = -0.5;
        ContactSolver.prepare(ground, box, m, params, dt);
        assertEquals(0.0, m.target[0], 1e-12);
        // a speculative contact (a gap of 0.03) lets the bodies approach by exactly the gap in one step
        ContactManifold gap = new ContactManifold();
        gap.setNormal(0, 1, 0);
        gap.add(0, 0, 0, 0, 0.03, 0, -0.03, 2);
        box.vy = -0.5;
        ContactSolver.prepare(ground, box, gap, params, dt);
        assertEquals(-0.03 / dt, gap.target[0], 1e-12);
        assertEquals(0.0, gap.bias[0], 0.0);
        // stored impulses are applied to the bodies when the step is prepared: a normal and two tangential ones at the centre
        box.vx = box.vy = box.vz = 0;
        ContactManifold warm = new ContactManifold();
        warm.setNormal(0, 1, 0);
        warm.add(0, 0.5, 0, 0, 0.5, 0, 0.0, 3);
        warm.setNormalImpulse(0, 3.0);
        warm.setTangentImpulses(0, 1.0, -2.0);
        double[] t1 = new double[3], t2 = new double[3];
        ContactSolver.tangentBasis(0, 1, 0, t1, t2);
        ContactSolver.prepare(ground, box, warm, params, dt);
        assertEquals((3.0 * 0 + t1[0] * 1.0 + t2[0] * -2.0) / 2.0, box.vx, 1e-12);
        assertEquals((3.0 * 1 + t1[1] * 1.0 + t2[1] * -2.0) / 2.0, box.vy, 1e-12);
        assertEquals((3.0 * 0 + t1[2] * 1.0 + t2[2] * -2.0) / 2.0, box.vz, 1e-12);
    }

    @Test
    void theSplitImpulseRemovesPenetrationWithoutChangingTheRealVelocities() {
        for (int trial = 0; trial < 30; trial++) {
            ContactSolver.Params params = new ContactSolver.Params();
            double dt = 0.016;
            RigidBody ground = new RigidBody();
            ground.makeStatic();
            RigidBody box = new RigidBody(MassProperties.box(0.5, 0.5, 0.5, 2.0));
            box.setPose(0, 0.5, 0, rng.nextDouble(-0.1, 0.1), 0, 0, 1);
            double depth = rng.nextDouble(0.02, 0.2);
            double px = rng.nextDouble(-0.4, 0.4), pz = rng.nextDouble(-0.4, 0.4);
            ContactManifold m = new ContactManifold();
            m.setNormal(0, 1, 0);
            m.add(px, 0, pz, px, -depth, pz, depth, 1);
            double vy0 = box.vy;
            ContactSolver.solve(ground, box, m, params, dt);
            double[] p = {px, 0, pz};
            double push = params.beta / dt * Math.max(0, depth - params.slop);
            double[] vb = velocityAt(box, p, true);
            assertEquals(push, vb[1], 1e-9 * (1 + push), "the bias velocity at the contact point along the normal reaches the correction speed, trial " + trial);
            assertEquals(vy0, box.vy, 1e-9 + Math.abs(vy0), "the real velocity changed by the real constraint only");
            assertTrue(m.normalImpulse(0) >= 0.0);
        }
    }

    @Test
    void solveAllRunsEveryManifoldAndSkipsStaticPairs() {
        ContactSolver.Params params = new ContactSolver.Params();
        RigidBody ground = new RigidBody();
        ground.makeStatic();
        RigidBody other = new RigidBody();
        other.makeStatic();
        RigidBody box = new RigidBody(MassProperties.box(0.5, 0.5, 0.5, 2.0));
        box.setPose(0, 0.49, 0, 0, 0, 0, 1);
        box.vy = -3;
        ContactManifold onGround = new ContactManifold(), staticPair = new ContactManifold();
        onGround.setNormal(0, 1, 0);
        onGround.add(0, 0, 0, 0, -0.01, 0, 0.01, 1);
        staticPair.setNormal(0, 1, 0);
        staticPair.add(0, 0, 0, 0, -0.01, 0, 0.01, 2);
        ContactSolver.solveAll(new RigidBody[] {ground, ground}, new RigidBody[] {box, other}, new ContactManifold[] {onGround, staticPair}, 2, params, 1.0 / 60);
        assertTrue(onGround.normalImpulse(0) > 0, "the contact on the box was solved");
        assertEquals(0.0, staticPair.normalImpulse(0), 0.0, "a pair of static bodies is skipped");
        assertTrue(box.vy > -3 + 1.0, "the box was slowed");
    }
}
