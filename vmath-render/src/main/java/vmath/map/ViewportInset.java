package vmath.map;

import vmath.annotations.Experimental;

/**
 * A rectangle of a larger window that a 2D map view draws into: the viewport and scissor
 * rectangles for the graphics API, and the conversion of window positions (the mouse) to positions
 * in the inset.
 *
 * <p>The rectangle is given in window pixels with the origin at the <em>top left</em> and y down,
 * as windowing systems report mouse positions, and clipped to the window. OpenGL wants it from the
 * bottom left ({@link #fillViewport}) and Vulkan and Direct3D from the top left
 * ({@link #fillTopLeft}); both are given. Drawing the map of {@code view.withViewport(width(), height())}
 * into the viewport (and scissor, so that nothing leaks) makes the inset a map of its own: its
 * matrix is that of the view of the inset's size, not of the window.
 *
 * <p>On a high-density display the framebuffer has more pixels than the window has units; give the
 * framebuffer size as the window size (and the rectangle in framebuffer pixels), or use
 * {@link #scaled} to turn a rectangle in window units into one in framebuffer pixels.
 *
 * <p><b>Thread safety.</b> Immutable: safe to share between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * ViewportInset inset = ViewportInset.of(1920, 1080, 1520, 680, 380, 380);      // 380 x 380 at the lower right
 * int[] v = new int[4];
 * inset.fillViewport(v);                                                          // glViewport(v[0], v[1], v[2], v[3]); glScissor(same)
 * MapView2d insetView = view.withViewport(inset.width(), inset.height());
 * }</pre>
 */
@Experimental("new in 0.2: the map layer may change")
public final class ViewportInset {

    private final int windowWidth;
    private final int windowHeight;
    private final int x;
    private final int y;
    private final int width;
    private final int height;

    private ViewportInset(int windowWidth, int windowHeight, int x, int y, int width, int height) {
        this.windowWidth = windowWidth;
        this.windowHeight = windowHeight;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /**
     * Makes an inset from a rectangle in window pixels.
     *
     * @param windowWidth the width of the window, positive
     * @param windowHeight the height of the window, positive
     * @param x the left edge, from the left of the window
     * @param y the top edge, from the top of the window
     * @param width the width, positive
     * @param height the height, positive
     * @return the inset, clipped to the window
     * @throws IllegalArgumentException if a size is not positive or the clipped rectangle is empty
     */
    public static ViewportInset of(int windowWidth, int windowHeight, int x, int y, int width, int height) {
        if (windowWidth < 1 || windowHeight < 1 || width < 1 || height < 1) {
            throw new IllegalArgumentException("the sizes must be positive");
        }
        int x0 = Math.max(0, x), y0 = Math.max(0, y);
        int x1 = (int) Math.min(windowWidth, (long) x + width), y1 = (int) Math.min(windowHeight, (long) y + height);
        if (x1 <= x0 || y1 <= y0) {
            throw new IllegalArgumentException("the rectangle is outside the window: " + x + "," + y + " " + width + "x" + height + " in " + windowWidth + "x" + windowHeight);
        }
        return new ViewportInset(windowWidth, windowHeight, x0, y0, x1 - x0, y1 - y0);
    }

    /**
     * Makes an inset from fractions of the window.
     *
     * @param windowWidth the width of the window, positive
     * @param windowHeight the height of the window, positive
     * @param left the left edge as a fraction of the width, 0 to 1
     * @param top the top edge as a fraction of the height, 0 to 1
     * @param fractionWidth the width as a fraction, above 0
     * @param fractionHeight the height as a fraction, above 0
     * @return the inset, rounded to pixels and clipped to the window
     * @throws IllegalArgumentException if a fraction is out of range or the rectangle is empty
     */
    public static ViewportInset fraction(int windowWidth, int windowHeight, double left, double top, double fractionWidth, double fractionHeight) {
        if (!(left >= 0 && left <= 1 && top >= 0 && top <= 1 && fractionWidth > 0 && fractionHeight > 0)) {
            throw new IllegalArgumentException("fractions: left and top 0 to 1, width and height above 0");
        }
        int x = (int) Math.round(left * windowWidth), y = (int) Math.round(top * windowHeight);
        return of(windowWidth, windowHeight, x, y, Math.max(1, (int) Math.round(fractionWidth * windowWidth)), Math.max(1, (int) Math.round(fractionHeight * windowHeight)));
    }

    /**
     * Gives the same rectangle in framebuffer pixels.
     *
     * @param factor the framebuffer pixels per window unit, positive (2 for a typical high-density display)
     * @return a new inset in the scaled window
     * @throws IllegalArgumentException if the factor is not positive
     */
    public ViewportInset scaled(double factor) {
        if (!(factor > 0.0)) {
            throw new IllegalArgumentException("the factor must be positive: " + factor);
        }
        return new ViewportInset((int) Math.round(windowWidth * factor), (int) Math.round(windowHeight * factor), (int) Math.round(x * factor), (int) Math.round(y * factor),
                Math.max(1, (int) Math.round(width * factor)), Math.max(1, (int) Math.round(height * factor)));
    }

    /**
     * Gives the width of the inset.
     *
     * @return pixels
     */
    public int width() {
        return width;
    }

    /**
     * Gives the height of the inset.
     *
     * @return pixels
     */
    public int height() {
        return height;
    }

    /**
     * Gives the left edge.
     *
     * @return pixels from the left of the window
     */
    public int x() {
        return x;
    }

    /**
     * Gives the top edge.
     *
     * @return pixels from the top of the window
     */
    public int y() {
        return y;
    }

    /**
     * Writes the rectangle as OpenGL takes it for {@code glViewport} and {@code glScissor}.
     *
     * @param out receives {@code x, y, width, height} with the origin at the bottom left of the window
     * @throws IllegalArgumentException if {@code out} is too short
     */
    public void fillViewport(int[] out) {
        if (out.length < 4) {
            throw new IllegalArgumentException("out must have room for 4 values");
        }
        out[0] = x;
        out[1] = windowHeight - y - height;
        out[2] = width;
        out[3] = height;
    }

    /**
     * Writes the rectangle with the origin at the top left, as Vulkan and Direct3D take it.
     *
     * @param out receives {@code x, y, width, height}
     * @throws IllegalArgumentException if {@code out} is too short
     */
    public void fillTopLeft(int[] out) {
        if (out.length < 4) {
            throw new IllegalArgumentException("out must have room for 4 values");
        }
        out[0] = x;
        out[1] = y;
        out[2] = width;
        out[3] = height;
    }

    /**
     * Tells whether a window position is inside the inset.
     *
     * @param windowX the position from the left of the window
     * @param windowY the position from the top of the window
     * @return {@code true} if inside
     */
    public boolean contains(double windowX, double windowY) {
        return windowX >= x && windowX < x + width && windowY >= y && windowY < y + height;
    }

    /**
     * Converts a window position to a position in the inset, which is what
     * {@link MapView2d#screenToProjected} of the inset's view takes.
     *
     * @param windowX the position from the left of the window
     * @param windowY the position from the top of the window
     * @param out receives the position from the top left of the inset at {@code out[0..1]}; it may be outside the inset
     * @throws IllegalArgumentException if {@code out} is too short
     */
    public void toLocal(double windowX, double windowY, double[] out) {
        if (out.length < 2) {
            throw new IllegalArgumentException("out must have room for 2 values");
        }
        out[0] = windowX - x;
        out[1] = windowY - y;
    }

    /**
     * Converts a position in the inset to a window position.
     *
     * @param localX the position from the left of the inset
     * @param localY the position from the top of the inset
     * @param out receives the position in the window at {@code out[0..1]}
     * @throws IllegalArgumentException if {@code out} is too short
     */
    public void toWindow(double localX, double localY, double[] out) {
        if (out.length < 2) {
            throw new IllegalArgumentException("out must have room for 2 values");
        }
        out[0] = localX + x;
        out[1] = localY + y;
    }
}
