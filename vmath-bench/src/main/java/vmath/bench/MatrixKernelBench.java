package vmath.bench;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
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
import vmath.bulk.Mat4fArray;
import vmath.bulk.MatrixKernels;
import vmath.bulk.QuatArray;
import vmath.bulk.TransformArray;
import vmath.core.Mat4f;
import vmath.core.Quatf;
import vmath.core.Transformf;
import vmath.core.Vec3f;

/**
 * Batch matrix product (scalar kernel against the one {@link MatrixKernels#best()} picks, which is the Vector API one when {@code vmath-simd} and the incubator
 * module are present), nlerp against slerp, and writing model matrices to an off-heap upload buffer either through a heap {@code Mat4fArray} and a copy or directly.
 * 100 000 elements each.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class MatrixKernelBench {

    private static final int N = 100_000;

    private Mat4fArray a, b, out;
    private QuatArray qa, qb, qout;
    private TransformArray transforms;
    private Mat4fArray heapMatrices;
    private Arena arena;
    private MemorySegment upload;

    @Setup
    public void setup() {
        SplittableRandom r = new SplittableRandom(4);
        a = new Mat4fArray(N);
        b = new Mat4fArray(N);
        out = new Mat4fArray(N);
        qa = new QuatArray(N);
        qb = new QuatArray(N);
        qout = new QuatArray(N);
        transforms = new TransformArray(N);
        heapMatrices = new Mat4fArray(N);
        for (int i = 0; i < N; i++) {
            a.add(Mat4f.translation((float) r.nextDouble(), 1f, 2f).mul(Mat4f.rotationY((float) r.nextDouble())));
            b.add(Mat4f.scaling(1f + (float) r.nextDouble(), 1f, 1f).mul(Mat4f.rotationX((float) r.nextDouble())));
            Quatf q1 = new Quatf((float) r.nextDouble(), (float) r.nextDouble(), (float) r.nextDouble(), (float) r.nextDouble()).normalize();
            Quatf q2 = new Quatf((float) r.nextDouble(), (float) r.nextDouble(), (float) r.nextDouble(), (float) r.nextDouble()).normalize();
            qa.add(q1);
            qb.add(q2);
            transforms.add(new Transformf(new Vec3f((float) r.nextDouble(), (float) r.nextDouble(), (float) r.nextDouble()), q1, Vec3f.ONE));
        }
        arena = Arena.ofConfined();
        upload = arena.allocate((long) N * 64, 16);
    }

    @TearDown
    public void tearDown() {
        arena.close();
    }

    @Benchmark
    public int multiplyScalar() {
        Mat4fArray.multiply(a, b, out, MatrixKernels.scalar());
        return out.size();
    }

    @Benchmark
    public int multiplyBest() {
        Mat4fArray.multiply(a, b, out);
        return out.size();
    }

    @Benchmark
    public int slerp() {
        QuatArray.slerp(qa, qb, 0.3f, qout);
        return qout.size();
    }

    @Benchmark
    public int nlerp() {
        QuatArray.nlerp(qa, qb, 0.3f, qout);
        return qout.size();
    }

    @Benchmark
    public int uploadViaHeapMatrices() {
        transforms.toMatrices(heapMatrices);
        heapMatrices.writeTo(upload, 0, 64, ByteOrder.nativeOrder());
        return heapMatrices.size();
    }

    @Benchmark
    public long uploadDirect() {
        transforms.toMatrices(upload, 0, 64);
        return upload.byteSize();
    }
}
