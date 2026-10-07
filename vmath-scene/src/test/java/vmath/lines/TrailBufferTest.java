package vmath.lines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.gl.GraphicsCapabilities;

/**
 * {@link TrailBuffer}: the ring of positions, the removal of old ones, and the polylines that fade
 * with age in a {@link LineSet}.
 */
class TrailBufferTest {

    private static LineSet newSet() {
        return new LineSet(LineRenderPlan.force(LineStrategy.INSTANCED_LOOP, GraphicsCapabilities.openGl(4, 6, List.of())), 1000);
    }

    private static final LineStyle STYLE = LineStyle.pixels(2f).withColor(0x00FF00FF);

    @Test
    void theRingKeepsTheNewestPositionsInOrder() {
        TrailBuffer trails = new TrailBuffer(3, 5, 4);
        int a = trails.addTrack();
        for (int i = 0; i < 12; i++) {
            trails.push(a, i, 2 * i, 0, i * 0.5);
        }
        assertEquals(5, trails.size(a));
        for (int i = 0; i < 5; i++) {
            assertEquals(7 + i, trails.coordinate(a, i, 0), "oldest first");
            assertEquals(2 * (7 + i), trails.coordinate(a, i, 1));
            assertEquals((7 + i) * 0.5, trails.time(a, i));
        }
        double[] out = new double[15];
        assertEquals(5, trails.copyPoints(a, out));
        assertEquals(7, out[0]);
        assertEquals(11, out[12]);
        assertEquals(2, trails.dropOlderThan(a, 4.2), "the points at 3.5 and 4.0 go");
        assertEquals(3, trails.size(a));
        assertEquals(9, trails.coordinate(a, 0, 0));
        trails.push(a, 100, 0, 0, 100);
        assertEquals(4, trails.size(a));
        assertEquals(100, trails.coordinate(a, 3, 0));
        trails.clear(a);
        assertEquals(0, trails.size(a));
        trails.push(a, 1, 1, 1, 1);
        assertEquals(1, trails.size(a));
    }

    @Test
    void tracksAreNumberedAndReused() {
        TrailBuffer trails = new TrailBuffer(2, 4, 2);
        int a = trails.addTrack(), b = trails.addTrack();
        assertTrue(a != b);
        assertThrows(IllegalStateException.class, trails::addTrack);
        trails.push(a, 1, 1, 1, 0);
        trails.removeTrack(a);
        assertThrows(IllegalArgumentException.class, () -> trails.push(a, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> trails.size(a));
        int c = trails.addTrack();
        assertEquals(a, c);
        assertEquals(0, trails.size(c), "a reused track starts empty");
        assertEquals(2, trails.trackCount());
    }

    @Test
    void badInputIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new TrailBuffer(0, 4, 2));
        assertThrows(IllegalArgumentException.class, () -> new TrailBuffer(1, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> new TrailBuffer(1, 4, 0));
        TrailBuffer trails = new TrailBuffer(1, 4, 2);
        int t = trails.addTrack();
        trails.push(t, 0, 0, 0, 5);
        assertThrows(IllegalArgumentException.class, () -> trails.push(t, 0, 0, 0, 4), "time goes forward");
        assertThrows(IllegalArgumentException.class, () -> trails.push(t, Double.NaN, 0, 0, 6));
        assertThrows(IndexOutOfBoundsException.class, () -> trails.coordinate(t, 1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> trails.coordinate(t, 0, 3));
        assertThrows(IllegalArgumentException.class, () -> trails.copyPoints(t, new double[2]));
        assertThrows(IndexOutOfBoundsException.class, () -> trails.alphaOfSlice(2));
        assertThrows(IllegalArgumentException.class, () -> trails.updateLines(newSet(), t, 6, 0, STYLE));
    }

    private static int alpha(LineBatch batch, int polyline) {
        return batch.style(batch.styleIndexOf(polyline)).color() & 255;
    }

    @Test
    void aTrailBecomesConnectedPolylinesThatFade() {
        TrailBuffer trails = new TrailBuffer(2, 32, 4);
        LineSet set = newSet();
        int t = trails.addTrack();
        for (int i = 0; i < 20; i++) {
            trails.push(t, i, i % 3, 0, i);
        }
        assertEquals(4, trails.updateLines(set, t, 20, 20, STYLE));
        assertEquals(4, set.size());
        LineBatch batch = set.toBatch();
        int points = 0;
        for (int p = 0; p < batch.polylineCount(); p++) {
            points += batch.pointCount(p);
        }
        assertEquals(20 + 3, points, "the 20 points, and the 3 that neighbouring pieces share");
        // the pieces come in the order they were added: newest (most opaque) first
        int previous = 256;
        for (int p = 0; p < batch.polylineCount(); p++) {
            assertTrue(alpha(batch, p) < previous, "older pieces are more transparent");
            previous = alpha(batch, p);
        }
        assertEquals(255, alpha(batch, 0));
        assertEquals(64, alpha(batch, 3), "the oldest of four slices keeps a quarter");
        // the pieces meet: the last point of one is the first of the next
        for (int p = 0; p + 1 < batch.polylineCount(); p++) {
            for (int axis = 0; axis < 3; axis++) {
                assertEquals(batch.coordinate(p, batch.pointCount(p) - 1, axis), batch.coordinate(p + 1, 0, axis));
            }
        }
        // nothing older than maxAge is drawn
        assertEquals(4, trails.updateLines(set, t, 30, 20, STYLE), "at the later time, ages 0.55 to 1.0 remain");
        batch = set.toBatch();
        double oldest = Double.MAX_VALUE;
        for (int p = 0; p < batch.polylineCount(); p++) {
            for (int i = 0; i < batch.pointCount(p); i++) {
                oldest = Math.min(oldest, batch.coordinate(p, i, 0));
            }
        }
        assertEquals(10, oldest, "the point with age exactly 1 is drawn and the older ones are not");
        assertEquals(0, trails.updateLines(set, t, 1000, 20, STYLE));
        assertEquals(0, set.size(), "a trail that has aged out leaves nothing");
    }

    @Test
    void aStationaryTrackOrAFewPointsDrawNothingAndRaiseNothing() {
        TrailBuffer trails = new TrailBuffer(1, 8, 3);
        LineSet set = newSet();
        int t = trails.addTrack();
        assertEquals(0, trails.updateLines(set, t, 0, 10, STYLE));
        trails.push(t, 5, 5, 5, 0);
        assertEquals(0, trails.updateLines(set, t, 0, 10, STYLE), "one point");
        trails.push(t, 5, 5, 5, 1);
        trails.push(t, 5, 5, 5, 2);
        assertEquals(0, trails.updateLines(set, t, 2, 10, STYLE), "no movement");
        trails.push(t, 9, 5, 5, 3);
        assertTrue(trails.updateLines(set, t, 3, 10, STYLE) > 0);
        assertTrue(set.size() > 0);
    }

    @Test
    void updatesReuseThePolylinesAndReleaseRemovesThem() {
        TrailBuffer trails = new TrailBuffer(2, 32, 4);
        LineSet set = newSet();
        int t = trails.addTrack(), u = trails.addTrack();
        for (int i = 0; i < 20; i++) {
            trails.push(t, i, 0, 0, i);
            trails.push(u, 0, i, 0, i);
        }
        trails.updateLines(set, t, 20, 20, STYLE);
        trails.updateLines(set, u, 20, 20, STYLE.withWidth(5f));
        int size = set.size();
        assertEquals(8, size);
        for (int frame = 0; frame < 10; frame++) {
            trails.push(t, 20 + frame, 0, 0, 20 + frame);
            trails.updateLines(set, t, 20 + frame, 20, STYLE);
            assertEquals(size, set.size(), "the same polylines are rewritten, none leaks");
        }
        trails.releaseLines(set, t);
        assertEquals(4, set.size());
        assertFalse(set.size() == 0);
        trails.releaseLines(set, u);
        assertEquals(0, set.size());
        trails.updateLines(set, u, 20, 20, STYLE);
        assertEquals(4, set.size(), "a released track can be drawn again");
    }

    @Test
    void changingTheBaseStyleRestylesTheExistingPolylines() {
        TrailBuffer trails = new TrailBuffer(1, 32, 2);
        LineSet set = newSet();
        int t = trails.addTrack();
        for (int i = 0; i < 10; i++) {
            trails.push(t, i, i, 0, i);
        }
        trails.updateLines(set, t, 10, 10, STYLE);
        trails.updateLines(set, t, 10, 10, STYLE.withColor(0xFF0000FF));
        LineBatch batch = set.toBatch();
        for (int p = 0; p < batch.polylineCount(); p++) {
            assertEquals(0xFF0000, batch.style(batch.styleIndexOf(p)).color() >>> 8);
        }
        assertEquals(2, set.styleCount(), "the old styles were released");
    }
}
