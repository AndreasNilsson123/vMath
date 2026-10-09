package vmath.samples.demos.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests of the options of the rigid-pile demo.
 *
 * <p><b>Thread safety.</b> Each test builds its own values; the tests may run in parallel.
 */
class PileOptionsTest {

    @Test
    void defaults() {
        PileOptions o = PileOptions.parse(List.of());
        assertEquals(1000, o.bodies());
        assertEquals(4, o.perFrame());
        assertFalse(o.contacts());
        assertFalse(o.verify());
        assertTrue(o.warmStart());
    }

    @Test
    void parsesAllOptions() {
        PileOptions o = PileOptions.parse(List.of("--bodies", "50", "--per-frame", "7", "--contacts", "--verify", "--no-warm-start"));
        assertEquals(50, o.bodies());
        assertEquals(7, o.perFrame());
        assertTrue(o.contacts() && o.verify());
        assertFalse(o.warmStart());
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> PileOptions.parse(List.of("--bodies", "0")));
        assertThrows(IllegalArgumentException.class, () -> PileOptions.parse(List.of("--bodies")));
        assertThrows(IllegalArgumentException.class, () -> PileOptions.parse(List.of("--what")));
    }
}
