package vmath.gl;

import vmath.annotations.GpuArray;
import vmath.annotations.GpuStruct;

/** A struct containing an array of structs, in scalar layout like its element. */
@GpuStruct(layout = GpuStruct.Layout.SCALAR)
record Batch(@GpuArray(3) Placement[] items, int count) {
}
