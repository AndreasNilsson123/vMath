package vmath.color;

import vmath.annotations.Experimental;
import vmath.core.Vec3f;

/**
 * Conversions between linear sRGB (the RGB of lighting, with Rec. 709 primaries) and the colour models artists and gradients use. Every conversion writes its three
 * results to {@code out[0..2]} (no allocation), and has a {@link Vec3f} overload for convenience.
 *
 * <ul>
 *   <li><b>HSV and HSL</b>: hue in degrees {@code [0, 360)}, saturation and value or lightness in {@code [0, 1]}. They are conveniences of the encoded sRGB cube, not
 *       perceptual: feed them encoded RGB if you want what a colour picker shows. Inputs above 1 (HDR) are accepted and give values above 1 in {@code v}.</li>
 *   <li><b>Oklab</b> (Björn Ottosson, 2020): a perceptually uniform space, taking and returning <em>linear</em> sRGB. {@code L} is lightness in {@code [0, 1]} for
 *       displayable colours, {@code a} (green to red) and {@code b} (blue to yellow) are about {@code [-0.4, 0.4]}. Equal steps in Oklab look like equal steps, so mix
 *       and make gradients here ({@link #mixOklab}), not in RGB.</li>
 *   <li><b>Oklch</b>: Oklab in polar form, chroma {@code C} and hue in degrees.</li>
 * </ul>
 *
 * <p>Oklab and Oklch values outside the sRGB gamut give linear RGB components outside {@code [0, 1]}; clamp them (or reduce the chroma) before display.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the same time. The arrays and buffers you pass in are
 * not synchronised, so two threads must not write the same one.
 */
@Experimental("the set of helpers may grow")
public final class ColorSpaces {

    private ColorSpaces() {
    }

    // ---------------------------------------------------------------- luminance

    /** The relative luminance (the Y of CIE XYZ) of a linear sRGB colour: {@code 0.2126 r + 0.7152 g + 0.0722 b}. */
    public static float luminance(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    /** The relative luminance of a linear sRGB colour, as {@link #luminance(float, float, float)}. */
    public static float luminance(Vec3f linearRgb) {
        return luminance(linearRgb.x(), linearRgb.y(), linearRgb.z());
    }

    // ---------------------------------------------------------------- HSV and HSL

    /** RGB to HSV: {@code out = (hue in degrees, saturation, value)}. A grey has hue 0 and saturation 0. */
    public static void rgbToHsv(float r, float g, float b, float[] out) {
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float chroma = max - min;
        out[0] = hue(r, g, b, max, chroma);
        out[1] = max > 0f ? chroma / max : 0f;
        out[2] = max;
    }

    /** HSV to RGB; the hue is taken modulo 360 degrees, saturation and value are used as given. */
    public static void hsvToRgb(float h, float s, float v, float[] out) {
        float c = v * s;
        hueToRgb(h, c, v - c, out);
    }

    /** RGB to HSL: {@code out = (hue in degrees, saturation, lightness)}. */
    public static void rgbToHsl(float r, float g, float b, float[] out) {
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float chroma = max - min;
        float l = 0.5f * (max + min);
        out[0] = hue(r, g, b, max, chroma);
        out[1] = chroma == 0f ? 0f : chroma / (1f - Math.abs(2f * l - 1f));
        out[2] = l;
    }

    /** HSL to RGB; the hue is taken modulo 360 degrees. */
    public static void hslToRgb(float h, float s, float l, float[] out) {
        float c = (1f - Math.abs(2f * l - 1f)) * s;
        hueToRgb(h, c, l - 0.5f * c, out);
    }

    private static float hue(float r, float g, float b, float max, float chroma) {
        if (chroma == 0f) {
            return 0f;
        }
        float h;
        if (max == r) {
            h = (g - b) / chroma;
            if (h < 0f) {
                h += 6f;
            }
        } else if (max == g) {
            h = (b - r) / chroma + 2f;
        } else {
            h = (r - g) / chroma + 4f;
        }
        h *= 60f;
        return h >= 360f ? h - 360f : h;
    }

    private static void hueToRgb(float hue, float chroma, float m, float[] out) {
        float h = hue % 360f;
        if (h < 0f) {
            h += 360f;
        }
        h /= 60f;
        float x = chroma * (1f - Math.abs(h % 2f - 1f));
        float r, g, b;
        switch ((int) h) {
            case 0 -> {
                r = chroma;
                g = x;
                b = 0f;
            }
            case 1 -> {
                r = x;
                g = chroma;
                b = 0f;
            }
            case 2 -> {
                r = 0f;
                g = chroma;
                b = x;
            }
            case 3 -> {
                r = 0f;
                g = x;
                b = chroma;
            }
            case 4 -> {
                r = x;
                g = 0f;
                b = chroma;
            }
            default -> {
                r = chroma;
                g = 0f;
                b = x;
            }
        }
        out[0] = r + m;
        out[1] = g + m;
        out[2] = b + m;
    }

    /** RGB to HSV as a vector {@code (hue in degrees, saturation, value)}; allocates the result, the array form does not. */
    public static Vec3f rgbToHsv(Vec3f rgb) {
        float[] o = new float[3];
        rgbToHsv(rgb.x(), rgb.y(), rgb.z(), o);
        return new Vec3f(o[0], o[1], o[2]);
    }

    /** HSV (hue in degrees, saturation, value) to RGB as a vector; allocates the result, the array form does not. */
    public static Vec3f hsvToRgb(Vec3f hsv) {
        float[] o = new float[3];
        hsvToRgb(hsv.x(), hsv.y(), hsv.z(), o);
        return new Vec3f(o[0], o[1], o[2]);
    }

    /** RGB to HSL as a vector {@code (hue in degrees, saturation, lightness)}; allocates the result, the array form does not. */
    public static Vec3f rgbToHsl(Vec3f rgb) {
        float[] o = new float[3];
        rgbToHsl(rgb.x(), rgb.y(), rgb.z(), o);
        return new Vec3f(o[0], o[1], o[2]);
    }

    /** HSL (hue in degrees, saturation, lightness) to RGB as a vector; allocates the result, the array form does not. */
    public static Vec3f hslToRgb(Vec3f hsl) {
        float[] o = new float[3];
        hslToRgb(hsl.x(), hsl.y(), hsl.z(), o);
        return new Vec3f(o[0], o[1], o[2]);
    }

    // ---------------------------------------------------------------- Oklab

    /** Linear sRGB to Oklab: {@code out = (L, a, b)}. */
    public static void linearSrgbToOklab(float r, float g, float b, float[] out) {
        float l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b;
        float m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b;
        float s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b;
        float l_ = (float) Math.cbrt(l), m_ = (float) Math.cbrt(m), s_ = (float) Math.cbrt(s);
        out[0] = 0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_;
        out[1] = 1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_;
        out[2] = 0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_;
    }

    /** Oklab to linear sRGB; components outside {@code [0, 1]} mean the colour is outside the sRGB gamut. */
    public static void oklabToLinearSrgb(float lightness, float a, float b, float[] out) {
        float l_ = lightness + 0.3963377774f * a + 0.2158037573f * b;
        float m_ = lightness - 0.1055613458f * a - 0.0638541728f * b;
        float s_ = lightness - 0.0894841775f * a - 1.2914855480f * b;
        float l = l_ * l_ * l_, m = m_ * m_ * m_, s = s_ * s_ * s_;
        out[0] = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s;
        out[1] = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s;
        out[2] = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s;
    }

    /** Oklab to Oklch: {@code out = (L, chroma, hue in degrees [0, 360))}. */
    public static void oklabToOklch(float lightness, float a, float b, float[] out) {
        out[0] = lightness;
        out[1] = (float) Math.sqrt((double) a * a + (double) b * b);
        float h = (float) Math.toDegrees(Math.atan2(b, a));
        out[2] = h < 0f ? h + 360f : h;
    }

    /** Oklch to Oklab: {@code out = (L, a, b)}. */
    public static void oklchToOklab(float lightness, float chroma, float hueDegrees, float[] out) {
        double h = Math.toRadians(hueDegrees);
        out[0] = lightness;
        out[1] = (float) (chroma * Math.cos(h));
        out[2] = (float) (chroma * Math.sin(h));
    }

    /**
     * Mixes two linear sRGB colours in Oklab: {@code t = 0} is the first, {@code t = 1} the second, and the steps in between look even. The result is in linear sRGB.
     */
    public static void mixOklab(float r0, float g0, float b0, float r1, float g1, float b1, float t, float[] out) {
        linearSrgbToOklab(r0, g0, b0, out);
        float l0 = out[0], a0 = out[1], c0 = out[2];
        linearSrgbToOklab(r1, g1, b1, out);
        oklabToLinearSrgb(l0 + (out[0] - l0) * t, a0 + (out[1] - a0) * t, c0 + (out[2] - c0) * t, out);
    }

    /** Linear sRGB to Oklab as a vector {@code (L, a, b)}; allocates the result, the array form does not. */
    public static Vec3f linearSrgbToOklab(Vec3f rgb) {
        float[] o = new float[3];
        linearSrgbToOklab(rgb.x(), rgb.y(), rgb.z(), o);
        return new Vec3f(o[0], o[1], o[2]);
    }

    /** Oklab {@code (L, a, b)} to linear sRGB as a vector; allocates the result, the array form does not. */
    public static Vec3f oklabToLinearSrgb(Vec3f lab) {
        float[] o = new float[3];
        oklabToLinearSrgb(lab.x(), lab.y(), lab.z(), o);
        return new Vec3f(o[0], o[1], o[2]);
    }
}
