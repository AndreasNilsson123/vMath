package vmath.bench;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import vmath.bulk.DirtyRanges;
import vmath.bulk.HandleRegistry;
import vmath.mem.ArenaAllocator;
import vmath.mem.FreeListAllocator;
import vmath.mem.RingAllocator;
import vmath.mem.SlabAllocator;

/**
 * The allocators, the handle registry and the dirty-range upload. Every benchmark does 1 000 operations per call, so divide the score by 1 000 for the cost of one.
 * The upload benchmarks compare writing a whole 100 000-element float buffer with writing only the dirty part (1% of the elements changed, in runs).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class MemBench {

    private ArenaAllocator arena;
    private SlabAllocator slab;
    private FreeListAllocator freeList;
    private RingAllocator ring;
    private HandleRegistry registry;
    private final long[] handles = new long[1000];
    private final long[] offsets = new long[1000];
    private final int[] order = new int[1000];

    private static final int N = 100_000;
    private float[] data;
    private DirtyRanges dirty;
    private Arena nativeArena;
    private MemorySegment mapped;

    @Setup
    public void setup() {
        arena = new ArenaAllocator(1 << 24);
        slab = new SlabAllocator(64, 1000);
        freeList = new FreeListAllocator(1 << 24, FreeListAllocator.Strategy.BEST_FIT);
        ring = new RingAllocator(1 << 20, 3);
        registry = new HandleRegistry(1000);
        SplittableRandom r = new SplittableRandom(5);
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        for (int i = order.length - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = order[i];
            order[i] = order[j];
            order[j] = t;
        }
        data = new float[N * 4];
        dirty = new DirtyRanges(N);
        nativeArena = Arena.ofConfined();
        mapped = nativeArena.allocate((long) N * 16, 16);
    }

    @TearDown
    public void tearDown() {
        nativeArena.close();
    }

    @Benchmark
    public long arenaAllocate1000() {
        arena.reset();
        long sum = 0;
        for (int i = 0; i < 1000; i++) {
            sum += arena.allocate(64, 16);
        }
        return sum;
    }

    @Benchmark
    public long slabAllocateFree1000() {
        long sum = 0;
        for (int i = 0; i < 1000; i++) {
            offsets[i] = slab.allocate();
            sum += offsets[i];
        }
        for (int i = 0; i < 1000; i++) {
            slab.free(offsets[order[i]]);
        }
        return sum;
    }

    @Benchmark
    public long freeListAllocateFree1000() {
        long sum = 0;
        for (int i = 0; i < 1000; i++) {
            offsets[i] = freeList.allocate(64 + (i & 63), 16);
            sum += offsets[i];
        }
        for (int i = 0; i < 1000; i++) {
            freeList.free(offsets[order[i]]);
        }
        return sum;
    }

    @Benchmark
    public long ringAllocate1000() {
        long sum = 0;
        for (int f = 0; f < 10; f++) {
            for (int i = 0; i < 100; i++) {
                sum += ring.allocate(256, 16);
            }
            ring.endFrame();
            if (ring.outstandingFrames() == 3) {
                ring.retireOldestFrame();
            }
        }
        return sum;
    }

    @Benchmark
    public long handleCreateDestroy1000() {
        for (int i = 0; i < 1000; i++) {
            handles[i] = registry.create();
        }
        long sum = 0;
        for (int i = 0; i < 1000; i++) {
            sum += registry.denseIndex(handles[order[i]]);
        }
        for (int i = 0; i < 1000; i++) {
            registry.destroy(handles[order[i]]);
        }
        return sum;
    }

    /** The baseline: copy the whole buffer. */
    @Benchmark
    public long uploadEverything() {
        MemorySegment.copy(data, 0, mapped, java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED, 0, data.length);
        return mapped.byteSize();
    }

    /** 1% of the elements dirty, in runs of 10: mark them and upload only those (with a gap of 8 elements merged). */
    @Benchmark
    public long uploadDirtyOnePercent() {
        for (int i = 0; i < N; i += 1000) {
            dirty.markRange(i, i + 10);
        }
        return dirty.uploadFloats(data, 4, N, mapped, 0, 8);
    }

    /** 10% of the elements dirty, in runs of 100. */
    @Benchmark
    public long uploadDirtyTenPercent() {
        for (int i = 0; i < N; i += 1000) {
            dirty.markRange(i, i + 100);
        }
        return dirty.uploadFloats(data, 4, N, mapped, 0, 8);
    }
}
