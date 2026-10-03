    package vmath.gpucull;

import vmath.annotations.Experimental;
import vmath.annotations.GpuStruct;
import vmath.annotations.GpuUint;
import vmath.core.Vec3f;

/**
 * One object of a GPU-driven culling pass: its world-space box, the draw it belongs to and some
 * flags. 32 bytes in std430 ({@code vec3} then {@code uint}, twice, so each pair fills a 16-byte
 * slot).
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads without
 * synchronization.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * CullObject object = new CullObject(new Vec3f(-1f, 0f, -1f), 0, new Vec3f(1f, 2f, 1f), 0);   // box, draw command index, flags
 * }</pre>
 *
 * @param min       box minimum
 *
 * @param drawIndex index of the draw command whose instance list this object joins when it is
 *     visible
 * @param max       box maximum
 * @param flags     {@link GpuCullReference#OBJECT_NO_OCCLUSION}: skip the Hi-Z test (frustum only), for objects whose box is a poor stand-in or that must always be considered
 */
@Experimental("the object record may gain members")
@GpuStruct(layout = GpuStruct.Layout.STD430)
public record CullObject(Vec3f min, @GpuUint int drawIndex, Vec3f max, @GpuUint int flags) {
}
