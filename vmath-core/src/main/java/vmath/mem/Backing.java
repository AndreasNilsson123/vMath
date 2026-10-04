package vmath.mem;

import java.lang.foreign.MemorySegment;

/**
 * The checks and views that the allocators of this package share.
 *
 * <p>Internal: the allocators hand out offsets, and an optional backing segment turns an offset
 * into memory; this class holds the part of that which does not depend on the allocator.
 *
 * <p><b>Thread safety.</b> Stateless: safe to call from any number of threads.
 */
final class Backing {

    private Backing() {
    }

    /**
     * Checks that an alignment is a power of two.
     *
     * @param alignment the alignment in bytes
     * @throws IllegalArgumentException if {@code alignment} is not a positive power of two
     */
    static void checkAlignment(long alignment) {
        if (alignment < 1 || (alignment & (alignment - 1)) != 0) {
            throw new IllegalArgumentException("the alignment must be a power of two: " + alignment);
        }
    }

    /**
     * Creates a view of a piece of an allocator's backing segment.
     *
     * @param backing the backing segment, or {@code null} if the allocator has none
     * @param offset the index of the first byte of the view
     * @param size the size of the view in bytes
     * @return a view of {@code size} bytes at {@code offset} of {@code backing} (allocates the view
     *     object)
     * @throws IllegalStateException if {@code backing} is {@code null}
     */
    static MemorySegment slice(MemorySegment backing, long offset, long size) {
        if (backing == null) {
            throw new IllegalStateException("this allocator has no backing segment");
        }
        return backing.asSlice(offset, size);
    }
}
