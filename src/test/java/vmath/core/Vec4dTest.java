package vmath.core;

// GENERATED from Vec4fTest.java by tools/GenDouble.java. Do not edit; edit the float source.

import static vmath.core.Check.check;
import static vmath.core.Check.close;
import static vmath.core.Rnd.N;

import org.junit.jupiter.api.Test;

class Vec4dTest {

    static final double EPS = 1e-12;

    final Rnd rnd = Rnd.create();

    @Test
    void arithmeticMatchesJoml() {
        for (int i = 0; i < N; i++) {
            Vec4d a = rnd.nextVec4d();
            Vec4d b = rnd.nextVec4d();
            double s = rnd.range(-10, 10);
            double t = rnd.range(0, 1);
            close(a.add(b), J.j(a).add(J.j(b)), EPS, i);
            close(a.sub(b), J.j(a).sub(J.j(b)), EPS, i);
            close(a.mul(s), J.j(a).mul(s), EPS, i);
            close(a.mul(b), J.j(a).mul(J.j(b)), EPS, i);
            close(a.negate(), J.j(a).negate(), EPS, i);
            close(a.dot(b), J.j(a).dot(J.j(b)), EPS, i);
            close(a.length(), J.j(a).length(), EPS, i);
            close(a.normalize(), J.j(a).normalize(), EPS, i);
            close(a.lerp(b, t), J.j(a).lerp(J.j(b), t), EPS, i);
        }
    }

    @Test
    void homogeneousHelpers() {
        for (int i = 0; i < N; i++) {
            Vec3d v = rnd.nextVec3d();
            check(Vec4d.point(v).w() == 1.0 && Vec4d.direction(v).w() == 0.0, i, "w of point/direction");
            check(Vec4d.point(v).xyz().equals(v), i, "xyz round trip");
            Vec4d h = Vec4d.point(v).mul(3.0);
            close(h.divideByW(), v, EPS, i);
        }
    }

    @Test
    void writeToMatchesJomlLayout() {
        var buf = java.nio.DoubleBuffer.allocate(6);
        var ref = java.nio.DoubleBuffer.allocate(6);
        Vec4d a = rnd.nextVec4d();
        a.writeTo(buf, 1);
        J.j(a).get(1, ref);
        check(buf.equals(ref), 0, "buffer layout differs");
    }
}
