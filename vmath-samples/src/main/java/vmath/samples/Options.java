package vmath.samples;

/**
 * The command line of {@link MillionInstances}.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads.
 *
 * @param instances the number of boxes in the city
 * @param width the width of the window in pixels
 * @param height the height of the window in pixels
 * @param frames the number of frames to render before printing the numbers and exiting, or 0 to run until the window is closed
 * @param warmup the number of frames at the start that the numbers leave out
 * @param vsync whether to wait for the display
 * @param threads the number of threads of the frustum culling; one culls on the render thread
 * @param framesInFlight the number of regions of the instance buffer, which is how far the CPU may run ahead of the GPU
 * @param culling whether the frustum culling is on; off draws every instance
 * @param screenshot the file to write the last frame to as a PNG, or {@code null} for none
 */
record Options(int instances, int width, int height, int frames, int warmup, boolean vsync, int threads, int framesInFlight, boolean culling,
        String screenshot) {

    static final String USAGE = """
            usage: MillionInstances [options]
              --instances N        boxes in the city (default 1000000)
              --width W --height H window size (default 1600 x 900)
              --frames N           render N frames along a scripted flight, print the timings and exit (default: interactive)
              --warmup N           frames left out of the timings (default 60)
              --vsync | --no-vsync wait for the display (default: on interactively, off with --frames)
              --threads N          threads for the frustum culling (default 1)
              --frames-in-flight N regions of the instance buffer (default 3)
              --no-cull            draw every instance (default: frustum culling)
              --screenshot FILE    write the last frame to a PNG
              --help               this text
            interactive: left mouse button + move to look, W A S D to fly, Space and Left Control up and down, Left Shift to go faster,
                         C toggles the culling, V toggles vsync, R resets the camera, Escape quits
            """;

    /**
     * Parses the arguments.
     *
     * @param args the command line; must not be {@code null}
     * @return the options, or {@code null} when the usage text was asked for
     * @throws IllegalArgumentException if an argument is unknown or a value is missing or not a number
     */
    static Options parse(String[] args) {
        int instances = 1_000_000, width = 1600, height = 900, frames = 0, warmup = 60, threads = 1, inFlight = 3;
        Boolean vsync = null;
        boolean culling = true;
        String screenshot = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--instances" -> instances = intValue(args, ++i);
                case "--width" -> width = intValue(args, ++i);
                case "--height" -> height = intValue(args, ++i);
                case "--frames" -> frames = intValue(args, ++i);
                case "--warmup" -> warmup = intValue(args, ++i);
                case "--threads" -> threads = intValue(args, ++i);
                case "--frames-in-flight" -> inFlight = intValue(args, ++i);
                case "--vsync" -> vsync = true;
                case "--no-vsync" -> vsync = false;
                case "--no-cull" -> culling = false;
                case "--screenshot" -> {
                    if (++i >= args.length) {
                        throw new IllegalArgumentException("--screenshot needs a file name");
                    }
                    screenshot = args[i];
                }
                case "--help", "-h" -> {
                    return null;
                }
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        if (instances < 1 || width < 1 || height < 1 || frames < 0 || warmup < 0 || threads < 1 || inFlight < 1) {
            throw new IllegalArgumentException("the counts and sizes must be positive (frames and warmup may be 0)");
        }
        return new Options(instances, width, height, frames, warmup, vsync != null ? vsync : frames == 0, threads, inFlight, culling, screenshot);
    }

    private static int intValue(String[] args, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException(args[i - 1] + " needs a number");
        }
        try {
            return Integer.parseInt(args[i]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(args[i - 1] + " needs a number, not " + args[i]);
        }
    }
}
