package vmath.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vmath.core.Check.close;
import static vmath.core.Check.closeArr;
import static vmath.core.Rnd.N;

import java.nio.FloatBuffer;
import org.joml.Matrix2f;
import org.joml.Vector2f;
import org.junit.jupiter.api.Test;
import vmath.annotations.DoubleOnly;
import vmath.annotations.Eps;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;

/** {@link Mat2f}: against JOML where it has the operation, and against the algebra otherwise. */
@GenerateDouble
class Mat2fTest {
    @Eps(d = 1e-11)
    static final float EPS = 1e-4f;

    final Rnd rnd = Rnd.create();

    private Mat2f random() {
        return new Mat2f((float) rnd.range(-3, 3), (float) rnd.range(-3, 3), (float) rnd.range(-3, 3), (float) rnd.range(-3, 3));
    }

    private static double[] arr(Mat2f m) {
        return new double[] {m.m00(), m.m01(), m.m10(), m.m11()};
    }

    private static double[] arr(Matrix2f m) {
        return new double[] {m.m00(), m.m01(), m.m10(), m.m11()};
    }

    private static Matrix2f j(Mat2f m) {
        return new Matrix2f(m.m00(), m.m01(), m.m10(), m.m11());
    }

    @Test
    void factoriesMatchJoml() {
        for (int i = 0; i < N; i++) {
            float angle = (float) rnd.range(-7, 7), sx = (float) rnd.range(-3, 3), sy = (float) rnd.range(-3, 3);
            // JOML computes the cosine from the sine (losing precision near 90 degrees), so the rotation is checked against the library functions instead
            closeArr(arr(Mat2f.rotation(angle)), new double[] {Math.cos(angle), Math.sin(angle), -Math.sin(angle), Math.cos(angle)}, EPS, i);
            closeArr(arr(Mat2f.scaling(sx, sy)), arr(new Matrix2f().scaling(sx, sy)), EPS, i);
            closeArr(arr(Mat2f.scaling(sx)), arr(new Matrix2f().scaling(sx)), EPS, i);
        }
        closeArr(arr(Mat2f.IDENTITY), arr(new Matrix2f()), 0.0, 0);
        closeArr(arr(Mat2f.ZERO), new double[4], 0.0, 0);
    }

    @Test
    void productsTransformsAndInversesMatchJoml() {
        for (int i = 0; i < N; i++) {
            Mat2f a = random(), b = random();
            Vec2f v = rnd.nextVec2f();
            closeArr(arr(a.mul(b)), arr(j(a).mul(j(b), new Matrix2f())), EPS * 10, i);
            Vector2f e = j(a).transform(new Vector2f(v.x(), v.y()));
            close(a.transform(v), e, EPS * 10, i);
            closeArr(arr(a.transpose()), arr(j(a).transpose(new Matrix2f())), 0.0, i);
            assertEquals(j(a).determinant(), a.determinant(), EPS * 10);
            if (Math.abs(a.determinant()) > 0.2) {
                closeArr(arr(a.invert()), arr(j(a).invert(new Matrix2f())), EPS * 100, i);
                // the inverse really is one
                Mat2f id = a.mul(a.invert());
                closeArr(arr(id), arr(Mat2f.IDENTITY), EPS * 100, i);
            }
        }
    }

    @Test
    void algebraOfTheSmallOperations() {
        for (int i = 0; i < N; i++) {
            Mat2f a = random(), b = random();
            float s = (float) rnd.range(-3, 3);
            closeArr(arr(a.add(b)), new double[] {a.m00() + b.m00(), a.m01() + b.m01(), a.m10() + b.m10(), a.m11() + b.m11()}, EPS, i);
            closeArr(arr(a.sub(b)), new double[] {a.m00() - b.m00(), a.m01() - b.m01(), a.m10() - b.m10(), a.m11() - b.m11()}, EPS, i);
            closeArr(arr(a.mul(s)), new double[] {a.m00() * s, a.m01() * s, a.m10() * s, a.m11() * s}, EPS, i);
            assertEquals(a.m00() + a.m11(), a.trace(), EPS);
            // adjugate times the matrix is the determinant times the identity (also for a singular matrix)
            closeArr(arr(a.mul(a.adjugate())), new double[] {a.determinant(), 0, 0, a.determinant()}, EPS * 10, i);
            // the normal matrix: inverse transpose
            if (Math.abs(a.determinant()) > 0.2) {
                closeArr(arr(a.normal()), arr(a.invert().transpose()), 0.0, i);
            }
            // (a b)^T = b^T a^T and det(a b) = det a det b
            closeArr(arr(a.mul(b).transpose()), arr(b.transpose().mul(a.transpose())), EPS * 10, i);
            assertEquals(a.determinant() * b.determinant(), a.mul(b).determinant(), EPS * 100);
        }
    }

    @Test
    void shearsReflectionsAndOuterProducts() {
        Vec2f p = new Vec2f(2f, 3f);
        close(Mat2f.shearX(0.5f).transform(p), new Vector2f(3.5f, 3f), EPS, 0);
        close(Mat2f.shearY(0.5f).transform(p), new Vector2f(2f, 4f), EPS, 0);
        assertEquals(1f, Mat2f.shearX(7f).determinant(), EPS);
        for (int i = 0; i < N; i++) {
            Vec2f axis = Vec2f.fromAngle((float) rnd.range(-3, 3));
            Mat2f r = Mat2f.reflection(axis);
            assertEquals(-1f, r.determinant(), EPS);
            assertTrue(r.isOrthonormal(EPS));
            // the axis stays, its perpendicular flips, and reflecting twice is the identity
            close(r.transform(axis), new Vector2f(axis.x(), axis.y()), EPS, i);
            Vec2f perp = axis.perpendicular();
            close(r.transform(perp), new Vector2f(-perp.x(), -perp.y()), EPS, i);
            closeArr(arr(r.mul(r)), arr(Mat2f.IDENTITY), EPS * 10, i);
            Vec2f a = rnd.nextVec2f(), b = rnd.nextVec2f(), v = rnd.nextVec2f();
            // the outer product maps v to a (b . v)
            close(Mat2f.outer(a, b).transform(v), new Vector2f(a.x() * b.dot(v), a.y() * b.dot(v)), EPS * 10, i);
        }
    }

    @Test
    void rotationsAndTheirAngle() {
        for (int i = 0; i < N; i++) {
            float angle = (float) rnd.range(-3, 3);
            Mat2f r = Mat2f.rotation(angle);
            assertTrue(r.isOrthonormal(EPS));
            assertEquals(1f, r.determinant(), EPS);
            assertEquals(angle, r.rotationAngle(), EPS);
            // rotating a vector is Vec2f.rotate
            Vec2f v = rnd.nextVec2f();
            close(r.transform(v), new Vector2f(v.rotate(angle).x(), v.rotate(angle).y()), EPS, i);
            // angles add
            float other = (float) rnd.range(-3, 3);
            assertEquals(Math.sin(angle + other), Mat2f.rotation(angle).mul(Mat2f.rotation(other)).m01(), EPS);
        }
        assertFalse(Mat2f.scaling(2f).isOrthonormal(EPS));
        assertFalse(Mat2f.shearX(0.5f).isOrthonormal(EPS));
        assertTrue(Mat2f.scaling(-1f, 1f).isOrthonormal(EPS));
    }

    @Test
    void accessorsAndConversions() {
        Mat2f m = new Mat2f(1f, 2f, 3f, 4f);
        assertEquals(new Vec2f(1f, 2f), m.column(0));
        assertEquals(new Vec2f(3f, 4f), m.column(1));
        assertEquals(new Vec2f(1f, 3f), m.row(0));
        assertEquals(new Vec2f(2f, 4f), m.row(1));
        assertEquals(1f, m.get(0, 0));
        assertEquals(2f, m.get(0, 1));
        assertEquals(3f, m.get(1, 0));
        assertEquals(4f, m.get(1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> m.column(2));
        assertThrows(IndexOutOfBoundsException.class, () -> m.row(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> m.get(2, 0));
        assertEquals(m, Mat2f.fromColumns(new Vec2f(1f, 2f), new Vec2f(3f, 4f)));
        float[] a = new float[6];
        m.writeTo(a, 1);
        assertEquals(m, Mat2f.fromArray(a, 1));
        assertEquals(0f, a[0]);
        FloatBuffer buf = FloatBuffer.allocate(6);
        m.writeTo(buf, 2);
        assertEquals(0, buf.position());
        assertEquals(1f, buf.get(2));
        assertEquals(4f, buf.get(5));
        assertTrue(m.isFinite());
        assertFalse(new Mat2f(1f, Float.NaN, 0f, 1f).isFinite());
        assertFalse(new Mat2f(1f, 0f, Float.POSITIVE_INFINITY, 1f).isFinite());
        assertTrue(m.approxEquals(new Mat2f(1.00001f, 2f, 3f, 4f), 1e-3f));
        assertFalse(m.approxEquals(new Mat2f(1f, 2f, 3f, 4.1f), 1e-3f));
        assertFalse(Mat2f.ZERO.invert().isFinite(), "a singular matrix inverts to non-finite components");
    }

    @FloatOnly
    @Test
    void wideningRoundTrips() {
        Mat2f m = new Mat2f(1f, 2f, 3f, 4f);
        assertEquals(m, m.toDouble().toFloat());
    }

    @DoubleOnly
    @Test
    void narrowingRoundTripsForRepresentableValues() {
        Mat2d m = new Mat2d(1, 2, 3, 4);
        assertEquals(m, m.toFloat().toDouble());
    }
}
