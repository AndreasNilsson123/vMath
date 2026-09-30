package vmath.bench;

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
import vmath.bulk.Mat4fArray;
import vmath.bulk.QuatArray;
import vmath.bulk.TransformArray;
import vmath.bulk.Vec3fArray;
import vmath.core.Mat4f;

/**
 * The bulk array kernels over {@code count} elements. Divide the score by {@code count} for the cost per element. Run with {@code -prof gc}: all of
 * them should report ~0 B/op.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class BulkBench {

    @Param({"100000"})
    public int count;

    private Vec3fArray points;
    private Vec3fArray moved;
    private QuatArray qa;
    private QuatArray qb;
    private QuatArray qo;
    private TransformArray ta;
    private TransformArray tb;
    private TransformArray to;
    private Mat4fArray mats;
    private Mat4f matrix;

    @Setup
    public void setup() {
        SplittableRandom r = new SplittableRandom(6);
        points = new Vec3fArray(count);
        moved = new Vec3fArray(count);
        qa = new QuatArray(count);
        qb = new QuatArray(count);
        qo = new QuatArray(count);
        ta = new TransformArray(count);
        tb = new TransformArray(count);
        to = new TransformArray(count);
        mats = new Mat4fArray(count);
        for (int i = 0; i < count; i++) {
            points.add((float) r.nextDouble(), (float) r.nextDouble(), (float) r.nextDouble());
            qa.add((float) r.nextDouble(-0.3, 0.3), (float) r.nextDouble(-0.3, 0.3), (float) r.nextDouble(-0.3, 0.3), 1f);
            qb.add((float) r.nextDouble(-0.3, 0.3), (float) r.nextDouble(-0.3, 0.3), (float) r.nextDouble(-0.3, 0.3), 1f);
            ta.add((float) r.nextDouble(), (float) r.nextDouble(), (float) r.nextDouble(), 0f, 0.1f, 0f, 1f, 1f, 1f, 1f);
            tb.add((float) r.nextDouble(), (float) r.nextDouble(), (float) r.nextDouble(), 0.1f, 0f, 0f, 1f, 2f, 2f, 2f);
        }
        qa.normalizeAll();
        qb.normalizeAll();
        ta.toMatrices(mats);
        points.transformPositions(Mat4f.IDENTITY, moved); // a filled array for normalizeAll to work on
        matrix = Mat4f.translation(1f, 2f, 3f).mul(Mat4f.rotationY(0.4f));
    }

    @Benchmark
    public int transformPositions() {
        points.transformPositions(matrix, moved);
        return moved.size();
    }

    @Benchmark
    public int normalizeAll() {
        moved.normalizeAll();
        return moved.size();
    }

    @Benchmark
    public int quatSlerp() {
        QuatArray.slerp(qa, qb, 0.37f, qo);
        return qo.size();
    }

    @Benchmark
    public int quatMultiply() {
        QuatArray.multiply(qa, qb, qo);
        return qo.size();
    }

    @Benchmark
    public int transformsToMatrices() {
        ta.toMatrices(mats);
        return mats.size();
    }

    @Benchmark
    public int transformBlend() {
        TransformArray.blend(ta, tb, 0.37f, to);
        return to.size();
    }
}
