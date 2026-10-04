package vmath.samples.demos.gpucull;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import vmath.bulk.BoundsArray;
import vmath.camera.Cameraf;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.core.Vec4f;
import vmath.geo.Frustumf;
import vmath.gpucull.CullObject;
import vmath.gpucull.CullObjectGpu;
import vmath.gpucull.CullView;
import vmath.gpucull.CullViewGpu;
import vmath.gpucull.GpuCullReference;

/**
 * Writes the inputs of the library's GPU culling pass in the layout of its structs: the objects as a
 * {@code CullObject} array and a view as a {@code CullView} block.
 *
 * <p>Every box of the scene becomes one object, all of them belonging to draw 0, which is the draw
 * of the unit cube. The last box is the ground, which is flagged to skip the depth test (its box is
 * a poor stand-in for a plane). The same bytes go to the GPU buffers and to the CPU reference, so a
 * difference between them cannot come from the data.
 *
 * <p>The class does not use OpenGL.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Stateless: the methods may be called from any number of threads.
 */
final class CullData {

    private CullData() {
    }

    /**
     * Writes the {@code CullObject} record of every box.
     *
     * @param arena owns the segment; must not be {@code null}
     * @param bounds the boxes, the last of which is the ground; must not be {@code null}
     * @return the segment, {@code bounds.size() * CullObjectGpu.SIZE} bytes
     */
    static MemorySegment writeObjects(Arena arena, BoundsArray bounds) {
        int n = bounds.size();
        MemorySegment seg = arena.allocate(n * CullObjectGpu.SIZE, 16);
        for (int i = 0; i < n; i++) {
            int flags = i == n - 1 ? GpuCullReference.OBJECT_NO_OCCLUSION : 0;
            CullObjectGpu.write(new CullObject(new Vec3f(bounds.minX(i), bounds.minY(i), bounds.minZ(i)), 0, new Vec3f(bounds.maxX(i), bounds.maxY(i), bounds.maxZ(i)), flags), seg, i * CullObjectGpu.SIZE);
        }
        return seg;
    }

    /**
     * Writes a {@code CullView}: the frustum planes of one camera, the view projection that made
     * the depth image of the pyramid (which is the previous frame's camera in a one-pass cull) and
     * the size of the pyramid.
     *
     * @param view receives the block; at least {@code CullViewGpu.SIZE} bytes; must not be
     *     {@code null}
     * @param frustumCamera the camera whose frustum is tested; must not be {@code null}
     * @param depthImageMatrix the matrix that produced the depth image; must not be {@code null}
     * @param objectCount the number of objects
     * @param hzbWidth the width of level 0 of the pyramid
     * @param hzbHeight the height of level 0
     * @param hzbLevels the number of levels
     * @param nearW the clip-space {@code w} at or below which a box is not occlusion tested; a huge
     *     value switches the occlusion test off, since every box then counts as crossing the camera
     *     plane
     */
    static void writeView(MemorySegment view, Cameraf frustumCamera, Mat4f depthImageMatrix, int objectCount, int hzbWidth, int hzbHeight, int hzbLevels, float nearW) {
        Frustumf f = frustumCamera.frustum();
        Vec4f[] planes = new Vec4f[6];
        for (int i = 0; i < 6; i++) {
            planes[i] = new Vec4f(f.plane(i).nx(), f.plane(i).ny(), f.plane(i).nz(), f.plane(i).d());
        }
        CullViewGpu.write(new CullView(planes, depthImageMatrix, objectCount, hzbWidth, hzbHeight, hzbLevels, nearW, 0, 0), view, 0);
    }
}
