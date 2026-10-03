package vmath.gl;

import vmath.annotations.GpuStruct;
import vmath.annotations.GpuUint;

/**
 * One command of {@code glDrawArraysIndirect} / {@code glMultiDrawArraysIndirect}, and the same
 * four 32-bit words as Vulkan's {@code VkDrawIndirectCommand}. 16 bytes, tightly packed (every
 * member is a 4-byte scalar, so std430 and scalar layouts agree).
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads without
 * synchronization.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * DrawArraysIndirect draw = new DrawArraysIndirect(36, 1, 0, 0);       // vertices, instances, first vertex, base instance
 * }</pre>
 *
 * @param count         vertices to draw (Vulkan: {@code vertexCount})
 *
 * @param instanceCount instances to draw
 * @param first         index of the first vertex (Vulkan: {@code firstVertex})
 * @param baseInstance value added to the instance index (Vulkan: {@code firstInstance}); it must be
 *     0 in GL unless {@code GL_ARB_shader_draw_parameters} / GL 4.2 base-instance support is
 *     present
 */
@GpuStruct(layout = GpuStruct.Layout.STD430)
public record DrawArraysIndirect(@GpuUint int count, @GpuUint int instanceCount, @GpuUint int first, @GpuUint int baseInstance) {
}
