package vmath.core;

import vmath.annotations.Eps;
import vmath.annotations.GenerateDouble;
import static vmath.core.Check.check;
import static vmath.core.Check.close;
import static vmath.core.Rnd.N;

import org.junit.jupiter.api.Test;

@GenerateDouble
class Vec4fTest {
    @Eps(d = 1e-12)
    static final float EPS = 1e-4f;

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

    @Test
    void vec4ExtrasMatchJoml() {
        for (int i = 0; i < N; i++) {
            Vec4f a = rnd.nextVec4f();
            Vec4f b = rnd.nextVec4f();
            float s = (float) rnd.range(-10, 10);
            close(a.add(b.x(), b.y(), b.z(), b.w()), J.j(a).add(J.j(b)), EPS, i);
            close(a.div(s), J.j(a).div(s), EPS, i);
            close(a.fma(b, s), J.j(a).add(J.j(b).mul(s)), EPS, i);
            close(a.distanceSquared(b), J.j(a).distanceSquared(J.j(b)), EPS, i);
            close(a.distance(b), J.j(a).distance(J.j(b)), EPS, i);
            close(a.min(b), J.j(a).min(J.j(b)), EPS, i);
            close(a.max(b), J.j(a).max(J.j(b)), EPS, i);
            close(a.abs(), J.j(a).absolute(), EPS, i);
            close(a.normalizeOrZero(), J.j(a).normalize(), EPS, i);
        }
        check(Vec4f.ZERO.normalizeOrZero().equals(Vec4f.ZERO), 0, "zero stays zero");
    }

    // ------------------------------------------------------------ toolbox

    @Test
    void componentToolboxMatchesScalarReference() {
        for (int i = 0; i < N; i++) {
            Vec4f a = rnd.nextVec4f();
            Vec4f lo = rnd.nextVec4f().mul(0.5f);
            Vec4f hi = lo.add(Vec4f.ONE.mul((float) rnd.range(0.1, 5)));
            float e0 = (float) rnd.range(-5, 0), e1 = (float) rnd.range(1, 6);
            float edge = (float) rnd.range(-5, 5);
            for (int c = 0; c < 4; c++) {
                float v = a.get(c);
                close(a.clamp(lo, hi).get(c), Math.min(Math.max(v, lo.get(c)), hi.get(c)), EPS, i);
                close(a.clamp(-2f, 3f).get(c), Math.min(Math.max(v, -2f), 3f), EPS, i);
                close(a.saturate().get(c), Math.min(Math.max(v, 0f), 1f), EPS, i);
                close(a.floor().get(c), Math.floor(v), EPS, i);
                close(a.ceil().get(c), Math.ceil(v), EPS, i);
                close(a.fract().get(c), v - Math.floor(v), EPS, i);
                check(a.fract().get(c) >= 0f && a.fract().get(c) < 1f, i, "fract must be in [0,1)");
                close(a.sign().get(c), Math.signum(v), EPS, i);
                close(a.step(edge).get(c), v < edge ? 0.0 : 1.0, EPS, i);
                double vd = v, e0d = e0, e1d = e1;
                double t = Math.min(Math.max((vd - e0d) / (e1d - e0d), 0.0), 1.0);
                close(a.smoothstep(e0, e1).get(c), t * t * (3.0 - 2.0 * t), EPS, i);
            }
            float mn = a.get(0), mx = a.get(0);
            for (int c = 1; c < 4; c++) {
                mn = Math.min(mn, a.get(c));
                mx = Math.max(mx, a.get(c));
            }
            close(a.minComponent(), mn, EPS, i);
            close(a.maxComponent(), mx, EPS, i);
        }
    }

    @Test
    void isFiniteDetectsNaNAndInfinity() {
        check(Vec4f.ONE.isFinite(), 0, "ones are finite");
        for (int c = 0; c < 4; c++) {
            float[] v = new float[4];
            java.util.Arrays.fill(v, 1f);
            v[c] = Float.NaN;
            check(!of(v).isFinite(), c, "NaN component " + c);
            v[c] = Float.POSITIVE_INFINITY;
            check(!of(v).isFinite(), c, "infinite component " + c);
        }
    }

    private static Vec4f of(float[] v) {
        return new Vec4f(v[0], v[1], v[2], v[3]);
    }

    @Test
    void projectAndRejectSplitTheVector() {
        for (int i = 0; i < N; i++) {
            Vec4f a = rnd.nextVec4f();
            Vec4f onto = rnd.nextVec4f();
            Vec4f p = a.project(onto);
            Vec4f q = a.reject(onto);
            close(p.add(q), a, EPS, i);
            close(q.dot(onto), 0.0, EPS * 10, i);
            close(p.lengthSquared() * onto.lengthSquared(), p.dot(onto) * p.dot(onto), EPS * 100, i);
        }
        check(rnd.nextVec4f().project(Vec4f.ZERO).equals(Vec4f.ZERO), 0, "projecting onto zero yields zero");
    }

    @Test
    void faceForwardFlipsAgainstTheReference() {
        for (int i = 0; i < N; i++) {
            Vec4f n = rnd.nextVec4f();
            Vec4f inc = rnd.nextVec4f();
            Vec4f ref = rnd.nextVec4f();
            Vec4f out = n.faceForward(inc, ref);
            check(out.equals(ref.dot(inc) < 0f ? n : n.negate()), i, "faceForward must follow the sign of ref.incident");
        }
    }
}
