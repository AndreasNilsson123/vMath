package vmath.spatial;

import java.util.Arrays;
import vmath.bulk.BoundsArray;
import vmath.bulk.IntList;
import vmath.bulk.VisibilitySet;
import vmath.geo.Aabbf;
import vmath.geo.Frustumf;
import vmath.geo.Rayf;
import vmath.geo.Spheref;

/**
 * Queries against a {@link StaticBvh}. An instance owns the traversal stack, so create one per thread and reuse it:
 * queries allocate nothing once the stack has grown to the tree depth. Results go into caller-supplied buffers
 * ({@link VisibilitySet}, {@link IntList}, {@link BvhHit}).
 *
 * <p>Every query takes the {@link BoundsArray} the tree was built (or refitted) from; primitives are tested against
 * their own bounds at the leaves.
 */
public final class BvhQuery {

    /** Narrow-phase test for ray casts: the exact hit distance of primitive {@code p}, or +Infinity. */
    @FunctionalInterface
    public interface PrimitiveTest {
        /** The exact hit distance of {@code primitive} along {@code ray} if it is hit before {@code tMax}, else {@link Float#POSITIVE_INFINITY}. */
        float intersect(int primitive, Rayf ray, float tMax);
    }

    /** Nearest ray hit, written by the raycast methods. */
    public static final class BvhHit {
        /** Index of the hit primitive, or -1. */
        public int primitive = -1;
        /** Distance along the ray in units of its direction; +Infinity when nothing was hit. */
        public float t = Float.POSITIVE_INFINITY;

        /** A hit that records nothing yet ({@code primitive} -1, {@code t} infinite). */
        public BvhHit() {
        }

        void reset() {
            primitive = -1;
            t = Float.POSITIVE_INFINITY;
        }
    }

    private final StaticBvh bvh;
    private int[] stack = new int[128];
    private float[] tStack = new float[64];
    private final float[] planes = new float[24];

    /** A query object over {@code bvh}. It owns the traversal stacks, so use one per thread; the queries allocate nothing after the stacks have grown. */
    public BvhQuery(StaticBvh bvh) {
        this.bvh = bvh;
    }

    // ------------------------------------------------------------------ frustum

    /**
     * Sets the bit of every primitive that may be visible in {@code frustum} (bits of others are left as they are;
     * clear {@code out} first for a fresh result). Subtrees entirely inside are accepted without visiting their
     * leaves, and each node only tests the planes its parent could not already decide. Returns the number of
     * primitives accepted.
     */
    public int frustum(Frustumf frustum, BoundsArray bounds, VisibilitySet out) {
        if (bvh.nodeCount() == 0) {
            return 0;
        }
        frustum.writeTo(planes, 0);
        float[] nb = bvh.nodeBounds();
        int[] order = bvh.order();
        float[] x0 = bounds.minXs(), y0 = bounds.minYs(), z0 = bounds.minZs();
        float[] x1 = bounds.maxXs(), y1 = bounds.maxYs(), z1 = bounds.maxZs();
        long[] words = out.words();
        int accepted = 0;
        int sp = 0;
        stack[sp++] = 0;
        stack[sp++] = 0x3F;
        while (sp > 0) {
            int mask = stack[--sp];
            int node = stack[--sp];
            int m = NodeTests.frustumMask(planes, nb, node * 6, mask);
            if (m < 0) {
                continue;
            }
            if (m == 0) {
                int first = bvh.firstPrimitive(node);
                int n = bvh.primitiveCount(node);
                for (int k = first; k < first + n; k++) {
                    int p = order[k];
                    words[p >>> 6] |= 1L << p;
                }
                accepted += n;
            } else if (bvh.isLeaf(node)) {
                int first = bvh.firstPrimitive(node);
                for (int k = first, end = first + bvh.primitiveCount(node); k < end; k++) {
                    int p = order[k];
                    boolean visible = true;
                    for (int pl = 0; pl < 6 && visible; pl++) {
                        if ((m & (1 << pl)) == 0) {
                            continue;
                        }
                        float nx = planes[pl * 4], ny = planes[pl * 4 + 1], nz = planes[pl * 4 + 2], d = planes[pl * 4 + 3];
                        float px = nx >= 0f ? x1[p] : x0[p];
                        float py = ny >= 0f ? y1[p] : y0[p];
                        float pz = nz >= 0f ? z1[p] : z0[p];
                        visible = d + nx * px + ny * py + nz * pz >= 0f;
                    }
                    if (visible) {
                        words[p >>> 6] |= 1L << p;
                        accepted++;
                    }
                }
            } else {
                if (sp + 4 > stack.length) {
                    stack = Arrays.copyOf(stack, stack.length * 2);
                }
                stack[sp++] = bvh.rightChild(node);
                stack[sp++] = m;
                stack[sp++] = node + 1;
                stack[sp++] = m;
            }
        }
        return accepted;
    }

    // ------------------------------------------------------------------ rays

    /** Nearest primitive whose <b>bounding box</b> the ray enters within {@code [0, tMax]}. */
    public boolean raycastBounds(Rayf ray, float tMax, BoundsArray bounds, BvhHit hit) {
        return raycast(ray, tMax, bounds, null, hit);
    }

    /**
     * Nearest hit found by {@code test}, visiting nodes front to back and pruning everything beyond the best hit so
     * far. With a {@code null} test the primitives' bounding boxes are the geometry. Returns whether anything was
     * hit; the result is in {@code hit}.
     */
    public boolean raycast(Rayf ray, float tMax, BoundsArray bounds, PrimitiveTest test, BvhHit hit) {
        hit.reset();
        if (bvh.nodeCount() == 0) {
            return false;
        }
        float ox = ray.ox(), oy = ray.oy(), oz = ray.oz();
        float ix = NodeTests.inverse(ray.dx()), iy = NodeTests.inverse(ray.dy()), iz = NodeTests.inverse(ray.dz());
        float[] nb = bvh.nodeBounds();
        int[] order = bvh.order();
        float best = tMax;
        int bestPrim = -1;
        float rootT = NodeTests.entry(nb, 0, ox, oy, oz, ix, iy, iz, best);
        if (rootT == Float.POSITIVE_INFINITY) {
            return false;
        }
        int sp = 0;
        stack[sp] = 0;
        tStack[sp++] = rootT;
        while (sp > 0) {
            sp--;
            if (tStack[sp] > best) {
                continue;
            }
            int node = stack[sp];
            if (bvh.isLeaf(node)) {
                int first = bvh.firstPrimitive(node);
                for (int k = first, end = first + bvh.primitiveCount(node); k < end; k++) {
                    int p = order[k];
                    float t;
                    if (test == null) {
                        t = Intersections.rayBox(ox, oy, oz, ix, iy, iz, bounds, p, best);
                    } else {
                        t = test.intersect(p, ray, best);
                    }
                    if (t <= best && t != Float.POSITIVE_INFINITY) {
                        best = t;
                        bestPrim = p;
                    }
                }
            } else {
                int l = node + 1, r = bvh.rightChild(node);
                float tl = NodeTests.entry(nb, l * 6, ox, oy, oz, ix, iy, iz, best);
                float tr = NodeTests.entry(nb, r * 6, ox, oy, oz, ix, iy, iz, best);
                if (sp + 2 > tStack.length) {
                    tStack = Arrays.copyOf(tStack, tStack.length * 2);
                }
                if (sp + 2 > stack.length) {
                    stack = Arrays.copyOf(stack, stack.length * 2);
                }
                // push the farther child first so the nearer is popped next
                if (tl <= tr) {
                    if (tr != Float.POSITIVE_INFINITY) {
                        stack[sp] = r;
                        tStack[sp++] = tr;
                    }
                    if (tl != Float.POSITIVE_INFINITY) {
                        stack[sp] = l;
                        tStack[sp++] = tl;
                    }
                } else {
                    if (tl != Float.POSITIVE_INFINITY) {
                        stack[sp] = l;
                        tStack[sp++] = tl;
                    }
                    stack[sp] = r;
                    tStack[sp++] = tr;
                }
            }
        }
        if (bestPrim < 0) {
            return false;
        }
        hit.primitive = bestPrim;
        hit.t = best;
        return true;
    }

    // ------------------------------------------------------------------ nearest neighbours

    /**
     * The {@code out.k()} primitives whose boxes are closest to the point {@code (x, y, z)}, nearest first (ties by smaller
     * index). Depth-first, nearer child first, pruning every subtree that cannot beat the worst neighbour kept so far.
     */
    public void nearest(float x, float y, float z, BoundsArray bounds, Neighbors out) {
        out.reset();
        if (bvh.nodeCount() == 0) {
            return;
        }
        float[] nb = bvh.nodeBounds();
        int[] order = bvh.order();
        float[] x0 = bounds.minXs(), y0 = bounds.minYs(), z0 = bounds.minZs();
        float[] x1 = bounds.maxXs(), y1 = bounds.maxYs(), z1 = bounds.maxZs();
        int sp = 0;
        stack[sp] = 0;
        tStack[sp++] = 0f;
        while (sp > 0) {
            sp--;
            if (tStack[sp] > out.bound()) {
                continue;
            }
            int node = stack[sp];
            if (bvh.isLeaf(node)) {
                for (int k = bvh.firstPrimitive(node), end = k + bvh.primitiveCount(node); k < end; k++) {
                    int p = order[k];
                    out.offer(p, distanceSquared(x0[p], y0[p], z0[p], x1[p], y1[p], z1[p], x, y, z));
                }
            } else {
                int l = node + 1, r = bvh.rightChild(node);
                float dl = distanceSquared(nb[l * 6], nb[l * 6 + 1], nb[l * 6 + 2], nb[l * 6 + 3], nb[l * 6 + 4], nb[l * 6 + 5], x, y, z);
                float dr = distanceSquared(nb[r * 6], nb[r * 6 + 1], nb[r * 6 + 2], nb[r * 6 + 3], nb[r * 6 + 4], nb[r * 6 + 5], x, y, z);
                if (sp + 2 > tStack.length) {
                    tStack = Arrays.copyOf(tStack, tStack.length * 2);
                }
                if (sp + 2 > stack.length) {
                    stack = Arrays.copyOf(stack, stack.length * 2);
                }
                // farther child first, so the nearer one is popped next
                if (dl <= dr) {
                    stack[sp] = r;
                    tStack[sp++] = dr;
                    stack[sp] = l;
                    tStack[sp++] = dl;
                } else {
                    stack[sp] = l;
                    tStack[sp++] = dl;
                    stack[sp] = r;
                    tStack[sp++] = dr;
                }
            }
        }
        out.finish();
    }

    // ------------------------------------------------------------------ overlaps

    /** Appends every primitive whose bounds overlap {@code box} to {@code out}. */
    public void overlapAabb(Aabbf box, BoundsArray bounds, IntList out) {
        if (bvh.nodeCount() == 0) {
            return;
        }
        float[] nb = bvh.nodeBounds();
        int[] order = bvh.order();
        float[] x0 = bounds.minXs(), y0 = bounds.minYs(), z0 = bounds.minZs();
        float[] x1 = bounds.maxXs(), y1 = bounds.maxYs(), z1 = bounds.maxZs();
        int sp = 0;
        stack[sp++] = 0;
        while (sp > 0) {
            int node = stack[--sp];
            int o = node * 6;
            if (nb[o] > box.maxX() || nb[o + 3] < box.minX() || nb[o + 1] > box.maxY() || nb[o + 4] < box.minY()
                    || nb[o + 2] > box.maxZ() || nb[o + 5] < box.minZ()) {
                continue;
            }
            if (bvh.isLeaf(node)) {
                int first = bvh.firstPrimitive(node);
                for (int k = first, end = first + bvh.primitiveCount(node); k < end; k++) {
                    int p = order[k];
                    if (x0[p] <= box.maxX() && x1[p] >= box.minX() && y0[p] <= box.maxY() && y1[p] >= box.minY()
                            && z0[p] <= box.maxZ() && z1[p] >= box.minZ()) {
                        out.add(p);
                    }
                }
            } else {
                if (sp + 2 > stack.length) {
                    stack = Arrays.copyOf(stack, stack.length * 2);
                }
                stack[sp++] = bvh.rightChild(node);
                stack[sp++] = node + 1;
            }
        }
    }

    /** Appends every primitive whose bounds touch {@code sphere} to {@code out}. */
    public void overlapSphere(Spheref sphere, BoundsArray bounds, IntList out) {
        if (bvh.nodeCount() == 0) {
            return;
        }
        float[] nb = bvh.nodeBounds();
        int[] order = bvh.order();
        float[] x0 = bounds.minXs(), y0 = bounds.minYs(), z0 = bounds.minZs();
        float[] x1 = bounds.maxXs(), y1 = bounds.maxYs(), z1 = bounds.maxZs();
        float cx = sphere.cx(), cy = sphere.cy(), cz = sphere.cz();
        float r2 = sphere.radius() * sphere.radius();
        int sp = 0;
        stack[sp++] = 0;
        while (sp > 0) {
            int node = stack[--sp];
            int o = node * 6;
            if (distanceSquared(nb[o], nb[o + 1], nb[o + 2], nb[o + 3], nb[o + 4], nb[o + 5], cx, cy, cz) > r2) {
                continue;
            }
            if (bvh.isLeaf(node)) {
                int first = bvh.firstPrimitive(node);
                for (int k = first, end = first + bvh.primitiveCount(node); k < end; k++) {
                    int p = order[k];
                    if (distanceSquared(x0[p], y0[p], z0[p], x1[p], y1[p], z1[p], cx, cy, cz) <= r2) {
                        out.add(p);
                    }
                }
            } else {
                if (sp + 2 > stack.length) {
                    stack = Arrays.copyOf(stack, stack.length * 2);
                }
                stack[sp++] = bvh.rightChild(node);
                stack[sp++] = node + 1;
            }
        }
    }

    private static float distanceSquared(float x0, float y0, float z0, float x1, float y1, float z1,
                                         float px, float py, float pz) {
        float dx = Math.max(Math.max(x0 - px, 0f), px - x1);
        float dy = Math.max(Math.max(y0 - py, 0f), py - y1);
        float dz = Math.max(Math.max(z0 - pz, 0f), pz - z1);
        return dx * dx + dy * dy + dz * dz;
    }
}
