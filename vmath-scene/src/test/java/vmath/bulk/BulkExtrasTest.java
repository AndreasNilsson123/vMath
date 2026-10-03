package vmath.bulk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.ClipSpace;
import vmath.core.Mat4f;
import vmath.core.Quatf;
import vmath.core.Transformf;
import vmath.core.Vec3f;
import vmath.core.Vec4f;
import vmath.geo.Aabbf;

class BulkExtrasTest {

    private static final long SEED = Long.getLong("vmath.seed", 17L);

    private static Mat4f randomMatrix(SplittableRandom r) {
        return new Mat4f(
                (float) (r.nextDouble() * 4 - 2), (float) (r.nextDouble() * 4 - 2), (float) (r.nextDouble() * 4 - 2), (float) (r.nextDouble() * 4 - 2),
                (float) (r.nextDouble() * 4 - 2), (float) (r.nextDouble() * 4 - 2), (float) (r.nextDouble() * 4 - 2), (float) (r.nextDouble() * 4 - 2),
                (float) (r.nextDouble() * 4 - 2), (float) (r.nextDouble() * 4 - 2), (float) (r.nextDouble() * 4 - 2), (float) (r.nextDouble() * 4 - 2),
                (float) (r.nextDouble() * 4 - 2), (float) (r.nextDouble() * 4 - 2), (float) (r.nextDouble() * 4 - 2), (float) (r.nextDouble() * 4 - 2));
    }

    private static Quatf randomQuat(SplittableRandom r) {
        return new Quatf((float) (r.nextDouble() - 0.5), (float) (r.nextDouble() - 0.5), (float) (r.nextDouble() - 0.5), (float) (r.nextDouble() - 0.5)).normalize();
    }

    private static void assertMatClose(Mat4f expected, Mat4f actual, float tol, String what) {
        assertTrue(expected.approxEquals(actual, tol), what + "\nexpected " + expected + "\nactual   " + actual);
    }

    // ---------------------------------------------------------------- Vec4fArray

    @Test
    void vec4ArrayBasicsAndWriters() {
        Vec4fArray a = new Vec4fArray(1);
        for (int i = 0; i < 100; i++) {
            assertEquals(i, a.add(i, i + 0.25f, -i, 1f));
        }
        assertEquals(100, a.size());
        assertEquals(new Vec4f(7f, 7.25f, -7f, 1f), a.get(7));
        assertEquals(-7f, a.z(7));
        assertEquals(1f, a.w(7));
        a.set(7, new Vec4f(1f, 2f, 3f, 4f));
        assertEquals(new Vec4f(1f, 2f, 3f, 4f), a.get(7));
        assertThrows(IndexOutOfBoundsException.class, () -> a.get(100));
        MemorySegment seg = MemorySegment.ofArray(new byte[100 * 16 + 8]);
        a.writeTo(seg, 8, 16, ByteOrder.nativeOrder());
        Vec4fArray b = new Vec4fArray(1);
        b.readFrom(seg, 8, 16, ByteOrder.nativeOrder(), 100);
        for (int i = 0; i < 100; i++) {
            assertEquals(a.get(i), b.get(i));
        }
        a.scaleAll(2f);
        assertEquals(new Vec4f(2f, 4f, 6f, 8f), a.get(7));
    }

    @Test
    void vec4TransformMatchesTheValueTypeAndDivideByWGivesNdc() {
        SplittableRandom r = new SplittableRandom(SEED);
        Vec4fArray in = new Vec4fArray(8), out = new Vec4fArray(8);
        Vec4f[] points = new Vec4f[500];
        for (int i = 0; i < points.length; i++) {
            points[i] = new Vec4f((float) (r.nextDouble() * 10 - 5), (float) (r.nextDouble() * 10 - 5), (float) (r.nextDouble() * 10 - 5), r.nextBoolean() ? 1f : (float) r.nextDouble() * 2);
            in.add(points[i]);
        }
        Mat4f m = randomMatrix(r);
        in.transform(m, out);
        assertEquals(points.length, out.size());
        for (int i = 0; i < points.length; i++) {
            Vec4f e = m.transform(points[i]);
            Vec4f g = out.get(i);
            assertEquals(e.x(), g.x(), 1e-4f);
            assertEquals(e.y(), g.y(), 1e-4f);
            assertEquals(e.z(), g.z(), 1e-4f);
            assertEquals(e.w(), g.w(), 1e-4f);
        }
        // in place
        in.transform(m, in);
        for (int i = 0; i < points.length; i++) {
            assertEquals(out.x(i), in.x(i));
        }
        // clip space to ndc, against Mat4f.transformProject
        Mat4f proj = Mat4f.perspective(1f, 1.5f, 0.1f, 100f, ClipSpace.D3D);
        Vec4fArray world = new Vec4fArray(8), clip = new Vec4fArray(8);
        Vec3fArray ndc = new Vec3fArray(8);
        Vec3f[] pts = new Vec3f[300];
        for (int i = 0; i < pts.length; i++) {
            pts[i] = new Vec3f((float) (r.nextDouble() * 6 - 3), (float) (r.nextDouble() * 6 - 3), (float) -(r.nextDouble() * 50 + 0.5));
            world.add(pts[i].x(), pts[i].y(), pts[i].z(), 1f);
        }
        world.transform(proj, clip);
        clip.divideByW(ndc);
        for (int i = 0; i < pts.length; i++) {
            Vec3f e = proj.transformProject(pts[i]);
            assertEquals(e.x(), ndc.x(i), 1e-4f);
            assertEquals(e.y(), ndc.y(i), 1e-4f);
            assertEquals(e.z(), ndc.z(i), 1e-4f);
        }
    }

    // ---------------------------------------------------------------- compaction

    private static VisibilitySet randomKeep(SplittableRandom r, int n, double p) {
        VisibilitySet keep = new VisibilitySet(Math.max(n, 1));
        for (int i = 0; i < n; i++) {
            if (r.nextDouble() < p) {
                keep.set(i);
            }
        }
        return keep;
    }

    @Test
    void compactKeepsTheMarkedElementsInOrderInEveryContainer() {
        SplittableRandom r = new SplittableRandom(SEED + 1);
        for (int n : new int[] {0, 1, 2, 63, 64, 65, 1000}) {
            for (double p : new double[] {0.0, 0.3, 1.0}) {
                VisibilitySet keep = randomKeep(r, n, p);
                int expected = keep.count();
                Vec3fArray v3 = new Vec3fArray(4);
                Vec4fArray v4 = new Vec4fArray(4);
                QuatArray q = new QuatArray(4);
                Mat4fArray m = new Mat4fArray(4);
                TransformArray t = new TransformArray(4);
                BoundsArray b = new BoundsArray(4);
                for (int i = 0; i < n; i++) {
                    v3.add(i, 2 * i, 3 * i);
                    v4.add(i, 2 * i, 3 * i, 4 * i);
                    q.add(new Quatf(i, 0, 0, 1));
                    m.add(Mat4f.translation(i, 0, 0));
                    t.add(new Transformf(new Vec3f(i, 0, 0), Quatf.IDENTITY, new Vec3f(1, 1, 1)));
                    b.add(i, i, i, i + 1, i + 1, i + 1);
                }
                assertEquals(expected, v3.compact(keep));
                assertEquals(expected, v4.compact(keep));
                assertEquals(expected, q.compact(keep));
                assertEquals(expected, m.compact(keep));
                assertEquals(expected, t.compact(keep));
                assertEquals(expected, b.compact(keep));
                int k = 0;
                for (int i = 0; i < n; i++) {
                    if (keep.get(i)) {
                        assertEquals(new Vec3f(i, 2 * i, 3 * i), v3.get(k));
                        assertEquals(new Vec4f(i, 2 * i, 3 * i, 4 * i), v4.get(k));
                        assertEquals(i, q.get(k).x());
                        assertEquals(i, m.get(k).m30());
                        assertEquals(i, t.get(k).translation().x());
                        assertEquals(new Aabbf(i, i, i, i + 1, i + 1, i + 1), b.get(k));
                        k++;
                    }
                }
                assertEquals(expected, k);
            }
        }
    }

    @Test
    void removeSwapMovesTheLastElementIntoTheGap() {
        Vec3fArray v = new Vec3fArray(4);
        BoundsArray b = new BoundsArray(4);
        Mat4fArray m = new Mat4fArray(4);
        for (int i = 0; i < 5; i++) {
            v.add(i, 0, 0);
            b.add(i, 0, 0, i + 1, 1, 1);
            m.add(Mat4f.translation(i, 0, 0));
        }
        assertEquals(4, v.removeSwap(1));
        assertEquals(4, b.removeSwap(1));
        assertEquals(4, m.removeSwap(1));
        assertEquals(4, v.size());
        assertEquals(4f, v.x(1));
        assertEquals(4f, b.minX(1));
        assertEquals(4f, m.get(1).m30());
        assertEquals(-1, v.removeSwap(3), "removing the last element moves nothing");
        assertEquals(3, v.size());
        assertThrows(IndexOutOfBoundsException.class, () -> v.removeSwap(3));
        // the whole array can be drained
        while (v.size() > 0) {
            v.removeSwap(0);
        }
        assertEquals(0, v.size());
    }

    // ---------------------------------------------------------------- Mat4fArray and QuatArray kernels

    @Test
    void matrixMultiplyMatchesTheValueTypeIncludingAliasing() {
        SplittableRandom r = new SplittableRandom(SEED + 2);
        int n = 300;
        Mat4fArray a = new Mat4fArray(n), b = new Mat4fArray(n), out = new Mat4fArray(1);
        Mat4f[] ma = new Mat4f[n], mb = new Mat4f[n];
        for (int i = 0; i < n; i++) {
            ma[i] = randomMatrix(r);
            mb[i] = randomMatrix(r);
            a.add(ma[i]);
            b.add(mb[i]);
        }
        Mat4fArray.multiply(a, b, out);
        assertEquals(n, out.size());
        for (int i = 0; i < n; i++) {
            assertMatClose(ma[i].mul(mb[i]), out.get(i), 1e-3f, "element " + i);
        }
        // out == a, out == b
        Mat4fArray a2 = new Mat4fArray(n), b2 = new Mat4fArray(n);
        for (int i = 0; i < n; i++) {
            a2.add(ma[i]);
            b2.add(mb[i]);
        }
        Mat4fArray.multiply(a2, b, a2);
        Mat4fArray.multiply(a, b2, b2);
        for (int i = 0; i < n; i++) {
            assertMatClose(ma[i].mul(mb[i]), a2.get(i), 1e-3f, "aliased with a, element " + i);
            assertMatClose(ma[i].mul(mb[i]), b2.get(i), 1e-3f, "aliased with b, element " + i);
        }
        assertThrows(IllegalArgumentException.class, () -> Mat4fArray.multiply(a, new Mat4fArray(1), out));
        // a common matrix on the left, in place and not
        Mat4f parent = randomMatrix(r);
        Mat4fArray world = new Mat4fArray(1);
        a.premultiply(parent, world);
        for (int i = 0; i < n; i++) {
            assertMatClose(parent.mul(ma[i]), world.get(i), 1e-3f, "premultiply " + i);
        }
        a.premultiply(parent, a);
        for (int i = 0; i < n; i++) {
            assertMatClose(parent.mul(ma[i]), a.get(i), 1e-3f, "premultiply in place " + i);
        }
    }

    @Test
    void quaternionNlerpMatchesTheValueTypeAndStaysUnit() {
        SplittableRandom r = new SplittableRandom(SEED + 3);
        int n = 500;
        QuatArray a = new QuatArray(n), b = new QuatArray(n), out = new QuatArray(1);
        Quatf[] qa = new Quatf[n], qb = new Quatf[n];
        for (int i = 0; i < n; i++) {
            qa[i] = randomQuat(r);
            qb[i] = i % 3 == 0 ? new Quatf(-qa[i].x(), -qa[i].y(), -qa[i].z(), -qa[i].w()) : randomQuat(r);
            a.add(qa[i]);
            b.add(qb[i]);
        }
        float t = 0.37f;
        QuatArray.nlerp(a, b, t, out);
        for (int i = 0; i < n; i++) {
            Quatf e = qa[i].nlerp(qb[i], t), g = out.get(i);
            assertEquals(e.x(), g.x(), 1e-5f);
            assertEquals(e.y(), g.y(), 1e-5f);
            assertEquals(e.z(), g.z(), 1e-5f);
            assertEquals(e.w(), g.w(), 1e-5f);
            assertEquals(1f, g.x() * g.x() + g.y() * g.y() + g.z() * g.z() + g.w() * g.w(), 1e-5f);
        }
        // opposite quaternions at t = 0.5 would cancel without the shortest-arc sign; the zero result becomes the identity
        QuatArray p = new QuatArray(1), q = new QuatArray(1), o = new QuatArray(1);
        p.add(new Quatf(0, 0, 0, 1));
        q.add(new Quatf(0, 0, 0, -1));
        QuatArray.nlerp(p, q, 0.5f, o);
        assertEquals(1f, Math.abs(o.get(0).w()), 1e-6f);
        // in place
        QuatArray.nlerp(a, b, t, a);
        assertEquals(out.get(5).x(), a.get(5).x());
    }
}
