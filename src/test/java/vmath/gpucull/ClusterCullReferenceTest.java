package vmath.gpucull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import vmath.core.ClipSpace;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.core.Vec4f;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;
import vmath.geo.Spheref;
import vmath.gl.DrawCommandBuffer;
import vmath.gl.GpuWriter;
import vmath.mesh.ClusterHierarchy;
import vmath.mesh.Mesh;
import vmath.mesh.MeshOptimizer;
import vmath.mesh.Primitives;
import vmath.spatial.ConeCull;

class ClusterCullReferenceTest {

    private final Random rnd = new Random(Long.getLong("vmath.seed", 8128L));

    private static Mesh welded(Mesh src) {
        Mesh m = new Mesh();
        for (int i = 0; i < src.vertexCount(); i++) {
            m.addVertex(src.positions()[i * 3], src.positions()[i * 3 + 1], src.positions()[i * 3 + 2]);
        }
        for (int t = 0; t < src.triangleCount(); t++) {
            m.addTriangle(src.indices()[t * 3], src.indices()[t * 3 + 1], src.indices()[t * 3 + 2]);
        }
        MeshOptimizer.weld(m, 1e-6f, true);
        return m;
    }

    /** The clusters as a buffer, and the concatenated index buffer they point into. */
    private record Scene(ClusterHierarchy h, MemorySegment clusters, int[] indexBuffer, int[] firstIndex) {
    }

    private static Scene scene(Mesh mesh, boolean neverConeCull) {
        ClusterHierarchy h = ClusterHierarchy.build(mesh, 64, 128, 4);
        int n = h.clusterCount();
        int[] first = new int[n];
        int total = 0;
        for (int c = 0; c < n; c++) {
            first[c] = total;
            total += h.triangleCount(c) * 3;
        }
        int[] indexBuffer = new int[total];
        MemorySegment seg = MemorySegment.ofArray(new byte[(int) (n * ClusterCullObjectGpu.SIZE)]);
        for (int c = 0; c < n; c++) {
            System.arraycopy(h.indices(c), 0, indexBuffer, first[c], h.triangleCount(c) * 3);
            ClusterCullObject o = ClusterCullObject.of(h, c, first[c]);
            if (neverConeCull) {
                o = new ClusterCullObject(o.sphere(), new Vec4f(0f, 0f, 1f, 1f), o.lodSphere(), o.parentSphere(), o.lodError(), o.parentError(), o.firstIndex(), o.indexCount());
            }
            ClusterCullObjectGpu.write(o, seg, c * ClusterCullObjectGpu.SIZE);
        }
        return new Scene(h, seg, indexBuffer, first);
    }

    private static MemorySegment view(Mat4f vp, DepthRange range, Vec3f eye, float pixelScale, float budget, int clusters, HiZPyramid hzb) {
        Frustumf f = Frustumf.fromViewProjection(vp, range);
        Vec4f[] planes = new Vec4f[6];
        for (int i = 0; i < 6; i++) {
            planes[i] = new Vec4f(f.plane(i).nx(), f.plane(i).ny(), f.plane(i).nz(), f.plane(i).d());
        }
        MemorySegment seg = MemorySegment.ofArray(new byte[(int) ClusterCullViewGpu.SIZE]);
        ClusterCullViewGpu.write(new ClusterCullView(planes, vp, new Vec4f(eye.x(), eye.y(), eye.z(), pixelScale), clusters, hzb == null ? 1 : hzb.width(0), hzb == null ? 1 : hzb.height(0),
                hzb == null ? 1 : hzb.levels(), 1e-4f, budget, range.ordinal(), 0), seg, 0);
        return seg;
    }

    private static TreeSet<Integer> chosen(DrawCommandBuffer out) {
        TreeSet<Integer> set = new TreeSet<>();
        for (int k = 0; k < out.count(); k++) {
            assertTrue(set.add(out.baseInstance(k)), "a cluster is drawn once");
            assertEquals(1, out.instanceCount(k));
        }
        return set;
    }

    @Test
    void layouts() {
        assertEquals(80, ClusterCullObjectGpu.SIZE);
        assertEquals(0, ClusterCullObjectGpu.OFFSET_SPHERE);
        assertEquals(16, ClusterCullObjectGpu.OFFSET_CONE);
        assertEquals(32, ClusterCullObjectGpu.OFFSET_LOD_SPHERE);
        assertEquals(48, ClusterCullObjectGpu.OFFSET_PARENT_SPHERE);
        assertEquals(64, ClusterCullObjectGpu.OFFSET_LOD_ERROR);
        assertEquals(68, ClusterCullObjectGpu.OFFSET_PARENT_ERROR);
        assertEquals(72, ClusterCullObjectGpu.OFFSET_FIRST_INDEX);
        assertEquals(76, ClusterCullObjectGpu.OFFSET_INDEX_COUNT);
        assertEquals(0, ClusterCullViewGpu.OFFSET_PLANES);
        assertEquals(96, ClusterCullViewGpu.OFFSET_VIEW_PROJECTION);
        assertEquals(160, ClusterCullViewGpu.OFFSET_EYE_PIXEL_SCALE);
        assertEquals(176, ClusterCullViewGpu.OFFSET_CLUSTER_COUNT);
        assertEquals(180, ClusterCullViewGpu.OFFSET_HZB_WIDTH);
        assertEquals(188, ClusterCullViewGpu.OFFSET_HZB_LEVELS);
        assertEquals(192, ClusterCullViewGpu.OFFSET_NEAR_W);
        assertEquals(196, ClusterCullViewGpu.OFFSET_PIXEL_BUDGET);
        assertEquals(200, ClusterCullViewGpu.OFFSET_DEPTH_MODE);
        assertEquals(204, ClusterCullViewGpu.OFFSET_FLAGS);
        assertEquals(208, ClusterCullViewGpu.SIZE);
    }

    @Test
    void theChosenClustersAreTheHierarchyChoiceInsideTheFrustumAndFacingTheEye() {
        Scene s = scene(welded(Primitives.icoSphere(1f, 5)), false);
        int n = s.h().clusterCount();
        float fovy = 1.0f, aspect = 16f / 9f, height = 1080f, pixelScale = height / (2f * (float) Math.tan(fovy * 0.5f));
        long compared = 0, mismatched = 0, drawnTotal = 0;
        for (int trial = 0; trial < 120; trial++) {
            Vec3f dir = new Vec3f((float) rnd.nextGaussian(), (float) rnd.nextGaussian(), (float) rnd.nextGaussian());
            Vec3f eye = dir.mul((float) Math.exp(Math.log(1.3) + rnd.nextDouble() * (Math.log(40) - Math.log(1.3))) / dir.length());
            // a camera looking towards the sphere, somewhat off the centre so that the frustum cuts through it at the near distances
            Vec3f target = new Vec3f((float) rnd.nextGaussian() * 0.3f, (float) rnd.nextGaussian() * 0.3f, (float) rnd.nextGaussian() * 0.3f);
            Mat4f vp = Mat4f.perspective(fovy, aspect, 0.05f, 200f, ClipSpace.D3D).mul(Mat4f.lookAt(eye, target, Math.abs(eye.y()) > 0.9f * eye.length() ? Vec3f.UNIT_X : Vec3f.UNIT_Y));
            float budget = new float[] {0.5f, 1f, 2f, 8f, 40f}[rnd.nextInt(5)];
            MemorySegment v = view(vp, DepthRange.ZERO_TO_ONE, eye, pixelScale, budget, n, null);
            MemorySegment commandMemory = MemorySegment.ofArray(new byte[n * 20]);
            DrawCommandBuffer out = new DrawCommandBuffer(commandMemory, DrawCommandBuffer.Kind.ELEMENTS, false);
            ClusterCullReference.Counters counters = new ClusterCullReference.Counters();
            ClusterCullReference.cull(v, s.clusters(), null, out, counters);
            // the oracle from the hierarchy's own accessors and the library's predicates
            int[] lod = new int[n];
            int selected = s.h().select(eye.x(), eye.y(), eye.z(), pixelScale, budget, lod);
            Frustumf frustum = Frustumf.fromViewProjection(vp, DepthRange.ZERO_TO_ONE);
            TreeSet<Integer> expected = new TreeSet<>();
            for (int k = 0; k < selected; k++) {
                int c = lod[k];
                if (frustum.intersects(new Spheref(s.h().sphereX(c), s.h().sphereY(c), s.h().sphereZ(c), s.h().sphereRadius(c)))
                        && !ConeCull.backfacing(s.h().sphereX(c), s.h().sphereY(c), s.h().sphereZ(c), s.h().sphereRadius(c), s.h().coneAxisX(c), s.h().coneAxisY(c), s.h().coneAxisZ(c),
                        s.h().coneCutoff(c), eye.x(), eye.y(), eye.z())) {
                    expected.add(c);
                }
            }
            TreeSet<Integer> got = chosen(out);
            compared += expected.size();
            TreeSet<Integer> diff = new TreeSet<>(got);
            diff.addAll(expected);
            TreeSet<Integer> both = new TreeSet<>(got);
            both.retainAll(expected);
            diff.removeAll(both);
            mismatched += diff.size();
            drawnTotal += got.size();
            // each command is that cluster's slice of the shared index buffer
            for (int k = 0; k < out.count(); k++) {
                int c = out.baseInstance(k);
                assertEquals(s.firstIndex()[c], GpuWriter.getInt(commandMemory, out.byteOffset(k) + 8), "firstIndex of the command of cluster " + c);
                assertEquals(s.h().triangleCount(c) * 3, GpuWriter.getInt(commandMemory, out.byteOffset(k)), "index count of the command of cluster " + c);
            }
            assertEquals(counters.drawn, got.size());
            assertEquals(counters.inFrustum - counters.backFacing - counters.occluded - counters.overflow, counters.drawn);
        }
        System.out.printf("CLUSTERCULL compared %d expected clusters over 120 views, %d differences (float against double rounding at the borderline)%n", compared, mismatched);
        assertTrue(compared > 3000, "the sample must be substantial: " + compared);
        assertTrue(mismatched <= compared * 0.002, "the reference and the oracle must agree except for rounding at a threshold: " + mismatched);
        assertTrue(drawnTotal > 0);
    }

    @Test
    void withoutFrustumConeAndOcclusionTheCommandsDrawAClosedCrackFreeSurface() {
        Scene s = scene(welded(Primitives.torus(1f, 0.35f, 96, 48)), true);
        int n = s.h().clusterCount();
        float pixelScale = 1080f / (2f * (float) Math.tan(0.5f));
        // a frustum that contains everything: an enormous field of view from far away
        Mat4f vp = Mat4f.perspective(2.5f, 1f, 0.01f, 1e5f, ClipSpace.D3D).mul(Mat4f.lookAt(new Vec3f(0f, 0f, 4f), Vec3f.ZERO, Vec3f.UNIT_Y));
        for (float budget : new float[] {0f, 0.5f, 2f, 8f, 100f, 1e9f}) {
            MemorySegment v = view(vp, DepthRange.ZERO_TO_ONE, new Vec3f(0f, 0f, 4f), pixelScale, budget, n, null);
            MemorySegment seg = MemorySegment.ofArray(new byte[n * 20]);
            DrawCommandBuffer out = new DrawCommandBuffer(seg, DrawCommandBuffer.Kind.ELEMENTS, false);
            ClusterCullReference.Counters counters = new ClusterCullReference.Counters();
            ClusterCullReference.cull(v, s.clusters(), null, out, counters);
            List<Integer> idx = new ArrayList<>();
            for (int k = 0; k < out.count(); k++) {
                int count = GpuWriter.getInt(seg, out.byteOffset(k)), first = GpuWriter.getInt(seg, out.byteOffset(k) + 8);
                for (int e = 0; e < count; e++) {
                    idx.add(s.indexBuffer()[first + e]);
                }
            }
            assertEquals(0, openEdges(idx.stream().mapToInt(Integer::intValue).toArray()), "budget " + budget + ": the drawn surface must be closed");
            assertEquals(counters.lodSelected, counters.drawn);
        }
    }

    private static int openEdges(int[] idx) {
        Map<Long, Integer> directed = new HashMap<>();
        for (int t = 0; t < idx.length; t += 3) {
            for (int k = 0; k < 3; k++) {
                directed.merge(((long) idx[t + k] << 32) | (idx[t + (k + 1) % 3] & 0xffffffffL), 1, Integer::sum);
            }
        }
        int open = 0;
        for (Map.Entry<Long, Integer> e : directed.entrySet()) {
            long rev = (e.getKey() << 32) | (e.getKey() >>> 32);
            if (e.getValue() != 1 || !directed.containsKey(rev)) {
                open++;
            }
        }
        return open;
    }

    @Test
    void aWallInFrontHidesEverythingAndASmallBufferDropsAndCounts() {
        Scene s = scene(welded(Primitives.icoSphere(1f, 4)), false);
        int n = s.h().clusterCount();
        float pixelScale = 1080f / (2f * (float) Math.tan(0.5f));
        Vec3f eye = new Vec3f(0f, 0f, 6f);
        Mat4f vp = Mat4f.perspective(1.0f, 16f / 9f, 0.05f, 200f, ClipSpace.D3D).mul(Mat4f.lookAt(eye, Vec3f.ZERO, Vec3f.UNIT_Y));
        int w = 64, h = 36;
        float[] wall = new float[w * h];
        java.util.Arrays.fill(wall, 0.0001f); // covers the screen and is nearer than anything
        HiZPyramid hzb = HiZPyramid.fromDepth(wall, w, h, DepthRange.ZERO_TO_ONE, true);
        MemorySegment v = view(vp, DepthRange.ZERO_TO_ONE, eye, pixelScale, 1f, n, hzb);
        DrawCommandBuffer out = new DrawCommandBuffer(MemorySegment.ofArray(new byte[n * 20]), DrawCommandBuffer.Kind.ELEMENTS, false);
        ClusterCullReference.Counters hidden = new ClusterCullReference.Counters();
        ClusterCullReference.cull(v, s.clusters(), hzb, out, hidden);
        assertEquals(0, hidden.drawn, "everything is behind the wall");
        assertTrue(hidden.occluded > 0);
        ClusterCullReference.Counters open = new ClusterCullReference.Counters();
        ClusterCullReference.cull(v, s.clusters(), null, out, open);
        assertTrue(open.drawn > 10, "without the pyramid there is something to draw: " + open.drawn);
        // a buffer with room for three commands
        DrawCommandBuffer small = new DrawCommandBuffer(MemorySegment.ofArray(new byte[3 * 20]), DrawCommandBuffer.Kind.ELEMENTS, false);
        ClusterCullReference.Counters tight = new ClusterCullReference.Counters();
        ClusterCullReference.cull(v, s.clusters(), null, small, tight);
        assertEquals(3, small.count());
        assertEquals(open.drawn - 3, tight.overflow);
        // the command buffer is cleared at the start of a pass
        ClusterCullReference.cull(v, s.clusters(), null, small, new ClusterCullReference.Counters());
        assertEquals(3, small.count());
    }

    @Test
    void theGlslIsCompleteAndMatchesTheLayouts() {
        for (int group : new int[] {32, 64, 256}) {
            String obj = GpuCullGlsl.computeShader(group), cl = GpuCullGlsl.clusterShader(group);
            assertTrue(obj.contains("local_size_x = " + group) && cl.contains("local_size_x = " + group));
            assertTrue(obj.contains("struct CullObject") && obj.contains("struct CullView") && obj.contains("struct DrawElementsIndirect"));
            assertTrue(cl.contains("struct ClusterCullObject") && cl.contains("struct ClusterCullView"));
            assertTrue(obj.contains("atomicAdd(commands[o.drawIndex].instanceCount, 1u)") && obj.contains("hiddenBox(o.min, o.max)"));
            assertTrue(cl.contains("atomicAdd(drawCount, 1u)") && cl.contains("backFacing(c.sphere, c.cone)") && cl.contains("projected(c.parentError, c.parentSphere)"));
            for (String text : new String[] {obj, cl}) {
                assertTrue(text.contains("float farness(float depth)") && text.contains("texelFetch(hzb"), "the shared Hi-Z test");
                int open = 0;
                for (char ch : text.toCharArray()) {
                    open += ch == '{' ? 1 : ch == '}' ? -1 : 0;
                    assertTrue(open >= 0, "balanced braces");
                }
                assertEquals(0, open, "balanced braces");
                assertTrue(!text.contains("%s") && !text.contains("%d"), "every placeholder was filled");
            }
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> GpuCullGlsl.computeShader(48));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> GpuCullGlsl.clusterShader(0));
    }
}
