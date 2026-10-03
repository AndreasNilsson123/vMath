package vmath.core;

import vmath.annotations.Eps;
import vmath.annotations.GenerateDouble;
import static vmath.core.Check.check;
import static vmath.core.Rnd.N;

import org.junit.jupiter.api.Test;

@GenerateDouble
class DualQuatfTest {
    @Eps(d = 1e-9)
    static final float EPS = 5e-4f;

    @Eps(d = 1e-8)
    static final float LOOSE = 3e-3f;

    final Rnd rnd = Rnd.create();

    private DualQuatf random() {
        return DualQuatf.of(rnd.nextUnitQuatf(), rnd.nextVec3f());
    }

    @Test
    void buildsAUnitDualQuaternionOfARotationAndATranslation() {
        for (int i = 0; i < N; i++) {
            Quatf r = rnd.nextUnitQuatf();
            Vec3f t = rnd.nextVec3f();
            DualQuatf q = DualQuatf.of(r, t);
            check(q.rotation().approxEquals(r, 0f), i, "the real part is the rotation");
            check(q.translation().approxEquals(t, EPS), i, "the translation comes back");
            check(Math.abs(q.real().dot(q.dual())) < EPS, i, "the parts are orthogonal");
            check(q.toMat4().approxEquals(new RigidTransformf(t, r).toMat4(), EPS), i, "the matrix");
            check(q.toRigid().approxEquals(new RigidTransformf(t, r), EPS), i, "the rigid transform");
        }
    }

    @Test
    void transformsAndComposesLikeMatrices() {
        for (int i = 0; i < N; i++) {
            DualQuatf a = random(), b = random();
            Vec3f p = rnd.nextVec3f();
            check(a.transformPosition(p).approxEquals(a.toMat4().transformPosition(p), EPS), i, "position");
            check(a.transformDirection(p).approxEquals(a.toMat4().transformDirection(p), EPS), i, "direction");
            check(a.mul(b).toMat4().approxEquals(a.toMat4().mul(b.toMat4()), EPS), i, "product");
            check(a.mul(b).transformPosition(p).approxEquals(a.transformPosition(b.transformPosition(p)), EPS), i, "the right operand acts first");
            check(a.mul(DualQuatf.IDENTITY).sameTransform(a, EPS), i, "identity");
        }
    }

    @Test
    void theConjugateIsTheInverseOfAUnitDualQuaternionAndInverseIsExact() {
        for (int i = 0; i < N; i++) {
            DualQuatf a = random();
            check(a.mul(a.conjugate()).sameTransform(DualQuatf.IDENTITY, EPS), i, "a * conjugate");
            check(a.conjugate().sameTransform(a.inverse(), EPS), i, "the same as the inverse");
            check(a.toMat4().invertAffine().approxEquals(a.inverse().toMat4(), EPS), i, "the inverse of the matrix");
            // not unit: the exact inverse still gives the identity
            DualQuatf scaled = a.mul(2f);
            DualQuatf one = scaled.mul(scaled.inverse());
            check(one.real().approxEquals(Quatf.IDENTITY, EPS), i, "real part of a non-unit product");
            check(Math.abs(one.dual().length()) < EPS, i, "dual part of a non-unit product");
        }
    }

    @Test
    void normalizeMakesASumARigidTransform() {
        for (int i = 0; i < N; i++) {
            DualQuatf a = random(), b = random();
            DualQuatf sum = a.mul(0.3f).add(b.dot(a) < 0f ? b.negate().mul(0.7f) : b.mul(0.7f)).normalize();
            check(Math.abs(sum.real().length() - 1f) < EPS, i, "unit real part");
            check(Math.abs(sum.real().dot(sum.dual())) < EPS, i, "orthogonal parts");
            check(sum.toMat4().isOrthonormal(EPS * 4f), i, "its matrix is a rotation");
            check(sum.negate().sameTransform(sum, EPS), i, "q and -q are the same transform");
        }
    }

    @Test
    void nlerpHasTheEndpointsAndIgnoresTheSignOfTheOperand() {
        for (int i = 0; i < N; i++) {
            DualQuatf a = random(), b = random();
            check(a.nlerp(b, 0f).sameTransform(a, EPS), i, "t = 0");
            check(a.nlerp(b, 1f).sameTransform(b, EPS), i, "t = 1");
            check(a.nlerp(b.negate(), 0.4f).sameTransform(a.nlerp(b, 0.4f), EPS), i, "b and -b");
        }
    }

    @Test
    void sclerpIsTheScrewMotionWithConstantSpeed() {
        for (int i = 0; i < N; i++) {
            DualQuatf a = random(), b = random();
            check(a.sclerp(b, 0f).sameTransform(a, LOOSE), i, "t = 0");
            check(a.sclerp(b, 1f).sameTransform(b, LOOSE), i, "t = 1");
            DualQuatf half = a.sclerp(b, 0.5f);
            check(Math.abs(half.real().length() - 1f) < LOOSE, i, "unit");
            // equal steps: the motion from a to the middle equals the one from the middle to b
            check(a.conjugate().mul(half).sameTransform(half.conjugate().mul(b), LOOSE), i, "constant speed");
            check(half.mul(half.conjugate().mul(b)).sameTransform(b, LOOSE), i, "two halves make the whole");
        }
    }

    @Test
    void sclerpFollowsAKnownScrew() {
        float turn = 2.0f, lift = 6f;
        DualQuatf end = DualQuatf.of(Quatf.rotationZ(turn), new Vec3f(0f, 0f, lift));
        for (float t : new float[] {0.25f, 0.5f, 0.75f}) {
            DualQuatf got = DualQuatf.IDENTITY.sclerp(end, t);
            DualQuatf want = DualQuatf.of(Quatf.rotationZ(turn * t), new Vec3f(0f, 0f, lift * t));
            check(got.sameTransform(want, EPS), (int) (t * 100), "a rotation about z with a lift along z, at t = " + t);
        }
    }

    @Test
    void powScalesTheMotion() {
        for (int i = 0; i < N; i++) {
            DualQuatf a = random();
            check(a.pow(0f).sameTransform(DualQuatf.IDENTITY, LOOSE), i, "pow(0)");
            check(a.pow(1f).sameTransform(a, LOOSE), i, "pow(1)");
            DualQuatf half = a.pow(0.5f);
            check(half.mul(half).sameTransform(a, LOOSE), i, "pow(0.5) twice");
        }
        Vec3f t = new Vec3f(4f, -2f, 8f);
        check(DualQuatf.ofTranslation(t).pow(0.25f).translation().approxEquals(t.mul(0.25f), EPS), 0, "a pure translation scales linearly");
        check(DualQuatf.ofRotation(Quatf.rotationX(1f)).pow(0.5f).rotation().sameRotation(Quatf.rotationX(0.5f), EPS), 1, "a pure rotation");
        check(DualQuatf.IDENTITY.pow(3f).sameTransform(DualQuatf.IDENTITY, EPS), 2, "identity");
    }

    @Test
    void blendingKeepsTheVolumeWhereBlendingMatricesDoesNot() {
        // two bones twisted by 180 degrees about the same axis, blended half and half: the matrix blend collapses the part of the
        // mesh near the axis (the candy wrapper), the dual quaternion blend is a rotation by 90 degrees
        DualQuatf a = DualQuatf.IDENTITY;
        DualQuatf b = DualQuatf.ofRotation(Quatf.rotationZ((float) Math.PI));
        Vec3f p = new Vec3f(1f, 0f, 0f);
        Vec3f dq = a.mul(0.5f).add(b.mul(0.5f)).normalize().transformPosition(p);
        check(Math.abs(dq.length() - 1f) < EPS, 0, "the dual quaternion blend keeps the distance from the axis: " + dq);
        Vec3f lbs = a.toMat4().transformPosition(p).mul(0.5f).add(b.toMat4().transformPosition(p).mul(0.5f));
        check(lbs.length() < 0.1f, 1, "the matrix blend collapses the point onto the axis: " + lbs);
    }

    @Test
    void comparisonsAndFiniteness() {
        DualQuatf a = random();
        check(a.approxEquals(a, 0f), 0, "equal");
        check(!a.approxEquals(a.negate(), EPS), 1, "the negative differs component by component");
        check(a.sameTransform(a.negate(), EPS), 2, "and is the same transform");
        check(a.isFinite(), 3, "finite");
        check(!new DualQuatf(new Quatf(Float.NaN, 0f, 0f, 1f), new Quatf(0f, 0f, 0f, 0f)).isFinite(), 4, "NaN");
        check(DualQuatf.ofTranslation(new Vec3f(1f, 2f, 3f)).translation().approxEquals(new Vec3f(1f, 2f, 3f), 0f), 5, "ofTranslation");
        check(DualQuatf.ofRotation(Quatf.rotationY(0.3f)).translation().approxEquals(Vec3f.ZERO, 0f), 6, "ofRotation");
    }
}
