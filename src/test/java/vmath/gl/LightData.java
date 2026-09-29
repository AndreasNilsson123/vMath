package vmath.gl;

import vmath.annotations.GpuArray;
import vmath.annotations.GpuStruct;
import vmath.core.Quatf;
import vmath.core.Vec3f;
import vmath.core.Vec4f;

/** vec3 + float packing, an array of vec4 and a quaternion. */
@GpuStruct(layout = GpuStruct.Layout.STD140)
record LightData(Vec3f position, float radius, Vec4f color, @GpuArray(4) Vec4f[] cascades, Quatf orientation) {
}
