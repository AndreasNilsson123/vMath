package vmath.samples.demos.streaming;

import vmath.mem.FreeListAllocator;
import vmath.mem.SlabAllocator;

/**
 * The offsets of the pool that the chunks live in, behind one interface, with the allocator that
 * hands them out as an option: a free list (first fit or best fit) for chunks of any size, or a slab
 * of equal blocks.
 *
 * <p>The pool owns no memory: it decides where in the GPU buffer a chunk goes. A free list packs
 * chunks tightly and can fragment (free space that no chunk fits in); a slab gives every chunk a
 * block of the largest chunk's size, never fragments, and wastes the difference.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: use it from the thread that owns the OpenGL context.
 */
final class StreamPool {

    /**
     * The allocators that can manage the pool.
     */
    enum Kind {
        /**
         * {@code FreeListAllocator} with the first-fit strategy.
         */
        FIRST_FIT("free list, first fit"),
        /**
         * {@code FreeListAllocator} with the best-fit strategy.
         */
        BEST_FIT("free list, best fit"),
        /**
         * {@code SlabAllocator} with blocks of the largest chunk.
         */
        SLAB("slab of equal blocks");

        private final String text;

        Kind(String text) {
            this.text = text;
        }

        /**
         * Gives the name for the display.
         *
         * @return the name
         */
        String text() {
            return text;
        }
    }

    /**
     * Returned by {@link #allocate} when the pool has no room.
     */
    static final long NONE = -1L;

    private final Kind kind;
    private final long capacity;
    private final long alignment;
    private final FreeListAllocator freeList;
    private final SlabAllocator slab;
    private final long blockBytes;
    private long requested;
    private long failures;

    /**
     * Creates a pool.
     *
     * @param kind the allocator; must not be {@code null}
     * @param capacity the size of the pool in bytes
     * @param blockBytes the size of a block of the slab, which must be at least the largest chunk
     *     (ignored by the free lists)
     * @param alignment the alignment of every chunk in bytes, a power of two
     */
    StreamPool(Kind kind, long capacity, long blockBytes, long alignment) {
        this.kind = kind;
        this.alignment = alignment;
        this.blockBytes = blockBytes;
        if (kind == Kind.SLAB) {
            int blocks = (int) (capacity / blockBytes);
            this.slab = new SlabAllocator(blockBytes, blocks);
            this.freeList = null;
            this.capacity = (long) blocks * blockBytes;
        } else {
            this.freeList = new FreeListAllocator(capacity, kind == Kind.BEST_FIT ? FreeListAllocator.Strategy.BEST_FIT : FreeListAllocator.Strategy.FIRST_FIT);
            this.slab = null;
            this.capacity = capacity;
        }
    }

    /**
     * Gives the allocator in use.
     *
     * @return the kind
     */
    Kind kind() {
        return kind;
    }

    /**
     * Reserves room for a chunk.
     *
     * @param bytes the size of the chunk, at most the block size for a slab
     * @return the offset in the pool, or {@link #NONE} if there is no room
     */
    long allocate(long bytes) {
        long offset = slab != null ? slab.allocate() : freeList.allocate(bytes, alignment);
        if (offset < 0) {
            failures++;
            return NONE;
        }
        requested += bytes;
        return offset;
    }

    /**
     * Releases the room of a chunk.
     *
     * @param offset the offset that {@link #allocate} returned
     * @param bytes the size that it was asked for
     */
    void free(long offset, long bytes) {
        if (slab != null) {
            slab.free(offset);
        } else {
            freeList.free(offset);
        }
        requested -= bytes;
    }

    /**
     * Gives the size of the pool.
     *
     * @return the bytes (a whole number of blocks for a slab)
     */
    long capacity() {
        return capacity;
    }

    /**
     * Gives the bytes that chunks asked for and hold now, not counting what the allocator adds.
     *
     * @return the bytes of the live chunks
     */
    long requestedBytes() {
        return requested;
    }

    /**
     * Gives the bytes that the allocator has reserved: for a free list the sizes of its blocks (the
     * requests rounded up to the alignment), for a slab whole blocks.
     *
     * @return the bytes
     */
    long reservedBytes() {
        return slab != null ? (long) slab.allocatedCount() * blockBytes : freeList.allocatedBytes();
    }

    /**
     * Gives the largest chunk that fits now.
     *
     * @return the bytes: the largest free block of a free list, a whole block for a slab with one
     *     free, otherwise 0
     */
    long largestFree() {
        return slab != null ? (slab.freeBlocks() > 0 ? blockBytes : 0L) : freeList.largestFree();
    }

    /**
     * Gives the number of free bytes.
     *
     * @return the bytes in all the free blocks
     */
    long freeBytes() {
        return slab != null ? (long) slab.freeBlocks() * blockBytes : freeList.freeBytes();
    }

    /**
     * Gives the number of blocks (free and allocated) of a free list, which grows with
     * fragmentation, or the number of blocks of a slab.
     *
     * @return the count
     */
    int blocks() {
        return slab != null ? slab.blockCount() : freeList.blockCount();
    }

    /**
     * Gives the number of times that {@link #allocate} found no room.
     *
     * @return the count
     */
    long failures() {
        return failures;
    }

    /**
     * Checks the internal consistency of the allocator.
     *
     * @return {@code null} if it is consistent, otherwise a description of the first problem
     */
    String validate() {
        return slab != null ? null : freeList.validate();
    }
}
