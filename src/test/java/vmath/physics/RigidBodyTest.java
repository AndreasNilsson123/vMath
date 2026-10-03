package vmath.physics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Rnd;
import vmath.physics.MassProperties.Axis;

class RigidBodyTest {

    private final SplittableRandom rng = new SplittableRandom(Rnd.SEED);

    @Test
    void freeFlightAndGravityFollowTheExactSemiImplicitFormula() {
        RigidBody b = new RigidBody(MassProperties.sphere(0.5, 2));
        b.setPose(1, 2, 3, 0, 0, 0, 1);
        b.vx = 3;
        b.vy = 4;
        double dt = 0.01, g = -9.81;
        int n = 250;
        for (int i = 0; i < n; i++) {
            b.applyForce(0, g * 2, 0); // the weight of a 2 kg body
            b.integrate(dt);
        }
        // symplectic Euler: v_k = v0 + k g dt, x_k = x0 + dt sum_{j=1..k} v_j = x0 + v0 k dt + g dt^2 k (k + 1) / 2
        assertEquals(1 + 3 * n * dt, b.px, 1e-9);
        assertEquals(2 + 4 * n * dt + g * dt * dt * n * (n + 1) / 2.0, b.py, 1e-9);
        assertEquals(3.0, b.pz, 1e-12);
        assertEquals(4 + g * n * dt, b.vy, 1e-9);
        // the force accumulator was cleared by each step
        assertEquals(0.0, b.fy, 0.0);
        // close to the exact parabola
        double t = n * dt;
        assertEquals(2 + 4 * t + 0.5 * g * t * t, b.py, 0.5 * Math.abs(g) * dt * t * 1.0001, "the difference to the exact parabola is the first-order error g dt t / 2 of the method");
    }

    @Test
    void staticBodiesDoNotMove() {
        RigidBody s = new RigidBody();
        assertTrue(s.isStatic());
        assertEquals(Double.POSITIVE_INFINITY, s.mass());
        s.vx = 1;
        s.applyForce(10, 10, 10);
        s.applyTorque(1, 1, 1);
        s.applyImpulse(5, 5, 5);
        s.applyImpulseAtPoint(1, 1, 1, 2, 2, 2);
        s.integrate(1.0);
        assertEquals(0.0, s.px, 0.0, "a static body does not integrate even with a velocity set");
        assertEquals(1.0, s.vx, 0.0);
        assertEquals(0.0, s.fx, 0.0);
        assertEquals(0.0, s.kineticEnergy(), 0.0);
        RigidBody d = new RigidBody(MassProperties.box(1, 1, 1, 3));
        assertEquals(3.0, d.mass(), 1e-12);
        d.makeStatic();
        assertTrue(d.isStatic());
        assertEquals(0.0, d.inverseMass(), 0.0);
    }

    @Test
    void impulsesAtAPointChangeBothVelocities() {
        MassProperties box = MassProperties.box(1, 2, 3, 12);
        RigidBody b = new RigidBody(box);
        b.setPose(0, 0, 0, 0, 0, 0, 1);
        // an impulse J at r from the centre: v = J / m, w = I^-1 (r x J)
        double jx = 0, jy = 6, jz = 0, rx = 1, ry = 0, rz = 0;
        b.applyImpulseAtPoint(jx, jy, jz, rx, ry, rz);
        assertEquals(0.5, b.vy, 1e-12);
        // r x J = (0, 0, 6): w_z = 6 / Izz
        assertEquals(6.0 / box.izz(), b.wz, 1e-12);
        assertEquals(0.0, b.wx, 1e-12);
        // the velocity of the point that was hit: v + w x r
        double[] pv = new double[3];
        b.pointVelocity(rx, ry, rz, pv);
        assertEquals(0.5 + 6.0 / box.izz() * 1.0, pv[1], 1e-12);
        // energy: the work of the impulse is J . (the mean of the velocities of the point before and after) = J . v_p / 2
        assertEquals(0.5 * 6 * pv[1], b.kineticEnergy(), 1e-12);
        // a force at a point accumulates its torque
        RigidBody c = new RigidBody(box);
        c.applyForceAtPoint(0, 0, 4, 0, 3, 0);
        assertEquals(12.0, c.tx, 1e-12); // r x F = (0, 3, 0) x (0, 0, 4) = (12, 0, 0)
        assertEquals(0.0, c.ty, 1e-12);
        c.applyTorque(1, 2, 3);
        assertEquals(13.0, c.tx, 1e-12);
        c.applyForce(1, 1, 1);
        assertEquals(1.0, c.fx, 1e-12);
        // rotated body: the angular impulse is mapped through the tensor in the world frame
        RigidBody e = new RigidBody(box);
        e.setPose(0, 0, 0, 0, 0, Math.sin(Math.PI / 4), Math.cos(Math.PI / 4)); // a quarter turn about z: world x is body y
        e.applyAngularImpulse(1, 0, 0);
        assertEquals(1.0 / box.iyy(), e.wx, 1e-12);
    }

    @Test
    void aConstantAngularVelocityRotatesExactly() {
        // a sphere has no gyroscopic effect: the orientation after n steps is the rotation by w t about the axis
        RigidBody b = new RigidBody(MassProperties.sphere(1, 1));
        double ax = 1, ay = 2, az = 2, al = 3, speed = 1.7;
        b.wx = speed * ax / al;
        b.wy = speed * ay / al;
        b.wz = speed * az / al;
        double dt = 1.0 / 60;
        int n = 600;
        for (int i = 0; i < n; i++) {
            b.integrate(dt);
        }
        double angle = speed * n * dt;
        // q and -q are the same rotation: compare the absolute values of the scalar part and of the length of the vector part
        assertEquals(Math.abs(Math.cos(angle / 2)), Math.abs(b.qw), 1e-9);
        assertEquals(Math.abs(Math.sin(angle / 2)), Math.sqrt(b.qx * b.qx + b.qy * b.qy + b.qz * b.qz), 1e-9);
        assertEquals(1.0, Math.sqrt(b.qx * b.qx + b.qy * b.qy + b.qz * b.qz + b.qw * b.qw), 1e-12);
        // and the axis is the one of the angular velocity
        double len = Math.sqrt(b.qx * b.qx + b.qy * b.qy + b.qz * b.qz);
        assertEquals(1.0, Math.abs((b.qx * ax + b.qy * ay + b.qz * az) / al / len), 1e-9);
        assertEquals(speed * ax / al, b.wx, 1e-12);
    }

    /** A symmetric top with the moments (1, 1, 2) and the angular velocity (0.5, 0, 3) in the body frame precesses: w_x = 0.5 cos(W t), w_y = 0.5 sin(W t) with W = (Iz - Ix) / Ix * w_z = 3. */
    @Test
    void aTorqueFreeSymmetricTopPrecessesAsTheoryPredicts() {
        MassProperties p = MassProperties.of(1, vmath.core.Vec3d.ZERO, 1, 1, 2, 0, 0, 0);
        RigidBody b = new RigidBody(p);
        b.wx = 0.5;
        b.wz = 3.0;
        double e0 = b.kineticEnergy();
        double[] l0 = new double[3];
        b.angularMomentum(l0);
        double dt = 1e-3;
        int n = 1000;
        double worstBody = 0;
        double[] r = new double[9];
        for (int i = 1; i <= n; i++) {
            b.integrate(dt);
            double t = i * dt;
            // the body-frame angular velocity: R^T w
            b.rotationMatrix(r);
            double bx = r[0] * b.wx + r[3] * b.wy + r[6] * b.wz, by = r[1] * b.wx + r[4] * b.wy + r[7] * b.wz, bz = r[2] * b.wx + r[5] * b.wy + r[8] * b.wz;
            worstBody = Math.max(worstBody, Math.max(Math.abs(bx - 0.5 * Math.cos(3 * t)), Math.max(Math.abs(by - 0.5 * Math.sin(3 * t)), Math.abs(bz - 3.0))));
        }
        double[] l1 = new double[3];
        b.angularMomentum(l1);
        double drift = Math.sqrt(Math.pow(l1[0] - l0[0], 2) + Math.pow(l1[1] - l0[1], 2) + Math.pow(l1[2] - l0[2], 2)) / Math.sqrt(l0[0] * l0[0] + l0[1] * l0[1] + l0[2] * l0[2]);
        // measured: see docs/ANIMATION.md; the implicit step loses a little energy and angular momentum, at a rate proportional to the step
        assertTrue(worstBody < 0.01, "body-frame angular velocity off by " + worstBody);
        assertTrue(drift < 0.01, "angular momentum drift " + drift);
        assertTrue(b.kineticEnergy() <= e0 * (1 + 1e-9) && b.kineticEnergy() > 0.99 * e0, "energy " + b.kineticEnergy() + " of " + e0);
    }

    /** The stable integration of the tumbling of an asymmetric body (the intermediate axis theorem) against a fine Runge-Kutta solution of Euler's equations. */
    @Test
    void anAsymmetricBodyTumblesAsTheReferenceSaysAtAnyStepSize() {
        double[] inertia = {1, 2, 3};
        double[] w0 = {0.1, 2.0, 0.1}; // close to the unstable intermediate axis
        double tEnd = 2.0;
        double[] reference = rk4Euler(inertia, w0, tEnd, 1e-5);
        for (double dt : new double[] {1.0 / 240, 1.0 / 60}) {
            RigidBody b = new RigidBody(MassProperties.of(1, vmath.core.Vec3d.ZERO, inertia[0], inertia[1], inertia[2], 0, 0, 0));
            b.wx = w0[0];
            b.wy = w0[1];
            b.wz = w0[2];
            int n = (int) Math.round(tEnd / dt);
            for (int i = 0; i < n; i++) {
                b.integrate(dt);
            }
            double[] r = new double[9];
            b.rotationMatrix(r);
            double bx = r[0] * b.wx + r[3] * b.wy + r[6] * b.wz, by = r[1] * b.wx + r[4] * b.wy + r[7] * b.wz, bz = r[2] * b.wx + r[5] * b.wy + r[8] * b.wz;
            double err = Math.max(Math.abs(bx - reference[0]), Math.max(Math.abs(by - reference[1]), Math.abs(bz - reference[2])));
            // the explicit method of the same order would be unstable here; the implicit one tracks the tumble with an error that falls with the step
            assertTrue(err < (dt > 0.01 ? 0.6 : 0.2), "dt = " + dt + ": body-frame angular velocity off by " + err);
            assertTrue(Double.isFinite(b.wx + b.wy + b.wz));
            assertTrue(b.kineticEnergy() > 0);
        }
    }

    /** Euler's equations I dw/dt = -w x I w, integrated with a small step of the classical Runge-Kutta method (the body-frame angular velocity). */
    private static double[] rk4Euler(double[] inertia, double[] w0, double time, double dt) {
        double[] w = w0.clone();
        int n = (int) Math.round(time / dt);
        for (int i = 0; i < n; i++) {
            double[] k1 = euler(inertia, w);
            double[] k2 = euler(inertia, add(w, k1, dt / 2));
            double[] k3 = euler(inertia, add(w, k2, dt / 2));
            double[] k4 = euler(inertia, add(w, k3, dt));
            for (int k = 0; k < 3; k++) {
                w[k] += dt / 6 * (k1[k] + 2 * k2[k] + 2 * k3[k] + k4[k]);
            }
        }
        return w;
    }

    private static double[] add(double[] w, double[] k, double s) {
        return new double[] {w[0] + s * k[0], w[1] + s * k[1], w[2] + s * k[2]};
    }

    private static double[] euler(double[] i, double[] w) {
        return new double[] {(i[1] - i[2]) * w[1] * w[2] / i[0], (i[2] - i[0]) * w[2] * w[0] / i[1], (i[0] - i[1]) * w[0] * w[1] / i[2]};
    }

    @Test
    void theImplicitGyroscopicStepIsStableWhereTheExplicitOneBlowsUp() {
        // a long thin rod spun about its axis of least inertia at a large step: the energy must not grow
        MassProperties rod = MassProperties.cylinder(0.05, 1.0, Axis.X, 1.0);
        RigidBody b = new RigidBody(rod);
        b.wx = 40;
        b.wy = 0.5;
        double e0 = b.kineticEnergy();
        for (int i = 0; i < 5000; i++) {
            b.integrate(1.0 / 30);
            assertTrue(b.kineticEnergy() <= e0 * (1 + 1e-9), "the energy grew at step " + i);
        }
        assertTrue(Double.isFinite(b.wx));
    }

    @Test
    void torqueAndDampingActOnTheAngularVelocity() {
        MassProperties p = MassProperties.sphere(1, 2.5); // I = 1
        RigidBody b = new RigidBody(p);
        for (int i = 0; i < 100; i++) {
            b.applyTorque(0, 0, 2.0);
            b.integrate(0.01);
        }
        // the angular acceleration is torque / I = 2 / 1: w = 2 after one second
        assertEquals(2.0 / p.ixx(), b.wz, 1e-9);
        // damping: the velocity is divided by 1 + c dt each step
        RigidBody d = new RigidBody(p);
        d.vx = 10;
        d.wx = 5;
        d.linearDamping = 2;
        d.angularDamping = 3;
        d.integrate(0.1);
        assertEquals(10 / 1.2, d.vx, 1e-12);
        assertEquals(5 / 1.3, d.wx, 1e-9);
    }

    @Test
    void queriesAboutThePose() {
        RigidBody b = new RigidBody(MassProperties.box(1, 2, 3, 6));
        b.setPose(1, 2, 3, 1, 2, 3, 4); // normalised
        assertEquals(1.0, Math.sqrt(b.qx * b.qx + b.qy * b.qy + b.qz * b.qz + b.qw * b.qw), 1e-15);
        assertEquals(2.0, b.position().y(), 0.0);
        assertEquals(b.qx, b.orientation().x(), 0.0);
        double[] r = b.rotationMatrix(new double[9]);
        double det = r[0] * (r[4] * r[8] - r[5] * r[7]) - r[1] * (r[3] * r[8] - r[5] * r[6]) + r[2] * (r[3] * r[7] - r[4] * r[6]);
        assertEquals(1.0, det, 1e-12);
        // the angular momentum is I w in the world frame: for a rotated body its length is not that of the unrotated one, but L . w = 2 E_rot
        b.wx = 1;
        b.wy = -2;
        b.wz = 0.5;
        double[] l = new double[3];
        b.angularMomentum(l);
        assertEquals(2 * b.kineticEnergy(), l[0] * b.wx + l[1] * b.wy + l[2] * b.wz, 1e-9);
        // the world inverse inertia is symmetric and positive: w . I^-1 w > 0
        double[] iw = b.worldInverseInertia(new double[6]);
        assertTrue(iw[0] > 0 && iw[1] > 0 && iw[2] > 0);
    }

    @Test
    void theWorldInertiaFollowsTheOrientationAndTheTensor() {
        RigidBody b = new RigidBody(MassProperties.box(1, 0.5, 0.25, 3));
        double[] first = b.worldInverseInertia(new double[6]);
        double[] second = b.worldInverseInertia(new double[6]);
        assertArrayEquals(first, second, 0.0);
        // turn the body a quarter about z by writing the public fields: x and y swap
        double s = Math.sqrt(0.5);
        b.qx = 0;
        b.qy = 0;
        b.qz = s;
        b.qw = s;
        double[] turned = b.worldInverseInertia(new double[6]);
        assertEquals(first[1], turned[0], 1e-12);
        assertEquals(first[0], turned[1], 1e-12);
        assertEquals(first[2], turned[2], 1e-12);
        // a new tensor replaces the cached one
        b.setMassProperties(MassProperties.box(0.2, 0.2, 0.2, 1));
        double[] cube = b.worldInverseInertia(new double[6]);
        assertEquals(cube[0], cube[1], 1e-12);
        assertEquals(1.0 / MassProperties.box(0.2, 0.2, 0.2, 1).ixx(), cube[0], 1e-9);
        b.makeStatic();
        assertEquals(0.0, b.worldInverseInertia(new double[6])[0], 0.0);
    }
}
