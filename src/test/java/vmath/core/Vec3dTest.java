package vmath.core;

// GENERATED from Vec3fTest.java by tools/GenDouble.java. Do not edit; edit the float source.

import static vmath.core.Check.check;
import static vmath.core.Check.close;
import static vmath.core.Rnd.N;

import org.junit.jupiter.api.Test;

class Vec3dTest {

    static final double EPS = 1e-12;

    final Rnd rnd = Rnd.create();

    @Test
    void arithmeticMatchesJoml() {
        for (int i = 0; i < N; i++) {
            Vec3d a = rnd.nextVec3d();
            Vec3d b = rnd.nextVec3d();
            double s = rnd.range(-10, 10);
            close(a.add(b), J.j(a).add(J.j(b)), EPS, i);
            close(a.add(b.x(), b.y(), b.z()), J.j(a).add(b.x(), b.y(), b.z()), EPS, i);
            close(a.sub(b), J.j(a).sub(J.j(b)), EPS, i);
            close(a.mul(s), J.j(a).mul(s), EPS, i);
            close(a.mul(b), J.j(a).mul(J.j(b)), EPS, i);
            close(a.div(s), J.j(a).div(s), EPS, i);
            close(a.negate(), J.j(a).negate(), EPS, i);
            close(a.fma(b, s), J.j(a).add(J.j(b).mul(s)), EPS, i);
            close(a.min(b), J.j(a).min(J.j(b)), EPS, i);
            close(a.max(b), J.j(a).max(J.j(b)), EPS, i);
            close(a.abs(), J.j(a).absolute(), EPS, i);
        }
    }

    @Test
    void geometryMatchesJoml() {
        for (int i = 0; i < N; i++) {
            Vec3d a = rnd.nextVec3d();
            Vec3d b = rnd.nextVec3d();
            double t = rnd.range(0, 1);
            close(a.dot(b), J.j(a).dot(J.j(b)), EPS, i);
            close(a.cross(b), J.j(a).cross(J.j(b)), EPS, i);
            close(a.length(), J.j(a).length(), EPS, i);
            close(a.lengthSquared(), J.j(a).lengthSquared(), EPS, i);
            close(a.distance(b), J.j(a).distance(J.j(b)), EPS, i);
            close(a.distanceSquared(b), J.j(a).distanceSquared(J.j(b)), EPS, i);
            close(a.normalize(), J.j(a).normalize(), EPS, i);
            close(a.normalizeOrZero(), J.j(a).normalize(), EPS, i);
            close(a.lerp(b, t), J.j(a).lerp(J.j(b), t), EPS, i);
            close(a.angle(b), J.j(a).angle(J.j(b)), EPS, i);
        }
    }

    @Test
    void crossIsRightHanded() {
        check(Vec3d.UNIT_X.cross(Vec3d.UNIT_Y).equals(Vec3d.UNIT_Z), 0, "x cross y must be z");
    }

    @Test
    void normalizeOrZeroHandlesZero() {
        check(Vec3d.ZERO.normalizeOrZero().equals(Vec3d.ZERO), 0, "zero stays zero");
        check(Double.isNaN(Vec3d.ZERO.normalize().x()), 0, "plain normalize of zero is NaN, like JOML");
    }

    @Test
    void writeToMatchesJomlLayout() {
        var buf = java.nio.DoubleBuffer.allocate(5);
        var ref = java.nio.DoubleBuffer.allocate(5);
        Vec3d a = rnd.nextVec3d();
        a.writeTo(buf, 2);
        J.j(a).get(2, ref);
        check(buf.equals(ref), 0, "buffer layout differs");
        check(buf.position() == 0, 0, "writeTo must not move the position");
    }
}
