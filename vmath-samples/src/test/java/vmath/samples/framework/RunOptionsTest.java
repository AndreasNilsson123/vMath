package vmath.samples.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests of the command-line parsing of the launcher.
 *
 * <p><b>Thread safety.</b> Each test builds its own options; the tests may run in parallel.
 */
class RunOptionsTest {

    @Test
    void defaultsOpenTheMenuInteractively() {
        RunOptions o = RunOptions.parse(new String[0]);
        assertTrue(o.demos().isEmpty());
        assertEquals(1600, o.width());
        assertEquals(900, o.height());
        assertEquals(0, o.frames());
        assertTrue(o.vsync());
        assertTrue(o.hud());
        assertFalse(o.checkGl());
    }

    @Test
    void scriptedRunsTurnVsyncOffUnlessAsked() {
        assertFalse(RunOptions.parse(new String[] {"--demo", "city", "--frames", "10"}).vsync());
        assertTrue(RunOptions.parse(new String[] {"--demo", "city", "--frames", "10", "--vsync"}).vsync());
    }

    @Test
    void demosCanBeNamedSeveralWays() {
        RunOptions o = RunOptions.parse(new String[] {"--demo", "a", "--sequence", "b, c,", "--demo", "d"});
        assertEquals(List.of("a", "b", "c", "d"), o.demos());
    }

    @Test
    void unknownArgumentsGoToTheDemoWithTheirValues() {
        RunOptions o = RunOptions.parse(new String[] {"--demo", "city", "--instances", "5000", "--no-cull", "--frames", "3", "--threads", "4"});
        assertEquals(List.of("--instances", "5000", "--no-cull", "--threads", "4"), o.demoArgs());
        assertEquals(3, o.frames());
    }

    @Test
    void smokeChecksGlErrorsAndKeepsTheWarmupLongEnoughForTheJit() {
        RunOptions o = RunOptions.parse(new String[] {"--smoke"});
        assertTrue(o.smoke());
        assertTrue(o.checkGl());
        assertFalse(o.vsync());
        assertEquals(60, o.warmup());
    }

    @Test
    void framesNeedADemo() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> RunOptions.parse(new String[] {"--frames", "10"}));
        assertTrue(e.getMessage().contains("--demo"));
    }

    @Test
    void badValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RunOptions.parse(new String[] {"--width"}));
        assertThrows(IllegalArgumentException.class, () -> RunOptions.parse(new String[] {"--width", "wide"}));
        assertThrows(IllegalArgumentException.class, () -> RunOptions.parse(new String[] {"--width", "0"}));
        assertThrows(IllegalArgumentException.class, () -> RunOptions.parse(new String[] {"--demo", "x", "--frames", "-1"}));
    }

    @Test
    void parsesTheFilesAndFlags() {
        RunOptions o = RunOptions.parse(new String[] {"--help", "--list", "--no-hud", "--check-gl", "--screenshot", "a.png", "--report", "r.md", "--warmup", "5"});
        assertTrue(o.help());
        assertTrue(o.list());
        assertFalse(o.hud());
        assertTrue(o.checkGl());
        assertEquals("a.png", o.screenshot());
        assertEquals("r.md", o.report());
        assertEquals(5, o.warmup());
    }
}
