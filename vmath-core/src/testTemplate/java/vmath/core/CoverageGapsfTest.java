package vmath.core;

import vmath.annotations.Eps;
import vmath.annotations.GenerateDouble;
import static vmath.core.Check.check;
import static vmath.core.Rnd.N;

import org.junit.jupiter.api.Test;

/**
 * Behaviour that mutation testing (docs/COVERAGE.md) found nothing was checking: {@code approxEquals} rejecting values that differ in any single component, the
 * overflow and underflow paths of {@code normalize}, {@code splat}, {@code div}, {@code writeTo} with an offset, the range of {@code toEuler}'s angles, and
 * {@code squadControl} against a closed form.
 */
@GenerateDouble
class CoverageGapsfTest {
    @Eps(d = 1e-12)
    static final float EPS = 1e-4f;
    @Eps(d = 1e-9)
    static final float TOL = 1e-3f;
    /** Large enough that the squared length overflows float (and double, in the generated twin). */
    @Eps(d = 1e200)
    static final float BIG = 1e30f;
    /** Small enough that the squared length underflows to zero. */
    @Eps(d = 1e-200)
    static final float TINY = 1e-30f;

    final Rnd rnd = Rnd.create();

    private static void close(Vec2f a, Vec2f b, float eps, int i) {
        check(a.approxEquals(b, eps), i, a + " differs from " + b);
    }

    private static void close(Vec3f a, Vec3f b, float eps, int i) {
        check(a.approxEquals(b, eps), i, a + " differs from " + b);
    }

    private static void close(Vec4f a, Vec4f b, float eps, int i) {
        check(a.approxEquals(b, eps), i, a + " differs from " + b);
    }

    // ---------------------------------------------------------------- approxEquals: every component counts, in both directions

    @Test
    void approxEqualsRejectsADifferenceInAnySingleComponentAndAcceptsASmallOne() {
        float far = 2f * TOL, near = 0.5f * TOL;
        float[] base = {1f, -2f, 3f, 4f, -5f, 6f, 7f, -8f, 9f};
        for (int k = 0; k < 2; k++) {
            check(!v2(base, 0).approxEquals(v2(shifted(base, k, far), 0), TOL), k, "Vec2 component " + k + " far");
            check(v2(base, 0).approxEquals(v2(shifted(base, k, near), 0), TOL), k, "Vec2 component " + k + " near");
            check(!v2(base, 0).approxEquals(v2(shifted(base, k, -far), 0), TOL), k, "Vec2 component " + k + " far below");
        }
        for (int k = 0; k < 3; k++) {
            check(!v3(base, 0).approxEquals(v3(shifted(base, k, far), 0), TOL), k, "Vec3 component " + k + " far");
            check(v3(base, 0).approxEquals(v3(shifted(base, k, near), 0), TOL), k, "Vec3 component " + k + " near");
            check(!v3(base, 0).approxEquals(v3(shifted(base, k, -far), 0), TOL), k, "Vec3 component " + k + " far below");
        }
        for (int k = 0; k < 4; k++) {
            check(!v4(base, 0).approxEquals(v4(shifted(base, k, far), 0), TOL), k, "Vec4 component " + k + " far");
            check(v4(base, 0).approxEquals(v4(shifted(base, k, near), 0), TOL), k, "Vec4 component " + k + " near");
            check(!v4(base, 0).approxEquals(v4(shifted(base, k, -far), 0), TOL), k, "Vec4 component " + k + " far below");
            Quatf q = new Quatf(base[0], base[1], base[2], base[3]);
            float[] s = shifted(base, k, far);
            check(!q.approxEquals(new Quatf(s[0], s[1], s[2], s[3]), TOL), k, "Quat component " + k + " far");
            s = shifted(base, k, near);
            check(q.approxEquals(new Quatf(s[0], s[1], s[2], s[3]), TOL), k, "Quat component " + k + " near");
        }
        for (int k = 0; k < 9; k++) {
            check(!Mat3f.fromArray(base, 0).approxEquals(Mat3f.fromArray(shifted(base, k, far), 0), TOL), k, "Mat3 element " + k + " far");
            check(Mat3f.fromArray(base, 0).approxEquals(Mat3f.fromArray(shifted(base, k, near), 0), TOL), k, "Mat3 element " + k + " near");
            check(!Mat3f.fromArray(base, 0).approxEquals(Mat3f.fromArray(shifted(base, k, -far), 0), TOL), k, "Mat3 element " + k + " far below");
        }
        Transformf t = new Transformf(new Vec3f(1f, 2f, 3f), new Quatf(0f, 0f, 0f, 1f), new Vec3f(1f, 1f, 1f));
        check(t.approxEquals(t, 0f), 0, "a transform equals itself");
        check(!t.approxEquals(new Transformf(new Vec3f(1f + far, 2f, 3f), new Quatf(0f, 0f, 0f, 1f), new Vec3f(1f, 1f, 1f)), TOL), 0, "translation");
        check(!t.approxEquals(new Transformf(new Vec3f(1f, 2f, 3f), new Quatf(0f, 0.1f, 0f, 0.99f), new Vec3f(1f, 1f, 1f)), TOL), 1, "rotation");
        check(!t.approxEquals(new Transformf(new Vec3f(1f, 2f, 3f), new Quatf(0f, 0f, 0f, 1f), new Vec3f(1f, 1f, 1f + far)), TOL), 2, "scale");
        check(t.approxEquals(new Transformf(new Vec3f(1f, 2f, 3f), new Quatf(0f, 0f, 0f, -1f), new Vec3f(1f, 1f, 1f)), TOL), 3, "q and -q are the same rotation");
        check(t.hasUniformScale(0f), 4, "unit scale is uniform");
        check(!new Transformf(t.translation(), t.rotation(), new Vec3f(1f, 1f + far, 1f)).hasUniformScale(TOL), 5, "y differs");
        check(!new Transformf(t.translation(), t.rotation(), new Vec3f(1f, 1f, 1f + far)).hasUniformScale(TOL), 6, "z differs");
        check(new Transformf(t.translation(), t.rotation(), new Vec3f(1f, 1f + near, 1f)).hasUniformScale(TOL), 7, "within tolerance");
    }

    private static float[] shifted(float[] a, int k, float d) {
        float[] c = a.clone();
        c[k] += d;
        return c;
    }

    private static Vec2f v2(float[] a, int o) {
        return new Vec2f(a[o], a[o + 1]);
    }

    private static Vec3f v3(float[] a, int o) {
        return new Vec3f(a[o], a[o + 1], a[o + 2]);
    }

    private static Vec4f v4(float[] a, int o) {
        return new Vec4f(a[o], a[o + 1], a[o + 2], a[o + 3]);
    }

    // ---------------------------------------------------------------- splat, div, writeTo with an offset

    @Test
    void splatDivAndWriteToWithAnOffset() {
        check(Vec2f.splat(2.5f).equals(new Vec2f(2.5f, 2.5f)), 0, "Vec2 splat");
        check(Vec3f.splat(2.5f).equals(new Vec3f(2.5f, 2.5f, 2.5f)), 0, "Vec3 splat");
        check(Vec4f.splat(2.5f).equals(new Vec4f(2.5f, 2.5f, 2.5f, 2.5f)), 0, "Vec4 splat");
        for (int i = 0; i < N; i++) {
            Vec2f a = rnd.nextVec2f();
            Vec3f b = rnd.nextVec3f();
            Vec4f c = rnd.nextVec4f();
            float s = (float) rnd.range(0.5, 10);
            close(a.div(s), new Vec2f(a.x() / s, a.y() / s), EPS, i);
            close(b.div(s), new Vec3f(b.x() / s, b.y() / s, b.z() / s), EPS, i);
            close(c.div(s), new Vec4f(c.x() / s, c.y() / s, c.z() / s, c.w() / s), EPS, i);
            float[] out = new float[12];
            java.util.Arrays.fill(out, -77f);
            a.writeTo(out, 1);
            b.writeTo(out, 4);
            c.writeTo(out, 8);
            check(out[0] == -77f && out[1] == a.x() && out[2] == a.y() && out[3] == -77f, i, "Vec2 writeTo touches exactly its two slots");
            check(out[4] == b.x() && out[5] == b.y() && out[6] == b.z() && out[7] == -77f, i, "Vec3 writeTo");
            check(out[8] == c.x() && out[9] == c.y() && out[10] == c.z() && out[11] == c.w(), i, "Vec4 writeTo");
        }
    }

    // ---------------------------------------------------------------- normalize at the extremes

    @Test
    void normalizeSurvivesSquaresThatOverflowOrUnderflow() {
        for (float scale : new float[] {BIG, TINY}) {
            Vec2f a = new Vec2f(3f * scale, 4f * scale).normalize();
            close(a, new Vec2f(0.6f, 0.8f), EPS, 0);
            // normalizeOrZero keeps the direction of a huge vector but, by its contract, treats a vector this short as zero
            close(new Vec2f(-3f * scale, 4f * scale).normalizeOrZero(), scale == BIG ? new Vec2f(-0.6f, 0.8f) : new Vec2f(0f, 0f), EPS, 1);
            Vec3f b = new Vec3f(scale, 2f * scale, 2f * scale).normalize();
            close(b, new Vec3f(1f / 3f, 2f / 3f, 2f / 3f), EPS, 2);
            close(new Vec3f(2f * scale, -1f * scale, 2f * scale).normalizeOrZero(), scale == BIG ? new Vec3f(2f / 3f, -1f / 3f, 2f / 3f) : new Vec3f(0f, 0f, 0f), EPS, 3);
            Vec4f c = new Vec4f(scale, scale, scale, scale).normalize();
            close(c, new Vec4f(0.5f, 0.5f, 0.5f, 0.5f), EPS, 4);
            close(new Vec4f(scale, -scale, scale, -scale).normalizeOrZero(), scale == BIG ? new Vec4f(0.5f, -0.5f, 0.5f, -0.5f) : new Vec4f(0f, 0f, 0f, 0f), EPS, 5);
            close(new Vec4f(1f * scale, 2f * scale, 3f * scale, 4f * scale).normalize(), new Vec4f(1f, 2f, 3f, 4f).normalize(), EPS, 6);
            Quatf q = new Quatf(scale, scale, scale, scale).normalize();
            close(new Vec4f(q.x(), q.y(), q.z(), q.w()), new Vec4f(0.5f, 0.5f, 0.5f, 0.5f), EPS, 7);
        }
        check(new Vec3f(0f, 0f, 0f).normalizeOrZero().equals(new Vec3f(0f, 0f, 0f)), 8, "zero stays zero");
    }

    // ---------------------------------------------------------------- Euler angle ranges

    @Test
    void eulerAnglesLieInTheirDocumentedRanges() {
        for (EulerOrder order : EulerOrder.values()) {
            boolean proper = order.first() == order.third();
            for (int i = 0; i < N; i++) {
                Quatf q = new Quatf((float) rnd.range(-1, 1), (float) rnd.range(-1, 1), (float) rnd.range(-1, 1), (float) rnd.range(-1, 1)).normalize();
                Vec3f e = q.toEuler(order);
                float pi = (float) Math.PI;
                check(e.x() >= -pi - EPS && e.x() <= pi + EPS, i, order + " first angle in [-pi, pi]: " + e.x());
                check(e.z() >= -pi - EPS && e.z() <= pi + EPS, i, order + " third angle in [-pi, pi]: " + e.z());
                if (proper) {
                    check(e.y() >= -EPS && e.y() <= pi + EPS, i, order + " middle angle in [0, pi]: " + e.y());
                } else {
                    check(e.y() >= -pi / 2 - EPS && e.y() <= pi / 2 + EPS, i, order + " middle angle in [-pi/2, pi/2]: " + e.y());
                }
            }
        }
    }

    // ---------------------------------------------------------------- squadControl against a closed form

    @Test
    void squadControlOfRotationsAboutOneAxisHasAClosedForm() {
        // all three keys rotate about the same axis by angles p, c, n: the control point rotates by c - ((n - c) + (p - c)) / 4
        for (int i = 0; i < N; i++) {
            Vec3f axis = rnd.nextVec3f().normalize();
            // the neighbours stay within 1.5 radians of the key, so that the shortest arc between keys is the arc the closed form uses
            float c = (float) rnd.range(-1, 1), p = c + (float) rnd.range(-1.5, 1.5), n = c + (float) rnd.range(-1.5, 1.5);
            Quatf prev = Quatf.fromAxisAngle(p, axis), cur = Quatf.fromAxisAngle(c, axis), next = Quatf.fromAxisAngle(n, axis);
            Quatf expected = Quatf.fromAxisAngle(c - ((n - c) + (p - c)) / 4f, axis);
            Quatf control = Quatf.squadControl(prev, cur, next);
            check(control.sameRotation(expected, EPS * 10), i, "control " + control + " expected " + expected);
            // the other hemisphere of the same rotation gives the same control rotation
            Quatf negatedNext = new Quatf(-next.x(), -next.y(), -next.z(), -next.w());
            Quatf negatedPrev = new Quatf(-prev.x(), -prev.y(), -prev.z(), -prev.w());
            check(Quatf.squadControl(negatedPrev, cur, negatedNext).sameRotation(expected, EPS * 10), i, "negated neighbours");
        }
        // equally spaced keys: the control point is the key itself
        Vec3f axis = new Vec3f(0f, 1f, 0f);
        Quatf cur = Quatf.fromAxisAngle(0.7f, axis);
        check(Quatf.squadControl(Quatf.fromAxisAngle(0.4f, axis), cur, Quatf.fromAxisAngle(1.0f, axis)).sameRotation(cur, EPS), 0, "symmetric keys");
    }
}
