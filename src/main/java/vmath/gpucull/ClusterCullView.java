package vmath.gpucull;

import vmath.annotations.Experimental;
import vmath.annotations.GpuArray;
import vmath.annotations.GpuStruct;
import vmath.annotations.GpuUint;
import vmath.core.Mat4f;
import vmath.core.Vec4f;

/**
 * Per-view parameters of a cluster culling pass, a std140 uniform block: like {@link CullView} plus the eye and the numbers of the level-of-detail choice.
 *
 * @param planes         the six frustum planes (inward normals, {@code vec4(normal, d)})
 * @param viewProjection the matrix that produced the depth image of the Hi-Z pyramid
 * @param eyePixelScale  {@code xyz}: the eye in the space of the cluster data, {@code w}: {@code pixelScale}, {@code viewportHeight / (2 tan(fovY / 2))}
 * @param clusterCount   number of valid entries in the cluster buffer
 * @param hzbWidth       width of level 0 of the Hi-Z pyramid
 * @param hzbHeight      height of level 0
 * @param hzbLevels      number of levels
 * @param nearW          clip-space {@code w} at or below which a box is not occlusion tested
 * @param pixelBudget    the projected error, in pixels, up to which a cluster is acceptable (see {@link vmath.mesh.ClusterHierarchy#select})
 * @param depthMode      index of the {@link vmath.geo.DepthRange} of the depth image
 * @param flags          {@link GpuCullReference#VIEW_Y_DOWN}
 */
@Experimental("the view record may gain members")
@GpuStruct(layout = GpuStruct.Layout.STD140)
public record ClusterCullView(@GpuArray(6) Vec4f[] planes, Mat4f viewProjection, Vec4f eyePixelScale, @GpuUint int clusterCount, @GpuUint int hzbWidth, @GpuUint int hzbHeight,
                              @GpuUint int hzbLevels, float nearW, float pixelBudget, @GpuUint int depthMode, @GpuUint int flags) {
}
