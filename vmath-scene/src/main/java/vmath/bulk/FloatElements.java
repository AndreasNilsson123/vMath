package vmath.bulk;

import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * The shared mechanics of the containers that keep fixed-size elements ({@code stride} floats each) in one {@code float[]}: {@link Vec3fArray}, {@link Vec4fArray},
 * {@link QuatArray}, {@link Mat4fArray} and {@link TransformArray}. Growth, bounds checks, the strided writers and the compaction are written here once; the
 * subclasses add the element accessors and the batch kernels, which read the array and the count directly. Public so that the inherited methods are part of the
 * documented API of each container; it cannot be extended outside this package (its constructor is package-private).
 *
 * <p><b>Thread safety.</b> Not thread-safe: it is mutable, so use one instance per thread or synchronise externally. Concurrent reads are safe only while no
 * thread is writing.
 */
public abstract class FloatElements {

    /** The backing array; element {@code i} starts at {@code i * stride}. Replaced when the container grows. */
    float[] data;
    /** The element count. */
    int size;
    private final int stride;

    FloatElements(int capacity, int stride) {
        this.stride = stride;
        this.data = new float[Math.max(capacity, 1) * stride];
    }

    /** The number of elements. */
    public int size() {
        return size;
    }

    /** The number of elements that fit without growing. */
    public int capacity() {
        return data.length / stride;
    }

    /** Removes all elements; the capacity is kept. */
    public void clear() {
        size = 0;
    }

    /** Sets the element count after writing into {@link #data()} directly. */
    public void setSize(int n) {
        if (n < 0 || n > capacity()) {
            throw new IllegalArgumentException("size " + n + " outside 0.." + capacity());
        }
        size = n;
    }

    /** Makes room for {@code n} elements; the array at least doubles when it has to grow, and the contents are kept. */
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

    /** The live backing array (element {@code i} starts at {@code i * STRIDE}); replaced when the container grows. */
    public float[] data() {
        return data;
    }

    /**
     * Writes all elements into {@code dst} starting at byte {@code offset}, {@code strideBytes} apart (at least the element size in bytes), in the given byte
     * order. A larger stride leaves the bytes between elements alone, so this fills one attribute of an interleaved buffer.
     */
    public void writeTo(MemorySegment dst, long offset, long strideBytes, ByteOrder order) {
        Strided.write(data, 0, stride, size, dst, offset, strideBytes, order);
    }

    /** Replaces the contents with {@code count} elements read from {@code src} ({@link #writeTo(MemorySegment, long, long, ByteOrder)} reversed). */
    public void readFrom(MemorySegment src, long offset, long strideBytes, ByteOrder order, int count) {
        ensureCapacity(count);
        Strided.read(src, offset, strideBytes, order, data, 0, stride, count);
        size = count;
    }

    /** Absolute write of all elements at {@code index}; does not move the buffer position. */
    public void writeTo(FloatBuffer dst, int index) {
        dst.put(index, data, 0, size * stride);
    }

    /**
     * Removes element {@code i} by moving the last element into its place: O(1), the order of the others is kept except for that one. Returns the index the
     * moved element had before (the old last index), or -1 if {@code i} was the last element. Mirror it in parallel arrays with the same call.
     */
    public int removeSwap(int i) {
        checkIndex(i);
        int moved = Compaction.swapRemove(data, stride, size, i);
        size--;
        return moved;
    }

    /** Keeps only the elements whose bit is set in {@code keep} (bit {@code i} for element {@code i}), in their original order. Returns the new size. */
    public int compact(VisibilitySet keep) {
        size = Compaction.stable(data, stride, size, keep);
        return size;
    }
}
