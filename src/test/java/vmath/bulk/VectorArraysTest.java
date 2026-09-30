package vmath.bulk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.FloatBuffer;
import org.junit.jupiter.api.Test;
import vmath.core.Mat4f;
import vmath.core.Quatf;
import vmath.core.Rnd;
import vmath.core.Transformf;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;

/** {@link Vec3fArray}, {@link QuatArray} and {@link TransformArray}: the containers and every batch kernel against the value-type classes. */
class VectorArraysTest {

    final Rnd rnd = Rnd.create();
    static final int COUNT = 500;

    private static void closeTo(Vec3f a, Vec3f e, float eps, String what) {
        assertEquals(e.x(), a.x(), eps, what + " x");
        assertEquals(e.y(), a.y(), eps, what + " y");
        assertEquals(e.z(), a.z(), eps, what + " z");
    }

    private static boolean sameRotation(Quatf a, Quatf b, float eps) {
        return Math.abs(a.x() * b.x() + a.y() * b.y() + a.z() * b.z() + a.w() * b.w()) >= 1f - eps;
    }

    // ------------------------------------------------------------ Vec3fArray

    @Test
    void vec3ContainerBasics() {
        Vec3fArray a = new Vec3fArray(1);
        assertEquals(0, a.size());
        for (int i = 0; i < 100; i++) {
            assertEquals(i, a.add(i, 2f * i, 3f * i));
        }
        assertEquals(100, a.size());
        assertTrue(a.capacity() >= 100);
        assertEquals(new Vec3f(7f, 14f, 21f), a.get(7));
        assertEquals(14f, a.y(7));
        a.set(7, new Vec3f(1f, 2f, 3f));
        assertEquals(new Vec3f(1f, 2f, 3f), a.get(7));
        FloatBuffer buf = FloatBuffer.allocate(5 + 300);
        a.writeTo(buf, 5);
        assertEquals(1f, buf.get(5 + 7 * 3));
        assertEquals(99f, buf.get(5 + 99 * 3));
        assertThrows(IndexOutOfBoundsException.class, () -> a.get(100));
        assertThrows(IndexOutOfBoundsException.class, () -> a.x(-1));
        assertThrows(IllegalArgumentException.class, () -> a.setSize(a.capacity() + 1));
        a.clear();
        assertEquals(0, a.size());
        assertTrue(a.bounds().isEmpty());
    }

    @Test
    void vec3TransformsMatchTheMatrixOneElementAtATime() {
        for (int trial = 0; trial < 20; trial++) {
            Mat4f m = rnd.nextTrsMat4f();
            Vec3fArray in = new Vec3fArray(1), out = new Vec3fArray(1);
            Vec3f[] pts = new Vec3f[COUNT];
            for (int i = 0; i < COUNT; i++) {
                pts[i] = rnd.nextVec3f().mul(5f);
                in.add(pts[i]);
            }
            in.transformPositions(m, out);
            assertEquals(COUNT, out.size());
            for (int i = 0; i < COUNT; i++) {
                closeTo(out.get(i), m.transformPosition(pts[i]), 1e-4f, "position " + i);
            }
            in.transformDirections(m, out);
            for (int i = 0; i < COUNT; i++) {
                closeTo(out.get(i), m.transformDirection(pts[i]), 1e-4f, "direction " + i);
            }
            in.transformPositions(m, in); // in place
            for (int i = 0; i < COUNT; i++) {
                closeTo(in.get(i), m.transformPosition(pts[i]), 1e-4f, "in place " + i);
            }
        }
    }

    @Test
    void vec3NormalizeAndBounds() {
        Vec3fArray a = new Vec3fArray(4);
        Vec3f[] v = new Vec3f[COUNT];
        float x0 = Float.MAX_VALUE, y0 = x0, z0 = x0, x1 = -Float.MAX_VALUE, y1 = x1, z1 = x1;
        for (int i = 0; i < COUNT; i++) {
            v[i] = rnd.nextVec3f().mul(9f);
            a.add(v[i]);
            x0 = Math.min(x0, v[i].x());
            y0 = Math.min(y0, v[i].y());
            z0 = Math.min(z0, v[i].z());
            x1 = Math.max(x1, v[i].x());
            y1 = Math.max(y1, v[i].y());
            z1 = Math.max(z1, v[i].z());
        }
        assertEquals(new Aabbf(x0, y0, z0, x1, y1, z1), a.bounds());
        a.add(0f, 0f, 0f);
        a.normalizeAll();
        for (int i = 0; i < COUNT; i++) {
            closeTo(a.get(i), v[i].normalizeOrZero(), 1e-5f, "normalized " + i);
        }
        assertEquals(Vec3f.ZERO, a.get(COUNT), "a zero vector stays zero");
    }

    // ------------------------------------------------------------ QuatArray

    @Test
    void quatContainerBasics() {
        QuatArray a = new QuatArray(1);
        for (int i = 0; i < 50; i++) {
            a.add(rnd.nextUnitQuatf());
        }
        Quatf q = rnd.nextUnitQuatf();
        a.set(3, q);
        assertEquals(q, a.get(3));
        assertEquals(50, a.size());
        assertThrows(IndexOutOfBoundsException.class, () -> a.get(50));
        assertThrows(IllegalArgumentException.class, () -> a.setSize(-1));
        FloatBuffer buf = FloatBuffer.allocate(200);
        a.writeTo(buf, 0);
        assertEquals(q.w(), buf.get(3 * 4 + 3));
    }

    @Test
    void quatKernelsMatchQuatf() {
        for (int trial = 0; trial < 10; trial++) {
            QuatArray a = new QuatArray(1), b = new QuatArray(1), out = new QuatArray(1);
            Quatf[] qa = new Quatf[COUNT], qb = new Quatf[COUNT];
            for (int i = 0; i < COUNT; i++) {
                qa[i] = rnd.nextUnitQuatf();
                qb[i] = rnd.nextUnitQuatf(); // about half have a negative dot with qa: the long way round must not happen
                a.add(qa[i]);
                b.add(qb[i]);
            }
            float t = (float) rnd.range(0, 1);
            QuatArray.slerp(a, b, t, out);
            for (int i = 0; i < COUNT; i++) {
                assertTrue(sameRotation(out.get(i), qa[i].slerp(qb[i], t), 2e-6f), "slerp " + i);
            }
            QuatArray product = new QuatArray(1);
            QuatArray.multiply(a, b, product);
            for (int i = 0; i < COUNT; i++) {
                Quatf e = qa[i].mul(qb[i]);
                Quatf g = product.get(i);
                assertEquals(e.x(), g.x(), 1e-5f);
                assertEquals(e.y(), g.y(), 1e-5f);
                assertEquals(e.z(), g.z(), 1e-5f);
                assertEquals(e.w(), g.w(), 1e-5f);
            }
            QuatArray.slerp(a, b, 0f, out);
            for (int i = 0; i < COUNT; i++) {
                assertTrue(sameRotation(out.get(i), qa[i], 1e-6f), "t=0 gives a");
            }
            QuatArray.slerp(a, b, 1f, out);
            for (int i = 0; i < COUNT; i++) {
                assertTrue(sameRotation(out.get(i), qb[i], 1e-6f), "t=1 gives b");
            }
            QuatArray.slerp(a, b, t, a); // in place
            for (int i = 0; i < COUNT; i++) {
                assertTrue(sameRotation(a.get(i), qa[i].slerp(qb[i], t), 2e-6f), "slerp in place " + i);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> QuatArray.slerp(new QuatArray(2), filled(3), 0.5f, new QuatArray(1)));
        assertThrows(IllegalArgumentException.class, () -> QuatArray.multiply(filled(2), filled(3), new QuatArray(1)));
    }

    private QuatArray filled(int n) {
        QuatArray a = new QuatArray(n);
        for (int i = 0; i < n; i++) {
            a.add(rnd.nextUnitQuatf());
        }
        return a;
    }

    @Test
    void quatNormalizeAndMatrices() {
        QuatArray a = new QuatArray(1);
        Quatf[] raw = new Quatf[COUNT];
        for (int i = 0; i < COUNT; i++) {
            Quatf u = rnd.nextUnitQuatf();
            float s = (float) rnd.range(0.2, 5);
            raw[i] = new Quatf(u.x() * s, u.y() * s, u.z() * s, u.w() * s);
            a.add(raw[i]);
        }
        a.add(0f, 0f, 0f, 0f);
        a.add(Float.NaN, 0f, 0f, 1f);
        a.normalizeAll();
        for (int i = 0; i < COUNT; i++) {
            Quatf e = raw[i].normalize();
            assertEquals(e.x(), a.get(i).x(), 1e-5f);
            assertEquals(e.w(), a.get(i).w(), 1e-5f);
        }
        assertEquals(new Quatf(0f, 0f, 0f, 1f), a.get(COUNT), "a zero quaternion becomes the identity");
        assertEquals(new Quatf(0f, 0f, 0f, 1f), a.get(COUNT + 1), "a non-finite one does too");
        QuatArray units = new QuatArray(1);
        for (int i = 0; i < COUNT; i++) {
            units.add(a.get(i));
        }
        Mat4fArray mats = new Mat4fArray(1);
        units.toMatrices(mats);
        assertEquals(COUNT, mats.size());
        for (int i = 0; i < COUNT; i++) {
            Mat4f e = Mat4f.rotation(units.get(i));
            float[] got = mats.data();
            float[] want = new float[16];
            e.writeTo(want, 0);
            for (int k = 0; k < 16; k++) {
                assertEquals(want[k], got[i * 16 + k], 1e-5f, "matrix " + i + " element " + k);
            }
        }
    }

    // ------------------------------------------------------------ TransformArray

    @Test
    void transformArrayMatchesTransformf() {
        for (int trial = 0; trial < 10; trial++) {
            TransformArray a = new TransformArray(1), b = new TransformArray(1), out = new TransformArray(1);
            Transformf[] ta = new Transformf[COUNT], tb = new Transformf[COUNT];
            for (int i = 0; i < COUNT; i++) {
                ta[i] = new Transformf(rnd.nextVec3f().mul(4f), rnd.nextUnitQuatf(), rnd.nextScaleVec3f());
                tb[i] = new Transformf(rnd.nextVec3f().mul(4f), rnd.nextUnitQuatf(), rnd.nextScaleVec3f());
                a.add(ta[i]);
                b.add(tb[i]);
            }
            assertEquals(ta[7], a.get(7));
            Mat4fArray mats = new Mat4fArray(1);
            a.toMatrices(mats);
            assertEquals(COUNT, mats.size());
            float[] want = new float[16];
            for (int i = 0; i < COUNT; i++) {
                ta[i].toMat4().writeTo(want, 0);
                for (int k = 0; k < 16; k++) {
                    assertEquals(want[k], mats.data()[i * 16 + k], 1e-4f, "matrix " + i + " element " + k);
                }
            }
            float t = (float) rnd.range(0, 1);
            TransformArray.blend(a, b, t, out);
            for (int i = 0; i < COUNT; i++) {
                Transformf e = ta[i].blend(tb[i], t), g = out.get(i);
                closeTo(g.translation(), e.translation(), 1e-5f, "blend translation " + i);
                closeTo(g.scale(), e.scale(), 1e-5f, "blend scale " + i);
                assertTrue(sameRotation(g.rotation(), e.rotation(), 2e-6f), "blend rotation " + i);
            }
            TransformArray.blend(a, b, 0f, a); // in place; t = 0 leaves a unchanged
            for (int i = 0; i < COUNT; i++) {
                closeTo(a.get(i).translation(), ta[i].translation(), 1e-6f, "t=0 translation");
                assertTrue(sameRotation(a.get(i).rotation(), ta[i].rotation(), 1e-6f), "t=0 rotation");
            }
        }
        TransformArray three = new TransformArray(3), two = new TransformArray(2);
        for (int i = 0; i < 3; i++) {
            three.add(Transformf.IDENTITY);
        }
        two.add(Transformf.IDENTITY);
        two.add(Transformf.IDENTITY);
        assertThrows(IllegalArgumentException.class, () -> TransformArray.blend(three, two, 0.5f, new TransformArray(1)));
        assertThrows(IndexOutOfBoundsException.class, () -> three.get(3));
        assertThrows(IllegalArgumentException.class, () -> three.setSize(99));
        assertEquals(10, TransformArray.STRIDE);
    }

    @Test
    void transformArrayStaysInStepWithThePoseLayout() {
        // the ten floats are translation, quaternion, scale: the same as vmath.anim.Pose and TransformHierarchy's local data
        TransformArray a = new TransformArray(1);
        a.add(1f, 2f, 3f, 0f, 0f, 0f, 1f, 4f, 5f, 6f);
        assertArrayEquals(new float[] {1f, 2f, 3f, 0f, 0f, 0f, 1f, 4f, 5f, 6f}, java.util.Arrays.copyOf(a.data(), 10));
        vmath.anim.Pose pose = new vmath.anim.Pose(1);
        System.arraycopy(a.data(), 0, pose.data(), 0, 10);
        Mat4fArray mats = new Mat4fArray(1);
        a.toMatrices(mats);
        vmath.anim.TransformHierarchy h = new vmath.anim.TransformHierarchy(1);
        int n = h.add(-1);
        h.setLocal(n, 1f, 2f, 3f, 0f, 0f, 0f, 1f, 4f, 5f, 6f);
        h.update();
        assertArrayEquals(java.util.Arrays.copyOf(mats.data(), 16), java.util.Arrays.copyOf(h.worldMatrices().data(), 16), 1e-6f,
                "TransformArray.toMatrices gives what the hierarchy computes for a root");
    }
}
