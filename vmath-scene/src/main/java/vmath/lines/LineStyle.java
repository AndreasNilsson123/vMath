package vmath.lines;

import java.util.Arrays;
import vmath.annotations.Experimental;

/**
 * How a polyline is drawn: colour, width, caps, joins and dashes.
 *
 * <p>A style is a small immutable value; the methods that start with {@code with} return a changed
 * copy. A {@link LineBatch} keeps one table of the distinct styles it uses, so that thousands of
 * polylines that look alike share one entry in the style buffer.
 *
 * <ul>
 *   <li><b>Width</b> is in pixels ({@link WidthUnit#PIXELS}, the same on screen at any distance) or
 *       in world units ({@link WidthUnit#WORLD}, which shrinks with distance in a perspective
 *       view and is the pixel size on the ground in an orthographic one).
 *   <li><b>Caps</b> close the two ends of an open polyline; <b>joins</b> fill the outside of a
 *       corner. A miter join that would be longer than the <b>miter limit</b> (the ratio of the
 *       length of the miter to the width, as in SVG and Java 2D) becomes a bevel.
 *   <li><b>Dashes</b> are lengths in <em>world</em> units along the polyline, "on, off, on, off,
 *       ...", up to eight numbers; the pattern runs on continuously through the corners of the
 *       polyline. For dashes that keep one length on the screen divide a pixel length by the
 *       size of a pixel on the ground, {@link #dashedInPixels}.
 *   <li>The <b>layer</b> orders polylines: lower layers are drawn first.
 * </ul>
 *
 * <p><b>Thread safety.</b> Immutable: safe to share between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * LineStyle road = LineStyle.pixels(3f).withColor(0xFFCC33FF).withJoin(LineStyle.Join.ROUND).withCap(LineStyle.Cap.ROUND);
 * LineStyle border = LineStyle.world(250f).withColor(0x808080FF).withDash(1000f, 500f).withLayer(-1);
 * }</pre>
 */
@Experimental("the style model may change")
public final class LineStyle {

    /**
     * How the ends of an open polyline are closed.
     */
    public enum Cap {
        /** The line ends exactly at its last point. */
        BUTT,
        /** The line is extended by half its width. */
        SQUARE,
        /** A half circle of the diameter of the width. */
        ROUND
    }

    /**
     * How the outside of a corner is filled.
     */
    public enum Join {
        /** The two edges are extended until they meet, up to the miter limit; beyond it the corner is cut off like a bevel. */
        MITER,
        /** The corner is cut off with a straight edge. */
        BEVEL,
        /** The corner is a circular arc. */
        ROUND
    }

    /**
     * The unit of the width.
     */
    public enum WidthUnit {
        /** Pixels on the screen. */
        PIXELS,
        /** World units: pixels per unit depend on the view. */
        WORLD
    }

    /** The most numbers in a dash pattern. */
    public static final int MAX_DASH_VALUES = 8;

    private final int color;
    private final float width;
    private final WidthUnit unit;
    private final Cap cap;
    private final Join join;
    private final float miterLimit;
    private final float[] dash;
    private final int layer;

    private LineStyle(int color, float width, WidthUnit unit, Cap cap, Join join, float miterLimit, float[] dash, int layer) {
        this.color = color;
        this.width = width;
        this.unit = unit;
        this.cap = cap;
        this.join = join;
        this.miterLimit = miterLimit;
        this.dash = dash;
        this.layer = layer;
    }

    private static float width(float w) {
        if (!(w > 0f) || Float.isInfinite(w)) {
            throw new IllegalArgumentException("the width must be positive and finite: " + w);
        }
        return w;
    }

    /**
     * Starts a style with a width in pixels: white, butt caps, miter joins with a limit of 4, no
     * dashes, layer 0.
     *
     * @param widthPixels the width in pixels
     * @return the style
     * @throws IllegalArgumentException if the width is not positive and finite
     */
    public static LineStyle pixels(float widthPixels) {
        return new LineStyle(0xFFFFFFFF, width(widthPixels), WidthUnit.PIXELS, Cap.BUTT, Join.MITER, 4f, new float[0], 0);
    }

    /**
     * Starts a style with a width in world units, otherwise as {@link #pixels}.
     *
     * @param widthWorld the width in world units
     * @return the style
     * @throws IllegalArgumentException if the width is not positive and finite
     */
    public static LineStyle world(float widthWorld) {
        return new LineStyle(0xFFFFFFFF, width(widthWorld), WidthUnit.WORLD, Cap.BUTT, Join.MITER, 4f, new float[0], 0);
    }

    /**
     * Changes the colour.
     *
     * @param rgba the colour as {@code 0xRRGGBBAA}
     * @return the changed style
     */
    public LineStyle withColor(int rgba) {
        return new LineStyle(rgba, width, unit, cap, join, miterLimit, dash, layer);
    }

    /**
     * Changes the width, keeping its unit.
     *
     * @param newWidth the width
     * @return the changed style
     * @throws IllegalArgumentException if the width is not positive and finite
     */
    public LineStyle withWidth(float newWidth) {
        return new LineStyle(color, width(newWidth), unit, cap, join, miterLimit, dash, layer);
    }

    /**
     * Changes the caps.
     *
     * @param newCap the cap; must not be {@code null}
     * @return the changed style
     */
    public LineStyle withCap(Cap newCap) {
        return new LineStyle(color, width, unit, java.util.Objects.requireNonNull(newCap), join, miterLimit, dash, layer);
    }

    /**
     * Changes the joins.
     *
     * @param newJoin the join; must not be {@code null}
     * @return the changed style
     */
    public LineStyle withJoin(Join newJoin) {
        return new LineStyle(color, width, unit, cap, java.util.Objects.requireNonNull(newJoin), miterLimit, dash, layer);
    }

    /**
     * Changes the miter limit.
     *
     * @param limit the longest miter as a multiple of the width, at least 1
     * @return the changed style
     * @throws IllegalArgumentException if {@code limit} is below 1 or not finite
     */
    public LineStyle withMiterLimit(float limit) {
        if (!(limit >= 1f) || Float.isInfinite(limit)) {
            throw new IllegalArgumentException("the miter limit must be at least 1 and finite: " + limit);
        }
        return new LineStyle(color, width, unit, cap, join, limit, dash, layer);
    }

    /**
     * Changes the dash pattern.
     *
     * @param onOff lengths in world units, alternately "on" and "off", starting with "on"; an
     *     empty array means a solid line; copied
     * @return the changed style
     * @throws IllegalArgumentException if there are more than {@link #MAX_DASH_VALUES} numbers or
     *     an odd count, a number is negative or not finite, or they sum to zero
     */
    public LineStyle withDash(float... onOff) {
        if (onOff.length > MAX_DASH_VALUES || onOff.length % 2 != 0) {
            throw new IllegalArgumentException("a dash pattern has an even number of values, at most " + MAX_DASH_VALUES + ": " + onOff.length);
        }
        double sum = 0;
        for (float v : onOff) {
            if (!(v >= 0f) || Float.isInfinite(v)) {
                throw new IllegalArgumentException("a dash length must be finite and not negative: " + v);
            }
            sum += v;
        }
        if (onOff.length > 0 && !(sum > 0)) {
            throw new IllegalArgumentException("a dash pattern must have a length");
        }
        return new LineStyle(color, width, unit, cap, join, miterLimit, onOff.clone(), layer);
    }

    /**
     * Makes dashes whose lengths are given in pixels, for a view in which a pixel is a known size
     * on the ground (an orthographic or map view).
     *
     * @param pixelSizeOnGround the size of a pixel in world units, positive
     * @param onOffPixels the lengths in pixels, as for {@link #withDash}
     * @return the changed style, with the lengths converted to world units
     * @throws IllegalArgumentException if the pixel size is not positive and finite, or the
     *     pattern is not valid
     */
    public LineStyle dashedInPixels(float pixelSizeOnGround, float... onOffPixels) {
        if (!(pixelSizeOnGround > 0f) || Float.isInfinite(pixelSizeOnGround)) {
            throw new IllegalArgumentException("the pixel size must be positive and finite: " + pixelSizeOnGround);
        }
        float[] world = new float[onOffPixels.length];
        for (int i = 0; i < world.length; i++) {
            world[i] = onOffPixels[i] * pixelSizeOnGround;
        }
        return withDash(world);
    }

    /**
     * Changes the layer.
     *
     * @param newLayer the layer; lower layers are drawn first
     * @return the changed style
     */
    public LineStyle withLayer(int newLayer) {
        return new LineStyle(color, width, unit, cap, join, miterLimit, dash, newLayer);
    }

    /**
     * Gives the colour.
     *
     * @return the colour as {@code 0xRRGGBBAA}
     */
    public int color() {
        return color;
    }

    /**
     * Gives the width.
     *
     * @return the width, in the unit of {@link #unit()}
     */
    public float width() {
        return width;
    }

    /**
     * Gives the unit of the width.
     *
     * @return the unit
     */
    public WidthUnit unit() {
        return unit;
    }

    /**
     * Gives the cap.
     *
     * @return the cap
     */
    public Cap cap() {
        return cap;
    }

    /**
     * Gives the join.
     *
     * @return the join
     */
    public Join join() {
        return join;
    }

    /**
     * Gives the miter limit.
     *
     * @return the longest miter as a multiple of the width
     */
    public float miterLimit() {
        return miterLimit;
    }

    /**
     * Counts the numbers of the dash pattern.
     *
     * @return the count; 0 for a solid line
     */
    public int dashCount() {
        return dash.length;
    }

    /**
     * Gives the dash pattern.
     *
     * @return a copy of the lengths in world units, "on, off, ..."; empty for a solid line
     */
    public float[] dash() {
        return dash.clone();
    }

    /**
     * Gives the length of one repetition of the dash pattern.
     *
     * @return the sum of the dash numbers, 0 for a solid line
     */
    public double dashPeriod() {
        double sum = 0;
        for (float v : dash) {
            sum += v;
        }
        return sum;
    }

    /**
     * Gives the layer.
     *
     * @return the layer
     */
    public int layer() {
        return layer;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LineStyle s && color == s.color && Float.compare(width, s.width) == 0 && unit == s.unit && cap == s.cap && join == s.join
                && Float.compare(miterLimit, s.miterLimit) == 0 && Arrays.equals(dash, s.dash) && layer == s.layer;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(color, width, unit, cap, join, miterLimit, Arrays.hashCode(dash), layer);
    }

    @Override
    public String toString() {
        return "LineStyle[" + width + (unit == WidthUnit.PIXELS ? " px" : " units") + ", " + String.format("%08X", color) + ", " + cap + ", " + join + ", miter " + miterLimit
                + (dash.length > 0 ? ", dash " + Arrays.toString(dash) : "") + ", layer " + layer + "]";
    }
}
