package vmath.core;

import static vmath.core.Check.check;
import static vmath.core.Check.close;
import static vmath.core.Check.sameRotation;
import static vmath.core.Rnd.N;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

class QuatfTest {

    static final float EPS = 1e-5f; // @eps-double 1e-12
    /**
     * JOML derives w with cosFromSin = sqrt(1 - sin^2), which loses precision near angle = PI
     * (about sqrt(machine epsilon)). We call cos directly, so the oracle is the less accurate side here.
     */
    static final float EPS_TRIG = 1e-3f; // @eps-double 1e-7

    final Rnd rnd = Rnd.create();

    @Test
    void constructionMatchesJoml() {
        for (int i = 0; i < N; i++) {
            Vec3f axis = rnd.nextVec3f();
            float angle = (float) rnd.range(-7, 7);
            close(Quatf.fromAxisAngle(angle, axis),
                    new Quaternionf().rotationAxis(angle, axis.x(), axis.y(), axis.z()), EPS_TRIG, i);
            // Compared against rotationAxis rather than JOML's rotationX/Y/Z:
            // JOML 1.10.8 Quaterniond.rotationX returns (sin, 0, cos, 0), fixed on JOML main.
            close(Quatf.rotationX(angle), new Quaternionf().rotationAxis(angle, 1f, 0f, 0f), EPS_TRIG, i);
            close(Quatf.rotationY(angle), new Quaternionf().rotationAxis(angle, 0f, 1f, 0f), EPS_TRIG, i);
            close(Quatf.rotationZ(angle), new Quaternionf().rotationAxis(angle, 0f, 0f, 1f), EPS_TRIG, i);
        }
    }

    @Test
    void constructionIsAccurate() {
        for (int i = 0; i < N; i++) {
            Vec3f axis = rnd.nextVec3f();
            float angle = (float) rnd.range(-7, 7);
            Quatf q = Quatf.fromAxisAngle(angle, axis);
            close(q.lengthSquared(), 1f, EPS, i);
            close(q.w(), Math.cos(angle * 0.5), EPS, i);
            close(q.angle(), Math.abs(Math.IEEEremainder(angle, 2 * Math.PI)), EPS_TRIG, i);
            close(Quatf.rotationX(angle), Quatf.fromAxisAngle(angle, Vec3f.UNIT_X), EPS, i);
            close(Quatf.rotationY(angle), Quatf.fromAxisAngle(angle, Vec3f.UNIT_Y), EPS, i);
            close(Quatf.rotationZ(angle), Quatf.fromAxisAngle(angle, Vec3f.UNIT_Z), EPS, i);
        }
    }

    @Test
    void algebraMatchesJoml() {
        for (int i = 0; i < N; i++) {
            Quatf a = rnd.nextUnitQuatf();
            Quatf b = rnd.nextUnitQuatf();
            float k = 0.5f + (float) rnd.range(0, 3);
            Quatf big = new Quatf(a.x() * k, a.y() * k, a.z() * k, a.w() * k); // non-unit
            close(a.mul(b), J.j(a).mul(J.j(b)), EPS, i);
            close(a.conjugate(), J.j(a).conjugate(), EPS, i);
            close(big.invert(), J.j(big).invert(), EPS, i);
            close(big.normalize(), J.j(big).normalize(), EPS, i);
            close(a.dot(b), J.j(a).dot(J.j(b)), EPS, i);
            close(big.lengthSquared(), J.j(big).lengthSquared(), EPS, i);
            // JOML returns 2*acos(w) in [0, 2*PI]; we fold to the shortest arc in [0, PI].
            double ja = J.j(a).angle();
            close(a.angle(), Math.min(ja, 2 * Math.PI - ja), EPS_TRIG, i); // acos is ill-conditioned near |w| = 1
        }
    }

    @Test
    void transformMatchesJoml() {
        for (int i = 0; i < N; i++) {
            Quatf q = rnd.nextUnitQuatf();
            Vec3f v = rnd.nextVec3f();
            close(q.transform(v), J.j(q).transform(J.j(v)), EPS, i);
        }
    }

    @Test
    void interpolationMatchesJoml() {
        for (int i = 0; i < N; i++) {
            Quatf a = rnd.nextUnitQuatf();
            Quatf b = rnd.nextUnitQuatf();
            float t = (float) rnd.range(0, 1);
            close(a.slerp(b, t), J.j(a).slerp(J.j(b), t), EPS * 10f, i);
            close(a.nlerp(b, t), J.j(a).nlerp(J.j(b), t), EPS, i);
            close(a.slerp(a, t), J.j(a), EPS, i); // degenerate branch
        }
    }

    @Test
    void matrixConversionMatchesJoml() {
        for (int i = 0; i < N; i++) {
            Quatf q = rnd.nextUnitQuatf();
            close(q.toMat3(), new Matrix3f().rotation(J.j(q)), EPS, i);
            close(q.toMat4(), new Matrix4f().rotation(J.j(q)), EPS, i);
        }
    }

    @Test
    void groupProperties() {
        for (int i = 0; i < N; i++) {
            Quatf a = rnd.nextUnitQuatf();
            Quatf b = rnd.nextUnitQuatf();
            Vec3f v = rnd.nextVec3f();
            sameRotation(a.mul(a.conjugate()), Quatf.IDENTITY, EPS, i);
            // a.mul(b) applies b first
            close(a.mul(b).transform(v), a.transform(b.transform(v)), EPS * 10f, i);
            // q and -q are the same rotation
            Quatf neg = new Quatf(-a.x(), -a.y(), -a.z(), -a.w());
            close(neg.transform(v), a.transform(v), EPS * 10f, i);
            check(a.sameRotation(neg, EPS), i, "q ~ -q");
        }
    }
}
