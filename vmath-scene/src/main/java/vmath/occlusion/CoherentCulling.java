package vmath.occlusion;

import java.util.Arrays;
import java.util.function.IntConsumer;
import vmath.annotations.Experimental;
import vmath.geo.Frustumf;
import vmath.spatial.StaticBvh;

/**
 * Coherent hierarchical occlusion culling (Bittner, Wimmer, Piringer and Purgathofer, 2004): a
 * traversal of a bounding volume hierarchy that uses the <em>last frame's</em> visibility to issue
 * as few occlusion queries as possible and never to wait for one.
 *
 * <p><b>The idea.</b> The tree is visited front to back by a priority queue of the distance to the
 * camera, so that the nearer objects are drawn first and hide the farther ones. A node that was
 * visible in the last frame is not queried: an inner node is simply opened, and a leaf is drawn at
 * once, with a query for it issued in the same step whose result only decides next frame (and, only
 * every {@link #setQueryInterval queryInterval} frames, so that the cost is spread over the frames).
 * A node that was <em>not</em> visible gets a query and waits: the traversal carries on with other
 * nodes, and when the result arrives, a node that is visible is opened (and its visibility is
 * propagated up to its ancestors, so that they are known to be visible next frame), while a hidden
 * node and everything under it is dropped. The queue of pending queries is polled as results become
 * ready and drained at the end, so the answer is complete although no single query is waited for
 * while other work is available.
 *
 * <p><b>What it gives.</b> {@link #cull} calls a consumer with the index of every object that has
 * to be drawn, in roughly front-to-back order, and the engine draws it. No visible object is ever
 * left out: the objects that are not drawn are outside the frustum, or were hidden by what had been
 * drawn before the query was issued (the depth buffer only grows, so they stay hidden). Objects may
 * be drawn that are hidden (a visible leaf is not tested every frame, and a query is conservative);
 * that costs time, not correctness.
 *
 * <p>A leaf of the tree holds up to a few objects ({@link StaticBvh}) and is queried as a whole.
 * The {@link VisibilityHistory} that is given to the constructor records every object that was drawn,
 * for the per-object half of temporal coherence.
 *
 * <p><b>Consistency of the tree.</b> The tree is the one that was built; if the objects move, {@link
 * StaticBvh#refit} it before the frame (the shape of the tree is kept, so the history of the nodes
 * stays meaningful), and rebuild it, then {@link #reset}, when the objects have moved so far that its
 * quality has gone.
 *
 * <p>The queries are those of {@link OcclusionQueries}; the library makes no graphics call. The
 * traversal is tested in {@code CoherentCullingTest} against a software renderer, which checks that
 * the image of the objects it draws is the image of all of them, frame after frame, and counts the
 * queries it saves against a query for every node.
 *
 * <p><b>Thread safety.</b> Not thread-safe: one culler per tree and per thread; the tree must not be
 * refitted during a call.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * StaticBvh bvh = StaticBvh.build(bounds);
 * VisibilityHistory history = new VisibilityHistory(bounds.size());
 * CoherentCulling culling = new CoherentCulling(bvh, history);
 * // each frame, with the engine's occlusion queries on the box of a node:
 * CoherentCulling.Stats stats = culling.cull(camera.frustum(), eye.x(), eye.y(), eye.z(), queries, object -> draw(object));
 * }</pre>
 */
@Experimental("new in 0.2: the temporal culling may change")
public final class CoherentCulling {

    /**
     * What one {@link #cull} did.
     *
     * @param nodesVisited the nodes taken from the queue of distances
     * @param frustumCulled the nodes (and with them their subtrees) outside the frustum
     * @param queries the occlusion queries issued
     * @param visibleResults the queries whose result was that something is visible
     * @param hiddenResults the queries whose result was that nothing is visible (a subtree dropped)
     * @param drawnLeaves the leaves whose objects were handed to the consumer
     * @param drawnObjects the objects handed to the consumer
     * @param assumedVisible the visible leaves that were drawn without a query in this frame
     */
    public record Stats(int nodesVisited, int frustumCulled, int queries, int visibleResults, int hiddenResults, int drawnLeaves, int drawnObjects, int assumedVisible) {
    }

    private final StaticBvh bvh;
    private final VisibilityHistory history;
    private final boolean[] visible;
    private final int[] lastVisited;
    private final int[] drawnIn;
    private final float[] planes = new float[24];
    private int frame;
    private int queryInterval = 3;
    private int minSamples = 1;

    private float[] heapKey = new float[64];
    private int[] heapNode = new int[64];
    private int heapSize;

    private int[] pendNode = new int[64];
    private int[] pendQuery = new int[64];
    private boolean[] pendDrawn = new boolean[64];
    private int pendHead;
    private int pendCount;

    /**
     * Makes a culler for a tree.
     *
     * @param bvh the tree; must not be {@code null}
     * @param history the history to keep up to date with the drawn objects, or {@code null} for none;
     *     its capacity must cover the objects of the tree
     * @throws IllegalArgumentException if the history is too small
     */
    public CoherentCulling(StaticBvh bvh, VisibilityHistory history) {
        this.bvh = bvh;
        if (history != null) {
            history.ensureCapacity(bvh.primitiveCount());
        }
        this.history = history;
        int n = bvh.nodeCount();
        visible = new boolean[n];
        lastVisited = new int[n];
        drawnIn = new int[n];
        reset();
    }

    /**
     * Forgets what the nodes were in the last frames: every node is taken as hidden, which is what
     * to do after the tree has been rebuilt or the camera has jumped (the next frame then queries
     * what it must).
     */
    public void reset() {
        Arrays.fill(visible, false);
        Arrays.fill(lastVisited, -10);
        Arrays.fill(drawnIn, -10);
    }

    /**
     * Sets how often a leaf that was visible is queried again.
     *
     * <p>A visible leaf is drawn without waiting for anything; its query is only to find out that
     * it has become hidden. Querying every frame finds out soonest and costs most; the
     * default of 3 asks about each visible leaf every third frame, spread by the index of the node so
     * that the queries do not all come at once.
     *
     * @param frames the interval in frames, at least 1
     * @throws IllegalArgumentException if it is below 1
     */
    public void setQueryInterval(int frames) {
        if (frames < 1) {
            throw new IllegalArgumentException("the interval must be at least 1: " + frames);
        }
        queryInterval = frames;
    }

    /**
     * Gives the interval between the queries of a visible leaf.
     *
     * @return frames
     */
    public int queryInterval() {
        return queryInterval;
    }

    /**
     * Sets the number of samples a query must see for the node to count as visible.
     *
     * @param samples at least 1; a larger number culls what shows only as a few pixels (not
     *     conservative: that is a decision to leave small things out)
     * @throws IllegalArgumentException if it is below 1
     */
    public void setMinimumSamples(int samples) {
        if (samples < 1) {
            throw new IllegalArgumentException("the minimum must be at least 1: " + samples);
        }
        minSamples = samples;
    }

    /**
     * Gives the number of the last frame.
     *
     * @return the frame number, 0 before the first {@code cull}, then 1, 2, ...
     */
    public int frame() {
        return frame;
    }

    /**
     * Tells whether a node was found visible in the last frame.
     *
     * @param node the node index of the tree
     * @return {@code true} if it was visible and visited in the last frame
     * @throws IndexOutOfBoundsException if the node is out of range
     */
    public boolean wasVisible(int node) {
        return visible[node] && lastVisited[node] == frame;
    }

    /**
     * Culls the tree for a frustum.
     *
     * @param frustum the view frustum; must not be {@code null}
     * @param eyeX the x of the camera, which orders the traversal
     * @param eyeY the y of the camera
     * @param eyeZ the z of the camera
     * @param queries the occlusion queries; must not be {@code null}
     * @param draw receives the index of every object to draw, nearest nodes first; must not be {@code null}
     * @return what was done
     */
    public Stats cull(Frustumf frustum, float eyeX, float eyeY, float eyeZ, OcclusionQueries queries, IntConsumer draw) {
        frustum.writeTo(planes, 0);
        return cull(planes, eyeX, eyeY, eyeZ, queries, draw);
    }

    /**
     * Culls the tree for six planes.
     *
     * @param inwardPlanes 24 floats: six planes {@code a, b, c, d} with the normal pointing into the
     *     frustum, so that a point is inside if {@code a x + b y + c z + d >= 0} for all six (the
     *     layout of {@link Frustumf#writeTo})
     * @param eyeX the x of the camera
     * @param eyeY the y of the camera
     * @param eyeZ the z of the camera
     * @param queries the occlusion queries; must not be {@code null}
     * @param draw receives the index of every object to draw, nearest nodes first; must not be {@code null}
     * @return what was done
     * @throws IllegalArgumentException if fewer than 24 plane values are given
     */
    public Stats cull(float[] inwardPlanes, float eyeX, float eyeY, float eyeZ, OcclusionQueries queries, IntConsumer draw) {
        if (inwardPlanes.length < 24) {
            throw new IllegalArgumentException("six planes need 24 values: " + inwardPlanes.length);
        }
        frame++;
        if (history != null) {
            history.beginFrame();
        }
        float[] b = bvh.nodeBounds();
        int[] order = bvh.order();
        int visited = 0, culled = 0, issued = 0, yes = 0, no = 0, leaves = 0, objects = 0, assumed = 0;
        heapSize = 0;
        pendHead = 0;
        pendCount = 0;
        push(0, distanceSquared(b, 0, eyeX, eyeY, eyeZ));
        while (heapSize > 0 || pendCount > 0) {
            // results that are in, or all of them when there is nothing else to do
            while (pendCount > 0 && (heapSize == 0 || queries.isReady(pendQuery[pendHead]))) {
                int node = pendNode[pendHead], query = pendQuery[pendHead];
                boolean drawn = pendDrawn[pendHead];
                pendHead = (pendHead + 1) % pendNode.length;
                pendCount--;
                if (queries.visibleSamples(query) >= minSamples) {
                    yes++;
                    pullUp(node);
                    if (!drawn) {
                        int[] counts = traverse(node, b, order, eyeX, eyeY, eyeZ, draw);
                        leaves += counts[0];
                        objects += counts[1];
                    }
                } else {
                    no++;
                }
            }
            if (heapSize == 0) {
                continue;
            }
            int node = popNode();
            visited++;
            if (!inside(inwardPlanes, b, node)) {
                culled++;
                continue;
            }
            boolean wasVisible = visible[node] && lastVisited[node] == frame - 1;
            visible[node] = false;
            lastVisited[node] = frame;
            boolean leaf = bvh.isLeaf(node);
            if (wasVisible && !leaf) {
                int[] counts = traverse(node, b, order, eyeX, eyeY, eyeZ, draw);
                leaves += counts[0];
                objects += counts[1];
                continue;
            }
            if (wasVisible) {
                // a leaf that was visible is drawn at once; its query, if it is due, only decides about next frame
                int[] counts = drawLeaf(node, order, draw);
                leaves += counts[0];
                objects += counts[1];
                if ((frame + node) % queryInterval == 0) {
                    enqueue(node, queries.issue(b[6 * node], b[6 * node + 1], b[6 * node + 2], b[6 * node + 3], b[6 * node + 4], b[6 * node + 5]), true);
                    issued++;
                } else {
                    pullUp(node);
                    assumed++;
                }
            } else {
                enqueue(node, queries.issue(b[6 * node], b[6 * node + 1], b[6 * node + 2], b[6 * node + 3], b[6 * node + 4], b[6 * node + 5]), false);
                issued++;
            }
        }
        if (history != null) {
            history.endFrame();
        }
        return new Stats(visited, culled, issued, yes, no, leaves, objects, assumed);
    }

    /** Marks a node and the ancestors that are not yet marked as visible in this frame. */
    private void pullUp(int node) {
        visible[node] = true;
        lastVisited[node] = frame;
        // the tree has no parent links of its own: they are made once, from the layout (the left child follows its parent, the right one is stored)
        int p = parentOf(node);
        while (p >= 0 && !visible[p]) {
            visible[p] = true;
            lastVisited[p] = frame;
            p = parentOf(p);
        }
    }

    private int[] parents;

    private int parentOf(int node) {
        if (parents == null) {
            parents = new int[bvh.nodeCount()];
            Arrays.fill(parents, -1);
            for (int n = 0; n < bvh.nodeCount(); n++) {
                if (!bvh.isLeaf(n)) {
                    parents[n + 1] = n;
                    parents[bvh.rightChild(n)] = n;
                }
            }
        }
        return parents[node];
    }

    private final int[] counts = new int[2];

    /** Opens a node: draws a leaf, or puts both children into the queue of distances. */
    private int[] traverse(int node, float[] b, int[] order, float eyeX, float eyeY, float eyeZ, IntConsumer draw) {
        if (bvh.isLeaf(node)) {
            return drawLeaf(node, order, draw);
        }
        int left = node + 1, right = bvh.rightChild(node);
        push(left, distanceSquared(b, left, eyeX, eyeY, eyeZ));
        push(right, distanceSquared(b, right, eyeX, eyeY, eyeZ));
        counts[0] = 0;
        counts[1] = 0;
        return counts;
    }

    private int[] drawLeaf(int node, int[] order, IntConsumer draw) {
        counts[0] = 0;
        counts[1] = 0;
        if (drawnIn[node] == frame) {
            return counts;
        }
        drawnIn[node] = frame;
        int first = bvh.firstPrimitive(node), n = bvh.primitiveCount(node);
        for (int k = 0; k < n; k++) {
            int object = order[first + k];
            draw.accept(object);
            if (history != null) {
                history.markVisible(object);
            }
        }
        counts[0] = 1;
        counts[1] = n;
        return counts;
    }

    private static boolean inside(float[] planes, float[] b, int node) {
        int o = 6 * node;
        for (int p = 0; p < 6; p++) {
            float a = planes[4 * p], bb = planes[4 * p + 1], c = planes[4 * p + 2], d = planes[4 * p + 3];
            float x = a >= 0f ? b[o + 3] : b[o], y = bb >= 0f ? b[o + 4] : b[o + 1], z = c >= 0f ? b[o + 5] : b[o + 2];
            if (a * x + bb * y + c * z + d < 0f) {
                return false;
            }
        }
        return true;
    }

    private static float distanceSquared(float[] b, int node, float x, float y, float z) {
        int o = 6 * node;
        float dx = Math.max(Math.max(b[o] - x, x - b[o + 3]), 0f), dy = Math.max(Math.max(b[o + 1] - y, y - b[o + 4]), 0f), dz = Math.max(Math.max(b[o + 2] - z, z - b[o + 5]), 0f);
        return dx * dx + dy * dy + dz * dz;
    }

    // ---------------------------------------------------------------- the queue of pending queries

    private void enqueue(int node, int query, boolean drawn) {
        if (pendCount == pendNode.length) {
            int n = pendNode.length * 2;
            int[] nn = new int[n], nq = new int[n];
            boolean[] nd = new boolean[n];
            for (int i = 0; i < pendCount; i++) {
                int j = (pendHead + i) % pendNode.length;
                nn[i] = pendNode[j];
                nq[i] = pendQuery[j];
                nd[i] = pendDrawn[j];
            }
            pendNode = nn;
            pendQuery = nq;
            pendDrawn = nd;
            pendHead = 0;
        }
        int at = (pendHead + pendCount) % pendNode.length;
        pendNode[at] = node;
        pendQuery[at] = query;
        pendDrawn[at] = drawn;
        pendCount++;
    }

    // ---------------------------------------------------------------- the queue of distances: a binary min-heap

    private void push(int node, float key) {
        if (heapSize == heapKey.length) {
            heapKey = Arrays.copyOf(heapKey, heapSize * 2);
            heapNode = Arrays.copyOf(heapNode, heapSize * 2);
        }
        int i = heapSize++;
        while (i > 0) {
            int parent = (i - 1) >> 1;
            if (heapKey[parent] <= key) {
                break;
            }
            heapKey[i] = heapKey[parent];
            heapNode[i] = heapNode[parent];
            i = parent;
        }
        heapKey[i] = key;
        heapNode[i] = node;
    }

    private int popNode() {
        int top = heapNode[0];
        heapSize--;
        if (heapSize > 0) {
            float key = heapKey[heapSize];
            int node = heapNode[heapSize];
            int i = 0;
            while (true) {
                int child = 2 * i + 1;
                if (child >= heapSize) {
                    break;
                }
                if (child + 1 < heapSize && heapKey[child + 1] < heapKey[child]) {
                    child++;
                }
                if (heapKey[child] >= key) {
                    break;
                }
                heapKey[i] = heapKey[child];
                heapNode[i] = heapNode[child];
                i = child;
            }
            heapKey[i] = key;
            heapNode[i] = node;
        }
        return top;
    }
}
