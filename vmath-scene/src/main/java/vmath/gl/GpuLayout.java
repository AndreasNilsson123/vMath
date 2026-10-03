package vmath.gl;

/**
 * The memory layout rules a GLSL block follows.
 *
 * <p>They differ in how much padding they insert:
 *
 * <ul>
 *   <li>{@link #STD140}: uniform blocks. Array elements and structs are aligned to 16 bytes, so
 *       {@code float[4]} takes 64 bytes and every matrix column takes a full {@code vec4}
 *       slot.</li>
 *   <li>{@link #STD430}: storage blocks. Like std140 but arrays and structs are <em>not</em>
 *       rounded up to 16, so {@code float[4]} is 16 bytes. {@code vec3} is still aligned to
 *       16.</li>
 *   <li>{@link #SCALAR}: {@code GL_EXT_scalar_block_layout} / Vulkan scalar layout. Everything
 *       aligns to its 4-byte component: a {@code vec3} is 12 bytes and a {@code mat3} is 36.</li>
 * </ul>
 *
 * <p><b>Thread safety.</b> Immutable: the constants can be shared between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * GpuLayout layout = GpuLayout.STD430;
 * GlslType.Struct block = new GlslType.Struct("Particle", List.of(new GlslType.Member("position", GlslType.VEC3), new GlslType.Member("life", GlslType.FLOAT)));
 * StructLayout offsets = block.layout(layout);
 * long lifeOffset = offsets.offsetOf("life");                          // 12: std430 packs the float into the vec3 slot
 * }</pre>
 */
public enum GpuLayout {
    /**
     * The uniform block layout, with 16-byte rounding of arrays and structs.
     */
    STD140("std140"),
    /**
     * The storage block layout: std140 without the rounding of arrays and structs.
     */
    STD430("std430"),
    /**
     * The scalar block layout, everything aligned to its 4-byte component.
     */
    SCALAR("scalar");

    private final String glslName;

    GpuLayout(String glslName) {
        this.glslName = glslName;
    }

    /**
     * Exposes the identifier that the layout has in {@code layout(...)} qualifiers.
     *
     * @return the name used in {@code layout(...)} qualifiers
     */
    public String glslName() {
        return glslName;
    }
}
