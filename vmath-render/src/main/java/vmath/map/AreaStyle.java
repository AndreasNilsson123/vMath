package vmath.map;

import vmath.annotations.Experimental;

/**
 * How a filled area is painted: a fill colour and, over it, a pattern of lines or dots that the
 * fragment shader evaluates in window pixels.
 *
 * <p>The patterns are what a chart uses to tell areas apart without colour (restricted and danger
 * areas as hatching, a built-up area as dots). Spacing, line width and dot size are in pixels, so
 * a pattern keeps its look as the map is scaled; the pattern is fixed to the window unless
 * {@link AreaRenderPlan#patternOffset} moves it with the map. Colours are {@code 0xRRGGBBAA}; the
 * pattern replaces the fill where it is on, so a transparent fill with a hatch draws the lines
 * only.
 *
 * <p><b>Thread safety.</b> Immutable: safe to share between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * AreaStyle restricted = AreaStyle.hatch(0xFF000020, 0xFF0000FF, 8f, 1.5f, Math.toRadians(45));
 * AreaStyle water = AreaStyle.solid(0x4080C0FF);
 * }</pre>
 *
 * @param fill the fill colour {@code 0xRRGGBBAA}
 * @param pattern the pattern over the fill
 * @param patternColor the colour of the pattern {@code 0xRRGGBBAA}
 * @param spacing the distance between lines, or between dot centres, in pixels; positive for a pattern
 * @param width the width of a line, or the diameter of a dot, in pixels
 * @param angle the direction of the lines in radians counter-clockwise from the window's x axis (the rotation of the dot grid)
 */
@Experimental("new in 0.2: the map layer may change")
public record AreaStyle(int fill, Pattern pattern, int patternColor, float spacing, float width, float angle) {

    /** The patterns of an area. */
    public enum Pattern {
        /** No pattern: the fill only. */
        SOLID,
        /** Parallel lines. */
        HATCH,
        /** Two sets of lines at a right angle. */
        CROSSHATCH,
        /** A grid of dots. */
        DOTS
    }

    /**
     * Checks the values.
     *
     * @throws IllegalArgumentException if the pattern is {@code null}, a number is not finite, or a pattern has a spacing that is not positive or a negative width
     */
    public AreaStyle {
        if (pattern == null) {
            throw new IllegalArgumentException("the pattern must not be null");
        }
        if (!Float.isFinite(spacing) || !Float.isFinite(width) || !Float.isFinite(angle) || width < 0f) {
            throw new IllegalArgumentException("spacing, width and angle must be finite and the width not negative");
        }
        if (pattern != Pattern.SOLID && !(spacing > 0f)) {
            throw new IllegalArgumentException("a pattern needs a positive spacing: " + spacing);
        }
    }

    /**
     * Makes a plain fill.
     *
     * @param fill the colour {@code 0xRRGGBBAA}
     * @return the style
     */
    public static AreaStyle solid(int fill) {
        return new AreaStyle(fill, Pattern.SOLID, 0, 0f, 0f, 0f);
    }

    /**
     * Makes a fill with parallel lines.
     *
     * @param fill the fill colour
     * @param lineColor the colour of the lines
     * @param spacing the distance between lines in pixels, positive
     * @param width the width of a line in pixels, not negative
     * @param angle the direction of the lines in radians
     * @return the style
     * @throws IllegalArgumentException if the spacing is not positive or the width is negative
     */
    public static AreaStyle hatch(int fill, int lineColor, float spacing, float width, double angle) {
        return new AreaStyle(fill, Pattern.HATCH, lineColor, spacing, width, (float) angle);
    }

    /**
     * Makes a fill with two sets of lines at a right angle.
     *
     * @param fill the fill colour
     * @param lineColor the colour of the lines
     * @param spacing the distance between lines in pixels, positive
     * @param width the width of a line in pixels, not negative
     * @param angle the direction of the first set in radians
     * @return the style
     * @throws IllegalArgumentException if the spacing is not positive or the width is negative
     */
    public static AreaStyle crosshatch(int fill, int lineColor, float spacing, float width, double angle) {
        return new AreaStyle(fill, Pattern.CROSSHATCH, lineColor, spacing, width, (float) angle);
    }

    /**
     * Makes a fill with a grid of dots.
     *
     * @param fill the fill colour
     * @param dotColor the colour of the dots
     * @param spacing the distance between dot centres in pixels, positive
     * @param diameter the diameter of a dot in pixels, not negative
     * @param angle the rotation of the grid in radians
     * @return the style
     * @throws IllegalArgumentException if the spacing is not positive or the diameter is negative
     */
    public static AreaStyle dots(int fill, int dotColor, float spacing, float diameter, double angle) {
        return new AreaStyle(fill, Pattern.DOTS, dotColor, spacing, diameter, (float) angle);
    }
}
