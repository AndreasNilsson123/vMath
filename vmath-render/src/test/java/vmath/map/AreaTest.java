package vmath.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;
import vmath.core.ClipSpace;
import vmath.geo.Utm;
import vmath.geo.WebMercatorProjection;
import vmath.gl.DrawList;
import vmath.gl.DrawSubmission;
import vmath.gl.GraphicsCapabilities;
import vmath.gl.GraphicsCapabilities.Feature;

/** {@link AreaStyle}, {@link AreaBatch}, {@link AreaRenderPlan} and {@link AreaShaderModel}. */
class AreaTest {

    private static final GraphicsCapabilities GL33 = GraphicsCapabilities.baseline();

    private static double ringArea(double[] xy, int from, int to) {
        double a = 0;
        for (int i = from; i < to; i++) {
            int j = i + 1 == to ? from : i + 1;
            a += xy[2 * i] * xy[2 * j + 1] - xy[2 * j] * xy[2 * i + 1];
        }
        return 0.5 * a;
    }

    private static double triangleArea(AreaBatch b) {
        double sum = 0;
        for (int t = 0; t < b.triangleCount(); t++) {
            int i = b.index(3 * t), j = b.index(3 * t + 1), k = b.index(3 * t + 2);
            sum += 0.5 * Math.abs((b.x(j) - b.x(i)) * (b.y(k) - b.y(i)) - (b.x(k) - b.x(i)) * (b.y(j) - b.y(i)));
        }
        return sum;
    }

    // ---------------------------------------------------------------- styles and batch

    @Test
    void stylesAreCheckedAndEqualOnesShareAnIndex() {
        AreaBatch b = new AreaBatch();
        int a = b.style(AreaStyle.solid(0x112233FF));
        assertEquals(a, b.style(AreaStyle.solid(0x112233FF)));
        assertEquals(1, b.style(AreaStyle.hatch(0, 0xFF, 6f, 1f, 0.5)));
        assertEquals(2, b.styleCount());
        assertThrows(IllegalArgumentException.class, () -> AreaStyle.hatch(0, 0, 0f, 1f, 0.0));
        assertThrows(IllegalArgumentException.class, () -> AreaStyle.dots(0, 0, 4f, -1f, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new AreaStyle(0, null, 0, 0f, 0f, 0f));
        assertThrows(IllegalArgumentException.class, () -> b.style(null));
        assertThrows(IllegalArgumentException.class, () -> b.addPolygon(new double[6], 3, 9));
    }

    @Test
    void aPolygonWithHolesIsTriangulatedToItsArea() {
        AreaBatch b = new AreaBatch();
        int s = b.style(AreaStyle.solid(0xFFFFFFFF));
        double[] xy = {0, 0, 100, 0, 100, 80, 0, 80,  20, 20, 20, 40, 40, 40, 40, 20,  60, 30, 60, 60, 80, 60, 80, 30};
        b.addPolygon(xy, new int[] {4, 8, 12}, s);
        double expected = ringArea(xy, 0, 4) - Math.abs(ringArea(xy, 4, 8)) - Math.abs(ringArea(xy, 8, 12));
        assertEquals(expected, triangleArea(b), 1e-3);
        assertEquals(12, b.vertexCount());
        assertEquals(1, b.polygonCount());
        assertEquals(b.triangleCount() * 3, b.indexCountOf(0));
    }

    @Test
    void aPolygonFarFromTheOriginKeepsItsShape() {
        // a UTM-like coordinate: 6 million metres north, where a float has half a metre of precision
        AreaBatch b = new AreaBatch();
        int s = b.style(AreaStyle.solid(0xFFFFFFFF));
        double ox = 512_345.25, oy = 6_100_000.5;
        double[] xy = {ox, oy, ox + 3.25, oy, ox + 3.25, oy + 1.5, ox, oy + 1.5};
        b.addPolygon(xy, 4, s);
        assertEquals(3.25 * 1.5, triangleArea(b), 1e-9, "the area in double precision");
    }

    @Test
    void badPolygonsAreRefused() {
        AreaBatch b = new AreaBatch();
        int s = b.style(AreaStyle.solid(0));
        assertThrows(IllegalArgumentException.class, () -> b.addPolygon(new double[] {0, 0, 1, 1}, 2, s));
        assertThrows(IllegalArgumentException.class, () -> b.addPolygon(new double[] {0, 0, 2, 2, 2, 0, 0, 2}, 4, s), "a bow tie");
        assertThrows(IllegalArgumentException.class, () -> b.addPolygon(new double[] {0, 0, 1, 0, Double.NaN, 1}, 3, s));
        assertThrows(IllegalArgumentException.class, () -> b.addPolygon(new double[] {0, 0, 1, 0, 1, 1, 5, 5, 6, 5}, new int[] {3, 5}, s), "a hole of two vertices");
        assertEquals(0, b.polygonCount());
    }

    @Test
    void shapesFromTheMapFeedTheBatch() {
        MapView2d v = MapView2d.of(WebMercatorProjection.INSTANCE, Math.toRadians(50), Math.toRadians(8), 400, 300).withMetersPerPixel(100.0);
        AreaBatch b = new AreaBatch();
        int s = b.style(AreaStyle.solid(0x4080C0FF));
        MapShapes shapes = new MapShapes(v, 0.5);
        shapes.disc(Math.toRadians(50), Math.toRadians(8), 5000.0, b.sink(s));
        assertTrue(b.polygonCount() >= 1);
        double radius = 5000.0 / Math.cos(Math.toRadians(50));                 // the disc in Web Mercator metres
        assertEquals(Math.PI * radius * radius, triangleArea(b), 0.01 * Math.PI * radius * radius);
        shapes.circle(Math.toRadians(50), Math.toRadians(8), 5000.0, b.sink(s));      // lines are ignored
    }

    // ---------------------------------------------------------------- the plan

    @Test
    void theStrategyFollowsTheCapabilities() {
        assertEquals(AreaStrategy.STYLE_TABLE, AreaStrategy.choose(GL33));
        assertEquals(AreaStrategy.VERTEX_COLOR, AreaStrategy.choose(GL33.without(Feature.UNIFORM_BLOCKS)));
        assertThrows(UnsupportedOperationException.class, () -> AreaRenderPlan.force(AreaStrategy.STYLE_TABLE, GL33.without(Feature.UNIFORM_BLOCKS)));
        AreaRenderPlan p = AreaRenderPlan.choose(GL33);
        assertTrue(p.vertexShader().contains("fetch_styles(int(a_word))"));
        assertTrue(p.fragmentShader().contains(AreaRenderPlan.U_PATTERN_OFFSET));
        assertNotNull(p.styleAccess());
        assertNull(AreaRenderPlan.force(AreaStrategy.VERTEX_COLOR, GL33).styleAccess());
        assertTrue(p.describe().contains("STYLE_TABLE"));
        assertEquals(48, AreaRenderPlan.STYLE_BYTES);
    }

    private static AreaBatch twoStyles() {
        AreaBatch b = new AreaBatch();
        int red = b.style(AreaStyle.solid(0xFF0000FF));
        int hatch = b.style(AreaStyle.hatch(0x0000FF40, 0x000000FF, 8f, 2f, Math.PI / 4));
        b.addPolygon(new double[] {0, 0, 10, 0, 10, 10, 0, 10}, 4, red);
        b.addPolygon(new double[] {20, 0, 30, 0, 30, 10, 20, 10}, 4, red);          // merges with the first
        b.addPolygon(new double[] {40, 0, 50, 0, 50, 10, 40, 10}, 4, hatch);
        b.addPolygon(new double[] {60, 0, 70, 0, 70, 10, 60, 10}, 4, red);
        return b;
    }

    @Test
    void consecutivePolygonsOfEqualStyleAreOneDraw() {
        AreaBatch b = twoStyles();
        for (AreaStrategy s : AreaStrategy.values()) {
            AreaRenderPlan plan = AreaRenderPlan.force(s, GL33);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment vertices = arena.allocate(plan.vertexBytes(b)), indices = arena.allocate(plan.indexBytes(b)), styles = arena.allocate(Math.max(16, plan.styleBytes(b)));
                DrawList draws = new DrawList(DrawList.Kind.ELEMENTS, 1);
                assertEquals(3, plan.write(b, 0, 0, vertices, indices, styles, draws), s.name());
                assertEquals(12, draws.count(0));
                assertEquals(0, draws.first(0));
                assertEquals(6, draws.count(1));
                assertEquals(12, draws.first(1));
                assertEquals(1, draws.user(1));
                assertEquals(0, draws.baseVertex(0));
                assertTrue(plan.submission(draws) != null);
            }
        }
        AreaRenderPlan plan = AreaRenderPlan.choose(GL33);
        DrawList draws = new DrawList(DrawList.Kind.ELEMENTS, 1);
        try (Arena arena = Arena.ofConfined()) {
            plan.write(b, 0, 0, arena.allocate(plan.vertexBytes(b)), arena.allocate(plan.indexBytes(b)), arena.allocate(plan.styleBytes(b)), draws);
        }
        assertEquals(DrawSubmission.MULTI_DRAW_CLIENT, plan.submission(draws));
        assertEquals(DrawSubmission.DRAW_LOOP, AreaRenderPlan.choose(GL33.without(Feature.MULTI_DRAW)).submission(draws));
        assertEquals(DrawSubmission.MULTI_DRAW_INDIRECT, AreaRenderPlan.choose(GraphicsCapabilities.openGl(4, 6, java.util.List.of())).submission(draws));
    }

    @Test
    void theStyleTableRoundTrips() {
        AreaStyle in = AreaStyle.crosshatch(0x11223344, 0x55667788, 7.5f, 1.25f, 0.3);
        MemorySegment m = MemorySegment.ofArray(new byte[(int) AreaRenderPlan.STYLE_BYTES * 2]);
        AreaRenderPlan.writeStyle(m, AreaRenderPlan.STYLE_BYTES, in);
        assertEquals(in, AreaRenderPlan.readStyle(m, AreaRenderPlan.STYLE_BYTES));
    }

    private static MapView2d view() {
        return MapView2d.of(WebMercatorProjection.INSTANCE, Math.toRadians(40), Math.toRadians(5), 200, 100).withMetersPerPixel(1.0);
    }

    /** Fills a pixel grid from the model, painter's order; returns the colour per pixel (0 = nothing). */
    private static int[] rasterise(AreaRenderPlan plan, AreaBatch batch, MapView2d v, float offX, float offY) {
        int[] image = new int[200 * 100];
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment vertices = arena.allocate(Math.max(16, plan.vertexBytes(batch))), indices = arena.allocate(Math.max(16, plan.indexBytes(batch)));
            MemorySegment styles = arena.allocate(Math.max(16, plan.styleBytes(batch)));
            DrawList draws = new DrawList(DrawList.Kind.ELEMENTS, 1);
            plan.write(batch, v.centerX(), v.centerY(), vertices, indices, styles, draws);
            float[] vp = new float[16];
            v.viewProjection(ClipSpace.OPENGL, v.centerX(), v.centerY(), vp);
            AreaShaderModel.evaluate(plan, vertices, indices, styles, draws, vp, 200f, 100f, (x, y, style) -> {
                for (int py = 0; py < 100; py++) {
                    for (int px = 0; px < 200; px++) {
                        float cx = px + 0.5f, cy = py + 0.5f;
                        float d = (x[1] - x[0]) * (y[2] - y[0]) - (x[2] - x[0]) * (y[1] - y[0]);
                        float w0 = ((x[1] - cx) * (y[2] - cy) - (x[2] - cx) * (y[1] - cy)) / d, w1 = ((x[2] - cx) * (y[0] - cy) - (x[0] - cx) * (y[2] - cy)) / d;
                        if (w0 >= 0f && w1 >= 0f && 1f - w0 - w1 >= 0f) {
                            image[py * 200 + px] = AreaShaderModel.colorAt(style, cx, cy, offX, offY);
                        }
                    }
                }
            });
        }
        return image;
    }

    @Test
    void theModelPaintsTheCoveredPixelsWithTheFillAndTheHatchOverIt() {
        MapView2d v = view();
        AreaBatch b = new AreaBatch();
        int plain = b.style(AreaStyle.solid(0xFF0000FF));
        int hatched = b.style(AreaStyle.hatch(0x00FF00FF, 0x000000FF, 10f, 3f, 0.0));      // lines along x: bands of 3 pixels every 10, in window y
        double cx = v.centerX(), cy = v.centerY(), u = v.mapUnitsPerPixel();
        b.addPolygon(new double[] {cx - 90 * u, cy - 40 * u, cx - 10 * u, cy - 40 * u, cx - 10 * u, cy + 40 * u, cx - 90 * u, cy + 40 * u}, 4, plain);
        b.addPolygon(new double[] {cx + 10 * u, cy - 40 * u, cx + 90 * u, cy - 40 * u, cx + 90 * u, cy + 40 * u, cx + 10 * u, cy + 40 * u}, 4, hatched);
        int[] image = rasterise(AreaRenderPlan.choose(GL33), b, v, 0f, 0f);
        int red = 0, bands = 0, ground = 0;
        for (int y = 0; y < 100; y++) {
            for (int x = 0; x < 200; x++) {
                int c = image[y * 200 + x];
                if (x >= 10 && x < 90 && y >= 10 && y < 90) {
                    red += c == 0xFF0000FF ? 1 : 0;
                }
                if (x >= 110 && x < 190 && y >= 10 && y < 90) {
                    if (c == 0x000000FF) {
                        bands++;
                    } else if (c == 0x00FF00FF) {
                        ground++;
                    }
                }
            }
        }
        assertEquals(80 * 80, red);
        assertEquals(80 * 80, bands + ground);
        assertEquals(0.3, bands / (80.0 * 80.0), 0.02, "3 pixels in 10 are the line");
        int[] moved = rasterise(AreaRenderPlan.choose(GL33), b, v, 0f, 4f);
        int different = 0;
        for (int i = 0; i < image.length; i++) {
            different += image[i] != moved[i] ? 1 : 0;
        }
        assertTrue(different > 0, "the offset moves the pattern");
    }

    @Test
    void thePatternsHaveTheirPeriod() {
        AreaStyle hatch = AreaStyle.hatch(0, 0xAAAAAAFF, 10f, 2f, 0.0);
        int on = 0;
        for (int i = 0; i < 10; i++) {
            on += AreaShaderModel.colorAt(hatch, 5.5f, i + 0.5f, 0f, 0f) == 0xAAAAAAFF ? 1 : 0;
        }
        assertEquals(2, on, "two of ten pixels along a period");
        assertEquals(AreaShaderModel.colorAt(hatch, 3.5f, 4.5f, 0f, 0f), AreaShaderModel.colorAt(hatch, 33.5f, 14.5f, 0f, 0f), "periodic in x and y by the spacing");
        AreaStyle cross = AreaStyle.crosshatch(0, 0xFFFFFFFF, 10f, 2f, 0.0);
        assertEquals(0xFFFFFFFF, AreaShaderModel.colorAt(cross, 5.5f, 0.5f, 0f, 0f));
        assertEquals(0xFFFFFFFF, AreaShaderModel.colorAt(cross, 9.5f, 5.5f, 0f, 0f), "the second set is at a right angle");
        assertEquals(0, AreaShaderModel.colorAt(cross, 5.5f, 5.5f, 0f, 0f));
        AreaStyle dots = AreaStyle.dots(0, 0xFFFFFFFF, 10f, 4f, 0.0);
        assertEquals(0xFFFFFFFF, AreaShaderModel.colorAt(dots, 5.0f, 5.0f, 0f, 0f), "the dot is centred between lattice lines");
        assertEquals(0, AreaShaderModel.colorAt(dots, 0.5f, 5.0f, 0f, 0f), "between two dots");
        assertEquals(0x11223344, AreaShaderModel.colorAt(AreaStyle.solid(0x11223344), 3f, 4f, 0f, 0f));
    }

    @Test
    void theVertexColorFloorDrawsTheFillOfEveryStyle() {
        MapView2d v = view();
        AreaBatch b = new AreaBatch();
        int hatched = b.style(AreaStyle.hatch(0x00FF00FF, 0x000000FF, 10f, 3f, 0.0));
        double cx = v.centerX(), cy = v.centerY(), u = v.mapUnitsPerPixel();
        b.addPolygon(new double[] {cx - 50 * u, cy - 30 * u, cx + 50 * u, cy - 30 * u, cx + 50 * u, cy + 30 * u, cx - 50 * u, cy + 30 * u}, 4, hatched);
        int[] image = rasterise(AreaRenderPlan.force(AreaStrategy.VERTEX_COLOR, GL33), b, v, 0f, 0f);
        int fill = 0;
        for (int c : image) {
            fill += c == 0x00FF00FF ? 1 : 0;
            assertTrue(c == 0 || c == 0x00FF00FF, "no pattern pixels without the style table");
        }
        assertEquals(100 * 60, fill);
    }

    @Test
    void thePatternCanBeFixedToTheMap() {
        MapView2d v = view();
        float[] a = new float[2], b = new float[2];
        AreaRenderPlan.patternOffset(v, v.centerX(), v.centerY(), a);
        assertEquals(-100f, a[0], 1e-3f);
        assertEquals(-50f, a[1], 1e-3f);
        AreaRenderPlan.patternOffset(v.withCenterOffset(0, 0), v.centerX() + 10.0 * v.mapUnitsPerPixel(), v.centerY(), b);
        assertEquals(-110f, b[0], 1e-2f);
        assertThrows(IllegalArgumentException.class, () -> AreaRenderPlan.patternOffset(v, 0, 0, new float[1]));
    }

    @Test
    void aFarPolygonStaysWhereItIsOnTheScreen() {
        // a view in a UTM zone: the polygon is a few pixels from the centre, millions of metres from the zone's origin
        var utm = Utm.projection(32, true);
        MapView2d v = MapView2d.of(utm, Math.toRadians(52), Math.toRadians(9), 200, 100).withMetersPerPixel(1.0);
        AreaBatch b = new AreaBatch();
        int s = b.style(AreaStyle.solid(0xFFFFFFFF));
        double cx = v.centerX(), cy = v.centerY();
        b.addPolygon(new double[] {cx - 20, cy - 10, cx + 20, cy - 10, cx + 20, cy + 10, cx - 20, cy + 10}, 4, s);
        int[] image = rasterise(AreaRenderPlan.choose(GL33), b, v, 0f, 0f);
        int n = 0;
        for (int y = 0; y < 100; y++) {
            for (int x = 0; x < 200; x++) {
                if (image[y * 200 + x] != 0) {
                    n++;
                    assertTrue(x >= 100 - 20 / v.mapUnitsPerPixel() * 1.0 - 2 && x < 100 + 22 && y >= 50 - 12 && y < 50 + 12);
                }
            }
        }
        assertEquals(40 * 20 * v.pixelsPerMapUnit() * v.pixelsPerMapUnit(), n, 40.0 * 20.0 * v.pixelsPerMapUnit() * 0.1 + 20);
    }

    @Test
    void smallBuffersAndWrongDrawListsAreRefused() {
        AreaBatch b = twoStyles();
        AreaRenderPlan plan = AreaRenderPlan.choose(GL33);
        MemorySegment big = MemorySegment.ofArray(new byte[4096]);
        assertThrows(IllegalArgumentException.class, () -> plan.write(b, 0, 0, MemorySegment.ofArray(new byte[8]), big, big, new DrawList(DrawList.Kind.ELEMENTS, 1)));
        assertThrows(IllegalArgumentException.class, () -> plan.write(b, 0, 0, big, big, big, new DrawList(DrawList.Kind.ARRAYS, 1)));
        assertThrows(IllegalArgumentException.class, () -> plan.write(b, 0, 0, big, big, MemorySegment.ofArray(new byte[8]), new DrawList(DrawList.Kind.ELEMENTS, 1)));
        assertEquals(0, plan.write(new AreaBatch(), 0, 0, big, big, big, new DrawList(DrawList.Kind.ELEMENTS, 1)));
    }
}
