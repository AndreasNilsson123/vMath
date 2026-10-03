package vmath.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vmath.annotations.Eps;
import vmath.annotations.GenerateDouble;

/**
 * INF-7: the expected behaviour of the core operations on degenerate input, one case at a time, in both precisions ({@code docs/ROBUSTNESS.md} is the readable
 * version). The constants are relative to the type so that the same test means "largest", "smallest" and "subnormal" in float and in double.
 */
@GenerateDouble
class DegenerateContractfTest {
    @Eps(d = 1e-13)
    static final float EPS = 1e-6f;

    /** Near the top of the range: its square overflows. */
    static final float HUGE = Float.MAX_VALUE * 0.9f;
    /** Subnormal: its square underflows to zero. */
    static final float TINY = Float.MIN_VALUE * 4f;
    static final float NAN = Float.NaN;
    static final float INF = Float.POSITIVE_INFINITY;

    private static boolean allNaN(Vec3f v) {
        return Float.isNaN(v.x()) && Float.isNaN(v.y()) && Float.isNaN(v.z());
    }

    private static boolean allNaN(Quatf q) {
        return Float.isNaN(q.x()) && Float.isNaN(q.y()) && Float.isNaN(q.z()) && Float.isNaN(q.w());
    }

    // ---------------------------------------------------------------- normalize

    @Test
    void normalizeOfZeroNaNAndInfinityIsNaN() {
        assertTrue(allNaN(Vec3f.ZERO.normalize()), "zero");
        assertTrue(allNaN(new Vec3f(-0f, 0f, -0f).normalize()), "negative zeros");
        assertTrue(allNaN(new Vec3f(NAN, 1f, 0f).normalize()), "NaN");
        assertTrue(allNaN(new Vec3f(INF, 1f, 0f).normalize()), "infinity");
        assertTrue(allNaN(new Vec3f(-INF, INF, 0f).normalize()), "both infinities");
        assertTrue(Float.isNaN(Vec2f.ZERO.normalize().x()) && Float.isNaN(Vec2f.ZERO.normalize().y()));
        assertTrue(Float.isNaN(Vec4f.ZERO.normalize().w()));
        assertTrue(allNaN(new Quatf(0f, 0f, 0f, 0f).normalize()), "the zero quaternion");
        assertTrue(allNaN(new Quatf(NAN, 0f, 0f, 1f).normalize()));
    }

    @Test
    void normalizeKeepsTheDirectionOfHugeAndTinyVectors() {
        Vec3f h = new Vec3f(HUGE, 0f, 0f).normalize();
        assertEquals(1f, h.x(), EPS);
        assertEquals(0f, h.y());
        Vec3f diag = new Vec3f(HUGE, HUGE, HUGE).normalize();
        float third = (float) (1.0 / Math.sqrt(3.0));
        assertEquals(third, diag.x(), EPS);
        assertEquals(third, diag.y(), EPS);
        assertEquals(third, diag.z(), EPS);
        Vec3f t = new Vec3f(TINY, 0f, 0f).normalize();
        assertEquals(1f, t.x(), EPS, "a subnormal vector still has a direction");
        assertEquals(0f, t.y());
        Vec3f mixed = new Vec3f(HUGE, TINY, -TINY).normalize();
        assertEquals(1f, mixed.x(), EPS);
        Vec2f v2 = new Vec2f(3f * (HUGE / 5f), 4f * (HUGE / 5f)).normalize();
        assertEquals(0.6f, v2.x(), EPS);
        assertEquals(0.8f, v2.y(), EPS);
        Vec4f v4 = new Vec4f(HUGE, HUGE, HUGE, HUGE).normalize();
        assertEquals(0.5f, v4.x(), EPS);
        Quatf q = new Quatf(HUGE, 0f, 0f, HUGE).normalize();
        assertEquals((float) Math.sqrt(0.5), q.x(), EPS);
        assertEquals((float) Math.sqrt(0.5), q.w(), EPS);
        Quatf qt = new Quatf(TINY, 0f, 0f, TINY).normalize();
        assertEquals((float) Math.sqrt(0.5), qt.w(), EPS);
    }

    @Test
    void normalizeIsUnitLengthAcrossTheWholeExponentRange() {
        // from the smallest subnormal to just below the overflow of the square
        for (int k = Float.MIN_EXPONENT - 23; k <= Float.MAX_EXPONENT - 1; k++) {
            float s = Math.scalb(1f, k);
            Vec3f n = new Vec3f(s, s * 0.5f, -s * 0.25f).normalize();
            double len = Math.sqrt(1.0 * n.x() * n.x() + 1.0 * n.y() * n.y() + 1.0 * n.z() * n.z());
            // a subnormal has few significant bits, so its direction is only as good as those bits: compare with the exact direction of the rounded components
            assertEquals(1.0, len, 1e-5, "exponent " + k);
        }
    }

    @Test
    void normalizeOrZero() {
        assertEquals(Vec3f.ZERO, Vec3f.ZERO.normalizeOrZero());
        assertEquals(Vec3f.ZERO, new Vec3f(TINY, 0f, 0f).normalizeOrZero(), "squared length at or below 1e-30 counts as zero");
        assertEquals(Vec3f.ZERO, new Vec3f(1e-16f, 0f, 0f).normalizeOrZero());
        assertEquals(1f, new Vec3f(1e-10f, 0f, 0f).normalizeOrZero().x(), EPS, "above the threshold it normalizes");
        assertEquals(1f, new Vec3f(HUGE, 0f, 0f).normalizeOrZero().x(), EPS, "a huge vector keeps its direction (it used to become the zero vector)");
        assertTrue(allNaN(new Vec3f(NAN, 0f, 0f).normalizeOrZero()), "NaN is not hidden");
        assertTrue(allNaN(new Vec3f(INF, 0f, 0f).normalizeOrZero()));
    }

    // ---------------------------------------------------------------- lengths and overflow

    @Test
    void lengthsUseTheDirectFormulaSoTheyOverflowAndUnderflow() {
        assertEquals(INF, new Vec3f(HUGE, 0f, 0f).length(), "the square overflows although the length is representable");
        assertEquals(INF, new Vec3f(HUGE, 0f, 0f).lengthSquared());
        assertEquals(0f, new Vec3f(TINY, 0f, 0f).length(), "the square underflows");
        assertEquals(INF, Vec3f.ZERO.distance(new Vec3f(INF, 0f, 0f)));
        assertTrue(Float.isNaN(new Vec3f(NAN, 0f, 0f).length()));
        assertEquals(INF, new Quatf(HUGE, 0f, 0f, 0f).length());
    }

    // ---------------------------------------------------------------- vector operations

    @Test
    void vectorOperationsOnDegenerateVectors() {
        Vec3f x = Vec3f.UNIT_X;
        assertEquals(Vec3f.ZERO, x.cross(x), "parallel vectors");
        assertEquals(Vec3f.ZERO, x.cross(x.mul(-3f)));
        assertTrue(Float.isNaN(x.cross(new Vec3f(NAN, 0f, 0f)).y()));
        assertEquals(0f, x.angle(Vec3f.ZERO), "the angle to a zero vector is 0");
        assertEquals((float) Math.PI, x.angle(x.mul(-1f)), EPS);
        assertEquals(0f, x.angle(x), EPS);
        assertEquals(Vec3f.ZERO, x.project(Vec3f.ZERO), "projection onto a zero vector is zero");
        assertEquals(x, x.reject(Vec3f.ZERO), "rejection from a zero vector leaves the vector");
        assertEquals(x, x.reflect(Vec3f.ZERO), "reflection in a zero normal leaves the vector");
        assertEquals(Vec3f.ZERO, x.refract(Vec3f.ZERO, 1.5f), "total internal reflection convention: the zero vector");
        assertTrue(allNaN(x.lerp(Vec3f.UNIT_Y, NAN)), "NaN is not hidden by a lerp");
        assertEquals(INF, x.div(0f).x(), "IEEE division");
        assertTrue(Float.isNaN(x.div(0f).y()), "0 / 0");
        assertTrue(allNaN(Vec3f.ZERO.div(0f)));
        assertTrue(allNaN(Vec3f.ZERO.anyPerpendicular()), "no perpendicular to a zero vector");
        Vec3f p = x.anyPerpendicular();
        assertEquals(0f, p.dot(x), EPS);
        assertEquals(1f, p.length(), EPS);
    }

    // ---------------------------------------------------------------- quaternions

    @Test
    void quaternionConstructionFromDegenerateInput() {
        Quatf opposite = Quatf.fromTo(Vec3f.UNIT_X, Vec3f.UNIT_X.mul(-1f));
        assertEquals(1f, opposite.length(), EPS, "opposite directions: a valid half turn");
        assertEquals(0f, opposite.w(), EPS);
        assertEquals(Vec3f.UNIT_X.mul(-1f).x(), opposite.transform(Vec3f.UNIT_X).x(), 1e-5f);
        assertTrue(allNaN(Quatf.fromTo(Vec3f.ZERO, Vec3f.UNIT_X)), "no direction to start from");
        assertTrue(allNaN(Quatf.lookRotation(Vec3f.UNIT_X, Vec3f.UNIT_X)), "up parallel to forward");
        assertTrue(allNaN(Quatf.lookRotation(Vec3f.ZERO, Vec3f.UNIT_Y)), "no forward direction");
        Quatf zeroAxis = Quatf.fromAxisAngle(1f, Vec3f.ZERO);
        assertTrue(Float.isNaN(zeroAxis.x()) && Float.isNaN(zeroAxis.y()) && Float.isNaN(zeroAxis.z()), "a zero axis is invalid and says so");
        assertTrue(allNaN(Quatf.fromAxisAngle(NAN, Vec3f.UNIT_X)));
        assertTrue(allNaN(new Quatf(0f, 0f, 0f, 0f).invert()), "the zero quaternion has no inverse");
    }

    @Test
    void quaternionInterpolationTakesTheShortArcAndPropagatesNaN() {
        Quatf id = Quatf.IDENTITY, minusId = new Quatf(0f, 0f, 0f, -1f);
        Quatf a = id.slerp(minusId, 0.5f);
        assertEquals(1f, a.length(), EPS, "q and -q are the same rotation: the short arc is no rotation at all");
        assertEquals(1f, Math.abs(a.w()), EPS);
        Quatf b = id.nlerp(minusId, 0.5f);
        assertEquals(1f, b.length(), EPS);
        assertTrue(Float.isNaN(id.slerp(new Quatf(NAN, 0f, 0f, 0f), 0.5f).x()), "NaN in, NaN out");
        assertEquals(1f, id.slerp(Quatf.fromAxisAngle(1f, Vec3f.UNIT_Y), 0f).length(), EPS);
    }

    @Test
    void quaternionOperationsAssumeUnitLength() {
        // documented, not validated: a zero quaternion is not a rotation, and these are the (harmless) results
        Quatf zero = new Quatf(0f, 0f, 0f, 0f);
        assertEquals(Vec3f.UNIT_X, zero.transform(Vec3f.UNIT_X), "transform with a zero quaternion leaves the vector alone");
        Mat3f m = zero.toMat3();
        assertEquals(0f, m.m00(), "and the matrix of a zero quaternion is the zero matrix");
        assertEquals(Quatf.IDENTITY, zero.pow(NAN), "the zero rotation to any power is the identity, even a NaN power");
        assertEquals(Quatf.IDENTITY, zero.exp());
        Quatf q = Quatf.fromAxisAngle(0.3f, Vec3f.UNIT_Z);
        assertEquals(q, q.integrate(Vec3f.ZERO, NAN), "no angular velocity: unchanged, whatever the time step");
    }

    // ---------------------------------------------------------------- matrices

    @Test
    void invertingSingularMatricesGivesNonFiniteEntries() {
        Mat4f singular = Mat4f.translationRotateScale(new Vec3f(1f, 2f, 3f), Quatf.IDENTITY, new Vec3f(0f, 1f, 1f));
        assertEquals(0f, singular.determinant());
        assertFalse(singular.invert().isFinite(), "the caller can detect it with isFinite() or by the zero determinant");
        assertFalse(singular.invertAffine().isFinite());
        Mat3f singular3 = new Mat3f(1f, 2f, 3f, 2f, 4f, 6f, 3f, 6f, 9f);
        assertEquals(0f, singular3.determinant());
        assertFalse(singular3.invert().isFinite());
        Mat4f nan = Mat4f.translation(NAN, 0f, 0f);
        assertFalse(nan.invert().isFinite());
        assertFalse(nan.isFinite());
    }

    @Test
    void anAlmostSingularMatrixStillInvertsAndAHugeOneOverflows() {
        Mat4f nearly = Mat4f.translationRotateScale(Vec3f.ZERO, Quatf.IDENTITY, new Vec3f(1e-20f, 1f, 1f));
        Mat4f inverse = nearly.invert();
        assertTrue(inverse.isFinite());
        assertEquals(1e20f, inverse.m00(), 1e20f * 1e-5f, "a scale of 1e-20 inverts to 1e20");
        Mat4f huge = Mat4f.translationRotateScale(Vec3f.ZERO, Quatf.IDENTITY, new Vec3f(HUGE, HUGE, HUGE));
        assertEquals(INF, huge.determinant(), "the determinant overflows");
        assertFalse(huge.invert().isFinite(), "and so does the inverse, which is NaN where the determinant is infinite");
    }

    @Test
    void decomposeOfAZeroScaleKeepsTranslationAndScaleAndMakesTheRotationNaN() {
        Mat4f m = Mat4f.translationRotateScale(new Vec3f(1f, 2f, 3f), Quatf.IDENTITY, new Vec3f(0f, 1f, 1f));
        Mat4f.Trs trs = m.decompose();
        assertEquals(1f, trs.translation().x());
        assertEquals(0f, trs.scale().x());
        assertTrue(allNaN(trs.rotation()), "a zero scale axis has no direction");
    }

    @Test
    void projectionBuildersDoNotValidateTheirArguments() {
        assertEquals(INF, Mat4f.perspective(1f, 0f, 0.1f, 10f, false).m00(), "aspect 0");
        assertEquals(INF, Mat4f.perspective(1f, 1f, 1f, 1f, false).m22(), "near == far");
        Mat4f nearZero = Mat4f.perspective(1f, 1f, 0f, 10f, false);
        assertTrue(nearZero.isFinite(), "near 0 is finite but has no depth resolution: m32 is 0");
        assertTrue(nearZero.m32() == 0f, "m32 is zero (of either sign)");
        assertEquals(INF, Mat4f.ortho(1f, 1f, -1f, 1f, 0f, 1f, false).m00(), "left == right");
        assertTrue(Float.isNaN(Mat4f.lookAt(Vec3f.UNIT_X, Vec3f.UNIT_X, Vec3f.UNIT_Y).m00()), "eye == target");
        assertTrue(Float.isNaN(Mat4f.lookAt(Vec3f.ZERO, Vec3f.UNIT_X, Vec3f.UNIT_X).m00()), "up parallel to the view direction");
        assertTrue(Float.isNaN(Mat4f.rotationAxis(1f, Vec3f.ZERO).m00()), "a zero axis");
    }

    @Test
    void affineMethodsIgnoreTheBottomRowByDesign() {
        Mat4f m = new Mat4f(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 5f, 6f, 7f, NAN);
        assertEquals(6f, m.transformPosition(new Vec3f(1f, 0f, 0f)).x(), 0f, "the bottom row (here m33 = NaN) is not read by the affine transform");
        assertEquals(1f, m.transformDirection(new Vec3f(1f, 0f, 0f)).x());
        assertTrue(m.invertAffine().m00() == 1f);
        assertFalse(m.isFinite(), "but isFinite looks at every entry");
    }

    // ---------------------------------------------------------------- equality and hashing (CORE-11)

    @Test
    void equalsAndHashCodeFollowTheBitwiseRulesOfJavaRecords() {
        // equals is exact and compares like Float.compare: NaN equals itself, and 0 and -0 differ
        assertEquals(new Vec3f(NAN, 0f, 0f), new Vec3f(NAN, 0f, 0f));
        assertEquals(new Vec3f(NAN, 0f, 0f).hashCode(), new Vec3f(NAN, 0f, 0f).hashCode());
        assertFalse(new Vec3f(0f, 0f, 0f).equals(new Vec3f(-0f, 0f, 0f)), "0 and -0 are different keys");
        assertFalse(new Quatf(0f, 0f, 0f, 1f).equals(new Quatf(-0f, 0f, 0f, 1f)));
        assertEquals(new Vec3f(1f, 2f, 3f).hashCode(), new Vec3f(1f, 2f, 3f).hashCode());
        assertFalse(new Vec3f(INF, 0f, 0f).equals(new Vec3f(-INF, 0f, 0f)));
        // the tolerant comparison is the other way round: -0 equals 0, NaN equals nothing
        assertTrue(new Vec3f(0f, 0f, 0f).approxEquals(new Vec3f(-0f, 0f, 0f), 0f));
        assertFalse(new Vec3f(NAN, 0f, 0f).approxEquals(new Vec3f(NAN, 0f, 0f), 1f), "NaN is never approximately equal, not even to itself");
        assertTrue(new Vec3f(1f, 0f, 0f).approxEquals(new Vec3f(1.05f, 0f, 0f), 0.1f));
        assertFalse(new Vec3f(INF, 0f, 0f).approxEquals(new Vec3f(INF, 0f, 0f), 1f), "infinity minus infinity is NaN, so even equal infinities are not approximately equal");
    }
}
