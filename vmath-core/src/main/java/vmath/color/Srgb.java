package vmath.color;

import vmath.annotations.Experimental;

/**
 * The sRGB transfer function between the encoded values stored in 8-bit textures and the linear
 * light that lighting and blending need.
 *
 * <p>The exact functions are the piecewise definition of IEC 61966-2-1: linear below
 * {@code 0.04045} (encoded) and a power of 2.4 above it. Values below zero are mirrored
 * ({@code f(-x) = -f(x)}), values above one continue along the power curve (HDR), and NaN stays
 * NaN. The {@code Fast} variants are cheap approximations for inputs in {@code [0, 1]} (they clamp
 * their input): a cubic polynomial for decoding and the exact linear segment plus a sum of three
 * square roots for encoding, with the error measured in {@code docs/FORMATS.md}. Use the exact
 * functions where the result must match what the GPU's sRGB hardware does (it is exact), and the
 * fast ones for bulk work where a fraction of an 8-bit step does not matter.
 *
 * <p>Only the colour channels are encoded; alpha is always linear, which is why the array methods
 * take the number of components per pixel and leave the fourth one alone.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time. The arrays and buffers you pass in are not synchronised, so two threads must not write
 * the same one.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * float linear = Srgb.toLinear(0.5f);                          // about 0.214
 * float encoded = Srgb.fromLinear(linear);                     // 0.5
 * int byteValue = Srgb.linearToByte(linear);                   // 128
 * float[] pixels = {0.5f, 0.5f, 0.5f, 1f};
 * Srgb.toLinear(pixels, 0, 1, 4, false);                       // converts in place, alpha untouched
 * }</pre>
 */
@Experimental("the set of helpers may grow")
public final class Srgb {

    private static final float[] BYTE_TO_LINEAR = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            BYTE_TO_LINEAR[i] = toLinear(i / 255f);
        }
    }

    private Srgb() {
    }

    /**
     * Decodes an sRGB-encoded channel to linear light using the exact piecewise transfer function
     * (a linear toe followed by a power curve); negative values are mirrored through zero.
     *
     * @param c the encoded channel value, nominally in {@code [0, 1]}
     * @return encoded to linear, exact
     */
    public static float toLinear(float c) {
        if (c < 0f) {
            return -toLinear(-c);
        }
        return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /**
     * Encodes a linear-light channel with the exact piecewise sRGB transfer function (a linear toe
     * followed by a power curve); negative values are mirrored through zero.
     *
     * @param x the x component
     * @return linear to encoded, exact
     */
    public static float fromLinear(float x) {
        if (x < 0f) {
            return -fromLinear(-x);
        }
        return x <= 0.0031308f ? x * 12.92f : (float) (1.055 * Math.pow(x, 1.0 / 2.4) - 0.055);
    }

    /**
     * Decodes an sRGB channel with a cubic polynomial instead of the power function; faster than
     * the exact form, but only valid on the unit interval, to which the input is clamped.
     *
     * @param c the encoded channel value, nominally in {@code [0, 1]}
     * @return encoded to linear, a cubic approximation for {@code c} in {@code [0, 1]} (the input
     *     is clamped)
     */
    public static float toLinearFast(float c) {
        float s = c < 0f ? 0f : c > 1f ? 1f : c;
        return s * (s * (s * 0.305306011f + 0.682171111f) + 0.012522878f);
    }

    /**
     * Encodes a linear channel with an approximation built on square roots instead of the power
     * function; faster than the exact form, but the input is clamped to the unit interval.
     *
     * @param x the x component
     * @return linear to encoded, an approximation for {@code x} in {@code [0, 1]} (the input is
     *     clamped): exact below the linear threshold, three square roots above it
     */
    public static float fromLinearFast(float x) {
        float v = x < 0f ? 0f : x > 1f ? 1f : x;
        if (v <= 0.0031308f) {
            return v * 12.92f; // the fit of the square roots is poor in the linear segment, which is exact and cheap
        }
        float s1 = (float) Math.sqrt(v);
        float s2 = (float) Math.sqrt(s1);
        float s3 = (float) Math.sqrt(s2);
        return 0.662002687f * s1 + 0.684122060f * s2 - 0.323583601f * s3 - 0.0225411470f * v;
    }

    /**
     * Decodes an 8-bit sRGB channel with a 256-entry lookup table, which makes the conversion exact
     * and branch-free; only the low eight bits of the argument are used.
     *
     * @param b the encoded 8-bit channel value, 0 to 255
     * @return the linear value of an 8-bit encoded channel (a table look-up; exact)
     */
    public static float byteToLinear(int b) {
        return BYTE_TO_LINEAR[b & 0xFF];
    }

    /**
     * Quantizes a linear value to the 8-bit sRGB code that an sRGB8 render target would store,
     * clamping to the representable range; NaN maps to 0.
     *
     * @param x the x component
     * @return the 8-bit encoding of a linear value, rounded to nearest and clamped to 0 to 255
     *     (exact; this is what an sRGB8 render target stores)
     */
    public static int linearToByte(float x) {
        float e = fromLinear(x);
        return e <= 0f || e != e ? 0 : e >= 1f ? 255 : (int) (e * 255f + 0.5f);
    }

    /**
     * Quantizes a linear value to an 8-bit sRGB code through the fast encoder; trades exactness for
     * speed, with an error of at most one code.
     *
     * @param x the x component
     * @return as {@link #linearToByte} with the fast approximation; usually the same byte, and
     *     never more than one off
     */
    public static int linearToByteFast(float x) {
        return (int) (fromLinearFast(x) * 255f + 0.5f);
    }

    /**
     * Converts {@code pixels} pixels of {@code components} floats each, starting at {@code offset},
     * from encoded to linear in place.
     *
     * <p>Only the first {@code min(components, 3)} channels are converted; a fourth component
     * (alpha) is left alone.
     *
     * @param a the array holding the pixels, converted in place
     * @param offset the index of the first element to read or write
     * @param pixels the size in pixels
     * @param components the number of components
     * @param fast whether fast
     */
    public static void toLinear(float[] a, int offset, int pixels, int components, boolean fast) {
        convert(a, offset, pixels, components, fast, true);
    }

    /**
     * Reverses {@link #toLinear(float[], int, int, int, boolean)}: converts the pixels from linear
     * to encoded in place, leaving alpha untouched.
     *
     * @param a the array holding the pixels, converted in place
     * @param offset the index of the first element to read or write
     * @param pixels the size in pixels
     * @param components the number of components
     * @param fast whether fast
     */
    public static void fromLinear(float[] a, int offset, int pixels, int components, boolean fast) {
        convert(a, offset, pixels, components, fast, false);
    }

    private static void convert(float[] a, int offset, int pixels, int components, boolean fast, boolean decode) {
        if (components < 1 || components > 4 || pixels < 0 || offset < 0 || (long) offset + (long) pixels * components > a.length) {
            throw new IllegalArgumentException("pixels, components or offset do not fit the array");
        }
        int channels = Math.min(components, 3);
        for (int p = 0, i = offset; p < pixels; p++, i += components) {
            for (int c = 0; c < channels; c++) {
                float v = a[i + c];
                a[i + c] = decode ? (fast ? toLinearFast(v) : toLinear(v)) : (fast ? fromLinearFast(v) : fromLinear(v));
            }
        }
    }
}
