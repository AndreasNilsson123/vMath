package vmath.map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Random;
import org.junit.jupiter.api.Test;
import vmath.gl.DrawList;
import vmath.gl.GraphicsCapabilities;

/** {@link DisplayPalette}, its use on the style tables of the map layer, and {@link ViewportInset}. */
class PaletteTest {

    private static final GraphicsCapabilities GL33 = GraphicsCapabilities.baseline();

    private static void near(int expected, int actual, int tolerance) {
        assertTrue(Math.abs(expected - actual) <= tolerance, expected + " vs " + actual);
    }

    // ---------------------------------------------------------------- palette

    @Test
    void theDayPaletteChangesNothing() {
        Random r = new Random(1);
        for (int i = 0; i < 2000; i++) {
            int c = r.nextInt();
            assertEquals(c, DisplayPalette.DAY.map(c), Integer.toHexString(c));
        }
    }

    @Test
    void theNightPaletteDimsAndKeepsTheAlphaAndTheOrder() {
        int previous = -1;
        for (int v = 0; v < 256; v++) {
            int c = v << 24 | v << 16 | v << 8 | 0x7F;
            int mapped = DisplayPalette.NIGHT.map(c);
            assertEquals(0x7F, mapped & 255, "alpha");
            int grey = mapped >>> 24;
            assertEquals(grey, (mapped >>> 16) & 255, "a grey stays grey");
            assertTrue(grey >= previous, "monotonic");
            assertTrue(grey <= v, "never brighter");
            previous = grey;
        }
        // a quarter of the light in linear terms is 137 of 255 in sRGB
        near(137, DisplayPalette.NIGHT.map(0xFFFFFFFF) >>> 24, 1);
        assertEquals(0, DisplayPalette.NIGHT.map(0x000000FF) >>> 8);
    }

    @Test
    void nightVisionIsGreenOnlyAndTracksTheLuminance() {
        Random r = new Random(2);
        for (int i = 0; i < 500; i++) {
            int c = r.nextInt() | 0xFF;
            int m = DisplayPalette.NIGHT_VISION.map(c);
            assertEquals(0, m >>> 24, "no red");
            assertEquals(0, (m >>> 8) & 255, "no blue");
            assertEquals(c & 255, m & 255);
        }
        assertTrue(((DisplayPalette.NIGHT_VISION.map(0xFFFFFFFF) >>> 16) & 255) > ((DisplayPalette.NIGHT_VISION.map(0x808080FF) >>> 16) & 255));
        assertTrue(((DisplayPalette.NIGHT_VISION.map(0x00FF00FF) >>> 16) & 255) > ((DisplayPalette.NIGHT_VISION.map(0x0000FFFF) >>> 16) & 255), "green is brighter than blue");
        assertEquals(0, DisplayPalette.RED_NIGHT.map(0x808080FF) & 0x00FFFF00);
        assertEquals("night vision", DisplayPalette.NIGHT_VISION.name());
    }

    @Test
    void aPaletteCanBeMadeAndRefusesNonsense() {
        DisplayPalette swap = DisplayPalette.of("swap", new float[] {0, 0, 1, 0, 1, 0, 1, 0, 0}, 1f);
        assertEquals(0x3020F0FF, swap.map(0xF02030FF), "red and blue exchanged");
        assertThrows(IllegalArgumentException.class, () -> DisplayPalette.of("x", new float[8], 1f));
        assertThrows(IllegalArgumentException.class, () -> DisplayPalette.of("x", new float[9], -1f));
        assertThrows(IllegalArgumentException.class, () -> DisplayPalette.of(null, new float[9], 1f));
        int[] colors = {0xFFFFFFFF, 0x000000FF};
        DisplayPalette.NIGHT.mapAll(colors);
        assertNotEquals(0xFFFFFFFF, colors[0]);
        assertTrue(DisplayPalette.NIGHT.toString().contains("night"));
        assertEquals(DisplayPalette.NIGHT.map(0x123456FF), DisplayPalette.NIGHT.applyAsInt(0x123456FF));
    }

    // ---------------------------------------------------------------- applied to the layers

    @Test
    void anAreaStyleTableIsRewrittenWithoutTouchingTheVertices() {
        AreaBatch b = new AreaBatch();
        int s = b.style(AreaStyle.hatch(0xFFC000FF, 0x203040FF, 8f, 2f, 0.5));
        b.addPolygon(new double[] {0, 0, 10, 0, 10, 10}, 3, s);
        AreaRenderPlan plan = AreaRenderPlan.choose(GL33);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment v = arena.allocate(plan.vertexBytes(b)), i = arena.allocate(plan.indexBytes(b)), st = arena.allocate(plan.styleBytes(b)), st2 = arena.allocate(plan.styleBytes(b));
            DrawList draws = new DrawList(DrawList.Kind.ELEMENTS, 1);
            plan.write(b, 0, 0, v, i, st, draws, DisplayPalette.NIGHT);
            plan.write(b, 0, 0, v, i, st2, draws);
            plan.rewriteStyles(b, st2, DisplayPalette.NIGHT);
            assertArrayEquals(st.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE), st2.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE), "the rewrite is the write");
            AreaStyle shown = AreaRenderPlan.readStyle(st, 0);
            assertEquals(DisplayPalette.NIGHT.map(0xFFC000FF), shown.fill());
            near(DisplayPalette.NIGHT.map(0x203040FF) >>> 24, shown.patternColor() >>> 24, 1);
            assertEquals(8f, shown.spacing());
            plan.rewriteStyles(b, st2, null);
            assertEquals(0xFFC000FF, AreaRenderPlan.readStyle(st2, 0).fill());
        }
        AreaRenderPlan floor = AreaRenderPlan.force(AreaStrategy.VERTEX_COLOR, GL33);
        assertThrows(IllegalStateException.class, () -> floor.rewriteStyles(b, MemorySegment.ofArray(new byte[8]), DisplayPalette.NIGHT));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment v = arena.allocate(floor.vertexBytes(b)), i = arena.allocate(floor.indexBytes(b));
            floor.write(b, 0, 0, v, i, null, new DrawList(DrawList.Kind.ELEMENTS, 1), DisplayPalette.NIGHT);
            assertEquals(DisplayPalette.NIGHT.map(0xFFC000FF), vmath.gl.GpuWriter.getInt(v, 8));
        }
    }

    @Test
    void symbolColoursAndSpritesAreMapped() {
        SymbolAtlas atlas = SymbolAtlas.builder().add("a", 2, 2, new byte[] {(byte) 255, (byte) 128, 0, (byte) 200, (byte) 255, (byte) 128, 0, (byte) 200, (byte) 255, (byte) 128, 0, (byte) 200, (byte) 255,
            (byte) 128, 0, (byte) 200}).build(16);
        SymbolAtlas night = atlas.mapped(DisplayPalette.NIGHT);
        assertEquals(atlas.width(), night.width());
        float[] uv = new float[4];
        atlas.rect(0, uv);
        int day = atlas.sampleNearest((uv[0] + uv[2]) / 2, (uv[1] + uv[3]) / 2);
        assertEquals(DisplayPalette.NIGHT.map(day), night.sampleNearest((uv[0] + uv[2]) / 2, (uv[1] + uv[3]) / 2));
        assertEquals(200, day & 255, "alpha stays");
        SymbolBatch batch = new SymbolBatch(1);
        batch.add(0, 0, 0, 10f, uv, 0xFFFFFFFF, 0);
        SymbolRenderPlan plan = SymbolRenderPlan.choose(GL33);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(plan.dataBytes(batch));
            plan.write(batch, 0, 0, data, new DrawList(DrawList.Kind.ARRAYS, 1), DisplayPalette.NIGHT);
            assertEquals(DisplayPalette.NIGHT.map(0xFFFFFFFF), SymbolGpu.color(data, 0));
        }
    }

    @Test
    void aRampIsMappedStopByStop() {
        ColorRamp r = ColorRamp.linear(new double[] {0, 10}, new int[] {0xFF0000FF, 0x00FF00FF});
        ColorRamp n = r.mapped(DisplayPalette.NIGHT);
        assertEquals(DisplayPalette.NIGHT.map(0xFF0000FF), n.colorAt(0));
        assertEquals(DisplayPalette.NIGHT.map(0x00FF00FF), n.colorAt(10));
        assertEquals(0xFF0000FF, r.colorAt(0), "the original is unchanged");
        assertTrue(ColorRamp.steps(new double[] {1}, new int[] {1, 2}).mapped(DisplayPalette.DAY).isStepped());
    }

    // ---------------------------------------------------------------- inset

    @Test
    void anInsetGivesViewportsFromBothCorners() {
        ViewportInset inset = ViewportInset.of(1920, 1080, 1520, 680, 380, 380);
        int[] gl = new int[4], top = new int[4];
        inset.fillViewport(gl);
        inset.fillTopLeft(top);
        assertArrayEquals(new int[] {1520, 20, 380, 380}, gl, "20 pixels above the bottom edge");
        assertArrayEquals(new int[] {1520, 680, 380, 380}, top);
        assertTrue(inset.contains(1520, 680));
        assertTrue(!inset.contains(1900, 700));
        assertTrue(!inset.contains(1519.9, 700));
        double[] p = new double[2];
        inset.toLocal(1600, 700, p);
        assertEquals(80, p[0]);
        assertEquals(20, p[1]);
        inset.toWindow(80, 20, p);
        assertEquals(1600, p[0]);
        assertEquals(700, p[1]);
    }

    @Test
    void anInsetIsClippedAndScaledAndRefusesEmptyOnes() {
        ViewportInset clipped = ViewportInset.of(100, 100, 80, 90, 50, 50);
        assertEquals(20, clipped.width());
        assertEquals(10, clipped.height());
        ViewportInset left = ViewportInset.of(100, 100, -10, -10, 50, 50);
        assertEquals(40, left.width());
        assertEquals(0, left.x());
        ViewportInset f = ViewportInset.fraction(1000, 500, 0.5, 0.5, 0.5, 0.5);
        assertEquals(500, f.x());
        assertEquals(250, f.height());
        ViewportInset s = f.scaled(2.0);
        assertEquals(1000, s.x());
        assertEquals(500, s.height());
        int[] gl = new int[4];
        s.fillViewport(gl);
        assertEquals(0, gl[1]);
        assertThrows(IllegalArgumentException.class, () -> ViewportInset.of(100, 100, 100, 0, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> ViewportInset.of(100, 100, 0, 0, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> ViewportInset.fraction(100, 100, 1.5, 0, 0.1, 0.1));
        assertThrows(IllegalArgumentException.class, () -> f.scaled(0.0));
        assertThrows(IllegalArgumentException.class, () -> f.fillViewport(new int[3]));
    }
}
