package vmath.gpucull;

import java.lang.foreign.MemorySegment;
import vmath.annotations.Experimental;
import vmath.gl.DrawCommandBuffer;
import vmath.gl.GpuWriter;
import vmath.spatial.ConeCull;

/**
 * The CPU reference of GPU-driven <b>cluster</b> culling with continuous level of detail: for every cluster of a {@link vmath.mesh.ClusterHierarchy}, choose it or not by the
 * projected error of its level and of its parent ({@code error * pixelScale / distance}: chosen when its own is within {@code pixelBudget} and its parent's is not, the rule of
 * {@link vmath.mesh.ClusterHierarchy#select}), then frustum test its bounding sphere, back-face test it with its normal cone ({@link ConeCull#backfacing}), and Hi-Z test the box
 * around its sphere. A survivor appends one indirect draw command ({@code DrawElementsIndirect}: its {@code firstIndex} and {@code indexCount}, one instance,
 * {@code baseInstance = } the cluster index, so the vertex shader finds the cluster through {@code gl_BaseInstance}) to the command buffer; on a GPU the slot comes from an atomic
 * counter and the draw count is read by {@code glMultiDrawElementsIndirectCount}. A command that does not fit in the buffer is dropped and counted.
 *
 * <p>Reads and writes the same bytes as the shader in {@link GpuCullGlsl#clusterShader}. Every test is conservative (NaN is never a separation), so the result may keep a cluster that
 * a perfect test would drop and never drops one that is needed. The set of chosen clusters is a cut of the hierarchy, which is what makes the surface crack free (see
 * {@link vmath.mesh.ClusterHierarchy}); the frustum, cone and Hi-Z tests only remove whole clusters from it.
 *
 * <p><b>Thread safety.</b> Stateless: the pass is a static method. The {@link Counters}, the command buffer and the arrays you pass in are not
 * synchronised, so two threads must not share one of them.
 */
@Experimental("the pass structure may change")
public final class ClusterCullReference {

    private ClusterCullReference() {
    }

    /** What a pass did. */
    public static final class Counters {
        /** Counters that start at zero. */
        public Counters() {
        }

        public int clusters;
        /** Clusters whose level of detail is the right one for the view. */
        public int lodSelected;
        /** Of those, the ones inside the frustum. */
        public int inFrustum;
        /** Of those, the ones removed as back facing. */
        public int backFacing;
        /** Of the remaining ones, the ones hidden by the Hi-Z pyramid. */
        public int occluded;
        /** Commands written. */
        public int drawn;
        /** Survivors dropped because the command buffer was full. */
        public int overflow;
        private final HizState hiz = new HizState();

        public void reset() {
            clusters = 0;
            lodSelected = 0;
            inFrustum = 0;
            backFacing = 0;
            occluded = 0;
            drawn = 0;
            overflow = 0;
        }
    }

    private static float f(MemorySegment s, long offset) {
        return GpuWriter.getFloat(s, offset);
    }

    /** The projected error of a cluster measured against a sphere, in pixels: {@code error * pixelScale / max(distance to the sphere's near side, 1e-4)}; infinite for an infinite error. */
    static float projected(float error, float cx, float cy, float cz, float r, float ex, float ey, float ez, float pixelScale) {
        if (error == Float.POSITIVE_INFINITY) {
            return Float.POSITIVE_INFINITY;
        }
        float dx = cx - ex, dy = cy - ey, dz = cz - ez;
        float d = Math.max((float) Math.sqrt(dx * dx + dy * dy + dz * dz) - r, 1e-4f);
        return error * pixelScale / d;
    }

    /**
     * One pass over every cluster, appending to {@code out} (cleared first). {@code hzb} may be null to skip the occlusion test.
     */
    public static void cull(MemorySegment view, MemorySegment clusters, HiZPyramid hzb, DrawCommandBuffer out, Counters counters) {
        int n = GpuWriter.getInt(view, ClusterCullViewGpu.OFFSET_CLUSTER_COUNT);
        float ex = f(view, ClusterCullViewGpu.OFFSET_EYE_PIXEL_SCALE), ey = f(view, ClusterCullViewGpu.OFFSET_EYE_PIXEL_SCALE + 4), ez = f(view, ClusterCullViewGpu.OFFSET_EYE_PIXEL_SCALE + 8);
        float pixelScale = f(view, ClusterCullViewGpu.OFFSET_EYE_PIXEL_SCALE + 12), budget = f(view, ClusterCullViewGpu.OFFSET_PIXEL_BUDGET);
        counters.hiz.load(view, ClusterCullViewGpu.OFFSET_VIEW_PROJECTION, ClusterCullViewGpu.OFFSET_NEAR_W, ClusterCullViewGpu.OFFSET_DEPTH_MODE);
        out.clear();
        for (int i = 0; i < n; i++) {
            counters.clusters++;
            long o = (long) i * ClusterCullObjectGpu.SIZE;
            long lod = o + ClusterCullObjectGpu.OFFSET_LOD_SPHERE, parent = o + ClusterCullObjectGpu.OFFSET_PARENT_SPHERE;
            float own = projected(f(clusters, o + ClusterCullObjectGpu.OFFSET_LOD_ERROR), f(clusters, lod), f(clusters, lod + 4), f(clusters, lod + 8), f(clusters, lod + 12), ex, ey, ez, pixelScale);
            float up = projected(f(clusters, o + ClusterCullObjectGpu.OFFSET_PARENT_ERROR), f(clusters, parent), f(clusters, parent + 4), f(clusters, parent + 8), f(clusters, parent + 12), ex, ey,
                    ez, pixelScale);
            if (!(own <= budget && up > budget)) {
                continue;
            }
            counters.lodSelected++;
            long s = o + ClusterCullObjectGpu.OFFSET_SPHERE;
            float cx = f(clusters, s), cy = f(clusters, s + 4), cz = f(clusters, s + 8), r = f(clusters, s + 12);
            if (!sphereInFrustum(view, cx, cy, cz, r)) {
                continue;
            }
            counters.inFrustum++;
            long c = o + ClusterCullObjectGpu.OFFSET_CONE;
            if (ConeCull.backfacing(cx, cy, cz, r, f(clusters, c), f(clusters, c + 4), f(clusters, c + 8), f(clusters, c + 12), ex, ey, ez)) {
                counters.backFacing++;
                continue;
            }
            if (hzb != null && GpuCullReference.hiddenBox(counters.hiz, cx - r, cy - r, cz - r, cx + r, cy + r, cz + r, hzb)) {
                counters.occluded++;
                continue;
            }
            if (out.count() >= out.capacity()) {
                counters.overflow++;
                continue;
            }
            out.addElements(GpuWriter.getInt(clusters, o + ClusterCullObjectGpu.OFFSET_INDEX_COUNT), 1, GpuWriter.getInt(clusters, o + ClusterCullObjectGpu.OFFSET_FIRST_INDEX), 0, i);
            counters.drawn++;
        }
    }

    private static boolean sphereInFrustum(MemorySegment view, float cx, float cy, float cz, float r) {
        for (int p = 0; p < 6; p++) {
            long po = ClusterCullViewGpu.OFFSET_PLANES + (long) p * ClusterCullViewGpu.STRIDE_PLANES;
            if (f(view, po) * cx + f(view, po + 4) * cy + f(view, po + 8) * cz + f(view, po + 12) < -r) {
                return false;
            }
        }
        return true;
    }
}
