package vmath.bulk;

import java.util.Arrays;

/**
 * A fixed-capacity set of object indices, stored as a bitset.
 *
 * <p>Culling stages refine one shared set: it starts with every object set ({@link #setAll}) and
 * each stage clears the bits of the objects it rejects. Sixty-four objects share a word, so
 * combining sets and counting them is cheap, and {@link #toIndices} compacts survivors for the draw
 * list.
 *
 * <p>Threading: writers that own disjoint 64-aligned index ranges do not conflict.
 *
 * <p><b>Thread safety.</b> Not thread-safe in general. Writers that own disjoint 64-aligned index
 * ranges do not conflict, which is how {@link vmath.spatial.ParallelFrustumKernel} works, but
 * nobody may read a word while another thread writes it.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * VisibilitySet visible = new VisibilitySet(1000);
 * visible.setAll(1000);                                                       // everything visible
 * visible.clear(7);                                                           // a culling stage rejects object 7
 * int[] survivors = new int[1000];
 * int count = visible.toIndices(survivors);                                   // 999 indices, ascending
 * }</pre>
 */
public final class VisibilitySet {

    private long[] words;
    private int capacity;

    /**
     * Creates a set for indices {@code 0 .. capacity - 1}, all clear.
     *
     * @param capacity the capacity in elements
     */
    public VisibilitySet(int capacity) {
        this.capacity = capacity;
        this.words = new long[wordsFor(capacity)];
    }

    private static int wordsFor(int bits) {
        return (bits + 63) >>> 6;
    }

    /**
     * Exposes the number of indices that the set can hold.
     *
     * @return the number of indices the set holds
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Grows to hold {@code bits} indices, keeping current contents; new bits are clear.
     *
     * @param bits the number of bits
     */
    public void ensureCapacity(int bits) {
        if (bits > capacity) {
            words = Arrays.copyOf(words, wordsFor(bits));
            capacity = bits;
        }
    }

    /**
     * Returns whether bit {@code i} is set; the index must be below the capacity.
     *
     * @param i the index
     * @return {@code true} if bit {@code i} is set; the index must be below the capacity
     */
    public boolean get(int i) {
        return (words[i >>> 6] & (1L << i)) != 0L;
    }

    /**
     * Sets bit {@code i}.
     *
     * @param i the index
     */
    public void set(int i) {
        words[i >>> 6] |= 1L << i;
    }

    /**
     * Clears bit {@code i}.
     *
     * @param i the index
     */
    public void clear(int i) {
        words[i >>> 6] &= ~(1L << i);
    }

    /**
     * Clears every bit.
     */
    public void clearAll() {
        Arrays.fill(words, 0L);
    }

    /**
     * Sets exactly the first {@code n} bits and clears the rest.
     *
     * @param n the number of elements
     * @throws IllegalArgumentException if {@code n} exceeds the capacity
     */
    public void setAll(int n) {
        if (n > capacity) {
            throw new IllegalArgumentException("n " + n + " exceeds capacity " + capacity);
        }
        int full = n >>> 6;
        Arrays.fill(words, 0, full, -1L);
        Arrays.fill(words, full, words.length, 0L);
        if ((n & 63) != 0) {
            words[full] = (1L << n) - 1L;
        }
    }

    /**
     * Counts the set bits by summing the population count of each word.
     *
     * @return number of set bits
     */
    public int count() {
        int c = 0;
        for (long w : words) {
            c += Long.bitCount(w);
        }
        return c;
    }

    /**
     * Searches for the next set bit at or after a position, a word at a time; the way to iterate
     * over visible objects.
     *
     * @param from the index to start searching at
     * @return index of the first set bit at or after {@code from}, or -1
     */
    public int nextSetBit(int from) {
        if (from >= capacity) {
            return -1;
        }
        int wi = from >>> 6;
        long w = words[wi] & (-1L << from);
        while (true) {
            if (w != 0L) {
                return (wi << 6) + Long.numberOfTrailingZeros(w);
            }
            if (++wi == words.length) {
                return -1;
            }
            w = words[wi];
        }
    }

    /**
     * Appends the set indices, ascending, to {@code out}.
     *
     * @param out receives the result; must not be {@code null}
     */
    public void toIndices(IntList out) {
        for (int wi = 0; wi < words.length; wi++) {
            long w = words[wi];
            int base = wi << 6;
            while (w != 0L) {
                out.add(base + Long.numberOfTrailingZeros(w));
                w &= w - 1L;
            }
        }
    }

    /**
     * Writes the set indices, ascending, into {@code out} and returns how many.
     *
     * <p>{@code out} must be big enough.
     *
     * @param out receives the result
     * @return how many
     */
    public int toIndices(int[] out) {
        int n = 0;
        for (int wi = 0; wi < words.length; wi++) {
            long w = words[wi];
            int base = wi << 6;
            while (w != 0L) {
                out[n++] = base + Long.numberOfTrailingZeros(w);
                w &= w - 1L;
            }
        }
        return n;
    }

    /**
     * Intersects this set with the other one over the shared capacity: {@code this &= o}.
     *
     * @param o the other visibility set; must not be {@code null}
     */
    public void and(VisibilitySet o) {
        int n = Math.min(words.length, o.words.length);
        for (int i = 0; i < n; i++) {
            words[i] &= o.words[i];
        }
        Arrays.fill(words, n, words.length, 0L);
    }

    /**
     * Adds the other set to this one over the shared capacity: {@code this |= o}.
     *
     * @param o the other visibility set; must not be {@code null}
     */
    public void or(VisibilitySet o) {
        int n = Math.min(words.length, o.words.length);
        for (int i = 0; i < n; i++) {
            words[i] |= o.words[i];
        }
    }

    /**
     * Removes the bits of the other set from this one: {@code this &= ~o}.
     *
     * @param o the other visibility set; must not be {@code null}
     */
    public void andNot(VisibilitySet o) {
        int n = Math.min(words.length, o.words.length);
        for (int i = 0; i < n; i++) {
            words[i] &= ~o.words[i];
        }
    }

    /**
     * Makes this set equal to {@code o}, growing it if needed.
     *
     * @param o the other visibility set; must not be {@code null}
     */
    public void copyFrom(VisibilitySet o) {
        ensureCapacity(o.capacity);
        System.arraycopy(o.words, 0, words, 0, o.words.length);
        Arrays.fill(words, o.words.length, words.length, 0L);
    }

    /**
     * Exposes the backing words as a live array, for kernels that process the set a word at a time.
     *
     * @return the live backing words (bit {@code i} is {@code words[i >>> 6] & (1L << i)}), for
     *     kernels
     */
    public long[] words() {
        return words;
    }
}
