package vmath.gpucull;

import vmath.Report;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;
import vmath.geo.DepthRange;

class HiZPyramidTest {

    private final Random rnd = new Random(Long.getLong("vmath.seed", 2026L));

    /** A depth image of random opaque rectangles, each at one depth in [0, 1) (conventional, near = 0), the nearest one winning. Cleared to the far value 1. */
    private float[] randomScene(int w, int h, int rects) {
        float[] depth = new float[w * h];
        java.util.Arrays.fill(depth, 1f);
        for (int r = 0; r < rects; r++) {
            int x0 = rnd.nextInt(w), y0 = rnd.nextInt(h), x1 = Math.min(w, x0 + 1 + rnd.nextInt(Math.max(1, w / 2))), y1 = Math.min(h, y0 + 1 + rnd.nextInt(Math.max(1, h / 2)));
            float d = rnd.nextFloat();
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    depth[y * w + x] = Math.min(depth[y * w + x], d);
                }
            }
        }
        return depth;
    }

    private static float convert(float conventional, DepthRange range) {
        return switch (range) {
            case ZERO_TO_ONE -> conventional;
            case NEGATIVE_ONE_TO_ONE -> conventional * 2f - 1f;
            case REVERSED_ZERO_TO_ONE -> 1f - conventional;
        };
    }

    @Test
    void everyTexelIsTheFarthestOfThePixelsItCovers() {
        for (int[] size : new int[][] {{64, 64}, {100, 37}, {1, 1}, {7, 1}, {1, 9}, {255, 256}, {33, 65}}) {
            int w = size[0], h = size[1];
            float[] depth = randomScene(w, h, 12);
            HiZPyramid p = HiZPyramid.fromDepth(depth, w, h, DepthRange.ZERO_TO_ONE, false);
            assertEquals(vmath.occlusion.HiZ.mipCount(w, h), p.levels());
            assertEquals(1, p.width(p.levels() - 1));
            assertEquals(1, p.height(p.levels() - 1));
            for (int l = 0; l < p.levels(); l++) {
                int cover = 1 << l;
                assertEquals(vmath.occlusion.HiZ.mipSize(w, l), p.width(l));
                for (int y = 0; y < p.height(l); y++) {
                    for (int x = 0; x < p.width(l); x++) {
                        float expected = Float.NEGATIVE_INFINITY;
                        for (int py = y * cover; py < Math.min(h, (y + 1) * cover); py++) {
                            for (int px = x * cover; px < Math.min(w, (x + 1) * cover); px++) {
                                expected = Math.max(expected, depth[py * w + px]);
                            }
                        }
                        assertEquals(expected, p.at(l, x, y), 0f, size[0] + "x" + size[1] + " level " + l + " texel " + x + "," + y);
                    }
                }
            }
        }
    }

    @Test
    void theThreeDepthConventionsGiveTheSamePyramid() {
        int w = 50, h = 33;
        float[] conventional = randomScene(w, h, 10);
        HiZPyramid reference = HiZPyramid.fromDepth(conventional, w, h, DepthRange.ZERO_TO_ONE, false);
        for (DepthRange range : DepthRange.values()) {
            float[] converted = new float[conventional.length];
            for (int i = 0; i < converted.length; i++) {
                converted[i] = convert(conventional[i], range);
            }
            HiZPyramid p = HiZPyramid.fromDepth(converted, w, h, range, false);
            for (int l = 0; l < p.levels(); l++) {
                for (int i = 0; i < p.level(l).length; i++) {
                    assertEquals(reference.level(l)[i], p.level(l)[i], 1e-6f, range + " level " + l + " texel " + i);
                }
            }
        }
    }

    /** Exact test with no pyramid: hidden when every pixel the rectangle touches is nearer than the object's nearest point. */
    private static boolean exactlyHidden(float[] farness, int w, int h, boolean yDown, float minX, float minY, float maxX, float maxY, float nearest) {
        float x0 = Math.max(minX, -1f), x1 = Math.min(maxX, 1f), y0 = Math.max(minY, -1f), y1 = Math.min(maxY, 1f);
        int px0 = clamp((int) Math.floor((x0 * 0.5f + 0.5f) * w), w), px1 = clamp((int) Math.floor((x1 * 0.5f + 0.5f) * w), w);
        float ya = yDown ? 0.5f - y1 * 0.5f : y0 * 0.5f + 0.5f, yb = yDown ? 0.5f - y0 * 0.5f : y1 * 0.5f + 0.5f;
        int py0 = clamp((int) Math.floor(ya * h), h), py1 = clamp((int) Math.floor(yb * h), h);
        for (int y = py0; y <= py1; y++) {
            for (int x = px0; x <= px1; x++) {
                if (!(farness[y * w + x] < nearest)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int clamp(int v, int n) {
        return v < 0 ? 0 : Math.min(v, n - 1);
    }

    @Test
    void aHiddenAnswerIsAlwaysRightAndMostHiddenObjectsAreFound() {
        long hiddenByPyramid = 0, hiddenExactly = 0, wrong = 0;
        for (boolean yDown : new boolean[] {false, true}) {
            for (int[] size : new int[][] {{64, 64}, {100, 37}, {128, 72}, {33, 65}}) {
                int w = size[0], h = size[1];
                for (int scene = 0; scene < 8; scene++) {
                    float[] depth = randomScene(w, h, 25);
                    HiZPyramid p = HiZPyramid.fromDepth(depth, w, h, DepthRange.ZERO_TO_ONE, yDown);
                    for (int t = 0; t < 4000; t++) {
                        float cx = rnd.nextFloat() * 2.4f - 1.2f, cy = rnd.nextFloat() * 2.4f - 1.2f;
                        float hw = (float) Math.exp(Math.log(0.005) + rnd.nextDouble() * (Math.log(1.5) - Math.log(0.005))), hh = (float) Math.exp(Math.log(0.005) + rnd.nextDouble() * (Math.log(1.5) - Math.log(0.005)));
                        float nearest = rnd.nextFloat();
                        boolean pyramid = p.isHidden(cx - hw, cy - hh, cx + hw, cy + hh, nearest);
                        boolean exact = cx + hw >= -1f && cx - hw <= 1f && cy + hh >= -1f && cy - hh <= 1f
                                && exactlyHidden(depth, w, h, yDown, cx - hw, cy - hh, cx + hw, cy + hh, nearest);
                        if (pyramid) {
                            hiddenByPyramid++;
                            if (!exact) {
                                wrong++;
                            }
                        }
                        if (exact) {
                            hiddenExactly++;
                        }
                    }
                }
            }
        }
        Report.printf("HIZ rectangles hidden exactly %d, found by the pyramid %d (%.1f%%), pyramid answers that were wrong %d%n", hiddenExactly, hiddenByPyramid,
                100.0 * hiddenByPyramid / hiddenExactly, wrong);
        assertEquals(0, wrong, "the pyramid must never hide an object that has a pixel showing something as far or farther");
        assertTrue(hiddenExactly > 20000, "enough hidden cases in the sample: " + hiddenExactly);
        assertTrue(hiddenByPyramid >= hiddenExactly * 0.4, "a guard, not a promise: the pyramid found 49.5% of the exactly hidden rectangles on these scenes, and must not fall far below that: " + hiddenByPyramid + " of " + hiddenExactly);
    }

    @Test
    void edgeCases() {
        float[] depth = new float[16 * 16];
        java.util.Arrays.fill(depth, 0.3f);
        HiZPyramid p = HiZPyramid.fromDepth(depth, 16, 16, DepthRange.ZERO_TO_ONE, false);
        assertTrue(p.isHidden(-0.5f, -0.5f, 0.5f, 0.5f, 0.31f), "behind a uniform wall");
        assertFalse(p.isHidden(-0.5f, -0.5f, 0.5f, 0.5f, 0.3f), "exactly as far is kept (strict comparison)");
        assertFalse(p.isHidden(-0.5f, -0.5f, 0.5f, 0.5f, 0.29f), "in front of the wall");
        assertFalse(p.isHidden(Float.NaN, 0f, 0.5f, 0.5f, 0.9f), "NaN is not hidden");
        assertFalse(p.isHidden(0f, 0f, 0.5f, 0.5f, Float.NaN));
        assertFalse(p.isHidden(2f, 2f, 3f, 3f, 0.9f), "wholly off screen");
        assertFalse(p.isHidden(0.5f, 0.5f, 0.2f, 0.7f, 0.9f), "an inverted rectangle");
        assertTrue(p.isHidden(-5f, -5f, 5f, 5f, 0.99f), "a rectangle larger than the screen is clamped to it");
        // a nonfinite depth makes its texels (and every parent) the farthest possible: nothing can be hidden behind them
        depth[5] = Float.NaN;
        HiZPyramid q = HiZPyramid.fromDepth(depth, 16, 16, DepthRange.ZERO_TO_ONE, false);
        assertFalse(q.isHidden(-1f, -1f, 1f, 1f, 0.99f), "a NaN texel in the whole-screen rectangle keeps the object");
        assertThrows(IllegalArgumentException.class, () -> HiZPyramid.fromDepth(new float[3], 4, 4, DepthRange.ZERO_TO_ONE, false));
        HiZPyramid one = HiZPyramid.fromDepth(new float[] {0.5f}, 1, 1, DepthRange.REVERSED_ZERO_TO_ONE, false);
        assertEquals(1, one.levels());
        assertEquals(0.5f, one.at(0, 0, 0), 0f, "reversed 0.5 is 0.5 farness");
        assertTrue(one.isHidden(-1f, -1f, 1f, 1f, 0.6f));
    }
}
