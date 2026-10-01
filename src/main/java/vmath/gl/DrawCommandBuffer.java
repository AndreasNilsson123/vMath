package vmath.gl;

import java.lang.foreign.MemorySegment;

/**
 * A run of indirect commands of one kind written into a {@link MemorySegment} (typically the mapped memory of an indirect buffer), for
 * {@code glMultiDraw*Indirect}, {@code vkCmdDrawIndirect} and friends. Commands go one after another at a fixed {@linkplain #stride() stride};
 * {@link #count()} is the draw count to pass to the multi-draw call.
 *
 * <p>The {@code add...} methods write the words directly and allocate nothing. Padding bytes (when {@code pad16} makes the stride a multiple of 16)
 * are never written. Not thread-safe: one writer per buffer region.
 */
public final class DrawCommandBuffer {

    /** The command kinds, with their size in bytes. */
    public enum Kind {
        /** {@link DrawArraysIndirect}, 16 bytes. */
        ARRAYS(16),
        /** {@link DrawElementsIndirect}, 20 bytes. */
        ELEMENTS(20),
        /** {@link DispatchIndirect}, 12 bytes. */
        DISPATCH(12);

        private final int bytes;

        Kind(int bytes) {
            this.bytes = bytes;
        }

        /** Size of one command in bytes. */
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
     * @param segment the memory to write into, starting at offset 0
     * @param kind    which command to store
     * @param pad16   round the stride up to a multiple of 16 bytes (some drivers and tools want 16-byte aligned commands); the commands themselves
     *                keep their size and are placed at the start of each slot
     */
    public DrawCommandBuffer(MemorySegment segment, Kind kind, boolean pad16) {
        this.segment = segment;
        this.kind = kind;
        this.stride = stride(kind, pad16);
        this.capacity = segment.byteSize() / stride;
    }

    /** Bytes between the starts of consecutive commands of {@code kind}. */
    public static long stride(Kind kind, boolean pad16) {
        return pad16 ? (kind.bytes + 15L) & ~15L : kind.bytes;
    }

    public long stride() {
        return stride;
    }

    /** How many commands fit. */
    public int capacity() {
        return (int) Math.min(capacity, Integer.MAX_VALUE);
    }

    /** Commands written so far: the draw count. */
    public int count() {
        return count;
    }

    /** Bytes used so far, including padding between commands (but not after the last one). */
    public long usedBytes() {
        return count == 0 ? 0 : (count - 1) * stride + kind.bytes;
    }

    /** Byte offset of command {@code index}, for {@code glMultiDraw*Indirect}'s offset argument or for a sub-range upload. */
    public long byteOffset(int index) {
        return index * stride;
    }

    /** Forgets all commands; the memory is not cleared. */
    public void clear() {
        count = 0;
    }

    /** Appends a {@link DrawArraysIndirect} command; returns its index. */
    public int addArrays(int vertexCount, int instanceCount, int first, int baseInstance) {
        long o = next(Kind.ARRAYS);
        GpuWriter.putInt(segment, o, vertexCount);
        GpuWriter.putInt(segment, o + 4, instanceCount);
        GpuWriter.putInt(segment, o + 8, first);
        GpuWriter.putInt(segment, o + 12, baseInstance);
        return count++;
    }

    /** Appends a {@link DrawElementsIndirect} command; returns its index. */
    public int addElements(int indexCount, int instanceCount, int firstIndex, int baseVertex, int baseInstance) {
        long o = next(Kind.ELEMENTS);
        GpuWriter.putInt(segment, o, indexCount);
        GpuWriter.putInt(segment, o + 4, instanceCount);
        GpuWriter.putInt(segment, o + 8, firstIndex);
        GpuWriter.putInt(segment, o + 12, baseVertex);
        GpuWriter.putInt(segment, o + 16, baseInstance);
        return count++;
    }

    /** Appends a {@link DispatchIndirect} command; returns its index. */
    public int addDispatch(int x, int y, int z) {
        long o = next(Kind.DISPATCH);
        GpuWriter.putInt(segment, o, x);
        GpuWriter.putInt(segment, o + 4, y);
        GpuWriter.putInt(segment, o + 8, z);
        return count++;
    }

    /** Appends a command given as a record; returns its index. */
    public int add(DrawArraysIndirect c) {
        return addArrays(c.count(), c.instanceCount(), c.first(), c.baseInstance());
    }

    public int add(DrawElementsIndirect c) {
        return addElements(c.count(), c.instanceCount(), c.firstIndex(), c.baseVertex(), c.baseInstance());
    }

    public int add(DispatchIndirect c) {
        return addDispatch(c.x(), c.y(), c.z());
    }

    /**
     * Reads back the instance count of command {@code index} (word 1 of arrays and elements commands): the field a culling pass sets to 0 to hide a
     * draw. Dispatch commands have no instance count.
     */
    public int instanceCount(int index) {
        if (kind == Kind.DISPATCH) {
            throw new UnsupportedOperationException("a dispatch command has no instance count");
        }
        checkIndex(index);
        return GpuWriter.getInt(segment, index * stride + 4);
    }

    /** Reads back the base instance of command {@code index} (the first instance slot of its draw: word 4 of elements commands, word 3 of arrays commands). */
    public int baseInstance(int index) {
        if (kind == Kind.DISPATCH) {
            throw new UnsupportedOperationException("a dispatch command has no base instance");
        }
        checkIndex(index);
        return GpuWriter.getInt(segment, index * stride + (kind == Kind.ELEMENTS ? 16 : 12));
    }

    /** Overwrites the instance count of command {@code index}: zero makes the draw a no-op without changing the draw count. */
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
