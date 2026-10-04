package vmath.samples.framework;

import java.util.ArrayList;
import java.util.List;

/**
 * The command line of the launcher: which demos to run, how, and what to do with the results.
 * Everything the launcher does not recognise is passed on to the demo, which parses its own
 * options.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads.
 *
 * @param demos the ids of the demos to run, in order; empty to open the menu
 * @param list whether to print the registered demos and exit
 * @param smoke whether to run every registered demo briefly and check it
 * @param help whether to print the usage and exit
 * @param width the width of the window in pixels
 * @param height the height of the window in pixels
 * @param frames the number of frames to render per demo before printing the numbers, or 0 to run
 *     until the window is closed
 * @param warmup the number of frames at the start that the numbers leave out
 * @param vsync whether to wait for the display
 * @param hud whether to draw the heads-up display
 * @param checkGl whether to check for OpenGL errors after every frame
 * @param screenshot the file to write the last frame to, or {@code null}; with several demos the
 *     id is added before the extension
 * @param report the markdown file to append the numbers to, or {@code null}
 * @param demoArgs the arguments that the launcher did not recognise, in order, for the demo
 */
public record RunOptions(List<String> demos, boolean list, boolean smoke, boolean help, int width, int height, int frames, int warmup, boolean vsync,
        boolean hud, boolean checkGl, String screenshot, String report, List<String> demoArgs) {

    /**
     * The usage text of the launcher.
     */
    public static final String USAGE = """
            usage: Launcher [options] [demo options]
              --list               print the demos and exit
              --demo ID            run one demo (several: --demo a --demo b, or --sequence a,b)
              --sequence A,B,C     run the demos one after the other
              --smoke              run every demo for a few frames and check it (OpenGL errors, allocation, a blank frame)
              --frames N           scripted run: N frames along the demo's fixed path, print the numbers and go on (default: interactive)
              --warmup N           frames left out of the numbers (default 60)
              --width W --height H window size (default 1600 x 900)
              --vsync | --no-vsync wait for the display (default: on interactively, off with --frames)
              --no-hud             leave the text out of the window and of the screenshots
              --check-gl           check glGetError after every frame
              --screenshot FILE    write the last frame of a scripted run to a PNG (the demo id is added with several demos)
              --report FILE        append the numbers of a scripted run to a markdown file
              --help               this text
            Options that the launcher does not know are passed to the demo (see the card of the demo in docs/DEMOS.md).
            Without --demo and --sequence the window opens with a menu.
            keys of every demo: Tab menu, PageUp and PageDown switch demo, V toggles vsync, P saves a screenshot, F1 hides the text, Escape quits
            """;

    /**
     * Parses the command line.
     *
     * @param args the arguments; must not be {@code null}
     * @return the options
     * @throws IllegalArgumentException if a value is missing or is not a number, or a count or size
     *     is out of range
     */
    public static RunOptions parse(String[] args) {
        List<String> demos = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        boolean list = false, smoke = false, help = false, hud = true, checkGl = false;
        int width = 1600, height = 900, frames = 0;
        Integer warmup = null;
        Boolean vsync = null;
        String screenshot = null, report = null;
        boolean passing = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--list" -> list = true;
                case "--smoke" -> smoke = true;
                case "--help", "-h" -> help = true;
                case "--demo" -> demos.add(value(args, ++i));
                case "--sequence" -> {
                    for (String id : value(args, ++i).split(",")) {
                        if (!id.isBlank()) {
                            demos.add(id.trim());
                        }
                    }
                }
                case "--width" -> width = number(args, ++i);
                case "--height" -> height = number(args, ++i);
                case "--frames" -> frames = number(args, ++i);
                case "--warmup" -> warmup = number(args, ++i);
                case "--vsync" -> vsync = true;
                case "--no-vsync" -> vsync = false;
                case "--no-hud" -> hud = false;
                case "--check-gl" -> checkGl = true;
                case "--screenshot" -> screenshot = value(args, ++i);
                case "--report" -> report = value(args, ++i);
                default -> {
                    // an argument of the demo and, behind it, the values that do not look like options
                    rest.add(a);
                    while (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        rest.add(args[++i]);
                    }
                }
            }
        }
        if (width < 1 || height < 1 || frames < 0 || (warmup != null && warmup < 0)) {
            throw new IllegalArgumentException("the sizes must be positive (frames and warmup may be 0)");
        }
        if (frames > 0 && demos.isEmpty() && !smoke) {
            throw new IllegalArgumentException("--frames needs a demo: add --demo ID (see --list)");
        }
        return new RunOptions(List.copyOf(demos), list, smoke, help, width, height, frames, warmup != null ? warmup : 60,
                vsync != null ? vsync : frames == 0 && !smoke, hud, checkGl || smoke, screenshot, report, List.copyOf(rest));
    }

    private static String value(String[] args, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException(args[i - 1] + " needs a value");
        }
        return args[i];
    }

    private static int number(String[] args, int i) {
        String v = value(args, i);
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(args[i - 1] + " needs a number, not " + v);
        }
    }
}
