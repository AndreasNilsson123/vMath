package vmath.mem;

import java.lang.foreign.MemorySegment;
import vmath.annotations.Experimental;

/**
 * A persistently mapped upload buffer shared by {@code N} frames in flight: the mapped range is cut
 * into {@code N} equal regions, frame {@code f} writes only into region {@code f % N}, and before a
 * region is reused the ring waits for the fence that was inserted when its last frame was
 * submitted, so the CPU never overwrites data the GPU may still read.
 *
 * <p>Within a frame, allocations are bump allocations in the frame's region ({@link #allocate}).
 *
 * <p>The ring does not know the graphics API: fences come through {@link FenceOps}, four methods
 * that map to {@code glFenceSync}/{@code glClientWaitSync}/{@code glDeleteSync} or to a Vulkan
 * fence or timeline semaphore. The offsets returned are relative to the start of the mapped
 * segment, which is assumed to be aligned for everything that will be allocated in it (mapped
 * pointers are page aligned in practice); alignments larger than the region alignment are honoured
 * by aligning the absolute offset.
 *
 * <pre>{@code
 * ring.beginFrame();                              // waits if the region is still in use by the GPU
 * long a = ring.allocate(64 * count, 16);         // -1 if the frame's region is full
 * ... write into ring.slice(a, 64 * count), record commands ...
 * ring.endFrame();                                // inserts the fence right after the submit
 * }</pre>
 *
 * <p>Nothing allocates after construction (the {@code slice} views excepted). Not thread-safe.
 *
 * <p><b>Thread safety.</b> Not thread-safe: all calls must come from the thread that records the
 * frames, in the order {@link #beginFrame()}, {@link #allocate}, {@link #endFrame()}, and
 * {@link #close()} once at the end. {@link #beginFrame()} blocks while the fence of the region it
 * reuses has not signalled, by calling {@link FenceOps#await}.
 *
 * @param <F> the fence type of the graphics API binding
 */
@Experimental("the ring and the fence hooks may change")
public final class PersistentBufferRing<F> implements AutoCloseable {

    /**
     * The fence operations of the graphics API.
     *
     * @param <F> the type of the fence objects
     */
    public interface FenceOps<F> {
        /**
         * Creates a fence that signals when all work submitted so far has finished on the GPU.
         *
         * @return a new fence, never {@code null}
         */
        F insert();

        /**
         * Returns whether the fence has signalled (must not block).
         *
         * @param fence the fence; must not be {@code null}
         * @return {@code true} if the fence has signalled (must not block)
         */
        boolean isSignaled(F fence);

        /**
         * Blocks until the fence has signalled.
         *
         * @param fence the fence; must not be {@code null}
         */
        void await(F fence);

        /**
         * Destroys the fence.
         *
         * @param fence the fence; must not be {@code null}
         */
        void release(F fence);
    }

    /**
     * Returned by {@link #allocate} when the current frame's region has no room.
     */
    public static final long NONE = -1L;

    private final MemorySegment mapped;
    private final FenceOps<F> ops;
    private final int frames;
    private final long regionSize;
    private final java.util.List<F> fences; // one slot per region, null while the region has no fence
    private long frame = -1;
    private int region;
    private long used;
    private boolean inFrame;
    private long stalls;
    private long highWater;

    /**
     * Creates a ring over the mapped buffer with one region per frame in flight, each guarded by a
     * fence.
     *
     * @param mapped          the mapped buffer
     *
     * @param framesInFlight how many frames the CPU may be ahead of the GPU (2 or 3 typically)
     * @param regionAlignment each region starts at a multiple of this (a power of two, such as 256
     *     for uniform buffer offset alignment)
     * @param ops             the fence operations
     * @throws IllegalArgumentException if {@code framesInFlight} is below 1 or the mapped range is
     *     too small for the frames
     */
    public PersistentBufferRing(MemorySegment mapped, int framesInFlight, long regionAlignment, FenceOps<F> ops) {
        Backing.checkAlignment(regionAlignment);
        if (framesInFlight < 1) {
            throw new IllegalArgumentException("framesInFlight must be at least 1: " + framesInFlight);
        }
        long region = mapped.byteSize() / framesInFlight & -regionAlignment;
        if (region < 1) {
            throw new IllegalArgumentException("a mapped range of " + mapped.byteSize() + " bytes is too small for " + framesInFlight + " regions aligned to " + regionAlignment);
        }
        this.mapped = mapped;
        this.frames = framesInFlight;
        this.regionSize = region;
        this.ops = ops;
        this.fences = new java.util.ArrayList<>(java.util.Collections.nCopies(framesInFlight, (F) null));
    }

    /**
     * Reports how many frames the CPU may run ahead of the GPU, which equals the number of regions
     * the buffer is split into.
     *
     * @return the number of frames that may be in flight at once, which is the number of regions
     */
    public int framesInFlight() {
        return frames;
    }

    /**
     * Reports the size of the region that one frame may fill; fixed at construction.
     *
     * @return the size of each region in bytes
     */
    public long regionSize() {
        return regionSize;
    }

    /**
     * Reports which frame the ring is on, counting from zero.
     *
     * @return the index of the current frame (0 for the first), or -1 before the first
     *     {@link #beginFrame}
     */
    public long frameIndex() {
        return frame;
    }

    /**
     * Computes where the region of the current frame starts inside the mapped buffer, from the
     * frame's region number and the region size.
     *
     * @return the byte offset where the current frame's region starts
     */
    public long regionOffset() {
        return (long) region * regionSize;
    }

    /**
     * Reports how much of the current frame's region has been consumed, counting alignment padding
     * as consumed.
     *
     * @return bytes allocated so far in the current frame (including alignment padding)
     */
    public long used() {
        return used;
    }

    /**
     * Counts the waits the ring has had to perform for the GPU; a rising value means the region
     * count or the GPU work is too small for the frame rate.
     *
     * <p>A steady non-zero rate means the CPU is ahead of the GPU.
     *
     * @return how many times {@link #beginFrame} had to block because the GPU had not finished with
     *     the region
     */
    public long stalls() {
        return stalls;
    }

    /**
     * Tracks the peak consumption of any frame so far, which is the figure to size a region by.
     *
     * @return the most bytes any frame has used so far: how much of a region is actually needed
     */
    public long highWaterMark() {
        return highWater;
    }

    /**
     * Starts the next frame: moves to the next region, waiting first for the fence of its previous
     * use if that has not signalled, and empties it. If waiting for the fence throws, the ring
     * stays on the previous frame and the call may be repeated.
     *
     * @return the byte offset of the frame's region
     * @throws IllegalStateException if {@link #endFrame()} has not been called for the previous
     *     frame
     */
    public long beginFrame() {
        if (inFrame) {
            throw new IllegalStateException("endFrame() has not been called for frame " + frame);
        }
        // nothing is committed before the wait has succeeded, so a failing fence leaves the ring on the previous frame and the call can be repeated
        long next = frame + 1;
        int nextRegion = (int) (next % frames);
        F fence = fences.get(nextRegion);
        if (fence != null) {
            if (!ops.isSignaled(fence)) {
                stalls++;
                ops.await(fence);
            }
            fences.set(nextRegion, null);
            ops.release(fence);
        }
        frame = next;
        region = nextRegion;
        used = 0;
        inFrame = true;
        return regionOffset();
    }

    /**
     * Reserves {@code size} bytes aligned to {@code alignment} (a power of two) in the current
     * frame's region and returns the offset relative to the mapped segment, or {@link #NONE} if the
     * region has no room left.
     *
     * @param size the size
     * @param alignment the alignment
     * @return the offset relative to the mapped segment, or {@link #NONE} if the region has no room
     *     left
     * @throws IllegalStateException if {@link #beginFrame()} has not been called
     * @throws IllegalArgumentException if {@code size} is negative
     */
    public long allocate(long size, long alignment) {
        if (!inFrame) {
            throw new IllegalStateException("beginFrame() has not been called");
        }
        Backing.checkAlignment(alignment);
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative: " + size);
        }
        long base = regionOffset();
        long absolute = (base + used + alignment - 1) & -alignment;
        long end = absolute + size;
        if (absolute < base || end < absolute || end > base + regionSize) {
            return NONE;
        }
        used = end - base;
        highWater = Math.max(highWater, used);
        return absolute;
    }

    /**
     * Ends the frame: inserts the fence for its region.
     *
     * <p>Call it right after submitting the frame's commands.
     *
     * @throws IllegalStateException if {@link #beginFrame()} has not been called
     */
    public void endFrame() {
        if (!inFrame) {
            throw new IllegalStateException("beginFrame() has not been called");
        }
        fences.set(region, ops.insert());
        inFrame = false;
    }

    /**
     * Waits for every outstanding fence and releases it: call it (or use try-with-resources) before
     * the mapped buffer is unmapped or recreated, because the GPU may still be reading a region.
     *
     * <p>The ring owns the fences it created, not the mapped memory.
     */
    @Override
    public void close() {
        drain();
    }

    /**
     * Waits for every outstanding fence and releases it, for shutdown or before recreating the
     * buffer.
     */
    public void drain() {
        for (int i = 0; i < frames; i++) {
            F fence = fences.get(i);
            if (fence != null) {
                ops.await(fence);
                ops.release(fence);
                fences.set(i, null);
            }
        }
    }

    /**
     * Exposes the persistently mapped memory that all offsets refer to.
     *
     * @return the mapped segment the offsets are relative to
     */
    public MemorySegment segment() {
        return mapped;
    }

    /**
     * Creates a view of a piece of the mapped segment; allocates the view object, so keep it off
     * hot paths.
     *
     * @param offset the index of the first element to read or write
     * @param size the size
     * @return a view of {@code size} bytes at {@code offset} of the mapped segment (allocates the
     *     view object)
     */
    public MemorySegment slice(long offset, long size) {
        return mapped.asSlice(offset, size);
    }
}
