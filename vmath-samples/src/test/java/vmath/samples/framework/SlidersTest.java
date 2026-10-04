package vmath.samples.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

import org.junit.jupiter.api.Test;

/**
 * Tests of the hit tests and the value mapping of the mouse controls, and of the mouse click edge
 * of the input, none of which needs a window.
 *
 * <p><b>Thread safety.</b> Each test builds its own values; the tests may run in parallel.
 */
class SlidersTest {

    @Test
    void aPointIsInsideARectangleIncludingItsEdges() {
        assertTrue(Sliders.hit(10f, 10f, 10f, 10f, 5f, 5f));
        assertTrue(Sliders.hit(15f, 15f, 10f, 10f, 5f, 5f));
        assertTrue(Sliders.hit(12f, 12f, 10f, 10f, 5f, 5f));
        assertFalse(Sliders.hit(9.9f, 12f, 10f, 10f, 5f, 5f));
        assertFalse(Sliders.hit(12f, 15.1f, 10f, 10f, 5f, 5f));
    }

    @Test
    void theDragMapsTheMouseToTheRangeAndClampsOutsideTheTrack() {
        assertEquals(0f, Sliders.drag(100f, 100f, 200f, 0f, 24f), 1e-6f);
        assertEquals(12f, Sliders.drag(200f, 100f, 200f, 0f, 24f), 1e-6f);
        assertEquals(24f, Sliders.drag(300f, 100f, 200f, 0f, 24f), 1e-6f);
        assertEquals(0f, Sliders.drag(-50f, 100f, 200f, 0f, 24f), 1e-6f);
        assertEquals(24f, Sliders.drag(9999f, 100f, 200f, 0f, 24f), 1e-6f);
        assertEquals(-85f + 170f * 0.25f, Sliders.drag(150f, 100f, 200f, -85f, 85f), 1e-4f);
    }

    @Test
    void aRowOfButtonsFindsTheOneUnderThePointerAndNoneInTheGaps() {
        float[] widths = {40f, 60f, 40f};
        assertEquals(0, Sliders.choose(10f, 5f, 0f, 0f, widths, 4f, 20f));
        assertEquals(-1, Sliders.choose(42f, 5f, 0f, 0f, widths, 4f, 20f), "in the gap");
        assertEquals(1, Sliders.choose(60f, 5f, 0f, 0f, widths, 4f, 20f));
        assertEquals(2, Sliders.choose(130f, 5f, 0f, 0f, widths, 4f, 20f));
        assertEquals(-1, Sliders.choose(10f, 25f, 0f, 0f, widths, 4f, 20f), "below the buttons");
        assertEquals(-1, Sliders.choose(500f, 5f, 0f, 0f, widths, 4f, 20f));
    }

    @Test
    void aMouseButtonIsPressedForOneFrameAfterItWentDown() {
        Input in = new Input();
        in.poll(0L);
        assertFalse(in.mousePressed(GLFW_MOUSE_BUTTON_LEFT));
        in.onMouse(GLFW_MOUSE_BUTTON_LEFT, true);
        assertTrue(in.mouseDown(GLFW_MOUSE_BUTTON_LEFT));
        assertTrue(in.mousePressed(GLFW_MOUSE_BUTTON_LEFT), "down now and not down at the previous poll");
        in.poll(0L);
        assertTrue(in.mouseDown(GLFW_MOUSE_BUTTON_LEFT));
        assertFalse(in.mousePressed(GLFW_MOUSE_BUTTON_LEFT), "still down, no longer a new press");
        in.onMouse(GLFW_MOUSE_BUTTON_LEFT, false);
        in.poll(0L);
        assertFalse(in.mouseDown(GLFW_MOUSE_BUTTON_LEFT));
        assertFalse(in.mousePressed(GLFW_MOUSE_BUTTON_LEFT));
    }

    @Test
    void theControlsDoNotCaptureTheMouseBeforeAnythingWasDrawn() {
        Sliders s = new Sliders();
        assertFalse(s.capturing());
        s.endFrame();
        assertFalse(s.capturing());
    }
}
