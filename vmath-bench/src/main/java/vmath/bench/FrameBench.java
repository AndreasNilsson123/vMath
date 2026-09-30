package vmath.bench;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
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
import vmath.gl.DrawCommandBuffer;
import vmath.gl.InstanceWriter;
import vmath.spatial.BvhStage;
import vmath.spatial.CullContext;
import vmath.spatial.CullStage;
import vmath.spatial.CullStages;
import vmath.spatial.FrustumKernels;
import vmath.spatial.ParallelFrustumKernel;
import vmath.spatial.StaticBvh;

/**
 * The CPU work of one GPU-driven frame at 1M instances (the scene of {@code CullAndDrawSample}): cull, then write every survivor into the instance buffer
 * and emit one indirect draw command. {@code stage} picks the culling strategy, so the score shows how much of the frame the cull is and what the
 * alternatives buy. Run with {@code -prof gc}: the frame should not allocate.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class FrameBench {

    private static final int COUNT = 1_000_000;
    private static final float WORLD = 500f;

    /** {@code serial}: best single-thread kernel. {@code parallel4}: four chunks. {@code bvh}: static BVH traversal. */
    @Param({"serial", "parallel4", "bvh"})
    public String stage;

    private BoundsArray bounds;
    private VisibilitySet visible;
    private CullContext ctx;
    private CullStage cull;
    private ExecutorService pool;
    private Arena arena;
    private MemorySegment instances;
    private DrawCommandBuffer draw;

    @Setup
    public void setup() {
        SplittableRandom r = new SplittableRandom(7);
        bounds = new BoundsArray(COUNT);
        for (int i = 0; i < COUNT; i++) {
            float cx = (float) (r.nextDouble() * 2 - 1) * WORLD, cy = (float) (r.nextDouble() * 2 - 1) * WORLD;
            float cz = (float) (r.nextDouble() * 2 - 1) * WORLD, h = 0.2f + (float) r.nextDouble() * 2f;
            bounds.add(cx - h, cy - h, cz - h, cx + h, cy + h, cz + h);
        }
        Mat4f view = Mat4f.lookAt(Vec3f.ZERO, new Vec3f(0.3f, 0.1f, -1f), Vec3f.UNIT_Y);
        Mat4f proj = Mat4f.perspective(1.0f, 16f / 9f, 0.1f, WORLD * 1.5f, ClipSpace.VULKAN);
        ctx = new CullContext(Frustumf.fromViewProjection(proj.mul(view), DepthRange.of(ClipSpace.VULKAN)), Vec3f.ZERO, 0f);
        visible = new VisibilitySet(COUNT);
        switch (stage) {
            case "serial" -> cull = new CullStages.Frustum(FrustumKernels.best());
            case "parallel4" -> {
                pool = Executors.newFixedThreadPool(3);
                cull = new CullStages.Frustum(new ParallelFrustumKernel(pool, 4));
            }
            case "bvh" -> cull = new BvhStage(StaticBvh.build(bounds));
            default -> throw new IllegalArgumentException(stage);
        }
        arena = Arena.ofShared();
        instances = arena.allocate(COUNT * InstanceWriter.STRIDE, 16);
        draw = new DrawCommandBuffer(arena.allocate(DrawCommandBuffer.stride(DrawCommandBuffer.Kind.ELEMENTS, false), 16), DrawCommandBuffer.Kind.ELEMENTS, false);
    }

    @TearDown
    public void tearDown() {
        if (pool != null) {
            pool.shutdownNow();
        }
        arena.close();
    }

    @Benchmark
    public int frame() {
        visible.setAll(COUNT);
        cull.cull(ctx, bounds, visible);
        int n = InstanceWriter.writeVisibleTranslations(instances, 0, visible, bounds);
        draw.clear();
        draw.addElements(36, n, 0, 0, 0);
        return n;
    }

    /** The cull alone, to split the frame time. */
    @Benchmark
    public int cullOnly() {
        visible.setAll(COUNT);
        cull.cull(ctx, bounds, visible);
        return visible.count();
    }
}
