package vmath.spatial;

import java.util.Arrays;
import vmath.bulk.IntList;
import vmath.bulk.VisibilitySet;
import vmath.geo.Aabbf;
import vmath.geo.Frustumf;
import vmath.geo.Rayf;
import vmath.geo.Spheref;

/**
 * A bounding-volume tree for objects that move: objects can be inserted, removed and moved at any time, and queries always
 * see the current state. Where {@link StaticBvh} is rebuilt or refitted in bulk, this tree updates incrementally, so a
 * scene of mostly-still objects with a few movers costs almost nothing per frame.
 *
 * <p><b>Fat boxes.</b> Each leaf is stored with a slightly enlarged ("fat") box, padded by {@code margin} and stretched
 * along the direction it is moving. {@link #move} only touches the tree when the object leaves its fat box, so an object
 * drifting slowly costs nothing on most frames. Queries still test the object's own tight box at the leaf, so results are
 * exact; the fat boxes only affect how well subtrees prune.
 *
 * <p><b>Structure.</b> Nodes are entries in parallel arrays (no node objects). Insertion picks the sibling that grows the
 * surface area least (branch-and-descend on the surface-area heuristic), and each change rebalances with AVL-style
 * rotations on the way back up, so the depth stays logarithmic. Only <em>internal</em> nodes are ever rearranged: a leaf's
 * index is a <b>stable handle</b> for as long as the object is in the tree.
 *
 * <p>Each object carries an {@code int} of user data (typically an index into your own arrays); queries report that value.
 *
 * <p>Not thread-safe for updates. Concurrent read-only queries are fine if each thread uses its own {@link Query} and the
 * tree is not being modified.
 */
public final class DynamicAabbTree {

    private static final int NULL = -1;

    private final float margin;
    /** How far ahead (in multiples of the displacement passed to {@link #move}) the fat box is stretched. */
    private static final float VELOCITY_MULTIPLIER = 2f;

    private float[] bounds;   // 6 per node: fat box for leaves, union of children for internal nodes
    private float[] tight;    // 6 per node, leaves only: the object's own box
    /**
     * Eight ints per node so that everything a traversal needs beyond the bounds sits in half a cache line: left, right,
     * parent, height (leaf 0, internal >= 1, free -1) and user data (leaves only). Separate arrays cost a cache miss each.
     */
    private int[] link;
    private static final int STRIDE_SHIFT = 3;
    private static final int LEFT = 0;
    private static final int RIGHT = 1;
    private static final int PARENT = 2;
    private static final int HEIGHT = 3;
    private static final int ITEM = 4;
    private static final int HANDLE = 5;

    /**
     * Handle to node. Handles are what callers hold; nodes are renumbered by {@link #optimize()}. A free entry is negative and
     * chains the free list ({@code -2 - next}, so the end of the list is {@code -1}).
     */
    private int[] handleNode;
    private int handleFree = NULL;

    // spare buffers for optimize(), swapped with the live ones so that optimizing allocates nothing once they exist
    private float[] spareBounds;
    private float[] spareTight;
    private int[] spareLink;
    private int[] remap;
    private int[] walk = new int[64];

    private int root = NULL;
    private int freeList = NULL;
    private int nodeCapacity;
    private int nodeCount;
    private int leafCount;
    private long reinsertions;

    /** A tree whose fat boxes extend {@code 0.1} beyond each object. */
    public DynamicAabbTree() {
        this(0.1f, 64);
    }

    /**
     * @param margin          how far to pad each object's box on every side; larger means fewer updates but looser pruning.
     *                        A good value is a fraction of a typical object's size or of how far it moves per frame.
     * @param initialCapacity nodes to preallocate (a tree with n objects has 2n - 1 nodes)
     */
    public DynamicAabbTree(float margin, int initialCapacity) {
        if (!(margin >= 0f)) {
            throw new IllegalArgumentException("margin must be >= 0: " + margin);
        }
        this.margin = margin;
        int c = Math.max(initialCapacity, 4);
        bounds = new float[c * 6];
        tight = new float[c * 6];
        link = new int[c << STRIDE_SHIFT];
        handleNode = new int[c];
        nodeCapacity = c;
        linkFreeNodes(0);
        linkFreeHandles(0);
    }

    // ---------------------------------------------------------------- public API: updates

    /** Adds an object and returns its handle. */
    public int insert(Aabbf box, int userData) {
        return insert(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(), userData);
    }

    /** Adds an object with the box given by its six bounds and returns its handle; {@code userData} is returned by queries and {@link #userData}. */
    public int insert(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, int userData) {
        int leaf = allocate();
        int o = leaf * 6;
        tight[o] = minX;
        tight[o + 1] = minY;
        tight[o + 2] = minZ;
        tight[o + 3] = maxX;
        tight[o + 4] = maxY;
        tight[o + 5] = maxZ;
        bounds[o] = minX - margin;
        bounds[o + 1] = minY - margin;
        bounds[o + 2] = minZ - margin;
        bounds[o + 3] = maxX + margin;
        bounds[o + 4] = maxY + margin;
        bounds[o + 5] = maxZ + margin;
        link[(leaf << 3) + HEIGHT] = 0;
        link[(leaf << 3) + ITEM] = userData;
        link[(leaf << 3) + LEFT] = NULL;
        link[(leaf << 3) + RIGHT] = NULL;
        int handle = allocateHandle();
        handleNode[handle] = leaf;
        link[(leaf << 3) + HANDLE] = handle;
        insertLeaf(leaf);
        leafCount++;
        return handle;
    }

    /** Removes the object; its handle becomes invalid (and may be reused by a later {@link #insert}). */
    public void remove(int handle) {
        int leaf = leafOf(handle);
        removeLeaf(leaf);
        release(leaf);
        freeHandle(handle);
        leafCount--;
    }

    /**
     * Updates the object's box. Returns {@code true} if the tree had to be changed (the object left its fat box), {@code false}
     * if the fat box still covers it and nothing was done (the tight box is always updated). {@code displacementX/Y/Z} is
     * how far the object moved this frame: the new fat box is stretched that far ahead, so an object moving steadily
     * leaves its fat box rarely.
     */
    public boolean move(int handle, float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                        float displacementX, float displacementY, float displacementZ) {
        int leaf = leafOf(handle);
        int o = leaf * 6;
        tight[o] = minX;
        tight[o + 1] = minY;
        tight[o + 2] = minZ;
        tight[o + 3] = maxX;
        tight[o + 4] = maxY;
        tight[o + 5] = maxZ;
        boolean fatContains = bounds[o] <= minX && bounds[o + 1] <= minY && bounds[o + 2] <= minZ
                && bounds[o + 3] >= maxX && bounds[o + 4] >= maxY && bounds[o + 5] >= maxZ;
        if (fatContains) {
            // also re-fit when the fat box has become much larger than the object, which would prune badly
            float huge = 4f * margin;
            boolean tightEnough = minX - huge <= bounds[o] && minY - huge <= bounds[o + 1] && minZ - huge <= bounds[o + 2]
                    && maxX + huge >= bounds[o + 3] && maxY + huge >= bounds[o + 4] && maxZ + huge >= bounds[o + 5];
            if (tightEnough) {
                return false;
            }
        }
        removeLeaf(leaf);
        float dx = displacementX * VELOCITY_MULTIPLIER;
        float dy = displacementY * VELOCITY_MULTIPLIER;
        float dz = displacementZ * VELOCITY_MULTIPLIER;
        bounds[o] = minX - margin + Math.min(dx, 0f);
        bounds[o + 1] = minY - margin + Math.min(dy, 0f);
        bounds[o + 2] = minZ - margin + Math.min(dz, 0f);
        bounds[o + 3] = maxX + margin + Math.max(dx, 0f);
        bounds[o + 4] = maxY + margin + Math.max(dy, 0f);
        bounds[o + 5] = maxZ + margin + Math.max(dz, 0f);
        insertLeaf(leaf);
        reinsertions++;
        return true;
    }

    /** As the six-bounds {@code move}, with the box given as an {@link Aabbf}. */
    public boolean move(int handle, Aabbf box, float displacementX, float displacementY, float displacementZ) {
        return move(handle, box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(),
                displacementX, displacementY, displacementZ);
    }

    /** Removes every object. Capacity is kept. */
    public void clear() {
        root = NULL;
        leafCount = 0;
        nodeCount = 0;
        linkFreeNodes(0);
        handleFree = NULL;
        linkFreeHandles(0);
    }

    // ---------------------------------------------------------------- public API: inspection

    /** Number of objects in the tree. */
    public int size() {
        return leafCount;
    }

    /** True when {@code handle} names an object currently in the tree. */
    public boolean isValid(int handle) {
        return handle >= 0 && handle < nodeCapacity && handleNode[handle] >= 0;
    }

    /** The {@code userData} given when the object was inserted; {@link IllegalArgumentException} for a handle that is not in the tree. */
    public int userData(int handle) {
        return link[(leafOf(handle) << 3) + ITEM];
    }

    /** The object's own box, as last given to {@link #insert} or {@link #move}. */
    public Aabbf tightBounds(int handle) {
        int o = leafOf(handle) * 6;
        return new Aabbf(tight[o], tight[o + 1], tight[o + 2], tight[o + 3], tight[o + 4], tight[o + 5]);
    }

    /** The padded box the tree currently uses for this object; always contains {@link #tightBounds}. */
    public Aabbf fatBounds(int handle) {
        int o = leafOf(handle) * 6;
        return new Aabbf(bounds[o], bounds[o + 1], bounds[o + 2], bounds[o + 3], bounds[o + 4], bounds[o + 5]);
    }

    /** Bounds of everything in the tree (fat boxes); {@link Aabbf#EMPTY} when empty. */
    public Aabbf totalBounds() {
        if (root == NULL) {
            return Aabbf.EMPTY;
        }
        int o = root * 6;
        return new Aabbf(bounds[o], bounds[o + 1], bounds[o + 2], bounds[o + 3], bounds[o + 4], bounds[o + 5]);
    }

    /** Depth of the tree in levels (0 when empty, 1 for a single object). */
    public int height() {
        return root == NULL ? 0 : link[(root << 3) + HEIGHT] + 1;
    }

    /** How many {@link #move} calls had to restructure the tree so far. Shows how well the fat boxes are working. */
    public long reinsertions() {
        return reinsertions;
    }

    /**
     * The same quality measure as {@link StaticBvh#sahCost()}: expected cost of a random ray, relative to the root area. Lower
     * is better; comparing it with a freshly built static tree tells you when a rebuild would pay off.
     */
    public float sahCost() {
        if (root == NULL) {
            return 0f;
        }
        double rootArea = area(root);
        if (rootArea <= 0.0) {
            return 0f;
        }
        double cost = 0.0;
        for (int n = 0; n < nodeCapacity; n++) {
            if (link[(n << 3) + HEIGHT] >= 0) { // skip free nodes
                cost += area(n) * (link[(n << 3) + LEFT] == NULL ? 2.0 : 1.0);
            }
        }
        return (float) (cost / rootArea);
    }

    /**
     * Checks every structural invariant and throws {@link IllegalStateException} naming the first violation: parent/child links
     * agree, each internal node's box contains its children's, heights are consistent, the tree is shallow (see below), every
     * leaf's fat box contains its tight box, and the counts add up.
     *
     * <p>Balance is checked as an overall depth bound, not per node. Insertion places a new object next to whichever node
     * grows the surface area least, which may be a whole subtree, so a node's two children can differ by more than one level
     * (as in the well-known Box2D tree this design follows); the rotations keep the depth logarithmic all the same. A tree of
     * {@code n} objects must be no deeper than {@code 3 * ceil(log2(n + 1))} levels (and at least 8 are always allowed).
     */
    public void validate() {
        if (root == NULL) {
            if (leafCount != 0) {
                throw new IllegalStateException("empty tree reports " + leafCount + " leaves");
            }
            return;
        }
        if (link[(root << 3) + PARENT] != NULL) {
            throw new IllegalStateException("root " + root + " has a parent " + link[(root << 3) + PARENT]);
        }
        int[] counts = new int[2];
        validateNode(root, counts);
        if (counts[0] != leafCount) {
            throw new IllegalStateException("counted " + counts[0] + " leaves but size() is " + leafCount);
        }
        if (counts[1] != counts[0] - 1) {
            throw new IllegalStateException("a binary tree with " + counts[0] + " leaves has " + (counts[0] - 1)
                    + " internal nodes, found " + counts[1]);
        }
        int cap = Math.max(8, 3 * (32 - Integer.numberOfLeadingZeros(leafCount)));
        if (height() > cap) {
            throw new IllegalStateException("tree of " + leafCount + " objects is " + height() + " levels deep, more than the "
                    + cap + " allowed: the rotations are not keeping it shallow");
        }
    }

    private void validateNode(int n, int[] counts) {
        if (n < 0 || n >= nodeCapacity || isFree(n)) {
            throw new IllegalStateException("link to invalid or free node " + n);
        }
        int o = n * 6;
        if (link[(n << 3) + LEFT] == NULL) {
            if (link[(n << 3) + RIGHT] != NULL || link[(n << 3) + HEIGHT] != 0) {
                throw new IllegalStateException("leaf " + n + " must have no children and height 0");
            }
            counts[0]++;
            int h = link[(n << 3) + HANDLE];
            if (h < 0 || h >= nodeCapacity || handleNode[h] != n) {
                throw new IllegalStateException("leaf " + n + " and its handle " + h + " do not point at each other");
            }
            if (bounds[o] > tight[o] || bounds[o + 1] > tight[o + 1] || bounds[o + 2] > tight[o + 2]
                    || bounds[o + 3] < tight[o + 3] || bounds[o + 4] < tight[o + 4] || bounds[o + 5] < tight[o + 5]) {
                throw new IllegalStateException("fat box of leaf " + n + " does not contain its tight box");
            }
            return;
        }
        counts[1]++;
        int l = link[(n << 3) + LEFT], r = link[(n << 3) + RIGHT];
        if (r == NULL) {
            throw new IllegalStateException("internal node " + n + " has only one child");
        }
        if (link[(l << 3) + PARENT] != n || link[(r << 3) + PARENT] != n) {
            throw new IllegalStateException("children of " + n + " do not point back to it");
        }
        int expected = 1 + Math.max(link[(l << 3) + HEIGHT], link[(r << 3) + HEIGHT]);
        if (link[(n << 3) + HEIGHT] != expected) {
            throw new IllegalStateException("height of " + n + " is " + link[(n << 3) + HEIGHT] + ", should be " + expected);
        }
        int lo = l * 6, ro = r * 6;
        for (int k = 0; k < 3; k++) {
            float min = Math.min(bounds[lo + k], bounds[ro + k]);
            float max = Math.max(bounds[lo + 3 + k], bounds[ro + 3 + k]);
            if (bounds[o + k] > min || bounds[o + 3 + k] < max) {
                throw new IllegalStateException("box of " + n + " does not contain its children on axis " + k);
            }
        }
        validateNode(l, counts);
        validateNode(r, counts);
    }

    // ---------------------------------------------------------------- allocation

    private boolean isFree(int n) {
        return link[(n << 3) + HEIGHT] == -1;
    }

    private void linkFreeNodes(int from) {
        for (int i = from; i < nodeCapacity - 1; i++) {
            link[(i << 3) + PARENT] = i + 1;
            link[(i << 3) + HEIGHT] = -1;
        }
        link[(nodeCapacity - 1 << 3) + PARENT] = NULL;
        link[(nodeCapacity - 1 << 3) + HEIGHT] = -1;
        freeList = from;
    }

    private int allocate() {
        if (freeList == NULL) {
            int old = nodeCapacity;
            nodeCapacity = old * 2;
            bounds = Arrays.copyOf(bounds, nodeCapacity * 6);
            tight = Arrays.copyOf(tight, nodeCapacity * 6);
            link = Arrays.copyOf(link, nodeCapacity << STRIDE_SHIFT);
            handleNode = Arrays.copyOf(handleNode, nodeCapacity);
            linkFreeNodes(old);
            linkFreeHandles(old);
        }
        int n = freeList;
        freeList = link[(n << 3) + PARENT];
        link[(n << 3) + PARENT] = NULL;
        link[(n << 3) + LEFT] = NULL;
        link[(n << 3) + RIGHT] = NULL;
        link[(n << 3) + HEIGHT] = 0;
        nodeCount++;
        return n;
    }

    private void release(int n) {
        link[(n << 3) + PARENT] = freeList;
        link[(n << 3) + HEIGHT] = -1;
        link[(n << 3) + LEFT] = NULL;
        link[(n << 3) + RIGHT] = NULL;
        freeList = n;
        nodeCount--;
    }

    /** The leaf node behind {@code handle}; throws for a handle that is not in the tree. */
    private int leafOf(int handle) {
        if (!isValid(handle)) {
            throw new IllegalArgumentException("not a valid object handle: " + handle);
        }
        return handleNode[handle];
    }

    /** Chains handles {@code from} .. capacity-1 in front of whatever was already free. */
    private void linkFreeHandles(int from) {
        for (int i = from; i < nodeCapacity - 1; i++) {
            handleNode[i] = -2 - (i + 1);
        }
        handleNode[nodeCapacity - 1] = -2 - handleFree;
        handleFree = from;
    }

    private int allocateHandle() {
        int h = handleFree;
        handleFree = -2 - handleNode[h];
        return h;
    }

    private void freeHandle(int h) {
        handleNode[h] = -2 - handleFree;
        handleFree = h;
    }

    // ---------------------------------------------------------------- optimisation

    /**
     * Renumbers every node in depth-first order (a node's left child directly follows it), so that queries walk memory
     * sequentially instead of jumping between wherever insertions happened to put nodes. Handles, user data and the tree's
     * shape are unchanged; only the node numbering (and so cache behaviour) is. Frustum queries over 100k scattered objects
     * are several times faster afterwards.
     *
     * <p>Cost is O(n) and allocation-free after the first call (it keeps a second set of node arrays and swaps). Call it after
     * loading a level, after bulk inserts, or every few hundred frames when objects are being reinserted a lot.
     */
    public void optimize() {
        if (root == NULL) {
            return;
        }
        if (spareLink == null || spareLink.length != link.length) {
            spareBounds = new float[bounds.length];
            spareTight = new float[tight.length];
            spareLink = new int[link.length];
            remap = new int[nodeCapacity];
        }
        // pass 1: depth-first numbering (left child right after its parent)
        int next = 0;
        int sp = 0;
        walk[sp++] = root;
        while (sp > 0) {
            int n = walk[--sp];
            remap[n] = next++;
            int l = link[(n << 3) + LEFT];
            if (l != NULL) {
                if (sp + 2 > walk.length) {
                    walk = Arrays.copyOf(walk, walk.length * 2);
                }
                walk[sp++] = link[(n << 3) + RIGHT];
                walk[sp++] = l;
            }
        }
        // pass 2: copy into the spare arrays under the new numbers
        for (int old = 0; old < nodeCapacity; old++) {
            if (link[(old << 3) + HEIGHT] < 0) {
                continue;
            }
            int k = remap[old];
            System.arraycopy(bounds, old * 6, spareBounds, k * 6, 6);
            System.arraycopy(tight, old * 6, spareTight, k * 6, 6);
            int p = link[(old << 3) + PARENT], l = link[(old << 3) + LEFT], r = link[(old << 3) + RIGHT];
            int q = k << 3;
            spareLink[q + PARENT] = p == NULL ? NULL : remap[p];
            spareLink[q + LEFT] = l == NULL ? NULL : remap[l];
            spareLink[q + RIGHT] = r == NULL ? NULL : remap[r];
            spareLink[q + HEIGHT] = link[(old << 3) + HEIGHT];
            spareLink[q + ITEM] = link[(old << 3) + ITEM];
            int h = link[(old << 3) + HANDLE];
            spareLink[q + HANDLE] = h;
            if (l == NULL) {
                handleNode[h] = k;
            }
        }
        float[] b = bounds;
        bounds = spareBounds;
        spareBounds = b;
        float[] t = tight;
        tight = spareTight;
        spareTight = t;
        int[] li = link;
        link = spareLink;
        spareLink = li;
        root = 0;
        // the remaining slots (next .. capacity-1) become the free list
        if (next < nodeCapacity) {
            linkFreeNodes(next);
        } else {
            freeList = NULL;
        }
    }

    // ---------------------------------------------------------------- geometry helpers on the arrays

    private float area(int n) {
        int o = n * 6;
        float dx = bounds[o + 3] - bounds[o], dy = bounds[o + 4] - bounds[o + 1], dz = bounds[o + 5] - bounds[o + 2];
        return 2f * (dx * dy + dy * dz + dz * dx);
    }

    /** Surface area of the union of node {@code a} and node {@code b}. */
    private float unionArea(int a, int b) {
        int p = a * 6, q = b * 6;
        float dx = Math.max(bounds[p + 3], bounds[q + 3]) - Math.min(bounds[p], bounds[q]);
        float dy = Math.max(bounds[p + 4], bounds[q + 4]) - Math.min(bounds[p + 1], bounds[q + 1]);
        float dz = Math.max(bounds[p + 5], bounds[q + 5]) - Math.min(bounds[p + 2], bounds[q + 2]);
        return 2f * (dx * dy + dy * dz + dz * dx);
    }

    /** Sets node {@code into} to the union of nodes {@code a} and {@code b}. */
    private void setUnion(int into, int a, int b) {
        int o = into * 6, p = a * 6, q = b * 6;
        bounds[o] = Math.min(bounds[p], bounds[q]);
        bounds[o + 1] = Math.min(bounds[p + 1], bounds[q + 1]);
        bounds[o + 2] = Math.min(bounds[p + 2], bounds[q + 2]);
        bounds[o + 3] = Math.max(bounds[p + 3], bounds[q + 3]);
        bounds[o + 4] = Math.max(bounds[p + 4], bounds[q + 4]);
        bounds[o + 5] = Math.max(bounds[p + 5], bounds[q + 5]);
    }

    // ---------------------------------------------------------------- insertion, removal, balancing

    private void insertLeaf(int leaf) {
        if (root == NULL) {
            root = leaf;
            link[(leaf << 3) + PARENT] = NULL;
            return;
        }
        int sibling = findBestSibling(leaf);
        int oldParent = link[(sibling << 3) + PARENT];
        int newParent = allocate();
        link[(newParent << 3) + PARENT] = oldParent;
        link[(newParent << 3) + HEIGHT] = link[(sibling << 3) + HEIGHT] + 1;
        setUnion(newParent, leaf, sibling);
        link[(newParent << 3) + LEFT] = sibling;
        link[(newParent << 3) + RIGHT] = leaf;
        link[(sibling << 3) + PARENT] = newParent;
        link[(leaf << 3) + PARENT] = newParent;
        if (oldParent == NULL) {
            root = newParent;
        } else if (link[(oldParent << 3) + LEFT] == sibling) {
            link[(oldParent << 3) + LEFT] = newParent;
        } else {
            link[(oldParent << 3) + RIGHT] = newParent;
        }
        refitUpward(link[(leaf << 3) + PARENT]);
    }

    /**
     * Descends from the root, at each internal node comparing "pair the new leaf with this node" against "push it into
     * the cheaper child", where cost is the surface area that would be added to the tree.
     */
    private int findBestSibling(int leaf) {
        int index = root;
        while (link[(index << 3) + LEFT] != NULL) {
            int l = link[(index << 3) + LEFT], r = link[(index << 3) + RIGHT];
            float area = area(index);
            float combined = unionArea(index, leaf);
            float costHere = 2f * combined;
            float inheritance = 2f * (combined - area);
            float costLeft = descendCost(l, leaf) + inheritance;
            float costRight = descendCost(r, leaf) + inheritance;
            if (costHere < costLeft && costHere < costRight) {
                break;
            }
            index = costLeft < costRight ? l : r;
        }
        return index;
    }

    private float descendCost(int child, int leaf) {
        float united = unionArea(child, leaf);
        return link[(child << 3) + LEFT] == NULL ? united : united - area(child);
    }

    private void removeLeaf(int leaf) {
        if (leaf == root) {
            root = NULL;
            return;
        }
        int p = link[(leaf << 3) + PARENT];
        int grand = link[(p << 3) + PARENT];
        int sibling = link[(p << 3) + LEFT] == leaf ? link[(p << 3) + RIGHT] : link[(p << 3) + LEFT];
        if (grand != NULL) {
            if (link[(grand << 3) + LEFT] == p) {
                link[(grand << 3) + LEFT] = sibling;
            } else {
                link[(grand << 3) + RIGHT] = sibling;
            }
            link[(sibling << 3) + PARENT] = grand;
            release(p);
            refitUpward(grand);
        } else {
            root = sibling;
            link[(sibling << 3) + PARENT] = NULL;
            release(p);
        }
        link[(leaf << 3) + PARENT] = NULL;
    }

    /** Walks from {@code index} to the root, rebalancing and recomputing heights and boxes. */
    private void refitUpward(int start) {
        int index = start;
        while (index != NULL) {
            index = balance(index);
            int l = link[(index << 3) + LEFT], r = link[(index << 3) + RIGHT];
            link[(index << 3) + HEIGHT] = 1 + Math.max(link[(l << 3) + HEIGHT], link[(r << 3) + HEIGHT]);
            setUnion(index, l, r);
            index = link[(index << 3) + PARENT];
        }
    }

    /** AVL-style rotation: if one child is two levels taller than the other, lifts it. Returns the new subtree root. */
    private int balance(int a) {
        if (link[(a << 3) + LEFT] == NULL || link[(a << 3) + HEIGHT] < 2) {
            return a;
        }
        int b = link[(a << 3) + LEFT], c = link[(a << 3) + RIGHT];
        int balance = link[(c << 3) + HEIGHT] - link[(b << 3) + HEIGHT];
        if (balance > 1) {
            int f = link[(c << 3) + LEFT], g = link[(c << 3) + RIGHT];
            link[(c << 3) + LEFT] = a;
            link[(c << 3) + PARENT] = link[(a << 3) + PARENT];
            link[(a << 3) + PARENT] = c;
            replaceChild(link[(c << 3) + PARENT], a, c);
            if (link[(f << 3) + HEIGHT] > link[(g << 3) + HEIGHT]) {
                link[(c << 3) + RIGHT] = f;
                link[(a << 3) + RIGHT] = g;
                link[(g << 3) + PARENT] = a;
                setUnion(a, b, g);
                setUnion(c, a, f);
                link[(a << 3) + HEIGHT] = 1 + Math.max(link[(b << 3) + HEIGHT], link[(g << 3) + HEIGHT]);
                link[(c << 3) + HEIGHT] = 1 + Math.max(link[(a << 3) + HEIGHT], link[(f << 3) + HEIGHT]);
            } else {
                link[(c << 3) + RIGHT] = g;
                link[(a << 3) + RIGHT] = f;
                link[(f << 3) + PARENT] = a;
                setUnion(a, b, f);
                setUnion(c, a, g);
                link[(a << 3) + HEIGHT] = 1 + Math.max(link[(b << 3) + HEIGHT], link[(f << 3) + HEIGHT]);
                link[(c << 3) + HEIGHT] = 1 + Math.max(link[(a << 3) + HEIGHT], link[(g << 3) + HEIGHT]);
            }
            return c;
        }
        if (balance < -1) {
            int d = link[(b << 3) + LEFT], e = link[(b << 3) + RIGHT];
            link[(b << 3) + LEFT] = a;
            link[(b << 3) + PARENT] = link[(a << 3) + PARENT];
            link[(a << 3) + PARENT] = b;
            replaceChild(link[(b << 3) + PARENT], a, b);
            if (link[(d << 3) + HEIGHT] > link[(e << 3) + HEIGHT]) {
                link[(b << 3) + RIGHT] = d;
                link[(a << 3) + LEFT] = e;
                link[(e << 3) + PARENT] = a;
                setUnion(a, c, e);
                setUnion(b, a, d);
                link[(a << 3) + HEIGHT] = 1 + Math.max(link[(c << 3) + HEIGHT], link[(e << 3) + HEIGHT]);
                link[(b << 3) + HEIGHT] = 1 + Math.max(link[(a << 3) + HEIGHT], link[(d << 3) + HEIGHT]);
            } else {
                link[(b << 3) + RIGHT] = e;
                link[(a << 3) + LEFT] = d;
                link[(d << 3) + PARENT] = a;
                setUnion(a, c, d);
                setUnion(b, a, e);
                link[(a << 3) + HEIGHT] = 1 + Math.max(link[(c << 3) + HEIGHT], link[(d << 3) + HEIGHT]);
                link[(b << 3) + HEIGHT] = 1 + Math.max(link[(a << 3) + HEIGHT], link[(e << 3) + HEIGHT]);
            }
            return b;
        }
        return a;
    }

    /** Makes {@code newChild} take {@code oldChild}'s place under {@code p}, or becomes the root when {@code p} is NULL. */
    private void replaceChild(int p, int oldChild, int newChild) {
        if (p == NULL) {
            root = newChild;
        } else if (link[(p << 3) + LEFT] == oldChild) {
            link[(p << 3) + LEFT] = newChild;
        } else {
            link[(p << 3) + RIGHT] = newChild;
        }
    }

    // ---------------------------------------------------------------- queries

    /** A query object with its own traversal stack. Create one per thread; reuse it every frame. */
    public Query newQuery() {
        return new Query(this);
    }

    /**
     * Read-only queries on a {@link DynamicAabbTree}. Results are the objects' user data. Leaves are tested against their own
     * (tight) boxes, so results are exact; nothing allocates once the stack has grown to the tree depth.
     */
    public static final class Query {

        private final DynamicAabbTree tree;
        private int[] stack = new int[128];
        private int[] sub = new int[64];
        private float[] tStack = new float[128];
        private final float[] planes = new float[24];

        private Query(DynamicAabbTree tree) {
            this.tree = tree;
        }

        /**
         * Sets bit {@code userData} of {@code out} for every object that may be visible in {@code frustum} (bits of other
         * objects are left alone). Subtrees entirely inside are accepted whole, and each node tests only the planes its
         * parent could not decide. Returns the number of objects accepted.
         */
        public int frustum(Frustumf frustum, VisibilitySet out) {
            return traverseFrustum(frustum, out, null);
        }

        /** Appends the user data of every object that may be visible in {@code frustum}; returns how many. */
        public int frustum(Frustumf frustum, IntList out) {
            return traverseFrustum(frustum, null, out);
        }

        /** Exactly one of {@code set} and {@code list} is non-null. */
        private int traverseFrustum(Frustumf frustum, VisibilitySet set, IntList list) {
            DynamicAabbTree t = tree;
            if (t.root == NULL) {
                return 0;
            }
            frustum.writeTo(planes, 0);
            float[] b = t.bounds;
            int accepted = 0;
            int sp = 0;
            stack[sp++] = t.root;
            stack[sp++] = 0x3F;
            while (sp > 0) {
                int mask = stack[--sp];
                int n = stack[--sp];
                int m = NodeTests.frustumMask(planes, b, n * 6, mask);
                if (m < 0) {
                    continue;
                }
                if (t.link[(n << 3) + LEFT] == NULL) {
                    // a leaf: test its own box against the planes still undecided
                    if (m == 0 || t.tightVisible(n, planes, m)) {
                        emit(t.link[(n << 3) + ITEM], set, list);
                        accepted++;
                    }
                } else if (m == 0) {
                    accepted += acceptSubtree(n, set, list);
                } else {
                    if (sp + 4 > stack.length) {
                        stack = Arrays.copyOf(stack, stack.length * 2);
                    }
                    stack[sp++] = t.link[(n << 3) + RIGHT];
                    stack[sp++] = m;
                    stack[sp++] = t.link[(n << 3) + LEFT];
                    stack[sp++] = m;
                }
            }
            return accepted;
        }

        private static void emit(int userData, VisibilitySet set, IntList list) {
            if (set != null) {
                set.set(userData);
            } else {
                list.add(userData);
            }
        }

        /** Accepts every leaf below {@code n}, whose box is known to lie fully inside the frustum. Returns how many. */
        private int acceptSubtree(int n, VisibilitySet set, IntList list) {
            DynamicAabbTree t = tree;
            int count = 0;
            int sp = 0;
            sub[sp++] = n;
            while (sp > 0) {
                int x = sub[--sp];
                if (t.link[(x << 3) + LEFT] == NULL) {
                    emit(t.link[(x << 3) + ITEM], set, list);
                    count++;
                } else {
                    if (sp + 2 > sub.length) {
                        sub = Arrays.copyOf(sub, sub.length * 2);
                    }
                    sub[sp++] = t.link[(x << 3) + LEFT];
                    sub[sp++] = t.link[(x << 3) + RIGHT];
                }
            }
            return count;
        }

        /** Appends the user data of every object whose own box overlaps {@code box}. */
        public void overlapAabb(Aabbf box, IntList out) {
            DynamicAabbTree t = tree;
            if (t.root == NULL) {
                return;
            }
            int sp = 0;
            stack[sp++] = t.root;
            while (sp > 0) {
                int n = stack[--sp];
                int o = n * 6;
                float[] b = t.bounds;
                if (b[o] > box.maxX() || b[o + 3] < box.minX() || b[o + 1] > box.maxY() || b[o + 4] < box.minY()
                        || b[o + 2] > box.maxZ() || b[o + 5] < box.minZ()) {
                    continue;
                }
                if (t.link[(n << 3) + LEFT] == NULL) {
                    float[] g = t.tight;
                    if (g[o] <= box.maxX() && g[o + 3] >= box.minX() && g[o + 1] <= box.maxY() && g[o + 4] >= box.minY()
                            && g[o + 2] <= box.maxZ() && g[o + 5] >= box.minZ()) {
                        out.add(t.link[(n << 3) + ITEM]);
                    }
                } else {
                    if (sp + 2 > stack.length) {
                        stack = Arrays.copyOf(stack, stack.length * 2);
                    }
                    stack[sp++] = t.link[(n << 3) + RIGHT];
                    stack[sp++] = t.link[(n << 3) + LEFT];
                }
            }
        }

        /** Appends the user data of every object whose own box touches {@code sphere}. */
        public void overlapSphere(Spheref sphere, IntList out) {
            DynamicAabbTree t = tree;
            if (t.root == NULL) {
                return;
            }
            float cx = sphere.cx(), cy = sphere.cy(), cz = sphere.cz();
            float r2 = sphere.radius() * sphere.radius();
            int sp = 0;
            stack[sp++] = t.root;
            while (sp > 0) {
                int n = stack[--sp];
                int o = n * 6;
                if (distanceSquared(t.bounds, o, cx, cy, cz) > r2) {
                    continue;
                }
                if (t.link[(n << 3) + LEFT] == NULL) {
                    if (distanceSquared(t.tight, o, cx, cy, cz) <= r2) {
                        out.add(t.link[(n << 3) + ITEM]);
                    }
                } else {
                    if (sp + 2 > stack.length) {
                        stack = Arrays.copyOf(stack, stack.length * 2);
                    }
                    stack[sp++] = t.link[(n << 3) + RIGHT];
                    stack[sp++] = t.link[(n << 3) + LEFT];
                }
            }
        }

        /**
         * The {@code out.k()} objects whose own boxes are closest to the point, nearest first; {@code out} holds their user
         * data. Ties are broken by the smaller user data. Subtrees that cannot beat the worst neighbour kept so far are skipped.
         */
        public void nearest(float x, float y, float z, Neighbors out) {
            out.reset();
            DynamicAabbTree t = tree;
            if (t.root == NULL) {
                return;
            }
            int sp = 0;
            stack[sp] = t.root;
            tStack[sp++] = 0f;
            while (sp > 0) {
                sp--;
                if (tStack[sp] > out.bound()) {
                    continue;
                }
                int n = stack[sp];
                int l = t.link[(n << 3) + LEFT];
                if (l == NULL) {
                    out.offer(t.link[(n << 3) + ITEM], distanceSquared(t.tight, n * 6, x, y, z));
                } else {
                    int r = t.link[(n << 3) + RIGHT];
                    float dl = distanceSquared(t.bounds, l * 6, x, y, z);
                    float dr = distanceSquared(t.bounds, r * 6, x, y, z);
                    if (sp + 2 > stack.length) {
                        stack = Arrays.copyOf(stack, stack.length * 2);
                    }
                    if (sp + 2 > tStack.length) {
                        tStack = Arrays.copyOf(tStack, tStack.length * 2);
                    }
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

        private static float distanceSquared(float[] b, int o, float px, float py, float pz) {
            float dx = Math.max(Math.max(b[o] - px, 0f), px - b[o + 3]);
            float dy = Math.max(Math.max(b[o + 1] - py, 0f), py - b[o + 4]);
            float dz = Math.max(Math.max(b[o + 2] - pz, 0f), pz - b[o + 5]);
            return dx * dx + dy * dy + dz * dz;
        }

        /**
         * Nearest hit along {@code ray} within {@code [0, tMax]}, visiting nodes front to back and pruning beyond the best
         * hit so far. {@code test} is the exact test for the object with the given user data; with {@code null} the objects'
         * own boxes are the geometry. The result (user data of the hit object, distance) goes into {@code hit}.
         */
        public boolean raycast(Rayf ray, float tMax, BvhQuery.PrimitiveTest test, BvhQuery.BvhHit hit) {
            hit.primitive = -1;
            hit.t = Float.POSITIVE_INFINITY;
            DynamicAabbTree t = tree;
            if (t.root == NULL) {
                return false;
            }
            float ox = ray.ox(), oy = ray.oy(), oz = ray.oz();
            float ix = NodeTests.inverse(ray.dx()), iy = NodeTests.inverse(ray.dy()), iz = NodeTests.inverse(ray.dz());
            float best = tMax;
            int bestItem = -1;
            float rootT = NodeTests.entry(t.bounds, t.root * 6, ox, oy, oz, ix, iy, iz, best);
            if (rootT == Float.POSITIVE_INFINITY) {
                return false;
            }
            if (tStack.length < stack.length) {
                tStack = new float[stack.length];
            }
            int sp = 0;
            stack[sp] = t.root;
            tStack[sp++] = rootT;
            while (sp > 0) {
                sp--;
                if (tStack[sp] > best) {
                    continue;
                }
                int n = stack[sp];
                if (t.link[(n << 3) + LEFT] == NULL) {
                    float leafT;
                    if (test == null) {
                        leafT = NodeTests.entry(t.tight, n * 6, ox, oy, oz, ix, iy, iz, best);
                    } else {
                        leafT = test.intersect(t.link[(n << 3) + ITEM], ray, best);
                    }
                    if (leafT <= best && leafT != Float.POSITIVE_INFINITY) {
                        best = leafT;
                        bestItem = t.link[(n << 3) + ITEM];
                    }
                } else {
                    int l = t.link[(n << 3) + LEFT], r = t.link[(n << 3) + RIGHT];
                    float tl = NodeTests.entry(t.bounds, l * 6, ox, oy, oz, ix, iy, iz, best);
                    float tr = NodeTests.entry(t.bounds, r * 6, ox, oy, oz, ix, iy, iz, best);
                    if (sp + 2 > stack.length) {
                        stack = Arrays.copyOf(stack, stack.length * 2);
                    }
                    if (sp + 2 > tStack.length) {
                        tStack = Arrays.copyOf(tStack, Math.max(tStack.length * 2, stack.length));
                    }
                    // the nearer child is pushed last so it is visited first
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
            if (bestItem < 0) {
                return false;
            }
            hit.primitive = bestItem;
            hit.t = best;
            return true;
        }
    }

    /** Whether leaf {@code n}'s own box passes the planes of {@code mask} (the p-vertex test used by the flat kernels). */
    private boolean tightVisible(int n, float[] planes, int mask) {
        int o = n * 6;
        for (int p = 0; p < 6; p++) {
            if ((mask & (1 << p)) == 0) {
                continue;
            }
            float nx = planes[p * 4], ny = planes[p * 4 + 1], nz = planes[p * 4 + 2], d = planes[p * 4 + 3];
            float px = nx >= 0f ? tight[o + 3] : tight[o];
            float py = ny >= 0f ? tight[o + 4] : tight[o + 1];
            float pz = nz >= 0f ? tight[o + 5] : tight[o + 2];
            if (d + nx * px + ny * py + nz * pz < 0f) {
                return false;
            }
        }
        return true;
    }
}
