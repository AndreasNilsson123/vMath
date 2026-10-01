package vmath.bulk;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import vmath.annotations.Experimental;

/**
 * Which elements of an array changed since the last upload, as a bitset, so that only the changed part of a GPU buffer is written. Mark elements as you change them
 * ({@link #mark}, {@link #markRange}); at upload time {@link #ranges} or {@link #forEachRange} give the changed runs, with runs separated by a small gap merged
 * ({@code maxGap}), because one slightly larger copy is cheaper than two calls. {@link #uploadFloats} does the copy for the common case of a {@code float[]}
 * container going into a mapped buffer.
 *
 * <p>With several frames in flight each buffer copy needs everything that changed since <em>that</em> copy was last written, not since the previous frame: use
 * {@link FrameDirtyRanges}, which keeps one set per buffer.
 *
 * <p>Nothing here allocates after construction (the set grows with {@link #ensureCapacity}). Not thread-safe.
 */
@Experimental("the upload helpers may grow")
public final class DirtyRanges {

    /** Receives the changed runs: elements {@code [from, to)}. */
    @FunctionalInterface
    public interface RangeVisitor {
        void range(int from, int to);
    }

    private long[] words;
    private int capacity;

    /** A set for elements {@code 0 .. capacity - 1}, all clean. */
    public DirtyRanges(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must not be negative: " + capacity);
        }
        this.capacity = capacity;
        this.words = new long[(capacity + 63) >>> 6];
    }

    public int capacity() {
        return capacity;
    }

    /** Grows the set to at least {@code n} elements; the new elements are clean. */
    public void ensureCapacity(int n) {
        if (n <= capacity) {
            return;
        }
        int need = (n + 63) >>> 6;
        if (need > words.length) {
            words = Arrays.copyOf(words, Math.max(need, words.length * 2));
        }
        capacity = n;
    }

    private void check(int i) {
        if (i < 0 || i >= capacity) {
            throw new IndexOutOfBoundsException("element " + i + ", capacity " + capacity);
        }
    }

    public void mark(int i) {
        check(i);
        words[i >>> 6] |= 1L << i;
    }

    /** Marks elements {@code [from, to)}. */
    public void markRange(int from, int to) {
        if (from < 0 || to > capacity || from > to) {
            throw new IndexOutOfBoundsException("range [" + from + ", " + to + ") outside 0.." + capacity);
        }
        if (from == to) {
            return;
        }
        int w0 = from >>> 6, w1 = (to - 1) >>> 6;
        long first = -1L << from, last = -1L >>> (63 - ((to - 1) & 63));
        if (w0 == w1) {
            words[w0] |= first & last;
        } else {
            words[w0] |= first;
            for (int w = w0 + 1; w < w1; w++) {
                words[w] = -1L;
            }
            words[w1] |= last;
        }
    }

    /** Marks every element, for example after the array was rebuilt or the buffer was recreated. */
    public void markAll() {
        markRange(0, capacity);
    }

    public boolean isDirty(int i) {
        check(i);
        return (words[i >>> 6] & (1L << i)) != 0L;
    }

    /** Marks everything clean. */
    public void clear() {
        Arrays.fill(words, 0L);
    }

    public boolean isEmpty() {
        for (long w : words) {
            if (w != 0L) {
                return false;
            }
        }
        return true;
    }

    /** The number of dirty elements. */
    public int count() {
        int n = 0;
        for (long w : words) {
            n += Long.bitCount(w);
        }
        return n;
    }

    /** The first dirty element at or after {@code from}, or -1. */
    public int firstDirty(int from) {
        if (from < 0) {
            throw new IndexOutOfBoundsException("from " + from);
        }
        if (from >= capacity) {
            return -1;
        }
        int wi = from >>> 6;
        long w = words[wi] & (-1L << from);
        while (true) {
            if (w != 0L) {
                return (wi << 6) + Long.numberOfTrailingZeros(w);
            }
            if (++wi >= words.length) {
                return -1;
            }
            w = words[wi];
        }
    }

    /** The first clean element at or after {@code from}, or {@link #capacity()} if the rest is dirty. */
    public int firstClean(int from) {
        if (from < 0) {
            throw new IndexOutOfBoundsException("from " + from);
        }
        if (from >= capacity) {
            return capacity;
        }
        int wi = from >>> 6;
        long w = ~words[wi] & (-1L << from);
        while (true) {
            if (w != 0L) {
                return Math.min(capacity, (wi << 6) + Long.numberOfTrailingZeros(w));
            }
            if (++wi >= words.length) {
                return capacity;
            }
            w = ~words[wi];
        }
    }

    /**
     * Writes the changed runs to {@code out} as pairs {@code from, to} (elements {@code [from, to)}), ascending, merging runs that are at most {@code maxGap} clean
     * elements apart. Returns the number of runs found; only as many as fit in {@code out} are written, so a result above {@code out.length / 2} means the array
     * was too small.
     */
    public int ranges(int maxGap, int[] out) {
        if (maxGap < 0) {
            throw new IllegalArgumentException("maxGap must not be negative: " + maxGap);
        }
        int count = 0;
        int start = firstDirty(0);
        while (start >= 0) {
            int end = firstClean(start);
            int next = firstDirty(end);
            while (next >= 0 && next - end <= maxGap) {
                end = firstClean(next);
                next = firstDirty(end);
            }
            if (2 * count + 1 < out.length) {
                out[2 * count] = start;
                out[2 * count + 1] = end;
            }
            count++;
            start = next;
        }
        return count;
    }

    /** Calls {@code visitor} for each changed run, as {@link #ranges}. Allocation-free if the visitor is reused. */
    public void forEachRange(int maxGap, RangeVisitor visitor) {
        if (maxGap < 0) {
            throw new IllegalArgumentException("maxGap must not be negative: " + maxGap);
        }
        int start = firstDirty(0);
        while (start >= 0) {
            int end = firstClean(start);
            int next = firstDirty(end);
            while (next >= 0 && next - end <= maxGap) {
                end = firstClean(next);
                next = firstDirty(end);
            }
            visitor.range(start, end);
            start = next;
        }
    }

    /**
     * Copies the changed runs of a {@code float[]} container into a buffer and marks everything clean. Element {@code i} (of {@code floatsPerElement} floats, starting at
     * {@code src[i * floatsPerElement]}) goes to byte {@code dstOffset + i * floatsPerElement * 4} of {@code dst}, native byte order; only elements below {@code count}
     * are copied. Returns the bytes copied.
     */
    public long uploadFloats(float[] src, int floatsPerElement, int count, MemorySegment dst, long dstOffset, int maxGap) {
        if (floatsPerElement < 1 || count < 0 || count > capacity || (long) count * floatsPerElement > src.length) {
            throw new IllegalArgumentException("count " + count + " x " + floatsPerElement + " floats does not fit the source or the set");
        }
        long bytes = 0;
        int start = firstDirty(0);
        while (start >= 0 && start < count) {
            int end = firstClean(start);
            int next = firstDirty(end);
            while (next >= 0 && next - end <= maxGap) {
                end = firstClean(next);
                next = firstDirty(end);
            }
            end = Math.min(end, count);
            int n = (end - start) * floatsPerElement;
            MemorySegment.copy(src, start * floatsPerElement, dst, ValueLayout.JAVA_FLOAT_UNALIGNED, dstOffset + (long) start * floatsPerElement * Float.BYTES, n);
            bytes += (long) n * Float.BYTES;
            start = next;
        }
        clear();
        return bytes;
    }

    /** As {@link #uploadFloats} for an off-heap source such as {@link SegmentFloatArray#segment()}, with elements of {@code elementBytes} bytes. */
    public long uploadSegment(MemorySegment src, long elementBytes, int count, MemorySegment dst, long dstOffset, int maxGap) {
        if (elementBytes < 1 || count < 0 || count > capacity || (long) count * elementBytes > src.byteSize()) {
            throw new IllegalArgumentException("count " + count + " x " + elementBytes + " bytes does not fit the source or the set");
        }
        long bytes = 0;
        int start = firstDirty(0);
        while (start >= 0 && start < count) {
            int end = firstClean(start);
            int next = firstDirty(end);
            while (next >= 0 && next - end <= maxGap) {
                end = firstClean(next);
                next = firstDirty(end);
            }
            end = Math.min(end, count);
            long n = (end - start) * elementBytes;
            MemorySegment.copy(src, start * elementBytes, dst, dstOffset + start * elementBytes, n);
            bytes += n;
            start = next;
        }
        clear();
        return bytes;
    }
}
