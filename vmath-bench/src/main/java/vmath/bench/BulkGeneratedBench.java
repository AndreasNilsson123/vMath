package vmath.bench;

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
import org.openjdk.jmh.annotations.Warmup;
import vmath.bulk.MatrixKernels;
import vmath.bulk.Vec3fArray;
import vmath.core.Mat4f;
import vmath.core.Mat4fBulk;
import vmath.core.Vec3fBulk;

/**
 * The loops that the generator writes from the {@code @Bulk} methods, in both layouts, against the
 * hand-written scalar kernel for the same operation. 100 000 elements each.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class BulkGeneratedBench {

    private static final int N = 100_000;

    private float[] matrix = new float[16];
    private float[] a = new float[3 * N], b = new float[3 * N], out = new float[3 * N];
    private float[] ax = new float[N], ay = new float[N], az = new float[N];
    private float[] bx = new float[N], by = new float[N], bz = new float[N];
    private float[] ox = new float[N], oy = new float[N], oz = new float[N];
    private Vec3fArray points, moved;
    private Mat4f m;

    @Setup
    public void setup() {
        SplittableRandom r = new SplittableRandom(6);
        m = Mat4f.translation(1f, 2f, 3f).mul(Mat4f.rotationY(0.7f)).mul(Mat4f.scaling(1f, 2f, 3f));
        m.writeTo(matrix, 0);
        points = new Vec3fArray(N);
        moved = new Vec3fArray(N);
        for (int i = 0; i < N; i++) {
            for (int c = 0; c < 3; c++) {
                a[3 * i + c] = (float) r.nextDouble();
                b[3 * i + c] = (float) r.nextDouble();
            }
            ax[i] = a[3 * i];
            ay[i] = a[3 * i + 1];
            az[i] = a[3 * i + 2];
            bx[i] = b[3 * i];
            by[i] = b[3 * i + 1];
            bz[i] = b[3 * i + 2];
            points.add(a[3 * i], a[3 * i + 1], a[3 * i + 2]);
        }
        moved.ensureCapacity(N);
    }

    @Benchmark
    public int positionsHandWrittenScalar() {
        points.transformPositions(m, moved, MatrixKernels.scalar());
        return moved.size();
    }

    @Benchmark
    public float positionsGeneratedInterleaved() {
        Mat4fBulk.transformPositions(matrix, 0, a, 0, out, 0, N);
        return out[0];
    }

    @Benchmark
    public float positionsGeneratedPlanar() {
        Mat4fBulk.transformPositionsPlanar(matrix, 0, ax, ay, az, 0, ox, oy, oz, 0, N);
        return ox[0];
    }

    @Benchmark
    public float addGeneratedInterleaved() {
        Vec3fBulk.add(a, 0, b, 0, out, 0, N);
        return out[0];
    }

    @Benchmark
    public float addGeneratedPlanar() {
        Vec3fBulk.addPlanar(ax, ay, az, 0, bx, by, bz, 0, ox, oy, oz, 0, N);
        return ox[0];
    }

    @Benchmark
    public float crossGeneratedInterleaved() {
        Vec3fBulk.cross(a, 0, b, 0, out, 0, N);
        return out[0];
    }

    @Benchmark
    public float crossGeneratedPlanar() {
        Vec3fBulk.crossPlanar(ax, ay, az, 0, bx, by, bz, 0, ox, oy, oz, 0, N);
        return ox[0];
    }
}
