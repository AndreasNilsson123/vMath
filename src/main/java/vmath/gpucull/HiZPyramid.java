package vmath.gpucull;

import vmath.annotations.Experimental;
import vmath.geo.DepthRange;
import vmath.occlusion.HiZ;

/**
 * A CPU model of the Hi-Z texture of GPU-driven occlusion culling: a depth image followed by successively half-sized copies, each texel the <em>farthest</em> depth of
 * the (up to) four texels below it, so that a texel can answer "is everything under me nearer than this?" for a whole screen region at once.
 *
 * <p><b>Depth convention.</b> Depth images are what the API wrote: conventional depth (near maps to 0, or -1) or reversed-Z (near maps to 1). The pyramid converts every texel to
 * one internal ordering, <em>farness</em>, where a larger value is farther ({@code z} for [0, 1], {@code (z + 1) / 2} for [-1, 1], {@code 1 - z} for reversed-Z) and reduces
 * with {@code max}. {@link #farness} is the same conversion for the nearest depth of an object, so the test is the same in every convention. (A shader would keep the API depth and
 * pick {@code max} or {@code min} accordingly; the two are the same test.)
 *
 * <p><b>Sizes</b> follow {@link HiZ#mipSize}: halved rounding up, down to one texel. A texel of level {@code n + 1} covers the texels {@code 2x, 2x + 1} and {@code 2y, 2y + 1}
 * of level {@code n} that exist, so every texel has exactly one parent and nothing under a texel is left out, for any size, not only powers of two.
 *
 * <p><b>The test</b> ({@link #isHidden}). A screen rectangle (NDC) and the nearest farness of the object. The level is the smallest one at which the rectangle spans at most one
 * texel in each direction, so it touches at most 2 x 2 texels; the object is hidden when its nearest farness is beyond the farthest of those texels, which means every pixel under
 * the rectangle is covered by something nearer. It is <b>sound</b>: it never reports hidden an object whose rectangle has a pixel that shows something at least as far as the object's
 * nearest point (checked against a per-pixel test in {@code HiZPyramidTest}); it may report visible an object that is in fact hidden, which costs a draw, never an image error.
 */
@Experimental("the pyramid model and its test may change")
public final class HiZPyramid {

    private final int levels;
    private final int[] widths, heights;
    private final float[][] farness;
    private final boolean yDown;
    private final DepthRange range;

    private HiZPyramid(int width, int height, boolean yDown, DepthRange range) {
        this.levels = HiZ.mipCount(width, height);
        this.widths = new int[levels];
        this.heights = new int[levels];
        this.farness = new float[levels][];
        for (int l = 0; l < levels; l++) {
            widths[l] = HiZ.mipSize(width, l);
            heights[l] = HiZ.mipSize(height, l);
            farness[l] = new float[widths[l] * heights[l]];
        }
        this.yDown = yDown;
        this.range = range;
    }

    /**
     * Builds the pyramid from a depth image (row-major, {@code width * height} values, in the convention {@code range}). {@code yDown} says whether row 0 is at NDC y = +1
     * (D3D-style clip space, and Vulkan with a flipped projection) or at y = -1 (OpenGL); it only matters for mapping NDC rectangles to texels.
     */
    public static HiZPyramid fromDepth(float[] depth, int width, int height, DepthRange range, boolean yDown) {
        if (depth.length < (long) width * height) {
            throw new IllegalArgumentException("the depth image has " + depth.length + " values for " + width + " x " + height);
        }
        HiZPyramid p = new HiZPyramid(width, height, yDown, range);
        float[] base = p.farness[0];
        for (int i = 0; i < width * height; i++) {
            base[i] = farness(depth[i], range);
        }
        p.reduce();
        return p;
    }

    /** Rebuilds all levels above the first from level 0, which a caller may have filled through {@link #level(int)}. */
    public void reduce() {
        for (int l = 1; l < levels; l++) {
            int w = widths[l], h = heights[l], pw = widths[l - 1], ph = heights[l - 1];
            float[] src = farness[l - 1], dst = farness[l];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int x0 = 2 * x, y0 = 2 * y, x1 = Math.min(x0 + 1, pw - 1), y1 = Math.min(y0 + 1, ph - 1);
                    float m = Math.max(Math.max(src[y0 * pw + x0], src[y0 * pw + x1]), Math.max(src[y1 * pw + x0], src[y1 * pw + x1]));
                    // NaN in the depth is the farthest possible, so it can only make an object visible
                    dst[y * w + x] = Float.isNaN(src[y0 * pw + x0]) || Float.isNaN(src[y0 * pw + x1]) || Float.isNaN(src[y1 * pw + x0]) || Float.isNaN(src[y1 * pw + x1]) ? Float.POSITIVE_INFINITY : m;
                }
            }
        }
    }

    /** The farness of an API depth value: larger is farther in every convention. */
    public static float farness(float depth, DepthRange range) {
        return switch (range) {
            case ZERO_TO_ONE -> depth;
            case NEGATIVE_ONE_TO_ONE -> (depth + 1f) * 0.5f;
            case REVERSED_ZERO_TO_ONE -> 1f - depth;
        };
    }

    public int levels() {
        return levels;
    }

    public int width(int level) {
        return widths[level];
    }

    public int height(int level) {
        return heights[level];
    }

    public boolean yDown() {
        return yDown;
    }

    public DepthRange depthRange() {
        return range;
    }

    /** The live farness values of a level (row-major). Level 0 may be written, then call {@link #reduce()}. */
    public float[] level(int level) {
        return farness[level];
    }

    /** The farness of a texel. */
    public float at(int level, int x, int y) {
        return farness[level][y * widths[level] + x];
    }

    /**
     * True when an object whose screen rectangle is {@code [minX, maxX] x [minY, maxY]} (NDC) and whose nearest point has farness {@code nearestFarness} is hidden behind what the
     * pyramid holds. The rectangle is clamped to the screen; anything not finite or not on the screen at all is "not hidden". The comparison is strict, so an object exactly as far as the
     * farthest texel is kept.
     */
    public boolean isHidden(float minX, float minY, float maxX, float maxY, float nearestFarness) {
        if (!(minX <= maxX) || !(minY <= maxY) || Float.isNaN(nearestFarness)) {
            return false;
        }
        if (maxX < -1f || minX > 1f || maxY < -1f || minY > 1f) {
            return false; // wholly off screen: the frustum test decides, nothing to read here
        }
        float x0 = Math.max(minX, -1f), x1 = Math.min(maxX, 1f), y0 = Math.max(minY, -1f), y1 = Math.min(maxY, 1f);
        int w0 = widths[0], h0 = heights[0];
        float pxMin = (x0 * 0.5f + 0.5f) * w0, pxMax = (x1 * 0.5f + 0.5f) * w0;
        float pyMin, pyMax;
        if (yDown) {
            pyMin = (0.5f - y1 * 0.5f) * h0;
            pyMax = (0.5f - y0 * 0.5f) * h0;
        } else {
            pyMin = (y0 * 0.5f + 0.5f) * h0;
            pyMax = (y1 * 0.5f + 0.5f) * h0;
        }
        // the level at which the rectangle is at most one texel across: 2^level >= its longest side in pixels
        float extent = Math.max(pxMax - pxMin, pyMax - pyMin);
        int level = 0;
        while (level + 1 < levels && (float) (1 << level) < extent) {
            level++;
        }
        int w = widths[level], h = heights[level];
        float scale = 1f / (1 << level);
        int tx0 = clamp((int) Math.floor(pxMin * scale), w), tx1 = clamp((int) Math.floor(pxMax * scale), w);
        int ty0 = clamp((int) Math.floor(pyMin * scale), h), ty1 = clamp((int) Math.floor(pyMax * scale), h);
        float far = Float.NEGATIVE_INFINITY;
        float[] data = farness[level];
        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                far = Math.max(far, data[ty * w + tx]);
            }
        }
        return nearestFarness > far;
    }

    private static int clamp(int v, int n) {
        return v < 0 ? 0 : Math.min(v, n - 1);
    }
}
