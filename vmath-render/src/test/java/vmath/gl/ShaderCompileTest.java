package vmath.gl;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import vmath.lighting.ClusterGrid;
import vmath.camera.DualParaboloid;
import vmath.gpucull.GpuCullGlsl;

/**
 * Compiles every GLSL text the library generates with a real front end ({@code glslangValidator}, or {@code glslc} from the Vulkan SDK), when one is installed:
 * a shader that does not compile is a bug that no comparison with the Java reference can find. The test is <em>skipped</em>, not passed, when no compiler is on the
 * {@code PATH} (or named by {@code -Dvmath.glslang=...}), so it proves nothing on a machine without one; CI installs glslang and the test then runs. It checks that the
 * text compiles, not that it computes the right thing.
 */
class ShaderCompileTest {

    private static final String PROPERTY = "vmath.glslang";

    /** The compiler command and the flags that select the stage, or null when none is installed. */
    private static List<String> compiler() {
        String configured = System.getProperty(PROPERTY);
        for (String candidate : configured != null ? List.of(configured) : List.of("glslangValidator", "glslc")) {
            try {
                Process p = new ProcessBuilder(candidate, "--version").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor(30, TimeUnit.SECONDS)) {
                    return List.of(candidate);
                }
            } catch (IOException | InterruptedException notThere) {
                // try the next one
            }
        }
        return null;
    }

    private static void compile(String name, String stage, String source) throws Exception {
        List<String> tool = compiler();
        assumeTrue(tool != null, "no glslangValidator or glslc on the PATH (or -D" + PROPERTY + ")");
        Path file = Files.createTempFile("vmath-" + name, "." + stage);
        try {
            Files.writeString(file, source, StandardCharsets.UTF_8);
            List<String> cmd = new java.util.ArrayList<>(tool);
            if (tool.get(0).contains("glslc")) {
                cmd.addAll(List.of("-fshader-stage=" + stage, "-o", file + ".spv", file.toString()));
            } else {
                cmd.addAll(List.of("-S", stage, file.toString()));
            }
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
                fail(name + " does not compile:\n" + output);
            }
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(Path.of(file + ".spv"));
        }
    }

    @Test
    void objectCullingComputeShaderCompiles() throws Exception {
        for (int group : new int[] {32, 64, 128, 256}) {
            compile("cull" + group, "comp", GpuCullGlsl.computeShader(group));
        }
    }

    @Test
    void clusterCullingComputeShaderCompiles() throws Exception {
        for (int group : new int[] {32, 64, 256}) {
            compile("cluster" + group, "comp", GpuCullGlsl.clusterShader(group));
        }
    }

    @Test
    void clusterLookupCompiles() throws Exception {
        for (boolean yDown : new boolean[] {false, true}) {
            ClusterGrid g = ClusterGrid.of(1f, 16f / 9f, 0.1f, 200f, 1920, 1080, 64, 24, yDown);
            compile("clusterlookup", "frag", "#version 450\nlayout(location = 0) in vec3 viewPos;\nlayout(location = 0) out vec4 color;\n" + g.glslLookup()
                    + "void main() { color = vec4(float(clusterIndex(gl_FragCoord.xy, -viewPos.z))); }\n");
        }
    }

    @Test
    void dualParaboloidProjectionCompiles() throws Exception {
        compile("paraboloid", "vert", "#version 450\nlayout(location = 0) in vec3 position;\nout float gl_ClipDistance[1];\n" + DualParaboloid.glsl()
                + "void main() { float side; gl_Position = dualParaboloid(position, 0.1, 100.0, side); gl_ClipDistance[0] = side; }\n");
    }

    @Test
    void everyAccessModeOfAStructArrayCompilesAtEveryVersionItClaims() throws Exception {
        GlslType.Struct style = new GlslType.Struct("Style", List.of(new GlslType.Member("color", GlslType.VEC4), new GlslType.Member("width", GlslType.FLOAT),
                new GlslType.Member("flags", GlslType.UINT), new GlslType.Member("offset", GlslType.IVEC2)));
        int[][] versions = {{3, 3}, {4, 0}, {4, 1}, {4, 2}, {4, 3}, {4, 4}, {4, 5}, {4, 6}};
        for (int[] v : versions) {
            GraphicsCapabilities caps = GraphicsCapabilities.openGl(v[0], v[1], List.of());
            for (StructArrayAccess.Mode mode : StructArrayAccess.Mode.values()) {
                if (!caps.has(mode.requires())) {
                    continue;
                }
                StructArrayAccess a = StructArrayAccess.of(style, mode, "styles", 64, mode == StructArrayAccess.Mode.VERTEX_ATTRIBUTE ? 4 : 1, caps);
                String header = ShaderHeader.builder("STYLES").access(a).build(ShaderHeader.Language.GLSL);
                String source = caps.glsl().versionLine() + "\n" + header + "layout(location = 0) in vec3 position;\n"
                        + "void main() {\n    Style s = " + a.fetchFunction() + "(gl_VertexID);\n"
                        + "    gl_Position = vec4(position * s.width + s.color.xyz + float(s.flags) + float(s.offset.x), 1.0);\n}\n";
                compile("access-" + mode + "-" + caps.glsl().number(), "vert", source);
            }
        }
    }

    @Test
    void theLineShadersOfEveryStrategyCompileAtEveryVersionItClaims() throws Exception {
        int[][] versions = {{3, 3}, {4, 0}, {4, 1}, {4, 2}, {4, 3}, {4, 4}, {4, 5}, {4, 6}};
        for (int[] v : versions) {
            GraphicsCapabilities caps = GraphicsCapabilities.openGl(v[0], v[1], List.of("GL_ARB_shader_draw_parameters"));
            for (vmath.lines.LineStrategy strategy : vmath.lines.LineStrategy.values()) {
                if (!vmath.lines.LineStrategy.chooser().supports(strategy, caps)) {
                    continue;
                }
                vmath.lines.LineRenderPlan plan = vmath.lines.LineRenderPlan.force(strategy, caps);
                compile("line-" + strategy + "-" + caps.glsl().number(), "vert", plan.vertexShader());
                compile("line-" + strategy + "-" + caps.glsl().number(), "frag", plan.fragmentShader());
            }
        }
    }
}
