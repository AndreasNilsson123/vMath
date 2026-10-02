package vmath.mem;

import java.lang.foreign.MemorySegment;
import vmath.annotations.Experimental;

/**
 * A bump (linear) allocator over a range of bytes: {@link #allocate} hands out the next aligned piece, nothing is freed individually, and {@link #reset} (or
 * {@link #rewind} to a {@link #mark}) releases everything at once. The fastest allocator there is, for data with one shared lifetime such as the transient uploads
 * of one frame or the contents of a level.
 *
 * <p>The allocator only does the arithmetic: it returns <b>byte offsets</b> into a range of {@link #capacity()} bytes, so it works the same for a heap segment, a
 * native one or a persistently mapped GPU buffer (use {@link #slice} to get a {@link MemorySegment} view of a result). Running out returns {@link #NONE} instead of
 * throwing, because a full upload buffer is an everyday event for a renderer. Nothing allocates. Not thread-safe.
 */
@Experimental("the allocator set and their signatures may change")
public final class ArenaAllocator {

    /** Returned when the request does not fit. */
    public static final long NONE = -1L;

    private final long capacity;
    private final MemorySegment backing;
    private long used;

    /** An allocator over {@code capacity} bytes, without a backing segment (offsets only). */
    public ArenaAllocator(long capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must not be negative: " + capacity);
        }
        this.capacity = capacity;
        this.backing = null;
    }

    /** An allocator over the whole of {@code backing}. */
    public ArenaAllocator(MemorySegment backing) {
        this.capacity = backing.byteSize();
        this.backing = backing;
    }

    static void checkAlignment(long alignment) {
        if (alignment < 1 || (alignment & (alignment - 1)) != 0) {
            throw new IllegalArgumentException("the alignment must be a power of two: " + alignment);
        }
    }

    /** The size of the range in bytes. */
    public long capacity() {
        return capacity;
    }

    /** Bytes handed out so far, including the padding that alignment cost. */
    public long used() {
        return used;
    }

    /** The bytes still available, ignoring the padding a later aligned request may need: {@code capacity() - used()}. */
    public long remaining() {
        return capacity - used;
    }

    /**
     * The offset of a new piece of {@code size} bytes aligned to {@code alignment} (a power of two; the offset is relative to the start of the range, so the range itself
     * must be at least that aligned), or {@link #NONE} if it does not fit.
     */
    public long allocate(long size, long alignment) {
        checkAlignment(alignment);
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative: " + size);
        }
        long start = (used + alignment - 1) & -alignment;
        if (start < used || size > capacity - start) { // overflow of the rounding, or no room
            return NONE;
        }
        used = start + size;
        return start;
    }

    /** A position to {@link #rewind} to later. */
    public long mark() {
        return used;
    }

    /** Releases everything allocated after {@code mark}. */
    public void rewind(long mark) {
        if (mark < 0 || mark > used) {
            throw new IllegalArgumentException("mark " + mark + " is not a position between 0 and " + used);
        }
        used = mark;
    }

    /** Releases everything. */
    public void reset() {
        used = 0;
    }

    /** The backing segment, or {@code null} if the allocator was built from a size only. */
    public MemorySegment segment() {
        return backing;
    }

    /** A view of {@code size} bytes at {@code offset} of the backing segment (allocates the view object, so keep it off hot paths). */
    public MemorySegment slice(long offset, long size) {
        if (backing == null) {
            throw new IllegalStateException("this allocator has no backing segment");
        }
        return backing.asSlice(offset, size);
    }
}
