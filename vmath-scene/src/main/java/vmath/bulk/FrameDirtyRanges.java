package vmath.bulk;

import vmath.annotations.Experimental;

/**
 * Dirty tracking for a buffer that exists once per frame in flight (a ring of N copies). A change must reach all N copies, one per frame, so each copy has its own
 * {@link DirtyRanges}: {@link #mark} marks an element in every one of them, and when the copy for frame slot {@code s} is about to be written, {@link #forSlot} gives
 * the set of everything that changed since that copy was last written. After uploading, clear that set ({@code forSlot(s).clear()}, or let
 * {@link DirtyRanges#uploadFloats} do it); the other copies keep their marks until their turn.
 *
 * <p>With one slot this is a plain {@link DirtyRanges}.
 *
 * <p><b>Thread safety.</b> Not thread-safe: it is mutable, so use one instance per thread or synchronise externally. Concurrent reads are safe only
 * while no thread is writing.
 */
@Experimental("the upload helpers may grow")
public final class FrameDirtyRanges {

    private final DirtyRanges[] slots;

    /** Sets for elements {@code 0 .. capacity - 1}, one for each of {@code slots} buffers (at least 1), all clean. */
    public FrameDirtyRanges(int capacity, int slots) {
        if (slots < 1) {
            throw new IllegalArgumentException("slots must be at least 1: " + slots);
        }
        this.slots = new DirtyRanges[slots];
        for (int i = 0; i < slots; i++) {
            this.slots[i] = new DirtyRanges(capacity);
        }
    }

    /** The number of buffers, one set each. */
    public int slots() {
        return slots.length;
    }

    /** The number of elements each set covers. */
    public int capacity() {
        return slots[0].capacity();
    }

    /** Grows every set to at least {@code n} elements; the new elements are clean. */
    public void ensureCapacity(int n) {
        for (DirtyRanges d : slots) {
            d.ensureCapacity(n);
        }
    }

    /** Marks element {@code i} in every slot. */
    public void mark(int i) {
        for (DirtyRanges d : slots) {
            d.mark(i);
        }
    }

    /** Marks elements {@code [from, to)} in every slot. */
    public void markRange(int from, int to) {
        for (DirtyRanges d : slots) {
            d.markRange(from, to);
        }
    }

    /** Marks everything in every slot (after the array was rebuilt, or the buffers were recreated). */
    public void markAll() {
        for (DirtyRanges d : slots) {
            d.markAll();
        }
    }

    /** The live set of the buffer copy for frame slot {@code slot} ({@code frame % slots()}). */
    public DirtyRanges forSlot(int slot) {
        if (slot < 0 || slot >= slots.length) {
            throw new IndexOutOfBoundsException("slot " + slot + " of " + slots.length);
        }
        return slots[slot];
    }
}
