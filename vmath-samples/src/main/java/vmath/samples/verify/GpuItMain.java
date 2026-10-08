package vmath.samples.verify;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs every check of the shaders and buffers against the OpenGL driver of the machine: the table of GLSL
 * features ({@link GlslDriverCheck}), the culling shaders against their CPU references
 * ({@link CullShaderGpuCheck}), the layouts of the structs against the reflection of the driver
 * ({@link LayoutGpuCheck}) and the persistent ring with real fences ({@link RingGpuCheck}); exits with 1
 * if one fails and 2 if there is no driver. Internal: part of the samples.
 *
 * <p><b>Thread safety.</b> Not thread-safe: run it on the main thread.
 */
public final class GpuItMain {

    private GpuItMain() {
    }

    /** One line of a report, whichever check made it. */
    public record Line(String name, boolean ok, String detail) {
    }

    /**
     * Runs all the checks on the current context.
     *
     * @param out where to print the progress; may be {@code null}
     * @return every outcome
     */
    public static List<Line> runAll(java.io.PrintStream out) {
        List<Line> all = new ArrayList<>();
        for (GlslDriverCheck.Outcome o : GlslDriverCheck.check(out)) {
            all.add(new Line("glsl: " + o.name(), o.ok(), o.detail()));
        }
        for (GlslDriverCheck.Outcome o : GlslDriverCheck.generators(out)) {
            all.add(new Line("link: " + o.name(), o.ok(), o.detail()));
        }
        for (CullShaderGpuCheck.Outcome o : CullShaderGpuCheck.check(out)) {
            all.add(new Line("cull: " + o.name(), o.ok(), o.detail()));
        }
        for (LayoutGpuCheck.Outcome o : LayoutGpuCheck.check(out)) {
            all.add(new Line("layout: " + o.name(), o.ok(), o.detail()));
        }
        for (RingGpuCheck.Outcome o : RingGpuCheck.check(out)) {
            all.add(new Line("ring: " + o.name(), o.ok(), o.detail()));
        }
        return all;
    }

    /**
     * Runs the checks from the command line.
     *
     * @param args unused
     */
    public static void main(String[] args) {
        long window = LineGpuCheck.openContext();
        if (window == 0) {
            System.err.println("no OpenGL context could be opened");
            System.exit(2);
        }
        try {
            System.out.println("driver: " + LineGpuCheck.driver());
            List<Line> all = runAll(System.out);
            long failed = all.stream().filter(l -> !l.ok()).count();
            System.out.println(all.size() - failed + " of " + all.size() + " checks agree");
            if (failed > 0) {
                System.exit(1);
            }
        } finally {
            LineGpuCheck.closeContext(window);
        }
    }
}
