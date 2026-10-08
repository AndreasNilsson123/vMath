package vmath.occlusion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** The helpers of {@link HiZ} for a pyramid in an OpenGL texture: the power-of-two base, the farthest resampling and the depth conversion. */
class HiZContractsTest {

    @Test
    void theBaseIsTheNextPowerOfTwo() {
        int[][] cases = {{1, 1}, {2, 2}, {3, 4}, {4, 4}, {5, 8}, {900, 1024}, {1024, 1024}, {1025, 2048}, {1600, 2048}, {(1 << 30), (1 << 30)}};
        for (int[] c : cases) {
            assertEquals(c[1], HiZ.baseSize(c[0]), "base of " + c[0]);
        }
        assertThrows(IllegalArgumentException.class, () -> HiZ.baseSize(0));
        assertThrows(IllegalArgumentException.class, () -> HiZ.baseSize((1 << 30) + 1));
    }

    @Test
    void openGlHalvesRoundingDownSoTheLibrarysLevelCountFitsOnlyAPowerOfTwoBase() {
        // the sizes of the chain that OpenGL makes: floor(dim / 2) per level, down to 1
        for (int dim : new int[] {1600, 900, 1024, 2048, 25, 7}) {
            int glLevels = 1;
            for (int d = dim; d > 1; d /= 2) {
                glLevels++;
            }
            boolean power = Integer.bitCount(dim) == 1;
            assertEquals(power, HiZ.mipCount(dim, dim) == glLevels, "dim " + dim + ": library " + HiZ.mipCount(dim, dim) + " levels, OpenGL " + glLevels);
            // and on the base that HiZ.baseSize gives they agree
            int base = HiZ.baseSize(dim);
            int levels = 1;
            for (int d = base; d > 1; d /= 2) {
                levels++;
            }
            assertEquals(levels, HiZ.mipCount(base, base), "base " + base);
            for (int level = 0; level < levels; level++) {
                assertEquals(Math.max(1, base >> level), HiZ.mipSize(base, level), "base " + base + " level " + level);
            }
        }
    }

    @Test
    void resamplingKeepsTheFarthestValueUnderEveryTexel() {
        Random rnd = new Random(4);
        int w = 13, h = 7, dw = 16, dh = 8;
        float[] src = new float[w * h];
        for (int i = 0; i < src.length; i++) {
            src[i] = rnd.nextFloat();
        }
        for (boolean larger : new boolean[] {true, false}) {
            float[] dst = new float[dw * dh];
            HiZ.resampleFarthest(src, w, h, dst, dw, dh, larger);
            // brute force: the pixels whose area overlaps the texel
            for (int y = 0; y < dh; y++) {
                for (int x = 0; x < dw; x++) {
                    float best = larger ? -1f : 2f;
                    for (int sy = 0; sy < h; sy++) {
                        for (int sx = 0; sx < w; sx++) {
                            boolean overlaps = sx * (double) dw < (x + 1.0) * w && (sx + 1.0) * dw > x * (double) w && sy * (double) dh < (y + 1.0) * h && (sy + 1.0) * dh > y * (double) h;
                            if (overlaps) {
                                best = larger ? Math.max(best, src[sy * w + sx]) : Math.min(best, src[sy * w + sx]);
                            }
                        }
                    }
                    assertEquals(best, dst[y * dw + x], "texel " + x + "," + y + (larger ? " (max)" : " (min)"));
                }
            }
        }
    }

    @Test
    void anImageThatIsAlreadyAPowerOfTwoIsCopied() {
        float[] src = {0.1f, 0.2f, 0.3f, 0.4f};
        float[] dst = new float[4];
        HiZ.resampleFarthest(src, 2, 2, dst, 2, 2, true);
        assertTrue(java.util.Arrays.equals(src, dst));
    }

    @Test
    void theResampledPyramidIsNeverNearerThanTheImageItCameFrom() {
        // conservative: every source pixel is at or nearer than the texels that cover it, so a box hidden by the texels is hidden by the image
        Random rnd = new Random(8);
        int w = 100, h = 60, dw = HiZ.baseSize(w), dh = HiZ.baseSize(h);
        float[] src = new float[w * h], dst = new float[dw * dh];
        for (int i = 0; i < src.length; i++) {
            src[i] = rnd.nextFloat();
        }
        HiZ.resampleFarthest(src, w, h, dst, dw, dh, true);
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                int sx = Math.min(w - 1, (int) ((x + 0.5) * w / dw)), sy = Math.min(h - 1, (int) ((y + 0.5) * h / dh));
                assertTrue(dst[y * dw + x] >= src[sy * w + sx], "texel " + x + "," + y);
            }
        }
    }

    @Test
    void depthIsConvertedToNormalisedDeviceDepth() {
        assertEquals(-1f, HiZ.toNormalizedDeviceDepth(0f, true));
        assertEquals(1f, HiZ.toNormalizedDeviceDepth(1f, true));
        assertEquals(0f, HiZ.toNormalizedDeviceDepth(0.5f, true));
        assertEquals(0.25f, HiZ.toNormalizedDeviceDepth(0.25f, false));
    }

    @Test
    void badArgumentsAreRefused() {
        float[] a = new float[16];
        assertThrows(IllegalArgumentException.class, () -> HiZ.resampleFarthest(a, 4, 4, new float[4], 2, 2, true));
        assertThrows(IllegalArgumentException.class, () -> HiZ.resampleFarthest(a, 0, 4, a, 4, 4, true));
        assertThrows(IllegalArgumentException.class, () -> HiZ.resampleFarthest(new float[3], 4, 4, a, 4, 4, true));
        assertThrows(IllegalArgumentException.class, () -> HiZ.resampleFarthest(a, 4, 4, new float[3], 4, 4, true));
    }
}
