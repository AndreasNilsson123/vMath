package vmath.color;

import vmath.annotations.Experimental;

/**
 * Tone-mapping curves: they take scene-referred linear light, which can be any non-negative number, to display-referred linear light in {@code [0, 1]} (apply
 * {@link Srgb#fromLinear} afterwards). Each curve here is a function of one channel with {@code f(0) = 0}, monotonic, and clamped to at most 1; applying it to the
 * three channels separately desaturates bright colours towards white, which is the look these curves are known for. For a hue-preserving alternative apply
 * {@link #byLuminance} to scale all channels by the curve of the luminance.
 *
 * <p>The curves are the usual published ones, not colour-managed film emulations:
 * <ul>
 *   <li>{@link #reinhard}: {@code x / (1 + x)}; never reaches 1.</li>
 *   <li>{@link #reinhardExtended}: Reinhard with a white point: {@code x (1 + x / w^2) / (1 + x)}, reaching 1 at {@code x = w}.</li>
 *   <li>{@link #aces}: the Narkowicz fit of the ACES filmic curve, {@code x (2.51 x + 0.03) / (x (2.43 x + 0.59) + 0.14)}, clamped.</li>
 *   <li>{@link #hable}: the "Uncharted 2" filmic curve with its published constants and a white point of 11.2, normalized so that 11.2 maps to 1.</li>
 *   <li>{@link #exposure}: {@code 1 - exp(-x)}; a soft clip with a simple falloff.</li>
 * </ul>
 * Apply the exposure before the curve ({@code x * 2^EV}); see the physical camera model for how EV relates to scene luminance.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the same time. The arrays and buffers you pass in are
 * not synchronised, so two threads must not write the same one.
 */
@Experimental("the set of curves may grow")
public final class ToneMap {

    /** The curve to apply; used by the array method {@link #apply}. */
    public enum Curve {
        /** {@link #reinhard}: {@code x / (1 + x)}. */
        REINHARD,
        /** {@link #reinhardExtended}: Reinhard with a white point (the {@code whitePoint} argument of the array method). */
        REINHARD_EXTENDED,
        /** {@link #aces}: the Narkowicz fit of the ACES filmic curve. */
        ACES,
        /** {@link #hable}: the "Uncharted 2" filmic curve. */
        HABLE,
        /** {@link #exposure}: {@code 1 - exp(-x)}. */
        EXPOSURE
    }

    private static final float HABLE_A = 0.15f, HABLE_B = 0.50f, HABLE_C = 0.10f, HABLE_D = 0.20f, HABLE_E = 0.02f, HABLE_F = 0.30f;
    private static final float HABLE_WHITE = 11.2f;
    private static final float HABLE_WHITE_SCALE = 1f / hableRaw(HABLE_WHITE);

    private ToneMap() {
    }

    private static float saturate(float x) {
        return x < 0f ? 0f : x > 1f ? 1f : x;
    }

    /** {@code x / (1 + x)} for {@code x >= 0}; negative input gives 0. */
    public static float reinhard(float x) {
        return x <= 0f ? 0f : x / (1f + x);
    }

    /** Reinhard with a white point: {@code whitePoint} maps to 1 and larger values clamp to 1. {@code whitePoint} must be positive. */
    public static float reinhardExtended(float x, float whitePoint) {
        if (!(whitePoint > 0f)) {
            throw new IllegalArgumentException("the white point must be positive: " + whitePoint);
        }
        if (x <= 0f) {
            return 0f;
        }
        return saturate(x * (1f + x / (whitePoint * whitePoint)) / (1f + x));
    }

    /** The Narkowicz fit of the ACES filmic curve, clamped to {@code [0, 1]}. */
    public static float aces(float x) {
        if (x <= 0f) {
            return 0f;
        }
        return saturate((x * (2.51f * x + 0.03f)) / (x * (2.43f * x + 0.59f) + 0.14f));
    }

    private static float hableRaw(float x) {
        return ((x * (HABLE_A * x + HABLE_C * HABLE_B) + HABLE_D * HABLE_E) / (x * (HABLE_A * x + HABLE_B) + HABLE_D * HABLE_F)) - HABLE_E / HABLE_F;
    }

    /** The Hable ("Uncharted 2") filmic curve, normalized so that 11.2 maps to 1 and larger values clamp. Hable's own use applies an exposure bias of 2 before it. */
    public static float hable(float x) {
        return x <= 0f ? 0f : saturate(hableRaw(x) * HABLE_WHITE_SCALE);
    }

    /** {@code 1 - exp(-x)} for {@code x >= 0}. */
    public static float exposure(float x) {
        return x <= 0f ? 0f : (float) -Math.expm1(-x);
    }

    /** Applies a curve to one channel; {@code whitePoint} is used by {@link Curve#REINHARD_EXTENDED} only. */
    public static float apply(Curve curve, float x, float whitePoint) {
        return switch (curve) {
            case REINHARD -> reinhard(x);
            case REINHARD_EXTENDED -> reinhardExtended(x, whitePoint);
            case ACES -> aces(x);
            case HABLE -> hable(x);
            case EXPOSURE -> exposure(x);
        };
    }

    /**
     * Tone-maps {@code pixels} pixels of {@code components} floats (3 or 4; alpha is left alone) in place, each colour channel separately, starting at {@code offset}.
     */
    public static void apply(Curve curve, float whitePoint, float[] a, int offset, int pixels, int components) {
        if (components < 3 || components > 4 || pixels < 0 || offset < 0 || (long) offset + (long) pixels * components > a.length) {
            throw new IllegalArgumentException("pixels, components or offset do not fit the array");
        }
        for (int p = 0, i = offset; p < pixels; p++, i += components) {
            a[i] = apply(curve, a[i], whitePoint);
            a[i + 1] = apply(curve, a[i + 1], whitePoint);
            a[i + 2] = apply(curve, a[i + 2], whitePoint);
        }
    }

    /**
     * Tone-maps by luminance: all three channels are scaled by {@code curve(Y) / Y}, so the hue and the ratios between channels are kept (a very saturated bright colour can
     * then leave the displayable range and is clamped). {@code rgb} is linear and is modified in place; black stays black.
     */
    public static void byLuminance(Curve curve, float whitePoint, float[] rgb, int offset) {
        float y = ColorSpaces.luminance(rgb[offset], rgb[offset + 1], rgb[offset + 2]);
        if (!(y > 0f)) {
            rgb[offset] = 0f;
            rgb[offset + 1] = 0f;
            rgb[offset + 2] = 0f;
            return;
        }
        float scale = apply(curve, y, whitePoint) / y;
        for (int c = 0; c < 3; c++) {
            rgb[offset + c] = saturate(rgb[offset + c] * scale);
        }
    }
}
