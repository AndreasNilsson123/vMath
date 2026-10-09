package vmath.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Mat3d;
import vmath.core.Rnd;
import vmath.core.Vec3d;

/**
 * QA-1: {@link RigidBody} against the laws it implements, written out independently with plain matrices: impulses change the velocities by {@code J / m} and
 * {@code I^-1 (r x J)}, a force at a point adds the torque {@code r x F}, the point velocity is {@code v + w x r}, the kinetic energy and angular momentum
 * follow from the world inertia {@code R I R^T}, and a step of {@link RigidBody#integrate} conserves what it should. The body has a full, rotated inertia tensor
 * and a mass that is not 1, so that a wrong index or a missing factor shows.
 */
class RigidBodyLawsTest {

    private final SplittableRandom rng = new SplittableRandom(Rnd.SEED);
    private static final double MASS = 3.7;

    private RigidBody body(boolean damped) {
        MassProperties mp = MassProperties.of(MASS, new Vec3d(0, 0, 0), 2.0, 3.1, 4.3, 0.3, -0.2, 0.15);
        RigidBody b = new RigidBody(mp);
        b.setPose(rng.nextDouble(-3, 3), rng.nextDouble(-3, 3), rng.nextDouble(-3, 3), rng.nextDouble(-1, 1), rng.nextDouble(-1, 1), rng.nextDouble(-1, 1), rng.nextDouble(-1, 1) + 0.1);
        b.vx = rng.nextDouble(-2, 2);
        b.vy = rng.nextDouble(-2, 2);
        b.vz = rng.nextDouble(-2, 2);
        b.wx = rng.nextDouble(-2, 2);
        b.wy = rng.nextDouble(-2, 2);
        b.wz = rng.nextDouble(-2, 2);
        if (damped) {
            b.linearDamping = 0.3;
            b.angularDamping = 0.2;
        }
        return b;
    }

    private static double[] bodyInertia() {
        Mat3d i = MassProperties.of(MASS, new Vec3d(0, 0, 0), 2.0, 3.1, 4.3, 0.3, -0.2, 0.15).inertia();
        return new double[] {i.m00(), i.m10(), i.m20(), i.m01(), i.m11(), i.m21(), i.m02(), i.m12(), i.m22()}; // row-major
    }

    private static double[] rotation(RigidBody b) {
        double x = b.qx, y = b.qy, z = b.qz, w = b.qw;
        return new double[] {1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w), 2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
                2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)};
    }

    private static double[] mul(double[] a, double[] b) {
        double[] c = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    c[3 * i + j] += a[3 * i + k] * b[3 * k + j];
                }
            }
        }
        return c;
    }

    private static double[] transpose(double[] a) {
        return new double[] {a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8]};
    }

    private static double[] apply(double[] m, double[] v) {
        return new double[] {m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2], m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] worldInertia(RigidBody b) {
        double[] r = rotation(b);
        return mul(mul(r, bodyInertia()), transpose(r));
    }

    private static double[] solve(double[] m, double[] v) {
        double det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6]);
        double[] inv = {(m[4] * m[8] - m[5] * m[7]) / det, (m[2] * m[7] - m[1] * m[8]) / det, (m[1] * m[5] - m[2] * m[4]) / det,
                (m[5] * m[6] - m[3] * m[8]) / det, (m[0] * m[8] - m[2] * m[6]) / det, (m[2] * m[3] - m[0] * m[5]) / det,
                (m[3] * m[7] - m[4] * m[6]) / det, (m[1] * m[6] - m[0] * m[7]) / det, (m[0] * m[4] - m[1] * m[3]) / det};
        return apply(inv, v);
    }

    private double[] randomVector() {
        return new double[] {rng.nextDouble(-2, 2), rng.nextDouble(-2, 2), rng.nextDouble(-2, 2)};
    }

    private static void assertVector(double[] expected, double x, double y, double z, double tol, String what) {
        assertEquals(expected[0], x, tol, what + " x");
        assertEquals(expected[1], y, tol, what + " y");
        assertEquals(expected[2], z, tol, what + " z");
    }

    @Test
    void impulsesChangeTheVelocitiesByTheLaw() {
        for (int trial = 0; trial < 100; trial++) {
            RigidBody b = body(false);
            double v0x = b.vx, v0y = b.vy, v0z = b.vz, w0x = b.wx, w0y = b.wy, w0z = b.wz;
            double[] j = randomVector(), at = randomVector();
            double[] r = {at[0] - b.px, at[1] - b.py, at[2] - b.pz};
            double[] dw = solve(worldInertia(b), cross(r, j));
            b.applyImpulseAtPoint(j[0], j[1], j[2], at[0], at[1], at[2]);
            assertVector(new double[] {v0x + j[0] / MASS, v0y + j[1] / MASS, v0z + j[2] / MASS}, b.vx, b.vy, b.vz, 1e-12, "impulse at a point: v");
            assertVector(new double[] {w0x + dw[0], w0y + dw[1], w0z + dw[2]}, b.wx, b.wy, b.wz, 1e-11, "impulse at a point: w");
            // a linear impulse alone, an angular impulse alone
            RigidBody c = body(false);
            double cvx = c.vx, cwx = c.wx, cwy = c.wy, cwz = c.wz;
            c.applyImpulse(j[0], j[1], j[2]);
            assertEquals(cvx + j[0] / MASS, c.vx, 1e-12);
            assertEquals(cwx, c.wx, 0.0, "a linear impulse does not spin the body");
            double[] dl = solve(worldInertia(c), j);
            c.applyAngularImpulse(j[0], j[1], j[2]);
            assertVector(new double[] {cwx + dl[0], cwy + dl[1], cwz + dl[2]}, c.wx, c.wy, c.wz, 1e-11, "angular impulse");
        }
    }

    @Test
    void biasImpulsesMoveOnlyTheBiasVelocitiesAndAreForgottenByTheStep() {
        for (int trial = 0; trial < 50; trial++) {
            RigidBody b = body(false);
            double vx = b.vx, wx = b.wx;
            double[] j = randomVector(), at = randomVector();
            double[] r = {at[0] - b.px, at[1] - b.py, at[2] - b.pz};
            double[] dw = solve(worldInertia(b), cross(r, j));
            b.applyBiasImpulseAtPoint(j[0], j[1], j[2], at[0], at[1], at[2]);
            assertEquals(vx, b.vx, 0.0, "the real velocity is not touched");
            assertEquals(wx, b.wx, 0.0);
            assertVector(new double[] {j[0] / MASS, j[1] / MASS, j[2] / MASS}, b.bvx, b.bvy, b.bvz, 1e-12, "bias v");
            assertVector(dw, b.bwx, b.bwy, b.bwz, 1e-11, "bias w");
            double px = b.px;
            b.integrate(0.01);
            assertTrue(b.px != px);
            assertEquals(0.0, b.bvx, 0.0);
            assertEquals(0.0, b.bwz, 0.0);
        }
    }

    @Test
    void forcesAccumulateWithTheirTorqueAndTheStepClearsThem() {
        RigidBody b = body(false);
        double[] f1 = randomVector(), at = randomVector(), f2 = randomVector(), tq = randomVector();
        b.applyForceAtPoint(f1[0], f1[1], f1[2], at[0], at[1], at[2]);
        b.applyForce(f2[0], f2[1], f2[2]);
        b.applyTorque(tq[0], tq[1], tq[2]);
        double[] r = {at[0] - b.px, at[1] - b.py, at[2] - b.pz};
        double[] t = cross(r, f1);
        assertVector(new double[] {f1[0] + f2[0], f1[1] + f2[1], f1[2] + f2[2]}, b.fx, b.fy, b.fz, 1e-13, "force");
        assertVector(new double[] {t[0] + tq[0], t[1] + tq[1], t[2] + tq[2]}, b.tx, b.ty, b.tz, 1e-13, "torque");
        b.integrate(0.01);
        assertEquals(0.0, b.fx, 0.0);
        assertEquals(0.0, b.ty, 0.0);
    }

    @Test
    void pointVelocityEnergyAndAngularMomentumFollowFromTheState() {
        for (int trial = 0; trial < 100; trial++) {
            RigidBody b = body(false);
            double[] at = randomVector();
            double[] out = new double[3];
            b.pointVelocity(at[0], at[1], at[2], out);
            double[] w = {b.wx, b.wy, b.wz};
            double[] wr = cross(w, new double[] {at[0] - b.px, at[1] - b.py, at[2] - b.pz});
            assertVector(new double[] {b.vx + wr[0], b.vy + wr[1], b.vz + wr[2]}, out[0], out[1], out[2], 1e-13, "point velocity");
            double[] iw = apply(worldInertia(b), w);
            b.angularMomentum(out);
            assertVector(iw, out[0], out[1], out[2], 1e-11, "angular momentum");
            double energy = 0.5 * MASS * (b.vx * b.vx + b.vy * b.vy + b.vz * b.vz) + 0.5 * (w[0] * iw[0] + w[1] * iw[1] + w[2] * iw[2]);
            assertEquals(energy, b.kineticEnergy(), 1e-11 * (1 + energy));
        }
    }

    @Test
    void theWorldInverseInertiaIsTheRotatedInverseAndFollowsTheOrientation() {
        RigidBody b = body(false);
        double[] out = new double[6];
        for (int trial = 0; trial < 50; trial++) {
            b.setPose(b.px, b.py, b.pz, rng.nextDouble(-1, 1), rng.nextDouble(-1, 1), rng.nextDouble(-1, 1), rng.nextDouble(-1, 1) + 0.1);
            b.worldInverseInertia(out);
            double[] expected = solveColumns(worldInertia(b));
            double[] full = {out[0], out[3], out[4], out[3], out[1], out[5], out[4], out[5], out[2]};
            for (int i = 0; i < 9; i++) {
                assertEquals(expected[i], full[i], 1e-12, "entry " + i);
            }
            // a second call with the same orientation gives the same numbers (the cache), and the orientation field alone invalidates it
            double[] again = new double[6];
            b.worldInverseInertia(again);
            assertEquals(out[2], again[2], 0.0);
        }
        // a new mass description is not served from the cache of the old one
        double before = b.worldInverseInertia(new double[6])[0];
        b.setMassProperties(MassProperties.box(1, 1, 1, 1));
        assertTrue(Math.abs(b.worldInverseInertia(new double[6])[0] - before) > 1e-6);
        b.makeStatic();
        assertEquals(0.0, b.worldInverseInertia(new double[6])[0], 0.0);
        assertTrue(b.isStatic());
        assertEquals(0.0, b.kineticEnergy(), 0.0);
    }

    private static double[] solveColumns(double[] m) {
        double[] inv = new double[9];
        for (int c = 0; c < 3; c++) {
            double[] e = {0, 0, 0};
            e[c] = 1;
            double[] col = solve(m, e);
            inv[c] = col[0];
            inv[3 + c] = col[1];
            inv[6 + c] = col[2];
        }
        return inv;
    }

    @Test
    void aStepAppliesTheForceTheDampingAndTheVelocity() {
        for (int trial = 0; trial < 50; trial++) {
            RigidBody b = body(true);
            double[] f = randomVector();
            double dt = rng.nextDouble(0.001, 0.05);
            double px = b.px, py = b.py, pz = b.pz, vx = b.vx, vy = b.vy, vz = b.vz;
            b.applyForce(f[0], f[1], f[2]);
            b.integrate(dt);
            double damp = 1.0 / (1.0 + 0.3 * dt);
            double nvx = (vx + f[0] / MASS * dt) * damp, nvy = (vy + f[1] / MASS * dt) * damp, nvz = (vz + f[2] / MASS * dt) * damp;
            assertVector(new double[] {nvx, nvy, nvz}, b.vx, b.vy, b.vz, 1e-12, "damped velocity");
            assertVector(new double[] {px + nvx * dt, py + nvy * dt, pz + nvz * dt}, b.px, b.py, b.pz, 1e-12, "position moves with the new velocity");
            assertEquals(1.0, Math.sqrt(b.qx * b.qx + b.qy * b.qy + b.qz * b.qz + b.qw * b.qw), 1e-12);
        }
    }

    @Test
    void aSphereSpinsAtAConstantRateAndTheOrientationTurnsByTheExactAngle() {
        RigidBody b = new RigidBody(MassProperties.sphere(0.5, 2.0));
        b.setPose(0, 0, 0, 0, 0, 0, 1);
        b.wz = 2.0;
        for (int i = 0; i < 100; i++) {
            b.integrate(0.01);
        }
        assertEquals(2.0, b.wz, 1e-12);
        assertEquals(Math.sin(1.0), b.qz, 1e-12, "100 steps of 0.01 at 2 rad/s turn the body by 2 rad about z: q = (0, 0, sin 1, cos 1)");
        assertEquals(Math.cos(1.0), b.qw, 1e-12);
        assertEquals(0.0, b.qx, 1e-12);
    }

    @Test
    void anAsymmetricTopKeepsItsAngularMomentumAndNearlyItsEnergy() {
        RigidBody b = body(false);
        double[] l0 = new double[3], l = new double[3];
        b.angularMomentum(l0);
        double e0 = b.kineticEnergy();
        for (int i = 0; i < 2000; i++) {
            b.integrate(0.001);
        }
        b.angularMomentum(l);
        double scale = Math.sqrt(l0[0] * l0[0] + l0[1] * l0[1] + l0[2] * l0[2]);
        for (int k = 0; k < 3; k++) {
            assertEquals(l0[k], l[k], 2e-3 * scale, "angular momentum " + k);
        }
        double pe = 0.5 * MASS * (b.vx * b.vx + b.vy * b.vy + b.vz * b.vz);
        assertEquals(e0, b.kineticEnergy(), 2e-3 * e0, "kinetic energy" + pe);
        assertTrue(b.isFinite());
        assertFalse(Double.isNaN(b.qw));
    }

    @Test
    void aStaticBodyDoesNotMoveAndItsForcesAreDropped() {
        RigidBody s = new RigidBody();
        s.makeStatic();
        s.setPose(1, 2, 3, 0, 0, 0, 1);
        s.applyForce(5, 5, 5);
        s.applyTorque(1, 1, 1);
        s.integrate(0.1);
        assertEquals(1.0, s.px, 0.0);
        assertEquals(0.0, s.fx, 0.0);
        assertEquals(0.0, s.tz, 0.0);
        assertEquals(Double.POSITIVE_INFINITY, s.mass());
    }
}
