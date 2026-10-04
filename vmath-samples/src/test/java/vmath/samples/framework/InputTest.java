package vmath.samples.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_D;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.GLFW_REPEAT;

import org.junit.jupiter.api.Test;

/**
 * Tests of the keyboard and mouse state, fed by hand instead of by a window.
 *
 * <p><b>Thread safety.</b> Each test builds its own input; the tests may run in parallel.
 */
class InputTest {

    @Test
    void aKeyIsPressedForOneFrameAndDownUntilReleased() {
        Input in = new Input();
        in.onKey(GLFW_KEY_A, GLFW_PRESS);
        assertFalse(in.pressed(GLFW_KEY_A), "the edge is visible only after poll");
        in.poll(0L);
        assertTrue(in.down(GLFW_KEY_A));
        assertTrue(in.pressed(GLFW_KEY_A));
        in.poll(0L);
        assertTrue(in.down(GLFW_KEY_A));
        assertFalse(in.pressed(GLFW_KEY_A));
        in.onKey(GLFW_KEY_A, GLFW_RELEASE);
        in.poll(0L);
        assertFalse(in.down(GLFW_KEY_A));
    }

    @Test
    void repeatEventsDoNotCountAsNewPresses() {
        Input in = new Input();
        in.onKey(GLFW_KEY_A, GLFW_PRESS);
        in.poll(0L);
        in.onKey(GLFW_KEY_A, GLFW_REPEAT);
        in.onKey(GLFW_KEY_A, GLFW_PRESS);
        in.poll(0L);
        assertFalse(in.pressed(GLFW_KEY_A));
    }

    @Test
    void aPressAndReleaseInsideOneFrameStillCountsAsPressed() {
        Input in = new Input();
        in.onKey(GLFW_KEY_A, GLFW_PRESS);
        in.onKey(GLFW_KEY_A, GLFW_RELEASE);
        in.poll(0L);
        assertTrue(in.pressed(GLFW_KEY_A));
        assertFalse(in.down(GLFW_KEY_A));
    }

    @Test
    void axisReadsTwoKeysAsOneDirection() {
        Input in = new Input();
        assertEquals(0f, in.axis(GLFW_KEY_D, GLFW_KEY_A));
        in.onKey(GLFW_KEY_D, GLFW_PRESS);
        assertEquals(1f, in.axis(GLFW_KEY_D, GLFW_KEY_A));
        in.onKey(GLFW_KEY_A, GLFW_PRESS);
        assertEquals(0f, in.axis(GLFW_KEY_D, GLFW_KEY_A));
        in.onKey(GLFW_KEY_D, GLFW_RELEASE);
        assertEquals(-1f, in.axis(GLFW_KEY_D, GLFW_KEY_A));
    }

    @Test
    void invalidKeysAreIgnored() {
        Input in = new Input();
        in.onKey(-1, GLFW_PRESS);
        in.onKey(100_000, GLFW_PRESS);
        in.poll(0L);
        assertFalse(in.down(-1));
        assertFalse(in.pressed(100_000));
    }

    @Test
    void mouseButtonsFollowTheEvents() {
        Input in = new Input();
        assertFalse(in.mouseDown(GLFW_MOUSE_BUTTON_LEFT));
        in.onMouse(GLFW_MOUSE_BUTTON_LEFT, true);
        assertTrue(in.mouseDown(GLFW_MOUSE_BUTTON_LEFT));
        assertFalse(in.mouseDown(99));
    }
}
