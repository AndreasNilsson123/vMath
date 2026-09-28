package vmath.core;

// GENERATED from Vec2fTest.java by tools/GenDouble.java. Do not edit; edit the float source.

import static vmath.core.Check.check;
import static vmath.core.Check.close;
import static vmath.core.Rnd.N;

import org.junit.jupiter.api.Test;

class Vec2dTest {

    static final double EPS = 1e-12;

    final Rnd rnd = Rnd.create();

    @Test
    void arithmeticMatchesJoml() {
        for (int i = 0; i < N; i++) {
            Vec2d a = rnd.nextVec2d();
            Vec2d b = rnd.nextVec2d();
            double s = rnd.range(-10, 10);
            close(a.add(b), J.j(a).add(J.j(b)), EPS, i);
            close(a.sub(b), J.j(a).sub(J.j(b)), EPS, i);
            close(a.mul(s), J.j(a).mul(s), EPS, i);
            close(a.mul(b), J.j(a).mul(J.j(b)), EPS, i);
            close(a.negate(), J.j(a).negate(), EPS, i);
            close(a.fma(b, s), J.j(a).add(J.j(b).mul(s)), EPS, i);
            close(a.min(b), J.j(a).min(J.j(b)), EPS, i);
            close(a.max(b), J.j(a).max(J.j(b)), EPS, i);
        }
    }

    @Test
    void metricsMatchJoml() {
        for (int i = 0; i < N; i++) {
            Vec2d a = rnd.nextVec2d();
            Vec2d b = rnd.nextVec2d();
            double t = rnd.range(0, 1);
            close(a.dot(b), J.j(a).dot(J.j(b)), EPS, i);
            close(a.length(), J.j(a).length(), EPS, i);
            close(a.distance(b), J.j(a).distance(J.j(b)), EPS, i);
            close(a.normalize(), J.j(a).normalize(), EPS, i);
            close(a.lerp(b, t), J.j(a).lerp(J.j(b), t), EPS, i);
        }
    }

    @Test
    void perpendicularIsCounterClockwise() {
        for (int i = 0; i < N; i++) {
            Vec2d a = rnd.nextVec2d();
            Vec2d p = a.perpendicular();
            close(a.dot(p), 0.0, EPS, i);
            check(a.cross(p) > 0.0, i, "perpendicular must be CCW");
            close(p.length(), a.length(), EPS, i);
        }
    }

    @Test
    void writeToMatchesJomlLayout() {
        var buf = java.nio.DoubleBuffer.allocate(4);
        var ref = java.nio.DoubleBuffer.allocate(4);
        Vec2d a = rnd.nextVec2d();
        a.writeTo(buf, 1);
        J.j(a).get(1, ref);
        check(buf.equals(ref), 0, "buffer layout differs");
        check(buf.position() == 0, 0, "writeTo must not move the position");
    }
}
