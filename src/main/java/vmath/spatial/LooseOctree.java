package vmath.spatial;

import java.util.Arrays;
import vmath.bulk.IntList;
import vmath.geo.Aabbf;
import vmath.geo.Spheref;

/**
 * A loose octree over object boxes. The world is a cube that is halved repeatedly; every node's <em>loose</em> box is twice
 * as wide as its cell, so an object is stored in exactly one node, chosen by its centre and size, however it straddles cell
 * boundaries. An object goes as deep as its size allows: a node at depth {@code d} takes objects whose largest half-extent
 * is at most the cell's half-width. That single placement is what makes updates cheap (no duplicate entries as in
 * {@link UniformGrid}) and lets the tree cope with objects of very different sizes.
 *
 * <p><b>Storage.</b> Nodes and objects are entries in plain arrays; children are created on demand and empty nodes are removed
 * again, so memory follows the objects, not the world size. An object is unlinked from its node in O(1), and {@link #move}
 * does nothing to the tree when the object still belongs to the same node.
 *
 * <p><b>Catch-all root.</b> An object whose centre lies outside the world cube is kept at the root, which every query checks
 * object by object. Objects therefore never get lost, but a world that is too small makes queries slow.
 *
 * <p>Each object has a stable integer handle and an {@code int} of user data, as in {@link DynamicAabbTree}. Not thread-safe
 * for updates; concurrent read-only queries are fine with one {@link Query} per thread.
 */
public final class LooseOctree {

    private static final int NONE = -1;
    /** Deepest level the constructor accepts. */
    public static final int MAX_DEPTH_LIMIT = 24;

    private final float rootX;
    private final float rootY;
    private final float rootZ;
    private final float rootHalf;
    private final int maxDepth;

    // nodes
    private float[] geom = new float[4 * 16];     // centre x y z, half width of the (tight) cell
    private int[] child = new int[8 * 16];
    private int[] parent = new int[16];
    private int[] firstObject = new int[16];
    private int[] objectCount = new int[16];
    private int[] childCount = new int[16];
    private int[] nodeDepth = new int[16];
    private int nodeHigh;
    private int freeNode = NONE;
    private int nodeCount;

    // objects
    private float[] box = new float[6 * 16];
    private int[] item = new int[16];
    private int[] objNode = new int[16];
    private int[] prev = new int[16];
    private int[] next = new int[16];
    private boolean[] alive = new boolean[16];
    private int objectHigh;
    private int freeObject = NONE;
    private int liveCount;

    /**
     * @param centerX  centre of the world cube
     * @param halfSize half the world's edge length; objects centred outside it stay at the root
     * @param maxDepth deepest level, {@code 1..MAX_DEPTH_LIMIT}; the smallest cell is {@code 2 * halfSize / 2^maxDepth} wide
     */
    public LooseOctree(float centerX, float centerY, float centerZ, float halfSize, int maxDepth) {
        if (!(halfSize > 0f) || Float.isInfinite(halfSize) || !Float.isFinite(centerX) || !Float.isFinite(centerY)
                || !Float.isFinite(centerZ)) {
            throw new IllegalArgumentException("the world must have a finite centre and a positive finite half size");
        }
        if (maxDepth < 1 || maxDepth > MAX_DEPTH_LIMIT) {
            throw new IllegalArgumentException("maxDepth must be in [1, " + MAX_DEPTH_LIMIT + "]: " + maxDepth);
        }
        this.rootX = centerX;
        this.rootY = centerY;
        this.rootZ = centerZ;
        this.rootHalf = halfSize;
        this.maxDepth = maxDepth;
        newNode(NONE, centerX, centerY, centerZ, halfSize, 0); // node 0 is the root and is never freed
    }

    /** A cube centred at the origin with 10 levels. */
    public LooseOctree(float halfSize) {
        this(0f, 0f, 0f, halfSize, 10);
    }

    /** The number of objects in the octree. */
    public int size() {
        return liveCount;
    }

    /** Nodes currently allocated (at least 1, the root). */
    public int nodeCount() {
        return nodeCount;
    }

    /** The depth limit the octree was built with: the root is level 0. */
    public int maxDepth() {
        return maxDepth;
    }

    // ---------------------------------------------------------------- updates

    /** Adds an object with the given box and returns its handle; {@code userData} is what queries report for it. */
    public int insert(Aabbf b, int userData) {
        return insert(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), userData);
    }

    /**
     * Adds an object with the box given by its six bounds and returns its handle; {@code userData} is what queries report for it. The box must be finite and ordered ({@link IllegalArgumentException} otherwise).
     */
    public int insert(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, int userData) {
        checkBox(minX, minY, minZ, maxX, maxY, maxZ);
        int h = allocateObject();
        setBox(h, minX, minY, minZ, maxX, maxY, maxZ);
        item[h] = userData;
        alive[h] = true;
        liveCount++;
        link(h, locate(minX, minY, minZ, maxX, maxY, maxZ, true));
        return h;
    }

    /** Removes the object; its handle becomes invalid (and may be reused by a later insert). */
    public void remove(int handle) {
        requireLive(handle);
        unlink(handle);
        alive[handle] = false;
        next[handle] = freeObject;
        freeObject = handle;
        liveCount--;
    }

    /**
     * Moves the object to a new box. Returns {@code true} if it changed nodes, {@code false} if it stayed in its node and only
     * the box was updated.
     */
    public boolean move(int handle, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        requireLive(handle);
        checkBox(minX, minY, minZ, maxX, maxY, maxZ);
        setBox(handle, minX, minY, minZ, maxX, maxY, maxZ);
        int target = locate(minX, minY, minZ, maxX, maxY, maxZ, false);
        if (target == objNode[handle]) {
            return false;
        }
        unlink(handle);
        link(handle, locate(minX, minY, minZ, maxX, maxY, maxZ, true));
        return true;
    }

    /** As the six-bounds {@code move}, with the box given as an {@link Aabbf}. */
    public boolean move(int handle, Aabbf b) {
        return move(handle, b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }

    /** Removes every object and every node except the root. Capacity is kept. */
    public void clear() {
        Arrays.fill(alive, false);
        objectHigh = 0;
        freeObject = NONE;
        liveCount = 0;
        nodeHigh = 0;
        freeNode = NONE;
        nodeCount = 0;
        newNode(NONE, rootX, rootY, rootZ, rootHalf, 0);
    }

    // ---------------------------------------------------------------- inspection

    /** True when {@code handle} names an object currently in the octree. */
    public boolean isValid(int handle) {
        return handle >= 0 && handle < objectHigh && alive[handle];
    }

    /** The {@code userData} given when the object was inserted; {@link IllegalArgumentException} for a handle that is not in the octree. */
    public int userData(int handle) {
        requireLive(handle);
        return item[handle];
    }

    /**
     * The object's box as last given to {@code insert} or {@code move}; allocates the result. {@link IllegalArgumentException} for a handle that is not in the octree.
     */
    public Aabbf bounds(int handle) {
        requireLive(handle);
        int o = handle * 6;
        return new Aabbf(box[o], box[o + 1], box[o + 2], box[o + 3], box[o + 4], box[o + 5]);
    }

    /** Depth of the node holding the object (0 = root). Shows how well sizes match the world. */
    public int depthOf(int handle) {
        requireLive(handle);
        return nodeDepth[objNode[handle]];
    }

    /** Checks the internal invariants and throws {@link IllegalStateException} for the first violation. */
    public void validate() {
        int live = 0;
        int nodes = 0;
        boolean[] seen = new boolean[nodeHigh];
        int[] stack = new int[nodeHigh + 1];
        int sp = 0;
        stack[sp++] = 0;
        int listed = 0;
        while (sp > 0) {
            int n = stack[--sp];
            if (seen[n]) {
                throw new IllegalStateException("node " + n + " reached twice");
            }
            seen[n] = true;
            nodes++;
            int c = 0;
            for (int i = 0; i < 8; i++) {
                int ch = child[n * 8 + i];
                if (ch == NONE) {
                    continue;
                }
                c++;
                if (parent[ch] != n) {
                    throw new IllegalStateException("child " + ch + " does not point back to " + n);
                }
                if (nodeDepth[ch] != nodeDepth[n] + 1) {
                    throw new IllegalStateException("depth of " + ch + " is wrong");
                }
                stack[sp++] = ch;
            }
            if (c != childCount[n]) {
                throw new IllegalStateException("node " + n + " counts " + childCount[n] + " children, has " + c);
            }
            int objects = 0;
            for (int h = firstObject[n]; h != NONE; h = next[h]) {
                objects++;
                if (!alive[h] || objNode[h] != n) {
                    throw new IllegalStateException("object " + h + " is listed in node " + n + " but does not belong there");
                }
                if (next[h] != NONE && prev[next[h]] != h) {
                    throw new IllegalStateException("object list of node " + n + " is not doubly linked");
                }
                if (n != 0 && !insideLoose(n, h)) {
                    throw new IllegalStateException("object " + h + " sticks out of the loose box of node " + n);
                }
            }
            listed += objects;
            if (objects != objectCount[n]) {
                throw new IllegalStateException("node " + n + " counts " + objectCount[n] + " objects, has " + objects);
            }
            if (n != 0 && objects == 0 && c == 0) {
                throw new IllegalStateException("empty node " + n + " was not removed");
            }
        }
        for (int h = 0; h < objectHigh; h++) {
            if (alive[h]) {
                live++;
            }
        }
        if (live != liveCount || listed != liveCount) {
            throw new IllegalStateException("size() is " + liveCount + ", " + live + " alive, " + listed + " listed in nodes");
        }
        if (nodes != nodeCount) {
            throw new IllegalStateException("counted " + nodes + " nodes, nodeCount is " + nodeCount);
        }
    }

    private boolean insideLoose(int n, int h) {
        float e = 2f * geom[n * 4 + 3];
        float slack = 1e-4f * e;
        int o = h * 6;
        return box[o] >= geom[n * 4] - e - slack && box[o + 3] <= geom[n * 4] + e + slack
                && box[o + 1] >= geom[n * 4 + 1] - e - slack && box[o + 4] <= geom[n * 4 + 1] + e + slack
                && box[o + 2] >= geom[n * 4 + 2] - e - slack && box[o + 5] <= geom[n * 4 + 2] + e + slack;
    }

    // ---------------------------------------------------------------- placement

    private static void checkBox(float x0, float y0, float z0, float x1, float y1, float z1) {
        if (!(Float.isFinite(x0) && Float.isFinite(y0) && Float.isFinite(z0) && Float.isFinite(x1) && Float.isFinite(y1)
                && Float.isFinite(z1))) {
            throw new IllegalArgumentException("box must be finite");
        }
        if (x0 > x1 || y0 > y1 || z0 > z1) {
            throw new IllegalArgumentException("box has min > max");
        }
    }

    /**
     * The node an object with this box belongs in: as deep as the box's size allows, following its centre. With
     * {@code create} false, returns {@link #NONE} when that node does not exist yet.
     */
    private int locate(float x0, float y0, float z0, float x1, float y1, float z1, boolean create) {
        float cx = (x0 + x1) * 0.5f, cy = (y0 + y1) * 0.5f, cz = (z0 + z1) * 0.5f;
        if (Math.abs(cx - rootX) > rootHalf || Math.abs(cy - rootY) > rootHalf || Math.abs(cz - rootZ) > rootHalf) {
            return 0;
        }
        float half = 0.5f * Math.max(x1 - x0, Math.max(y1 - y0, z1 - z0));
        int depth;
        if (half <= 0f) {
            depth = maxDepth;
        } else {
            float ratio = rootHalf / half * 0.9999f;
            depth = ratio < 1f ? 0 : Math.min(maxDepth, Math.getExponent(ratio));
        }
        int n = 0;
        for (int d = 0; d < depth; d++) {
            int o = n * 4;
            int i = (cx >= geom[o] ? 1 : 0) | (cy >= geom[o + 1] ? 2 : 0) | (cz >= geom[o + 2] ? 4 : 0);
            int c = child[n * 8 + i];
            if (c == NONE) {
                if (!create) {
                    return NONE;
                }
                float q = geom[o + 3] * 0.5f;
                c = newNode(n, geom[o] + ((i & 1) != 0 ? q : -q), geom[o + 1] + ((i & 2) != 0 ? q : -q),
                        geom[o + 2] + ((i & 4) != 0 ? q : -q), q, d + 1);
                child[n * 8 + i] = c;
                childCount[n]++;
            }
            n = c;
        }
        return n;
    }

    private void link(int h, int n) {
        objNode[h] = n;
        prev[h] = NONE;
        next[h] = firstObject[n];
        if (firstObject[n] != NONE) {
            prev[firstObject[n]] = h;
        }
        firstObject[n] = h;
        objectCount[n]++;
    }

    /** Takes the object out of its node and removes nodes that became empty. */
    private void unlink(int h) {
        int n = objNode[h];
        if (prev[h] != NONE) {
            next[prev[h]] = next[h];
        } else {
            firstObject[n] = next[h];
        }
        if (next[h] != NONE) {
            prev[next[h]] = prev[h];
        }
        objectCount[n]--;
        objNode[h] = NONE;
        while (n != 0 && objectCount[n] == 0 && childCount[n] == 0) {
            int p = parent[n];
            for (int i = 0; i < 8; i++) {
                if (child[p * 8 + i] == n) {
                    child[p * 8 + i] = NONE;
                    break;
                }
            }
            childCount[p]--;
            freeNodeSlot(n);
            n = p;
        }
    }

    // ---------------------------------------------------------------- pools

    private int newNode(int parentNode, float cx, float cy, float cz, float half, int depth) {
        int n;
        if (freeNode != NONE) {
            n = freeNode;
            freeNode = parent[n];
        } else {
            if (nodeHigh == parent.length) {
                growNodes(nodeHigh * 2);
            }
            n = nodeHigh++;
        }
        geom[n * 4] = cx;
        geom[n * 4 + 1] = cy;
        geom[n * 4 + 2] = cz;
        geom[n * 4 + 3] = half;
        Arrays.fill(child, n * 8, n * 8 + 8, NONE);
        parent[n] = parentNode;
        firstObject[n] = NONE;
        objectCount[n] = 0;
        childCount[n] = 0;
        nodeDepth[n] = depth;
        nodeCount++;
        return n;
    }

    private void freeNodeSlot(int n) {
        parent[n] = freeNode;
        freeNode = n;
        nodeCount--;
    }

    private void growNodes(int capacity) {
        geom = Arrays.copyOf(geom, capacity * 4);
        child = Arrays.copyOf(child, capacity * 8);
        parent = Arrays.copyOf(parent, capacity);
        firstObject = Arrays.copyOf(firstObject, capacity);
        objectCount = Arrays.copyOf(objectCount, capacity);
        childCount = Arrays.copyOf(childCount, capacity);
        nodeDepth = Arrays.copyOf(nodeDepth, capacity);
    }

    private int allocateObject() {
        if (freeObject != NONE) {
            int h = freeObject;
            freeObject = next[h];
            return h;
        }
        if (objectHigh == item.length) {
            int c = objectHigh * 2;
            box = Arrays.copyOf(box, c * 6);
            item = Arrays.copyOf(item, c);
            objNode = Arrays.copyOf(objNode, c);
            prev = Arrays.copyOf(prev, c);
            next = Arrays.copyOf(next, c);
            alive = Arrays.copyOf(alive, c);
        }
        return objectHigh++;
    }

    private void setBox(int h, float x0, float y0, float z0, float x1, float y1, float z1) {
        int o = h * 6;
        box[o] = x0;
        box[o + 1] = y0;
        box[o + 2] = z0;
        box[o + 3] = x1;
        box[o + 4] = y1;
        box[o + 5] = z1;
    }

    private void requireLive(int handle) {
        if (!isValid(handle)) {
            throw new IllegalArgumentException("not a valid object handle: " + handle);
        }
    }

    // ---------------------------------------------------------------- queries

    /** A query object with its own traversal stack. Create one per thread; reuse it. */
    public Query newQuery() {
        return new Query(this);
    }

    /** Read-only queries on a {@link LooseOctree}. Results are the objects' user data. */
    public static final class Query {

        private final LooseOctree tree;
        private int[] stack = new int[64];
        private float[] dStack = new float[64];
        private final int[] order = new int[8];
        private final float[] dist = new float[8];

        private Query(LooseOctree tree) {
            this.tree = tree;
        }

        /** Appends the user data of every object whose box overlaps {@code b} (touching counts). */
        public void overlapAabb(Aabbf b, IntList out) {
            overlap(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), 0f, 0f, 0f, -1f, out);
        }

        /** Appends the user data of every object whose box touches {@code s}. */
        public void overlapSphere(Spheref s, IntList out) {
            float r = s.radius();
            float pad = 4f * Math.ulp(Math.max(Math.abs(s.cx()) + Math.abs(s.cy()) + Math.abs(s.cz()), r));
            float e = r + pad;
            overlap(s.cx() - e, s.cy() - e, s.cz() - e, s.cx() + e, s.cy() + e, s.cz() + e, s.cx(), s.cy(), s.cz(), r * r, out);
        }

        private void overlap(float x0, float y0, float z0, float x1, float y1, float z1,
                             float cx, float cy, float cz, float r2, IntList out) {
            LooseOctree t = tree;
            if (t.liveCount == 0 || !(x0 <= x1 && y0 <= y1 && z0 <= z1)) {
                return;
            }
            float[] g = t.geom;
            float[] b = t.box;
            int sp = 0;
            stack[sp++] = 0;
            while (sp > 0) {
                int n = stack[--sp];
                if (n != 0) { // the root is a catch-all and is never pruned
                    float e = 2f * g[n * 4 + 3];
                    if (g[n * 4] - e > x1 || g[n * 4] + e < x0 || g[n * 4 + 1] - e > y1 || g[n * 4 + 1] + e < y0
                            || g[n * 4 + 2] - e > z1 || g[n * 4 + 2] + e < z0) {
                        continue;
                    }
                }
                for (int h = t.firstObject[n]; h != NONE; h = t.next[h]) {
                    int o = h * 6;
                    if (b[o] > x1 || b[o + 3] < x0 || b[o + 1] > y1 || b[o + 4] < y0 || b[o + 2] > z1 || b[o + 5] < z0) {
                        continue;
                    }
                    if (r2 < 0f || distanceSquared(b, o, cx, cy, cz) <= r2) {
                        out.add(t.item[h]);
                    }
                }
                if (t.childCount[n] > 0) {
                    if (sp + 8 > stack.length) {
                        stack = Arrays.copyOf(stack, stack.length * 2);
                    }
                    for (int i = 0; i < 8; i++) {
                        int c = t.child[n * 8 + i];
                        if (c != NONE) {
                            stack[sp++] = c;
                        }
                    }
                }
            }
        }

        /**
         * The {@code out.k()} objects whose boxes are closest to the point, nearest first; {@code out} holds their user data
         * (ties: smaller user data). Nodes are visited nearest loose box first and skipped once they cannot beat the worst
         * neighbour kept.
         */
        public void nearest(float x, float y, float z, Neighbors out) {
            LooseOctree t = tree;
            out.reset();
            if (t.liveCount == 0 || x != x || y != y || z != z) {
                return;
            }
            float[] g = t.geom;
            float[] b = t.box;
            int sp = 0;
            stack[sp] = 0;
            dStack[sp++] = 0f;
            while (sp > 0) {
                sp--;
                if (dStack[sp] > out.bound()) {
                    continue;
                }
                int n = stack[sp];
                for (int h = t.firstObject[n]; h != NONE; h = t.next[h]) {
                    out.offer(t.item[h], distanceSquared(b, h * 6, x, y, z));
                }
                int count = 0;
                for (int i = 0; i < 8; i++) {
                    int c = t.child[n * 8 + i];
                    if (c == NONE) {
                        continue;
                    }
                    float e = 2f * g[c * 4 + 3];
                    float dx = Math.max(Math.max(g[c * 4] - e - x, 0f), x - (g[c * 4] + e));
                    float dy = Math.max(Math.max(g[c * 4 + 1] - e - y, 0f), y - (g[c * 4 + 1] + e));
                    float dz = Math.max(Math.max(g[c * 4 + 2] - e - z, 0f), z - (g[c * 4 + 2] + e));
                    float d = dx * dx + dy * dy + dz * dz;
                    // insertion sort by distance, nearest first
                    int j = count++;
                    while (j > 0 && dist[j - 1] > d) {
                        dist[j] = dist[j - 1];
                        order[j] = order[j - 1];
                        j--;
                    }
                    dist[j] = d;
                    order[j] = c;
                }
                if (sp + count > stack.length) {
                    stack = Arrays.copyOf(stack, Math.max(stack.length * 2, sp + count));
                    dStack = Arrays.copyOf(dStack, stack.length);
                }
                if (dStack.length < stack.length) {
                    dStack = Arrays.copyOf(dStack, stack.length);
                }
                for (int i = count - 1; i >= 0; i--) { // farthest pushed first, so the nearest pops next
                    stack[sp] = order[i];
                    dStack[sp++] = dist[i];
                }
            }
            out.finish();
        }

        private static float distanceSquared(float[] b, int o, float px, float py, float pz) {
            float dx = Math.max(Math.max(b[o] - px, 0f), px - b[o + 3]);
            float dy = Math.max(Math.max(b[o + 1] - py, 0f), py - b[o + 4]);
            float dz = Math.max(Math.max(b[o + 2] - pz, 0f), pz - b[o + 5]);
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
