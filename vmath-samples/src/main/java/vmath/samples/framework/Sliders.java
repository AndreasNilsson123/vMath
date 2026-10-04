package vmath.samples.framework;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

/**
 * Mouse-driven controls for the heads-up display, in the immediate-mode style: a demo calls
 * {@link #slider}, {@link #checkbox} or {@link #choice} in its {@code hud} method every frame with
 * the current value and gets the new value back, and the control is drawn where it is asked for.
 *
 * <p>A slider is dragged with the left mouse button: pressing on it starts the drag, moving the mouse
 * while the button is down sets the value from the horizontal position, and releasing ends it. A
 * checkbox flips on a click and a choice selects the button that was clicked. While the pointer is
 * over a control or a drag is going on, {@link #capturing} tells the demo to leave the mouse alone
 * (so that dragging a slider does not also turn the camera); it reports what the previous frame's
 * controls saw, which is why a demo asks it in {@code update}.
 *
 * <p>The hit tests and the values are in {@link #drag}, {@link #hit} and {@link #choose}, which do
 * not draw and need no OpenGL, so they can be tested. Positions are in window pixels with the origin
 * at the top left, the same as the heads-up display and the mouse position.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: it is used by the render thread.
 */
public final class Sliders {

    private static final float TRACK_HEIGHT = 10f;
    private static final float GAP = 4f;

    private int dragging;
    private boolean overNow;
    private boolean overBefore;

    /**
     * Tells whether the controls want the mouse: the pointer was over one in the last frame or a
     * drag is going on.
     *
     * @return {@code true} if a demo should not use the mouse for anything else
     */
    public boolean capturing() {
        return dragging != 0 || overBefore;
    }

    /**
     * Gives the height that a slider takes, so that a demo can stack them.
     *
     * @param hud the display; must not be {@code null}
     * @return the height of a slider with its label in pixels, including a gap below
     */
    public static float height(Hud hud) {
        return hud.lineHeight() + GAP + TRACK_HEIGHT + 8f;
    }

    /**
     * Tells whether a point is inside a rectangle.
     *
     * @param px the x coordinate of the point
     * @param py the y coordinate of the point
     * @param x the left edge of the rectangle
     * @param y the top edge of the rectangle
     * @param w the width of the rectangle
     * @param h the height of the rectangle
     * @return {@code true} if the point is inside or on the edge
     */
    static boolean hit(float px, float py, float x, float y, float w, float h) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    /**
     * Gives the value of a slider for a mouse position.
     *
     * @param px the x coordinate of the mouse
     * @param x the left end of the track
     * @param w the width of the track
     * @param min the value at the left end
     * @param max the value at the right end
     * @return the value, clamped to the range of the slider
     */
    static float drag(float px, float x, float w, float min, float max) {
        float t = Math.max(0f, Math.min(1f, (px - x) / w));
        return min + t * (max - min);
    }

    /**
     * Finds which of several equal buttons in a row a point is on.
     *
     * @param px the x coordinate of the point
     * @param py the y coordinate of the point
     * @param x the left edge of the first button
     * @param y the top edge of the buttons
     * @param widths the widths of the buttons, without gaps; must not be {@code null}
     * @param gap the gap between neighbouring buttons
     * @param h the height of the buttons
     * @return the index of the button, or -1 if the point is on none
     */
    static int choose(float px, float py, float x, float y, float[] widths, float gap, float h) {
        float left = x;
        for (int i = 0; i < widths.length; i++) {
            if (hit(px, py, left, y, widths[i], h)) {
                return i;
            }
            left += widths[i] + gap;
        }
        return -1;
    }

    /**
     * Draws a slider and applies the mouse to it.
     *
     * @param hud the display; must not be {@code null}
     * @param in the input of the frame; must not be {@code null}
     * @param label the name of the control, which also identifies it; must not be {@code null}
     * @param valueText the value as text to show beside the name; must not be {@code null}
     * @param value the current value
     * @param min the value at the left end
     * @param max the value at the right end
     * @param x the left edge in pixels
     * @param y the top edge in pixels
     * @param width the width of the track in pixels
     * @return the new value, which is the old one unless the slider was dragged
     */
    public float slider(Hud hud, Input in, String label, String valueText, float value, float min, float max, float x, float y, float width) {
        int id = label.hashCode() | 1;
        float trackY = y + hud.lineHeight() + GAP;
        boolean over = hit(in.mouseX(), in.mouseY(), x - 4f, y, width + 8f, height(hud) - 6f);
        overNow |= over;
        if (over && in.mousePressed(GLFW_MOUSE_BUTTON_LEFT)) {
            dragging = id;
        }
        if (!in.mouseDown(GLFW_MOUSE_BUTTON_LEFT) && dragging == id) {
            dragging = 0;
        }
        float v = value;
        if (dragging == id) {
            v = drag(in.mouseX(), x, width, min, max);
        }
        hud.color(0.9f, 0.92f, 1f).text(x, y, label + ": " + valueText);
        float t = max == min ? 0f : (v - min) / (max - min);
        hud.bar(x, trackY, width, TRACK_HEIGHT, t, 0.35f, 0.65f, 1f);
        hud.rect(x + t * width - 3f, trackY - 3f, 6f, TRACK_HEIGHT + 6f, 1f, 1f, 1f, 1f);
        return v;
    }

    /**
     * Draws a checkbox with its label and applies a click to it.
     *
     * @param hud the display; must not be {@code null}
     * @param in the input of the frame; must not be {@code null}
     * @param label the text beside the box; must not be {@code null}
     * @param value whether the box is checked
     * @param x the left edge in pixels
     * @param y the top edge in pixels
     * @return the new value, flipped if the box or its label was clicked
     */
    public boolean checkbox(Hud hud, Input in, String label, boolean value, float x, float y) {
        float size = hud.lineHeight();
        float w = size + 8f + label.length() * hud.advance();
        boolean over = hit(in.mouseX(), in.mouseY(), x, y, w, size);
        overNow |= over;
        boolean v = value;
        if (over && in.mousePressed(GLFW_MOUSE_BUTTON_LEFT)) {
            v = !v;
        }
        hud.rect(x, y, size, size, 0f, 0f, 0f, 0.7f);
        hud.rect(x + 2f, y + 2f, size - 4f, size - 4f, 0.25f, 0.25f, 0.28f, 0.95f);
        if (v) {
            hud.rect(x + 4f, y + 4f, size - 8f, size - 8f, 0.35f, 0.9f, 0.45f, 1f);
        }
        hud.color(0.9f, 0.92f, 1f).text(x + size + 6f, y, label);
        return v;
    }

    /**
     * Draws a row of buttons of which one is selected, and applies a click to it.
     *
     * @param hud the display; must not be {@code null}
     * @param in the input of the frame; must not be {@code null}
     * @param labels the text of the buttons; must not be {@code null}
     * @param selected the index of the selected button
     * @param x the left edge of the first button in pixels
     * @param y the top edge in pixels
     * @return the index of the selected button, which is a new one if a button was clicked
     */
    public int choice(Hud hud, Input in, String[] labels, int selected, float x, float y) {
        float h = hud.lineHeight() + 4f;
        float[] widths = new float[labels.length];
        for (int i = 0; i < labels.length; i++) {
            widths[i] = labels[i].length() * hud.advance() + 12f;
        }
        int over = choose(in.mouseX(), in.mouseY(), x, y, widths, 4f, h);
        overNow |= over >= 0;
        int result = selected;
        if (over >= 0 && in.mousePressed(GLFW_MOUSE_BUTTON_LEFT)) {
            result = over;
        }
        float left = x;
        for (int i = 0; i < labels.length; i++) {
            boolean on = i == result;
            hud.rect(left, y, widths[i], h, on ? 0.2f : 0.1f, on ? 0.45f : 0.1f, on ? 0.9f : 0.12f, 0.85f);
            hud.color(1f, 1f, 1f).text(left + 6f, y + 2f, labels[i]);
            left += widths[i] + 4f;
        }
        return result;
    }

    /**
     * Ends the frame of controls: what the pointer was over becomes what {@link #capturing}
     * reports. A demo calls it once at the end of its controls, in its {@code hud} method.
     */
    public void endFrame() {
        overBefore = overNow;
        overNow = false;
    }
}
