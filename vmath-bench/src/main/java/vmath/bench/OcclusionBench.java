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
import vmath.core.ClipSpace;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;
import vmath.occlusion.DepthBuffer;
import vmath.occlusion.OcclusionStage;
import vmath.spatial.CullContext;
import vmath.spatial.CullPipeline;
import vmath.spatial.CullStages;

/**
 * A city: a grid of tall box "buildings" on the ground with many small objects between and behind them, seen from a camera at
 * street height. Buildings are the occluders; the small objects are what gets tested.
 *
 * <ul>
 *   <li>{@code rasterize}: begin, add every building, build the pyramid: the per-frame cost of the occluders</li>
 *   <li>{@code testAll}: the occlusion stage over every object (pyramid already built)</li>
 *   <li>{@code frustumThenOcclusion}: the realistic pipeline, frustum first and occlusion on the survivors</li>
 * </ul>
 * The setup prints how many objects survive each step, since the value of occlusion culling is the number it removes.
 * Run with {@code -prof gc}: all of it should report ~0 B/op.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class OcclusionBench {

    @Param({"100000"})
    public int count;

    @Param({"256x128", "512x256"})
    public String resolution;

    private BoundsArray objects;
    private BoundsArray buildings;
    private DepthBuffer depth;
    private OcclusionStage stage;
    private CullPipeline frustumThenOcclusion;
    private CullContext ctx;
    private VisibilitySet visible;
    private Mat4f viewProjection;

    @Setup
    public void setup() {
        SplittableRandom r = new SplittableRandom(3);
        buildings = new BoundsArray(400);
        // a 20 x 20 grid of city blocks, 60 units apart, buildings 10..40 wide and 15..90 tall
        for (int gx = -10; gx < 10; gx++) {
            for (int gz = -10; gz < 10; gz++) {
                float cx = gx * 60f + (float) r.nextDouble() * 10f, cz = gz * 60f + (float) r.nextDouble() * 10f;
                float hw = 5f + (float) r.nextDouble() * 15f, hd = 5f + (float) r.nextDouble() * 15f;
                float hh = 15f + (float) r.nextDouble() * 75f;
                buildings.add(cx - hw, 0f, cz - hd, cx + hw, hh * 2f, cz + hd);
            }
        }
        objects = new BoundsArray(count);
        for (int i = 0; i < count; i++) {
            float x = (float) (r.nextDouble() * 2 - 1) * 600f, z = (float) (r.nextDouble() * 2 - 1) * 600f;
            float y = (float) r.nextDouble() * 30f;
            float h = 0.5f + (float) r.nextDouble() * 2f;
            objects.add(x - h, y, z - h, x + h, y + 2 * h, z + h);
        }
        int w = Integer.parseInt(resolution.split("x")[0]), h = Integer.parseInt(resolution.split("x")[1]);
        depth = new DepthBuffer(w, h);
        Vec3f eye = new Vec3f(3f, 2f, 3f);
        Mat4f view = Mat4f.lookAt(eye, new Vec3f(200f, 20f, 120f), Vec3f.UNIT_Y);
        viewProjection = Mat4f.perspective(1.0f, 2f, 0.3f, 800f, ClipSpace.D3D).mul(view);
        Frustumf frustum = Frustumf.fromViewProjection(viewProjection, DepthRange.ZERO_TO_ONE);
        ctx = new CullContext(frustum, eye, 0f);
        visible = new VisibilitySet(count);
        stage = new OcclusionStage(depth);
        frustumThenOcclusion = CullPipeline.of(new CullStages.Frustum(), stage);
        rasterize();

        visible.setAll(count);
        new CullStages.Frustum().cull(ctx, objects, visible);
        int inFrustum = visible.count();
        stage.cull(ctx, objects, visible);
        System.out.println("# " + resolution + ": " + count + " objects, " + inFrustum + " in the frustum, "
                + visible.count() + " left after occlusion (" + String.format("%.1f", 100.0 * (inFrustum - visible.count()) / inFrustum)
                + "% of the visible-in-frustum removed), " + depth.coveredPixels() + " of " + w * h + " pixels covered");
    }

    private void rasterize() {
        depth.begin(viewProjection, 0.3f);
        float[] x0 = buildings.minXs(), y0 = buildings.minYs(), z0 = buildings.minZs();
        float[] x1 = buildings.maxXs(), y1 = buildings.maxYs(), z1 = buildings.maxZs();
        for (int i = 0; i < buildings.size(); i++) {
            depth.addBox(x0[i], y0[i], z0[i], x1[i], y1[i], z1[i]);
        }
        depth.finish();
    }

    @Benchmark
    public int rasterize400Buildings() {
        rasterize();
        return depth.coveredPixels();
    }

    @Benchmark
    public int testAll() {
        visible.setAll(count);
        stage.cull(ctx, objects, visible);
        return visible.count();
    }

    @Benchmark
    public int frustumThenOcclusion() {
        return frustumThenOcclusion.run(ctx, objects, visible);
    }
}
