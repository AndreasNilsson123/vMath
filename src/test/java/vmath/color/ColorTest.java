package vmath.color;

import vmath.Report;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.color.ToneMap.Curve;
import vmath.core.Vec3f;

class ColorTest {

    private static final long SEED = Long.getLong("vmath.seed", 83L);

    // ---------------------------------------------------------------- sRGB

    private static double exactToLinear(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double exactFromLinear(double x) {
        return x <= 0.0031308 ? x * 12.92 : 1.055 * Math.pow(x, 1 / 2.4) - 0.055;
    }

    @Test
    void srgbTransferMatchesKnownValuesAndIsItsOwnInverse() {
        assertEquals(0f, Srgb.toLinear(0f));
        assertEquals(1f, Srgb.toLinear(1f), 1e-6f);
        assertEquals(0.21404114f, Srgb.toLinear(0.5f), 1e-6f);      // the usual figure for mid grey
        assertEquals(0.73535698f, Srgb.fromLinear(0.5f), 1e-6f);
        assertEquals(0.18f, Srgb.toLinear(0.4614f), 1e-4f);          // 18% grey encodes to about 0.461
        // the two pieces meet at the threshold
        assertEquals(0.04045f / 12.92f, Srgb.toLinear(0.04045f), 1e-9f);
        assertEquals(Srgb.toLinear(0.04045f), Srgb.toLinear(Math.nextUp(0.04045f)), 1e-7f);
        assertEquals(Srgb.fromLinear(0.0031308f), Srgb.fromLinear(Math.nextUp(0.0031308f)), 1e-6f);
        SplittableRandom r = new SplittableRandom(SEED);
        float previous = -1f;
        for (int k = 0; k < 100_000; k++) {
            float c = (float) r.nextDouble();
            assertEquals(exactToLinear(c), Srgb.toLinear(c), 1e-6, "decode " + c);
            assertEquals(exactFromLinear(c), Srgb.fromLinear(c), 1e-6, "encode " + c);
            assertEquals(c, Srgb.fromLinear(Srgb.toLinear(c)), 2e-6f, "round trip " + c);
        }
        for (int i = 0; i <= 1000; i++) {
            float lin = Srgb.toLinear(i / 1000f);
            assertTrue(lin >= previous, "monotonic");
            previous = lin;
        }
        // HDR continues along the curve, negatives mirror, NaN stays NaN
        assertEquals(exactToLinear(2.0), Srgb.toLinear(2f), 1e-5);
        assertEquals(-Srgb.toLinear(0.3f), Srgb.toLinear(-0.3f));
        assertEquals(-Srgb.fromLinear(0.3f), Srgb.fromLinear(-0.3f));
        assertTrue(Float.isNaN(Srgb.toLinear(Float.NaN)) && Float.isNaN(Srgb.fromLinear(Float.NaN)));
    }

    @Test
    void eightBitConversionsAreExactAndInverse() {
        for (int b = 0; b < 256; b++) {
            assertEquals(exactToLinear(b / 255.0), Srgb.byteToLinear(b), 1e-6, "table entry " + b);
            assertEquals(b, Srgb.linearToByte(Srgb.byteToLinear(b)), "byte " + b + " survives decode and encode");
            assertEquals(b, Srgb.linearToByteFast(Srgb.byteToLinear(b)), 1, "the fast encoder is within one of the exact one for byte " + b);
        }
        assertEquals(0, Srgb.linearToByte(-1f));
        assertEquals(0, Srgb.linearToByte(Float.NaN));
        assertEquals(255, Srgb.linearToByte(5f));
        assertEquals(255, Srgb.byteToLinear(255) == 1f ? 255 : -1);
        assertEquals(Srgb.byteToLinear(0xAB), Srgb.byteToLinear(0x1AB), "only the low byte counts");
    }

    @Test
    void fastApproximationsStayWithinTheirMeasuredBounds() {
        double worstDecode = 0, worstEncode = 0;
        int differentBytes = 0, off = 0, total = 200_000;
        for (int i = 0; i <= total; i++) {
            float x = (float) i / total;
            worstDecode = Math.max(worstDecode, Math.abs(Srgb.toLinearFast(x) - exactToLinear(x)));
            worstEncode = Math.max(worstEncode, Math.abs(Srgb.fromLinearFast(x) - exactFromLinear(x)));
            int exact = Srgb.linearToByte(x), fast = Srgb.linearToByteFast(x);
            if (exact != fast) {
                differentBytes++;
                off = Math.max(off, Math.abs(exact - fast));
            }
        }
        Report.printf("sRGB fast: decode worst error %.5f, encode worst error %.5f (%.2f of an 8-bit step), %.2f%% of bytes differ, at most %d off%n",
                worstDecode, worstEncode, worstEncode * 255, 100.0 * differentBytes / (total + 1), off);
        assertTrue(worstDecode < 0.002, "decode error " + worstDecode);
        assertTrue(worstEncode < 0.003, "encode error " + worstEncode);
        assertTrue(off <= 1);
        assertEquals(0f, Srgb.toLinearFast(-3f));
        assertEquals(1f, Srgb.toLinearFast(7f), 1e-6f);
        assertEquals(0f, Srgb.fromLinearFast(-3f));
        assertEquals(1f, Srgb.fromLinearFast(9f), 1e-3f);
    }

    @Test
    void arrayConversionsLeaveAlphaAlone() {
        float[] rgba = {0.5f, 0.25f, 1f, 0.5f, 0f, 0.75f, 0.1f, 0.9f};
        float[] copy = rgba.clone();
        Srgb.toLinear(rgba, 0, 2, 4, false);
        assertEquals(Srgb.toLinear(0.5f), rgba[0]);
        assertEquals(Srgb.toLinear(0.25f), rgba[1]);
        assertEquals(0.5f, rgba[3]);
        assertEquals(0.9f, rgba[7]);
        Srgb.fromLinear(rgba, 0, 2, 4, false);
        for (int i = 0; i < rgba.length; i++) {
            assertEquals(copy[i], rgba[i], 2e-6f);
        }
        float[] rgb = {0.5f, 0.5f, 0.5f, 1f, 1f, 1f};
        Srgb.toLinear(rgb, 3, 1, 3, true);
        assertEquals(0.5f, rgb[0]);
        assertEquals(1f, rgb[3], 1e-6f);
        assertThrows(IllegalArgumentException.class, () -> Srgb.toLinear(new float[7], 0, 2, 4, false));
        assertThrows(IllegalArgumentException.class, () -> Srgb.toLinear(new float[8], 0, 2, 5, false));
    }

    // ---------------------------------------------------------------- HSV, HSL

    private static float[] hsvToRgbByFormula(float h, float s, float v) {
        float[] rgb = new float[3];
        int[] n = {5, 3, 1};
        for (int i = 0; i < 3; i++) {
            float k = (n[i] + h / 60f) % 6f;
            rgb[i] = v - v * s * Math.max(0f, Math.min(Math.min(k, 4f - k), 1f));
        }
        return rgb;
    }

    private static float[] hslToRgbByFormula(float h, float s, float l) {
        float[] rgb = new float[3];
        int[] n = {0, 8, 4};
        float a = s * Math.min(l, 1f - l);
        for (int i = 0; i < 3; i++) {
            float k = (n[i] + h / 30f) % 12f;
            rgb[i] = l - a * Math.max(-1f, Math.min(Math.min(k - 3f, 9f - k), 1f));
        }
        return rgb;
    }

    @Test
    void hsvAndHslMatchKnownColorsAndAnIndependentFormula() {
        float[] o = new float[3];
        ColorSpaces.rgbToHsv(1f, 0f, 0f, o);
        assertArrayEquals(new float[] {0f, 1f, 1f}, o);
        ColorSpaces.rgbToHsv(0f, 1f, 0f, o);
        assertArrayEquals(new float[] {120f, 1f, 1f}, o);
        ColorSpaces.rgbToHsv(0f, 0f, 1f, o);
        assertArrayEquals(new float[] {240f, 1f, 1f}, o);
        ColorSpaces.rgbToHsv(1f, 1f, 0f, o);
        assertArrayEquals(new float[] {60f, 1f, 1f}, o);
        ColorSpaces.rgbToHsv(0.5f, 0.5f, 0.5f, o);
        assertArrayEquals(new float[] {0f, 0f, 0.5f}, o);
        ColorSpaces.rgbToHsv(1f, 0f, 0.5f, o);
        assertEquals(330f, o[0], 1e-4f, "a magenta-red");
        ColorSpaces.rgbToHsl(1f, 0f, 0f, o);
        assertArrayEquals(new float[] {0f, 1f, 0.5f}, o);
        ColorSpaces.rgbToHsl(0.25f, 0.5f, 0.75f, o);
        assertEquals(210f, o[0], 1e-4f);
        assertEquals(0.5f, o[1], 1e-6f);
        assertEquals(0.5f, o[2], 1e-6f);
        ColorSpaces.rgbToHsl(0f, 0f, 0f, o);
        assertArrayEquals(new float[] {0f, 0f, 0f}, o);
        ColorSpaces.rgbToHsl(1f, 1f, 1f, o);
        assertArrayEquals(new float[] {0f, 0f, 1f}, o);
        SplittableRandom r = new SplittableRandom(SEED + 1);
        for (int k = 0; k < 50_000; k++) {
            float h = (float) (r.nextDouble() * 360), s = (float) r.nextDouble(), v = (float) r.nextDouble();
            ColorSpaces.hsvToRgb(h, s, v, o);
            float[] e = hsvToRgbByFormula(h, s, v);
            assertArrayEquals(e, o, 2e-6f);
            ColorSpaces.hslToRgb(h, s, v, o);
            e = hslToRgbByFormula(h, s, v);
            assertArrayEquals(e, o, 2e-6f);
            // round trips from RGB
            float cr = (float) r.nextDouble(), cg = (float) r.nextDouble(), cb = (float) r.nextDouble();
            float[] hsv = new float[3], back = new float[3];
            ColorSpaces.rgbToHsv(cr, cg, cb, hsv);
            ColorSpaces.hsvToRgb(hsv[0], hsv[1], hsv[2], back);
            assertArrayEquals(new float[] {cr, cg, cb}, back, 3e-6f);
            assertTrue(hsv[0] >= 0f && hsv[0] < 360f && hsv[1] >= 0f && hsv[1] <= 1f);
            ColorSpaces.rgbToHsl(cr, cg, cb, hsv);
            ColorSpaces.hslToRgb(hsv[0], hsv[1], hsv[2], back);
            assertArrayEquals(new float[] {cr, cg, cb}, back, 3e-6f);
        }
        // hue wraps
        float[] a = new float[3], b = new float[3];
        ColorSpaces.hsvToRgb(30f, 0.7f, 0.9f, a);
        ColorSpaces.hsvToRgb(30f + 720f, 0.7f, 0.9f, b);
        assertArrayEquals(a, b, 1e-5f);
        ColorSpaces.hsvToRgb(-330f, 0.7f, 0.9f, b);
        assertArrayEquals(a, b, 1e-5f);
        Vec3f v = ColorSpaces.hsvToRgb(new Vec3f(120f, 1f, 1f));
        assertEquals(1f, v.y());
        assertEquals(240f, ColorSpaces.rgbToHsl(new Vec3f(0f, 0f, 1f)).x());
    }

    // ---------------------------------------------------------------- Oklab and luminance

    @Test
    void oklabMatchesThePublishedValuesAndRoundTrips() {
        float[] o = new float[3];
        // Ottosson's table of the sRGB primaries (linear input)
        ColorSpaces.linearSrgbToOklab(1f, 0f, 0f, o);
        assertArrayEquals(new float[] {0.6280f, 0.2249f, 0.1258f}, o, 1e-3f);
        ColorSpaces.linearSrgbToOklab(0f, 1f, 0f, o);
        assertArrayEquals(new float[] {0.8664f, -0.2339f, 0.1795f}, o, 1e-3f);
        ColorSpaces.linearSrgbToOklab(0f, 0f, 1f, o);
        assertArrayEquals(new float[] {0.4520f, -0.0325f, -0.3115f}, o, 1e-3f);
        ColorSpaces.linearSrgbToOklab(1f, 1f, 1f, o);
        assertArrayEquals(new float[] {1f, 0f, 0f}, o, 2e-4f);
        ColorSpaces.linearSrgbToOklab(0f, 0f, 0f, o);
        assertArrayEquals(new float[] {0f, 0f, 0f}, o, 1e-7f);
        SplittableRandom r = new SplittableRandom(SEED + 2);
        float[] lab = new float[3], back = new float[3], lch = new float[3];
        for (int k = 0; k < 50_000; k++) {
            float cr = (float) r.nextDouble(), cg = (float) r.nextDouble(), cb = (float) r.nextDouble();
            ColorSpaces.linearSrgbToOklab(cr, cg, cb, lab);
            ColorSpaces.oklabToLinearSrgb(lab[0], lab[1], lab[2], back);
            assertArrayEquals(new float[] {cr, cg, cb}, back, 3e-5f);
            ColorSpaces.oklabToOklch(lab[0], lab[1], lab[2], lch);
            assertTrue(lch[2] >= 0f && lch[2] < 360.0001f && lch[1] >= 0f);
            float[] lab2 = new float[3];
            ColorSpaces.oklchToOklab(lch[0], lch[1], lch[2], lab2);
            assertArrayEquals(lab, lab2, 2e-6f);
        }
        // greys have no chroma, and their lightness is the cube root of the linear value
        for (float g : new float[] {0.01f, 0.125f, 0.5f, 0.9f}) {
            ColorSpaces.linearSrgbToOklab(g, g, g, o);
            assertEquals(Math.cbrt(g), o[0], 2e-4);
            assertEquals(0f, o[1], 2e-4f);
            assertEquals(0f, o[2], 2e-4f);
        }
        // mixing: endpoints, and black to white at the middle has Oklab lightness 0.5, which is linear 0.125
        float[] m = new float[3];
        ColorSpaces.mixOklab(1f, 0f, 0f, 0f, 0f, 1f, 0f, m);
        assertArrayEquals(new float[] {1f, 0f, 0f}, m, 3e-5f);
        ColorSpaces.mixOklab(1f, 0f, 0f, 0f, 0f, 1f, 1f, m);
        assertArrayEquals(new float[] {0f, 0f, 1f}, m, 3e-5f);
        ColorSpaces.mixOklab(0f, 0f, 0f, 1f, 1f, 1f, 0.5f, m);
        assertArrayEquals(new float[] {0.125f, 0.125f, 0.125f}, m, 1e-3f);
        assertEquals(0.2126f, ColorSpaces.luminance(1f, 0f, 0f));
        assertEquals(1f, ColorSpaces.luminance(1f, 1f, 1f), 1e-6f);
        assertEquals(0.7152f, ColorSpaces.luminance(new Vec3f(0f, 1f, 0f)));
        Vec3f red = ColorSpaces.linearSrgbToOklab(new Vec3f(1f, 0f, 0f));
        assertEquals(0.628f, red.x(), 1e-3f);
        assertEquals(1f, ColorSpaces.oklabToLinearSrgb(new Vec3f(red.x(), red.y(), red.z())).x(), 3e-5f);
    }

    // ---------------------------------------------------------------- tone mapping

    @Test
    void everyCurveStartsAtZeroIsMonotonicAndBounded() {
        for (Curve c : Curve.values()) {
            float previous = 0f;
            assertEquals(0f, ToneMap.apply(c, 0f, 4f), c.toString());
            assertEquals(0f, ToneMap.apply(c, -3f, 4f), c + " clamps negatives");
            for (int i = 1; i <= 20_000; i++) {
                float x = i * 0.005f;           // up to 100
                float y = ToneMap.apply(c, x, 4f);
                assertTrue(y >= previous - 1e-7f, c + " is monotonic at " + x + ": " + previous + " then " + y);
                assertTrue(y >= 0f && y <= 1f, c + " stays in [0, 1] at " + x + ": " + y);
                previous = y;
            }
            assertTrue(ToneMap.apply(c, 1e6f, 4f) > 0.95f, c + " approaches white");
        }
    }

    @Test
    void curvesHitTheirAnalyticPoints() {
        assertEquals(0.5f, ToneMap.reinhard(1f));
        assertEquals(0.9f, ToneMap.reinhard(9f), 1e-6f);
        assertEquals(1f, ToneMap.reinhardExtended(4f, 4f), 1e-6f, "the white point maps to 1");
        assertEquals(1f, ToneMap.reinhardExtended(9f, 4f), "and larger values clamp");
        assertEquals(ToneMap.reinhard(0.5f), ToneMap.reinhardExtended(0.5f, 1000f), 1e-5f, "a far white point is plain Reinhard");
        assertEquals(2.54f / 3.16f, ToneMap.aces(1f), 1e-6f);
        assertEquals(1f, ToneMap.aces(50f));
        assertEquals(1f, ToneMap.hable(11.2f), 1e-6f);
        assertEquals((float) (1 - Math.exp(-1)), ToneMap.exposure(1f), 1e-6f);
        assertEquals(1e-6f, ToneMap.exposure(1e-6f), 1e-11f, "no precision loss near zero");
        assertThrows(IllegalArgumentException.class, () -> ToneMap.reinhardExtended(1f, 0f));
    }

    @Test
    void arrayAndLuminanceVariants() {
        float[] px = {0.5f, 1f, 2f, 0.25f, 0f, 0f, 0f, 0.75f};
        ToneMap.apply(Curve.REINHARD, 0f, px, 0, 2, 4);
        assertEquals(1f / 3f, px[0], 1e-6f);
        assertEquals(2f / 3f, px[2], 1e-6f);
        assertEquals(0.25f, px[3], "alpha is left alone");
        assertEquals(0.75f, px[7]);
        assertThrows(IllegalArgumentException.class, () -> ToneMap.apply(Curve.ACES, 0f, new float[5], 0, 1, 2));
        // hue-preserving: the channel ratios stay
        float[] c = {2f, 1f, 0.5f};
        ToneMap.byLuminance(Curve.REINHARD, 0f, c, 0);
        assertEquals(2f, c[0] / c[1], 1e-5f);
        assertEquals(0.5f, c[2] / c[1], 1e-5f);
        float y = ColorSpaces.luminance(2f, 1f, 0.5f);
        assertEquals(ToneMap.reinhard(y), ColorSpaces.luminance(c[0], c[1], c[2]), 1e-5f);
        float[] black = {0f, 0f, 0f};
        ToneMap.byLuminance(Curve.ACES, 0f, black, 0);
        assertArrayEquals(new float[] {0f, 0f, 0f}, black);
    }

    // ---------------------------------------------------------------- premultiplied alpha

    @Test
    void premultiplyAndUnpremultiplyAreInverseAndHandleZeroAlpha() {
        SplittableRandom r = new SplittableRandom(SEED + 3);
        float[] px = new float[4 * 1000];
        for (int i = 0; i < px.length; i++) {
            px[i] = (float) r.nextDouble();
        }
        float[] copy = px.clone();
        PremultipliedAlpha.premultiply(px, 0, 1000);
        for (int p = 0; p < 1000; p++) {
            assertEquals(copy[4 * p] * copy[4 * p + 3], px[4 * p]);
            assertEquals(copy[4 * p + 3], px[4 * p + 3], "alpha unchanged");
        }
        PremultipliedAlpha.unpremultiply(px, 0, 1000);
        assertArrayEquals(copy, px, 2e-6f);
        float[] zero = {0.3f, 0.4f, 0.5f, 0f};
        PremultipliedAlpha.unpremultiply(zero, 0, 1);
        assertArrayEquals(new float[] {0f, 0f, 0f, 0f}, zero);
        assertThrows(IllegalArgumentException.class, () -> PremultipliedAlpha.premultiply(new float[7], 0, 2));
    }

    @Test
    void overIsAssociativeAndHasTheExpectedEndpoints() {
        SplittableRandom r = new SplittableRandom(SEED + 4);
        for (int k = 0; k < 5000; k++) {
            float[][] layers = new float[3][4];
            for (float[] l : layers) {
                float a = (float) r.nextDouble();
                l[3] = a;
                for (int c = 0; c < 3; c++) {
                    l[c] = (float) r.nextDouble() * a;     // premultiplied: colour never exceeds alpha
                }
            }
            float[] ab = new float[4], left = new float[4], bc = new float[4], right = new float[4];
            PremultipliedAlpha.over(layers[0], 0, layers[1], 0, ab, 0);
            PremultipliedAlpha.over(ab, 0, layers[2], 0, left, 0);
            PremultipliedAlpha.over(layers[1], 0, layers[2], 0, bc, 0);
            PremultipliedAlpha.over(layers[0], 0, bc, 0, right, 0);
            assertArrayEquals(left, right, 2e-6f);
            assertTrue(left[3] <= 1.0000001f);
        }
        float[] opaque = {0.2f, 0.4f, 0.6f, 1f}, behind = {0.9f, 0.9f, 0.9f, 1f}, none = {0f, 0f, 0f, 0f}, out = new float[4];
        PremultipliedAlpha.over(opaque, 0, behind, 0, out, 0);
        assertArrayEquals(opaque, out);
        PremultipliedAlpha.over(none, 0, behind, 0, out, 0);
        assertArrayEquals(behind, out);
        // in place
        PremultipliedAlpha.over(opaque, 0, behind, 0, behind, 0);
        assertArrayEquals(opaque, behind);
    }

    @Test
    void eightBitPremultiplicationRoundsToNearestExactly() {
        for (int a = 0; a < 256; a++) {
            for (int c = 0; c < 256; c++) {
                int packed = c | (c << 8) | (c << 16) | (a << 24);
                int p = PremultipliedAlpha.premultiplyRgba8(packed);
                int expected = (int) Math.round(c * a / 255.0);
                assertEquals(expected, p & 0xFF, "c=" + c + " a=" + a);
                assertEquals(expected, (p >>> 8) & 0xFF);
                assertEquals(expected, (p >>> 16) & 0xFF);
                assertEquals(a, p >>> 24);
                if (a > 0) {
                    // premultiplying an unpremultiplied premultiplied pixel gives the same pixel back
                    int u = PremultipliedAlpha.unpremultiplyRgba8(p);
                    assertEquals(p, PremultipliedAlpha.premultiplyRgba8(u), "a=" + a + " c=" + c);
                }
            }
        }
        assertEquals(0, PremultipliedAlpha.unpremultiplyRgba8(0x00FFFFFF));
        assertEquals(0xFF0000FF, PremultipliedAlpha.premultiplyRgba8(0xFF0000FF));
    }
}
