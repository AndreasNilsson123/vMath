package vmath.samples.demos.occlusion;

import java.util.List;

/**
 * The options of the {@link OcclusionDemo}, parsed from the arguments that the launcher passes on.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads.
 *
 * @param blocks the number of buildings along one side of the city
 * @param props the number of street props per building
 * @param depthWidth the width of the depth buffer in pixels; its height is half of it
 * @param verify whether a sample of the boxes that the occlusion culling removes is checked every
 *     frame with rays against the occluders, which fails the run if one of them is visible
 * @param occlusion whether the occlusion culling starts on
 * @param threads the number of threads that test the boxes against the depth buffer, which may
 *     be read by any number of threads once it is finished
 */
record OcclusionOptions(int blocks, int props, int depthWidth, boolean verify, boolean occlusion, int threads) {

    /**
     * The usage text of the demo's own options.
     */
    static final String USAGE = """
            options of the occlusion demo:
              --blocks N           buildings along one side of the city (default 100: 10,000 buildings)
              --props N            street props per building (default 40)
              --depth W            width of the depth buffer, its height is half (default 256)
              --verify             check a sample of the removed boxes with rays against the occluders; fail if one is visible
              --no-occlusion       start with the occlusion culling off
              --threads N          threads that test the boxes against the finished depth buffer (default 1)
            """;

    /**
     * Parses the arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @return the options
     * @throws IllegalArgumentException if an argument is unknown or a value is missing, not a
     *     number or out of range; the message includes the usage text
     */
    static OcclusionOptions parse(List<String> args) {
        int blocks = 100, props = 40, depth = 256, threads = 1;
        boolean verify = false, occlusion = true;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--blocks" -> blocks = intValue(args, ++i);
                case "--props" -> props = intValue(args, ++i);
                case "--depth" -> depth = intValue(args, ++i);
                case "--verify" -> verify = true;
                case "--no-occlusion" -> occlusion = false;
                case "--threads" -> threads = intValue(args, ++i);
                default -> throw new IllegalArgumentException("unknown argument " + args.get(i) + "\n" + USAGE);
            }
        }
        if (blocks < 4 || props < 0 || depth < 16 || depth > 4096 || threads < 1) {
            throw new IllegalArgumentException("--blocks must be at least 4, --props at least 0, --depth from 16 to 4096 and --threads at least 1\n" + USAGE);
        }
        return new OcclusionOptions(blocks, props, depth, verify, occlusion, threads);
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
