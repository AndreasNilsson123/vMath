package vmath.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * QA-1: the bookkeeping of {@link ContactManifold}: adding, clearing, copying and carrying impulses from one step to the next (the matching rules of
 * {@code warmStartFrom}), and {@link OrientedBox}.
 */
class ContactManifoldTest {

    private static ContactManifold manifold(int... ids) {
        ContactManifold m = new ContactManifold();
        m.setNormal(0, 3, 4);
        for (int i = 0; i < ids.length; i++) {
            m.add(i, 10 * i, 100 * i, i + 0.5, 10 * i + 0.5, 100 * i + 0.5, 0.01 * (i + 1), ids[i]);
        }
        return m;
    }

    @Test
    void theNormalIsNormalisedAndPointsAreMidpointsAndTheFifthPointIsDropped() {
        ContactManifold m = manifold(1, 2, 3, 4, 5);
        assertEquals(0.0, m.nx, 1e-15);
        assertEquals(0.6, m.ny, 1e-15);
        assertEquals(0.8, m.nz, 1e-15);
        assertEquals(4, m.count(), "at most four points");
        assertEquals(3.25, m.point(3, 0), 1e-15);
        assertEquals(30.25, m.point(3, 1), 1e-15);
        assertEquals(300.25, m.point(3, 2), 1e-15);
        assertEquals(3.0, m.pointA(3, 0), 0.0);
        assertEquals(3.5, m.pointB(3, 0), 0.0);
        assertEquals(0.04, m.depth(3), 1e-15);
        assertEquals(4, m.id(3));
        assertEquals(1, m.id(0));
    }

    @Test
    void clearForgetsThePointsAndTheImpulses() {
        ContactManifold m = manifold(1, 2);
        m.setNormalImpulse(0, 5);
        m.setTangentImpulses(1, 2, 3);
        m.clear();
        assertEquals(0, m.count());
        m.add(0, 0, 0, 0, 0, 0, 0, 9);
        assertEquals(0.0, m.normalImpulse(0), 0.0, "a new point starts without impulse");
        assertEquals(0.0, m.tangentImpulse1(0), 0.0);
        m.add(0, 0, 0, 0, 0, 0, 0, 9);
        assertEquals(0.0, m.tangentImpulse2(1), 0.0);
        // clear zeroes the impulses of the slots too: set after a point exists, clear, add again
        m.setNormalImpulse(0, 7);
        m.setTangentImpulses(0, 8, 9);
        m.clear();
        m.add(0, 0, 0, 0, 0, 0, 0, 9);
        assertEquals(0.0, m.normalImpulse(0), 0.0);
        assertEquals(0.0, m.tangentImpulse1(0), 0.0);
        assertEquals(0.0, m.tangentImpulse2(0), 0.0);
    }

    @Test
    void copyFromCopiesEverything() {
        ContactManifold a = manifold(11, 12, 13);
        for (int i = 0; i < 3; i++) {
            a.setNormalImpulse(i, 1 + i);
            a.setTangentImpulses(i, 10 + i, 20 + i);
        }
        ContactManifold b = new ContactManifold();
        b.copyFrom(a);
        assertEquals(3, b.count());
        assertEquals(a.nx, b.nx, 0.0);
        assertEquals(a.ny, b.ny, 0.0);
        assertEquals(a.nz, b.nz, 0.0);
        for (int i = 0; i < 3; i++) {
            for (int axis = 0; axis < 3; axis++) {
                assertEquals(a.pointA(i, axis), b.pointA(i, axis), 0.0);
                assertEquals(a.pointB(i, axis), b.pointB(i, axis), 0.0);
            }
            assertEquals(a.depth(i), b.depth(i), 0.0);
            assertEquals(a.id(i), b.id(i));
            assertEquals(1 + i, b.normalImpulse(i), 0.0);
            assertEquals(10 + i, b.tangentImpulse1(i), 0.0);
            assertEquals(20 + i, b.tangentImpulse2(i), 0.0);
        }
    }

    @Test
    void warmStartMatchesByIdFirstThenByDistanceAndUsesEachPreviousPointOnce() {
        ContactManifold prev = manifold(7, 8, 9);
        for (int i = 0; i < 3; i++) {
            prev.setNormalImpulse(i, 100 + i);
            prev.setTangentImpulses(i, 200 + i, 300 + i);
        }
        // new points: ids 9 and 7 (match by id, in another order), id 5 near previous point 1 (by distance), and id 6 far from everything
        ContactManifold now = new ContactManifold();
        now.setNormal(0, 1, 0);
        now.add(2, 20, 200, 0, 0, 0, 0.1, 9);
        now.add(0, 0, 0, 0, 0, 0, 0.1, 7);
        now.add(1.01, 10.01, 100.01, 0, 0, 0, 0.1, 5);
        now.add(50, 50, 50, 0, 0, 0, 0.1, 6);
        now.warmStartFrom(prev, 0.1);
        assertEquals(102, now.normalImpulse(0), 0.0);
        assertEquals(202, now.tangentImpulse1(0), 0.0);
        assertEquals(302, now.tangentImpulse2(0), 0.0);
        assertEquals(100, now.normalImpulse(1), 0.0);
        assertEquals(101, now.normalImpulse(2), 0.0, "by distance");
        assertEquals(201, now.tangentImpulse1(2), 0.0);
        assertEquals(301, now.tangentImpulse2(2), 0.0);
        assertEquals(0.0, now.normalImpulse(3), 0.0, "nothing near");
        // the distance limit is inclusive of the points inside it and exclusive of those outside
        ContactManifold far = new ContactManifold();
        far.add(1.2, 10, 100, 0, 0, 0, 0.1, 55);
        far.warmStartFrom(prev, 0.1);
        assertEquals(0.0, far.normalImpulse(0), 0.0, "0.2 away with a limit of 0.1");
        ContactManifold near = new ContactManifold();
        near.add(1.0, 10.1, 100, 0, 0, 0, 0.1, 55);
        near.warmStartFrom(prev, 0.1);
        assertEquals(101, near.normalImpulse(0), 0.0, "exactly at the limit still matches");
        // one previous point serves one new point: two new points near the same old one
        ContactManifold two = new ContactManifold();
        two.add(1.0, 10.0, 100.0, 0, 0, 0, 0.1, 56);
        two.add(1.0, 10.01, 100.0, 0, 0, 0, 0.1, 57);
        two.warmStartFrom(prev, 0.5);
        assertEquals(1, (two.normalImpulse(0) == 101 ? 1 : 0) + (two.normalImpulse(1) == 101 ? 1 : 0));
        // an id that matches an already used point is not used twice
        ContactManifold same = new ContactManifold();
        same.add(0, 0, 0, 0, 0, 0, 0.1, 7);
        same.add(0, 0, 0, 0, 0, 0, 0.1, 7);
        same.warmStartFrom(prev, 0.0);
        assertEquals(1, (same.normalImpulse(0) == 100 ? 1 : 0) + (same.normalImpulse(1) == 100 ? 1 : 0));
    }

    @Test
    void anOrientedBoxReportsItsPoseAndItsSupportPoint() {
        double s = Math.sin(0.3), c = Math.cos(0.3);
        OrientedBox b = new OrientedBox().set(1, 2, 3, 0, 0, s, c, 0.5, 1.5, 2.5); // turned by 0.6 about z
        assertEquals(1.0, b.center(0), 0.0);
        assertEquals(2.0, b.center(1), 0.0);
        assertEquals(3.0, b.center(2), 0.0);
        assertEquals(0.5, b.halfExtent(0), 0.0);
        assertEquals(1.5, b.halfExtent(1), 0.0);
        assertEquals(2.5, b.halfExtent(2), 0.0);
        assertEquals(Math.cos(0.6), b.axis(0, 0), 1e-12);
        assertEquals(Math.sin(0.6), b.axis(0, 1), 1e-12);
        assertEquals(0.0, b.axis(0, 2), 1e-12);
        assertEquals(-Math.sin(0.6), b.axis(1, 0), 1e-12);
        assertEquals(Math.cos(0.6), b.axis(1, 1), 1e-12);
        assertEquals(1.0, b.axis(2, 2), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> b.axis(3, 0));
        assertThrows(IllegalArgumentException.class, () -> b.axis(0, -1));
        assertThrows(IllegalArgumentException.class, () -> b.halfExtent(3));
        assertThrows(IllegalArgumentException.class, () -> b.center(-1));
        // the support point of a direction is the corner with the largest dot product
        double[] out = new double[3];
        for (int i = 0; i < 200; i++) {
            double dx = Math.sin(i * 1.7) * 3, dy = Math.cos(i * 0.9) * 2, dz = Math.sin(i * 0.37 + 1);
            b.support(dx, dy, dz, out);
            double best = Double.NEGATIVE_INFINITY;
            for (int k = 0; k < 8; k++) {
                double sx = (k & 1) == 0 ? -1 : 1, sy = (k & 2) == 0 ? -1 : 1, sz = (k & 4) == 0 ? -1 : 1;
                double x = 1 + sx * 0.5 * b.axis(0, 0) + sy * 1.5 * b.axis(1, 0) + sz * 2.5 * b.axis(2, 0);
                double y = 2 + sx * 0.5 * b.axis(0, 1) + sy * 1.5 * b.axis(1, 1) + sz * 2.5 * b.axis(2, 1);
                double z = 3 + sx * 0.5 * b.axis(0, 2) + sy * 1.5 * b.axis(1, 2) + sz * 2.5 * b.axis(2, 2);
                best = Math.max(best, x * dx + y * dy + z * dz);
            }
            assertEquals(best, out[0] * dx + out[1] * dy + out[2] * dz, 1e-12, "direction " + i);
        }
        // set from a body
        RigidBody body = new RigidBody(MassProperties.box(1, 1, 1, 1));
        body.setPose(4, 5, 6, 0, 0, 0, 1);
        OrientedBox fromBody = new OrientedBox().set(body, 0.1, 0.2, 0.3);
        assertEquals(4.0, fromBody.center(0), 0.0);
        assertEquals(6.0, fromBody.center(2), 0.0);
        assertEquals(0.3, fromBody.halfExtent(2), 0.0);
        assertEquals(1.0, fromBody.axis(0, 0), 1e-15);
        // an unnormalised quaternion is normalised
        OrientedBox scaled = new OrientedBox().set(0, 0, 0, 0, 0, 2 * s, 2 * c, 1, 1, 1);
        assertEquals(Math.cos(0.6), scaled.axis(0, 0), 1e-12);
    }
}
