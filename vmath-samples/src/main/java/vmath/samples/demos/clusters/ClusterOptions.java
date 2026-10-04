package vmath.samples.demos.clusters;

import java.util.List;

/**
 * The options of the {@link ClusterLodDemo}, parsed from the arguments that the launcher passes on.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads.
 *
 * @param detail the subdivisions of the icosphere that the rock is made from; the rock has
 *     {@code 20 * 4^detail} triangles
 * @param budget the projected error in pixels that a cluster may have
 * @param gpu whether the clusters are chosen by the compute shader instead of on the CPU
 * @param verify whether the demo runs the culling shader at four distances and compares its commands
 *     with the library's CPU reference, failing the run if they differ
 */
record ClusterOptions(int detail, float budget, boolean gpu, boolean verify) {

    /**
     * The usage text of the demo's own options.
     */
    static final String USAGE = """
            options of the cluster-lod demo:
              --detail N           subdivisions of the icosphere the rock is made of: 20 * 4^N triangles (default 6: 81,920; 2 to 8)
              --budget PIXELS      projected error that a cluster may have (default 1)
              --gpu                choose the clusters with the compute shader instead of on the CPU
              --verify             run the culling shader at four distances and compare it with the library's CPU reference
            """;

    /**
     * Parses the arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @return the options
     * @throws IllegalArgumentException if an argument is unknown or a value is missing, not a
     *     number or out of range; the message includes the usage text
     */
    static ClusterOptions parse(List<String> args) {
        int detail = 6;
        float budget = 1f;
        boolean gpu = false, verify = false;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--detail" -> detail = (int) number(args, ++i);
                case "--budget" -> budget = (float) number(args, ++i);
                case "--gpu" -> gpu = true;
                case "--verify" -> verify = true;
                default -> throw new IllegalArgumentException("unknown argument " + args.get(i) + "\n" + USAGE);
            }
        }
        if (detail < 2 || detail > 8 || !(budget > 0f)) {
            throw new IllegalArgumentException("--detail must be from 2 to 8 and --budget positive\n" + USAGE);
        }
        return new ClusterOptions(detail, budget, gpu, verify);
    }

    private static double number(List<String> args, int i) {
        if (i >= args.size()) {
            throw new IllegalArgumentException(args.get(i - 1) + " needs a number\n" + USAGE);
        }
        try {
            return Double.parseDouble(args.get(i));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(args.get(i - 1) + " needs a number, not " + args.get(i) + "\n" + USAGE);
        }
    }
}
