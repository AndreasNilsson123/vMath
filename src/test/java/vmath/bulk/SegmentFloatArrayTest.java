package vmath.bulk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Mat4f;
import vmath.core.Quatf;
import vmath.core.Transformf;
import vmath.core.Vec3f;
import vmath.core.Vec4f;

class SegmentFloatArrayTest {

    private static final long SEED = Long.getLong("vmath.seed", 23L);

    @Test
    void behavesLikeTheHeapContainerOnAddSetGetAndGrowth() {
        try (SegmentFloatArray a = SegmentFloatArray.ofVec3(1)) {
            Vec3fArray heap = new Vec3fArray(1);
            SplittableRandom r = new SplittableRandom(SEED);
            for (int i = 0; i < 1000; i++) {
                Vec3f v = new Vec3f((float) r.nextDouble(), (float) r.nextDouble(), (float) r.nextDouble());
                assertEquals(i, a.addVec3(v));
                heap.add(v);
            }
            assertEquals(1000, a.size());
            assertTrue(a.capacity() >= 1000);
            for (int i = 0; i < 1000; i++) {
                assertEquals(heap.get(i), a.getVec3(i));
            }
            float[] src = {7f, 8f, 9f, 0f};
            a.set(10, src, 0);
            float[] dst = new float[5];
            a.get(10, dst, 2);
            assertArrayEquals(new float[] {0f, 0f, 7f, 8f, 9f}, dst);
            assertEquals(8f, a.getFloat(10, 1));
            a.setFloat(10, 1, -1f);
            assertEquals(new Vec3f(7f, -1f, 9f), a.getVec3(10));
            assertThrows(IndexOutOfBoundsException.class, () -> a.getVec3(1000));
            assertThrows(IndexOutOfBoundsException.class, () -> a.getFloat(0, 3));
            assertThrows(IllegalStateException.class, () -> a.getVec4(0));
            assertThrows(IllegalArgumentException.class, () -> a.add(new float[2], 0));
        }
    }

    @Test
    void typedAccessorsRoundTrip() {
        try (SegmentFloatArray m = SegmentFloatArray.ofMat4(2); SegmentFloatArray q = SegmentFloatArray.ofVec4(2)) {
            Mat4f mat = Mat4f.translation(1f, 2f, 3f).mul(Mat4f.rotationY(0.5f));
            m.addMat4(mat);
            assertTrue(mat.approxEquals(m.getMat4(0), 0f));
            Quatf quat = new Quatf(0.1f, 0.2f, 0.3f, 0.9f);
            q.addQuat(quat);
            assertEquals(quat, q.getQuat(0));
            q.addVec4(new Vec4f(1f, 2f, 3f, 4f));
            assertEquals(new Vec4f(1f, 2f, 3f, 4f), q.getVec4(1));
        }
    }

    @Test
    void removeSwapAndCompactMirrorTheHeapContainers() {
        try (SegmentFloatArray a = SegmentFloatArray.ofMat4(4)) {
            Mat4fArray heap = new Mat4fArray(4);
            for (int i = 0; i < 70; i++) {
                a.addMat4(Mat4f.translation(i, 0, 0));
                heap.add(Mat4f.translation(i, 0, 0));
            }
            assertEquals(heap.removeSwap(3), a.removeSwap(3));
            assertEquals(heap.removeSwap(68), a.removeSwap(68));
            assertEquals(heap.size(), a.size());
            VisibilitySet keep = new VisibilitySet(70);
            for (int i = 0; i < a.size(); i += 3) {
                keep.set(i);
            }
            assertEquals(heap.compact(keep), a.compact(keep));
            for (int i = 0; i < heap.size(); i++) {
                assertTrue(heap.get(i).approxEquals(a.getMat4(i), 0f), "element " + i);
            }
        }
    }

    @Test
    void bulkCopiesAndTheSegmentKernelAgreeWithTheHeapOnes() {
        SplittableRandom r = new SplittableRandom(SEED + 1);
        int n = 500;
        TransformArray t = new TransformArray(n);
        for (int i = 0; i < n; i++) {
            Quatf q = new Quatf((float) (r.nextDouble() - 0.5), (float) (r.nextDouble() - 0.5), (float) (r.nextDouble() - 0.5), (float) (r.nextDouble() - 0.5)).normalize();
            t.add(new Transformf(new Vec3f((float) r.nextDouble() * 10, (float) r.nextDouble() * 10, (float) r.nextDouble() * 10), q,
                    new Vec3f(0.5f + (float) r.nextDouble(), 0.5f + (float) r.nextDouble(), 0.5f + (float) r.nextDouble())));
        }
        Mat4fArray heap = new Mat4fArray(1);
        t.toMatrices(heap);
        // straight into an off-heap segment, tightly packed and with a stride that leaves per-instance data alone
        try (SegmentFloatArray tight = SegmentFloatArray.ofMat4(n)) {
            t.toMatrices(tight.segment(), 0, 64);
            tight.setSize(n);
            float[] copy = new float[n * 16];
            tight.copyTo(copy);
            assertArrayEquals(java.util.Arrays.copyOf(heap.data(), n * 16), copy);
            // round trip through copyFrom
            try (SegmentFloatArray other = SegmentFloatArray.ofMat4(1)) {
                other.copyFrom(heap.data(), n);
                assertEquals(n, other.size());
                for (int i = 0; i < n; i += 37) {
                    assertTrue(heap.get(i).approxEquals(other.getMat4(i), 0f));
                }
            }
        }
        MemorySegment strided = MemorySegment.ofArray(new byte[n * 80 + 16]);
        for (int i = 0; i < n; i++) {
            strided.set(ValueLayout.JAVA_INT_UNALIGNED, 16 + i * 80L + 64, 0x1234 + i);
        }
        t.toMatrices(strided, 16, 80);
        for (int i = 0; i < n; i += 11) {
            assertEquals(0x1234 + i, strided.get(ValueLayout.JAVA_INT_UNALIGNED, 16 + i * 80L + 64), "per-instance data survives");
            for (int c = 0; c < 16; c++) {
                assertEquals(heap.data()[i * 16 + c], strided.get(ValueLayout.JAVA_FLOAT_UNALIGNED, 16 + i * 80L + c * 4L));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> t.toMatrices(strided, 0, 32));
    }

    @Test
    void closeReleasesTheMemoryAndIsIdempotent() {
        SegmentFloatArray a = SegmentFloatArray.ofVec3(4);
        a.addVec3(new Vec3f(1f, 2f, 3f));
        MemorySegment seg = a.segment();
        a.close();
        a.close();
        assertThrows(IllegalStateException.class, () -> seg.get(ValueLayout.JAVA_FLOAT, 0));
        // growing frees the old segment
        SegmentFloatArray b = SegmentFloatArray.ofVec3(1);
        MemorySegment first = b.segment();
        for (int i = 0; i < 10; i++) {
            b.addVec3(Vec3f.ZERO);
        }
        assertFalse(first.scope().isAlive());
        assertTrue(b.segment().scope().isAlive());
        b.close();
    }
}
