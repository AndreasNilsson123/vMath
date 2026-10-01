package vmath.mem;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import vmath.annotations.Experimental;

/**
 * A general-purpose allocator for pieces of any size that are freed in any order, over a range of bytes: vertex and index buffer sub-allocation, mesh streaming,
 * per-asset blocks. The bookkeeping is a sorted array of blocks that tiles the range exactly (each block free or allocated); {@link #free} merges a freed block with
 * free neighbours, so freeing everything restores one block. Allocation scans the blocks, {@link Strategy#FIRST_FIT} stopping at the first that fits and
 * {@link Strategy#BEST_FIT} choosing the one that leaves the least over, so it costs O(number of blocks) and is meant for hundreds to thousands of live
 * allocations, not millions (use {@link SlabAllocator} for those). Alignment is honoured by splitting off the padding in front as a free block, so no bytes are wasted.
 *
 * <p>Returns byte offsets and {@link #NONE} when nothing fits ({@link #largestFree()} says whether defragmenting could help). The bookkeeping is in arrays outside the
 * managed range. Allocation of the bookkeeping happens only when the block count outgrows the arrays. Not thread-safe.
 */
@Experimental("the allocator set and their signatures may change")
public final class FreeListAllocator {

    /** Returned when no free block is large enough. */
    public static final long NONE = -1L;

    /** How a free block is chosen. */
    public enum Strategy {
        /** The first block that fits: fastest, slightly more fragmentation. */
        FIRST_FIT,
        /** The block that leaves the smallest remainder: slower scan, usually less fragmentation. */
        BEST_FIT
    }

    private final long capacity;
    private final Strategy strategy;
    private final MemorySegment backing;
    private long[] start = new long[16];
    private long[] length = new long[16];
    private boolean[] free = new boolean[16];
    private int blocks;
    private long freeBytes;

    public FreeListAllocator(long capacity, Strategy strategy) {
        this(capacity, strategy, null);
    }

    /** An allocator over the whole of {@code backing}. */
    public FreeListAllocator(MemorySegment backing, Strategy strategy) {
        this(backing.byteSize(), strategy, backing);
    }

    private FreeListAllocator(long capacity, Strategy strategy, MemorySegment backing) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must not be negative: " + capacity);
        }
        this.capacity = capacity;
        this.strategy = strategy;
        this.backing = backing;
        reset();
    }

    public long capacity() {
        return capacity;
    }

    public long freeBytes() {
        return freeBytes;
    }

    public long allocatedBytes() {
        return capacity - freeBytes;
    }

    /** The number of blocks (free and allocated) the range is split into right now. */
    public int blockCount() {
        return blocks;
    }

    /** The size of the largest single free block: the biggest request that can succeed. */
    public long largestFree() {
        long best = 0;
        for (int i = 0; i < blocks; i++) {
            if (free[i] && length[i] > best) {
                best = length[i];
            }
        }
        return best;
    }

    /** Frees everything. */
    public void reset() {
        blocks = capacity == 0 ? 0 : 1;
        if (blocks == 1) {
            start[0] = 0;
            length[0] = capacity;
            free[0] = true;
        }
        freeBytes = capacity;
    }

    private void insert(int at) {
        if (blocks == start.length) {
            int n = blocks * 2;
            start = Arrays.copyOf(start, n);
            length = Arrays.copyOf(length, n);
            free = Arrays.copyOf(free, n);
        }
        System.arraycopy(start, at, start, at + 1, blocks - at);
        System.arraycopy(length, at, length, at + 1, blocks - at);
        System.arraycopy(free, at, free, at + 1, blocks - at);
        blocks++;
    }

    private void remove(int at) {
        System.arraycopy(start, at + 1, start, at, blocks - at - 1);
        System.arraycopy(length, at + 1, length, at, blocks - at - 1);
        System.arraycopy(free, at + 1, free, at, blocks - at - 1);
        blocks--;
    }

    /**
     * The offset of a new block of {@code size} bytes (positive) aligned to {@code alignment} (a power of two; relative to the start of the range), or {@link #NONE}.
     */
    public long allocate(long size, long alignment) {
        ArenaAllocator.checkAlignment(alignment);
        if (size < 1) {
            throw new IllegalArgumentException("size must be positive: " + size);
        }
        int chosen = -1;
        long chosenWaste = Long.MAX_VALUE;
        for (int i = 0; i < blocks; i++) {
            if (!free[i] || length[i] < size) {
                continue;
            }
            long aligned = (start[i] + alignment - 1) & -alignment;
            long padding = aligned - start[i];
            if (aligned < start[i] || padding > length[i] - size) {
                continue;
            }
            long waste = length[i] - padding - size;
            if (strategy == Strategy.FIRST_FIT) {
                chosen = i;
                break;
            }
            if (waste < chosenWaste) {
                chosen = i;
                chosenWaste = waste;
                if (waste == 0) {
                    break;
                }
            }
        }
        if (chosen < 0) {
            return NONE;
        }
        long aligned = (start[chosen] + alignment - 1) & -alignment;
        long padding = aligned - start[chosen];
        long tail = length[chosen] - padding - size;
        int at = chosen;
        if (padding > 0) {
            // the front of the block stays free as its own block
            length[at] = padding;
            insert(at + 1);
            at++;
            start[at] = aligned;
            length[at] = size + tail;
            free[at] = true;
        }
        free[at] = false;
        if (tail > 0) {
            length[at] = size;
            insert(at + 1);
            start[at + 1] = aligned + size;
            length[at + 1] = tail;
            free[at + 1] = true;
        }
        freeBytes -= size;
        return aligned;
    }

    private int find(long offset) {
        int lo = 0, hi = blocks - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (start[mid] < offset) {
                lo = mid + 1;
            } else if (start[mid] > offset) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    /** The size of the allocated block at {@code offset}, or -1 if no allocated block starts there. */
    public long sizeOf(long offset) {
        int i = find(offset);
        return i >= 0 && !free[i] ? length[i] : -1L;
    }

    /**
     * Frees the block at {@code offset} and returns its size.
     *
     * @throws IllegalArgumentException if no allocated block starts at {@code offset} (a double free, or a wrong value)
     */
    public long free(long offset) {
        int i = find(offset);
        if (i < 0 || free[i]) {
            throw new IllegalArgumentException("offset " + offset + " is not the start of an allocated block");
        }
        long size = length[i];
        free[i] = true;
        freeBytes += size;
        if (i + 1 < blocks && free[i + 1]) {
            length[i] += length[i + 1];
            remove(i + 1);
        }
        if (i > 0 && free[i - 1]) {
            length[i - 1] += length[i];
            remove(i);
        }
        return size;
    }

    public MemorySegment segment() {
        return backing;
    }

    /** A view of the allocated block at {@code offset} (allocates the view object). */
    public MemorySegment slice(long offset) {
        if (backing == null) {
            throw new IllegalStateException("this allocator has no backing segment");
        }
        long size = sizeOf(offset);
        if (size < 0) {
            throw new IllegalArgumentException("offset " + offset + " is not the start of an allocated block");
        }
        return backing.asSlice(offset, size);
    }

    /** Checks that the blocks tile the range, no two free blocks are adjacent and the free total adds up; for tests and debugging. Returns a problem or {@code null}. */
    public String validate() {
        long position = 0, freeSum = 0;
        for (int i = 0; i < blocks; i++) {
            if (start[i] != position) {
                return "block " + i + " starts at " + start[i] + ", expected " + position;
            }
            if (length[i] < 1) {
                return "block " + i + " is empty";
            }
            if (i > 0 && free[i] && free[i - 1]) {
                return "blocks " + (i - 1) + " and " + i + " are both free and adjacent";
            }
            position += length[i];
            if (free[i]) {
                freeSum += length[i];
            }
        }
        if (position != capacity) {
            return "the blocks cover " + position + " bytes of " + capacity;
        }
        if (freeSum != freeBytes) {
            return "free total is " + freeBytes + " but the free blocks add up to " + freeSum;
        }
        return null;
    }
}
