package vmath.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FrameTimerTest {

    private static final long MS = 1_000_000L;

    @Test
    void theFirstTickOnlyStartsTheClock() {
        FrameTimer t = new FrameTimer(10);
        t.tick(5 * MS);
        assertEquals(0, t.frames());
        assertTrue(Double.isNaN(t.fps()) && Double.isNaN(t.medianMillis()) && Double.isNaN(t.lowFps(1)));
        assertEquals(0, t.hitches(2));
        t.tick(21 * MS);
        assertEquals(1, t.frames());
        assertEquals(16.0, t.averageMillis(), 1e-12);
        assertEquals(62.5, t.fps(), 1e-9);
    }

    @Test
    void aSmoothRunAndARunWithStallsHaveTheSameMeanButNotTheSameLowFps() {
        FrameTimer smooth = new FrameTimer(200), stalls = new FrameTimer(200);
        for (int i = 0; i < 200; i++) {
            smooth.record(10 * MS);
            stalls.record(i % 50 == 49 ? 59 * MS : 9 * MS); // 4 stalls of 59 ms among 9 ms frames: the mean is 10.0 ms as well
        }
        assertEquals(10.0, smooth.averageMillis(), 1e-9);
        assertEquals(10.0, stalls.averageMillis(), 1e-9);
        assertEquals(100.0, smooth.fps(), 1e-9);
        assertEquals(100.0, stalls.fps(), 1e-9);
        assertEquals(100.0, smooth.lowFps(1), 1e-9);
        assertEquals(1000.0 / 59, stalls.lowFps(1), 1e-9, "the 1% low sees the 59 ms stalls");
        assertEquals(9.0, stalls.medianMillis(), 1e-9);
        assertEquals(59.0, stalls.maxMillis(), 1e-9);
        assertEquals(9.0, stalls.minMillis(), 1e-9);
        assertEquals(4, stalls.hitches(2), "four frames took more than twice the median");
        assertEquals(0, smooth.hitches(1.5));
    }

    @Test
    void theWindowForgetsOldFrames() {
        FrameTimer t = new FrameTimer(4);
        for (int i = 0; i < 4; i++) {
            t.record(100 * MS);
        }
        for (int i = 0; i < 4; i++) {
            t.record(10 * MS);
        }
        assertEquals(4, t.frames());
        assertEquals(8, t.totalFrames());
        assertEquals(10.0, t.averageMillis(), 1e-12);
        t.reset();
        assertEquals(0, t.frames());
        t.tick(1);
        assertEquals(0, t.frames(), "reset forgets the clock too");
        assertEquals(0, t.stats().size());
    }

    @Test
    void theRealClockWorksAndBadInputIsRefused() {
        FrameTimer t = new FrameTimer(8);
        t.tick();
        t.tick();
        assertEquals(1, t.frames());
        assertTrue(t.averageMillis() >= 0);
        assertThrows(IllegalArgumentException.class, () -> new FrameTimer(0));
        assertThrows(IllegalArgumentException.class, () -> t.record(-1));
        FrameTimer manual = new FrameTimer(8);
        manual.tick(100);
        assertThrows(IllegalArgumentException.class, () -> manual.tick(99));
        assertThrows(IllegalArgumentException.class, () -> t.lowFps(0));
        assertThrows(IllegalArgumentException.class, () -> t.lowFps(101));
        assertThrows(IllegalArgumentException.class, () -> t.hitches(1));
        assertThrows(IllegalArgumentException.class, () -> t.percentileMillis(101));
    }
}
