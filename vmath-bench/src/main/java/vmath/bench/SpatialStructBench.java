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
import vmath.geo.Aabbf;
import vmath.spatial.BvhQuery;
import vmath.spatial.DynamicAabbTree;
import vmath.spatial.LooseOctree;
import vmath.spatial.Neighbors;
import vmath.spatial.StaticBvh;
import vmath.spatial.UniformGrid;

/**
 * The four spatial structures on the same objects: box-overlap queries, 8-nearest-neighbour queries, and moving one object.
 * Run with {@code -prof gc}: every steady-state operation should report ~0 B/op.
 *
 * <p>{@code layout=uniform} scatters objects evenly through the world; {@code clustered} packs them into a few dense blobs,
 * which is what hurts a fixed grid. The static BVH has no per-object move (it is refitted or rebuilt as a whole), so
 * {@code bvhRefit} shows the cost of refitting after <em>every</em> object has moved.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class SpatialStructBench {

    @Param({"100000"})
    public int count;

    @Param({"uniform", "clustered"})
    public String layout;

    private static final float WORLD = 500f;

    private float[] cx;
    private float[] cy;
    private float[] cz;
    private float[] half;
    private SplittableRandom rnd;
    private int cursor;

    private BoundsArray bounds;
    private StaticBvh bvh;
    private BvhQuery bvhQuery;
    private DynamicAabbTree dyn;
    private DynamicAabbTree.Query dynQuery;
    private int[] dynHandle;
    private UniformGrid grid;
    private UniformGrid.Query gridQuery;
    private int[] gridHandle;
    private LooseOctree octree;
    private LooseOctree.Query octQuery;
    private int[] octHandle;

    private final IntList results = new IntList(4096);
    private final Neighbors neighbors = new Neighbors(8);
    private Aabbf probe;
    private final float[] qx = new float[256];
    private final float[] qy = new float[256];
    private final float[] qz = new float[256];
    private final Aabbf[] probes = new Aabbf[256]; // built up front so the benchmark itself allocates nothing
    private int qi;

    @Setup
    public void setup() {
        rnd = new SplittableRandom(5);
        cx = new float[count];
        cy = new float[count];
        cz = new float[count];
        half = new float[count];
        float[][] centres = new float[8][3];
        for (float[] c : centres) {
            for (int k = 0; k < 3; k++) {
                c[k] = (float) (rnd.nextDouble() * 2 - 1) * WORLD * 0.8f;
            }
        }
        for (int i = 0; i < count; i++) {
            if (layout.equals("uniform")) {
                cx[i] = coord();
                cy[i] = coord();
                cz[i] = coord();
            } else {
                float[] c = centres[rnd.nextInt(centres.length)];
                cx[i] = c[0] + gauss() * 25f;
                cy[i] = c[1] + gauss() * 25f;
                cz[i] = c[2] + gauss() * 25f;
            }
            half[i] = 0.2f + (float) rnd.nextDouble();
        }
        bounds = new BoundsArray(count);
        dyn = new DynamicAabbTree(0.25f, count * 2);
        grid = new UniformGrid(4f, count);
        octree = new LooseOctree(0f, 0f, 0f, WORLD * 1.5f, 8);
        dynHandle = new int[count];
        gridHandle = new int[count];
        octHandle = new int[count];
        for (int i = 0; i < count; i++) {
            bounds.add(cx[i] - half[i], cy[i] - half[i], cz[i] - half[i], cx[i] + half[i], cy[i] + half[i], cz[i] + half[i]);
            dynHandle[i] = dyn.insert(cx[i] - half[i], cy[i] - half[i], cz[i] - half[i], cx[i] + half[i], cy[i] + half[i], cz[i] + half[i], i);
            gridHandle[i] = grid.insert(cx[i] - half[i], cy[i] - half[i], cz[i] - half[i], cx[i] + half[i], cy[i] + half[i], cz[i] + half[i], i);
            octHandle[i] = octree.insert(cx[i] - half[i], cy[i] - half[i], cz[i] - half[i], cx[i] + half[i], cy[i] + half[i], cz[i] + half[i], i);
        }
        dyn.optimize();
        bvh = StaticBvh.build(bounds);
        bvhQuery = new BvhQuery(bvh);
        dynQuery = dyn.newQuery();
        gridQuery = grid.newQuery();
        octQuery = octree.newQuery();
        // query points sit on objects, so that the probe density follows the data
        for (int i = 0; i < qx.length; i++) {
            int o = rnd.nextInt(count);
            qx[i] = cx[o] + (float) (rnd.nextDouble() - 0.5) * 6f;
            qy[i] = cy[o] + (float) (rnd.nextDouble() - 0.5) * 6f;
            qz[i] = cz[o] + (float) (rnd.nextDouble() - 0.5) * 6f;
            probes[i] = new Aabbf(qx[i] - 4f, qy[i] - 4f, qz[i] - 4f, qx[i] + 4f, qy[i] + 4f, qz[i] + 4f);
        }
        System.out.println("# grid entries " + grid.entryCount() + " (" + grid.oversizeCount() + " oversize), octree nodes "
                + octree.nodeCount() + ", dynamic tree height " + dyn.height());
    }

    private float coord() {
        return (float) (rnd.nextDouble() * 2 - 1) * WORLD;
    }

    private float gauss() {
        double s = 0;
        for (int i = 0; i < 4; i++) {
            s += rnd.nextDouble() - 0.5;
        }
        return (float) s;
    }

    private void nextQuery() {
        qi = qi + 1 == qx.length ? 0 : qi + 1;
        probe = probes[qi];
    }

    // ---------------------------------------------------------------- overlap: a box about 8 units wide

    @Benchmark
    public int overlapBvh() {
        nextQuery();
        results.clear();
        bvhQuery.overlapAabb(probe, bounds, results);
        return results.size();
    }

    @Benchmark
    public int overlapDynamicTree() {
        nextQuery();
        results.clear();
        dynQuery.overlapAabb(probe, results);
        return results.size();
    }

    @Benchmark
    public int overlapGrid() {
        nextQuery();
        results.clear();
        gridQuery.overlapAabb(probe, results);
        return results.size();
    }

    @Benchmark
    public int overlapOctree() {
        nextQuery();
        results.clear();
        octQuery.overlapAabb(probe, results);
        return results.size();
    }

    // ---------------------------------------------------------------- 8 nearest neighbours

    @Benchmark
    public int nearestBvh() {
        nextQuery();
        neighbors.reset(8);
        bvhQuery.nearest(qx[qi], qy[qi], qz[qi], bounds, neighbors);
        return neighbors.size();
    }

    @Benchmark
    public int nearestDynamicTree() {
        nextQuery();
        neighbors.reset(8);
        dynQuery.nearest(qx[qi], qy[qi], qz[qi], neighbors);
        return neighbors.size();
    }

    @Benchmark
    public int nearestGrid() {
        nextQuery();
        neighbors.reset(8);
        gridQuery.nearest(qx[qi], qy[qi], qz[qi], neighbors);
        return neighbors.size();
    }

    @Benchmark
    public int nearestOctree() {
        nextQuery();
        neighbors.reset(8);
        octQuery.nearest(qx[qi], qy[qi], qz[qi], neighbors);
        return neighbors.size();
    }

    // ---------------------------------------------------------------- moving one object by up to 1.5 units

    private int moveTarget() {
        int i = cursor;
        cursor = cursor + 1 == count ? 0 : cursor + 1;
        cx[i] += (float) (rnd.nextDouble() - 0.5) * 3f;
        cy[i] += (float) (rnd.nextDouble() - 0.5) * 3f;
        cz[i] += (float) (rnd.nextDouble() - 0.5) * 3f;
        return i;
    }

    @Benchmark
    public boolean moveDynamicTree() {
        int i = moveTarget();
        float h = half[i];
        return dyn.move(dynHandle[i], cx[i] - h, cy[i] - h, cz[i] - h, cx[i] + h, cy[i] + h, cz[i] + h, 0f, 0f, 0f);
    }

    @Benchmark
    public boolean moveGrid() {
        int i = moveTarget();
        float h = half[i];
        return grid.move(gridHandle[i], cx[i] - h, cy[i] - h, cz[i] - h, cx[i] + h, cy[i] + h, cz[i] + h);
    }

    @Benchmark
    public boolean moveOctree() {
        int i = moveTarget();
        float h = half[i];
        return octree.move(octHandle[i], cx[i] - h, cy[i] - h, cz[i] - h, cx[i] + h, cy[i] + h, cz[i] + h);
    }

    /** Refit after every object moved (the static BVH's only update path): the cost for all {@code count} objects at once. */
    @Benchmark
    public void bvhRefit() {
        bvh.refit(bounds);
    }
}
