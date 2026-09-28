package vmath.core;

import static vmath.core.Check.check;
import static vmath.core.Check.close;
import static vmath.core.Rnd.N;

import org.junit.jupiter.api.Test;

class Vec2fTest {

    static final float EPS = 1e-4f; // @eps-double 1e-12

    final Rnd rnd = Rnd.create();

    @Test
    void arithmeticMatchesJoml() {
        for (int i = 0; i < N; i++) {
            Vec2f a = rnd.nextVec2f();
            Vec2f b = rnd.nextVec2f();
            float s = (float) rnd.range(-10, 10);
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
            Vec2f a = rnd.nextVec2f();
            Vec2f b = rnd.nextVec2f();
            float t = (float) rnd.range(0, 1);
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
            Vec2f a = rnd.nextVec2f();
            Vec2f p = a.perpendicular();
            close(a.dot(p), 0f, EPS, i);
            check(a.cross(p) > 0f, i, "perpendicular must be CCW");
            close(p.length(), a.length(), EPS, i);
        }
    }

    @Test
    void writeToMatchesJomlLayout() {
        var buf = java.nio.FloatBuffer.allocate(4);
        var ref = java.nio.FloatBuffer.allocate(4);
        Vec2f a = rnd.nextVec2f();
        a.writeTo(buf, 1);
        J.j(a).get(1, ref);
        check(buf.equals(ref), 0, "buffer layout differs");
        check(buf.position() == 0, 0, "writeTo must not move the position");
    }
}
