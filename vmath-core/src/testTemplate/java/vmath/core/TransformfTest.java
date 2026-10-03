package vmath.core;

import vmath.annotations.Eps;
import vmath.annotations.GenerateDouble;
import static vmath.core.Check.check;
import static vmath.core.Check.close;
import static vmath.core.Check.sameRotation;
import static vmath.core.Rnd.N;

import org.junit.jupiter.api.Test;

@GenerateDouble
class TransformfTest {
    @Eps(d = 1e-10)
    static final float EPS = 1e-3f;

    final Rnd rnd = Rnd.create();

    private Transformf random() {
        return new Transformf(rnd.nextVec3f(), rnd.nextUnitQuatf(), rnd.nextScaleVec3f());
    }

    private Transformf randomUniform() {
        return new Transformf(rnd.nextVec3f(), rnd.nextUnitQuatf(), Vec3f.splat((float) rnd.range(0.3, 3)));
    }

    @Test
    void matchesTheEquivalentMatrix() {
        for (int i = 0; i < N; i++) {
            Transformf t = random();
            Vec3f p = rnd.nextVec3f();
            close(t.toMat4(), Mat4f.translationRotateScale(t.translation(), t.rotation(), t.scale()), 0.0, i);
            close(t.toMat4x3(), Mat4x3f.fromMat4(t.toMat4()), EPS, i);
            close(t.transformPosition(p), t.toMat4().transformPosition(p), EPS, i);
            close(t.transformDirection(p), t.toMat4().transformDirection(p), EPS, i);
        }
        close(Transformf.IDENTITY.transformPosition(Vec3f.ONE), Vec3f.ONE, 0.0, 0);
    }

    @Test
    void compositionMatchesMatrixProductForUniformParents() {
        for (int i = 0; i < N; i++) {
            Transformf parent = randomUniform();
            Transformf child = random(); // the child may have any scale
            Vec3f p = rnd.nextVec3f();
            close(parent.mul(child).transformPosition(p), parent.toMat4().mul(child.toMat4()).transformPosition(p), EPS * 10, i);
            close(parent.mul(child).toMat4(), parent.toMat4().mul(child.toMat4()), EPS * 10, i);
        }
    }

    @Test
    void inverseUndoesAUniformTransform() {
        for (int i = 0; i < N; i++) {
            Transformf t = randomUniform();
            Vec3f p = rnd.nextVec3f();
            close(t.inverse().transformPosition(t.transformPosition(p)), p, EPS * 10, i);
            close(t.transformPosition(t.inverse().transformPosition(p)), p, EPS * 10, i);
            close(t.inverse().toMat4(), t.toMat4().invertAffine(), EPS * 10, i);
            check(t.mul(t.inverse()).approxEquals(Transformf.IDENTITY, EPS * 10), i, "t * t^-1 is the identity");
        }
        // rotation-free non-uniform scale is also exactly invertible
        Transformf scaled = new Transformf(rnd.nextVec3f(), Quatf.IDENTITY, new Vec3f(2f, 0.5f, 3f));
        Vec3f p = rnd.nextVec3f();
        close(scaled.inverse().transformPosition(scaled.transformPosition(p)), p, EPS, 0);
    }

    @Test
    void blendInterpolatesEachPartAndHitsTheEnds() {
        for (int i = 0; i < N; i++) {
            Transformf a = random(), b = random();
            float t = (float) rnd.range(0, 1);
            check(a.blend(b, 0f).approxEquals(a, EPS), i, "t = 0 gives this");
            check(a.blend(b, 1f).approxEquals(b, EPS), i, "t = 1 gives other");
            Transformf m = a.blend(b, t);
            close(m.translation(), a.translation().lerp(b.translation(), t), EPS, i);
            close(m.scale(), a.scale().lerp(b.scale(), t), EPS, i);
            sameRotation(m.rotation(), a.rotation().slerp(b.rotation(), t), EPS, i);
            close(m.rotation().length(), 1.0, EPS, i);
        }
    }

    @Test
    void fromMat4RoundTripsAndFlagsUniformScale() {
        for (int i = 0; i < N; i++) {
            Transformf t = random();
            Transformf back = Transformf.fromMat4(t.toMat4());
            check(back.approxEquals(t, EPS * 10), i, "decompose(compose(t)) == t");
            check(randomUniform().hasUniformScale(1e-6f), i, "uniform");
            check(!new Transformf(Vec3f.ZERO, Quatf.IDENTITY, new Vec3f(1f, 2f, 1f)).hasUniformScale(1e-3f), i, "non-uniform");
        }
        check(Transformf.ofTranslation(Vec3f.ONE).translation().equals(Vec3f.ONE), 0, "ofTranslation");
        check(Transformf.ofScale(Vec3f.splat(2f)).scale().equals(Vec3f.splat(2f)), 0, "ofScale");
        check(Transformf.ofRotation(Quatf.rotationX(1f)).rotation().equals(Quatf.rotationX(1f)), 0, "ofRotation");
        check(Transformf.IDENTITY.isFinite(), 0, "identity is finite");
        check(!new Transformf(new Vec3f(Float.NaN, 0f, 0f), Quatf.IDENTITY, Vec3f.ONE).isFinite(), 0, "NaN is not finite");
    }
}
