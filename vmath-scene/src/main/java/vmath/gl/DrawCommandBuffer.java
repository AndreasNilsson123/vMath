package vmath.gl;

import java.lang.foreign.MemorySegment;

/**
 * A run of indirect commands of one kind written into a {@link MemorySegment} (typically the mapped
 * memory of an indirect buffer), for {@code glMultiDraw*Indirect}, {@code vkCmdDrawIndirect} and
 * friends.
 *
 * <p>Commands go one after another at a fixed {@linkplain #stride() stride}; {@link #count()} is
 * the draw count to pass to the multi-draw call.
 *
 * <p>The {@code add...} methods write the words directly and allocate nothing. Padding bytes (when
 * {@code pad16} makes the stride a multiple of 16) are never written. Not thread-safe: one writer
 * per buffer region.
 *
 * <p><b>Thread safety.</b> Not thread-safe: one writer per buffer region, from one thread at a
 * time.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * MemorySegment memory = Arena.ofAuto().allocate(1024);
 * DrawCommandBuffer commands = new DrawCommandBuffer(memory, DrawCommandBuffer.Kind.ELEMENTS, false);
 * int first = commands.addElements(36, 100, 0, 0, 0);                  // returns the index of the command
 * commands.setInstanceCount(first, 0);                                 // a culling pass hides the draw like this
 * long bytes = commands.usedBytes();
 * }</pre>
 */
public final class DrawCommandBuffer {

    /**
     * The command kinds, with their size in bytes.
     */
    public enum Kind {
        /**
         * {@link DrawArraysIndirect}, 16 bytes.
         */
        ARRAYS(16),
        /**
         * {@link DrawElementsIndirect}, 20 bytes.
         */
        ELEMENTS(20),
        /**
         * {@link DispatchIndirect}, 12 bytes.
         */
        DISPATCH(12);

        private final int bytes;

        Kind(int bytes) {
            this.bytes = bytes;
        }

        /**
         * Exposes the size of one command of this kind in bytes.
         *
         * @return size of one command in bytes
         */
        public int bytes() {
            return bytes;
        }
    }

    private final MemorySegment segment;
    private final Kind kind;
    private final long stride;
    private final long capacity;
    private int count;

    /**
     * Creates a command buffer over the segment for commands of the given kind.
     *
     * @param segment the memory to write into, starting at offset 0
     * @param kind    which command to store
     * @param pad16   round the stride up to a multiple of 16 bytes (some drivers and tools want 16-byte aligned commands); the commands themselves keep their size and are placed at the start of each slot
     */
    public DrawCommandBuffer(MemorySegment segment, Kind kind, boolean pad16) {
        this.segment = segment;
        this.kind = kind;
        this.stride = stride(kind, pad16);
        this.capacity = segment.byteSize() / stride;
    }

    /**
     * Computes the distance between consecutive commands of a kind, optionally with padding for
     * drivers that want a larger stride.
     *
     * @param kind the kind; must not be {@code null}
     * @param pad16 whether pad16
     * @return bytes between the starts of consecutive commands of {@code kind}
     */
    public static long stride(Kind kind, boolean pad16) {
        return pad16 ? (kind.bytes + 15L) & ~15L : kind.bytes;
    }

    /**
     * Exposes the distance between consecutive commands in this buffer.
     *
     * @return bytes between the starts of consecutive commands in this buffer
     */
    public long stride() {
        return stride;
    }

    /**
     * Reports how many commands fit in the buffer.
     *
     * @return how many commands fit
     */
    public int capacity() {
        return (int) Math.min(capacity, Integer.MAX_VALUE);
    }

    /**
     * Counts the commands written so far, which is the draw count to pass to the multi-draw call.
     *
     * @return commands written so far: the draw count
     */
    public int count() {
        return count;
    }

    /**
     * Measures the filled part of the buffer, including the padding between commands.
     *
     * @return bytes used so far, including padding between commands (but not after the last one)
     */
    public long usedBytes() {
        return count == 0 ? 0 : (count - 1) * stride + kind.bytes;
    }

    /**
     * Computes the byte offset of a command, as needed for the offset argument of the indirect draw
     * calls or for uploading a sub-range.
     *
     * @param index the index
     * @return byte offset of command {@code index}, for {@code glMultiDraw*Indirect}'s offset
     *     argument or for a sub-range upload
     */
    public long byteOffset(int index) {
        return index * stride;
    }

    /**
     * Forgets all commands; the memory is not cleared.
     */
    public void clear() {
        count = 0;
    }

    /**
     * Appends a {@link DrawArraysIndirect} command; returns its index.
     *
     * @param vertexCount the number of vertices
     * @param instanceCount the instance count
     * @param first the first
     * @param baseInstance the base instance
     * @return its index
     */
    public int addArrays(int vertexCount, int instanceCount, int first, int baseInstance) {
        long o = next(Kind.ARRAYS);
        GpuWriter.putInt(segment, o, vertexCount);
        GpuWriter.putInt(segment, o + 4, instanceCount);
        GpuWriter.putInt(segment, o + 8, first);
        GpuWriter.putInt(segment, o + 12, baseInstance);
        return count++;
    }

    /**
     * Appends a {@link DrawElementsIndirect} command; returns its index.
     *
     * @param indexCount the index count
     * @param instanceCount the instance count
     * @param firstIndex the first index
     * @param baseVertex the base vertex
     * @param baseInstance the base instance
     * @return its index
     */
    public int addElements(int indexCount, int instanceCount, int firstIndex, int baseVertex, int baseInstance) {
        long o = next(Kind.ELEMENTS);
        GpuWriter.putInt(segment, o, indexCount);
        GpuWriter.putInt(segment, o + 4, instanceCount);
        GpuWriter.putInt(segment, o + 8, firstIndex);
        GpuWriter.putInt(segment, o + 12, baseVertex);
        GpuWriter.putInt(segment, o + 16, baseInstance);
        return count++;
    }

    /**
     * Appends a {@link DispatchIndirect} command; returns its index.
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @return its index
     */
    public int addDispatch(int x, int y, int z) {
        long o = next(Kind.DISPATCH);
        GpuWriter.putInt(segment, o, x);
        GpuWriter.putInt(segment, o + 4, y);
        GpuWriter.putInt(segment, o + 8, z);
        return count++;
    }

    /**
     * Appends a command given as a record; returns its index.
     *
     * @param c the draw arrays indirect; must not be {@code null}
     * @return its index
     */
    public int add(DrawArraysIndirect c) {
        return addArrays(c.count(), c.instanceCount(), c.first(), c.baseInstance());
    }

    /**
     * Appends a command given as a record; returns its index.
     *
     * @param c the draw elements indirect; must not be {@code null}
     * @return its index
     */
    public int add(DrawElementsIndirect c) {
        return addElements(c.count(), c.instanceCount(), c.firstIndex(), c.baseVertex(), c.baseInstance());
    }

    /**
     * Appends a dispatch given as a record; returns its index.
     *
     * @param c the dispatch indirect; must not be {@code null}
     * @return its index
     */
    public int add(DispatchIndirect c) {
        return addDispatch(c.x(), c.y(), c.z());
    }

    /**
     * Reads back the instance count of command {@code index} (word 1 of arrays and elements
     * commands): the field a culling pass sets to 0 to hide a draw.
     *
     * <p>Dispatch commands have no instance count.
     *
     * @param index the index
     * @return the instance count of the command
     * @throws UnsupportedOperationException if the command is a dispatch, which has no instance
     *     count
     */
    public int instanceCount(int index) {
        if (kind == Kind.DISPATCH) {
            throw new UnsupportedOperationException("a dispatch command has no instance count");
        }
        checkIndex(index);
        return GpuWriter.getInt(segment, index * stride + 4);
    }

    /**
     * Reads back the base instance of command {@code index} (the first instance slot of its draw:
     * word 4 of elements commands, word 3 of arrays commands).
     *
     * @param index the index
     * @return the base instance of the command
     * @throws UnsupportedOperationException if the command is a dispatch, which has no base
     *     instance
     */
    public int baseInstance(int index) {
        if (kind == Kind.DISPATCH) {
            throw new UnsupportedOperationException("a dispatch command has no base instance");
        }
        checkIndex(index);
        return GpuWriter.getInt(segment, index * stride + (kind == Kind.ELEMENTS ? 16 : 12));
    }

    /**
     * Overwrites the instance count of command {@code index}: zero makes the draw a no-op without
     * changing the draw count.
     *
     * @param index the index
     * @param instanceCount the instance count
     * @throws UnsupportedOperationException if the command is a dispatch, which has no instance
     *     count
     */
    public void setInstanceCount(int index, int instanceCount) {
        if (kind == Kind.DISPATCH) {
            throw new UnsupportedOperationException("a dispatch command has no instance count");
        }
        checkIndex(index);
        GpuWriter.putInt(segment, index * stride + 4, instanceCount);
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("command " + index + " of " + count);
        }
    }

    private long next(Kind expected) {
        if (kind != expected) {
            throw new IllegalStateException("this buffer holds " + kind + " commands, not " + expected);
        }
        if (count >= capacity) {
            throw new IllegalStateException("the buffer is full: " + capacity + " commands of " + stride + " bytes");
        }
        return count * stride;
    }
}
