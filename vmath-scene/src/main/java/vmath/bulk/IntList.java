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

    /** An empty list with room for 64 values. */
    public IntList() {
        this(64);
    }

    /** An empty list with room for {@code capacity} values (at least 4). */
    public IntList(int capacity) {
        this.data = new int[Math.max(capacity, 4)];
    }

    /** The number of values. */
    public int size() {
        return size;
    }

    /** True when the list holds no value. */
    public boolean isEmpty() {
        return size == 0;
    }

    /** Removes all values; the capacity is kept. */
    public void clear() {
        size = 0;
    }

    /** Appends a value; the array doubles when it is full. */
    public void add(int v) {
        if (size == data.length) {
            data = Arrays.copyOf(data, size * 2);
        }
        data[size++] = v;
    }

    /** The value at {@code i}; {@link IndexOutOfBoundsException} for an index that is not below {@link #size()}. */
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

    /** A copy of the values. */
    public int[] toArray() {
        return Arrays.copyOf(data, size);
    }

    /** Sorts ascending, which is handy for comparing query results. */
    public void sort() {
        Arrays.sort(data, 0, size);
    }
}
