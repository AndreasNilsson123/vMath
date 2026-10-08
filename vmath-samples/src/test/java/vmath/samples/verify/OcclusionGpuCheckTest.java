package vmath.samples.verify;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Runs {@link OcclusionGpuCheck} on the OpenGL driver of the machine: the coherent culling against
 * real occlusion queries, compared with the image of drawing everything. Skipped, not passed, when
 * no window with an OpenGL context can be opened (a machine without a display or a driver), so it
 * proves nothing there.
 *
 * <p><b>Thread safety.</b> Opens a window: the tests of this class must not run in parallel with
 * other tests that do.
 */
class OcclusionGpuCheckTest {

    @Test
    void theCoherentCullingDrawsTheImageOfEverythingOnTheDriver() {
        long window = 0;
        try {
            window = LineGpuCheck.openContext();
        } catch (Throwable noDriver) {
            // GLFW or LWJGL could not start: no display
        }
        Assumptions.assumeTrue(window != 0, "no OpenGL context could be opened");
        try {
            List<OcclusionGpuCheck.Outcome> results = OcclusionGpuCheck.check(null);
            assertTrue(results.size() >= 3, "the scenes ran");
            for (OcclusionGpuCheck.Outcome o : results) {
                assertTrue(o.ok(), o.name() + ": " + o.detail());
            }
        } finally {
            LineGpuCheck.closeContext(window);
        }
    }
}
