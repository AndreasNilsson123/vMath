package vmath.lines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import vmath.gl.DrawCommandBuffer;
import vmath.gl.DrawList;
import vmath.gl.DrawSubmission;
import vmath.gl.GraphicsCapabilities;
import vmath.gl.GraphicsCapabilities.Feature;
import vmath.gl.StructArrayAccess;

/**
 * The whole phase over a matrix of capability profiles (LINE-8): for every profile and every
 * strategy, either the plan exists and its buffers, draws and commands are well formed and its
 * shader text asks for nothing the profile lacks, or the request throws and says what is missing.
 * The pixels of each strategy are compared with the reference in {@code LineRenderPlanTest} and
 * {@code LineSetTest}, and on a real driver by the samples module (LineGpuCheck).
 */
class LinePhaseTest {

    private static Map<String, GraphicsCapabilities> profiles() {
        Map<String, GraphicsCapabilities> p = new LinkedHashMap<>();
        for (int[] v : new int[][] {{3, 3}, {4, 0}, {4, 1}, {4, 2}, {4, 3}, {4, 4}, {4, 5}, {4, 6}}) {
            p.put("OpenGL " + v[0] + "." + v[1], GraphicsCapabilities.openGl(v[0], v[1], List.of()));
        }
        p.put("OpenGL 4.5 with draw parameters", GraphicsCapabilities.openGl(4, 5, List.of("GL_ARB_shader_draw_parameters")));
        p.put("OpenGL 3.3 without texture buffers", GraphicsCapabilities.baseline().without(Feature.TEXTURE_BUFFERS));
        p.put("OpenGL 3.3 without multi-draw and texture buffers", GraphicsCapabilities.baseline().without(Feature.MULTI_DRAW, Feature.TEXTURE_BUFFERS));
        p.put("OpenGL 4.6 without storage buffers", GraphicsCapabilities.openGl(4, 6, List.of()).without(Feature.STORAGE_BUFFERS));
        p.put("OpenGL 4.6 without instancing", GraphicsCapabilities.openGl(4, 6, List.of()).without(Feature.INSTANCED_DRAWS, Feature.INSTANCED_ARRAYS));
        p.put("Vulkan, none", GraphicsCapabilities.vulkan(false, false, false));
        p.put("Vulkan, multiDrawIndirect", GraphicsCapabilities.vulkan(true, false, false));
        p.put("Vulkan, all", GraphicsCapabilities.vulkan(true, true, true));
        return p;
    }

    private static LineBatch scene() {
        Random rnd = new Random(3);
        LineBatch b = new LineBatch();
        LineStyle[] styles = {LineStyle.pixels(2f), LineStyle.pixels(5f).withColor(0xFF0000FF).withDash(4f, 2f), LineStyle.world(3f).withLayer(2), LineStyle.pixels(1f).withLayer(-1).withColor(0x00FF00FF)};
        for (int i = 0; i < 40; i++) {
            int n = 2 + rnd.nextInt(5);
            double[] xyz = new double[3 * n];
            for (int k = 0; k < 3 * n; k++) {
                xyz[k] = rnd.nextInt(100);
            }
            try {
                b.addPolyline(xyz, 0, n, n > 2 && i % 4 == 0, styles[(i / 3) % styles.length]);
            } catch (IllegalArgumentException repeated) {
                // too few distinct points
            }
        }
        return b;
    }

    private static MemorySegment buffer(long bytes) {
        return MemorySegment.ofArray(new byte[(int) Math.max(bytes, 16)]);
    }

    @Test
    void everyRequestEitherSucceedsWellFormedOrThrowsWithTheMissingFeature() {
        LineBatch batch = scene();
        int supportedCases = 0, refusedCases = 0;
        for (Map.Entry<String, GraphicsCapabilities> e : profiles().entrySet()) {
            GraphicsCapabilities caps = e.getValue();
            // the choice itself always succeeds: the hairline strategy needs nothing
            LineRenderPlan best = LineRenderPlan.choose(caps);
            assertNotNull(best, e.getKey());
            assertTrue(LineStrategy.chooser().supports(best.strategy(), caps), e.getKey() + ": the chosen strategy is supported");
            for (LineStrategy strategy : LineStrategy.values()) {
                String what = e.getKey() + " / " + strategy;
                if (!LineStrategy.chooser().supports(strategy, caps)) {
                    UnsupportedOperationException refused = assertThrows(UnsupportedOperationException.class, () -> LineRenderPlan.force(strategy, caps), what);
                    assertTrue(refused.getMessage().contains(strategy.name()) || refused.getMessage().length() > 20, what + ": the message says why: " + refused.getMessage());
                    refusedCases++;
                    continue;
                }
                supportedCases++;
                LineRenderPlan plan = LineRenderPlan.force(strategy, caps);
                assertEquals(strategy, plan.strategy());
                assertShaderText(plan, caps, what);
                assertWellFormed(plan, caps, batch, what);
            }
        }
        assertTrue(supportedCases > 40 && refusedCases > 5, "the matrix covers both outcomes: " + supportedCases + " supported, " + refusedCases + " refused");
    }

    private static void assertShaderText(LineRenderPlan plan, GraphicsCapabilities caps, String what) {
        String vs = plan.vertexShader(), fs = plan.fragmentShader();
        String version = caps.glsl().versionLine();
        assertTrue(vs.startsWith(version + "\n") && fs.startsWith(version + "\n"), what + ": both stages start with " + version);
        assertEquals(1, vs.split("#version", -1).length - 1, what + ": one version line");
        String code = vs.replaceAll("//[^\n]*", "");   // the comments describe the layouts, whatever the version
        if (caps.glsl().number() < 420) {
            assertFalse(code.contains("binding ="), what + ": no explicit bindings before 4.20");
        }
        if (caps.glsl().number() < 430) {
            assertFalse(code.contains("std430") || code.contains("buffer "), what + ": no storage blocks before 4.30");
        }
        if (plan.strategy() != LineStrategy.INDIRECT_DRAW_ID) {
            assertFalse(vs.contains("gl_DrawID"), what + ": only the draw-id strategy reads the draw index");
        } else if (caps.api() == GraphicsCapabilities.Api.VULKAN) {
            assertTrue(vs.contains("gl_DrawID"), what + ": the text is the OpenGL form; the Vulkan form is GPU-13");
        } else if (caps.glsl().number() < 460) {
            assertTrue(vs.contains("#extension GL_ARB_shader_draw_parameters : require") && vs.contains("gl_DrawIDARB"), what + ": the extension form of the draw index");
        } else {
            assertTrue(vs.contains("gl_DrawID") && !vs.contains("gl_DrawIDARB"), what + ": the core form");
        }
        if (plan.strategy() == LineStrategy.EXPANDED_MULTIDRAW) {
            assertTrue(vs.contains("gl_VertexID / VERTS_PER_SEGMENT"), what + ": vertex pulling");
        }
        assertTrue(fs.contains("o_color"), what);
    }

    private static void assertWellFormed(LineRenderPlan plan, GraphicsCapabilities caps, LineBatch batch, String what) {
        MemorySegment data = buffer(plan.dataBytes(batch)), styles = buffer(plan.styleBytes(batch));
        DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 4);
        int n = plan.write(batch, data, styles, draws);
        assertEquals(n, draws.size(), what);
        assertTrue(n > 0, what);
        DrawSubmission how = plan.submission(draws);
        assertEquals(DrawSubmission.choose(caps, draws), how, what);
        long units = plan.strategy() == LineStrategy.HAIRLINE ? plan.dataBytes(batch) / LineGpu.HAIRLINE_VERTEX_BYTES : batch.segmentCount();
        long covered = 0;
        for (int i = 0; i < draws.size(); i++) {
            assertTrue(draws.user(i) >= 0 && draws.user(i) < batch.styleCount(), what + ": the user value is a style index");
            switch (plan.strategy()) {
                case EXPANDED_MULTIDRAW -> {
                    assertEquals(1, draws.instanceCount(i), what);
                    assertEquals(0, draws.count(i) % LineGeometry.VERTICES_PER_SEGMENT, what + ": whole segments");
                    assertEquals(0, draws.first(i) % LineGeometry.VERTICES_PER_SEGMENT, what);
                    assertTrue((draws.first(i) + draws.count(i)) / LineGeometry.VERTICES_PER_SEGMENT <= units, what + ": inside the data");
                    covered += draws.count(i) / LineGeometry.VERTICES_PER_SEGMENT;
                }
                case HAIRLINE -> {
                    assertEquals(1, draws.instanceCount(i), what);
                    assertTrue(draws.count(i) >= 2 && draws.first(i) + draws.count(i) <= units, what + ": a strip inside the data");
                    covered += draws.count(i);
                }
                default -> {
                    assertEquals(LineGeometry.VERTICES_PER_SEGMENT, draws.count(i), what);
                    assertEquals(0, draws.first(i), what);
                    assertTrue(draws.instanceCount(i) > 0 && draws.baseInstance(i) + draws.instanceCount(i) <= units, what + ": instances inside the data");
                    covered += draws.instanceCount(i);
                }
            }
        }
        assertEquals(units, covered, what + ": every segment (or vertex) is drawn once");
        if (how == DrawSubmission.MULTI_DRAW_INDIRECT) {
            MemorySegment commands = buffer(draws.size() * DrawCommandBuffer.Kind.ARRAYS.bytes());
            assertEquals(draws.size(), draws.writeIndirect(new DrawCommandBuffer(commands, DrawCommandBuffer.Kind.ARRAYS, false)), what);
        } else if (how == DrawSubmission.MULTI_DRAW_CLIENT) {
            int[] counts = new int[draws.size()], firsts = new int[draws.size()];
            assertEquals(draws.size(), draws.copyCounts(counts), what);
            assertEquals(draws.size(), draws.copyFirsts(firsts), what);
        } else {
            assertEquals(DrawSubmission.DRAW_LOOP, how, what);
        }
        if (plan.strategy() != LineStrategy.HAIRLINE) {
            assertEquals(StructArrayAccess.Mode.TEXTURE_BUFFER == plan.segmentAccess().mode(), plan.strategy() == LineStrategy.EXPANDED_MULTIDRAW, what + ": only vertex pulling reads the segments from a texture");
            assertTrue(plan.describe().contains(plan.strategy().name()), what);
        }
    }

    @Test
    void aSetOverEveryProfileKeepsTheLimitsOfItsStyleTable() {
        GraphicsCapabilities uniformOnly = GraphicsCapabilities.baseline().without(Feature.TEXTURE_BUFFERS, Feature.STORAGE_BUFFERS);
        LineRenderPlan plan = LineRenderPlan.choose(uniformOnly);
        assertEquals(LineStrategy.INSTANCED_LOOP, plan.strategy());
        assertEquals(StructArrayAccess.Mode.UNIFORM_BLOCK, plan.styleAccess().mode());
        LineSet set = new LineSet(plan, 2000);
        for (int i = 0; i < LineRenderPlan.STYLE_TABLE_UNIFORM_LENGTH + 1; i++) {
            set.add(new double[] {0, 0, 0, 10 + i, 5, 0}, 0, 2, false, LineStyle.pixels(1f + i * 0.01f));
        }
        MemorySegment data = buffer(set.dataBytes()), styles = buffer(set.styleBytes());
        DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 8);
        assertThrows(IllegalArgumentException.class, () -> set.update(data, styles, draws), "257 styles do not fit a uniform block");
        LineRenderPlan roomy = LineRenderPlan.choose(GraphicsCapabilities.openGl(4, 6, List.of()));
        LineSet big = new LineSet(roomy, 2000);
        for (int i = 0; i < LineRenderPlan.STYLE_TABLE_UNIFORM_LENGTH + 1; i++) {
            big.add(new double[] {0, 0, 0, 10 + i, 5, 0}, 0, 2, false, LineStyle.pixels(1f + i * 0.01f));
        }
        assertTrue(big.update(buffer(big.dataBytes()), buffer(big.styleBytes()), new DrawList(DrawList.Kind.ARRAYS, 8)) > 0, "a storage block holds them");
    }

    @Test
    void theBuffersAreTooSmallIsReportedNotWrittenOutside() {
        LineBatch batch = scene();
        LineRenderPlan plan = LineRenderPlan.force(LineStrategy.INSTANCED_LOOP, GraphicsCapabilities.openGl(4, 6, List.of()));
        DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 4);
        assertThrows(IllegalArgumentException.class, () -> plan.write(batch, buffer(plan.dataBytes(batch) - 48), buffer(plan.styleBytes(batch)), draws));
        assertThrows(IllegalArgumentException.class, () -> plan.write(batch, buffer(plan.dataBytes(batch)), buffer(plan.styleBytes(batch) - 64), draws));
        assertThrows(IllegalArgumentException.class, () -> plan.write(batch, buffer(plan.dataBytes(batch)), buffer(plan.styleBytes(batch)), new DrawList(DrawList.Kind.ELEMENTS, 4)));
        LineSet set = new LineSet(plan, 100);
        set.add(new double[] {0, 0, 0, 5, 5, 0}, 0, 2, false, LineStyle.pixels(2f));
        assertThrows(IllegalArgumentException.class, () -> set.update(buffer(set.dataBytes() - 1), buffer(set.styleBytes()), draws));
        assertThrows(IllegalArgumentException.class, () -> set.update(buffer(set.dataBytes()), buffer(set.styleBytes()), new DrawList(DrawList.Kind.ELEMENTS, 4)));
    }
}
