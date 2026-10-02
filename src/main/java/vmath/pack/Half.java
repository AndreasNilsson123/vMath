package vmath.pack;

/**
 * IEEE 754 half-precision (16-bit) floats, held as {@code short} bits. Half floats halve the memory and bandwidth of vertex
 * attributes, textures and animation data at the cost of range and precision: 11 significant bits, and finite values only up to
 * {@value #MAX_VALUE}.
 *
 * <p>The conversions are the JDK's own {@link Float#floatToFloat16(float)} and {@link Float#float16ToFloat(short)}, so rounding is
 * round-to-nearest-even and they are intrinsified on hardware with F16C or NEON. This class adds bulk versions over arrays and
 * packing of two or four halves into one {@code int} or {@code long}.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the same time. The arrays and buffers you pass in are
 * not synchronised, so two threads must not write the same one.
 */
public final class Half {

    private Half() {
    }

    /** Largest finite half value. Larger magnitudes round to infinity. */
    public static final float MAX_VALUE = 65504f;
    /** Smallest positive normal half value, {@code 2^-14}. */
    public static final float MIN_NORMAL = 6.103515625e-5f;
    /** Smallest positive (subnormal) half value, {@code 2^-24}. */
    public static final float MIN_VALUE = 5.960464477539063e-8f;

    /** Bits of the half nearest to {@code f} (ties to even). NaN stays NaN; overflow gives infinity. */
    public static short toBits(float f) {
        return Float.floatToFloat16(f);
    }

    /** The float value of the half with these bits (exact: every half is a float). */
    public static float toFloat(short bits) {
        return Float.float16ToFloat(bits);
    }

    /** The float nearest to {@code f} that a half can hold, i.e. {@code toFloat(toBits(f))}. */
    public static float round(float f) {
        return Float.float16ToFloat(Float.floatToFloat16(f));
    }

    /** True if the bits are neither infinity nor NaN. */
    public static boolean isFinite(short bits) {
        return (bits & 0x7C00) != 0x7C00;
    }

    // ---------------------------------------------------------------- bulk

    /** Converts {@code count} floats from {@code src[srcOffset..]} into {@code dst[dstOffset..]}. */
    public static void pack(float[] src, int srcOffset, short[] dst, int dstOffset, int count) {
        for (int i = 0; i < count; i++) {
            dst[dstOffset + i] = Float.floatToFloat16(src[srcOffset + i]);
        }
    }

    /** Converts {@code count} halves from {@code src[srcOffset..]} into {@code dst[dstOffset..]}. */
    public static void unpack(short[] src, int srcOffset, float[] dst, int dstOffset, int count) {
        for (int i = 0; i < count; i++) {
            dst[dstOffset + i] = Float.float16ToFloat(src[srcOffset + i]);
        }
    }

    // ---------------------------------------------------------------- small vectors in one word

    /** Two halves in one int: {@code x} in the low 16 bits, {@code y} in the high 16 (GLSL {@code packHalf2x16}). */
    public static int pack2(float x, float y) {
        return (Float.floatToFloat16(x) & 0xFFFF) | (Float.floatToFloat16(y) << 16);
    }

    /** Component {@code i} (0 or 1) of an int made by {@link #pack2}. */
    public static float unpack2(int packed, int i) {
        return Float.float16ToFloat((short) (packed >>> (16 * i)));
    }

    /** Four halves in one long: {@code x} lowest, {@code w} highest. */
    public static long pack4(float x, float y, float z, float w) {
        return (Float.floatToFloat16(x) & 0xFFFFL) | ((Float.floatToFloat16(y) & 0xFFFFL) << 16)
                | ((Float.floatToFloat16(z) & 0xFFFFL) << 32) | ((long) Float.floatToFloat16(w) << 48);
    }

    /** Component {@code i} (0 to 3) of a long made by {@link #pack4}. */
    public static float unpack4(long packed, int i) {
        return Float.float16ToFloat((short) (packed >>> (16 * i)));
    }
}
