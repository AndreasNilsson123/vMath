package vmath.tex;

import vmath.annotations.Experimental;

/**
 * Sizes and offsets of a tightly packed texture: dimensions, format, mip levels, array layers and
 * cube faces.
 *
 * <p><b>Order of the data</b> (the order of the contents of a KTX2 level, and of a Vulkan
 * buffer-to-image copy per level): mip level major, then array layer, then cube face, then depth
 * slice, each image tightly packed with its block rows. A cube map has 6 faces in the order
 * {@code +X, -X, +Y, -Y, +Z, -Z} ({@link CubeFace}); a cube array has {@code layers * 6} images per
 * level. Offsets here are in that in-memory order; a KTX2 file stores whole levels in the opposite
 * order (smallest first), which {@link Ktx2} reports through its own level offsets.
 *
 * <p>Level sizes follow the usual rule {@code max(1, size >> level)}; for a block-compressed format
 * the image of a level is rounded up to whole blocks.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * TextureLayout layout = TextureLayout.texture2d(TextureFormat.R8G8B8A8_UNORM, 1024, 1024);
 * long total = layout.totalBytes();
 * long mip3 = layout.levelOffset(3);
 * TextureLayout cube = TextureLayout.cube(TextureFormat.R8G8B8A8_UNORM, 256);
 * }</pre>
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

    /**
     * Checks the arguments: a non-null format, every dimension and the layer count at least 1,
     * {@code faces} 1 or 6, {@code levels} between 1 and the number the dimensions allow, and a
     * total size that fits a {@code long}; {@link IllegalArgumentException} otherwise.
     *
     * @param format the format; may be {@code null}
     * @param width the width
     * @param height the height
     * @param depth the depth
     * @param levels the levels
     * @param layers the layers
     * @param faces the faces
     * @throws IllegalArgumentException if {@code format} is {@code null}, a dimension or the layer
     *     count is below 1, {@code faces} is not 1 or 6, or {@code levels} is out of range
     */
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

    /**
     * Refuses a texture whose total size does not fit a {@code long}: every size and offset the
     * methods below return would be wrong (negative) for it.
     */
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

    /**
     * Describes a plain two-dimensional texture with a complete mip chain down to one texel.
     *
     * @param format the format; must not be {@code null}
     * @param width the width
     * @param height the height
     * @return a 2D texture with the full mip chain
     */
    public static TextureLayout texture2d(TextureFormat format, int width, int height) {
        return new TextureLayout(format, width, height, 1, maxLevels(width, height, 1), 1, 1);
    }

    /**
     * Describes a cube map with six faces and a complete mip chain down to one texel.
     *
     * @param format the format; must not be {@code null}
     * @param size the size
     * @return a cube map with the full mip chain; {@code size} is the edge of a face
     */
    public static TextureLayout cube(TextureFormat format, int size) {
        return new TextureLayout(format, size, size, 1, maxLevels(size, size, 1), 1, 6);
    }

    /**
     * Counts the levels of a complete mip chain from the position of the highest set bit of the
     * largest dimension.
     *
     * @param width the width
     * @param height the height
     * @param depth the depth
     * @return the number of levels of a full chain:
     *     {@code floor(log2(max(width, height, depth))) + 1}
     */
    public static int maxLevels(int width, int height, int depth) {
        int m = Math.max(width, Math.max(height, depth));
        return 32 - Integer.numberOfLeadingZeros(m);
    }

    /**
     * Halves a dimension once per mip level, never going below one texel, which is how mip
     * dimensions are defined.
     *
     * @param size the size
     * @param level the level
     * @return the size of a dimension at {@code level}: {@code max(1, size >> level)}
     */
    public static int levelSize(int size, int level) {
        return Math.max(1, size >> level);
    }

    /**
     * Computes the mip dimension of one level; levels the texture does not have are rejected.
     *
     * @param level the level
     * @return the width in texels of {@code level} (0 is the finest);
     *     {@link IndexOutOfBoundsException} for a level the texture does not have
     */
    public int levelWidth(int level) {
        checkLevel(level);
        return levelSize(width, level);
    }

    /**
     * Computes the mip dimension of one level; levels the texture does not have are rejected.
     *
     * @param level the level
     * @return the height in texels of {@code level}
     */
    public int levelHeight(int level) {
        checkLevel(level);
        return levelSize(height, level);
    }

    /**
     * Computes the mip dimension of one level; levels the texture does not have are rejected.
     *
     * @param level the level
     * @return the depth in texels of {@code level}
     */
    public int levelDepth(int level) {
        checkLevel(level);
        return levelSize(depth, level);
    }

    /**
     * Sizes one image of a level from the block grid of its dimensions multiplied by the depth.
     *
     * @param level the level
     * @return bytes of one image (one layer, one face, all depth slices) at {@code level}
     */
    public long imageBytes(int level) {
        return format.imageBytes(levelWidth(level), levelHeight(level)) * levelDepth(level);
    }

    /**
     * Sizes a whole level by multiplying the image size by the number of layers and faces.
     *
     * @param level the level
     * @return bytes of all layers and faces at {@code level}
     */
    public long levelBytes(int level) {
        return imageBytes(level) * layers * faces;
    }

    /**
     * Sums the sizes of all coarser-numbered finer levels that precede the level in the tightly
     * packed layout; cost grows with the level number.
     *
     * @param level the level
     * @return byte offset of the first byte of {@code level} in the tightly packed whole
     */
    public long levelOffset(int level) {
        checkLevel(level);
        long offset = 0;
        for (int l = 0; l < level; l++) {
            offset += levelBytes(l);
        }
        return offset;
    }

    /**
     * Addresses one image inside a level of the tightly packed layout, where layers are the outer
     * and faces the inner index; out-of-range indices are rejected.
     *
     * @param level the level
     * @param layer the layer
     * @param face the face index
     * @return byte offset of the image of {@code layer} and {@code face} at {@code level}
     * @throws IndexOutOfBoundsException if {@code layer} or {@code face} is out of range
     */
    public long imageOffset(int level, int layer, int face) {
        if (layer < 0 || layer >= layers || face < 0 || face >= faces) {
            throw new IndexOutOfBoundsException("layer " + layer + " of " + layers + ", face " + face + " of " + faces);
        }
        return levelOffset(level) + (layer * (long) faces + face) * imageBytes(level);
    }

    /**
     * Sizes the whole texture as the end offset of the last level.
     *
     * @return bytes of the whole texture, every level, layer and face
     */
    public long totalBytes() {
        return levelOffset(levels - 1) + levelBytes(levels - 1);
    }

    private void checkLevel(int level) {
        if (level < 0 || level >= levels) {
            throw new IndexOutOfBoundsException("level " + level + " of " + levels);
        }
    }
}
