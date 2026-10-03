package vmath.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vmath.core.Check.close;
import static vmath.core.Check.closeArr;
import static vmath.core.Rnd.N;

import java.nio.FloatBuffer;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;
import org.junit.jupiter.api.Test;
import vmath.annotations.DoubleOnly;
import vmath.annotations.Eps;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;

/** {@link Mat3x2f}: against JOML's {@code Matrix3x2f}, against the homogeneous 3x3 matrix, and by round trips. */
@GenerateDouble
class Mat3x2fTest {
    @Eps(d = 1e-10)
    static final float EPS = 1e-4f;

    final Rnd rnd = Rnd.create();

    private Mat3x2f random() {
        return new Mat3x2f((float) rnd.range(-3, 3), (float) rnd.range(-3, 3), (float) rnd.range(-3, 3), (float) rnd.range(-3, 3), (float) rnd.range(-5, 5), (float) rnd.range(-5, 5));
    }

    private Mat3x2f trs() {
        return Mat3x2f.translationRotateScale(rnd.nextVec2f(), (float) rnd.range(-3, 3), new Vec2f((float) rnd.range(0.4, 3), (float) rnd.range(0.4, 3)));
    }

    private static double[] arr(Mat3x2f m) {
        return new double[] {m.m00(), m.m01(), m.m10(), m.m11(), m.m20(), m.m21()};
    }

    private static double[] arr(Matrix3x2f m) {
        return new double[] {m.m00(), m.m01(), m.m10(), m.m11(), m.m20(), m.m21()};
    }

    private static Matrix3x2f j(Mat3x2f m) {
        return new Matrix3x2f(m.m00(), m.m01(), m.m10(), m.m11(), m.m20(), m.m21());
    }

    @Test
    void factoriesMatchJoml() {
        for (int i = 0; i < N; i++) {
            float angle = (float) rnd.range(-7, 7), x = (float) rnd.range(-5, 5), y = (float) rnd.range(-5, 5), sx = (float) rnd.range(-3, 3), sy = (float) rnd.range(-3, 3);
            closeArr(arr(Mat3x2f.translation(x, y)), arr(new Matrix3x2f().translation(x, y)), EPS, i);
            closeArr(arr(Mat3x2f.translation(new Vec2f(x, y))), arr(new Matrix3x2f().translation(x, y)), EPS, i);
            closeArr(arr(Mat3x2f.rotation(angle)), new double[] {Math.cos(angle), Math.sin(angle), -Math.sin(angle), Math.cos(angle), 0, 0}, EPS, i);
            closeArr(arr(Mat3x2f.scaling(sx, sy)), arr(new Matrix3x2f().scaling(sx, sy)), EPS, i);
            closeArr(arr(Mat3x2f.scaling(sx)), arr(new Matrix3x2f().scaling(sx)), EPS, i);
            // T R S as JOML builds it: translate, rotate, scale
            Vec2f t = new Vec2f(x, y);
            // T R S is the product of the three factories
            closeArr(arr(Mat3x2f.translationRotateScale(t, angle, new Vec2f(sx, sy))), arr(Mat3x2f.translation(t).mul(Mat3x2f.rotation(angle)).mul(Mat3x2f.scaling(sx, sy))), EPS * 10, i);
            closeArr(arr(Mat3x2f.translationRotateScale(t, 0f, new Vec2f(sx, sy))), arr(new Matrix3x2f().translate(x, y).scale(sx, sy)), EPS * 10, i);
        }
        closeArr(arr(Mat3x2f.IDENTITY), arr(new Matrix3x2f()), 0.0, 0);
    }

    @Test
    void productsTransformsAndInversesMatchJoml() {
        for (int i = 0; i < N; i++) {
            Mat3x2f a = random(), b = random();
            Vec2f v = rnd.nextVec2f();
            closeArr(arr(a.mul(b)), arr(j(a).mul(j(b), new Matrix3x2f())), EPS * 10, i);
            close(a.transformPosition(v), j(a).transformPosition(new Vector2f(v.x(), v.y())), EPS * 10, i);
            close(a.transformDirection(v), j(a).transformDirection(new Vector2f(v.x(), v.y())), EPS * 10, i);
            assertEquals(j(a).determinant(), a.determinant(), EPS * 10);
            if (Math.abs(a.determinant()) > 0.3) {
                closeArr(arr(a.invert()), arr(j(a).invert(new Matrix3x2f())), EPS * 100, i);
                closeArr(arr(a.mul(a.invert())), arr(Mat3x2f.IDENTITY), EPS * 100, i);
                closeArr(arr(a.invert().mul(a)), arr(Mat3x2f.IDENTITY), EPS * 100, i);
            }
        }
    }

    @Test
    void behavesLikeTheHomogeneousMatrix() {
        for (int i = 0; i < N; i++) {
            Mat3x2f a = random(), b = random();
            Mat3f ha = a.toMat3(), hb = b.toMat3();
            assertEquals(0f, ha.m02());
            assertEquals(0f, ha.m12());
            assertEquals(1f, ha.m22());
            // the product of the homogeneous matrices is the homogeneous matrix of the product
            assertTrue(Mat3x2f.fromMat3(ha.mul(hb)).approxEquals(a.mul(b), EPS * 10));
            assertTrue(Mat3x2f.fromMat3(ha).approxEquals(a, 0f));
            Vec2f v = rnd.nextVec2f();
            Vec3f h = ha.transform(new Vec3f(v.x(), v.y(), 1f));
            close(a.transformPosition(v), new Vector2f(h.x(), h.y()), EPS * 10, i);
            if (Math.abs(a.determinant()) > 0.3) {
                assertTrue(Mat3x2f.fromMat3(ha.invert()).approxEquals(a.invert(), EPS * 100));
            }
            assertEquals(a.determinant(), ha.determinant(), EPS * 10);
        }
    }

    @Test
    void linearPartTranslationAndConstruction() {
        Mat3x2f m = new Mat3x2f(1f, 2f, 3f, 4f, 5f, 6f);
        assertEquals(new Mat2f(1f, 2f, 3f, 4f), m.linear());
        assertEquals(new Vec2f(5f, 6f), m.getTranslation());
        assertEquals(m, Mat3x2f.fromMat2(new Mat2f(1f, 2f, 3f, 4f), new Vec2f(5f, 6f)));
        assertEquals(new Mat3x2f(1f, 2f, 3f, 4f, 0f, 0f), Mat3x2f.fromMat2(new Mat2f(1f, 2f, 3f, 4f)));
        assertEquals(m, Mat3x2f.fromColumns(new Vec2f(1f, 2f), new Vec2f(3f, 4f), new Vec2f(5f, 6f)));
        assertEquals(new Vec2f(1f, 2f), m.column(0));
        assertEquals(new Vec2f(3f, 4f), m.column(1));
        assertEquals(new Vec2f(5f, 6f), m.column(2));
        assertEquals(new Vec3f(1f, 3f, 5f), m.row(0));
        assertEquals(new Vec3f(2f, 4f, 6f), m.row(1));
        for (int c = 0; c < 3; c++) {
            for (int r = 0; r < 2; r++) {
                assertEquals(m.column(c).get(r), m.get(c, r));
            }
        }
        assertThrows(IndexOutOfBoundsException.class, () -> m.column(3));
        assertThrows(IndexOutOfBoundsException.class, () -> m.row(2));
        assertThrows(IndexOutOfBoundsException.class, () -> m.get(3, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> m.get(0, 2));
        // the translation applies to points and not to directions
        close(m.transformPosition(Vec2f.ZERO), new Vector2f(5f, 6f), EPS, 0);
        close(m.transformDirection(Vec2f.ZERO), new Vector2f(0f, 0f), EPS, 0);
    }

    @Test
    void rotationAboutAPivotKeepsThePivot() {
        for (int i = 0; i < N; i++) {
            Vec2f pivot = rnd.nextVec2f();
            float angle = (float) rnd.range(-7, 7);
            Mat3x2f r = Mat3x2f.rotationAround(angle, pivot);
            close(r.transformPosition(pivot), new Vector2f(pivot.x(), pivot.y()), EPS * 10, i);
            Vec2f p = rnd.nextVec2f();
            Vec2f expected = p.rotateAround(pivot, angle);
            close(r.transformPosition(p), new Vector2f(expected.x(), expected.y()), EPS * 10, i);
            assertEquals(1f, r.determinant(), EPS);
        }
    }

    @Test
    void decompositionRecomposes() {
        for (int i = 0; i < N; i++) {
            Vec2f t = rnd.nextVec2f();
            float angle = (float) rnd.range(-3, 3);
            Vec2f s = new Vec2f((float) rnd.range(0.4, 3), (float) rnd.range(0.4, 3));
            for (int mirrored = 0; mirrored < 2; mirrored++) {
                Vec2f sm = mirrored == 0 ? s : new Vec2f(-s.x(), s.y());
                Mat3x2f m = Mat3x2f.translationRotateScale(t, angle, sm);
                Mat3x2f.Trs d = m.decompose();
                assertTrue(Mat3x2f.translationRotateScale(d.translation(), d.rotation(), d.scale()).approxEquals(m, EPS * 10), "trial " + i + " mirrored " + mirrored);
                assertTrue(d.translation().approxEquals(t, EPS));
                if (mirrored == 0) {
                    assertEquals(angle, d.rotation(), EPS * 10);
                    assertTrue(d.scale().approxEquals(s, EPS * 10));
                }
            }
        }
    }

    @Test
    void decompositionWithShearIsExact() {
        for (int i = 0; i < N; i++) {
            Vec2f t = rnd.nextVec2f();
            float angle = (float) rnd.range(-3, 3), shear = (float) rnd.range(-1.5, 1.5);
            Vec2f s = new Vec2f((float) rnd.range(0.4, 3), (float) rnd.range(0.4, 3));
            for (int mirrored = 0; mirrored < 2; mirrored++) {
                Vec2f sm = mirrored == 0 ? s : new Vec2f(-s.x(), s.y());
                Mat3x2f m = Mat3x2f.translationRotateShearScale(t, angle, shear, sm);
                Mat3x2f.ShearDecomposition d = m.decomposeWithShear();
                assertTrue(Mat3x2f.translationRotateShearScale(d.translation(), d.rotation(), d.shear(), d.scale()).approxEquals(m, EPS * 10), "trial " + i + " mirrored " + mirrored);
                if (mirrored == 0) {
                    assertEquals(angle, d.rotation(), EPS * 10);
                    assertEquals(shear, d.shear(), EPS * 10);
                    assertTrue(d.scale().approxEquals(s, EPS * 10));
                }
            }
            assertTrue(Mat3x2f.translationRotateShearScale(t, angle, 0f, s).approxEquals(Mat3x2f.translationRotateScale(t, angle, s), EPS));
        }
        // a non-uniform scale below a rotation shears: plain decompose cannot represent it, decomposeWithShear can
        Mat3x2f m = Mat3x2f.scaling(1f, 3f).mul(Mat3x2f.rotation(0.7f));
        Mat3x2f.Trs plain = m.decompose();
        assertFalse(Mat3x2f.translationRotateScale(plain.translation(), plain.rotation(), plain.scale()).approxEquals(m, 1e-3f));
        Mat3x2f.ShearDecomposition d = m.decomposeWithShear();
        assertTrue(Mat3x2f.translationRotateShearScale(d.translation(), d.rotation(), d.shear(), d.scale()).approxEquals(m, EPS * 10));
        // with no shear in the matrix the shear of the decomposition is zero
        assertEquals(0f, trs().decomposeWithShear().shear(), EPS * 10);
    }

    @Test
    void validityAndWriters() {
        Mat3x2f m = new Mat3x2f(1f, 2f, 3f, 4f, 5f, 6f);
        assertTrue(m.isFinite());
        for (int k = 0; k < 6; k++) {
            float[] v = {1f, 2f, 3f, 4f, 5f, 6f};
            v[k] = Float.NaN;
            assertFalse(Mat3x2f.fromArray(v, 0).isFinite(), "component " + k);
            v[k] = Float.POSITIVE_INFINITY;
            assertFalse(Mat3x2f.fromArray(v, 0).isFinite(), "component " + k);
            v[k] += 0;
        }
        for (int k = 0; k < 6; k++) {
            float[] v = {1f, 2f, 3f, 4f, 5f, 6f};
            v[k] += 0.5f;
            assertFalse(m.approxEquals(Mat3x2f.fromArray(v, 0), 0.1f), "component " + k);
            assertTrue(m.approxEquals(Mat3x2f.fromArray(v, 0), 0.6f));
        }
        float[] a = new float[8];
        m.writeTo(a, 2);
        assertEquals(m, Mat3x2f.fromArray(a, 2));
        assertEquals(0f, a[1]);
        FloatBuffer buf = FloatBuffer.allocate(8);
        m.writeTo(buf, 1);
        assertEquals(0, buf.position());
        assertEquals(1f, buf.get(1));
        assertEquals(6f, buf.get(6));
        assertFalse(new Mat3x2f(0f, 0f, 0f, 0f, 1f, 1f).invert().isFinite());
    }

    @Test
    void vectorHelpersOfVec2() {
        for (int i = 0; i < N; i++) {
            float angle = (float) rnd.range(-3, 3);
            Vec2f u = Vec2f.fromAngle(angle);
            assertEquals(1f, u.length(), EPS);
            assertEquals(angle, u.polarAngle(), EPS);
            Vec2f a = rnd.nextVec2f(), b = rnd.nextVec2f();
            if (a.length() > 0.2f && b.length() > 0.2f) {
                // the signed angle rotates a onto b
                Vec2f rotated = a.rotate(a.signedAngle(b));
                assertEquals(0f, rotated.normalize().sub(b.normalize()).length(), EPS * 10);
                assertEquals(Math.abs(a.signedAngle(b)), a.angle(b), EPS * 10);
                assertEquals(-a.signedAngle(b), b.signedAngle(a), EPS * 10);
            }
            assertEquals(a.cross(b), a.perpDot(b), 0f);
            assertEquals(-a.perpDot(b), b.perpDot(a), 0f);
            // the orientation: positive for counter-clockwise, and twice the triangle area
            Vec2f c = rnd.nextVec2f();
            assertEquals(Vec2f.orient(a, b, c), -Vec2f.orient(a, c, b), EPS * 10);
            assertEquals(Vec2f.orient(a, b, c), b.sub(a).perpDot(c.sub(a)), EPS * 10);
        }
        assertTrue(Vec2f.orient(new Vec2f(0f, 0f), new Vec2f(1f, 0f), new Vec2f(0f, 1f)) > 0f);
        assertTrue(Vec2f.orient(new Vec2f(0f, 0f), new Vec2f(0f, 1f), new Vec2f(1f, 0f)) < 0f);
        assertEquals(0f, Vec2f.orient(new Vec2f(0f, 0f), new Vec2f(1f, 1f), new Vec2f(2f, 2f)));
        assertEquals(0f, Vec2f.ZERO.polarAngle());
    }

    @FloatOnly
    @Test
    void wideningRoundTrips() {
        Mat3x2f m = new Mat3x2f(1f, 2f, 3f, 4f, 5f, 6f);
        assertEquals(m, m.toDouble().toFloat());
    }

    @DoubleOnly
    @Test
    void narrowingRoundTripsForRepresentableValues() {
        Mat3x2d m = new Mat3x2d(1, 2, 3, 4, 5, 6);
        assertEquals(m, m.toFloat().toDouble());
    }
}
