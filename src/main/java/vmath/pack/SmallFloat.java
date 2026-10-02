package vmath.pack;

import vmath.core.Vec3f;

/**
 * The two packed HDR color formats: {@code R11G11B10F} (three unsigned small floats in 32 bits) and {@code RGB9E5} (three 9-bit
 * mantissas sharing one 5-bit exponent). Both store non-negative values only, in far less space than three half floats, which is why
 * they are the usual choice for HDR render targets and light probes.
 *
 * <ul>
 *   <li><b>R11G11B10F</b> ({@code GL_R11F_G11F_B10F}, Vulkan {@code B10G11R11_UFLOAT_PACK32}): red is 5 exponent bits and 6
 *       mantissa bits, green the same, blue 5 and 5. Red occupies bits 0 to 10, green 11 to 21, blue 22 to 31. Negative values
 *       clamp to 0, values above the largest finite number (65024 for red and green, 64512 for blue) clamp to it, infinity stays
 *       infinity and NaN stays NaN.</li>
 *   <li><b>RGB9E5</b> ({@code GL_RGB9_E5}, Vulkan {@code E5B9G9R9_UFLOAT_PACK32}): red in bits 0 to 8, green 9 to 17, blue 18 to 26 and
 *       the shared exponent in 27 to 31. All three channels share the exponent of the largest, so small channels next to a large one
 *       lose precision. The largest value is 65408. Negative, NaN and out-of-range inputs clamp to {@code [0, 65408]}.</li>
 * </ul>
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the same time. The arrays and buffers you pass in are
 * not synchronised, so two threads must not write the same one.
 */
public final class SmallFloat {

    private SmallFloat() {
    }

    // ---------------------------------------------------------------- unsigned 5-exponent floats (10 and 11 bit)

    private static final int EXP_BITS = 5;
    private static final int EXP_BIAS = 15;
    private static final int MIN_EXP = 1 - EXP_BIAS; // exponent of the smallest normal number, -14

    /** Encodes {@code v} as an unsigned float with a 5-bit exponent and {@code mantBits} mantissa bits, rounding to nearest even. */
    static int encodeUnsigned(float v, int mantBits) {
        int infinity = ((1 << EXP_BITS) - 1) << mantBits;
        if (v != v) {
            return infinity | ((1 << mantBits) - 1); // NaN: exponent all ones, nonzero mantissa
        }
        if (!(v > 0f)) {
            return 0; // zero and negatives
        }
        if (v == Float.POSITIVE_INFINITY) {
            return infinity;
        }
        int maxFinite = infinity - 1;
        int e = Math.getExponent(v);
        if (e < MIN_EXP) {
            // subnormal in the target: steps of 2^(MIN_EXP - mantBits). A result of 2^mantBits is the smallest normal, correctly.
            return (int) Math.rint((double) v * Math.scalb(1.0, mantBits - MIN_EXP));
        }
        double frac = (double) v / Math.scalb(1.0, e) - 1.0;
        int mant = (int) Math.rint(frac * (1 << mantBits));
        if (mant == (1 << mantBits)) {
            mant = 0;
            e++;
        }
        if (e > EXP_BIAS) {
            return maxFinite;
        }
        return ((e + EXP_BIAS) << mantBits) | mant;
    }

    static float decodeUnsigned(int bits, int mantBits) {
        int exp = bits >>> mantBits;
        int mant = bits & ((1 << mantBits) - 1);
        if (exp == 0) {
            return (float) (mant * Math.scalb(1.0, MIN_EXP - mantBits));
        }
        if (exp == (1 << EXP_BITS) - 1) {
            return mant == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
        }
        return (float) ((1.0 + mant / (double) (1 << mantBits)) * Math.scalb(1.0, exp - EXP_BIAS));
    }

    /** Largest finite value of the 11-bit float used for red and green: 65024. */
    public static final float MAX_R11 = 65024f;
    /** Largest finite value of the 10-bit float used for blue: 64512. */
    public static final float MAX_B10 = 64512f;

    // ---------------------------------------------------------------- R11G11B10F

    public static int packR11G11B10F(float r, float g, float b) {
        return encodeUnsigned(r, 6) | (encodeUnsigned(g, 6) << 11) | (encodeUnsigned(b, 5) << 22);
    }

    public static int packR11G11B10F(Vec3f rgb) {
        return packR11G11B10F(rgb.x(), rgb.y(), rgb.z());
    }

    public static Vec3f unpackR11G11B10F(int packed) {
        return new Vec3f(decodeUnsigned(packed & 0x7FF, 6), decodeUnsigned((packed >>> 11) & 0x7FF, 6),
                decodeUnsigned((packed >>> 22) & 0x3FF, 5));
    }

    // ---------------------------------------------------------------- RGB9E5

    private static final int E5_MANTISSA_BITS = 9;
    private static final int E5_BIAS = 15;
    private static final int E5_MAX_EXPONENT = 31;
    /** Largest value RGB9E5 can hold: {@code (511 / 512) * 2^16}. */
    public static final float MAX_RGB9E5 = 65408f;

    private static double clampE5(float v) {
        if (!(v > 0f)) {
            return 0.0; // zero, negatives and NaN
        }
        return Math.min(v, MAX_RGB9E5);
    }

    /** Packs to RGB9E5 following the {@code EXT_texture_shared_exponent} algorithm: the exponent is chosen from the largest channel. */
    public static int packRgb9E5(float r, float g, float b) {
        double rc = clampE5(r), gc = clampE5(g), bc = clampE5(b);
        double max = Math.max(rc, Math.max(gc, bc));
        int shared = (max == 0.0)
                ? 0
                : Math.max(-E5_BIAS - 1, Math.getExponent(max)) + 1 + E5_BIAS;
        double denom = Math.scalb(1.0, shared - E5_BIAS - E5_MANTISSA_BITS);
        int maxm = (int) Math.floor(max / denom + 0.5);
        if (maxm == (1 << E5_MANTISSA_BITS)) {
            shared++;
            denom *= 2.0;
        }
        int rm = (int) Math.floor(rc / denom + 0.5);
        int gm = (int) Math.floor(gc / denom + 0.5);
        int bm = (int) Math.floor(bc / denom + 0.5);
        return rm | (gm << 9) | (bm << 18) | (Math.min(shared, E5_MAX_EXPONENT) << 27);
    }

    public static int packRgb9E5(Vec3f rgb) {
        return packRgb9E5(rgb.x(), rgb.y(), rgb.z());
    }

    public static Vec3f unpackRgb9E5(int packed) {
        int shared = packed >>> 27;
        double scale = Math.scalb(1.0, shared - E5_BIAS - E5_MANTISSA_BITS);
        return new Vec3f((float) ((packed & 0x1FF) * scale), (float) (((packed >>> 9) & 0x1FF) * scale),
                (float) (((packed >>> 18) & 0x1FF) * scale));
    }
}
