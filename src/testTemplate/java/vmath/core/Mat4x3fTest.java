package vmath.core;

import vmath.annotations.Eps;
import vmath.annotations.GenerateDouble;
import static vmath.core.Check.check;
import static vmath.core.Check.close;
import static vmath.core.Check.sameRotation;
import static vmath.core.Rnd.N;

import org.joml.Matrix3f;
import org.joml.Matrix4x3f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

@GenerateDouble
class Mat4x3fTest {
    @Eps(d = 1e-11)
    static final float EPS = 1e-4f;

    final Rnd rnd = Rnd.create();

    private Mat4x3f trs() {
        return Mat4x3f.fromMat4(rnd.nextTrsMat4f());
    }

    @Test
    void factoriesMatchJoml() {
        for (int i = 0; i < N; i++) {
            Vec3f t = rnd.nextVec3f();
            Vec3f s = rnd.nextScaleVec3f();
            Quatf q = rnd.nextUnitQuatf();
            close(Mat4x3f.translation(t), new Matrix4x3f().translation(J.j(t)), EPS, i);
            close(Mat4x3f.translation(t.x(), t.y(), t.z()), Mat4x3f.translation(t), EPS, i);
            close(Mat4x3f.scaling(s.x(), s.y(), s.z()), new Matrix4x3f().scaling(s.x(), s.y(), s.z()), EPS, i);
            close(Mat4x3f.rotation(q), new Matrix4x3f().rotation(J.j(q)), EPS, i);
            close(Mat4x3f.translationRotateScale(t, q, s),
                    new Matrix4x3f().translationRotateScale(J.j(t), J.j(q), J.j(s)), EPS, i);
            close(Mat4x3f.IDENTITY, new Matrix4x3f(), 0.0, i);
        }
    }

    @Test
    void conversionWithMat4RoundTrips() {
        for (int i = 0; i < N; i++) {
            Mat4f m = rnd.nextTrsMat4f();
            Mat4x3f a = Mat4x3f.fromMat4(m);
            close(a.toMat4(), m, EPS, i);
            close(Mat4x3f.fromMat4(a.toMat4()), a, 0.0, i);
            check(a.toMat4().isAffine(0f), i, "toMat4 has bottom row (0,0,0,1)");
        }
    }

    @Test
    void algebraMatchesJomlAndMat4() {
        for (int i = 0; i < N; i++) {
            Mat4x3f a = trs(), b = trs();
            Vec3f p = rnd.nextVec3f();
            close(a.mul(b), J.j(a).mul(J.j(b)), EPS * 10, i);
            close(a.mul(b).toMat4(), a.toMat4().mul(b.toMat4()), EPS * 10, i);
            close(a.mul(b.toMat4()), a.toMat4().mul(b.toMat4()), EPS * 10, i);
            close(a.transformPosition(p), J.j(a).transformPosition(J.j(p), new Vector3f()), EPS, i);
            close(a.transformDirection(p), J.j(a).transformDirection(J.j(p), new Vector3f()), EPS, i);
            close(a.transformPosition(p), a.toMat4().transformPosition(p), EPS, i);
            close(a.determinant(), J.j(a).determinant(), EPS * 10, i);
            close(a.invert(), J.j(a).invert(), EPS * 10, i);
            close(a.invert().mul(a), Mat4x3f.IDENTITY, EPS * 10, i);
            close(a.mul(a.invert()), Mat4x3f.IDENTITY, EPS * 10, i);
            close(a.normalMatrix(), J.j(a).normal(new Matrix3f()), EPS * 10, i);
            close(a.invert().toMat4(), a.toMat4().invertAffine(), EPS * 10, i);
        }
    }

    @Test
    void invertHandlesMirroredTransforms() {
        for (int i = 0; i < N; i++) {
            Vec3f s = rnd.nextScaleVec3f();
            Mat4x3f m = Mat4x3f.translationRotateScale(rnd.nextVec3f(), rnd.nextUnitQuatf(), new Vec3f(-s.x(), s.y(), s.z()));
            check(m.determinant() < 0f, i, "mirrored");
            close(m.mul(m.invert()), Mat4x3f.IDENTITY, EPS * 10, i);
        }
    }

    @Test
    void accessorsAndPredicates() {
        for (int i = 0; i < N; i++) {
            Mat4x3f m = trs();
            Mat4f full = m.toMat4();
            for (int c = 0; c < 4; c++) {
                for (int r = 0; r < 3; r++) {
                    close(m.get(c, r), full.get(c, r), 0.0, i);
                }
                close(m.column(c), full.column(c).xyz(), 0.0, i);
            }
            close(m.getTranslation(), full.getTranslation(), 0.0, i);
            Vec3f t = rnd.nextVec3f();
            close(m.withTranslation(t).getTranslation(), t, 0.0, i);
            close(m.withTranslation(t).upperLeft3x3(), m.upperLeft3x3(), 0.0, i);
            check(m.approxEquals(m, 0f) && m.isFinite(), i, "self-equal and finite");
            check(!m.approxEquals(m.withTranslation(m.getTranslation().add(1f, 0f, 0f)), EPS), i, "differs by 1");
        }
        check(!new Mat4x3f(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, Float.NaN, 0f).isFinite(), 0, "NaN is not finite");
        check(new Mat4x3f(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 1f, 2f, 3f).column(3).equals(new Vec3f(1f, 2f, 3f)), 0, "translation column");
        var e = org.junit.jupiter.api.Assertions.assertThrows(IndexOutOfBoundsException.class, () -> Mat4x3f.IDENTITY.column(4));
        check(e != null, 0, "bad column index rejected");
    }

    @Test
    void decomposeMatchesMat4() {
        for (int i = 0; i < N; i++) {
            Vec3f t = rnd.nextVec3f();
            Quatf q = rnd.nextUnitQuatf();
            Vec3f s = rnd.nextScaleVec3f();
            Mat4f.Trs d = Mat4x3f.translationRotateScale(t, q, s).decompose();
            close(d.translation(), t, EPS, i);
            close(d.scale(), s, EPS * 10, i);
            sameRotation(d.rotation(), q, EPS * 10, i);
        }
    }

    @Test
    void writersAndFromArray() {
        for (int i = 0; i < N / 10; i++) {
            Mat4x3f m = trs();
            float[] arr = new float[2 + 12];
            m.writeTo(arr, 2);
            close(Mat4x3f.fromArray(arr, 2), m, 0.0, i);
            var buf = java.nio.FloatBuffer.allocate(14);
            var ref = java.nio.FloatBuffer.allocate(14);
            m.writeTo(buf, 2);
            J.j(m).get(2, ref);
            check(buf.equals(ref), i, "buffer layout differs from JOML");
            check(buf.position() == 0, i, "writeTo must not move the position");
            // rows layout: row r holds (m0r, m1r, m2r, m3r)
            float[] rows = new float[12];
            m.writeRows(rows, 0);
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 4; c++) {
                    close(rows[r * 4 + c], m.get(c, r), 0.0, i);
                }
            }
            close(Mat4x3f.fromColumns(m.column(0), m.column(1), m.column(2), m.column(3)), m, 0.0, i);
        }
    }
}
