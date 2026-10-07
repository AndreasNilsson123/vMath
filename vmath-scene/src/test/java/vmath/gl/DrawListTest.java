package vmath.gl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.gl.DrawList.Kind;
import vmath.gl.GraphicsCapabilities.Feature;

/**
 * {@link DrawList} and {@link DrawSubmission}: the same list written in each form, and the choice
 * of the form for a spread of capability profiles.
 */
class DrawListTest {

    private static DrawList elements() {
        DrawList d = new DrawList(Kind.ELEMENTS, 1);
        d.addElements(36, 0, 0, 1, 0, 10);
        d.addElements(24, 36, 100, 1, 0, 11);
        d.addElements(12, 60, 200, 0, 0, 12); // culled
        d.addElements(6, 72, 300, 1, 0, 13);
        return d;
    }

    @Test
    void theListKeepsWhatIsAddedAndGrows() {
        DrawList d = new DrawList(Kind.ELEMENTS, 1);
        for (int i = 0; i < 100; i++) {
            assertEquals(i, d.addElements(i + 1, 2 * i, 3 * i, 4, 5, 6 + i));
        }
        assertEquals(100, d.size());
        assertEquals(51, d.count(50));
        assertEquals(100, d.first(50));
        assertEquals(150, d.baseVertex(50));
        assertEquals(4, d.instanceCount(50));
        assertEquals(5, d.baseInstance(50));
        assertEquals(56, d.user(50));
        d.clear();
        assertEquals(0, d.size());
        assertEquals(Kind.ELEMENTS, d.kind());
    }

    @Test
    void mistakesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new DrawList(Kind.ARRAYS, 0));
        DrawList e = new DrawList(Kind.ELEMENTS, 2), a = new DrawList(Kind.ARRAYS, 2);
        assertThrows(IllegalStateException.class, () -> e.addArrays(3, 0, 1, 0, 0));
        assertThrows(IllegalStateException.class, () -> a.addElements(3, 0, 0, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> a.addArrays(-1, 0, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> a.addArrays(3, -1, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> a.addArrays(3, 0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> a.addArrays(3, 0, 1, -1, 0));
        a.addArrays(3, 0, 1, 0, 0);
        assertThrows(IndexOutOfBoundsException.class, () -> a.count(1));
        assertThrows(IndexOutOfBoundsException.class, () -> a.setInstanceCount(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> a.setInstanceCount(0, -1));
        assertThrows(IllegalArgumentException.class, () -> a.setBaseInstance(0, -1));
    }

    @Test
    void indirectCommandsKeepTheOrderAndTheZeroInstanceDraws() {
        DrawList d = elements();
        MemorySegment seg = MemorySegment.ofArray(new byte[4 * 20]);
        DrawCommandBuffer cb = new DrawCommandBuffer(seg, DrawCommandBuffer.Kind.ELEMENTS, false);
        assertEquals(4, d.writeIndirect(cb));
        assertEquals(4, cb.count());
        assertEquals(0, cb.instanceCount(2), "the culled draw keeps its command, so that command index equals draw index");
        assertEquals(1, cb.instanceCount(3));
        assertEquals(36, GpuWriter.getInt(seg, cb.byteOffset(0)));
        assertEquals(36, GpuWriter.getInt(seg, cb.byteOffset(1) + 8), "the first index");
        assertEquals(200, GpuWriter.getInt(seg, cb.byteOffset(2) + 12), "the base vertex");
        assertThrows(IllegalStateException.class, () -> d.writeIndirect(new DrawCommandBuffer(MemorySegment.ofArray(new byte[16]), DrawCommandBuffer.Kind.ARRAYS, false)));
        DrawList arrays = new DrawList(Kind.ARRAYS, 2);
        arrays.addArrays(5, 7, 3, 9, 0);
        DrawCommandBuffer ab = new DrawCommandBuffer(MemorySegment.ofArray(new byte[16]), DrawCommandBuffer.Kind.ARRAYS, false);
        arrays.writeIndirect(ab);
        assertEquals(3, ab.instanceCount(0));
        assertEquals(9, ab.baseInstance(0));
    }

    @Test
    void clientArraysLeaveOutTheDrawsThatDrawNothing() {
        DrawList d = elements();
        int[] counts = new int[4], firsts = new int[4], bases = new int[4];
        long[] offsets = new long[4];
        assertEquals(3, d.copyCounts(counts));
        assertEquals(3, d.copyFirsts(firsts));
        assertEquals(3, d.copyBaseVertices(bases));
        assertEquals(3, d.copyIndexOffsets(offsets, 2));
        assertArrayEquals(new int[] {36, 24, 6, 0}, counts);
        assertArrayEquals(new int[] {0, 36, 72, 0}, firsts);
        assertArrayEquals(new int[] {0, 100, 300, 0}, bases);
        assertArrayEquals(new long[] {0, 72, 144, 0}, offsets);
        assertThrows(IllegalArgumentException.class, () -> d.copyCounts(new int[3]));
        assertThrows(IllegalArgumentException.class, () -> d.copyIndexOffsets(offsets, 3));
        assertThrows(IllegalArgumentException.class, () -> d.copyIndexOffsets(new long[3], 4));
    }

    @Test
    void clientArraysCannotInstance() {
        DrawList d = new DrawList(Kind.ARRAYS, 2);
        d.addArrays(3, 0, 5, 0, 0);
        assertTrue(d.usesInstancing());
        assertThrows(IllegalStateException.class, () -> d.copyCounts(new int[2]));
        DrawList b = new DrawList(Kind.ARRAYS, 2);
        b.addArrays(3, 0, 1, 4, 0);
        assertTrue(b.usesBaseInstance() && !b.usesInstancing());
        assertThrows(IllegalStateException.class, () -> b.copyFirsts(new int[2]));
        assertThrows(IllegalStateException.class, () -> b.copyBaseVertices(new int[2]));
        assertThrows(IllegalStateException.class, () -> b.copyIndexOffsets(new long[2], 4));
    }

    @Test
    void theLoopSeesTheDrawsThatDrawSomething() {
        DrawList d = elements();
        List<String> seen = new ArrayList<>();
        d.forEach((count, first, baseVertex, instances, baseInstance, user) -> seen.add(count + "/" + first + "/" + baseVertex + "/" + instances + "/" + baseInstance + "/" + user));
        assertEquals(List.of("36/0/0/1/0/10", "24/36/100/1/0/11", "6/72/300/1/0/13"), seen);
        d.setInstanceCount(2, 7);
        d.setBaseInstance(2, 40);
        assertEquals(7, d.instanceCount(2));
        assertEquals(40, d.baseInstance(2));
    }

    // ---------------------------------------------------------------- the choice

    private static final GraphicsCapabilities GL33 = GraphicsCapabilities.baseline();
    private static final GraphicsCapabilities GL42 = GraphicsCapabilities.openGl(4, 2, List.of());
    private static final GraphicsCapabilities GL43 = GraphicsCapabilities.openGl(4, 3, List.of());
    private static final GraphicsCapabilities GL46 = GraphicsCapabilities.openGl(4, 6, List.of());

    @Test
    void plainDrawsUseTheBestFormThatExists() {
        assertEquals(DrawSubmission.MULTI_DRAW_CLIENT, DrawSubmission.choose(GL33, false, false));
        assertEquals(DrawSubmission.MULTI_DRAW_CLIENT, DrawSubmission.choose(GL42, false, false));
        assertEquals(DrawSubmission.MULTI_DRAW_INDIRECT, DrawSubmission.choose(GL43, false, false));
        assertEquals(DrawSubmission.MULTI_DRAW_INDIRECT, DrawSubmission.choose(GL46, false, false));
        assertEquals(DrawSubmission.DRAW_LOOP, DrawSubmission.choose(GL46.without(Feature.MULTI_DRAW_INDIRECT, Feature.MULTI_DRAW), false, false));
        assertEquals(DrawSubmission.MULTI_DRAW_INDIRECT, DrawSubmission.choose(GraphicsCapabilities.vulkan(true, false, false), false, false));
        assertEquals(DrawSubmission.DRAW_LOOP, DrawSubmission.choose(GraphicsCapabilities.vulkan(false, false, false), false, false), "Vulkan without multi-draw loops");
    }

    @Test
    void instancedDrawsCannotUseTheClientArrays() {
        assertEquals(DrawSubmission.DRAW_LOOP, DrawSubmission.choose(GL33, true, false));
        assertEquals(DrawSubmission.DRAW_LOOP, DrawSubmission.choose(GL42, true, false));
        assertEquals(DrawSubmission.MULTI_DRAW_INDIRECT, DrawSubmission.choose(GL43, true, false));
    }

    @Test
    void aBaseInstanceNeedsTheFeatureForTheIndirectForm() {
        assertEquals(DrawSubmission.DRAW_LOOP, DrawSubmission.choose(GL33, true, true));
        assertEquals(DrawSubmission.DRAW_LOOP, DrawSubmission.choose(GL42.without(Feature.MULTI_DRAW_INDIRECT), true, true));
        assertEquals(DrawSubmission.MULTI_DRAW_INDIRECT, DrawSubmission.choose(GL43, true, true));
        assertEquals(DrawSubmission.DRAW_LOOP, DrawSubmission.choose(GL43.without(Feature.BASE_INSTANCE), true, true), "multi-draw indirect without a base instance cannot express it");
    }

    @Test
    void theChoiceReadsTheShapeOfTheList() {
        DrawList plain = elements();
        assertEquals(DrawSubmission.MULTI_DRAW_CLIENT, DrawSubmission.choose(GL33, plain));
        DrawList instanced = new DrawList(Kind.ELEMENTS, 2);
        instanced.addElements(36, 0, 0, 100, 0, 0);
        assertEquals(DrawSubmission.DRAW_LOOP, DrawSubmission.choose(GL33, instanced));
        assertEquals(DrawSubmission.MULTI_DRAW_INDIRECT, DrawSubmission.choose(GL46, instanced));
    }

    @Test
    void forcingALowerFormChecksThatItCanExpressTheList() {
        DrawList plain = elements();
        assertEquals(DrawSubmission.DRAW_LOOP, DrawSubmission.force(DrawSubmission.DRAW_LOOP, GL46, plain));
        assertEquals(DrawSubmission.MULTI_DRAW_CLIENT, DrawSubmission.force(DrawSubmission.MULTI_DRAW_CLIENT, GL46, plain));
        assertThrows(UnsupportedOperationException.class, () -> DrawSubmission.force(DrawSubmission.MULTI_DRAW_INDIRECT, GL33, plain));
        DrawList instanced = new DrawList(Kind.ELEMENTS, 2);
        instanced.addElements(36, 0, 0, 100, 0, 0);
        assertThrows(UnsupportedOperationException.class, () -> DrawSubmission.force(DrawSubmission.MULTI_DRAW_CLIENT, GL46, instanced));
        DrawList based = new DrawList(Kind.ELEMENTS, 2);
        based.addElements(36, 0, 0, 1, 5, 0);
        assertThrows(UnsupportedOperationException.class, () -> DrawSubmission.force(DrawSubmission.MULTI_DRAW_CLIENT, GL46, based));
    }

    @Test
    void theThreeTablesNameEveryForm() {
        assertFalse(DrawSubmission.chooser(false, false).strategies().contains(null));
        assertEquals(List.of(DrawSubmission.MULTI_DRAW_INDIRECT, DrawSubmission.MULTI_DRAW_CLIENT, DrawSubmission.DRAW_LOOP), DrawSubmission.chooser(false, false).strategies());
        assertEquals(List.of(DrawSubmission.MULTI_DRAW_INDIRECT, DrawSubmission.DRAW_LOOP), DrawSubmission.chooser(true, false).strategies());
        assertEquals(DrawSubmission.chooser(true, true), DrawSubmission.chooser(false, true));
    }
}
