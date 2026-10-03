package vmath.bulk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class DirtyRangesTest {

    private static final long SEED = Long.getLong("vmath.seed", 41L);

    /** The reference: runs of a boolean array, merged over gaps of at most maxGap. */
    private static List<int[]> modelRanges(boolean[] dirty, int maxGap) {
        List<int[]> runs = new ArrayList<>();
        int i = 0;
        while (i < dirty.length) {
            if (!dirty[i]) {
                i++;
                continue;
            }
            int j = i;
            while (j < dirty.length && dirty[j]) {
                j++;
            }
            runs.add(new int[] {i, j});
            i = j;
        }
        List<int[]> merged = new ArrayList<>();
        for (int[] r : runs) {
            if (!merged.isEmpty() && r[0] - merged.get(merged.size() - 1)[1] <= maxGap) {
                merged.get(merged.size() - 1)[1] = r[1];
            } else {
                merged.add(new int[] {r[0], r[1]});
            }
        }
        return merged;
    }

    @Test
    void markingAndQueriesMatchABooleanModel() {
        SplittableRandom r = new SplittableRandom(SEED);
        for (int capacity : new int[] {0, 1, 63, 64, 65, 127, 128, 129, 1000}) {
            DirtyRanges d = new DirtyRanges(capacity);
            boolean[] model = new boolean[capacity];
            for (int step = 0; step < 300; step++) {
                switch (r.nextInt(4)) {
                    case 0 -> {
                        if (capacity > 0) {
                            int i = r.nextInt(capacity);
                            d.mark(i);
                            model[i] = true;
                        }
                    }
                    case 1 -> {
                        int a = r.nextInt(capacity + 1), b = r.nextInt(capacity + 1);
                        int from = Math.min(a, b), to = Math.max(a, b);
                        if (r.nextInt(3) == 0) {
                            to = Math.min(capacity, from + r.nextInt(5));
                        }
                        d.markRange(from, to);
                        for (int i = from; i < to; i++) {
                            model[i] = true;
                        }
                    }
                    case 2 -> {
                        if (r.nextInt(20) == 0) {
                            d.clear();
                            Arrays.fill(model, false);
                        }
                    }
                    default -> {
                        if (r.nextInt(30) == 0) {
                            d.markAll();
                            Arrays.fill(model, true);
                        }
                    }
                }
                int expectedCount = 0;
                for (boolean b : model) {
                    expectedCount += b ? 1 : 0;
                }
                assertEquals(expectedCount, d.count());
                assertEquals(expectedCount == 0, d.isEmpty());
                if (step % 10 == 0) {
                    for (int i = 0; i < capacity; i++) {
                        assertEquals(model[i], d.isDirty(i));
                    }
                    for (int from = 0; from <= capacity; from += 7) {
                        int fd = -1, fc = capacity;
                        for (int i = from; i < capacity; i++) {
                            if (model[i] && fd < 0) {
                                fd = i;
                            }
                            if (!model[i] && fc == capacity) {
                                fc = i;
                            }
                        }
                        assertEquals(fd, d.firstDirty(from), "firstDirty from " + from);
                        assertEquals(fc, d.firstClean(from), "firstClean from " + from);
                    }
                    for (int gap : new int[] {0, 1, 3, 100}) {
                        List<int[]> expected = modelRanges(model, gap);
                        int[] out = new int[2 * (expected.size() + 2)];
                        assertEquals(expected.size(), d.ranges(gap, out));
                        for (int k = 0; k < expected.size(); k++) {
                            assertEquals(expected.get(k)[0], out[2 * k], "gap " + gap + " run " + k);
                            assertEquals(expected.get(k)[1], out[2 * k + 1], "gap " + gap + " run " + k);
                        }
                        List<int[]> visited = new ArrayList<>();
                        d.forEachRange(gap, (from, to) -> visited.add(new int[] {from, to}));
                        assertEquals(expected.size(), visited.size());
                        for (int k = 0; k < expected.size(); k++) {
                            assertArrayEquals(expected.get(k), visited.get(k));
                        }
                    }
                }
            }
        }
    }

    @Test
    void edgesAndErrors() {
        DirtyRanges d = new DirtyRanges(130);
        d.markRange(63, 65);
        assertTrue(d.isDirty(63) && d.isDirty(64) && !d.isDirty(62) && !d.isDirty(65));
        d.clear();
        d.markRange(0, 130);
        assertEquals(130, d.count());
        d.clear();
        d.markRange(64, 128);
        assertEquals(64, d.count());
        d.markRange(5, 5);
        assertEquals(64, d.count(), "an empty range marks nothing");
        assertThrows(IndexOutOfBoundsException.class, () -> d.mark(130));
        assertThrows(IndexOutOfBoundsException.class, () -> d.markRange(3, 131));
        assertThrows(IndexOutOfBoundsException.class, () -> d.markRange(5, 3));
        assertThrows(IllegalArgumentException.class, () -> d.ranges(-1, new int[2]));
        // a result array that is too small still reports how many runs there are
        DirtyRanges e = new DirtyRanges(100);
        e.mark(1);
        e.mark(10);
        e.mark(50);
        int[] small = new int[2];
        assertEquals(3, e.ranges(0, small));
        assertArrayEquals(new int[] {1, 2}, small);
        // growth keeps the marks and adds clean elements
        e.ensureCapacity(1000);
        assertEquals(1000, e.capacity());
        assertTrue(e.isDirty(50) && !e.isDirty(999));
        e.mark(999);
        assertEquals(4, e.count());
    }

    @Test
    void uploadCopiesOnlyTheDirtyRunsAndClears() {
        int n = 200, per = 3;
        float[] src = new float[n * per];
        for (int i = 0; i < src.length; i++) {
            src[i] = i + 1;
        }
        MemorySegment dst = MemorySegment.ofArray(new byte[16 + n * per * 4]);
        for (int i = 0; i < dst.byteSize() / 4; i++) {
            dst.setAtIndex(ValueLayout.JAVA_FLOAT_UNALIGNED, i, -1f);
        }
        DirtyRanges d = new DirtyRanges(n);
        d.markRange(10, 12);
        d.mark(14);
        d.mark(100);
        long bytes = d.uploadFloats(src, per, n, dst, 16, 0);
        assertEquals((2 + 1 + 1) * per * 4L, bytes);
        assertTrue(d.isEmpty());
        for (int i = 0; i < n; i++) {
            boolean uploaded = i == 10 || i == 11 || i == 14 || i == 100;
            for (int c = 0; c < per; c++) {
                float v = dst.get(ValueLayout.JAVA_FLOAT_UNALIGNED, 16 + (i * per + c) * 4L);
                assertEquals(uploaded ? src[i * per + c] : -1f, v, "element " + i);
            }
        }
        // a gap of 2 merges 12..13 into the first run: elements 10..14 are copied as one
        d.markRange(10, 12);
        d.mark(14);
        assertEquals(5 * per * 4L, d.uploadFloats(src, per, n, dst, 16, 2));
        // only elements below count are copied
        d.markRange(190, 200);
        assertEquals(5 * per * 4L, d.uploadFloats(src, per, 195, dst, 16, 0));
        assertThrows(IllegalArgumentException.class, () -> d.uploadFloats(src, per, n + 1, dst, 16, 0));
        // off-heap source
        MemorySegment from = MemorySegment.ofArray(new byte[n * 8]);
        for (int i = 0; i < n * 2; i++) {
            from.setAtIndex(ValueLayout.JAVA_FLOAT_UNALIGNED, i, 1000f + i);
        }
        MemorySegment to = MemorySegment.ofArray(new byte[n * 8]);
        d.mark(3);
        d.mark(4);
        assertEquals(16L, d.uploadSegment(from, 8, n, to, 0, 0));
        assertEquals(1006f, to.getAtIndex(ValueLayout.JAVA_FLOAT_UNALIGNED, 6));
        assertEquals(0f, to.getAtIndex(ValueLayout.JAVA_FLOAT_UNALIGNED, 5));
    }

    @Test
    void everyBufferInTheRingEndsUpEqualToTheCpuArray() {
        // N buffers, one written per frame. Each frame changes some elements; the buffer written at frame f must be equal to the CPU array afterwards.
        SplittableRandom r = new SplittableRandom(SEED + 1);
        int n = 500, per = 2, slots = 3;
        float[] cpu = new float[n * per];
        float[][] gpu = new float[slots][n * per];
        FrameDirtyRanges dirty = new FrameDirtyRanges(n, slots);
        long copied = 0;
        for (int frame = 0; frame < 200; frame++) {
            int changes = r.nextInt(6);
            for (int k = 0; k < changes; k++) {
                int i = r.nextInt(n);
                int len = r.nextInt(4) == 0 ? 1 + r.nextInt(20) : 1;
                for (int j = i; j < Math.min(n, i + len); j++) {
                    cpu[j * per] = frame + 1;
                    cpu[j * per + 1] = -j;
                }
                dirty.markRange(i, Math.min(n, i + len));
            }
            int slot = frame % slots;
            DirtyRanges set = dirty.forSlot(slot);
            MemorySegment seg = MemorySegment.ofArray(gpu[slot]);
            copied += set.uploadFloats(cpu, per, n, seg, 0, 2);
            assertArrayEquals(cpu, gpu[slot], "buffer of slot " + slot + " after frame " + frame);
        }
        assertTrue(copied < 200L * n * per * 4, "far less than uploading everything every frame: " + copied);
        assertEquals(slots, dirty.slots());
        assertThrows(IndexOutOfBoundsException.class, () -> dirty.forSlot(3));
        dirty.markAll();
        assertEquals(n, dirty.forSlot(2).count());
    }

    @Test
    void frameDirtyRangesKeepOneSetPerSlot() {
        FrameDirtyRanges f = new FrameDirtyRanges(100, 3);
        assertEquals(3, f.slots());
        assertEquals(100, f.capacity());
        f.mark(5);
        f.markRange(10, 20);
        for (int slot = 0; slot < 3; slot++) {
            assertTrue(f.forSlot(slot).isDirty(5), "mark reaches slot " + slot);
            assertTrue(f.forSlot(slot).isDirty(10) && f.forSlot(slot).isDirty(19) && !f.forSlot(slot).isDirty(20), "markRange reaches slot " + slot);
        }
        f.forSlot(1).clear();
        assertTrue(f.forSlot(0).isDirty(5) && !f.forSlot(1).isDirty(5), "slots are cleared independently");
        f.ensureCapacity(500);
        assertEquals(500, f.capacity());
        for (int slot = 0; slot < 3; slot++) {
            assertEquals(500, f.forSlot(slot).capacity(), "ensureCapacity grows slot " + slot);
        }
        assertThrows(IndexOutOfBoundsException.class, () -> f.forSlot(3));
        assertThrows(IndexOutOfBoundsException.class, () -> f.forSlot(-1));
        assertThrows(IllegalArgumentException.class, () -> new FrameDirtyRanges(10, 0));
        assertEquals(1, new FrameDirtyRanges(10, 1).slots());
    }
}
