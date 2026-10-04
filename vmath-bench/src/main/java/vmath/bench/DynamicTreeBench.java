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
import vmath.bulk.IntList;
import vmath.bulk.VisibilitySet;
import vmath.core.ClipSpace;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;
import vmath.spatial.BvhQuery;
import vmath.spatial.DynamicAabbTree;
import vmath.spatial.StaticBvh;

/**
 * The dynamic AABB tree at scale. Run with {@code -prof gc}: the steady-state operations should all report ~0 B/op.
 *
 * <ul>
 *   <li>{@code moveWithinFatBox}: a tiny drift, which the fat box absorbs (the common case for slow movers)</li>
 *   <li>{@code moveAndReinsert}: a jump far outside the fat box, so the object is removed and reinserted</li>
 *   <li>{@code removeAndInsert}: churn, as when objects stream in and out of a level</li>
 *   <li>{@code frustumQuery}, {@code overlapQuery}: queries over the whole tree</li>
 * </ul>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class DynamicTreeBench {

    @Param({"100000"})
    public int count;

    /** Whether {@link DynamicAabbTree#optimize()} ran after loading (depth-first node order) or not (insertion order). */
    @Param({"false", "true"})
    public boolean optimized;

    private static final float WORLD = 500f;

    private DynamicAabbTree tree;
    private DynamicAabbTree.Query query;
    private int[] handles;
    private float[] cx;
    private float[] cy;
    private float[] cz;
    private float[] half;
    private SplittableRandom rnd;
    private int cursor;
    private VisibilitySet visible;
    private IntList results;
    private Frustumf frustum;
    private Aabbf probe;

    @Setup
    public void setup() {
        rnd = new SplittableRandom(11);
        tree = new DynamicAabbTree(0.25f, count * 2);
        handles = new int[count];
        cx = new float[count];
        cy = new float[count];
        cz = new float[count];
        half = new float[count];
        for (int i = 0; i < count; i++) {
            cx[i] = (float) (rnd.nextDouble() * 2 - 1) * WORLD;
            cy[i] = (float) (rnd.nextDouble() * 2 - 1) * WORLD;
            cz[i] = (float) (rnd.nextDouble() * 2 - 1) * WORLD;
            half[i] = 0.2f + (float) rnd.nextDouble();
            handles[i] = tree.insert(cx[i] - half[i], cy[i] - half[i], cz[i] - half[i],
                    cx[i] + half[i], cy[i] + half[i], cz[i] + half[i], i);
        }
        if (optimized) {
            tree.optimize();
        }
        query = tree.newQuery();
        visible = new VisibilitySet(count);
        results = new IntList(4096);
        Mat4f view = Mat4f.lookAt(Vec3f.ZERO, new Vec3f(0.3f, 0.1f, -1f), Vec3f.UNIT_Y);
        frustum = Frustumf.fromViewProjection(Mat4f.perspective(1.0f, 16f / 9f, 0.1f, 750f, ClipSpace.D3D).mul(view), DepthRange.ZERO_TO_ONE);
        probe = Aabbf.fromCenterHalfExtent(new Vec3f(10f, 10f, 10f), Vec3f.splat(15f));

        // the same scene in a static BVH, as the reference for the dynamic tree's query cost
        BoundsArray all = new BoundsArray(count);
        for (int i = 0; i < count; i++) {
            all.add(cx[i] - half[i], cy[i] - half[i], cz[i] - half[i], cx[i] + half[i], cy[i] + half[i], cz[i] + half[i]);
        }
        staticBvh = StaticBvh.build(all);
        staticQuery = new BvhQuery(staticBvh);
        staticBounds = all;
    }

    private StaticBvh staticBvh;
    private BvhQuery staticQuery;
    private BoundsArray staticBounds;

    private Frustumf everything;

    /** Diagnosis: every object inside, so the whole tree is accepted as one subtree (isolates the accept walk). */
    @Benchmark
    public int diagAcceptAllDynamic() {
        if (everything == null) {
            everything = Frustumf.fromViewProjection(Mat4f.perspective(3.0f, 1f, 0.1f, 1e6f, ClipSpace.D3D)
                    .mul(Mat4f.lookAt(new Vec3f(0f, 0f, 2000f), Vec3f.ZERO, Vec3f.UNIT_Y)), DepthRange.ZERO_TO_ONE);
        }
        visible.clearAll();
        return query.frustum(everything, visible);
    }

    @Benchmark
    public int diagAcceptAllStatic() {
        if (everything == null) {
            everything = Frustumf.fromViewProjection(Mat4f.perspective(3.0f, 1f, 0.1f, 1e6f, ClipSpace.D3D)
                    .mul(Mat4f.lookAt(new Vec3f(0f, 0f, 2000f), Vec3f.ZERO, Vec3f.UNIT_Y)), DepthRange.ZERO_TO_ONE);
        }
        visible.clearAll();
        return staticQuery.frustum(everything, staticBounds, visible);
    }

    /** The reference: same objects, same frustum, through a SAH-built {@link StaticBvh}. */
    @Benchmark
    public int frustumQueryStaticReference() {
        visible.clearAll();
        return staticQuery.frustum(frustum, staticBounds, visible);
    }

    private int next() {
        int i = cursor;
        cursor = cursor + 1 == count ? 0 : cursor + 1;
        return i;
    }

    @Benchmark
    public boolean moveWithinFatBox() {
        int i = next();
        float h = half[i];
        // a drift of 0.01 stays inside the 0.25 margin and the "not too loose" check
        float nx = cx[i] + 0.01f;
        cx[i] = nx;
        return tree.move(handles[i], nx - h, cy[i] - h, cz[i] - h, nx + h, cy[i] + h, cz[i] + h, 0.01f, 0f, 0f);
    }

    @Benchmark
    public boolean moveAndReinsert() {
        int i = next();
        float h = half[i];
        cx[i] = (float) (rnd.nextDouble() * 2 - 1) * WORLD;
        cy[i] = (float) (rnd.nextDouble() * 2 - 1) * WORLD;
        cz[i] = (float) (rnd.nextDouble() * 2 - 1) * WORLD;
        return tree.move(handles[i], cx[i] - h, cy[i] - h, cz[i] - h, cx[i] + h, cy[i] + h, cz[i] + h, 0f, 0f, 0f);
    }

    @Benchmark
    public int removeAndInsert() {
        int i = next();
        float h = half[i];
        tree.remove(handles[i]);
        cx[i] = (float) (rnd.nextDouble() * 2 - 1) * WORLD;
        cy[i] = (float) (rnd.nextDouble() * 2 - 1) * WORLD;
        cz[i] = (float) (rnd.nextDouble() * 2 - 1) * WORLD;
        int handle = tree.insert(cx[i] - h, cy[i] - h, cz[i] - h, cx[i] + h, cy[i] + h, cz[i] + h, i);
        handles[i] = handle;
        return handle;
    }

    @Benchmark
    public int frustumQuery() {
        visible.clearAll();
        return query.frustum(frustum, visible);
    }

    @Benchmark
    public int overlapQuery() {
        results.clear();
        query.overlapAabb(probe, results);
        return results.size();
    }
}
