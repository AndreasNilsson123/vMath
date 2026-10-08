package vmath.samples.verify;

import java.util.List;

/**
 * Runs {@link GlslDriverCheck} from the command line; exits with 1 if the driver refuses a construct
 * that the table of {@code GlslFeature} says exists, 2 if there is no driver. Internal: part of the samples.
 *
 * <p><b>Thread safety.</b> Not thread-safe: run it on the main thread.
 */
public final class GlslCheckMain {

    private GlslCheckMain() {
    }

    /**
     * Runs the check.
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
            List<GlslDriverCheck.Outcome> results = GlslDriverCheck.check(System.out);
            long failed = results.stream().filter(o -> !o.ok()).count();
            if (failed > 0) {
                System.exit(1);
            }
        } finally {
            LineGpuCheck.closeContext(window);
        }
    }
}
