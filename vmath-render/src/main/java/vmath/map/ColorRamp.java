package vmath.map;

import java.util.Arrays;
import java.util.function.IntUnaryOperator;
import vmath.annotations.Experimental;

/**
 * A map from a value (a height, a slope, a height above a reference altitude) to a colour, made of
 * stops, either interpolated or in steps, and its bake into a one-row texture.
 *
 * <p>Colours are {@code 0xRRGGBBAA} and are interpolated channel by channel (alpha too, not
 * premultiplied). A value below the first stop or above the last takes the colour of that stop. The
 * ramp is baked with {@link #bake} into {@code texels} colours over a value range {@code [min, max]};
 * the shader (and {@link Baked#colorAt}) looks the value up as {@code index = min(texels - 1,
 * floor(clamp((v - min) / (max - min), 0, 1) * texels)}, so a ramp costs one texture fetch and
 * changing it (a new reference altitude every second) costs one small upload. {@link
 * Baked#quantum()} is the value step of the texture, which is the precision of the edges of a
 * stepped ramp.
 *
 * <p><b>Relative ramps.</b> {@link #relativeSteps} places the steps at offsets from a reference
 * altitude, which is what a terrain-awareness display does: the terrain more than a margin below
 * the aircraft is one colour, within the margin another, above the aircraft a third. The reference
 * moves, the ramp is rebuilt and re-uploaded; the library does not know what any of the colours mean
 * (a safety-relevant colour scheme is the integrator's and a certification matter).
 *
 * <p><b>Thread safety.</b> Immutable: safe to share between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * ColorRamp ramp = ColorRamp.relativeSteps(3000.0, new double[] {-600.0, -300.0, 0.0}, new int[] {0x00000000, 0x40A040FF, 0xE0C000FF, 0xE00000FF});
 * ColorRamp.Baked baked = ramp.bake(-500.0, 9000.0, 256);
 * byte[] row = new byte[4 * baked.texels()];
 * baked.writeRgba(row);                                         // upload as a 256 x 1 texture
 * }</pre>
 */
@Experimental("new in 0.2: the map layer may change")
public final class ColorRamp {

    private final double[] values;
    private final int[] colors;
    private final boolean steps;

    private ColorRamp(double[] values, int[] colors, boolean steps) {
        this.values = values;
        this.colors = colors;
        this.steps = steps;
    }

    /**
     * Makes a ramp that blends between stops.
     *
     * @param values the stop values, strictly increasing, at least 1
     * @param colors the colours {@code 0xRRGGBBAA}, one per value
     * @return the ramp
     * @throws IllegalArgumentException if the arrays are empty or of different lengths, or the values are not finite and strictly increasing
     */
    public static ColorRamp linear(double[] values, int[] colors) {
        check(values, colors.length);
        return new ColorRamp(values.clone(), colors.clone(), false);
    }

    /**
     * Makes a ramp of steps: the colour {@code colors[k]} holds from {@code edges[k - 1]} (included)
     * up to {@code edges[k]}.
     *
     * @param edges the values where the colour changes, strictly increasing, at least 1
     * @param colors the colours {@code 0xRRGGBBAA}, one more than the edges: the first below the
     *     first edge, the last from the last edge on
     * @return the ramp
     * @throws IllegalArgumentException if there is not one more colour than edges, or the edges are not finite and strictly increasing
     */
    public static ColorRamp steps(double[] edges, int[] colors) {
        check(edges, colors.length - 1);
        return new ColorRamp(edges.clone(), colors.clone(), true);
    }

    /**
     * Makes a ramp of steps at offsets from a reference altitude.
     *
     * @param reference the reference altitude (the altitude of the aircraft, say)
     * @param offsets the offsets of the edges from the reference, strictly increasing, at least 1
     *     (negative offsets are below the reference)
     * @param colors the colours, one more than the offsets, as for {@link #steps}
     * @return the ramp, with the edges at {@code reference + offsets[k]}
     * @throws IllegalArgumentException as for {@link #steps}, or if the reference is not finite
     */
    public static ColorRamp relativeSteps(double reference, double[] offsets, int[] colors) {
        if (!Double.isFinite(reference)) {
            throw new IllegalArgumentException("the reference must be finite: " + reference);
        }
        double[] edges = new double[offsets.length];
        for (int i = 0; i < edges.length; i++) {
            edges[i] = reference + offsets[i];
        }
        return steps(edges, colors);
    }

    /**
     * Makes a blending ramp at offsets from a reference altitude.
     *
     * @param reference the reference altitude
     * @param offsets the offsets of the stops from the reference, strictly increasing
     * @param colors the colours, one per offset
     * @return the ramp, with the stops at {@code reference + offsets[k]}
     * @throws IllegalArgumentException as for {@link #linear}, or if the reference is not finite
     */
    public static ColorRamp relativeLinear(double reference, double[] offsets, int[] colors) {
        if (!Double.isFinite(reference)) {
            throw new IllegalArgumentException("the reference must be finite: " + reference);
        }
        double[] stops = new double[offsets.length];
        for (int i = 0; i < stops.length; i++) {
            stops[i] = reference + offsets[i];
        }
        return linear(stops, colors);
    }

    private static void check(double[] v, int colorCount) {
        if (v.length == 0) {
            throw new IllegalArgumentException("need at least one value");
        }
        for (int i = 0; i < v.length; i++) {
            if (!Double.isFinite(v[i]) || i > 0 && !(v[i] > v[i - 1])) {
                throw new IllegalArgumentException("the values must be finite and strictly increasing");
            }
        }
        if (colorCount != v.length) {
            throw new IllegalArgumentException("the number of colours does not match the number of values: " + colorCount + " for " + v.length);
        }
    }

    /**
     * Makes the ramp with every stop colour passed through a colour map: a display palette. The
     * blending happens between the mapped colours.
     *
     * @param colorMap maps a colour {@code 0xRRGGBBAA} to the colour to show; must not be {@code null}
     * @return the new ramp, with the same stops
     */
    public ColorRamp mapped(IntUnaryOperator colorMap) {
        int[] mapped = new int[colors.length];
        for (int i = 0; i < mapped.length; i++) {
            mapped[i] = colorMap.applyAsInt(colors[i]);
        }
        return new ColorRamp(values, mapped, steps);
    }

    /**
     * Tells whether the ramp is made of steps.
     *
     * @return {@code true} for {@link #steps} and {@link #relativeSteps}
     */
    public boolean isStepped() {
        return steps;
    }

    /**
     * Gives the first and last stop value.
     *
     * @param out receives the lowest at {@code out[0]} and the highest at {@code out[1]}
     * @throws IllegalArgumentException if {@code out} is too short
     */
    public void range(double[] out) {
        if (out.length < 2) {
            throw new IllegalArgumentException("out must have room for 2 values");
        }
        out[0] = values[0];
        out[1] = values[values.length - 1];
    }

    /**
     * Gives the colour at a value.
     *
     * @param v the value; {@code NaN} gives a transparent colour
     * @return the colour {@code 0xRRGGBBAA}
     */
    public int colorAt(double v) {
        if (v != v) {
            return 0;
        }
        if (steps) {
            int k = 0;
            while (k < values.length && v >= values[k]) {
                k++;
            }
            return colors[k];
        }
        if (v <= values[0]) {
            return colors[0];
        }
        int last = values.length - 1;
        if (v >= values[last]) {
            return colors[last];
        }
        int hi = Arrays.binarySearch(values, v);
        if (hi >= 0) {
            return colors[hi];
        }
        hi = -hi - 1;
        double t = (v - values[hi - 1]) / (values[hi] - values[hi - 1]);
        int out = 0;
        for (int shift = 24; shift >= 0; shift -= 8) {
            double a = (colors[hi - 1] >>> shift) & 255, b = (colors[hi] >>> shift) & 255;
            out |= (int) Math.round(a + (b - a) * t) << shift;
        }
        return out;
    }

    /** A ramp baked into a row of colours over a range of values: what the texture holds. */
    public static final class Baked {
        private final int[] texels;
        private final float min;
        private final float max;

        private Baked(int[] texels, float min, float max) {
            this.texels = texels;
            this.min = min;
            this.max = max;
        }

        /**
         * Gives the number of colours.
         *
         * @return the width of the texture
         */
        public int texels() {
            return texels.length;
        }

        /**
         * Gives the lowest value of the range.
         *
         * @return the value at the left edge of the first texel
         */
        public float min() {
            return min;
        }

        /**
         * Gives the highest value of the range.
         *
         * @return the value at the right edge of the last texel
         */
        public float max() {
            return max;
        }

        /**
         * Gives the value step of one texel.
         *
         * @return {@code (max - min) / texels}
         */
        public float quantum() {
            return (max - min) / texels.length;
        }

        /**
         * Gives the index of the texel that a value falls on, the way the shader computes it.
         *
         * @param v the value
         * @return 0 to {@code texels() - 1}
         */
        public int indexOf(float v) {
            float u = Math.min(1f, Math.max(0f, (v - min) / (max - min)));
            return Math.min(texels.length - 1, (int) (u * texels.length));
        }

        /**
         * Looks a value up as the shader does.
         *
         * @param v the value; {@code NaN} gives a transparent colour
         * @return the colour {@code 0xRRGGBBAA}
         */
        public int colorAt(float v) {
            return v != v ? 0 : texels[indexOf(v)];
        }

        /**
         * Writes the row as RGBA bytes for a texture of {@code texels() x 1}.
         *
         * @param out receives {@code 4 * texels()} bytes
         * @throws IllegalArgumentException if {@code out} is too short
         */
        public void writeRgba(byte[] out) {
            if (out.length < 4 * texels.length) {
                throw new IllegalArgumentException("out needs " + 4 * texels.length + " bytes");
            }
            for (int i = 0; i < texels.length; i++) {
                out[4 * i] = (byte) (texels[i] >>> 24);
                out[4 * i + 1] = (byte) (texels[i] >>> 16);
                out[4 * i + 2] = (byte) (texels[i] >>> 8);
                out[4 * i + 3] = (byte) texels[i];
            }
        }
    }

    /**
     * Bakes the ramp over a range of values.
     *
     * @param min the value at the left edge of the texture
     * @param max the value at the right edge, above {@code min}
     * @param texels the width of the texture, at least 1; each texel takes the colour at its centre
     * @return the baked row
     * @throws IllegalArgumentException if the range is empty or not finite, or there are no texels
     */
    public Baked bake(float min, float max, int texels) {
        if (!(max > min) || !Float.isFinite(min) || !Float.isFinite(max) || texels < 1) {
            throw new IllegalArgumentException("need a finite range with max > min and at least one texel");
        }
        int[] row = new int[texels];
        for (int i = 0; i < texels; i++) {
            row[i] = colorAt(min + (i + 0.5) / texels * ((double) max - min));
        }
        return new Baked(row, min, max);
    }
}
