package vmath.bulk;

import java.util.Arrays;
import vmath.annotations.Experimental;

/**
 * Stable entity handles over densely packed data: a sparse set with generation counters. {@link #create()} hands out a {@code long} handle; the handle stays valid
 * until {@link #destroy}, after which {@link #isAlive} is false for it even if its slot is reused (the generation differs), so a stale handle can never reach a new
 * entity. Alive entities occupy the dense indices {@code 0 .. size() - 1} with no holes, so component arrays ({@link Mat4fArray}, {@link TransformArray}, any
 * {@code float[]}) indexed by the dense index can be iterated straight through and uploaded as they are.
 *
 * <p>Destroying swaps the last alive entity into the freed dense slot, like {@code removeSwap} on the containers: {@link #destroy} returns the freed dense index
 * {@code d}; if {@code d < size()} afterwards, the entity that was last (at dense index {@code size()}) now sits at {@code d}, and the caller does
 * {@code removeSwap(d)} on every parallel component array to match. {@link #denseIndex} maps a handle to its current dense index (it changes when other entities are
 * destroyed), {@link #handleAt} the other way.
 *
 * <p>A handle is {@code generation << 32 | slot}; generations start at 1, so {@link #INVALID} (0) is never alive. A slot's generation wraps after 2^32 - 1
 * reuses of that one slot (skipping 0), at which point a handle that old could in theory be mistaken for a current one. Nothing allocates except growth. Not
 * thread-safe.
 */
@Experimental("the API may grow")
public final class HandleRegistry {

    /** A handle that is never alive. */
    public static final long INVALID = 0L;

    private int[] generation;
    private int[] denseOf;       // slot -> dense index, or -1 when the slot is free
    private int[] nextFree;      // slot -> next free slot, valid while the slot is free
    private int[] sparseOf;      // dense index -> slot
    private int slots;
    private int size;
    private int freeHead = -1;

    /** An empty registry with room for 16 entities; it grows as handles are created. */
    public HandleRegistry() {
        this(16);
    }

    /** An empty registry with room for {@code capacity} entities (at least 1); it grows as handles are created. */
    public HandleRegistry(int capacity) {
        int c = Math.max(capacity, 1);
        generation = new int[c];
        denseOf = new int[c];
        nextFree = new int[c];
        sparseOf = new int[c];
    }

    /** The slot (index into the sparse side) of a handle. */
    public static int slot(long handle) {
        return (int) handle;
    }

    /** The generation of a handle. */
    public static int generation(long handle) {
        return (int) (handle >>> 32);
    }

    /** The handle for {@code slot} and {@code generation}: the generation in the high 32 bits, the slot in the low 32. */
    public static long pack(int slot, int generation) {
        return ((long) generation << 32) | (slot & 0xFFFFFFFFL);
    }

    /** The number of alive entities, which is also the length of the dense arrays that are in use. */
    public int size() {
        return size;
    }

    /** Makes room for {@code n} entities without further allocation. */
    public void reserve(int n) {
        if (n > generation.length) {
            int c = Math.max(n, generation.length * 2);
            generation = Arrays.copyOf(generation, c);
            denseOf = Arrays.copyOf(denseOf, c);
            nextFree = Arrays.copyOf(nextFree, c);
            sparseOf = Arrays.copyOf(sparseOf, c);
        }
    }

    /** Creates an entity at the end of the dense range (its dense index is {@code size() - 1} afterwards) and returns its handle. */
    public long create() {
        int slot;
        if (freeHead >= 0) {
            slot = freeHead;
            freeHead = nextFree[slot];
        } else {
            if (slots == Integer.MAX_VALUE) {
                throw new IllegalStateException("out of handle slots");
            }
            reserve(slots + 1);
            slot = slots++;
            generation[slot] = 1;
        }
        int dense = size++;
        denseOf[slot] = dense;
        sparseOf[dense] = slot;
        return pack(slot, generation[slot]);
    }

    /** Whether {@code handle} refers to an entity that has been created and not destroyed. */
    public boolean isAlive(long handle) {
        int slot = slot(handle);
        return slot >= 0 && slot < slots && generation[slot] == generation(handle) && denseOf[slot] >= 0;
    }

    /** The current dense index of the entity, or -1 if the handle is not alive. */
    public int denseIndex(long handle) {
        return isAlive(handle) ? denseOf[slot(handle)] : -1;
    }

    /** The handle of the entity at dense index {@code dense}. */
    public long handleAt(int dense) {
        if (dense < 0 || dense >= size) {
            throw new IndexOutOfBoundsException("dense index " + dense + ", size " + size);
        }
        int slot = sparseOf[dense];
        return pack(slot, generation[slot]);
    }

    /**
     * Destroys the entity. Returns the dense index it had (see the class comment for mirroring the removal in component arrays), or -1 if the handle was not
     * alive (destroying twice, or a stale handle, is harmless).
     */
    public int destroy(long handle) {
        if (!isAlive(handle)) {
            return -1;
        }
        int slot = slot(handle);
        int dense = denseOf[slot];
        int last = size - 1;
        if (dense != last) {
            int movedSlot = sparseOf[last];
            sparseOf[dense] = movedSlot;
            denseOf[movedSlot] = dense;
        }
        size--;
        denseOf[slot] = -1;
        int g = generation[slot] + 1;
        generation[slot] = g == 0 ? 1 : g;
        nextFree[slot] = freeHead;
        freeHead = slot;
        return dense;
    }

    /** Destroys every entity; all existing handles become stale. */
    public void clear() {
        for (int dense = size - 1; dense >= 0; dense--) {
            destroy(handleAt(dense));
        }
    }

    /** Copies the handles of all alive entities, in dense order, into {@code out[0 .. size())}. */
    public void copyHandles(long[] out) {
        if (out.length < size) {
            throw new IllegalArgumentException("the array of " + out.length + " is smaller than the " + size + " alive entities");
        }
        for (int d = 0; d < size; d++) {
            int slot = sparseOf[d];
            out[d] = pack(slot, generation[slot]);
        }
    }
}
