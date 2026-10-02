package vmath.mem;

import java.lang.foreign.MemorySegment;
import vmath.annotations.Experimental;

/**
 * A circular allocator for per-frame data that is produced in order and released in the same order once the GPU is done with it: allocations advance a head around
 * the range; {@link #endFrame} marks where a frame's allocations stop; {@link #retireOldestFrame} frees everything up to the oldest mark (call it when the fence of
 * that frame has signalled). Memory is reused as soon as it is retired, so one buffer of a few frames' worth serves forever, which is how dynamic uniforms, instance data
 * and streamed vertices are usually handled.
 *
 * <p>Each allocation is contiguous: if it does not fit before the end of the range the allocator wraps and the bytes at the end are skipped (they come back when their
 * frame is retired). A request that cannot be satisfied right now returns {@link #NONE}; the usual remedy is to retire frames (waiting for the GPU) and retry. Offsets are
 * relative to the start of the range. Nothing allocates after construction. Not thread-safe.
 */
@Experimental("the allocator set and their signatures may change")
public final class RingAllocator {

    /** Returned when the request does not fit in the space that is free right now. */
    public static final long NONE = -1L;

    private final long capacity;
    private final MemorySegment backing;
    private final long[] frameHead;
    private final long[] frameTotal;
    private int frameFirst;
    private int frameCount;
    private long head;
    private long tail;
    private long used;
    private long consumedTotal;
    private long retiredTotal;

    /** A ring of {@code capacity} bytes that can have up to {@code maxFrames} frames outstanding. */
    public RingAllocator(long capacity, int maxFrames) {
        this(capacity, maxFrames, null);
    }

    /** A ring over the whole of {@code backing}. */
    public RingAllocator(MemorySegment backing, int maxFrames) {
        this(backing.byteSize(), maxFrames, backing);
    }

    private RingAllocator(long capacity, int maxFrames, MemorySegment backing) {
        if (capacity < 0 || maxFrames < 1) {
            throw new IllegalArgumentException("capacity must not be negative and maxFrames must be positive: " + capacity + ", " + maxFrames);
        }
        this.capacity = capacity;
        this.backing = backing;
        this.frameHead = new long[maxFrames];
        this.frameTotal = new long[maxFrames];
    }

    /** The size of the ring in bytes. */
    public long capacity() {
        return capacity;
    }

    /** Bytes currently reserved, including bytes skipped at the end of the range by a wrap. */
    public long used() {
        return used;
    }

    /** Frames that have been ended and not yet retired. */
    public int outstandingFrames() {
        return frameCount;
    }

    /** The offset of a new contiguous piece of {@code size} bytes aligned to {@code alignment} (a power of two), or {@link #NONE} if it does not fit right now. */
    public long allocate(long size, long alignment) {
        ArenaAllocator.checkAlignment(alignment);
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative: " + size);
        }
        if (size > capacity) {
            return NONE;
        }
        if (used == 0 && frameCount == 0) {
            head = 0;
            tail = 0;
        }
        if (used == capacity) {
            return NONE;
        }
        long start = (head + alignment - 1) & -alignment;
        // the contiguous space ahead of the head: up to the end of the range if the data lies behind it, or up to the tail if the head has wrapped
        long limit = head > tail || used == 0 ? capacity : tail;
        if (start >= head && start <= limit && size <= limit - start) {
            consume(start - head + size, start + size);
            return start;
        }
        if (head >= tail && size <= tail) {
            // wrap: skip the end of the range (it counts as used until its frame is retired)
            long skipped = capacity - head;
            consume(skipped + size, size);
            return 0;
        }
        return NONE;
    }

    private void consume(long bytes, long newHead) {
        used += bytes;
        consumedTotal += bytes;
        head = newHead == capacity ? 0 : newHead;
    }

    /** Marks the end of the current frame's allocations. Fails if {@code maxFrames} frames are already outstanding (retire one first). */
    public void endFrame() {
        if (frameCount == frameHead.length) {
            throw new IllegalStateException("all " + frameHead.length + " frames are outstanding; retire the oldest first");
        }
        int slot = (frameFirst + frameCount) % frameHead.length;
        frameHead[slot] = head;
        frameTotal[slot] = consumedTotal;
        frameCount++;
    }

    /** Frees everything that was allocated up to the end of the oldest outstanding frame. */
    public void retireOldestFrame() {
        if (frameCount == 0) {
            throw new IllegalStateException("no frame is outstanding");
        }
        tail = frameHead[frameFirst];
        retiredTotal = frameTotal[frameFirst];
        used = consumedTotal - retiredTotal;
        frameFirst = (frameFirst + 1) % frameHead.length;
        frameCount--;
        if (used == 0 && frameCount == 0) {
            head = 0;
            tail = 0;
        }
    }

    /** Frees everything, outstanding frames included. */
    public void reset() {
        head = 0;
        tail = 0;
        used = 0;
        frameFirst = 0;
        frameCount = 0;
        consumedTotal = 0;
        retiredTotal = 0;
    }

    /** The memory behind the offsets, or {@code null} for a ring made from a capacity only. */
    public MemorySegment segment() {
        return backing;
    }

    /** A view of {@code size} bytes at {@code offset} (allocates the view object). */
    public MemorySegment slice(long offset, long size) {
        if (backing == null) {
            throw new IllegalStateException("this allocator has no backing segment");
        }
        return backing.asSlice(offset, size);
    }
}
