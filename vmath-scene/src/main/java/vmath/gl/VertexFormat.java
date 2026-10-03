package vmath.gl;

import vmath.annotations.Experimental;

/**
 * The format of one vertex attribute as it sits in a vertex buffer, with the numbers both graphics APIs need to describe it: the OpenGL component count, type
 * and normalization flag (for {@code glVertexAttribFormat} / {@code glVertexAttribIFormat}), the Vulkan {@code VkFormat} value, and the GLSL type the shader
 * declares. The names follow the component type and width: {@code FLOAT32X3} is {@code vec3}, {@code SNORM16X2} is two signed normalized 16-bit integers read as a
 * {@code vec2}, {@code UINT8X4} is four unsigned bytes read as a {@code uvec4}.
 *
 * <p>The GL type and Vulkan format numbers were entered by hand from the OpenGL and Vulkan registries; the tests pin the ones they spell out and compare the shared Vulkan
 * numbers with {@code TextureFormat}, but no test reads the Khronos headers (docs/technical-debt.md, TD-01).
 */
@Experimental("the set of formats may grow")
public enum VertexFormat {
    /** One 32-bit float, read as {@code float} (4 bytes; Vulkan format 100). */
    FLOAT32(1, 4, 0x1406, false, false, 100, "float"),
    /** Two 32-bit floats, read as {@code vec2} (8 bytes; Vulkan format 103). */
    FLOAT32X2(2, 4, 0x1406, false, false, 103, "vec2"),
    /** Three 32-bit floats, read as {@code vec3} (12 bytes; Vulkan format 106). */
    FLOAT32X3(3, 4, 0x1406, false, false, 106, "vec3"),
    /** Four 32-bit floats, read as {@code vec4} (16 bytes; Vulkan format 109). */
    FLOAT32X4(4, 4, 0x1406, false, false, 109, "vec4"),
    /** Two half floats, read as {@code vec2} (4 bytes; Vulkan format 83). */
    FLOAT16X2(2, 2, 0x140B, false, false, 83, "vec2"),
    /** Four half floats, read as {@code vec4} (8 bytes; Vulkan format 97). */
    FLOAT16X4(4, 2, 0x140B, false, false, 97, "vec4"),
    /** Four unsigned normalized 8-bit integers, read as {@code vec4} (4 bytes; Vulkan format 37). */
    UNORM8X4(4, 1, 0x1401, true, false, 37, "vec4"),
    /** Four signed normalized 8-bit integers, read as {@code vec4} (4 bytes; Vulkan format 38). */
    SNORM8X4(4, 1, 0x1400, true, false, 38, "vec4"),
    /** Four unsigned 8-bit integers, read as {@code uvec4} (4 bytes; Vulkan format 41). */
    UINT8X4(4, 1, 0x1401, false, true, 41, "uvec4"),
    /** Two unsigned normalized 16-bit integers, read as {@code vec2} (4 bytes; Vulkan format 77). */
    UNORM16X2(2, 2, 0x1403, true, false, 77, "vec2"),
    /** Two signed normalized 16-bit integers, read as {@code vec2} (4 bytes; Vulkan format 78). */
    SNORM16X2(2, 2, 0x1402, true, false, 78, "vec2"),
    /** Four unsigned normalized 16-bit integers, read as {@code vec4} (8 bytes; Vulkan format 91). */
    UNORM16X4(4, 2, 0x1403, true, false, 91, "vec4"),
    /** Four signed normalized 16-bit integers, read as {@code vec4} (8 bytes; Vulkan format 92). */
    SNORM16X4(4, 2, 0x1402, true, false, 92, "vec4"),
    /** One unsigned 32-bit integer, read as {@code uint} (4 bytes; Vulkan format 98). */
    UINT32(1, 4, 0x1405, false, true, 98, "uint"),
    /** Two unsigned 32-bit integers, read as {@code uvec2} (8 bytes; Vulkan format 101). */
    UINT32X2(2, 4, 0x1405, false, true, 101, "uvec2"),
    /** Three unsigned 32-bit integers, read as {@code uvec3} (12 bytes; Vulkan format 104). */
    UINT32X3(3, 4, 0x1405, false, true, 104, "uvec3"),
    /** Four unsigned 32-bit integers, read as {@code uvec4} (16 bytes; Vulkan format 107). */
    UINT32X4(4, 4, 0x1405, false, true, 107, "uvec4"),
    /** One signed 32-bit integer, read as {@code int} (4 bytes; Vulkan format 99). */
    SINT32(1, 4, 0x1404, false, true, 99, "int"),
    /** Two signed 32-bit integers, read as {@code ivec2} (8 bytes; Vulkan format 102). */
    SINT32X2(2, 4, 0x1404, false, true, 102, "ivec2"),
    /** Three signed 32-bit integers, read as {@code ivec3} (12 bytes; Vulkan format 105). */
    SINT32X3(3, 4, 0x1404, false, true, 105, "ivec3"),
    /** Four signed 32-bit integers, read as {@code ivec4} (16 bytes; Vulkan format 108). */
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
