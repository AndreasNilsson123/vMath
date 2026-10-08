package vmath.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** {@link Declutter}: no overlap, priorities, candidates, mandatory items, stability and the batch hook-up. */
class DeclutterTest {

    private static boolean overlap(Declutter d, int i, int j, float[][] box, float pad) {
        float ax = box[i][0] + d.offsetX(i), ay = box[i][1] + d.offsetY(i), bx = box[j][0] + d.offsetX(j), by = box[j][1] + d.offsetY(j);
        return Math.abs(ax - bx) < 0.5f * (box[i][2] + box[j][2]) + pad - 1e-4f && Math.abs(ay - by) < 0.5f * (box[i][3] + box[j][3]) + pad - 1e-4f;
    }

    @Test
    void shownItemsNeverOverlapAndAreAsManyAsAGreedyPassCanKeep() {
        Random r = new Random(11);
        Declutter d = new Declutter(30f, 4);
        d.useRingCandidates(18f, 2);
        d.setPadding(3f);
        int n = 600;
        float[][] box = new float[n][4];
        d.begin();
        for (int i = 0; i < n; i++) {
            box[i] = new float[] {r.nextFloat() * 800f, r.nextFloat() * 600f, 10f + r.nextInt(30), 8f + r.nextInt(14)};
            d.add(i, box[i][0], box[i][1], box[i][2], box[i][3], r.nextInt(100), false);
        }
        int shown = d.solve();
        int counted = 0;
        for (int i = 0; i < n; i++) {
            if (d.shown(i)) {
                counted++;
            }
        }
        assertEquals(shown, counted);
        assertTrue(shown > 100 && shown < n, "a crowded screen shows some, not all: " + shown);
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (d.shown(i) && d.shown(j)) {
                    assertFalse(overlap(d, i, j, box, 3f), "items " + i + " and " + j + " overlap");
                }
            }
        }
        // greedy and complete: a hidden item has no free candidate among the shown ones
        for (int i = 0; i < n; i++) {
            if (!d.shown(i)) {
                for (int c = 0; c < d.candidateCount(); c++) {
                    boolean free = true;
                    for (int j = 0; j < n && free; j++) {
                        if (d.shown(j)) {
                            // candidate c of item i against the final place of j
                            float cx = box[i][0] + d.candidateX(c), cy = box[i][1] + d.candidateY(c);
                            float bx = box[j][0] + d.offsetX(j), by = box[j][1] + d.offsetY(j);
                            if (Math.abs(cx - bx) < 0.5f * (box[i][2] + box[j][2]) + 3f - 1e-4f && Math.abs(cy - by) < 0.5f * (box[i][3] + box[j][3]) + 3f - 1e-4f) {
                                free = false;
                            }
                        }
                    }
                    // the items placed before i are in the final set, and everything placed later avoided them and i was not placed: no candidate may be free
                    assertFalse(free, "hidden item " + i + " had a free candidate " + c);
                }
            }
        }
    }

    @Test
    void withOneCandidateTheMoreImportantItemWinsWhateverTheOrderOfAdding() {
        Declutter d = new Declutter(20f, 4);
        d.begin();
        int low = d.add(1, 100f, 100f, 20f, 20f, 1, false);
        int high = d.add(2, 105f, 100f, 20f, 20f, 9, false);
        assertEquals(1, d.solve());
        assertTrue(d.shown(high));
        assertFalse(d.shown(low));
        assertEquals(2, d.count());
    }

    @Test
    void candidatesAreTriedInOrder() {
        Declutter d = new Declutter(20f, 4);
        d.setCandidates(new float[] {0f, 30f, -30f}, new float[] {0f, 0f, 0f});
        d.setPadding(0f);
        d.begin();
        int a = d.add(1, 100f, 100f, 20f, 20f, 9, false);
        int b = d.add(2, 105f, 100f, 20f, 20f, 1, false);
        int c = d.add(3, 102f, 100f, 20f, 20f, 0, false);
        assertEquals(3, d.solve());
        assertEquals(0, d.chosen(a));
        assertEquals(1, d.chosen(b), "the second candidate: 30 pixels to the right");
        assertEquals(2, d.chosen(c));
        assertTrue(d.needsLeader(b));
        assertFalse(d.needsLeader(a));
        assertEquals(30f, d.offsetX(b));
        assertEquals(-30f, d.offsetX(c));
    }

    @Test
    void anItemWithNoFreeCandidateIsHiddenUnlessMandatory() {
        Declutter d = new Declutter(20f, 4);
        d.setPadding(0f);
        d.begin();
        int top = d.add(1, 50f, 50f, 40f, 40f, 5, false);
        int low = d.add(2, 55f, 50f, 10f, 10f, 1, false);
        int must = d.add(3, 52f, 52f, 10f, 10f, 0, true);
        d.solve();
        assertTrue(d.shown(must), "mandatory ones come first, then the others around them");
        assertFalse(d.shown(top), "the big one now collides with the mandatory one");
        assertFalse(d.shown(low));
    }

    @Test
    void aMandatoryItemIsShownEvenWhereNothingIsFree() {
        Declutter d = new Declutter(20f, 4);
        d.setPadding(0f);
        d.begin();
        int m1 = d.add(1, 50f, 50f, 20f, 20f, 0, true);
        int m2 = d.add(2, 50f, 50f, 20f, 20f, 0, true);
        assertEquals(2, d.solve());
        assertTrue(d.shown(m1) && d.shown(m2));
        assertEquals(0, d.chosen(m2));
    }

    @Test
    void thePaddingKeepsAGapAndTouchingIsNotOverlap() {
        Declutter d = new Declutter(20f, 4);
        d.setPadding(0f);
        d.begin();
        d.add(1, 0f, 0f, 10f, 10f, 5, false);
        d.add(2, 10f, 0f, 10f, 10f, 4, false);
        assertEquals(2, d.solve(), "edges that just touch are free");
        d.setPadding(4f);
        d.forget();
        d.begin();
        d.add(1, 0f, 0f, 10f, 10f, 5, false);
        d.add(2, 10f, 0f, 10f, 10f, 4, false);
        assertEquals(1, d.solve(), "a 4 pixel gap is now required");
        assertThrows(IllegalArgumentException.class, () -> d.setPadding(-1f));
    }

    @Test
    void theClipRectangleKeepsItemsInsideTheScreen() {
        Declutter d = new Declutter(20f, 4);
        d.setClip(0f, 0f, 100f, 100f);
        d.setCandidates(new float[] {0f, 20f}, new float[] {0f, 0f});
        d.begin();
        int edge = d.add(1, 95f, 50f, 20f, 20f, 1, false);     // sticks out by 5 pixels in place; the shifted candidate is worse
        int inside = d.add(2, 50f, 50f, 20f, 20f, 1, false);
        d.solve();
        assertFalse(d.shown(edge));
        assertTrue(d.shown(inside));
        d.clearClip();
        d.begin();
        d.add(1, 95f, 50f, 20f, 20f, 1, false);
        assertEquals(1, d.solve());
    }

    @Test
    void anItemKeepsItsCandidateFromFrameToFrameAndNoNewcomerOfEqualRankDisplacesIt() {
        Declutter d = new Declutter(20f, 4);
        d.setCandidates(new float[] {0f, 30f, -30f}, new float[] {0f, 0f, 0f});
        d.setPadding(0f);
        d.begin();
        d.add(1, 100f, 100f, 20f, 20f, 5, false);
        int old = d.add(2, 105f, 100f, 20f, 20f, 3, false);
        d.solve();
        assertEquals(1, d.chosen(old));
        // next frame, a newcomer of the same priority appears first in the list and wants the same place
        d.begin();
        d.add(1, 100f, 100f, 20f, 20f, 5, false);
        int newcomer = d.add(3, 107f, 100f, 20f, 20f, 3, false);
        int again = d.add(2, 105f, 100f, 20f, 20f, 3, false);
        d.solve();
        assertEquals(1, d.chosen(again), "the item shown before keeps its place");
        assertEquals(2, d.chosen(newcomer), "the newcomer takes what is left");
        // with no history the order of adding decides
        d.forget();
        d.begin();
        d.add(1, 100f, 100f, 20f, 20f, 5, false);
        int first = d.add(3, 107f, 100f, 20f, 20f, 3, false);
        d.add(2, 105f, 100f, 20f, 20f, 3, false);
        d.solve();
        assertEquals(1, d.chosen(first));
    }

    @Test
    void aPanningCrowdDoesNotFlicker() {
        Random r = new Random(3);
        int n = 300;
        float[][] at = new float[n][2];
        for (float[] p : at) {
            p[0] = r.nextFloat() * 800f;
            p[1] = r.nextFloat() * 600f;
        }
        Declutter d = new Declutter(30f, n);
        d.useRingCandidates(20f, 1);
        boolean[] was = new boolean[n];
        int changed = 0, total = 0;
        for (int frame = 0; frame < 20; frame++) {
            d.begin();
            for (int i = 0; i < n; i++) {
                d.add(i, at[i][0] + frame * 0.5f, at[i][1], 24f, 12f, 10 + i % 7, false);
            }
            d.solve();
            if (frame > 0) {
                for (int i = 0; i < n; i++) {
                    total++;
                    if (was[i] != d.shown(i)) {
                        changed++;
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                was[i] = d.shown(i);
            }
        }
        assertTrue(changed < total / 100, "a half pixel pan should change less than one percent of the decisions: " + changed + " of " + total);
    }

    @Test
    void theResultIsAppliedToTheSymbolsOfABatch() {
        SymbolBatch batch = new SymbolBatch(4);
        float[] uv = {0f, 0f, 1f, 1f};
        for (int i = 0; i < 3; i++) {
            batch.add(i, i, 0.0, 20f, uv, 0xFFFFFFFF, 0);
        }
        Declutter d = new Declutter(20f, 4);
        d.setCandidates(new float[] {0f, 0f, 0f}, new float[] {0f, 25f, 50f});
        d.setPadding(0f);
        d.begin();
        d.add(0, 100f, 100f, 20f, 20f, 9, false);
        d.add(1, 100f, 100f, 20f, 20f, 8, false);
        d.add(2, 100f, 100f, 20f, 20f, 7, false);
        d.solve();
        d.apply(batch, 0, true);
        assertEquals(0f, batch.offset(0, 1));
        assertEquals(-25f, batch.offset(1, 1), "window y grows downwards: a candidate in +y goes down, which is -y on the symbol");
        assertEquals(-50f, batch.offset(2, 1));
        assertEquals(0, batch.flags(1) & SymbolGpu.HIDDEN);
        d.begin();
        d.add(0, 100f, 100f, 20f, 20f, 9, false);
        d.add(1, 100f, 100f, 20f, 20f, 8, false);
        d.setCandidates(new float[] {0f}, new float[] {0f});
        d.solve();
        d.apply(batch, 0, false);
        assertEquals(SymbolGpu.HIDDEN, batch.flags(1) & SymbolGpu.HIDDEN);
        assertThrows(IndexOutOfBoundsException.class, () -> d.chosen(5));
    }

    @Test
    void badArgumentsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Declutter(0f, 1));
        Declutter d = new Declutter(10f, 1);
        assertThrows(IllegalArgumentException.class, () -> d.setCandidates(new float[0], new float[0]));
        assertThrows(IllegalArgumentException.class, () -> d.setCandidates(new float[1], new float[2]));
        assertThrows(IllegalArgumentException.class, () -> d.add(0, Float.NaN, 0f, 1f, 1f, 0, false));
        assertThrows(IllegalArgumentException.class, () -> d.add(0, 0f, 0f, -1f, 1f, 0, false));
        assertThrows(IllegalArgumentException.class, () -> d.useRingCandidates(0f, 1));
        assertThrows(IllegalArgumentException.class, () -> d.setClip(1f, 1f, 1f, 2f));
        assertEquals(1, d.candidateCount());
    }
}
