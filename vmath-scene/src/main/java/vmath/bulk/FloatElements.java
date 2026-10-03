package vmath.bulk;

import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * The shared mechanics of the containers that keep fixed-size elements ({@code stride} floats each)
 * in one {@code float[]}: {@link Vec3fArray}, {@link Vec4fArray}, {@link QuatArray},
 * {@link Mat4fArray} and {@link TransformArray}.
 *
 * <p>Growth, bounds checks, the strided writers and the compaction are written here once; the
 * subclasses add the element accessors and the batch kernels, which read the array and the count
 * directly. Public so that the inherited methods are part of the documented API of each container;
 * it cannot be extended outside this package (its constructor is package-private).
 *
 * <p><b>Thread safety.</b> Not thread-safe: it is mutable, so use one instance per thread or
 * synchronise externally. Concurrent reads are safe only while no thread is writing.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Vec3fArray positions = new Vec3fArray(16);                              // a FloatElements container
 * positions.add(1f, 2f, 3f);
 * positions.add(4f, 5f, 6f);
 * FloatBuffer buffer = FloatBuffer.allocate(6);
 * positions.writeTo(buffer, 0);                                            // absolute write
 * positions.removeSwap(0);                                                 // O(1): the last element takes its place
 * }</pre>
 */
public abstract class FloatElements {

    /**
     * The backing array; element {@code i} starts at {@code i * stride}.
     *
     * <p>Replaced when the container grows.
     */
    float[] data;
    /**
     * The element count.
     */
    int size;
    private final int stride;

    FloatElements(int capacity, int stride) {
        this.stride = stride;
        this.data = new float[Math.max(capacity, 1) * stride];
    }

    /**
     * Counts the elements.
     *
     * @return the number of elements
     */
    public int size() {
        return size;
    }

    /**
     * Reports how many elements fit before the array is reallocated.
     *
     * @return the number of elements that fit without growing
     */
    public int capacity() {
        return data.length / stride;
    }

    /**
     * Removes all elements; the capacity is kept.
     */
    public void clear() {
        size = 0;
    }

    /**
     * Sets the element count after writing into {@link #data()} directly.
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
     * Makes room for {@code n} elements; the array at least doubles when it has to grow, and the
     * contents are kept.
     *
     * @param n the number of elements
     * @throws OutOfMemoryError if {@code n} elements do not fit in one array
     */
    public void ensureCapacity(int n) {
        if ((long) n * stride > data.length) {
            long grown = Math.max((long) n, capacity() * 2L) * stride;
            if (grown > Integer.MAX_VALUE - 8) {
                throw new OutOfMemoryError("a container of " + n + " elements of " + stride + " floats does not fit in one array");
            }
            data = Arrays.copyOf(data, (int) grown);
        }
    }

    final void checkIndex(int i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException("index " + i + ", size " + size);
        }
    }

    /**
     * Exposes the backing array of the container as a live array, which is replaced when the
     * container grows, so do not cache it.
     *
     * @return the live backing array (element {@code i} starts at {@code i * STRIDE}); replaced
     *     when the container grows
     */
    public float[] data() {
        return data;
    }

    /**
     * Writes all elements into {@code dst} starting at byte {@code offset}, {@code strideBytes}
     * apart (at least the element size in bytes), in the given byte order.
     *
     * <p>A larger stride leaves the bytes between elements alone, so this fills one attribute of an
     * interleaved buffer.
     *
     * @param dst receives the result; must not be {@code null}
     * @param offset the index of the first element to read or write
     * @param strideBytes the stride bytes
     * @param order the order; must not be {@code null}
     */
    public void writeTo(MemorySegment dst, long offset, long strideBytes, ByteOrder order) {
        Strided.write(data, 0, stride, size, dst, offset, strideBytes, order);
    }

    /**
     * Replaces the contents with {@code count} elements read from {@code src}
     * ({@link #writeTo(MemorySegment, long, long, ByteOrder)} reversed).
     *
     * @param src the source to read from; must not be {@code null}
     * @param offset the index of the first element to read or write
     * @param strideBytes the stride bytes
     * @param order the order; must not be {@code null}
     * @param count the number of elements
     */
    public void readFrom(MemorySegment src, long offset, long strideBytes, ByteOrder order, int count) {
        ensureCapacity(count);
        Strided.read(src, offset, strideBytes, order, data, 0, stride, count);
        size = count;
    }

    /**
     * Writes all elements at the absolute position {@code index}; does not move the buffer
     * position.
     *
     * @param dst receives the result; must not be {@code null}
     * @param index the index
     */
    public void writeTo(FloatBuffer dst, int index) {
        dst.put(index, data, 0, size * stride);
    }

    /**
     * Removes element {@code i} by moving the last element into its place: O(1), the order of the
     * others is kept except for that one.
     *
     * <p>Returns the index the moved element had before (the old last index), or -1 if {@code i}
     * was the last element. Mirror it in parallel arrays with the same call.
     *
     * @param i the index
     * @return the index the moved element had before (the old last index), or -1 if {@code i} was
     *     the last element
     */
    public int removeSwap(int i) {
        checkIndex(i);
        int moved = Compaction.swapRemove(data, stride, size, i);
        size--;
        return moved;
    }

    /**
     * Keeps only the elements whose bit is set in {@code keep} (bit {@code i} for element
     * {@code i}), in their original order.
     *
     * <p>Returns the new size.
     *
     * @param keep the keep; must not be {@code null}
     * @return the new size
     */
    public int compact(VisibilitySet keep) {
        size = Compaction.stable(data, stride, size, keep);
        return size;
    }
}
