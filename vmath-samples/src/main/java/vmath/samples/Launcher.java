package vmath.samples;

import java.io.IOException;
import java.util.List;
import vmath.samples.framework.DemoEntry;
import vmath.samples.framework.DemoRunner;
import vmath.samples.framework.RunOptions;

/**
 * The entry point of the demos: lists them, runs one or several of them, checks them all in a
 * smoke run, or opens a window with a menu.
 *
 * <p>Run it with {@code ./gradlew -Psamples :vmath-samples:run} (the menu), with
 * {@code --args="--demo city"} for one demo, or with {@code --args="--demo city --frames 600"} for
 * a scripted run that prints the numbers. {@code --help} lists the options. The demos need a driver
 * with OpenGL 4.5 (not macOS, which stops at 4.1).
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Everything happens on the thread that runs {@link #main}, which owns
 * the OpenGL context.
 */
public final class Launcher {

    private Launcher() {
    }

    /**
     * Runs the launcher and exits with the code of the run.
     *
     * @param args the command line; see {@code --help}
     * @throws IOException if a screenshot or the report cannot be written
     */
    public static void main(String[] args) throws IOException {
        System.setProperty("java.awt.headless", "true");
        List<DemoEntry> entries = Demos.all();
        RunOptions options;
        try {
            options = RunOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.print(RunOptions.USAGE);
            System.exit(2);
            return;
        }
        if (options.help()) {
            System.out.print(RunOptions.USAGE);
            return;
        }
        if (options.list()) {
            for (DemoEntry e : entries) {
                System.out.printf("%-20s %s%n", e.info().id(), e.info().title());
                System.out.printf("%-20s %s%n", "", e.info().claim());
            }
            return;
        }
        int code;
        try {
            code = DemoRunner.run(options, entries);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.print(RunOptions.USAGE);
            System.exit(2);
            return;
        }
        System.exit(code);
    }
}
