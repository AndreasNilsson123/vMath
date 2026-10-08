package vmath.samples.verify;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Runs {@link GpuItMain} on the OpenGL driver of the machine: the table of GLSL features, the programs of the
 * generators, the culling shaders against their CPU references, the struct layouts against the reflection of
 * the driver, and the persistent ring with real fences. Skipped, not passed, when no window with an OpenGL
 * context can be opened (a machine without a display or a driver), so it proves nothing there.
 *
 * <p><b>Thread safety.</b> Opens a window: the tests of this class must not run in parallel with other tests
 * that do.
 */
class GpuItTest {

    @Test
    void theShadersAndBuffersDoOnTheDriverWhatTheLibraryExpects() {
        long window = 0;
        try {
            window = LineGpuCheck.openContext();
        } catch (Throwable noDriver) {
            // GLFW or LWJGL could not start: no display
        }
        Assumptions.assumeTrue(window != 0, "no OpenGL context could be opened");
        try {
            List<GpuItMain.Line> all = GpuItMain.runAll(null);
            assertTrue(all.size() >= 150, "the checks ran: " + all.size());
            for (GpuItMain.Line l : all) {
                assertTrue(l.ok(), l.name() + ": " + l.detail());
            }
        } finally {
            LineGpuCheck.closeContext(window);
        }
    }
}
