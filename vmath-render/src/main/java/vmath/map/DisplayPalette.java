package vmath.map;

import java.util.function.IntUnaryOperator;
import vmath.annotations.Experimental;

/**
 * A display palette: a function from the colours the map is styled with to the colours it shows,
 * for a day, a night and a night-vision look of the same style tables.
 *
 * <p>The palette works on {@code 0xRRGGBBAA} colours, keeps the alpha, and does its arithmetic in
 * linear light: the sRGB values are decoded, multiplied by a 3 by 3 matrix and a gain, clamped and
 * encoded again. It is an {@link IntUnaryOperator}, so it plugs into the colour-map overloads of
 * {@code LineRenderPlan.write}, {@code LineSet.setColorMap}, {@link AreaRenderPlan#write},
 * {@link SymbolRenderPlan#write}, {@link ColorRamp#mapped} and {@link SymbolAtlas#mapped}. A style
 * table is one small buffer, so switching the palette is rewriting that buffer and uploading it
 * (the paths that keep a colour in the vertices write the data again).
 *
 * <p>The presets are generic looks and nothing more: {@link #DAY} (unchanged), {@link #NIGHT} (a
 * quarter of the light), {@link #NIGHT_VISION} (the luminance as green, at half) and
 * {@link #RED_NIGHT} (the luminance as red, at a third). Whether a colour set is compatible with a
 * particular goggle or cockpit lighting standard is a property of the display hardware and its
 * spectrum that no table of 8-bit values can establish; the integrator defines and verifies the
 * palette that is used, and {@link #of} is how.
 *
 * <p><b>Thread safety.</b> Immutable: safe to share between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * DisplayPalette night = DisplayPalette.NIGHT;
 * int dimmed = night.map(0xFFC000FF);                       // amber, at a quarter of the light
 * linePlan.rewriteStyles(batch, draws, styleBuffer, night);   // then upload the style buffer
 * }</pre>
 */
@Experimental("new in 0.2: the map layer may change")
public final class DisplayPalette implements IntUnaryOperator {

    private static final float[] DECODE = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            DECODE[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
    }

    /** Unchanged colours. */
    public static final DisplayPalette DAY = new DisplayPalette("day", new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1}, 1f);
    /** A quarter of the light, the hues kept. */
    public static final DisplayPalette NIGHT = new DisplayPalette("night", new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1}, 0.25f);
    /** The luminance as green, at half the light: a monochrome display for use with goggles, to be verified by the integrator. */
    public static final DisplayPalette NIGHT_VISION = new DisplayPalette("night vision", new float[] {0, 0, 0, 0.2126f, 0.7152f, 0.0722f, 0, 0, 0}, 0.5f);
    /** The luminance as red, at a third of the light: a monochrome display that keeps the eye dark adapted. */
    public static final DisplayPalette RED_NIGHT = new DisplayPalette("red night", new float[] {0.2126f, 0.7152f, 0.0722f, 0, 0, 0, 0, 0, 0}, 1f / 3f);

    private final String name;
    private final float[] matrix;
    private final float gain;

    private DisplayPalette(String name, float[] matrix, float gain) {
        this.name = name;
        this.matrix = matrix;
        this.gain = gain;
    }

    /**
     * Makes a palette.
     *
     * @param name a name for logs and menus; must not be {@code null}
     * @param matrix the 3 by 3 matrix in row-major order that maps linear {@code (r, g, b)} to the shown linear colour: the first row gives the red, and so on; 9 values, copied
     * @param gain a factor on the light after the matrix, not negative
     * @return the palette
     * @throws IllegalArgumentException if the matrix does not have 9 finite values or the gain is negative or not finite
     */
    public static DisplayPalette of(String name, float[] matrix, float gain) {
        if (name == null || matrix.length != 9 || !(gain >= 0f) || !Float.isFinite(gain)) {
            throw new IllegalArgumentException("need a name, 9 matrix values and a gain that is not negative");
        }
        for (float v : matrix) {
            if (!Float.isFinite(v)) {
                throw new IllegalArgumentException("the matrix values must be finite");
            }
        }
        return new DisplayPalette(name, matrix.clone(), gain);
    }

    /**
     * Gives the name.
     *
     * @return the name
     */
    public String name() {
        return name;
    }

    /**
     * Maps a colour.
     *
     * @param rgba the colour {@code 0xRRGGBBAA}
     * @return the colour to show; the alpha is unchanged
     */
    public int map(int rgba) {
        float r = DECODE[(rgba >>> 24) & 255], g = DECODE[(rgba >>> 16) & 255], b = DECODE[(rgba >>> 8) & 255];
        int out = rgba & 255;
        for (int k = 0; k < 3; k++) {
            float v = gain * (matrix[3 * k] * r + matrix[3 * k + 1] * g + matrix[3 * k + 2] * b);
            out |= encode(v) << (24 - 8 * k);
        }
        return out;
    }

    @Override
    public int applyAsInt(int rgba) {
        return map(rgba);
    }

    private static int encode(float linear) {
        double v = Math.min(1.0, Math.max(0.0, linear));
        double c = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1.0 / 2.4) - 0.055;
        return (int) Math.round(c * 255.0);
    }

    /**
     * Maps an array of colours in place.
     *
     * @param colors the colours {@code 0xRRGGBBAA}; must not be {@code null}
     */
    public void mapAll(int[] colors) {
        for (int i = 0; i < colors.length; i++) {
            colors[i] = map(colors[i]);
        }
    }

    @Override
    public String toString() {
        return "DisplayPalette[" + name + "]";
    }
}
