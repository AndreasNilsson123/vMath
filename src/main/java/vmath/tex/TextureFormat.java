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
    /** Vulkan format 9: 1 byte per texel. */
    R8_UNORM(9, 1, 1, 1, false),
    /** Vulkan format 16: 2 bytes per texel. */
    R8G8_UNORM(16, 1, 1, 2, false),
    /** Vulkan format 23: 3 bytes per texel. */
    R8G8B8_UNORM(23, 1, 1, 3, false),
    /** Vulkan format 37: 4 bytes per texel. */
    R8G8B8A8_UNORM(37, 1, 1, 4, false),
    /** Vulkan format 43: 4 bytes per texel, sRGB encoded. */
    R8G8B8A8_SRGB(43, 1, 1, 4, true),
    /** Vulkan format 44: 4 bytes per texel. */
    B8G8R8A8_UNORM(44, 1, 1, 4, false),
    /** Vulkan format 50: 4 bytes per texel, sRGB encoded. */
    B8G8R8A8_SRGB(50, 1, 1, 4, true),
    /** Vulkan format 64: 4 bytes per texel. */
    A2B10G10R10_UNORM(64, 1, 1, 4, false),
    /** Vulkan format 76: 2 bytes per texel. */
    R16_SFLOAT(76, 1, 1, 2, false),
    /** Vulkan format 83: 4 bytes per texel. */
    R16G16_SFLOAT(83, 1, 1, 4, false),
    /** Vulkan format 97: 8 bytes per texel. */
    R16G16B16A16_SFLOAT(97, 1, 1, 8, false),
    /** Vulkan format 100: 4 bytes per texel. */
    R32_SFLOAT(100, 1, 1, 4, false),
    /** Vulkan format 103: 8 bytes per texel. */
    R32G32_SFLOAT(103, 1, 1, 8, false),
    /** Vulkan format 109: 16 bytes per texel. */
    R32G32B32A32_SFLOAT(109, 1, 1, 16, false),

    /** Vulkan format 131: 4 x 4 texel blocks of 8 bytes. */
    BC1_RGB_UNORM(131, 4, 4, 8, false),
    /** Vulkan format 132: 4 x 4 texel blocks of 8 bytes, sRGB encoded. */
    BC1_RGB_SRGB(132, 4, 4, 8, true),
    /** Vulkan format 133: 4 x 4 texel blocks of 8 bytes. */
    BC1_RGBA_UNORM(133, 4, 4, 8, false),
    /** Vulkan format 134: 4 x 4 texel blocks of 8 bytes, sRGB encoded. */
    BC1_RGBA_SRGB(134, 4, 4, 8, true),
    /** Vulkan format 135: 4 x 4 texel blocks of 16 bytes. */
    BC2_UNORM(135, 4, 4, 16, false),
    /** Vulkan format 136: 4 x 4 texel blocks of 16 bytes, sRGB encoded. */
    BC2_SRGB(136, 4, 4, 16, true),
    /** Vulkan format 137: 4 x 4 texel blocks of 16 bytes. */
    BC3_UNORM(137, 4, 4, 16, false),
    /** Vulkan format 138: 4 x 4 texel blocks of 16 bytes, sRGB encoded. */
    BC3_SRGB(138, 4, 4, 16, true),
    /** Vulkan format 139: 4 x 4 texel blocks of 8 bytes. */
    BC4_UNORM(139, 4, 4, 8, false),
    /** Vulkan format 140: 4 x 4 texel blocks of 8 bytes. */
    BC4_SNORM(140, 4, 4, 8, false),
    /** Vulkan format 141: 4 x 4 texel blocks of 16 bytes. */
    BC5_UNORM(141, 4, 4, 16, false),
    /** Vulkan format 142: 4 x 4 texel blocks of 16 bytes. */
    BC5_SNORM(142, 4, 4, 16, false),
    /** Vulkan format 143: 4 x 4 texel blocks of 16 bytes. */
    BC6H_UFLOAT(143, 4, 4, 16, false),
    /** Vulkan format 144: 4 x 4 texel blocks of 16 bytes. */
    BC6H_SFLOAT(144, 4, 4, 16, false),
    /** Vulkan format 145: 4 x 4 texel blocks of 16 bytes. */
    BC7_UNORM(145, 4, 4, 16, false),
    /** Vulkan format 146: 4 x 4 texel blocks of 16 bytes, sRGB encoded. */
    BC7_SRGB(146, 4, 4, 16, true),

    /** Vulkan format 147: 4 x 4 texel blocks of 8 bytes. */
    ETC2_RGB8_UNORM(147, 4, 4, 8, false),
    /** Vulkan format 148: 4 x 4 texel blocks of 8 bytes, sRGB encoded. */
    ETC2_RGB8_SRGB(148, 4, 4, 8, true),
    /** Vulkan format 149: 4 x 4 texel blocks of 8 bytes. */
    ETC2_RGB8A1_UNORM(149, 4, 4, 8, false),
    /** Vulkan format 150: 4 x 4 texel blocks of 8 bytes, sRGB encoded. */
    ETC2_RGB8A1_SRGB(150, 4, 4, 8, true),
    /** Vulkan format 151: 4 x 4 texel blocks of 16 bytes. */
    ETC2_RGBA8_UNORM(151, 4, 4, 16, false),
    /** Vulkan format 152: 4 x 4 texel blocks of 16 bytes, sRGB encoded. */
    ETC2_RGBA8_SRGB(152, 4, 4, 16, true),
    /** Vulkan format 153: 4 x 4 texel blocks of 8 bytes. */
    EAC_R11_UNORM(153, 4, 4, 8, false),
    /** Vulkan format 154: 4 x 4 texel blocks of 8 bytes. */
    EAC_R11_SNORM(154, 4, 4, 8, false),
    /** Vulkan format 155: 4 x 4 texel blocks of 16 bytes. */
    EAC_RG11_UNORM(155, 4, 4, 16, false),
    /** Vulkan format 156: 4 x 4 texel blocks of 16 bytes. */
    EAC_RG11_SNORM(156, 4, 4, 16, false),

    /** Vulkan format 157: 4 x 4 texel blocks of 16 bytes. */
    ASTC_4x4_UNORM(157, 4, 4, 16, false),
    /** Vulkan format 158: 4 x 4 texel blocks of 16 bytes, sRGB encoded. */
    ASTC_4x4_SRGB(158, 4, 4, 16, true),
    /** Vulkan format 159: 5 x 4 texel blocks of 16 bytes. */
    ASTC_5x4_UNORM(159, 5, 4, 16, false),
    /** Vulkan format 160: 5 x 4 texel blocks of 16 bytes, sRGB encoded. */
    ASTC_5x4_SRGB(160, 5, 4, 16, true),
    /** Vulkan format 161: 5 x 5 texel blocks of 16 bytes. */
    ASTC_5x5_UNORM(161, 5, 5, 16, false),
    /** Vulkan format 162: 5 x 5 texel blocks of 16 bytes, sRGB encoded. */
    ASTC_5x5_SRGB(162, 5, 5, 16, true),
    /** Vulkan format 163: 6 x 5 texel blocks of 16 bytes. */
    ASTC_6x5_UNORM(163, 6, 5, 16, false),
    /** Vulkan format 164: 6 x 5 texel blocks of 16 bytes, sRGB encoded. */
    ASTC_6x5_SRGB(164, 6, 5, 16, true),
    /** Vulkan format 165: 6 x 6 texel blocks of 16 bytes. */
    ASTC_6x6_UNORM(165, 6, 6, 16, false),
    /** Vulkan format 166: 6 x 6 texel blocks of 16 bytes, sRGB encoded. */
    ASTC_6x6_SRGB(166, 6, 6, 16, true),
    /** Vulkan format 167: 8 x 5 texel blocks of 16 bytes. */
    ASTC_8x5_UNORM(167, 8, 5, 16, false),
    /** Vulkan format 168: 8 x 5 texel blocks of 16 bytes, sRGB encoded. */
    ASTC_8x5_SRGB(168, 8, 5, 16, true),
    /** Vulkan format 169: 8 x 6 texel blocks of 16 bytes. */
    ASTC_8x6_UNORM(169, 8, 6, 16, false),
    /** Vulkan format 170: 8 x 6 texel blocks of 16 bytes, sRGB encoded. */
    ASTC_8x6_SRGB(170, 8, 6, 16, true),
    /** Vulkan format 171: 8 x 8 texel blocks of 16 bytes. */
    ASTC_8x8_UNORM(171, 8, 8, 16, false),
    /** Vulkan format 172: 8 x 8 texel blocks of 16 bytes, sRGB encoded. */
    ASTC_8x8_SRGB(172, 8, 8, 16, true),
    /** Vulkan format 173: 10 x 5 texel blocks of 16 bytes. */
    ASTC_10x5_UNORM(173, 10, 5, 16, false),
    /** Vulkan format 174: 10 x 5 texel blocks of 16 bytes, sRGB encoded. */
    ASTC_10x5_SRGB(174, 10, 5, 16, true),
    /** Vulkan format 175: 10 x 6 texel blocks of 16 bytes. */
    ASTC_10x6_UNORM(175, 10, 6, 16, false),
    /** Vulkan format 176: 10 x 6 texel blocks of 16 bytes, sRGB encoded. */
    ASTC_10x6_SRGB(176, 10, 6, 16, true),
    /** Vulkan format 177: 10 x 8 texel blocks of 16 bytes. */
    ASTC_10x8_UNORM(177, 10, 8, 16, false),
    /** Vulkan format 178: 10 x 8 texel blocks of 16 bytes, sRGB encoded. */
    ASTC_10x8_SRGB(178, 10, 8, 16, true),
    /** Vulkan format 179: 10 x 10 texel blocks of 16 bytes. */
    ASTC_10x10_UNORM(179, 10, 10, 16, false),
    /** Vulkan format 180: 10 x 10 texel blocks of 16 bytes, sRGB encoded. */
    ASTC_10x10_SRGB(180, 10, 10, 16, true),
    /** Vulkan format 181: 12 x 10 texel blocks of 16 bytes. */
    ASTC_12x10_UNORM(181, 12, 10, 16, false),
    /** Vulkan format 182: 12 x 10 texel blocks of 16 bytes, sRGB encoded. */
    ASTC_12x10_SRGB(182, 12, 10, 16, true),
    /** Vulkan format 183: 12 x 12 texel blocks of 16 bytes. */
    ASTC_12x12_UNORM(183, 12, 12, 16, false),
    /** Vulkan format 184: 12 x 12 texel blocks of 16 bytes, sRGB encoded. */
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

    /** True for a block-compressed format (blocks larger than one texel). */
    public boolean isCompressed() {
        return blockWidth > 1 || blockHeight > 1;
    }

    /** True when the colour channels are stored in the sRGB transfer function (decoded to linear by the sampler). */
    public boolean isSrgb() {
        return srgb;
    }

    /** Blocks needed to cover {@code width} texels (partial blocks at the edge count as whole blocks). */
    public int blocksWide(int width) {
        return (int) (((long) width + blockWidth - 1) / blockWidth); // in long: width + blockWidth - 1 overflows an int near Integer.MAX_VALUE
    }

    /** Blocks needed to cover {@code height} texels. */
    public int blocksHigh(int height) {
        return (int) (((long) height + blockHeight - 1) / blockHeight);
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
