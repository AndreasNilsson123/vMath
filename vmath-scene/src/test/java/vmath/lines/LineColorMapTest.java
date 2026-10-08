package vmath.lines;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;
import vmath.gl.DrawList;
import vmath.gl.GraphicsCapabilities;

/** The colour maps of {@link LineRenderPlan} and {@link LineSet}: what a display palette plugs into. */
class LineColorMapTest {

    private static final IntUnaryOperator DIM = c -> (c >>> 25) << 24 | ((c >>> 17) & 0x7F) << 16 | ((c >>> 9) & 0x7F) << 8 | c & 255;

    private static LineBatch batch() {
        LineBatch b = new LineBatch();
        b.addPolyline(new double[] {0, 0, 0, 10, 0, 0, 10, 10, 0}, 0, 3, false, LineStyle.pixels(3f).withColor(0xFF8000FF));
        b.addPolyline(new double[] {0, 5, 0, 10, 5, 0}, 0, 2, false, LineStyle.pixels(5f).withColor(0x2060C0FF));
        b.addPolyline(new double[] {0, 7, 0, 10, 7, 0}, 0, 2, false, LineStyle.pixels(3f).withColor(0xFF8000FF));
        return b;
    }

    private static byte[] bytes(MemorySegment m) {
        return m.toArray(ValueLayout.JAVA_BYTE);
    }

    @Test
    void theColourMapIsAppliedWhereTheStylesAreWritten() {
        LineBatch b = batch();
        for (LineStrategy strategy : LineStrategy.values()) {
            if (strategy == LineStrategy.HAIRLINE) {
                continue;
            }
            GraphicsCapabilities caps = strategy == LineStrategy.INDIRECT_DRAW_ID ? GraphicsCapabilities.openGl(4, 6, java.util.List.of()) : strategy == LineStrategy.INDIRECT_INSTANCE_STYLE
                    ? GraphicsCapabilities.openGl(4, 3, java.util.List.of()) : GraphicsCapabilities.baseline();
            LineRenderPlan plan = LineRenderPlan.force(strategy, caps);
            MemorySegment data = MemorySegment.ofArray(new byte[(int) plan.dataBytes(b)]);
            MemorySegment plain = MemorySegment.ofArray(new byte[(int) Math.max(plan.styleBytes(b), 1)]);
            MemorySegment mapped = MemorySegment.ofArray(new byte[plain.toArray(ValueLayout.JAVA_BYTE).length]);
            MemorySegment rewritten = MemorySegment.ofArray(new byte[mapped.toArray(ValueLayout.JAVA_BYTE).length]);
            DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 4);
            plan.write(b, data, plain, draws);
            DrawList draws2 = new DrawList(DrawList.Kind.ARRAYS, 4);
            plan.write(b, data, mapped, draws2, DIM);
            assertEquals(draws.size(), draws2.size(), strategy.name());
            plan.rewriteStyles(b, draws, rewritten, DIM);
            assertArrayEquals(bytes(mapped), bytes(rewritten), strategy + ": the rewrite is the write");
            int style0 = strategy == LineStrategy.INDIRECT_DRAW_ID ? draws.user(0) : 0;
            assertEquals(DIM.applyAsInt(0xFF8000FF), LineGpu.colorOf(mapped, 0), strategy.name());
            assertEquals(0xFF8000FF, LineGpu.colorOf(plain, 0), "the unmapped write is unchanged");
            plan.rewriteStyles(b, draws, rewritten, null);
            assertArrayEquals(bytes(plain), bytes(rewritten), "a null map is the original colours");
            assertTrue(style0 >= 0);
        }
    }

    @Test
    void theHairlineKeepsItsColoursInTheVerticesAndSaysSo() {
        LineBatch b = batch();
        LineRenderPlan plan = LineRenderPlan.force(LineStrategy.HAIRLINE, GraphicsCapabilities.baseline());
        MemorySegment data = MemorySegment.ofArray(new byte[(int) plan.dataBytes(b)]);
        DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 4);
        plan.write(b, data, null, draws, DIM);
        assertEquals(DIM.applyAsInt(0xFF8000FF), vmath.gl.GpuWriter.getInt(data, 12));
        assertThrows(IllegalStateException.class, () -> plan.rewriteStyles(b, draws, MemorySegment.ofArray(new byte[16]), DIM));
    }

    @Test
    void aLineSetWritesTheMappedColoursAndSwitchesBack() {
        for (LineStrategy strategy : new LineStrategy[] {LineStrategy.EXPANDED_MULTIDRAW, LineStrategy.INSTANCED_LOOP, LineStrategy.HAIRLINE}) {
            LineRenderPlan plan = LineRenderPlan.force(strategy, GraphicsCapabilities.baseline());
            LineSet set = new LineSet(plan, 64);
            set.add(new double[] {0, 0, 0, 10, 0, 0}, 0, 2, false, LineStyle.pixels(3f).withColor(0xFF8000FF));
            MemorySegment data = MemorySegment.ofArray(new byte[(int) set.dataBytes()]);
            MemorySegment styles = MemorySegment.ofArray(new byte[(int) Math.max(16, set.styleBytes())]);
            DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 4);
            set.update(data, styles, draws);
            int vertexColour = strategy == LineStrategy.HAIRLINE ? vmath.gl.GpuWriter.getInt(data, 12) : LineGpu.colorOf(styles, 0);
            assertEquals(0xFF8000FF, vertexColour, strategy.name());
            set.setColorMap(DIM);
            set.update(data, styles, draws);
            vertexColour = strategy == LineStrategy.HAIRLINE ? vmath.gl.GpuWriter.getInt(data, 12) : LineGpu.colorOf(styles, 0);
            assertEquals(DIM.applyAsInt(0xFF8000FF), vertexColour, strategy.name());
            if (strategy != LineStrategy.HAIRLINE) {
                assertEquals(0, set.dirtyRangeCount(), "a switch of palette touches the style table only");
            } else {
                assertTrue(set.dirtyRangeCount() > 0);
            }
            set.setColorMap(null);
            set.update(data, styles, draws);
            vertexColour = strategy == LineStrategy.HAIRLINE ? vmath.gl.GpuWriter.getInt(data, 12) : LineGpu.colorOf(styles, 0);
            assertEquals(0xFF8000FF, vertexColour, strategy.name());
        }
    }
}
