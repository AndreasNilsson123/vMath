package vmath.gl;

/**
 * The memory layout rules a GLSL block follows. They differ in how much padding they insert:
 *
 * <ul>
 *   <li>{@link #STD140}: uniform blocks. Array elements and structs are aligned to 16 bytes, so {@code float[4]} takes 64
 *       bytes and every matrix column takes a full {@code vec4} slot.</li>
 *   <li>{@link #STD430}: storage blocks. Like std140 but arrays and structs are <em>not</em> rounded up to 16, so
 *       {@code float[4]} is 16 bytes. {@code vec3} is still aligned to 16.</li>
 *   <li>{@link #SCALAR}: {@code GL_EXT_scalar_block_layout} / Vulkan scalar layout. Everything aligns to its 4-byte
 *       component: a {@code vec3} is 12 bytes and a {@code mat3} is 36.</li>
 * </ul>
 */
public enum GpuLayout {
    STD140("std140"),
    STD430("std430"),
    SCALAR("scalar");

    private final String glslName;

    GpuLayout(String glslName) {
        this.glslName = glslName;
    }

    /** The name used in {@code layout(...)} qualifiers. */
    public String glslName() {
        return glslName;
    }
}
