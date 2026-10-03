package vmath.core;

import vmath.annotations.Eps;
import vmath.annotations.GenerateDouble;
import static vmath.core.Check.check;
import static vmath.core.Check.close;
import static vmath.core.Check.sameRotation;
import static vmath.core.Rnd.N;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

@GenerateDouble
class QuatfTest {
    @Eps(d = 1e-12)
    static final float EPS = 1e-5f;
    /**
     * JOML derives w with cosFromSin = sqrt(1 - sin^2), which loses precision near angle = PI
     * (about sqrt(machine epsilon)). We call cos directly, so the oracle is the less accurate side here.
     */
    @Eps(d = 1e-7)
    static final float EPS_TRIG = 1e-3f;

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

    // ------------------------------------------------------------ conversions and features

    @Test
    void fromMat3RoundTripsAndMatchesJoml() {
        for (int i = 0; i < N; i++) {
            Quatf q = rnd.nextUnitQuatf();
            Mat3f m = q.toMat3();
            sameRotation(Quatf.fromMat3(m), q, EPS_TRIG, i);
            var e = new org.joml.Quaternionf().setFromNormalized(J.j(m));
            sameRotation(Quatf.fromMat3(m), new Quatf(e.x, e.y, e.z, e.w), EPS_TRIG, i);
        }
        // all four branches: identity (trace), and half turns about each axis (diagonal)
        sameRotation(Quatf.fromMat3(Mat3f.IDENTITY), Quatf.IDENTITY, EPS, 0);
        for (Quatf half : new Quatf[] {
                Quatf.rotationX((float) Math.PI), Quatf.rotationY((float) Math.PI), Quatf.rotationZ((float) Math.PI)}) {
            sameRotation(Quatf.fromMat3(half.toMat3()), half, EPS_TRIG, 0);
        }
    }

    @Test
    void fromToRotatesOneDirectionOntoAnother() {
        for (int i = 0; i < N; i++) {
            Vec3f a = rnd.nextVec3f();
            Vec3f b = rnd.nextVec3f();
            Quatf q = Quatf.fromTo(a, b);
            close(q.length(), 1.0, EPS, i);
            close(q.transform(a.normalize()), b.normalize(), EPS_TRIG, i);
        }
        // nearly opposite directions must stay accurate (no coarse special case near 180 degrees)
        for (int i = 0; i < N; i++) {
            Vec3f a = rnd.nextVec3f().normalize();
            Vec3f wobble = a.anyPerpendicular().mul((float) rnd.range(1e-3, 3e-2));
            Vec3f b = a.negate().add(wobble);
            close(Quatf.fromTo(a, b).transform(a), b.normalize(), EPS_TRIG, i);
        }
        Vec3f v = rnd.nextVec3f();
        close(Quatf.fromTo(v, v.negate()).transform(v.normalize()), v.normalize().negate(), EPS_TRIG, 0);
        sameRotation(Quatf.fromTo(v, v.mul(3f)), Quatf.IDENTITY, EPS_TRIG, 0);
    }

    @Test
    void eulerRoundTripsForAllTwelveOrders() {
        for (EulerOrder order : EulerOrder.values()) {
            for (int i = 0; i < N / 4; i++) {
                float a = (float) rnd.range(-3.0, 3.0);
                float c = (float) rnd.range(-3.0, 3.0);
                float b = order.isProper() ? (float) rnd.range(0.05, 3.0) : (float) rnd.range(-1.45, 1.45);
                Quatf q = Quatf.fromEuler(order, a, b, c);
                Vec3f e = q.toEuler(order);
                check(Math.abs(wrap(e.x() - a)) < EPS_TRIG * 30 && Math.abs(e.y() - b) < EPS_TRIG * 30
                        && Math.abs(wrap(e.z() - c)) < EPS_TRIG * 30, i,
                        order + ": expected (" + a + ", " + b + ", " + c + ") got " + e);
                sameRotation(Quatf.fromEuler(order, e), q, EPS_TRIG, i);
            }
        }
    }

    @Test
    void eulerHandlesGimbalLock() {
        for (EulerOrder order : EulerOrder.values()) {
            float[] locked = order.isProper()
                    ? new float[] {0f, (float) Math.PI}
                    : new float[] {(float) (Math.PI / 2), (float) (-Math.PI / 2)};
            for (float b : locked) {
                for (int i = 0; i < 50; i++) {
                    Quatf q = Quatf.fromEuler(order, (float) rnd.range(-3, 3), b, (float) rnd.range(-3, 3));
                    Vec3f e = q.toEuler(order);
                    check(e.isFinite(), i, order + " gimbal lock produced " + e);
                    sameRotation(Quatf.fromEuler(order, e), q, EPS_TRIG * 30, i);
                }
            }
        }
    }

    @Test
    void eulerMatchesJomlForItsOrders() {
        for (int i = 0; i < N; i++) {
            float a = (float) rnd.range(-3, 3), b = (float) rnd.range(-3, 3), c = (float) rnd.range(-3, 3);
            // JOML composes right-to-left (rotationZYX = Rz * Ry * Rx), which is our extrinsic XYZ, and so on
            var zyx = new org.joml.Quaternionf().rotationZYX(c, b, a);
            sameRotation(Quatf.fromEuler(EulerOrder.XYZ, a, b, c), new Quatf(zyx.x, zyx.y, zyx.z, zyx.w), EPS_TRIG, i);
            var xyz = new org.joml.Quaternionf().rotationXYZ(c, b, a);
            sameRotation(Quatf.fromEuler(EulerOrder.ZYX, a, b, c), new Quatf(xyz.x, xyz.y, xyz.z, xyz.w), EPS_TRIG, i);
            var yxz = new org.joml.Quaternionf().rotationYXZ(c, b, a);
            sameRotation(Quatf.fromEuler(EulerOrder.ZXY, a, b, c), new Quatf(yxz.x, yxz.y, yxz.z, yxz.w), EPS_TRIG, i);
        }
    }

    private static double wrap(double a) {
        double r = a;
        while (r > Math.PI) {
            r -= 2 * Math.PI;
        }
        while (r < -Math.PI) {
            r += 2 * Math.PI;
        }
        return r;
    }

    @Test
    void lookRotationPointsMinusZAtTheTarget() {
        for (int i = 0; i < N; i++) {
            Vec3f fwd = rnd.nextVec3f();
            Vec3f up = rnd.nextVec3f();
            if (fwd.normalize().cross(up.normalize()).length() < 0.05f) {
                continue; // nearly parallel: no defined roll
            }
            Quatf q = Quatf.lookRotation(fwd, up);
            close(q.length(), 1.0, EPS, i);
            close(q.transform(new Vec3f(0f, 0f, -1f)), fwd.normalize(), EPS_TRIG, i);
            // +Y lies in the plane of forward and up, on the same side as up
            Vec3f y = q.transform(Vec3f.UNIT_Y);
            close(y.dot(fwd.normalize()), 0.0, EPS_TRIG, i);
            check(y.dot(up) > 0f, i, "+Y must lean toward up");
            close(q.transform(Vec3f.UNIT_X).dot(fwd.normalize().cross(up.normalize())) > 0f ? 1.0 : 0.0, 1.0, EPS, i);
        }
    }

    @Test
    void swingTwistDecomposition() {
        for (int i = 0; i < N; i++) {
            Quatf q = rnd.nextUnitQuatf();
            Vec3f axis = rnd.nextVec3f();
            Quatf twist = q.twist(axis);
            Quatf swing = q.swing(axis);
            sameRotation(swing.mul(twist), q, EPS_TRIG, i);
            close(twist.length(), 1.0, EPS, i);
            // twist rotates only around `axis`; swing's axis is perpendicular to it
            Vec3f n = axis.normalize();
            close(new Vec3f(twist.x(), twist.y(), twist.z()).cross(n).length(), 0.0, EPS_TRIG, i);
            close(swing.x() * n.x() + swing.y() * n.y() + swing.z() * n.z(), 0.0, EPS_TRIG, i);
        }
    }

    @Test
    void logExpAndPow() {
        for (int i = 0; i < N; i++) {
            Quatf q = rnd.nextUnitQuatf();
            float s = (float) rnd.range(-1.0, 1.0);
            sameRotation(q.log().exp(), q, EPS_TRIG, i);
            sameRotation(q.pow(1f), q, EPS_TRIG, i);
            sameRotation(q.pow(0f), Quatf.IDENTITY, EPS_TRIG, i);
            float t = (float) rnd.range(0, 1);
            sameRotation(q.pow(s).mul(q.pow(t)), q.pow(s + t), EPS_TRIG * 10, i);
            // pow scales the full rotation angle (w < 0 means more than a half turn), reported as a shortest arc
            double theta = 2.0 * Math.atan2(Math.sqrt(q.x() * q.x() + q.y() * q.y() + q.z() * q.z()), q.w());
            double scaled = t * theta;
            close(q.pow(t).angle(), scaled <= Math.PI ? scaled : 2.0 * Math.PI - scaled, EPS_TRIG * 30, i);
        }
        close(Quatf.IDENTITY.log().length(), 0.0, EPS, 0);
    }

    @Test
    void axisAndAngleReproduceTheRotation() {
        for (int i = 0; i < N; i++) {
            Quatf q = rnd.nextUnitQuatf();
            sameRotation(Quatf.fromAxisAngle(q.angle(), q.axis()), q, EPS_TRIG, i);
        }
        close(Quatf.IDENTITY.axis(), Vec3f.UNIT_X, EPS, 0);
    }

    @Test
    void squadHitsItsKeysAndStaysUnit() {
        for (int i = 0; i < N / 4; i++) {
            Quatf q0 = rnd.nextUnitQuatf(), q1 = rnd.nextUnitQuatf(), q2 = rnd.nextUnitQuatf(), q3 = rnd.nextUnitQuatf();
            Quatf a = Quatf.squadControl(q0, q1, q2);
            Quatf b = Quatf.squadControl(q1, q2, q3);
            sameRotation(q1.squad(q2, a, b, 0f), q1, EPS_TRIG, i);
            sameRotation(q1.squad(q2, a, b, 1f), q2, EPS_TRIG, i);
            close(q1.squad(q2, a, b, (float) rnd.range(0, 1)).length(), 1.0, EPS_TRIG, i);
        }
    }

    @Test
    void integrateAppliesAWorldSpaceAngularVelocity() {
        for (int i = 0; i < N; i++) {
            Quatf q = rnd.nextUnitQuatf();
            Vec3f omega = rnd.nextVec3f();
            float dt = (float) rnd.range(0.001, 0.2);
            Quatf expected = Quatf.fromAxisAngle(omega.length() * dt, omega).mul(q);
            sameRotation(q.integrate(omega, dt), expected, EPS_TRIG, i);
        }
        Quatf q = rnd.nextUnitQuatf();
        check(q.integrate(Vec3f.ZERO, 1f).equals(q), 0, "zero velocity leaves the rotation unchanged");
    }

    @Test
    void getAndIsFinite() {
        Quatf q = new Quatf(1f, 2f, 3f, 4f);
        close(q.get(0), 1.0, EPS, 0);
        close(q.get(3), 4.0, EPS, 0);
        check(q.isFinite(), 0, "finite");
        check(!new Quatf(0f, Float.NaN, 0f, 1f).isFinite(), 0, "NaN is not finite");
    }
}
