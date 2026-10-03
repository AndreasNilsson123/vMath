package vmath.core;

import vmath.annotations.Eps;
import vmath.annotations.GenerateDouble;
import static vmath.core.Check.check;
import static vmath.core.Check.close;
import static vmath.core.Check.sameRotation;
import static vmath.core.Rnd.N;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

@GenerateDouble
class Mat4fTest {
    @Eps(d = 1e-11)
    static final float EPS = 1e-4f;
    /** Our reversed-Z matrix is exact; JOML's infinite projection bakes in a 1e-6 epsilon. */
    @Eps(d = 1e-5)
    static final float EPS_REVZ = 1e-5f;

    final Rnd rnd = Rnd.create();

    // ------------------------------------------------------------ construction

    @Test
    void basicFactoriesMatchJoml() {
        for (int i = 0; i < N; i++) {
            Vec3f t = rnd.nextVec3f();
            Vec3f s = rnd.nextScaleVec3f();
            Quatf q = rnd.nextUnitQuatf();
            close(Mat4f.translation(t), new Matrix4f().translation(J.j(t)), EPS, i);
            close(Mat4f.scaling(s.x(), s.y(), s.z()), new Matrix4f().scaling(s.x(), s.y(), s.z()), EPS, i);
            close(Mat4f.rotation(q), new Matrix4f().rotation(J.j(q)), EPS, i);
            close(Mat4f.translationRotateScale(t, q, s),
                    new Matrix4f().translationRotateScale(J.j(t), J.j(q), J.j(s)), EPS, i);
            close(Mat4f.IDENTITY, new Matrix4f(), 0f, i);
        }
    }

    @Test
    void projectionsMatchJoml() {
        for (int i = 0; i < N; i++) {
            float fovy = (float) rnd.range(0.3, 2.5);
            float aspect = (float) rnd.range(0.5, 3);
            float near = (float) rnd.range(0.01, 10);
            float far = near * (float) rnd.range(10, 1e4);
            boolean zeroToOne = rnd.nextBoolean();
            close(Mat4f.perspective(fovy, aspect, near, far, zeroToOne),
                    new Matrix4f().setPerspective(fovy, aspect, near, far, zeroToOne), EPS, i);
            close(Mat4f.perspectiveReversedZ(fovy, aspect, near),
                    new Matrix4f().setPerspective(fovy, aspect, Float.POSITIVE_INFINITY, near, true), EPS_REVZ, i);

            float l = (float) rnd.range(-10, -1), r = (float) rnd.range(1, 10);
            float b = (float) rnd.range(-10, -1), t = (float) rnd.range(1, 10);
            close(Mat4f.ortho(l, r, b, t, near, far, zeroToOne),
                    new Matrix4f().setOrtho(l, r, b, t, near, far, zeroToOne), EPS, i);
        }
    }

    @Test
    void reversedZMapsNearToOneAndInfinityToZero() {
        for (int i = 0; i < N; i++) {
            float near = (float) rnd.range(0.01, 10);
            Mat4f p = Mat4f.perspectiveReversedZ(1f, 1.5f, near);
            close(p.transformProject(new Vec3f(0f, 0f, -near)).z(), 1f, EPS, i);
            float dFar = p.transformProject(new Vec3f(0f, 0f, -near * 1e6f)).z();
            check(dFar > 0f && dFar < 1e-5f, i, "far depth should approach 0, was " + dFar);
            float d1 = p.transformProject(new Vec3f(0f, 0f, -near * 2f)).z();
            float d2 = p.transformProject(new Vec3f(0f, 0f, -near * 3f)).z();
            check(d1 > d2, i, "depth must decrease with distance");
        }
    }

    @Test
    void lookAtMatchesJoml() {
        for (int i = 0; i < N; i++) {
            Vec3f eye = rnd.nextVec3f().mul(10f);
            Vec3f center = rnd.nextVec3f();
            Vec3f up = Vec3f.UNIT_Y;
            if (Math.abs(eye.sub(center).normalize().dot(up)) > 0.99f) {
                continue; // degenerate: view direction parallel to up
            }
            Mat4f view = Mat4f.lookAt(eye, center, up);
            close(view, new Matrix4f().setLookAt(J.j(eye), J.j(center), J.j(up)), EPS, i);
            // the eye lands at the origin and the target on the -Z axis
            close(view.transformPosition(eye), Vec3f.ZERO, EPS * 10f, i);
            Vec3f c = view.transformPosition(center);
            check(c.z() < 0f && Math.abs(c.x()) < 1e-3f && Math.abs(c.y()) < 1e-3f, i, "center on -Z, got " + c);
        }
    }

    // ------------------------------------------------------------ algebra

    @Test
    void algebraMatchesJoml() {
        for (int i = 0; i < N; i++) {
            Mat4f a = rnd.nextDenseMat4f();
            Mat4f b = rnd.nextDenseMat4f();
            Mat4f trs = rnd.nextTrsMat4f();
            close(a.mul(b), J.j(a).mul(J.j(b)), EPS, i);
            close(a.transpose(), J.j(a).transpose(), EPS, i);
            close(a.determinant(), J.j(a).determinant(), EPS, i);
            close(a.invert(), J.j(a).invert(), EPS, i);
            close(trs.invert(), J.j(trs).invert(), EPS, i);
            close(trs.invertAffine(), J.j(trs).invertAffine(), EPS, i);
            close(trs.normalMatrix(), J.j(trs).normal(new Matrix3f()), EPS, i);
            close(trs.upperLeft3x3(), J.j(trs).get3x3(new Matrix3f()), EPS, i);
        }
    }

    @Test
    void inverseProperties() {
        for (int i = 0; i < N; i++) {
            Mat4f a = rnd.nextDenseMat4f();
            Mat4f trs = rnd.nextTrsMat4f();
            close(a.mul(a.invert()), Mat4f.IDENTITY, EPS, i);
            close(trs.mul(trs.invertAffine()), Mat4f.IDENTITY, EPS, i);
            close(trs.invertAffine(), trs.invert(), EPS, i);
        }
        check(!Mat4f.scaling(1f, 1f, 0f).invert().isFinite(), 0, "singular inverse must be non-finite");
    }

    @Test
    void mulComposesRightToLeft() {
        for (int i = 0; i < N; i++) {
            Mat4f a = rnd.nextTrsMat4f();
            Mat4f b = rnd.nextTrsMat4f();
            Vec3f v = rnd.nextVec3f();
            close(a.mul(b).transformPosition(v), a.transformPosition(b.transformPosition(v)), EPS * 10f, i);
        }
    }

    // ------------------------------------------------------------ transforms

    @Test
    void transformsMatchJoml() {
        for (int i = 0; i < N; i++) {
            Mat4f trs = rnd.nextTrsMat4f();
            Mat4f dense = rnd.nextDenseMat4f();
            Vec3f v = rnd.nextVec3f();
            Vec4f h = rnd.nextVec4f();
            close(dense.transform(h), J.j(dense).transform(J.j(h)), EPS, i);
            close(trs.transformPosition(v), J.j(trs).transformPosition(J.j(v)), EPS, i);
            close(trs.transformDirection(v), J.j(trs).transformDirection(J.j(v)), EPS, i);
            float w = dense.row(3).dot(Vec4f.point(v));
            if (Math.abs(w) > 0.1f) {
                close(dense.transformProject(v), J.j(dense).transformProject(J.j(v)), EPS * 10f, i);
            }
        }
    }

    // ------------------------------------------------------------ accessors and layout

    @Test
    void accessorsAreConsistent() {
        Mat4f a = rnd.nextDenseMat4f();
        Matrix4f ja = J.j(a);
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                check(a.get(c, r) == ja.get(c, r), 0, "get(" + c + "," + r + ")");
                check(a.column(c).get(r) == a.row(r).get(c), 0, "row/column mismatch");
            }
        }
        check(Mat4f.fromColumns(a.column(0), a.column(1), a.column(2), a.column(3)).equals(a), 0, "columns");
        float[] arr = new float[20];
        a.writeTo(arr, 4);
        check(Mat4f.fromArray(arr, 4).equals(a), 0, "array round trip");
        Vec3f t = rnd.nextVec3f();
        check(a.withTranslation(t).getTranslation().equals(t), 0, "withTranslation");
    }

    @Test
    void writeToMatchesJomlLayout() {
        var buf = java.nio.FloatBuffer.allocate(20);
        var ref = java.nio.FloatBuffer.allocate(20);
        Mat4f a = rnd.nextDenseMat4f();
        a.writeTo(buf, 4);
        J.j(a).get(4, ref);
        check(buf.equals(ref), 0, "buffer layout differs");
        check(buf.position() == 0, 0, "writeTo must not move the position");
    }

    // ------------------------------------------------------------ more factories, decomposition

    @Test
    void frustumAndInfinitePerspectiveMatchJoml() {
        for (int i = 0; i < N; i++) {
            float l = (float) rnd.range(-5, -0.1), r = (float) rnd.range(0.1, 5);
            float b = (float) rnd.range(-5, -0.1), t = (float) rnd.range(0.1, 5);
            float near = (float) rnd.range(0.05, 5);
            float far = near * (float) rnd.range(10, 1e3);
            boolean zeroToOne = rnd.nextBoolean();
            close(Mat4f.frustum(l, r, b, t, near, far, zeroToOne),
                    new Matrix4f().setFrustum(l, r, b, t, near, far, zeroToOne), EPS, i);
            float fovy = (float) rnd.range(0.3, 2.5);
            float aspect = (float) rnd.range(0.5, 3);
            close(Mat4f.perspectiveInfinite(fovy, aspect, near, zeroToOne),
                    new Matrix4f().setPerspective(fovy, aspect, near, Float.POSITIVE_INFINITY, zeroToOne), EPS_REVZ, i);
        }
    }

    @Test
    void infinitePerspectiveIsTheLimitOfFinite() {
        float fovy = 1.1f, aspect = 1.6f, near = 0.1f;
        for (boolean zeroToOne : new boolean[] {false, true}) {
            close(Mat4f.perspectiveInfinite(fovy, aspect, near, zeroToOne),
                    Mat4f.perspective(fovy, aspect, near, 1e7f, zeroToOne), 1e-3, 0);
        }
    }

    @Test
    void axisRotationsAndLookTo() {
        for (int i = 0; i < N; i++) {
            float angle = (float) rnd.range(-7, 7);
            Vec3f axis = rnd.nextVec3f();
            // exact trigonometry as the oracle: JOML's cosFromSin loses digits near +-PI/2 (see Mat3fTest)
            close(Mat4f.rotationX(angle), Mat4f.fromMat3(Mat3f.rotationX(angle)), EPS, i);
            close(Mat4f.rotationY(angle), Mat4f.fromMat3(Mat3f.rotationY(angle)), EPS, i);
            close(Mat4f.rotationZ(angle), Mat4f.fromMat3(Mat3f.rotationZ(angle)), EPS, i);
            close(Mat4f.rotationAxis(angle, axis), Mat4f.rotation(Quatf.fromAxisAngle(angle, axis)), EPS, i);
            Vec3f eye = rnd.nextVec3f();
            Vec3f dir = rnd.nextVec3f();
            Vec3f up = Vec3f.UNIT_Y;
            if (dir.normalize().cross(up).length() < 0.2f) {
                continue;
            }
            close(Mat4f.lookTo(eye, dir, up), new Matrix4f().lookAlong(J.j(dir), J.j(up)).translate(J.j(eye).negate()), EPS, i);
            close(Mat4f.lookTo(eye, dir, up), Mat4f.lookAt(eye, eye.add(dir), up), EPS, i);
        }
    }

    @Test
    void decomposeRecoversTranslationRotationScale() {
        for (int i = 0; i < N; i++) {
            Vec3f t = rnd.nextVec3f();
            Quatf q = rnd.nextUnitQuatf();
            Vec3f s = rnd.nextScaleVec3f();
            Mat4f.Trs d = Mat4f.translationRotateScale(t, q, s).decompose();
            close(d.translation(), t, EPS, i);
            close(d.scale(), s, EPS * 10, i);
            sameRotation(d.rotation(), q, EPS * 10, i);
        }
    }

    @Test
    void decomposeRecomposesMirroredMatrices() {
        for (int i = 0; i < N; i++) {
            Vec3f s = rnd.nextScaleVec3f();
            Vec3f mirrored = new Vec3f(-s.x(), s.y(), s.z());
            Vec3f t = rnd.nextVec3f();
            Quatf q = rnd.nextUnitQuatf();
            Mat4f m = Mat4f.translationRotateScale(t, q, mirrored);
            Mat4f.Trs d = m.decompose();
            close(Mat4f.translationRotateScale(d.translation(), d.rotation(), d.scale()), m, EPS * 10, i);
            check(d.scale().x() < 0f, i, "reflection must be folded into a negative x scale");
        }
    }

    @Test
    void isAffineDistinguishesProjections() {
        check(Mat4f.IDENTITY.isAffine(0f), 0, "identity is affine");
        check(rnd.nextTrsMat4f().isAffine(EPS), 0, "TRS is affine");
        check(!Mat4f.perspective(1f, 1.5f, 0.1f, 100f, false).isAffine(EPS), 0, "perspective is not affine");
    }
}
