package vmath.spatial;

import java.util.Arrays;
import vmath.bulk.BoundsArray;

/**
 * A bounding-volume hierarchy over the boxes of a {@link BoundsArray}, built with the binned
 * surface-area heuristic.
 *
 * <p><b>Layout.</b> Nodes live in parallel arrays in depth-first order, so the left child of an
 * internal node {@code i} is always {@code i + 1} and only the right child is stored. Each node
 * also records the contiguous range of {@link #order()} that holds all primitives beneath it, which
 * lets a query accept a whole subtree at once. There are no node objects: traversal reads
 * {@code float[]} and {@code int[]} only, and a node costs 24 bytes of bounds plus 12 bytes of
 * links.
 *
 * <p><b>Updates.</b> A moved object only needs {@link #refit}, an O(n) bottom-up bounds
 * recomputation that keeps the topology. Rebuild when objects have moved far enough that quality
 * ({@link #sahCost}) degrades.
 *
 * <p>Instances are immutable except for {@link #refit}, and safe to query from many threads, each
 * with its own {@link BvhQuery}.
 *
 * <p><b>Thread safety.</b> Immutable except for {@link #refit}. It is safe to query from many
 * threads, each with its own {@link BvhQuery}, as long as nobody calls {@code refit} meanwhile.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * BoundsArray bounds = new BoundsArray(1000);
 * bounds.add(-1f, -1f, -1f, 1f, 1f, 1f);
 * StaticBvh bvh = StaticBvh.build(bounds);                                    // binned SAH, at most 4 primitives per leaf
 * bounds.set(0, Aabbf.of(new Vec3f(5f, 0f, 0f), new Vec3f(6f, 1f, 1f)));
 * bvh.refit(bounds);                                                          // the boxes moved, the tree shape stays
 * }</pre>
 */
public final class StaticBvh {

    private static final int BINS = 16;

    private final float[] nodeBounds;
    private final int[] right;
    private final int[] first;
    private final int[] count;
    private final int[] order;
    private final int nodeCount;
    private final int maxLeafSize;

    private StaticBvh(float[] nodeBounds, int[] right, int[] first, int[] count, int[] order, int nodeCount,
                      int maxLeafSize) {
        this.nodeBounds = nodeBounds;
        this.right = right;
        this.first = first;
        this.count = count;
        this.order = order;
        this.nodeCount = nodeCount;
        this.maxLeafSize = maxLeafSize;
    }

    // ------------------------------------------------------------------ accessors

    /**
     * Counts the nodes of the tree, where the root is node zero.
     *
     * <p>Zero for a tree over no primitives.
     *
     * @return the number of nodes; node 0 is the root
     */
    public int nodeCount() {
        return nodeCount;
    }

    /**
     * Counts the primitives that the tree was built over.
     *
     * @return number of primitives the tree was built over
     */
    public int primitiveCount() {
        return order.length;
    }

    /**
     * Exposes the largest number of primitives that a leaf may hold.
     *
     * @return the largest number of primitives a leaf may hold, as given to the builder
     */
    public int maxLeafSize() {
        return maxLeafSize;
    }

    /**
     * Returns whether {@code node} is a leaf; a leaf holds {@code primitiveCount(node)} primitives
     * starting at {@code firstPrimitive(node)} in {@link #order()}.
     *
     * @param node the node index
     * @return {@code true} if {@code node} is a leaf; a leaf holds {@code primitiveCount(node)}
     *     primitives starting at {@code firstPrimitive(node)} in {@link #order()}
     */
    public boolean isLeaf(int node) {
        return right[node] == 0;
    }

    /**
     * Reads the right child of an internal node; the left child directly follows its parent in
     * memory.
     *
     * @param node the node index
     * @return right child of an internal node (its left child is {@code node + 1})
     */
    public int rightChild(int node) {
        return right[node];
    }

    /**
     * Reads where the primitives of a node start in the ordering.
     *
     * @param node the node index
     * @return start of the node's primitives in {@link #order()}
     */
    public int firstPrimitive(int node) {
        return first[node];
    }

    /**
     * Counts the primitives under a node.
     *
     * @param node the node index
     * @return number of primitives under the node
     */
    public int primitiveCount(int node) {
        return count[node];
    }

    /**
     * Exposes the primitive order in which every node's primitives are contiguous, as a live array.
     *
     * <p>Live array, do not modify.
     *
     * @return primitive indices arranged so that every node's primitives are contiguous
     */
    public int[] order() {
        return order;
    }

    /**
     * Exposes the node bounds as a flat live array, six floats per node.
     *
     * <p>Live array, do not modify.
     *
     * @return node bounds, six floats per node: {@code minX, minY, minZ, maxX, maxY, maxZ}
     */
    public float[] nodeBounds() {
        return nodeBounds;
    }

    /**
     * Measures the depth of the tree, which bounds the traversal stack.
     *
     * @return longest root-to-leaf path, in nodes
     */
    public int depth() {
        int[] depthOf = new int[nodeCount];
        int max = 0;
        for (int i = 0; i < nodeCount; i++) {
            int d = depthOf[i] + 1;
            max = Math.max(max, d);
            if (right[i] != 0) {
                depthOf[i + 1] = d;
                depthOf[right[i]] = d;
            }
        }
        return max;
    }

    /**
     * Computes the surface area heuristic cost of the tree, a quality measure that predicts the
     * cost of random ray queries.
     *
     * <p>Lower is better; compare it before and after {@link #refit} to decide when to rebuild.
     *
     * @return expected cost of a random ray query relative to the root: the sum of node surface
     *     areas weighted by cost (1 per node visited, 1 per primitive test in a leaf), divided by
     *     the root area
     */
    public float sahCost() {
        if (nodeCount == 0) {
            return 0f;
        }
        double rootArea = area(nodeBounds, 0);
        if (rootArea <= 0.0) {
            return 0f;
        }
        double cost = 0.0;
        for (int i = 0; i < nodeCount; i++) {
            double a = area(nodeBounds, i * 6);
            cost += a * (right[i] == 0 ? 1.0 + count[i] : 1.0);
        }
        return (float) (cost / rootArea);
    }

    // ------------------------------------------------------------------ refit

    /**
     * Recomputes every node's bounds from {@code bounds} without changing the tree shape.
     *
     * <p>The array must have the same size as when the tree was built.
     *
     * @param bounds the bounds; must not be {@code null}
     * @throws IllegalArgumentException if {@code bounds} does not have the size of the build
     */
    public void refit(BoundsArray bounds) {
        if (bounds.size() != order.length) {
            throw new IllegalArgumentException("bounds size " + bounds.size() + " differs from build size " + order.length);
        }
        float[] x0 = bounds.minXs(), y0 = bounds.minYs(), z0 = bounds.minZs();
        float[] x1 = bounds.maxXs(), y1 = bounds.maxYs(), z1 = bounds.maxZs();
        for (int node = nodeCount - 1; node >= 0; node--) {
            int o = node * 6;
            if (right[node] == 0) {
                float a0 = Float.POSITIVE_INFINITY, a1 = a0, a2 = a0;
                float b0 = Float.NEGATIVE_INFINITY, b1 = b0, b2 = b0;
                for (int k = first[node], end = k + count[node]; k < end; k++) {
                    int p = order[k];
                    a0 = Math.min(a0, x0[p]);
                    a1 = Math.min(a1, y0[p]);
                    a2 = Math.min(a2, z0[p]);
                    b0 = Math.max(b0, x1[p]);
                    b1 = Math.max(b1, y1[p]);
                    b2 = Math.max(b2, z1[p]);
                }
                nodeBounds[o] = a0;
                nodeBounds[o + 1] = a1;
                nodeBounds[o + 2] = a2;
                nodeBounds[o + 3] = b0;
                nodeBounds[o + 4] = b1;
                nodeBounds[o + 5] = b2;
            } else {
                int l = (node + 1) * 6, r = right[node] * 6;
                nodeBounds[o] = Math.min(nodeBounds[l], nodeBounds[r]);
                nodeBounds[o + 1] = Math.min(nodeBounds[l + 1], nodeBounds[r + 1]);
                nodeBounds[o + 2] = Math.min(nodeBounds[l + 2], nodeBounds[r + 2]);
                nodeBounds[o + 3] = Math.max(nodeBounds[l + 3], nodeBounds[r + 3]);
                nodeBounds[o + 4] = Math.max(nodeBounds[l + 4], nodeBounds[r + 4]);
                nodeBounds[o + 5] = Math.max(nodeBounds[l + 5], nodeBounds[r + 5]);
            }
        }
    }

    // ------------------------------------------------------------------ build

    /**
     * Builds with leaves of at most 4 primitives.
     *
     * @param bounds the bounds; must not be {@code null}
     * @return the tree, never {@code null}
     */
    public static StaticBvh build(BoundsArray bounds) {
        return build(bounds, 4);
    }

    /**
     * Builds the tree top-down with the binned surface area heuristic: centroids are binned along
     * the widest axis and the split with the lowest cost wins; build time is roughly linear times
     * logarithmic in the primitive count.
     *
     * <p>O(n log n) time; allocates the result arrays plus O(n) scratch, so build outside the frame
     * loop.
     *
     * @param bounds the bounds; must not be {@code null}
     * @param maxLeafSize the max leaf size
     * @return top-down binned SAH build: at each node the centroids are binned along their widest
     *     axis into 16 bins, and the split plane with the lowest surface-area cost wins
     * @throws IllegalArgumentException if {@code maxLeafSize} is below 1
     */
    public static StaticBvh build(BoundsArray bounds, int maxLeafSize) {
        if (maxLeafSize < 1) {
            throw new IllegalArgumentException("maxLeafSize must be >= 1");
        }
        int n = bounds.size();
        if (n == 0) {
            return new StaticBvh(new float[0], new int[0], new int[0], new int[0], new int[0], 0, maxLeafSize);
        }
        float[] x0 = bounds.minXs(), y0 = bounds.minYs(), z0 = bounds.minZs();
        float[] x1 = bounds.maxXs(), y1 = bounds.maxYs(), z1 = bounds.maxZs();
        float[][] cen = new float[3][n];
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
            cen[0][i] = (x0[i] + x1[i]) * 0.5f;
            cen[1][i] = (y0[i] + y1[i]) * 0.5f;
            cen[2][i] = (z0[i] + z1[i]) * 0.5f;
        }
        int maxNodes = 2 * n - 1;
        float[] nb = new float[maxNodes * 6];
        int[] right = new int[maxNodes];
        int[] first = new int[maxNodes];
        int[] count = new int[maxNodes];
        int nodes = 0;

        int[] stack = new int[96];
        int sp = 0;
        stack[sp++] = 0;
        stack[sp++] = n;
        stack[sp++] = -1;

        int[] binCount = new int[BINS];
        float[] binBounds = new float[BINS * 6];
        float[] leftArea = new float[BINS];
        int[] leftCount = new int[BINS];

        while (sp > 0) {
            int parent = stack[--sp];
            int end = stack[--sp];
            int start = stack[--sp];
            int node = nodes++;
            if (parent >= 0) {
                right[parent] = node;
            }
            int cnt = end - start;
            first[node] = start;
            count[node] = cnt;

            // node bounds and centroid bounds
            float a0 = Float.POSITIVE_INFINITY, a1 = a0, a2 = a0;
            float b0 = Float.NEGATIVE_INFINITY, b1 = b0, b2 = b0;
            float c0 = a0, c1 = a0, c2 = a0;
            float d0 = b0, d1 = b0, d2 = b0;
            for (int k = start; k < end; k++) {
                int p = idx[k];
                a0 = Math.min(a0, x0[p]);
                a1 = Math.min(a1, y0[p]);
                a2 = Math.min(a2, z0[p]);
                b0 = Math.max(b0, x1[p]);
                b1 = Math.max(b1, y1[p]);
                b2 = Math.max(b2, z1[p]);
                c0 = Math.min(c0, cen[0][p]);
                c1 = Math.min(c1, cen[1][p]);
                c2 = Math.min(c2, cen[2][p]);
                d0 = Math.max(d0, cen[0][p]);
                d1 = Math.max(d1, cen[1][p]);
                d2 = Math.max(d2, cen[2][p]);
            }
            int o = node * 6;
            nb[o] = a0;
            nb[o + 1] = a1;
            nb[o + 2] = a2;
            nb[o + 3] = b0;
            nb[o + 4] = b1;
            nb[o + 5] = b2;

            if (cnt == 1) {
                continue; // leaf
            }
            float e0 = d0 - c0, e1 = d1 - c1, e2 = d2 - c2;
            int axis = e0 >= e1 && e0 >= e2 ? 0 : e1 >= e2 ? 1 : 2;
            float cMin = axis == 0 ? c0 : axis == 1 ? c1 : c2;
            float extent = axis == 0 ? e0 : axis == 1 ? e1 : e2;

            int mid;
            if (extent <= 0f) {
                // all centroids coincide: no spatial split exists
                if (cnt <= maxLeafSize) {
                    continue;
                }
                mid = start + cnt / 2;
            } else {
                float[] ca = cen[axis];
                float scale = BINS / extent;
                Arrays.fill(binCount, 0);
                for (int i = 0; i < BINS; i++) {
                    binBounds[i * 6] = Float.POSITIVE_INFINITY;
                    binBounds[i * 6 + 1] = Float.POSITIVE_INFINITY;
                    binBounds[i * 6 + 2] = Float.POSITIVE_INFINITY;
                    binBounds[i * 6 + 3] = Float.NEGATIVE_INFINITY;
                    binBounds[i * 6 + 4] = Float.NEGATIVE_INFINITY;
                    binBounds[i * 6 + 5] = Float.NEGATIVE_INFINITY;
                }
                for (int k = start; k < end; k++) {
                    int p = idx[k];
                    int bin = Math.min(BINS - 1, (int) ((ca[p] - cMin) * scale));
                    binCount[bin]++;
                    int bo = bin * 6;
                    binBounds[bo] = Math.min(binBounds[bo], x0[p]);
                    binBounds[bo + 1] = Math.min(binBounds[bo + 1], y0[p]);
                    binBounds[bo + 2] = Math.min(binBounds[bo + 2], z0[p]);
                    binBounds[bo + 3] = Math.max(binBounds[bo + 3], x1[p]);
                    binBounds[bo + 4] = Math.max(binBounds[bo + 4], y1[p]);
                    binBounds[bo + 5] = Math.max(binBounds[bo + 5], z1[p]);
                }
                // sweep from the left: area and count of bins [0..i]
                float m0 = Float.POSITIVE_INFINITY, m1 = m0, m2 = m0;
                float n0 = Float.NEGATIVE_INFINITY, n1 = n0, n2 = n0;
                int running = 0;
                for (int i = 0; i < BINS - 1; i++) {
                    int bo = i * 6;
                    m0 = Math.min(m0, binBounds[bo]);
                    m1 = Math.min(m1, binBounds[bo + 1]);
                    m2 = Math.min(m2, binBounds[bo + 2]);
                    n0 = Math.max(n0, binBounds[bo + 3]);
                    n1 = Math.max(n1, binBounds[bo + 4]);
                    n2 = Math.max(n2, binBounds[bo + 5]);
                    running += binCount[i];
                    leftArea[i] = area(m0, m1, m2, n0, n1, n2);
                    leftCount[i] = running;
                }
                // sweep from the right and pick the cheapest plane
                m0 = Float.POSITIVE_INFINITY;
                m1 = m0;
                m2 = m0;
                n0 = Float.NEGATIVE_INFINITY;
                n1 = n0;
                n2 = n0;
                int rightRunning = 0;
                float best = Float.POSITIVE_INFINITY;
                int bestPlane = -1;
                for (int i = BINS - 1; i > 0; i--) {
                    int bo = i * 6;
                    m0 = Math.min(m0, binBounds[bo]);
                    m1 = Math.min(m1, binBounds[bo + 1]);
                    m2 = Math.min(m2, binBounds[bo + 2]);
                    n0 = Math.max(n0, binBounds[bo + 3]);
                    n1 = Math.max(n1, binBounds[bo + 4]);
                    n2 = Math.max(n2, binBounds[bo + 5]);
                    rightRunning += binCount[i];
                    int lc = leftCount[i - 1];
                    if (lc == 0 || rightRunning == 0) {
                        continue;
                    }
                    float cost = leftArea[i - 1] * lc + area(m0, m1, m2, n0, n1, n2) * rightRunning;
                    if (cost < best) {
                        best = cost;
                        bestPlane = i;
                    }
                }
                if (bestPlane < 0) {
                    if (cnt <= maxLeafSize) {
                        continue;
                    }
                    mid = start + cnt / 2;
                } else {
                    float nodeArea = area(a0, a1, a2, b0, b1, b2);
                    float splitCost = nodeArea + best; // one traversal step plus expected primitive tests
                    if (cnt <= maxLeafSize && splitCost >= nodeArea * cnt) {
                        continue; // cheaper to test the primitives directly
                    }
                    // partition idx[start, end) so bins below bestPlane come first
                    int i = start;
                    int j = end - 1;
                    while (i <= j) {
                        int bin = Math.min(BINS - 1, (int) ((ca[idx[i]] - cMin) * scale));
                        if (bin < bestPlane) {
                            i++;
                        } else {
                            int t = idx[i];
                            idx[i] = idx[j];
                            idx[j] = t;
                            j--;
                        }
                    }
                    mid = i;
                    if (mid == start || mid == end) {
                        mid = start + cnt / 2;
                    }
                }
            }

            if (sp + 6 > stack.length) {
                stack = Arrays.copyOf(stack, stack.length * 2);
            }
            // push right first so the left child is processed next and gets index node + 1
            stack[sp++] = mid;
            stack[sp++] = end;
            stack[sp++] = node;
            stack[sp++] = start;
            stack[sp++] = mid;
            stack[sp++] = -1;
            // mark internal until the right child is created (0 would mean leaf)
            right[node] = -1;
        }
        return new StaticBvh(Arrays.copyOf(nb, nodes * 6), Arrays.copyOf(right, nodes), Arrays.copyOf(first, nodes),
                Arrays.copyOf(count, nodes), idx, nodes, maxLeafSize);
    }

    private static float area(float[] b, int o) {
        return area(b[o], b[o + 1], b[o + 2], b[o + 3], b[o + 4], b[o + 5]);
    }

    /**
     * Surface area; zero for an empty box.
     */
    static float area(float x0, float y0, float z0, float x1, float y1, float z1) {
        float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        if (dx < 0f || dy < 0f || dz < 0f) {
            return 0f;
        }
        return 2f * (dx * dy + dy * dz + dz * dx);
    }
}
