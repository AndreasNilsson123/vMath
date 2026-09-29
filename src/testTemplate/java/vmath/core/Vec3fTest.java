package vmath.core;

import vmath.annotations.Eps;
import vmath.annotations.GenerateDouble;
import static vmath.core.Check.check;
import static vmath.core.Check.close;
import static vmath.core.Rnd.N;

import org.junit.jupiter.api.Test;

@GenerateDouble
class Vec3fTest {
    @Eps(d = 1e-12)
    static final float EPS = 1e-4f;

    final Rnd rnd = Rnd.create();

    @Test
    void arithmeticMatchesJoml() {
        for (int i = 0; i < N; i++) {
            Vec3f a = rnd.nextVec3f();
            Vec3f b = rnd.nextVec3f();
            float s = (float) rnd.range(-10, 10);
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
            Vec3f a = rnd.nextVec3f();
            Vec3f b = rnd.nextVec3f();
            float t = (float) rnd.range(0, 1);
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
        check(Vec3f.UNIT_X.cross(Vec3f.UNIT_Y).equals(Vec3f.UNIT_Z), 0, "x cross y must be z");
    }

    @Test
    void normalizeOrZeroHandlesZero() {
        check(Vec3f.ZERO.normalizeOrZero().equals(Vec3f.ZERO), 0, "zero stays zero");
        check(Float.isNaN(Vec3f.ZERO.normalize().x()), 0, "plain normalize of zero is NaN, like JOML");
    }

    @Test
    void writeToMatchesJomlLayout() {
        var buf = java.nio.FloatBuffer.allocate(5);
        var ref = java.nio.FloatBuffer.allocate(5);
        Vec3f a = rnd.nextVec3f();
        a.writeTo(buf, 2);
        J.j(a).get(2, ref);
        check(buf.equals(ref), 0, "buffer layout differs");
        check(buf.position() == 0, 0, "writeTo must not move the position");
    }

    @Test
    void anyPerpendicularIsUnitAndPerpendicular() {
        for (int i = 0; i < N; i++) {
            Vec3f a = rnd.nextVec3f();
            Vec3f p = a.anyPerpendicular();
            close(p.length(), 1.0, EPS, i);
            close(p.dot(a.normalize()), 0.0, EPS, i);
        }
        for (Vec3f axis : new Vec3f[] {Vec3f.UNIT_X, Vec3f.UNIT_Y, Vec3f.UNIT_Z}) {
            close(axis.anyPerpendicular().dot(axis), 0.0, EPS, 0);
        }
    }

    // ------------------------------------------------------------ toolbox

    @Test
    void componentToolboxMatchesScalarReference() {
        for (int i = 0; i < N; i++) {
            Vec3f a = rnd.nextVec3f();
            Vec3f lo = rnd.nextVec3f().mul(0.5f);
            Vec3f hi = lo.add(Vec3f.ONE.mul((float) rnd.range(0.1, 5)));
            float e0 = (float) rnd.range(-5, 0), e1 = (float) rnd.range(1, 6);
            float edge = (float) rnd.range(-5, 5);
            for (int c = 0; c < 3; c++) {
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
            for (int c = 1; c < 3; c++) {
                mn = Math.min(mn, a.get(c));
                mx = Math.max(mx, a.get(c));
            }
            close(a.minComponent(), mn, EPS, i);
            close(a.maxComponent(), mx, EPS, i);
        }
    }

    @Test
    void isFiniteDetectsNaNAndInfinity() {
        check(Vec3f.ONE.isFinite(), 0, "ones are finite");
        for (int c = 0; c < 3; c++) {
            float[] v = new float[3];
            java.util.Arrays.fill(v, 1f);
            v[c] = Float.NaN;
            check(!of(v).isFinite(), c, "NaN component " + c);
            v[c] = Float.POSITIVE_INFINITY;
            check(!of(v).isFinite(), c, "infinite component " + c);
        }
    }

    private static Vec3f of(float[] v) {
        return new Vec3f(v[0], v[1], v[2]);
    }

    @Test
    void projectAndRejectSplitTheVector() {
        for (int i = 0; i < N; i++) {
            Vec3f a = rnd.nextVec3f();
            Vec3f onto = rnd.nextVec3f();
            Vec3f p = a.project(onto);
            Vec3f q = a.reject(onto);
            close(p.add(q), a, EPS, i);
            close(q.dot(onto), 0.0, EPS * 10, i);
            close(p.lengthSquared() * onto.lengthSquared(), p.dot(onto) * p.dot(onto), EPS * 100, i);
        }
        check(rnd.nextVec3f().project(Vec3f.ZERO).equals(Vec3f.ZERO), 0, "projecting onto zero yields zero");
    }

    @Test
    void faceForwardFlipsAgainstTheReference() {
        for (int i = 0; i < N; i++) {
            Vec3f n = rnd.nextVec3f();
            Vec3f inc = rnd.nextVec3f();
            Vec3f ref = rnd.nextVec3f();
            Vec3f out = n.faceForward(inc, ref);
            check(out.equals(ref.dot(inc) < 0f ? n : n.negate()), i, "faceForward must follow the sign of ref.incident");
        }
    }

    @Test
    void reflectPreservesLengthAndMirrorsTheNormalComponent() {
        for (int i = 0; i < N; i++) {
            Vec3f a = rnd.nextVec3f();
            Vec3f nrm = rnd.nextVec3f().normalize();
            Vec3f m = a.reflect(nrm);
            close(m.length(), a.length(), EPS, i);
            close(m.dot(nrm), -a.dot(nrm), EPS, i);
            close(m.reflect(nrm), a, EPS, i);
        }
    }

    @Test
    void refractFollowsSnellsLaw() {
        int refracted = 0;
        for (int i = 0; i < N; i++) {
            Vec3f inc = rnd.nextVec3f().normalize();
            Vec3f nrm = rnd.nextVec3f().normalize();
            if (inc.dot(nrm) > 0f) {
                nrm = nrm.negate(); // normal must face the incident ray
            }
            float eta = (float) rnd.range(0.5, 1.5);
            Vec3f t = inc.refract(nrm, eta);
            float sin2 = 1f - inc.dot(nrm) * inc.dot(nrm);
            if (1f - eta * eta * sin2 < 0f) {
                check(t.equals(Vec3f.ZERO), i, "total internal reflection yields zero");
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
            Vec3f inc = rnd.nextVec3f().normalize();
            Vec3f nrm = rnd.nextVec3f().normalize();
            if (inc.dot(nrm) > 0f) {
                nrm = nrm.negate();
            }
            close(inc.refract(nrm, 1f), inc, EPS * 10, i);
        }
    }
}
