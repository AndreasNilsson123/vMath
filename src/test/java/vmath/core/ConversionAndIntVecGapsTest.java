package vmath.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** What mutation testing found unchecked in the float-only conversions and in the small integer vectors (docs/COVERAGE.md). */
class ConversionAndIntVecGapsTest {

    @Test
    void toDoubleKeepsEveryComponent() {
        Vec2d a = new Vec2f(1.5f, -2.25f).toDouble();
        assertEquals(1.5, a.x());
        assertEquals(-2.25, a.y());
        Vec4d b = new Vec4f(1f, 2f, 3f, 4f).toDouble();
        assertEquals(4.0, b.w());
        assertEquals(3.0, b.z());
        Mat3f m = Mat3f.fromArray(new float[] {1, 2, 3, 4, 5, 6, 7, 8, 9}, 0);
        Mat3d md = m.toDouble();
        assertNotNull(md);
        double[] back = new double[9];
        md.writeTo(back, 0);
        assertEquals(Arrays.toString(new double[] {1, 2, 3, 4, 5, 6, 7, 8, 9}), Arrays.toString(back));
        Transformf t = new Transformf(new Vec3f(1f, 2f, 3f), new Quatf(0f, 0.6f, 0f, 0.8f), new Vec3f(2f, 2f, 2f));
        Transformd td = t.toDouble();
        assertEquals(3.0, td.translation().z());
        assertEquals(0.6f, (float) td.rotation().y());
        assertEquals(2.0, td.scale().x());
        assertEquals(t, td.toFloat());
    }

    @Test
    void relativeToSubtractsInDoubleAndThenNarrows() {
        assertEquals(new Vec3f(9f, 18f, 27f), new Vec3d(10.0, 20.0, 30.0).relativeTo(new Vec3d(1.0, 2.0, 3.0)));
        assertEquals(new Vec3f(-9f, -18f, -27f), new Vec3d(1.0, 2.0, 3.0).relativeTo(new Vec3d(10.0, 20.0, 30.0)));
        // 6.4 million units out, where a float cannot represent the positions: the difference is still exact
        Vec3f local = new Vec3d(6_400_003.25, 10.5, -50.0).relativeTo(new Vec3d(6_400_000.0, 10.0, 0.0));
        assertEquals(3.25f, local.x());
        assertEquals(0.5f, local.y());
        assertEquals(-50f, local.z());
    }

    @Test
    void smallIntegerVectors() {
        Vec2i p = new Vec2i(7, -3);
        assertEquals(new Vec2i(5, 5), Vec2i.splat(5));
        assertEquals(7, p.get(0));
        assertEquals(-3, p.get(1));
        assertThrows(IndexOutOfBoundsException.class, () -> p.get(2));
        assertEquals(-3, p.minComponent());
        assertEquals(7, p.maxComponent());
        assertEquals(-3, new Vec2i(5, -3).minComponent());
        assertEquals(5, new Vec2i(5, -3).maxComponent());
        Vec3i q = new Vec3i(4, 9, -1);
        assertEquals(new Vec3i(2, 2, 2), Vec3i.splat(2));
        int[] out = new int[6];
        Arrays.fill(out, -9);
        q.writeTo(out, 2);
        assertEquals(Arrays.toString(new int[] {-9, -9, 4, 9, -1, -9}), Arrays.toString(out));
        int[] two = {-9, -9, -9, -9};
        p.writeTo(two, 1);
        assertEquals(Arrays.toString(new int[] {-9, 7, -3, -9}), Arrays.toString(two));
    }
}
