package vmath.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RebaseTest {

    final Rnd rnd = Rnd.create();

    private double[] randomWorld(int n, double centre) {
        double[] a = new double[n];
        for (int i = 0; i < n; i++) {
            a[i] = centre + rnd.range(-50, 50);
        }
        return a;
    }

    @Test
    void positionsAreSubtractedInDoubleBeforeTheyAreNarrowed() {
        double centre = 6_400_000.0;
        double[] world = randomWorld(3 * 100 + 4, centre);
        float[] local = new float[3 * 100 + 5];
        Rebase.positions(world, 2, centre, centre, centre, local, 3, 100);
        int narrowFirstWrong = 0;
        for (int i = 0; i < 100; i++) {
            for (int c = 0; c < 3; c++) {
                float expected = (float) (world[2 + 3 * i + c] - centre);
                assertEquals(expected, local[3 + 3 * i + c], 0f, "element " + i + " component " + c);
                float narrowFirst = (float) world[2 + 3 * i + c] - (float) centre;
                if (Math.abs(narrowFirst - expected) > 0.1f) {
                    narrowFirstWrong++;
                }
            }
        }
        assertTrue(narrowFirstWrong > 30, "narrowing first, with a spacing of 0.5 at this distance, loses the fraction of most values: " + narrowFirstWrong);
        assertEquals(0f, local[0], 0f, "the floats before the output are untouched");
        // the Vec3d form is the same
        float[] again = new float[3 * 100 + 5];
        Rebase.positions(world, 2, new Vec3d(centre, centre, centre), again, 3, 100);
        assertArrayEquals(local, again, 0f);
        // an empty batch needs no arrays
        Rebase.positions(null, 0, 0.0, 0.0, 0.0, null, 0, 0);
        Rebase.positions(null, 0, 0.0, 0.0, 0.0, null, 0, -1);
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> Rebase.positions(world, 0, 0.0, 0.0, 0.0, new float[5], 0, 2));
    }

    @Test
    void matricesKeepTheirRotationAndMoveTheirTranslation() {
        Vec3d origin = new Vec3d(6_400_000.0, -3_000_000.0, 100.0);
        int n = 20;
        double[] world = new double[16 + n * 16];
        Mat4d[] expected = new Mat4d[n];
        for (int i = 0; i < n; i++) {
            Mat4d m = rnd.nextTrsMat4d();
            m = new Mat4d(m.m00(), m.m01(), m.m02(), m.m03(), m.m10(), m.m11(), m.m12(), m.m13(), m.m20(), m.m21(), m.m22(), m.m23(),
                    origin.x() + m.m30(), origin.y() + m.m31(), origin.z() + m.m32(), m.m33());
            expected[i] = m;
            m.writeTo(world, 16 + 16 * i);
        }
        float[] local = new float[n * 16 + 3];
        Rebase.matrices(world, 16, origin, local, 3, n);
        for (int i = 0; i < n; i++) {
            Mat4f got = Mat4f.fromArray(local, 3 + 16 * i);
            Mat4f want = expected[i].relativeTo(origin);
            assertEquals(want, got, "matrix " + i + " equals Mat4d.relativeTo");
            assertEquals((float) (expected[i].m30() - origin.x()), got.m30(), 0f, "the translation is subtracted in double");
            assertEquals((float) expected[i].m00(), got.m00(), 0f, "the rotation is only rounded");
            assertEquals(1f, got.m33(), 0f);
        }
        Rebase.matrices(null, 0, 0.0, 0.0, 0.0, null, 0, 0);
    }

    @Test
    void boundsAreSubtractedLikePositions() {
        double[] world = {10.5, 20.25, 30.125, 11.5, 21.25, 31.125, 6_400_000.25, 1.0, 2.0, 6_400_001.75, 3.0, 4.0};
        float[] local = new float[12];
        Rebase.bounds(world, 0, 6_400_000.0, 1.0, 2.0, local, 0, 2);
        assertArrayEquals(new float[] {-6_399_989.5f, 19.25f, 28.125f, -6_399_988.5f, 20.25f, 29.125f, 0.25f, 0f, 0f, 1.75f, 2f, 2f}, local, 0.5f);
        assertEquals(0.25f, local[6], 0f);
        assertEquals(1.75f, local[9], 0f);
    }

    @Test
    void shiftingRelativeDataFollowsTheOrigin() {
        float[] positions = {1030f, 2f, -3f, 4096f + 5f, 0.5f, 1f};
        Rebase.shiftPositions(positions, 0, 2, new Vec3d(1024.0, 0.0, 0.0));
        assertArrayEquals(new float[] {6f, 2f, -3f, 3077f, 0.5f, 1f}, positions, 0f);
        float[] m = new float[32];
        Mat4f.translation(1030f, 2f, 3f).writeTo(m, 0);
        Mat4f.translation(-100f, 0f, 0f).writeTo(m, 16);
        Rebase.shiftMatrices(m, 0, 2, new Vec3d(1024.0, 0.0, 512.0));
        assertEquals(6f, m[12], 0f);
        assertEquals(2f, m[13], 0f);
        assertEquals(-509f, m[14], 0f);
        assertEquals(-1124f, m[28], 0f);
        assertEquals(1f, m[15], 0f);
        // data near the shift is exact (Sterbenz), whatever its fraction
        float[] near = new float[300];
        for (int i = 0; i < near.length; i++) {
            near[i] = 1024f + (float) rnd.range(-400, 400);
        }
        float[] copy = near.clone();
        Rebase.shiftPositions(near, 0, 100, new Vec3d(1024.0, 1024.0, 1024.0));
        for (int i = 0; i < near.length; i++) {
            assertEquals((double) copy[i] - 1024.0, (double) near[i], 0.0, "exact at " + i);
        }
    }

    @Test
    void aFloatingOriginFollowsTheCameraInSteps() {
        FloatingOrigin origin = new FloatingOrigin(1024.0, 4096.0);
        assertEquals(Vec3d.ZERO, origin.origin());
        assertFalse(origin.update(new Vec3d(4096.0, -4096.0, 100.0)), "on the threshold: no move");
        assertEquals(0L, origin.generation());
        assertTrue(origin.update(new Vec3d(5000.0, 0.0, 0.0)), "beyond it");
        assertEquals(new Vec3d(5120.0, 0.0, 0.0), origin.origin(), "to the nearest multiple of the cell");
        assertEquals(new Vec3d(5120.0, 0.0, 0.0), origin.lastShift());
        assertEquals(1L, origin.generation());
        assertFalse(origin.update(new Vec3d(5000.0, 0.0, 0.0)), "the camera is near the new origin");
        assertEquals(Vec3d.ZERO, origin.lastShift(), "no shift since the last update");
        // a camera hovering around a boundary does not move the origin back and forth
        for (int i = 0; i < 100; i++) {
            assertFalse(origin.update(new Vec3d(5120.0 + 600.0 * Math.sin(i), 0.0, 700.0 * Math.cos(i))));
        }
        assertEquals(1L, origin.generation());
        assertTrue(origin.update(new Vec3d(5120.0, 9_000.0, -9_000.0)));
        assertEquals(new Vec3d(0.0, 9216.0, -9216.0), origin.lastShift(), "only the axes that moved shift");
        // non-finite positions change nothing
        assertFalse(origin.update(new Vec3d(Double.NaN, 0.0, 0.0)));
        assertFalse(origin.update(new Vec3d(Double.POSITIVE_INFINITY, 0.0, 0.0)));
        assertEquals(new Vec3d(5120.0, 9216.0, -9216.0), origin.origin());
    }

    @Test
    void aFloatingOriginKeepsTheFractionOfFarPositions() {
        FloatingOrigin origin = new FloatingOrigin(1024.0, 2048.0);
        origin.update(new Vec3d(6_400_000.0, 0.0, 0.0));
        Vec3d world = new Vec3d(6_400_000.25, 0.125, -3.0);
        Vec3f local = origin.toLocal(world);
        assertEquals((float) (6_400_000.25 - origin.origin().x()), local.x(), 0f);
        assertTrue(Math.abs(local.x()) < 1024f, "close to the origin: " + local.x());
        Vec3d back = origin.toWorld(local);
        assertEquals(world.x(), back.x(), 0.0);
        assertEquals(world.y(), back.y(), 0.0);
        assertEquals(world.z(), back.z(), 0.0);
        // data kept relative follows a shift without a trace
        float[] p = {local.x(), local.y(), local.z()};
        origin.update(new Vec3d(6_400_000.0 + 5000.0, 0.0, 0.0));
        Rebase.shiftPositions(p, 0, 1, origin.lastShift());
        Vec3f recomputed = origin.toLocal(world);
        assertEquals(recomputed.x(), p[0], 1e-3f);
        assertEquals(recomputed.y(), p[1], 0f);
        assertEquals(recomputed.z(), p[2], 0f);
    }

    @Test
    void aFloatingOriginRejectsNonsenseParameters() {
        assertThrows(IllegalArgumentException.class, () -> new FloatingOrigin(0.0, 10.0));
        assertThrows(IllegalArgumentException.class, () -> new FloatingOrigin(-1.0, 10.0));
        assertThrows(IllegalArgumentException.class, () -> new FloatingOrigin(Double.NaN, 10.0));
        assertThrows(IllegalArgumentException.class, () -> new FloatingOrigin(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new FloatingOrigin(10.0, 5.0));
        assertThrows(IllegalArgumentException.class, () -> new FloatingOrigin(10.0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new FloatingOrigin(10.0, Double.POSITIVE_INFINITY));
        new FloatingOrigin(10.0, 10.0);
    }

    @Test
    void relativeToOnTheDoubleTransformTypes() {
        Vec3d origin = new Vec3d(6_400_000.0, 12.0, -500.0);
        for (int i = 0; i < 200; i++) {
            Transformd t = new Transformd(rnd.nextVec3d().add(origin), rnd.nextUnitQuatd(), rnd.nextScaleVec3d());
            Transformf f = t.relativeTo(origin);
            assertEquals(t.translation().relativeTo(origin), f.translation());
            assertEquals(t.rotation().toFloat(), f.rotation());
            assertEquals(t.scale().toFloat(), f.scale());
            RigidTransformd r = new RigidTransformd(t.translation(), t.rotation());
            RigidTransformf rf = r.relativeTo(origin);
            assertEquals(r.translation().relativeTo(origin), rf.translation());
            assertEquals(r.rotation().toFloat(), rf.rotation());
            // the whole chain: a point in the object's frame, in the camera-relative frame, equals the world position minus the origin
            Vec3d p = rnd.nextVec3d();
            Vec3d world = r.transformPosition(p);
            Vec3f viaFloat = rf.transformPosition(p.toFloat());
            assertTrue(viaFloat.approxEquals(world.relativeTo(origin), 1e-4f), "camera-relative rendering of a point");
            Mat4x3d compact = Mat4x3d.translationRotateScale(t.translation(), t.rotation(), t.scale());
            Mat4x3f compactFloat = compact.relativeTo(origin);
            assertEquals((float) (compact.m30() - origin.x()), compactFloat.m30(), 0f);
            assertEquals((float) compact.m11(), compactFloat.m11(), 0f);
            Mat4d full = t.toMat4();
            Mat4f fullFloat = full.relativeTo(origin);
            assertEquals((float) (full.m32() - origin.z()), fullFloat.m32(), 0f);
            assertEquals((float) full.m22(), fullFloat.m22(), 0f);
            FrameTransformd tagged = FrameTransformd.of(Frame.of("a"), Frame.of("b"), t);
            assertEquals(tagged.source(), tagged.relativeTo(origin).source());
            assertEquals(tagged.target(), tagged.relativeTo(origin).target());
            assertEquals(f, tagged.relativeTo(origin).transform());
        }
    }
}
