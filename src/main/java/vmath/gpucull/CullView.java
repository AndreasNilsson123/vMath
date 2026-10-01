package vmath.gpucull;

import vmath.annotations.Experimental;
import vmath.annotations.GpuArray;
import vmath.annotations.GpuStruct;
import vmath.annotations.GpuUint;
import vmath.core.Mat4f;
import vmath.core.Vec4f;

/**
 * Per-view parameters of a GPU-driven culling pass, a std140 uniform block: the six frustum planes (inward normals, {@code vec4(normal, d)}, in the order of
 * {@link vmath.geo.Frustumf#plane}), the view-projection matrix, the number of objects, the size of the Hi-Z pyramid and the conventions.
 *
 * @param planes         the six planes; a plane with a zero normal and {@code d >= 0} (an infinite far plane) never rejects
 * @param viewProjection the matrix that produced the depth image of the pyramid
 * @param objectCount    number of valid entries in the object buffer
 * @param hzbWidth       width of level 0 of the pyramid, texels
 * @param hzbHeight      height of level 0
 * @param hzbLevels      number of levels
 * @param nearW          clip-space {@code w} below which a box counts as crossing the camera plane and is not occlusion tested (it is visible); a small positive value
 * @param depthMode      index of the {@link vmath.geo.DepthRange} of the depth image: 0 for -1..1, 1 for 0..1, 2 for reversed-Z
 * @param flags          {@link GpuCullReference#VIEW_Y_DOWN}: row 0 of the pyramid is the top of the screen
 */
@Experimental("the view record may gain members")
@GpuStruct(layout = GpuStruct.Layout.STD140)
public record CullView(@GpuArray(6) Vec4f[] planes, Mat4f viewProjection, @GpuUint int objectCount, @GpuUint int hzbWidth, @GpuUint int hzbHeight,
                       @GpuUint int hzbLevels, float nearW, @GpuUint int depthMode, @GpuUint int flags) {
}
