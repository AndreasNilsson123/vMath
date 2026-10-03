package vmath.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Quatf;
import vmath.core.Rnd;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;
import vmath.geo.ConvexPolytope;

class ContactSolverTest {

    private final SplittableRandom rng = new SplittableRandom(Rnd.SEED);

    private static RigidBody sphereBody(double mass, double x, double vx) {
        RigidBody b = new RigidBody(MassProperties.sphere(0.5, mass));
        b.setPose(x, 0, 0, 0, 0, 0, 1);
        b.vx = vx;
        return b;
    }

    private static ContactManifold headOn(double x) {
        ContactManifold m = new ContactManifold();
        m.setNormal(1, 0, 0);
        m.add(x, 0, 0, x, 0, 0, 0.0, 1);
        return m;
    }

    @Test
    void theTangentBasisIsOrthonormal() {
        double[] t1 = new double[3], t2 = new double[3];
        for (int i = 0; i < 1000; i++) {
            double x = rng.nextGaussian(), y = rng.nextGaussian(), z = rng.nextGaussian(), l = Math.sqrt(x * x + y * y + z * z);
            x /= l;
            y /= l;
            z /= l;
            ContactSolver.tangentBasis(x, y, z, t1, t2);
            assertEquals(1.0, Math.sqrt(t1[0] * t1[0] + t1[1] * t1[1] + t1[2] * t1[2]), 1e-12);
            assertEquals(1.0, Math.sqrt(t2[0] * t2[0] + t2[1] * t2[1] + t2[2] * t2[2]), 1e-12);
            assertEquals(0.0, t1[0] * t2[0] + t1[1] * t2[1] + t1[2] * t2[2], 1e-12);
            assertEquals(0.0, t1[0] * x + t1[1] * y + t1[2] * z, 1e-12);
            assertEquals(0.0, t2[0] * x + t2[1] * y + t2[2] * z, 1e-12);
        }
        ContactSolver.tangentBasis(0, 0, -1, t1, t2); // the singular direction of simple constructions
        assertEquals(1.0, Math.sqrt(t1[0] * t1[0] + t1[1] * t1[1] + t1[2] * t1[2]), 1e-12);
    }

    @Test
    void effectiveMassOfAHeadOnContactIsTheReducedMass() {
        RigidBody a = sphereBody(2, 0, 0), b = sphereBody(3, 1, 0);
        assertEquals(2.0 * 3.0 / 5.0, ContactSolver.effectiveMass(a, b, 0.5, 0, 0, 1, 0, 0), 1e-12);
        // a static partner: the mass of the dynamic one
        RigidBody wall = new RigidBody();
        wall.setPose(1, 0, 0, 0, 0, 0, 1);
        assertEquals(2.0, ContactSolver.effectiveMass(a, wall, 0.5, 0, 0, 1, 0, 0), 1e-12);
        assertEquals(0.0, ContactSolver.effectiveMass(wall, wall, 0, 0, 0, 1, 0, 0), 0.0);
        // a contact point off the line of centres adds the rotational term: (r x n) . I^-1 (r x n) for each body
        MassProperties p = MassProperties.sphere(0.5, 2); // I = 0.2 * 2 * 0.25 = 0.1
        double ry = 0.4;
        double k = 1.0 / 2 + 1.0 / 3 + ry * ry / p.ixx() + ry * ry / MassProperties.sphere(0.5, 3).ixx();
        assertEquals(1.0 / k, ContactSolver.effectiveMass(a, b, 0.5, ry, 0, 1, 0, 0), 1e-9 * 1.0);
    }

    @Test
    void elasticAndInelasticHeadOnCollisionsFollowTheTextbookFormulas() {
        ContactSolver.Params params = new ContactSolver.Params();
        params.beta = 0;
        params.restitutionThreshold = 0;
        for (double e : new double[] {0, 0.5, 1}) {
            double m1 = 2, m2 = 1, v = 3;
            RigidBody a = sphereBody(m1, 0, v), b = sphereBody(m2, 1, 0);
            double momentum = m1 * a.vx + m2 * b.vx, energy = a.kineticEnergy() + b.kineticEnergy();
            params.restitution = e;
            ContactSolver.solve(a, b, headOn(0.5), params, 1.0 / 60);
            // the closing speed is reversed and scaled by e; momentum is conserved
            assertEquals(e * v, b.vx - a.vx, 1e-12, "e = " + e);
            assertEquals(momentum, m1 * a.vx + m2 * b.vx, 1e-12);
            if (e == 1) {
                assertEquals((m1 - m2) / (m1 + m2) * v, a.vx, 1e-12);
                assertEquals(2 * m1 / (m1 + m2) * v, b.vx, 1e-12);
                assertEquals(energy, a.kineticEnergy() + b.kineticEnergy(), 1e-12);
            }
            if (e == 0) {
                assertEquals(m1 * v / (m1 + m2), a.vx, 1e-12);
                assertEquals(a.vx, b.vx, 1e-12);
                assertTrue(a.kineticEnergy() + b.kineticEnergy() < energy);
            }
        }
        // bodies already separating are left alone, and contacts push and never pull
        RigidBody a = sphereBody(1, 0, -1), b = sphereBody(1, 1, 1);
        ContactSolver.solve(a, b, headOn(0.5), params, 1.0 / 60);
        assertEquals(-1.0, a.vx, 0.0);
        assertEquals(1.0, b.vx, 0.0);
    }

    @Test
    void anImpactAgainstAWallAtAnOffsetPointSpinsTheBody() {
        RigidBody wall = new RigidBody();
        wall.setPose(-1, 0, 0, 0, 0, 0, 1);
        MassProperties box = MassProperties.box(0.5, 0.5, 0.5, 1);
        RigidBody b = new RigidBody(box);
        b.setPose(0, 0, 0, 0, 0, 0, 1);
        b.vx = -4;
        ContactManifold m = new ContactManifold();
        m.setNormal(1, 0, 0); // from the wall (A) to the box (B)
        m.add(-0.5, 0.3, 0.0, -0.5, 0.3, 0.0, 0.0, 1);
        ContactSolver.Params params = new ContactSolver.Params();
        params.restitution = 0;
        params.friction = 0; // friction would couple the spin back into the normal direction
        ContactSolver.solve(wall, b, m, params, 1.0 / 60);
        // the relative normal velocity at the contact point is zero afterwards
        assertEquals(0.0, ContactSolver.relativeNormalVelocity(wall, b, -0.5, 0.3, 0, 1, 0, 0), 1e-12);
        // by hand: the impulse j = -vn k with the effective mass of the offset contact; the angular velocity is I^-1 (r x J) with r = (-0.5, 0.3, 0)
        double ry = 0.3, k = 1.0 + ry * ry / box.izz() * 1.0;
        double j = 4.0 / k;
        assertEquals(-4.0 + j, b.vx, 1e-12);
        // r x J = (rx, ry, 0) x (j, 0, 0) = (0, 0, -ry j)
        assertEquals(-ry * j / box.izz(), b.wz, 1e-12);
        assertEquals(j, m.normalImpulse(0), 1e-12, "the impulse was stored for the next frame");
    }

    @Test
    void positionCorrectionAndSpeculativeContacts() {
        ContactSolver.Params params = new ContactSolver.Params();
        // two bodies penetrating by 0.1 at rest: the solver gives them a separating speed of beta / dt (depth - slop)
        RigidBody a = sphereBody(1, 0, 0), b = sphereBody(1, 0.9, 0);
        ContactManifold m = new ContactManifold();
        m.setNormal(1, 0, 0);
        m.add(0.5, 0, 0, 0.4, 0, 0, 0.1, 1);
        double dt = 1.0 / 60;
        params.splitImpulse = false;
        ContactSolver.solve(a, b, m, params, dt);
        assertEquals(params.beta / dt * (0.1 - params.slop), b.vx - a.vx, 1e-9, "plain Baumgarte puts the correction into the velocity");
        // with split impulses the velocity stays clean and the same push is in the bias velocity
        params.splitImpulse = true;
        RigidBody a2 = sphereBody(1, 0, 0), b2 = sphereBody(1, 0.9, 0);
        ContactManifold m2 = new ContactManifold();
        m2.setNormal(1, 0, 0);
        m2.add(0.5, 0, 0, 0.4, 0, 0, 0.1, 1);
        ContactSolver.solve(a2, b2, m2, params, dt);
        assertEquals(0.0, b2.vx - a2.vx, 1e-12);
        assertEquals(params.beta / dt * (0.1 - params.slop), b2.bvx - a2.bvx, 1e-9);
        double gap = b2.px - a2.px;
        a2.integrate(dt);
        b2.integrate(dt);
        assertEquals(gap + params.beta * (0.1 - params.slop), b2.px - a2.px, 1e-12, "the bias moves the bodies once");
        assertEquals(0.0, b2.bvx, 0.0, "and is then discarded");
        // within the slop nothing is corrected
        RigidBody c = sphereBody(1, 0, 0), d = sphereBody(1, 0.9, 0);
        ContactManifold n = new ContactManifold();
        n.setNormal(1, 0, 0);
        n.add(0.5, 0, 0, 0.4955, 0, 0, 0.0045, 1);
        ContactSolver.solve(c, d, n, params, dt);
        assertEquals(0.0, d.vx - c.vx, 1e-12);
        // a speculative contact (a gap of 0.02) lets the bodies approach by exactly that gap in one step
        RigidBody e = sphereBody(1, 0, 1), f = sphereBody(1, 1.02, -1);
        ContactManifold s = new ContactManifold();
        s.setNormal(1, 0, 0);
        s.add(0.5, 0, 0, 0.52, 0, 0, -0.02, 1);
        ContactSolver.solve(e, f, s, params, dt);
        assertEquals(-0.02 / dt, f.vx - e.vx, 1e-9, "the closing speed is limited to gap / dt");
        // and a body that approaches slowly enough is not touched
        RigidBody g = sphereBody(1, 0, 0.1), h = sphereBody(1, 1.02, 0);
        ContactSolver.solve(g, h, s, params, dt);
        assertEquals(0.1, g.vx, 1e-12);
    }

    @Test
    void frictionIsLimitedByCoulombsLaw() {
        RigidBody ground = new RigidBody();
        RigidBody body = new RigidBody(MassProperties.box(0.5, 0.5, 0.5, 2));
        body.setPose(0, 0.5, 0, 0, 0, 0, 1);
        body.vx = 3;
        ContactManifold m = new ContactManifold();
        m.setNormal(0, 1, 0); // from the ground up to the body
        for (int i = 0; i < 4; i++) {
            m.add((i & 1) - 0.5, 0, (i >> 1) - 0.5, (i & 1) - 0.5, 0, (i >> 1) - 0.5, 0.0, i);
        }
        ContactSolver.Params params = new ContactSolver.Params();
        params.friction = 0.5;
        // the body presses on the ground with the weight of one frame: the normal impulse of the step is m g dt; give it as a downward velocity
        double dt = 1.0 / 60;
        body.vy = -9.81 * dt;
        ContactSolver.solve(ground, body, m, params, dt);
        double normalImpulse = 0;
        for (int i = 0; i < 4; i++) {
            normalImpulse += m.normalImpulse(i);
        }
        assertEquals(2 * 9.81 * dt, normalImpulse, 1e-3, "the normal impulses stop the fall (ten sweeps over four coupled points)");
        // the friction removed at most mu times the normal impulse of horizontal momentum: 3 - mu g dt
        assertEquals(3 - 0.5 * 9.81 * dt, body.vx, 1e-3);
        // a slow slide is stopped at once: the friction needed is less than the limit
        RigidBody slow = new RigidBody(MassProperties.box(0.5, 0.5, 0.5, 2));
        slow.setPose(0, 0.5, 0, 0, 0, 0, 1);
        slow.vx = 0.01;
        slow.vy = -9.81 * dt;
        ContactManifold m2 = new ContactManifold();
        m2.setNormal(0, 1, 0);
        for (int i = 0; i < 4; i++) {
            m2.add((i & 1) - 0.5, 0, (i >> 1) - 0.5, (i & 1) - 0.5, 0, (i >> 1) - 0.5, 0.0, i);
        }
        ContactSolver.solve(ground, slow, m2, params, dt);
        assertEquals(0.0, slow.vx, 1e-5, "sequential impulses: ten sweeps");
        assertEquals(0.0, ContactSolver.combineFriction(0.0, 0.7), 0.0);
        assertEquals(0.5, ContactSolver.combineFriction(0.5, 0.5), 1e-15);
        assertEquals(0.9, ContactSolver.combineRestitution(0.2, 0.9), 0.0);
    }

    // ------------------------------------------------------------ the pieces together

    private static ConvexPolytope shape(RigidBody b, double hx, double hy, double hz) {
        return ConvexPolytope.of(new Aabbf((float) -hx, (float) -hy, (float) -hz, (float) hx, (float) hy, (float) hz)).transformed(new Quatf((float) b.qx, (float) b.qy, (float) b.qz, (float) b.qw),
                new Vec3f((float) b.px, (float) b.py, (float) b.pz));
    }

    private static void step(RigidBody ground, ConvexPolytope groundShape, RigidBody[] bodies, double[][] half, ManifoldBuilder builder, ContactManifold[] previous, ContactSolver.Params params,
            double dt) {
        RigidBody[] as = new RigidBody[bodies.length], bs = new RigidBody[bodies.length];
        ContactManifold[] ms = new ContactManifold[bodies.length];
        int count = 0;
        for (int i = 0; i < bodies.length; i++) {
            RigidBody b = bodies[i];
            ContactManifold m = new ContactManifold();
            RigidBody below = i == 0 ? ground : bodies[i - 1];
            ConvexPolytope shapeBelow = i == 0 ? groundShape : shape(bodies[i - 1], half[i - 1][0], half[i - 1][1], half[i - 1][2]);
            if (builder.polytopes(shapeBelow, shape(b, half[i][0], half[i][1], half[i][2]), 0.0, m)) {
                m.warmStartFrom(previous[i], 0.05);
                as[count] = below;
                bs[count] = b;
                ms[count++] = m;
            }
            previous[i] = m;
        }
        ContactSolver.solveAll(as, bs, ms, count, params, dt);
        for (RigidBody b : bodies) {
            b.applyForce(0, -9.81 * b.mass(), 0);
            b.integrate(dt);
        }
    }

    @Test
    void aBoxLandsOnTheGroundAndComesToRest() {
        RigidBody ground = new RigidBody();
        ground.setPose(0, -1, 0, 0, 0, 0, 1);
        ConvexPolytope groundShape = shape(ground, 20, 1, 20); // the top is at y = 0
        RigidBody box = new RigidBody(MassProperties.box(0.5, 0.5, 0.5, 1));
        box.setPose(0, 1.5, 0, 0, 0, 0, 1);
        ManifoldBuilder builder = new ManifoldBuilder();
        ContactSolver.Params params = new ContactSolver.Params();
        ContactManifold[] previous = {new ContactManifold()};
        double dt = 1.0 / 60;
        for (int i = 0; i < 240; i++) {
            step(ground, groundShape, new RigidBody[] {box}, new double[][] {{0.5, 0.5, 0.5}}, builder, previous, params, dt);
        }
        assertEquals(0.5 - (params.slop + 9.81 * dt * dt / params.beta), box.py, 1e-3, "Baumgarte steady state: sink = slop + g dt^2 / beta");
        assertTrue(Math.abs(box.vy + 9.81 * dt) < 0.01, "at rest, the velocity is the gravity of one step that the next solve takes away: " + box.vy);
        assertTrue(Math.abs(box.vx) < 1e-6 && Math.abs(box.wx) + Math.abs(box.wy) + Math.abs(box.wz) < 0.01);
    }

    @Test
    void aSlidingBoxIsStoppedByFrictionAtTheRateOfTheLaw() {
        RigidBody ground = new RigidBody();
        ground.setPose(0, -1, 0, 0, 0, 0, 1);
        ConvexPolytope groundShape = shape(ground, 50, 1, 50);
        RigidBody box = new RigidBody(MassProperties.box(0.5, 0.5, 0.5, 1));
        box.setPose(0, 0.5, 0, 0, 0, 0, 1);
        box.vx = 3;
        ManifoldBuilder builder = new ManifoldBuilder();
        ContactSolver.Params params = new ContactSolver.Params();
        params.friction = 0.5;
        ContactManifold[] previous = {new ContactManifold()};
        double dt = 1.0 / 60;
        double vAt03 = Double.NaN;
        for (int i = 1; i <= 120; i++) {
            step(ground, groundShape, new RigidBody[] {box}, new double[][] {{0.5, 0.5, 0.5}}, builder, previous, params, dt);
            if (i == 18) {
                vAt03 = box.vx;
            }
        }
        // the deceleration is mu g = 4.905: after 0.3 s the speed is 3 - 1.47, and the box stops after 0.61 s
        assertEquals(3 - 0.5 * 9.81 * 0.3, vAt03, 0.1);
        assertTrue(Math.abs(box.vx) < 0.05, "the box has stopped: " + box.vx);
        double travelled = box.px;
        assertEquals(3.0 * 3.0 / (2 * 0.5 * 9.81), travelled, 0.15, "the stopping distance v^2 / (2 mu g)");
        assertEquals(0.5 - (params.slop + 9.81 * dt * dt / params.beta), box.py, 2e-3);
    }

    @Test
    void twoBoxesStackAndStayPut() {
        RigidBody ground = new RigidBody();
        ground.setPose(0, -1, 0, 0, 0, 0, 1);
        ConvexPolytope groundShape = shape(ground, 20, 1, 20);
        RigidBody lower = new RigidBody(MassProperties.box(0.5, 0.5, 0.5, 1)), upper = new RigidBody(MassProperties.box(0.4, 0.4, 0.4, 1));
        lower.setPose(0, 0.5, 0, 0, 0, 0, 1);
        upper.setPose(0.05, 1.4, 0, 0, 0, 0, 1);
        RigidBody[] bodies = {lower, upper};
        double[][] half = {{0.5, 0.5, 0.5}, {0.4, 0.4, 0.4}};
        ManifoldBuilder builder = new ManifoldBuilder();
        ContactSolver.Params params = new ContactSolver.Params();
        ContactManifold[] previous = {new ContactManifold(), new ContactManifold()};
        double dt = 1.0 / 60;
        for (int i = 0; i < 360; i++) {
            step(ground, groundShape, bodies, half, builder, previous, params, dt);
        }
        double sink = params.slop + 9.81 * dt * dt / params.beta; // the steady state does not depend on the mass: the bias cancels one step of gravity
        assertEquals(0.5 - sink, lower.py, 3e-3);
        double overlap = (lower.py + 0.5) - (upper.py - 0.4);
        assertTrue(overlap > 0 && overlap < 0.03, "the boxes overlap by the steady state penetration only: " + overlap);
        assertTrue(Math.abs(upper.vy + 9.81 * dt) < 0.02 && Math.abs(lower.vy + 9.81 * dt) < 0.02);
        assertEquals(0.05, upper.px, 0.005, "the top box has not slid off");
        assertEquals(0.0, lower.px, 0.005);
        double spin = Math.abs(upper.wx) + Math.abs(upper.wy) + Math.abs(upper.wz);
        assertTrue(spin < 0.1, "spin " + spin);
    }

    @Test
    void aTiltedBoxFallsFlat() {
        RigidBody ground = new RigidBody();
        ground.setPose(0, -1, 0, 0, 0, 0, 1);
        ConvexPolytope groundShape = shape(ground, 20, 1, 20);
        RigidBody box = new RigidBody(MassProperties.box(0.5, 0.5, 0.5, 1));
        Quatf tilt = Quatf.fromAxisAngle(0.35f, new Vec3f(1, 0, 1).normalize());
        box.setPose(0, 1.2, 0, tilt.x(), tilt.y(), tilt.z(), tilt.w());
        ManifoldBuilder builder = new ManifoldBuilder();
        ContactSolver.Params params = new ContactSolver.Params();
        params.friction = 0.6;
        ContactManifold[] previous = {new ContactManifold()};
        double dt = 1.0 / 60;
        for (int i = 0; i < 480; i++) {
            step(ground, groundShape, new RigidBody[] {box}, new double[][] {{0.5, 0.5, 0.5}}, builder, previous, params, dt);
        }
        double[] r = box.rotationMatrix(new double[9]);
        // the body's up axis (the second column) is vertical again: r[4] is its y component
        assertTrue(r[4] > 0.995 || Math.abs(r[0]) > 0.995 || Math.abs(r[8]) > 0.995, "the box lies on a face: up components " + r[1] + " " + r[4] + " " + r[7]);
        assertTrue(Math.abs(box.vy + 9.81 * dt) < 0.01 && Math.abs(box.wx) + Math.abs(box.wy) + Math.abs(box.wz) < 0.05, "at rest: " + box.vy + " " + box.wx);
        assertEquals(0.5, box.py, 0.03);
    }
}
