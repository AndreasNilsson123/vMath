package vmath.lines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import vmath.gl.DrawList;
import vmath.gl.DrawSubmission;
import vmath.gl.GlslVersion;
import vmath.gl.GraphicsCapabilities;
import vmath.gl.GraphicsCapabilities.Feature;
import vmath.gl.StructArrayAccess;

/**
 * {@link LineStrategy}, {@link LineRenderPlan} and {@link LineShaderModel}: the choice, the draws
 * and buffers each strategy writes, and above all that every strategy produces the same pixels as
 * the reference expansion of the batch, orthographic and perspective, with dashes, world-unit
 * widths, layers, closed lines and runs of equal style.
 */
class LineRenderPlanTest {

    private static final int SIZE = 200;
    private static final GraphicsCapabilities GL33 = GraphicsCapabilities.baseline();
    private static final GraphicsCapabilities GL42 = GraphicsCapabilities.openGl(4, 2, List.of());
    private static final GraphicsCapabilities GL43 = GraphicsCapabilities.openGl(4, 3, List.of());
    private static final GraphicsCapabilities GL45 = GraphicsCapabilities.openGl(4, 5, List.of("GL_ARB_shader_draw_parameters"));
    private static final GraphicsCapabilities GL46 = GraphicsCapabilities.openGl(4, 6, List.of());

    private final Random rnd = new Random(Long.getLong("vmath.seed", 99L));

    // ---------------------------------------------------------------- the choice

    @Test
    void theBestStrategyFollowsTheCapabilities() {
        assertEquals(LineStrategy.EXPANDED_MULTIDRAW, LineStrategy.choose(GL33));
        assertEquals(LineStrategy.EXPANDED_MULTIDRAW, LineStrategy.choose(GL42));
        assertEquals(LineStrategy.INDIRECT_INSTANCE_STYLE, LineStrategy.choose(GL43));
        assertEquals(LineStrategy.INDIRECT_DRAW_ID, LineStrategy.choose(GL45));
        assertEquals(LineStrategy.INDIRECT_DRAW_ID, LineStrategy.choose(GL46));
        assertEquals(LineStrategy.INSTANCED_LOOP, LineStrategy.choose(GraphicsCapabilities.vulkan(false, false, false)), "Vulkan has no glMultiDrawArrays");
        assertEquals(LineStrategy.INDIRECT_INSTANCE_STYLE, LineStrategy.choose(GraphicsCapabilities.vulkan(true, true, false)));
        assertEquals(LineStrategy.INDIRECT_DRAW_ID, LineStrategy.choose(GraphicsCapabilities.vulkan(true, true, true)));
        assertEquals(LineStrategy.INSTANCED_LOOP, LineStrategy.choose(GL33.without(Feature.MULTI_DRAW)));
        assertEquals(LineStrategy.INSTANCED_LOOP, LineStrategy.choose(GL33.without(Feature.TEXTURE_BUFFERS)));
        assertEquals(LineStrategy.HAIRLINE, LineStrategy.choose(GL33.without(Feature.MULTI_DRAW, Feature.INSTANCED_DRAWS)));
        assertEquals(LineStrategy.HAIRLINE, LineStrategy.choose(GL33.without(Feature.TEXTURE_BUFFERS, Feature.INSTANCED_ARRAYS)));
    }

    @Test
    void aCeilingAndForcingChooseLowerStrategies() {
        assertEquals(LineStrategy.INDIRECT_INSTANCE_STYLE, LineStrategy.chooseAtMost(GL46, LineStrategy.INDIRECT_INSTANCE_STYLE));
        assertEquals(LineStrategy.EXPANDED_MULTIDRAW, LineStrategy.chooseAtMost(GL46, LineStrategy.EXPANDED_MULTIDRAW));
        assertEquals(LineStrategy.INSTANCED_LOOP, LineStrategy.chooseAtMost(GL46, LineStrategy.INSTANCED_LOOP));
        assertEquals(LineStrategy.HAIRLINE, LineStrategy.chooseAtMost(GL46, LineStrategy.HAIRLINE));
        assertEquals(LineStrategy.HAIRLINE, LineRenderPlan.force(LineStrategy.HAIRLINE, GL33).strategy());
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class, () -> LineRenderPlan.force(LineStrategy.INDIRECT_DRAW_ID, GL43));
        assertTrue(e.getMessage().contains("SHADER_DRAW_PARAMETERS"), e.getMessage());
        assertEquals(List.of(LineStrategy.INDIRECT_DRAW_ID, LineStrategy.INDIRECT_INSTANCE_STYLE, LineStrategy.EXPANDED_MULTIDRAW, LineStrategy.INSTANCED_LOOP, LineStrategy.HAIRLINE),
                LineStrategy.chooser().strategies());
        assertEquals(LineStrategy.EXPANDED_MULTIDRAW, LineRenderPlan.choose(GL33).strategy());
        assertEquals(GL33, LineRenderPlan.choose(GL33).capabilities());
    }

    // ---------------------------------------------------------------- scenes

    /** Points on the screen with a pixel per world unit. */
    private LineBatch screenScene(int polylines, boolean world) {
        LineBatch b = new LineBatch();
        LineStyle[] styles = {
                LineStyle.pixels(3f).withColor(0xFF0000FF),
                LineStyle.pixels(9f).withColor(0x00FF00FF).withCap(LineStyle.Cap.ROUND).withJoin(LineStyle.Join.ROUND),
                LineStyle.pixels(5f).withColor(0x0000FFFF).withCap(LineStyle.Cap.SQUARE).withJoin(LineStyle.Join.BEVEL).withDash(12f, 7f),
                (world ? LineStyle.world(6f) : LineStyle.pixels(6f)).withColor(0xFFFF00FF).withMiterLimit(2f).withDash(20f, 5f, 3f, 5f),
                LineStyle.pixels(2f).withColor(0xFF00FFFF).withLayer(-1),
                LineStyle.pixels(7f).withColor(0x00FFFFFF).withLayer(1).withJoin(LineStyle.Join.ROUND)};
        for (int i = 0; i < polylines; i++) {
            int n = 2 + rnd.nextInt(5);
            double[] xyz = new double[3 * n];
            for (int k = 0; k < n; k++) {
                xyz[3 * k] = 20 + rnd.nextInt(160);
                xyz[3 * k + 1] = 20 + rnd.nextInt(160);
            }
            try {
                b.addPolyline(xyz, 0, n, n >= 3 && rnd.nextInt(3) == 0, styles[rnd.nextInt(styles.length)]);
            } catch (IllegalArgumentException repeatedPoints) {
                // a polyline that collapsed to too few distinct points: skip it
            }
        }
        return b;
    }

    private static float[] orthographic() {
        float w = SIZE;
        return new float[] {2f / w, 0, 0, 0, 0, 2f / w, 0, 0, 0, 0, 1f, 0, -1f, -1f, 0f, 1f};
    }

    /** A perspective view from the origin looking down -z, 60 degrees: the matrix and the pixels per world unit at w = 1. */
    private static float[] perspective() {
        float f = (float) (1.0 / Math.tan(Math.toRadians(30))), n = 0.1f, far = 100f;
        return new float[] {f, 0, 0, 0, 0, f, 0, 0, 0, 0, (far + n) / (n - far), -1f, 0, 0, 2f * far * n / (n - far), 0f};
    }

    private LineBatch depthScene() {
        LineBatch b = new LineBatch();
        LineStyle thin = LineStyle.pixels(3f).withColor(0xFF0000FF), world = LineStyle.world(0.4f).withColor(0x00FF00FF).withCap(LineStyle.Cap.ROUND).withDash(1f, 0.5f);
        for (int i = 0; i < 25; i++) {
            int n = 2 + rnd.nextInt(4);
            double[] xyz = new double[3 * n];
            for (int k = 0; k < n; k++) {
                double z = -(6 + rnd.nextInt(30));
                xyz[3 * k] = (rnd.nextDouble() - 0.5) * 0.9 * -z;
                xyz[3 * k + 1] = (rnd.nextDouble() - 0.5) * 0.9 * -z;
                xyz[3 * k + 2] = z;
            }
            b.addPolyline(xyz, 0, n, n >= 3 && i % 3 == 0, i % 2 == 0 ? thin : world);
        }
        return b;
    }

    private static CoverageRaster fill(java.util.function.Consumer<LineGeometry.Sink> producer) {
        CoverageRaster r = new CoverageRaster(SIZE, SIZE);
        producer.accept((x0, y0, a0, x1, y1, a1, x2, y2, a2, dash, dashCount, color) -> r.fill(x0, y0, a0, x1, y1, a1, x2, y2, a2, dash, dashCount));
        return r;
    }

    private static MemorySegment buffer(long bytes) {
        return MemorySegment.ofArray(new byte[(int) Math.max(bytes, 16)]);
    }

    private static final LineStrategy[] TRIANGLE_STRATEGIES = {LineStrategy.INDIRECT_DRAW_ID, LineStrategy.INDIRECT_INSTANCE_STYLE, LineStrategy.EXPANDED_MULTIDRAW, LineStrategy.INSTANCED_LOOP};

    private void assertSamePixels(LineBatch batch, float[] vp, float worldToPixel, String what) {
        CoverageRaster expected = fill(sink -> LineExpander.expand(batch, vp, SIZE, SIZE, worldToPixel, sink));
        assertTrue(expected.area() > 50, what + ": the scene draws something");
        for (LineStrategy s : TRIANGLE_STRATEGIES) {
            LineRenderPlan plan = LineRenderPlan.force(s, GL46);
            MemorySegment data = buffer(plan.dataBytes(batch)), styles = buffer(plan.styleBytes(batch));
            DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 2);
            plan.write(batch, data, styles, draws);
            CoverageRaster got = fill(sink -> LineShaderModel.evaluate(plan, data, styles, draws, vp, SIZE, SIZE, worldToPixel, sink));
            assertEquals(0, expected.differences(got), what + " with " + s + ": samples that differ from the reference");
        }
    }

    // ---------------------------------------------------------------- equivalence

    @Test
    void everyStrategyDrawsWhatTheReferenceDrawsOnTheScreen() {
        for (int rep = 0; rep < 12; rep++) {
            assertSamePixels(screenScene(5 + rnd.nextInt(40), false), orthographic(), 1f, "screen scene " + rep);
        }
    }

    @Test
    void everyStrategyDrawsWhatTheReferenceDrawsInPerspectiveWithWorldWidths() {
        float[] vp = perspective();
        for (int rep = 0; rep < 8; rep++) {
            assertSamePixels(depthScene(), vp, 0.5f * SIZE * vp[5], "perspective scene " + rep);
        }
    }

    @Test
    void worldWidthsInAnOrthographicViewFollowTheScale() {
        for (int rep = 0; rep < 5; rep++) {
            assertSamePixels(screenScene(20, true), orthographic(), 1f, "world widths " + rep);
        }
    }

    @Test
    void aFarOriginDoesNotChangeThePixels() {
        double shift = 4_000_000.0;
        LineBatch near = new LineBatch(), far = new LineBatch();
        LineStyle s = LineStyle.pixels(6f).withCap(LineStyle.Cap.ROUND).withJoin(LineStyle.Join.ROUND).withDash(15f, 6f);
        double[] pts = {20, 30, 0, 150, 60, 0, 90, 170, 0, 30, 120, 0};
        double[] shifted = pts.clone();
        for (int i = 0; i < shifted.length; i += 3) {
            shifted[i] += shift;
            shifted[i + 1] += shift;
        }
        near.addPolyline(pts, 0, 4, false, s);
        near.addPolyline(pts, 0, 4, true, s.withColor(0xFF));
        far.addPolyline(shifted, 0, 4, false, s);
        far.addPolyline(shifted, 0, 4, true, s.withColor(0xFF));
        far.setOrigin(shift, shift, 0.0);
        double w = SIZE;
        double[] world = {2 / w, 0, 0, 0, 0, 2 / w, 0, 0, 0, 0, 1, 0, -1 - 2 * shift / w, -1 - 2 * shift / w, 0, 1};
        float[] relative = new float[16];
        far.relativeViewProjection(world, relative);
        CoverageRaster a = fill(sink -> LineExpander.expand(near, orthographic(), SIZE, SIZE, 1f, sink));
        CoverageRaster b = fill(sink -> LineExpander.expand(far, relative, SIZE, SIZE, 1f, sink));
        assertTrue(a.area() > 100);
        assertEquals(0, a.differences(b), "relative positions and the relative matrix reproduce the lines near the origin exactly");
        LineRenderPlan plan = LineRenderPlan.force(LineStrategy.INSTANCED_LOOP, GL33);
        MemorySegment data = buffer(plan.dataBytes(far)), styles = buffer(plan.styleBytes(far));
        DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 2);
        plan.write(far, data, styles, draws);
        CoverageRaster c = fill(sink -> LineShaderModel.evaluate(plan, data, styles, draws, relative, SIZE, SIZE, 1f, sink));
        assertEquals(0, a.differences(c));
    }

    // ---------------------------------------------------------------- the draws and buffers

    private static LineBatch runsBatch() {
        LineBatch b = new LineBatch();
        LineStyle a = LineStyle.pixels(2f), c = LineStyle.pixels(4f).withColor(0xFF), d = LineStyle.pixels(6f).withColor(0xFF00);
        double[] three = {10, 10, 0, 50, 10, 0, 50, 50, 0};  // two segments open, three closed
        b.addPolyline(three, 0, 3, false, a);       // 2 segments
        b.addPolyline(three, 0, 3, true, a);        // 3
        b.addPolyline(three, 0, 3, false, c);       // 2
        b.addPolyline(three, 0, 3, false, a);       // 2
        b.addPolyline(three, 0, 3, true, a);        // 3
        b.addPolyline(three, 0, 3, false, d);       // 2
        return b;
    }

    @Test
    void polylinesOfEqualStyleFormOneRun() {
        LineBatch b = runsBatch();
        assertEquals(14, b.segmentCount());
        int[] instances = {5, 2, 5, 2};
        int[] bases = {0, 5, 7, 12};
        for (LineStrategy s : new LineStrategy[] {LineStrategy.INDIRECT_DRAW_ID, LineStrategy.INDIRECT_INSTANCE_STYLE, LineStrategy.INSTANCED_LOOP}) {
            LineRenderPlan plan = LineRenderPlan.force(s, GL46);
            DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 1);
            assertEquals(4, plan.write(b, buffer(plan.dataBytes(b)), buffer(plan.styleBytes(b)), draws), s.toString());
            for (int i = 0; i < 4; i++) {
                assertEquals(LineGeometry.VERTICES_PER_SEGMENT, draws.count(i), s + " draw " + i);
                assertEquals(0, draws.first(i));
                assertEquals(instances[i], draws.instanceCount(i), s + " draw " + i);
                assertEquals(bases[i], draws.baseInstance(i), s + " draw " + i);
            }
            assertEquals(14L * LineGpu.SEGMENT_BYTES, plan.dataBytes(b));
            assertEquals(s == LineStrategy.INDIRECT_DRAW_ID ? 4 * LineGpu.STYLE_BYTES : 3 * LineGpu.STYLE_BYTES, plan.styleBytes(b), "the table of the draw index has one entry per draw");
            assertEquals(LineGpu.SEGMENT_BYTES, plan.segmentAccess().elementStride());
        }
    }

    @Test
    void theExpandedStrategyDrawsRangesOfVerticesWithoutInstancing() {
        LineBatch b = runsBatch();
        LineRenderPlan plan = LineRenderPlan.force(LineStrategy.EXPANDED_MULTIDRAW, GL33);
        DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 1);
        plan.write(b, buffer(plan.dataBytes(b)), buffer(plan.styleBytes(b)), draws);
        int v = LineGeometry.VERTICES_PER_SEGMENT;
        assertEquals(4, draws.size());
        int[] segs = {5, 2, 5, 2}, firsts = {0, 5, 7, 12};
        for (int i = 0; i < 4; i++) {
            assertEquals(segs[i] * v, draws.count(i));
            assertEquals(firsts[i] * v, draws.first(i));
            assertEquals(1, draws.instanceCount(i));
            assertEquals(0, draws.baseInstance(i));
        }
        assertFalse(draws.usesInstancing() || draws.usesBaseInstance());
        assertEquals(DrawSubmission.MULTI_DRAW_CLIENT, plan.submission(draws), "one glMultiDrawArrays without any indirect or instanced feature");
        assertEquals(DrawSubmission.MULTI_DRAW_INDIRECT, LineRenderPlan.force(LineStrategy.EXPANDED_MULTIDRAW, GL46).submission(draws));
        assertEquals(StructArrayAccess.Mode.TEXTURE_BUFFER, plan.segmentAccess().mode());
        assertEquals(StructArrayAccess.Mode.TEXTURE_BUFFER, plan.styleAccess().mode(), "a context without storage buffers reads the styles from a texture buffer too");
    }

    @Test
    void theInstancedStrategiesNeedTheMatchingSubmission() {
        LineBatch b = runsBatch();
        LineRenderPlan loop = LineRenderPlan.force(LineStrategy.INSTANCED_LOOP, GL33);
        DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 1);
        loop.write(b, buffer(loop.dataBytes(b)), buffer(loop.styleBytes(b)), draws);
        assertEquals(DrawSubmission.DRAW_LOOP, loop.submission(draws), "instances and a base instance, but no indirect draws");
        LineRenderPlan indirect = LineRenderPlan.force(LineStrategy.INDIRECT_INSTANCE_STYLE, GL43);
        draws = new DrawList(DrawList.Kind.ARRAYS, 1);
        indirect.write(b, buffer(indirect.dataBytes(b)), buffer(indirect.styleBytes(b)), draws);
        assertEquals(DrawSubmission.MULTI_DRAW_INDIRECT, indirect.submission(draws));
        assertEquals(StructArrayAccess.Mode.VERTEX_ATTRIBUTE, indirect.segmentAccess().mode());
        assertEquals(StructArrayAccess.Mode.STORAGE_BLOCK, indirect.styleAccess().mode(), "4.3 has storage blocks");
    }

    @Test
    void theUserValueOfADrawIsTheIndexOfItsStyle() {
        LineBatch b = runsBatch();
        LineRenderPlan plan = LineRenderPlan.force(LineStrategy.INSTANCED_LOOP, GL33);
        DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 1);
        plan.write(b, buffer(plan.dataBytes(b)), buffer(plan.styleBytes(b)), draws);
        for (int i = 0; i < draws.size(); i++) {
            assertTrue(b.style(draws.user(i)) != null);
        }
        assertEquals(b.styleIndexOf(0), draws.user(0));
        assertEquals(b.styleIndexOf(2), draws.user(1));
        assertEquals(b.styleIndexOf(3), draws.user(2));
        assertEquals(b.styleIndexOf(5), draws.user(3));
    }

    @Test
    void layersChangeTheOrderOfTheRuns() {
        LineBatch b = new LineBatch();
        double[] p = {10, 10, 0, 50, 10, 0};
        b.addPolyline(p, 0, 2, false, LineStyle.pixels(2f));
        b.addPolyline(p, 0, 2, false, LineStyle.pixels(3f).withLayer(-1));
        b.addPolyline(p, 0, 2, false, LineStyle.pixels(2f));
        LineRenderPlan plan = LineRenderPlan.force(LineStrategy.INSTANCED_LOOP, GL33);
        DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 1);
        plan.write(b, buffer(plan.dataBytes(b)), buffer(plan.styleBytes(b)), draws);
        assertEquals(2, draws.size(), "the layer -1 line first, then the two equal ones together");
        assertEquals(1, draws.instanceCount(0));
        assertEquals(2, draws.instanceCount(1));
        assertEquals(b.styleIndexOf(1), draws.user(0));
    }

    @Test
    void buffersAndListsThatDoNotFitAreRefused() {
        LineBatch b = runsBatch();
        LineRenderPlan plan = LineRenderPlan.force(LineStrategy.INSTANCED_LOOP, GL33);
        DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 1);
        assertThrows(IllegalArgumentException.class, () -> plan.write(b, buffer(plan.dataBytes(b) - 100), buffer(plan.styleBytes(b)), draws));
        assertThrows(IllegalArgumentException.class, () -> plan.write(b, buffer(plan.dataBytes(b)), buffer(plan.styleBytes(b) - 100), draws));
        assertThrows(IllegalArgumentException.class, () -> plan.write(b, buffer(plan.dataBytes(b)), null, draws));
        assertThrows(IllegalArgumentException.class, () -> plan.write(b, buffer(plan.dataBytes(b)), buffer(plan.styleBytes(b)), new DrawList(DrawList.Kind.ELEMENTS, 1)));
        assertThrows(IllegalStateException.class, plan::hairlineLayout);
    }

    @Test
    void aUniformBlockStyleTableHoldsAtMostItsLength() {
        GraphicsCapabilities onlyBlocks = GL33.without(Feature.TEXTURE_BUFFERS);
        LineRenderPlan plan = LineRenderPlan.force(LineStrategy.INSTANCED_LOOP, onlyBlocks);
        assertEquals(StructArrayAccess.Mode.UNIFORM_BLOCK, plan.styleAccess().mode());
        LineBatch tooMany = new LineBatch(), fits = new LineBatch();
        for (int i = 0; i < LineRenderPlan.STYLE_TABLE_UNIFORM_LENGTH + 1; i++) {
            if (i < LineRenderPlan.STYLE_TABLE_UNIFORM_LENGTH) {
                fits.addPolyline(new double[] {0, 0, 0, 1, 1, 0}, 0, 2, false, LineStyle.pixels(1f + i));
            }
            tooMany.addPolyline(new double[] {0, 0, 0, 1, 1, 0}, 0, 2, false, LineStyle.pixels(1f + i));
        }
        DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 1);
        assertThrows(IllegalArgumentException.class, () -> plan.write(tooMany, buffer(plan.dataBytes(tooMany)), buffer(plan.styleBytes(tooMany)), draws));
        assertTrue(plan.write(fits, buffer(plan.dataBytes(fits)), buffer(plan.styleBytes(fits)), draws) > 0);
    }

    // ---------------------------------------------------------------- hairlines

    @Test
    void hairlinesAreStripsWithTheColourOfTheStyle() {
        LineBatch b = new LineBatch();
        b.setOrigin(100, 200, 0);
        b.addPolyline(new double[] {110, 210, 0, 130, 210, 0, 130, 240, 0}, 0, 3, false, LineStyle.pixels(9f).withColor(0x11223344));
        b.addPolyline(new double[] {100, 200, 0, 120, 200, 0, 120, 220, 0}, 0, 3, true, LineStyle.pixels(1f).withColor(0xAABBCCDD));
        LineRenderPlan plan = LineRenderPlan.force(LineStrategy.HAIRLINE, GL33);
        assertEquals(LineRenderPlan.Primitive.LINE_STRIP, plan.primitive());
        assertNull(plan.segmentAccess());
        assertNull(plan.styleAccess());
        assertEquals(16, plan.hairlineLayout().stride());
        assertEquals(0, plan.styleBytes(b));
        assertEquals((3 + 4) * 16L, plan.dataBytes(b), "a closed polyline repeats its first point");
        MemorySegment data = buffer(plan.dataBytes(b));
        DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 1);
        assertEquals(2, plan.write(b, data, null, draws));
        assertEquals(3, draws.count(0));
        assertEquals(0, draws.first(0));
        assertEquals(4, draws.count(1));
        assertEquals(3, draws.first(1));
        List<String> lines = new ArrayList<>();
        LineShaderModel.evaluateHairlines(plan, data, draws, (x0, y0, z0, c0, x1, y1, z1, c1, draw) -> lines.add(draw + ":" + x0 + "," + y0 + ">" + x1 + "," + y1 + "@" + Integer.toHexString(c0)));
        assertEquals(List.of("0:10.0,10.0>30.0,10.0@11223344", "0:30.0,10.0>30.0,40.0@11223344", "1:0.0,0.0>20.0,0.0@aabbccdd", "1:20.0,0.0>20.0,20.0@aabbccdd", "1:20.0,20.0>0.0,0.0@aabbccdd"),
                lines);
        assertEquals(DrawSubmission.MULTI_DRAW_CLIENT, plan.submission(draws));
        assertThrows(IllegalArgumentException.class, () -> LineShaderModel.evaluate(plan, data, null, draws, orthographic(), 1f, 1f, 1f, (x0, y0, a0, x1, y1, a1, x2, y2, a2, d, dc, c) -> { }));
        assertThrows(IllegalArgumentException.class, () -> LineShaderModel.evaluateHairlines(LineRenderPlan.force(LineStrategy.INSTANCED_LOOP, GL33), data, draws, (a, b2, c, d, e, f, g, h, i) -> { }));
    }

    // ---------------------------------------------------------------- the shader text

    @Test
    void theShadersStartWithTheVersionOfTheContextAndFetchWhatTheStrategyReads() {
        LineRenderPlan loop = LineRenderPlan.force(LineStrategy.INSTANCED_LOOP, GL33);
        assertTrue(loop.vertexShader().startsWith("#version 330 core\n"), loop.vertexShader().substring(0, 40));
        assertTrue(loop.fragmentShader().startsWith("#version 330 core\n"));
        assertTrue(loop.vertexShader().contains("layout(location = 0) in vec3 segments_p0;"));
        assertTrue(loop.vertexShader().contains("LineSegment seg = fetch_segments(0);"));
        assertTrue(loop.vertexShader().contains("fetch_styles(int(seg.flagsAndStyle & 0xFFFFFFu))"));
        assertTrue(loop.vertexShader().contains("uniform usamplerBuffer styles_texels;"), "330 reads the styles from a texture buffer");
        assertTrue(loop.vertexShader().contains("const int VERTS_PER_SEGMENT = " + LineGeometry.VERTICES_PER_SEGMENT + ";"));
        assertFalse(loop.vertexShader().contains("binding"), "GLSL 3.30 has no explicit bindings");

        LineRenderPlan expanded = LineRenderPlan.force(LineStrategy.EXPANDED_MULTIDRAW, GL33);
        assertTrue(expanded.vertexShader().contains("uniform usamplerBuffer segments_texels;"));
        assertTrue(expanded.vertexShader().contains("fetch_segments(gl_VertexID / VERTS_PER_SEGMENT)"));
        assertFalse(expanded.vertexShader().contains("in vec3 segments_p0"));

        LineRenderPlan id46 = LineRenderPlan.force(LineStrategy.INDIRECT_DRAW_ID, GL46);
        assertTrue(id46.vertexShader().startsWith("#version 460 core\n"));
        assertTrue(id46.vertexShader().contains("fetch_styles(gl_DrawID)"));
        assertFalse(id46.vertexShader().contains("#extension"));
        assertTrue(id46.vertexShader().contains("layout(std430, binding = 1) readonly buffer styles_Block"));

        LineRenderPlan id45 = LineRenderPlan.force(LineStrategy.INDIRECT_DRAW_ID, GL45);
        assertTrue(id45.vertexShader().contains("#extension GL_ARB_shader_draw_parameters : require\n"));
        assertTrue(id45.vertexShader().contains("fetch_styles(gl_DrawIDARB)"));

        LineRenderPlan hair = LineRenderPlan.force(LineStrategy.HAIRLINE, GL33);
        assertTrue(hair.vertexShader().contains("layout(location = 0) in vec3 a_position;") && hair.vertexShader().contains("layout(location = 1) in uint a_color;"));
        assertTrue(hair.fragmentShader().contains("o_color = v_color;"));
        assertNotEquals(loop.vertexShader(), id46.vertexShader());
        for (LineRenderPlan p : new LineRenderPlan[] {loop, expanded, id46, id45}) {
            assertTrue(p.fragmentShader().contains("discard;") && p.fragmentShader().contains("v_dashCount"));
            assertTrue(p.describe().contains(p.strategy().toString()));
            assertTrue(p.vertexShader().contains(LineRenderPlan.U_VIEW_PROJECTION) && p.vertexShader().contains(LineRenderPlan.U_VIEWPORT) && p.vertexShader().contains(LineRenderPlan.U_WORLD_TO_PIXEL));
        }
        assertEquals(GlslVersion.V330, loop.capabilities().glsl());
    }
}
