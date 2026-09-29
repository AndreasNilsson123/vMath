package vmath.pack;

import vmath.core.Vec2f;
import vmath.core.Vec4f;

/**
 * Normalized integer formats: a float in a fixed range stored as an integer of 8, 10 or 16 bits.
 *
 * <ul>
 *   <li><b>unorm</b> maps {@code [0, 1]} to {@code [0, 2^n - 1]}: {@code round(f * (2^n - 1))}.</li>
 *   <li><b>snorm</b> maps {@code [-1, 1]} to {@code [-(2^(n-1) - 1), 2^(n-1) - 1]}: {@code round(f * (2^(n-1) - 1))}. The most negative
 *       integer ({@code -128}, {@code -32768}, {@code -512}) is not produced by packing and decodes to exactly -1 (the graphics-API
 *       rule), so 0 is exactly representable and the range is symmetric.</li>
 * </ul>
 * Inputs outside the range are clamped, NaN becomes 0, and rounding is to nearest with ties rounding up. The worst-case round-trip
 * error is half a step: {@code 0.5 / 255} for unorm8, {@code 0.5 / 127} for snorm8, {@code 0.5 / 65535} for unorm16.
 *
 * <p>The vector packers use the GLSL layouts ({@code packUnorm4x8} and friends): the first component is in the lowest bits. The
 * 2-10-10-10 packers match {@code GL_UNSIGNED_INT_2_10_10_10_REV} / Vulkan {@code A2B10G10R10}: red in bits 0 to 9, then green, blue,
 * and alpha in bits 30 and 31.
 */
public final class Norm {

    private Norm() {
    }

    private static int round(float scaled) {
        return (int) Math.floor(scaled + 0.5f);
    }

    private static float clamp01(float f) {
        return f > 0f ? (f < 1f ? f : 1f) : 0f; // NaN fails both comparisons and becomes 0
    }

    private static float clampSigned(float f) {
        return f > -1f ? (f < 1f ? f : 1f) : (f == f ? -1f : 0f);
    }

    // ---------------------------------------------------------------- 8-bit

    /** {@code [0, 1]} to {@code 0..255}. */
    public static int packUnorm8(float f) {
        return round(clamp01(f) * 255f);
    }

    /** The low 8 bits as unorm: {@code 0..255} to {@code [0, 1]}. */
    public static float unpackUnorm8(int bits) {
        return (bits & 0xFF) / 255f;
    }

    /** {@code [-1, 1]} to {@code -127..127}. */
    public static int packSnorm8(float f) {
        return round(clampSigned(f) * 127f);
    }

    /** The low 8 bits as a signed byte: {@code -128..127} to {@code [-1, 1]} ({@code -128} is -1). */
    public static float unpackSnorm8(int bits) {
        return Math.max((byte) bits / 127f, -1f);
    }

    // ---------------------------------------------------------------- 16-bit

    /** {@code [0, 1]} to {@code 0..65535}. */
    public static int packUnorm16(float f) {
        return round(clamp01(f) * 65535f);
    }

    public static float unpackUnorm16(int bits) {
        return (bits & 0xFFFF) / 65535f;
    }

    /** {@code [-1, 1]} to {@code -32767..32767}. */
    public static int packSnorm16(float f) {
        return round(clampSigned(f) * 32767f);
    }

    /** The low 16 bits as a signed short ({@code -32768} is -1). */
    public static float unpackSnorm16(int bits) {
        return Math.max((short) bits / 32767f, -1f);
    }

    // ---------------------------------------------------------------- 10-bit (for 2-10-10-10 formats)

    /** {@code [0, 1]} to {@code 0..1023}. */
    public static int packUnorm10(float f) {
        return round(clamp01(f) * 1023f);
    }

    public static float unpackUnorm10(int bits) {
        return (bits & 0x3FF) / 1023f;
    }

    /** {@code [-1, 1]} to {@code -511..511}, as a 10-bit two's-complement field (not sign-extended). */
    public static int packSnorm10(float f) {
        return round(clampSigned(f) * 511f) & 0x3FF;
    }

    /** A 10-bit two's-complement field to {@code [-1, 1]} ({@code -512} is -1). */
    public static float unpackSnorm10(int bits) {
        int v = (bits << 22) >> 22; // sign-extend 10 bits
        return Math.max(v / 511f, -1f);
    }

    // ---------------------------------------------------------------- packed vectors

    /** Four unorm8 in one int, {@code x} in the lowest byte (GLSL {@code packUnorm4x8}). */
    public static int packUnorm4x8(Vec4f v) {
        return packUnorm8(v.x()) | (packUnorm8(v.y()) << 8) | (packUnorm8(v.z()) << 16) | (packUnorm8(v.w()) << 24);
    }

    public static Vec4f unpackUnorm4x8(int p) {
        return new Vec4f(unpackUnorm8(p), unpackUnorm8(p >>> 8), unpackUnorm8(p >>> 16), unpackUnorm8(p >>> 24));
    }

    /** Four snorm8 in one int, {@code x} in the lowest byte (GLSL {@code packSnorm4x8}). */
    public static int packSnorm4x8(Vec4f v) {
        return (packSnorm8(v.x()) & 0xFF) | ((packSnorm8(v.y()) & 0xFF) << 8) | ((packSnorm8(v.z()) & 0xFF) << 16)
                | ((packSnorm8(v.w()) & 0xFF) << 24);
    }

    public static Vec4f unpackSnorm4x8(int p) {
        return new Vec4f(unpackSnorm8(p), unpackSnorm8(p >>> 8), unpackSnorm8(p >>> 16), unpackSnorm8(p >>> 24));
    }

    /** Two unorm16 in one int, {@code x} in the low half (GLSL {@code packUnorm2x16}). */
    public static int packUnorm2x16(Vec2f v) {
        return packUnorm16(v.x()) | (packUnorm16(v.y()) << 16);
    }

    public static Vec2f unpackUnorm2x16(int p) {
        return new Vec2f(unpackUnorm16(p), unpackUnorm16(p >>> 16));
    }

    /** Two snorm16 in one int, {@code x} in the low half (GLSL {@code packSnorm2x16}). */
    public static int packSnorm2x16(Vec2f v) {
        return (packSnorm16(v.x()) & 0xFFFF) | ((packSnorm16(v.y()) & 0xFFFF) << 16);
    }

    public static Vec2f unpackSnorm2x16(int p) {
        return new Vec2f(unpackSnorm16(p), unpackSnorm16(p >>> 16));
    }

    /**
     * Three unorm10 and a unorm2 in one int: red in bits 0 to 9, green 10 to 19, blue 20 to 29, alpha in bits 30 and 31
     * ({@code GL_RGB10_A2} / Vulkan {@code A2B10G10R10_UNORM_PACK32}). Alpha steps are 0, 1/3, 2/3, 1.
     */
    public static int packRgb10A2(Vec4f rgba) {
        return packUnorm10(rgba.x()) | (packUnorm10(rgba.y()) << 10) | (packUnorm10(rgba.z()) << 20)
                | (round(clamp01(rgba.w()) * 3f) << 30);
    }

    public static Vec4f unpackRgb10A2(int p) {
        return new Vec4f(unpackUnorm10(p), unpackUnorm10(p >>> 10), unpackUnorm10(p >>> 20), (p >>> 30) / 3f);
    }

    /**
     * Three snorm10 and a snorm2 in one int (Vulkan {@code A2B10G10R10_SNORM_PACK32}, {@code GL_INT_2_10_10_10_REV}). The 2-bit alpha holds
     * -1, 0 or +1 (the two's-complement values -1, 0, 1; the fourth value, -2, decodes to -1 as well).
     */
    public static int packRgb10A2Snorm(Vec4f rgba) {
        int a = round(clampSigned(rgba.w())) & 0x3;
        return packSnorm10(rgba.x()) | (packSnorm10(rgba.y()) << 10) | (packSnorm10(rgba.z()) << 20) | (a << 30);
    }

    public static Vec4f unpackRgb10A2Snorm(int p) {
        int a = p >> 30; // arithmetic shift sign-extends the 2-bit field: -2, -1, 0, 1
        return new Vec4f(unpackSnorm10(p), unpackSnorm10(p >>> 10), unpackSnorm10(p >>> 20), Math.max(a, -1));
    }
}
