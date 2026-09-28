package vmath.core;

// GENERATED from QuatfTest.java by tools/GenDouble.java. Do not edit; edit the float source.

import static vmath.core.Check.check;
import static vmath.core.Check.close;
import static vmath.core.Check.sameRotation;
import static vmath.core.Rnd.N;

import org.joml.Matrix3d;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

class QuatdTest {

    static final double EPS = 1e-12;
    /**
     * JOML derives w with cosFromSin = sqrt(1 - sin^2), which loses precision near angle = PI
     * (about sqrt(machine epsilon)). We call cos directly, so the oracle is the less accurate side here.
     */
    static final double EPS_TRIG = 1e-7;

    final Rnd rnd = Rnd.create();

    @Test
    void constructionMatchesJoml() {
        for (int i = 0; i < N; i++) {
            Vec3d axis = rnd.nextVec3d();
            double angle = rnd.range(-7, 7);
            close(Quatd.fromAxisAngle(angle, axis),
                    new Quaterniond().rotationAxis(angle, axis.x(), axis.y(), axis.z()), EPS_TRIG, i);
            // Compared against rotationAxis rather than JOML's rotationX/Y/Z:
            // JOML 1.10.8 Quaterniond.rotationX returns (sin, 0, cos, 0), fixed on JOML main.
            close(Quatd.rotationX(angle), new Quaterniond().rotationAxis(angle, 1.0, 0.0, 0.0), EPS_TRIG, i);
            close(Quatd.rotationY(angle), new Quaterniond().rotationAxis(angle, 0.0, 1.0, 0.0), EPS_TRIG, i);
            close(Quatd.rotationZ(angle), new Quaterniond().rotationAxis(angle, 0.0, 0.0, 1.0), EPS_TRIG, i);
        }
    }

    @Test
    void constructionIsAccurate() {
        for (int i = 0; i < N; i++) {
            Vec3d axis = rnd.nextVec3d();
            double angle = rnd.range(-7, 7);
            Quatd q = Quatd.fromAxisAngle(angle, axis);
            close(q.lengthSquared(), 1.0, EPS, i);
            close(q.w(), Math.cos(angle * 0.5), EPS, i);
            close(q.angle(), Math.abs(Math.IEEEremainder(angle, 2 * Math.PI)), EPS_TRIG, i);
            close(Quatd.rotationX(angle), Quatd.fromAxisAngle(angle, Vec3d.UNIT_X), EPS, i);
            close(Quatd.rotationY(angle), Quatd.fromAxisAngle(angle, Vec3d.UNIT_Y), EPS, i);
            close(Quatd.rotationZ(angle), Quatd.fromAxisAngle(angle, Vec3d.UNIT_Z), EPS, i);
        }
    }

    @Test
    void algebraMatchesJoml() {
        for (int i = 0; i < N; i++) {
            Quatd a = rnd.nextUnitQuatd();
            Quatd b = rnd.nextUnitQuatd();
            double k = 0.5 + rnd.range(0, 3);
            Quatd big = new Quatd(a.x() * k, a.y() * k, a.z() * k, a.w() * k); // non-unit
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
            Quatd q = rnd.nextUnitQuatd();
            Vec3d v = rnd.nextVec3d();
            close(q.transform(v), J.j(q).transform(J.j(v)), EPS, i);
        }
    }

    @Test
    void interpolationMatchesJoml() {
        for (int i = 0; i < N; i++) {
            Quatd a = rnd.nextUnitQuatd();
            Quatd b = rnd.nextUnitQuatd();
            double t = rnd.range(0, 1);
            close(a.slerp(b, t), J.j(a).slerp(J.j(b), t), EPS * 10.0, i);
            close(a.nlerp(b, t), J.j(a).nlerp(J.j(b), t), EPS, i);
            close(a.slerp(a, t), J.j(a), EPS, i); // degenerate branch
        }
    }

    @Test
    void matrixConversionMatchesJoml() {
        for (int i = 0; i < N; i++) {
            Quatd q = rnd.nextUnitQuatd();
            close(q.toMat3(), new Matrix3d().rotation(J.j(q)), EPS, i);
            close(q.toMat4(), new Matrix4d().rotation(J.j(q)), EPS, i);
        }
    }

    @Test
    void groupProperties() {
        for (int i = 0; i < N; i++) {
            Quatd a = rnd.nextUnitQuatd();
            Quatd b = rnd.nextUnitQuatd();
            Vec3d v = rnd.nextVec3d();
            sameRotation(a.mul(a.conjugate()), Quatd.IDENTITY, EPS, i);
            // a.mul(b) applies b first
            close(a.mul(b).transform(v), a.transform(b.transform(v)), EPS * 10.0, i);
            // q and -q are the same rotation
            Quatd neg = new Quatd(-a.x(), -a.y(), -a.z(), -a.w());
            close(neg.transform(v), a.transform(v), EPS * 10.0, i);
            check(a.sameRotation(neg, EPS), i, "q ~ -q");
        }
    }
}
