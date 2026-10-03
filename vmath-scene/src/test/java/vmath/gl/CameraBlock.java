package vmath.gl;

import vmath.annotations.GpuStruct;
import vmath.core.Mat4f;
import vmath.core.Vec3f;

/**
 * Sample structs for the generator tests: {@code @GpuStruct} records here get a {@code <Name>Gpu} class generated at build
 * time (see {@code GpuStructTest}).
 */
final class GpuSamples {

    private GpuSamples() {
    }
}

/** A typical uniform block. */
@GpuStruct
record CameraBlock(Mat4f view, Mat4f proj, Vec3f position, float time) {
}
