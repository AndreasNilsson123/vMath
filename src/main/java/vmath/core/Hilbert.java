package vmath.core;

/**
 * Hilbert curve codes: like {@link Morton} codes, but consecutive codes are always neighbouring grid cells, so sorting by Hilbert code gives a better locality
 * than Z-order (no long jumps between quadrants). The price is a more expensive encode, a loop over the bits instead of a few shifts.
 *
 * <p>The curve starts at the origin and visits every cell of the {@code 2^bits} grid once. It is hierarchical: the top {@code 2k} (2D) or {@code 3k} (3D)
 * bits of a code depend only on the top {@code k} bits of the coordinates, so a code prefix names a square or cube of cells. Coordinates are treated as unsigned
 * and must fit in {@code bits} bits (an {@link IllegalArgumentException} otherwise). The implementation is Skilling's transpose algorithm
 * (J. Skilling, "Programming the Hilbert curve", 2004).
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
        x ^= x >>> 32;
        return x;
    }

    /** The Hilbert index of cell {@code (x, y)} of a {@code 2^bits} by {@code 2^bits} grid ({@code 1 <= bits <= 32}); unsigned if {@code bits == 32}. */
    public static long encode2(int x, int y, int bits) {
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
