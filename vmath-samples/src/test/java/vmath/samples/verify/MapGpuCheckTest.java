package vmath.samples.verify;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Runs {@link MapGpuCheck} on the OpenGL driver of the machine: every map shader strategy at every
 * GLSL version from 3.30 up to the driver's, compiled, linked, drawn and compared with the CPU
 * models of {@code vmath.map}. Skipped, not passed, when no window with an OpenGL 4.3 context can be
 * opened (a machine without a display or a driver), so it proves nothing there.
 *
 * <p><b>Thread safety.</b> Opens a window: the tests of this class must not run in parallel with
 * other tests that do.
 */
class MapGpuCheckTest {

    @Test
    void everyMapShaderDrawsWhatItsModelDrawsOnTheDriver() {
        long window = 0;
        try {
            window = LineGpuCheck.openContext();
        } catch (Throwable noDriver) {
            // GLFW or LWJGL could not start: no display
        }
        Assumptions.assumeTrue(window != 0, "no OpenGL context could be opened");
        try {
            List<MapGpuCheck.Outcome> results = MapGpuCheck.checkAll(null);
            assertTrue(results.size() >= 25, "the matrix ran: " + results.size() + " cases");
            for (MapGpuCheck.Outcome o : results) {
                assertTrue(o.ok(), o.name() + ": " + o.detail());
            }
        } finally {
            LineGpuCheck.closeContext(window);
        }
    }
}
