package vmath.bulk;

import java.util.Arrays;
import vmath.geo.Aabbf;

/**
 * Axis-aligned bounds of many objects in structure-of-arrays layout: six parallel {@code float[]},
 * one per component.
 *
 * <p>A culling or BVH kernel that needs one component of every object then streams a single
 * contiguous array, which is what makes those loops cache-friendly and auto-vectorizable. There is
 * no object per element.
 *
 * <p>The backing arrays are exposed ({@link #minXs()} and friends) for kernels. They are replaced
 * when the container grows, so re-fetch them after {@code add}/{@code ensureCapacity} instead of
 * caching them.
 *
 * <p><b>Thread safety.</b> Not thread-safe: it is mutable, so use one instance per thread or
 * synchronise externally. Concurrent reads are safe only while no thread is writing.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * BoundsArray bounds = new BoundsArray(1024);
 * int id = bounds.add(-1f, 0f, -1f, 1f, 2f, 1f);
 * bounds.set(id, Aabbf.of(new Vec3f(0f, 0f, 0f), new Vec3f(2f, 2f, 2f)));
 * Aabbf all = bounds.union();                                             // the box around every box
 * float[] minXs = bounds.minXs();                                         // the live arrays: use them in kernels, do not keep them
 * }</pre>
 */
public final class BoundsArray {

    private float[] minX;
    private float[] minY;
    private float[] minZ;
    private float[] maxX;
    private float[] maxY;
    private float[] maxZ;
    private int size;

    /**
     * Creates an empty array with room for {@code capacity} boxes (at least 4); it grows as boxes
     * are added.
     *
     * @param capacity the capacity in elements
     */
    public BoundsArray(int capacity) {
        int c = Math.max(capacity, 4);
        minX = new float[c];
        minY = new float[c];
        minZ = new float[c];
        maxX = new float[c];
        maxY = new float[c];
        maxZ = new float[c];
    }

    /**
     * Counts the boxes.
     *
     * @return the number of boxes
     */
    public int size() {
        return size;
    }

    /**
     * Reports how many boxes fit before the arrays are reallocated.
     *
     * @return the number of boxes that fit without growing
     */
    public int capacity() {
        return minX.length;
    }

    /**
     * Removes all boxes; the capacity is kept.
     */
    public void clear() {
        size = 0;
    }

    /**
     * Sets the element count after writing into the backing arrays directly.
     *
     * <p>Must not exceed the capacity.
     *
     * @param n the number of elements
     * @throws IllegalArgumentException if {@code n} is not in {@code [0, capacity()]}
     */
    public void setSize(int n) {
        if (n < 0 || n > capacity()) {
            throw new IllegalArgumentException("size " + n + " outside 0.." + capacity());
        }
        size = n;
    }

    /**
     * Makes room for {@code n} boxes; the arrays at least double when they have to grow, and the
     * contents are kept.
     *
     * @param n the number of elements
     */
    public void ensureCapacity(int n) {
        if (n > minX.length) {
            int c = Math.max(n, minX.length * 2);
            minX = Arrays.copyOf(minX, c);
            minY = Arrays.copyOf(minY, c);
            minZ = Arrays.copyOf(minZ, c);
            maxX = Arrays.copyOf(maxX, c);
            maxY = Arrays.copyOf(maxY, c);
            maxZ = Arrays.copyOf(maxZ, c);
        }
    }

    /**
     * Appends a box and returns its index.
     *
     * @param x0 the smallest x of the box
     * @param y0 the smallest y of the box
     * @param z0 the smallest z of the box
     * @param x1 the largest x of the box
     * @param y1 the largest y of the box
     * @param z1 the largest z of the box
     * @return its index
     */
    public int add(float x0, float y0, float z0, float x1, float y1, float z1) {
        ensureCapacity(size + 1);
        int i = size++;
        minX[i] = x0;
        minY[i] = y0;
        minZ[i] = z0;
        maxX[i] = x1;
        maxY[i] = y1;
        maxZ[i] = z1;
        return i;
    }

    /**
     * Appends a box and returns its index.
     *
     * @param b the second box; must not be {@code null}
     * @return its index
     */
    public int add(Aabbf b) {
        return add(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }

    /**
     * Replaces box {@code i}; {@link IndexOutOfBoundsException} for an index that is not below
     * {@link #size()}.
     *
     * @param i the index
     * @param x0 the smallest x of the box
     * @param y0 the smallest y of the box
     * @param z0 the smallest z of the box
     * @param x1 the largest x of the box
     * @param y1 the largest y of the box
     * @param z1 the largest z of the box
     */
    public void set(int i, float x0, float y0, float z0, float x1, float y1, float z1) {
        checkIndex(i);
        minX[i] = x0;
        minY[i] = y0;
        minZ[i] = z0;
        maxX[i] = x1;
        maxY[i] = y1;
        maxZ[i] = z1;
    }

    /**
     * Replaces box {@code i}; {@link IndexOutOfBoundsException} for an index that is not below
     * {@link #size()}.
     *
     * @param i the index
     * @param b the second box; must not be {@code null}
     */
    public void set(int i, Aabbf b) {
        set(i, b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }

    /**
     * Reads a box as an object; allocates, so use the per-component accessors in loops.
     *
     * @param i the index
     * @return box {@code i} as a value (allocates); {@link IndexOutOfBoundsException} for an index
     *     that is not below {@link #size()}
     */
    public Aabbf get(int i) {
        checkIndex(i);
        return new Aabbf(minX[i], minY[i], minZ[i], maxX[i], maxY[i], maxZ[i]);
    }

    private void checkIndex(int i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException("index " + i + ", size " + size);
        }
    }

    /**
     * Reads the minimum x of a box without allocating.
     *
     * <p>The single-component getters do not check the index against the size.
     *
     * @param i the index
     * @return the minimum x of box {@code i}
     */
    public float minX(int i) {
        return minX[i];
    }

    /**
     * Reads the minimum y of a box without allocating.
     *
     * @param i the index
     * @return the minimum y of box {@code i}
     */
    public float minY(int i) {
        return minY[i];
    }

    /**
     * Reads the minimum z of a box without allocating.
     *
     * @param i the index
     * @return the minimum z of box {@code i}
     */
    public float minZ(int i) {
        return minZ[i];
    }

    /**
     * Reads the maximum x of a box without allocating.
     *
     * @param i the index
     * @return the maximum x of box {@code i}
     */
    public float maxX(int i) {
        return maxX[i];
    }

    /**
     * Reads the maximum y of a box without allocating.
     *
     * @param i the index
     * @return the maximum y of box {@code i}
     */
    public float maxY(int i) {
        return maxY[i];
    }

    /**
     * Reads the maximum z of a box without allocating.
     *
     * @param i the index
     * @return the maximum z of box {@code i}
     */
    public float maxZ(int i) {
        return maxZ[i];
    }

    /**
     * Exposes the minimum x values as the live array of the structure-of-arrays layout, which is
     * replaced when the container grows, so do not cache it.
     *
     * @return the live array of minimum x values, one per box; replaced when the array grows
     */
    public float[] minXs() {
        return minX;
    }

    /**
     * Exposes the minimum y values as the live array of the structure-of-arrays layout, which is
     * replaced when the container grows, so do not cache it.
     *
     * @return the live array of minimum y values, one per box; replaced when the array grows
     */
    public float[] minYs() {
        return minY;
    }

    /**
     * Exposes the minimum z values as the live array of the structure-of-arrays layout, which is
     * replaced when the container grows, so do not cache it.
     *
     * @return the live array of minimum z values, one per box; replaced when the array grows
     */
    public float[] minZs() {
        return minZ;
    }

    /**
     * Exposes the maximum x values as the live array of the structure-of-arrays layout, which is
     * replaced when the container grows, so do not cache it.
     *
     * @return the live array of maximum x values, one per box; replaced when the array grows
     */
    public float[] maxXs() {
        return maxX;
    }

    /**
     * Exposes the maximum y values as the live array of the structure-of-arrays layout, which is
     * replaced when the container grows, so do not cache it.
     *
     * @return the live array of maximum y values, one per box; replaced when the array grows
     */
    public float[] maxYs() {
        return maxY;
    }

    /**
     * Exposes the maximum z values as the live array of the structure-of-arrays layout, which is
     * replaced when the container grows, so do not cache it.
     *
     * @return the live array of maximum z values, one per box; replaced when the array grows
     */
    public float[] maxZs() {
        return maxZ;
    }

    /**
     * Merges all boxes into one by a linear scan.
     *
     * @return the union of all boxes; {@link Aabbf#EMPTY} when there are none
     */
    public Aabbf union() {
        float x0 = Float.POSITIVE_INFINITY, y0 = x0, z0 = x0;
        float x1 = Float.NEGATIVE_INFINITY, y1 = x1, z1 = x1;
        for (int i = 0; i < size; i++) {
            x0 = Math.min(x0, minX[i]);
            y0 = Math.min(y0, minY[i]);
            z0 = Math.min(z0, minZ[i]);
            x1 = Math.max(x1, maxX[i]);
            y1 = Math.max(y1, maxY[i]);
            z1 = Math.max(z1, maxZ[i]);
        }
        return new Aabbf(x0, y0, z0, x1, y1, z1);
    }

    /**
     * Sets this array to {@code local[i]} transformed by {@code matrices[i]} (exact for affine
     * matrices), for every element of {@code local}.
     *
     * <p>The kernel for turning per-object local bounds into world bounds each frame.
     *
     * @param local the local; must not be {@code null}
     * @param matrices the matrices; must not be {@code null}
     * @throws IllegalArgumentException if there are fewer matrices than boxes
     */
    public void transformFrom(BoundsArray local, Mat4fArray matrices) {
        int n = local.size;
        if (matrices.size() < n) {
            throw new IllegalArgumentException("fewer matrices (" + matrices.size() + ") than boxes (" + n + ")");
        }
        ensureCapacity(n);
        size = n;
        float[] m = matrices.data();
        for (int i = 0, o = 0; i < n; i++, o += Mat4fArray.STRIDE) {
            float cx = (local.minX[i] + local.maxX[i]) * 0.5f;
            float cy = (local.minY[i] + local.maxY[i]) * 0.5f;
            float cz = (local.minZ[i] + local.maxZ[i]) * 0.5f;
            float hx = (local.maxX[i] - local.minX[i]) * 0.5f;
            float hy = (local.maxY[i] - local.minY[i]) * 0.5f;
            float hz = (local.maxZ[i] - local.minZ[i]) * 0.5f;
            float m00 = m[o], m01 = m[o + 1], m02 = m[o + 2];
            float m10 = m[o + 4], m11 = m[o + 5], m12 = m[o + 6];
            float m20 = m[o + 8], m21 = m[o + 9], m22 = m[o + 10];
            float ncx = m00 * cx + m10 * cy + m20 * cz + m[o + 12];
            float ncy = m01 * cx + m11 * cy + m21 * cz + m[o + 13];
            float ncz = m02 * cx + m12 * cy + m22 * cz + m[o + 14];
            float nhx = Math.abs(m00) * hx + Math.abs(m10) * hy + Math.abs(m20) * hz;
            float nhy = Math.abs(m01) * hx + Math.abs(m11) * hy + Math.abs(m21) * hz;
            float nhz = Math.abs(m02) * hx + Math.abs(m12) * hy + Math.abs(m22) * hz;
            minX[i] = ncx - nhx;
            minY[i] = ncy - nhy;
            minZ[i] = ncz - nhz;
            maxX[i] = ncx + nhx;
            maxY[i] = ncy + nhy;
            maxZ[i] = ncz + nhz;
        }
    }

    /**
     * Writes {@code minX, minY, minZ, maxX, maxY, maxZ} for each element, interleaved, e.g. for a
     * GPU culling buffer.
     *
     * @param dst receives the result
     * @param off the index of the first element to read or write
     */
    public void writeInterleaved(float[] dst, int off) {
        for (int i = 0, o = off; i < size; i++, o += 6) {
            dst[o] = minX[i];
            dst[o + 1] = minY[i];
            dst[o + 2] = minZ[i];
            dst[o + 3] = maxX[i];
            dst[o + 4] = maxY[i];
            dst[o + 5] = maxZ[i];
        }
    }

    // ---------------------------------------------------------------- compaction

    /**
     * Removes box {@code i} by moving the last box into its place: O(1), the order of the others is
     * kept except for that one.
     *
     * <p>Returns the index the moved box had before (the old last index), or -1 if {@code i} was
     * the last box.
     *
     * @param i the index
     * @return the index the moved box had before (the old last index), or -1 if {@code i} was the
     *     last box
     */
    public int removeSwap(int i) {
        checkIndex(i);
        int moved = Compaction.swapRemove(minX, 1, size, i);
        Compaction.swapRemove(minY, 1, size, i);
        Compaction.swapRemove(minZ, 1, size, i);
        Compaction.swapRemove(maxX, 1, size, i);
        Compaction.swapRemove(maxY, 1, size, i);
        Compaction.swapRemove(maxZ, 1, size, i);
        size--;
        return moved;
    }

    /**
     * Keeps only the boxes whose bit is set in {@code keep}, in their original order.
     *
     * <p>Returns the new size.
     *
     * @param keep the keep; must not be {@code null}
     * @return the new size
     */
    public int compact(VisibilitySet keep) {
        int n = Compaction.stable(minX, 1, size, keep);
        Compaction.stable(minY, 1, size, keep);
        Compaction.stable(minZ, 1, size, keep);
        Compaction.stable(maxX, 1, size, keep);
        Compaction.stable(maxY, 1, size, keep);
        Compaction.stable(maxZ, 1, size, keep);
        size = n;
        return n;
    }
}
