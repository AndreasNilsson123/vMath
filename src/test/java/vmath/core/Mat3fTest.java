package vmath.core;

import static vmath.core.Check.check;
import static vmath.core.Check.close;
import static vmath.core.Rnd.N;

import org.joml.Matrix3f;
import org.junit.jupiter.api.Test;

class Mat3fTest {

    static final float EPS = 1e-4f; // @eps-double 1e-11

    final Rnd rnd = Rnd.create();

    @Test
    void constructionMatchesJoml() {
        for (int i = 0; i < N; i++) {
            Quatf q = rnd.nextUnitQuatf();
            Vec3f s = rnd.nextScaleVec3f();
            close(Mat3f.rotation(q), new Matrix3f().rotation(J.j(q)), EPS, i);
            close(Mat3f.scaling(s.x(), s.y(), s.z()), new Matrix3f().scaling(s.x(), s.y(), s.z()), EPS, i);
            close(Mat3f.IDENTITY, new Matrix3f(), 0f, i);
        }
    }

    @Test
    void algebraMatchesJoml() {
        for (int i = 0; i < N; i++) {
            Mat3f a = rnd.nextDenseMat3f();
            Mat3f b = rnd.nextDenseMat3f();
            Vec3f v = rnd.nextVec3f();
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
            Mat3f a = rnd.nextDenseMat3f();
            close(a.mul(a.invert()), Mat3f.IDENTITY, EPS, i);
            close(a.invert().mul(a), Mat3f.IDENTITY, EPS, i);
            Mat3f r = Mat3f.rotation(rnd.nextUnitQuatf());
            close(r.invert(), r.transpose(), EPS, i);
            close(r.determinant(), 1f, EPS, i);
        }
    }

    @Test
    void columnsAndIndexing() {
        Mat3f a = rnd.nextDenseMat3f();
        check(Mat3f.fromColumns(a.column(0), a.column(1), a.column(2)).equals(a), 0, "column round trip");
        for (int c = 0; c < 3; c++) {
            for (int r = 0; r < 3; r++) {
                check(a.get(c, r) == J.j(a).get(c, r), 0, "get(" + c + "," + r + ")");
            }
        }
    }

    @Test
    void writeToMatchesJomlLayout() {
        var buf = java.nio.FloatBuffer.allocate(12);
        var ref = java.nio.FloatBuffer.allocate(12);
        Mat3f a = rnd.nextDenseMat3f();
        a.writeTo(buf, 3);
        J.j(a).get(3, ref);
        check(buf.equals(ref), 0, "buffer layout differs");
    }
}
