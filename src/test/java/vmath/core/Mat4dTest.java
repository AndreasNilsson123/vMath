package vmath.core;

// GENERATED from Mat4fTest.java by tools/GenDouble.java. Do not edit; edit the float source.

import static vmath.core.Check.check;
import static vmath.core.Check.close;
import static vmath.core.Rnd.N;

import org.joml.Matrix3d;
import org.joml.Matrix4d;
import org.junit.jupiter.api.Test;

class Mat4dTest {

    static final double EPS = 1e-11;
    /** Our reversed-Z matrix is exact; JOML's infinite projection bakes in a 1e-6 epsilon. */
    static final double EPS_REVZ = 1e-5;

    final Rnd rnd = Rnd.create();

    // ------------------------------------------------------------ construction

    @Test
    void basicFactoriesMatchJoml() {
        for (int i = 0; i < N; i++) {
            Vec3d t = rnd.nextVec3d();
            Vec3d s = rnd.nextScaleVec3d();
            Quatd q = rnd.nextUnitQuatd();
            close(Mat4d.translation(t), new Matrix4d().translation(J.j(t)), EPS, i);
            close(Mat4d.scaling(s.x(), s.y(), s.z()), new Matrix4d().scaling(s.x(), s.y(), s.z()), EPS, i);
            close(Mat4d.rotation(q), new Matrix4d().rotation(J.j(q)), EPS, i);
            close(Mat4d.translationRotateScale(t, q, s),
                    new Matrix4d().translationRotateScale(J.j(t), J.j(q), J.j(s)), EPS, i);
            close(Mat4d.IDENTITY, new Matrix4d(), 0.0, i);
        }
    }

    @Test
    void projectionsMatchJoml() {
        for (int i = 0; i < N; i++) {
            double fovy = rnd.range(0.3, 2.5);
            double aspect = rnd.range(0.5, 3);
            double near = rnd.range(0.01, 10);
            double far = near * rnd.range(10, 1e4);
            boolean zeroToOne = rnd.nextBoolean();
            close(Mat4d.perspective(fovy, aspect, near, far, zeroToOne),
                    new Matrix4d().setPerspective(fovy, aspect, near, far, zeroToOne), EPS, i);
            close(Mat4d.perspectiveReversedZ(fovy, aspect, near),
                    new Matrix4d().setPerspective(fovy, aspect, Double.POSITIVE_INFINITY, near, true), EPS_REVZ, i);

            double l = rnd.range(-10, -1), r = rnd.range(1, 10);
            double b = rnd.range(-10, -1), t = rnd.range(1, 10);
            close(Mat4d.ortho(l, r, b, t, near, far, zeroToOne),
                    new Matrix4d().setOrtho(l, r, b, t, near, far, zeroToOne), EPS, i);
        }
    }

    @Test
    void reversedZMapsNearToOneAndInfinityToZero() {
        for (int i = 0; i < N; i++) {
            double near = rnd.range(0.01, 10);
            Mat4d p = Mat4d.perspectiveReversedZ(1.0, 1.5, near);
            close(p.transformProject(new Vec3d(0.0, 0.0, -near)).z(), 1.0, EPS, i);
            double dFar = p.transformProject(new Vec3d(0.0, 0.0, -near * 1e6)).z();
            check(dFar > 0.0 && dFar < 1e-5, i, "far depth should approach 0, was " + dFar);
            double d1 = p.transformProject(new Vec3d(0.0, 0.0, -near * 2.0)).z();
            double d2 = p.transformProject(new Vec3d(0.0, 0.0, -near * 3.0)).z();
            check(d1 > d2, i, "depth must decrease with distance");
        }
    }

    @Test
    void lookAtMatchesJoml() {
        for (int i = 0; i < N; i++) {
            Vec3d eye = rnd.nextVec3d().mul(10.0);
            Vec3d center = rnd.nextVec3d();
            Vec3d up = Vec3d.UNIT_Y;
            if (Math.abs(eye.sub(center).normalize().dot(up)) > 0.99) {
                continue; // degenerate: view direction parallel to up
            }
            Mat4d view = Mat4d.lookAt(eye, center, up);
            close(view, new Matrix4d().setLookAt(J.j(eye), J.j(center), J.j(up)), EPS, i);
            // the eye lands at the origin and the target on the -Z axis
            close(view.transformPosition(eye), Vec3d.ZERO, EPS * 10.0, i);
            Vec3d c = view.transformPosition(center);
            check(c.z() < 0.0 && Math.abs(c.x()) < 1e-3 && Math.abs(c.y()) < 1e-3, i, "center on -Z, got " + c);
        }
    }

    // ------------------------------------------------------------ algebra

    @Test
    void algebraMatchesJoml() {
        for (int i = 0; i < N; i++) {
            Mat4d a = rnd.nextDenseMat4d();
            Mat4d b = rnd.nextDenseMat4d();
            Mat4d trs = rnd.nextTrsMat4d();
            close(a.mul(b), J.j(a).mul(J.j(b)), EPS, i);
            close(a.transpose(), J.j(a).transpose(), EPS, i);
            close(a.determinant(), J.j(a).determinant(), EPS, i);
            close(a.invert(), J.j(a).invert(), EPS, i);
            close(trs.invert(), J.j(trs).invert(), EPS, i);
            close(trs.invertAffine(), J.j(trs).invertAffine(), EPS, i);
            close(trs.normalMatrix(), J.j(trs).normal(new Matrix3d()), EPS, i);
            close(trs.upperLeft3x3(), J.j(trs).get3x3(new Matrix3d()), EPS, i);
        }
    }

    @Test
    void inverseProperties() {
        for (int i = 0; i < N; i++) {
            Mat4d a = rnd.nextDenseMat4d();
            Mat4d trs = rnd.nextTrsMat4d();
            close(a.mul(a.invert()), Mat4d.IDENTITY, EPS, i);
            close(trs.mul(trs.invertAffine()), Mat4d.IDENTITY, EPS, i);
            close(trs.invertAffine(), trs.invert(), EPS, i);
        }
        check(!Mat4d.scaling(1.0, 1.0, 0.0).invert().isFinite(), 0, "singular inverse must be non-finite");
    }

    @Test
    void mulComposesRightToLeft() {
        for (int i = 0; i < N; i++) {
            Mat4d a = rnd.nextTrsMat4d();
            Mat4d b = rnd.nextTrsMat4d();
            Vec3d v = rnd.nextVec3d();
            close(a.mul(b).transformPosition(v), a.transformPosition(b.transformPosition(v)), EPS * 10.0, i);
        }
    }

    // ------------------------------------------------------------ transforms

    @Test
    void transformsMatchJoml() {
        for (int i = 0; i < N; i++) {
            Mat4d trs = rnd.nextTrsMat4d();
            Mat4d dense = rnd.nextDenseMat4d();
            Vec3d v = rnd.nextVec3d();
            Vec4d h = rnd.nextVec4d();
            close(dense.transform(h), J.j(dense).transform(J.j(h)), EPS, i);
            close(trs.transformPosition(v), J.j(trs).transformPosition(J.j(v)), EPS, i);
            close(trs.transformDirection(v), J.j(trs).transformDirection(J.j(v)), EPS, i);
            double w = dense.row(3).dot(Vec4d.point(v));
            if (Math.abs(w) > 0.1) {
                close(dense.transformProject(v), J.j(dense).transformProject(J.j(v)), EPS * 10.0, i);
            }
        }
    }

    // ------------------------------------------------------------ accessors and layout

    @Test
    void accessorsAreConsistent() {
        Mat4d a = rnd.nextDenseMat4d();
        Matrix4d ja = J.j(a);
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                check(a.get(c, r) == ja.get(c, r), 0, "get(" + c + "," + r + ")");
                check(a.column(c).get(r) == a.row(r).get(c), 0, "row/column mismatch");
            }
        }
        check(Mat4d.fromColumns(a.column(0), a.column(1), a.column(2), a.column(3)).equals(a), 0, "columns");
        double[] arr = new double[20];
        a.writeTo(arr, 4);
        check(Mat4d.fromArray(arr, 4).equals(a), 0, "array round trip");
        Vec3d t = rnd.nextVec3d();
        check(a.withTranslation(t).getTranslation().equals(t), 0, "withTranslation");
    }

    @Test
    void writeToMatchesJomlLayout() {
        var buf = java.nio.DoubleBuffer.allocate(20);
        var ref = java.nio.DoubleBuffer.allocate(20);
        Mat4d a = rnd.nextDenseMat4d();
        a.writeTo(buf, 4);
        J.j(a).get(4, ref);
        check(buf.equals(ref), 0, "buffer layout differs");
        check(buf.position() == 0, 0, "writeTo must not move the position");
    }
}
