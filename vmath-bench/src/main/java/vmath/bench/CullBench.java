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
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;
import vmath.spatial.BvhStage;
import vmath.spatial.CullContext;
import vmath.spatial.CullStages;
import vmath.spatial.FrustumKernels;
import vmath.spatial.StaticBvh;

/**
 * Frustum culling of N boxes three ways. Read the result as objects per second: {@code N / score}.
 * Run with {@code -prof gc} to confirm that the culling kernels allocate nothing per frame.
 *
 * <ul>
 *   <li>{@code naiveObjects}: the obvious version, one {@link Aabbf} and one {@code Frustumf.intersects} per object</li>
 *   <li>{@code flatScalar}: the portable plane-major SoA kernel over {@link BoundsArray}</li>
 *   <li>{@code flatSimd}: the Vector API kernel from {@code vmath-simd} (same as scalar if the module is absent)</li>
 *   <li>{@code bvh}: traversal of a prebuilt {@link StaticBvh}</li>
 * </ul>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class CullBench {

    @Param({"10000", "100000", "1000000"})
    public int count;

    /** Half-width of the cube the objects are scattered in; the camera sees a slice of it. */
    @Param({"500"})
    public float world;

    private BoundsArray bounds;
    private Aabbf[] objects;
    private Frustumf frustum;
    private CullContext ctx;
    private VisibilitySet visible;
    private CullStages.Frustum flatScalar;
    private CullStages.Frustum flatBest;
    private BvhStage bvhStage;

    @Setup
    public void setup() {
        SplittableRandom r = new SplittableRandom(7);
        bounds = new BoundsArray(count);
        objects = new Aabbf[count];
        for (int i = 0; i < count; i++) {
            float cx = (float) (r.nextDouble() * 2 - 1) * world;
            float cy = (float) (r.nextDouble() * 2 - 1) * world;
            float cz = (float) (r.nextDouble() * 2 - 1) * world;
            float h = 0.2f + (float) r.nextDouble() * 2f;
            objects[i] = new Aabbf(cx - h, cy - h, cz - h, cx + h, cy + h, cz + h);
            bounds.add(objects[i]);
        }
        Mat4f view = Mat4f.lookAt(new Vec3f(0f, 0f, 0f), new Vec3f(0.3f, 0.1f, -1f), Vec3f.UNIT_Y);
        Mat4f proj = Mat4f.perspective(1.0f, 16f / 9f, 0.1f, world * 1.5f, true);
        frustum = Frustumf.fromViewProjection(proj.mul(view), DepthRange.ZERO_TO_ONE);
        ctx = new CullContext(frustum, Vec3f.ZERO, 0f);
        visible = new VisibilitySet(count);
        flatScalar = new CullStages.Frustum(FrustumKernels.scalar());
        flatBest = new CullStages.Frustum();
        System.out.println("# kernels available: " + FrustumKernels.available() + ", best: " + FrustumKernels.best().name());
        bvhStage = new BvhStage(StaticBvh.build(bounds));
    }

    @Benchmark
    public int naiveObjects() {
        int n = 0;
        for (Aabbf box : objects) {
            if (frustum.intersects(box)) {
                n++;
            }
        }
        return n;
    }

    @Benchmark
    public int flatScalar() {
        visible.setAll(count);
        flatScalar.cull(ctx, bounds, visible);
        return visible.count();
    }

    @Benchmark
    public int flatSimd() {
        visible.setAll(count);
        flatBest.cull(ctx, bounds, visible);
        return visible.count();
    }

    @Benchmark
    public int bvh() {
        visible.setAll(count);
        bvhStage.cull(ctx, bounds, visible);
        return visible.count();
    }
}
