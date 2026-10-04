package vmath.samples.demos.culling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests of the options of the culling lab.
 *
 * <p><b>Thread safety.</b> Each test builds its own values; the tests may run in parallel.
 */
class CullingLabOptionsTest {

    @Test
    void defaults() {
        CullingLabOptions o = CullingLabOptions.parse(List.of());
        assertEquals(250_000, o.instances());
        assertNull(o.method());
        assertEquals(4, o.threads());
        assertEquals(120, o.segment());
        assertFalse(o.verify());
        assertFalse(o.animate());
        assertFalse(o.showBvh());
    }

    @Test
    void parsesAllOptions() {
        CullingLabOptions o = CullingLabOptions.parse(List.of("--instances", "1000", "--method", "octree", "--threads", "2", "--segment", "9", "--verify", "--animate", "--show-bvh"));
        assertEquals(1000, o.instances());
        assertEquals("octree", o.method());
        assertEquals(2, o.threads());
        assertEquals(9, o.segment());
        assertTrue(o.verify() && o.animate() && o.showBvh());
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> CullingLabOptions.parse(List.of("--nope")));
        assertThrows(IllegalArgumentException.class, () -> CullingLabOptions.parse(List.of("--method")));
        assertThrows(IllegalArgumentException.class, () -> CullingLabOptions.parse(List.of("--segment", "0")));
        assertThrows(IllegalArgumentException.class, () -> CullingLabOptions.parse(List.of("--instances", "lots")));
    }
}
