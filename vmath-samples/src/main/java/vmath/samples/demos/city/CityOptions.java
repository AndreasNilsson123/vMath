package vmath.samples.demos.city;

import java.util.List;

/**
 * The options of the {@link CityDemo}, parsed from the arguments that the launcher passes on.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads.
 *
 * @param instances the number of boxes in the city
 * @param threads the number of threads of the frustum culling; one culls on the render thread
 * @param framesInFlight the number of regions of the instance buffer, which is how far the CPU may
 *     run ahead of the GPU
 * @param culling whether the frustum culling starts on; off draws every instance
 */
record CityOptions(int instances, int threads, int framesInFlight, boolean culling) {

    /**
     * The usage text of the demo's own options.
     */
    static final String USAGE = """
            options of the city demo:
              --instances N        boxes in the city (default 1000000)
              --threads N          threads for the frustum culling (default 1)
              --frames-in-flight N regions of the instance buffer (default 3)
              --no-cull            start with the culling off: every instance is drawn
            """;

    /**
     * Parses the arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @return the options
     * @throws IllegalArgumentException if an argument is unknown or a value is missing, not a
     *     number or not positive; the message includes the usage text
     */
    static CityOptions parse(List<String> args) {
        int instances = 1_000_000, threads = 1, inFlight = 3;
        boolean culling = true;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--instances" -> instances = intValue(args, ++i);
                case "--threads" -> threads = intValue(args, ++i);
                case "--frames-in-flight" -> inFlight = intValue(args, ++i);
                case "--no-cull" -> culling = false;
                default -> throw new IllegalArgumentException("unknown argument " + args.get(i) + "\n" + USAGE);
            }
        }
        if (instances < 1 || threads < 1 || inFlight < 1) {
            throw new IllegalArgumentException("the counts must be positive\n" + USAGE);
        }
        return new CityOptions(instances, threads, inFlight, culling);
    }

    private static int intValue(List<String> args, int i) {
        if (i >= args.size()) {
            throw new IllegalArgumentException(args.get(i - 1) + " needs a number\n" + USAGE);
        }
        try {
            return Integer.parseInt(args.get(i));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(args.get(i - 1) + " needs a number, not " + args.get(i) + "\n" + USAGE);
        }
    }
}
