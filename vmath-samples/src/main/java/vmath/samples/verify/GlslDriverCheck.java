package vmath.samples.verify;

import static org.lwjgl.opengl.GL46.GL_COMPILE_STATUS;
import static org.lwjgl.opengl.GL46.GL_COMPUTE_SHADER;
import static org.lwjgl.opengl.GL46.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL46.glCompileShader;
import static org.lwjgl.opengl.GL46.glCreateShader;
import static org.lwjgl.opengl.GL46.glDeleteShader;
import static org.lwjgl.opengl.GL46.glGetShaderInfoLog;
import static org.lwjgl.opengl.GL46.glGetShaderi;
import static org.lwjgl.opengl.GL46.glShaderSource;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import vmath.gl.GraphicsCapabilities;
import vmath.lines.LineRenderPlan;
import vmath.lines.LineStrategy;
import vmath.map.AreaRenderPlan;
import vmath.map.AreaStrategy;
import vmath.map.SymbolRenderPlan;
import vmath.map.SymbolStrategy;
import vmath.map.TerrainShader;
import vmath.samples.framework.Gl;
import vmath.gl.GlslFeature;
import vmath.gl.GlslVersion;

/**
 * Compiles the probe shader of every {@link GlslFeature} at every GLSL version on the OpenGL
 * driver of the machine, and compares what the driver accepts with the table. It is the driver's
 * half of the compile matrix: {@code glslang} (in {@code GlslFeatureCompileTest}) is the strict front
 * end that the table follows, a driver is what the user actually has.
 *
 * <p>A driver that <em>refuses</em> a construct from the version that the table gives is a failure
 * (the library would write text that this driver does not take). A driver that <em>accepts</em> a
 * construct below that version is reported but not a failure: drivers are often lenient (they take
 * what they can compile, with or without a warning, where the specification asks for an extension), and
 * the library writes only what the specification and the strict compiler allow. The check also tries
 * {@code gl_DrawIDARB} with {@code GL_ARB_shader_draw_parameters} at each version, because the front end
 * {@code glslang} knows it from 4.40 only while the extension text says 4.30.
 *
 * <p>Needs a display and an OpenGL 4.6 driver; {@code ./gradlew -Psamples :vmath-samples:glslCheck}.
 * Internal: part of the samples.
 *
 * <p><b>Thread safety.</b> Not thread-safe: run it on the main thread.
 */
public final class GlslDriverCheck {

    private static final List<GlslVersion> VERSIONS = List.of(GlslVersion.V330, GlslVersion.V400, GlslVersion.V410, GlslVersion.V420, GlslVersion.V430, GlslVersion.V440, GlslVersion.V450,
            GlslVersion.V460);

    private GlslDriverCheck() {
    }

    /** One line of the report. */
    public record Outcome(String name, boolean ok, String detail) {
    }

    /**
     * Compiles a shader on the current context.
     *
     * @param stage {@code vert} or {@code comp}
     * @param source the text, with its {@code #version} line
     * @return {@code null} if it compiled, else the driver's log
     */
    public static String compile(String stage, String source) {
        int shader = glCreateShader(stage.equals("comp") ? GL_COMPUTE_SHADER : GL_VERTEX_SHADER);
        try {
            glShaderSource(shader, source);
            glCompileShader(shader);
            return glGetShaderi(shader, GL_COMPILE_STATUS) != 0 ? null : glGetShaderInfoLog(shader).trim();
        } finally {
            glDeleteShader(shader);
        }
    }

    private static GraphicsCapabilities capsOf(GlslVersion v) {
        return GraphicsCapabilities.openGl(v.number() >= 400 ? 4 : 3, v.number() >= 400 ? (v.number() - 400) / 10 : 3, List.of());
    }

    private static String link(String vertex, String fragment) {
        try {
            org.lwjgl.opengl.GL46.glDeleteProgram(Gl.program(vertex, fragment));
            return null;
        } catch (RuntimeException e) {
            return String.valueOf(e.getMessage()).split("\n\n")[0];
        }
    }

    /**
     * Links the shaders of the generators as real programs at every version they claim: the line
     * strategies, the symbol and area strategies and the terrain shader, with the capabilities of each version.
     *
     * @param out where to print the progress; may be {@code null}
     * @return one outcome per generator and version
     */
    public static List<Outcome> generators(PrintStream out) {
        List<Outcome> results = new ArrayList<>();
        for (GlslVersion v : VERSIONS) {
            GraphicsCapabilities caps = capsOf(v);
            List<String[]> programs = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (LineStrategy ls : LineStrategy.values()) {
                if (LineStrategy.chooser().supports(ls, caps)) {
                    LineRenderPlan plan = LineRenderPlan.force(ls, caps);
                    names.add("lines " + ls);
                    programs.add(new String[] {plan.vertexShader(), plan.fragmentShader()});
                }
            }
            for (SymbolStrategy ss : SymbolStrategy.values()) {
                if (SymbolStrategy.chooser().supports(ss, caps)) {
                    SymbolRenderPlan plan = SymbolRenderPlan.force(ss, caps);
                    names.add("symbols " + ss);
                    programs.add(new String[] {plan.vertexShader(), plan.fragmentShader()});
                }
            }
            for (AreaStrategy as : AreaStrategy.values()) {
                if (AreaStrategy.chooser().supports(as, caps)) {
                    AreaRenderPlan plan = AreaRenderPlan.force(as, caps);
                    names.add("areas " + as);
                    programs.add(new String[] {plan.vertexShader(), plan.fragmentShader()});
                }
            }
            names.add("terrain");
            programs.add(new String[] {TerrainShader.vertexSource(caps), TerrainShader.fragmentSource(caps)});
            for (int i = 0; i < programs.size(); i++) {
                String problem = link(programs.get(i)[0], programs.get(i)[1]);
                Outcome o = new Outcome("linked: " + names.get(i) + " at " + v, problem == null, problem == null ? "compiles and links" : problem);
                results.add(o);
                if (out != null && !o.ok()) {
                    out.println("FAIL  " + o.name() + "\n      " + o.detail());
                }
            }
        }
        if (out != null) {
            long ok = results.stream().filter(Outcome::ok).count();
            out.println("info  " + ok + " of " + results.size() + " programs of the generators link on this driver at the versions they claim");
        }
        return results;
    }

    /**
     * Runs the check on the current context.
     *
     * @param out where to print the table; may be {@code null}
     * @return one outcome per feature, and one for the draw index extension
     */
    public static List<Outcome> check(PrintStream out) {
        List<Outcome> results = new ArrayList<>();
        for (GlslFeature f : GlslFeature.values()) {
            StringBuilder row = new StringBuilder();
            StringBuilder lenient = new StringBuilder();
            StringBuilder refused = new StringBuilder();
            for (GlslVersion v : VERSIONS) {
                boolean accepted = compile(f.probeStage(), v.versionLine() + "\n" + f.probeBody()) == null;
                row.append(accepted ? 'Y' : '-');
                if (accepted && !v.supports(f)) {
                    lenient.append(' ').append(v.number());
                }
                if (!accepted && v.supports(f)) {
                    refused.append(' ').append(v.number());
                }
            }
            boolean ok = refused.length() == 0;
            String detail = "driver " + row + " (table: from " + f.minimum().number() + ")" + (lenient.length() > 0 ? "; more lenient than the table at" + lenient : "")
                    + (refused.length() > 0 ? "; REFUSED where the table says it exists:" + refused : "");
            Outcome o = new Outcome(f.name(), ok, detail);
            results.add(o);
            if (out != null) {
                out.println((ok ? "ok    " : "FAIL  ") + String.format("%-28s ", f.name()) + detail);
            }
        }
        StringBuilder row = new StringBuilder();
        for (GlslVersion v : VERSIONS) {
            String text = v.versionLine() + "\n#extension GL_ARB_shader_draw_parameters : require\nvoid main() { gl_Position = vec4(float(gl_DrawIDARB)); }\n";
            row.append(compile("vert", text) == null ? 'Y' : '-');
        }
        Outcome draw = new Outcome("gl_DrawIDARB with GL_ARB_shader_draw_parameters", true, "driver " + row + " at 330 400 410 420 430 440 450 460 (glslang: from 440)");
        results.add(draw);
        if (out != null) {
            out.println("info  " + String.format("%-28s ", "gl_DrawIDARB + extension") + draw.detail());
        }
        return results;
    }
}
