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
import vmath.geo.Aabbf;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;
import vmath.spatial.CullContext;
import vmath.spatial.CullPipeline;
import vmath.spatial.CullStages;
import vmath.spatial.PortalCuller;
import vmath.spatial.PortalGraph;
import vmath.spatial.PortalStage;

/**
 * Portal culling in a building of {@code n x n} rooms (10 m square, a door between neighbours with probability 0.7) holding 200 000 small objects: the traversal, the whole portal
 * stage, a plain frustum stage over the same objects, and the two together.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class PortalBench {

    @Param({"16", "32"})
    public int n;

    private static final int OBJECTS = 200_000;
    private PortalGraph graph;
    private PortalCuller culler;
    private PortalStage stage;
    private CullStages.Frustum frustumStage;
    private CullPipeline both;
    private CullContext ctx;
    private Mat4f vp;
    private Vec3f eye;
    private BoundsArray bounds;
    private VisibilitySet visible;
    private int start;
    private int sector;

    @Setup
    public void setup() {
        SplittableRandom r = new SplittableRandom(11);
        PortalGraph.Builder b = PortalGraph.builder();
        for (int row = 0; row < n; row++) {
            for (int c = 0; c < n; c++) {
                b.addBox(new Aabbf(c * 10, 0, row * 10, c * 10 + 10, 3, row * 10 + 10));
            }
        }
        for (int row = 0; row < n; row++) {
            for (int c = 0; c < n; c++) {
                if (c + 1 < n && r.nextDouble() < 0.7) {
                    float x = (c + 1) * 10, z0 = row * 10 + 3.5f;
                    b.addPortal(row * n + c, row * n + c + 1, new float[] {x, 0, z0, x, 0, z0 + 3, x, 2.5f, z0 + 3, x, 2.5f, z0}, 4, 1, 0, 0);
                }
                if (row + 1 < n && r.nextDouble() < 0.7) {
                    float z = (row + 1) * 10, x0 = c * 10 + 3.5f;
                    b.addPortal(row * n + c, (row + 1) * n + c, new float[] {x0, 0, z, x0 + 3, 0, z, x0 + 3, 2.5f, z, x0, 2.5f, z}, 4, 0, 0, 1);
                }
            }
        }
        graph = b.build();
        bounds = new BoundsArray(OBJECTS);
        for (int i = 0; i < OBJECTS; i++) {
            float x = (float) (r.nextDouble() * n * 10), z = (float) (r.nextDouble() * n * 10), y = (float) (0.2 + r.nextDouble() * 2.4);
            bounds.add(x, y, z, x + 0.4f, y + 0.4f, z + 0.4f);
        }
        graph.assignAll(bounds);
        eye = new Vec3f(n * 5 + 5f, 1.5f, n * 5 + 5f);
        start = graph.locate(eye.x(), eye.y(), eye.z());
        vp = Mat4f.perspective(1.2f, 1.6f, 0.1f, 500f, ClipSpace.D3D).mul(Mat4f.lookAt(eye, new Vec3f(eye.x() + 30, 1.5f, eye.z() + 17), new Vec3f(0, 1, 0)));
        ctx = new CullContext(Frustumf.fromViewProjection(vp, DepthRange.ZERO_TO_ONE), eye, 0f);
        culler = new PortalCuller(graph);
        stage = new PortalStage(graph).setView(vp, DepthRange.ZERO_TO_ONE);
        frustumStage = new CullStages.Frustum();
        both = CullPipeline.of(frustumStage, stage);
        visible = new VisibilitySet(OBJECTS);
    }

    @Benchmark
    public int traverse() {
        return culler.traverse(vp, DepthRange.ZERO_TO_ONE, eye.x(), eye.y(), eye.z(), start);
    }

    @Benchmark
    public int portalStage() {
        visible.setAll(OBJECTS);
        stage.cull(ctx, bounds, visible);
        return visible.count();
    }

    @Benchmark
    public int frustumStageOnly() {
        visible.setAll(OBJECTS);
        frustumStage.cull(ctx, bounds, visible);
        return visible.count();
    }

    @Benchmark
    public int frustumThenPortal() {
        visible.setAll(OBJECTS);
        return both.run(ctx, bounds, visible);
    }

    @Benchmark
    public int moveOneObject() {
        sector = (sector + 7919) % OBJECTS;
        return graph.update(sector, bounds.minX(sector), bounds.minY(sector), bounds.minZ(sector), bounds.maxX(sector), bounds.maxY(sector), bounds.maxZ(sector));
    }

    @Benchmark
    public int locateWithHint() {
        return graph.locate(eye.x(), eye.y(), eye.z(), start);
    }
}
