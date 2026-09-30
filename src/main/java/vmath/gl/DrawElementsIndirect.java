package vmath.gl;

import vmath.annotations.GpuStruct;
import vmath.annotations.GpuUint;

/**
 * One command of {@code glDrawElementsIndirect} / {@code glMultiDrawElementsIndirect}, and the same five 32-bit words as Vulkan's
 * {@code VkDrawIndexedIndirectCommand}. 20 bytes, tightly packed (every member is a 4-byte scalar, so std430 and scalar layouts agree).
 *
 * @param count         indices to draw (Vulkan: {@code indexCount})
 * @param instanceCount instances to draw
 * @param firstIndex    first index in the bound index buffer, counted in indices, not bytes
 * @param baseVertex    signed value added to every index before it selects a vertex (Vulkan: {@code vertexOffset})
 * @param baseInstance  value added to the instance index (Vulkan: {@code firstInstance})
 */
@GpuStruct(layout = GpuStruct.Layout.STD430)
public record DrawElementsIndirect(@GpuUint int count, @GpuUint int instanceCount, @GpuUint int firstIndex, int baseVertex,
                                   @GpuUint int baseInstance) {
}
