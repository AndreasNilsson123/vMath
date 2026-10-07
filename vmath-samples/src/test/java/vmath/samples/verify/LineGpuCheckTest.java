package vmath.samples.verify;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Runs {@link LineGpuCheck} on the OpenGL driver of the machine: every strategy at every GLSL
 * version from 3.30 up to the driver's, compiled, linked, drawn and compared with the reference of
 * {@code vmath.lines}. Skipped, not passed, when no window with an OpenGL 4.3 context can be
 * opened (a machine without a display or a driver), so it proves nothing there.
 *
 * <p><b>Thread safety.</b> Opens a window: the tests of this class must not run in parallel with
 * other tests that do.
 */
class LineGpuCheckTest {

    @Test
    void everyLineStrategyDrawsWhatTheReferenceDrawsOnTheDriver() {
        long window = 0;
        try {
            window = LineGpuCheck.openContext();
        } catch (Throwable noDriver) {
            // GLFW or LWJGL could not start: no display
        }
        Assumptions.assumeTrue(window != 0, "no OpenGL context could be opened");
        try {
            List<LineGpuCheck.Outcome> results = LineGpuCheck.checkMatrix(null);
            assertTrue(results.size() >= 60, "the matrix ran: " + results.size() + " cases");
            for (LineGpuCheck.Outcome o : results) {
                assertTrue(o.ok(), o.name() + ": " + o.detail());
            }
        } finally {
            LineGpuCheck.closeContext(window);
        }
    }
}
