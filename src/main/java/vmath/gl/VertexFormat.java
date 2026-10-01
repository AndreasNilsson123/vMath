package vmath.gl;

import vmath.annotations.Experimental;

/**
 * The format of one vertex attribute as it sits in a vertex buffer, with the numbers both graphics APIs need to describe it: the OpenGL component count, type
 * and normalization flag (for {@code glVertexAttribFormat} / {@code glVertexAttribIFormat}), the Vulkan {@code VkFormat} value, and the GLSL type the shader
 * declares. The names follow the component type and width: {@code FLOAT32X3} is {@code vec3}, {@code SNORM16X2} is two signed normalized 16-bit integers read as a
 * {@code vec2}, {@code UINT8X4} is four unsigned bytes read as a {@code uvec4}.
 *
 * <p>The GL type and Vulkan format numbers are the values of the OpenGL and Vulkan registries; the tests pin them.
 */
@Experimental("the set of formats may grow")
public enum VertexFormat {
    FLOAT32(1, 4, 0x1406, false, false, 100, "float"),
    FLOAT32X2(2, 4, 0x1406, false, false, 103, "vec2"),
    FLOAT32X3(3, 4, 0x1406, false, false, 106, "vec3"),
    FLOAT32X4(4, 4, 0x1406, false, false, 109, "vec4"),
    FLOAT16X2(2, 2, 0x140B, false, false, 83, "vec2"),
    FLOAT16X4(4, 2, 0x140B, false, false, 97, "vec4"),
    UNORM8X4(4, 1, 0x1401, true, false, 37, "vec4"),
    SNORM8X4(4, 1, 0x1400, true, false, 38, "vec4"),
    UINT8X4(4, 1, 0x1401, false, true, 41, "uvec4"),
    UNORM16X2(2, 2, 0x1403, true, false, 77, "vec2"),
    SNORM16X2(2, 2, 0x1402, true, false, 78, "vec2"),
    UNORM16X4(4, 2, 0x1403, true, false, 91, "vec4"),
    SNORM16X4(4, 2, 0x1402, true, false, 92, "vec4"),
    UINT32(1, 4, 0x1405, false, true, 98, "uint"),
    UINT32X2(2, 4, 0x1405, false, true, 101, "uvec2"),
    UINT32X3(3, 4, 0x1405, false, true, 104, "uvec3"),
    UINT32X4(4, 4, 0x1405, false, true, 107, "uvec4"),
    SINT32(1, 4, 0x1404, false, true, 99, "int"),
    SINT32X2(2, 4, 0x1404, false, true, 102, "ivec2"),
    SINT32X3(3, 4, 0x1404, false, true, 105, "ivec3"),
    SINT32X4(4, 4, 0x1404, false, true, 108, "ivec4");

    private final int components;
    private final int componentBytes;
    private final int glType;
    private final boolean normalized;
    private final boolean integer;
    private final int vkFormat;
    private final String glsl;

    VertexFormat(int components, int componentBytes, int glType, boolean normalized, boolean integer, int vkFormat, String glsl) {
        this.components = components;
        this.componentBytes = componentBytes;
        this.glType = glType;
        this.normalized = normalized;
        this.integer = integer;
        this.vkFormat = vkFormat;
        this.glsl = glsl;
    }

    /** Number of components (1 to 4). */
    public int components() {
        return components;
    }

    /** Bytes of one component. */
    public int componentBytes() {
        return componentBytes;
    }

    /** Bytes of the whole attribute. */
    public int bytes() {
        return components * componentBytes;
    }

    /** The alignment the attribute's offset needs: its component size (what both APIs require). */
    public int alignment() {
        return componentBytes;
    }

    /** The OpenGL type enum of a component ({@code GL_FLOAT}, {@code GL_HALF_FLOAT}, {@code GL_UNSIGNED_BYTE}, and so on). */
    public int glType() {
        return glType;
    }

    /** The {@code normalized} argument of {@code glVertexAttribFormat}; always {@code false} for the integer formats. */
    public boolean normalized() {
        return normalized;
    }

    /** {@code true} if the shader sees integers: use {@code glVertexAttribIFormat}, not {@code glVertexAttribFormat}. */
    public boolean integer() {
        return integer;
    }

    /** The Vulkan {@code VkFormat} enum value. */
    public int vkFormat() {
        return vkFormat;
    }

    /** The GLSL type of the shader input that reads this attribute. */
    public String glsl() {
        return glsl;
    }
}
