package vmath.bulk;

import java.util.Arrays;

/**
 * A fixed-capacity set of object indices, stored as a bitset. Culling stages refine one shared set: it starts with
 * every object set ({@link #setAll}) and each stage clears the bits of the objects it rejects. Sixty-four objects share
 * a word, so combining sets and counting them is cheap, and {@link #toIndices} compacts survivors for the draw list.
 *
 * <p>Threading: writers that own disjoint 64-aligned index ranges do not conflict.
 */
public final class VisibilitySet {

    private long[] words;
    private int capacity;

    public VisibilitySet(int capacity) {
        this.capacity = capacity;
        this.words = new long[wordsFor(capacity)];
    }

    private static int wordsFor(int bits) {
        return (bits + 63) >>> 6;
    }

    public int capacity() {
        return capacity;
    }

    /** Grows to hold {@code bits} indices, keeping current contents; new bits are clear. */
    public void ensureCapacity(int bits) {
        if (bits > capacity) {
            words = Arrays.copyOf(words, wordsFor(bits));
            capacity = bits;
        }
    }

    public boolean get(int i) {
        return (words[i >>> 6] & (1L << i)) != 0L;
    }

    public void set(int i) {
        words[i >>> 6] |= 1L << i;
    }

    public void clear(int i) {
        words[i >>> 6] &= ~(1L << i);
    }

    public void clearAll() {
        Arrays.fill(words, 0L);
    }

    /** Sets exactly the first {@code n} bits and clears the rest. */
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

    /** Number of set bits. */
    public int count() {
        int c = 0;
        for (long w : words) {
            c += Long.bitCount(w);
        }
        return c;
    }

    /** Index of the first set bit at or after {@code from}, or -1. */
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

    /** Appends the set indices, ascending, to {@code out}. */
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

    /** Writes the set indices, ascending, into {@code out} and returns how many. {@code out} must be big enough. */
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

    /** {@code this &= o} over the shared capacity. */
    public void and(VisibilitySet o) {
        int n = Math.min(words.length, o.words.length);
        for (int i = 0; i < n; i++) {
            words[i] &= o.words[i];
        }
        Arrays.fill(words, n, words.length, 0L);
    }

    public void or(VisibilitySet o) {
        int n = Math.min(words.length, o.words.length);
        for (int i = 0; i < n; i++) {
            words[i] |= o.words[i];
        }
    }

    /** {@code this &= ~o}. */
    public void andNot(VisibilitySet o) {
        int n = Math.min(words.length, o.words.length);
        for (int i = 0; i < n; i++) {
            words[i] &= ~o.words[i];
        }
    }

    public void copyFrom(VisibilitySet o) {
        ensureCapacity(o.capacity);
        System.arraycopy(o.words, 0, words, 0, o.words.length);
        Arrays.fill(words, o.words.length, words.length, 0L);
    }

    /** The live backing words (bit {@code i} is {@code words[i >>> 6] & (1L << i)}), for kernels. */
    public long[] words() {
        return words;
    }
}
