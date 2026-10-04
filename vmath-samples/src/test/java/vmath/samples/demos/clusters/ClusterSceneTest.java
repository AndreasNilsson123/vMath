package vmath.samples.demos.clusters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;
import vmath.camera.Cameraf;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;
import vmath.gl.DrawCommandBuffer;
import vmath.gpucull.ClusterCullObjectGpu;
import vmath.gpucull.ClusterCullReference;
import vmath.gpucull.ClusterCullViewGpu;
import vmath.mesh.ClusterHierarchy;

/**
 * Tests of the rock and its hierarchy without a window: the shared index buffer holds every
 * cluster, a bigger error budget chooses fewer clusters and triangles, and the library's CPU
 * reference of the culling shader chooses the clusters that {@code ClusterHierarchy.select} chooses
 * when nothing is culled by the frustum, the cone or the depth.
 *
 * <p><b>Thread safety.</b> The scene is built once and only read; the tests may run in parallel.
 */
class ClusterSceneTest {

    private static final ClusterScene SCENE = new ClusterScene(4);

    private static Cameraf camera(float distance) {
        return Cameraf.lookingAt(new Vec3f(0f, 0f, distance), Vec3f.ZERO, Vec3f.UNIT_Y, 1.0f, 16f / 9f, 0.05f, 500f, DepthRange.NEGATIVE_ONE_TO_ONE);
    }

    @Test
    void theIndexBufferHoldsEveryClusterOneAfterTheOther() {
        ClusterHierarchy h = SCENE.hierarchy();
        assertTrue(h.clusterCount() > 100 && h.levelCount() >= 3);
        int expected = 0;
        for (int c = 0; c < h.clusterCount(); c++) {
            assertEquals(expected, SCENE.firstIndex(c));
            int[] ix = h.indices(c);
            for (int i = 0; i < ix.length; i++) {
                assertEquals(ix[i], SCENE.indices()[expected + i]);
            }
            expected += ix.length;
        }
        assertEquals(expected, SCENE.indices().length);
        assertEquals(SCENE.hierarchy().vertices().vertexCount() * 3, SCENE.positions().length);
    }

    @Test
    void aBiggerBudgetChoosesFewerClustersAndTriangles() {
        ClusterHierarchy h = SCENE.hierarchy();
        int[] out = new int[h.clusterCount()];
        float scale = 900f / (2f * (float) Math.tan(0.5));
        int previousClusters = Integer.MAX_VALUE;
        long previousTriangles = Long.MAX_VALUE;
        for (float budget : new float[] {0.25f, 1f, 4f, 16f, 64f}) {
            int n = h.select(0f, 0f, 6f, scale, budget, out);
            long tris = 0;
            for (int i = 0; i < n; i++) {
                tris += h.triangleCount(out[i]);
            }
            assertTrue(n >= 1 && n <= previousClusters, "budget " + budget + ": " + n + " clusters");
            assertTrue(tris <= previousTriangles, "budget " + budget + ": " + tris + " triangles");
            previousClusters = n;
            previousTriangles = tris;
        }
    }

    @Test
    void theReferenceOfTheShaderChoosesTheClustersThatSelectChoosesAmongTheOnesInView() {
        ClusterHierarchy h = SCENE.hierarchy();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment clusters = SCENE.writeClusters(arena);
            MemorySegment view = arena.allocate(ClusterCullViewGpu.SIZE, 16);
            long stride = DrawCommandBuffer.stride(DrawCommandBuffer.Kind.ELEMENTS, false);
            DrawCommandBuffer out = new DrawCommandBuffer(arena.allocate(stride * h.clusterCount(), 16), DrawCommandBuffer.Kind.ELEMENTS, false);
            int[] selected = new int[h.clusterCount()];
            for (float distance : new float[] {2.5f, 5f, 12f, 60f}) {
                Cameraf cam = camera(distance);
                SCENE.writeView(view, cam, 900, 1f, 1, 1, 1);
                ClusterCullReference.Counters counters = new ClusterCullReference.Counters();
                counters.reset();
                ClusterCullReference.cull(view, clusters, null, out, counters);
                float scale = 900f / (2f * (float) Math.tan(cam.fovy() * 0.5f));
                int n = h.select(0f, 0f, distance, scale, 1f, selected);
                assertEquals(n, counters.lodSelected, "the shader's level choice is the library's select at distance " + distance);
                assertEquals(counters.drawn, out.count());
                assertTrue(counters.drawn <= n);
                assertTrue(counters.drawn > 0);
                assertTrue(counters.backFacing > 0 || distance > 30f, "the cones remove back-facing clusters at distance " + distance);
                boolean[] chosen = new boolean[h.clusterCount()];
                for (int i = 0; i < n; i++) {
                    chosen[selected[i]] = true;
                }
                for (int i = 0; i < out.count(); i++) {
                    assertTrue(chosen[out.baseInstance(i)], "the shader drew cluster " + out.baseInstance(i) + " which select did not choose");
                }
            }
        }
    }

    @Test
    void theRecordsAreWrittenWithTheLayoutOfTheStruct() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment clusters = SCENE.writeClusters(arena);
            assertEquals(SCENE.hierarchy().clusterCount() * ClusterCullObjectGpu.SIZE, clusters.byteSize());
            int last = SCENE.hierarchy().clusterCount() - 1;
            assertEquals(SCENE.firstIndex(last), clusters.get(java.lang.foreign.ValueLayout.JAVA_INT, last * ClusterCullObjectGpu.SIZE + ClusterCullObjectGpu.OFFSET_FIRST_INDEX));
            assertEquals(SCENE.hierarchy().triangleCount(last) * 3, clusters.get(java.lang.foreign.ValueLayout.JAVA_INT, last * ClusterCullObjectGpu.SIZE + ClusterCullObjectGpu.OFFSET_INDEX_COUNT));
        }
    }
}
