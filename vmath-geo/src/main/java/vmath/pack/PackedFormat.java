package vmath.pack;

/**
 * The compact formats of this package, with the token each graphics API uses for them, so buffers
 * and textures can be created without pulling in a graphics binding.
 *
 * <p>{@code glInternalFormat} is the OpenGL sized internal format ({@code GL_RGBA16F} and so on);
 * {@code vkFormat} is the numeric {@code VkFormat}. The values were entered by hand from the OpenGL
 * and Vulkan registries and compared in tests with the numbers {@code TextureFormat} uses for the
 * formats they share; no test reads the Khronos headers (docs/technical-debt.md, TD-01).
 *
 * <p>{@code bytesPerTexel} is the size of one element in memory, which is also the vertex attribute
 * size for the same format.
 *
 * <p><b>Thread safety.</b> Immutable: the constants can be shared between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * PackedFormat format = PackedFormat.values()[0];
 * int glFormat = format.glInternalFormat();                              // 0 when OpenGL has none
 * int vkFormat = format.vkFormat();
 * int bytes = format.bytesPerTexel();
 * }</pre>
 */
public enum PackedFormat {

    /**
     * One 8-bit unsigned normalized channel.
     */
    R8_UNORM(0x8229, 9, 1),
    /**
     * Two 8-bit unsigned normalized channels.
     */
    RG8_UNORM(0x822B, 16, 2),
    /**
     * Four 8-bit unsigned normalized channels.
     */
    RGBA8_UNORM(0x8058, 37, 4),
    /**
     * Four 8-bit signed normalized channels.
     */
    RGBA8_SNORM(0x8F97, 38, 4),

    /**
     * One 16-bit unsigned normalized channel.
     */
    R16_UNORM(0x822A, 70, 2),
    /**
     * Two 16-bit unsigned normalized channels.
     */
    RG16_UNORM(0x822C, 77, 4),
    /**
     * Two 16-bit signed normalized channels.
     */
    RG16_SNORM(0x8F99, 78, 4),
    /**
     * Four 16-bit unsigned normalized channels.
     */
    RGBA16_UNORM(0x805B, 91, 8),
    /**
     * Four 16-bit signed normalized channels.
     */
    RGBA16_SNORM(0x8F9B, 92, 8),

    /**
     * One half-float channel.
     */
    R16_SFLOAT(0x822D, 76, 2),
    /**
     * Two half-float channels.
     */
    RG16_SFLOAT(0x822F, 83, 4),
    /**
     * Four half-float channels.
     */
    RGBA16_SFLOAT(0x881A, 97, 8),

    /**
     * One 32-bit float channel.
     */
    R32_SFLOAT(0x822E, 100, 4),
    /**
     * Two 32-bit float channels.
     */
    RG32_SFLOAT(0x8230, 103, 8),
    /**
     * Four 32-bit float channels.
     */
    RGBA32_SFLOAT(0x8814, 109, 16),

    /**
     * {@link Norm#packRgb10A2}.
     */
    RGB10_A2_UNORM(0x8059, 64, 4),
    /**
     * {@link Norm#packRgb10A2Snorm}; Vulkan has it, OpenGL has no sized internal format for it (0
     * here).
     */
    RGB10_A2_SNORM(0, 65, 4),
    /**
     * {@link SmallFloat#packR11G11B10F}.
     */
    R11G11B10_UFLOAT(0x8C3A, 122, 4),
    /**
     * {@link SmallFloat#packRgb9E5}.
     */
    RGB9_E5_UFLOAT(0x8C3D, 123, 4);

    private final int glInternalFormat;
    private final int vkFormat;
    private final int bytesPerTexel;

    PackedFormat(int glInternalFormat, int vkFormat, int bytesPerTexel) {
        this.glInternalFormat = glInternalFormat;
        this.vkFormat = vkFormat;
        this.bytesPerTexel = bytesPerTexel;
    }

    /**
     * Looks up the OpenGL sized internal format of this packed format; not every format has one.
     *
     * @return OpenGL sized internal format, or 0 if OpenGL has none
     */
    public int glInternalFormat() {
        return glInternalFormat;
    }

    /**
     * Exposes the Vulkan format identifier of this packed format.
     *
     * @return the numeric {@code VkFormat}
     */
    public int vkFormat() {
        return vkFormat;
    }

    /**
     * Exposes the size of one element, which equals the size of a vertex attribute of this format.
     *
     * @return the size of one element in bytes, which is also the vertex attribute size of the same
     *     format
     */
    public int bytesPerTexel() {
        return bytesPerTexel;
    }
}
