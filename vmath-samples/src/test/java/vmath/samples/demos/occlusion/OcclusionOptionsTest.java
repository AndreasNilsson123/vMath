package vmath.samples.demos.occlusion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests of the options of the occlusion demo.
 *
 * <p><b>Thread safety.</b> Each test builds its own values; the tests may run in parallel.
 */
class OcclusionOptionsTest {

    @Test
    void defaults() {
        OcclusionOptions o = OcclusionOptions.parse(List.of());
        assertEquals(100, o.blocks());
        assertEquals(40, o.props());
        assertEquals(256, o.depthWidth());
        assertFalse(o.verify());
        assertTrue(o.occlusion());
        assertEquals(1, o.threads());
    }

    @Test
    void parsesAllOptions() {
        OcclusionOptions o = OcclusionOptions.parse(List.of("--blocks", "10", "--props", "5", "--depth", "128", "--verify", "--no-occlusion", "--threads", "3"));
        assertEquals(10, o.blocks());
        assertEquals(5, o.props());
        assertEquals(128, o.depthWidth());
        assertTrue(o.verify());
        assertFalse(o.occlusion());
        assertEquals(3, o.threads());
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> OcclusionOptions.parse(List.of("--nope")));
        assertThrows(IllegalArgumentException.class, () -> OcclusionOptions.parse(List.of("--blocks", "2")));
        assertThrows(IllegalArgumentException.class, () -> OcclusionOptions.parse(List.of("--depth", "8")));
        assertThrows(IllegalArgumentException.class, () -> OcclusionOptions.parse(List.of("--threads", "0")));
        assertThrows(IllegalArgumentException.class, () -> OcclusionOptions.parse(List.of("--props")));
    }
}
