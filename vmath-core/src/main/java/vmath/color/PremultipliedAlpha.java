package vmath.color;

import vmath.annotations.Experimental;

/**
 * Premultiplied alpha: colour channels stored already multiplied by alpha, so that blending is {@code src + dst * (1 - src.a)} with no per-channel multiply by the
 * source alpha, filtering does not bleed the colour of transparent texels, and additive and alpha blending are the same operation. Convert at the edges of the
 * pipeline (texture import, export) and keep the data premultiplied in between. Do the arithmetic on <em>linear</em> values: premultiply after decoding sRGB
 * ({@link Srgb}), unpremultiply before encoding.
 *
 * <p>Pixels are {@code r, g, b, a} consecutive floats; the 8-bit packed form is a 32-bit int with red in the lowest byte, as {@code Norm.packUnorm4x8} writes it.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the same time. The arrays and buffers you pass in are
 * not synchronised, so two threads must not write the same one.
 */
@Experimental("the set of helpers may grow")
public final class PremultipliedAlpha {

    private PremultipliedAlpha() {
    }

    /** Multiplies the colour channels of {@code pixels} RGBA pixels by their alpha, in place, starting at float index {@code offset}. */
    public static void premultiply(float[] rgba, int offset, int pixels) {
        check(rgba, offset, pixels);
        for (int p = 0, i = offset; p < pixels; p++, i += 4) {
            float a = rgba[i + 3];
            rgba[i] *= a;
            rgba[i + 1] *= a;
            rgba[i + 2] *= a;
        }
    }

    /** Divides the colour channels by alpha, in place; a pixel with alpha 0 becomes (0, 0, 0, 0), since its colour is not recoverable. */
    public static void unpremultiply(float[] rgba, int offset, int pixels) {
        check(rgba, offset, pixels);
        for (int p = 0, i = offset; p < pixels; p++, i += 4) {
            float a = rgba[i + 3];
            if (a > 0f) {
                float inv = 1f / a;
                rgba[i] *= inv;
                rgba[i + 1] *= inv;
                rgba[i + 2] *= inv;
            } else {
                rgba[i] = 0f;
                rgba[i + 1] = 0f;
                rgba[i + 2] = 0f;
                rgba[i + 3] = 0f;
            }
        }
    }

    private static void check(float[] a, int offset, int pixels) {
        if (pixels < 0 || offset < 0 || (long) offset + 4L * pixels > a.length) {
            throw new IllegalArgumentException("pixels or offset do not fit the array");
        }
    }

    /**
     * The "over" operator on premultiplied colours: the source drawn on top of the destination, {@code out = src + dst * (1 - src.a)} for all four channels. The
     * arrays may be the same; {@code src} and {@code dst} are read at {@code so} and {@code dsto}, the result is written at {@code oo}.
     */
    public static void over(float[] src, int so, float[] dst, int dsto, float[] out, int oo) {
        float inv = 1f - src[so + 3];
        float r = src[so] + dst[dsto] * inv, g = src[so + 1] + dst[dsto + 1] * inv, b = src[so + 2] + dst[dsto + 2] * inv, a = src[so + 3] + dst[dsto + 3] * inv;
        out[oo] = r;
        out[oo + 1] = g;
        out[oo + 2] = b;
        out[oo + 3] = a;
    }

    /** Premultiplies a packed 8-bit RGBA pixel, rounding each channel to nearest ({@code (c * a + 127) / 255}, which has no ties because 255 is odd). */
    public static int premultiplyRgba8(int packed) {
        int a = packed >>> 24;
        int r = ((packed & 0xFF) * a + 127) / 255;
        int g = (((packed >>> 8) & 0xFF) * a + 127) / 255;
        int b = (((packed >>> 16) & 0xFF) * a + 127) / 255;
        return r | (g << 8) | (b << 16) | (a << 24);
    }

    /**
     * Unpremultiplies a packed 8-bit RGBA pixel, rounding to nearest and clamping to 255; alpha 0 gives 0. The 8-bit round trip is lossy for small alpha: a colour channel
     * only keeps about {@code log2(alpha)} of its eight bits.
     */
    public static int unpremultiplyRgba8(int packed) {
        int a = packed >>> 24;
        if (a == 0) {
            return 0;
        }
        int r = Math.min(255, (((packed & 0xFF) * 255) + a / 2) / a);
        int g = Math.min(255, ((((packed >>> 8) & 0xFF) * 255) + a / 2) / a);
        int b = Math.min(255, ((((packed >>> 16) & 0xFF) * 255) + a / 2) / a);
        return r | (g << 8) | (b << 16) | (a << 24);
    }
}
