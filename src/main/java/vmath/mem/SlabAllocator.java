package vmath.mem;

import java.lang.foreign.MemorySegment;
import vmath.annotations.Experimental;

/**
 * A pool of equally sized blocks: allocation and release are O(1) and fragmentation is impossible. For many objects of one size that come and go in any order
 * (per-instance records, material slots, light entries). The block bookkeeping lives in ordinary arrays, not in the managed memory, so the managed range can be GPU
 * memory that the CPU should not touch.
 *
 * <p>Like {@link ArenaAllocator} it returns byte offsets ({@code block index * blockSize}) and {@link #NONE} when the pool is full. Releasing an offset that is
 * not an allocated block throws, which catches double frees. Nothing allocates after construction. Not thread-safe.
 */
@Experimental("the allocator set and their signatures may change")
public final class SlabAllocator {

    /** Returned when no block is free. */
    public static final long NONE = -1L;

    private final long blockSize;
    private final int blockCount;
    private final MemorySegment backing;
    private final int[] freeStack;
    private final long[] allocatedBits;
    private int freeCount;

    /** {@code blockCount} blocks of {@code blockSize} bytes, offsets only. */
    public SlabAllocator(long blockSize, int blockCount) {
        this(blockSize, blockCount, null);
    }

    /** A pool over the first {@code blockSize * blockCount} bytes of {@code backing}. */
    public SlabAllocator(long blockSize, int blockCount, MemorySegment backing) {
        if (blockSize < 1 || blockCount < 0) {
            throw new IllegalArgumentException("blockSize must be positive and blockCount not negative: " + blockSize + ", " + blockCount);
        }
        if (blockSize > Long.MAX_VALUE / Math.max(blockCount, 1)) {
            throw new IllegalArgumentException("the pool would be larger than a long can address");
        }
        if (backing != null && backing.byteSize() < blockSize * blockCount) {
            throw new IllegalArgumentException("the backing segment of " + backing.byteSize() + " bytes is smaller than the pool of " + blockSize * blockCount);
        }
        this.blockSize = blockSize;
        this.blockCount = blockCount;
        this.backing = backing;
        this.freeStack = new int[blockCount];
        this.allocatedBits = new long[(blockCount + 63) >>> 6];
        reset();
    }

    /** The size of every block in bytes. */
    public long blockSize() {
        return blockSize;
    }

    /** The number of blocks in the pool. */
    public int blockCount() {
        return blockCount;
    }

    /** The blocks handed out and not yet freed. */
    public int allocatedCount() {
        return blockCount - freeCount;
    }

    /** The blocks available: {@code blockCount() - allocatedCount()}. */
    public int freeBlocks() {
        return freeCount;
    }

    /** The offset of a free block, or {@link #NONE} if all are taken. The lowest-numbered free blocks are handed out first after a {@link #reset}. */
    public long allocate() {
        if (freeCount == 0) {
            return NONE;
        }
        int index = freeStack[--freeCount];
        allocatedBits[index >>> 6] |= 1L << index;
        return index * blockSize;
    }

    /** Whether {@code offset} is the start of an allocated block. */
    public boolean isAllocated(long offset) {
        if (offset < 0 || offset % blockSize != 0 || offset / blockSize >= blockCount) {
            return false;
        }
        int index = (int) (offset / blockSize);
        return (allocatedBits[index >>> 6] & (1L << index)) != 0L;
    }

    /** The block index of an offset returned by {@link #allocate}. */
    public int blockIndex(long offset) {
        return (int) (offset / blockSize);
    }

    /** Returns a block to the pool.
     *
     * @throws IllegalArgumentException if {@code offset} is not the start of an allocated block (a double free, or a wrong value)
     */
    public void free(long offset) {
        if (!isAllocated(offset)) {
            throw new IllegalArgumentException("offset " + offset + " is not an allocated block");
        }
        int index = (int) (offset / blockSize);
        allocatedBits[index >>> 6] &= ~(1L << index);
        freeStack[freeCount++] = index;
    }

    /** Frees every block. */
    public void reset() {
        java.util.Arrays.fill(allocatedBits, 0L);
        for (int i = 0; i < blockCount; i++) {
            freeStack[i] = blockCount - 1 - i;
        }
        freeCount = blockCount;
    }

    /** The memory behind the blocks, or {@code null} for a pool of offsets only. */
    public MemorySegment segment() {
        return backing;
    }

    /** A view of the block at {@code offset} (allocates the view object). */
    public MemorySegment slice(long offset) {
        if (backing == null) {
            throw new IllegalStateException("this allocator has no backing segment");
        }
        return backing.asSlice(offset, blockSize);
    }
}
