package vmath.gl;

import vmath.annotations.GpuStruct;
import vmath.core.Mat4x3f;
import vmath.core.Vec3i;

/** Scalar layout: nothing is padded. */
@GpuStruct(layout = GpuStruct.Layout.SCALAR)
record Placement(Mat4x3f model, Vec3i cell) {
}
