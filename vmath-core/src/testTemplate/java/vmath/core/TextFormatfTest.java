package vmath.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vmath.core.Rnd.N;

import org.junit.jupiter.api.Test;
import vmath.annotations.GenerateDouble;

/**
 * CORE-12: {@code parse}, {@code toCompactString}, {@code format} and {@code toMatrixString} of the vector, quaternion and matrix types.
 */
@GenerateDouble
class TextFormatfTest {

    final Rnd rnd = Rnd.create();

    @Test
    void everyFormTheTypesPrintReadsBack() {
        for (int i = 0; i < N; i++) {
            Vec2f a = rnd.nextVec2f();
            Vec3f b = rnd.nextVec3f();
            Vec4f c = rnd.nextVec4f();
            Quatf q = rnd.nextUnitQuatf();
            assertEquals(a, Vec2f.parse(a.toString()), "the toString of the record");
            assertEquals(a, Vec2f.parse(a.toCompactString()), "the compact form");
            assertEquals(b, Vec3f.parse(b.toString()));
            assertEquals(b, Vec3f.parse(b.toCompactString()));
            assertEquals(c, Vec4f.parse(c.toString()));
            assertEquals(c, Vec4f.parse(c.toCompactString()));
            assertEquals(q, Quatf.parse(q.toString()));
            assertEquals(q, Quatf.parse(q.toCompactString()));
        }
    }

    @Test
    void theFormsPeopleWrite() {
        Vec3f v = new Vec3f(1f, 2f, 3f);
        for (String text : new String[] {"1 2 3", "1,2,3", "(1, 2, 3)", "[1; 2; 3]", "{1 , 2 , 3}", "  1\n2\t3 ", "Vec3f[x=1, y=2, z=3]", "z=3, x=1.0, y=2e0", "Vec3f(1,2,3)", "| 1 2 3 |"}) {
            assertEquals(v, Vec3f.parse(text), text);
        }
        Vec2f negative = Vec2f.parse("-1.5e-3 +4");
        assertEquals(-1.5e-3f, negative.x());
        assertEquals(4f, negative.y());
        assertTrue(Float.isNaN(Vec2f.parse("NaN, Infinity").x()) && Float.isInfinite(Vec2f.parse("NaN, Infinity").y()));
    }

    @Test
    void badTextIsRefusedWithAMessageThatNamesTheProblem() {
        IllegalArgumentException few = assertThrows(IllegalArgumentException.class, () -> Vec3f.parse("1 2"));
        assertTrue(few.getMessage().contains("Vec3f") && few.getMessage().contains("3 numbers") && few.getMessage().contains("2"), few.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Vec3f.parse("1 2 3 4"));
        assertThrows(IllegalArgumentException.class, () -> Vec3f.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Vec3f.parse("1 2 abc"));
        assertThrows(IllegalArgumentException.class, () -> Vec3f.parse("x=1, y=2, w=3"), "w is not a component of a Vec3f");
        assertThrows(IllegalArgumentException.class, () -> Vec3f.parse("x=1, x=2, z=3"), "twice");
        assertThrows(IllegalArgumentException.class, () -> Vec3f.parse("x=1, 2, 3"), "labelled and unlabelled");
        assertThrows(IllegalArgumentException.class, () -> Vec3f.parse("x=, y=2, z=3"));
        IllegalArgumentException other = assertThrows(IllegalArgumentException.class, () -> Vec3f.parse("Quatf[x=1, y=2, z=3]"));
        assertTrue(other.getMessage().contains("Quatf"), other.getMessage());
        assertThrows(NullPointerException.class, () -> Vec3f.parse(null));
        IllegalArgumentException long1 = assertThrows(IllegalArgumentException.class, () -> Vec2f.parse("1 2 " + "3 ".repeat(100)));
        assertTrue(long1.getMessage().length() < 200, "the text in the message is shortened");
    }

    @Test
    void fixedDecimalsRoundAndRefuseAbsurdCounts() {
        Vec3f v = new Vec3f(1f, 2.5f, -0.125f);
        assertEquals("(1.00, 2.50, -0.13)", v.format(2), "HALF_UP: -0.125 is exact in binary");
        assertEquals("(1, 2, -0)", new Vec3f(1f, 2.4f, -0.125f).format(0));
        assertEquals("(1.0, 2.5, -0.125)", v.toCompactString());
        assertThrows(IllegalArgumentException.class, () -> v.format(-1));
        assertThrows(IllegalArgumentException.class, () -> v.format(18));
    }

    @Test
    void matricesAreReadRowByRowAndLaidOutRowByRow() {
        // a translation: the last column (m30, m31, m32) holds the offset, which the display shows at the end of the first three rows
        Mat4f translation = new Mat4f(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 2f, 3f, 1f);
        String text = translation.toMatrixString(1);
        assertEquals("| 1.0 0.0 0.0 1.0 |\n| 0.0 1.0 0.0 2.0 |\n| 0.0 0.0 1.0 3.0 |\n| 0.0 0.0 0.0 1.0 |", text);
        assertEquals(translation, Mat4f.parse(text));
        assertEquals(translation, Mat4f.parse("1 0 0 1  0 1 0 2  0 0 1 3  0 0 0 1"), "unlabelled numbers are rows");
        assertEquals(translation, Mat4f.parse(translation.toString()), "the record's own text is labelled and read by name");
        assertEquals(translation, Mat4f.parse(translation.toCompactString()));
        assertEquals("((1.0, 0.0, 0.0, 1.0), (0.0, 1.0, 0.0, 2.0), (0.0, 0.0, 1.0, 3.0), (0.0, 0.0, 0.0, 1.0))", translation.toCompactString());
        Mat3f m3 = new Mat3f(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f); // m00 m01 m02 is the first column
        assertEquals("| 1 4 7 |\n| 2 5 8 |\n| 3 6 9 |", m3.toMatrixString(0));
        assertEquals(m3, Mat3f.parse(m3.toMatrixString(0)));
        Mat2f m2 = new Mat2f(1f, -2f, 3f, 4f);
        assertEquals(m2, Mat2f.parse(m2.toCompactString()));
        assertEquals(m2, Mat2f.parse("m11=4, m00=1, m10=3, m01=-2"));
        // columns are aligned to the widest number
        Mat2f wide = new Mat2f(1f, 10f, -100f, 2f);
        assertEquals("|    1 -100 |\n|   10    2 |", wide.toMatrixString(0));
        assertThrows(IllegalArgumentException.class, () -> Mat4f.parse("1 2 3"));
        assertThrows(IllegalArgumentException.class, () -> translation.toMatrixString(99));
    }

    @Test
    void randomMatricesRoundTripThroughTheCompactAndTheRecordText() {
        for (int i = 0; i < N; i++) {
            Mat4f m = rnd.nextDenseMat4f();
            assertEquals(m, Mat4f.parse(m.toCompactString()));
            assertEquals(m, Mat4f.parse(m.toString()));
            Mat3f m3 = rnd.nextDenseMat3f();
            assertEquals(m3, Mat3f.parse(m3.toCompactString()));
            assertEquals(m3, Mat3f.parse(m3.toString()));
        }
    }
}
