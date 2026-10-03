package vmath.bulk;

import java.util.Arrays;

/**
 * A growable {@code int[]}: the result buffer for spatial queries.
 *
 * <p>Reuse one instance across frames ({@link #clear()} keeps the storage), so queries allocate
 * nothing once it has grown to its working size.
 *
 * <p><b>Thread safety.</b> Not thread-safe: it is mutable, so use one instance per thread or
 * synchronise externally. Concurrent reads are safe only while no thread is writing.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * IntList list = new IntList();
 * list.add(5);
 * list.add(2);
 * list.sort();
 * int first = list.get(0);                                                  // 2
 * int[] copy = list.toArray();
 * }</pre>
 */
public final class IntList {

    private int[] data;
    private int size;

    /**
     * Creates an empty list with room for 64 values.
     */
    public IntList() {
        this(64);
    }

    /**
     * Creates an empty list with room for {@code capacity} values (at least 4).
     *
     * @param capacity the capacity in elements
     */
    public IntList(int capacity) {
        this.data = new int[Math.max(capacity, 4)];
    }

    /**
     * Counts the values in the list.
     *
     * @return the number of values
     */
    public int size() {
        return size;
    }

    /**
     * Tests whether the list is empty.
     *
     * @return {@code true} when the list holds no value
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Removes all values; the capacity is kept.
     */
    public void clear() {
        size = 0;
    }

    /**
     * Appends a value; the array doubles when it is full.
     *
     * @param v the value to append
     */
    public void add(int v) {
        if (size == data.length) {
            data = Arrays.copyOf(data, size * 2);
        }
        data[size++] = v;
    }

    /**
     * Reads the value at an index, checked against the size.
     *
     * @param i the index
     * @return the value at {@code i}; {@link IndexOutOfBoundsException} for an index that is not
     *     below {@link #size()}
     * @throws IndexOutOfBoundsException if {@code i} is not below {@link #size()}
     */
    public int get(int i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException(i);
        }
        return data[i];
    }

    /**
     * Exposes the backing array as a live array, which is replaced when the list grows, so do not
     * cache it; only the indices below the size are meaningful.
     *
     * @return the live backing array, valid for indices below {@link #size()}; replaced when the
     *     list grows
     */
    public int[] array() {
        return data;
    }

    /**
     * Copies the values into a new array of exactly the list's size.
     *
     * @return a copy of the values
     */
    public int[] toArray() {
        return Arrays.copyOf(data, size);
    }

    /**
     * Sorts ascending, which is handy for comparing query results.
     */
    public void sort() {
        Arrays.sort(data, 0, size);
    }
}
