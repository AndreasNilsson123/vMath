package vmath.spatial;

import java.util.Arrays;

/**
 * Result buffer of the k-nearest-neighbour queries: the {@code k} closest objects to a point, by the squared distance from
 * the point to the object's box (0 when the point is inside it). Reuse one instance per caller; queries allocate nothing.
 *
 * <p>While a query runs the buffer is a bounded max-heap on plain arrays, so the worst kept candidate is known at once and
 * is used to prune the search ({@link #bound()}). When the query returns the entries are sorted <b>ascending</b> by
 * {@code (distance, index)}: ties are broken by the smaller index, so results are deterministic and comparable with a brute
 * force scan. Read them with {@link #index} and {@link #distanceSquared}.
 *
 * <p>An object whose distance is NaN (a NaN box or a NaN query point) is never kept.
 */
public final class Neighbors {

    private int k;
    private int size;
    private final int[] ids;
    private final float[] d2;

    /** A buffer that can hold up to {@code capacity} neighbours. */
    public Neighbors(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1: " + capacity);
        }
        ids = new int[capacity];
        d2 = new float[capacity];
        k = capacity;
    }

    /** Empties the buffer and sets how many neighbours the next query keeps ({@code 0 < k <= capacity}). */
    public void reset(int k) {
        if (k < 1 || k > ids.length) {
            throw new IllegalArgumentException("k must be in [1, " + ids.length + "]: " + k);
        }
        this.k = k;
        this.size = 0;
    }

    /** Empties the buffer, keeping the current {@code k}. */
    public void reset() {
        size = 0;
    }

    public int capacity() {
        return ids.length;
    }

    /** How many neighbours a query keeps at most. */
    public int k() {
        return k;
    }

    /** Neighbours found; less than {@link #k()} when the structure holds fewer objects. */
    public int size() {
        return size;
    }

    /** The {@code i}th nearest object's index (primitive index or user data, depending on the structure), 0 = nearest. */
    public int index(int i) {
        checkRange(i);
        return ids[i];
    }

    /** Squared distance from the query point to the {@code i}th nearest object's box. */
    public float distanceSquared(int i) {
        checkRange(i);
        return d2[i];
    }

    private void checkRange(int i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException("neighbour " + i + " of " + size);
        }
    }

    // ---------------------------------------------------------------- for the queries

    /** Squared distance beyond which a candidate cannot enter the buffer: +Infinity until it is full. */
    float bound() {
        return size < k ? Float.POSITIVE_INFINITY : d2[0];
    }

    /** Offers a candidate; keeps it if it is among the {@code k} best so far. */
    void offer(int id, float dist2) {
        if (dist2 != dist2) {
            return;
        }
        if (size < k) {
            int i = size++;
            ids[i] = id;
            d2[i] = dist2;
            siftUp(i);
        } else if (dist2 < d2[0] || (dist2 == d2[0] && id < ids[0])) {
            ids[0] = id;
            d2[0] = dist2;
            siftDown(0, size);
        }
    }

    /** Sorts ascending; called by the query when it is done. */
    void finish() {
        for (int end = size - 1; end > 0; end--) {
            swap(0, end);
            siftDown(0, end);
        }
    }

    /** True when entry {@code a} is worse (farther, or the same distance and a larger index) than entry {@code b}. */
    private boolean worse(int a, int b) {
        return d2[a] > d2[b] || (d2[a] == d2[b] && ids[a] > ids[b]);
    }

    private void siftUp(int i) {
        while (i > 0) {
            int p = (i - 1) >>> 1;
            if (!worse(i, p)) {
                break;
            }
            swap(i, p);
            i = p;
        }
    }

    private void siftDown(int i, int n) {
        while (true) {
            int l = 2 * i + 1;
            if (l >= n) {
                return;
            }
            int c = l + 1 < n && worse(l + 1, l) ? l + 1 : l;
            if (!worse(c, i)) {
                return;
            }
            swap(i, c);
            i = c;
        }
    }

    private void swap(int a, int b) {
        int ti = ids[a];
        ids[a] = ids[b];
        ids[b] = ti;
        float td = d2[a];
        d2[a] = d2[b];
        d2[b] = td;
    }

    @Override
    public String toString() {
        return "Neighbors" + Arrays.toString(Arrays.copyOf(ids, size));
    }
}
