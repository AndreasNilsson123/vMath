package vmath.gl;

import vmath.annotations.GpuStruct;
import vmath.annotations.GpuUint;

/**
 * The argument of {@code glDispatchComputeIndirect} and Vulkan's {@code VkDispatchIndirectCommand}:
 * the number of work groups in each dimension. 12 bytes, tightly packed.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads without
 * synchronization.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * DispatchIndirect dispatch = new DispatchIndirect(64, 1, 1);          // workgroup counts of an indirect compute dispatch
 * }</pre>
 *
 * @param x work groups along x
 * @param y work groups along y
 * @param z work groups along z
 */
@GpuStruct(layout = GpuStruct.Layout.STD430)
public record DispatchIndirect(@GpuUint int x, @GpuUint int y, @GpuUint int z) {
}
