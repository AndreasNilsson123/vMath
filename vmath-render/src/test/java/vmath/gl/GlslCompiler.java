package vmath.gl;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The GLSL front end that the tests of the generators compile with: {@code glslang} (the
 * {@code glslang} command of the current releases or {@code glslangValidator} of older ones) or
 * {@code glslc}, found on the {@code PATH} or named by {@code -Dvmath.glslang=...}. Without one the
 * tests are <em>skipped</em>, which proves nothing, unless {@code -Dvmath.requireEnvironment=true} is
 * set (as the Linux CI job does), in which case a missing compiler is a failure.
 *
 * <p>The compiler is only asked to check the text of one stage ({@code -S stage}), for the version that
 * the text itself names with its {@code #version} line; nothing is linked and no code is generated.
 *
 * <p><b>Thread safety.</b> Stateless: safe to call from any number of threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * GlslCompiler.compile("name", "vert", "#version 330 core\nvoid main() { gl_Position = vec4(0.0); }\n");
 * boolean ok = GlslCompiler.accepts("comp", "#version 330 core\nlayout(local_size_x = 1) in;\nvoid main() { }\n");   // false: no compute at 3.30
 * }</pre>
 */
public final class GlslCompiler {

    private static final String PROPERTY = "vmath.glslang";
    private static List<String> cached;
    private static boolean searched;

    private GlslCompiler() {
    }

    private static synchronized List<String> tool() {
        if (!searched) {
            searched = true;
            String configured = System.getProperty(PROPERTY);
            for (String candidate : configured != null ? List.of(configured) : List.of("glslang", "glslangValidator", "glslc")) {
                try {
                    Process p = new ProcessBuilder(candidate, "--version").redirectErrorStream(true).start();
                    p.getInputStream().readAllBytes();
                    if (p.waitFor(30, TimeUnit.SECONDS)) {
                        cached = List.of(candidate);
                        break;
                    }
                } catch (IOException | InterruptedException notThere) {
                    // try the next one
                }
            }
        }
        return cached;
    }

    /**
     * Tells whether a compiler is installed.
     *
     * @return {@code true} if one was found
     */
    public static boolean available() {
        return tool() != null;
    }

    /**
     * Skips the test (or, when the environment is required, fails it) if there is no compiler.
     */
    public static void require() {
        if (tool() == null && Boolean.getBoolean("vmath.requireEnvironment")) {
            fail("vmath.requireEnvironment is set and there is no glslang, glslangValidator or glslc on the PATH (or -D" + PROPERTY + ")");
        }
        assumeTrue(tool() != null, "no glslang, glslangValidator or glslc on the PATH (or -D" + PROPERTY + ")");
    }

    /**
     * Compiles a shader and says what the compiler said.
     *
     * @param stage {@code vert}, {@code frag}, {@code comp}, ...
     * @param source the text, with its own {@code #version} line
     * @return {@code null} if it was accepted, else the compiler's message
     */
    public static String check(String stage, String source) {
        require();
        List<String> tool = tool();
        try {
            Path file = Files.createTempFile("vmath-glsl", "." + stage);
            try {
                Files.writeString(file, source, StandardCharsets.UTF_8);
                List<String> cmd = new ArrayList<>(tool);
                if (tool.get(0).contains("glslc")) {
                    cmd.addAll(List.of("-fshader-stage=" + stage, "-o", file + ".spv", file.toString()));
                } else {
                    cmd.addAll(List.of("-S", stage, file.toString()));
                }
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!p.waitFor(60, TimeUnit.SECONDS)) {
                    return "the compiler did not finish";
                }
                return p.exitValue() == 0 ? null : output;
            } finally {
                Files.deleteIfExists(file);
                Files.deleteIfExists(Path.of(file + ".spv"));
            }
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("cannot run the compiler", e);
        }
    }

    /**
     * Compiles a Vulkan GLSL shader to SPIR-V and says what the compiler said.
     *
     * @param stage {@code vert}, {@code frag}, {@code comp}, ...
     * @param source the text, with its {@code #version 450} line
     * @return {@code null} if it was accepted, else the compiler's message
     */
    public static String checkSpirv(String stage, String source) {
        require();
        List<String> tool = tool();
        try {
            Path file = Files.createTempFile("vmath-vk", "." + stage);
            Path spv = Path.of(file + ".spv");
            try {
                Files.writeString(file, source, StandardCharsets.UTF_8);
                List<String> cmd = new ArrayList<>(tool);
                if (tool.get(0).contains("glslc")) {
                    cmd.addAll(List.of("-fshader-stage=" + stage, "--target-env=vulkan1.0", "-o", spv.toString(), file.toString()));
                } else {
                    cmd.addAll(List.of("-V", "-S", stage, "-o", spv.toString(), file.toString()));
                }
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!p.waitFor(60, TimeUnit.SECONDS)) {
                    return "the compiler did not finish";
                }
                return p.exitValue() == 0 && Files.size(spv) > 0 ? null : output;
            } finally {
                Files.deleteIfExists(file);
                Files.deleteIfExists(spv);
            }
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("cannot run the compiler", e);
        }
    }

    /**
     * Tells whether the compiler accepts a text.
     *
     * @param stage the stage
     * @param source the text
     * @return {@code true} if accepted
     */
    public static boolean accepts(String stage, String source) {
        return check(stage, source) == null;
    }

    /**
     * Compiles a text and fails the test with the compiler's message if it is not accepted.
     *
     * @param name what the text is, for the message
     * @param stage the stage
     * @param source the text
     */
    public static void compile(String name, String stage, String source) {
        String message = check(stage, source);
        if (message != null) {
            fail(name + " does not compile:\n" + message);
        }
    }
}
