package vmath.samples.framework;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

/**
 * A 2D view of a plane with a pan (left mouse button) and a zoom about the cursor (wheel), and the
 * orthographic matrix in double precision that the line renderer needs.
 *
 * <p>The scale is pixels per world unit; world y points up the window. {@link #viewProjection}
 * gives the matrix in world coordinates, which {@code LineSet#relativeViewProjection} or
 * {@code LineBatch#relativeViewProjection} makes relative to the origin of the lines.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe.
 */
public final class PanZoom2d {

    private double centerX;
    private double centerY;
    private double scale;
    private final double minScale;
    private final double maxScale;

    /**
     * Creates a view.
     *
     * @param centerX the world x at the middle of the window
     * @param centerY the world y at the middle of the window
     * @param scale the pixels per world unit
     * @param minScale the smallest scale the wheel allows
     * @param maxScale the largest scale the wheel allows
     */
    public PanZoom2d(double centerX, double centerY, double scale, double minScale, double maxScale) {
        this.centerX = centerX;
        this.centerY = centerY;
        this.scale = scale;
        this.minScale = minScale;
        this.maxScale = maxScale;
    }

    /**
     * Applies the mouse of a frame: drag to pan, wheel to zoom about the cursor.
     *
     * @param frame the frame; must not be {@code null}
     * @param blocked whether a control of the HUD has the pointer, in which case nothing moves
     */
    public void update(FrameInfo frame, boolean blocked) {
        Input in = frame.input();
        if (blocked) {
            return;
        }
        if (in.mouseDown(GLFW_MOUSE_BUTTON_LEFT)) {
            centerX -= in.mouseDx() / scale;
            centerY += in.mouseDy() / scale;
        }
        float notches = in.scroll();
        if (notches != 0f) {
            double before = scale;
            scale = Math.max(minScale, Math.min(maxScale, scale * Math.pow(1.15, notches)));
            // the world point under the cursor stays under it
            double px = in.mouseX() - frame.width() / 2.0, py = frame.height() / 2.0 - in.mouseY();
            centerX += px / before - px / scale;
            centerY += py / before - py / scale;
        }
    }

    /**
     * Places the view, for a scripted path.
     *
     * @param x the world x at the middle
     * @param y the world y at the middle
     * @param newScale the pixels per world unit
     */
    public void place(double x, double y, double newScale) {
        centerX = x;
        centerY = y;
        scale = Math.max(minScale, Math.min(maxScale, newScale));
    }

    /**
     * Gives the world x at the middle of the window.
     *
     * @return the x
     */
    public double centerX() {
        return centerX;
    }

    /**
     * Gives the world y at the middle of the window.
     *
     * @return the y
     */
    public double centerY() {
        return centerY;
    }

    /**
     * Gives the scale.
     *
     * @return the pixels per world unit
     */
    public double scale() {
        return scale;
    }

    /**
     * Writes the orthographic view-projection matrix of the window in world coordinates.
     *
     * @param width the width of the window in pixels
     * @param height the height of the window in pixels
     * @param out receives 16 values, column-major
     */
    public void viewProjection(int width, int height, double[] out) {
        java.util.Arrays.fill(out, 0, 16, 0.0);
        out[0] = 2.0 * scale / width;
        out[5] = 2.0 * scale / height;
        out[10] = 1.0;
        out[12] = -centerX * out[0];
        out[13] = -centerY * out[5];
        out[15] = 1.0;
    }
}
