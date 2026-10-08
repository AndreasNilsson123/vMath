package vmath.map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.core.ClipSpace;
import vmath.geo.WebMercatorProjection;
import vmath.gl.DrawList;
import vmath.gl.GraphicsCapabilities;
import vmath.gl.GraphicsCapabilities.Feature;

/** {@link SymbolAtlas}, {@link SymbolBatch}, {@link SymbolRenderPlan} and its CPU model. */
class SymbolTest {

    private static final GraphicsCapabilities GL33 = GraphicsCapabilities.baseline();

    private static byte[] solid(int w, int h, int rgba) {
        byte[] b = new byte[w * h * 4];
        for (int i = 0; i < w * h; i++) {
            b[4 * i] = (byte) (rgba >>> 24);
            b[4 * i + 1] = (byte) (rgba >>> 16);
            b[4 * i + 2] = (byte) (rgba >>> 8);
            b[4 * i + 3] = (byte) rgba;
        }
        return b;
    }

    private static SymbolAtlas atlas() {
        return SymbolAtlas.builder().add("red", 8, 8, solid(8, 8, 0xFF0000FF)).add("green", 16, 10, solid(16, 10, 0x00FF00FF)).add("blue", 5, 21, solid(5, 21, 0x0000FFFF)).build(256);
    }

    // ---------------------------------------------------------------- atlas

    @Test
    void theAtlasPacksWithoutOverlapAndKeepsTheGutterClear() {
        SymbolAtlas.Builder b = SymbolAtlas.builder();
        int n = 40;
        for (int i = 0; i < n; i++) {
            b.add("s" + i, 3 + i % 13, 4 + i % 7, solid(3 + i % 13, 4 + i % 7, 0x01010100 * (i + 1) | 0xFF));
        }
        SymbolAtlas a = b.build(256);
        assertEquals(n, a.count());
        assertEquals(Integer.bitCount(a.width()), 1);
        assertEquals(Integer.bitCount(a.height()), 1);
        int[][] r = new int[n][4];
        for (int i = 0; i < n; i++) {
            a.texels(i, r[i]);
            assertTrue(r[i][0] >= SymbolAtlas.GUTTER && r[i][1] >= SymbolAtlas.GUTTER);
            assertTrue(r[i][0] + r[i][2] + SymbolAtlas.GUTTER <= a.width() && r[i][1] + r[i][3] + SymbolAtlas.GUTTER <= a.height());
            // every texel of the sprite and its gutter has the sprite's colour
            int colour = 0x01010100 * (i + 1) | 0xFF;
            for (int y = r[i][1] - 1; y < r[i][1] + r[i][3] + 1; y++) {
                for (int x = r[i][0] - 1; x < r[i][0] + r[i][2] + 1; x++) {
                    assertEquals(colour, a.sampleNearest((x + 0.5f) / a.width(), (y + 0.5f) / a.height()), "sprite " + i + " at " + x + "," + y);
                }
            }
            for (int j = 0; j < i; j++) {
                boolean apart = r[i][0] - 1 >= r[j][0] + r[j][2] + 1 || r[j][0] - 1 >= r[i][0] + r[i][2] + 1 || r[i][1] - 1 >= r[j][1] + r[j][3] + 1 || r[j][1] - 1 >= r[i][1] + r[i][3] + 1;
                assertTrue(apart, "sprites " + i + " and " + j + " overlap including their gutters");
            }
        }
    }

    @Test
    void theRectangleIsTheWholeSpriteInTextureCoordinates() {
        SymbolAtlas a = atlas();
        int[] t = new int[4];
        float[] uv = new float[4];
        int g = a.indexOf("green");
        a.texels(g, t);
        a.rect(g, uv);
        assertEquals((float) t[0] / a.width(), uv[0]);
        assertEquals((float) (t[1] + t[3]) / a.height(), uv[3]);
        assertEquals(16, a.spriteWidth(g));
        assertEquals(10, a.spriteHeight(g));
        assertEquals("green", a.name(g));
        assertEquals(0x00FF00FF, a.sampleNearest((uv[0] + uv[2]) / 2, (uv[1] + uv[3]) / 2));
    }

    @Test
    void theAtlasRefusesBadInput() {
        assertThrows(IllegalArgumentException.class, () -> SymbolAtlas.builder().add("a", 2, 2, new byte[3]));
        assertThrows(IllegalArgumentException.class, () -> SymbolAtlas.builder().add("a", 0, 2, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> SymbolAtlas.builder().add("a", 1, 1, new byte[4]).add("a", 1, 1, new byte[4]));
        assertThrows(IllegalArgumentException.class, () -> SymbolAtlas.builder().build(64));
        assertThrows(IllegalStateException.class, () -> SymbolAtlas.builder().add("big", 70, 70, new byte[70 * 70 * 4]).build(64));
        assertThrows(IllegalArgumentException.class, () -> atlas().indexOf("nope"));
    }

    // ---------------------------------------------------------------- records

    @Test
    void theRecordIsFortyEightBytesAndRoundTrips() {
        assertEquals(48, SymbolGpu.BYTES);
        MemorySegment m = MemorySegment.ofArray(new byte[96]);
        SymbolGpu.write(m, 48, 1.5f, -2.5f, 0.25f, 24f, new float[] {0.1f, 0.2f, 0.3f, 0.4f}, 0x11223344, SymbolGpu.ROTATE_WITH_MAP | SymbolGpu.HIDDEN, 3f, -4f);
        assertEquals(1.5f, SymbolGpu.read(m, 48, 0));
        assertEquals(-2.5f, SymbolGpu.read(m, 48, 1));
        assertEquals(0.25f, SymbolGpu.read(m, 48, 2));
        assertEquals(24f, SymbolGpu.read(m, 48, 3));
        assertEquals(0.3f, SymbolGpu.read(m, 48, 6));
        assertEquals(3f, SymbolGpu.read(m, 48, 8));
        assertEquals(-4f, SymbolGpu.read(m, 48, 9));
        assertEquals(0x11223344, SymbolGpu.color(m, 48));
        assertEquals(5, SymbolGpu.flags(m, 48));
        assertThrows(IllegalArgumentException.class, () -> SymbolGpu.read(m, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> SymbolGpu.write(m, 0, 0f, 0f, 0f, 1f, new float[3], 0, 0, 0f, 0f));
    }

    @Test
    void theBatchGrowsAndKeepsItsValues() {
        SymbolBatch b = new SymbolBatch(1);
        float[] uv = {0f, 0f, 1f, 1f};
        for (int i = 0; i < 100; i++) {
            assertEquals(i, b.add(i, -i, 0.5 * i, 10f + i, uv, i, 0));
        }
        assertEquals(100, b.count());
        assertEquals(99.0, b.x(99));
        assertEquals(-99.0, b.y(99));
        assertEquals(109f, b.size(99));
        b.setFlags(5, SymbolGpu.HIDDEN, true);
        assertEquals(SymbolGpu.HIDDEN, b.flags(5));
        b.setFlags(5, SymbolGpu.HIDDEN, false);
        assertEquals(0, b.flags(5));
        b.setOffset(7, 2f, 3f);
        assertEquals(3f, b.offset(7, 1));
        b.clear();
        assertEquals(0, b.count());
        assertThrows(IndexOutOfBoundsException.class, () -> b.x(0));
        assertThrows(IllegalArgumentException.class, () -> b.add(Double.NaN, 0, 0, 1f, uv, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> b.add(0, 0, 0, -1f, uv, 0, 0));
    }

    // ---------------------------------------------------------------- the plan

    @Test
    void theStrategyFollowsTheCapabilities() {
        assertEquals(SymbolStrategy.INSTANCED, SymbolStrategy.choose(GL33));
        assertEquals(SymbolStrategy.TEXTURE_FETCH, SymbolStrategy.choose(GL33.without(Feature.INSTANCED_ARRAYS)));
        assertEquals(SymbolStrategy.TEXTURE_FETCH, SymbolStrategy.choose(GL33.without(Feature.INSTANCED_DRAWS)));
        assertEquals(SymbolStrategy.EXPANDED, SymbolStrategy.choose(GL33.without(Feature.INSTANCED_ARRAYS, Feature.TEXTURE_BUFFERS)));
        assertEquals(SymbolStrategy.EXPANDED, SymbolStrategy.chooseAtMost(GL33, SymbolStrategy.EXPANDED));
        assertThrows(UnsupportedOperationException.class, () -> SymbolRenderPlan.force(SymbolStrategy.INSTANCED, GL33.without(Feature.INSTANCED_ARRAYS)));
        assertEquals(List.of(SymbolStrategy.INSTANCED, SymbolStrategy.TEXTURE_FETCH, SymbolStrategy.EXPANDED), SymbolStrategy.chooser().strategies());
    }

    @Test
    void theShadersNameTheUniformsAndTheStrategyDecidesTheIndex() {
        SymbolRenderPlan fetch = SymbolRenderPlan.force(SymbolStrategy.TEXTURE_FETCH, GL33);
        SymbolRenderPlan inst = SymbolRenderPlan.force(SymbolStrategy.INSTANCED, GL33);
        assertTrue(fetch.vertexShader().startsWith("#version 330"));
        assertTrue(fetch.vertexShader().contains("fetch_symbols(gl_VertexID / 6)"));
        assertTrue(inst.vertexShader().contains("fetch_symbols(0)"));
        for (String u : new String[] {SymbolRenderPlan.U_VIEW_PROJECTION, SymbolRenderPlan.U_VIEWPORT, SymbolRenderPlan.U_MAP_ROTATION, SymbolRenderPlan.U_PIXELS_PER_UNIT, SymbolRenderPlan.U_HANDEDNESS}) {
            assertTrue(inst.vertexShader().contains(u), u);
        }
        assertTrue(inst.fragmentShader().contains(SymbolRenderPlan.U_ATLAS));
        assertTrue(inst.describe().contains("INSTANCED"));
        assertTrue(fetch.describe().contains("texture buffer"));
        assertTrue(SymbolRenderPlan.force(SymbolStrategy.EXPANDED, GL33).describe().contains("repeated 6 times"));
    }

    private static final class Collected {
        final List<float[]> tris = new ArrayList<>();
        final List<Integer> symbols = new ArrayList<>();
        final List<Integer> colors = new ArrayList<>();
    }

    private static Collected run(SymbolRenderPlan plan, SymbolBatch batch, MapView2d view, float handedness, ClipSpace space) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(Math.max(1, plan.dataBytes(batch)));
            DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 1);
            plan.write(batch, view.centerX(), view.centerY(), data, draws);
            float[] vp = new float[16];
            view.viewProjection(space, view.centerX(), view.centerY(), vp);
            Collected c = new Collected();
            SymbolShaderModel.evaluate(plan, data, draws, vp, view.width(), view.height(), SymbolRenderPlan.mapRotation(view), (float) view.pixelsPerMapUnit(), handedness,
                    (s, x, y, u, v, color) -> {
                        c.tris.add(new float[] {x[0], y[0], x[1], y[1], x[2], y[2], u[0], v[0], u[1], v[1], u[2], v[2]});
                        c.symbols.add(s);
                        c.colors.add(color);
                    });
            return c;
        }
    }

    private static MapView2d view() {
        return MapView2d.of(WebMercatorProjection.INSTANCE, Math.toRadians(30.0), Math.toRadians(10.0), 800, 600).withMetersPerPixel(100.0);
    }

    @Test
    void aSymbolIsASquareOfThePixelSizeAtItsPosition() {
        MapView2d v = view();
        SymbolBatch b = new SymbolBatch(2);
        float[] uv = {0.25f, 0.25f, 0.5f, 0.5f};
        double[] p = new double[2];
        v.projectedToScreen(v.centerX() + 3000.0, v.centerY() - 1000.0, p);       // 30 pixels right, 10 pixels down of the centre
        b.add(v.centerX() + 3000.0, v.centerY() - 1000.0, 0.0, 20f, uv, 0xFFFFFFFF, 0);
        for (SymbolStrategy s : SymbolStrategy.values()) {
            Collected c = run(SymbolRenderPlan.force(s, GL33), b, v, 1f, ClipSpace.OPENGL);
            assertEquals(2, c.tris.size(), s.name());
            float minX = 1e9f, maxX = -1e9f, minY = 1e9f, maxY = -1e9f;
            for (float[] t : c.tris) {
                for (int k = 0; k < 3; k++) {
                    minX = Math.min(minX, t[2 * k]);
                    maxX = Math.max(maxX, t[2 * k]);
                    minY = Math.min(minY, t[2 * k + 1]);
                    maxY = Math.max(maxY, t[2 * k + 1]);
                }
            }
            assertEquals(20f, maxX - minX, 1e-3f, s.name());
            assertEquals(20f, maxY - minY, 1e-3f, s.name());
            assertEquals(p[0], (minX + maxX) / 2, 1e-2, s.name());
            // window y counts up in the model, from the bottom of the viewport: 600 - screen y
            assertEquals(600 - p[1], (minY + maxY) / 2, 1e-2, s.name());
        }
    }

    @Test
    void everyStrategyGivesTheSameTriangles() {
        MapView2d v = view().withOrientation(MapView2d.Orientation.ANGLE, Math.toRadians(30));
        SymbolBatch b = new SymbolBatch(4);
        float[] uv = {0f, 0f, 1f, 1f};
        java.util.Random r = new java.util.Random(5);
        for (int i = 0; i < 50; i++) {
            b.add(v.centerX() + (r.nextDouble() - 0.5) * 60000, v.centerY() + (r.nextDouble() - 0.5) * 40000, r.nextDouble() * 6, 8f + r.nextInt(30), uv, r.nextInt(),
                    (r.nextBoolean() ? SymbolGpu.ROTATE_WITH_MAP : 0) | (r.nextInt(5) == 0 ? SymbolGpu.HIDDEN : 0));
            b.setOffset(i, r.nextInt(20) - 10, r.nextInt(20) - 10);
        }
        Collected base = run(SymbolRenderPlan.force(SymbolStrategy.INSTANCED, GL33), b, v, 1f, ClipSpace.OPENGL);
        assertTrue(base.tris.size() > 60);
        for (SymbolStrategy s : new SymbolStrategy[] {SymbolStrategy.TEXTURE_FETCH, SymbolStrategy.EXPANDED}) {
            Collected c = run(SymbolRenderPlan.force(s, GL33), b, v, 1f, ClipSpace.OPENGL);
            assertEquals(base.tris.size(), c.tris.size(), s.name());
            for (int i = 0; i < c.tris.size(); i++) {
                assertArrayEquals(base.tris.get(i), c.tris.get(i), s.name() + " triangle " + i);
                assertEquals(base.symbols.get(i), c.symbols.get(i));
                assertEquals(base.colors.get(i), c.colors.get(i));
            }
        }
        for (int s : base.symbols) {
            assertEquals(0, b.flags(s) & SymbolGpu.HIDDEN, "a hidden symbol was drawn");
        }
    }

    /** The direction in which the top of the sprite points, window pixels with y up, from the vertices with v = 0 against the centre of the quad. */
    private static double[] top(Collected c, MapView2d v) {
        double sx = 0, sy = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (float[] t : c.tris) {
            for (int k = 0; k < 3; k++) {
                if (t[7 + 2 * k] == 0f && seen.add(t[2 * k] + "," + t[2 * k + 1])) {
                    sx += t[2 * k] - v.width() / 2.0;
                    sy += t[2 * k + 1] - v.height() / 2.0;
                }
            }
        }
        double len = Math.hypot(sx, sy);
        return new double[] {sx / len, sy / len};
    }

    /** The direction on the screen (y up) of a step of the given projected vector from the centre of the view. */
    private static double[] stepOnScreen(MapView2d v, double dx, double dy) {
        double[] a = new double[2], b = new double[2];
        v.projectedToScreen(v.centerX(), v.centerY(), a);
        v.projectedToScreen(v.centerX() + dx, v.centerY() + dy, b);
        double ex = b[0] - a[0], ey = -(b[1] - a[1]);
        double len = Math.hypot(ex, ey);
        return new double[] {ex / len, ey / len};
    }

    @Test
    void rotationWithTheMapFollowsTheGridAndTheOtherDoesNot() {
        float[] uv = {0f, 0f, 1f, 1f};
        for (MapView2d v : new MapView2d[] {view(), view().withOrientation(MapView2d.Orientation.ANGLE, Math.PI / 2), view().withOrientation(MapView2d.Orientation.ANGLE, Math.toRadians(200))}) {
            SymbolBatch fixed = new SymbolBatch(1), turning = new SymbolBatch(1);
            fixed.add(v.centerX(), v.centerY(), 0.0, 40f, uv, 0xFFFFFFFF, 0);
            turning.add(v.centerX(), v.centerY(), 0.0, 40f, uv, 0xFFFFFFFF, SymbolGpu.ROTATE_WITH_MAP);
            double[] upright = top(run(SymbolRenderPlan.choose(GL33), fixed, v, 1f, ClipSpace.OPENGL), v);
            assertEquals(0.0, upright[0], 1e-4);
            assertEquals(1.0, upright[1], 1e-4);
            double[] north = stepOnScreen(v, 0, 1000);
            double[] pointing = top(run(SymbolRenderPlan.choose(GL33), turning, v, 1f, ClipSpace.OPENGL), v);
            assertEquals(north[0], pointing[0], 1e-3, "orientation " + v.orientation() + " " + Math.toDegrees(v.upBearing()));
            assertEquals(north[1], pointing[1], 1e-3);
        }
    }

    @Test
    void aHeadingDrawsTowardsItsBearingOnTheScreen() {
        float[] uv = {0f, 0f, 1f, 1f};
        for (MapView2d v : new MapView2d[] {view(), view().withOrientation(MapView2d.Orientation.ANGLE, Math.toRadians(70))}) {
            for (double bearingDegrees : new double[] {0, 90, 135, 270}) {
                double bearing = Math.toRadians(bearingDegrees);
                SymbolBatch b = new SymbolBatch(1);
                b.add(v.centerX(), v.centerY(), -bearing, 40f, uv, 0xFFFFFFFF, SymbolGpu.ROTATE_WITH_MAP);
                double[] toward = stepOnScreen(v, 1000 * Math.sin(bearing), 1000 * Math.cos(bearing));
                double[] pointing = top(run(SymbolRenderPlan.choose(GL33), b, v, 1f, ClipSpace.OPENGL), v);
                assertEquals(toward[0], pointing[0], 1e-3, "bearing " + bearingDegrees);
                assertEquals(toward[1], pointing[1], 1e-3, "bearing " + bearingDegrees);
            }
        }
    }

    @Test
    void aSizeInMapUnitsScalesWithTheMap() {
        MapView2d v = view();
        float[] uv = {0f, 0f, 1f, 1f};
        SymbolBatch b = new SymbolBatch(1);
        b.add(v.centerX(), v.centerY(), 0.0, 5000f, uv, 0xFFFFFFFF, SymbolGpu.SIZE_IN_MAP_UNITS);
        for (double mpp : new double[] {100.0, 50.0}) {
            MapView2d w = v.withMetersPerPixel(mpp);
            Collected c = run(SymbolRenderPlan.force(SymbolStrategy.INSTANCED, GL33), b, w, 1f, ClipSpace.OPENGL);
            float minX = 1e9f, maxX = -1e9f;
            for (float[] t : c.tris) {
                for (int k = 0; k < 3; k++) {
                    minX = Math.min(minX, t[2 * k]);
                    maxX = Math.max(maxX, t[2 * k]);
                }
            }
            assertEquals(5000.0 / (mpp / 1.0) * 1.0 / (w.mapUnitsPerPixel() / mpp), maxX - minX, 0.05, "mpp " + mpp);
        }
    }

    @Test
    void aYDownClipSpaceMirrorsTheOffsetsAndTheAngles() {
        MapView2d v = view();
        float[] uv = {0f, 0f, 1f, 1f};
        SymbolBatch b = new SymbolBatch(1);
        b.add(v.centerX(), v.centerY(), 0.0, 10f, uv, 0xFFFFFFFF, 0);
        b.setOffset(0, 30f, 20f);
        Collected up = run(SymbolRenderPlan.choose(GL33), b, v, 1f, ClipSpace.OPENGL);
        Collected down = run(SymbolRenderPlan.choose(GL33), b, v, -1f, ClipSpace.VULKAN);
        float upY = up.tris.get(0)[1], downY = down.tris.get(0)[1];
        assertEquals(300f + 20f - 5f, upY, 1e-3f);
        assertEquals(300f - 20f + 5f, downY, 1e-3f);
    }

    @Test
    void theBufferIsRelativeToTheOriginSoFarFromZeroStaysExact() {
        MapView2d v = MapView2d.of(WebMercatorProjection.INSTANCE, Math.toRadians(60.0), Math.toRadians(179.9), 800, 600).withMetersPerPixel(1.0);
        float[] uv = {0f, 0f, 1f, 1f};
        SymbolBatch b = new SymbolBatch(2);
        b.add(v.centerX() + 10.0, v.centerY(), 0.0, 4f, uv, 0xFFFFFFFF, 0);
        b.add(v.centerX() + 11.0, v.centerY(), 0.0, 4f, uv, 0xFFFFFFFF, 0);
        Collected c = run(SymbolRenderPlan.choose(GL33), b, v, 1f, ClipSpace.OPENGL);
        float x0 = c.tris.get(0)[0], x1 = c.tris.get(2)[0];
        assertEquals((float) v.pixelsPerMapUnit(), x1 - x0, 1e-3f, "one projected metre apart, 20 million metres from the origin of the projection");
    }

    @Test
    void anEmptyBatchHasNoDrawAndSmallBuffersAreRefused() {
        SymbolRenderPlan plan = SymbolRenderPlan.choose(GL33);
        DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 1);
        assertEquals(0, plan.write(new SymbolBatch(1), 0, 0, MemorySegment.ofArray(new byte[0]), draws));
        SymbolBatch b = new SymbolBatch(1);
        b.add(0, 0, 0, 1f, new float[4], 0, 0);
        assertThrows(IllegalArgumentException.class, () -> plan.write(b, 0, 0, MemorySegment.ofArray(new byte[10]), draws));
        assertThrows(IllegalArgumentException.class, () -> plan.write(b, 0, 0, MemorySegment.ofArray(new byte[100]), new DrawList(DrawList.Kind.ELEMENTS, 1)));
        assertEquals(SymbolGpu.BYTES * 6, SymbolRenderPlan.force(SymbolStrategy.EXPANDED, GL33).dataBytes(b));
        assertFalse(plan.submission(draws) == null);
    }
}
