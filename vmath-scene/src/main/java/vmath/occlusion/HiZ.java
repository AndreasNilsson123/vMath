package vmath.occlusion;

/**
 * Sizing rules for a Hi-Z pyramid (a depth buffer followed by successively half-sized min- or
 * max-reduced copies), shared by the CPU {@link DepthBuffer} and by GPU implementations that need
 * the same numbers to allocate a texture and dispatch a downsample per level.
 *
 * <h2>The two-phase contract for GPU-driven occlusion culling</h2>
 * <ol>
 *   <li><b>Phase 1.</b> Test every object against last frame's Hi-Z pyramid (reprojected into this
 *       frame's camera) and draw the ones that pass. These are objects that were visible recently;
 *       drawing them fills this frame's depth buffer.</li>
 *   <li><b>Rebuild.</b> Build the Hi-Z pyramid from this frame's depth: level {@code n + 1} texel
 *       is the farthest (max, for depth where larger is farther) of the four level {@code n} texels
 *       below it.</li>
 *   <li><b>Phase 2.</b> Test only the objects that failed phase 1 against the new pyramid and draw
 *       the ones that now pass. They were hidden last frame but are not hidden now, so nothing is
 *       missing from the image.</li>
 * </ol> The CPU {@link DepthBuffer} does the same test with occluders rasterized on the CPU
 * instead, in a single phase and with the opposite depth convention ({@code 1 / w}, so it reduces
 * with {@code min}); use it as the reference when checking a GPU implementation.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time. The arrays and buffers you pass in are not synchronised, so two threads must not write
 * the same one.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * int levels = HiZ.mipCount(1024, 512);                                 // down to a single texel
 * int width3 = HiZ.mipSize(1024, 3);                                    // 128
 * }</pre>
 */
public final class HiZ {

    private HiZ() {
    }

    /**
     * Computes how many levels a pyramid over a base of the given size has, down to a single texel.
     *
     * @param width the width
     * @param height the height
     * @return number of levels of a pyramid over a {@code width x height} base, down to a single
     *     texel (the base counts as level 0)
     * @throws IllegalArgumentException if {@code width} or {@code height} is not positive
     */
    public static int mipCount(int width, int height) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("size must be positive: " + width + "x" + height);
        }
        int longest = Math.max(width, height);
        return 1 + (32 - Integer.numberOfLeadingZeros(longest - 1));
    }

    /**
     * Computes the size of a dimension at a pyramid level by halving, rounding up, per level.
     *
     * @param dim the dimension
     * @param level the level
     * @return size of one dimension {@code dim} at pyramid level {@code level}: halved (rounding
     *     up) per level, never below 1
     * @throws IllegalArgumentException if {@code dim} is not positive or {@code level} is not in
     *     {@code [0, 30]}
     */
    public static int mipSize(int dim, int level) {
        if (dim < 1 || level < 0 || level > 30) {
            throw new IllegalArgumentException("dim must be positive and level in [0, 30]: " + dim + ", " + level);
        }
        return Math.max(1, (int) (((long) dim + (1L << level) - 1L) >> level));
    }

    /**
     * Selects the pyramid level at which a screen rectangle spans only a few texels, which is the
     * level that a hierarchical depth test should sample.
     *
     * @param rectWidth the rect width
     * @param rectHeight the rect height
     * @param maxTexels the max texels
     * @param mipCount the mip count
     * @return the coarsest level at which a screen rectangle of {@code width x height} pixels spans
     *     at most {@code maxTexels} texels in each direction, the level a Hi-Z test should sample
     *     so that it reads a handful of texels whatever the object's size
     */
    public static int levelFor(int rectWidth, int rectHeight, int maxTexels, int mipCount) {
        int l = 0;
        int longest = Math.max(rectWidth, rectHeight);
        while (l + 1 < mipCount && (longest >> l) > maxTexels) {
            l++;
        }
        return l;
    }
}
