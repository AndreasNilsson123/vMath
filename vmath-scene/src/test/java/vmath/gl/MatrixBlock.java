package vmath.gl;

import vmath.annotations.GpuStruct;
import vmath.core.Mat3f;
import vmath.core.Mat4x3f;
import vmath.core.Vec2f;

/** Matrices in each shape the writer supports, plus a 2-vector. */
@GpuStruct
record MatrixBlock(Mat3f normal, Mat4x3f model, Vec2f uvScale) {
}
