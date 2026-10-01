package vmath.bench;

import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import vmath.bulk.LocalityOrder;
import vmath.bulk.PrefixSum;
import vmath.bulk.RadixSorter;
import vmath.core.Hilbert;
import vmath.core.Morton;

/**
 * Radix sort against the JDK sorts, for the job it is for: ordering {@code n} items by a float key and getting the permutation (draw sorting). The JDK baseline packs
 * the order-preserving key bits and the index into one {@code long} and calls {@code Arrays.sort(long[])}, which is the fastest way to sort pairs without objects. The setup
 * restores the unsorted data before every call (outside the timing for the baseline only through the copy, which both versions pay equally).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class SortBench {

    @Param({"1000", "100000", "1000000"})
    public int n;

    private float[] keys, work;
    private long[] packed, packedWork, codes, codesWork;
    private int[] order, values;
    private float[] xyz;
    private int[] counts, countsWork;
    private final RadixSorter sorter = new RadixSorter();

    @Setup
    public void setup() {
        SplittableRandom r = new SplittableRandom(9);
        keys = new float[n];
        work = new float[n];
        packed = new long[n];
        packedWork = new long[n];
        codes = new long[n];
        codesWork = new long[n];
        order = new int[n];
        values = new int[n];
        xyz = new float[3 * n];
        counts = new int[n];
        countsWork = new int[n];
        for (int i = 0; i < n; i++) {
            keys[i] = (float) (r.nextDouble() * 1000);
            packed[i] = ((long) RadixSorter.floatKey(keys[i]) << 32) | i;
            codes[i] = r.nextLong() & 0x7FFFFFFFFFFFFFFFL;
            counts[i] = r.nextInt(4);
        }
        for (int i = 0; i < xyz.length; i++) {
            xyz[i] = (float) r.nextDouble();
        }
        sorter.reserve(n);
    }

    @Benchmark
    public int radixOrderFloat() {
        sorter.order(keys, n, order, false);
        return order[0];
    }

    @Benchmark
    public long jdkSortPackedFloat() {
        System.arraycopy(packed, 0, packedWork, 0, n);
        Arrays.sort(packedWork);
        return packedWork[0];
    }

    @Benchmark
    public float jdkSortFloatKeysOnly() {
        System.arraycopy(keys, 0, work, 0, n);
        Arrays.sort(work);
        return work[0];
    }

    @Benchmark
    public float radixSortFloatKeysOnly() {
        System.arraycopy(keys, 0, work, 0, n);
        sorter.sort(work, null, n);
        return work[0];
    }

    @Benchmark
    public long radixSortLongKeysWithIndex() {
        System.arraycopy(codes, 0, codesWork, 0, n);
        for (int i = 0; i < n; i++) {
            values[i] = i;
        }
        sorter.sort(codesWork, values, n);
        return codesWork[0];
    }

    @Benchmark
    public long jdkSortLongKeysOnly() {
        System.arraycopy(codes, 0, codesWork, 0, n);
        Arrays.sort(codesWork);
        return codesWork[0];
    }

    @Benchmark
    public int localityOrderMorton() {
        LocalityOrder.order(xyz, n, LocalityOrder.Curve.MORTON, order, codesWork, sorter);
        return order[0];
    }

    @Benchmark
    public int localityOrderHilbert() {
        LocalityOrder.order(xyz, n, LocalityOrder.Curve.HILBERT, order, codesWork, sorter);
        return order[0];
    }

    @Benchmark
    public long encodeMorton3() {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            sum += Morton.encode3(i & 0x1FFFFF, (i * 7) & 0x1FFFFF, (i * 13) & 0x1FFFFF);
        }
        return sum;
    }

    @Benchmark
    public long encodeHilbert3() {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            sum += Hilbert.encode3(i & 0x1FFFFF, (i * 7) & 0x1FFFFF, (i * 13) & 0x1FFFFF, 21);
        }
        return sum;
    }

    @Benchmark
    public int prefixSumSequential() {
        System.arraycopy(counts, 0, countsWork, 0, n);
        return PrefixSum.exclusive(countsWork, n);
    }

    @Benchmark
    public int prefixSumParallel8() {
        System.arraycopy(counts, 0, countsWork, 0, n);
        return PrefixSum.exclusiveParallel(countsWork, n, 8);
    }
}
