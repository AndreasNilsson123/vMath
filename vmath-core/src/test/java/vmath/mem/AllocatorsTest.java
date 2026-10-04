package vmath.mem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.mem.FreeListAllocator.Strategy;

class AllocatorsTest {

    private static final long SEED = Long.getLong("vmath.seed", 61L);

    // ---------------------------------------------------------------- arena

    @Test
    void arenaAlignsExhaustsAndRewinds() {
        ArenaAllocator a = new ArenaAllocator(100);
        assertEquals(0, a.allocate(10, 1));
        assertEquals(16, a.allocate(4, 16));
        assertEquals(20, a.used());
        long mark = a.mark();
        assertEquals(32, a.allocate(8, 32));
        assertEquals(64, a.allocate(36, 64));
        assertEquals(100, a.used());
        assertEquals(ArenaAllocator.NONE, a.allocate(1, 1));
        assertEquals(100, a.allocate(0, 1), "a zero-size request at the end still fits");
        a.rewind(mark);
        assertEquals(20, a.used());
        assertEquals(20, a.allocate(5, 4));
        a.reset();
        assertEquals(0, a.used());
        assertEquals(100, a.remaining());
        assertEquals(ArenaAllocator.NONE, a.allocate(101, 1));
        assertEquals(ArenaAllocator.NONE, a.allocate(Long.MAX_VALUE, 1));
        assertEquals(0, a.allocate(1, 1));
        assertEquals(ArenaAllocator.NONE, a.allocate(1, 1L << 62), "rounding up to a huge alignment cannot overflow into success");
        assertThrows(IllegalArgumentException.class, () -> a.allocate(1, 3));
        assertThrows(IllegalArgumentException.class, () -> a.allocate(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> a.rewind(5));
        assertThrows(IllegalStateException.class, () -> a.slice(0, 1));
        MemorySegment seg = MemorySegment.ofArray(new byte[64]);
        ArenaAllocator b = new ArenaAllocator(seg);
        assertEquals(64, b.capacity());
        long o = b.allocate(16, 8);
        assertEquals(16, b.slice(o, 16).byteSize());
    }

    // ---------------------------------------------------------------- slab

    @Test
    void slabHandsOutEveryBlockOnceAndReusesFreedOnes() {
        SlabAllocator s = new SlabAllocator(48, 100);
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            long o = s.allocate();
            assertTrue(o >= 0 && o % 48 == 0 && o / 48 < 100);
            assertTrue(seen.add(o), "no block twice");
            assertTrue(s.isAllocated(o));
        }
        assertEquals(SlabAllocator.NONE, s.allocate());
        assertEquals(100, s.allocatedCount());
        s.free(96);
        assertEquals(1, s.freeBlocks());
        assertEquals(96, s.allocate(), "the freed block comes back");
        assertThrows(IllegalArgumentException.class, () -> s.free(5));
        s.free(0);
        assertThrows(IllegalArgumentException.class, () -> s.free(0), "double free");
        assertThrows(IllegalArgumentException.class, () -> s.free(48 * 100));
        s.reset();
        assertEquals(100, s.freeBlocks());
        assertEquals(0, s.allocate(), "the lowest block first after a reset");
        assertEquals(48, s.allocate());
        assertEquals(1, s.blockIndex(48));
        SlabAllocator empty = new SlabAllocator(8, 0);
        assertEquals(SlabAllocator.NONE, empty.allocate());
        assertThrows(IllegalArgumentException.class, () -> new SlabAllocator(8, 4, MemorySegment.ofArray(new byte[31])));
        SlabAllocator backed = new SlabAllocator(8, 4, MemorySegment.ofArray(new byte[32]));
        assertEquals(8, backed.slice(backed.allocate()).byteSize());
    }

    // ---------------------------------------------------------------- free list

    private record Live(long offset, long size) {
    }

    private static void assertNoOverlap(List<Live> live, long capacity) {
        List<Live> sorted = new ArrayList<>(live);
        sorted.sort((a, b) -> Long.compare(a.offset, b.offset));
        long end = 0;
        for (Live l : sorted) {
            assertTrue(l.offset >= end, "overlap at " + l);
            end = l.offset + l.size;
            assertTrue(end <= capacity);
        }
    }

    @Test
    void freeListKeepsItsInvariantsUnderRandomUse() {
        for (Strategy strategy : Strategy.values()) {
            SplittableRandom r = new SplittableRandom(SEED + strategy.ordinal());
            long capacity = 100_000;
            FreeListAllocator a = new FreeListAllocator(capacity, strategy);
            List<Live> live = new ArrayList<>();
            long liveBytes = 0;
            int failures = 0;
            for (int step = 0; step < 30_000; step++) {
                if (live.isEmpty() || r.nextInt(100) < 55) {
                    long size = 1 + r.nextInt(r.nextInt(10) == 0 ? 3000 : 200);
                    long align = 1L << r.nextInt(7);
                    long o = a.allocate(size, align);
                    if (o == FreeListAllocator.NONE) {
                        failures++;
                        assertTrue(a.largestFree() < size + align || a.freeBytes() < size + align, "a failed allocation had no block that could hold it");
                    } else {
                        assertEquals(0, o % align, "aligned");
                        assertEquals(size, a.sizeOf(o));
                        live.add(new Live(o, size));
                        liveBytes += size;
                    }
                } else {
                    Live l = live.remove(r.nextInt(live.size()));
                    assertEquals(l.size, a.free(l.offset));
                    liveBytes -= l.size;
                    assertEquals(-1, a.sizeOf(l.offset));
                }
                assertEquals(liveBytes, a.allocatedBytes());
                if (step % 97 == 0) {
                    assertNull(a.validate(), a.validate());
                    assertNoOverlap(live, capacity);
                }
            }
            assertTrue(failures > 0, "the test must reach a full allocator now and then");
            for (Live l : live) {
                a.free(l.offset);
            }
            assertEquals(1, a.blockCount(), strategy + ": everything merges back into one block");
            assertEquals(capacity, a.largestFree());
            assertNull(a.validate());
        }
    }

    @Test
    void freeListAlignmentPaddingStaysFreeAndFragmentationIsReported() {
        FreeListAllocator a = new FreeListAllocator(1000, Strategy.FIRST_FIT);
        assertEquals(0, a.allocate(10, 1));
        assertEquals(64, a.allocate(100, 64));
        assertEquals(1000 - 164, a.largestFree());
        assertEquals(54 + 1000 - 164, a.freeBytes(), "the 54 padding bytes in front are still free");
        assertEquals(10, a.allocate(54, 1), "the padding is usable by a later request");
        // fragment: free every other block of a run of small allocations
        FreeListAllocator f = new FreeListAllocator(1000, Strategy.BEST_FIT);
        long[] o = new long[10];
        for (int i = 0; i < 10; i++) {
            o[i] = f.allocate(100, 1);
        }
        for (int i = 0; i < 10; i += 2) {
            f.free(o[i]);
        }
        assertEquals(500, f.freeBytes());
        assertEquals(100, f.largestFree());
        assertEquals(FreeListAllocator.NONE, f.allocate(101, 1), "enough bytes in total, no block big enough");
        assertThrows(IllegalArgumentException.class, () -> f.free(5));
        assertThrows(IllegalArgumentException.class, () -> f.free(o[0]), "double free");
        assertThrows(IllegalArgumentException.class, () -> f.allocate(0, 1));
        f.reset();
        assertEquals(1000, f.largestFree());
    }

    @Test
    void bestFitPicksTheSnugBlockWhereFirstFitDoesNot() {
        for (Strategy s : Strategy.values()) {
            FreeListAllocator a = new FreeListAllocator(1000, s);
            long big = a.allocate(300, 1), keep1 = a.allocate(50, 1), small = a.allocate(120, 1), keep2 = a.allocate(50, 1);
            a.free(big);
            a.free(small);
            // free blocks: [0, 300) and [350, 470) and the tail; a request of 100 fits all three
            long o = a.allocate(100, 1);
            assertEquals(s == Strategy.FIRST_FIT ? 0 : 350, o, s.toString());
            assertNotEquals(keep1, keep2);
        }
    }

    // ---------------------------------------------------------------- ring

    @Test
    void ringWrapsSkipsTheTailAndReclaimsRetiredFrames() {
        RingAllocator r = new RingAllocator(100, 4);
        assertEquals(0, r.allocate(60, 1));
        r.endFrame();
        assertEquals(60, r.allocate(30, 1));
        assertEquals(RingAllocator.NONE, r.allocate(20, 1), "nothing retired yet");
        r.endFrame();
        r.retireOldestFrame();
        assertEquals(30, r.used());
        // 10 bytes remain at the end of the range but 20 are needed: wrap to 0, the 10 are skipped
        assertEquals(0, r.allocate(20, 1));
        assertEquals(60, r.used());
        assertEquals(RingAllocator.NONE, r.allocate(50, 1), "only the space up to the oldest data is free");
        assertEquals(20, r.allocate(40, 1));
        assertEquals(RingAllocator.NONE, r.allocate(1, 1));
        r.endFrame();
        r.retireOldestFrame();
        r.retireOldestFrame();
        assertEquals(0, r.used());
        assertEquals(0, r.outstandingFrames());
        assertEquals(0, r.allocate(100, 1), "an empty ring starts over at 0");
        assertThrows(IllegalStateException.class, () -> {
            RingAllocator t = new RingAllocator(10, 1);
            t.endFrame();
            t.endFrame();
        });
        assertThrows(IllegalStateException.class, () -> new RingAllocator(10, 1).retireOldestFrame());
        assertEquals(RingAllocator.NONE, new RingAllocator(10, 1).allocate(11, 1));
    }

    private record RingAlloc(int frame, long offset, long size) {
    }

    @Test
    void ringNeverHandsOutMemoryThatIsStillInUse() {
        SplittableRandom rnd = new SplittableRandom(SEED + 9);
        long capacity = 4096;
        int maxFrames = 5;
        RingAllocator ring = new RingAllocator(capacity, maxFrames);
        List<RingAlloc> live = new ArrayList<>();
        int currentFrame = 0, oldestFrame = 0, ended = 0;
        long granted = 0;
        for (int step = 0; step < 50_000; step++) {
            int op = rnd.nextInt(10);
            if (op < 7) {
                long size = rnd.nextInt(4) == 0 ? rnd.nextInt(900) : rnd.nextInt(60);
                long align = 1L << rnd.nextInt(6);
                long o = ring.allocate(size, align);
                if (o != RingAllocator.NONE) {
                    assertEquals(0, o % align);
                    assertTrue(o >= 0 && o + size <= capacity);
                    live.add(new RingAlloc(currentFrame, o, size));
                    granted += size;
                    assertTrue(ring.used() <= capacity);
                }
            } else if (op < 9) {
                if (ring.outstandingFrames() < maxFrames) {
                    ring.endFrame();
                    ended++;
                    currentFrame++;
                }
            } else if (ring.outstandingFrames() > 0) {
                ring.retireOldestFrame();
                int retired = oldestFrame++;
                live.removeIf(a -> a.frame <= retired);
            }
            if (step % 53 == 0) {
                for (int i = 0; i < live.size(); i++) {
                    for (int j = i + 1; j < live.size(); j++) {
                        RingAlloc x = live.get(i), y = live.get(j);
                        if (x.size > 0 && y.size > 0) {
                            assertTrue(x.offset + x.size <= y.offset || y.offset + y.size <= x.offset, "overlap " + x + " " + y);
                        }
                    }
                }
            }
        }
        assertTrue(granted > 100_000 && ended > 100, "the simulation made progress: granted " + granted + ", frames " + ended);
    }

    // ---------------------------------------------------------------- persistent buffer ring

    /** A fake graphics API: a fence is the number of the frame it was inserted after; the "GPU" has completed all frames up to {@code completed}. */
    private static class FakeGpu implements PersistentBufferRing.FenceOps<Integer> {
        int submitted;
        int completed;
        int inserted;
        int released;
        int waits;

        @Override
        public Integer insert() {
            inserted++;
            return ++submitted;
        }

        @Override
        public boolean isSignaled(Integer fence) {
            return fence <= completed;
        }

        @Override
        public void await(Integer fence) {
            waits++;
            completed = Math.max(completed, fence);
        }

        @Override
        public void release(Integer fence) {
            released++;
        }
    }

    @Test
    void ringRegionsRotateAndAreNeverReusedBeforeTheGpuIsDone() {
        for (int lag = 0; lag <= 4; lag++) {
            FakeGpu gpu = new FakeGpu();
            int frames = 3;
            MemorySegment mapped = MemorySegment.ofArray(new byte[3 * 1024 + 100]);
            PersistentBufferRing<Integer> ring = new PersistentBufferRing<>(mapped, frames, 256, gpu);
            assertEquals(1024, ring.regionSize(), "the region size is rounded down to the alignment");
            int[] lastFrameOfRegion = {-1, -1, -1};
            for (int frame = 0; frame < 60; frame++) {
                long base = ring.beginFrame();
                int region = (int) (base / ring.regionSize());
                assertEquals(frame % frames, region);
                assertEquals(frame, ring.frameIndex());
                if (lastFrameOfRegion[region] >= 0) {
                    // the GPU has completed the frame that last wrote into this region (fence number = frame + 1)
                    assertTrue(gpu.completed >= lastFrameOfRegion[region] + 1, "lag " + lag + ", frame " + frame + ": region reused before the GPU finished");
                }
                long a = ring.allocate(100, 16), b = ring.allocate(10, 256);
                assertEquals(base, a);
                assertEquals(0, b % 256);
                assertTrue(b >= base + 100 && b + 10 <= base + 1024);
                assertEquals(ring.allocate(2000, 1), PersistentBufferRing.NONE);
                lastFrameOfRegion[region] = frame;
                ring.endFrame();
                // the GPU completes work some frames behind the CPU
                gpu.completed = Math.max(gpu.completed, gpu.submitted - lag);
            }
            if (lag <= 2) {
                assertEquals(0, ring.stalls(), "a GPU at most " + lag + " frames behind never makes a 3-frame ring wait");
            } else {
                assertTrue(ring.stalls() > 0, "lag " + lag);
                assertEquals(ring.stalls(), gpu.waits);
            }
            ring.drain();
            assertEquals(gpu.inserted, gpu.released, "every fence is released");
            assertTrue(ring.highWaterMark() >= 256 + 10);
        }
    }

    @Test
    void closingTheRingWaitsForAndReleasesEveryFence() {
        FakeGpu gpu = new FakeGpu();
        MemorySegment mapped = MemorySegment.ofArray(new byte[3 * 1024]);
        try (PersistentBufferRing<Integer> ring = new PersistentBufferRing<>(mapped, 3, 256, gpu)) {
            for (int f = 0; f < 3; f++) {
                ring.beginFrame();
                ring.allocate(100, 16);
                ring.endFrame();
            }
            assertEquals(3, gpu.inserted);
            assertEquals(0, gpu.released, "the GPU has not finished with any region yet");
        }
        assertEquals(3, gpu.released, "close() released every fence");
        assertEquals(3, gpu.waits, "and waited for each one that had not signalled");
    }

    @Test
    void ringStaysOnThePreviousFrameWhenWaitingForAFenceFails() {
        boolean[] fail = {false};
        FakeGpu gpu = new FakeGpu() {
            @Override
            public void await(Integer fence) {
                if (fail[0]) {
                    throw new IllegalStateException("device lost");
                }
                super.await(fence);
            }
        };
        MemorySegment mapped = MemorySegment.ofArray(new byte[1024]);
        PersistentBufferRing<Integer> ring = new PersistentBufferRing<>(mapped, 2, 16, gpu);
        ring.beginFrame();
        ring.endFrame();
        ring.beginFrame();
        ring.endFrame();
        long frame = ring.frameIndex();
        fail[0] = true;
        assertThrows(IllegalStateException.class, ring::beginFrame);
        assertEquals(frame, ring.frameIndex(), "the frame counter did not move");
        assertThrows(IllegalStateException.class, () -> ring.allocate(1, 1), "and the ring is not in a frame");
        assertThrows(IllegalStateException.class, ring::beginFrame, "so the failing wait is repeated, not skipped");
        fail[0] = false;
        assertEquals(0, ring.beginFrame(), "the same region as before the failure is entered");
        assertEquals(frame + 1, ring.frameIndex());
    }

    @Test
    void ringChecksItsUsage() {
        FakeGpu gpu = new FakeGpu();
        MemorySegment mapped = MemorySegment.ofArray(new byte[1024]);
        PersistentBufferRing<Integer> ring = new PersistentBufferRing<>(mapped, 2, 16, gpu);
        assertThrows(IllegalStateException.class, () -> ring.allocate(1, 1));
        assertThrows(IllegalStateException.class, ring::endFrame);
        ring.beginFrame();
        assertThrows(IllegalStateException.class, ring::beginFrame);
        assertEquals(64, ring.slice(ring.allocate(64, 16), 64).byteSize());
        ring.endFrame();
        assertThrows(IllegalArgumentException.class, () -> new PersistentBufferRing<>(MemorySegment.ofArray(new byte[8]), 2, 16, gpu));
        assertThrows(IllegalArgumentException.class, () -> new PersistentBufferRing<>(mapped, 0, 16, gpu));
        assertThrows(IllegalArgumentException.class, () -> new PersistentBufferRing<>(mapped, 2, 12, gpu));
    }
}
