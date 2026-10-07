package vmath.lines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import vmath.gl.DrawList;
import vmath.gl.GraphicsCapabilities;

/**
 * {@link LineSet}: handles, the free space, the dirty ranges, and above all that after any
 * sequence of additions, edits, removals, compactions and growth the buffers, copied to a "GPU"
 * buffer through the dirty ranges alone, draw what the reference expansion of the same polylines
 * draws, in every strategy.
 */
class LineSetTest {

    private static final int SIZE = 200;
    private static final GraphicsCapabilities GL46 = GraphicsCapabilities.openGl(4, 6, List.of());
    private static final LineStrategy[] ALL = LineStrategy.values();

    private final Random rnd = new Random(Long.getLong("vmath.seed", 7L));

    private static final LineStyle[] STYLES = {
            LineStyle.pixels(3f).withColor(0xFF0000FF),
            LineStyle.pixels(9f).withColor(0x00FF00FF).withCap(LineStyle.Cap.ROUND).withJoin(LineStyle.Join.ROUND),
            LineStyle.pixels(5f).withColor(0x0000FFFF).withCap(LineStyle.Cap.SQUARE).withJoin(LineStyle.Join.BEVEL).withDash(12f, 7f),
            LineStyle.pixels(2f).withColor(0xFF00FFFF).withLayer(-1),
            LineStyle.pixels(7f).withColor(0x00FFFFFF).withLayer(1).withJoin(LineStyle.Join.ROUND)};

    private static MemorySegment buffer(long bytes) {
        return MemorySegment.ofArray(new byte[(int) Math.max(bytes, 16)]);
    }

    private double[] randomPoints(int n) {
        double[] xyz = new double[3 * n];
        for (int k = 0; k < n; k++) {
            xyz[3 * k] = 20 + rnd.nextInt(160);
            xyz[3 * k + 1] = 20 + rnd.nextInt(160);
        }
        return xyz;
    }

    private static float[] orthographic() {
        float w = SIZE;
        return new float[] {2f / w, 0, 0, 0, 0, 2f / w, 0, 0, 0, 0, 1f, 0, -1f, -1f, 0f, 1f};
    }

    private static CoverageRaster fill(java.util.function.Consumer<LineGeometry.Sink> producer) {
        CoverageRaster r = new CoverageRaster(SIZE, SIZE);
        producer.accept((x0, y0, a0, x1, y1, a1, x2, y2, a2, dash, dashCount, color) -> r.fill(x0, y0, a0, x1, y1, a1, x2, y2, a2, dash, dashCount));
        return r;
    }

    /** A mirror, the GPU copy fed through the dirty ranges, the styles and the draws of one set. */
    private static final class Rig {
        final LineRenderPlan plan;
        final LineSet set;
        MemorySegment mirror;
        MemorySegment gpu;
        MemorySegment styles;
        final DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 4);
        long uploaded;

        Rig(LineStrategy strategy, int capacity) {
            plan = LineRenderPlan.force(strategy, GL46);
            set = new LineSet(plan, capacity);
            allocate();
        }

        void allocate() {
            mirror = buffer(set.dataBytes());
            gpu = buffer(set.dataBytes());
            styles = buffer(Math.max(set.styleBytes(), 16) + 64 * 64L);
        }

        void update() {
            if (set.dataBytes() > mirror.byteSize()) {
                MemorySegment newMirror = buffer(set.dataBytes()), newGpu = buffer(set.dataBytes());
                mirror = newMirror;
                gpu = newGpu;
                set.invalidate();
            }
            if (set.styleBytes() > styles.byteSize()) {
                styles = buffer(set.styleBytes() * 2);
            }
            set.update(mirror, styles, draws);
            for (int i = 0; i < set.dirtyRangeCount(); i++) {
                MemorySegment.copy(mirror, set.dirtyOffset(i), gpu, set.dirtyOffset(i), set.dirtyLength(i));
                uploaded += set.dirtyLength(i);
            }
        }
    }

    private void assertDrawsLikeTheReference(Rig rig, String what) {
        LineBatch reference = rig.set.toBatch();
        CoverageRaster expected = fill(sink -> LineExpander.expand(reference, orthographic(), SIZE, SIZE, 1f, sink));
        if (rig.plan.strategy() == LineStrategy.HAIRLINE) {
            int[] lines = new int[1];
            LineShaderModel.evaluateHairlines(rig.plan, rig.gpu, rig.draws, (x0, y0, z0, c0, x1, y1, z1, c1, draw) -> lines[0]++);
            int expectedLines = 0;
            for (int p = 0; p < reference.polylineCount(); p++) {
                expectedLines += reference.segmentCount() == 0 ? 0 : reference.pointCount(p) - 1 + (reference.isClosed(p) ? 1 : 0);
            }
            assertEquals(expectedLines, lines[0], what + ": the strips hold the segments of the polylines");
            return;
        }
        CoverageRaster got = fill(sink -> LineShaderModel.evaluate(rig.plan, rig.gpu, rig.styles, rig.draws, orthographic(), SIZE, SIZE, 1f, sink));
        assertEquals(0, expected.differences(got), what + " (" + rig.plan.strategy() + "): samples that differ from the reference");
    }

    // ---------------------------------------------------------------- handles

    @Test
    void handlesAreValidUntilRemovedAndNeverComeBack() {
        LineSet set = new LineSet(LineRenderPlan.force(LineStrategy.INSTANCED_LOOP, GL46), 100);
        long a = set.add(randomPoints(3), 0, 3, false, STYLES[0]);
        assertNotEquals(LineSet.NONE, a);
        assertTrue(set.contains(a));
        assertTrue(set.remove(a));
        assertFalse(set.contains(a));
        assertFalse(set.remove(a), "a second removal reports false");
        assertThrows(IllegalArgumentException.class, () -> set.set(a, randomPoints(2), 0, 2, false));
        long b = set.add(randomPoints(3), 0, 3, false, STYLES[0]);
        assertNotEquals(a, b, "the slot is reused, the handle is not");
        assertFalse(set.contains(a));
        assertTrue(set.contains(b));
        assertFalse(set.contains(LineSet.NONE));
        assertThrows(IllegalArgumentException.class, () -> set.setStyle(LineSet.NONE, STYLES[1]));
        assertEquals(1, set.size());
    }

    @Test
    void badInputChangesNothing() {
        LineSet set = new LineSet(LineRenderPlan.force(LineStrategy.INSTANCED_LOOP, GL46), 10);
        long a = set.add(new double[] {0, 0, 0, 5, 5, 0, 9, 1, 0}, 0, 3, false, STYLES[0]);
        long used = set.usedUnits();
        assertThrows(IllegalArgumentException.class, () -> set.add(new double[] {1, 1, 0, 1, 1, 0}, 0, 2, false, STYLES[0]), "one distinct point");
        assertThrows(IllegalArgumentException.class, () -> set.add(new double[] {Double.NaN, 1, 0, 1, 1, 0}, 0, 2, false, STYLES[0]));
        assertThrows(IllegalArgumentException.class, () -> set.set(a, new double[] {1, 1, 0}, 0, 1, false));
        assertThrows(IllegalArgumentException.class, () -> set.set(a, new double[] {1, 1, 0}, 0, 5, false), "array too short");
        assertEquals(used, set.usedUnits());
        assertEquals(3, set.pointCount(a));
        assertThrows(IllegalStateException.class, () -> set.add(randomPoints(12), 0, 12, false, STYLES[0]), "11 segments in a set of 10");
        assertThrows(IllegalStateException.class, () -> set.set(a, randomPoints(12), 0, 12, false));
        assertEquals(used, set.usedUnits());
        assertEquals(3, set.pointCount(a), "a refused edit leaves the polyline as it was");
        assertEquals(1, set.size());
    }

    // ---------------------------------------------------------------- space

    @Test
    void aFullSetCompactsByItselfAndThenAsksForGrowth() {
        Rig rig = new Rig(LineStrategy.INSTANCED_LOOP, 12);
        long[] h = new long[4];
        for (int i = 0; i < 4; i++) {
            h[i] = rig.set.add(randomPoints(4), 0, 4, false, STYLES[0]); // 3 segments each: 12 in all
        }
        assertThrows(IllegalStateException.class, () -> rig.set.add(randomPoints(2), 0, 2, false, STYLES[0]));
        rig.set.remove(h[0]);
        rig.set.remove(h[2]);
        assertEquals(3, rig.set.largestFreeUnits(), "two holes of 3");
        long big = rig.set.add(randomPoints(6), 0, 6, false, STYLES[0]);      // 5 segments: fits only after compaction
        assertTrue(rig.set.contains(big));
        assertEquals(11, rig.set.usedUnits());
        rig.update();
        assertDrawsLikeTheReference(rig, "after the automatic compaction");
        assertThrows(IllegalStateException.class, () -> rig.set.add(randomPoints(4), 0, 4, false, STYLES[0]));
        rig.set.grow(40);
        long more = rig.set.add(randomPoints(4), 0, 4, false, STYLES[0]);
        assertTrue(rig.set.contains(more));
        rig.update();
        assertDrawsLikeTheReference(rig, "after growth");
        assertThrows(IllegalArgumentException.class, () -> rig.set.grow(40));
    }

    @Test
    void compactionJoinsTheRunsOfEqualStyleIntoFewerDraws() {
        Rig rig = new Rig(LineStrategy.INDIRECT_INSTANCE_STYLE, 200);
        List<Long> handles = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            handles.add(rig.set.add(randomPoints(3), 0, 3, false, STYLES[0]));
        }
        rig.update();
        assertEquals(1, rig.draws.size(), "adjacent polylines of one style are one draw");
        for (int i = 0; i < 20; i += 2) {
            rig.set.remove(handles.get(i));
        }
        rig.update();
        assertEquals(10, rig.draws.size(), "the holes split the run");
        rig.set.compact();
        rig.update();
        assertEquals(1, rig.draws.size());
        assertEquals(1, rig.set.largestFreeUnits() > 0 ? 1 : 0);
        assertDrawsLikeTheReference(rig, "after compaction");
    }

    // ---------------------------------------------------------------- dirty ranges

    @Test
    void onlyWhatChangedIsReported() {
        for (LineStrategy strategy : ALL) {
            Rig rig = new Rig(strategy, 1000);
            List<Long> handles = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                handles.add(rig.set.add(randomPoints(5), 0, 5, false, STYLES[i % 2]));
            }
            rig.update();
            long unit = rig.plan.unitBytes();
            assertEquals(1, rig.set.dirtyRangeCount(), strategy + ": the first update writes the adjacent new polylines as one range");
            assertEquals(30 * rig.plan.unitsOf(5, false) * unit, rig.set.dirtyLength(0));
            rig.update();
            assertEquals(0, rig.set.dirtyRangeCount(), strategy + ": nothing changed");
            rig.set.set(handles.get(7), randomPoints(5), 0, 5, false);
            rig.update();
            assertEquals(1, rig.set.dirtyRangeCount());
            assertEquals(rig.plan.unitsOf(5, false) * unit, rig.set.dirtyLength(0), strategy + ": one polyline was edited in place");
            rig.set.set(handles.get(3), randomPoints(5), 0, 5, false);
            rig.set.set(handles.get(20), randomPoints(5), 0, 5, false);
            rig.update();
            assertEquals(2, rig.set.dirtyRangeCount(), strategy + ": two separate edits are two ranges");
            assertTrue(rig.set.dirtyOffset(0) < rig.set.dirtyOffset(1), "sorted");
            rig.set.remove(handles.get(10));
            rig.update();
            assertEquals(0, rig.set.dirtyRangeCount(), strategy + ": a removal writes nothing");
            rig.set.setOrigin(1000, 0, 0);
            rig.update();
            assertEquals(2, rig.set.dirtyRangeCount(), strategy + ": a new origin rewrites everything: two ranges, the hole of the removed polyline between them");
            assertEquals(29 * rig.plan.unitsOf(5, false) * unit, rig.set.dirtyLength(0) + rig.set.dirtyLength(1));
            assertThrows(IndexOutOfBoundsException.class, () -> rig.set.dirtyOffset(5));
        }
    }

    // ---------------------------------------------------------------- the same pixels in every strategy

    @Test
    void anySequenceOfChangesDrawsWhatTheReferenceDraws() {
        for (LineStrategy strategy : ALL) {
            Rig rig = new Rig(strategy, 120);
            List<Long> handles = new ArrayList<>();
            for (int step = 0; step < 400; step++) {
                int op = rnd.nextInt(10);
                if (op < 3 || handles.isEmpty()) {
                    int n = 2 + rnd.nextInt(5);
                    try {
                        handles.add(rig.set.add(randomPoints(n), 0, n, n >= 3 && rnd.nextInt(3) == 0, STYLES[rnd.nextInt(STYLES.length)]));
                    } catch (IllegalStateException full) {
                        rig.set.grow(rig.set.capacityUnits() + 60);
                    } catch (IllegalArgumentException collapsed) {
                        // duplicates left too few distinct points
                    }
                } else if (op < 5) {
                    int i = rnd.nextInt(handles.size());
                    int n = 2 + rnd.nextInt(6);
                    try {
                        rig.set.set(handles.get(i), randomPoints(n), 0, n, n >= 3 && rnd.nextInt(3) == 0);
                    } catch (IllegalStateException full) {
                        rig.set.grow(rig.set.capacityUnits() + 60);
                    } catch (IllegalArgumentException collapsed) {
                        // too few distinct points: nothing changed
                    }
                } else if (op < 7) {
                    rig.set.remove(handles.remove(rnd.nextInt(handles.size())));
                } else if (op < 8) {
                    rig.set.setStyle(handles.get(rnd.nextInt(handles.size())), STYLES[rnd.nextInt(STYLES.length)]);
                } else if (op < 9 && rnd.nextInt(4) == 0) {
                    rig.set.compact();
                }
                if (step % 7 == 0) {
                    rig.update();
                    if (rig.set.size() > 0) {
                        assertDrawsLikeTheReference(rig, "step " + step);
                    }
                }
            }
            rig.update();
            assertEquals(handles.size(), rig.set.size());
            assertDrawsLikeTheReference(rig, "at the end");
            assertTrue(rig.uploaded > 0);
        }
    }

    @Test
    void aFarOriginIsHandledByTheSetToo() {
        Rig rig = new Rig(LineStrategy.EXPANDED_MULTIDRAW, 100);
        double shift = 4_000_000.0;
        double[] pts = {20, 30, 0, 150, 60, 0, 90, 170, 0};
        double[] shifted = pts.clone();
        for (int i = 0; i < shifted.length; i += 3) {
            shifted[i] += shift;
            shifted[i + 1] += shift;
        }
        rig.set.add(shifted, 0, 3, false, STYLES[1]);
        rig.set.setOrigin(shift, shift, 0);
        assertFalse(rig.set.originTooFar(shift + 10, shift, 0, 1000));
        assertTrue(rig.set.originTooFar(0, 0, 0, 1000));
        rig.update();
        double w = SIZE;
        double[] world = {2 / w, 0, 0, 0, 0, 2 / w, 0, 0, 0, 0, 1, 0, -1 - 2 * shift / w, -1 - 2 * shift / w, 0, 1};
        float[] relative = new float[16];
        rig.set.relativeViewProjection(world, relative);
        LineBatch near = new LineBatch();
        near.addPolyline(pts, 0, 3, false, STYLES[1]);
        CoverageRaster a = fill(sink -> LineExpander.expand(near, orthographic(), SIZE, SIZE, 1f, sink));
        CoverageRaster b = fill(sink -> LineShaderModel.evaluate(rig.plan, rig.gpu, rig.styles, rig.draws, relative, SIZE, SIZE, 1f, sink));
        assertTrue(a.area() > 100);
        assertEquals(0, a.differences(b));
    }

    // ---------------------------------------------------------------- styles and order

    @Test
    void layersOrderThePolylinesAndAStyleChangeMovesOneBetweenLayers() {
        LineSet set = new LineSet(LineRenderPlan.force(LineStrategy.INSTANCED_LOOP, GL46), 100);
        long low = set.add(randomPoints(3), 0, 3, false, STYLES[3]);      // layer -1
        long mid1 = set.add(randomPoints(3), 0, 3, false, STYLES[0]);     // layer 0
        long high = set.add(randomPoints(3), 0, 3, false, STYLES[4]);     // layer 1
        long mid2 = set.add(randomPoints(3), 0, 3, false, STYLES[0]);
        assertEquals(3, set.styleCount(), "equal styles share an entry");
        LineBatch b = set.toBatch();
        assertEquals(4, b.polylineCount());
        assertEquals(-1, b.style(b.styleIndexOf(b.drawOrder()[0])).layer());
        assertEquals(1, b.style(b.styleIndexOf(b.drawOrder()[3])).layer());
        set.setStyle(high, STYLES[0]);
        b = set.toBatch();
        for (int p : b.drawOrder()) {
            assertTrue(b.style(b.styleIndexOf(p)).layer() <= 0);
        }
        assertEquals(2, set.styleCount(), "the style nobody uses is released");
        set.remove(low);
        set.remove(mid1);
        assertEquals(1, set.styleCount());
        assertTrue(set.contains(mid2) && set.contains(high));
    }

    @Test
    void boundsFollowEdits() {
        LineSet set = new LineSet(LineRenderPlan.force(LineStrategy.INSTANCED_LOOP, GL46), 100);
        long h = set.add(new double[] {1, 2, 3, 4, 6, 9}, 0, 2, false, STYLES[0]);
        double[] b = new double[6];
        set.bounds(h, b);
        assertEquals(1, b[0]);
        assertEquals(9, b[5]);
        set.set(h, new double[] {-5, 0, 0, 10, 20, 30, 7, 7, 7}, 0, 3, false);
        set.bounds(h, b);
        assertEquals(-5, b[0]);
        assertEquals(20, b[4]);
        assertEquals(30, b[5]);
    }
}
