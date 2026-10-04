package vmath.samples.demos.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Tests of the parts of the streaming demo that need no window: the world, its chunks, the pool over
 * the library's allocators and the options.
 *
 * <p><b>Thread safety.</b> Each test builds its own data; the tests may run in parallel.
 */
class StreamingTest {

    @Test
    void chunksHaveUnevenSizesAndContentThatDependsOnlyOnTheCell() {
        int min = Integer.MAX_VALUE, max = 0;
        long total = 0;
        for (int z = 0; z < 60; z++) {
            for (int x = 0; x < 60; x++) {
                int n = StreamPlan.points(x, z);
                assertTrue(n >= StreamPlan.MIN_POINTS && n <= StreamPlan.MAX_POINTS);
                assertEquals(n * StreamPlan.POINT_BYTES, StreamPlan.bytes(x, z));
                min = Math.min(min, n);
                max = Math.max(max, n);
                total += n;
            }
        }
        assertTrue(min < 700 && max > 5000, "sizes " + min + " to " + max);
        assertTrue(total / 3600.0 < 0.5 * (StreamPlan.MIN_POINTS + StreamPlan.MAX_POINTS), "the mean is below the middle: small chunks dominate");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment a = arena.allocate(StreamPlan.MAX_POINTS * 16L, 16), b = arena.allocate(StreamPlan.MAX_POINTS * 16L, 16);
            StreamPlan.fill(12, 34, a, 0);
            StreamPlan.fill(12, 34, b, 0);
            long bytes = StreamPlan.bytes(12, 34);
            assertEquals(-1L, a.asSlice(0, bytes).mismatch(b.asSlice(0, bytes)));
            StreamPlan.fill(13, 34, b, 0);
            long common = Math.min(bytes, StreamPlan.bytes(13, 34));
            assertTrue(a.asSlice(0, common).mismatch(b.asSlice(0, common)) != -1L, "another cell has other content");
            for (int i = 0; i < StreamPlan.points(12, 34); i++) {
                float x = a.get(ValueLayout.JAVA_FLOAT_UNALIGNED, i * 16L), z = a.get(ValueLayout.JAVA_FLOAT_UNALIGNED, i * 16L + 8);
                assertTrue(x >= 12 * StreamPlan.CELL && x < 13 * StreamPlan.CELL && z >= 34 * StreamPlan.CELL && z < 35 * StreamPlan.CELL, "point " + i + " is in its cell");
            }
        }
    }

    @Test
    void theNeighbourhoodIsADiscListedNearestFirst() {
        StreamPlan plan = new StreamPlan(100, 10);
        int n = plan.neighbourhood();
        assertTrue(n > 300 && n < 441, "cells " + n);
        int previous = -1;
        boolean[][] seen = new boolean[21][21];
        for (int i = 0; i < n; i++) {
            int d2 = plan.dx(i) * plan.dx(i) + plan.dz(i) * plan.dz(i);
            assertTrue(d2 >= previous);
            previous = d2;
            assertTrue(!seen[plan.dx(i) + 10][plan.dz(i) + 10]);
            seen[plan.dx(i) + 10][plan.dz(i) + 10] = true;
        }
        assertEquals(0, plan.dx(0));
        assertEquals(0, plan.dz(0));
    }

    @Test
    void theCameraStaysInsideTheWorldAndJumpsAtATeleport() {
        StreamPlan plan = new StreamPlan(160, 14);
        double[] p = new double[2], q = new double[2];
        double biggestStep = 0, lastX = 0, lastZ = 0;
        int jumps = 0;
        for (int f = 0; f < 3600; f++) {
            plan.camera(f / 60.0, 25.0, 3.0, p);
            assertTrue(p[0] >= 15 && p[0] <= 160 - 16 && p[1] >= 15 && p[1] <= 160 - 16, "position " + p[0] + ", " + p[1]);
            double step = f == 0 ? 0 : Math.hypot(p[0] - lastX, p[1] - lastZ);
            if (step > 5.0) {
                jumps++;
            }
            biggestStep = Math.max(biggestStep, step);
            lastX = p[0];
            lastZ = p[1];
        }
        assertTrue(jumps >= 15 && jumps <= 19, "jumps " + jumps + " in a minute with a teleport every three seconds");
        plan.camera(1.0, 25.0, 0.0, p);
        plan.camera(1.0, 25.0, 0.0, q);
        assertEquals(p[0], q[0], 0.0);
        assertTrue(biggestStep > 10.0);
    }

    @Test
    void everyPoolHandsOutNonOverlappingRoomAndKeepsItsBooks() {
        for (StreamPool.Kind kind : StreamPool.Kind.values()) {
            StreamPool pool = new StreamPool(kind, 4_000_000, 96_000, 16);
            Random r = new Random(5);
            List<long[]> live = new ArrayList<>();
            for (int round = 0; round < 4000; round++) {
                if (live.size() > 20 && r.nextInt(3) == 0) {
                    long[] gone = live.remove(r.nextInt(live.size()));
                    pool.free(gone[0], gone[1]);
                } else {
                    long bytes = 16L * (400 + r.nextInt(5600));
                    long at = pool.allocate(bytes);
                    if (at != StreamPool.NONE) {
                        assertTrue(at >= 0 && at + bytes <= pool.capacity() && at % 16 == 0, kind + ": " + at);
                        live.add(new long[] {at, bytes});
                    }
                }
                if (round % 500 == 0) {
                    assertNull(pool.validate(), kind.text());
                }
            }
            long sum = 0;
            for (long[] a : live) {
                sum += a[1];
                for (long[] b : live) {
                    assertTrue(a == b || a[0] + a[1] <= b[0] || b[0] + b[1] <= a[0], kind + ": overlap");
                }
            }
            assertEquals(sum, pool.requestedBytes(), kind.text());
            assertTrue(pool.reservedBytes() >= sum, kind.text());
        }
    }

    @Test
    void aSlabWastesTheDifferenceBetweenABlockAndAChunkAndNeverFragments() {
        StreamPool slab = new StreamPool(StreamPool.Kind.SLAB, 960_000, 96_000, 16);
        assertEquals(10, slab.blocks());
        long a = slab.allocate(6_400);
        assertEquals(96_000, slab.reservedBytes());
        assertEquals(6_400, slab.requestedBytes());
        assertEquals(96_000, slab.largestFree());
        for (int i = 0; i < 9; i++) {
            assertTrue(slab.allocate(10_000) != StreamPool.NONE);
        }
        assertEquals(StreamPool.NONE, slab.allocate(16));
        assertEquals(1, slab.failures());
        slab.free(a, 6_400);
        assertTrue(slab.allocate(96_000) != StreamPool.NONE, "any free block fits any chunk");
    }

    @Test
    void theOptionsHaveDefaultsAndRejectWhatIsWrong() {
        StreamingOptions o = StreamingOptions.parse(List.of());
        assertEquals(3, o.framesInFlight());
        assertEquals(StreamPool.Kind.FIRST_FIT, o.pool());
        StreamingOptions p = StreamingOptions.parse(List.of("--pool", "slab", "--frames-in-flight", "1", "--gpu-load", "0", "--verify"));
        assertEquals(StreamPool.Kind.SLAB, p.pool());
        assertEquals(1, p.framesInFlight());
        assertTrue(p.verify());
        assertThrows(IllegalArgumentException.class, () -> StreamingOptions.parse(List.of("--pool", "bogus")));
        assertThrows(IllegalArgumentException.class, () -> StreamingOptions.parse(List.of("--frames-in-flight", "5")));
        assertThrows(IllegalArgumentException.class, () -> StreamingOptions.parse(List.of("--budget-mb", "32")));
        assertThrows(IllegalArgumentException.class, () -> StreamingOptions.parse(List.of("--nonsense")));
    }
}
