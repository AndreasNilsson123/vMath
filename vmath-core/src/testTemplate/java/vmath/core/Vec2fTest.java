package vmath.core;

import vmath.annotations.Eps;
import vmath.annotations.GenerateDouble;
import static vmath.core.Check.check;
import static vmath.core.Check.close;
import static vmath.core.Rnd.N;

import org.junit.jupiter.api.Test;

@GenerateDouble
class Vec2fTest {
    @Eps(d = 1e-12)
    static final float EPS = 1e-4f;

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

    @Test
    void vec2ExtrasBehave() {
        for (int i = 0; i < N; i++) {
            Vec2f a = rnd.nextVec2f();
            Vec2f b = rnd.nextVec2f();
            float ang = (float) rnd.range(-7, 7);
            close(a.add(b.x(), b.y()), a.add(b), EPS, i);
            close(a.distanceSquared(b), a.sub(b).lengthSquared(), EPS, i);
            close(a.abs().x(), Math.abs(a.x()), EPS, i);
            close(a.normalizeOrZero(), a.normalize(), EPS, i);
            close(a.rotate(ang).length(), a.length(), EPS, i);
            close(a.rotate(ang).rotate(-ang), a, EPS, i);
            close(a.angle(b), Math.acos(Math.max(-1.0, Math.min(1.0, a.dot(b) / (a.length() * b.length())))), EPS * 10, i);
        }
        check(Vec2f.ZERO.normalizeOrZero().equals(Vec2f.ZERO), 0, "zero stays zero");
        close(Vec2f.UNIT_X.rotate((float) (Math.PI / 2)), Vec2f.UNIT_Y, EPS, 0);
    }

    // ------------------------------------------------------------ toolbox

    @Test
    void componentToolboxMatchesScalarReference() {
        for (int i = 0; i < N; i++) {
            Vec2f a = rnd.nextVec2f();
            Vec2f lo = rnd.nextVec2f().mul(0.5f);
            Vec2f hi = lo.add(Vec2f.ONE.mul((float) rnd.range(0.1, 5)));
            float e0 = (float) rnd.range(-5, 0), e1 = (float) rnd.range(1, 6);
            float edge = (float) rnd.range(-5, 5);
            for (int c = 0; c < 2; c++) {
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
            for (int c = 1; c < 2; c++) {
                mn = Math.min(mn, a.get(c));
                mx = Math.max(mx, a.get(c));
            }
            close(a.minComponent(), mn, EPS, i);
            close(a.maxComponent(), mx, EPS, i);
        }
    }

    @Test
    void isFiniteDetectsNaNAndInfinity() {
        check(Vec2f.ONE.isFinite(), 0, "ones are finite");
        for (int c = 0; c < 2; c++) {
            float[] v = new float[2];
            java.util.Arrays.fill(v, 1f);
            v[c] = Float.NaN;
            check(!of(v).isFinite(), c, "NaN component " + c);
            v[c] = Float.POSITIVE_INFINITY;
            check(!of(v).isFinite(), c, "infinite component " + c);
        }
    }

    private static Vec2f of(float[] v) {
        return new Vec2f(v[0], v[1]);
    }

    @Test
    void projectAndRejectSplitTheVector() {
        for (int i = 0; i < N; i++) {
            Vec2f a = rnd.nextVec2f();
            Vec2f onto = rnd.nextVec2f();
            Vec2f p = a.project(onto);
            Vec2f q = a.reject(onto);
            close(p.add(q), a, EPS, i);
            close(q.dot(onto), 0.0, EPS * 10, i);
            close(p.lengthSquared() * onto.lengthSquared(), p.dot(onto) * p.dot(onto), EPS * 100, i);
        }
        check(rnd.nextVec2f().project(Vec2f.ZERO).equals(Vec2f.ZERO), 0, "projecting onto zero yields zero");
    }

    @Test
    void faceForwardFlipsAgainstTheReference() {
        for (int i = 0; i < N; i++) {
            Vec2f n = rnd.nextVec2f();
            Vec2f inc = rnd.nextVec2f();
            Vec2f ref = rnd.nextVec2f();
            Vec2f out = n.faceForward(inc, ref);
            check(out.equals(ref.dot(inc) < 0f ? n : n.negate()), i, "faceForward must follow the sign of ref.incident");
        }
    }

    @Test
    void reflectPreservesLengthAndMirrorsTheNormalComponent() {
        for (int i = 0; i < N; i++) {
            Vec2f a = rnd.nextVec2f();
            Vec2f nrm = rnd.nextVec2f().normalize();
            Vec2f m = a.reflect(nrm);
            close(m.length(), a.length(), EPS, i);
            close(m.dot(nrm), -a.dot(nrm), EPS, i);
            close(m.reflect(nrm), a, EPS, i);
        }
    }

    @Test
    void refractFollowsSnellsLaw() {
        int refracted = 0;
        for (int i = 0; i < N; i++) {
            Vec2f inc = rnd.nextVec2f().normalize();
            Vec2f nrm = rnd.nextVec2f().normalize();
            if (inc.dot(nrm) > 0f) {
                nrm = nrm.negate(); // normal must face the incident ray
            }
            float eta = (float) rnd.range(0.5, 1.5);
            Vec2f t = inc.refract(nrm, eta);
            float sin2 = 1f - inc.dot(nrm) * inc.dot(nrm);
            if (1f - eta * eta * sin2 < 0f) {
                check(t.equals(Vec2f.ZERO), i, "total internal reflection yields zero");
                continue;
            }
            refracted++;
            close(t.length(), 1.0, EPS * 10, i);
            // the tangential component scales by eta, and the ray keeps crossing the surface
            close(t.reject(nrm).length(), eta * inc.reject(nrm).length(), EPS * 10, i);
            check(t.dot(nrm) < 1e-3f, i, "refracted ray must continue through the surface");
        }
        check(refracted > N / 4, 0, "too few refracting samples");
    }

    @Test
    void refractWithEqualIndicesIsIdentity() {
        for (int i = 0; i < N; i++) {
            Vec2f inc = rnd.nextVec2f().normalize();
            Vec2f nrm = rnd.nextVec2f().normalize();
            if (inc.dot(nrm) > 0f) {
                nrm = nrm.negate();
            }
            close(inc.refract(nrm, 1f), inc, EPS * 10, i);
        }
    }
}
