package vmath.samples.framework;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_LAST;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LAST;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.glfwGetCursorPos;
import static org.lwjgl.glfw.GLFW.glfwGetMouseButton;
import static org.lwjgl.glfw.GLFW.glfwSetKeyCallback;
import static org.lwjgl.glfw.GLFW.glfwSetScrollCallback;

/**
 * The keyboard and the mouse as a demo sees them: which keys are down, which went down in this
 * frame, how far the mouse moved and how far the wheel turned.
 *
 * <p>The state changes only in {@link #poll}, which the runner calls once per frame after it has
 * pumped the window's events, so every method answers the same for the whole frame. The class
 * does not use OpenGL; {@link #onKey} and {@link #onMouse} feed it from tests.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: it is fed and read by the render thread, which is the
 * one that pumps the events.
 */
public final class Input {

    private final boolean[] down = new boolean[GLFW_KEY_LAST + 1];
    private final boolean[] pressed = new boolean[GLFW_KEY_LAST + 1];
    private final boolean[] pendingPressed = new boolean[GLFW_KEY_LAST + 1];
    private final boolean[] buttons = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    private final double[] cursorX = new double[1];
    private final double[] cursorY = new double[1];
    private double lastX;
    private double lastY;
    private boolean haveCursor;
    private float mouseDx;
    private float mouseDy;
    private float pendingScroll;
    private float scroll;

    /**
     * Creates an input with nothing pressed.
     */
    public Input() {
    }

    /**
     * Installs the key and scroll callbacks on a window so that the events reach this object.
     *
     * @param window the GLFW window handle
     */
    void attach(long window) {
        glfwSetKeyCallback(window, (w, key, scancode, action, mods) -> onKey(key, action));
        glfwSetScrollCallback(window, (w, xoffset, yoffset) -> pendingScroll += (float) yoffset);
    }

    /**
     * Records a key event, as the window's key callback does.
     *
     * @param key the GLFW key code; codes that are not keys (negative) are ignored
     * @param action {@code GLFW_PRESS}, {@code GLFW_RELEASE} or {@code GLFW_REPEAT}
     */
    void onKey(int key, int action) {
        if (key < 0 || key > GLFW_KEY_LAST) {
            return;
        }
        if (action == GLFW_PRESS) {
            if (!down[key]) {
                pendingPressed[key] = true;
            }
            down[key] = true;
        } else if (action == GLFW_RELEASE) {
            down[key] = false;
        }
    }

    /**
     * Records the state of a mouse button and the movement of the cursor, as {@link #poll} does
     * from the window.
     *
     * @param button the GLFW button code
     * @param isDown whether the button is down
     */
    void onMouse(int button, boolean isDown) {
        buttons[button] = isDown;
    }

    /**
     * Makes the events since the last call visible: the keys that went down, the wheel, and the
     * mouse movement and buttons.
     *
     * @param window the GLFW window handle, or 0 to leave the mouse as it is (tests)
     */
    void poll(long window) {
        System.arraycopy(pendingPressed, 0, pressed, 0, pressed.length);
        java.util.Arrays.fill(pendingPressed, false);
        scroll = pendingScroll;
        pendingScroll = 0f;
        if (window != 0L) {
            glfwGetCursorPos(window, cursorX, cursorY);
            for (int b = 0; b < buttons.length; b++) {
                buttons[b] = glfwGetMouseButton(window, b) == GLFW_PRESS;
            }
            if (haveCursor) {
                mouseDx = (float) (cursorX[0] - lastX);
                mouseDy = (float) (cursorY[0] - lastY);
            } else {
                mouseDx = 0f;
                mouseDy = 0f;
                haveCursor = true;
            }
            lastX = cursorX[0];
            lastY = cursorY[0];
        }
    }

    /**
     * Tells whether a key is held down.
     *
     * @param key the GLFW key code, such as {@code GLFW_KEY_W}
     * @return {@code true} while the key is down
     */
    public boolean down(int key) {
        return key >= 0 && key <= GLFW_KEY_LAST && down[key];
    }

    /**
     * Tells whether a key went down since the previous frame, which is what a toggle wants.
     *
     * @param key the GLFW key code
     * @return {@code true} in the one frame in which the key was pressed
     */
    public boolean pressed(int key) {
        return key >= 0 && key <= GLFW_KEY_LAST && pressed[key];
    }

    /**
     * Reads two keys as one axis.
     *
     * @param positive the key that moves the axis to {@code +1}
     * @param negative the key that moves it to {@code -1}
     * @return 1 if only the positive key is down, -1 if only the negative one is, otherwise 0
     */
    public float axis(int positive, int negative) {
        return (down(positive) ? 1f : 0f) - (down(negative) ? 1f : 0f);
    }

    /**
     * Tells whether a mouse button is held down.
     *
     * @param button the GLFW button code, such as {@code GLFW_MOUSE_BUTTON_LEFT}
     * @return {@code true} while the button is down
     */
    public boolean mouseDown(int button) {
        return button >= 0 && button < buttons.length && buttons[button];
    }

    /**
     * Reads the horizontal movement of the cursor since the previous frame.
     *
     * @return the movement to the right in pixels
     */
    public float mouseDx() {
        return mouseDx;
    }

    /**
     * Reads the vertical movement of the cursor since the previous frame.
     *
     * @return the movement down in pixels
     */
    public float mouseDy() {
        return mouseDy;
    }

    /**
     * Reads the horizontal position of the cursor.
     *
     * @return the position in window coordinates, 0 at the left edge
     */
    public float mouseX() {
        return (float) lastX;
    }

    /**
     * Reads the vertical position of the cursor.
     *
     * @return the position in window coordinates, 0 at the top edge
     */
    public float mouseY() {
        return (float) lastY;
    }

    /**
     * Reads how far the mouse wheel turned since the previous frame.
     *
     * @return the turn in notches, positive away from the user
     */
    public float scroll() {
        return scroll;
    }
}
