package vmath.pack;

/**
 * IEEE 754 half-precision (16-bit) floats, held as {@code short} bits.
 *
 * <p>Half floats halve the memory and bandwidth of vertex attributes, textures and animation data
 * at the cost of range and precision: 11 significant bits, and finite values only up to
 * {@value #MAX_VALUE}.
 *
 * <p>The conversions are the JDK's own {@link Float#floatToFloat16(float)} and
 * {@link Float#float16ToFloat(short)}, so rounding is round-to-nearest-even and they are
 * intrinsified on hardware with F16C or NEON. This class adds bulk versions over arrays and packing
 * of two or four halves into one {@code int} or {@code long}.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time. The arrays and buffers you pass in are not synchronised, so two threads must not write
 * the same one.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * short bits = Half.toBits(0.1f);                                        // IEEE 754 half precision
 * float rounded = Half.round(0.1f);                                      // the float that a half can hold
 * int pair = Half.pack2(1f, 2f);
 * float second = Half.unpack2(pair, 1);                                  // 2
 * }</pre>
 */
public final class Half {

    private Half() {
    }

    /**
     * Largest finite half value.
     *
     * <p>Larger magnitudes round to infinity.
     */
    public static final float MAX_VALUE = 65504f;
    /**
     * Smallest positive normal half value, {@code 2^-14}.
     */
    public static final float MIN_NORMAL = 6.103515625e-5f;
    /**
     * Smallest positive (subnormal) half value, {@code 2^-24}.
     */
    public static final float MIN_VALUE = 5.960464477539063e-8f;

    /**
     * Converts a float to an IEEE half precision value with round-to-nearest-even; values beyond
     * the half range become infinity.
     *
     * <p>NaN stays NaN; overflow gives infinity.
     *
     * @param f the value to convert
     * @return bits of the half nearest to {@code f} (ties to even)
     */
    public static short toBits(float f) {
        return Float.floatToFloat16(f);
    }

    /**
     * Widens a half precision value to a float, which is always exact.
     *
     * @param bits the number of bits
     * @return the float value of the half with these bits (exact: every half is a float)
     */
    public static float toFloat(short bits) {
        return Float.float16ToFloat(bits);
    }

    /**
     * Rounds a float to the nearest value that half precision can store, which shows what
     * quantisation will do to a value.
     *
     * @param f the value to convert
     * @return the float nearest to {@code f} that a half can hold, i.e. {@code toFloat(toBits(f))}
     */
    public static float round(float f) {
        return Float.float16ToFloat(Float.floatToFloat16(f));
    }

    /**
     * Checks that a half precision value is neither infinity nor NaN.
     *
     * @param bits the number of bits
     * @return {@code true} if the bits are neither infinity nor NaN
     */
    public static boolean isFinite(short bits) {
        return (bits & 0x7C00) != 0x7C00;
    }

    // ---------------------------------------------------------------- bulk

    /**
     * Converts {@code count} floats from {@code src[srcOffset..]} into {@code dst[dstOffset..]}.
     *
     * @param src the source to read from
     * @param srcOffset the index of the first element read from the source
     * @param dst receives the result
     * @param dstOffset the index of the first element written to the destination
     * @param count the number of elements
     */
    public static void pack(float[] src, int srcOffset, short[] dst, int dstOffset, int count) {
        for (int i = 0; i < count; i++) {
            dst[dstOffset + i] = Float.floatToFloat16(src[srcOffset + i]);
        }
    }

    /**
     * Converts {@code count} halves from {@code src[srcOffset..]} into {@code dst[dstOffset..]}.
     *
     * @param src the source to read from
     * @param srcOffset the index of the first element read from the source
     * @param dst receives the result
     * @param dstOffset the index of the first element written to the destination
     * @param count the number of elements
     */
    public static void unpack(short[] src, int srcOffset, float[] dst, int dstOffset, int count) {
        for (int i = 0; i < count; i++) {
            dst[dstOffset + i] = Float.float16ToFloat(src[srcOffset + i]);
        }
    }

    // ---------------------------------------------------------------- small vectors in one word

    /**
     * Packs two floats as half precision values into one int, like GLSL {@code packHalf2x16}.
     *
     * @param x the x component
     * @param y the y component
     * @return two halves in one int: {@code x} in the low 16 bits, {@code y} in the high 16 (GLSL
     *     {@code packHalf2x16})
     */
    public static int pack2(float x, float y) {
        return (Float.floatToFloat16(x) & 0xFFFF) | (Float.floatToFloat16(y) << 16);
    }

    /**
     * Unpacks one half precision component from an int that holds two.
     *
     * @param packed the packed value
     * @param i the index
     * @return component {@code i} (0 or 1) of an int made by {@link #pack2}
     */
    public static float unpack2(int packed, int i) {
        return Float.float16ToFloat((short) (packed >>> (16 * i)));
    }

    /**
     * Packs four floats as half precision values into one long.
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @param w the w component
     * @return four halves in one long: {@code x} lowest, {@code w} highest
     */
    public static long pack4(float x, float y, float z, float w) {
        return (Float.floatToFloat16(x) & 0xFFFFL) | ((Float.floatToFloat16(y) & 0xFFFFL) << 16)
                | ((Float.floatToFloat16(z) & 0xFFFFL) << 32) | ((long) Float.floatToFloat16(w) << 48);
    }

    /**
     * Unpacks one half precision component from a long that holds four.
     *
     * @param packed the packed value
     * @param i the index
     * @return component {@code i} (0 to 3) of a long made by {@link #pack4}
     */
    public static float unpack4(long packed, int i) {
        return Float.float16ToFloat((short) (packed >>> (16 * i)));
    }
}
