package vmath.gl;

import vmath.annotations.GpuStruct;
import vmath.annotations.GpuUint;
import vmath.core.Vec3f;

/** A storage-buffer element: std430 packs it into 32 bytes with no padding. */
@GpuStruct(layout = GpuStruct.Layout.STD430)
record Particle(Vec3f position, float life, Vec3f velocity, @GpuUint int flags) {
}
