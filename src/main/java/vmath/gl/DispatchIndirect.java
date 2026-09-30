package vmath.gl;

import vmath.annotations.GpuStruct;
import vmath.annotations.GpuUint;

/**
 * The argument of {@code glDispatchComputeIndirect} and Vulkan's {@code VkDispatchIndirectCommand}: the number of work groups in each
 * dimension. 12 bytes, tightly packed.
 *
 * @param x work groups along x
 * @param y work groups along y
 * @param z work groups along z
 */
@GpuStruct(layout = GpuStruct.Layout.STD430)
public record DispatchIndirect(@GpuUint int x, @GpuUint int y, @GpuUint int z) {
}
