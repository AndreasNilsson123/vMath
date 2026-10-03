package vmath.core;

/**
 * Hilbert curve codes: like {@link Morton} codes, but consecutive codes are always neighbouring grid cells, so sorting by Hilbert code gives a better locality
 * than Z-order (no long jumps between quadrants). The price is a more expensive encode: a table lookup per two (3D) or four (2D) levels instead of a few shifts for the whole code.
 *
 * <p>The curve starts at the origin and visits every cell of the {@code 2^bits} grid once. It is hierarchical: the top {@code 2k} (2D) or {@code 3k} (3D)
 * bits of a code depend only on the top {@code k} bits of the coordinates, so a code prefix names a square or cube of cells. Coordinates are treated as unsigned
 * and must fit in {@code bits} bits (an {@link IllegalArgumentException} otherwise). The implementation is Skilling's transpose algorithm
 * (J. Skilling, "Programming the Hilbert curve", 2004).
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the same time. The arrays and buffers you pass in are
 * not synchronised, so two threads must not write the same one.
 */
public final class Hilbert {

    private Hilbert() {
    }

    /** Most bits per axis in 2D: the code then uses all 64 bits (unsigned). */
    public static final int MAX_BITS_2D = 32;
    /** Most bits per axis in 3D: 63 bits of code, always non-negative. */
    public static final int MAX_BITS_3D = 21;

    private static void checkBits(int bits, int max) {
        if (bits < 1 || bits > max) {
            throw new IllegalArgumentException("bits must be in [1, " + max + "]: " + bits);
        }
    }

    private static void checkCoordinate(int c, int bits) {
        if ((c & 0xFFFFFFFFL) >>> bits != 0L) {
            throw new IllegalArgumentException("coordinate " + Integer.toUnsignedString(c) + " does not fit in " + bits + " bits");
        }
    }

    /** Bit {@code j} of the result is the parity of the bits of {@code v} above bit {@code j}, excluding bit 0: the effect of the loop "xor q-1 for every set bit q above 1". */
    private static long suffixParity(long v) {
        long x = v >>> 1;
        x ^= x >>> 1;
        x ^= x >>> 2;
        x ^= x >>> 4;
        x ^= x >>> 8;
        x ^= x >>> 16;
        return x; // the coordinates have at most 32 bits, so x has at most 31 and these five steps cover them
    }

    /** The Hilbert index of cell {@code (x, y)} of a {@code 2^bits} by {@code 2^bits} grid ({@code 1 <= bits <= 32}); unsigned if {@code bits == 32}. */
    public static long encode2(int x, int y, int bits) {
        checkBits(bits, MAX_BITS_2D);
        checkCoordinate(x, bits);
        checkCoordinate(y, bits);
        return AUTOMATON_2.encode(Morton.encode2(y, x), bits);
    }

    /** Skilling's transpose algorithm as published, one bit at a time: the oracle of the table-driven {@link #encode2}, which gives the same codes. */
    static long encode2Reference(int x, int y, int bits) {
        checkBits(bits, MAX_BITS_2D);
        checkCoordinate(x, bits);
        checkCoordinate(y, bits);
        long a = x & 0xFFFFFFFFL, b = y & 0xFFFFFFFFL;
        long m = 1L << (bits - 1);
        // undo the excess work: invert and exchange low bits, from the top bit down
        for (long q = m; q > 1; q >>= 1) {
            long p = q - 1;
            int k = Long.numberOfTrailingZeros(q);
            a ^= p & -((a >>> k) & 1L);
            long mb = -((b >>> k) & 1L);
            long t = (a ^ b) & p & ~mb;
            a ^= (p & mb) ^ t;
            b ^= t;
        }
        // Gray encode
        b ^= a;
        long t = suffixParity(b);
        a ^= t;
        b ^= t;
        return Morton.spread2(b) | (Morton.spread2(a) << 1);
    }

    /** The cell of Hilbert index {@code code} in a {@code 2^bits} grid: the inverse of {@link #encode2}. */
    public static Vec2i decode2(long code, int bits) {
        checkBits(bits, MAX_BITS_2D);
        long b = Morton.compact2(code), a = Morton.compact2(code >>> 1);
        if (bits < 32 && (code >>> (2 * bits)) != 0) {
            throw new IllegalArgumentException("code " + Long.toUnsignedString(code) + " is outside a " + bits + "-bit grid");
        }
        long t = b >>> 1;
        b ^= a;
        a ^= t;
        long n = 1L << bits;
        for (long q = 2; q != n && q != 0; q <<= 1) {
            long p = q - 1;
            if ((b & q) != 0) {
                a ^= p;
            } else {
                long u = (a ^ b) & p;
                a ^= u;
                b ^= u;
            }
            if ((a & q) != 0) {
                a ^= p;
            }
        }
        return new Vec2i((int) a, (int) b);
    }

    /** The Hilbert index of cell {@code (x, y, z)} of a {@code 2^bits} cube ({@code 1 <= bits <= 21}). */
    public static long encode3(int x, int y, int z, int bits) {
        checkBits(bits, MAX_BITS_3D);
        checkCoordinate(x, bits);
        checkCoordinate(y, bits);
        checkCoordinate(z, bits);
        return AUTOMATON_3.encode(Morton.encode3(z, y, x), bits);
    }

    /** Skilling's transpose algorithm, branch-free, one bit at a time: the oracle of the table-driven {@link #encode3}, which gives the same codes. */
    static long encode3Reference(int x, int y, int z, int bits) {
        checkBits(bits, MAX_BITS_3D);
        checkCoordinate(x, bits);
        checkCoordinate(y, bits);
        checkCoordinate(z, bits);
        long a = x & 0xFFFFFFFFL, b = y & 0xFFFFFFFFL, c = z & 0xFFFFFFFFL;
        long m = 1L << (bits - 1);
        // branch-free: each "if bit then invert else exchange" is mask arithmetic, because the bits of coordinates are unpredictable
        for (long q = m; q > 1; q >>= 1) {
            long p = q - 1;
            int k = Long.numberOfTrailingZeros(q);
            a ^= p & -((a >>> k) & 1L);
            long mb = -((b >>> k) & 1L);
            long t = (a ^ b) & p & ~mb;
            a ^= (p & mb) ^ t;
            b ^= t;
            long mc = -((c >>> k) & 1L);
            t = (a ^ c) & p & ~mc;
            a ^= (p & mc) ^ t;
            c ^= t;
        }
        b ^= a;
        c ^= b;
        long t = suffixParity(c);
        a ^= t;
        b ^= t;
        c ^= t;
        return Morton.spread3(c) | (Morton.spread3(b) << 1) | (Morton.spread3(a) << 2);
    }

    /** The cell of Hilbert index {@code code} in a {@code 2^bits} cube: the inverse of {@link #encode3}. */
    public static Vec3i decode3(long code, int bits) {
        checkBits(bits, MAX_BITS_3D);
        if ((code >>> (3 * bits)) != 0) {
            throw new IllegalArgumentException("code " + code + " is outside a " + bits + "-bit cube");
        }
        long c = Morton.compact3(code), b = Morton.compact3(code >>> 1), a = Morton.compact3(code >>> 2);
        long t = c >>> 1;
        c ^= b;
        b ^= a;
        a ^= t;
        long n = 1L << bits;
        for (long q = 2; q != n; q <<= 1) {
            long p = q - 1;
            if ((c & q) != 0) {
                a ^= p;
            } else {
                long u = (a ^ c) & p;
                a ^= u;
                c ^= u;
            }
            if ((b & q) != 0) {
                a ^= p;
            } else {
                long u = (a ^ b) & p;
                a ^= u;
                b ^= u;
            }
            if ((a & q) != 0) {
                a ^= p;
            }
        }
        return new Vec3i((int) a, (int) b, (int) c);
    }

    // ---------------------------------------------------------------- the table-driven encoder

    private static final Automaton AUTOMATON_2 = new Automaton(2, 4);
    private static final Automaton AUTOMATON_3 = new Automaton(3, 2);

    /**
     * Skilling's algorithm as a finite automaton over Morton digits. Reading the coordinates from the top bit down, the work done on the bits below a level is always
     * one of a few signed permutations of the axes, together with the running parity of the Gray code; those (axis permutation, axis flips, parity) are the states,
     * 16 in 2D and 48 in 3D, found by exploring from the identity. A table gives, for a state and the bits of a level (one per axis), the code digit and
     * the next state; a second table does the same for {@code chunk} levels at once, so a 21-bit 3D code takes 11 lookups instead of 20 iterations of bit twiddling.
     * The tables are built once at class initialisation from the automaton's own definition, and the codes are checked against the original algorithm
     * ({@link #encode3Reference}) in the tests.
     */
    private static final class Automaton {
        private final int dims;
        private final int chunk;
        private final int[] one;   // [state << dims | r] = next << dims | digit
        private final int[] many;  // [state << (dims * chunk) | rr] = next << (dims * chunk) | digits

        /** An automaton state: for each effective axis the raw axis it reads and whether it is complemented; and the Gray-code parity. */
        private record State(int[] perm, boolean[] flip, int parity) {
            @Override
            public boolean equals(Object o) {
                return o instanceof State s && java.util.Arrays.equals(perm, s.perm) && java.util.Arrays.equals(flip, s.flip) && parity == s.parity;
            }

            @Override
            public int hashCode() {
                return java.util.Arrays.hashCode(perm) * 31 + java.util.Arrays.hashCode(flip) * 7 + parity;
            }
        }

        Automaton(int dims, int chunk) {
            this.dims = dims;
            this.chunk = chunk;
            java.util.List<State> states = new java.util.ArrayList<>();
            java.util.Map<State, Integer> index = new java.util.HashMap<>();
            int[] identity = new int[dims];
            for (int i = 0; i < dims; i++) {
                identity[i] = i;
            }
            State start = new State(identity, new boolean[dims], 0);
            index.put(start, 0);
            states.add(start);
            int[] digit = new int[1];
            for (int i = 0; i < states.size(); i++) {
                for (int r = 0; r < 1 << dims; r++) {
                    State n = step(states.get(i), r, digit);
                    if (!index.containsKey(n)) {
                        index.put(n, states.size());
                        states.add(n);
                    }
                }
            }
            int levelBits = dims * chunk;
            one = new int[states.size() << dims];
            many = new int[states.size() << levelBits];
            for (int i = 0; i < states.size(); i++) {
                for (int r = 0; r < 1 << dims; r++) {
                    State n = step(states.get(i), r, digit);
                    one[(i << dims) | r] = (index.get(n) << dims) | digit[0];
                }
                for (int rr = 0; rr < 1 << levelBits; rr++) {
                    State s = states.get(i);
                    int out = 0;
                    for (int level = chunk - 1; level >= 0; level--) {
                        s = step(s, (rr >>> (dims * level)) & ((1 << dims) - 1), digit);
                        out = (out << dims) | digit[0];
                    }
                    many[(i << levelBits) | rr] = (index.get(s) << levelBits) | out;
                }
            }
        }

        /** One level: {@code r} holds the raw bit of each axis (axis 0 in the highest bit); returns the next state and writes the code digit to {@code digitOut[0]}. */
        private State step(State s, int r, int[] digitOut) {
            int[] e = new int[dims];
            for (int i = 0; i < dims; i++) {
                e[i] = ((r >>> (dims - 1 - s.perm[i])) & 1) ^ (s.flip[i] ? 1 : 0);
            }
            int g = 0, digit = 0;
            int[] gray = new int[dims];
            for (int i = 0; i < dims; i++) {
                g ^= e[i];
                gray[i] = g;
            }
            for (int i = 0; i < dims; i++) {
                digit = (digit << 1) | (gray[i] ^ s.parity);
            }
            digitOut[0] = digit;
            int[] perm = s.perm.clone();
            boolean[] flip = s.flip.clone();
            for (int i = 0; i < dims; i++) {
                if (e[i] != 0) {
                    flip[0] = !flip[0];
                } else if (i != 0) {
                    int tp = perm[0];
                    perm[0] = perm[i];
                    perm[i] = tp;
                    boolean tf = flip[0];
                    flip[0] = flip[i];
                    flip[i] = tf;
                }
            }
            return new State(perm, flip, s.parity ^ gray[dims - 1]);
        }

        /** The Hilbert code of the cell whose Morton code is {@code morton} (axis 0 in the highest bit of each digit), for a grid of {@code bits} levels. */
        long encode(long morton, int bits) {
            int levelBits = dims * chunk;
            int mask = (1 << dims) - 1;
            int state = 0;
            long code = 0;
            int level = bits;
            while (level % chunk != 0) {
                level--;
                int e = one[(state << dims) | (int) ((morton >>> (dims * level)) & mask)];
                code = (code << dims) | (e & mask);
                state = e >>> dims;
            }
            long chunkMask = (1L << levelBits) - 1;
            while (level > 0) {
                level -= chunk;
                int e = many[(state << levelBits) | (int) ((morton >>> (dims * level)) & chunkMask)];
                code = (code << levelBits) | (e & chunkMask);
                state = e >>> levelBits;
            }
            return code;
        }
    }

    /** 32-bit code of a cell of a 65536 by 65536 grid; unsigned, so compare with {@link Integer#compareUnsigned}. */
    public static int encode2Int(int x, int y) {
        return (int) encode2(x, y, 16);
    }

    /** 30-bit code of a cell of a 1024-cell cube; non-negative. */
    public static int encode3Int(int x, int y, int z) {
        return (int) encode3(x, y, z, 10);
    }

    /** Hilbert code of {@code p} inside the given bounds at full 21-bit precision per axis (positions outside are clamped, see {@link Morton#quantize}). */
    public static long encode3(Vec3f p, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        int cells = Morton.MAX_3D + 1;
        return encode3(Morton.quantize(p.x(), minX, maxX, cells), Morton.quantize(p.y(), minY, maxY, cells),
                Morton.quantize(p.z(), minZ, maxZ, cells), MAX_BITS_3D);
    }
}
