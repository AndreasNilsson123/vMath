package vmath.core;

import vmath.annotations.Eps;
import vmath.annotations.GenerateDouble;
import static vmath.core.Check.check;
import static vmath.core.Rnd.N;

import org.junit.jupiter.api.Test;

@GenerateDouble
class RigidTransformfTest {
    @Eps(d = 1e-9)
    static final float EPS = 5e-4f;

    final Rnd rnd = Rnd.create();

    private RigidTransformf random() {
        return new RigidTransformf(rnd.nextVec3f(), rnd.nextUnitQuatf());
    }

    @Test
    void transformsPointsAndDirectionsLikeTheMatrix() {
        for (int i = 0; i < N; i++) {
            RigidTransformf a = random();
            Vec3f p = rnd.nextVec3f();
            Mat4f m = a.toMat4();
            check(a.transformPosition(p).approxEquals(m.transformPosition(p), EPS), i, "position");
            check(a.transformDirection(p).approxEquals(m.transformDirection(p), EPS), i, "direction");
            check(a.toMat4x3().transformPosition(p).approxEquals(m.transformPosition(p), EPS), i, "compact matrix");
            check(a.toTransform().transformPosition(p).approxEquals(m.transformPosition(p), EPS), i, "TRS");
        }
    }

    @Test
    void theProductIsTheProductOfTheMatrices() {
        for (int i = 0; i < N; i++) {
            RigidTransformf a = random(), b = random();
            check(a.mul(b).toMat4().approxEquals(a.toMat4().mul(b.toMat4()), EPS), i, "product");
            Vec3f p = rnd.nextVec3f();
            check(a.mul(b).transformPosition(p).approxEquals(a.transformPosition(b.transformPosition(p)), EPS), i, "the right operand acts first");
        }
    }

    @Test
    void theInverseUndoesTheTransform() {
        for (int i = 0; i < N; i++) {
            RigidTransformf a = random();
            check(a.mul(a.inverse()).approxEquals(RigidTransformf.IDENTITY, EPS), i, "a * a^-1");
            check(a.inverse().mul(a).approxEquals(RigidTransformf.IDENTITY, EPS), i, "a^-1 * a");
            Vec3f p = rnd.nextVec3f();
            check(a.inverse().transformPosition(a.transformPosition(p)).approxEquals(p, EPS), i, "round trip");
            check(a.inverseTransformPosition(p).approxEquals(a.inverse().transformPosition(p), EPS), i, "inverseTransformPosition");
            check(a.inverseTransformDirection(p).approxEquals(a.inverse().transformDirection(p), EPS), i, "inverseTransformDirection");
            check(a.inverse().toMat4().approxEquals(a.toMat4().invertAffine(), EPS), i, "inverse of the matrix");
        }
    }

    @Test
    void conversionsRoundTrip() {
        for (int i = 0; i < N; i++) {
            RigidTransformf a = random();
            check(RigidTransformf.fromMat4(a.toMat4()).approxEquals(a, EPS), i, "matrix");
            check(RigidTransformf.fromTransform(a.toTransform()).approxEquals(a, EPS), i, "TRS");
            check(a.toDualQuat().toRigid().approxEquals(a, EPS), i, "dual quaternion");
            // the matrix of a transform with a scale: only the rotation and the translation are taken
            Transformf scaled = new Transformf(a.translation(), a.rotation(), new Vec3f(2f, 2f, 2f));
            check(RigidTransformf.fromTransform(scaled).approxEquals(a, EPS), i, "scale dropped");
        }
    }

    @Test
    void blendHasTheEndpointsAndTheShortestRotation() {
        for (int i = 0; i < N; i++) {
            RigidTransformf a = random(), b = random();
            check(a.blend(b, 0f).approxEquals(a, EPS), i, "t = 0");
            check(a.blend(b, 1f).approxEquals(b, EPS), i, "t = 1");
            RigidTransformf mid = a.blend(b, 0.5f);
            check(mid.translation().approxEquals(a.translation().lerp(b.translation(), 0.5f), EPS), i, "translation is linear");
            check(Math.abs(mid.rotation().length() - 1f) < EPS, i, "the rotation stays unit");
            // the negative quaternion is the same rotation and must give the same blend
            RigidTransformf negated = new RigidTransformf(b.translation(), new Quatf(-b.rotation().x(), -b.rotation().y(), -b.rotation().z(), -b.rotation().w()));
            check(a.blend(negated, 0.3f).approxEquals(a.blend(b, 0.3f), EPS), i, "q and -q");
        }
    }

    @Test
    void specialValuesAndSmallOperations() {
        Vec3f p = new Vec3f(1f, 2f, 3f);
        check(RigidTransformf.IDENTITY.transformPosition(p).approxEquals(p, 0f), 0, "identity");
        check(RigidTransformf.ofTranslation(p).transformPosition(Vec3f.ZERO).approxEquals(p, 0f), 1, "translation");
        check(RigidTransformf.ofTranslation(p).transformDirection(p).approxEquals(p, 0f), 2, "a translation leaves directions");
        RigidTransformf turn = RigidTransformf.ofRotation(Quatf.rotationZ((float) (Math.PI / 2)));
        check(turn.transformPosition(new Vec3f(1f, 0f, 0f)).approxEquals(new Vec3f(0f, 1f, 0f), EPS), 3, "quarter turn");
        RigidTransformf drifted = new RigidTransformf(p, new Quatf(0f, 0f, 0.8f, 0.8f));
        check(Math.abs(drifted.normalize().rotation().length() - 1f) < EPS, 4, "normalize");
        check(drifted.normalize().translation().approxEquals(p, 0f), 5, "normalize keeps the translation");
        check(RigidTransformf.IDENTITY.isFinite(), 6, "finite");
        check(!new RigidTransformf(new Vec3f(Float.NaN, 0f, 0f), Quatf.IDENTITY).isFinite(), 7, "NaN");
    }
}
