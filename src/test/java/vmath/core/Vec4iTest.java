package vmath.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.IntBuffer;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** {@link Vec4i} against straightforward reference arithmetic. */
class Vec4iTest {

    final SplittableRandom r = new SplittableRandom(Rnd.SEED);

    private int small() {
        return r.nextInt(-1000, 1001);
    }

    private Vec4i v() {
        return new Vec4i(small(), small(), small(), small());
    }

    @Test
    void arithmeticMatchesComponentWiseReference() {
        for (int i = 0; i < 2000; i++) {
            Vec4i a = v(), b = v();
            int s = small();
            assertEquals(new Vec4i(a.x() + b.x(), a.y() + b.y(), a.z() + b.z(), a.w() + b.w()), a.add(b));
            assertEquals(new Vec4i(a.x() + 1, a.y() - 2, a.z() + 3, a.w() - 4), a.add(1, -2, 3, -4));
            assertEquals(new Vec4i(a.x() - b.x(), a.y() - b.y(), a.z() - b.z(), a.w() - b.w()), a.sub(b));
            assertEquals(new Vec4i(a.x() * s, a.y() * s, a.z() * s, a.w() * s), a.mul(s));
            assertEquals(new Vec4i(a.x() * b.x(), a.y() * b.y(), a.z() * b.z(), a.w() * b.w()), a.mul(b));
            assertEquals(Vec4i.ZERO.sub(a), a.negate());
            assertEquals(new Vec4i(Math.abs(a.x()), Math.abs(a.y()), Math.abs(a.z()), Math.abs(a.w())), a.abs());
            assertEquals(new Vec4i(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()), Math.min(a.w(), b.w())), a.min(b));
            assertEquals(new Vec4i(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()), Math.max(a.w(), b.w())), a.max(b));
            long dot = (long) a.x() * b.x() + (long) a.y() * b.y() + (long) a.z() * b.z() + (long) a.w() * b.w();
            assertEquals(dot, a.dot(b));
            assertEquals(a.dot(a), a.lengthSquared());
            assertEquals(a.sub(b).lengthSquared(), a.distanceSquared(b));
            assertEquals(Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z()) + Math.abs(a.w() - b.w()), a.manhattan(b));
            assertEquals(Math.max(Math.max(Math.abs(a.x() - b.x()), Math.abs(a.y() - b.y())), Math.max(Math.abs(a.z() - b.z()), Math.abs(a.w() - b.w()))), a.chebyshev(b));
            assertEquals(Math.min(Math.min(a.x(), a.y()), Math.min(a.z(), a.w())), a.minComponent());
            assertEquals(Math.max(Math.max(a.x(), a.y()), Math.max(a.z(), a.w())), a.maxComponent());
            Vec4i lo = a.min(b), hi = a.max(b);
            assertEquals(a, a.clamp(lo, hi));
            assertEquals(lo, lo.clamp(lo, hi));
            assertEquals(hi, Vec4i.splat(Integer.MAX_VALUE).clamp(lo, hi));
            assertEquals(a.x(), a.get(0));
            assertEquals(a.y(), a.get(1));
            assertEquals(a.z(), a.get(2));
            assertEquals(a.w(), a.get(3));
        }
        assertThrows(IndexOutOfBoundsException.class, () -> Vec4i.ZERO.get(4));
        assertThrows(IndexOutOfBoundsException.class, () -> Vec4i.ZERO.get(-1));
        assertEquals(new Vec4i(5, 5, 5, 5), Vec4i.splat(5));
        assertEquals(Vec4i.ONE.add(Vec4i.UNIT_X).add(Vec4i.UNIT_Y).add(Vec4i.UNIT_Z).add(Vec4i.UNIT_W), Vec4i.splat(2));
    }

    @Test
    void flooredDivisionAndModulus() {
        Vec4i a = new Vec4i(-7, 7, -1, 0);
        assertEquals(new Vec4i(-4, 3, -1, 0), a.floorDiv(2));
        assertEquals(new Vec4i(1, 1, 1, 0), a.floorMod(2));
        for (int i = 0; i < 500; i++) {
            Vec4i p = v();
            int d = r.nextInt(1, 50);
            assertEquals(p, p.floorDiv(d).mul(d).add(p.floorMod(d)));
        }
    }

    @Test
    void conversionsFromFloatVectors() {
        assertEquals(new Vec4i(-2, 1, 0, 3), Vec4i.floor(new Vec4f(-1.5f, 1.9f, 0.0f, 3.2f)));
        assertEquals(new Vec4i(-2, 1, 0, 3), Vec4i.floor(new Vec4d(-1.5, 1.9, 0.0, 3.2)));
        assertEquals(new Vec4i(-1, 2, 0, 4), Vec4i.ceil(new Vec4f(-1.5f, 1.1f, 0.0f, 3.2f)));
        assertEquals(new Vec4i(-1, 2, 0, 3), Vec4i.round(new Vec4f(-1.5f, 1.5f, 0.4f, 3.4f)));
        Vec4i a = new Vec4i(1, -2, 3, -4);
        assertEquals(new Vec4f(1f, -2f, 3f, -4f), a.toFloat());
        assertEquals(new Vec4d(1.0, -2.0, 3.0, -4.0), a.toDouble());
    }

    @Test
    void packingRoundTripsAndRejectsOutOfRange() {
        for (int i = 0; i < 2000; i++) {
            Vec4i a = new Vec4i(r.nextInt(Vec4i.PACK_MIN, Vec4i.PACK_MAX + 1), r.nextInt(Vec4i.PACK_MIN, Vec4i.PACK_MAX + 1), r.nextInt(Vec4i.PACK_MIN, Vec4i.PACK_MAX + 1),
                    r.nextInt(Vec4i.PACK_MIN, Vec4i.PACK_MAX + 1));
            assertEquals(a, Vec4i.unpack(a.pack()));
        }
        for (int e : new int[] {Vec4i.PACK_MIN, Vec4i.PACK_MAX, 0, -1, 1}) {
            Vec4i a = new Vec4i(e, Vec4i.PACK_MAX, e, Vec4i.PACK_MIN);
            assertEquals(a, Vec4i.unpack(a.pack()), "extreme " + e);
        }
        assertThrows(IllegalStateException.class, () -> new Vec4i(Vec4i.PACK_MAX + 1, 0, 0, 0).pack());
        assertThrows(IllegalStateException.class, () -> new Vec4i(0, Vec4i.PACK_MIN - 1, 0, 0).pack());
        assertThrows(IllegalStateException.class, () -> new Vec4i(0, 0, Vec4i.PACK_MAX + 1, 0).pack());
        assertThrows(IllegalStateException.class, () -> new Vec4i(0, 0, 0, Vec4i.PACK_MIN - 1).pack());
        assertTrue(new Vec4i(1, 2, 3, 4).pack() != new Vec4i(4, 3, 2, 1).pack());
    }

    @Test
    void writers() {
        Vec4i a = new Vec4i(1, 2, 3, 4);
        int[] dst = new int[6];
        a.writeTo(dst, 1);
        assertEquals(0, dst[0]);
        assertEquals(1, dst[1]);
        assertEquals(4, dst[4]);
        IntBuffer buf = IntBuffer.allocate(6);
        a.writeTo(buf, 2);
        assertEquals(0, buf.position());
        assertEquals(1, buf.get(2));
        assertEquals(4, buf.get(5));
    }

    @Test
    void overflowWrapsAndDistancesDoNotOverflow() {
        assertEquals(Integer.MIN_VALUE, new Vec4i(Integer.MAX_VALUE, 0, 0, 0).add(1, 0, 0, 0).x());
        Vec4i big = new Vec4i(1 << 30, 1 << 30, 1 << 30, 1 << 30);
        assertEquals(4L * (1L << 60), big.lengthSquared());
        assertEquals(4L * (1L << 60), big.distanceSquared(Vec4i.ZERO));
    }
}
