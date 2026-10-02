package vmath.tex;

import vmath.annotations.Experimental;

/**
 * Sizes and offsets of a tightly packed texture: dimensions, format, mip levels, array layers and cube faces.
 *
 * <p><b>Order of the data</b> (the order of the contents of a KTX2 level, and of a Vulkan buffer-to-image copy per level): mip level major, then array layer,
 * then cube face, then depth slice, each image tightly packed with its block rows. A cube map has 6 faces in the order {@code +X, -X, +Y, -Y, +Z, -Z}
 * ({@link CubeFace}); a cube array has {@code layers * 6} images per level. Offsets here are in that in-memory order; a KTX2 file stores whole levels in the
 * opposite order (smallest first), which {@link Ktx2} reports through its own level offsets.
 *
 * <p>Level sizes follow the usual rule {@code max(1, size >> level)}; for a block-compressed format the image of a level is rounded up to whole blocks.
 *
 * @param format the texel format
 * @param width width of level 0 in texels, at least 1
 * @param height height of level 0, at least 1
 * @param depth depth of level 0 (1 for everything but a 3D texture)
 * @param levels number of mip levels, between 1 and {@link #maxLevels}
 * @param layers array layers (1 for a plain texture)
 * @param faces 1, or 6 for a cube map
 */
@Experimental("a first cut of texture addressing; 3D and compressed-array corner cases are lightly tested")
public record TextureLayout(TextureFormat format, int width, int height, int depth, int levels, int layers, int faces) {

    public TextureLayout {
        if (format == null) {
            throw new IllegalArgumentException("format");
        }
        if (width < 1 || height < 1 || depth < 1 || layers < 1) {
            throw new IllegalArgumentException("every dimension and the layer count must be at least 1");
        }
        if (faces != 1 && faces != 6) {
            throw new IllegalArgumentException("faces must be 1 or 6: " + faces);
        }
        if (levels < 1 || levels > maxLevels(width, height, depth)) {
            throw new IllegalArgumentException("levels must be in 1.." + maxLevels(width, height, depth) + ": " + levels);
        }
        checkAddressable(format, width, height, depth, levels, layers, faces);
    }

    /** Refuses a texture whose total size does not fit a {@code long}: every size and offset the methods below return would be wrong (negative) for it. */
    private static void checkAddressable(TextureFormat format, int width, int height, int depth, int levels, int layers, int faces) {
        try {
            long total = 0;
            for (int l = 0; l < levels; l++) {
                long slice = Math.multiplyExact(Math.multiplyExact((long) format.blocksWide(levelSize(width, l)), (long) format.blocksHigh(levelSize(height, l))), (long) format.bytesPerBlock());
                long image = Math.multiplyExact(slice, (long) levelSize(depth, l));
                total = Math.addExact(total, Math.multiplyExact(Math.multiplyExact(image, (long) layers), (long) faces));
            }
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("a texture of " + width + " x " + height + " x " + depth + " texels with " + layers + " layers and " + faces + " faces is larger than 2^63 bytes");
        }
    }

    /** A 2D texture with the full mip chain. */
    public static TextureLayout texture2d(TextureFormat format, int width, int height) {
        return new TextureLayout(format, width, height, 1, maxLevels(width, height, 1), 1, 1);
    }

    /** A cube map with the full mip chain; {@code size} is the edge of a face. */
    public static TextureLayout cube(TextureFormat format, int size) {
        return new TextureLayout(format, size, size, 1, maxLevels(size, size, 1), 1, 6);
    }

    /** The number of levels of a full chain: {@code floor(log2(max(width, height, depth))) + 1}. */
    public static int maxLevels(int width, int height, int depth) {
        int m = Math.max(width, Math.max(height, depth));
        return 32 - Integer.numberOfLeadingZeros(m);
    }

    /** The size of a dimension at {@code level}: {@code max(1, size >> level)}. */
    public static int levelSize(int size, int level) {
        return Math.max(1, size >> level);
    }

    public int levelWidth(int level) {
        checkLevel(level);
        return levelSize(width, level);
    }

    public int levelHeight(int level) {
        checkLevel(level);
        return levelSize(height, level);
    }

    public int levelDepth(int level) {
        checkLevel(level);
        return levelSize(depth, level);
    }

    /** Bytes of one image (one layer, one face, all depth slices) at {@code level}. */
    public long imageBytes(int level) {
        return format.imageBytes(levelWidth(level), levelHeight(level)) * levelDepth(level);
    }

    /** Bytes of all layers and faces at {@code level}. */
    public long levelBytes(int level) {
        return imageBytes(level) * layers * faces;
    }

    /** Byte offset of the first byte of {@code level} in the tightly packed whole. */
    public long levelOffset(int level) {
        checkLevel(level);
        long offset = 0;
        for (int l = 0; l < level; l++) {
            offset += levelBytes(l);
        }
        return offset;
    }

    /** Byte offset of the image of {@code layer} and {@code face} at {@code level}. */
    public long imageOffset(int level, int layer, int face) {
        if (layer < 0 || layer >= layers || face < 0 || face >= faces) {
            throw new IndexOutOfBoundsException("layer " + layer + " of " + layers + ", face " + face + " of " + faces);
        }
        return levelOffset(level) + (layer * (long) faces + face) * imageBytes(level);
    }

    /** Bytes of the whole texture, every level, layer and face. */
    public long totalBytes() {
        return levelOffset(levels - 1) + levelBytes(levels - 1);
    }

    private void checkLevel(int level) {
        if (level < 0 || level >= levels) {
            throw new IndexOutOfBoundsException("level " + level + " of " + levels);
        }
    }
}
