package vmath.tex;

import vmath.annotations.Experimental;

/**
 * The texture formats a renderer meets most often, with the numbers needed to size and address their data: the Vulkan {@code VkFormat} value (which is also
 * what a KTX2 file stores), the block size in texels, and the bytes per block. An uncompressed format is a 1 x 1 block.
 *
 * <p>The table is not every format, only the common uncompressed ones, the BC (S3TC/RGTC/BPTC), ETC2/EAC and ASTC LDR families; {@link #fromVkFormat} returns
 * {@code null} for anything else. The values are the Vulkan enumerants; see the Vulkan specification, "Compatible formats" for the block sizes.
 */
@Experimental("formats will be added; the enum constants are stable, the set is not")
public enum TextureFormat {
    R8_UNORM(9, 1, 1, 1, false),
    R8G8_UNORM(16, 1, 1, 2, false),
    R8G8B8_UNORM(23, 1, 1, 3, false),
    R8G8B8A8_UNORM(37, 1, 1, 4, false),
    R8G8B8A8_SRGB(43, 1, 1, 4, true),
    B8G8R8A8_UNORM(44, 1, 1, 4, false),
    B8G8R8A8_SRGB(50, 1, 1, 4, true),
    A2B10G10R10_UNORM(64, 1, 1, 4, false),
    R16_SFLOAT(76, 1, 1, 2, false),
    R16G16_SFLOAT(83, 1, 1, 4, false),
    R16G16B16A16_SFLOAT(97, 1, 1, 8, false),
    R32_SFLOAT(100, 1, 1, 4, false),
    R32G32_SFLOAT(103, 1, 1, 8, false),
    R32G32B32A32_SFLOAT(109, 1, 1, 16, false),

    BC1_RGB_UNORM(131, 4, 4, 8, false),
    BC1_RGB_SRGB(132, 4, 4, 8, true),
    BC1_RGBA_UNORM(133, 4, 4, 8, false),
    BC1_RGBA_SRGB(134, 4, 4, 8, true),
    BC2_UNORM(135, 4, 4, 16, false),
    BC2_SRGB(136, 4, 4, 16, true),
    BC3_UNORM(137, 4, 4, 16, false),
    BC3_SRGB(138, 4, 4, 16, true),
    BC4_UNORM(139, 4, 4, 8, false),
    BC4_SNORM(140, 4, 4, 8, false),
    BC5_UNORM(141, 4, 4, 16, false),
    BC5_SNORM(142, 4, 4, 16, false),
    BC6H_UFLOAT(143, 4, 4, 16, false),
    BC6H_SFLOAT(144, 4, 4, 16, false),
    BC7_UNORM(145, 4, 4, 16, false),
    BC7_SRGB(146, 4, 4, 16, true),

    ETC2_RGB8_UNORM(147, 4, 4, 8, false),
    ETC2_RGB8_SRGB(148, 4, 4, 8, true),
    ETC2_RGB8A1_UNORM(149, 4, 4, 8, false),
    ETC2_RGB8A1_SRGB(150, 4, 4, 8, true),
    ETC2_RGBA8_UNORM(151, 4, 4, 16, false),
    ETC2_RGBA8_SRGB(152, 4, 4, 16, true),
    EAC_R11_UNORM(153, 4, 4, 8, false),
    EAC_R11_SNORM(154, 4, 4, 8, false),
    EAC_RG11_UNORM(155, 4, 4, 16, false),
    EAC_RG11_SNORM(156, 4, 4, 16, false),

    ASTC_4x4_UNORM(157, 4, 4, 16, false),
    ASTC_4x4_SRGB(158, 4, 4, 16, true),
    ASTC_5x4_UNORM(159, 5, 4, 16, false),
    ASTC_5x4_SRGB(160, 5, 4, 16, true),
    ASTC_5x5_UNORM(161, 5, 5, 16, false),
    ASTC_5x5_SRGB(162, 5, 5, 16, true),
    ASTC_6x5_UNORM(163, 6, 5, 16, false),
    ASTC_6x5_SRGB(164, 6, 5, 16, true),
    ASTC_6x6_UNORM(165, 6, 6, 16, false),
    ASTC_6x6_SRGB(166, 6, 6, 16, true),
    ASTC_8x5_UNORM(167, 8, 5, 16, false),
    ASTC_8x5_SRGB(168, 8, 5, 16, true),
    ASTC_8x6_UNORM(169, 8, 6, 16, false),
    ASTC_8x6_SRGB(170, 8, 6, 16, true),
    ASTC_8x8_UNORM(171, 8, 8, 16, false),
    ASTC_8x8_SRGB(172, 8, 8, 16, true),
    ASTC_10x5_UNORM(173, 10, 5, 16, false),
    ASTC_10x5_SRGB(174, 10, 5, 16, true),
    ASTC_10x6_UNORM(175, 10, 6, 16, false),
    ASTC_10x6_SRGB(176, 10, 6, 16, true),
    ASTC_10x8_UNORM(177, 10, 8, 16, false),
    ASTC_10x8_SRGB(178, 10, 8, 16, true),
    ASTC_10x10_UNORM(179, 10, 10, 16, false),
    ASTC_10x10_SRGB(180, 10, 10, 16, true),
    ASTC_12x10_UNORM(181, 12, 10, 16, false),
    ASTC_12x10_SRGB(182, 12, 10, 16, true),
    ASTC_12x12_UNORM(183, 12, 12, 16, false),
    ASTC_12x12_SRGB(184, 12, 12, 16, true);

    private final int vkFormat;
    private final int blockWidth;
    private final int blockHeight;
    private final int bytesPerBlock;
    private final boolean srgb;

    TextureFormat(int vkFormat, int blockWidth, int blockHeight, int bytesPerBlock, boolean srgb) {
        this.vkFormat = vkFormat;
        this.blockWidth = blockWidth;
        this.blockHeight = blockHeight;
        this.bytesPerBlock = bytesPerBlock;
        this.srgb = srgb;
    }

    /** The Vulkan {@code VkFormat} value (what a KTX2 header stores in {@code vkFormat}). */
    public int vkFormat() {
        return vkFormat;
    }

    /** Texels per block horizontally; 1 for an uncompressed format. */
    public int blockWidth() {
        return blockWidth;
    }

    /** Texels per block vertically; 1 for an uncompressed format. */
    public int blockHeight() {
        return blockHeight;
    }

    /** Bytes per block; bytes per texel for an uncompressed format. */
    public int bytesPerBlock() {
        return bytesPerBlock;
    }

    public boolean isCompressed() {
        return blockWidth > 1 || blockHeight > 1;
    }

    /** True when the colour channels are stored in the sRGB transfer function (decoded to linear by the sampler). */
    public boolean isSrgb() {
        return srgb;
    }

    /** Blocks needed to cover {@code width} texels (partial blocks at the edge count as whole blocks). */
    public int blocksWide(int width) {
        return (width + blockWidth - 1) / blockWidth;
    }

    /** Blocks needed to cover {@code height} texels. */
    public int blocksHigh(int height) {
        return (height + blockHeight - 1) / blockHeight;
    }

    /** Bytes of one 2D image of {@code width} x {@code height} texels, tightly packed, edge blocks rounded up. */
    public long imageBytes(int width, int height) {
        return (long) blocksWide(width) * blocksHigh(height) * bytesPerBlock;
    }

    /** The format with this Vulkan value, or {@code null} when it is not in the table. */
    public static TextureFormat fromVkFormat(int vkFormat) {
        for (TextureFormat f : values()) {
            if (f.vkFormat == vkFormat) {
                return f;
            }
        }
        return null;
    }
}
