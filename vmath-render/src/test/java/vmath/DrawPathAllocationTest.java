package vmath;

import static vmath.Alloc.assertNoAllocation;

import java.lang.foreign.MemorySegment;
import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.bulk.VisibilitySet;
import vmath.gl.DrawCommandBuffer;
import vmath.gl.DrawList;
import vmath.gl.DrawSubmission;
import vmath.gl.GraphicsCapabilities;
import vmath.gpucull.CullBackend;
import vmath.gpucull.SurvivorBatcher;

/**
 * The allocation contract of the capability-driven draw path: choosing a strategy, filling and
 * encoding a {@link DrawList}, and grouping the survivors allocate nothing in steady state.
 */
class DrawPathAllocationTest {

    private static final int WARM = 20_000, CALLS = 20_000;

    @Test
    void choosingAStrategyAllocatesNothing() {
        GraphicsCapabilities gl33 = GraphicsCapabilities.baseline(), gl46 = GraphicsCapabilities.openGl(4, 6, List.of());
        DrawList instanced = new DrawList(DrawList.Kind.ELEMENTS, 4);
        instanced.addElements(36, 0, 0, 10, 3, 0);
        int[] sink = new int[1];
        assertNoAllocation("DrawSubmission.choose and CullBackend.choose", WARM, CALLS, () -> {
            sink[0] += DrawSubmission.choose(gl33, instanced).ordinal() + DrawSubmission.choose(gl46, instanced).ordinal();
            sink[0] += DrawSubmission.choose(gl33, false, false).ordinal() + CullBackend.choose(gl46).ordinal() + CullBackend.choose(gl33).ordinal();
        });
    }

    @Test
    void fillingAndEncodingADrawListAllocatesNothing() {
        DrawList list = new DrawList(DrawList.Kind.ELEMENTS, 512);
        MemorySegment seg = MemorySegment.ofArray(new byte[512 * 20]);
        DrawCommandBuffer commands = new DrawCommandBuffer(seg, DrawCommandBuffer.Kind.ELEMENTS, false);
        int[] counts = new int[512], firsts = new int[512], bases = new int[512];
        long[] offsets = new long[512];
        int[] sink = new int[1];
        DrawList.Visitor visitor = (count, first, baseVertex, instanceCount, baseInstance, user) -> sink[0] += count; // made once: a lambda made in the loop would be the allocation
        assertNoAllocation("DrawList fill, writeIndirect, copy arrays, forEach", WARM / 10, CALLS / 10, () -> {
            list.clear();
            for (int i = 0; i < 512; i++) {
                list.addElements(36, i * 36, i * 8, 1, 0, i);
            }
            commands.clear();
            sink[0] += list.writeIndirect(commands);
            sink[0] += list.copyCounts(counts) + list.copyFirsts(firsts) + list.copyBaseVertices(bases) + list.copyIndexOffsets(offsets, 4);
            list.forEach(visitor);
        });
    }

    @Test
    void groupingTheSurvivorsAllocatesNothing() {
        int n = 20_000, draws = 32;
        int[] drawOf = new int[n];
        VisibilitySet visible = new VisibilitySet(n);
        for (int i = 0; i < n; i++) {
            drawOf[i] = i % draws;
            if (i % 3 != 0) {
                visible.set(i);
            }
        }
        DrawList list = new DrawList(DrawList.Kind.ELEMENTS, draws);
        for (int d = 0; d < draws; d++) {
            list.addElements(36, 0, 0, 1, 0, d);
        }
        SurvivorBatcher batcher = new SurvivorBatcher();
        int[] sink = new int[1];
        assertNoAllocation("SurvivorBatcher.batch", 2_000, 2_000, () -> sink[0] += batcher.batch(visible, n, drawOf, list, null));
    }
}
