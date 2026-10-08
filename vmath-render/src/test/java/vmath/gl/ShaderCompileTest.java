package vmath.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.camera.DualParaboloid;
import vmath.camera.OrthoCameraf;
import vmath.core.ClipSpace;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;
import vmath.gpucull.GpuCullGlsl;
import vmath.lighting.ClusterGrid;
import vmath.map.AreaRenderPlan;
import vmath.map.AreaStrategy;
import vmath.map.SymbolRenderPlan;
import vmath.map.SymbolStrategy;
import vmath.map.TerrainShader;

/**
 * The compile matrix (roadmap item GPU-12): every GLSL text the library generates is compiled with a
 * real front end ({@code glslang}, see {@link GlslCompiler}) at every desktop version from 3.30, in
 * both directions. The text that a generator writes for a version must be accepted by the compiler at that
 * version, and a version that the table of {@link GlslFeature} says lacks a construct must make the
 * generator throw before it writes anything; the table itself is checked in {@code GlslFeatureCompileTest}
 * and the lower bound of the culling shaders is confirmed here by showing that the compiler refuses their
 * text below it. The tests are <em>skipped</em>, not passed, when no compiler is installed, so they prove
 * nothing on a machine without one; with {@code -Dvmath.requireEnvironment=true} (the Linux CI job, which installs
 * glslang) a missing compiler fails them. They check that the text compiles, not that it computes the right
 * thing (the shaders are run on a real driver by the checks of the samples).
 */
class ShaderCompileTest {

    private static final List<GlslVersion> ALL = List.of(GlslVersion.V330, GlslVersion.V400, GlslVersion.V410, GlslVersion.V420, GlslVersion.V430, GlslVersion.V440, GlslVersion.V450,
            GlslVersion.V460);

    private static void compile(String name, String stage, String source) {
        GlslCompiler.compile(name, stage, source);
    }

    private static GraphicsCapabilities capsOf(GlslVersion v) {
        return GraphicsCapabilities.openGl(v.number() >= 400 ? 4 : 3, v.number() >= 400 ? (v.number() - 400) / 10 : 3, List.of());
    }

    // ---------------------------------------------------------------- the culling shaders (GPU-10)

    @Test
    void theCullingShadersCompileAtEveryVersionFromFourThirty() {
        GlslCompiler.require();
        for (GlslVersion v : ALL) {
            for (int group : new int[] {32, 64, 128, 256}) {
                if (v.supports(GlslFeature.COMPUTE_SHADER)) {
                    compile("cull" + group + "-" + v.number(), "comp", GpuCullGlsl.computeShader(group, v));
                    compile("cluster" + group + "-" + v.number(), "comp", GpuCullGlsl.clusterShader(group, v));
                } else {
                    UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class, () -> GpuCullGlsl.computeShader(group, v));
                    assertTrue(e.getMessage().contains("compute shader") && e.getMessage().contains("GLSL 4.30") && e.getMessage().contains("CPU"), e.getMessage());
                    assertThrows(UnsupportedOperationException.class, () -> GpuCullGlsl.clusterShader(group, v));
                }
            }
        }
    }

    @Test
    void theLowestVersionOfTheCullingShadersIsTheLowestTheCompilerAccepts() {
        GlslCompiler.require();
        // the default text is the text for 4.50; written for 4.20 or below the compiler must refuse it, which is why the library refuses to write it
        for (int number : new int[] {330, 400, 410, 420}) {
            String object = "#version " + number + GpuCullGlsl.computeShader(64).substring("#version 450".length());
            String cluster = "#version " + number + GpuCullGlsl.clusterShader(64).substring("#version 450".length());
            assertFalse(GlslCompiler.accepts("comp", object), "the object shader at " + number);
            assertFalse(GlslCompiler.accepts("comp", cluster), "the cluster shader at " + number);
        }
        assertTrue(GlslCompiler.accepts("comp", GpuCullGlsl.computeShader(64, GlslVersion.V430)));
    }

    @Test
    void theTextForAVersionIsTheDefaultTextWithItsFirstLineChanged() {
        assertEquals(GpuCullGlsl.computeShader(64), GpuCullGlsl.computeShader(64, GpuCullGlsl.DEFAULT_VERSION));
        assertEquals(GpuCullGlsl.clusterShader(128), GpuCullGlsl.clusterShader(128, GpuCullGlsl.DEFAULT_VERSION));
        String at430 = GpuCullGlsl.computeShader(64, GlslVersion.V430);
        assertTrue(at430.startsWith("#version 430\nlayout(local_size_x = 64) in;"), at430.substring(0, 60));
        assertEquals(GpuCullGlsl.computeShader(64).substring("#version 450".length()), at430.substring("#version 430".length()));
    }

    // ---------------------------------------------------------------- the version-neutral generators (GPU-11)

    @Test
    void clusterLookupCompilesAtEveryVersion() {
        GlslCompiler.require();
        for (GlslVersion v : ALL) {
            for (boolean yDown : new boolean[] {false, true}) {
                ClusterGrid g = ClusterGrid.of(1f, 16f / 9f, 0.1f, 200f, 1920, 1080, 64, 24, yDown);
                compile("clusterlookup-" + v.number(), "frag", v.versionLine() + "\nin vec3 viewPos;\nout vec4 color;\n" + g.glslLookup()
                        + "void main() { color = vec4(float(clusterIndex(gl_FragCoord.xy, -viewPos.z))); }\n");
            }
        }
    }

    @Test
    void orthographicClusterLookupCompilesAtEveryVersion() {
        GlslCompiler.require();
        OrthoCameraf camera = OrthoCameraf.forViewport(new Vec3f(320f, 240f, 10f), 640, 480, 1f, 0.1f, 100f, DepthRange.of(ClipSpace.OPENGL));
        for (GlslVersion v : ALL) {
            ClusterGrid g = ClusterGrid.of(camera, 640, 480, 32, 16, 100f, false);
            compile("clusterlookup-ortho-" + v.number(), "frag", v.versionLine() + "\nin vec3 viewPos;\nout vec4 color;\n" + g.glslLookup()
                    + "void main() { color = vec4(float(clusterIndex(gl_FragCoord.xy, -viewPos.z))); }\n");
        }
    }

    @Test
    void dualParaboloidProjectionCompilesAtEveryVersion() {
        GlslCompiler.require();
        for (GlslVersion v : ALL) {
            compile("paraboloid-" + v.number(), "vert", v.versionLine() + "\nlayout(location = 0) in vec3 position;\nout float gl_ClipDistance[1];\n" + DualParaboloid.glsl()
                    + "void main() { float side; gl_Position = dualParaboloid(position, 0.1, 100.0, side); gl_ClipDistance[0] = side; }\n");
        }
    }

    @Test
    void vertexInputsCompileAtEveryVersion() {
        GlslCompiler.require();
        VertexBufferLayout layout = VertexBufferLayout.builder().attributeAt("a_position", 0, VertexFormat.FLOAT32X3, 0).attributeAt("a_color", 1, VertexFormat.UINT32, 12)
                .attribute("a_uv", 2, VertexFormat.FLOAT32X2).attribute("a_id", 3, VertexFormat.SINT32).attribute("a_params", 4, VertexFormat.FLOAT32X4).build();
        for (GlslVersion v : ALL) {
            compile("inputs-" + v.number(), "vert", v.versionLine() + "\n" + layout.glslInputs()
                    + "void main() { gl_Position = vec4(a_position, 1.0) + vec4(a_uv, float(a_color), float(a_id)) + a_params; }\n");
        }
    }

    // ---------------------------------------------------------------- ShaderHeader and the access modes (GPU-9)

    private static final GlslType.Struct STYLE = new GlslType.Struct("Style", List.of(new GlslType.Member("color", GlslType.VEC4), new GlslType.Member("width", GlslType.FLOAT),
            new GlslType.Member("flags", GlslType.UINT), new GlslType.Member("offset", GlslType.IVEC2)));

    @Test
    void everyAccessModeOfAStructArrayCompilesAtEveryVersionItClaims() {
        GlslCompiler.require();
        for (GlslVersion v : ALL) {
            GraphicsCapabilities caps = capsOf(v);
            for (StructArrayAccess.Mode mode : StructArrayAccess.Mode.values()) {
                if (!caps.has(mode.requires())) {
                    continue;
                }
                StructArrayAccess a = StructArrayAccess.of(STYLE, mode, "styles", 64, mode == StructArrayAccess.Mode.VERTEX_ATTRIBUTE ? 4 : 1, caps);
                String header = ShaderHeader.builder("STYLES").version(v).access(a).build(ShaderHeader.Language.GLSL);
                String source = v.versionLine() + "\n" + header + "layout(location = 0) in vec3 position;\n"
                        + "void main() {\n    Style s = " + a.fetchFunction() + "(gl_VertexID);\n"
                        + "    gl_Position = vec4(position * s.width + s.color.xyz + float(s.flags) + float(s.offset.x), 1.0);\n}\n";
                compile("access-" + mode + "-" + v.number(), "vert", source);
            }
        }
    }

    @Test
    void aHeaderWrittenForAnOlderVersionFromAnAccessOfANewerOneStillCompilesWithTheBindingsLeftOut() {
        GlslCompiler.require();
        GraphicsCapabilities gl46 = GraphicsCapabilities.openGl(4, 6, List.of());
        for (GlslVersion v : List.of(GlslVersion.V330, GlslVersion.V400, GlslVersion.V410)) {
            ShaderHeader h = ShaderHeader.builder("H").version(v).access(StructArrayAccess.of(STYLE, StructArrayAccess.Mode.UNIFORM_BLOCK, "table", 16, 1, gl46))
                    .access(StructArrayAccess.of(STYLE, StructArrayAccess.Mode.TEXTURE_BUFFER, "texels", 0, 2, gl46)).build();
            String source = v.versionLine() + "\n" + h.emit(ShaderHeader.Language.GLSL) + "void main() {\n    Style s = fetch_table(0);\n    Style t = fetch_texels(gl_VertexID);\n"
                    + "    gl_Position = s.color + t.color;\n}\n";
            compile("no-binding-" + v.number(), "vert", source);
            assertEquals(2, h.bindings().size());
            // and the text with the bindings left in is what the compiler refuses there: that is why they are left out
            String withBinding = v.versionLine() + "\n" + ShaderHeader.builder("H").access(StructArrayAccess.of(STYLE, StructArrayAccess.Mode.UNIFORM_BLOCK, "table", 16, 1, gl46)).build(ShaderHeader.Language.GLSL)
                    + "void main() {\n    gl_Position = fetch_table(0).color;\n}\n";
            assertFalse(GlslCompiler.accepts("vert", withBinding), "binding points at " + v);
        }
    }

    // ---------------------------------------------------------------- GLSL ES (GPU-14)

    @Test
    void anEsHeaderCompilesInAVertexAndAFragmentShaderAtEveryEsVersionItClaims() {
        GlslCompiler.require();
        GraphicsCapabilities gl46 = GraphicsCapabilities.openGl(4, 6, List.of());
        for (GlslEsVersion v : List.of(GlslEsVersion.V300, GlslEsVersion.V310, GlslEsVersion.V320)) {
            for (StructArrayAccess.Mode mode : StructArrayAccess.Mode.values()) {
                boolean allowed = switch (mode) {
                    case STORAGE_BLOCK -> v.supports(GlslFeature.STORAGE_BLOCK);
                    case TEXTURE_BUFFER -> v.supports(GlslFeature.TEXTURE_BUFFER);
                    default -> true;
                };
                ShaderHeader.Builder b = ShaderHeader.builder("ES").esVersion(v).withVersionLine().constant("N", 3).access(StructArrayAccess.of(STYLE, mode, "styles", 16, 1, gl46));
                if (!allowed) {
                    ShaderHeader h = b.build();
                    UnsupportedOperationException e = org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, () -> h.emit(ShaderHeader.Language.GLSL), mode + " at " + v);
                    assertTrue(e.getMessage().contains(v.toString()), e.getMessage());
                    continue;
                }
                String header = b.build(ShaderHeader.Language.GLSL);
                String fetch = mode == StructArrayAccess.Mode.VERTEX_ATTRIBUTE ? "fetch_styles(0)" : "fetch_styles(gl_VertexID & 15)";
                compile("es-" + mode + "-" + v.number(), "vert", header + "void main() {\n    Style s = " + fetch + ";\n    gl_Position = s.color + vec4(float(N));\n}\n");
                if (mode != StructArrayAccess.Mode.VERTEX_ATTRIBUTE) {
                    compile("es-" + mode + "-frag-" + v.number(), "frag", header + "out vec4 c;\nvoid main() {\n    Style s = fetch_styles(int(gl_FragCoord.x) & 15);\n    c = s.color + vec4(float(N));\n}\n");
                }
            }
        }
    }

    // ---------------------------------------------------------------- Vulkan GLSL, compiled to SPIR-V (GPU-13)

    @Test
    void aVulkanHeaderCompilesToSpirv() {
        GlslCompiler.require();
        GraphicsCapabilities vk = GraphicsCapabilities.vulkan(true, true, true);
        GlslType.Struct push = new GlslType.Struct("Push", List.of(new GlslType.Member("scale", GlslType.FLOAT), new GlslType.Member("offset", GlslType.VEC3)));
        GlslType.Struct params = new GlslType.Struct("Params", List.of(new GlslType.Member("tint", GlslType.VEC4)));
        String header = ShaderHeader.builder("VK").target(ShaderHeader.Target.VULKAN).version(GlslVersion.V450).withVersionLine().constant("N", 4)
                .block(params, GpuLayout.STD140, "uniform", "params", 0, 0).pushConstants(push, "pc")
                .access(StructArrayAccess.of(STYLE, StructArrayAccess.Mode.STORAGE_BLOCK, "storage", 0, 0, 1, vk))
                .access(StructArrayAccess.of(STYLE, StructArrayAccess.Mode.UNIFORM_BLOCK, "table", 16, 1, 0, vk))
                .access(StructArrayAccess.of(STYLE, StructArrayAccess.Mode.TEXTURE_BUFFER, "texels", 0, 1, 1, vk)).build(ShaderHeader.Language.GLSL);
        String source = header + "layout(location = 0) in vec3 position;\nvoid main() {\n    Style a = fetch_storage(gl_VertexIndex);\n    Style b = fetch_table(gl_VertexIndex & 15);\n"
                + "    Style c = fetch_texels(gl_VertexIndex);\n    gl_Position = vec4(position * pc.scale + pc.offset, 1.0) + params.tint + a.color + b.color + c.color;\n}\n";
        String problem = GlslCompiler.checkSpirv("vert", source);
        assertEquals(null, problem, problem);
        // an attribute access
        String attribute = ShaderHeader.builder("VK").target(ShaderHeader.Target.VULKAN).version(GlslVersion.V450).withVersionLine()
                .access(StructArrayAccess.of(STYLE, StructArrayAccess.Mode.VERTEX_ATTRIBUTE, "inst", 0, 0, 2, vk)).build(ShaderHeader.Language.GLSL)
                + "void main() {\n    Style s = fetch_inst(0);\n    gl_Position = s.color;\n}\n";
        assertEquals(null, GlslCompiler.checkSpirv("vert", attribute));
    }

    // ---------------------------------------------------------------- the line shaders

    @Test
    void theLineShadersOfEveryStrategyCompileAtEveryVersionItClaims() {
        GlslCompiler.require();
        for (GlslVersion v : ALL) {
            for (List<String> extensions : List.of(List.<String>of(), List.of("GL_ARB_shader_draw_parameters"))) {
                lineShaders(v, GraphicsCapabilities.openGl(v.number() >= 400 ? 4 : 3, v.number() >= 400 ? (v.number() - 400) / 10 : 3, extensions));
            }
        }
    }

    private static void lineShaders(GlslVersion v, GraphicsCapabilities caps) {
        for (vmath.lines.LineStrategy strategy : vmath.lines.LineStrategy.values()) {
            if (!vmath.lines.LineStrategy.chooser().supports(strategy, caps)) {
                continue;
            }
            vmath.lines.LineRenderPlan plan = vmath.lines.LineRenderPlan.force(strategy, caps);
            compile("line-" + strategy + "-" + v.number(), "vert", plan.vertexShader());
            compile("line-" + strategy + "-" + v.number(), "frag", plan.fragmentShader());
        }
    }

    // ---------------------------------------------------------------- the map shaders

    @Test
    void theMapShadersCompileAtEveryVersionItClaims() {
        GlslCompiler.require();
        for (GlslVersion v : ALL) {
            GraphicsCapabilities caps = capsOf(v);
            for (SymbolStrategy s : SymbolStrategy.values()) {
                if (SymbolStrategy.chooser().supports(s, caps)) {
                    SymbolRenderPlan plan = SymbolRenderPlan.force(s, caps);
                    compile("symbol-" + s + "-" + v.number(), "vert", plan.vertexShader());
                    compile("symbol-" + s + "-" + v.number(), "frag", plan.fragmentShader());
                }
            }
            for (AreaStrategy s : AreaStrategy.values()) {
                if (AreaStrategy.chooser().supports(s, caps)) {
                    AreaRenderPlan plan = AreaRenderPlan.force(s, caps);
                    compile("area-" + s + "-" + v.number(), "vert", plan.vertexShader());
                    compile("area-" + s + "-" + v.number(), "frag", plan.fragmentShader());
                }
            }
            compile("terrain-" + v.number(), "vert", TerrainShader.vertexSource(caps));
            compile("terrain-" + v.number(), "frag", TerrainShader.fragmentSource(caps));
        }
    }
}
