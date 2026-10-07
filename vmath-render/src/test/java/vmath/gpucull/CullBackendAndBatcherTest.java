package vmath.gpucull;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import vmath.bulk.VisibilitySet;
import vmath.gl.DrawList;
import vmath.gl.DrawSubmission;
import vmath.gl.GlslVersion;
import vmath.gl.GraphicsCapabilities;
import vmath.gl.GraphicsCapabilities.Feature;

/**
 * {@link CullBackend} (which side culls) and {@link SurvivorBatcher} (what the CPU side hands to
 * the draws), the latter against a brute-force grouping.
 */
class CullBackendAndBatcherTest {

    private final Random rnd = new Random(Long.getLong("vmath.seed", 77L));

    // ---------------------------------------------------------------- the backend choice

    @Test
    void computeNeedsComputeStorageIndirectBaseInstanceAndGlsl450() {
        assertEquals(CullBackend.CPU, CullBackend.choose(GraphicsCapabilities.baseline()));
        assertEquals(CullBackend.CPU, CullBackend.choose(GraphicsCapabilities.openGl(4, 3, List.of())), "4.3 has the features but the shaders are written for GLSL 4.50");
        assertEquals(CullBackend.COMPUTE, CullBackend.choose(GraphicsCapabilities.openGl(4, 5, List.of())));
        assertEquals(CullBackend.COMPUTE, CullBackend.choose(GraphicsCapabilities.openGl(4, 6, List.of())));
        assertEquals(CullBackend.COMPUTE, CullBackend.choose(GraphicsCapabilities.vulkan(false, true, false)));
        for (Feature missing : new Feature[] {Feature.COMPUTE_SHADERS, Feature.STORAGE_BUFFERS, Feature.DRAW_INDIRECT, Feature.BASE_INSTANCE}) {
            assertEquals(CullBackend.CPU, CullBackend.choose(GraphicsCapabilities.openGl(4, 6, List.of()).without(missing)), "without " + missing);
        }
    }

    @Test
    void theCpuBackendCanBeRequestedOnAModernContextAndTheOtherWayRoundIsRefused() {
        GraphicsCapabilities gl46 = GraphicsCapabilities.openGl(4, 6, List.of());
        assertEquals(CullBackend.CPU, CullBackend.force(CullBackend.CPU, gl46));
        assertEquals(CullBackend.COMPUTE, CullBackend.force(CullBackend.COMPUTE, gl46));
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class, () -> CullBackend.force(CullBackend.COMPUTE, GraphicsCapabilities.baseline()));
        assertTrue(e.getMessage().contains("COMPUTE_SHADERS") && e.getMessage().contains(GlslVersion.V450.toString()), e.getMessage());
        assertEquals(List.of(CullBackend.COMPUTE, CullBackend.CPU), CullBackend.chooser().strategies());
    }

    // ---------------------------------------------------------------- the batcher

    private static DrawList draws(int n) {
        DrawList d = new DrawList(DrawList.Kind.ELEMENTS, 2);
        for (int i = 0; i < n; i++) {
            d.addElements(36, 0, i * 8, 1, 99, i); // the instance counts and base instances are overwritten
        }
        return d;
    }

    /** The grouping by definition: the visible objects of each draw in ascending order, the draws one after the other. */
    private static int[] brute(VisibilitySet visible, int n, int[] drawOf, int drawCount, int[] capacity, int[] countsOut) {
        int[] out = new int[n];
        int o = 0;
        for (int d = 0; d < drawCount; d++) {
            int kept = 0;
            for (int i = 0; i < n; i++) {
                if (visible.get(i) && drawOf[i] == d && (capacity == null || kept < capacity[d])) {
                    out[o++] = i;
                    kept++;
                }
            }
            countsOut[d] = kept;
        }
        return Arrays.copyOf(out, o);
    }

    @Test
    void survivorsAreGroupedByDrawLikeTheDefinitionSays() {
        SurvivorBatcher batcher = new SurvivorBatcher();
        for (int rep = 0; rep < 200; rep++) {
            int n = 1 + rnd.nextInt(3000), drawCount = 1 + rnd.nextInt(40);
            int[] drawOf = new int[n];
            VisibilitySet vis = new VisibilitySet(n);
            for (int i = 0; i < n; i++) {
                drawOf[i] = rnd.nextInt(drawCount);
                if (rnd.nextInt(3) != 0) {
                    vis.set(i);
                }
            }
            DrawList d = draws(drawCount);
            int[] counts = new int[drawCount];
            int[] expected = brute(vis, n, drawOf, drawCount, null, counts);
            int kept = batcher.batch(vis, n, drawOf, d, null);
            assertEquals(expected.length, kept);
            assertEquals(expected.length, batcher.survivorCount());
            assertEquals(0, batcher.overflow());
            assertArrayEquals(expected, Arrays.copyOf(batcher.survivors(), kept), "rep " + rep);
            int base = 0;
            for (int k = 0; k < drawCount; k++) {
                assertEquals(counts[k], d.instanceCount(k), "draw " + k);
                assertEquals(base, d.baseInstance(k), "draw " + k);
                base += counts[k];
            }
        }
    }

    @Test
    void aCapacityKeepsTheFirstObjectsAndCountsTheRest() {
        SurvivorBatcher batcher = new SurvivorBatcher();
        for (int rep = 0; rep < 100; rep++) {
            int n = 1 + rnd.nextInt(2000), drawCount = 1 + rnd.nextInt(10);
            int[] drawOf = new int[n], capacity = new int[drawCount];
            VisibilitySet vis = new VisibilitySet(n);
            for (int i = 0; i < n; i++) {
                drawOf[i] = rnd.nextInt(drawCount);
                if (rnd.nextBoolean()) {
                    vis.set(i);
                }
            }
            for (int d = 0; d < drawCount; d++) {
                capacity[d] = rnd.nextInt(200);
            }
            DrawList d = draws(drawCount);
            int[] counts = new int[drawCount];
            int[] expected = brute(vis, n, drawOf, drawCount, capacity, counts);
            int kept = batcher.batch(vis, n, drawOf, d, capacity);
            assertArrayEquals(expected, Arrays.copyOf(batcher.survivors(), kept));
            assertEquals(vis.count() - kept, batcher.overflow(), "every visible object is kept or counted");
            for (int k = 0; k < drawCount; k++) {
                assertEquals(counts[k], d.instanceCount(k));
            }
        }
    }

    @Test
    void theResultFeedsEverySubmissionForm() {
        int n = 1000, drawCount = 5;
        int[] drawOf = new int[n];
        VisibilitySet vis = new VisibilitySet(n);
        for (int i = 0; i < n; i++) {
            drawOf[i] = i % drawCount;
            if (i % 7 != 0) {
                vis.set(i);
            }
        }
        DrawList d = draws(drawCount);
        new SurvivorBatcher().batch(vis, n, drawOf, d, null);
        assertTrue(d.usesInstancing() && d.usesBaseInstance());
        // instanced with a base instance: indirect where the context has it, a loop of instanced draws otherwise
        assertEquals(DrawSubmission.MULTI_DRAW_INDIRECT, DrawSubmission.choose(GraphicsCapabilities.openGl(4, 3, List.of()), d));
        assertEquals(DrawSubmission.DRAW_LOOP, DrawSubmission.choose(GraphicsCapabilities.baseline(), d));
        int[] instances = new int[1];
        d.forEach((count, first, baseVertex, instanceCount, baseInstance, user) -> instances[0] += instanceCount);
        assertEquals(vis.count(), instances[0]);
    }

    @Test
    void anObjectOutsideTheCountIsIgnoredAndAnOutOfRangeDrawIsRefused() {
        SurvivorBatcher batcher = new SurvivorBatcher();
        VisibilitySet vis = new VisibilitySet(10);
        vis.set(2);
        vis.set(8);
        DrawList d = draws(1);
        assertEquals(1, batcher.batch(vis, 5, new int[5], d, null), "object 8 is above the count");
        assertEquals(1, d.instanceCount(0));
        assertThrows(IllegalArgumentException.class, () -> batcher.batch(vis, 10, new int[] {0, 0, 5, 0, 0, 0, 0, 0, 0, 0}, draws(1), null));
        assertThrows(IllegalArgumentException.class, () -> batcher.batch(vis, 10, new int[] {0, 0, -1, 0, 0, 0, 0, 0, 0, 0}, draws(1), null));
        assertThrows(IllegalArgumentException.class, () -> batcher.batch(vis, 10, new int[5], draws(1), null));
        assertThrows(IllegalArgumentException.class, () -> batcher.batch(vis, 5, new int[5], draws(2), new int[1]));
    }

    @Test
    void anEmptyScene() {
        SurvivorBatcher batcher = new SurvivorBatcher();
        DrawList d = draws(3);
        assertEquals(0, batcher.batch(new VisibilitySet(0), 0, new int[0], d, null));
        for (int i = 0; i < 3; i++) {
            assertEquals(0, d.instanceCount(i));
            assertEquals(0, d.baseInstance(i));
        }
    }
}
