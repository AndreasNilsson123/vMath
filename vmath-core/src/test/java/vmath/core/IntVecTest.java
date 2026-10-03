package vmath.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.IntBuffer;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** Integer vectors and Morton codes, checked against straightforward reference arithmetic. */
class IntVecTest {

    final SplittableRandom r = new SplittableRandom(Rnd.SEED);

    private int smallInt() {
        return r.nextInt(-1000, 1001);
    }

    private Vec3i v3() {
        return new Vec3i(smallInt(), smallInt(), smallInt());
    }

    private Vec2i v2() {
        return new Vec2i(smallInt(), smallInt());
    }

    @Test
    void vec3iArithmetic() {
        for (int i = 0; i < 2000; i++) {
            Vec3i a = v3(), b = v3();
            int s = smallInt();
            assertEquals(new Vec3i(a.x() + b.x(), a.y() + b.y(), a.z() + b.z()), a.add(b));
            assertEquals(new Vec3i(a.x() + 3, a.y() - 4, a.z() + 5), a.add(3, -4, 5));
            assertEquals(new Vec3i(a.x() - b.x(), a.y() - b.y(), a.z() - b.z()), a.sub(b));
            assertEquals(new Vec3i(a.x() * s, a.y() * s, a.z() * s), a.mul(s));
            assertEquals(new Vec3i(a.x() * b.x(), a.y() * b.y(), a.z() * b.z()), a.mul(b));
            assertEquals(a.negate(), Vec3i.ZERO.sub(a));
            assertEquals(new Vec3i(Math.abs(a.x()), Math.abs(a.y()), Math.abs(a.z())), a.abs());
            assertEquals(new Vec3i(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z())), a.min(b));
            assertEquals(new Vec3i(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z())), a.max(b));
            assertEquals((long) a.x() * b.x() + (long) a.y() * b.y() + (long) a.z() * b.z(), a.dot(b));
            assertEquals(a.dot(a), a.lengthSquared());
            assertEquals(a.sub(b).lengthSquared(), a.distanceSquared(b));
            assertEquals(Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z()), a.manhattan(b));
            assertEquals(Math.max(Math.abs(a.x() - b.x()), Math.max(Math.abs(a.y() - b.y()), Math.abs(a.z() - b.z()))),
                    a.chebyshev(b));
            assertEquals(Math.min(a.x(), Math.min(a.y(), a.z())), a.minComponent());
            assertEquals(Math.max(a.x(), Math.max(a.y(), a.z())), a.maxComponent());
            Vec3i lo = a.min(b), hi = a.max(b);
            assertEquals(a, a.clamp(lo, hi));
            assertEquals(lo, lo.clamp(lo, hi));
            for (int c = 0; c < 3; c++) {
                assertEquals(c == 0 ? a.x() : c == 1 ? a.y() : a.z(), a.get(c));
            }
        }
        assertThrows(IndexOutOfBoundsException.class, () -> Vec3i.ZERO.get(3));
    }

    @Test
    void floorDivAndModUseFlooredSemantics() {
        for (int i = 0; i < 2000; i++) {
            Vec3i a = v3();
            int d = 1 + r.nextInt(64);
            Vec3i q = a.floorDiv(d), m = a.floorMod(d);
            assertEquals(a, q.mul(d).add(m), "a = q*d + m");
            assertTrue(m.minComponent() >= 0 && m.maxComponent() < d, "mod in [0, d): " + m);
        }
        // negative coordinates fall into the cell below, not toward zero
        assertEquals(new Vec3i(-1, 0, -2), new Vec3i(-1, 0, -9).floorDiv(8));
        assertEquals(new Vec2i(-1, 0), new Vec2i(-1, 7).floorDiv(8));
    }

    @Test
    void floorAndRoundFromFloatVectors() {
        for (int i = 0; i < 2000; i++) {
            Vec3f p = new Vec3f((float) r.nextDouble(-50, 50), (float) r.nextDouble(-50, 50), (float) r.nextDouble(-50, 50));
            Vec3i f = Vec3i.floor(p);
            assertTrue(f.x() <= p.x() && p.x() < f.x() + 1 && f.y() <= p.y() && p.y() < f.y() + 1
                    && f.z() <= p.z() && p.z() < f.z() + 1, "floor of " + p);
            Vec3i c = Vec3i.ceil(p);
            assertTrue(c.x() >= p.x() && c.x() - 1 < p.x());
            Vec3i n = Vec3i.round(p);
            assertTrue(Math.abs(n.x() - p.x()) <= 0.5f && Math.abs(n.y() - p.y()) <= 0.5f && Math.abs(n.z() - p.z()) <= 0.5f);
            assertEquals(f, Vec3i.floor(p.toDouble()));
            assertEquals(p.x(), f.toFloat().x() + (p.x() - f.x()), 1e-4f);
        }
        assertEquals(new Vec3i(-1, -1, 0), Vec3i.floor(new Vec3f(-0.5f, -1e-6f, 0f)));
    }

    @Test
    void vec3iPackRoundTripsAndRejectsOutOfRange() {
        for (int i = 0; i < 5000; i++) {
            Vec3i a = new Vec3i(r.nextInt(Vec3i.PACK_MIN, Vec3i.PACK_MAX + 1), r.nextInt(Vec3i.PACK_MIN, Vec3i.PACK_MAX + 1),
                    r.nextInt(Vec3i.PACK_MIN, Vec3i.PACK_MAX + 1));
            assertEquals(a, Vec3i.unpack(a.pack()));
        }
        for (int e : new int[] {Vec3i.PACK_MIN, Vec3i.PACK_MAX, -1, 0, 1}) {
            Vec3i a = new Vec3i(e, e, e);
            assertEquals(a, Vec3i.unpack(a.pack()), "extreme " + e);
        }
        assertThrows(IllegalStateException.class, () -> new Vec3i(Vec3i.PACK_MAX + 1, 0, 0).pack());
        assertThrows(IllegalStateException.class, () -> new Vec3i(0, Vec3i.PACK_MIN - 1, 0).pack());
        // distinct cells give distinct keys
        assertTrue(new Vec3i(1, 2, 3).pack() != new Vec3i(3, 2, 1).pack());
    }

    @Test
    void vec2iBehavesLikeVec3i() {
        for (int i = 0; i < 2000; i++) {
            Vec2i a = v2(), b = v2();
            int s = smallInt();
            assertEquals(new Vec2i(a.x() + b.x(), a.y() + b.y()), a.add(b));
            assertEquals(new Vec2i(a.x() + 1, a.y() + 2), a.add(1, 2));
            assertEquals(new Vec2i(a.x() - b.x(), a.y() - b.y()), a.sub(b));
            assertEquals(new Vec2i(a.x() * s, a.y() * s), a.mul(s));
            assertEquals(new Vec2i(a.x() * b.x(), a.y() * b.y()), a.mul(b));
            assertEquals(a.negate(), Vec2i.ZERO.sub(a));
            assertEquals((long) a.x() * b.x() + (long) a.y() * b.y(), a.dot(b));
            assertEquals(a.sub(b).lengthSquared(), a.distanceSquared(b));
            assertEquals(Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()), a.manhattan(b));
            assertEquals(Math.max(Math.abs(a.x() - b.x()), Math.abs(a.y() - b.y())), a.chebyshev(b));
            assertEquals(a, Vec2i.unpack(a.pack()));
            assertEquals(new Vec2i(Math.min(a.x(), b.x()), Math.min(a.y(), b.y())), a.min(b));
            assertEquals(new Vec2i(Math.max(a.x(), b.x()), Math.max(a.y(), b.y())), a.max(b));
            assertEquals(a, a.clamp(a.min(b), a.max(b)));
            int d = 1 + r.nextInt(30);
            assertEquals(a, a.floorDiv(d).mul(d).add(a.floorMod(d)));
            assertEquals(new Vec2i(Math.abs(a.x()), Math.abs(a.y())), a.abs());
        }
        assertEquals(12L, new Vec2i(3, 4).area());
        assertEquals(0L, new Vec2i(-3, 4).area());
        assertEquals(Long.valueOf(0x7FFFFFFFL) * 0x7FFFFFFFL, new Vec2i(Integer.MAX_VALUE, Integer.MAX_VALUE).area());
        assertEquals(new Vec2i(-1, 2), Vec2i.floor(new Vec2f(-0.25f, 2.75f)));
        assertEquals(new Vec2i(0, 3), Vec2i.round(new Vec2f(0.4f, 2.5f)));
        assertEquals(new Vec2i(Integer.MIN_VALUE, -1), Vec2i.unpack(new Vec2i(Integer.MIN_VALUE, -1).pack()));
        assertThrows(IndexOutOfBoundsException.class, () -> Vec2i.ZERO.get(2));
    }

    @Test
    void conversionsAndWriters() {
        Vec3i a = new Vec3i(1, -2, 3);
        assertEquals(new Vec3f(1f, -2f, 3f), a.toFloat());
        assertEquals(new Vec3d(1.0, -2.0, 3.0), a.toDouble());
        assertEquals(new Vec2f(4f, -5f), new Vec2i(4, -5).toFloat());
        assertEquals(new Vec2d(4.0, -5.0), new Vec2i(4, -5).toDouble());
        int[] arr = new int[6];
        a.writeTo(arr, 2);
        assertEquals(0, arr[0]);
        assertEquals(1, arr[2]);
        assertEquals(-2, arr[3]);
        assertEquals(3, arr[4]);
        IntBuffer buf = IntBuffer.allocate(5);
        a.writeTo(buf, 1);
        assertEquals(0, buf.position(), "writeTo must not move the position");
        assertEquals(3, buf.get(3));
        new Vec2i(7, 8).writeTo(buf, 0);
        assertEquals(8, buf.get(1));
        int[] two = new int[2];
        new Vec2i(9, 10).writeTo(two, 0);
        assertEquals(10, two[1]);
    }

    // ------------------------------------------------------------ Morton

    /** Reference: interleave bit by bit. */
    private static long naive3(int x, int y, int z) {
        long code = 0;
        for (int b = 0; b < 21; b++) {
            code |= ((long) (x >> b) & 1L) << (3 * b);
            code |= ((long) (y >> b) & 1L) << (3 * b + 1);
            code |= ((long) (z >> b) & 1L) << (3 * b + 2);
        }
        return code;
    }

    private static long naive2(int x, int y) {
        long code = 0;
        for (int b = 0; b < 32; b++) {
            code |= ((long) (x >>> b) & 1L) << (2 * b);
            code |= ((long) (y >>> b) & 1L) << (2 * b + 1);
        }
        return code;
    }

    @Test
    void morton3MatchesBitByBitInterleaving() {
        for (int i = 0; i < 5000; i++) {
            int x = r.nextInt(Morton.MAX_3D + 1), y = r.nextInt(Morton.MAX_3D + 1), z = r.nextInt(Morton.MAX_3D + 1);
            long code = Morton.encode3(x, y, z);
            assertEquals(naive3(x, y, z), code);
            assertEquals(new Vec3i(x, y, z), Morton.decode3(code));
            assertTrue(code >= 0, "3D codes fit in 63 bits");
        }
        assertEquals(0L, Morton.encode3(0, 0, 0));
        assertEquals(1L, Morton.encode3(1, 0, 0));
        assertEquals(2L, Morton.encode3(0, 1, 0));
        assertEquals(4L, Morton.encode3(0, 0, 1));
        assertEquals(Long.MAX_VALUE, Morton.encode3(Morton.MAX_3D, Morton.MAX_3D, Morton.MAX_3D));
    }

    @Test
    void morton2MatchesBitByBitInterleaving() {
        for (int i = 0; i < 5000; i++) {
            int x = r.nextInt(), y = r.nextInt();
            long code = Morton.encode2(x, y);
            assertEquals(naive2(x, y), code);
            assertEquals(new Vec2i(x, y), Morton.decode2(code));
        }
        assertEquals(-1L, Morton.encode2(-1, -1));
    }

    @Test
    void mortonOrderKeepsNeighboursClose() {
        // cells sharing a 2x2x2 block are consecutive codes: the locality property Z-order is used for
        for (int i = 0; i < 500; i++) {
            int bx = r.nextInt(1000) * 2, by = r.nextInt(1000) * 2, bz = r.nextInt(1000) * 2;
            long base = Morton.encode3(bx, by, bz);
            for (int k = 0; k < 8; k++) {
                assertEquals(base + k, Morton.encode3(bx + (k & 1), by + ((k >> 1) & 1), bz + ((k >> 2) & 1)));
            }
        }
    }

    @Test
    void quantizeClampsAndEncodesPositions() {
        assertEquals(0, Morton.quantize(-5f, 0f, 10f, 1024));
        assertEquals(1023, Morton.quantize(50f, 0f, 10f, 1024));
        assertEquals(512, Morton.quantize(5f, 0f, 10f, 1024));
        assertEquals(1023, Morton.quantize(10f, 0f, 10f, 1024), "the upper bound lands in the last cell");
        Vec3f lo = new Vec3f(-10f, -10f, -10f), hi = new Vec3f(10f, 10f, 10f);
        long a = Morton.encode3(new Vec3f(-9f, -9f, -9f), lo.x(), lo.y(), lo.z(), hi.x(), hi.y(), hi.z());
        long b = Morton.encode3(new Vec3f(9f, 9f, 9f), lo.x(), lo.y(), lo.z(), hi.x(), hi.y(), hi.z());
        assertTrue(a < b, "codes follow position along the diagonal");
        Vec3i cell = Morton.decode3(Morton.encode3(new Vec3f(0f, 0f, 0f), lo.x(), lo.y(), lo.z(), hi.x(), hi.y(), hi.z()));
        assertEquals(1 << 20, cell.x());
    }
}
