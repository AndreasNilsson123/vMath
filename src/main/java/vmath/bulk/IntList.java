package vmath.bulk;

import java.util.Arrays;

/**
 * A growable {@code int[]}: the result buffer for spatial queries. Reuse one instance across frames
 * ({@link #clear()} keeps the storage), so queries allocate nothing once it has grown to its working size.
 *
 * <p><b>Thread safety.</b> Not thread-safe: it is mutable, so use one instance per thread or synchronise externally. Concurrent reads are safe only
 * while no thread is writing.
 */
public final class IntList {

    private int[] data;
    private int size;

    public IntList() {
        this(64);
    }

    public IntList(int capacity) {
        this.data = new int[Math.max(capacity, 4)];
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void clear() {
        size = 0;
    }

    public void add(int v) {
        if (size == data.length) {
            data = Arrays.copyOf(data, size * 2);
        }
        data[size++] = v;
    }

    public int get(int i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException(i);
        }
        return data[i];
    }

    /** The live backing array, valid for indices below {@link #size()}; replaced when the list grows. */
    public int[] array() {
        return data;
    }

    public int[] toArray() {
        return Arrays.copyOf(data, size);
    }

    /** Sorts ascending, which is handy for comparing query results. */
    public void sort() {
        Arrays.sort(data, 0, size);
    }
}
