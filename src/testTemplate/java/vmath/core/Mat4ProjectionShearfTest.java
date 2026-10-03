package vmath.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vmath.core.Rnd.N;

import org.junit.jupiter.api.Test;
import vmath.annotations.Eps;
import vmath.annotations.GenerateDouble;

/** The fast projection inverse, the shear matrix, the orthonormality test and the shear-aware decomposition of {@link Mat4f}. */
@GenerateDouble
class Mat4ProjectionShearfTest {
    @Eps(d = 1e-9)
    static final float EPS = 2e-3f;
    @Eps(d = 1e-10)
    static final float TIGHT = 2e-4f;

    final Rnd rnd = Rnd.create();

    private Mat4f randomProjection(int kind) {
        float fovy = (float) rnd.range(0.3, 2.5), aspect = (float) rnd.range(0.5, 3), near = (float) rnd.range(0.05, 5), far = near * (float) rnd.range(5, 500);
        ClipSpace space = ClipSpace.values()[(int) (rnd.range(0, 3)) % ClipSpace.values().length];
        switch (kind) {
            case 0:
                return Mat4f.perspective(fovy, aspect, near, far, space);
            case 1:
                return Mat4f.perspectiveInfinite(fovy, aspect, near, space);
            case 2:
                return Mat4f.perspectiveReversedZ(fovy, aspect, near, rnd.nextBoolean() ? ClipSpace.D3D : ClipSpace.VULKAN);
            case 3: {
                float w = (float) rnd.range(0.5, 20), h = (float) rnd.range(0.5, 20);
                return Mat4f.ortho(-w + (float) rnd.range(-1, 1), w, -h, h + (float) rnd.range(0, 1), near, far, space);
            }
            default: {
                float l = (float) rnd.range(-3, -0.1), r = (float) rnd.range(0.1, 3), b = (float) rnd.range(-3, -0.1), t = (float) rnd.range(0.1, 3);
                return Mat4f.frustum(l, r, b, t, near, far, space);
            }
        }
    }

    @Test
    void projectionInverseIsTheInverseForEveryKindOfProjection() {
        for (int i = 0; i < N; i++) {
            for (int kind = 0; kind < 5; kind++) {
                Mat4f p = randomProjection(kind);
                assertTrue(p.isProjection(0f), "kind " + kind);
                Mat4f inv = p.invertProjection();
                assertTrue(p.mul(inv).approxEquals(Mat4f.IDENTITY, EPS), "kind " + kind + " trial " + i + ": P * inverse is not the identity");
                assertTrue(inv.mul(p).approxEquals(Mat4f.IDENTITY, EPS), "kind " + kind + " trial " + i);
                // and it agrees with the general inverse on the points that matter, the corners of clip space
                Mat4f general = p.invert();
                Vec4f clip = new Vec4f((float) rnd.range(-1, 1), (float) rnd.range(-1, 1), (float) rnd.range(0, 1), 1f);
                Vec4f a = inv.transform(clip), b = general.transform(clip);
                float scale = 1f + Math.abs(b.x()) + Math.abs(b.y()) + Math.abs(b.z()) + Math.abs(b.w());
                assertTrue(Math.abs(a.x() / a.w() - b.x() / b.w()) <= 5e-2f * scale / Math.abs(b.w()) + 1e-3f, "kind " + kind);
            }
        }
    }

    @Test
    void reversedOrthoMapsNearToOneAndFarToZero() {
        for (int i = 0; i < N; i++) {
            float near = (float) rnd.range(0.1, 5), far = near + (float) rnd.range(1, 100);
            float l = (float) rnd.range(-10, -1), r = (float) rnd.range(1, 10), b = (float) rnd.range(-10, -1), t = (float) rnd.range(1, 10);
            for (ClipSpace space : new ClipSpace[] {ClipSpace.D3D, ClipSpace.VULKAN}) {
                Mat4f m = Mat4f.orthoReversedZ(l, r, b, t, near, far, space);
                Mat4f plain = Mat4f.ortho(l, r, b, t, near, far, space);
                assertTrue(Math.abs(m.transformProject(new Vec3f(0f, 0f, -near)).z() - 1f) <= TIGHT);
                assertTrue(Math.abs(m.transformProject(new Vec3f(0f, 0f, -far)).z()) <= TIGHT);
                float zMid = (float) rnd.range(near, far);
                float z1 = plain.transformProject(new Vec3f(0f, 0f, -zMid)).z(), z2 = m.transformProject(new Vec3f(0f, 0f, -zMid)).z();
                assertTrue(Math.abs(z1 + z2 - 1f) <= TIGHT, "reversed depth is one minus the plain depth");
                assertTrue(Math.abs(m.transformProject(new Vec3f(1f, 2f, -zMid)).x() - plain.transformProject(new Vec3f(1f, 2f, -zMid)).x()) <= TIGHT);
                assertTrue(m.isProjection(0f));
            }
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Mat4f.orthoReversedZ(-1f, 1f, -1f, 1f, 0.1f, 10f, ClipSpace.OPENGL));
    }

    @Test
    void notEveryMatrixIsAProjection() {
        assertTrue(Mat4f.IDENTITY.isProjection(0f));
        assertTrue(Mat4f.translation(1f, 2f, 3f).isProjection(0f), "an orthographic projection carries a translation in x and y");
        assertFalse(Mat4f.IDENTITY.withTranslation(new Vec3f(0f, 0f, 0f)).mul(Mat4f.rotationZ(0.2f)).isProjection(1e-6f));
        assertFalse(Mat4f.rotationZ(0.3f).isProjection(1e-6f));
        assertFalse(Mat4f.scaling(0f, 1f, 1f).isProjection(0f));
        assertFalse(Mat4f.scaling(1f, 0f, 1f).isProjection(0f));
    }

    @Test
    void shearMovesPointsAlongTheNamedAxis() {
        Vec3f p = new Vec3f(1f, 2f, 3f);
        assertTrue(Mat4f.shear(0.5f, 0f, 0f, 0f, 0f, 0f).transformPosition(p).approxEquals(new Vec3f(1f + 0.5f * 2f, 2f, 3f), TIGHT));
        assertTrue(Mat4f.shear(0f, 0.5f, 0f, 0f, 0f, 0f).transformPosition(p).approxEquals(new Vec3f(1f + 0.5f * 3f, 2f, 3f), TIGHT));
        assertTrue(Mat4f.shear(0f, 0f, 0.5f, 0f, 0f, 0f).transformPosition(p).approxEquals(new Vec3f(1f, 2f + 0.5f * 1f, 3f), TIGHT));
        assertTrue(Mat4f.shear(0f, 0f, 0f, 0.5f, 0f, 0f).transformPosition(p).approxEquals(new Vec3f(1f, 2f + 0.5f * 3f, 3f), TIGHT));
        assertTrue(Mat4f.shear(0f, 0f, 0f, 0f, 0.5f, 0f).transformPosition(p).approxEquals(new Vec3f(1f, 2f, 3f + 0.5f * 1f), TIGHT));
        assertTrue(Mat4f.shear(0f, 0f, 0f, 0f, 0f, 0.5f).transformPosition(p).approxEquals(new Vec3f(1f, 2f, 3f + 0.5f * 2f), TIGHT));
        assertTrue(Mat4f.shear(0f, 0f, 0f, 0f, 0f, 0f).approxEquals(Mat4f.IDENTITY, 0f));
        // a single shear factor keeps the volume: the determinant stays 1
        assertTrue(Math.abs(Mat4f.shear(0.7f, 0f, 0f, 0f, 0f, 0f).determinant() - 1f) <= TIGHT);
    }

    @Test
    void orthonormalityTellsRotationsFromScalesAndShears() {
        for (int i = 0; i < N; i++) {
            Mat4f r = Mat4f.rotation(rnd.nextUnitQuatf()).withTranslation(rnd.nextVec3f());
            assertTrue(r.isOrthonormal(1e-4f));
            assertFalse(Mat4f.scaling(1.5f, 1f, 1f).mul(r).isOrthonormal(1e-4f));
            assertFalse(r.mul(Mat4f.shear(0.3f, 0f, 0f, 0f, 0f, 0f)).isOrthonormal(1e-4f));
        }
        // a mirror is orthonormal too: it differs from a rotation by the sign of the determinant
        Mat4f mirror = Mat4f.scaling(-1f, 1f, 1f);
        assertTrue(mirror.isOrthonormal(1e-6f));
        assertTrue(mirror.determinant() < 0f);
    }

    @Test
    void decompositionWithShearRecomposesExactly() {
        for (int i = 0; i < N; i++) {
            Vec3f t = rnd.nextVec3f();
            Quatf q = rnd.nextUnitQuatf();
            Vec3f s = rnd.nextScaleVec3f();
            Vec3f shear = new Vec3f((float) rnd.range(-1, 1), (float) rnd.range(-1, 1), (float) rnd.range(-1, 1));
            for (int mirrored = 0; mirrored < 2; mirrored++) {
                Vec3f sm = mirrored == 0 ? s : new Vec3f(-s.x(), s.y(), s.z());
                Mat4f m = Mat4f.translationRotateShearScale(t, q, shear, sm);
                Mat4f.ShearDecomposition d = m.decomposeWithShear();
                Mat4f back = Mat4f.translationRotateShearScale(d.translation(), d.rotation(), d.shear(), d.scale());
                assertTrue(back.approxEquals(m, EPS), "trial " + i + " mirrored " + mirrored);
                assertTrue(d.translation().approxEquals(t, TIGHT));
                // the factorisation is unique for a positive-determinant frame: the pieces come back as they went in
                assertTrue(d.scale().approxEquals(sm, EPS) && d.shear().approxEquals(shear, EPS), "scale and shear, trial " + i + " " + d + " vs " + sm + " " + shear);
            }
        }
    }

    @Test
    void withoutShearTheDecompositionsAgree() {
        for (int i = 0; i < N; i++) {
            Mat4f m = rnd.nextTrsMat4f();
            Mat4f.Trs plain = m.decompose();
            Mat4f.ShearDecomposition full = m.decomposeWithShear();
            assertTrue(full.scale().approxEquals(plain.scale(), EPS));
            assertTrue(full.shear().approxEquals(Vec3f.ZERO, EPS), "no shear in a TRS matrix: " + full.shear());
            assertTrue(Mat4f.translationRotateShearScale(full.translation(), full.rotation(), full.shear(), full.scale()).approxEquals(m, EPS));
            assertTrue(Mat4f.translationRotateShearScale(plain.translation(), plain.rotation(), Vec3f.ZERO, plain.scale()).approxEquals(
                    Mat4f.translationRotateScale(plain.translation(), plain.rotation(), plain.scale()), TIGHT));
        }
    }

    @Test
    void ashearedMatrixIsNotRepresentedByPlainDecompose() {
        // the documented limit of decompose(): a non-uniform scale below a rotation shears, and only decomposeWithShear() recomposes it
        Mat4f m = Mat4f.scaling(1f, 3f, 0.5f).mul(Mat4f.rotation(Quatf.fromAxisAngle(0.9f, new Vec3f(0.3f, 0.5f, 0.8f).normalize())));
        Mat4f.Trs plain = m.decompose();
        assertFalse(Mat4f.translationRotateScale(plain.translation(), plain.rotation(), plain.scale()).approxEquals(m, 1e-3f));
        Mat4f.ShearDecomposition d = m.decomposeWithShear();
        assertTrue(Mat4f.translationRotateShearScale(d.translation(), d.rotation(), d.shear(), d.scale()).approxEquals(m, EPS));
    }
}
