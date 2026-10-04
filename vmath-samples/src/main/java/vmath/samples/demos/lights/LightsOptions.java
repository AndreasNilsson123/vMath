package vmath.samples.demos.lights;

import java.util.List;

/**
 * The options of the {@link LightsDemo}, parsed from the arguments that the launcher passes on.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads.
 *
 * @param lights the number of point lights
 * @param blocks the number of buildings along one side of the city
 * @param mode the starting way to light: 0 clusters, 1 every light, 2 heat map
 * @param verify whether the demo renders a few views with the clusters and with a loop over every
 *     light at the start and fails if the images differ
 */
record LightsOptions(int lights, int blocks, int mode, boolean verify) {

    /**
     * The usage text of the demo's own options.
     */
    static final String USAGE = """
            options of the clustered-lights demo:
              --lights N           point lights (default 4096; try 10000, the assignment then takes about 100 ms on the CPU)
              --blocks N           buildings along one side of the city (default 16)
              --brute              start by looping over every light in the shader instead of using the clusters
              --heat               start with the heat map of the lights per cluster
              --verify             at the start, render three views both ways at 640 x 360 and fail if they differ
            """;

    /**
     * Parses the arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @return the options
     * @throws IllegalArgumentException if an argument is unknown or a value is missing, not a
     *     number or out of range; the message includes the usage text
     */
    static LightsOptions parse(List<String> args) {
        int lights = 4096, blocks = 16, mode = 0;
        boolean verify = false;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--lights" -> lights = intValue(args, ++i);
                case "--blocks" -> blocks = intValue(args, ++i);
                case "--brute" -> mode = 1;
                case "--heat" -> mode = 2;
                case "--verify" -> verify = true;
                default -> throw new IllegalArgumentException("unknown argument " + args.get(i) + "\n" + USAGE);
            }
        }
        if (lights < 1 || lights > 100_000 || blocks < 2 || blocks > 100) {
            throw new IllegalArgumentException("--lights must be from 1 to 100000 and --blocks from 2 to 100\n" + USAGE);
        }
        return new LightsOptions(lights, blocks, mode, verify);
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
