package vmath.pack;

/**
 * The compact formats of this package, with the token each graphics API uses for them, so buffers and textures can be created without
 * pulling in a graphics binding. {@code glInternalFormat} is the OpenGL sized internal format ({@code GL_RGBA16F} and so on);
 * {@code vkFormat} is the numeric {@code VkFormat}. The values were checked against the Khronos {@code glcorearb.h} and
 * {@code vulkan_core.h} headers.
 *
 * <p>{@code bytesPerTexel} is the size of one element in memory, which is also the vertex attribute size for the same format.
 */
public enum PackedFormat {

    R8_UNORM(0x8229, 9, 1),
    RG8_UNORM(0x822B, 16, 2),
    RGBA8_UNORM(0x8058, 37, 4),
    RGBA8_SNORM(0x8F97, 38, 4),

    R16_UNORM(0x822A, 70, 2),
    RG16_UNORM(0x822C, 77, 4),
    RG16_SNORM(0x8F99, 78, 4),
    RGBA16_UNORM(0x805B, 91, 8),
    RGBA16_SNORM(0x8F9B, 92, 8),

    R16_SFLOAT(0x822D, 76, 2),
    RG16_SFLOAT(0x822F, 83, 4),
    RGBA16_SFLOAT(0x881A, 97, 8),

    R32_SFLOAT(0x822E, 100, 4),
    RG32_SFLOAT(0x8230, 103, 8),
    RGBA32_SFLOAT(0x8814, 109, 16),

    /** {@link Norm#packRgb10A2}. */
    RGB10_A2_UNORM(0x8059, 64, 4),
    /** {@link Norm#packRgb10A2Snorm}; Vulkan has it, OpenGL has no sized internal format for it (0 here). */
    RGB10_A2_SNORM(0, 65, 4),
    /** {@link SmallFloat#packR11G11B10F}. */
    R11G11B10_UFLOAT(0x8C3A, 122, 4),
    /** {@link SmallFloat#packRgb9E5}. */
    RGB9_E5_UFLOAT(0x8C3D, 123, 4);

    private final int glInternalFormat;
    private final int vkFormat;
    private final int bytesPerTexel;

    PackedFormat(int glInternalFormat, int vkFormat, int bytesPerTexel) {
        this.glInternalFormat = glInternalFormat;
        this.vkFormat = vkFormat;
        this.bytesPerTexel = bytesPerTexel;
    }

    /** OpenGL sized internal format, or 0 if OpenGL has none. */
    public int glInternalFormat() {
        return glInternalFormat;
    }

    /** The numeric {@code VkFormat}. */
    public int vkFormat() {
        return vkFormat;
    }

    public int bytesPerTexel() {
        return bytesPerTexel;
    }
}
