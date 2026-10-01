package vmath;

import static vmath.Alloc.assertNoAllocation;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.anim.AnimationClip;
import vmath.anim.ClipSampler;
import vmath.anim.Pose;
import vmath.anim.Skeleton;
import vmath.anim.Skinning;
import vmath.anim.TransformHierarchy;
import vmath.bulk.BoundsArray;
import vmath.bulk.IntList;
import vmath.bulk.Mat4fArray;
import vmath.bulk.VisibilitySet;
import vmath.camera.Cameraf;
import vmath.camera.Cascades;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;
import vmath.geo.Rayf;
import vmath.geo.Spheref;
import vmath.occlusion.DepthBuffer;
import vmath.occlusion.OcclusionStage;
import vmath.spatial.BvhQuery;
import vmath.spatial.CascadeCasters;
import vmath.spatial.ConeCull;
import vmath.spatial.CullContext;
import vmath.spatial.CullPipeline;
import vmath.spatial.CullStages;
import vmath.spatial.DynamicAabbTree;
import vmath.spatial.FrustumKernels;
import vmath.spatial.LightCull;
import vmath.spatial.LodSelector;
import vmath.spatial.LooseOctree;
import vmath.spatial.Neighbors;
import vmath.spatial.StaticBvh;
import vmath.spatial.UniformGrid;

/**
 * The library promises that its hot paths allocate nothing once set up (docs/PERFORMANCE.md). This test holds it to that: each test method runs one
 * path in a loop and fails if it allocates, naming the path.
 *
 * <p><b>Deliberately not covered:</b>
 * <ul>
 *   <li>APIs that return value records ({@code Vec3f}, {@code Mat4f}, {@code Aabbf}, ...): they allocate unless the JIT inlines and scalar-replaces
 *       them, which depends on the caller, so it is measured in {@code CoreBench} rather than enforced here (and under Valhalla they do not allocate at all).</li>
 *   <li>Offline tools that work on a whole mesh at load time ({@code MeshTools}, {@code MeshOptimizer}), which allocate working arrays by design.</li>
 *   <li>The executor hand-off in {@code ParallelFrustumKernel}: the driver allocates nothing, the executor may.</li>
 * </ul>
 *
 * <p>Each case warms up first so that the JIT has compiled the code, then allows a quarter of a byte per call on average.
 */
class AllocationContractTest {

    private static final int N = 4000;
    private static final int WARM = 30_000;
    private static final int CALLS = 60_000;
    private static final int WARM_BIG = 3_000;
    private static final int CALLS_BIG = 6_000;

    private static BoundsArray scene(int n, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        BoundsArray b = new BoundsArray(n);
        for (int i = 0; i < n; i++) {
            float cx = (float) (r.nextDouble() * 2 - 1) * 100f, cy = (float) (r.nextDouble() * 2 - 1) * 100f, cz = (float) (r.nextDouble() * 2 - 1) * 100f;
            float h = 0.2f + (float) r.nextDouble();
            b.add(cx - h, cy - h, cz - h, cx + h, cy + h, cz + h);
        }
        return b;
    }

    private static Mat4f viewProjection() {
        Mat4f view = Mat4f.lookAt(Vec3f.ZERO, new Vec3f(0.3f, 0.1f, -1f), Vec3f.UNIT_Y);
        return Mat4f.perspective(1.0f, 16f / 9f, 0.3f, 150f, true).mul(view);
    }

    private static Frustumf frustum() {
        return Frustumf.fromViewProjection(viewProjection(), DepthRange.ZERO_TO_ONE);
    }

    // ------------------------------------------------------------ culling

    @Test
    void scalarFrustumKernel() {
        BoundsArray b = scene(N, 1);
        var kernel = FrustumKernels.scalar();
        Frustumf f = frustum();
        VisibilitySet vis = new VisibilitySet(N);
        assertNoAllocation("FrustumCuller.cull", WARM_BIG, CALLS_BIG, () -> {
            vis.setAll(N);
            kernel.cull(f, b, vis);
        });
    }

    @Test
    void cullPipeline() {
        BoundsArray b = scene(N, 2);
        CullContext ctx = CullContext.perspective(frustum(), Vec3f.ZERO, 1.0f, 1080);
        var distance = new CullStages.Distance(120f);
        var frustumStage = new CullStages.Frustum(FrustumKernels.scalar());
        var smallFeature = new CullStages.SmallFeature(2f);
        CullPipeline pipeline = CullPipeline.of(distance, frustumStage, smallFeature);
        VisibilitySet vis = new VisibilitySet(N);
        // each stage on its own first, so that a failure names the stage that allocates
        assertNoAllocation("CullStages.Distance", WARM_BIG, CALLS_BIG, () -> {
            vis.setAll(N);
            distance.cull(ctx, b, vis);
        });
        assertNoAllocation("CullStages.SmallFeature", WARM_BIG, CALLS_BIG, () -> {
            vis.setAll(N);
            smallFeature.cull(ctx, b, vis);
        });
        // the frustum stage hands ctx.frustum() (a value record) to the kernel: free on a plain JVM, a constant per-call buffer on the Valhalla build
        BoundsArray big = scene(N * 4, 22);
        VisibilitySet bigVis = new VisibilitySet(N * 4);
        Alloc.assertNoAllocationPerElement("CullStages.Frustum", WARM_BIG, CALLS_BIG / 2, () -> {
            vis.setAll(N);
            frustumStage.cull(ctx, b, vis);
        }, () -> {
            bigVis.setAll(N * 4);
            frustumStage.cull(ctx, big, bigVis);
        });
        Alloc.assertNoAllocationPerElement("CullPipeline.run", WARM_BIG, CALLS_BIG / 2, () -> pipeline.run(ctx, b, vis), () -> pipeline.run(ctx, big, bigVis));
    }

    @Test
    void staticBvhQueries() {
        BoundsArray b = scene(N, 3);
        BvhQuery q = new BvhQuery(StaticBvh.build(b));
        Frustumf f = frustum();
        VisibilitySet vis = new VisibilitySet(N);
        IntList out = new IntList(N);
        Aabbf probe = new Aabbf(-20f, -20f, -20f, 20f, 20f, 20f);
        Spheref sphere = new Spheref(10f, 10f, 10f, 25f);
        Rayf ray = new Rayf(0f, 0f, 0f, 0.3f, 0.1f, -1f);
        var hit = new BvhQuery.BvhHit();
        Neighbors nn = new Neighbors(8);
        assertNoAllocation("BvhQuery.frustum", WARM, CALLS, () -> {
            vis.clearAll();
            q.frustum(f, b, vis);
        });
        assertNoAllocation("BvhQuery.overlapAabb", WARM, CALLS, () -> {
            out.clear();
            q.overlapAabb(probe, b, out);
        });
        assertNoAllocation("BvhQuery.overlapSphere", WARM, CALLS, () -> {
            out.clear();
            q.overlapSphere(sphere, b, out);
        });
        assertNoAllocation("BvhQuery.raycastBounds", WARM, CALLS, () -> q.raycastBounds(ray, 500f, b, hit));
        assertNoAllocation("BvhQuery.nearest", WARM, CALLS, () -> {
            nn.reset(8);
            q.nearest(5f, 5f, 5f, b, nn);
        });
    }

    @Test
    void dynamicTree() {
        BoundsArray b = scene(N, 4);
        DynamicAabbTree tree = new DynamicAabbTree(0.25f, N * 2);
        int[] handles = new int[N];
        for (int i = 0; i < N; i++) {
            handles[i] = tree.insert(b.minX(i), b.minY(i), b.minZ(i), b.maxX(i), b.maxY(i), b.maxZ(i), i);
        }
        tree.optimize();
        var q = tree.newQuery();
        Frustumf f = frustum();
        VisibilitySet vis = new VisibilitySet(N);
        IntList out = new IntList(N);
        Aabbf probe = new Aabbf(-20f, -20f, -20f, 20f, 20f, 20f);
        Rayf ray = new Rayf(0f, 0f, 0f, 0.3f, 0.1f, -1f);
        var hit = new BvhQuery.BvhHit();
        Neighbors nn = new Neighbors(8);
        assertNoAllocation("DynamicAabbTree.Query.frustum", WARM, CALLS, () -> {
            vis.clearAll();
            q.frustum(f, vis);
        });
        assertNoAllocation("DynamicAabbTree.Query.overlapAabb", WARM, CALLS, () -> {
            out.clear();
            q.overlapAabb(probe, out);
        });
        assertNoAllocation("DynamicAabbTree.Query.raycast", WARM, CALLS, () -> q.raycast(ray, 500f, null, hit));
        assertNoAllocation("DynamicAabbTree.Query.nearest", WARM, CALLS, () -> {
            nn.reset(8);
            q.nearest(5f, 5f, 5f, nn);
        });
        int[] step = {0};
        assertNoAllocation("DynamicAabbTree.move (reinserting)", WARM, CALLS, () -> {
            int i = step[0]++ % N;
            float dx = (step[0] & 1) == 0 ? 5f : -5f;
            tree.move(handles[i], b.minX(i) + dx, b.minY(i), b.minZ(i), b.maxX(i) + dx, b.maxY(i), b.maxZ(i), 0f, 0f, 0f);
        });
        assertNoAllocation("DynamicAabbTree.insert + remove", WARM, CALLS, () -> {
            int h = tree.insert(1f, 1f, 1f, 2f, 2f, 2f, -1);
            tree.remove(h);
        });
        assertNoAllocation("DynamicAabbTree.optimize", 50, 2_000, tree::optimize);
    }

    @Test
    void uniformGrid() {
        BoundsArray b = scene(N, 5);
        UniformGrid grid = new UniformGrid(4f, N);
        int[] handles = new int[N];
        for (int i = 0; i < N; i++) {
            handles[i] = grid.insert(b.minX(i), b.minY(i), b.minZ(i), b.maxX(i), b.maxY(i), b.maxZ(i), i);
        }
        var q = grid.newQuery();
        IntList out = new IntList(N);
        Aabbf probe = new Aabbf(-20f, -20f, -20f, 20f, 20f, 20f);
        Spheref sphere = new Spheref(10f, 10f, 10f, 25f);
        Neighbors nn = new Neighbors(8);
        assertNoAllocation("UniformGrid.Query.overlapAabb", WARM, CALLS, () -> {
            out.clear();
            q.overlapAabb(probe, out);
        });
        assertNoAllocation("UniformGrid.Query.overlapSphere", WARM, CALLS, () -> {
            out.clear();
            q.overlapSphere(sphere, out);
        });
        assertNoAllocation("UniformGrid.Query.nearest", WARM, CALLS, () -> {
            nn.reset(8);
            q.nearest(5f, 5f, 5f, nn);
        });
        int[] step = {0};
        assertNoAllocation("UniformGrid.move (changing cells)", WARM, CALLS, () -> {
            int i = step[0]++ % N;
            float dx = (step[0] & 1) == 0 ? 9f : -9f;
            grid.move(handles[i], b.minX(i) + dx, b.minY(i), b.minZ(i), b.maxX(i) + dx, b.maxY(i), b.maxZ(i));
        });
    }

    @Test
    void looseOctree() {
        BoundsArray b = scene(N, 6);
        LooseOctree tree = new LooseOctree(0f, 0f, 0f, 150f, 8);
        int[] handles = new int[N];
        for (int i = 0; i < N; i++) {
            handles[i] = tree.insert(b.minX(i), b.minY(i), b.minZ(i), b.maxX(i), b.maxY(i), b.maxZ(i), i);
        }
        var q = tree.newQuery();
        IntList out = new IntList(N);
        Aabbf probe = new Aabbf(-20f, -20f, -20f, 20f, 20f, 20f);
        Spheref sphere = new Spheref(10f, 10f, 10f, 25f);
        Neighbors nn = new Neighbors(8);
        assertNoAllocation("LooseOctree.Query.overlapAabb", WARM, CALLS, () -> {
            out.clear();
            q.overlapAabb(probe, out);
        });
        assertNoAllocation("LooseOctree.Query.overlapSphere", WARM, CALLS, () -> {
            out.clear();
            q.overlapSphere(sphere, out);
        });
        assertNoAllocation("LooseOctree.Query.nearest", WARM, CALLS, () -> {
            nn.reset(8);
            q.nearest(5f, 5f, 5f, nn);
        });
        int[] step = {0};
        assertNoAllocation("LooseOctree.move (changing nodes)", WARM, CALLS, () -> {
            int i = step[0]++ % N;
            float dx = (step[0] & 1) == 0 ? 30f : -30f;
            tree.move(handles[i], b.minX(i) + dx, b.minY(i), b.minZ(i), b.maxX(i) + dx, b.maxY(i), b.maxZ(i));
        });
    }

    @Test
    void occlusion() {
        BoundsArray b = scene(N, 7);
        DepthBuffer d = new DepthBuffer(128, 64);
        Mat4f vp = viewProjection();
        OcclusionStage stage = new OcclusionStage(d);
        VisibilitySet vis = new VisibilitySet(N);
        assertNoAllocation("DepthBuffer begin + addBox + finish", WARM_BIG, CALLS_BIG, () -> {
            d.begin(vp, 0.3f);
            d.addBox(-5f, -5f, -40f, 5f, 5f, -39f);
            d.addBox(10f, -5f, -60f, 20f, 5f, -58f);
            d.finish();
        });
        assertNoAllocation("DepthBuffer.isHidden", WARM_BIG, CALLS_BIG, () -> d.isHidden(-1f, -1f, -80f, 1f, 1f, -78f));
        assertNoAllocation("OcclusionStage.cull", WARM_BIG, CALLS_BIG, () -> {
            vis.setAll(N);
            stage.cull(null, b, vis);
        });
    }

    @Test
    void lodConeShadowAndLightCulling() {
        BoundsArray b = scene(N, 8);
        CullContext ctx = CullContext.perspective(frustum(), Vec3f.ZERO, 1.0f, 1080);
        VisibilitySet vis = new VisibilitySet(N);
        LodSelector lod = LodSelector.of(300f, 100f, 30f);
        byte[] levels = new byte[N];
        java.util.Arrays.fill(levels, LodSelector.NO_LEVEL);
        float[] fade = new float[N];
        assertNoAllocation("LodSelector.select", WARM_BIG, CALLS_BIG, () -> {
            vis.setAll(N);
            lod.select(ctx, b, vis, levels, fade);
        });
        var clusters = new ConeCull.Clusters();
        for (int i = 0; i < N; i++) {
            clusters.add(b.minX(i), b.minY(i), b.minZ(i), 1f, 0f, 1f, 0f, 0.3f);
        }
        assertNoAllocation("ConeCull.Clusters.cull", WARM_BIG, CALLS_BIG, () -> {
            vis.setAll(N);
            clusters.cull(0f, 0f, 0f, vis);
        });
        Cameraf camera = Cameraf.lookingAt(Vec3f.ZERO, new Vec3f(0f, 0f, -1f), Vec3f.UNIT_Y, 1f, 1.5f, 0.3f, 300f, DepthRange.ZERO_TO_ONE);
        var cascade = Cascades.fit(camera, 0.3f, 60f, new Vec3f(0.3f, -1f, -0.2f), 1024, true, 100f, DepthRange.ZERO_TO_ONE);
        CascadeCasters casters = new CascadeCasters(camera, cascade, 0.1f);
        assertNoAllocation("CascadeCasters.cull", WARM_BIG, CALLS_BIG, () -> {
            vis.setAll(N);
            casters.cull(null, b, vis);
        });
        byte[] masks = new byte[N];
        assertNoAllocation("LightCull.pointLight", WARM_BIG, CALLS_BIG, () -> {
            vis.setAll(N);
            LightCull.pointLight(b, vis, 0f, 0f, 0f, 60f);
        });
        assertNoAllocation("LightCull.spotLight", WARM_BIG, CALLS_BIG, () -> {
            vis.setAll(N);
            LightCull.spotLight(b, vis, 0f, 0f, 0f, 0f, 0f, -1f, 0.6f, 100f);
        });
        assertNoAllocation("LightCull.cubeFaces", WARM_BIG, CALLS_BIG, () -> {
            vis.setAll(N);
            LightCull.cubeFaces(b, vis, 0f, 0f, 0f, 80f, masks);
        });
    }

    @Test
    void boundsTransform() {
        BoundsArray local = scene(N, 9);
        BoundsArray world = new BoundsArray(N);
        world.setSize(N);
        Mat4fArray matrices = new Mat4fArray(N);
        for (int i = 0; i < N; i++) {
            matrices.add(Mat4f.IDENTITY);
        }
        assertNoAllocation("BoundsArray.transformFrom", WARM_BIG, CALLS_BIG, () -> world.transformFrom(local, matrices));
    }

    // ------------------------------------------------------------ bulk arrays

    @Test
    void bulkArrayKernels() {
        int n = 2000;
        vmath.bulk.Vec3fArray points = new vmath.bulk.Vec3fArray(n), moved = new vmath.bulk.Vec3fArray(n);
        vmath.bulk.QuatArray qa = new vmath.bulk.QuatArray(n), qb = new vmath.bulk.QuatArray(n), qo = new vmath.bulk.QuatArray(n);
        vmath.bulk.TransformArray ta = new vmath.bulk.TransformArray(n), tb = new vmath.bulk.TransformArray(n), to = new vmath.bulk.TransformArray(n);
        Mat4fArray mats = new Mat4fArray(n);
        for (int i = 0; i < n; i++) {
            points.add(i, 2f * i, 3f);
            float s = (float) Math.sin(i), c = (float) Math.cos(i);
            qa.add(0f, s * 0.3f, 0f, 1f);
            qb.add(0f, 0f, c * 0.3f, 1f);
            ta.add(i, 0f, 0f, 0f, s * 0.2f, 0f, 1f, 1f, 1f, 1f);
            tb.add(0f, i, 0f, 0f, 0f, c * 0.2f, 1f, 2f, 2f, 2f);
        }
        qa.normalizeAll();
        qb.normalizeAll();
        // a matrix passed to a bulk kernel is a value record read into locals: one call is one set of reads, so it stays zero under Valhalla too
        Mat4f m = Mat4f.translation(1f, 2f, 3f);
        assertNoAllocation("Vec3fArray.transformPositions", WARM_BIG, CALLS_BIG, () -> points.transformPositions(m, moved));
        assertNoAllocation("Vec3fArray.transformDirections", WARM_BIG, CALLS_BIG, () -> points.transformDirections(m, moved));
        assertNoAllocation("Vec3fArray.normalizeAll", WARM_BIG, CALLS_BIG, moved::normalizeAll);
        assertNoAllocation("QuatArray.slerp", WARM_BIG, CALLS_BIG, () -> vmath.bulk.QuatArray.slerp(qa, qb, 0.3f, qo));
        assertNoAllocation("QuatArray.multiply", WARM_BIG, CALLS_BIG, () -> vmath.bulk.QuatArray.multiply(qa, qb, qo));
        assertNoAllocation("QuatArray.toMatrices", WARM_BIG, CALLS_BIG, () -> qa.toMatrices(mats));
        assertNoAllocation("TransformArray.toMatrices", WARM_BIG, CALLS_BIG, () -> ta.toMatrices(mats));
        assertNoAllocation("TransformArray.blend", WARM_BIG, CALLS_BIG, () -> vmath.bulk.TransformArray.blend(ta, tb, 0.5f, to));
    }

    // ------------------------------------------------------------ clustered lighting

    @Test
    void clusterLightAssignment() {
        vmath.camera.ClusterGrid grid = vmath.camera.ClusterGrid.of(1.0f, 16f / 9f, 0.1f, 200f, 1280, 720, 64, 16, false);
        vmath.camera.ClusterLights lights = new vmath.camera.ClusterLights();
        for (int i = 0; i < 200; i++) {
            float d = 2f + i * 0.5f;
            if (i % 4 == 0) {
                lights.addSpot((i % 7 - 3) * 0.5f, (i % 5 - 2) * 0.4f, -d, 0.1f, -0.2f, -1f, 0.6f, 20f);
            } else {
                lights.addPoint((i % 9 - 4) * 0.8f, (i % 3 - 1) * 0.7f, -d, 6f);
            }
        }
        assertNoAllocation("ClusterLights.assign", WARM_BIG, CALLS_BIG, () -> lights.assign(grid));
        assertNoAllocation("ClusterGrid.clusterOf", WARM, CALLS, () -> grid.clusterOf(400f, 300f, 12f));
        assertNoAllocation("ClusterGrid.clusterOfViewPosition", WARM, CALLS, () -> grid.clusterOfViewPosition(0.3f, 0.2f, -9f));
        float[] box = new float[6];
        assertNoAllocation("ClusterGrid.bounds", WARM, CALLS, () -> grid.bounds(1234, box, 0));
    }

    // ------------------------------------------------------------ GPU-driven culling references

    @Test
    void gpuCullingReferences() {
        int n = 4000;
        java.lang.foreign.MemorySegment objects = java.lang.foreign.MemorySegment.ofArray(new byte[(int) (n * vmath.gpucull.CullObjectGpu.SIZE)]);
        for (int i = 0; i < n; i++) {
            float x = (i % 40 - 20) * 2f, y = (i / 40 % 20 - 10) * 1.5f, z = -5f - (i / 800) * 20f;
            vmath.gpucull.CullObjectGpu.write(new vmath.gpucull.CullObject(new vmath.core.Vec3f(x - 0.5f, y - 0.5f, z - 0.5f), i % 4, new vmath.core.Vec3f(x + 0.5f, y + 0.5f, z + 0.5f), 0),
                    objects, i * vmath.gpucull.CullObjectGpu.SIZE);
        }
        vmath.core.Mat4f vp = vmath.core.Mat4f.perspective(1.0f, 1.6f, 0.1f, 200f, vmath.core.ClipSpace.VULKAN);
        vmath.geo.Frustumf f = vmath.geo.Frustumf.fromViewProjection(vp, vmath.geo.DepthRange.ZERO_TO_ONE);
        vmath.core.Vec4f[] planes = new vmath.core.Vec4f[6];
        for (int i = 0; i < 6; i++) {
            planes[i] = new vmath.core.Vec4f(f.plane(i).nx(), f.plane(i).ny(), f.plane(i).nz(), f.plane(i).d());
        }
        float[] depth = new float[64 * 40];
        java.util.Arrays.fill(depth, 0.9f);
        vmath.gpucull.HiZPyramid hzb = vmath.gpucull.HiZPyramid.fromDepth(depth, 64, 40, vmath.geo.DepthRange.ZERO_TO_ONE, false);
        java.lang.foreign.MemorySegment view = java.lang.foreign.MemorySegment.ofArray(new byte[(int) vmath.gpucull.CullViewGpu.SIZE]);
        vmath.gpucull.CullViewGpu.write(new vmath.gpucull.CullView(planes, vp, n, 64, 40, hzb.levels(), 1e-4f, 1, 0), view, 0);
        int[] capacity = {n, n, n, n};
        vmath.gl.DrawCommandBuffer commands = new vmath.gl.DrawCommandBuffer(java.lang.foreign.MemorySegment.ofArray(new byte[4 * 20]), vmath.gl.DrawCommandBuffer.Kind.ELEMENTS, false);
        for (int d = 0; d < 4; d++) {
            commands.addElements(36, 0, 0, 0, d * n);
        }
        java.lang.foreign.MemorySegment visible = java.lang.foreign.MemorySegment.ofArray(new byte[4 * 4 * n]);
        vmath.gpucull.GpuCullReference.Counters counters = new vmath.gpucull.GpuCullReference.Counters();
        vmath.bulk.VisibilitySet last = new vmath.bulk.VisibilitySet(n), drawn = new vmath.bulk.VisibilitySet(n), now = new vmath.bulk.VisibilitySet(n);
        last.setAll(n);
        Runnable reset = () -> {
            for (int d = 0; d < 4; d++) {
                commands.setInstanceCount(d, 0);
            }
            counters.reset();
        };
        assertNoAllocation("GpuCullReference.cullSinglePass", 300, 2000, () -> {
            reset.run();
            vmath.gpucull.GpuCullReference.cullSinglePass(view, objects, hzb, commands, capacity, visible, counters);
        });
        assertNoAllocation("GpuCullReference.cullPhase1", 300, 2000, () -> {
            reset.run();
            vmath.gpucull.GpuCullReference.cullPhase1(view, objects, last, drawn, commands, capacity, visible, counters);
        });
        assertNoAllocation("GpuCullReference.cullPhase2", 300, 2000, () -> {
            reset.run();
            vmath.gpucull.GpuCullReference.cullPhase2(view, objects, hzb, drawn, now, commands, capacity, visible, counters);
        });
        assertNoAllocation("HiZPyramid.isHidden", WARM, CALLS, () -> hzb.isHidden(-0.3f, -0.2f, 0.4f, 0.3f, 0.95f));
        // the cluster pass
        vmath.mesh.Mesh sphere = vmath.mesh.Primitives.icoSphere(1f, 4);
        vmath.mesh.MeshOptimizer.weld(sphere, 1e-6f, true);
        vmath.mesh.ClusterHierarchy h = vmath.mesh.ClusterHierarchy.build(sphere, 64, 128, 4);
        int nc = h.clusterCount();
        java.lang.foreign.MemorySegment clusters = java.lang.foreign.MemorySegment.ofArray(new byte[(int) (nc * vmath.gpucull.ClusterCullObjectGpu.SIZE)]);
        for (int c = 0; c < nc; c++) {
            vmath.gpucull.ClusterCullObjectGpu.write(vmath.gpucull.ClusterCullObject.of(h, c, 0), clusters, c * vmath.gpucull.ClusterCullObjectGpu.SIZE);
        }
        java.lang.foreign.MemorySegment cview = java.lang.foreign.MemorySegment.ofArray(new byte[(int) vmath.gpucull.ClusterCullViewGpu.SIZE]);
        vmath.gpucull.ClusterCullViewGpu.write(new vmath.gpucull.ClusterCullView(planes, vp, new vmath.core.Vec4f(0f, 0f, 4f, 800f), nc, 64, 40, hzb.levels(), 1e-4f, 1f, 1, 0), cview, 0);
        vmath.gl.DrawCommandBuffer out = new vmath.gl.DrawCommandBuffer(java.lang.foreign.MemorySegment.ofArray(new byte[nc * 20]), vmath.gl.DrawCommandBuffer.Kind.ELEMENTS, false);
        vmath.gpucull.ClusterCullReference.Counters cc = new vmath.gpucull.ClusterCullReference.Counters();
        assertNoAllocation("ClusterCullReference.cull", 300, 2000, () -> {
            cc.reset();
            vmath.gpucull.ClusterCullReference.cull(cview, clusters, hzb, out, cc);
        });
    }

    // ------------------------------------------------------------ GPU buffers

    @Test
    void indirectDrawAndInstanceWriters() {
        java.lang.foreign.MemorySegment seg = java.lang.foreign.MemorySegment.ofArray(new byte[64 * 1024]);
        vmath.gl.DrawCommandBuffer commands = new vmath.gl.DrawCommandBuffer(seg, vmath.gl.DrawCommandBuffer.Kind.ELEMENTS, true);
        int[] n = {0};
        assertNoAllocation("DrawCommandBuffer.addElements", WARM, CALLS, () -> {
            if (commands.count() == commands.capacity()) {
                commands.clear();
            }
            int i = commands.addElements(n[0]++ & 0xFFFF, 4, 0, 0, 0);
            commands.setInstanceCount(i, 0);
        });
        vmath.core.Mat4x3f transform = vmath.core.Mat4x3f.translation(1f, 2f, 3f);
        assertNoAllocation("InstanceWriter.write", WARM, CALLS, () -> vmath.gl.InstanceWriter.write(seg, n[0]++ & 511, transform, 7));
        assertNoAllocation("InstanceWriter.writeTranslation", WARM, CALLS, () -> vmath.gl.InstanceWriter.writeTranslation(seg, n[0]++ & 511, 1f, 2f, 3f, 7));
        vmath.bulk.BoundsArray bounds = new vmath.bulk.BoundsArray(256);
        vmath.bulk.VisibilitySet visible = new vmath.bulk.VisibilitySet(256);
        for (int i = 0; i < 256; i++) {
            bounds.add(i, i, i, i + 1f, i + 1f, i + 1f);
            if ((i & 3) == 0) {
                visible.set(i);
            }
        }
        assertNoAllocation("InstanceWriter.writeVisibleTranslations", WARM_BIG, CALLS_BIG, () -> vmath.gl.InstanceWriter.writeVisibleTranslations(seg, 0, visible, bounds));
    }

    // ------------------------------------------------------------ animation

    @Test
    void transformHierarchyUpdate() {
        TransformHierarchy h = new TransformHierarchy(N);
        for (int i = 0; i < N; i++) {
            h.add(i == 0 ? -1 : Math.max(0, i - 1 - (i % 7)));
        }
        h.update();
        float[] phase = {0f};
        assertNoAllocation("TransformHierarchy.setTranslation + update", WARM_BIG, CALLS_BIG, () -> {
            phase[0] += 0.01f;
            for (int i = 0; i < N; i += 50) {
                h.setTranslation(i, phase[0], 0f, 1f);
            }
            h.update();
        });
    }

    @Test
    void skeletalAnimation() {
        int joints = 48;
        int[] parents = new int[joints];
        float[] bind = new float[joints * 10];
        for (int j = 0; j < joints; j++) {
            parents[j] = j == 0 ? -1 : Math.max(0, j - 1 - (j % 3));
            bind[j * 10 + 1] = 0.3f;
            bind[j * 10 + 6] = 1f;
            bind[j * 10 + 7] = 1f;
            bind[j * 10 + 8] = 1f;
            bind[j * 10 + 9] = 1f;
        }
        Skeleton skeleton = new Skeleton(parents, bind);
        AnimationClip.Builder cb = AnimationClip.builder(joints);
        float[] times = {0f, 0.5f, 1f, 1.5f};
        for (int j = 0; j < joints; j++) {
            cb.translation(j, times, new float[] {0, 0, 0, 1, 0, 0, 2, 0, 0, 3, 0, 0});
            cb.rotation(j, times, new float[] {0, 0, 0, 1, 0, 0.1f, 0, 1, 0, 0.2f, 0, 1, 0, 0.3f, 0, 1});
        }
        AnimationClip clip = cb.build();
        ClipSampler sampler = new ClipSampler(clip);
        Pose a = new Pose(skeleton), b = new Pose(skeleton), out = new Pose(skeleton), additive = new Pose(skeleton);
        Mat4fArray matrices = new Mat4fArray(joints);
        float[] scratch = new float[joints * 16];
        float[] mask = new float[joints];
        java.util.Arrays.fill(mask, 0.5f);
        float[] t = {0f};
        assertNoAllocation("ClipSampler.sample", WARM, CALLS, () -> {
            t[0] += 0.016f;
            sampler.sample(t[0], true, a);
        });
        sampler.sample(0.7f, true, b);
        assertNoAllocation("Pose.lerp", WARM, CALLS, () -> Pose.lerp(a, b, 0.4f, out));
        assertNoAllocation("Pose.blendMasked", WARM, CALLS, () -> Pose.blendMasked(a, b, mask, 0.8f, out));
        assertNoAllocation("Pose.makeAdditive + applyAdditive", WARM, CALLS, () -> {
            Pose.makeAdditive(a, b, additive);
            Pose.applyAdditive(a, additive, 0.5f, out);
        });
        assertNoAllocation("Skinning.jointMatrices", WARM, CALLS, () -> Skinning.jointMatrices(skeleton, out, scratch, matrices));
        int vc = 500;
        float[] pos = new float[vc * 3], normals = new float[vc * 3], skinned = new float[vc * 3];
        int[] ji = new int[vc * 4];
        float[] w = new float[vc * 4];
        for (int v = 0; v < vc; v++) {
            normals[v * 3 + 1] = 1f;
            for (int k = 0; k < 4; k++) {
                ji[v * 4 + k] = (v + k) % joints;
                w[v * 4 + k] = 0.25f;
            }
        }
        assertNoAllocation("Skinning.skinPositions", WARM_BIG, CALLS_BIG, () -> Skinning.skinPositions(matrices.data(), pos, ji, w, vc, skinned));
        assertNoAllocation("Skinning.skinNormals", WARM_BIG, CALLS_BIG, () -> Skinning.skinNormals(matrices.data(), normals, ji, w, vc, skinned));
    }

    // ------------------------------------------------------------ the helper itself

    @Test
    void theHelperCatchesARealAllocation() {
        java.util.List<Object> sink = new java.util.ArrayList<>();
        double bytes = Alloc.bytesPerCall(() -> {
            sink.add(new long[16]);
            if (sink.size() > 100) {
                sink.clear();
            }
        }, 1_000, 10_000);
        org.junit.jupiter.api.Assertions.assertTrue(bytes > 100.0, "a path that allocates a 16-long array per call must be seen, measured " + bytes);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> assertNoAllocation("x", 10, 10, () -> { }));
    }

    // ------------------------------------------------------------ sorting and scans

    @Test
    void radixSortAndLocalityOrder() {
        int n = 3000;
        java.util.SplittableRandom r = new java.util.SplittableRandom(1);
        vmath.bulk.RadixSorter sorter = new vmath.bulk.RadixSorter();
        sorter.reserve(n);
        int[] keys = new int[n], work = new int[n], values = new int[n];
        long[] lkeys = new long[n], lwork = new long[n];
        float[] fkeys = new float[n], fwork = new float[n];
        double[] dkeys = new double[n], dwork = new double[n];
        for (int i = 0; i < n; i++) {
            keys[i] = r.nextInt();
            lkeys[i] = r.nextLong();
            fkeys[i] = (float) r.nextDouble();
            dkeys[i] = r.nextDouble();
        }
        assertNoAllocation("RadixSorter.sort(int)", 300, 2000, () -> {
            System.arraycopy(keys, 0, work, 0, n);
            sorter.sort(work, values, n);
        });
        assertNoAllocation("RadixSorter.sortUnsigned(long)", 300, 2000, () -> {
            System.arraycopy(lkeys, 0, lwork, 0, n);
            sorter.sortUnsigned(lwork, values, n);
        });
        assertNoAllocation("RadixSorter.sort(float)", 300, 2000, () -> {
            System.arraycopy(fkeys, 0, fwork, 0, n);
            sorter.sort(fwork, values, n, true);
        });
        assertNoAllocation("RadixSorter.order(float)", 300, 2000, () -> sorter.order(fkeys, n, values, false));
        assertNoAllocation("RadixSorter.order(double)", 300, 2000, () -> sorter.order(dkeys, n, values, false));
        float[] xyz = new float[3 * n];
        for (int i = 0; i < xyz.length; i++) {
            xyz[i] = (float) r.nextDouble();
        }
        int[] order = new int[n];
        long[] codes = new long[n];
        assertNoAllocation("LocalityOrder.order(HILBERT)", 300, 2000, () -> vmath.bulk.LocalityOrder.order(xyz, n, vmath.bulk.LocalityOrder.Curve.HILBERT, order, codes, sorter));
        assertNoAllocation("LocalityOrder.order(MORTON)", 300, 2000, () -> vmath.bulk.LocalityOrder.order(xyz, n, vmath.bulk.LocalityOrder.Curve.MORTON, order, codes, sorter));
        int[] counts = new int[n];
        assertNoAllocation("PrefixSum.exclusive", 300, 2000, () -> {
            java.util.Arrays.fill(counts, 1);
            vmath.bulk.PrefixSum.exclusive(counts, n);
        });
        assertNoAllocation("Hilbert.encode3", 1000, 100000, () -> vmath.core.Hilbert.encode3(12345, 6789, 424242, 21));
    }
}
