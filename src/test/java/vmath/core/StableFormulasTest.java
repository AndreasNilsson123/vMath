package vmath.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The formulas that are written so that they do not lose precision where the textbook ones do: the angle between vectors (from the cross product and the dot product, not
 * from {@code acos} of a normalised dot product), the rotation angle of a quaternion (from {@code atan2}, not {@code acos(w)}), and normalisation of vectors whose squared
 * length overflows or underflows.
 */
class StableFormulasTest {

    @Test
    void vectorAnglesAreAccurateForTinyAndNearlyOppositeVectors() {
        for (double a : new double[] {1e-9, 1e-6, 1e-4, 1e-2}) {
            Vec3d u = new Vec3d(1, 0, 0), v = new Vec3d(Math.cos(a), Math.sin(a), 0);
            assertEquals(a, u.angle(v), a * 1e-12, "double, angle " + a);
            Vec3f uf = new Vec3f(1, 0, 0), vf = new Vec3f((float) Math.cos(a), (float) Math.sin(a), 0);
            assertEquals(a, uf.angle(vf), Math.max(a * 1e-3, 1e-7), "float, angle " + a);
            Vec3d opposite = new Vec3d(-Math.cos(a), Math.sin(a), 0);
            assertEquals(Math.PI - a, u.angle(opposite), 1e-12, "close to PI, double");
        }
        assertEquals(0.0, new Vec3d(0.3, 0.4, 0.5).angle(new Vec3d(0.3, 0.4, 0.5)), 1e-15);
        assertEquals(Math.PI, new Vec3d(1, 2, 3).angle(new Vec3d(-1, -2, -3)), 1e-15);
        assertEquals(Math.PI / 2, new Vec2d(1, 0).angle(new Vec2d(0, 5)), 1e-15);
    }

    @Test
    void quaternionAnglesAreAccurateForTinyRotationsAndNearPi() {
        Vec3d axis = new Vec3d(1, 2, 3).normalize();
        for (double a : new double[] {1e-7, 1e-5, 1e-3, 0.5, 3.0, Math.PI - 1e-3, Math.PI - 1e-5}) {
            assertEquals(a, Quatd.fromAxisAngle(a, axis).angle(), Math.max(a * 1e-12, 1e-14), "double, angle " + a);
            float expected = (float) a;
            float got = Quatf.fromAxisAngle(expected, (float) axis.x(), (float) axis.y(), (float) axis.z()).angle();
            assertEquals(expected, got, Math.max(expected * 2e-3f, 2e-7f), "float, angle " + a);
        }
        // the shortest arc: a rotation by 2 PI - a is the rotation by a the other way round
        assertEquals(1e-5, Quatd.fromAxisAngle(2 * Math.PI - 1e-5, axis).angle(), 1e-11);
        // a quaternion that is not exactly unit length still gives the right angle
        Quatd q = Quatd.fromAxisAngle(0.7, axis);
        assertEquals(0.7, new Quatd(q.x() * 1.5, q.y() * 1.5, q.z() * 1.5, q.w() * 1.5).angle(), 1e-12);
    }

    @Test
    void normalizeSurvivesOverflowAndUnderflow() {
        Vec3f huge = new Vec3f(3e30f, 4e30f, 0f);
        assertEquals(1.0, huge.normalize().length(), 1e-6, "squared length of 3e30 overflows a float");
        Vec3f tiny = new Vec3f(3e-30f, 4e-30f, 0f);
        assertEquals(0.6f, tiny.normalize().x(), 1e-6f, "squared length of 3e-30 underflows a float");
        Vec3d hugeD = new Vec3d(3e200, 4e200, 0);
        assertEquals(0.8, hugeD.normalize().y(), 1e-15);
        assertEquals(0.0, Vec3f.ZERO.normalizeOrZero().length());
    }
}
