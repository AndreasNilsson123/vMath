package vmath.bulk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class StridedTest {

    private static float[] ramp(int n) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) {
            a[i] = i + 0.5f;
        }
        return a;
    }

    @Test
    void packedRoundTripInBothByteOrders() {
        try (Arena arena = Arena.ofConfined()) {
            for (ByteOrder order : new ByteOrder[] {ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
                MemorySegment seg = arena.allocate(64);
                float[] src = ramp(12);
                Strided.write(src, 0, 3, 4, seg, 8, 12, order);
                assertEquals(src[4], seg.get(ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(order), 8 + 16));
                float[] back = new float[12];
                Strided.read(seg, 8, 12, order, back, 0, 3, 4);
                assertArrayEquals(src, back);
            }
        }
    }

    @Test
    void stridedWriteLeavesTheOtherAttributesAlone() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(5 * 32);
            // fill with a marker pattern standing in for the other attributes
            for (long o = 0; o < seg.byteSize(); o += 4) {
                seg.set(ValueLayout.JAVA_FLOAT_UNALIGNED, o, -1f);
            }
            float[] src = ramp(15);
            Strided.write(src, 0, 3, 5, seg, 16, 32, ByteOrder.nativeOrder());
            for (int i = 0; i < 5; i++) {
                for (int c = 0; c < 3; c++) {
                    assertEquals(src[i * 3 + c], seg.get(ValueLayout.JAVA_FLOAT_UNALIGNED, 16 + i * 32L + c * 4L));
                }
                assertEquals(-1f, seg.get(ValueLayout.JAVA_FLOAT_UNALIGNED, i * 32L), "bytes before the attribute");
                assertEquals(-1f, seg.get(ValueLayout.JAVA_FLOAT_UNALIGNED, 16 + i * 32L + 12), "bytes after the attribute");
            }
            float[] back = new float[15];
            Strided.read(seg, 16, 32, ByteOrder.nativeOrder(), back, 0, 3, 5);
            assertArrayEquals(src, back);
        }
    }

    @Test
    void byteBufferUsesItsOwnOrder() {
        for (ByteOrder order : new ByteOrder[] {ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            ByteBuffer bb = ByteBuffer.allocate(4 * 24).order(order);
            float[] src = ramp(12);
            Strided.write(src, 0, 3, 4, bb, 4, 24);
            assertEquals(src[3], bb.getFloat(4 + 24));
            float[] back = new float[12];
            Strided.read(bb, 4, 24, back, 0, 3, 4);
            assertArrayEquals(src, back);
            assertEquals(0, bb.position(), "absolute access does not move the position");
        }
    }

    @Test
    void containersWriteAndReadThroughTheSegment() {
        try (Arena arena = Arena.ofConfined()) {
            Vec3fArray v = new Vec3fArray(4);
            v.add(1f, 2f, 3f);
            v.add(4f, 5f, 6f);
            MemorySegment seg = arena.allocate(2 * 32);
            v.writeTo(seg, 4, 32, ByteOrder.nativeOrder());
            assertEquals(5f, seg.get(ValueLayout.JAVA_FLOAT_UNALIGNED, 4 + 32 + 4));
            Vec3fArray w = new Vec3fArray(1);
            w.readFrom(seg, 4, 32, ByteOrder.nativeOrder(), 2);
            assertEquals(2, w.size());
            assertEquals(6f, w.z(1));

            Mat4fArray m = new Mat4fArray(2);
            m.add(vmath.core.Mat4f.translation(1f, 2f, 3f));
            MemorySegment ms = arena.allocate(64);
            m.writeTo(ms, 0, 64, ByteOrder.nativeOrder());
            assertEquals(1f, ms.get(ValueLayout.JAVA_FLOAT_UNALIGNED, 12 * 4L));
            Mat4fArray m2 = new Mat4fArray(1);
            m2.readFrom(ms, 0, 64, ByteOrder.nativeOrder(), 1);
            assertEquals(vmath.core.Mat4f.translation(1f, 2f, 3f), m2.get(0));

            QuatArray q = new QuatArray(1);
            q.add(0f, 0f, 0f, 1f);
            MemorySegment qs = arena.allocate(16);
            q.writeTo(qs, 0, 16, ByteOrder.nativeOrder());
            assertEquals(1f, qs.get(ValueLayout.JAVA_FLOAT_UNALIGNED, 12));

            TransformArray t = new TransformArray(1);
            t.add(vmath.core.Transformf.IDENTITY);
            MemorySegment ts = arena.allocate(40);
            t.writeTo(ts, 0, 40, ByteOrder.nativeOrder());
            TransformArray t2 = new TransformArray(1);
            t2.readFrom(ts, 0, 40, ByteOrder.nativeOrder(), 1);
            assertEquals(vmath.core.Transformf.IDENTITY, t2.get(0));
        }
    }

    @Test
    void rejectsAStrideSmallerThanOneElement() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(64);
            assertThrows(IllegalArgumentException.class, () -> Strided.write(ramp(6), 0, 3, 2, seg, 0, 8, ByteOrder.nativeOrder()));
            assertThrows(IllegalArgumentException.class, () -> Strided.write(ramp(6), 0, 0, 2, seg, 0, 8, ByteOrder.nativeOrder()));
        }
    }
}
