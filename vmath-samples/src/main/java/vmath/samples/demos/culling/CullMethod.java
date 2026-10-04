package vmath.samples.demos.culling;

import java.util.concurrent.Executor;
import vmath.bulk.BoundsArray;
import vmath.bulk.IntList;
import vmath.bulk.VisibilitySet;
import vmath.camera.Cameraf;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;
import vmath.geo.Frustumf;
import vmath.spatial.BvhQuery;
import vmath.spatial.CullContext;
import vmath.spatial.CullPipeline;
import vmath.spatial.CullStages;
import vmath.spatial.DynamicAabbTree;
import vmath.spatial.FrustumKernel;
import vmath.spatial.FrustumKernels;
import vmath.spatial.LooseOctree;
import vmath.spatial.ParallelFrustumKernel;
import vmath.spatial.StaticBvh;
import vmath.spatial.UniformGrid;

/**
 * One way of finding the boxes that are inside the camera's frustum, behind one interface: the
 * four batch kernels over the whole array (scalar, SIMD, parallel) and the four spatial structures
 * that avoid looking at most of it (static BVH, dynamic AABB tree, loose octree, uniform grid).
 *
 * <p>A method owns its structure. {@link #build} builds it once from the boxes, {@link #cull} fills
 * a visibility set with the boxes inside the frustum, and {@link #moved} brings the structure up to
 * date after the boxes in an index range moved. All methods return exactly the boxes that the
 * scalar kernel returns, or a superset of them; the demo checks this.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: a method owns scratch memory and is used by the render
 * thread only.
 */
abstract class CullMethod {

    private final String name;

    CullMethod(String name) {
        this.name = name;
    }

    /**
     * Reads the name of the method.
     *
     * @return the name shown in the report and the display; never {@code null}
     */
    final String name() {
        return name;
    }

    /**
     * Builds the structure from the boxes, replacing a previous one.
     *
     * @param bounds the boxes; must not be {@code null}
     */
    void build(BoundsArray bounds) {
    }

    /**
     * Finds the boxes inside the camera's frustum.
     *
     * @param camera the camera; must not be {@code null}
     * @param viewportHeight the height of the viewport in pixels
     * @param bounds the boxes; must not be {@code null}
     * @param visible receives the result, whose previous content is replaced; must not be
     *     {@code null}
     * @return the number of boxes in the result
     */
    abstract int cull(Cameraf camera, int viewportHeight, BoundsArray bounds, VisibilitySet visible);

    /**
     * Brings the structure up to date after the boxes {@code from} to {@code to - 1} moved
     * vertically by {@code dy}.
     *
     * @param bounds the boxes with their new positions; must not be {@code null}
     * @param from the first moved index
     * @param to one past the last moved index
     * @param dy the largest vertical displacement, as a hint for structures that keep spare room
     */
    void moved(BoundsArray bounds, int from, int to, float dy) {
    }

    /**
     * Releases threads or memory that the method holds.
     */
    void close() {
    }

    /**
     * Tells whether this method builds a structure whose build time is worth reporting.
     *
     * @return {@code true} for the spatial structures, {@code false} for the batch kernels
     */
    boolean hasStructure() {
        return false;
    }

    // ------------------------------------------------------------------------------ the kernels

    /**
     * A batch kernel over the whole array, run through the library's cull pipeline.
     */
    static final class Kernel extends CullMethod {

        private final FrustumKernel kernel;
        private final CullPipeline pipeline;
        private final Runnable onClose;

        Kernel(String name, FrustumKernel kernel, Runnable onClose) {
            super(name);
            this.kernel = kernel;
            this.pipeline = CullPipeline.of(new CullStages.Frustum(kernel));
            this.onClose = onClose;
        }

        static Kernel scalar() {
            return new Kernel("scalar", FrustumKernels.scalar(), () -> { });
        }

        static Kernel best() {
            FrustumKernel k = FrustumKernels.best();
            return new Kernel(k.name(), k, () -> { });
        }

        static Kernel parallel(Executor executor, int parts, Runnable onClose) {
            return new Kernel("parallel x" + parts, new ParallelFrustumKernel(executor, parts), onClose);
        }

        @Override
        int cull(Cameraf camera, int viewportHeight, BoundsArray bounds, VisibilitySet visible) {
            CullContext ctx = CullContext.perspective(camera.frustum(), camera.position(), camera.fovy(), viewportHeight);
            return pipeline.run(ctx, bounds, visible);
        }

        @Override
        void close() {
            onClose.run();
        }

        FrustumKernel kernel() {
            return kernel;
        }
    }

    // ------------------------------------------------------------------------------ the structures

    /**
     * The static BVH: built once with a binned SAH, queried top down with whole subtrees accepted
     * and rejected, and refitted bottom up when boxes move (the shape of the tree stays).
     */
    static final class Bvh extends CullMethod {

        private StaticBvh bvh;
        private BvhQuery query;
        private int depth;

        Bvh() {
            super("static BVH");
        }

        @Override
        boolean hasStructure() {
            return true;
        }

        @Override
        void build(BoundsArray bounds) {
            bvh = StaticBvh.build(bounds);
            query = new BvhQuery(bvh);
            depth = bvh.depth(); // walks the whole tree and allocates, so it is read once; refitting keeps the shape
        }

        @Override
        int cull(Cameraf camera, int viewportHeight, BoundsArray bounds, VisibilitySet visible) {
            visible.ensureCapacity(bounds.size());
            visible.clearAll();
            return query.frustum(camera.frustum(), bounds, visible);
        }

        @Override
        void moved(BoundsArray bounds, int from, int to, float dy) {
            bvh.refit(bounds);
        }

        StaticBvh tree() {
            return bvh;
        }

        int depth() {
            return depth;
        }
    }

    /**
     * The dynamic AABB tree: boxes are inserted one by one into a tree that balances itself, each
     * stored with a little spare room so that a small move needs no change.
     */
    static final class Dynamic extends CullMethod {

        private DynamicAabbTree tree;
        private DynamicAabbTree.Query query;
        private int[] handles = new int[0];

        Dynamic() {
            super("dynamic tree");
        }

        @Override
        boolean hasStructure() {
            return true;
        }

        @Override
        void build(BoundsArray bounds) {
            tree = new DynamicAabbTree(0.4f, bounds.size());
            query = tree.newQuery();
            handles = new int[bounds.size()];
            for (int i = 0; i < bounds.size(); i++) {
                handles[i] = tree.insert(bounds.minX(i), bounds.minY(i), bounds.minZ(i), bounds.maxX(i), bounds.maxY(i), bounds.maxZ(i), i);
            }
        }

        @Override
        int cull(Cameraf camera, int viewportHeight, BoundsArray bounds, VisibilitySet visible) {
            visible.ensureCapacity(bounds.size());
            visible.clearAll();
            return query.frustum(camera.frustum(), visible);
        }

        @Override
        void moved(BoundsArray bounds, int from, int to, float dy) {
            for (int i = from; i < to; i++) {
                tree.move(handles[i], bounds.minX(i), bounds.minY(i), bounds.minZ(i), bounds.maxX(i), bounds.maxY(i), bounds.maxZ(i), 0f, dy, 0f);
            }
        }
    }

    /**
     * The loose octree. It has no frustum query: the method asks it for everything that overlaps
     * the box around the frustum and tests those candidates against the six planes.
     */
    static final class Octree extends CullMethod {

        private LooseOctree tree;
        private LooseOctree.Query query;
        private int[] handles = new int[0];
        private final IntList candidates = new IntList(1024);
        private final float[] planes = new float[24];
        private final float[] box = new float[6];

        Octree() {
            super("loose octree");
        }

        @Override
        boolean hasStructure() {
            return true;
        }

        @Override
        void build(BoundsArray bounds) {
            Aabbf world = bounds.union();
            float cx = (world.minX() + world.maxX()) * 0.5f, cy = (world.minY() + world.maxY()) * 0.5f, cz = (world.minZ() + world.maxZ()) * 0.5f;
            float half = Math.max(world.maxX() - world.minX(), Math.max(world.maxY() - world.minY(), world.maxZ() - world.minZ())) * 0.5f + 1f;
            tree = new LooseOctree(cx, cy, cz, half, 10);
            query = tree.newQuery();
            handles = new int[bounds.size()];
            for (int i = 0; i < bounds.size(); i++) {
                handles[i] = tree.insert(bounds.minX(i), bounds.minY(i), bounds.minZ(i), bounds.maxX(i), bounds.maxY(i), bounds.maxZ(i), i);
            }
        }

        @Override
        int cull(Cameraf camera, int viewportHeight, BoundsArray bounds, VisibilitySet visible) {
            visible.ensureCapacity(bounds.size());
            visible.clearAll();
            frustumBox(camera, box);
            candidates.clear();
            query.overlapAabb(new Aabbf(box[0], box[1], box[2], box[3], box[4], box[5]), candidates);
            return filter(camera.frustum(), candidates, bounds, visible, planes);
        }

        @Override
        void moved(BoundsArray bounds, int from, int to, float dy) {
            for (int i = from; i < to; i++) {
                tree.move(handles[i], bounds.minX(i), bounds.minY(i), bounds.minZ(i), bounds.maxX(i), bounds.maxY(i), bounds.maxZ(i));
            }
        }
    }

    /**
     * The uniform grid, used like the octree: the candidates of the box around the frustum are
     * tested against the planes.
     */
    static final class Grid extends CullMethod {

        private UniformGrid grid;
        private UniformGrid.Query query;
        private int[] handles = new int[0];
        private final IntList candidates = new IntList(1024);
        private final float[] planes = new float[24];
        private final float[] box = new float[6];

        Grid() {
            super("uniform grid");
        }

        @Override
        boolean hasStructure() {
            return true;
        }

        @Override
        void build(BoundsArray bounds) {
            grid = new UniformGrid(12f, bounds.size());
            query = grid.newQuery();
            handles = new int[bounds.size()];
            for (int i = 0; i < bounds.size(); i++) {
                handles[i] = grid.insert(bounds.minX(i), bounds.minY(i), bounds.minZ(i), bounds.maxX(i), bounds.maxY(i), bounds.maxZ(i), i);
            }
        }

        @Override
        int cull(Cameraf camera, int viewportHeight, BoundsArray bounds, VisibilitySet visible) {
            visible.ensureCapacity(bounds.size());
            visible.clearAll();
            frustumBox(camera, box);
            candidates.clear();
            query.overlapAabb(new Aabbf(box[0], box[1], box[2], box[3], box[4], box[5]), candidates);
            return filter(camera.frustum(), candidates, bounds, visible, planes);
        }

        @Override
        void moved(BoundsArray bounds, int from, int to, float dy) {
            for (int i = from; i < to; i++) {
                grid.move(handles[i], bounds.minX(i), bounds.minY(i), bounds.minZ(i), bounds.maxX(i), bounds.maxY(i), bounds.maxZ(i));
            }
        }
    }

    // ------------------------------------------------------------------------------ helpers

    /**
     * Computes the axis-aligned box around a camera's frustum from its eight corners.
     *
     * @param camera the camera; must not be {@code null}
     * @param out receives {@code minX, minY, minZ, maxX, maxY, maxZ}
     */
    static void frustumBox(Cameraf camera, float[] out) {
        Vec3f p = camera.position(), f = camera.forward(), r = camera.right(), u = camera.up();
        float tan = (float) Math.tan(camera.fovy() * 0.5f);
        out[0] = out[1] = out[2] = Float.POSITIVE_INFINITY;
        out[3] = out[4] = out[5] = Float.NEGATIVE_INFINITY;
        for (int plane = 0; plane < 2; plane++) {
            float d = plane == 0 ? camera.near() : camera.far();
            float hh = tan * d, hw = hh * camera.aspect();
            for (int c = 0; c < 4; c++) {
                float sx = (c & 1) == 0 ? -1f : 1f, sy = (c & 2) == 0 ? -1f : 1f;
                float x = p.x() + f.x() * d + r.x() * sx * hw + u.x() * sy * hh;
                float y = p.y() + f.y() * d + r.y() * sx * hw + u.y() * sy * hh;
                float z = p.z() + f.z() * d + r.z() * sx * hw + u.z() * sy * hh;
                out[0] = Math.min(out[0], x);
                out[1] = Math.min(out[1], y);
                out[2] = Math.min(out[2], z);
                out[3] = Math.max(out[3], x);
                out[4] = Math.max(out[4], y);
                out[5] = Math.max(out[5], z);
            }
        }
    }

    /**
     * Keeps the candidates whose boxes are not entirely outside one of the six planes, with the
     * same test as the scalar kernel, and sets their bits.
     *
     * @param frustum the frustum
     * @param candidates the indices of the boxes to test
     * @param bounds the boxes
     * @param visible receives the bits of the boxes that pass
     * @param planes scratch of 24 floats
     * @return the number of boxes that passed
     */
    static int filter(Frustumf frustum, IntList candidates, BoundsArray bounds, VisibilitySet visible, float[] planes) {
        frustum.writeTo(planes, 0);
        float[] x0 = bounds.minXs(), y0 = bounds.minYs(), z0 = bounds.minZs();
        float[] x1 = bounds.maxXs(), y1 = bounds.maxYs(), z1 = bounds.maxZs();
        int[] list = candidates.array();
        int n = candidates.size(), passed = 0;
        for (int k = 0; k < n; k++) {
            int i = list[k];
            float cx = (x0[i] + x1[i]) * 0.5f, cy = (y0[i] + y1[i]) * 0.5f, cz = (z0[i] + z1[i]) * 0.5f;
            float hx = (x1[i] - x0[i]) * 0.5f, hy = (y1[i] - y0[i]) * 0.5f, hz = (z1[i] - z0[i]) * 0.5f;
            boolean inside = true;
            for (int p = 0; p < 24 && inside; p += 4) {
                float s = planes[p] * cx + planes[p + 1] * cy + planes[p + 2] * cz + planes[p + 3];
                float r = hx * Math.abs(planes[p]) + hy * Math.abs(planes[p + 1]) + hz * Math.abs(planes[p + 2]);
                inside = s + r >= 0f;
            }
            if (inside) {
                visible.set(i);
                passed++;
            }
        }
        return passed;
    }
}
