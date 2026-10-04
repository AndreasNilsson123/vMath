package vmath.samples.demos.gpucull;

import java.util.List;

/**
 * The options of the {@link GpuCullDemo}, parsed from the arguments that the launcher passes on.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads.
 *
 * @param blocks the number of buildings along one side of the city
 * @param props the number of street props per building
 * @param occlusion whether the depth pyramid is used from the start
 * @param verify whether every 20 frames the depth pyramid and the culling are checked against the
 *     library's CPU models and the run fails on a difference
 */
record GpuCullOptions(int blocks, int props, boolean occlusion, boolean verify) {

    /**
     * The usage text of the demo's own options.
     */
    static final String USAGE = """
            options of the gpu-culling demo:
              --blocks N           buildings along one side of the city (default 100: 10,000 buildings)
              --props N            street props per building (default 40)
              --no-occlusion       start with the depth pyramid off: frustum culling only
              --verify             every 20 frames, read back the depth, the pyramid and the survivors and compare them with the library's CPU models
            """;

    /**
     * Parses the arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @return the options
     * @throws IllegalArgumentException if an argument is unknown or a value is missing, not a
     *     number or out of range; the message includes the usage text
     */
    static GpuCullOptions parse(List<String> args) {
        int blocks = 100, props = 40;
        boolean occlusion = true, verify = false;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--blocks" -> blocks = intValue(args, ++i);
                case "--props" -> props = intValue(args, ++i);
                case "--no-occlusion" -> occlusion = false;
                case "--verify" -> verify = true;
                default -> throw new IllegalArgumentException("unknown argument " + args.get(i) + "\n" + USAGE);
            }
        }
        if (blocks < 4 || blocks > 300 || props < 0) {
            throw new IllegalArgumentException("--blocks must be from 4 to 300 and --props at least 0\n" + USAGE);
        }
        return new GpuCullOptions(blocks, props, occlusion, verify);
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
