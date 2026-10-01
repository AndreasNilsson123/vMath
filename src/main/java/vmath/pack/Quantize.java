package vmath.pack;

import vmath.annotations.Experimental;

/**
 * Quantization to an arbitrary number of bits, as mesh pipelines such as meshoptimizer use it: normalized integers of 1 to 24 bits, and rounding of a float's mantissa
 * to fewer bits (which keeps the value a float but makes the data far more compressible). {@link Norm} has the fixed 8-, 10- and 16-bit formats with the GL packing
 * rules; this is for formats of your own choosing, such as 12-bit UVs or 14-bit positions. The conventions are the same as there: values outside the range are clamped,
 * NaN becomes 0, rounding is to nearest (ties up), and zero is exact in the signed form.
 */
@Experimental("the set of helpers may grow")
public final class Quantize {

    private Quantize() {
    }

    private static void checkBits(int bits, int min, int max) {
        if (bits < min || bits > max) {
            throw new IllegalArgumentException("bits must be in [" + min + ", " + max + "]: " + bits);
        }
    }

    /** The largest unsigned code of {@code bits} bits: {@code 2^bits - 1}. */
    public static int unormMax(int bits) {
        checkBits(bits, 1, 24);
        return (1 << bits) - 1;
    }

    /** The largest signed code: {@code 2^(bits-1) - 1} (the codes run from its negative to it, so zero is exact). */
    public static int snormMax(int bits) {
        checkBits(bits, 2, 24);
        return (1 << (bits - 1)) - 1;
    }

    /** {@code v} in {@code [0, 1]} as a code in {@code [0, 2^bits - 1]}, nearest. */
    public static int unorm(float v, int bits) {
        int max = unormMax(bits);
        if (!(v > 0f)) {
            return 0;
        }
        if (v >= 1f) {
            return max;
        }
        return (int) ((double) v * max + 0.5);
    }

    /** The value of an unsigned code, in {@code [0, 1]}. */
    public static float fromUnorm(int code, int bits) {
        int max = unormMax(bits);
        return (float) ((double) Math.max(0, Math.min(code, max)) / max);
    }

    /** {@code v} in {@code [-1, 1]} as a code in {@code [-(2^(bits-1) - 1), 2^(bits-1) - 1]}, nearest (ties up, as in {@link Norm}). */
    public static int snorm(float v, int bits) {
        int max = snormMax(bits);
        if (!(v > -1f)) {
            return v != v ? 0 : -max;
        }
        if (v >= 1f) {
            return max;
        }
        return (int) Math.round((double) v * max);
    }

    /** The value of a signed code, in {@code [-1, 1]}; the most negative two's complement value {@code -2^(bits-1)} also maps to -1. */
    public static float fromSnorm(int code, int bits) {
        int max = snormMax(bits);
        return (float) Math.max(-1.0, (double) Math.min(code, max) / max);
    }

    /**
     * Rounds the mantissa of {@code f} to {@code bits} bits (of the 23 stored; 0 keeps only the power of two), to nearest with ties to even in the discarded bits'
     * sense of "round half up on the magnitude". Infinities and NaNs are unchanged, the sign is kept, and a value that rounds up to the next power of two does so
     * correctly. The result is a float, so it needs no decoding; its low {@code 23 - bits} mantissa bits are zero. Relative error at most {@code 2^-(bits+1)}.
     */
    public static float mantissa(float f, int bits) {
        checkBits(bits, 0, 23);
        int b = Float.floatToRawIntBits(f);
        if ((b & 0x7F800000) == 0x7F800000) {
            return f; // infinity or NaN
        }
        int drop = 23 - bits;
        if (drop == 0) {
            return f;
        }
        int half = 1 << (drop - 1);
        // adding half to the magnitude bits carries into the exponent when the mantissa overflows, which is the correct rounding up to the next power of two
        int magnitude = (b & 0x7FFFFFFF) + half;
        magnitude &= -(1 << drop);
        if (magnitude >= 0x7F800000) {
            return Float.intBitsToFloat((b & 0x80000000) | 0x7F800000); // rounded up to infinity
        }
        return Float.intBitsToFloat((b & 0x80000000) | magnitude);
    }

    /** {@link #mantissa(float, int)} of {@code count} floats of {@code a} starting at {@code offset}, in place. */
    public static void mantissa(float[] a, int offset, int count, int bits) {
        for (int i = offset; i < offset + count; i++) {
            a[i] = mantissa(a[i], bits);
        }
    }
}
