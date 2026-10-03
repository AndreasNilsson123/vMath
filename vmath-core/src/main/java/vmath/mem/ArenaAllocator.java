package vmath.mem;

import java.lang.foreign.MemorySegment;
import vmath.annotations.Experimental;

/**
 * A bump (linear) allocator over a range of bytes: {@link #allocate} hands out the next aligned
 * piece, nothing is freed individually, and {@link #reset} (or {@link #rewind} to a {@link #mark})
 * releases everything at once.
 *
 * <p>The fastest allocator there is, for data with one shared lifetime such as the transient
 * uploads of one frame or the contents of a level.
 *
 * <p>The allocator only does the arithmetic: it returns <b>byte offsets</b> into a range of
 * {@link #capacity()} bytes, so it works the same for a heap segment, a native one or a
 * persistently mapped GPU buffer (use {@link #slice} to get a {@link MemorySegment} view of a
 * result). Running out returns {@link #NONE} instead of throwing, because a full upload buffer is
 * an everyday event for a renderer. Nothing allocates. Not thread-safe.
 *
 * <p><b>Thread safety.</b> Not thread-safe: use one allocator per thread, or synchronize
 * externally. No method blocks.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * ArenaAllocator arena = new ArenaAllocator(1 << 20);          // offsets only, no backing memory
 * long first = arena.allocate(256, 16);
 * long mark = arena.mark();
 * long scratch = arena.allocate(1024, 16);
 * arena.rewind(mark);                                          // releases the scratch space
 * arena.reset();                                               // releases everything
 * }</pre>
 */
@Experimental("the allocator set and their signatures may change")
public final class ArenaAllocator {

    /**
     * Returned when the request does not fit.
     */
    public static final long NONE = -1L;

    private final long capacity;
    private final MemorySegment backing;
    private long used;

    /**
     * Creates an allocator over {@code capacity} bytes, without a backing segment (offsets only).
     *
     * @param capacity the capacity in elements
     * @throws IllegalArgumentException if {@code capacity} is negative
     */
    public ArenaAllocator(long capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must not be negative: " + capacity);
        }
        this.capacity = capacity;
        this.backing = null;
    }

    /**
     * Creates an allocator over the whole of {@code backing}.
     *
     * @param backing the backing; must not be {@code null}
     */
    public ArenaAllocator(MemorySegment backing) {
        this.capacity = backing.byteSize();
        this.backing = backing;
    }

    static void checkAlignment(long alignment) {
        if (alignment < 1 || (alignment & (alignment - 1)) != 0) {
            throw new IllegalArgumentException("the alignment must be a power of two: " + alignment);
        }
    }

    /**
     * Reports the total size of the managed range; fixed for the lifetime of the allocator.
     *
     * @return the size of the range in bytes
     */
    public long capacity() {
        return capacity;
    }

    /**
     * Reports how much of the range has been consumed, counting alignment padding as consumed.
     *
     * @return bytes handed out so far, including the padding that alignment cost
     */
    public long used() {
        return used;
    }

    /**
     * Reports the space left in the range, as a plain difference that does not account for the
     * padding a later aligned request may need.
     *
     * @return the bytes still available, ignoring the padding a later aligned request may need:
     *     {@code capacity() - used()}
     */
    public long remaining() {
        return capacity - used;
    }

    /**
     * Hands out the next piece of the range by advancing a bump pointer, which is constant time and
     * allocation-free; individual pieces cannot be freed, only the whole arena reset or rewound.
     *
     * @param size the size
     * @param alignment the alignment
     * @return the offset of a new piece of {@code size} bytes aligned to {@code alignment} (a power
     *     of two; the offset is relative to the start of the range, so the range itself must be at
     *     least that aligned), or {@link #NONE} if it does not fit
     * @throws IllegalArgumentException if {@code size} is negative
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

    /**
     * Captures the current bump position so that everything allocated after it can be released at
     * once with {@link #rewind}.
     *
     * @return a position to {@link #rewind} to later
     */
    public long mark() {
        return used;
    }

    /**
     * Releases everything allocated after {@code mark}.
     *
     * @param mark the mark
     * @throws IllegalArgumentException if {@code mark} is not a position between 0 and the size
     *     used so far
     */
    public void rewind(long mark) {
        if (mark < 0 || mark > used) {
            throw new IllegalArgumentException("mark " + mark + " is not a position between 0 and " + used);
        }
        used = mark;
    }

    /**
     * Releases everything.
     */
    public void reset() {
        used = 0;
    }

    /**
     * Exposes the memory the offsets refer to, when the allocator was built over a segment.
     *
     * @return the backing segment, or {@code null} if the allocator was built from a size only
     */
    public MemorySegment segment() {
        return backing;
    }

    /**
     * Creates a view of a piece of the backing segment; allocates the view object, so keep it off
     * hot paths.
     *
     * @param offset the index of the first element to read or write
     * @param size the size
     * @return a view of {@code size} bytes at {@code offset} of the backing segment (allocates the
     *     view object, so keep it off hot paths)
     * @throws IllegalStateException if this allocator has no backing segment
     */
    public MemorySegment slice(long offset, long size) {
        if (backing == null) {
            throw new IllegalStateException("this allocator has no backing segment");
        }
        return backing.asSlice(offset, size);
    }
}
