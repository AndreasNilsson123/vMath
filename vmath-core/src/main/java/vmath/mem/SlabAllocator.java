package vmath.mem;

import java.lang.foreign.MemorySegment;
import vmath.annotations.Experimental;

/**
 * A pool of equally sized blocks: allocation and release are O(1) and fragmentation is impossible.
 *
 * <p>For many objects of one size that come and go in any order (per-instance records, material
 * slots, light entries). The block bookkeeping lives in ordinary arrays, not in the managed memory,
 * so the managed range can be GPU memory that the CPU should not touch.
 *
 * <p>Like {@link ArenaAllocator} it returns byte offsets ({@code block index * blockSize}) and
 * {@link #NONE} when the pool is full. Releasing an offset that is not an allocated block throws,
 * which catches double frees. Nothing allocates after construction. Not thread-safe.
 *
 * <p><b>Thread safety.</b> Not thread-safe: use one allocator per thread, or synchronize
 * externally. No method blocks.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * SlabAllocator pool = new SlabAllocator(64, 1024);            // 1024 blocks of 64 bytes
 * long block = pool.allocate();
 * boolean live = pool.isAllocated(block);                      // true
 * pool.free(block);
 * }</pre>
 */
@Experimental("the allocator set and their signatures may change")
public final class SlabAllocator {

    /**
     * Returned when no block is free.
     */
    public static final long NONE = -1L;

    private final long blockSize;
    private final int blockCount;
    private final MemorySegment backing;
    private final int[] freeStack;
    private final long[] allocatedBits;
    private int freeCount;

    /**
     * Creates a pool of {@code blockCount} blocks of {@code blockSize} bytes that hands out offsets
     * only.
     *
     * @param blockSize the block size
     * @param blockCount the block count
     */
    public SlabAllocator(long blockSize, int blockCount) {
        this(blockSize, blockCount, null);
    }

    /**
     * Creates a pool over the first {@code blockSize * blockCount} bytes of {@code backing}.
     *
     * @param blockSize the block size
     * @param blockCount the block count
     * @param backing the backing; may be {@code null}
     * @throws IllegalArgumentException if {@code blockSize} is not positive, {@code blockCount} is
     *     negative, the pool is too large to address or the backing segment is smaller than the
     *     pool
     */
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

    /**
     * Reports the size of every block in the pool; fixed at construction.
     *
     * @return the size of every block in bytes
     */
    public long blockSize() {
        return blockSize;
    }

    /**
     * Reports how many blocks the pool holds in total.
     *
     * @return the number of blocks in the pool
     */
    public int blockCount() {
        return blockCount;
    }

    /**
     * Counts the blocks that are currently in use.
     *
     * @return the blocks handed out and not yet freed
     */
    public int allocatedCount() {
        return blockCount - freeCount;
    }

    /**
     * Counts the blocks that are still available.
     *
     * @return the blocks available: {@code blockCount() - allocatedCount()}
     */
    public int freeBlocks() {
        return freeCount;
    }

    /**
     * Takes a block from the free stack in constant time; running out is reported through a
     * sentinel instead of an exception.
     *
     * <p>The lowest-numbered free blocks are handed out first after a {@link #reset}.
     *
     * @return the offset of a free block, or {@link #NONE} if all are taken
     */
    public long allocate() {
        if (freeCount == 0) {
            return NONE;
        }
        int index = freeStack[--freeCount];
        allocatedBits[index >>> 6] |= 1L << index;
        return index * blockSize;
    }

    /**
     * Returns whether {@code offset} is the start of an allocated block.
     *
     * @param offset the index of the first element to read or write
     * @return {@code true} if {@code offset} is the start of an allocated block
     */
    public boolean isAllocated(long offset) {
        if (offset < 0 || offset % blockSize != 0 || offset / blockSize >= blockCount) {
            return false;
        }
        int index = (int) (offset / blockSize);
        return (allocatedBits[index >>> 6] & (1L << index)) != 0L;
    }

    /**
     * Converts a block offset to its index by division; the offset must be one the allocator handed
     * out.
     *
     * @param offset the index of the first element to read or write
     * @return the block index of an offset returned by {@link #allocate}
     */
    public int blockIndex(long offset) {
        return (int) (offset / blockSize);
    }

    /**
     * Returns a block to the pool.
     *
     * @param offset the index of the first element to read or write
     * @throws IllegalArgumentException if {@code offset} is not the start of an allocated block (a
     *     double free, or a wrong value)
     */
    public void free(long offset) {
        if (!isAllocated(offset)) {
            throw new IllegalArgumentException("offset " + offset + " is not an allocated block");
        }
        int index = (int) (offset / blockSize);
        allocatedBits[index >>> 6] &= ~(1L << index);
        freeStack[freeCount++] = index;
    }

    /**
     * Frees every block.
     */
    public void reset() {
        java.util.Arrays.fill(allocatedBits, 0L);
        for (int i = 0; i < blockCount; i++) {
            freeStack[i] = blockCount - 1 - i;
        }
        freeCount = blockCount;
    }

    /**
     * Exposes the memory the blocks live in, when the pool was built over a segment.
     *
     * @return the memory behind the blocks, or {@code null} for a pool of offsets only
     */
    public MemorySegment segment() {
        return backing;
    }

    /**
     * Creates a view of a block; allocates the view object, so keep it off hot paths.
     *
     * @param offset the index of the first element to read or write
     * @return a view of the block at {@code offset} (allocates the view object)
     * @throws IllegalStateException if this allocator has no backing segment
     */
    public MemorySegment slice(long offset) {
        return Backing.slice(backing, offset, blockSize);
    }
}
