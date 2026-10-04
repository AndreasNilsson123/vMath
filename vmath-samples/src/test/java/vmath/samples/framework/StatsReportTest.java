package vmath.samples.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests of the statistics and of the report that formats them.
 *
 * <p><b>Thread safety.</b> Each test builds its own statistics; the tests may run in parallel.
 */
class StatsReportTest {

    @Test
    void averagesCountOnlyMeasuredFrames() {
        Stats stats = new Stats();
        int s = stats.timer("cull", "frustum");
        stats.record(s, 100.0);
        assertEquals(100.0, stats.last(s));
        assertEquals(0, stats.samples(s));
        assertTrue(Double.isNaN(stats.average(s)));
        stats.setMeasuring(true);
        stats.recordNanos(s, 2_000_000L);
        stats.recordNanos(s, 4_000_000L);
        assertEquals(2, stats.samples(s));
        assertEquals(3.0, stats.average(s), 1e-12);
        assertEquals(4.0, stats.last(s), 1e-12);
    }

    @Test
    void seriesGrowPastTheInitialCapacity() {
        Stats stats = new Stats();
        stats.setMeasuring(true);
        for (int i = 0; i < 40; i++) {
            int s = stats.series("s" + i, "", 0, "series " + i);
            assertEquals(i, s);
            stats.record(s, i);
        }
        for (int i = 0; i < 40; i++) {
            assertEquals(i, stats.average(i), 0.0);
            assertEquals("s" + i, stats.name(i));
        }
        assertEquals(7, stats.indexOf("s7"));
        assertEquals(-1, stats.indexOf("missing"));
    }

    @Test
    void textLeavesOutSeriesWithoutSamplesAndAddsNotes() {
        Stats stats = new Stats();
        stats.setMeasuring(true);
        int a = stats.series("visible", "", 0, "instances");
        stats.series("gpu", "ms", 3, "never recorded");
        stats.record(a, 1234567.0);
        stats.note("stalls: 0");
        String text = Report.text(stats);
        assertTrue(text.contains("visible"));
        assertTrue(text.contains("1,234,567"));
        assertFalse(text.contains("gpu"));
        assertTrue(text.contains("stalls: 0"));
    }

    @Test
    void markdownIsATableWithTheNotesBelow() {
        Stats stats = new Stats();
        stats.setMeasuring(true);
        stats.record(stats.series("frame", "ms", 3, "wall time"), 9.5);
        stats.note("culling on");
        String md = Report.markdown("city: 10 frames", stats);
        assertTrue(md.startsWith("### city: 10 frames"));
        assertTrue(md.contains("| frame | 9.500 | ms | wall time |"));
        assertTrue(md.contains("- culling on"));
    }
}
