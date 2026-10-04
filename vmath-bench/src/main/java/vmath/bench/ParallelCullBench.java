package vmath.bench;

import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.core.ClipSpace;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;
import vmath.spatial.FrustumKernel;
import vmath.spatial.FrustumKernels;
import vmath.spatial.ParallelFrustumKernel;

/**
 * How {@link ParallelFrustumKernel} scales with the number of chunks (one thread each). {@code parts = 1} is the plain
 * serial kernel for reference. Run with {@code -prof gc}: the driver should not allocate per call.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ParallelCullBench {

    @Param({"1000000"})
    public int count;

    @Param({"1", "2", "4", "8"})
    public int parts;

    private BoundsArray bounds;
    private Frustumf frustum;
    private VisibilitySet visible;
    private FrustumKernel kernel;
    private ExecutorService pool;

    @Setup
    public void setup() {
        SplittableRandom r = new SplittableRandom(7);
        bounds = new BoundsArray(count);
        for (int i = 0; i < count; i++) {
            float cx = (float) (r.nextDouble() * 2 - 1) * 500f;
            float cy = (float) (r.nextDouble() * 2 - 1) * 500f;
            float cz = (float) (r.nextDouble() * 2 - 1) * 500f;
            float h = 0.2f + (float) r.nextDouble() * 2f;
            bounds.add(cx - h, cy - h, cz - h, cx + h, cy + h, cz + h);
        }
        Mat4f view = Mat4f.lookAt(Vec3f.ZERO, new Vec3f(0.3f, 0.1f, -1f), Vec3f.UNIT_Y);
        frustum = Frustumf.fromViewProjection(Mat4f.perspective(1.0f, 16f / 9f, 0.1f, 750f, ClipSpace.D3D).mul(view),
                DepthRange.ZERO_TO_ONE);
        visible = new VisibilitySet(count);
        pool = Executors.newFixedThreadPool(Math.max(1, parts - 1));
        kernel = parts == 1 ? FrustumKernels.best() : new ParallelFrustumKernel(pool, parts);
        System.out.println("# " + kernel.name());
    }

    @TearDown
    public void tearDown() {
        pool.shutdownNow();
    }

    @Benchmark
    public int cull() {
        visible.setAll(count);
        kernel.cull(frustum, bounds, visible);
        return visible.count();
    }
}
