package vmath.samples.demos.clusters;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import vmath.camera.Cameraf;
import vmath.core.Mat4f;
import vmath.core.Vec4f;
import vmath.geo.Frustumf;
import vmath.gpucull.ClusterCullObject;
import vmath.gpucull.ClusterCullObjectGpu;
import vmath.gpucull.ClusterCullView;
import vmath.gpucull.ClusterCullViewGpu;
import vmath.mesh.ClusterHierarchy;
import vmath.mesh.Mesh;
import vmath.mesh.Primitives;
import vmath.util.Noise;

/**
 * A dense rock and its cluster hierarchy, with everything that the culling needs in the layout of
 * the library's GPU structs.
 *
 * <p>The rock is an icosphere whose vertices are pushed in and out by fractal noise, so it has
 * ridges at every scale; {@code ClusterHierarchy.build} cuts it into clusters of at most 64 vertices
 * and 124 triangles, groups and simplifies them level by level. The scene holds the shared index
 * buffer (every cluster's indices one after the other), the {@code ClusterCullObject} records
 * written into a memory segment, and a method that writes a {@code ClusterCullView} for a camera.
 *
 * <p>The class does not use OpenGL.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Immutable after construction, except for the view segments that
 * {@link #writeView} fills, which the caller owns.
 */
final class ClusterScene {

    private final ClusterHierarchy hierarchy;
    private final int[] indices;
    private final int[] firstIndex;
    private final float[] positions;
    private final int triangles;
    private final long buildMillis;
    private final float radius;

    /**
     * Builds the rock and its hierarchy.
     *
     * @param subdivisions the subdivisions of the icosphere, which has {@code 20 * 4^subdivisions}
     *     triangles; from 2 to 8
     */
    ClusterScene(int subdivisions) {
        Mesh mesh = Primitives.icoSphere(1f, subdivisions);
        float[] p = mesh.positions();
        for (int v = 0; v < mesh.vertexCount(); v++) {
            float x = p[v * 3], y = p[v * 3 + 1], z = p[v * 3 + 2];
            double n = Noise.fbm3(Noise.Kind.SIMPLEX, x * 2.3, y * 2.3, z * 2.3, 5, 6, 2.0, 0.5);
            float s = 1f + 0.32f * (float) n;
            mesh.setPosition(v, x * s, y * s, z * s);
        }
        triangles = mesh.triangleCount();
        long t0 = System.nanoTime();
        hierarchy = ClusterHierarchy.build(mesh, 64, 124, 4);
        buildMillis = (System.nanoTime() - t0) / 1_000_000L;
        int clusters = hierarchy.clusterCount();
        firstIndex = new int[clusters];
        int total = 0;
        for (int c = 0; c < clusters; c++) {
            firstIndex[c] = total;
            total += hierarchy.triangleCount(c) * 3;
        }
        indices = new int[total];
        for (int c = 0; c < clusters; c++) {
            int[] ix = hierarchy.indices(c);
            System.arraycopy(ix, 0, indices, firstIndex[c], ix.length);
        }
        Mesh pool = hierarchy.vertices();
        positions = java.util.Arrays.copyOf(pool.positions(), pool.vertexCount() * 3);
        float r = 0f;
        for (int v = 0; v < pool.vertexCount(); v++) {
            r = Math.max(r, (float) Math.sqrt(positions[v * 3] * positions[v * 3] + positions[v * 3 + 1] * positions[v * 3 + 1] + positions[v * 3 + 2] * positions[v * 3 + 2]));
        }
        radius = r;
    }

    ClusterHierarchy hierarchy() {
        return hierarchy;
    }

    int[] indices() {
        return indices;
    }

    int firstIndex(int cluster) {
        return firstIndex[cluster];
    }

    float[] positions() {
        return positions;
    }

    int triangles() {
        return triangles;
    }

    long buildMillis() {
        return buildMillis;
    }

    /**
     * Reads the radius of the sphere around the rock at the origin.
     *
     * @return the radius
     */
    float radius() {
        return radius;
    }

    /**
     * Writes the {@code ClusterCullObject} records of every cluster into a new segment.
     *
     * @param arena owns the segment; must not be {@code null}
     * @return the segment, {@code clusterCount * ClusterCullObjectGpu.SIZE} bytes
     */
    MemorySegment writeClusters(Arena arena) {
        MemorySegment seg = arena.allocate(hierarchy.clusterCount() * ClusterCullObjectGpu.SIZE, 16);
        for (int c = 0; c < hierarchy.clusterCount(); c++) {
            ClusterCullObjectGpu.write(ClusterCullObject.of(hierarchy, c, firstIndex[c]), seg, c * ClusterCullObjectGpu.SIZE);
        }
        return seg;
    }

    /**
     * Writes the {@code ClusterCullView} of a camera into a segment: the frustum planes, the matrix,
     * the eye, the pixel scale for the viewport height and the error budget.
     *
     * @param view receives the block; at least {@code ClusterCullViewGpu.SIZE} bytes; must not be
     *     {@code null}
     * @param camera the camera; must not be {@code null}
     * @param viewportHeight the height of the viewport in pixels
     * @param pixelBudget the projected error in pixels that a cluster may have
     * @param hzbWidth the width of level 0 of the depth pyramid
     * @param hzbHeight the height of level 0
     * @param hzbLevels the number of levels
     */
    void writeView(MemorySegment view, Cameraf camera, int viewportHeight, float pixelBudget, int hzbWidth, int hzbHeight, int hzbLevels) {
        Frustumf f = camera.frustum();
        Vec4f[] planes = new Vec4f[6];
        for (int i = 0; i < 6; i++) {
            planes[i] = new Vec4f(f.plane(i).nx(), f.plane(i).ny(), f.plane(i).nz(), f.plane(i).d());
        }
        float pixelScale = viewportHeight / (2f * (float) Math.tan(camera.fovy() * 0.5f));
        Mat4f vp = camera.viewProjection();
        ClusterCullViewGpu.write(new ClusterCullView(planes, vp, new Vec4f(camera.position().x(), camera.position().y(), camera.position().z(), pixelScale), hierarchy.clusterCount(), hzbWidth,
                hzbHeight, hzbLevels, camera.near(), pixelBudget, 0, 0), view, 0);
    }
}
