package vmath.gpucull;

import vmath.annotations.Experimental;
import vmath.annotations.GpuStruct;
import vmath.annotations.GpuUint;
import vmath.core.Vec4f;
import vmath.mesh.ClusterHierarchy;

/**
 * One cluster of a {@link ClusterHierarchy} as a GPU-driven cluster culling pass reads it: 80 bytes in std430 (four {@code vec4}s, two floats, two uints).
 *
 * @param sphere       {@code xyz}: centre, {@code w}: radius of the bounding sphere of the cluster's own geometry (for the frustum, cone and Hi-Z tests)
 * @param cone         {@code xyz}: normal cone axis, {@code w}: cutoff (the sine of its half angle, 1 means "never back-face culled"), as for {@link vmath.spatial.ConeCull}
 * @param lodSphere    {@code xyz} and {@code w}: the sphere that the error of this cluster is measured against (see {@link ClusterHierarchy#lodError})
 * @param parentSphere the same for the group that replaces this cluster; the radius is irrelevant when {@code parentError} is infinite
 * @param lodError     error of this cluster's simplification, 0 for the original detail
 * @param parentError  error of the parent group, {@code +Infinity} for a root
 * @param firstIndex   first index of the cluster in the shared index buffer
 * @param indexCount   number of indices (three per triangle)
 */
@Experimental("the cluster record may gain members")
@GpuStruct(layout = GpuStruct.Layout.STD430)
public record ClusterCullObject(Vec4f sphere, Vec4f cone, Vec4f lodSphere, Vec4f parentSphere, float lodError, float parentError, @GpuUint int firstIndex,
                                @GpuUint int indexCount) {

    /** The record for cluster {@code c} of a hierarchy, whose indices start at {@code firstIndex} of the shared index buffer. */
    public static ClusterCullObject of(ClusterHierarchy h, int c, int firstIndex) {
        return new ClusterCullObject(new Vec4f(h.sphereX(c), h.sphereY(c), h.sphereZ(c), h.sphereRadius(c)), new Vec4f(h.coneAxisX(c), h.coneAxisY(c), h.coneAxisZ(c), h.coneCutoff(c)),
                new Vec4f(h.lodCenterX(c), h.lodCenterY(c), h.lodCenterZ(c), h.lodRadius(c)), new Vec4f(h.parentCenterX(c), h.parentCenterY(c), h.parentCenterZ(c), h.parentRadius(c)),
                h.lodError(c), h.parentError(c), firstIndex, h.triangleCount(c) * 3);
    }
}
