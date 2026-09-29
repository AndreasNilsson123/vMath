package vmath.core;

import vmath.annotations.Eps;
import vmath.annotations.GenerateDouble;
import static vmath.core.Check.check;
import static vmath.core.Check.close;
import static vmath.core.Rnd.N;

import org.joml.Matrix3f;
import org.junit.jupiter.api.Test;

@GenerateDouble
class Mat3fTest {
    @Eps(d = 1e-11)
    static final float EPS = 1e-4f;

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

    // ------------------------------------------------------------ rotations, skew, basis

    /**
     * The oracle here is exact double-precision trigonometry, not JOML: JOML derives the cosine as
     * {@code sqrt(1 - sin^2)}, which loses about half the digits near +-PI/2.
     */
    @Test
    void axisRotationsMatchExactTrigonometry() {
        for (int i = 0; i < N; i++) {
            float angle = (float) rnd.range(-7, 7);
            double c = Math.cos(angle), s = Math.sin(angle);
            close(Mat3f.rotationX(angle), new Mat3f(1f, 0f, 0f, 0f, (float) c, (float) s, 0f, (float) -s, (float) c), EPS, i);
            close(Mat3f.rotationY(angle), new Mat3f((float) c, 0f, (float) -s, 0f, 1f, 0f, (float) s, 0f, (float) c), EPS, i);
            close(Mat3f.rotationZ(angle), new Mat3f((float) c, (float) s, 0f, (float) -s, (float) c, 0f, 0f, 0f, 1f), EPS, i);
            Vec3f axis = rnd.nextVec3f();
            Mat3f r = Mat3f.rotationAxis(angle, axis);
            close(r, Mat3f.rotation(Quatf.fromAxisAngle(angle, axis)), EPS, i);
            close(r.determinant(), 1.0, EPS, i);
            close(r.transform(axis.normalize()), axis.normalize(), EPS, i);
            close(Mat3f.rotationX(angle).transform(Vec3f.UNIT_Y), new Vec3f(0f, (float) c, (float) s), EPS, i);
        }
    }

    @Test
    void skewMatrixIsTheCrossProduct() {
        for (int i = 0; i < N; i++) {
            Vec3f v = rnd.nextVec3f();
            Vec3f u = rnd.nextVec3f();
            close(Mat3f.skew(v).transform(u), v.cross(u), EPS, i);
        }
    }

    @Test
    void basisFromNormalIsOrthonormalAndRightHanded() {
        for (int i = 0; i < N; i++) {
            Vec3f n = rnd.nextVec3f().normalize();
            Mat3f b = Mat3f.basisFromNormal(n);
            close(b.column(2), n, EPS, i);
            close(b.determinant(), 1.0, EPS, i);
            close(b.transpose().mul(b), Mat3f.IDENTITY, EPS, i);
        }
        for (Vec3f axis : new Vec3f[] {Vec3f.UNIT_X, Vec3f.UNIT_Y, Vec3f.UNIT_Z, Vec3f.UNIT_Z.negate()}) {
            Mat3f b = Mat3f.basisFromNormal(axis);
            check(b.isFinite(), 0, "basis at axis-aligned normal must be finite: " + b);
            close(b.transpose().mul(b), Mat3f.IDENTITY, EPS, 0);
        }
    }

    @Test
    void isFiniteDetectsNaN() {
        check(Mat3f.IDENTITY.isFinite(), 0, "identity is finite");
        check(!new Mat3f(1f, 0f, 0f, 0f, Float.NaN, 0f, 0f, 0f, 1f).isFinite(), 0, "NaN");
        check(!new Mat3f(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, Float.POSITIVE_INFINITY).isFinite(), 0, "infinity");
    }
}
