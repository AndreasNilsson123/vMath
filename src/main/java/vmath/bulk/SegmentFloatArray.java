package vmath.bulk;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import vmath.annotations.Experimental;
import vmath.core.Mat4f;
import vmath.core.Quatf;
import vmath.core.Vec3f;
import vmath.core.Vec4f;

/**
 * The off-heap twin of the {@code float[]} containers: a growable array of fixed-size elements of {@code floatsPerElement} native-order floats in a
 * {@link MemorySegment} outside the Java heap. The shape of the API is the same as {@link Mat4fArray} and its siblings (add, set, get, size, capacity, ensureCapacity,
 * clear, removeSwap, compact), with {@code float[]} scratch arguments instead of one class per element type, plus typed convenience methods for the common
 * element sizes. Use it where the data is read by native code or the GPU (memory-mapped buffers, shared upload staging), or where the heap must stay small.
 *
 * <p><b>Ownership.</b> The array owns its memory, in a shared {@link Arena}: {@link #close()} releases it (deterministically) and every method then fails. A {@link Cleaner}
 * releases it as a safety net if the array is garbage collected without having been closed, so a forgotten {@code close()} is not a permanent leak, but that is not a
 * substitute for closing: native memory is not counted against the heap, so the collector may not run for a long time. Because of the cleaner, <b>keep the array reachable
 * for as long as a {@link MemorySegment} fetched from {@link #segment()} is in use</b>; a segment outliving its array may be released under you.
 *
 * <p><b>Growth and other threads.</b> When the array grows, the segment is replaced and the old one is freed, so fetch {@link #segment()} again after adding (as with
 * {@code data()} on the heap containers). A thread that still holds the old segment gets an {@link IllegalStateException} on its next access. The shared arena lets other
 * threads read the memory, but only while no thread grows, clears or closes the array: size it in advance with {@link #ensureCapacity} before handing the segment to readers.
 * The memory is not zeroed beyond what the arena guarantees (zeroed), and elements beyond {@code size} are unspecified after removals.
 *
 * <p>Compared with the heap containers an access costs a bounds check on the segment but no copy; bulk transfers with {@link #copyFrom} and {@link #copyTo} move whole
 * runs. The batch kernels of the heap containers write straight into a segment with the {@code MemorySegment} overloads (for example
 * {@link TransformArray#toMatrices(MemorySegment, long, long)}).
 *
 * <p><b>Thread safety.</b> Not thread-safe for mutation: one thread adds, sets, removes, grows and closes. Reads from other threads are safe only while no thread mutates
 * (see above for growth).
 */
@Experimental("the typed accessors may grow")
public final class SegmentFloatArray implements AutoCloseable {

    private static final ValueLayout.OfFloat F = ValueLayout.JAVA_FLOAT_UNALIGNED;

    private static final Cleaner CLEANER = Cleaner.create();

    /** What the cleaner releases: the current arena (replaced on growth). It must not reference the array itself. */
    private static final class Owner implements Runnable {
        private Arena arena;

        Owner(Arena arena) {
            this.arena = arena;
        }

        @Override
        public void run() {
            if (arena.scope().isAlive()) {
                arena.close();
            }
        }
    }

    private final int stride;
    private final Owner owner;
    private final Cleaner.Cleanable cleanable;
    private Arena arena;
    private MemorySegment segment;
    private int capacity;
    private int size;

    public SegmentFloatArray(int floatsPerElement, int capacity) {
        if (floatsPerElement < 1) {
            throw new IllegalArgumentException("floatsPerElement must be positive: " + floatsPerElement);
        }
        this.stride = floatsPerElement;
        this.capacity = Math.max(capacity, 1);
        this.arena = Arena.ofShared();
        this.segment = arena.allocate((long) this.capacity * stride * Float.BYTES, Float.BYTES);
        this.owner = new Owner(arena);
        this.cleanable = CLEANER.register(this, owner);
    }

    /** Elements of 3 floats ({@link Vec3fArray}). */
    public static SegmentFloatArray ofVec3(int capacity) {
        return new SegmentFloatArray(Vec3fArray.STRIDE, capacity);
    }

    /** Elements of 4 floats ({@link Vec4fArray}, {@link QuatArray}). */
    public static SegmentFloatArray ofVec4(int capacity) {
        return new SegmentFloatArray(4, capacity);
    }

    /** Elements of 16 floats ({@link Mat4fArray}). */
    public static SegmentFloatArray ofMat4(int capacity) {
        return new SegmentFloatArray(Mat4fArray.STRIDE, capacity);
    }

    /** Elements of 10 floats ({@link TransformArray}). */
    public static SegmentFloatArray ofTransform(int capacity) {
        return new SegmentFloatArray(TransformArray.STRIDE, capacity);
    }

    public int floatsPerElement() {
        return stride;
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return capacity;
    }

    /** Bytes of one element. */
    public int elementBytes() {
        return stride * Float.BYTES;
    }

    /** The live segment (element {@code i} starts at byte {@code i * elementBytes()}), replaced when the array grows. */
    public MemorySegment segment() {
        return segment;
    }

    public void clear() {
        size = 0;
    }

    /** Sets the element count after writing into {@link #segment()} directly. */
    public void setSize(int n) {
        if (n < 0 || n > capacity) {
            throw new IllegalArgumentException("size " + n + " outside 0.." + capacity);
        }
        size = n;
    }

    public void ensureCapacity(int n) {
        if (n <= capacity) {
            return;
        }
        int next = (int) Math.min(Integer.MAX_VALUE / stride, Math.max((long) n, (long) capacity * 2));
        if (next < n) {
            throw new IllegalArgumentException("capacity " + n + " is too large for " + stride + " floats per element");
        }
        Arena fresh = Arena.ofShared();
        MemorySegment grown = fresh.allocate((long) next * stride * Float.BYTES, Float.BYTES);
        MemorySegment.copy(segment, 0, grown, 0, (long) size * stride * Float.BYTES);
        arena.close();
        arena = fresh;
        owner.arena = fresh;
        segment = grown;
        capacity = next;
    }

    private void checkIndex(int i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException("index " + i + ", size " + size);
        }
    }

    private void checkSource(float[] a, int off) {
        if (off < 0 || off + stride > a.length) {
            throw new IllegalArgumentException("a float[] range of " + stride + " starting at " + off + " does not fit an array of " + a.length);
        }
    }

    /** Appends an element read from {@code src[off .. off + floatsPerElement)}; returns its index. */
    public int add(float[] src, int off) {
        checkSource(src, off);
        ensureCapacity(size + 1);
        MemorySegment.copy(src, off, segment, F, (long) size * elementBytes(), stride);
        return size++;
    }

    public void set(int i, float[] src, int off) {
        checkIndex(i);
        checkSource(src, off);
        MemorySegment.copy(src, off, segment, F, (long) i * elementBytes(), stride);
    }

    /** Copies element {@code i} into {@code dst[off ..)}. */
    public void get(int i, float[] dst, int off) {
        checkIndex(i);
        checkSource(dst, off);
        MemorySegment.copy(segment, F, (long) i * elementBytes(), dst, off, stride);
    }

    /** One component of one element. */
    public float getFloat(int i, int component) {
        checkIndex(i);
        if (component < 0 || component >= stride) {
            throw new IndexOutOfBoundsException("component " + component + ", " + stride + " per element");
        }
        return segment.get(F, (long) i * elementBytes() + (long) component * Float.BYTES);
    }

    public void setFloat(int i, int component, float v) {
        checkIndex(i);
        if (component < 0 || component >= stride) {
            throw new IndexOutOfBoundsException("component " + component + ", " + stride + " per element");
        }
        segment.set(F, (long) i * elementBytes() + (long) component * Float.BYTES, v);
    }

    // ---------------------------------------------------------------- typed accessors (the element size must match)

    private void requireStride(int n, String what) {
        if (stride != n) {
            throw new IllegalStateException(what + " needs " + n + " floats per element, this array has " + stride);
        }
    }

    public int addVec3(Vec3f v) {
        requireStride(3, "a Vec3f");
        ensureCapacity(size + 1);
        long o = (long) size * 12;
        segment.set(F, o, v.x());
        segment.set(F, o + 4, v.y());
        segment.set(F, o + 8, v.z());
        return size++;
    }

    public Vec3f getVec3(int i) {
        requireStride(3, "a Vec3f");
        checkIndex(i);
        long o = (long) i * 12;
        return new Vec3f(segment.get(F, o), segment.get(F, o + 4), segment.get(F, o + 8));
    }

    public int addVec4(Vec4f v) {
        requireStride(4, "a Vec4f");
        ensureCapacity(size + 1);
        long o = (long) size * 16;
        segment.set(F, o, v.x());
        segment.set(F, o + 4, v.y());
        segment.set(F, o + 8, v.z());
        segment.set(F, o + 12, v.w());
        return size++;
    }

    public Vec4f getVec4(int i) {
        requireStride(4, "a Vec4f");
        checkIndex(i);
        long o = (long) i * 16;
        return new Vec4f(segment.get(F, o), segment.get(F, o + 4), segment.get(F, o + 8), segment.get(F, o + 12));
    }

    public int addQuat(Quatf q) {
        return addVec4(new Vec4f(q.x(), q.y(), q.z(), q.w()));
    }

    public Quatf getQuat(int i) {
        Vec4f v = getVec4(i);
        return new Quatf(v.x(), v.y(), v.z(), v.w());
    }

    public int addMat4(Mat4f m) {
        requireStride(16, "a Mat4f");
        ensureCapacity(size + 1);
        long o = (long) size * 64;
        segment.set(F, o, m.m00());
        segment.set(F, o + 4, m.m01());
        segment.set(F, o + 8, m.m02());
        segment.set(F, o + 12, m.m03());
        segment.set(F, o + 16, m.m10());
        segment.set(F, o + 20, m.m11());
        segment.set(F, o + 24, m.m12());
        segment.set(F, o + 28, m.m13());
        segment.set(F, o + 32, m.m20());
        segment.set(F, o + 36, m.m21());
        segment.set(F, o + 40, m.m22());
        segment.set(F, o + 44, m.m23());
        segment.set(F, o + 48, m.m30());
        segment.set(F, o + 52, m.m31());
        segment.set(F, o + 56, m.m32());
        segment.set(F, o + 60, m.m33());
        return size++;
    }

    /** The matrix at index {@code i}; allocates the returned value only (use {@link #get(int, float[], int)} to avoid even that). */
    public Mat4f getMat4(int i) {
        requireStride(16, "a Mat4f");
        checkIndex(i);
        long o = (long) i * 64;
        return new Mat4f(
                segment.get(F, o), segment.get(F, o + 4), segment.get(F, o + 8), segment.get(F, o + 12),
                segment.get(F, o + 16), segment.get(F, o + 20), segment.get(F, o + 24), segment.get(F, o + 28),
                segment.get(F, o + 32), segment.get(F, o + 36), segment.get(F, o + 40), segment.get(F, o + 44),
                segment.get(F, o + 48), segment.get(F, o + 52), segment.get(F, o + 56), segment.get(F, o + 60));
    }

    // ---------------------------------------------------------------- compaction

    /** Removes element {@code i} by moving the last into its place; returns the index the moved element had, or -1 if {@code i} was last. */
    public int removeSwap(int i) {
        checkIndex(i);
        int last = size - 1;
        int moved = -1;
        if (i != last) {
            MemorySegment.copy(segment, (long) last * elementBytes(), segment, (long) i * elementBytes(), elementBytes());
            moved = last;
        }
        size--;
        return moved;
    }

    /** Keeps only the elements whose bit is set in {@code keep}, in order. Returns the new size. */
    public int compact(VisibilitySet keep) {
        int out = 0;
        int i = keep.nextSetBit(0);
        int bytes = elementBytes();
        while (i >= 0 && i < size) {
            if (out != i) {
                MemorySegment.copy(segment, (long) i * bytes, segment, (long) out * bytes, bytes);
            }
            out++;
            i = keep.nextSetBit(i + 1);
        }
        size = out;
        return out;
    }

    // ---------------------------------------------------------------- bulk transfer

    /** Replaces the contents with {@code count} elements read from {@code src[0 .. count * floatsPerElement)}, such as {@code Mat4fArray.data()}. */
    public void copyFrom(float[] src, int count) {
        if (count < 0 || (long) count * stride > src.length) {
            throw new IllegalArgumentException("count " + count + " does not fit an array of " + src.length + " floats");
        }
        size = 0;
        ensureCapacity(count);
        MemorySegment.copy(src, 0, segment, F, 0L, count * stride);
        size = count;
    }

    /** Copies all elements into {@code dst[0 ..)}, which must hold {@code size * floatsPerElement} floats. */
    public void copyTo(float[] dst) {
        if ((long) size * stride > dst.length) {
            throw new IllegalArgumentException("the destination of " + dst.length + " floats is too small for " + size * stride);
        }
        MemorySegment.copy(segment, F, 0L, dst, 0, size * stride);
    }

    /** Releases the memory. Further use of this array, or of a segment fetched earlier, throws. Closing twice is harmless. */
    @Override
    public void close() {
        cleanable.clean();
        Reference.reachabilityFence(this);
    }
}
