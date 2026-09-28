package vmath.core;

// GENERATED from Mat3fTest.java by tools/GenDouble.java. Do not edit; edit the float source.

import static vmath.core.Check.check;
import static vmath.core.Check.close;
import static vmath.core.Rnd.N;

import org.joml.Matrix3d;
import org.junit.jupiter.api.Test;

class Mat3dTest {

    static final double EPS = 1e-11;

    final Rnd rnd = Rnd.create();

    @Test
    void constructionMatchesJoml() {
        for (int i = 0; i < N; i++) {
            Quatd q = rnd.nextUnitQuatd();
            Vec3d s = rnd.nextScaleVec3d();
            close(Mat3d.rotation(q), new Matrix3d().rotation(J.j(q)), EPS, i);
            close(Mat3d.scaling(s.x(), s.y(), s.z()), new Matrix3d().scaling(s.x(), s.y(), s.z()), EPS, i);
            close(Mat3d.IDENTITY, new Matrix3d(), 0.0, i);
        }
    }

    @Test
    void algebraMatchesJoml() {
        for (int i = 0; i < N; i++) {
            Mat3d a = rnd.nextDenseMat3d();
            Mat3d b = rnd.nextDenseMat3d();
            Vec3d v = rnd.nextVec3d();
            close(a.mul(b), J.j(a).mul(J.j(b)), EPS, i);
            close(a.transform(v), J.j(a).transform(J.j(v)), EPS, i);
            close(a.transpose(), J.j(a).transpose(), EPS, i);
            close(a.determinant(), J.j(a).determinant(), EPS, i);
            close(a.invert(), J.j(a).invert(), EPS, i);
            close(a.normal(), J.j(a).normal(), EPS, i);
        }
    }

    @Test
    void inverseProperties() {
        for (int i = 0; i < N; i++) {
            Mat3d a = rnd.nextDenseMat3d();
            close(a.mul(a.invert()), Mat3d.IDENTITY, EPS, i);
            close(a.invert().mul(a), Mat3d.IDENTITY, EPS, i);
            Mat3d r = Mat3d.rotation(rnd.nextUnitQuatd());
            close(r.invert(), r.transpose(), EPS, i);
            close(r.determinant(), 1.0, EPS, i);
        }
    }

    @Test
    void columnsAndIndexing() {
        Mat3d a = rnd.nextDenseMat3d();
        check(Mat3d.fromColumns(a.column(0), a.column(1), a.column(2)).equals(a), 0, "column round trip");
        for (int c = 0; c < 3; c++) {
            for (int r = 0; r < 3; r++) {
                check(a.get(c, r) == J.j(a).get(c, r), 0, "get(" + c + "," + r + ")");
            }
        }
    }

    @Test
    void writeToMatchesJomlLayout() {
        var buf = java.nio.DoubleBuffer.allocate(12);
        var ref = java.nio.DoubleBuffer.allocate(12);
        Mat3d a = rnd.nextDenseMat3d();
        a.writeTo(buf, 3);
        J.j(a).get(3, ref);
        check(buf.equals(ref), 0, "buffer layout differs");
    }
}
