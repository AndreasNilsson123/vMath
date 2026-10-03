package vmath.anim;

import java.util.Arrays;
import vmath.bulk.Mat4fArray;
import vmath.core.Mat4f;
import vmath.core.Quatf;
import vmath.core.Transformf;
import vmath.core.Vec3f;

/**
 * A scene graph in structure-of-arrays form: each node has a parent, a local transform
 * (translation, rotation, scale) and a world matrix.
 *
 * <p>There are no node objects and no recursion.
 *
 * <p><b>Order invariant.</b> A node's parent always has a smaller index than the node ({@link #add}
 * can only attach to an existing node, so it holds by construction). That lets {@link #update()}
 * compute every world matrix in a single forward pass over the arrays: by the time a node is
 * reached, its parent's matrix is final.
 *
 * <p><b>Dirty tracking.</b> Changing a node's local transform marks it dirty. {@link #update()}
 * recomputes each dirty node and everything below it (a node is recomputed if it or its parent
 * was), and starts at the lowest dirty index, so an update after a few changes near the end of the
 * arrays does not touch the rest. World matrices are stored as 16 floats per node in a
 * {@link Mat4fArray}, in GPU (column-major) order, ready to upload.
 *
 * <p><b>Transform model.</b> A local transform is {@code T * R * S} (translation, then rotation,
 * then scale), so the world matrix is the exact matrix product down the chain, including the shear
 * that non-uniform scale above a rotated child produces. That is what a renderer wants and it
 * differs from composing {@link Transformf}s, which cannot represent shear. Local transforms are
 * affine, so the bottom row of every matrix is {@code (0, 0, 0, 1)}. Quaternions are normalised
 * when set.
 *
 * <p>Not thread-safe. Nothing allocates in {@link #update()} or the setters.
 *
 * <p><b>Thread safety.</b> Not thread-safe: use it from one thread at a time. Nothing blocks.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * TransformHierarchy tree = new TransformHierarchy();
 * int parent = tree.add(-1);
 * int child = tree.add(parent);
 * tree.setTranslation(parent, 1f, 0f, 0f);
 * tree.setTranslation(child, 0f, 2f, 0f);
 * tree.update();                                                          // recomputes only what changed
 * Mat4f world = tree.worldMatrix(child);                                  // translates by (1, 2, 0)
 * }</pre>
 */
public final class TransformHierarchy {

    /**
     * Floats of local transform per node: translation (3), rotation (4), scale (3).
     */
    private static final int LOCAL = 10;

    private int[] parent;
    private float[] local;
    private boolean[] dirty;
    private final Mat4fArray world;
    private int size;
    private int firstDirty = Integer.MAX_VALUE;
    private int lastUpdated;

    /**
     * Creates an empty hierarchy with room for 64 nodes; it grows as nodes are added.
     */
    public TransformHierarchy() {
        this(64);
    }

    /**
     * Creates an empty hierarchy with room for {@code capacity} nodes (at least 4); it grows as
     * nodes are added.
     *
     * @param capacity the capacity in elements
     */
    public TransformHierarchy(int capacity) {
        int c = Math.max(capacity, 4);
        parent = new int[c];
        local = new float[c * LOCAL];
        dirty = new boolean[c];
        world = new Mat4fArray(c);
    }

    // ---------------------------------------------------------------- structure

    /**
     * Counts the nodes of the hierarchy.
     *
     * @return number of nodes
     */
    public int size() {
        return size;
    }

    /**
     * Looks up the parent of a node.
     *
     * @param node the node index
     * @return parent of {@code node}, or {@code -1} for a root
     */
    public int parent(int node) {
        check(node);
        return parent[node];
    }

    /**
     * Adds a node under {@code parentNode} ({@code -1} for a root) with the identity local
     * transform, and returns its index.
     *
     * <p>The node is dirty until the next {@link #update()}.
     *
     * @param parentNode the parent node
     * @return its index
     * @throws IllegalArgumentException if {@code parentNode} does not exist
     */
    public int add(int parentNode) {
        if (parentNode < -1 || parentNode >= size) {
            throw new IllegalArgumentException("parent " + parentNode + " does not exist (size " + size + ")");
        }
        if (size == parent.length) {
            int c = size * 2;
            parent = Arrays.copyOf(parent, c);
            local = Arrays.copyOf(local, c * LOCAL);
            dirty = Arrays.copyOf(dirty, c);
        }
        int n = size++;
        world.ensureCapacity(size);
        world.setSize(size);
        parent[n] = parentNode;
        int o = n * LOCAL;
        local[o] = 0f;
        local[o + 1] = 0f;
        local[o + 2] = 0f;
        local[o + 3] = 0f;
        local[o + 4] = 0f;
        local[o + 5] = 0f;
        local[o + 6] = 1f;
        local[o + 7] = 1f;
        local[o + 8] = 1f;
        local[o + 9] = 1f;
        markDirty(n);
        return n;
    }

    /**
     * Removes {@code node} and its whole subtree and compacts the arrays, keeping the order
     * invariant.
     *
     * <p>Returns {@code remap}: for every old index its new index, or {@code -1} for a removed
     * node. World matrices and dirty flags of the survivors move with them (a survivor's world
     * matrix does not depend on anything that was removed, so no update is needed afterwards).
     *
     * @param node the node index
     * @return {@code remap}: for every old index its new index, or {@code -1} for a removed node
     */
    public int[] remove(int node) {
        check(node);
        boolean[] gone = new boolean[size];
        gone[node] = true;
        for (int i = node + 1; i < size; i++) {
            gone[i] = parent[i] >= 0 && gone[parent[i]];
        }
        int[] remap = new int[size];
        int next = 0;
        float[] w = world.data();
        int oldSize = size;
        for (int i = 0; i < oldSize; i++) {
            if (gone[i]) {
                remap[i] = -1;
                continue;
            }
            remap[i] = next;
            if (next != i) {
                parent[next] = parent[i] >= 0 ? remap[parent[i]] : -1;
                System.arraycopy(local, i * LOCAL, local, next * LOCAL, LOCAL);
                System.arraycopy(w, i * 16, w, next * 16, 16);
                dirty[next] = dirty[i];
            } else if (parent[i] >= 0) {
                parent[i] = remap[parent[i]];
            }
            next++;
        }
        size = next;
        world.setSize(size);
        firstDirty = Integer.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            if (dirty[i]) {
                firstDirty = i;
                break;
            }
        }
        return remap;
    }

    /**
     * Removes every node.
     *
     * <p>Capacity is kept.
     */
    public void clear() {
        size = 0;
        world.setSize(0);
        Arrays.fill(dirty, false);
        firstDirty = Integer.MAX_VALUE;
    }

    // ---------------------------------------------------------------- local transforms

    /**
     * Sets the whole local transform; the quaternion is normalised (a zero quaternion becomes the
     * identity).
     *
     * @param node the node index
     * @param tx the x component of the translation
     * @param ty the y component of the translation
     * @param tz the z component of the translation
     * @param qx the x component of the local rotation quaternion
     * @param qy the y component of the local rotation quaternion
     * @param qz the z component of the local rotation quaternion
     * @param qw the w component of the local rotation quaternion
     * @param sx the scale along x
     * @param sy the scale along y
     * @param sz the scale along z
     */
    public void setLocal(int node, float tx, float ty, float tz, float qx, float qy, float qz, float qw, float sx, float sy, float sz) {
        check(node);
        int o = node * LOCAL;
        local[o] = tx;
        local[o + 1] = ty;
        local[o + 2] = tz;
        storeRotation(o + 3, qx, qy, qz, qw);
        local[o + 7] = sx;
        local[o + 8] = sy;
        local[o + 9] = sz;
        markDirty(node);
    }

    /**
     * Sets the whole local transform of {@code node} from a {@link Transformf}.
     *
     * @param node the node index
     * @param t the transform; must not be {@code null}
     */
    public void setLocal(int node, Transformf t) {
        Vec3f p = t.translation(), s = t.scale();
        Quatf q = t.rotation();
        setLocal(node, p.x(), p.y(), p.z(), q.x(), q.y(), q.z(), q.w(), s.x(), s.y(), s.z());
    }

    /**
     * Sets the local translation of {@code node}.
     *
     * @param node the node index
     * @param x the x component
     * @param y the y component
     * @param z the z component
     */
    public void setTranslation(int node, float x, float y, float z) {
        check(node);
        int o = node * LOCAL;
        local[o] = x;
        local[o + 1] = y;
        local[o + 2] = z;
        markDirty(node);
    }

    /**
     * Sets the rotation; the quaternion is normalised.
     *
     * @param node the node index
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @param w the w component
     */
    public void setRotation(int node, float x, float y, float z, float w) {
        check(node);
        storeRotation(node * LOCAL + 3, x, y, z, w);
        markDirty(node);
    }

    /**
     * Sets the local scale of {@code node}.
     *
     * @param node the node index
     * @param x the x component
     * @param y the y component
     * @param z the z component
     */
    public void setScale(int node, float x, float y, float z) {
        check(node);
        int o = node * LOCAL;
        local[o + 7] = x;
        local[o + 8] = y;
        local[o + 9] = z;
        markDirty(node);
    }

    /**
     * Reads the local transform of a node as an object; allocates, so it is for inspection and
     * tests, not for loops.
     *
     * @param node the node index
     * @return the node's local transform as a value (allocates; for inspection and tests, not for
     *     loops)
     */
    public Transformf local(int node) {
        check(node);
        int o = node * LOCAL;
        return new Transformf(new Vec3f(local[o], local[o + 1], local[o + 2]),
                new Quatf(local[o + 3], local[o + 4], local[o + 5], local[o + 6]),
                new Vec3f(local[o + 7], local[o + 8], local[o + 9]));
    }

    private void storeRotation(int o, float x, float y, float z, float w) {
        double len = Math.sqrt((double) x * x + (double) y * y + (double) z * z + (double) w * w);
        if (len > 1e-20 && Double.isFinite(len)) {
            local[o] = (float) (x / len);
            local[o + 1] = (float) (y / len);
            local[o + 2] = (float) (z / len);
            local[o + 3] = (float) (w / len);
        } else {
            local[o] = 0f;
            local[o + 1] = 0f;
            local[o + 2] = 0f;
            local[o + 3] = 1f;
        }
    }

    private void markDirty(int node) {
        dirty[node] = true;
        if (node < firstDirty) {
            firstDirty = node;
        }
    }

    private void check(int node) {
        if (node < 0 || node >= size) {
            throw new IndexOutOfBoundsException("node " + node + " of " + size);
        }
    }

    // ---------------------------------------------------------------- world matrices

    /**
     * Tells whether a node, or one of its ancestors, has changed since the last update and
     * therefore will be recomputed.
     *
     * @param node the node index
     * @return {@code true} if the node's local transform (or an ancestor's) changed since the last
     *     {@link #update()}
     */
    public boolean isDirty(int node) {
        check(node);
        int n = node;
        while (n >= 0) {
            if (dirty[n]) {
                return true;
            }
            n = parent[n];
        }
        return false;
    }

    /**
     * Recomputes the world matrix of every node whose local transform or ancestry changed, in one
     * forward pass, and clears the dirty flags.
     *
     * <p>Returns how many nodes were recomputed.
     *
     * @return how many nodes were recomputed
     */
    public int update() {
        int count = 0;
        float[] w = world.data();
        int start = firstDirty;
        for (int i = start; i < size; i++) {
            int p = parent[i];
            if (!dirty[i]) {
                if (p < 0 || !dirty[p]) {
                    continue;
                }
                dirty[i] = true; // an ancestor changed: this node follows (and passes it on to its own children)
            }
            compute(w, i, p);
            count++;
        }
        for (int i = Math.min(start, size); i < size; i++) {
            dirty[i] = false;
        }
        firstDirty = Integer.MAX_VALUE;
        lastUpdated = count;
        return count;
    }

    /**
     * Counts the nodes that the last update recomputed, which shows how effective dirty tracking
     * is.
     *
     * @return how many nodes the last {@link #update()} recomputed
     */
    public int lastUpdateCount() {
        return lastUpdated;
    }

    /**
     * Exposes the world matrices as the live internal array in column-major order, ready for upload
     * to the GPU.
     *
     * <p>Live storage: valid after {@link #update()}, and replaced when the hierarchy grows, so
     * fetch it again after adding nodes.
     *
     * @return world matrices, 16 floats per node in column-major (GPU) order, indexed by node
     */
    public Mat4fArray worldMatrices() {
        return world;
    }

    /**
     * Reads the world matrix of a node as an object; allocates, so it is for inspection and tests,
     * not for loops.
     *
     * <p>Call {@link #update()} first.
     *
     * @param node the node index
     * @return the world matrix of {@code node} as a value (allocates)
     */
    public Mat4f worldMatrix(int node) {
        check(node);
        return world.get(node);
    }

    /**
     * Computes {@code world[i] = world[parent] * T * R * S} for an affine chain, writing 16 floats
     * at {@code i * 16}.
     */
    private void compute(float[] w, int i, int p) {
        TransformMath.compose(w, i, p, local, i * LOCAL);
    }
}
