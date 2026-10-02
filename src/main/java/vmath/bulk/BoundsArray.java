package vmath.bulk;

import java.util.Arrays;
import vmath.geo.Aabbf;

/**
 * Axis-aligned bounds of many objects in structure-of-arrays layout: six parallel {@code float[]}, one per component.
 * A culling or BVH kernel that needs one component of every object then streams a single contiguous array, which is
 * what makes those loops cache-friendly and auto-vectorizable. There is no object per element.
 *
 * <p>The backing arrays are exposed ({@link #minXs()} and friends) for kernels. They are replaced when the container
 * grows, so re-fetch them after {@code add}/{@code ensureCapacity} instead of caching them.
 *
 * <p><b>Thread safety.</b> Not thread-safe: it is mutable, so use one instance per thread or synchronise externally. Concurrent reads are safe only
 * while no thread is writing.
 */
public final class BoundsArray {

    private float[] minX;
    private float[] minY;
    private float[] minZ;
    private float[] maxX;
    private float[] maxY;
    private float[] maxZ;
    private int size;

    public BoundsArray(int capacity) {
        int c = Math.max(capacity, 4);
        minX = new float[c];
        minY = new float[c];
        minZ = new float[c];
        maxX = new float[c];
        maxY = new float[c];
        maxZ = new float[c];
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return minX.length;
    }

    public void clear() {
        size = 0;
    }

    /** Sets the element count after writing into the backing arrays directly. Must not exceed the capacity. */
    public void setSize(int n) {
        if (n < 0 || n > capacity()) {
            throw new IllegalArgumentException("size " + n + " outside 0.." + capacity());
        }
        size = n;
    }

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

    /** Appends a box and returns its index. */
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

    public int add(Aabbf b) {
        return add(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }

    public void set(int i, float x0, float y0, float z0, float x1, float y1, float z1) {
        checkIndex(i);
        minX[i] = x0;
        minY[i] = y0;
        minZ[i] = z0;
        maxX[i] = x1;
        maxY[i] = y1;
        maxZ[i] = z1;
    }

    public void set(int i, Aabbf b) {
        set(i, b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }

    public Aabbf get(int i) {
        checkIndex(i);
        return new Aabbf(minX[i], minY[i], minZ[i], maxX[i], maxY[i], maxZ[i]);
    }

    private void checkIndex(int i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException("index " + i + ", size " + size);
        }
    }

    public float minX(int i) {
        return minX[i];
    }

    public float minY(int i) {
        return minY[i];
    }

    public float minZ(int i) {
        return minZ[i];
    }

    public float maxX(int i) {
        return maxX[i];
    }

    public float maxY(int i) {
        return maxY[i];
    }

    public float maxZ(int i) {
        return maxZ[i];
    }

    public float[] minXs() {
        return minX;
    }

    public float[] minYs() {
        return minY;
    }

    public float[] minZs() {
        return minZ;
    }

    public float[] maxXs() {
        return maxX;
    }

    public float[] maxYs() {
        return maxY;
    }

    public float[] maxZs() {
        return maxZ;
    }

    /** The union of all boxes; {@link Aabbf#EMPTY} when there are none. */
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
     * Sets this array to {@code local[i]} transformed by {@code matrices[i]} (exact for affine matrices), for every
     * element of {@code local}. The kernel for turning per-object local bounds into world bounds each frame.
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

    /** Writes {@code minX, minY, minZ, maxX, maxY, maxZ} for each element, interleaved, e.g. for a GPU culling buffer. */
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
     * Removes box {@code i} by moving the last box into its place: O(1), the order of the others is kept except for that one. Returns the index the moved box had
     * before (the old last index), or -1 if {@code i} was the last box.
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

    /** Keeps only the boxes whose bit is set in {@code keep}, in their original order. Returns the new size. */
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
