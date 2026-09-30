package vmath.bench;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
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
import vmath.bulk.BoundsArray;
import vmath.bulk.Vec3fArray;
import vmath.bulk.VisibilitySet;
import vmath.gl.InstanceWriter;

/**
 * Where the time of "write the visible instances into the instance buffer" goes, on a fixed visibility set (about 9.5% of 1M, the scene of
 * {@code FrameBench}). {@code gatherOnly} reads the bounds and does no buffer write; {@code writeOnly} writes without gathering; the others are candidate
 * implementations of the whole step.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class InstanceWriteBench {

    private static final int COUNT = 1_000_000;

    private BoundsArray bounds;
    private Vec3fArray centers;
    private VisibilitySet visible;
    private int[] indices;
    private int n;
    private Arena arena;
    private MemorySegment instances;
    private float[] staging;

    @Setup
    public void setup() {
        SplittableRandom r = new SplittableRandom(7);
        bounds = new BoundsArray(COUNT);
        centers = new Vec3fArray(COUNT);
        visible = new VisibilitySet(COUNT);
        indices = new int[COUNT];
        for (int i = 0; i < COUNT; i++) {
            float cx = (float) (r.nextDouble() * 2 - 1) * 500f, cy = (float) (r.nextDouble() * 2 - 1) * 500f, cz = (float) (r.nextDouble() * 2 - 1) * 500f;
            float h = 0.2f + (float) r.nextDouble() * 2f;
            bounds.add(cx - h, cy - h, cz - h, cx + h, cy + h, cz + h);
            centers.add(cx, cy, cz);
            // visible at random: the real scene's survivors are spatially clustered in the array only by luck, so random is the honest worst case
            if (r.nextDouble() < 0.095) {
                visible.set(i);
                indices[n++] = i;
            }
        }
        arena = Arena.ofShared();
        instances = arena.allocate(COUNT * InstanceWriter.STRIDE, 16);
        staging = new float[COUNT * 16];
    }

    @TearDown
    public void tearDown() {
        arena.close();
    }

    @Benchmark
    public int baseline() {
        int k = 0;
        for (int i = visible.nextSetBit(0); i >= 0; i = visible.nextSetBit(i + 1)) {
            InstanceWriter.writeTranslation(instances, k++, (bounds.minX(i) + bounds.maxX(i)) * 0.5f, (bounds.minY(i) + bounds.maxY(i)) * 0.5f,
                    (bounds.minZ(i) + bounds.maxZ(i)) * 0.5f, i);
        }
        return k;
    }

    /** Reads the six bounds arrays and sums them: the gather without the writes. */
    @Benchmark
    public float gatherOnly() {
        float sum = 0f;
        for (int i = visible.nextSetBit(0); i >= 0; i = visible.nextSetBit(i + 1)) {
            sum += bounds.minX(i) + bounds.maxX(i) + bounds.minY(i) + bounds.maxY(i) + bounds.minZ(i) + bounds.maxZ(i);
        }
        return sum;
    }

    /** Writes constant instances from a precomputed index list: the buffer write without the gather or the bit scan. */
    @Benchmark
    public int writeOnly() {
        for (int k = 0; k < n; k++) {
            InstanceWriter.writeTranslation(instances, k, 1f, 2f, 3f, indices[k]);
        }
        return n;
    }

    /** Only the bit scan. */
    @Benchmark
    public int scanOnly() {
        int k = 0;
        for (int i = visible.nextSetBit(0); i >= 0; i = visible.nextSetBit(i + 1)) {
            k += i;
        }
        return k;
    }

    /** Candidate: one packed centre array (x, y, z together) instead of six bounds arrays. */
    @Benchmark
    public int packedCenters() {
        float[] c = centers.data();
        int k = 0;
        for (int i = visible.nextSetBit(0); i >= 0; i = visible.nextSetBit(i + 1)) {
            InstanceWriter.writeTranslation(instances, k++, c[i * 3], c[i * 3 + 1], c[i * 3 + 2], i);
        }
        return k;
    }

    /** Candidate: fill a heap float[] and copy it to the segment in one bulk operation. */
    @Benchmark
    public int stagedBulkCopy() {
        float[] s = staging;
        int k = 0, o = 0;
        for (int i = visible.nextSetBit(0); i >= 0; i = visible.nextSetBit(i + 1)) {
            s[o] = 1f;
            s[o + 1] = 0f;
            s[o + 2] = 0f;
            s[o + 3] = (bounds.minX(i) + bounds.maxX(i)) * 0.5f;
            s[o + 4] = 0f;
            s[o + 5] = 1f;
            s[o + 6] = 0f;
            s[o + 7] = (bounds.minY(i) + bounds.maxY(i)) * 0.5f;
            s[o + 8] = 0f;
            s[o + 9] = 0f;
            s[o + 10] = 1f;
            s[o + 11] = (bounds.minZ(i) + bounds.maxZ(i)) * 0.5f;
            s[o + 12] = Float.intBitsToFloat(i);
            o += 16;
            k++;
        }
        MemorySegment.copy(s, 0, instances, ValueLayout.JAVA_FLOAT_UNALIGNED, 0, o);
        return k;
    }

    /** Candidate: iterate the set bits word by word with {@code w &= w - 1} instead of calling nextSetBit per element. */
    @Benchmark
    public int wordLoop() {
        long[] words = visible.words();
        int k = 0;
        for (int wi = 0; wi < words.length; wi++) {
            long w = words[wi];
            while (w != 0L) {
                int i = (wi << 6) + Long.numberOfTrailingZeros(w);
                w &= w - 1;
                InstanceWriter.writeTranslation(instances, k++, (bounds.minX(i) + bounds.maxX(i)) * 0.5f, (bounds.minY(i) + bounds.maxY(i)) * 0.5f,
                        (bounds.minZ(i) + bounds.maxZ(i)) * 0.5f, i);
            }
        }
        return k;
    }

    /** Candidate: the word loop over the packed centre array. */
    @Benchmark
    public int wordLoopPacked() {
        long[] words = visible.words();
        float[] c = centers.data();
        int k = 0;
        for (int wi = 0; wi < words.length; wi++) {
            long w = words[wi];
            while (w != 0L) {
                int i = (wi << 6) + Long.numberOfTrailingZeros(w);
                w &= w - 1;
                InstanceWriter.writeTranslation(instances, k++, c[i * 3], c[i * 3 + 1], c[i * 3 + 2], i);
            }
        }
        return k;
    }

    /** The word loop alone (no gather, no write). */
    @Benchmark
    public int wordScanOnly() {
        long[] words = visible.words();
        int k = 0;
        for (int wi = 0; wi < words.length; wi++) {
            long w = words[wi];
            while (w != 0L) {
                k += (wi << 6) + Long.numberOfTrailingZeros(w);
                w &= w - 1;
            }
        }
        return k;
    }
}
