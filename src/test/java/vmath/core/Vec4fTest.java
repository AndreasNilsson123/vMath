package vmath.core;

import static vmath.core.Check.check;
import static vmath.core.Check.close;
import static vmath.core.Rnd.N;

import org.junit.jupiter.api.Test;

class Vec4fTest {

    static final float EPS = 1e-4f; // @eps-double 1e-12

    final Rnd rnd = Rnd.create();

    @Test
    void arithmeticMatchesJoml() {
        for (int i = 0; i < N; i++) {
            Vec4f a = rnd.nextVec4f();
            Vec4f b = rnd.nextVec4f();
            float s = (float) rnd.range(-10, 10);
            float t = (float) rnd.range(0, 1);
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
            Vec3f v = rnd.nextVec3f();
            check(Vec4f.point(v).w() == 1f && Vec4f.direction(v).w() == 0f, i, "w of point/direction");
            check(Vec4f.point(v).xyz().equals(v), i, "xyz round trip");
            Vec4f h = Vec4f.point(v).mul(3f);
            close(h.divideByW(), v, EPS, i);
        }
    }

    @Test
    void writeToMatchesJomlLayout() {
        var buf = java.nio.FloatBuffer.allocate(6);
        var ref = java.nio.FloatBuffer.allocate(6);
        Vec4f a = rnd.nextVec4f();
        a.writeTo(buf, 1);
        J.j(a).get(1, ref);
        check(buf.equals(ref), 0, "buffer layout differs");
    }
}
