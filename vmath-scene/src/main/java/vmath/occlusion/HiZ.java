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
 * <h2>The three contracts of a pyramid in a GPU texture</h2>
 * <ol>
 *   <li><b>The base is a power of two on OpenGL.</b> {@link #mipSize} halves rounding <em>up</em> (1600,
 *       800, ..., 25, 13, 7, 4, 2, 1), as the culling shader and {@code HiZPyramid} do; OpenGL halves a mip
 *       chain rounding <em>down</em> (1600, ..., 25, 12, 6, 3, 1) and has one level fewer, so for a base that
 *       is not a power of two the two disagree: {@code glTextureStorage2D} with the library's level count
 *       fails with {@code GL_INVALID_OPERATION} (12 levels against 11 at 1600 x 900), and with the right
 *       count the shader would read a texel that is not there. Make the base a power of two:
 *       {@link #baseSize} gives the size for a dimension of the depth image, and
 *       {@link #resampleFarthest} resamples the image to it taking, under every texel, the <em>farthest</em>
 *       value (which keeps the test conservative: a box is only called hidden by what really hides it).</li>
 *   <li><b>The texels hold normalised device depth.</b> The shader compares them with the normalised device
 *       depth of the corners of a box, so for {@code DepthRange.NEGATIVE_ONE_TO_ONE} (OpenGL) a texel holds
 *       {@code 2 d - 1} of the window depth {@code d} that the depth buffer holds, not {@code d}. The
 *       CPU {@code HiZPyramid} keeps "farness" ({@code (z + 1) / 2} for that range), so a comparison of the
 *       GPU's texels with it converts one of them. For {@code ZERO_TO_ONE} nothing is converted.</li>
 *   <li><b>The passes that build the pyramid need two barriers.</b> After the compute passes that
 *       downsample, the culling shader reads the pyramid with {@code texelFetch}, which needs
 *       {@code GL_TEXTURE_FETCH_BARRIER_BIT}; a {@code glGetTextureImage} of a level needs
 *       {@code GL_TEXTURE_UPDATE_BARRIER_BIT}. A barrier for image access alone
 *       ({@code GL_SHADER_IMAGE_ACCESS_BARRIER_BIT}) is not enough for either. Vulkan has the equivalent
 *       pipeline barriers between the compute passes and the culling dispatch.</li>
 * </ol>
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
 * int base = HiZ.baseSize(1600);                                        // 2048: the base of a pyramid on OpenGL
 * }</pre>
 */
public final class HiZ {

    private HiZ() {
    }

    /**
     * Gives the size of the base of a pyramid for an image dimension: the next power of two at or
     * above it, which is what a pyramid in an OpenGL texture needs (contract 1 above).
     *
     * @param dim the width or the height of the depth image, at least 1
     * @return the smallest power of two that is at least {@code dim}
     * @throws IllegalArgumentException if {@code dim} is not positive or is above 2^30
     */
    public static int baseSize(int dim) {
        if (dim < 1 || dim > (1 << 30)) {
            throw new IllegalArgumentException("the dimension must be 1 to 2^30: " + dim);
        }
        return dim == 1 ? 1 : 1 << (32 - Integer.numberOfLeadingZeros(dim - 1));
    }

    /**
     * Resamples a depth image to the base of a pyramid, taking under every destination texel the
     * <em>farthest</em> of the source pixels that it covers.
     *
     * <p>Each destination texel {@code (x, y)} covers the source pixels from
     * {@code floor(x * srcWidth / dstWidth)} up to (not including) {@code ceil((x + 1) * srcWidth /
     * dstWidth)}, and likewise in y, which is at least one pixel when the destination is not smaller than
     * the source (as with {@link #baseSize}); the result of the covered pixels is their maximum, or their
     * minimum for reversed depth. A depth image that is resampled this way can only be farther than the
     * truth at a texel, so a box that is called hidden by the pyramid is hidden.
     *
     * @param source the depth image, row-major, {@code srcWidth * srcHeight} values; must not be {@code null}
     * @param srcWidth the width of the source, at least 1
     * @param srcHeight the height of the source, at least 1
     * @param destination receives {@code dstWidth * dstHeight} values, row-major; must not be {@code null}
     * @param dstWidth the width of the base, at least {@code srcWidth}
     * @param dstHeight the height of the base, at least {@code srcHeight}
     * @param largerIsFarther {@code true} for conventional depth (the maximum is kept), {@code false} for reversed depth (the minimum)
     * @throws IllegalArgumentException if a size is not positive, the destination is smaller than the source in a dimension, or an array is too short
     */
    public static void resampleFarthest(float[] source, int srcWidth, int srcHeight, float[] destination, int dstWidth, int dstHeight, boolean largerIsFarther) {
        if (srcWidth < 1 || srcHeight < 1 || dstWidth < srcWidth || dstHeight < srcHeight) {
            throw new IllegalArgumentException("need 1 <= source <= destination in both dimensions: " + srcWidth + "x" + srcHeight + " to " + dstWidth + "x" + dstHeight);
        }
        if (source.length < (long) srcWidth * srcHeight || destination.length < (long) dstWidth * dstHeight) {
            throw new IllegalArgumentException("an array is too short for its size");
        }
        for (int y = 0; y < dstHeight; y++) {
            int y0 = (int) ((long) y * srcHeight / dstHeight), y1 = (int) (((long) (y + 1) * srcHeight + dstHeight - 1) / dstHeight);
            for (int x = 0; x < dstWidth; x++) {
                int x0 = (int) ((long) x * srcWidth / dstWidth), x1 = (int) (((long) (x + 1) * srcWidth + dstWidth - 1) / dstWidth);
                float best = source[y0 * srcWidth + x0];
                for (int sy = y0; sy < y1; sy++) {
                    for (int sx = x0; sx < x1; sx++) {
                        float v = source[sy * srcWidth + sx];
                        best = largerIsFarther ? Math.max(best, v) : Math.min(best, v);
                    }
                }
                destination[y * dstWidth + x] = best;
            }
        }
    }

    /**
     * Converts window depth to the normalised device depth that the culling shader compares, for
     * the range of the clip space (contract 2 above).
     *
     * @param windowDepth the depth that the depth buffer holds, 0 to 1
     * @param negativeOneToOne {@code true} for OpenGL's range of -1 to 1 (the value is {@code 2 d - 1}), {@code false} for 0 to 1 (unchanged)
     * @return the normalised device depth
     */
    public static float toNormalizedDeviceDepth(float windowDepth, boolean negativeOneToOne) {
        return negativeOneToOne ? 2f * windowDepth - 1f : windowDepth;
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
